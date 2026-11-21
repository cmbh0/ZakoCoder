package com.simplechat.app.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.ui.markdown.warmMarkdownCache
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.TITLE_MATERIAL_CHARS
import com.simplechat.app.data.TITLE_MAX_TOKENS
import com.simplechat.app.data.isAutoTitle
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.data.cleanGeneratedTitle
import com.simplechat.app.data.createLatestReminder
import com.simplechat.app.data.derivedTitle
import com.simplechat.app.data.titleSystemPrompt
import com.simplechat.app.data.userMessage
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.db.DraftEntity
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.DeepSeekTts
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.MessageContent
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.ModelResolver
import com.simplechat.app.agent.AgentEvent
import com.simplechat.app.agent.ApprovalDecision
import com.simplechat.app.agent.ApprovalGate
import com.simplechat.app.agent.ApprovalMode
import com.simplechat.app.agent.ApprovalPolicy
import com.simplechat.app.agent.ApprovalRequest
import com.simplechat.app.agent.AgentRunner
import com.simplechat.app.agent.AgentPrompt
import com.simplechat.app.agent.BuiltinTools
import com.simplechat.app.agent.CloudEnvs
import com.simplechat.app.agent.CloudTools
import com.simplechat.app.agent.RemoteWorkspaces
import com.simplechat.app.agent.Rewind
import com.simplechat.app.agent.RewindReport
import com.simplechat.app.agent.RewindTool
import com.simplechat.app.agent.LinuxTools
import com.simplechat.app.agent.ScreenAutomation
import com.simplechat.app.agent.ScheduleTools
import com.simplechat.app.agent.ScreenTools
import com.simplechat.app.agent.Workspace
import com.simplechat.app.agent.WebTools
import com.simplechat.app.agent.WorkspaceKind
import com.simplechat.app.skill.BuiltinSkills
import com.simplechat.app.mcp.McpStore
import com.simplechat.app.skill.SkillStore
import com.simplechat.app.net.ToolSpecDto
import kotlin.coroutines.resume
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.anthropic.AnthropicRequest
import com.simplechat.app.net.isOfficialDeepSeekEndpoint
import com.simplechat.app.net.serverErrorText
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 对话状态与流式调度。
 *
 * 数据流：
 * - **内存**中的 `messages` 是 UI 的唯一来源（流式增量只替换目标那一条）。
 * - **Room** 是持久层，按 §4.4 的策略**节流 checkpoint**：
 *   流中每约 1.5s 落一次盘，流结束/中断时终写一次。
 *   若每个 token 都回写 Room，一次 2000 字回复就是 2000 次磁盘事务。
 *
 * 其他关键点：
 * - **节流合并**：token 先进缓冲区，由独立 flusher 每 [STREAM_THROTTLE_MS] 合并提交一次
 *   （≈ 一帧一次，对齐官方 demo 的 rAF 节拍），避免每个 token 都触发列表重组（§8）。
 * - **不发送 `reasoning_content`**：本期不带 `tools`，官方明确无需回传（§11.1）。
 */
/** 换会话时后台预热的尾部消息条数：贴底打开先看到的就是这几条。 */
private const val WarmTailMessages = 10

/**
 * 预热时单条消息的长度上限。
 *
 * 一条几万字的消息解析要几百毫秒；替它预热不会让首屏更快（列表本身也要等它排版），
 * 只会把后台线程占满、拖慢真正该快的那几条。
 */
private const val WarmTailMaxChars = 24_000

/** 断流后每条回复最多自动续跑几次。 */
private const val MaxAutoResume = 2

/** 自动续跑前的停顿：上游刚抖过，立刻重连多半还是失败。 */
private const val AutoResumeDelayMs = 1_500L

