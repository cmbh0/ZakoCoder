package com.simplechat.app.agent

import com.simplechat.app.skill.SkillStore
import com.simplechat.app.terminal.TerminalSessions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * 内置工具集。
 *
 * 只有 6 个，且刻意保持"最小可用"：
 * 模型能读写文件、看目录、跑命令、跑 Python、抓网页，就足以完成绝大多数任务；
 * 工具越多，选错的概率越高，上下文也越贵。
 *
 * 每个 description 都写了**触发条件**（"当用户要…时用"）而不是功能说明。
 * 这是实践里差别最大的一处：模型是按场景匹配工具的。
 */
object BuiltinTools {

    fun all(client: OkHttpClient): List<AgentTool> = listOf(
        ReadFile,
        WriteFile,
        EditFile,
        PatchFile,
        SearchContent,
        FindFiles,
        ListDir,
        RunShell,
        RunPython,
        ReadSkill,
    )

    // ── 读文件 ────────────────────────────────────────────────

    private object ReadFile : AgentTool {
        override val name = "read_file"
        override val description =
            "读取工作区里的一个文本文件。修改文件前先用它看清现状；读大文件时用 offset/limit 分段读。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string"); put("description", "相对工作区的路径") }
                putJsonObject("offset") { put("type", "integer"); put("description", "起始行，1 起") }
                putJsonObject("limit") { put("type", "integer"); put("description", "最多读多少行，默认 400") }
            }
            putJsonArray("required") { add("path") }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val path = args.str("path") ?: return ToolOutcome.fail("缺少 path")
            val file = Workspace.resolve(path)
            if (!file.exists()) return ToolOutcome.fail("文件不存在：${Workspace.relative(file)}")
            if (file.isDirectory) return ToolOutcome.fail("这是目录，请用 list_dir")
            if (file.length() > MaxReadBytes) {
                return ToolOutcome.fail("文件过大（${file.length() / 1024} KB），请用 offset/limit 分段读")
            }
            val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
            val limit = (args.int("limit") ?: 400).coerceIn(1, 2000)
            val lines = withContext(Dispatchers.IO) { file.readLines() }
            val slice = lines.drop(offset - 1).take(limit)
            if (slice.isEmpty()) return ToolOutcome.fail("第 $offset 行之后没有内容（共 ${lines.size} 行）")
            val numbered = slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
            return ToolOutcome.ok(
                "${Workspace.relative(file)}（第 $offset–${offset + slice.size - 1} 行，共 ${lines.size} 行）\n$numbered",
            )
        }
    }

    // ── 写文件 ────────────────────────────────────────────────

    private object WriteFile : AgentTool {
        override val name = "write_file"
        override val description =
            "把内容整体写入一个文件（覆盖）。新建文件或需要整篇替换时用；只改几行请用 edit_file。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add("path"); add("content") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val path = args.str("path") ?: return ToolOutcome.fail("缺少 path")
            val content = args.str("content") ?: ""
            val file = Workspace.resolve(path)
            return runCatching {
                /*
                 * 写之前先读一眼旧内容，只为了算 diff。
                 *
                 * 放在同一段 IO 里做，不额外多一次往返；文件超过 2 MB 就跳过 ——
                 * 那种文件本来也不是"改几行"的场景，硬算 diff 只会让工具变慢。
                 */
                val before = withContext(Dispatchers.IO) {
                    if (file.isFile && file.length() <= 2 * 1024 * 1024) {
                        runCatching { file.readText() }.getOrNull()
                    } else {
                        null
                    }
                }
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
                val lines = content.count { it == '\n' } + 1
                val diff = if (before == null) {
                    // 新建文件：全部是新增行，界面上显示成绿油油一片是对的
                    content.lines().map { DiffLine(DiffLine.Kind.ADD, it) }
                } else {
                    Diffs.compute(before, content)
                }
                ToolOutcome.ok(
                    "已写入 ${Workspace.relative(file)}（$lines 行，${content.length} 字符）",
                    diff,
                    artifacts = listOf(file.absolutePath),
                )
            }.getOrElse { ToolOutcome.fail("写入失败：${it.message}") }
        }
    }

    // ── 局部替换 ──────────────────────────────────────────────

    private object EditFile : AgentTool {
        override val name = "edit_file"
        override val description =
            "把文件里的一段原文替换成新文本。old 必须与文件内容逐字符一致（含缩进），否则会失败。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("old") { put("type", "string"); put("description", "要被替换的原文，唯一") }
                putJsonObject("new") { put("type", "string") }
            }
            putJsonArray("required") { add("path"); add("old"); add("new") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val path = args.str("path") ?: return ToolOutcome.fail("缺少 path")
            val old = args.str("old") ?: return ToolOutcome.fail("缺少 old")
            val new = args.str("new") ?: ""
            val file = Workspace.resolve(path)
            if (!file.exists()) return ToolOutcome.fail("文件不存在：${Workspace.relative(file)}")
            val text = withContext(Dispatchers.IO) { file.readText() }
            val first = text.indexOf(old)
            if (first < 0) return ToolOutcome.fail("没找到这段原文，请先 read_file 确认（注意缩进与空行）")
            if (text.indexOf(old, first + 1) >= 0) {
                return ToolOutcome.fail("这段原文出现了多次，请加长上下文使其唯一")
            }
            val updated = text.replaceFirst(old, new)
            withContext(Dispatchers.IO) { file.writeText(updated) }
            return ToolOutcome.ok(
                "已修改 ${Workspace.relative(file)}",
                Diffs.compute(text, updated),
                artifacts = listOf(file.absolutePath),
            )
        }
    }

    // ── 看目录 ────────────────────────────────────────────────

    private object ListDir : AgentTool {
        override val name = "list_dir"
        override val description = "列出工作区某个目录下的文件与子目录。先看结构再动手，比猜路径可靠。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "相对路径，留空表示工作区根目录")
                }
            }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val dir = Workspace.resolve(args.str("path") ?: ".")
            if (!dir.exists()) return ToolOutcome.fail("目录不存在：${Workspace.relative(dir)}")
            if (!dir.isDirectory) return ToolOutcome.fail("这是文件，请用 read_file")
            val entries = withContext(Dispatchers.IO) { dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) }
                .orEmpty()
            if (entries.isEmpty()) return ToolOutcome.ok("（空目录）")
            val text = entries.take(300).joinToString("\n") { file ->
                val size = if (file.isDirectory) "" else "  ${file.length()}B"
                (if (file.isDirectory) "${file.name}/" else file.name) + size
            }
            return ToolOutcome.ok("${Workspace.relative(dir)}/\n$text")
        }
    }

    // ── shell ────────────────────────────────────────────────

    private object RunShell : AgentTool {
        override val name = "run_shell"
        override val description =
            "在工作区里执行一条 shell 命令（Android 的 toybox/busybox）。用于查看环境、处理文件、跑脚本。不要用它做破坏性操作。" +
                "这台设备没有 root，sudo 一定失败；需要系统级能力时请用户换到云端工作环境。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
                putJsonObject("timeout") { put("type", "integer"); put("description", "秒，默认 30") }
            }
            putJsonArray("required") { add("command") }
        }
        override val mutating = true

        override val risk = ToolRisk.EXEC

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val command = args.str("command") ?: return ToolOutcome.fail("缺少 command")
            val timeout = (args.int("timeout") ?: 30).coerceIn(1, 120).toLong()
            val result = Shell.run(command, ctx.workspace, timeout)
            /*
             * 记进终端的 AI 标签。
             *
             * 工具卡的摘要只有一行（"run_shell · ls -la"），用户想核实
             * "它到底跑了什么、输出了什么"得展开卡片，而那是聊天记录里的
             * 一次性东西。终端是常驻的、可以往回翻的 —— 命令记在那儿才像样。
             */
            TerminalSessions.recordAi(
                channel = "local",
                command = command,
                stdout = result.stdout,
                stderr = result.stderr,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
            )
            return ToolOutcome.ok(result.asToolOutput())
        }
    }

    // ── python ───────────────────────────────────────────────

    private object RunPython : AgentTool {
        override val name = "python"
        override val description =
            "执行一段 Python 代码并返回 stdout。用于计算、数据清洗、文本处理。解释器来自 Ubuntu 环境；缺库就用 linux_shell 里的 pip 装。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("code") { put("type", "string") }
            }
            putJsonArray("required") { add("code") }
        }
        override val mutating = true

        override val risk = ToolRisk.EXEC

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val code = args.str("code") ?: return ToolOutcome.fail("缺少 code")
            return PyRuntime.run(code, ctx.workspace)
        }
    }

    // ── 读技能 ────────────────────────────────────────────────

    /**
     * 技能的正文不进系统提示词（几百行会把普通对话也拖贵），
     * 所以必须给模型一条"要用的时候怎么读"的路径。
     *
     * 它读的是 **App 私有目录**下的 `skills/<id>/`，与工作区无关 ——
     * 因此不能复用 read_file（那条路径被工作区边界拦着，正是故意的）。
     */
    /*
     * 技能正文不进系统提示词，所以"怎么读它"必须是一条工具。
     *
     * 它读的是 **App 私有目录**里的 `skills/<id>/`，与工作区无关 ——
     * 因此云端工作区那边也复用同一个实现：技能说明在手机上，产物在远端，
     * 这是两件事，不该因为换了个工作区就丢掉读技能的能力。
     */
    internal object ReadSkill : AgentTool {
        override val name = "read_skill"
        override val description =
            "读取一个技能包的说明（SKILL.md），或它目录下的某个文件。决定用某个技能前先读它 —— 不要凭名字猜用法。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "string"); put("description", "技能 id（系统提示词里列出的那个）") }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "可选，相对技能目录的文件名，如 scripts/build.py；留空读 SKILL.md")
                }
            }
            putJsonArray("required") { add("id") }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val id = args.str("id") ?: return ToolOutcome.fail("缺少 id")
            val base = runCatching { SkillStore.rootDir().canonicalFile }.getOrElse {
                return ToolOutcome.fail("技能目录不可用")
            }
            val dir = File(base, id).canonicalFile
            if (!dir.path.startsWith(base.path + File.separator)) return ToolOutcome.fail("技能 id 不合法")
            if (!dir.isDirectory) return ToolOutcome.fail("没有这个技能：$id")
            val relative = args.str("file")?.trim()?.trimStart('/').orEmpty()
            val target = if (relative.isEmpty()) {
                File(dir, "SKILL.md").takeIf { it.isFile } ?: File(dir, "skill.md")
            } else {
                File(dir, relative).canonicalFile.also { file ->
                    if (!file.path.startsWith(dir.path + File.separator)) {
                        return ToolOutcome.fail("文件越界：$relative")
                    }
                }
            }
            if (!target.isFile) return ToolOutcome.fail("技能里没有这个文件：${relative.ifEmpty { "SKILL.md" }}")
            if (target.length() > MaxReadBytes) return ToolOutcome.fail("文件过大（${target.length() / 1024} KB）")
            val text = withContext(Dispatchers.IO) { runCatching { target.readText() }.getOrNull() }
                ?: return ToolOutcome.fail("读取失败")
            return ToolOutcome.ok("${id}/${target.name}\n$text")
        }
    }

    // ── 批量替换 ──────────────────────────────────────────────

    private object PatchFile : AgentTool {
        override val name = "patch_file"
        override val description =
            "把一个文件里的多处改动一次性写入。hunks 里每项是一处 old/new 替换，按顺序应用；" +
                "任意一处找不到原文就整批不落盘。改两处以上时优先用它，而不是连发多次 edit_file —— " +
                "后者中间失败会留下改了一半的文件。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("hunks") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("old") { put("type", "string") }
                            putJsonObject("new") { put("type", "string") }
                            putJsonObject("all") {
                                put("type", "boolean")
                                put("description", "true = 替换所有出现处；默认要求原文唯一")
                            }
                        }
                        putJsonArray("required") { add("old"); add("new") }
                    }
                }
            }
            putJsonArray("required") { add("path"); add("hunks") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val path = args.str("path") ?: return ToolOutcome.fail("缺少 path")
            val raw = args["hunks"] as? kotlinx.serialization.json.JsonArray
                ?: return ToolOutcome.fail("缺少 hunks 数组")
            data class Hunk(val old: String, val new: String, val all: Boolean)
            val hunks = raw.mapNotNull { element ->
                (element as? JsonObject)?.let { obj ->
                    val old = obj.str("old") ?: return@let null
                    Hunk(old, obj.str("new") ?: "", obj.bool("all") == true)
                }
            }
            if (hunks.isEmpty()) return ToolOutcome.fail("hunks 里没有任何有效的 {old, new}")

            val file = Workspace.resolve(path)
            if (!file.exists()) return ToolOutcome.fail("文件不存在：${Workspace.relative(file)}")
            if (file.length() > 4L * 1024 * 1024) return ToolOutcome.fail("文件过大（超过 4 MB），请用 run_shell 处理")

            val before = withContext(Dispatchers.IO) { file.readText() }
            var text = before
            hunks.forEachIndexed { index, hunk ->
                val position = text.indexOf(hunk.old)
                if (position < 0) {
                    val preview = hunk.old.lineSequence().firstOrNull()?.take(60).orEmpty()
                    return ToolOutcome.fail(
                        "第 ${index + 1} 处找不到原文（前 $index 处已校验但未落盘）：$preview",
                    )
                }
                if (hunk.all) {
                    text = text.replace(hunk.old, hunk.new)
                } else {
                    if (text.indexOf(hunk.old, position + 1) >= 0) {
                        return ToolOutcome.fail(
                            "第 ${index + 1} 处的原文出现了多次，请加长上下文使其唯一，或设 all=true",
                        )
                    }
                    text = text.substring(0, position) + hunk.new + text.substring(position + hunk.old.length)
                }
            }
            withContext(Dispatchers.IO) { file.writeText(text) }
            return ToolOutcome.ok(
                "已修改 ${Workspace.relative(file)}（${hunks.size} 处）",
                Diffs.compute(before, text),
                artifacts = listOf(file.absolutePath),
            )
        }
    }

    // ── 内容搜索 ──────────────────────────────────────────────

    private object SearchContent : AgentTool {
        override val name = "search_content"
        override val description =
            "在工作区的文件内容里搜索（相当于 grep -rn）。不确定某个符号、字符串、报错信息在哪时用它，" +
                "比逐个 read_file 快得多。返回命中文件清单与带行号的整行文本。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") { put("type", "string"); put("description", "要搜的文本或正则") }
                putJsonObject("path") { put("type", "string"); put("description", "搜索起点，默认工作区根") }
                putJsonObject("glob") {
                    put("type", "string")
                    put("description", "只搜匹配的文件，如 **/*.kt、*.md")
                }
                putJsonObject("regex") { put("type", "boolean"); put("description", "pattern 是否按正则解释，默认 true") }
                putJsonObject("case") { put("type", "boolean"); put("description", "是否区分大小写，默认 false") }
                putJsonObject("max") { put("type", "integer"); put("description", "最多返回多少条，默认 200") }
                putJsonObject("context") { put("type", "integer"); put("description", "每条匹配前后各带几行，默认 0") }
            }
            putJsonArray("required") { add("pattern") }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val pattern = args.str("pattern") ?: return ToolOutcome.fail("缺少 pattern")
            val root = Workspace.resolve(args.str("path") ?: ".")
            if (!root.exists()) return ToolOutcome.fail("目录不存在：${Workspace.relative(root)}")
            val result = try {
                FileSearch.grep(
                    root = root,
                    pattern = pattern,
                    regex = args.bool("regex") ?: true,
                    caseSensitive = args.bool("case") ?: false,
                    glob = args.str("glob"),
                    max = (args.int("max") ?: 200).coerceIn(1, 1000),
                    context = (args.int("context") ?: 0).coerceIn(0, 5),
                )
            } catch (error: Exception) {
                return ToolOutcome.fail(error.message ?: "搜索失败")
            }

            if (result.hits.isEmpty()) {
                return ToolOutcome.ok("没有命中「$pattern」（扫描了 ${result.scanned} 个文件）")
            }
            val body = buildString {
                appendLine("命中 ${result.hits.size} 处，分布在 ${result.files.size} 个文件（扫描 ${result.scanned} 个，${result.ms} ms）")
                result.files.take(20).forEach { (path, count) -> appendLine("  $path（$count）") }
                appendLine()
                var lastPath = ""
                result.hits.take(120).forEach { hit ->
                    if (hit.path != lastPath) {
                        appendLine("── ${hit.path}")
                        lastPath = hit.path
                    }
                    hit.before.forEach { appendLine("   ${hit.line - 1}\t$it") }
                    appendLine("${hit.line}\t${hit.text}")
                    hit.after.forEach { appendLine("   ${hit.line + 1}\t$it") }
                }
                if (result.truncated) append("… 结果被 max 截断，把 max 调大或缩小 path 范围")
            }
            return ToolOutcome.ok(body)
        }
    }

    // ── 按名字找文件 ──────────────────────────────────────────

    private object FindFiles : AgentTool {
        override val name = "find_files"
        override val description =
            "按文件名找文件（相当于 find），支持 ** 通配符，如 **/*.kt、src/**/test_*.js。" +
                "不知道文件在哪时先用它或 list_dir，别猜路径。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") { put("type", "string") }
                putJsonObject("path") { put("type", "string"); put("description", "搜索起点，默认工作区根") }
                putJsonObject("max") { put("type", "integer"); put("description", "最多返回多少个，默认 300") }
            }
            putJsonArray("required") { add("pattern") }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val pattern = args.str("pattern") ?: return ToolOutcome.fail("缺少 pattern")
            val root = Workspace.resolve(args.str("path") ?: ".")
            if (!root.exists()) return ToolOutcome.fail("目录不存在：${Workspace.relative(root)}")
            val files = try {
                FileSearch.find(root, pattern, (args.int("max") ?: 300).coerceIn(1, 2000))
            } catch (error: Exception) {
                return ToolOutcome.fail(error.message ?: "查找失败")
            }
            if (files.isEmpty()) return ToolOutcome.ok("没有匹配「$pattern」的文件")
            return ToolOutcome.ok(
                "${files.size} 个文件\n" + files.joinToString("\n") { file ->
                    "${FileSearch.relativeName(root.canonicalFile, file)}\t${file.length()}B"
                },
            )
        }
    }

    private const val MaxReadBytes = 512 * 1024
}
