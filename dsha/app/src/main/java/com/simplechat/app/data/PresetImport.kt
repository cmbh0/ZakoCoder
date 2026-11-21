package com.simplechat.app.data

import android.content.Context
import android.net.Uri
import com.simplechat.app.R
import com.simplechat.app.data.import.docxXmlToText
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从**文档文件**导入预设。
 *
 * ### 为什么不设格式
 *
 * 预设的本质就是"一段可命名的系统提示词"，所以**任何能读成文字的文件**
 * 都应该能变成预设 —— Markdown、纯文本、JSON、YAML、一段代码、一份
 * 会议纪要。用户手里有什么，就导什么；要求他先转成某个格式，
 * 才会有一半人直接放弃。
 *
 * 但"什么格式都能进"不等于"什么格式都一样地进"。这里做了三件事，
 * 全是**锦上添花**，缺了哪一步都不影响导入成功：
 *
 * 1. **认得 JSON / YAML front matter**：如果文件里本来就有
 *    `name` / `description` 这类字段，就取出来当标题与正文 ——
 *    用户写角色卡时本来就在填这两栏，没必要让他再抄一遍。
 * 2. **认得 Markdown 的首个标题**：`# 名字` 当标题，正文去掉这一行。
 * 3. **其余一律按纯文本**：整段就是正文，一个字都不改。
 *
 * 最后一条是**兜底**，也是最主要的一条。
 */
object PresetImport {

    /** 预设正文的上限。超了就是"拿错文件了"，不是"内容太长"。 */
    const val MAX_CONTENT_CHARS = 200_000

    /**
     * 解析出来的一份草稿。
     *
     * [note] 如实说明"我做了什么"，比如"从 JSON 里取出了 name 与 description"。
     * 用户看到自己导入的东西被改了名字会疑惑 —— 说清楚比不说好。
     */
    data class Draft(
        val name: String,
        val content: String,
        val note: String? = null,
    )

    /**
     * 从一段文本解析出预设草稿。
     *
     * 纯函数，不碰 Android —— 可以直接跑单测。
     */
    fun fromText(fileName: String, raw: String): Draft {
        val text = normalize(raw)
        if (text.isBlank()) {
            return Draft(name = cleanName(fileName), content = "")
        }

        // ① JSON
        jsonDraft(text)?.let { return it }

        // ② YAML front matter
        frontMatterDraft(text)?.let { return it }

        // ③ Markdown 标题
        markdownDraft(text, fileName)?.let { return it }

        // ④ 兜底：整段就是正文
        return Draft(
            name = cleanName(fileName),
            content = text.take(MAX_CONTENT_CHARS),
            note = if (text.length > MAX_CONTENT_CHARS) "文件很长，只导入了前 $MAX_CONTENT_CHARS 个字符。" else null,
        )
    }

