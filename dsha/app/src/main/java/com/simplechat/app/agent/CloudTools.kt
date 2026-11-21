package com.simplechat.app.agent

import com.simplechat.app.terminal.TerminalSessions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 云端工作环境的工具集。
 *
 * 工具**名字和参数**与 [BuiltinTools] 完全一致 —— 模型看到的世界不该因为
 * "文件在本地还是公网"而改变。变的只有执行方式：一次 HTTP 往返代替一次文件系统调用。
 *
 * 少了一个 `web_fetch`：云端服务不替模型抓网页（那会把出口 IP 暴露成服务端），
 * 需要时用 `run_shell` 里的 `curl`，语义一样清楚。
 */
object CloudTools {

    private const val MaxReadChars = 400_000

    fun all(remote: RemoteWorkspace): List<AgentTool> = listOf(
        ReadFile(remote),
        WriteFile(remote),
        EditFile(remote),
        PatchFile(remote),
        SearchContent(remote),
        FindFiles(remote),
        ListDir(remote),
        RunShell(remote),
        RunPython(remote),
        InstallPackages(remote),
        SysInfo(remote),
        // 技能包在手机本地（App 私有目录），不在云端 —— 但模型读它的能力不能少
        BuiltinTools.ReadSkill,
    )

    /** 把模型给的路径规整成服务端认的相对路径。 */
    private fun clean(path: String): String =
        path.trim().removePrefix("file://").trimStart('/').ifEmpty { "." }

    // ── 结构化搜索 / 批量编辑 / 装依赖 ────────────────────────
    //
    // 这一组是 v2 加的。它们存在的理由都一样：把"模型拼命令"换成"模型填参数"。
    // 云端的一次错误命令要一个 RTT 才发现，而模型拿到"grep 返回 0 行"时
    // 会当成"确实没有"，接着开始凭印象改代码 —— 那是最贵的一种错。

