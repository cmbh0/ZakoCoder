package com.simplechat.app.terminal

import com.simplechat.app.agent.Shell
import com.simplechat.app.agent.UbuntuEnv
import com.simplechat.app.agent.Workspace
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 终端里的一行。 */
data class TerminalLine(
    val text: String,
    val kind: Kind,
) {
    enum class Kind { INPUT, OUTPUT, ERROR, INFO }
}

/**
 * 会话式终端。
 *
 * 移动端做**真 PTY** 不值当：Android 没有公开的 forkpty，得带一份 C 的
 * `libterm`，体积和崩溃面都不小，而实际用法几乎都是"敲一条，看结果"。
 *
 * 所以这里是**命令会话**：每条命令起一个 `sh -c` 子进程，但**工作目录由
 * 会话维护** —— `cd` 被截下来改状态，不真的执行。这样 `cd /sdcard` 之后
 * 下一条命令照样在那个目录里，用起来和真终端没差别，只是不支持
 * `vim` 这类需要交互的程序（移动端也基本不会用）。
 */
class TerminalSession(
    /**
     * 只读会话：AI 执行过的命令记录。
     *
     * 它不是"又一条 shell"，而是"AI 干过什么"的流水账 —— 所以终端页
     * 对这类会话隐藏快捷键与输入行，也不允许 `submit`。
     */
    val readOnly: Boolean = false,
) {

    private companion object {
        /**
         * 行数上限。
         *
         * AI 一次任务能跑几十条命令，每条带输出就是几十行；不封顶的话
         * 长时间挂着会一直涨。4000 行约等于几十屏，往回翻也翻不到那么远。
         */
        const val MaxLines = 4000
    }

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _cwd = MutableStateFlow(Workspace.rootOrNull() ?: File("/"))
    val cwd: StateFlow<File> = _cwd.asStateFlow()

    /**
     * 是否切到 Ubuntu。装了才有得切 —— 没装时这个开关是关的，界面也不显示。
     *
     * 两种 shell 的差异不是"好看不好看"，而是**有没有 apt / gcc / 真 bash**：
     * 手机自带的 toybox 连 `--include` 都不认，而 Ubuntu 里可以 `apt install gcc`
     * 然后编译。默认装上就用 Ubuntu —— 用户装它就是为了这个。
     */
    private val _ubuntu = MutableStateFlow(UbuntuEnv.isInstalled())
    val ubuntu: StateFlow<Boolean> = _ubuntu.asStateFlow()

    /** Ubuntu 里的当前目录（guest 路径，如 `/workspace/src`）。 */
    private val _guest = MutableStateFlow("/workspace")
    val guest: StateFlow<String> = _guest.asStateFlow()

    fun ubuntuAvailable(): Boolean = UbuntuEnv.isInstalled()

    /** 在 sh 与 Ubuntu 之间切换。没装 Ubuntu 时什么也不做。 */
    fun toggleShell() {
        if (!UbuntuEnv.isInstalled()) {
            appendInfo("还没有安装 Ubuntu 环境。到「设置 → 运行环境」装一次就能用。")
            return
        }
        _ubuntu.value = !_ubuntu.value
        appendInfo(if (_ubuntu.value) "已切到 Ubuntu（apt / gcc / 真 bash 都可用）" else "已切回本机 sh")
    }

    private val history = mutableListOf<String>()
    private var historyCursor = -1

    /**
     * 从外部（AI 工具）写一条命令记录。
     *
     * 与 [submit] 的区别：**不执行、不进 history、不需要 cwd** —— 命令已经在
     * 别处跑完了，这里只负责把它排成真终端的样子：`prompt 命令` 一行，
     * 输出逐行跟在后面。这样 AI 的记录和用户自己敲的命令在视觉上是同一种东西。
     */
    fun appendExternal(
        prompt: String,
        command: String,
        output: String?,
        isError: Boolean,
    ) {
        val block = buildList {
            add(TerminalLine("$prompt $command", TerminalLine.Kind.INPUT))
            output?.trimEnd()
                ?.takeIf { it.isNotBlank() }
                ?.lineSequence()
                ?.forEach {
                    add(
                        TerminalLine(
                            it,
                            if (isError) TerminalLine.Kind.ERROR else TerminalLine.Kind.OUTPUT,
                        ),
                    )
                }
        }
        _lines.value = (_lines.value + block).let {
            if (it.size <= MaxLines) it else it.subList(it.size - MaxLines, it.size)
        }
    }

    fun appendInfo(text: String) {
        _lines.value = _lines.value + TerminalLine(text, TerminalLine.Kind.INFO)
    }

    /**
     * 中断当前命令。
     *
     * 做不到真正的 SIGINT（子进程不是 tty 会话），但把界面从 busy 里放出来、
     * 并在输出里留一行说明，比"点了没反应"好 —— 用户至少知道该等它自己超时。
     */
    fun abort() {
        if (!_busy.value) return
        _busy.value = false
        appendInfo("（已请求中断；正在运行的子进程会在超时后回收）")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** 提示符。相对工作区的路径更短，手机上不容易换行。 */
    fun prompt(): String {
        if (_ubuntu.value) return "ubuntu:${_guest.value}$"
        val base = Workspace.rootOrNull()
        val current = _cwd.value
        val shown = if (base != null && current.path.startsWith(base.path)) {
            "~" + current.path.removePrefix(base.path)
        } else {
            current.path
        }
        return "dsha:$shown$"
    }

    /** 上一条 / 下一条历史，供输入框的上下箭头用。 */
    fun historyUp(): String? {
        if (history.isEmpty()) return null
        historyCursor = (historyCursor - 1).coerceAtLeast(0)
        return history[historyCursor]
    }

    /** 折叠 `/a/../b`、`./` 这类路径。guest 里没有 host 的 canonicalFile 可用。 */
    private fun normalizeGuest(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun historyDown(): String? {
        if (history.isEmpty()) return null
        historyCursor = (historyCursor + 1).coerceAtMost(history.lastIndex)
        return history[historyCursor]
    }

    suspend fun submit(raw: String) {
        // 只读会话不接受输入：它是记录，不是 shell
        if (readOnly) return
        val command = raw.trim()
        if (command.isEmpty()) return
        history += command
        historyCursor = history.size

        _lines.value = _lines.value + TerminalLine("${prompt()} $command", TerminalLine.Kind.INPUT)

        if (command == "clear" || command == "cls") {
            clear()
            return
        }

        // `cd` 只能自己处理：子进程里的 chdir 不会影响下一次启动的进程
        if (command == "cd" || command.startsWith("cd ")) {
            if (_ubuntu.value) {
                val target = command.removePrefix("cd").trim().ifBlank { "~" }
                _guest.value = when {
                    target == "~" -> "/root"
                    target == "/workspace" -> "/workspace"
                    target.startsWith("/") -> normalizeGuest(target)
                    else -> normalizeGuest(_guest.value.trimEnd('/') + "/" + target)
                }
                return
            }
            val target = command.removePrefix("cd").trim().ifBlank { "~" }
            val next = when (target) {
                "~", "/workspace" -> Workspace.rootOrNull() ?: _cwd.value
                "-" -> _cwd.value
                else -> {
                    val candidate = if (target.startsWith("/")) File(target) else File(_cwd.value, target)
                    candidate
                }
            }
            if (next.isDirectory) {
                _cwd.value = runCatching { next.canonicalFile }.getOrDefault(next)
            } else {
                _lines.value = _lines.value + TerminalLine("cd: 没有这个目录：$target", TerminalLine.Kind.ERROR)
            }
            return
        }

        _busy.value = true
        try {
            val result = if (_ubuntu.value) {
                UbuntuEnv.run(command, cwd = _guest.value, timeoutSeconds = 60)
            } else {
                Shell.run(command, _cwd.value, 60)
            }
            val additions = buildList {
                result.stdout.trimEnd().takeIf { it.isNotBlank() }?.lineSequence()?.forEach {
                    add(TerminalLine(it, TerminalLine.Kind.OUTPUT))
                }
                result.stderr.trimEnd().takeIf { it.isNotBlank() }?.lineSequence()?.forEach {
                    add(TerminalLine(it, TerminalLine.Kind.ERROR))
                }
                if (result.timedOut) {
                    add(TerminalLine("（超时 60 秒，已终止）", TerminalLine.Kind.ERROR))
                }
                if (result.truncated) {
                    add(TerminalLine("（输出过长，已截断）", TerminalLine.Kind.INFO))
                }
            }
            _lines.value = _lines.value + additions
        } finally {
            _busy.value = false
        }
    }
}