    /**
     * 读一个文件并解析成草稿。
     *
     * 支持三类：
     * - **纯文本**（含 md / txt / json / yaml / 代码）：严格 UTF-8 解码；
     * - **docx**：从 ZIP 里抽 `word/document.xml`；
     * - 其余（比如 PDF）：明确报错，不假装成功。
     *
     * PDF 为什么不支持：Android 自带的 PdfRenderer **只能渲染、不能抽文本**，
     * 而导入预设要的是文字。硬做就得引 PDFBox（+10MB），与"轻量优先"冲突。
     */
    suspend fun read(context: Context, uri: Uri): Result<Draft> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(context, uri) ?: "preset.txt"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw AppTextException(R.string.err_file_read)
                if (bytes.size > 8L * 1024 * 1024) {
                    throw AppTextException(R.string.err_file_too_big, 8)
                }
                val head = bytes.copyOf(minOf(16, bytes.size))
                when {
                    head.startsWithAscii("%PDF") ->
                        throw AppTextException(R.string.err_preset_pdf)

                    head.startsWithBytes(0x50, 0x4B) -> {
                        val xml = zipEntry(bytes, "word/document.xml")
                            ?: throw AppTextException(R.string.err_preset_not_text)
                        fromText(name, docxXmlToText(xml))
                    }

                    else -> {
                        val text = runCatching {
                            Charsets.UTF_8.newDecoder()
                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                .toString()
                        }.getOrElse { throw AppTextException(R.string.err_not_plain_text) }
                        fromText(name, text)
                    }
                }
            }
        }

    // ── 各格式的解析 ─────────────────────────────────────────

    /** 标题字段的候选键名（小写匹配）。 */
    private val NAME_KEYS = listOf("name", "title", "名称", "标题", "角色名")

    /** 正文字段的候选键名（小写匹配）。 */
    private val CONTENT_KEYS = listOf(
        "description",
        "content",
        "personality",
        "scenario",
        "system_prompt",
        "systemprompt",
        "prompt",
        "persona",
        "预设",
        "描述",
        "人设",
        "正文",
    )

    private fun jsonDraft(text: String): Draft? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        /*
         * 刻意**不用** JSON 解析库去反序列化 —— 这个工程的数据层用的是
         * kotlinx.serialization，而这里要读的是**别人写的、结构未知的** JSON：
         * 字段类型可能是数字、数组、嵌套对象。走 `JsonObject` 那条路要引
         * 一堆容错分支，而这里的诉求很简单：找出几个字符串字段。
         *
         * 所以只做最保守的一件事：**扫描顶层的 `"key": "value"` 对**。
         * 嵌套对象里的同名字段不会误伤 —— 顶层扫描只看第一个大括号这一层。
         */
        val fields = topLevelStringFields(trimmed)
        if (fields.isEmpty()) return null

        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
            fields.entries.firstOrNull { it.key == key }?.value
        }
        val content = CONTENT_KEYS.firstNotNullOfOrNull { key ->
            fields.entries.firstOrNull { it.key == key }?.value
        }

        // 两个都没找到 → 这份 JSON 对预设没有额外信息，整段当正文
        if (name == null && content == null) return null

        val finalName = name?.takeIf { it.isNotBlank() } ?: cleanNameOf(text)
        val finalContent = content?.takeIf { it.isNotBlank() } ?: trimmed
        return Draft(
            name = finalName,
            content = finalContent.take(MAX_CONTENT_CHARS),
            note = "从 JSON 里取出了预设名与正文。",
        )
    }

    /**
     * 扫出 JSON 顶层里的 `"key": "value"`（value 必须是字符串）。
     *
     * 只认字符串值：数字、布尔、数组、对象一律跳过 —— 它们不适合当提示词正文。
     */
    private fun topLevelStringFields(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var depth = 0
        var i = 0
        val n = json.length
        while (i < n) {
            val ch = json[i]
            when {
                ch == '"' -> {
                    val (key, next) = readJsonString(json, i) ?: return result
                    i = next
                    // 跳过空白找冒号
                    var j = i
                    while (j < n && json[j].isWhitespace()) j++
                    if (depth == 1 && j < n && json[j] == ':') {
                        var k = j + 1
                        while (k < n && json[k].isWhitespace()) k++
                        if (k < n && json[k] == '"') {
                            val (value, after) = readJsonString(json, k) ?: return result
                            result[key.lowercase()] = value
                            i = after
                        } else {
                            i = j + 1
                        }
                    } else {
                        i = j
                    }
                }

                ch == '{' || ch == '[' -> {
                    depth++
                    i++
                }

                ch == '}' || ch == ']' -> {
                    depth--
                    i++
                }

                else -> i++
            }
        }
        return result
    }

    /** 读一个 JSON 字符串字面量，返回内容与结束位置（引号之后）。 */
    private fun readJsonString(json: String, start: Int): Pair<String, Int>? {
        if (start >= json.length || json[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            val ch = json[i]
            when {
                ch == '\\' -> {
                    if (i + 1 >= json.length) return null
                    val esc = json[i + 1]
                    sb.append(
                        when (esc) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            'b' -> '\b'
                            'f' -> ''
                            'u' -> {
                                if (i + 5 < json.length) {
                                    val hex = json.substring(i + 2, i + 6)
                                    i += 4
                                    hex.toIntOrNull(16)?.toChar() ?: '?'
                                } else {
                                    '?'
                                }
                            }

                            else -> esc
                        },
                    )
                    i += 2
                }

                ch == '"' -> return sb.toString() to (i + 1)
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return null
    }

    /**
     * YAML front matter（`---` 包裹的那一段）。
     *
     * 只认最常见的写法：`key: value`，value 可以带引号。
     * 不引入 YAML 解析器 —— 那是个几 MB 的依赖，而这里只需要认几个键。
     */
    private fun frontMatterDraft(text: String): Draft? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return null
        val lines = trimmed.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        // 从第 1 行开始找结束分隔线。不能用 `indexOfFirst { it != lines[0] }` ——
        // 结束线的内容和首行**一模一样**（都是 `---`），那样永远找不到
        val endIndex = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return null

        val fields = LinkedHashMap<String, String>()
        lines.subList(1, endIndex).forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) return@forEach
            val colon = trimmedLine.indexOf(':')
            if (colon <= 0) return@forEach
            val key = trimmedLine.substring(0, colon).trim().lowercase()
            var value = trimmedLine.substring(colon + 1).trim()
            if (value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotEmpty() && value.isNotEmpty()) fields[key] = value
        }
        if (fields.isEmpty()) return null

        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
            fields.entries.firstOrNull { it.key == key }?.value
        }
        val content = CONTENT_KEYS.firstNotNullOfOrNull { key ->
            fields.entries.firstOrNull { it.key == key }?.value
        }
        if (name == null && content == null) return null

        val body = lines.subList(endIndex + 1, lines.size).joinToString("\n").trim()
        return Draft(
            name = name?.takeIf { it.isNotBlank() } ?: cleanNameOf(text),
            content = (content?.takeIf { it.isNotBlank() } ?: body).take(MAX_CONTENT_CHARS),
            note = "从文档开头的 front matter 里取出了预设名与正文。",
        )
    }

    /**
     * Markdown：首个 `# 标题` 当预设名。
     *
     * 标题要从正文里**去掉** —— 它已经是名字了，留着就是重复一遍。
     */
    private fun markdownDraft(text: String, fileName: String): Draft? {
        val lines = text.lines()
        val index = lines.indexOfFirst { it.isNotBlank() }
        if (index < 0) return null
        val line = lines[index].trim()
        if (!line.startsWith("#")) return null
        val title = line.trimStart('#').trim()
        if (title.isEmpty() || title.length > 60) return null

        val body = lines.filterIndexed { i, _ -> i != index }
            .joinToString("\n")
            .trim()
        return Draft(
            name = title,
            content = body.ifBlank { text }.take(MAX_CONTENT_CHARS),
            note = "用文档的第一个标题当预设名。",
        )
    }

    // ── 工具 ─────────────────────────────────────────────────

    /** 去掉 BOM、统一换行、去掉首尾空白。 */
    private fun normalize(raw: String): String =
        raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    /** 文件名去掉扩展名，作为默认的预设名。 */
    private fun cleanName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.substringBeforeLast('.', base).ifBlank { base }
        return stem.trim().take(60).ifBlank { "导入的预设" }
    }

    /** 从正文里找一个像名字的东西：首个短行。 */
    private fun cleanNameOf(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        if (line.isEmpty() || line.length > 40) return "导入的预设"
        return line.trim('#', '*', ' ', '\t', '：', ':')
            .take(60)
            .ifBlank { "导入的预设" }
    }

    /** 从 ZIP 里取一个条目的内容；找不到返回 null。 */
    private fun zipEntry(bytes: ByteArray, path: String): String? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == path) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        val cursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull() ?: return null
        cursor.use {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index < 0 || !it.moveToFirst()) return null
            return it.getString(index)
        }
    }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean {
        if (size < prefix.length) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i].code.toByte()) return false
        }
        return true
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        for (i in values.indices) {
            if (this[i] != values[i].toByte()) return false
        }
        return true
    }
}
