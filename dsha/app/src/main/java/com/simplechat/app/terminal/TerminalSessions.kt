package com.simplechat.app.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 终端会话池。
 *
 * 为什么要"池"而不是一页一个会话：终端是**有状态**的 —— 目录、历史、切没切
 * Ubuntu、刚才跑到哪一步，全都是这个 shell 的私有状态。早先每次进页面都新建一个
 * 会话，退出去再进来就全丢了；而如果反过来共用一个全局会话，两条不相干的活儿
 * 会把输出和 cwd 搅在一起（"终端污染"）。
 *
 * 所以：**一个标签一个会话，退出页面不销毁，手动关才销毁**。
 * 生命周期跟着进程走 —— 重启 App 全部重来，这是终端的正常预期。
 */
object TerminalSessions {

    data class Entry(
        val id: String,
        val title: String,
        val session: TerminalSession,
        /** AI 记录会话：永远排第一、不可关闭。 */
        val ai: Boolean = false,
    )

    private val _list = MutableStateFlow<List<Entry>>(emptyList())
    val list: StateFlow<List<Entry>> = _list.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private var counter = 0

    /** AI 记录会话的固定 id。 */
    private const val AiId = "ai"

    private var aiEntry: Entry? = null

    /**
     * AI 记录会话。
     *
     * 存在的理由：AI 在后台跑的命令，用户只能在对话的工具卡里看到一行摘要 ——
     * 想核实"它到底执行了什么"，得展开卡片、还要在聊天记录里翻。终端本来就
     * 是"看命令"的地方，把 AI 的执行流水放进来，和自己的命令并排放，
     * 才像一个真终端；不然第一个标签永远是"终端 1"，而 AI 在另一处跑得飞快。
     */
    fun ai(): Entry {
        aiEntry?.let { existing ->
            if (_list.value.any { it.id == existing.id }) return existing
        }
        val entry = Entry(
            id = AiId,
            title = "AI",
            session = TerminalSession(readOnly = true),
            ai = true,
        )
        aiEntry = entry
        _list.value = listOf(entry) + _list.value.filter { it.id != AiId }
        return entry
    }

    /**
     * 记一条 AI 执行的命令。
     *
     * 终端页没打开也照写 —— 用户往往是先让 AI 干活、再切过来看它干了什么。
     */
    fun recordAi(
        channel: String,
        command: String,
        stdout: String = "",
        stderr: String = "",
        exitCode: Int = 0,
        timedOut: Boolean = false,
    ) {
        val prompt = when (channel) {
            "cloud" -> "cloud:~$"
            "ubuntu" -> "ubuntu:~$"
            else -> "dsha:~$"
        }
        /*
         * stdout 与 stderr 都写进同一块，按到达顺序（先 stdout 后 stderr）——
         * 真终端里两者本来就是交错的，分开标成两块反而不像终端。
         * 不补"（无输出）"：有 prompt 那一行就说明命令跑过了，空就是空。
         */
        val text = buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trimEnd())
            }
            if (timedOut) {
                if (isNotEmpty()) append('\n')
                append("（超时被终止）")
            }
        }
        ai().session.appendExternal(
            prompt = prompt,
            command = command,
            output = text.ifBlank { null },
            isError = exitCode != 0 || timedOut,
        )
    }

    /** 至少有会话可用，并且返回当前选中的那个。 */
    fun ensureActive(): Entry {
        // AI 记录永远在第一个 —— 即使一个用户会话都没有
        ai()
        val current = _activeId.value?.let { id -> _list.value.firstOrNull { it.id == id } }
        if (current != null) return current
        val first = _list.value.firstOrNull() ?: create()
        _activeId.value = first.id
        return first
    }

    fun create(title: String? = null): Entry {
        counter += 1
        val entry = Entry(
            id = UUID.randomUUID().toString().take(8),
            title = title?.takeIf { it.isNotBlank() } ?: "终端 $counter",
            session = TerminalSession(),
        )
        _list.value = _list.value + entry
        _activeId.value = entry.id
        return entry
    }

    fun select(id: String) {
        if (_list.value.any { it.id == id }) _activeId.value = id
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().take(16)
        if (clean.isEmpty()) return
        _list.value = _list.value.map { if (it.id == id) it.copy(title = clean) else it }
    }

    /**
     * 关掉一个会话。
     *
     * 关的是当前会话时，优先选右边那个（像浏览器标签），没有就选左边；
     * 全关光了就自动开一个新的 —— 终端页不该出现"一个标签都没有"的状态。
     */
    fun close(id: String) {
        // AI 记录不可关闭：它不是"一次会话"，是这个终端的一部分
        if (id == AiId) return
        val index = _list.value.indexOfFirst { it.id == id }
        if (index < 0) return
        val next = _list.value.toMutableList().also { it.removeAt(index) }
        _list.value = next
        if (_activeId.value == id) {
            _activeId.value = next.getOrNull(index)?.id
                ?: next.getOrNull(index - 1)?.id
                ?: create().id
        }
    }

    fun activeEntry(): Entry? = _activeId.value?.let { id -> _list.value.firstOrNull { it.id == id } }
}