class ChatViewModel(
    private val chatApi: ChatApi,
    private val settingsStore: SettingsStore,
    private val repository: ChatRepository,
) : ViewModel() {

    val messages = mutableStateListOf<UiMessage>()

    /** 当前会话 id；为空表示尚未落库的新对话。 */
    var currentConversationId by mutableStateOf<String?>(null)
        private set

    /**
     * 当前会话标题（库里的值）。
     *
     * 顶栏**必须**用它，而不是从首条用户消息推导 —— 否则用户在抽屉里
     * 重命名之后，顶栏还显示旧标题，两边对不上。
     */
    var currentConversationTitle by mutableStateOf<String?>(null)
        private set

    /**
     * 当前会话是否置顶。
     *
     * 顶栏菜单要立刻显示"置顶 / 取消置顶"，所以必须有个可观察的值。
     * 它**不是第二真相**：每次订阅回流的实体都会覆盖它（见 [observeTitle]）。
     */
    var currentPinned by mutableStateOf(false)
        private set

    /**
     * 「新对话」的**待用标题** —— 用户在发出第一条消息之前就起好的名。
     *
     * 那一刻会话行还不存在，库里没有地方写；这个名字与草稿同一个处境（§35），
     * 一起记在 `drafts` 表的 [DraftEntity.NewConversation] 行里，进程被杀也不丢。
     * [ensureConversation] 建会话时一并用上。
     */
    private var pendingTitle: String? = null

    init {
        viewModelScope.launch {
            val stored = repository.findDraftTitle(DraftEntity.NewConversation)
            pendingTitle = stored
            if (currentConversationId == null && currentConversationTitle == null) {
                currentConversationTitle = stored
            }
        }
    }

    /**
     * 活跃路径的**修订号**：每当路径被整段重建（换版本 / 编辑 / 删除 / 分叉）就 +1。
     *
     * 界面用它来分辨"这次列表变化是**新内容**还是**换了条分支**" ——
     * 前者要跟到底部，后者要停在原处。用 `messages.size` 分辨不出来
     * （换版本时条数常常一样）。
     */
    var pathRevision by mutableStateOf(0)
        private set

    /**
     * 是否正在切换会话（已切走、新会话的内容还在读）。
     *
     * 界面用它决定何时做交叉溶解与"落到最新一条" —— 这两件事都必须
     * 等新内容就位再做，否则溶的是旧内容、滚的是旧列表。
     */
    var switching by mutableStateOf(false)
        private set

    // ── 上下文压缩（设计文档 §9.1.1③）────────────────────
    //
    // 核心约定：**只改变发给 API 的内容，绝不删除本地消息。**
    // 被压缩掉的原文在界面上照常显示、照常可编辑、照常导出。

    /** 当前会话生效的压缩摘要。null 表示未压缩。 */
    var compaction by mutableStateOf<CompactionEntity?>(null)
        private set

    var compacting by mutableStateOf(false)
        private set

    /** 压缩过程中的流式预览，让用户看到"它在写什么"。 */
    var compactPreview by mutableStateOf("")
        private set

    /**
     * 本次压缩覆盖到的最后一条消息。
     *
     * 首次压缩时 `compaction` 还是 null（摘要在生成中），分界线没有可挂的位置 ——
     * 这个值先顶上；压缩成功后被 `compaction.throughMessageId` 取代，
     * 失败或取消时清空。
     */
    private var pendingAnchorId by mutableStateOf<String?>(null)

    /**
     * 消息流里那条压缩分界线要显示的内容；null = 不显示。
     *
     * 只在这里读 `messages` 做一次定位 —— 界面拿到的是一份不可变快照，
     * 不会因为流式期间每条消息都在变而反复重排。
     */
    val compactionDivider: CompactionDividerState?
        get() {
            val running = compacting && pendingAnchorId != null
            val anchor = if (running) pendingAnchorId else compaction?.throughMessageId
            if (anchor == null) return null
            val index = messages.indexOfFirst { it.id == anchor }
            if (index < 0) return null
            return CompactionDividerState(
                anchorId = anchor,
                coveredCount = index + 1,
                usedTokens = contextStats.usedTokens,
                windowTokens = contextStats.windowTokens,
                savedTokens = contextStats.compactedTokenSaving,
                compacting = running,
            )
        }

    private var compactJob: Job? = null

    var presets by mutableStateOf<List<Preset>>(emptyList())
        private set

    var settings by mutableStateOf(
        ConversationSettings(
            model = ModelInfo(id = ""),
        ),
    )
        private set

    var isStreaming by mutableStateOf(false)
        private set

    var banner by mutableStateOf<String?>(null)
        private set

    /**
     * 「连接不稳定，正在自动重试 2/5」这类**进行中**的提示。
     *
     * 与 `banner`（错误/全局提示）分开：重试不是失败，这一轮还在跑。
     * 有内容进来或本轮结束时清空。
     */
    var retryHint by mutableStateOf<String?>(null)
        private set

    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set

    /**
     * 用户选择的朗读音色（空 = 系统默认）。
     *
     * 单独开一个状态而不是把整个 `StoredSettings` 暴露出去：界面只需要这一个值，
     * 而 `stored` 里带着 API Key 等敏感字段。设置里换音色 → 流回来 → 朗读立即生效。
     */
    var ttsVoiceName by mutableStateOf("")
        private set

    /**
     * DeepSeek **官方** TTS 的接入参数；null = 当前服务商不支持官方朗读。
     *
     * 只有「服务商 = DeepSeek 官方 + Base URL 指向官方域 + Key 已填」才非空 ——
     * 官方 `/v1/tools/tts` 只在官方域提供。界面据此：
     * - 设置页列不列 `Stella` / `Tide` 两个官方音色；
     * - 对话页显不显示朗读按钮（用户要求：不支持的 API 直接隐藏入口）。
     */
    var officialTts by mutableStateOf<DeepSeekTts.Config?>(null)
        private set

    /** 朗读通道（[com.simplechat.app.data.TtsChannel]）。 */
    var ttsChannel by mutableStateOf(com.simplechat.app.data.TtsChannel.AUTO)
        private set

    /** 自定义语音 API 的接入参数；null = 没配完整。 */
    var customTts by mutableStateOf<com.simplechat.app.net.CustomTts.Config?>(null)
        private set

    /** 回复结束后自动朗读。 */
    var autoReadAloud by mutableStateOf(false)
        private set

    /** 绘图 API 是否已配置（决定 `generate_image` 工具挂不挂）。 */
    var imageApi by mutableStateOf<com.simplechat.app.agent.ImageGen.Config?>(null)
        private set

    /**
     * 实际生效的协议通道。
     *
     * 由「配置的档位 + 端点」共同决定（见 `ApiStyle.resolve`）：
     * `AUTO` 时 DeepSeek 官方走扩展通道（能拿到搜索 / 引用 / 服务端标题），
     * 其余端点走 OpenAI 兼容。
     */
    var apiStyle by mutableStateOf(ApiStyle.ANTHROPIC)
        private set

    /**
     * 当前通道能不能联网搜索 —— 它决定**要不要显示**那个开关。
     *
     * 搜索走的是扩展通道的工具（`deepseek_web_search`），OpenAI 兼容通道没有等价入口。
     * 所以通道不支持时干脆不显示，而不是留一个按下去什么都不发生的开关。
     */
    val supportsWebSearch: Boolean get() = apiStyle == ApiStyle.ANTHROPIC

    /**
     * 思考内容是否在**正文开始时自动收起**（全局显示设置）。
     *
     * 语义定案见 `TODO.md`：开着 → 正文一出现就收成一行「已深度思考 · N 秒」；
     * 关掉 → 全文保持展开，想收自己点。**只影响定稿后的初态与自动收起**，
     * 流式期间的尾巴模式（只看最后几行）与它无关。
     */
    var autoCollapseThinking by mutableStateOf(true)

    /** 自动压缩开关（设置页「对话与记忆」）。关掉只是不自动触发，手动压仍在。 */
    var autoCompactEnabled by mutableStateOf(true)

    /**
     * 已经向服务端要过标题的会话。
     *
     * 与 [titleGeneratedFor] 同一个作用：**每个会话只试一次**。
     * 标题不值得为它反复烧 token，兜底标题本来就是能看的。
     */
    private var serverTitleRequestedFor: String? = null

    var refreshingModels by mutableStateOf(false)
        private set

    var modelHint by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null

    /** 这条 job 属于哪个会话 —— 切走后不能再回写 `isStreaming`。 */
    private var streamConversationId: String? = null

    /**
     * 流式轮次世代号，每次 [startStream] 自增。
     *
     * `conversationId` 挡不住**同一会话里的新旧两轮**：用户按停止、队列里的下一条
     * 立刻发出去时，两轮的会话 id 一模一样。旧协程的 `finally` 是取消后异步执行的，
     * 它会把新流的 `isStreaming`、`streamJob`、侧栏转圈一起抹掉 —— 界面表现为
     * "停止按钮按了之后新消息在写，但没有转圈、也停不下来"。
     * 所以回写共享状态前先比世代号：只有仍是当前这一轮才动。
     */
    private var streamEpoch = 0L

    /** 用户切走了、但还在后台跑的会话。 */
    private val backgroundRuns = mutableMapOf<String, Job>()

    /** 每条回复已经自动续跑过几次（防止"断流 → 续跑 → 又断"无限循环）。 */
    private val autoResumeCounts = mutableMapOf<String, Int>()

    /**
     * 等待发送的消息队列（按会话隔离）。
     *
     * 用 `SnapshotStateList`：界面直接读它渲染队列条，增删即时可见。
     * 只在主线程读写。
     */
    val sendQueue = mutableStateListOf<QueuedMessage>()

    /** 把排队的那条撤下来（不发了）。 */
    fun removeQueued(id: String) {
        sendQueue.removeAll { it.id == id }
    }

    /** 清空当前会话的排队消息。 */
    fun clearQueued() {
        val id = currentConversationId
        sendQueue.removeAll { it.conversationId == id }
    }

    /**
     * 一轮结束后，把这个会话排队的下一条发出去。
     *
     * 一次只发一条：`send()` 会重新置 `isStreaming`，剩下的等下一轮结束再轮。
     * 别的会话排的队不动 —— 切过去才轮到它们。
     */
    private fun drainQueue(conversationId: String?) {
        if (isStreaming) return
        val next = sendQueue.firstOrNull { it.conversationId == conversationId } ?: return
        sendQueue.remove(next)
        send(next.text, next.attachments)
    }

    /** 会话切换任务；`send()` 会等它完成，避免把消息插进半成品列表。 */
    private var switchJob: Job? = null

    /** 会话级设置（补充提示词 / 预设勾选）的防抖落库任务。 */
    private var settingsPersistJob: Job? = null

    /**
     * 会话标题的观察任务。
     *
     * 标题**只有库里的那一列是真相** —— 顶栏、抽屉、对话设置里的名称框
     * 都从它读。这里订阅当前会话那一行，三种改名方式（抽屉重命名 /
     * AI 起名 / 名称框）都从库流回来，不可能各说各话。
     *
     * 早先 `currentConversationTitle` 是个只在切会话时同步一次的内存副本：
     * 从抽屉改名后**顶栏停在旧标题上**，而且只有顶栏看得出来，
     * 越看越像"抽屉写错了"。
     */
    private var titleObserverJob: Job? = null

    /** 会话切换的世代号，用于丢弃过期的加载结果。 */
    private var switchGeneration = 0

    /**
     * 用户按下停止时，被停的是**哪一轮**（[streamEpoch]）。
     *
     * 不能用 `Boolean`：停止之后队列里的下一条会立刻开跑，`startStream` 把
     * 布尔重置为 false —— 此时上一轮的协程才刚收到取消，它一看"没停"，就把
     * 一条被用户砍掉的回复判成失败，甚至触发自动续跑。于是界面上"停了还在写"。
     * 用轮次号，旧轮永远认得出自己是被停的那一轮。
     */
    private var stoppedEpoch: Long = -1L

    /**
     * 被用户停掉的回复（按消息 id 记，可能不止一条）。
     *
     * 停止之后网络层可能还会吐最后几个 token（socket 已经被 cancel，但缓冲区里
     * 可能还有一整行）。那些内容不该再往气泡里灌 —— 用户按的是"停"，不是
     * "再写两个字给我"。写库同理：存的就是停下那一刻屏幕上看到的内容。
     *
     * 用集合而不是单个 id：停止后队列里的下一条会立刻开始，那一刻不能把上一条
     * 的封印一起抹掉。只有**明确对同一条回复重新发起**（继续生成）才解封。
     */
    private val stoppedReplyIds = mutableSetOf<String>()
    private var stored: StoredSettings = StoredSettings()

    /** 本地资料（侧边栏底部那行）。跟着 DataStore 走，改完立刻生效。 */
    var profileName by mutableStateOf("")
        private set
    var profileAvatar by mutableStateOf("")
        private set
    private var previousProviderKind: ProviderKind? = null

    /** 上一次见到的端点，用来判断"模型列表该不该作废 / 换一份"。 */
    private var previousBaseUrl: String? = null

    /** 上一次见到的远端模型列表（落库的那份），同理只用来比较。 */
    private var previousModelIds: List<String> = emptyList()

    /**
     * 压缩参数。默认值与 `StoredSettings` 一致 —— 这里只是界面层的缓存，
     * 用户改了设置下一轮就生效，不用重启。
     *
     * 阈值与「保留条数」的含义已按 Operit 改掉：
     * 不再有"保留最近 N 条"，摘要之后的**全部**消息都会照常发送；
     * 触发只看 token 占用与「自上次摘要后的 user 消息数」两个阈值。
     */
    private var compactRatio: Float = ContextSummary.TokenThreshold
    private var compactByCount: Boolean = true
    private var compactCount: Int = ContextSummary.MessageCountThreshold
    private var compactRules: String = ""

    /**
     * 大窗口豁免：窗口 ≥ 512K 时不按比例、也不按条数自动压。
     *
     * 1M 上下文的模型本来就装得下，把它当 128K 的模型压，损失的是摘要质量
     * （四节摘要压不出 70 万 token 的细节），换来的是"上下文一直很干净"。
     * 这笔账不划算，所以默认开着。
     */
    private var compactLargeWindowExempt: Boolean = true

    /** 是否从窗口里预留本次请求的输出 token。关掉 = 按整个窗口算。 */
    private var compactReserveOutput: Boolean = true

    /** 长会话是否分段总结再合并。 */
    private var compactChunked: Boolean = true

    /**
     * 上一次压缩之后的占用下界。
     *
     * 迟滞用：只有占用比它又高出 15% 才允许再自动压一次，
     * 否则一次压不动的长会话会被一轮一轮地重压。
     */
    private var compactionFloorTokens: Int = 0
    private var proactiveChance: Int = 25
    private var proactiveIdleMinutes: Int = 20

    /**
     * 主动开口的"系统提示"。
     *
     * 只在一次请求里有效：`buildRequestMessages()` 末尾把它当成一条 user 消息
     * 追加进去（**不落库**），请求发完就清空。这样界面上不会出现一条假的用户发言，
     * 而模型确实收到了"现在轮到你主动说话"的信号。
     */
    private var pendingProactiveNudge: String? = null

    /**
     * Agent 模式。开着时这一轮走 [AgentRunner]（多轮 + 工具），关着走普通流式。
     *
     * 做成 `mutableStateOf` 而不是普通字段：输入栏的工具条要立刻变色响应。
     */
    var agentMode by mutableStateOf(false)
        private set

    /**
     * 工具审批预设。
     *
     * 和 [agentMode] 一样是界面要立刻响应的状态；真正生效是从**下一次请求**开始
     * （`run()` 时读一次），这样中途切换不会打断正在等的那个审批。
     */
    var approvalMode by mutableStateOf(ApprovalMode.NORMAL)
        private set

    /** 正在等用户点头的那一次调用。null = 没有待批。 */
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null)
        private set

    /**
     * 挂起点。同一时刻最多一个 —— 审批只发生在串行执行的路径上
     * （只读调用会并发，但它们本来就不需要批准）。
     */
    private var approvalContinuation: CancellableContinuation<ApprovalDecision>? = null

    /**
     * 审批门：把请求挂到界面上，等用户点完再放行。
     *
     * 用 `suspendCancellableCoroutine` 而不是 `CompletableDeferred`：用户中途点"停止"时
     * `streamJob` 被取消，这里会同步把 `pendingApproval` 清掉 —— 否则一张
     * "是否允许"的卡片会永远挂在一条已经结束的回复底下，点了也没人接。
     */
    private val approvalGate = ApprovalGate { request ->
        suspendCancellableCoroutine { continuation ->
            pendingApproval = request
            approvalContinuation = continuation
            continuation.invokeOnCancellation {
                pendingApproval = null
                approvalContinuation = null
            }
        }
    }

    /** 用户点了「允许 / 总是允许 / 拒绝」。 */
    fun resolveApproval(decision: ApprovalDecision) {
        val continuation = approvalContinuation ?: return
        approvalContinuation = null
        pendingApproval = null
        continuation.resume(decision)
    }

    /**
     * 跨层请求：抽屉里点「工具审批」「对话设置」时，目标控件在对话页里。
     *
     * 抽屉和对话页是两层（ModalDrawerSheet 罩在 ChatScreen 上），抽屉自己拿不到
     * 对话页的局部状态。用一条「请求 → 消费 → 清空」的单向管道：抽屉只负责投递，
     * 对话页关上抽屉后取走执行。不引入导航参数，也不把面板状态搬到 VM 里 ——
     * 面板本来就是页面的私事，只有这两条跨层指令需要过一下 VM。
     */
    var requestedPanel by mutableStateOf<InputPanelState?>(null)
        private set

    /** 自增计数：每点一次 +1，对话页看到变化就开弹窗。用计数而不是布尔，
     *  是为了「连续点两次」也能各自生效，不用先复位。 */
    var approvalDialogTick by mutableStateOf(0)
        private set

    fun requestPanel(target: InputPanelState) {
        requestedPanel = target
    }

    fun consumeRequestedPanel(): InputPanelState? {
        val target = requestedPanel
        requestedPanel = null
        return target
    }

    fun requestApprovalDialog() {
        approvalDialogTick += 1
    }

    /** 切换审批预设。YOLO 的二次确认在界面上做，这里只落库。 */
    fun pickApprovalMode(mode: ApprovalMode) {
        approvalMode = mode
        viewModelScope.launch { settingsStore.setApprovalMode(mode.id) }
    }

    /** 本轮已经发出的工具调用（UI 直接用，不再从事件流里推）。 */
    /**
     * 本地工具集。
     *
     * 联网搜索（`web_search` / `web_open`）**始终挂载**，不再受技能开关控制。
     * 技能开关只决定要不要把那段"怎么搜、怎么答"的说明书注入系统提示词 ——
     * 工具本身是基础能力：模型不知道有这个工具，就会拿训练数据里的旧印象回答
     * 版本号、价格、报错解法，而那些恰恰是最容易过期的三样东西。
     *
     * 这里每次现算而不是 `by lazy`：技能页的开关随时会变，而这个列表的构造
     * 只是一个 listOf，不值得为它做缓存失效。
     */
    private fun localTools(): List<com.simplechat.app.agent.AgentTool> {
        val client = ChatApi.defaultClient()
        /*
         * 没绑工作区 = 没有文件系统，也没有 shell。
         *
         * 这不是"少给几个工具"，是**不能给**：普通对话里模型手里握着
         * `run_shell`，就会真的去跑 `ls`、去 `mkdir`，然后在 App 私有目录里
         * 留下一堆用户看不见的文件，还把"我改了你的文件"当成事实说出来。
         * 早先这里恒挂全套，用户选了「不绑定工作区」照样能执行命令 —— 那是漏洞。
         *
         * 绑了才挂：内置工作区、自选文件夹、云端环境都算绑。
         */
        val bound = Workspace.current.value.kind != WorkspaceKind.NONE
        val base = if (bound) BuiltinTools.all(client) else emptyList()
        // Ubuntu 没装就不挂 linux_shell / install_packages：挂了也只能返回
        // "还没装"，白占工具表的位置，还让模型多试一次
        val linux = if (bound && LinuxTools.available()) LinuxTools.all() else emptyList()
        /*
         * 联网搜索（`web_search` / `web_open`）**只在「内置浏览器」技能启用时**挂上去。
         * 关掉之后模型连这两个名字都看不到 —— 不是"看得见但调用失败"，那种设计会让它
         * 反复重试一个已经没有的能力。
         *
         * 这里每次现算而不是 `by lazy`：技能页的开关随时会变，而这个列表的构造
         * 只是一个 listOf，不值得为它做缓存失效。
         */
        val web = if (BuiltinSkills.enabled(BuiltinSkills.Browser)) WebTools.all(client) else emptyList()
        /*
         * 绘图工具只在**配好绘图 API** 时挂上。
         *
         * 没配就不给，模型看不到这个能力，用户让它画图时它会直说做不到。
         * 反过来挂一个必然失败的工具，它会连着试三四次，把一轮对话拖成一场错误展。
         *
         * 不再要求"有工作区"：生成结果落在 App 私有目录，普通对话也应该能画图 ——
         * 早先卡在 `rootOrNull()` 上，没绑工作区就整个能力消失，而用户根本
         * 不知道这两件事有什么关联。
         */
        val image = imageApi?.let { listOf(com.simplechat.app.agent.ImageGen.tool(it)) } ?: emptyList()
        // 回滚工具本地也要挂：用户让它"退回去"时，它得能自己动手
        // 回滚只对文件有意义，没绑工作区就没有可回滚的东西
        val rewind = if (bound) listOf<com.simplechat.app.agent.AgentTool>(RewindTool) else emptyList()
        return base + linux + web + image + rewind
    }

    /** 流式 checkpoint 的节流计时。 */
    private var lastCheckpointAt = 0L

    private val providerKind: ProviderKind
        get() = runCatching { ProviderKind.valueOf(stored.providerId) }
            .getOrDefault(ProviderKind.DEEPSEEK)

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { value ->
                stored = value
                applyStored(value)
            }
        }
        viewModelScope.launch {
            repository.observePresets().collect { presets = it }
        }
        /*
         * 云端绑定变了就补一笔分类。
         *
         * 侧栏按工作区分组，而用户完全可能"先聊两句，再绑一台云机器"。
         * 只在发消息时落库的话，这条对话要等到下次发言才会挪进「云端工作区」——
         * 中间那段时间侧栏在说谎。
         */
        viewModelScope.launch {
            var lastBound: String? = null
            CloudEnvs.binding.collect { map ->
                val id = currentConversationId ?: return@collect
                val envId = map[id]
                if (envId == lastBound) return@collect
                lastBound = envId
                stampWorkspace(id)
            }
        }

        /*
         * 上下文统计：消息 / 设置 / 预设 / 压缩任何一个变了就重算，
         * 但**防抖 + 后台线程**（原因见 contextStats 的说明）。
         *
         * 用 `collectLatest` + `delay` 而不是 `debounce`：后者是 FlowPreview API，
         * 而这里要的语义就是"新输入来了就取消上一次等待"，两行代码说清楚。
         *
         * ⚠️ 这个 init 块**必须声明在 `settings` 等状态之后**：Kotlin 按声明顺序
         * 初始化属性，而 `Main.immediate` 的 `launch` 会当场执行到第一个挂起点 ——
         * 放在前面会读到还没赋值的委托（真机表现为启动即 NPE）。
         */
        viewModelScope.launch {
            snapshotFlow {
                StatsInput(messages.toList(), settings, presets, compaction)
            }
                .collectLatest { input ->
                    delay(ContextStatsDebounceMs)
                    val stats = withContext(Dispatchers.Default) { computeContextStats(input) }
                    contextStats = stats
                }
        }

        /*
         * 启动时**不恢复上次打开的会话**，一律停在「新对话」。
         *
         * 早先是恢复的，代价是：每次打开 App 都直接落进上一次那段对话里，
         * 想开新的得先点一下「新建对话」。而这个 App 的日常用法是
         * "想起来一句就写" —— 停在空白页比落在半篇文章里更贴近它。
         *
         * 连带把 `lastConversationId` 这条偏好整个删了：没人读的写
         * 就是死数据（同样的判断见 §4.2 与 §17.6）。
         */
    }

    private fun applyStored(value: StoredSettings) {
        val kind = runCatching { ProviderKind.valueOf(value.providerId) }
            .getOrDefault(ProviderKind.DEEPSEEK)
        /*
         * 模型列表有两个来源，都要跟：**端点换了**（服务商 / Base URL）要换一份，
         * **拉取结果变了**（设置页测试连接、或本页刷新）要跟着更新。
         *
         * 用 [ModelCatalog.pickerModels] 而不是 `knownModels`：自定义端点的内置
         * 目录是空的，早先它会把拉到的模型列表整个抹掉 —— 于是"拉取成功"之后
         * 一进设置、或重启一次，列表就空了。
         */
        val endpointChanged = kind != previousProviderKind || value.baseUrl != previousBaseUrl
        if (endpointChanged || value.modelIds != previousModelIds) {
            previousProviderKind = kind
            previousBaseUrl = value.baseUrl
            previousModelIds = value.modelIds
            models = ModelCatalog.pickerModels(kind, value.modelIds)
            // 端点换了，上一轮的"已拉取 N 个"不再成立；只是列表更新则保留提示
            if (endpointChanged) modelHint = null
        }
        apiStyle = ApiStyle.resolve(kind, value.apiStyleId, value.baseUrl)
        autoCollapseThinking = value.autoCollapseThinking
        ttsVoiceName = value.ttsVoiceName
        officialTts = if (DeepSeekTts.isOfficialTtsVoiceAvailable(value.providerId, value.apiKey)) {
            DeepSeekTts.Config(value.apiKey)
        } else {
            null
        }
        ttsChannel = value.ttsChannelId
        customTts = if (value.hasCustomTts) {
            com.simplechat.app.net.CustomTts.Config(
                url = value.ttsApiUrl,
                apiKey = value.ttsApiKey,
                model = value.ttsApiModel,
                voice = value.ttsApiVoice,
                format = value.ttsApiFormat,
                speed = value.ttsApiSpeed,
                headers = value.ttsApiHeaders,
            )
        } else {
            null
        }
        autoReadAloud = value.autoReadAloud
        imageApi = if (value.hasImageApi) {
            com.simplechat.app.agent.ImageGen.Config(
                url = value.imageApiUrl,
                apiKey = value.imageApiKey,
                model = value.imageApiModel,
                size = value.imageApiSize,
            )
        } else {
            null
        }
        // 上下文窗口：用户档位 > 白名单 > 能力表。白名单同时吃下带渠道前缀的 id
        val baseModel = ModelCatalog.find(kind, value.modelId)
        val window = ModelResolver.contextWindowFor(
            base = baseModel,
            presetId = value.contextPresetId,
            custom = value.contextWindowCustom,
        )
        autoCompactEnabled = value.autoCompact
        /*
         * 阈值与参数直接来自设置，不再按模型窗口自动推导。
         *
         * Operit 的触发条件只有两个固定值：token 0.70、自上次摘要后 16 条
         * user 消息。上一版按窗口大小算出一组比例/条数，看起来更"聪明"，
         * 代价是同一个会话换个模型就从"刚压过"变成"又要压"，行为不可预期。
         */
        compactRatio = value.compactRatio
        compactByCount = value.compactByCount
        compactCount = value.compactCount
        compactRules = value.compactRules
        compactLargeWindowExempt = value.compactLargeWindowExempt
        compactReserveOutput = value.compactReserveOutput
        compactChunked = value.compactChunked
        proactiveChance = value.proactiveChance
        proactiveIdleMinutes = value.proactiveIdleMinutes
        /*
         * Agent 模式**恒为开**。
         *
         * 设置里那一项已经删了，如果这里还去读 prefs，那些曾经关过一次的用户
         * 会永远停在"所有工具都不挂"的状态 —— 而界面上再也没有地方能改回来。
         * 输入栏那颗胶囊仍然能临时关（只影响本次运行），但冷启动一定回到开。
         */
        agentMode = true
        approvalMode = ApprovalMode.fromId(value.approvalModeId)
        profileName = value.userName
        profileAvatar = value.userAvatar
        settings = settings.copy(
            model = baseModel.copy(contextWindow = window),
            persona = value.userPersona,
            reasoningLevel = runCatching { ReasoningLevel.valueOf(value.reasoningLevelId) }
                .getOrDefault(ReasoningLevel.OFF),
            temperature = value.temperature,
            topP = value.topP,
            fast = value.fast,
            // 最大输出：新设置优先，旧的 maxTokens 兜底
            maxTokens = (if (value.maxOutputTokens > 0) value.maxOutputTokens else value.maxTokens)
                .takeIf { it > 0 },
        )
    }

    // ── 会话切换 ──────────────────────────────────────────

    /**
     * 订阅某个会话的标题。
     *
     * 这是标题**唯一的写入者**：三种改名方式都只写库，界面侧一律由这里回流。
     * 切换会话时重订（旧的取消），所以回调里再核对一次 `currentConversationId`
     * 防止快切时旧会话的标题盖上来。
     */
    private fun observeTitle(conversationId: String) {
        titleObserverJob?.cancel()
        titleObserverJob = viewModelScope.launch {
            repository.observeConversation(conversationId).collect { entity ->
                if (currentConversationId == conversationId) {
                    currentConversationTitle = entity?.title
                    currentPinned = entity?.pinned == true
                }
            }
        }
    }

    /**
     * 重命名当前会话（对话设置里的名称框用）。
     *
     * 只写库，**不在这里改 `currentConversationTitle`** —— 写了会变成
     * 第二个真相，而 [observeTitle] 那边马上会把库里的值送回来。
     * 名称框自身有防抖，这里按"每次调用都落库"理解即可。
     *
     * ⚠️ **会话还没建时例外**：那一刻库里没有行可写，内存里这份就是唯一的真相
     * （§35 同一个处境）。名字先当**待用标题**记下并落库，[ensureConversation]
     * 建会话时一并用上 —— 于是"发第一条消息之前起好名"就成立了。
     */
    fun renameCurrentConversation(title: String) {
        val conversationId = currentConversationId
        if (conversationId == null) {
            val clean = title.trim().takeIf { it.isNotBlank() }
            pendingTitle = clean
            currentConversationTitle = clean
            viewModelScope.launch {
                repository.saveDraftTitle(DraftEntity.NewConversation, clean)
            }
            return
        }
        viewModelScope.launch { repository.renameConversation(conversationId, title) }
    }

    /**
     * 切换会话。
     *
     * **id 与列表清空是同步做的，只有"读回来填入"这一步是异步的** ——
     * 早先整个切换都在协程里、且跨了两次挂起点，于是存在两个致命窗口：
     *
     * - `currentConversationId` 已改成新会话、但列表还是旧的 → 新消息被追加进旧列表
     * - 列表已清空、但 `addAll` 还没跑 → 新消息先入列，历史被追加到它**后面**，顺序颠倒
     *
     * 另外用 [switchGeneration] 丢弃过期结果：快速连点两个会话时，
     * 先发的那次加载不能覆盖后发的。
     */
    /**
     * 置顶 / 取消置顶。
     *
     * 先改内存再写库：置顶是个瞬间动作，等一次数据库往返再变色，手感上是"点了没反应"。
     * 万一写失败，下一次订阅回流会把真实值送回来 —— 不会长期错。
     */
    fun togglePin() {
        val id = currentConversationId ?: return
        val next = !currentPinned
        currentPinned = next
        viewModelScope.launch { repository.setPinned(id, next) }
    }

    /**
     * 删除当前会话。
     *
     * 删完直接回到"新对话"态：留着一个已经被删掉的 id，用户下一条消息会往
     * 不存在的会话里写（表现是消息发出去没反应）。
     */
    fun deleteCurrentConversation() {
        val id = currentConversationId
        if (id == null) {
            newConversation()
            return
        }
        viewModelScope.launch {
            repository.deleteConversations(listOf(id))
            newConversation()
        }
    }

    fun openConversation(id: String) {
        if (id == currentConversationId) return
        // 切会话 ≠ 停止：手上这一轮转到后台继续跑（见 detachRun）
        detachRun()
        switchJob?.cancel()

        val generation = ++switchGeneration
        currentConversationId = id
        // 标题立刻开始观察；下面那句清空只是防止旧标题残留一帧
        observeTitle(id)
        switching = true
        /*
         * 压缩是**会话内**的操作：切走之后它的 `compaction = entity` 会落到
         * 新会话头上（摘要对不上，分界线位置也找不到）。切会话一律先取消，
         * 与 newConversation 的处理保持一致。
         */
        cancelCompaction()
        compaction = null
        // 摘要没了，迟滞的下界也跟着失效：换会话不该继承上一段的"已压到多少"
        compactionFloorTokens = 0
        currentConversationTitle = null

        /*
         * 这条会话是不是**正在后台跑**（上一轮 detachRun 移交过来的）。
         *
         * 是的话下面两件事都不能按"冷打开"做：
         *  - `resolveDanglingStreams` 会把那条**还活着**的回复改写成 STOPPED，
         *    后台任务随后再 finishMessage，状态就乱了；
         *  - 读完库之后必须把 job 接回来，否则界面上它永远不转圈，
         *    而内容只有等下一次 checkpoint 才出现 —— 看起来就是"切回来一片空白"。
         */
        val resumeJob = backgroundRuns[id]

        switchJob = viewModelScope.launch {

            // 先清扫上次被中断的流式消息，否则它会以 STREAMING 状态卡在界面上。
            // 正在后台跑的那一条不算 dangling，跳过。
            if (resumeJob == null) repository.resolveDanglingStreams(id)

            val loaded = repository.loadActivePath(id)

            val loadedCompaction = repository.getCompactions(id).lastOrNull()
            val conversation = repository.findConversation(id)

            if (generation != switchGeneration) return@launch

            /*
             * ★ **先预热，再交给界面。**
             *
             * Markdown 解析是同步的（见 `MarkdownText` 的 `MarkdownStateCache`），
             * 一条长消息第一次组合会在**主线程**逐块解析 —— 五百条消息的会话里，
             * 这一步能吃掉几十毫秒，正好压在"侧边栏收起"和转圈动画上：
             * 动画自己也会卡，转圈就白转了。
             *
             * 所以把"打开就能看到的那几条"（贴底打开 = 尾部）在**后台**先解析好，
             * 再 publish 给界面。列表真正组合时只命中缓存，主线程保持空闲，
             * 转圈因此是顺的。
             */
            withContext(Dispatchers.Default) {
                loaded.asReversed()
                    .take(WarmTailMessages)
                    // 超长消息不预热：解析它本身就要几百毫秒，预热只会把后台线程占满
                    .filter { it.content.length <= WarmTailMaxChars }
                    .forEach { message -> warmMarkdownCache(message.content) }
            }


            if (generation != switchGeneration) return@launch

            /*
             * ⚠️ **列表是"先读回来、再整份替换"，不是"先清空"**。
             *
             * 早先是 `messages.clear()` 同步做、`addAll` 异步做 ——
             * 中间那一帧列表是空的，界面会闪一下空态（"一起创作吧"），
             * 快速连点会话时尤其明显。
             *
             * 代价是这一小段窗口里列表还是上一个会话的内容（几十毫秒）。
             * 用户在这个窗口里发消息基本不可能；真发生了也只会体现在画面上，
             * 数据是写进新会话的，下一次 reloadPath 就对齐了。
             */
            messages.clear()
            messages.addAll(loaded)

            compaction = loadedCompaction
            if (conversation != null) {
                // 顺手填一次，避免标题晚一帧才出来；**真相仍是 observeTitle 那条流**
                currentConversationTitle = conversation.title
                /*
                 * 预设属于**会话**：切过来必须换一份。
                 *
                 * 不读的话，在 A 对话里开的"冷叙事"会一路跟到 B 对话 ——
                 * 而 B 的「⋯」面板上什么都没勾，用户根本查不出语气为什么变了。
                 */
                val presetIds = repository.getEnabledPresetIds(conversation.id)
                settings = settings.copy(
                    mode = conversation.mode.toConversationModeSafe(),
                    systemPrompt = conversation.systemPrompt.orEmpty(),
                    enabledPresetIds = presetIds,
                )
            }
            /*
             * 切回一条还在后台跑的会话：把这一轮**接回来**。
             *
             * 不这么做的话，界面上它既不转圈也不更新（内容只在 checkpoint 时
             * 落库），用户看到的就是"切回来之后这条回复不动 / 空白"。
             */
            if (resumeJob != null && generation == switchGeneration) {
                backgroundRuns.remove(id)
                streamJob = resumeJob
                streamConversationId = id
                isStreaming = true
                // 不在这里补登记：那一轮的登记从 start 起就一直挂着，
                // 它自己结束时按轮次号摘掉。重登只会多出一格没人摘（见 ActiveRuns）。
            }
            switching = false
            // 切到一条没在跑的会话：它排着队的消息现在可以发了
            if (resumeJob == null && generation == switchGeneration) drainQueue(id)
        }
    }

    /**
     * 「新建对话」落库的那个协程。
     *
     * 新建要**立刻**在侧边栏出现一条记录（真实存在、可点、可删的空草稿），
     * 而写库是异步的。用户完全可能点完新建马上打字发送 —— 那时
     * [ensureConversation] 必须先等这次落库结束，否则会再建一条，侧栏里
     * 凭空多出两条「新对话」。
     */

    fun newConversation() {
        /*
         * 开新话题 ≠ 停止旧话题。
         *
         * 早先这里是 `stop()`，于是"新建对话"会把上一段正在生成的回答直接砍断。
         * 多任务并行才是要的语义：旧的那一轮移交后台继续跑，侧栏上它还在转圈，
         * 写完照常落库；新话题从空白开始，两边互不占。
         */
        detachRun()
        cancelCompaction()
        switchJob?.cancel()
        titleObserverJob?.cancel()
        currentConversationId = null
        currentPinned = false
        // 「新对话」的待用标题还在（用户可能先起好名再开始聊）
        currentConversationTitle = pendingTitle
        compaction = null
        compactionFloorTokens = 0
        messages.clear()
        banner = null
        switching = false
        // 新话题 = 新的一套开关：上一条对话勾的预设不该跟着过来
        settings = settings.copy(systemPrompt = "", enabledPresetIds = emptyList())
    }

    // ── 设置 ──────────────────────────────────────────────

    fun updateSettings(next: ConversationSettings) {
        val previous = settings
        settings = next
        viewModelScope.launch {
            if (next.model.id != previous.model.id) settingsStore.setModel(next.model.id)
            if (next.reasoningLevel != previous.reasoningLevel) {
                settingsStore.setReasoningLevel(next.reasoningLevel.name)
            }
            if (next.temperature != previous.temperature) settingsStore.setTemperature(next.temperature)
            if (next.topP != previous.topP) settingsStore.setTopP(next.topP)
            if (next.fast != previous.fast) settingsStore.setFast(next.fast)
            if (next.maxTokens != previous.maxTokens) settingsStore.setMaxTokens(next.maxTokens ?: 0)
        }

        /*
         * 会话级的两项 —— **补充提示词**与**预设勾选** —— 都写回这个会话。
         *
         * 提示词是逐字输入的，不能每敲一个字写一次库；预设是点一下就变的。
         * 两种节奏共用一个防抖：停手 400ms 后把两项一起同步成当前状态。
         * 只写其中一项会丢更新（先打字、400ms 内又勾预设 → 前一个 job
         * 被取消，提示词那次写入就没了）。
         *
         * 值在**进入协程前**就取好：中途切了会话的话，`settings` 已经变成
         * 另一个会话的内容，那时再读就会把 A 的设置写进 B。
         */
        val conversationId = currentConversationId
        val promptChanged = next.systemPrompt != previous.systemPrompt
        val presetsChanged = next.enabledPresetIds != previous.enabledPresetIds
        if (conversationId != null && (promptChanged || presetsChanged)) {
            val prompt = next.systemPrompt
            val presetIds = next.enabledPresetIds
            settingsPersistJob?.cancel()
            settingsPersistJob = viewModelScope.launch {
                delay(SETTINGS_PERSIST_DEBOUNCE_MS)
                repository.setConversationSystemPrompt(conversationId, prompt)
                repository.setConversationPresets(conversationId, presetIds)
            }
        }
    }

    fun dismissBanner() {
        banner = null
    }

    fun refreshModels() {
        if (refreshingModels) return
        refreshingModels = true
        modelHint = null
        viewModelScope.launch {
            val result = chatApi.fetchModels(
                baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                apiKey = stored.apiKey,
            )
            result.onSuccess { ids ->
                models = ModelCatalog.pickerModels(providerKind, ids)
                modelHint = Res.get(R.string.models_fetched, ids.size)
            }
            result.onFailure { error ->
                modelHint = Res.get(R.string.models_fetch_failed, error.userMessage()) +
                    if (!stored.hasKey && providerKind.requiresApiKey) {
                        Res.get(R.string.models_fetch_needs_key)
                    } else {
                        ""
                    }
            }
            // 落库：设置页的「模型」选择器读的是这份，重启后也还在
            result.getOrNull()?.let { settingsStore.setModelIds(it) }
            refreshingModels = false
        }
    }

    // ── 发送 ──────────────────────────────────────────────

    fun send(prompt: String, attachments: List<Attachment> = emptyList()) {
        val text = prompt.trim()
        // 允许「只有附件、没有文字」—— 丢一张图进来说"看看这个"是最自然的用法
        if (text.isEmpty() && attachments.isEmpty()) return

        /*
         * 正在生成 = **排队**，不是丢弃。
         *
         * 早先这里直接 return：用户打了一大段，点发送，什么都没发生。
         * 现在存进队列，当前这一轮说完立刻按序发出；界面上能看到排着几条、
         * 也能把某一条撤下来。
         */
        if (isStreaming) {
            sendQueue += QueuedMessage(
                conversationId = currentConversationId,
                text = text,
                attachments = attachments,
            )
            return
        }

        if (!stored.hasKey && providerKind.requiresApiKey) {
            banner = Res.get(R.string.error_no_api_key)
            return
        }

        /*
         * 还没选模型就发：自定义服务商的 `defaultModel` 是空串，而请求里的
         * `model` 字段一旦为空，服务端只会回一句看不懂的 400。在这里拦下来
         * 并直接说"去哪儿选"。
         */
        if (stored.modelId.isBlank()) {
            banner = Res.get(R.string.error_no_model)
            return
        }

        banner = null

        viewModelScope.launch {
            // 会话还在切换 → 等它读完再发，否则消息会被插进半成品列表（顺序颠倒）
            switchJob?.join()

            val conversationId = ensureConversation()

            /*
             * 发消息这一刻是"这段对话在哪儿"最准的采样点。
             *
             * 在建会话时写一次是不够的：用户完全可以聊到一半才去绑工作区，
             * 也可以换掉。挂在发送上，历史栏的分组就永远跟着最后一次实际情况走。
             * 云端优先 —— 它比全局绑定更具体（同一台手机上，一个对话可以在云上）。
             */
            stampWorkspace(conversationId)

            val user = repository.appendUserMessage(conversationId, text, attachments)
            // 首条用户消息会触发库里自动命名，顶栏同步一次
            currentConversationTitle = repository.findConversation(conversationId)?.title
            messages += user

            // 回复挂在**刚发的这条**下面
            val assistantId = repository.beginAssistantMessage(
                conversationId = conversationId,
                parentId = user.id,
                // 记下这条是谁写的 —— 事后换模型时，历史才分得清
                model = settings.model.id,
            )
            messages += UiMessage(
                id = assistantId,
                role = MessageRole.ASSISTANT,
                content = "",
                reasoning = "",
                status = MessageStatus.STREAMING,
                parentId = user.id,
                /*
                 * 这条内存对象是**手工构造**的，不走 `MessageEntity.toUi()`，
                 * 所以字段要一个个搬 —— 漏掉一个，它就会在整段流式期间缺失。
                 * （`model` 就漏过一次：库里写了、界面上看不到。）
                 *
                 * 重新生成那条路径没这个问题：它走 `reloadPath` 从库读回来。
                 */
                model = settings.model.id,
            )

            // 快满了就先整理旧对话（后台进行，不挡这一轮）
            autoCompactIfNeeded()

            /*
             * `@` 引用：先解析，再用它决定这一轮走不走 Agent。
             *
             * 用户 @ 了技能 / MCP 就是"这活儿要工具"，哪怕总开关是关的 ——
             * 让他为了用一次工具先去设置里打开开关，是把入口藏起来。
             */
            val mentions = Mentions.parse(text)
            mentions.filter { it.kind == MentionKind.MCP }.forEach { ref ->
                McpStore.servers.value.firstOrNull { it.id == ref.key && it.enabled }?.let { server ->
                    // 点名了就连上：不连的话那族工具根本不存在，@ 就成了摆设。
                    // 给 5 秒上限 —— 连不上也要照常发出去，不能为了一次握手卡住整条消息。
                    withTimeoutOrNull(5_000) { McpStore.connect(server, ChatApi.defaultClient()) }
                }
            }

            if (agentMode || mentions.isNotEmpty()) {
                startAgentStream(conversationId, assistantId, mentions)
            } else {
                startStream(conversationId, assistantId)
            }
        }
    }

    /**
     * 取当前会话；**已被删除就当作新对话**。
     *
     * 用户在抽屉里删掉正在看的会话时，`currentConversationId` 会短暂指向
     * 一个不存在的 id —— 此时直接往它下面插消息会撞外键约束。
     * 这里顺手把内存状态清干净，界面也就自然回到空态。
     */
    /** 把当前工作区（或这段对话绑的云环境）写进会话行。 */
    private fun stampWorkspace(conversationId: String) {
        val cloud = CloudEnvs.envOf(conversationId)
        val info = Workspace.current.value
        val kind = when {
            cloud != null -> "CLOUD"
            info.kind == WorkspaceKind.NONE -> "NONE"
            else -> info.kind.name
        }
        val label = when {
            cloud != null -> cloud.name
            info.kind == WorkspaceKind.NONE -> ""
            else -> info.label
        }
        viewModelScope.launch {
            repository.setConversationWorkspace(conversationId, kind, label)
        }
    }

    private suspend fun ensureConversation(): String {
        val existing = currentConversationId
        if (existing != null && repository.findConversation(existing) != null) return existing

        messages.clear()
        compaction = null
        compactionFloorTokens = 0
        val id = repository.createConversation(
            mode = settings.mode,
            provider = providerKind.name,
            model = settings.model.id,
            systemPrompt = settings.systemPrompt,
            enabledPresetIds = settings.enabledPresetIds,
            // 用户在发第一条消息之前就起好了名 → 直接用它，不再让 AI 起
            title = pendingTitle ?: Res.get(R.string.conversation_default_title),
        )
        if (pendingTitle != null) {
            // 名字是用户起的，`maybeGenerateTitle` 不该覆盖它
            titleGeneratedFor = id
            pendingTitle = null
            viewModelScope.launch {
                repository.saveDraftTitle(DraftEntity.NewConversation, null)
            }
        }
        currentConversationId = id
        /*
         * 待定的云端绑定落到这个新会话上。
         *
         * 用户是完全可以在发第一条消息之前就选好"这条对话在云上干"的 ——
         * 那次选择在会话行还不存在时记在 [CloudEnvs.setPending] 里，这里兑现。
         */
        CloudEnvs.applyPending(id)
        // 新会话刚建出来，标题也要开始观察（首条消息会触发库里自动命名）
        observeTitle(id)
        currentConversationTitle = repository.findConversation(id)?.title
        return id
    }

    /**
     * 从库里重新读一遍活跃路径。
     *
     * 树的增删改一律走这里，而不是在内存列表上原地修补 ——
     * 切换版本会**整段替换下游**（旧分支的子树让位给新分支的），
     * 原地修补要处理的组合太多，读一遍反而又快又不会错。
     */
    private suspend fun reloadPath(conversationId: String) {
        val path = repository.loadActivePath(conversationId)
        messages.clear()
        messages.addAll(path)
        pathRevision++
    }

    // ── 上下文压缩 ────────────────────────────────────────

    /**
     * 压缩上下文：把**较早**的对话总结成一份**自包含的摘要**。
     *
     * 整套机制照搬 Operit（`AIMessageManager.summarizeMemory` +
     * `ConversationService.generateSummaryFromPromptTurns`）：
     *
     * - 摘要是**唯一的历史参考**：生成它的时候把上一份摘要一起喂进去，
     *   产出的新摘要整段取代旧的。所以不会出现"摘要的摘要"逐轮衰减。
     * - 输入是**真实的对话轮次**（system 提示 + 原样 user/assistant + 一条
     *   "请按照要求总结"的 user 指令），不是把几十条消息拼成一段大 transcript。
     *   模型能看到谁在什么时候说了什么，时序不会糊。
     * - 摘要之后的消息**全部照常发送**。没有"保留最近 N 条"这种参数 ——
     *   保留区是摘要的盲区：它既不进摘要，又占着窗口，两头不讨好。
     * - 末尾附一份**对话回顾**（每条消息压成一行），保住原话的语感。
     *
     * 本地消息一条都不删：压缩只改变 `buildRequestMessages()` 从哪开始取。
     *
     * ### 这一版修掉的两件事
     *
     * **一、总结的是哪一段。**
     *
     * 旧版从**最早**的一端丢弃消息、把剩下的**最近**那段送去总结，
     * 却把 `throughMessageId` 记成最后一条消息 —— 也就是宣称"摘要已经覆盖
     * 全部历史"。结果最早的那批消息（往往正是最初的设定与约束）
     * **既没进摘要、也没被发出去**，就这么凭空消失了。
     *
     * 现在是标准做法：**总结最早的一段，最近的一段照常发送**。
     * `throughMessageId` 记的是**被总结掉的那段的最后一条**，语义与
     * `orderedOutgoingMessages` 里"覆盖位置之前的不再发"完全对上。
     *
     * **二、压完会不会又压。**
     *
     * 旧版的目标是"保留窗口的 85%"，而触发线是"窗口的 70%" ——
     * 压完仍然在线上，下一轮立刻又触发，每一轮都把同一段新对话重写一遍。
     * 现在目标线由 [AutoCompactPolicy] 给，**低于**触发线，压完就落到线下。
     */
    fun compactContext() {
        if (compacting || isStreaming) return
        val conversationId = currentConversationId ?: return

        val existing = compaction

        val candidates = compactionCandidates()
        if (candidates.isEmpty()) {
            banner = Res.get(R.string.compact_too_few)
            return
        }

        val plan = planCompaction(candidates)
        if (plan == null) {
            banner = Res.get(R.string.compact_too_long)
            return
        }

        val toSummarize = plan.head
        val throughId = toSummarize.last().id
        val previousSummary = existing?.summary?.takeIf { it.isNotBlank() }

        pendingAnchorId = throughId
        compacting = true
        compactPreview = ""
        banner = null

        compactJob = viewModelScope.launch {
            try {
                when (val outcome = summarize(previousSummary, toSummarize) { text ->
                    compactPreview = text
                }) {
                    is SummaryOutcome.Failed -> {
                        banner = Res.get(R.string.compact_failed_detail, outcome.reason)
                    }

                    is SummaryOutcome.Ok -> {
                        /*
                         * 对话回顾接在摘要后面（Operit 的 `dialogueReviewEnabled`，默认开）。
                         * 它让模型在摘要之外还能看到"当时是怎么说的" —— 纯摘要会把语气
                         * 磨平，续写时最先丢的就是这个。
                         */
                        val full = buildString {
                            append(outcome.text)
                            append("\n\n")
                            append(ContextSummary.dialogueReview(toSummarize))
                        }

                        val entity = CompactionEntity(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            throughMessageId = throughId,
                            summary = full,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                        )
                        repository.saveCompaction(entity)
                        compaction = entity
                        /*
                         * 迟滞用的下界：压完之后占用了多少。
                         * 下一次自动压缩要求"比这个下界还高出 15%"才允许，
                         * 否则一次压不动的长会话会被反复压、反复失败。
                         */
                        compactionFloorTokens = TokenEstimate.of(full) + plan.tailTokens
                    }
                }
            } catch (t: Throwable) {
                /*
                 * 取消不是失败。
                 *
                 * 切会话 / 新建对话会 cancel 这次压缩，`streamChat` 收到的是
                 * CancellationException；不往外抛的话它会被当成"压缩失败：已取消"，
                 * 在新会话里弹一条与用户无关的红条。
                 */
                if (t is kotlinx.coroutines.CancellationException) throw t
                banner = Res.get(R.string.compact_failed_detail, t.userMessage())
            } finally {
                compacting = false
                compactPreview = ""
                // 锚点交还给 compaction.throughMessageId：两者本应相同，清掉避免状态重叠
                pendingAnchorId = null
            }
        }
    }

    /** 一次摘要的结果。 */
    private sealed interface SummaryOutcome {
        data class Ok(val text: String) : SummaryOutcome
        data class Failed(val reason: String) : SummaryOutcome
    }

    /** 这次压缩要总结哪一段。 */
    private data class CompactionPlan(
        /** 要送去总结的消息（最早的一段）。 */
        val head: List<UiMessage>,
        /** 留在后面照常发送的那部分有多长（token）。 */
        val tailTokens: Int,
    )

    /**
     * 可以被这次压缩覆盖的消息：**上次摘要之后**、且有内容的那些。
     *
     * 起点用 `throughMessageId`（Operit 用"最后一条 sender=summary 的消息"
     * 当分界，这里是等价物，且分支切换时不会错位）：摘要之前的原文已经被
     * 写进摘要，再发一遍就是重复计费。
     */
    private fun compactionCandidates(): List<UiMessage> {
        val alreadyCovered = compaction
            ?.let { c -> messages.indexOfFirst { it.id == c.throughMessageId } }
            ?: -1
        return messages
            .filterIndexed { index, _ -> index > alreadyCovered }
            .filter { !it.streaming && (it.content.isNotBlank() || it.toolCalls.isNotEmpty()) }
    }

    /**
     * 算出这次压缩要总结哪一段。
     *
     * 目标：**压完之后**的占用（摘要 + 留下的原文）落到触发线**以下**。
     * 留多少原文、总结多少，用一个收缩比估算：一份四节摘要大约是原文的
     * 18% —— 这个数是经验值，估偏了只会多压或少压一点，不影响正确性。
     *
     * 切点必须落在**用户消息**上：否则留下的那段以半截助手回答开头，
     * 请求里就出现"一个没有问题的回答"。
     */
    private fun planCompaction(candidates: List<UiMessage>): CompactionPlan? {
        val decision = compactDecision(candidates)
        val target = decision.targetTokens.coerceAtLeast(1)
        val tokens = candidates.map { TokenEstimate.of(summarizableText(it)) }
        val total = tokens.sum()

        /*
         * 摘要请求本身也要装得下。
         *
         * 开了分段摘要（[compactChunked]），输入可以摊到多段里；
         * 没开就只允许一段 —— 一次塞 70 万 token 的请求，1M 的模型勉强收下，
         * 128K 的模型直接被服务端拒掉，"压缩"反而成了压垮上下文的那一下。
         */
        val inputBudget = if (compactChunked) {
            AutoCompactPolicy.SUMMARY_CHUNK_TOKENS * AutoCompactPolicy.MAX_SUMMARY_CHUNKS
        } else {
            AutoCompactPolicy.SUMMARY_CHUNK_TOKENS
        }

        var headTokens = 0
        var tailTokens = total
        var bestK = -1
        var bestTail = 0
        for (k in 1..candidates.size) {
            headTokens += tokens[k - 1]
            tailTokens -= tokens[k - 1]
            val boundary = k == candidates.size || candidates[k].role == MessageRole.USER
            if (!boundary) continue
            if (headTokens > inputBudget) break
            val after = (headTokens * AutoCompactPolicy.SUMMARY_SHRINK_RATIO).toInt() + tailTokens
            if (after <= target) {
                bestK = k
                bestTail = tailTokens
            }
        }
        if (bestK < 0) {
            /*
             * 目标线达不到 —— 通常是因为**留下**的那部分本身就超了。
             * 这时退一步：能压多少压多少（至少别让早期的设定白丢），
             * 剩下的交给迟滞兜底，不让它一轮一轮地重试。
             */
            headTokens = 0
            for (k in 1..candidates.size) {
                headTokens += tokens[k - 1]
                if (headTokens > inputBudget) break
                if (k == candidates.size || candidates[k].role == MessageRole.USER) {
                    bestK = k
                    bestTail = total - headTokens
                }
            }
            if (bestK < 0) return null
        }
        return CompactionPlan(
            head = candidates.take(bestK),
            tailTokens = bestTail,
        )
    }

    /**
     * 这次的压缩判定。阈值全部来自 [AutoCompactPolicy]。
     */
    private fun compactDecision(candidates: List<UiMessage>): AutoCompactPolicy.Decision =
        AutoCompactPolicy.decide(
            windowTokens = settings.model.contextWindow,
            outputTokens = settings.maxTokens ?: 0,
            usedTokens = contextStats.usedTokens,
            userRatio = compactRatio,
            userCount = compactCount,
            byCount = compactByCount,
            largeWindowExempt = compactLargeWindowExempt,
            reserveOutput = compactReserveOutput,
            messagesSinceCompaction = userMessagesSinceCompaction(),
            eligibleMessages = candidates.size,
        )

    /**
     * 真正去要那份摘要。
     *
     * 短会话走一趟；长会话**分段总结再合并**（见 [ContextSummary.chunkByTokens]）。
     */
    private suspend fun summarize(
        previousSummary: String?,
        toSummarize: List<UiMessage>,
        onPartial: (String) -> Unit,
    ): SummaryOutcome {
        val chunks = if (compactChunked) {
            ContextSummary.chunkByTokens(toSummarize, AutoCompactPolicy.SUMMARY_CHUNK_TOKENS)
        } else {
            listOf(toSummarize)
        }
        if (chunks.isEmpty()) return SummaryOutcome.Failed(Res.get(R.string.error_no_content))

        if (chunks.size == 1) {
            val system = ContextSummary.systemPrompt(
                previousSummary = previousSummary,
                rules = compactRules,
            )
            return requestSummary(
                system = system,
                body = conversationBody(chunks[0]),
                instruction = ContextSummary.userMessage(),
                onPartial = onPartial,
            )
        }

        /*
         * 分段：每段各总结一次。
         *
         * 每一段都**不带**上一次的摘要 —— 旧摘要留到最后合并时才参与，
         * 否则它会被复制到每一段的输入里，误差段段放大。
         */
        val partials = mutableListOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            val system = ContextSummary.chunkPrompt(
                chunkIndex = index + 1,
                chunkTotal = chunks.size,
                rules = compactRules,
            )
            val outcome = requestSummary(
                system = system,
                body = conversationBody(chunk),
                instruction = ContextSummary.userMessage(),
                onPartial = onPartial,
            )
            when (outcome) {
                is SummaryOutcome.Failed -> return outcome
                is SummaryOutcome.Ok -> partials += outcome.text
            }
        }

        // 合并：几段摘要 + 上一次摘要 → 一份全新的、自包含的摘要
        val mergeSystem = ContextSummary.mergePrompt(
            previousSummary = previousSummary,
            chunkCount = partials.size,
            rules = compactRules,
        )
        return requestSummary(
            system = mergeSystem,
            body = emptyList(),
            instruction = ContextSummary.mergeUserMessage(partials),
            onPartial = onPartial,
        )
    }

    /** 摘要请求里的对话部分：原样的 user / assistant 轮次。 */
    private fun conversationBody(messages: List<UiMessage>): List<OutMessageDto> =
        messages.map { message ->
            OutMessageDto(
                role = if (message.role == MessageRole.USER) "user" else "assistant",
                content = MessageContent.text(
                    if (message.role == MessageRole.USER) {
                        userSummarizableText(message)
                    } else {
                        summarizableText(message)
                    },
                ),
            )
        }

    /**
     * 发一次摘要请求。
     *
     * 请求的形状与 Operit 一致：system → 原样对话 → 最后一条 user 指令。
     *
     * 不把对话拼成 `用户: ... / AI: ...` 的一大段：那样模型分不清
     * "谁说的"和"哪句是引用"，摘要里经常把用户的要求写成 AI 的承诺。
     */
    private suspend fun requestSummary(
        system: String,
        body: List<OutMessageDto>,
        instruction: String,
        onPartial: (String) -> Unit,
    ): SummaryOutcome {
        val request = buildList {
            add(OutMessageDto(role = "system", content = MessageContent.text(system)))
            addAll(body)
            add(OutMessageDto(role = "user", content = MessageContent.text(instruction)))
        }
        val collected = StringBuilder()
        var failure: String? = null
        try {
            chatApi.streamChat(
                baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                apiKey = stored.apiKey,
                model = settings.model,
                messages = request,
                // 总结不需要思考，也不需要保留已存在的摘要上下文
                thinkingEnabled = false,
                temperature = 0.3,
                provider = providerKind,
            ).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        collected.append(event.text)
                        onPartial(collected.toString())
                    }

                    // 流内错误对象是**上游网关原文**：先翻成人话，认不出才保留原文
                    is StreamEvent.Error -> failure = serverErrorText(event.message)
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            /*
             * 取消不是失败 —— 见 [compactContext] 里的说明。
             */
            if (t is kotlinx.coroutines.CancellationException) throw t
            failure = t.userMessage()
        }
        if (failure != null) return SummaryOutcome.Failed(failure)
        val text = collected.toString().trim()
        if (text.isEmpty()) return SummaryOutcome.Failed(Res.get(R.string.error_no_content))
        return SummaryOutcome.Ok(text)
    }

    /**
     * 摘要请求里这一条消息的正文。
     *
     * assistant 走 `outgoingAssistantText`：正文为空但动过手的轮次不能消失，
     * 否则摘要会漏掉"查过什么、改过哪个文件"。
     * 文本附件的内容要带上，否则"我上传的那份设定"会在压缩后凭空消失。
     */
    private fun summarizableText(message: UiMessage): String {
        val body = if (message.role == MessageRole.ASSISTANT) {
            outgoingAssistantText(message)
        } else {
            message.content
        }
        val extra = message.attachments
            .filter { it.kind == Attachment.Kind.TEXT }
            .joinToString("\n") { it.toPromptBlock() }
        val images = message.attachments.count { it.isImage }
        val text = if (extra.isBlank()) body.trim() else (body.trim() + "\n" + extra).trim()
        // 图片本身不进摘要请求（模型看的是文字），但要留一句占位 ——
        // 否则摘要里会出现"用户发了一张图"却不知道是什么，或者干脆整条消失。
        return if (images > 0) "$text\n[image ×$images]".trim() else text
    }

    /** 用户消息：正文 + 文本附件，附件的 XML 围栏和正文一起进摘要。 */
    private fun userSummarizableText(message: UiMessage): String = summarizableText(message)

    /** 放弃压缩，恢复发送全部消息。**本地消息一条都没少。** */
    fun clearCompaction() {
        val active = compaction ?: return
        compaction = null
        compactionFloorTokens = 0
        viewModelScope.launch { repository.deleteCompaction(active.id) }
    }

    /** 手工修正摘要（AI 记错人名地名时用）。 */
    fun updateCompactionSummary(text: String) {
        val active = compaction ?: return
        val updated = active.copy(summary = text.trim(), updatedAt = System.currentTimeMillis())
        compaction = updated
        viewModelScope.launch { repository.saveCompaction(updated) }
    }

    fun cancelCompaction() {
        compactJob?.cancel()
        compactJob = null
        compacting = false
        compactPreview = ""
        pendingAnchorId = null
    }

    // ── 上下文统计 ────────────────────────────────────────

    /**
     * 当前会话的上下文占用与性能统计。
     *
     * ### 为什么不用 `derivedStateOf`
     *
     * 这段统计要**扫一遍全部消息的正文**来估 token。写成 `derivedStateOf` 时它依赖
     * `messages` 这个快照列表 —— 而流式期间列表每 16ms 就变一次，于是它每帧都把
     * 整条会话重新扫一遍（三十万 token 的会话 ≈ 每帧几十万字符 + 若干次全量 map）。
     * 真机表现就是：长会话里一开始生成，整个界面就发涩、滑不动。
     *
     * 现在改成 `snapshotFlow` + 防抖 + 后台线程：输入栏上那行统计晚几百毫秒更新
     * 完全无所谓，而主线程不再为它做任何与正文长度相关的工作。
     */
    var contextStats by mutableStateOf(emptyContextStats())
        private set

    private fun emptyContextStats() = ContextStats(
        usedTokens = 0,
        windowTokens = settings.model.contextWindow,
        messageCount = messages.size,
        totalTokens = 0,
        compactedTokenSaving = 0,
        avgTtftMs = null,
        avgTps = null,
        totalThinkingSeconds = null,
        hasCompaction = compaction != null,
    )

    /**
     * 统计的**输入快照**。
     *
     * 刻意做成一个 data class 而不是在 flow 里直接读各状态：`snapshotFlow` 用
     * `equals` 判"有没有变"，这里一次读全，语义最清楚（消息 / 设置 / 预设 / 压缩
     * 任何一项变了都要重算）。
     */
    @Immutable
    private data class StatsInput(
        val messages: List<UiMessage>,
        val settings: ConversationSettings,
        val presets: List<Preset>,
        val compaction: CompactionEntity?,
    )

    private fun computeContextStats(input: StatsInput): ContextStats {
        val messages = input.messages
        val settings = input.settings
        val presets = input.presets
        val active = input.compaction

        // —— 实际会发给 API 的部分 ——
        val system = settings.composedSystemPrompt(presets)
        val outgoing = orderedOutgoingMessages(messages, active)
        val inContext = buildList {
            if (system.isNotBlank()) add(system)
            if (active != null && active.summary.isNotBlank()) add(active.summary)
            outgoing.forEach { add(it.content) }
        }
        // 附件也要算进去：图片按每张上限 1024、文本附件按字数（见 RequestContent）
        val attachmentTokens = outgoing.sumOf { attachmentTokenEstimate(it.attachments) }

        // —— 本地全部内容（含被压缩掉的）——
        val allTexts = messages.filter { !it.streaming }.map { it.content }

        val ttfts = messages.mapNotNull { it.ttftMs }
        val tpsList = messages.mapNotNull { it.tps }
        val thinking = messages.mapNotNull { it.reasoningSeconds }

        return ContextStats(
            usedTokens = TokenEstimate.ofAll(inContext) + attachmentTokens,
            windowTokens = settings.model.contextWindow,
            messageCount = messages.size,
            totalTokens = TokenEstimate.ofAll(allTexts),
            compactedTokenSaving = run {
                if (active == null) return@run 0
                val cut = messages.indexOfFirst { it.id == active.throughMessageId }
                if (cut < 0) return@run 0
                TokenEstimate.ofAll(messages.take(cut + 1).map { it.content }) -
                    TokenEstimate.of(active.summary)
            },
            avgTtftMs = ttfts.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            avgTps = tpsList.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            totalThinkingSeconds = thinking.takeIf { it.isNotEmpty() }?.sum(),
            hasCompaction = active != null,
        )
    }

    // ── 消息操作：重新生成 / 重试 / 编辑 / 删除 / 切版本 ──────────

    /**
     * 重试一条**失败**的回复。
     *
     * 与 [regenerate] 的区别：重试是**原地**重来，不新增版本 ——
     * 重试的是同一次请求，用户看到的还应该是那一格。
     *
     * 用**当前**选的模型（顺带写回 `model`）：用户很可能就是换了模型
     * 才来点重试的。所以这里不做"沿用当初那个模型"的假设。
     */
    fun retryMessage(target: UiMessage) {
        if (isStreaming || target.role != MessageRole.ASSISTANT) return
        val conversationId = currentConversationId ?: return

        viewModelScope.launch {
            switchJob?.join()
            repository.resetForRetry(target.id, settings.model.id)
            reloadPath(conversationId)
            startStream(conversationId, target.id)
        }
    }

    /**
     * 重新生成**指定**的 AI 回复。
     *
     * 关键：新回复加在**同一个 slot** 上成为新版本，旧版本保留可回退，
     * 且其下游消息完全不受影响（设计文档 §4.1）。
     * 早先的实现按 `loadActiveMessages().size` 算了个新 slot，
     * 于是旧回复没被替换、新回复排到了队尾 —— 表现就是"一个接一个"。
     */
    fun regenerate(target: UiMessage) {
        if (isStreaming || target.role != MessageRole.ASSISTANT) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return

        viewModelScope.launch {
            // 挂在**被重新生成那条的父亲**下 → 成为它的兄弟，旧回答留着
            val fresh = repository.addActiveVariant(
                conversationId = conversationId,
                parentId = target.parentId,
                role = MessageRole.ASSISTANT,
                content = "",
                reasoning = "",
                status = MessageStatus.STREAMING,
            )
            reloadPath(conversationId)
            startStream(conversationId, fresh.id)
        }
    }

    /**
     * 继续生成（续写）。
     *
     * 语义是「接着这条写下去」，不是"重新生成一段" —— 所以：
     * - **不新增版本**，就写在这一条里（与 [regenerate] 相反）；
     * - 已有内容一个字不动，新内容接在后面；
     * - 请求把这条 assistant 作为**末尾前缀**发出去，服务端据此续写。
     *
     * 准入规则见 [canContinueReply]：只有活跃路径的最后一条、被停止或被上限截断的
     * 扩展通道回复才允许续写。不满足就静默返回 —— 按钮本来也不该出现。
     */
    fun continueMessage(target: UiMessage) {
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return
        if (!canContinueReply(target, isLastOnPath = index == messages.lastIndex)) return

        viewModelScope.launch {
            switchJob?.join()
            repository.resumeStreaming(target.id)
            patch(target.id) {
                it.copy(
                    status = MessageStatus.STREAMING,
                    errorMessage = null,
                    stopReason = null,
                )
            }
            startStream(conversationId, target.id, prefix = target)
        }
    }

    /**
     * 编辑一条消息。
     *
     * 新版本挂在**原消息的父亲**下，成为它的**兄弟**：
     * 旧的那条连同它**整棵子树**原样留着，切回去就能看到。
     *
     * - [resend] = false：只换文字，新兄弟没有子树 → 路径在此结束
     * - [resend] = true：换文字后在新兄弟下再生成一条回复
     *
     * **两种角色都不删任何东西。** 这正是换成树模型换来的东西。
     */
    fun editMessage(target: UiMessage, newText: String, resend: Boolean, attachments: List<Attachment>? = null) {
        if (isStreaming) return
        val text = newText.trim()
        val kept = attachments ?: target.attachments
        if (text.isEmpty() && kept.isEmpty()) return
        val conversationId = currentConversationId ?: return

        viewModelScope.launch {
            /*
             * 编辑重发 = 从这条消息重来，文件也要回到那一刻。
             *
             * 不回滚的话，新回复是在"上一条回复改过的世界"里生成的：
             * 它会读到一份自己没写过、用户也没见过的代码，然后基于它继续改。
             * 只改文字（resend = false）不动文件 —— 那只是修错别字。
             */
            if (resend) {
                val index = messages.indexOfFirst { it.id == target.id }
                if (index >= 0) {
                    val report = rewindFiles(conversationId, messages.drop(index).map { it.id }.toSet())
                    if (report.changed > 0 || report.failed.isNotEmpty() || report.missing.isNotEmpty()) {
                        banner = report.summary()
                    }
                }
            }

            val fresh = repository.addActiveVariant(
                conversationId = conversationId,
                parentId = target.parentId,
                role = target.role,
                content = text,
                /*
                 * 附件跟着走。改文案不该把图弄丢 —— 那是最容易让人骂街的一种丢失：
                 * 用户只想改个错别字，回头发现图没了，而且旧版本也翻不回来。
                 */
                attachments = kept,
                // 仅改文字时保留思考内容；重发时旧思考已与新回复无关
                reasoning = target.reasoning.takeIf { !resend && target.role == MessageRole.ASSISTANT },
            )

            val replyId = if (resend) {
                // 回复挂在**新的**这条下面
                repository.beginAssistantMessage(
                    conversationId = conversationId,
                    parentId = fresh.id,
                    model = settings.model.id,
                )
            } else {
                null
            }

            reloadPath(conversationId)
            replyId?.let { startStream(conversationId, it) }
            if (replyId == null) repository.touchConversation(conversationId)
        }
    }

    /** 导出当前会话为 JSON；尚未落库的新对话返回 null。 */
    suspend fun exportCurrentConversation(): String? =
        currentConversationId?.let { repository.exportConversation(it) }

    /** 导出当前会话为 **Markdown 文稿**；尚未落库的新对话返回 null。 */
    suspend fun exportCurrentConversationMarkdown(): String? =
        currentConversationId?.let { repository.exportConversationMarkdown(it) }

    // ── 未发送的输入草稿 ──────────────────────────────────

    /**
     * 读回草稿。[key] 是 `ChatScreen` 的 composer key —— 就是会话 id，
     * 还没建会话时是空串（`DraftEntity.NewConversation`）。
     */
    suspend fun loadDraft(key: String): String? = repository.findDraft(key)

    /** 落草稿。空文本即删行。 */
    suspend fun saveDraft(key: String, text: String) = repository.saveDraft(key, text)

    suspend fun clearDraft(key: String) = repository.clearDraft(key)

    /** 删除一条消息 —— 连同它的整棵子树。 */
    fun deleteMessage(target: UiMessage) {
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            repository.deleteMessage(target.id)
            reloadPath(conversationId)
        }
    }

    /**
     * 「回滚到此处」。
     *
     * 两件事一起做，且必须一起做：
     * 1. **文件**回到这条消息之前的样子（它改了什么、建了什么，全部撤销）
     * 2. **对话**截到这条消息之前（旧分支留着，切版本还能找回来）
     *
     * 只做 1 会得到"消息还在、但它说的文件已经不存在"的鬼状态；
     * 只做 2 则等于什么都没发生 —— 模型下一轮仍然站在被改过的世界上。
     *
     * 回滚本身也记账（见 `Rewind`），所以手滑了还能再回滚一次。
     */
    fun rewindTo(target: UiMessage) {
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return
        // 这条消息**及其之后**的所有改动都在撤销范围内 —— 后面的回复是建立在它之上的
        val ids = messages.drop(index).map { it.id }.toSet()

        viewModelScope.launch {
            val report = rewindFiles(conversationId, ids)
            repository.truncateBefore(conversationId, target.id)
            reloadPath(conversationId)
            banner = report.summary()
        }
    }

    /**
     * 「确定回滚」窗口里那一列文件。
     *
     * 计算范围与 [rewindTo] 完全一致（这条消息及其之后），否则窗口上写"改 2 个"、
     * 真回滚却动了 5 个，比不显示还糟。
     */
    fun rewindPreview(target: UiMessage): List<com.simplechat.app.agent.FileChange> {
        val conversationId = currentConversationId ?: return emptyList()
        if (messages.none { it.id == target.id }) return emptyList()
        val scope = CloudEnvs.envOf(conversationId)?.let { "cloud:${it.id}" } ?: "local"
        // changes() 内部按 seq 边界取"这条消息及其之后"，与 rewindTo 的范围同一口径
        return runCatching {
            com.simplechat.app.agent.Rewind.changes(conversationId, target.id, scope)
        }.getOrDefault(emptyList())
    }

    /**
     * 把 [ids] 这些消息造成的文件改动撤掉。
     *
     * 本地与云端走同一条路径，只有 scope 与远端句柄不同 —— 云端会话的文件
     * 不在手机上，但"回滚"这个动作对用户应该长得一模一样。
     */
    private suspend fun rewindFiles(conversationId: String, ids: Set<String>): RewindReport {
        if (ids.isEmpty()) return RewindReport()
        val cloud = CloudEnvs.envOf(conversationId)
        return withContext(Dispatchers.IO) {
            Rewind.rewind(
                conversationId = conversationId,
                messageIds = ids,
                scope = cloud?.let { "cloud:${it.id}" } ?: "local",
                remote = cloud?.let { RemoteWorkspaces.of(it) },
            )
        }
    }

    /**
     * 「从这里新建对话」（§21）。
     *
     * 把活跃路径到 [target] 为止的一段复制成一个**独立新会话**，然后打开它。
     * **原会话一个字节不动** —— 想回头，它就在列表里躺着。
     *
     * 这是**会话之间**的动作；会话**内部**的分支由「重新生成 / 编辑重发」覆盖
     * （它俩是同一个父节点下的兄弟，靠 `‹ n/m ›` 切）—— 那件事不需要再开一个入口。
     */
    fun forkFrom(target: UiMessage) {
        if (isStreaming) return
        viewModelScope.launch {
            val newId = repository.forkConversation(target.id) ?: return@launch
            openConversation(newId)
        }
    }

    /**
     * 切到同父的另一个版本。
     *
     * 路径会**整段重建** —— 旧分支的下游跟着回来，新分支的下游跟着消失。
     * 这就是"分支切换"在树模型下的天然行为。
     */
    fun switchVariant(target: UiMessage, variantIndex: Int) {
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        if (variantIndex !in 0 until target.variantCount) return

        viewModelScope.launch {
            val id = repository.messageIdAt(conversationId, target.parentId, variantIndex)
                ?: return@launch
            repository.activateVariant(id, conversationId, target.parentId)
            reloadPath(conversationId)
        }
    }

    fun stop() {
        if (!isStreaming) return
        val epoch = streamEpoch
        stoppedEpoch = epoch
        /*
         * **先就地截断，再取消协程。**
         *
         * `cancel()` 只在挂起点生效：协程正卡在一次阻塞读、一条 shell 命令
         * 或一次网络请求里时，要等它退出来才轮得到收尾。这段时间里气泡的
         * 三个点、标题的流光、工具卡的旋转全都还在动 —— 用户看到的就是
         * "按了停止，它还在跑"。
         *
         * 所以这里直接把最后一条 streaming 回复判成 STOPPED，并把没跑完的
         * 工具卡收口成"已停止"。动画的开关是 `status == STREAMING`，状态一改
         * 就全部停下，不等网络层。
         */
        messages.lastOrNull { it.status == MessageStatus.STREAMING }?.let { message ->
            stoppedReplyIds += message.id
            patch(message.id) {
                it.copy(
                    status = MessageStatus.STOPPED,
                    // 思考已结束：标题不再走"正在思考"的流光
                    reasoningDone = true,
                    toolCalls = it.toolCalls.map { call ->
                        if (call.status == ToolCallUi.Status.RUNNING ||
                            call.status == ToolCallUi.Status.AWAITING
                        ) {
                            call.copy(
                                status = ToolCallUi.Status.FAILED,
                                output = Res.get(R.string.tool_cancelled),
                            )
                        } else {
                            call
                        }
                    },
                )
            }
        }
        isStreaming = false
        retryHint = null
        // 它在替用户点屏幕时，那颗"正在操作屏幕"的悬浮条也要跟着收
        ScreenAutomation.endRound()
        currentConversationId?.let { com.simplechat.app.ui.chat.ActiveRuns.finish(it, epoch) }
        val job = streamJob
        streamJob = null
        streamConversationId = null
        job?.cancel()
        // 停止的是"当前这一轮"，排队里的话仍然是用户要发的 —— 接着发
        drainQueue(currentConversationId)
    }

    /**
     * 用户切到别的会话：**不停**，让它继续在后台跑。
     *
     * 会话之间用消息 id 天然隔离（后台那一路写的是旧 id，`patch` 在
     * 新列表里找不到，自然空转），数据库又是按 `conversationId` 落的，
     * 所以后台跑完再看，那条回复是完整的。
     *
     * 只有"用户自己按停止"才真的停 —— 切走不是"不要了"。
     */
    private fun detachRun() {
        if (!isStreaming) return
        val id = currentConversationId ?: return
        streamJob?.let { backgroundRuns[id] = it }
        streamJob = null
        streamConversationId = null
        isStreaming = false
    }

    // ── 流式实现 ──────────────────────────────────────────

    private fun startStream(conversationId: String, replyId: String, prefix: UiMessage? = null) {
        val epoch = ++streamEpoch
        isStreaming = true
        streamConversationId = conversationId
        retryHint = null
        ActiveRuns.start(conversationId, epoch)
        // 对同一条回复重新发起（继续生成）：解除它的停止封印
        stoppedReplyIds -= replyId
        lastCheckpointAt = System.currentTimeMillis()
        val extended = apiStyle == ApiStyle.ANTHROPIC
        val responses = apiStyle == ApiStyle.RESPONSES

        streamJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var firstTokenAt: Long? = null
            var contentChars = 0

            /*
             * 思维链的起止时刻。
             *
             * 用来算**真实思考时长** —— 先前拿的是"整条回复的总时长"，
             * 把写正文的时间也算进了「已深度思考 N 秒」里。
             */
            var reasoningStartedAt: Long? = null
            var reasoningEndedAt: Long? = null

            var reasoningText = ""
            var contentText = ""
            val reasonBuf = StringBuilder()
            val contentBuf = StringBuilder()

            /*
             * 扩展通道的块累加器。
             *
             * OpenAI 通道的回复没有"结构"（只有正文 + 思考两股流），所以这里为 null，
             * 走原来的 StringBuilder 路径。两条通道只在**收事件**时分叉，
             * 落盘、节流、性能统计全部共用，避免为了一个功能复制一整套流式调度。
             */
            val accumulator = if (extended) ReplyAccumulator() else null
            // 续写：以已有块开局，新内容往后面接（见 ReplyAccumulator.seed）
            if (accumulator != null && prefix != null) accumulator.seed(prefix.timeline)
            var timeline: List<ReplyBlock> = prefix?.timeline.orEmpty()
            var dirty = false

            /** 正文（或正文块）一出现 = 思维链已经写完，思考栏当场收口。 */
            fun noteBody() {
                if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                if (reasoningEndedAt != null) return
                val endedAt = System.currentTimeMillis()
                reasoningEndedAt = endedAt
                val ms = endedAt - (reasoningStartedAt ?: startedAt)
                patch(replyId) {
                    it.copy(reasoningDone = true, reasoningSeconds = thinkingSeconds(ms))
                }
            }

            fun noteThinking() {
                if (reasoningStartedAt == null) reasoningStartedAt = System.currentTimeMillis()
            }

            /**
             * 上一次落盘的工具调用 JSON。
             *
             * 用来跳过"没变化"的写入 —— 流式 checkpoint 每 1.5s 一次，
             * 而工具调用列表在一轮里只变几次；比较一下字符串比无条件
             * 写一遍几十 KB 的 JSON 便宜得多。
             */
            var lastToolsJson: String? = null

            // 节流提交器：与 collect 同在主线程调度器，天然串行，无需加锁
            val flusher = launch {
                while (isActive) {
                    delay(STREAM_THROTTLE_MS)
                    if (!dirty) continue

                    if (accumulator != null) {
                        // 块通道是**全量替换**：顺序与内容都由累加器持有
                        timeline = accumulator.blocks
                        patch(replyId) {
                            it.copy(
                                content = accumulator.answer,
                                reasoning = accumulator.thinking.ifEmpty { null },
                                timeline = accumulator.blocks,
                            )
                        }
                    } else {
                        flush(replyId, reasonBuf, contentBuf)
                    }
                    reasoningText = messages.firstOrNull { it.id == replyId }?.reasoning.orEmpty()
                    contentText = messages.firstOrNull { it.id == replyId }?.content.orEmpty()
                    dirty = false

                    // §4.4：流中每约 1.5s 落一次盘，崩溃不丢内容
                    val now = System.currentTimeMillis()
                    if (now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                        lastCheckpointAt = now
                        val r = reasoningText
                        val c = contentText
                        val t = timeline
                        withContext(NonCancellable) {
                            runCatching { repository.checkpoint(replyId, c, r, t) }
                            /*
                             * 工具调用也要跟着落盘。
                             *
                             * 只靠 `finishMessage` 的话，App 在流式中被系统
                             * 杀掉，重开时那条消息被判为"已中断"，工具调用
                             * 一条都不剩 —— 而用户最想看的正是"它跑到哪儿了"。
                             */
                            val callsNow = messages.firstOrNull { it.id == replyId }?.toolCalls.orEmpty()
                            val encoded = com.simplechat.app.data.ToolCalls.encode(callsNow)
                            if (encoded != null && encoded != lastToolsJson) {
                                lastToolsJson = encoded
                                runCatching { repository.checkpointTools(replyId, encoded) }
                            }
                        }
                    }
                }
            }

            var failure: String? = null
            /** 这次失败是不是"上游抖了一下"（断流 / 限流 / 5xx）—— 决定要不要自动续跑。 */
            var transientFailure = false
            var serverTitle: String? = null

            try {
                if (accumulator != null) {
                    chatApi.streamMessage(
                        baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                        apiKey = stored.apiKey,
                        request = buildAnthropicRequestNow(prefix),
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.BlockStart -> {
                                if (event.block is ReplyBlock.Text) noteBody()
                                if (event.block is ReplyBlock.Thinking) noteThinking()
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.TextDelta -> {
                                if (event.text.isNotEmpty()) {
                                    noteBody()
                                    contentChars += event.text.length
                                }
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.ThinkingDelta -> {
                                noteThinking()
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.Title -> serverTitle = event.text

                            is StreamEvent.CitationDelta, is StreamEvent.SignatureDelta,
                            is StreamEvent.BlockStop, is StreamEvent.Stopped -> {
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.Usage -> Unit

                            StreamEvent.Done -> Unit

                            // 流内错误对象是**上游网关原文**（如 `No available channel …`）：
                            // 先翻成人话，认不出才保留原文（见 `serverErrorText`）
                            is StreamEvent.Error -> failure = serverErrorText(event.message)

                            /*
                             * 块通道不该出现这三类；出现了说明通道配错，忽略。
                             * ToolCall 属于 Agent 通道，走 AgentRunner 的事件流。
                             */
                            is StreamEvent.Content, is StreamEvent.Reasoning,
                            is StreamEvent.ToolCall -> Unit

                            is StreamEvent.Retrying -> {
                                retryHint = Res.get(R.string.stream_retrying, event.attempt, event.max)
                            }
                        }
                    }
                } else {
                    val history = buildRequestMessages()
                    val stream = if (responses) {
                        chatApi.streamResponses(
                            baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                            apiKey = stored.apiKey,
                            model = settings.model,
                            messages = history,
                            thinkingEnabled = settings.reasoningLevel.enabled,
                            reasoningEffort = settings.reasoningEffort,
                            maxTokens = settings.maxTokens,
                            temperature = settings.temperature.toDouble(),
                            topP = settings.topP.toDouble(),
                        )
                    } else {
                        chatApi.streamChat(
                            baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                            apiKey = stored.apiKey,
                            model = settings.model,
                            messages = history,
                            thinkingEnabled = settings.reasoningLevel.enabled,
                            reasoningEffort = settings.reasoningEffort,
                            maxTokens = settings.maxTokens,
                            temperature = settings.temperature.toDouble(),
                            topP = settings.topP.toDouble(),
                            fast = settings.fast,
                            provider = providerKind,
                        )
                    }
                    stream.collect { event ->
                        when (event) {
                            is StreamEvent.Reasoning -> {
                                /*
                                 * 用户选了「关闭思考」就不收思考内容。
                                 *
                                 * 请求里已经带了 `thinking.type = disabled`，但**有些模型 / 网关
                                 * 仍会吐 `reasoning_content`** —— 实测：opencode 那边已停用的
                                 * `deepseek-v4-flash` 会被路由到 `deepseek-flash`，而网关不认
                                 * 我们发过去的 disabled。
                                 *
                                 * 既然用户选的是关闭，就当它没来过：不落库、不显示，
                                 * 界面与设置保持一致。（服务端是否真的少算这部分 token，
                                 * 客户端管不着 —— 那是上游的事。）
                                 */
                                if (!settings.reasoningLevel.enabled) return@collect
                                noteThinking()
                                reasonBuf.append(event.text)
                                dirty = true
                            }

                            is StreamEvent.Content -> {
                                noteBody()
                                contentBuf.append(event.text)
                                contentChars += event.text.length
                                dirty = true
                            }

                            is StreamEvent.Usage -> Unit // 后续接入用量与成本展示

                            StreamEvent.Done -> Unit

                            // 流内错误对象是**上游网关原文**（如 `No available channel …`）：
                            // 先翻成人话，认不出才保留原文（见 `serverErrorText`）
                            is StreamEvent.Error -> {
                                failure = serverErrorText(event.message)
                                transientFailure = event.code == null || event.code == 0 ||
                                    event.code == 429 || event.code in 500..599
                            }

                            is StreamEvent.Retrying -> {
                                retryHint = Res.get(R.string.stream_retrying, event.attempt, event.max)
                            }

                            // 块级事件只属于 Anthropic 通道；这条路径上忽略
                            else -> Unit
                        }
                    }
                }
            } catch (t: Throwable) {
                // 只认**这一轮**的停止：停止后队列里下一条会立刻开跑，
                // 共享布尔会被它重置，这一轮就会把取消误判成失败
                if (stoppedEpoch != epoch) failure = t.userMessage()
                // 网络层异常（含 socket 超时、连接被重置）都属于可续跑的临时故障
                if (t is java.io.IOException) transientFailure = true
                com.simplechat.app.util.AppLog.e("Chat", "流式请求异常", t)
            } finally {
                flusher.cancel()
                if (accumulator != null) {
                    accumulator.settle(System.currentTimeMillis())
                    timeline = accumulator.blocks
                    /*
                     * 被用户停掉的那条不再补最后一大块。
                     *
                     * 累加器持有的是"网络层收到过的一切"，而界面是每 1.5s
                     * 刷一次 —— 不封口的话，按下停止后气泡会突然一下长出一
                     * 整段没显示过的字，看着就像"根本没停"。
                     */
                    if (replyId !in stoppedReplyIds) {
                        patch(replyId) {
                            it.copy(
                                content = accumulator.answer,
                                reasoning = accumulator.thinking.ifEmpty { null },
                                timeline = accumulator.blocks,
                            )
                        }
                    }
                } else {
                    flush(replyId, reasonBuf, contentBuf)
                }
            }

            val finishedAt = System.currentTimeMillis()
            /*
             * 这里**不做任何"审核/撤回"处理**。
             *
             * 客户端只负责一件事：服务端发来什么就显示什么、存什么。`stop_reason` 原样记进
             * 数据库（`stopReason`），不据此删改内容 —— 早先按 Anthropic 文档的
             * "refusal 时丢弃已下发内容"做过一次，结果是一段已经写完的回复在收尾瞬间被吞掉，
             * 用户看到的只有"写完了，然后没了"。那是数据丢失，客户端没有理由替服务端做这件事。
             */
            val status = when {
                stoppedEpoch == epoch -> MessageStatus.STOPPED
                failure != null -> MessageStatus.ERROR
                else -> MessageStatus.DONE
            }
            val ttft = firstTokenAt?.minus(startedAt)
            val seconds = (finishedAt - startedAt).coerceAtLeast(1) / 1000f
            // 粗估：中文约 1 token ≈ 1.6 字符，仅用于性能仪表展示
            val tps = if (contentChars > 0) (contentChars / 1.6f) / seconds else null
            val finalContent = messages.firstOrNull { it.id == replyId }?.content.orEmpty()
            val finalReasoning = messages.firstOrNull { it.id == replyId }?.reasoning
            /*
             * **真实**思考时长 = 首条思维链 → 首条正文。
             *
             * 先前用的是 `finishedAt - startedAt`，也就是"想 + 写"的总时长，
             * 却挂在「已深度思考」后面 —— 想 5 秒、写 25 秒会显示成 30 秒。
             *
             * 没等到正文就结束（用户中断 / 出错）时，用结束时刻兜底。
             *
             * 续写且这一轮没再思考时，思考时长要**沿用上一轮的值** ——
             * 否则一条「已深度思考 · 8 秒」的回复，点一下继续生成就变成"没思考过"。
             */
            val thinkingMs = reasoningStartedAt?.let { start ->
                (reasoningEndedAt ?: finishedAt) - start
            } ?: prefix?.reasoningSeconds?.times(1000)

            val stoppedNow = stoppedEpoch == epoch
            patch(replyId) {
                it.copy(
                    status = status,
                    errorMessage = failure,
                    ttftMs = ttft,
                    tps = tps,
                    reasoningSeconds = thinkingMs?.let { thinkingSeconds(it) },
                    // 停下时保留屏上那份时间线，不把没显示过的块补进去
                    timeline = if (stoppedNow) it.timeline else timeline,
                    stopReason = accumulator?.stopReason,
                )
            }

            // 终写一次：即使进程被杀，内容也已落库
            withContext(NonCancellable) {
                runCatching {
                    repository.finishMessage(
                        id = replyId,
                        content = finalContent,
                        reasoning = finalReasoning,
                        status = status,
                        errorMessage = failure,
                        ttftMs = ttft,
                        tps = tps,
                        thinkingMs = thinkingMs,
                        timeline = timeline,
                        stopReason = accumulator?.stopReason,
                    )
                    repository.touchConversation(conversationId)
                }
            }

            /*
             * 服务端给了标题就用它 —— 比另发一次请求让模型起名更省、更准，
             * 而且这是官方 Chat Demo 的做法（`title.enabled` 的返回值）。
             */
            val title = serverTitle?.trim()
            if (!title.isNullOrEmpty()) {
                withContext(NonCancellable) {
                    runCatching { repository.renameConversation(conversationId, title) }
                }
                serverTitleRequestedFor = conversationId
            }

            /*
             * 失败**不再重复弹 banner**：
             *
             * 错误已经写在这条回复的 `errorMessage` 上（消息里那行红字 + 重试按钮），
             * 再弹一条一模一样的 banner 只是噪声 —— v1.1.3 之前的截图里同一个
             * `stream was reset` 就是上下各显示一次。banner 只留给"与某条消息无关"的
             * 全局提示（未配置 Key、压缩失败等）。
             */
            /*
             * 只有"这仍是当前会话的那一轮"才回写界面状态。
             *
             * 用户切走之后这轮转到了后台（detachRun），此时 `isStreaming`
             * 可能已经被新会话占用 —— 无脑置 false 会把新会话的转圈按灭。
             */
            /*
             * 只有"这仍是当前这一轮"才回写共享状态 —— 判据是世代号，不是会话 id：
             * 同一会话里按停止后队列立刻发出下一条时，两轮的会话 id 完全相同，
             * 旧协程的 finally 会把新流的转圈和停止按钮一起抹掉（见 [streamEpoch]）。
             */
            if (epoch == streamEpoch) {
                isStreaming = false
                streamJob = null
                streamConversationId = null
                retryHint = null
            }
            /*
             * 转圈按**轮次号**登记，旧轮退场只摘自己那一格 —— 所以这里必须
             * 无条件调用，不能跟着上面的世代判断一起跳过：会话被切走、用户在
             * 别的会话里发了消息之后，旧轮的 finally 已经进不了上面那个分支，
             * 转圈就再也没人摘。
             */
            ActiveRuns.finish(conversationId, epoch)
            if (backgroundRuns[conversationId] === coroutineContext[Job]) {
                backgroundRuns.remove(conversationId)
            }
            if (status == MessageStatus.DONE) autoResumeCounts.remove(replyId)

            val resuming = maybeAutoResume(
                conversationId = conversationId,
                replyId = replyId,
                transient = transientFailure,
                failed = failure != null,
                extended = extended,
                stopped = stoppedEpoch == epoch,
            )
            // 这一轮说完了 → 排着队的下一条跟上（要自动续跑时让续跑先跑完）
            if (!resuming && currentConversationId == conversationId && !switching) {
                drainQueue(conversationId)
            }

            /*
             * 首轮回复落地后，用一个 **flash 档模型**给会话起个标题。
             *
             * 刻意放在这里而不是"发出去就生成"：拿到回复才知道这段在聊什么，
             * 只根据提问猜出来的标题经常跑偏。
             *
             * 扩展通道已经由服务端给了标题（[serverTitle]），就不再自己发一轮请求。
             */
            if (status == MessageStatus.DONE && title.isNullOrEmpty()) {
                maybeGenerateTitle(conversationId)
            }
        }
    }

    /**
     * 断流之后**自动接着写**，而不是把一轮工作停在半截。
     *
     * 网络层那只重试覆盖"还一个字都没吐"的情况；已经写了几百字再断线，重来一遍
     * 既浪费又会重复。扩展通道支持把这条 assistant 作为末尾前缀发回去，正好用来
     * 续写 —— 已收到的内容一个字不动，新内容接在后面。
     *
     * 护栏：
     * - 用户主动停止 → 绝不自动续跑（停止就是停止）
     * - 只对临时故障（断流 / 限流 / 5xx），4xx 业务错误续了也是白续
     * - 只对扩展通道；OpenAI 兼容通道没有前缀续写协议
     * - 带过服务端工具的回复不续（工具块必须一起回传，官方限制）
     * - 每条回复最多 2 次，超出就把错误摆出来让人决定
     */
    private fun maybeAutoResume(
        conversationId: String,
        replyId: String,
        transient: Boolean,
        failed: Boolean,
        extended: Boolean,
        stopped: Boolean,
    ): Boolean {
        if (stopped || !transient || !failed || !extended) return false
        val message = messages.firstOrNull { it.id == replyId } ?: return false
        if (messages.lastOrNull()?.id != replyId) return false
        if (message.content.isBlank() && message.timeline.isEmpty()) return false
        if (message.timeline.any {
            it is ReplyBlock.ToolUse || it is ReplyBlock.SearchResult || it is ReplyBlock.FetchResult
        }
        ) {
            return false
        }
        val attempts = (autoResumeCounts[replyId] ?: 0) + 1
        if (attempts > MaxAutoResume) return false
        autoResumeCounts[replyId] = attempts

        viewModelScope.launch {
            delay(AutoResumeDelayMs)
            /*
             * 延迟这段时间里用户可能又按了停止 —— 停止已经把这条消息判成
             * STOPPED，这里看状态，不看那个会被下一轮重置的布尔。
             */
            if (messages.firstOrNull { it.id == replyId }?.status == MessageStatus.STOPPED) return@launch
            runCatching { repository.resumeStreaming(replyId) }
            patch(replyId) { it.copy(status = MessageStatus.STREAMING, errorMessage = null) }
            retryHint = Res.get(R.string.stream_auto_resume, attempts, MaxAutoResume)
            startStream(conversationId, replyId, prefix = message)
        }
        return true
    }

    /**
     * 组装扩展通道的请求。
     *
     * 扩展通道的 `system` 只有一个字符串，且要求 user/assistant 严格交替，
     * 所以摘要**并进历史第一条 user 的正文**（见 [withRecap]）。
     * 语义与 OpenAI 通道一致：它在 system 之后、所有对话之前，
     * 就是 Operit 里那条 `SUMMARY` 轮的位置。
     */
    private suspend fun buildAnthropicRequestNow(prefix: UiMessage?): AnthropicRequest {
        val active = compaction
        val system = settings.composedSystemPrompt(presets)
        val requestTitle = shouldRequestTitle()
        if (requestTitle) serverTitleRequestedFor = currentConversationId

        /*
         * 续写时把这条 assistant 从历史里摘出去：它要以**末尾前缀**的身份出现，
         * 留在历史里就是同一轮回答发了两遍，服务端会把它当成两个不同的回答。
         */
        val outgoing = orderedOutgoingMessages(messages, active)
            .filter { it.id != prefix?.id }
        /*
         * 附件全发，与 OpenAI 通道口径一致：图片是对话内容，不是缓存。
         * 两条通道各写一份裁剪规则的话，迟早出现"换协议之后图片突然又没了"。
         */
        val path = outgoing.mapIndexed { index, message ->
            val content = if (index == 0 && active != null && active.summary.isNotBlank()) {
                withRecap(active.summary, message.content)
            } else {
                message.content
            }
            message.copy(
                content = content,
                attachments = if (message.attachments.isEmpty()) {
                    emptyList()
                } else {
                    attachFilesToWorkspace(outgoingAttachments(message.attachments))
                },
            )
        }

        /*
         * 官方域才发 DeepSeek 扩展字段（safety_check / 搜索工具 / 服务端标题 /
         * latest_reminder 角色 / thinking.type=disabled）。自建网关或 Claude 兼容中转
         * 遇到这些会 400，而不是"忽略" —— 见 `buildAnthropicRequest` 的说明。
         */
        val officialDeepSeek = isOfficialDeepSeekEndpoint(
            stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
        )
        return buildAnthropicRequest(
            model = settings.model.id,
            systemPrompt = system,
            path = path,
            /*
             * 服务端搜索工具**不再受一个独立开关控制**。
             *
             * 搜索已经由内置 skill 的 `web_search` / `web_open` 工具承担：
             * 那套工具带正则在本地抓、能读任意网页，模型自己会决定要不要用。
             * 再挂一个"向服务端声明 deepseek_web_search"的开关，只是让设置页
             * 多一行看不懂的选项，而且它只在官方 DeepSeek 域上有效。
             */
            searchEnabled = false,
            thinkingEnabled = settings.thinkingEnabled,
            requestTitle = requestTitle,
            latestReminder = latestReminder(),
            maxTokens = settings.maxTokens,
            prefix = prefix,
            deepSeekExtensions = officialDeepSeek,
            temperature = settings.temperature.toDouble(),
            topP = settings.topP.toDouble(),
        )
    }

    /**
     * 本轮的时间地点提醒。
     *
     * 每次请求现算 —— "今天几号"必须跟着真实时间走，缓存一次就会在跨日之后说谎。
     */
    private fun latestReminder(): String = createLatestReminder(
        now = java.time.LocalDateTime.now(),
        cjk = Res.bool(R.bool.ui_is_cjk),
        region = stored.locationRegion,
        languageLabel = Res.get(R.string.prompt_language_name),
    )

    /**
     * 这一轮要不要请服务端顺手起个标题。
     *
     * 三条护栏与 [maybeGenerateTitle] 完全一致：只在首轮、只在标题还是兜底值、
     * 每个会话只试一次。区别只是"谁来起名"（服务端 vs 自己再发一轮请求）。
     */
    private fun shouldRequestTitle(): Boolean {
        val id = currentConversationId ?: return false
        if (serverTitleRequestedFor == id) return false
        if (messages.count { it.role == MessageRole.USER } != 1) return false
        val current = currentConversationTitle ?: return true
        if (current.isBlank() || isAutoTitle(current)) return true
        val first = messages.firstOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        return current == derivedTitle(first, Res.get(R.string.conversation_default_title))
    }


    // ── 会话标题 ──────────────────────────────────────────

    /**
     * 已经为哪个会话生成过标题。
     *
     * 在**发请求之前**就记下来：失败不再重试。标题不值得为它反复烧 token，
     * 兜底标题本来就是能看的。
     */
    private var titleGeneratedFor: String? = null
    private var titleJob: Job? = null

    /**
     * 用 flash 档模型给会话起标题。
     *
     * 三条护栏，任何一条不满足就安静退出：
     * 1. **只在首轮**（会话里只有一条用户消息）—— 聊开了再改名字会让人莫名其妙；
     * 2. **标题还没被人改过** —— 用户自己改的名字永远优先；
     * 3. 每个会话只尝试一次。
     */
    private fun maybeGenerateTitle(conversationId: String) {
        if (titleGeneratedFor == conversationId) return
        if (isStreaming) return

        val users = messages.filter { it.role == MessageRole.USER }
        if (users.size != 1) return
        val firstUser = users.first()
        val reply = messages.firstOrNull {
            it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
        } ?: return

        val fallback = derivedTitle(firstUser.content, Res.get(R.string.conversation_default_title))
        val current = currentConversationTitle
        if (current != null && current != fallback && !isAutoTitle(current)) {
            // 用户已经改过名（或者这就是上一轮生成的结果）→ 别覆盖
            titleGeneratedFor = conversationId
            return
        }

        titleGeneratedFor = conversationId

        val material = buildString {
            appendLine(Res.get(R.string.prompt_role_user))
            appendLine(firstUser.content.take(TITLE_MATERIAL_CHARS))
            appendLine()
            appendLine(Res.get(R.string.prompt_role_assistant))
            append(reply.content.take(TITLE_MATERIAL_CHARS))
        }
        val model = titleModel()

        titleJob = viewModelScope.launch {
            val collected = StringBuilder()
            runCatching {
                chatApi.streamChat(
                    baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                    apiKey = stored.apiKey,
                    model = model,
                    messages = listOf(
                        OutMessageDto(role = "system", content = MessageContent.text(titleSystemPrompt())),
                        OutMessageDto(role = "user", content = MessageContent.text(material)),
                    ),
                    // 起标题不需要思考，也不该等它想
                    thinkingEnabled = false,
                    maxTokens = TITLE_MAX_TOKENS,
                    temperature = 0.3,
                    provider = providerKind,
                ).collect { event ->
                    if (event is StreamEvent.Content) collected.append(event.text)
                }
            }

            val title = cleanGeneratedTitle(collected.toString()) ?: return@launch

            // 请求期间用户可能自己改了名 —— 落库前再确认一次
            val storedTitle = repository.findConversation(conversationId)?.title
            if (storedTitle != null && storedTitle != fallback) return@launch

            repository.renameConversation(conversationId, title)
            // 不用在这里同步顶栏：observeTitle 那条流会送回来
        }
    }

    /**
     * 起标题用哪个模型：当前服务商里**带 flash 的那个**。
     *
     * 起标题是"短、快、便宜"的活。用主力大模型既慢又贵，
     * 而且大模型更容易把标题写成一段小作文。目录里没有 flash 档就退回当前模型。
     */
    private fun titleModel(): ModelInfo {
        // 目录只认端点真实返回的；没有就退回当前模型，别再拿内置表冒充
        val catalog = models
        return catalog.firstOrNull { it.id.contains("flash", ignoreCase = true) }
            ?: settings.model
    }

    private fun flush(id: String, reason: StringBuilder, content: StringBuilder) {
        if (reason.isEmpty() && content.isEmpty()) return
        if (id in stoppedReplyIds) {
            // 已经停下：缓冲区里剩下的字一个都不要了
            reason.clear()
            content.clear()
            return
        }
        val reasonDelta = reason.toString()
        val contentDelta = content.toString()
        reason.clear()
        content.clear()
        patch(id) {
            it.copy(
                reasoning = (it.reasoning.orEmpty() + reasonDelta).ifEmpty { null },
                content = it.content + contentDelta,
            )
        }
    }

    /** 只替换目标那一条，其余列表项保持引用不变 —— 避免整列表重组。 */
    private inline fun patch(id: String, transform: (UiMessage) -> UiMessage) {
        val index = messages.indexOfLast { it.id == id }
        if (index >= 0) messages[index] = transform(messages[index])
    }

    /**
     * 构造请求用的附件：**模型不支持图片时把图片摘掉**，只留文本附件。
     *
     * 服务端对非视觉模型直接 400（`messages.N.content.0: images are not supported
     * by this model`），而历史里带图的会话很常见（导入来的、换过模型的、
     * 以前用视觉模型聊过的）。让整条对话因为一张旧图发不出去，代价太大 ——
     * 所以按模型能力过滤，并给一句普通提醒（不是错误）。
     */
    private suspend fun outgoingAttachments(attachments: List<Attachment>): List<Attachment> {
        if (attachments.isEmpty()) return attachments
        /*
         * **不再按模型白名单事前剥图。**
         *
         * 那张表是我们自己填的：新模型、中转站改过名的、自建网关上的多模态模型
         * 全都不在里面 —— 于是用户明明能识图，图片却被本地摘掉，界面上还写着
         * "该模型不支持图片"。能力该由服务端回答，不由客户端的表决定：
         * 现在原样发，服务端真拒绝时由 ChatApi 去掉图片重试一次，并在正文里说明。
         */
        val filtered = attachments
        // 字节读回 + 图片按真实内容校正（格式不收的转码、坏图丢掉）。见 ApiImage
        val (prepared, unreadable) = repository.prepareRequestAttachments(filtered)
        if (unreadable > 0) banner = Res.get(R.string.images_skipped_unreadable, unreadable)
        return prepared
    }

    /**
     * 已经上传过的附件（会话 + 附件文件 → 工作区路径）。
     *
     * 历史里的附件**每一轮都会重新参与请求组装**，没有这层记忆的话，
     * 一个 80MB 的视频会在每次追问时重传一遍。进程重启后重传一次可以接受。
     */
    private val uploadedAttachments = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    /**
     * 把任意文件附件放进**当前工作区**，并给出一条模型可用的路径。
     *
     * - 云端会话：POST `/fs/upload` 到远端 `uploads/`（流式，不读进内存）；
     * - 内置 / 本机 / 容器：复制到 `<工作区>/uploads/`。
     *
     * 为什么非做不可：模型看不到 App 私有目录里的 attachments，也不能把视频
     * 塞进上下文。把它放进工作区之后，模型可以用 `run_shell`（ffprobe、解压、
     * 读二进制头）真正处理它 —— 这才是"什么文件都能附加"的完整语义。
     */
    private suspend fun attachFilesToWorkspace(
        attachments: List<Attachment>,
    ): List<Attachment> {
        if (attachments.none { it.isFile }) return attachments
        val conversation = currentConversationId
        val cloud = conversation?.let { CloudEnvs.envOf(it) }
        return attachments.map { attachment ->
            if (!attachment.isFile) return@map attachment
            withContext(Dispatchers.IO) {
                val key = "${conversation.orEmpty()}:${attachment.path.orEmpty()}"
                uploadedAttachments[key]?.let { return@withContext attachment.copy(remotePath = it) }
                runCatching {
                    val local = java.io.File(
                        Workspace.appDir(),
                        "attachments/${attachment.path.orEmpty()}",
                    )
                    if (!local.isFile) error("附件文件缺失")
                    val remote = if (cloud != null) {
                        val name = remoteUploadName(attachment.name)
                        RemoteWorkspaces.of(cloud).uploadFile("uploads", name, local)
                        "uploads/$name"
                    } else {
                        Workspace.storeUpload(local, attachment.name)
                    }
                    uploadedAttachments[key] = remote
                    attachment.copy(remotePath = remote)
                }.getOrElse { error ->
                    com.simplechat.app.util.AppLog.w(
                        "Files",
                        "附件上传失败：${attachment.name}",
                        error,
                    )
                    attachment
                }
            }
        }
    }

    /** 云端文件名加一段短时间戳：同名附件不互相覆盖。 */
    private fun remoteUploadName(raw: String): String {
        val safe = Workspace.sanitizeFileName(raw)
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        return if (ext.isEmpty()) "$base-$stamp" else "$base-$stamp.$ext"
    }

    /**
     * 自动压缩：**双阈值**，token 比例或消息条数，任一满足就整理旧对话。
     *
     * 只用 token 比例会漏掉一类会话：一百多条短对白，每条几十字，占用始终不高，
     * 但轮次密度太大 —— 模型对开头指令的注意力照样会散。Operit 也是这么分的
     * （`enableSummaryByMessageCount` + `summaryMessageCountThreshold`）。
     *
     * 触发点放在**发送前**而不是流式结束后：结束时上下文已经"用掉了"，
     * 下一轮还是超；发送前压，这一轮就轻了。压缩是异步的，不挡发送 ——
     * 它生成的摘要会从下一轮开始生效。
     *
     * 具体的阈值怎么算（可用预算、窗口分档、大窗口豁免）全在
     * [AutoCompactPolicy] 里，这里只负责调用。
     */

    private fun autoCompactIfNeeded() {
        if (!autoCompactEnabled) return
        if (compacting || isStreaming) return

        val candidates = compactionCandidates()
        val decision = compactDecision(candidates)
        if (!decision.shouldCompact) return

        /*
         * **迟滞**：上一次压缩之后，占用降到了 [compactionFloorTokens]。
         *
         * 只有当占用比那个下界**又高出 15%** 时才允许再压一次 ——
         * 否则一次压不动的长会话会被反复压、反复失败，而每一轮都在把
         * 同一段对话重写一遍，摘要越来越像"刚才发生了什么"，早先的设定
         * 反而被挤掉。
         *
         * 例外：已经越过保底线（[AutoCompactPolicy.Reason.HARD_LIMIT]）时
         * 不受迟滞限制 —— 那种情况下"再试一次"比"卡住"好。
         */
        val floor = compactionFloorTokens
        if (floor > 0 && decision.reason != AutoCompactPolicy.Reason.HARD_LIMIT) {
            val nextFloor = (floor * 1.15f).toInt()
            if (decision.usedTokens < nextFloor) return
        }

        compactContext()
    }

    /** 自上次摘要覆盖位置之后，用户说了几句。Operit 数的就是 `sender == "user"`。 */
    private fun userMessagesSinceCompaction(): Int {
        val covered = compaction
            ?.throughMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?: -1
        return messages.drop(covered + 1).count { it.role == MessageRole.USER && !it.streaming }
    }

    // ── Agent 模式 ────────────────────────────────────────

    /**
     * 开 / 关 Agent 模式。
     *
     * 立刻写盘：这个开关决定"下一条消息带不带工具"，而用户点完往往立刻就发。
     * 等异步回写再发，就可能出现"开关是开的、这一条却没带工具"。
     */
    /**
     * 气泡里可以切换的模型：只算用户勾选过的那些。
     *
     * 服务商一次能返回几十个模型（OpenCode Go 就有 30 多个），全塞进小气泡里
     * 是一次没意义的滚动。勾选放在设置页的对话框里做，气泡只负责"在几个常用
     * 的之间切换"。没勾过时回落到全部 —— 见 [ModelCatalog.visibleModels]。
     */
    val pickableModels: List<com.simplechat.app.net.ModelInfo>
        get() = ModelCatalog.visibleModels(providerKind, stored.modelIds, stored.pinnedModelIds, stored.modelId)

    /**
     * 所有配置 —— 模型面板按它分组。
     *
     * 一个中转站同时代理 GPT / Claude / GLM 时，按"模型家族"分组是没有意义的：
     * 用户真正关心的是"这个模型走哪把 Key、哪个端点"。配置就是这件事的边界。
     */
    val modelProfiles: List<com.simplechat.app.data.ModelProfile>
        get() = stored.profiles

    /** 当前生效的配置 id。 */
    val activeProfileId: String
        get() = stored.activeProfileId

    /** 某套配置里可以显示的模型（勾选过滤规则与当前配置一致）。 */
    fun modelsOf(profile: com.simplechat.app.data.ModelProfile): List<com.simplechat.app.net.ModelInfo> =
        com.simplechat.app.net.ModelCatalog.visibleModels(
            runCatching { com.simplechat.app.net.ProviderKind.valueOf(profile.providerId) }
                .getOrDefault(com.simplechat.app.net.ProviderKind.CUSTOM),
            profile.modelIds,
            stored.pinnedModelIds,
            profile.modelId,
        )

    /**
     * 跨配置选模型。
     *
     * **必须同时切配置**：选了 B 端点下的模型，却还用 A 端点的 Key 去请求，
     * 是这个功能最容易踩的坑。`setActiveModel` 在同一笔事务里把两件事都写掉。
     */
    fun pickProfileModel(profileId: String, modelId: String) {
        viewModelScope.launch { settingsStore.setActiveModel(profileId, modelId) }
    }

    /** 目录全集，供"选择模型"对话框列出。 */
    val allModels: List<com.simplechat.app.net.ModelInfo>
        get() = ModelCatalog.pickerModels(providerKind, stored.modelIds)

    /** 当前勾中的模型 id。空集表示"没选过 = 全部可选"。 */
    val pinnedModelIds: Set<String>
        get() = ModelCatalog.pinnedIds(providerKind, stored.pinnedModelIds)

    /** 保存勾选。整份覆盖 —— 勾选是"全集的子集"，增量合并做不成取消。 */
    fun setPinnedModels(ids: Set<String>) {
        viewModelScope.launch {
            settingsStore.setPinnedModels(providerKind.name, ids.toList())
        }
    }

    /** 快捷换模型。与设置页走同一条落盘路径，行为完全一致。 */
    fun pickModel(modelId: String) {
        viewModelScope.launch { settingsStore.setModel(modelId) }
    }

    /**
     * 思考档位循环切换：关闭 → high → max → 关闭。
     *
     * 输入栏那颗胶囊只有一格位置，做成循环比弹面板快得多 ——
     * 三档的切换是"再强一点"这种连续意图，点一下就该前进一格。
     */
    fun cycleReasoning() {
        val order = ReasoningLevel.sliderOrder
        val next = order[(order.indexOf(settings.reasoningLevel) + 1).mod(order.size)]
        viewModelScope.launch { settingsStore.setReasoningLevel(next.name) }
    }

    fun toggleAgentMode(enabled: Boolean) {
        agentMode = enabled
        viewModelScope.launch { settingsStore.setAgentMode(enabled) }
    }

    /** Agent 的说明：工具使用规范 + 工作区说明 + 已启用技能的目录 + 本轮被 @ 的能力。 */
    private fun agentSystemPrefix(mentions: List<MentionRef>, conversationId: String): String = buildString {
        val cloud = CloudEnvs.envOf(conversationId)
        append(
            AgentPrompt.system(
                root = if (cloud != null) "/（云端工作区根）" else (Workspace.rootOrNull()?.absolutePath ?: "files/workspace"),
                cloud = cloud != null,
                /*
                 * 两件事按**实际挂上去没有**说，不是按"支持不支持"说。
                 * 提示词里提到一个不存在的能力，比不提更糟：模型会去调它，然后
                 * 拿到"没有这个工具"，接着浪费两轮猜原因。
                 */
                linux = cloud == null && LinuxTools.available(),
                web = BuiltinSkills.enabled(BuiltinSkills.Browser),
                ssh = cloud?.isSsh == true,
                // 没绑工作区：提示词里不能出现任何路径，否则它会照着编
                plain = cloud == null && Workspace.current.value.kind == WorkspaceKind.NONE,
            ),
        )
        if (cloud != null) {
            /*
             * 云端工作区要说清三件事：文件在远端、路径是相对的、没有常驻 shell 状态。
             * 不说清楚，模型会写 `ls /sdcard`、`cd` 之后下一条命令还在原地。
             */
            appendLine()
            appendLine("当前工作区是云端环境「${cloud.name}」（${cloud.hostLabel}）。所有路径都相对于工作区根目录，写相对路径。")
            appendLine("每次 run_shell 都是独立会话，不保留 cd 和环境变量；需要连续状态就写进一条命令或用 && 串起来。")
        } else {
            val info = Workspace.current.value
            // NONE = 普通对话：目录还是内置那个，模型不需要知道"有没有绑过工作区"
            if (info.kind != WorkspaceKind.INTERNAL && info.kind != WorkspaceKind.NONE) {
                appendLine()
                appendLine(
                    when (info.kind) {
                        WorkspaceKind.CONTAINER -> "当前工作区是容器内的目录（${info.path}），按宿主路径直接读写即可。"
                        else -> "当前工作区绑定在本机目录（${info.path}）。"
                    },
                )
            }
        }
        val skills = runCatching { SkillStore.enabledPromptBlock() }.getOrDefault("")
        if (skills.isNotBlank()) {
            appendLine()
            appendLine()
            append(skills)
        }
        val directive = Mentions.directive(mentions, SkillStore.skills.value, McpStore.servers.value)
        if (directive.isNotBlank()) {
            appendLine()
            appendLine()
            append(directive)
        }
    }

    /**
     * 把 Agent 说明**并进第一条 system**，而不是新增一条。
     *
     * 相当一部分中转站只认第一条 system，多出来的直接丢。被丢掉时不会报错，
     * 表现是"模型完全不听工具规范"——这种静默失效最难查，所以宁可合并。
     */
    private suspend fun agentHistory(
        mentions: List<MentionRef>,
        conversationId: String,
    ): List<OutMessageDto> {
        val base = buildRequestMessages()
        val prefix = agentSystemPrefix(mentions, conversationId)
        val systemTexts = base.filter { it.role == "system" }
            .mapNotNull { msg -> runCatching { msg.content.jsonPrimitive.contentOrNull }.getOrNull() }
            .filter { it.isNotBlank() }
        val merged = buildString {
            append(prefix)
            systemTexts.forEach {
                appendLine()
                appendLine()
                append(it)
            }
        }
        return listOf(
            OutMessageDto(role = "system", content = MessageContent.text(merged)),
        ) + base.filter { it.role != "system" }
    }

    /**
     * Agent 的流式执行。
     *
     * 与 [startStream] 并列而不是塞进去：那边的分支已经有"OpenAI 通道 / Anthropic 通道"
     * 两套累加器，再叠一层工具循环会变成三套状态互相纠缠。这里单独一条路径，
     * 共用同一套 `patch` / 落库 / 统计，反而两边都好读。
     */
    private fun startAgentStream(
        conversationId: String,
        replyId: String,
        mentions: List<MentionRef> = emptyList(),
    ) {
        val epoch = ++streamEpoch
        isStreaming = true
        streamConversationId = conversationId
        ActiveRuns.start(conversationId, epoch)
        // 对同一条回复重新发起（继续生成）：解除它的停止封印
        stoppedReplyIds -= replyId
        lastCheckpointAt = System.currentTimeMillis()

        streamJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var firstTokenAt: Long? = null
            var contentChars = 0
            var calls: List<ToolCallUi> = emptyList()
            var lastFlush = 0L
            var failure: String? = null

            val content = StringBuilder()
            val reasoning = StringBuilder()

            /**
             * 此刻模型是不是正在"想"。
             *
             * 不能再用 `正文非空` 判断思考是否结束：Agent 是多轮的，第 2、3 轮
             * 会**在已有正文之后**重新思考。旧判据会把这些新思考钉死在
             * 「已完成」状态 —— 思考栏收起不动，用户以为它卡住了。
             */
            var thinkingNow = false

            /*
             * 工具卡与思考的**真实顺序**坐标（见 `ToolCallUi.round` / `roundStartChars`）。
             *
             * Agent 的一轮是「思考 → 说话 → 动手」，下一轮又从「思考」开始。
             * 只靠正文偏移排不出这个顺序：第二轮开始时正文可能还是空的（偏移 0），
             * 与第一轮开头撞在一起。所以另外记「第几轮」与「本轮开始时正文多长」，
             * 渲染侧才能把每段思考放回它那一轮的前面。
             */
            var currentRound = 0
            var roundStartChars = 0
            /** 已经开始的思维链段数（每轮最多一段）。 */
            var reasoningSegments = 0
            /** 本轮是否已经开始写思维链。 */
            var reasoningSegmentOpen = false

            /**
             * 真实的思考时长（毫秒）。
             *
             * 旧值是**整轮耗时**（含工具执行），标题写「用时 30 秒」而模型其实
             * 只想了 5 秒，剩下 25 秒在跑命令。这里按「第一段思考字符 → 正文或
             * 工具的第一个事件」逐轮累加，才是真的思考时间。
             */
            var thinkingStartedAt: Long? = null
            var thinkingTotalMs = 0L
            fun closeThinking() {
                val started = thinkingStartedAt ?: return
                thinkingTotalMs += (System.currentTimeMillis() - started).coerceAtLeast(0)
                thinkingStartedAt = null
            }

            fun flush(force: Boolean = false) {
                if (replyId in stoppedReplyIds) return
                val now = System.currentTimeMillis()
                if (!force && now - lastFlush < STREAM_THROTTLE_MS) return
                lastFlush = now
                val text = content.toString()
                val think = reasoning.toString()
                patch(replyId) {
                    it.copy(
                        content = text,
                        reasoning = think.ifEmpty { null },
                        reasoningDone = !thinkingNow,
                        toolCalls = calls,
                    )
                }
            }

            /*
             * 整条流程包在 try 里，只为接住**用户点停止**那条路径。
             *
             * 协程取消会在下一个挂起点抛出，后续的 flush / patch / finishMessage
             * 全被跳过 —— 于是最后一次落库停在"工具正在跑"，卡片上的圈永远转下去。
             * catch 里把未完成的调用收口成「已停止」，再原样抛出，取消语义不变。
             */
            try {
            val history = runCatching { agentHistory(mentions, conversationId) }.getOrElse {
                failure = it.message ?: "组装请求失败"
                emptyList()
            }

            if (failure == null) {
                /*
                 * 工具集合按本轮情况裁剪：
                 * - @ 了某个 MCP → 只给它那一族的工具，别让模型在几十个工具里挑
                 * - 无障碍没连上 → 不挂屏幕工具（挂上去只会每次调用都失败）
                 */
                val pickedServers = mentions
                    .filter { it.kind == MentionKind.MCP }
                    .map { it.key }
                    .toSet()
                    .takeIf { it.isNotEmpty() }

                /*
                 * 会话绑了云端环境 → 文件与命令类工具换成远端那一套。
                 *
                 * 不混用：本地 read_file 读的是这台手机，云端 read_file 读的是那台机器，
                 * 同名不同义的工具同时挂上去，模型会挑错，而且用户看不出来它挑错了。
                 * MCP / 屏幕工具照旧 —— 它们本来就不属于"工作区"这一层。
                 */
                val cloud = CloudEnvs.envOf(conversationId)
                val remote = cloud?.let { RemoteWorkspaces.of(it) }
                /*
                 * 云端会话只挂云端工具。联网检索不在这里做第二份：手机端的
                 * `web_search` 已经覆盖了这个需求，出口还是用户自己的网络；
                 * 在服务器上再实现一套，等于把同一件事做两遍，还要多维护一份
                 * 解析逻辑和一套 SSRF 防护。
                 */
                val workspaceTools = if (remote != null) {
                    CloudTools.all(remote) + RewindTool
                } else {
                    localTools() + if (ScreenAutomation.isAvailable()) ScreenTools.all() else emptyList()
                }
                /*
                 * 定时任务工具**不跟着工作区走**：它操作的是这台手机的系统闹钟，
                 * 跟"文件在本地还是云端"没有关系。云端会话里也该能设提醒。
                 */
                val tools = workspaceTools + ScheduleTools.all() + McpStore.agentTools(pickedServers)

                val runner = AgentRunner(
                    chatApi = chatApi,
                    tools = tools,
                    /*
                     * **不设轮数上限**。0（默认）= 一直跑到模型自己说"做完了"。
                     *
                     * 原先 0 会被兜成 8 轮，于是在需要"读十几个文件再动手"的任务里，
                     * 第八轮就被硬切断，还得用户说一句"继续"。刹车交给用户手上的
                     * 停止键 —— 那是唯一知道"这件事值不值得再烧下去"的人。
                     */
                    maxRounds = stored.agentMaxRounds.takeIf { it > 0 } ?: Int.MAX_VALUE,
                    policy = ApprovalPolicy(mode = approvalMode),
                    gate = approvalGate,
                )

                runner.run(
                    baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                    apiKey = stored.apiKey,
                    model = settings.model,
                    provider = providerKind,
                    history = history,
                    thinkingEnabled = settings.thinkingEnabled,
                    reasoningEffort = settings.reasoningEffort,
                    maxTokens = settings.maxTokens,
                    temperature = settings.temperature.toDouble(),
                    topP = if (settings.thinkingEnabled) settings.topP.toDouble() else null,
                    fast = settings.fast,
                    conversationId = conversationId,
                    /*
                     * 文件快照的两个坐标：
                     * - messageId 让每一条 AI 回复成为一条可回滚的边界
                     * - remote/scope 让云端会话的快照落到同一套索引里，
                     *   但按 env 隔离 —— 两个云环境里都有 `src/main.py` 时不能串
                     */
                    messageId = replyId,
                    remote = remote,
                    scope = cloud?.let { "cloud:${it.id}" } ?: "local",
                    /*
                     * Anthropic 通道的工具循环属于另一套块协议，这里仍走 Chat
                     * Completions；Responses 显式切换，不能靠"模型名像 GPT"去猜。
                     */
                    apiStyle = if (apiStyle == ApiStyle.RESPONSES) {
                        ApiStyle.RESPONSES
                    } else {
                        ApiStyle.OPENAI
                    },
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Content -> {
                            if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                            if (thinkingNow) closeThinking()
                            thinkingNow = false
                            content.append(event.text)
                            contentChars += event.text.length
                            flush()
                        }

                        is AgentEvent.Reasoning -> {
                            thinkingNow = true
                            if (thinkingStartedAt == null) {
                                thinkingStartedAt = System.currentTimeMillis()
                            }
                            /*
                             * 每轮思维链各占一段，段与段之间插 `ReasoningSeparator`。
                             *
                             * **不在 `RoundStart` 里预先插**：很多轮模型根本不想（直接说话
                             * 或直接调工具），预先插就会留下空段，段号与轮次越错越远 ——
                             * 后面那轮的思考会被挂到更早的工具卡下面。
                             */
                            if (!reasoningSegmentOpen) {
                                if (reasoning.isNotEmpty()) reasoning.append(ReasoningSeparator)
                                reasoningSegmentOpen = true
                                reasoningSegments++
                            }
                            reasoning.append(event.text)
                            flush()
                        }

                        /*
                         * 新一轮开始：给上一轮收个尾。
                         *
                         * 不加这一步，第二轮的正文会直接接在第一轮末尾 ——
                         * 「我先看看文件」+「看完了，结论是…」读起来是一句话。
                         * 补一个空行是最轻的处理：既断开段落，又不插任何可见分隔符。
                         * 已经以换行结尾的（大多数情况）就不重复补。
                         */
                        is AgentEvent.RoundStart -> {
                            if (content.isNotEmpty() && !content.endsWith("\n")) content.append("\n\n")
                            /*
                             * 新一轮的坐标。
                             *
                             * `roundStartChars` 必须在这一刻取：再晚一点正文就开始长了，
                             * 早一点又还没补上一轮结尾的换行 —— 两个都会让本轮的思考
                             * 落到上一段正文的中间。
                             */
                            closeThinking()
                            currentRound = event.index - 1
                            roundStartChars = content.length
                            reasoningSegmentOpen = false
                            thinkingNow = false
                        }

                        is AgentEvent.ToolStarted -> {
                            /*
                             * 工具开始 = 这一轮思考已经结束。
                             *
                             * `thinkingNow` 原先只在收到正文时归位，于是模型
                             * "先想、再调工具"的那种回合里，工具卡片已经在跑，
                             * 思考栏还挂着「正在思考」的尾巴。
                             */
                            closeThinking()
                            thinkingNow = false
                            /*
                             * 悬停条只在**动屏幕**时挂上，不是所有工具都挂。
                             *
                             * 读文件、跑命令这些在 App 里就能看到进度；而一旦它去点
                             * 别人的界面，用户就彻底失明了 —— 那才是这条胶囊存在的理由。
                             */
                            if (ScreenAutomation.isScreenTool(event.call.name)) {
                                ScreenAutomation.beginRound()
                            }
                            com.simplechat.app.util.AppLog.i(
                                "Tool",
                                "开始 ${event.call.name} ${event.call.argumentsJson.take(300)}",
                            )
                            calls = calls + ToolCallUi(
                                id = event.call.id,
                                name = event.call.name,
                                summary = toolSummary(event.call.name, event.call.argumentsJson),
                                status = ToolCallUi.Status.RUNNING,
                                // 风险等级由审批事件补上：它在 invoke() 里才算得出来
                                // （同一个工具会因参数升降档，比如 run_shell 的 privileged）
                                argsJson = event.call.argumentsJson,
                                // 占位框按"这次真正会请求的比例"画，见 imageAspectOf
                                aspectRatio = imageAspectOf(
                                    name = event.call.name,
                                    argsJson = event.call.argumentsJson,
                                    model = imageApi?.model,
                                    fallbackSize = imageApi?.size,
                                ),
                                // 记在这一刻的正文长度上，渲染时据此把卡片插回原位
                                afterChars = content.length,
                                round = currentRound,
                                roundStartChars = roundStartChars,
                                // -1 = 本轮没有思考；不能用"下一段的序号"，那会和后面的段撞车
                                reasoningIndex = if (reasoningSegmentOpen) {
                                    reasoningSegments - 1
                                } else {
                                    -1
                                },
                            )
                            // 工具是"事件"，必须立刻显示，不能等节流
                            flush(force = true)
                        }

                        /*
                         * 等待用户批准：卡片原地变成"等确认"。
                         *
                         * 不弹独立对话框 —— 需要看参数才知道该不该点，而参数正在那张卡片里。
                         */
                        is AgentEvent.ApprovalPending -> {
                            calls = calls.map { call ->
                                if (call.id != event.callId) {
                                    call
                                } else {
                                    call.copy(
                                        status = ToolCallUi.Status.AWAITING,
                                        risk = event.request.risk,
                                    )
                                }
                            }
                            flush(force = true)
                        }

                        is AgentEvent.ApprovalCleared -> {
                            calls = calls.map { call ->
                                if (call.id == event.callId && call.status == ToolCallUi.Status.AWAITING) {
                                    call.copy(status = ToolCallUi.Status.RUNNING)
                                } else {
                                    call
                                }
                            }
                            flush(force = true)
                        }

                        is AgentEvent.ToolFinished -> {
                            calls = calls.map { call ->
                                if (call.id != event.callId) {
                                    call
                                } else {
                                    call.copy(
                                        status = if (event.ok) ToolCallUi.Status.OK else ToolCallUi.Status.FAILED,
                                        output = event.output,
                                        elapsedMs = event.elapsedMs,
                                        diff = event.diff,
                                        images = event.files,
                                        artifacts = event.artifacts,
                                    )
                                }
                            }
                            flush(force = true)
                        }

                        is AgentEvent.Retrying -> {
                            retryHint = Res.get(R.string.stream_retrying, event.attempt, event.max)
                        }

                        is AgentEvent.Failed -> {
                            failure = event.message
                            com.simplechat.app.util.AppLog.e("Agent", "本轮失败：${event.message}")
                        }

                        else -> Unit
                    }
                }
            }

            flush(force = true)

            val status = when {
                stoppedEpoch == epoch -> MessageStatus.STOPPED
                failure != null -> MessageStatus.ERROR
                else -> MessageStatus.DONE
            }
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            val ttft = firstTokenAt?.let { it - startedAt }
            // 中文约 1.5 字符/token，取 4 是保守估计（宁可低估速度，不要虚高）
            val tps = if (contentChars > 0) (contentChars / 4f) / (elapsed / 1000f) else null
            closeThinking()
            val thinkingMs = if (reasoning.isNotEmpty()) thinkingTotalMs.coerceAtLeast(1) else null

            patch(replyId) {
                it.copy(
                    status = status,
                    errorMessage = failure,
                    toolCalls = calls,
                    ttftMs = ttft,
                    tps = tps,
                    reasoningSeconds = thinkingMs?.let { ms -> thinkingSeconds(ms) },
                )
            }

            val finalContent = content.toString()
            val finalReasoning = reasoning.toString().ifEmpty { null }

            withContext(NonCancellable) {
                runCatching {
                    repository.finishMessage(
                        id = replyId,
                        content = finalContent,
                        reasoning = finalReasoning,
                        status = status,
                        errorMessage = failure,
                        ttftMs = ttft,
                        tps = tps,
                        thinkingMs = thinkingMs,
                        /*
                         * 工具调用跟着一起落库。
                         *
                         * 只留在内存里的话，用户杀掉 App 再进来，这一段就
                         * 只剩正文 —— "它读了哪个文件、跑了哪条命令"全没了。
                         * 过程也是这段对话的一部分，尤其 Agent 模式。
                         */
                        toolCalls = calls,
                    )
                    repository.touchConversation(conversationId)
                }
            }

            /*
             * 只有"这仍是当前会话的那一轮"才回写界面状态。
             *
             * 用户切走之后这轮转到了后台（detachRun），此时 `isStreaming`
             * 可能已经被新会话占用 —— 无脑置 false 会把新会话的转圈按灭。
             */
            // 同上（见 [streamEpoch]）：流状态只由当前轮回写，转圈按轮次号摘自己那一格
            if (epoch == streamEpoch) {
                isStreaming = false
                streamJob = null
                streamConversationId = null
                retryHint = null
                ScreenAutomation.endRound()
            }
            ActiveRuns.finish(conversationId, epoch)
            if (backgroundRuns[conversationId] === coroutineContext[Job]) {
                backgroundRuns.remove(conversationId)
            }
            // Agent 这一轮收尾后，同样把排队消息接着发出去
            if (currentConversationId == conversationId && !switching) drainQueue(conversationId)

            if (status == MessageStatus.DONE) maybeGenerateTitle(conversationId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                val settled = calls.map { call ->
                    if (call.status == ToolCallUi.Status.RUNNING ||
                        call.status == ToolCallUi.Status.AWAITING
                    ) {
                        call.copy(
                            status = ToolCallUi.Status.FAILED,
                            output = Res.get(R.string.tool_cancelled),
                        )
                    } else {
                        call
                    }
                }
                withContext(NonCancellable) {
                    /*
                     * 用户按下停止时，思考计时也要收口。
                     *
                     * 否则这一条永远停在"已深度思考"（没有用时）—— 明明已经想了
                     * 十几秒，标题却像什么都没发生过。
                     */
                    closeThinking()
                    val stoppedThinking = if (reasoning.isNotEmpty()) {
                        thinkingSeconds(thinkingTotalMs.coerceAtLeast(1))
                    } else {
                        null
                    }
                    patch(replyId) {
                        it.copy(
                            status = MessageStatus.STOPPED,
                            toolCalls = settled,
                            reasoningDone = true,
                            reasoningSeconds = stoppedThinking,
                        )
                    }
                    runCatching {
                        /*
                         * 落库用**屏幕上已经显示的那份**，不是缓冲区。
                         *
                         * 停止之后就不再往气泡里写字了（见 `stoppedReplyId`），
                         * 但网络层可能还塞了几个 token 进 builder。存进去的话，
                         * 重开 App 会看到比停止那一刻更多的内容 —— 界面上截断了，
                         * 库里没截断，是最难解释的一种不一致。
                         */
                        val shown = messages.firstOrNull { it.id == replyId }
                        repository.finishMessage(
                            id = replyId,
                            content = shown?.content ?: content.toString(),
                            reasoning = shown?.reasoning?.ifBlank { null } ?: reasoning.toString().ifEmpty { null },
                            status = MessageStatus.STOPPED,
                            thinkingMs = if (reasoning.isNotEmpty()) thinkingTotalMs else null,
                            toolCalls = settled,
                        )
                    }
                }
                throw cancelled
            }
        }
    }

    /**
     * 把摘要并进一条 user 消息的正文前面。
     *
     * 围栏文字读起来是"以下是早前对话的摘要"，然后空一行接用户原话 ——
     * 与 Operit 把摘要当成一条独立 user 轮的效果一致，但不新增消息，
     * 严格交替的通道（Anthropic 兼容）也不会被打回。
     */
    private fun withRecap(summary: String, text: String): String = buildString {
        append(Res.get(R.string.prompt_recap_intro))
        append(summary.trim())
        if (text.isNotBlank()) {
            append("\n\n")
            append(text)
        }
    }

    private suspend fun buildRequestMessages(): List<OutMessageDto> {
        val request = mutableListOf<OutMessageDto>()

        val system = settings.composedSystemPrompt(presets)
        if (system.isNotBlank()) {
            request += OutMessageDto(role = "system", content = MessageContent.text(system))
        }

        /*
         * 摘要不再单独作为一条 system 消息。
         *
         * Operit 把 `SUMMARY` 轮映射成 **user**（`OpenAIProvider` 的
         * `PromptTurnKind.USER, SUMMARY -> "user"`）：摘要是"用户提供的前情提要"，
         * 不是系统指令。塞进 system 有两个实际问题 —— 多数中转站只认第一条
         * system，多出来的直接丢；而模型会把一段叙事当成规则来遵守。
         *
         * 也不新增独立消息：Anthropic 兼容通道要求 user/assistant 严格交替。
         * 所以并进**历史第一条 user** 的正文前面，位置恰好就是 Operit 那条
         * summary 轮的位置（system 之后、所有对话之前）。
         */
        val active = compaction
        val ordered = orderedOutgoingMessages(messages, active)
        val firstUserIndex = ordered.indexOfFirst {
            it.role == MessageRole.USER && isSendableOutgoing(it)
        }

        ordered.forEachIndexed { index, message ->
            /*
             * 空消息不发：历史里出错/被中断的 assistant、既没正文也没附件的 user
             * 是可能存在的，而部分兼容网关对空 `content` 直接 400 ——
             * 一条空消息不值得让整段历史发不出去（见 `isSendableOutgoing`）。
             */
            if (!isSendableOutgoing(message)) return@forEachIndexed
            // assistant 走 outgoingAssistantText：空正文 + 有工具过程 / 被中断的轮次不能丢
            val rawText = if (message.role == MessageRole.ASSISTANT) {
                outgoingAssistantText(message)
            } else {
                message.content
            }
            val stamped = withTimestamp(message, rawText)
            val text = if (index == firstUserIndex && active != null && active.summary.isNotBlank()) {
                withRecap(active.summary, stamped)
            } else {
                stamped
            }

            request += OutMessageDto(
                role = if (message.role == MessageRole.USER) "user" else "assistant",
                /*
                 * 只有 user 消息可能带图；组装规则见 `userContent`
                 * （无附件 → 纯字符串；只有文本附件 → 纯字符串；有图 → content 数组）。
                 *
                 * assistant 消息即使历史里挂着附件也只发正文：图片放进 assistant
                 * 在协议里是不合法的（会 400），而 AI 回复本来也不该带图。
                 */
                /*
                 * **附件一律全发，不再按轮次裁剪。**
                 *
                 * 上一版把"图片只发最近 2 轮、文件最近 3 轮"当成省钱手段，
                 * 代价是**换模型之后旧图全部消失**：用户说的"它不记得那张图"
                 * 不是模型失忆，是我们根本没发。图片是对话内容，不是可回收的
                 * 缓存 —— 要省 token 就在压缩那一层省，不能偷偷从记忆里删。
                 */
                content = if (message.role != MessageRole.USER || message.attachments.isEmpty()) {
                    MessageContent.text(text)
                } else {
                    userContent(text, attachFilesToWorkspace(outgoingAttachments(message.attachments)))
                },
            )
        }

        /*
         * 主动开口：追加一条**不落库**的 user 指令。
         *
         * 只在末尾是 assistant 时追加 —— Anthropic 兼容通道要求 user/assistant
         * 严格交替，末尾已经是 user 时再塞一条会直接 400。
         */
        pendingProactiveNudge?.let { nudge ->
            if (ordered.lastOrNull()?.role == MessageRole.ASSISTANT) {
                request += OutMessageDto(role = "user", content = MessageContent.text(nudge))
            }
            pendingProactiveNudge = null
        }

        return request
    }

    /**
     * 给用户消息加时间戳前缀（「时间戳」技能开启时）。
     *
     * 为什么要走请求层而不是往正文里写：用户看到的必须还是他自己那句话。
     * 时间取**落库时间**，所以历史消息各自带着"当时几点"，模型能看出间隔。
     */
    private fun withTimestamp(message: UiMessage, text: String): String {
        if (message.role != MessageRole.USER) return text
        if (text.isBlank()) return text
        if (!BuiltinSkills.enabled(BuiltinSkills.Timestamp)) return text
        val at = message.createdAt.takeIf { it > 0L } ?: return text
        val label = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEE", java.util.Locale.getDefault())
                .format(java.util.Date(at))
        }.getOrNull() ?: return text
        return "[$label] $text"
    }

    // ── 主动发消息 ────────────────────────────────────────

    /** 现在适合主动开口吗（技能开着、有空会话内容、没在流式）。 */
    fun canSpeakProactively(): Boolean {
        if (isStreaming) return false
        if (!BuiltinSkills.enabled(BuiltinSkills.Proactive)) return false
        if (proactiveChance <= 0) return false
        if (!stored.hasKey && providerKind.requiresApiKey) return false
        if (stored.modelId.isBlank()) return false
        // 一句都没说过就主动开口，像陌生人突然搭话 —— 必须先有对话基础
        if (messages.none { it.role == MessageRole.USER }) return false
        // 末尾必须是"AI 说过话"，否则模型会把这条指令当成续写用户的话
        val last = messages.lastOrNull()
        return last != null && last.role == MessageRole.ASSISTANT && last.content.isNotBlank()
    }

    /** 空闲多久后才考虑主动（毫秒）。界面用它算倒计时。 */
    fun proactiveIdleMillis(): Long = proactiveIdleMinutes.coerceIn(5, 240) * 60_000L

    /**
     * 这一轮要不要开口：按概率掷一次。
     *
     * 概率是**按小时**配的（用户理解"每小时 25%"），而检查是每 [tickMillis]
     * 一次 —— 所以每次检查要按时间窗口折算，否则每 30 秒掷一次 25%，
     * 一小时会被触发十几次。
     */
    fun rollProactive(tickMillis: Long = 3_600_000L): Boolean {
        if (!canSpeakProactively()) return false
        val perTick = proactiveChance / 100.0 * (tickMillis.toDouble() / 3_600_000.0)
        return kotlin.random.Random.nextDouble() < perTick.coerceIn(0.0, 1.0)
    }

    /**
     * 主动说一句。
     *
     * 与普通发送的区别只有一个：没有用户消息，所以要先起一条 assistant 行，
     * 再把"你来说点什么"作为一条不落库的 user 指令附在请求末尾。
     */
    fun proactiveSpeak() {
        if (!canSpeakProactively()) return
        viewModelScope.launch {
            switchJob?.join()
            val conversationId = ensureConversation()
            val parent = messages.lastOrNull()?.id.orEmpty()
            val replyId = repository.beginAssistantMessage(
                conversationId = conversationId,
                parentId = parent,
                model = settings.model.id,
            )
            messages += UiMessage(
                id = replyId,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.STREAMING,
                parentId = parent,
                model = settings.model.id,
                createdAt = System.currentTimeMillis(),
            )
            pendingProactiveNudge = Res.get(R.string.prompt_proactive_nudge)
            if (agentMode) {
                startAgentStream(conversationId, replyId)
            } else {
                startStream(conversationId, replyId)
            }
        }
    }

    /**
     * 供顶栏标题使用。
     *
     * 优先用库里的标题（用户可能重命名过）；尚未落库的新对话才从首条用户消息推导。
     */
    fun conversationTitle(): String {
        currentConversationTitle?.let { return it }
        val first = messages.firstOrNull { it.role == MessageRole.USER }?.content?.trim()
        if (first.isNullOrEmpty()) return Res.get(R.string.conversation_default_title)
        return first.lineSequence().first().trim().take(20).ifBlank { Res.get(R.string.conversation_default_title) }
    }

    companion object {

        /**
         * 流式增量的提交间隔（毫秒）。
         *
         * **16ms ≈ 一帧**，对齐官方 Chat Demo 的 `requestAnimationFrame` 节拍：
         * 那一层是"**至多一帧一次**"，而不是"每 N 毫秒硬跳一次"。
         *
         * 早先这里是 50（20Hz）。问题是**两把尺子**：数据层 50ms 一提交，
         * 渲染层再按自己的 50ms 采样一次，两个独立时钟互相 beat ——
         * 相邻两次屏幕更新实际落在 50～100ms 之间抖，看着就是一跳一跳。
         * 官方只有 rAF 一把尺子，所以是连续流动的。
         *
         * 单次提交的**重活由渲染层兜住**：`MarkdownText` 现在按帧耗时自适应
         * 冷却（`adaptiveCooldownMs`），一帧被拖长就自动少跳几帧。
         * 也就是说"提频"与"防卡"分别由两层各管一段，而不是靠一个保守的常数两头兼顾。
         *
         * ⚠️ 真机上若出现滚动掉帧，先量一次帧时间再回调这个值 ——
         * 而不是凭感觉改回 50（那会连"连续"一起丢掉）。
         */
        private const val STREAM_THROTTLE_MS = 16L

        private const val CHECKPOINT_INTERVAL_MS = 1_500L

        /**
         * 会话级设置的落库防抖。
         *
         * 补充提示词是**逐字输入**的，改一次写一次库没必要；
         * 停手 400ms 再写，既跟得上手感也不会把主线程拖住。
         */
        private const val SETTINGS_PERSIST_DEBOUNCE_MS = 400L

        /**
         * 上下文统计的更新防抖。
         *
         * 统计要扫全部正文估 token；流式期间消息每 16ms 变一次，逐次重算会把主线程
         * 吃穿。输入栏上那行数字晚 250ms 更新，用户完全感知不到。
         */
        private const val ContextStatsDebounceMs = 250L

        /**
         * 压缩用的系统提示词见 [ContextSummary]（随界面语言）。
         *
         * 反复强调"设定 / 情节 / 文风"三件事，是因为长篇创作里丢失它们的代价
         * 远大于丢失具体措辞 —— 措辞模型能再生成，人名地名和人物关系不能。
         */

        fun factory(
            chatApi: ChatApi,
            settingsStore: SettingsStore,
            repository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(chatApi, settingsStore, repository)
            }
        }
    }
}

private fun String.toConversationModeSafe(): ConversationMode =
    runCatching { ConversationMode.valueOf(this) }.getOrDefault(ConversationMode.CREATIVE)

/**
 * 思考耗时 → 展示用秒数。
 *
 * 四舍五入，再兜底到 1：直接截断会把 0.9 秒的思考显示成「0 秒」，
 * 看着像计时坏了。
 *
 * `internal` 是为了能单测 —— 取整边界是最容易写错、又最不容易被发现的地方。
 */
internal fun thinkingSeconds(ms: Long): Long = ((ms + 500) / 1000).coerceAtLeast(1)








