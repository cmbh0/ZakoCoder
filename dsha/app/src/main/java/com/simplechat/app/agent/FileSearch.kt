package com.simplechat.app.agent

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地内容搜索与文件查找。
 *
 * 云端那套 `/fs/grep`、`/fs/glob` 的本地对应物 —— 参数、语义、输出形态
 * 都刻意保持一致。模型不该因为"文件在手机上还是公网机器上"而换一套说法：
 * 同一句"搜一下 buildJsonObject 在哪儿"，两条路径都该给出带行号的结果。
 *
 * 为什么不用 `run_shell` 里的 `grep`：Android 自带的是 toybox，参数与 GNU
 * 并不一致（`--include`、`--exclude-dir` 都不认）。一个写错的 grep 返回 0 行，
 * 模型会当成"确实没有"，然后开始凭印象改代码。
 */
object FileSearch {

    /** 默认跳过的目录。这些名字里的命中对模型没有价值，却能把结果挤满。 */
    private val IgnoreDirs = setOf(
        ".git", ".hg", ".svn", "node_modules", "__pycache__", ".venv", "venv",
        "dist", "build", "target", ".idea", ".gradle", ".mypy_cache", ".pytest_cache",
    )

    private const val MaxFileBytes = 2L * 1024 * 1024
    private const val MaxScanFiles = 20_000

    data class Hit(
        val path: String,
        val line: Int,
        val text: String,
        val before: List<String> = emptyList(),
        val after: List<String> = emptyList(),
    )

    data class Result(
        val hits: List<Hit>,
        val files: List<Pair<String, Int>>,
        val scanned: Int,
        val truncated: Boolean,
        val ms: Long,
    )

    /**
     * glob 转正则，支持 `**`。
     *
     * 不能直接用 Kotlin 的 `Regex` 或者 `File.pathMatcher`：前者要自己写通配，
     * 后者把 `*` 和 `**` 当同一件事 —— 双星号加斜杠的写法匹配不到根目录下的文件
     * （只有一层时，那个斜杠该匹配空）。而 AI 写路径几乎总是带双星号。
     */
    fun globToRegex(pattern: String): Regex {
        val out = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            when {
                ch == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        out.append("(?:.*/)?")
                        i += 3
                    } else {
                        out.append(".*")
                        i += 2
                    }
                }
                ch == '*' -> {
                    out.append("[^/]*")
                    i++
                }
                ch == '?' -> {
                    out.append("[^/]")
                    i++
                }
                ch == '[' -> {
                    val close = pattern.indexOf(']', i + 1)
                    if (close < 0) {
                        out.append("\\[")
                        i++
                    } else {
                        var body = pattern.substring(i + 1, close)
                        if (body.startsWith("!")) body = "^" + body.drop(1)
                        out.append('[').append(body).append(']')
                        i = close + 1
                    }
                }
                ch in "\\^$.|+(){}" -> {
                    out.append('\\').append(ch)
                    i++
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        out.append('$')
        return Regex(out.toString())
    }

    suspend fun grep(
        root: File,
        pattern: String,
        regex: Boolean = true,
        caseSensitive: Boolean = false,
        glob: String? = null,
        max: Int = 200,
        context: Int = 0,
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val matcher = if (regex) {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            try {
                Regex(pattern, options)
            } catch (error: Exception) {
                throw IllegalArgumentException("正则不合法：${error.message}")
            }
        } else {
            null
        }
        val needle = if (caseSensitive) pattern else pattern.lowercase()
        val nameRegex = glob?.takeIf { it.isNotBlank() }?.let(::globToRegex)
        val byPath = glob?.contains('/') == true
        val base = root.canonicalFile

        val hits = mutableListOf<Hit>()
        var scanned = 0
        var truncated = false

        fun walk(dir: File) {
            if (truncated || scanned >= MaxScanFiles) return
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (child in children) {
                if (truncated) return
                if (child.isDirectory) {
                    if (child.name !in IgnoreDirs) walk(child)
                    continue
                }
                val relative = relativeName(base, child)
                if (nameRegex != null) {
                    val subject = if (byPath) relative else child.name
                    if (!nameRegex.matches(subject)) continue
                }
                if (child.length() > MaxFileBytes) continue
                val lines = readLines(child) ?: continue
                scanned++
                lines.forEachIndexed { index, line ->
                    if (truncated) return@forEachIndexed
                    val hit = if (matcher != null) {
                        matcher.containsMatchIn(line)
                    } else if (caseSensitive) {
                        line.contains(needle)
                    } else {
                        line.lowercase().contains(needle)
                    }
                    if (!hit) return@forEachIndexed
                    val number = index + 1
                    hits += Hit(
                        path = relative,
                        line = number,
                        text = line.take(400),
                        before = if (context > 0) {
                            lines.subList(maxOf(0, index - context), index).map { it.take(400) }
                        } else {
                            emptyList()
                        },
                        after = if (context > 0) {
                            lines.subList(index + 1, minOf(lines.size, index + 1 + context)).map { it.take(400) }
                        } else {
                            emptyList()
                        },
                    )
                    if (hits.size >= max) truncated = true
                }
            }
        }

        walk(base)
        val grouped = LinkedHashMap<String, Int>()
        hits.forEach { grouped[it.path] = (grouped[it.path] ?: 0) + 1 }
        Result(
            hits = hits,
            files = grouped.toList(),
            scanned = scanned,
            truncated = truncated,
            ms = System.currentTimeMillis() - started,
        )
    }

    suspend fun find(root: File, pattern: String, max: Int = 300): List<File> =
        withContext(Dispatchers.IO) {
            val matcher = globToRegex(pattern)
            val byPath = pattern.contains('/')
            val base = root.canonicalFile
            val found = mutableListOf<File>()

            fun walk(dir: File) {
                if (found.size >= max) return
                val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: return
                for (child in children) {
                    if (found.size >= max) return
                    if (child.isDirectory) {
                        if (child.name !in IgnoreDirs) walk(child)
                        continue
                    }
                    val relative = relativeName(base, child)
                    val subject = if (byPath) relative else child.name
                    if (matcher.matches(subject)) found += child
                }
            }

            walk(base)
            found
        }

    /**
     * 读成行。二进制文件直接放弃 —— 它进不了上下文，读了也是浪费。
     * 前 4KB 里出现 NUL 基本可以断定不是文本。
     */
    private fun readLines(file: File): List<String>? = runCatching {
        val bytes = file.readBytes()
        if (bytes.take(4096).any { it == 0.toByte() }) return null
        bytes.toString(Charsets.UTF_8).split("\n")
    }.getOrNull()

    fun relativeName(base: File, file: File): String {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.path)
        return path.removePrefix(base.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }
}