    private class SearchContent(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "search_content"
        override val description =
            "在云端工作区的文件内容里搜索（相当于 grep -rn）。不确定某个符号、字符串、报错信息在哪时用它，" +
                "比逐个 read_file 快得多。返回命中文件清单与带行号的整行文本。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") { put("type", "string"); put("description", "要搜的文本或正则") }
                putJsonObject("path") { put("type", "string"); put("description", "搜索起点，默认工作区根") }
                putJsonObject("glob") {
                    put("type", "string")
                    put("description", "只搜匹配的文件，如 **/*.py、*.md")
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
            val result = try {
                remote.grep(
                    pattern = pattern,
                    path = clean(args.str("path") ?: "."),
                    glob = args.str("glob"),
                    regex = args.bool("regex") ?: true,
                    caseSensitive = args.bool("case") ?: false,
                    max = (args.int("max") ?: 200).coerceIn(1, 1000),
                    context = (args.int("context") ?: 0).coerceIn(0, 5),
                )
            } catch (error: Exception) {
                return ToolOutcome.fail(
                    "${error.message ?: "搜索失败"}（如果这是旧版云端服务，它没有 /fs/grep；" +
                        "让用户重新部署 assets 里的 cloud-workspace.zip）",
                )
            }

            if (result.matches.isEmpty()) {
                return ToolOutcome.ok("没有命中「$pattern」（扫描了 ${result.scanned} 个文件）")
            }
            val body = buildString {
                appendLine("命中 ${result.matches.size} 处，分布在 ${result.files.size} 个文件（扫描 ${result.scanned} 个，${result.ms} ms）")
                result.files.take(20).forEach { (path, count) -> appendLine("  $path（$count）") }
                appendLine()
                var lastPath = ""
                result.matches.take(120).forEach { match ->
                    if (match.path != lastPath) {
                        appendLine("── ${match.path}")
                        lastPath = match.path
                    }
                    match.before.forEach { appendLine("   ${match.line - 1}\t$it") }
                    appendLine("${match.line}\t${match.text}")
                    match.after.forEach { appendLine("   ${match.line + 1}\t$it") }
                }
                if (result.truncated) append("… 结果被 max 截断，把 max 调大或缩小 path 范围")
            }
            return ToolOutcome.ok(body)
        }
    }

    private class FindFiles(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "find_files"
        override val description =
            "按文件名找文件（相当于 find），支持 ** 通配符，如 **/*.py、src/**/test_*.js。" +
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
            val entries = try {
                remote.glob(
                    pattern = pattern,
                    path = clean(args.str("path") ?: "."),
                    max = (args.int("max") ?: 300).coerceIn(1, 2000),
                )
            } catch (error: Exception) {
                return ToolOutcome.fail(error.message ?: "查找失败")
            }
            if (entries.isEmpty()) return ToolOutcome.ok("没有匹配「$pattern」的文件")
            return ToolOutcome.ok(
                "${entries.size} 个文件\n" + entries.take(300).joinToString("\n") { entry ->
                    "${entry.name}\t${entry.size}B"
                },
            )
        }
    }

    private class PatchFile(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "patch_file"
        override val description =
            "把一个文件里的多处改动一次性写入。hunks 里每项是一处 old/new 替换，按顺序应用；" +
                "任意一处找不到原文就整批不落盘（不会留下改了一半的文件）。改两处以上时优先用它，而不是连发多次 edit_file。"
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
            val hunks = raw.mapNotNull { element ->
                (element as? JsonObject)?.let { obj ->
                    val old = obj.str("old") ?: return@let null
                    RemoteHunk(old = old, new = obj.str("new") ?: "", all = obj.bool("all") == true)
                }
            }
            if (hunks.isEmpty()) return ToolOutcome.fail("hunks 里没有任何有效的 {old, new}")
            val relative = clean(path)
            val diff = try {
                remote.patch(relative, hunks)
            } catch (error: Exception) {
                return ToolOutcome.fail(error.message ?: "修改失败")
            }
            return ToolOutcome.ok("已修改 $relative（${hunks.size} 处）", diff)
        }
    }

    private class InstallPackages(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "install_packages"
        override val description =
            "在云端安装依赖。kind=apt 装系统包（gcc/ffmpeg/jq…）、pip 装 Python 包、npm 装 Node 包。" +
                "会自动把源切到清华镜像（国内机器不切源，apt 经常几十秒超时）。" +
                "装完用 run_shell 验证一下命令在不在，再继续后面的步骤。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") { add("apt"); add("pip"); add("npm") }
                }
                putJsonObject("packages") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("mirror") {
                    put("type", "string")
                    put("description", "tuna = 先切清华源（默认）；none = 用机器原有源")
                }
            }
            putJsonArray("required") { add("kind"); add("packages") }
        }
        override val mutating = true
        override val risk = ToolRisk.PRIVILEGED

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val kind = args.str("kind")?.lowercase() ?: return ToolOutcome.fail("缺少 kind")
            if (kind !in setOf("apt", "pip", "npm")) return ToolOutcome.fail("kind 只能是 apt / pip / npm")
            val raw = args["packages"] as? kotlinx.serialization.json.JsonArray
                ?: return ToolOutcome.fail("缺少 packages 数组")
            val packages = raw.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
            }
            if (packages.isEmpty()) return ToolOutcome.fail("packages 是空的")
            val (ok, log) = try {
                remote.install(kind, packages)
            } catch (error: Exception) {
                return ToolOutcome.fail(error.message ?: "安装失败")
            }
            val tail = log.trim().takeLast(6000)
            return if (ok) {
                ToolOutcome.ok("已安装 ${packages.joinToString(" ")}（$kind）\n\n$tail")
            } else {
                ToolOutcome.Failed("安装失败（$kind ${packages.joinToString(" ")}）\n\n$tail")
            }
        }
    }

    private class ReadFile(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "read_file"
        override val description =
            "读取云端工作区里的一个文本文件。修改文件前先用它看清现状；读大文件时用 offset/limit 分段读。"
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
            val relative = clean(path)
            val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
            val limit = (args.int("limit") ?: 400).coerceIn(1, 2000)
            /*
             * 分页在**服务端**做。早先是"拉全文、本地切" —— 云端一个 3000 行的
             * 文件，为了看第 40 行要下整份，手机上等着就是一两秒的空白。
             */
            val page = runCatching { remote.readPage(relative, offset, limit) }
                .getOrElse { return ToolOutcome.fail(it.message ?: "读取失败") }
            if (page.content.isEmpty() && page.totalLines == 0) {
                return ToolOutcome.fail("文件为空或不存在：$relative")
            }
            if (page.content.isEmpty()) {
                return ToolOutcome.fail("第 $offset 行之后没有内容（共 ${page.totalLines} 行）")
            }
            val numbered = page.content.lines()
                .mapIndexed { i, line -> "${page.startLine + i}\t$line" }
                .joinToString("\n")
            return ToolOutcome.ok(
                "$relative（第 ${page.startLine}–${page.endLine} 行，共 ${page.totalLines} 行）\n$numbered",
            )
        }
    }

    private class WriteFile(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "write_file"
        override val description =
            "把内容整体写入云端工作区的一个文件（覆盖）。新建文件或需要整篇替换时用；只改几行请用 edit_file。"
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
            val relative = clean(path)
            // diff 由服务端在写入时顺带产出，客户端不多跑一次 HTTP
            val diff = runCatching { remote.write(relative, content) }
                .getOrElse { return ToolOutcome.fail("写入失败：${it.message}") }
            return ToolOutcome.ok("已写入 $relative（${content.length} 字符）", diff)
        }
    }

    private class EditFile(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "edit_file"
        override val description =
            "把云端文件里的一段原文替换成新文本。old 必须与文件内容逐字符一致（含缩进），否则会失败。"
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
            val relative = clean(path)
            /*
             * 一次往返：读、查重、替换、写全在服务端做完。
             *
             * 早先客户端分两步（read + write），云端每次编辑要 2 个 RTT、
             * 传两遍全文 —— 改一行也要等两三秒。这是"云上像本地"最要紧的一处。
             */
            val diff = runCatching { remote.edit(relative, old, new) }
                .getOrElse { return ToolOutcome.fail(it.message ?: "编辑失败") }
            return ToolOutcome.ok("已更新 $relative", diff)
        }
    }

    private class ListDir(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "list_dir"
        override val description = "列云端工作区里一个目录的内容。不确定文件在哪时先看目录。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "相对工作区的目录路径，留空表示根目录")
                }
            }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val relative = clean(args.str("path") ?: ".")
            val entries = runCatching { remote.list(relative) }
                .getOrElse { return ToolOutcome.fail(it.message ?: "读取目录失败") }
            if (entries.isEmpty()) return ToolOutcome.ok("$relative：空目录")
            val body = entries.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name })
                .joinToString("\n") { entry ->
                    if (entry.isDir) {
                        "${entry.name}/"
                    } else {
                        "${entry.name}\t${formatSize(entry.size)}"
                    }
                }
            return ToolOutcome.ok("$relative（${entries.size} 项）\n$body")
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }

    private class RunShell(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "run_shell"
        override val description =
            "在云端工作区执行 shell 命令（/bin/sh），工作目录是工作区根。每次调用是**独立 shell**，" +
                "不保留 cd 与环境变量 —— 需要连续状态就写成一条命令（`cd app && npm test`），或把状态写进文件。\n\n" +
                "**命令内容没有任何限制**：多步串联、管道、重定向、heredoc、`$(...)`、反引号都可以，" +
                "按正常 Linux 的写法来，不需要为了「过安检」把命令拆碎。\n" +
                "1. 需要系统级权限（装系统包、改 /etc、管服务）时把 privileged 设为 true，" +
                "会以 root 执行并先请用户批准；被拒就换个不需要权限的做法，或先说明为什么必须要 root。\n" +
                "2. 装依赖优先用 install_packages，不要自己拼 apt/pip 命令 —— 它会自动切国内镜像。\n" +
                "3. 输出过长会被截断；要看全量就重定向到文件，再用 read_file 分段读。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
                putJsonObject("cwd") { put("type", "string"); put("description", "可选，相对工作区的工作目录") }
                putJsonObject("privileged") {
                    put("type", "boolean")
                    put("description", "以 root（sudo）执行。仅系统级操作需要；普通命令不要开")
                }
            }
            putJsonArray("required") { add("command") }
        }
        override val mutating = true
        override val risk = ToolRisk.EXEC

        /** 带 privileged 的调用升到 [ToolRisk.PRIVILEGED]，单独一档审批。 */
        override fun riskOf(args: JsonObject): ToolRisk =
            if (args.bool("privileged") == true) ToolRisk.PRIVILEGED else ToolRisk.EXEC

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val command = args.str("command") ?: return ToolOutcome.fail("缺少 command")
            val cwd = args.str("cwd")?.let { clean(it) }
            val elevated = args.bool("privileged") == true
            val result = runCatching { remote.exec(command, cwd, sudo = elevated) }
                .getOrElse { return ToolOutcome.fail(it.message ?: "执行失败") }
            /*
             * 云端命令也进 AI 标签。
             *
             * cwd 并进命令行显示：真终端里"在哪个目录跑"是提示符的一部分，
             * 屏幕上只看到一条 `ls` 是分不清它看的是工作区根还是子目录的。
             */
            TerminalSessions.recordAi(
                channel = "cloud",
                command = if (cwd != null) "cd $cwd && $command" else command,
                stdout = result.stdout,
                stderr = result.stderr,
                exitCode = result.code,
            )
            val body = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[stderr]\n")
                    append(result.stderr.trimEnd())
                }
                if (isEmpty()) append("（命令结束，没有输出）")
                if (result.code != 0) {
                    append("\n\n[退出码 ${result.code}]")
                }
                // 明确回执"以什么身份跑的"：装包/改配置失败时，这是第一个要排除的可能
                if (result.elevated) append("\n[已以 root 执行]")
            }
            return if (result.code == 0) ToolOutcome.ok(body) else ToolOutcome.Failed(body)
        }
    }


    /**
     * 环境自述。
     *
     * 存在的理由：模型对"云端是哪台机器"一无所知时会连着试探好几轮
     * （`which python3`、`ls /usr/bin`、`apt-get -v`），每一轮都是公网往返。
     * 一个只读工具把答案一次给全，比让它猜便宜得多。
     */
    private class SysInfo(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "env_info"
        override val description =
            "查看云端环境：发行版、内核、当前用户、能否 sudo 提权、常用命令是否已安装。" +
                "刚开始一个需要系统权限或依赖的任务时，先调它一次，不要靠猜。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val info = remote.sysInfo() ?: return ToolOutcome.fail("云端环境没有响应 /sys/info（可能是旧版服务）")
            return ToolOutcome.ok(info.describe())
        }
    }

    private class RunPython(private val remote: RemoteWorkspace) : AgentTool {
        override val name = "python"
        override val description =
            "在云端工作区执行一段 Python 3 代码。适合数据处理、写脚本、算数。只用标准库时最稳。"
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
            /*
             * 用 heredoc 而不是 -c：代码里带引号是常态，-c 的转义规则会让模型
             * 反复踩坑。heredoc 用带引号的 PY 定界符，内容原样传给解释器，不展开变量。
             */
            val command = "python3 - <<'DSHA_PY'\n$code\nDSHA_PY"
            val result = runCatching { remote.exec(command) }
                .getOrElse { return ToolOutcome.fail(it.message ?: "执行失败") }
            val body = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[stderr]\n")
                    append(result.stderr.trimEnd())
                }
                if (isEmpty()) append("（脚本结束，没有输出）")
            }
            return if (result.code == 0) ToolOutcome.ok(body) else ToolOutcome.Failed(body)
        }
    }
}
