package com.simplechat.app.agent

import com.simplechat.app.terminal.TerminalSessions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 本机 Ubuntu 环境的工具。
 *
 * 与手机自带的 `run_shell` 是两件东西，所以名字必须分开 —— 模型看到两个都叫
 * `run_shell` 的时候会挑错，而用户完全看不出它挑错了：
 *
 * | | `run_shell` | `linux_shell` |
 * |---|---|---|
 * | 跑在 | Android 的 toybox | Ubuntu 24.04 |
 * | 有什么 | `sh`、`ls`、`busybox` 那一套 | `bash`、`apt`、`pip`、`gcc`、`git` |
 * | 权限 | 普通应用用户 | 容器内的 root |
 * | 能装东西吗 | 不能 | 能（`apt install`） |
 *
 * 工作区在 guest 里映射成 `/workspace`，所以模型写 `/workspace/src/main.py`
 * 与写 `src/main.py` 都指向同一份文件。
 */
object LinuxTools {

    fun available(): Boolean = UbuntuEnv.isReady() && UbuntuEnv.supported()

    fun all(): List<AgentTool> = listOf(Shell, Install, Env)

    // ── 环境自检 ─────────────────────────────────────────────

    /**
     * 一条只读的"环境到底什么样"。
     *
     * 没有它的时候，模型遇到 `python3: not found` 只能猜：是没装 Ubuntu？
     * 装了但没装 Python？还是 proot 起不来？—— 三种情况的修法完全不同，
     * 猜错就是让用户白折腾一轮。现在它可以先问一句，再照着结果说话。
     */
    private object Env : AgentTool {
        override val name = "linux_env"
        override val description =
            "查看本机 Ubuntu 环境的实际状态：是否安装、系统版本、python3 / pip 是否存在、磁盘余量。" +
                "准备装包或跑脚本、但不确定环境齐不齐时，先调它看一眼，比直接试错快。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        override val mutating: Boolean get() = false

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            if (!UbuntuEnv.supported()) {
                return ToolOutcome.fail(
                    UbuntuEnv.unsupportedReason()
                        ?: "这台设备不支持本机 Ubuntu 环境，只能用云端工作区。",
                )
            }
            if (!UbuntuEnv.isInstalled()) {
                return ToolOutcome.ok(
                    "本机 Ubuntu 环境**还没有安装**。请让用户到「设置 → 运行环境」点一次安装" +
                        "（约 30 MB 下载 + 解压，需要 1.2 GB 空闲空间）。装好之后 apt / pip 都能用。",
                )
            }
            val probe = UbuntuEnv.run(
                """
                echo "os=$(head -n 1 /etc/os-release 2>/dev/null | cut -d= -f2- | tr -d '\"')"
                echo "python=$(python3 -V 2>&1 || echo 未安装)"
                echo "pip=$(python3 -m pip --version 2>&1 | cut -d' ' -f1-2 || echo 未安装)"
                echo "disk=$(df -h / | tail -n 1)"
                """.trimIndent(),
                timeoutSeconds = 60,
            )
            val body = probe.stdout.trim().ifBlank { probe.stderr.trim() }
            return if (probe.exitCode == 0) {
                ToolOutcome.ok(body.ifBlank { "（环境没有返回任何信息）" })
            } else {
                ToolOutcome.fail("环境装了但探测失败：$body")
            }
        }
    }

    // ── 终端 ─────────────────────────────────────────────────

    private object Shell : AgentTool {
        override val name = "linux_shell"
        override val description =
            "在**本机的 Ubuntu 环境**里执行 shell 命令（真 bash + apt + pip + gcc，容器内 root）。" +
                "需要装包、编译、跑 Python 脚本、用 git 时用它；只是想看手机上的文件用 run_shell。" +
                "工作区在环境内映射为 /workspace，可以直接 cd 进去。" +
                "每条命令是独立会话，不保留 cd 与环境变量；要连续状态就写进一条命令或用 && 串起来。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
                putJsonObject("cwd") {
                    put("type", "string")
                    put("description", "相对工作区的子目录，会成为 /workspace/<cwd>；留空则在 /root")
                }
                putJsonObject("timeout") { put("type", "integer"); put("description", "秒，默认 120，上限 600") }
            }
            putJsonArray("required") { add("command") }
        }
        override val mutating = true
        override val risk = ToolRisk.PRIVILEGED

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val command = args.str("command") ?: return ToolOutcome.fail("缺少 command")
            if (!UbuntuEnv.isInstalled()) {
                return ToolOutcome.fail("本机 Ubuntu 环境还没装。让用户到「设置 → 运行环境」点一次安装。")
            }
            val timeout = (args.int("timeout") ?: 120).coerceIn(1, 600).toLong()
            val result = UbuntuEnv.run(command, args.str("cwd"), timeout)
            // Ubuntu 里的命令同样进 AI 标签（提示符换成 ubuntu:）
            TerminalSessions.recordAi(
                channel = "ubuntu",
                command = args.str("cwd")
                    ?.let { "cd /workspace/$it && $command" }
                    ?: command,
                stdout = result.stdout,
                stderr = result.stderr,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
            )
            return if (result.exitCode == 0 && !result.timedOut) {
                ToolOutcome.ok(result.asToolOutput())
            } else {
                ToolOutcome.Failed(result.asToolOutput())
            }
        }
    }

    // ── 装包 ─────────────────────────────────────────────────

    private object Install : AgentTool {
        override val name = "install_packages"
        override val description =
            "在 Ubuntu 环境里安装依赖。kind=apt 装系统包（gcc/make/ffmpeg/jq…）、pip 装 Python 包、" +
                "npm 装 Node 包。源已固定为清华镜像，不用自己换源。" +
                "装完用 linux_shell 验证一下命令在不在，再继续后面的步骤。"
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
            // 包名要拼进命令行，挡掉 shell 元字符 —— 否则等于给了任意命令执行
            val invalid = packages.firstOrNull { !it.matches(Regex("[A-Za-z0-9._@/:+=~<>-]+")) }
            if (invalid != null) return ToolOutcome.fail("包名含非法字符：$invalid")

            if (!UbuntuEnv.isInstalled()) {
                return ToolOutcome.fail("本机 Ubuntu 环境还没装。让用户到「设置 → 运行环境」点一次安装。")
            }

            val joined = packages.joinToString(" ")
            val command = when (kind) {
                /*
                 * apt：先 `dpkg --configure -a` 收尾上一次被打断的安装。
                 *
                 * 没这一步的话，任何一次网络抖动都会让 dpkg 停在半配置状态，
                 * 之后所有 apt 命令一律 "dpkg was interrupted" —— 用户看到的是
                 * "装什么都失败"，而正确做法只是把上次那条收完。
                 */
                "apt" -> "dpkg --configure -a >/dev/null 2>&1 || true; " +
                    "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y $joined"
                // pip：base 镜像里没有 pip，先自动补上再装包
                "pip" -> {
                    if (!UbuntuEnv.ensurePip()) {
                        return ToolOutcome.fail(
                            "Ubuntu 环境里没有可用的 pip，自动安装也失败了。" +
                                "可以先让我用 install_packages(kind=apt) 装 python3-pip，再重试。",
                        )
                    }
                    "python3 -m pip install --break-system-packages $joined"
                }
                else -> "npm install -g $joined"
            }
            val result = UbuntuEnv.run(command, timeoutSeconds = 600)
            val body = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n[stderr]\n")
                    append(result.stderr.trimEnd())
                }
                if (isEmpty()) append("（命令结束，没有输出）")
                if (result.timedOut) append("\n\n[超时被终止]")
                if (result.exitCode != 0 && !result.timedOut) append("\n\n[退出码 ${result.exitCode}]")
            }
            return if (result.exitCode == 0 && !result.timedOut) {
                ToolOutcome.ok("已安装 $joined（$kind）\n\n$body")
            } else {
                ToolOutcome.Failed("安装失败（$kind $joined）\n\n$body")
            }
        }
    }
}
