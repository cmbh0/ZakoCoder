package com.simplechat.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AttachmentImport
import com.simplechat.app.data.Res
import com.simplechat.app.data.userMessage
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.ui.common.ReadAloudEnabled
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.agent.ApprovalMode
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.dismissKeyboardOnTouch
import com.simplechat.app.ui.markdown.warmMarkdownCache
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxHeight

/*
 * 输入栏下方最多同时展开一个面板。
 *
 * 少了 MODEL：模型与思考档位已经搬到顶栏浮层（`ChatTopBar` 的 modelPanel），
 * 不再占输入框上方那块地方 —— 那里展开会把对话顶掉半屏。
 */
enum class InputPanelState { NONE, SETTINGS, ATTACH }

/** 主动消息的空闲检查间隔。30 秒：够省电，也不至于错过"刚离开"的窗口。 */
private const val ProactiveTickMs = 30_000L

/** 切换会话的淡入时长。**快** —— 慢一点就从"过渡"变成"等待"了。 */
private const val SwitchFadeInMs = 160

/** 换会话时等列表真正贴底的上限。超过就先淡入，别把白屏停太久。 */
private const val BottomSettleMs = 900L

/**
 * 判定"超长对话"的阈值（单条消息字符数）。
 *
 * 只有这种对话才给加载态 —— 因为拖慢首次布局的是**单条消息的文字排版**
 * （一条两万字的段落要排出上千行），不是对话有多少条。
 * 普通长对话（几十条短消息）直接出内容，不闪圈（对齐官方）。
 */
private const val HugeMessageChars = 20_000

/** 换会话时，慢过这么久才显示转圈 —— 短会话不该闪一下圈圈。 */
private const val SpinnerDelayMs = 120L

/**
 * 预热 Markdown 解析时，尾部消息超过这么多字符就不预热。
 *
 * 预热的意义是"打开就能看到的那一条不用现场解析"；一条几万字的消息本身就要
 * 几百毫秒解析，替它预热只会把后台线程占满、让真正的首屏更晚。
 */
private const val WarmTailMaxChars = 24_000

/** 等新会话内容就位的兜底上限。超过就当它已经好了，不能让界面停在透明态。 */
private const val SwitchLoadTimeoutMs = 2_000L

/** 打字时草稿落库的防抖间隔。与「会话标题」那条同档。 */
private const val DraftSaveDelayMs = 400L

/**
 * 输入栏**下面**那条渐变的高度（从屏幕底边往上量）。
 *
 * 只盖底部一条：正文滚到底就在这里化开。输入栏以上不铺任何遮罩 ——
 * 那会把正在读的字从中间洗掉。
 *
 * 36dp 那档太窄：深色页面上几乎看不出过渡，正文像被硬边切了一刀。
 * 放到 64dp 才有"化开"的感觉，也才够盖住输入栏圆角外那圈空隙。
 */
private val BottomFadeHeight = 64.dp

/**
 * 淡出带整体下移量：正文多留一点实字，贴着输入栏才淡完（尾段落在输入栏背后）。
 *
 * 取 0：羽化从**输入栏顶边**起算，不分走正文的可见高度。
 */
private val BottomFadeDrop = 0.dp

/**
 * 对话主界面。
 *
 * 消息与流式全部由 [ChatViewModel] 驱动；本文件只负责布局与交互。
 */
/** 输入超过这个字符数就自动转成 txt 附件。约 3–4k token，再长会明显拖慢每轮请求。 */
private const val AutoTxtChars = 4000

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenWorkspace: () -> Unit = {},
    /**
     * 顶栏「文件管理」。
     *
     * 与 [onOpenWorkspace] 分开：那个是"改工作区绑定"，这个是"看文件"。
     * 当前对话绑了云端环境时，走的是完全不同的页面（云端没有本地 File）。
     */
    onOpenFiles: () -> Unit = {},
    /** 设置页。模型浮层底部的「管理配置」跳这里（API 与模型目录都在那页）。 */
    onOpenSettings: () -> Unit = {},
    /** 云端环境管理页（工作区浮层底部的入口）。 */
    onOpenCloud: () -> Unit = {},
    /** 终端。从顶栏 ⋯ 进 —— 它是干活的地方，不属于"设置"。 */
    onOpenTerminal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val context = LocalContext.current

    var panel by remember { mutableStateOf(InputPanelState.NONE) }
    var input by remember { mutableStateOf("") }

    /**
     * 预设页开着没有。
     *
     * 与 [panel] 分开：那几个面板挂在输入栏上方、和键盘互斥；预设页是一整页
     * 的浮层（Dialog），开的时候输入栏还在下面，关掉就回到原样。
     */
    var presetsOpen by remember { mutableStateOf(false) }

    /*
     * 抽屉投递过来的两条指令。
     *
     * 抽屉罩在对话页之上，抽屉里点「工具审批」「对话设置」时，真正要动的东西
     * 都在这一页里。管道是单向的：抽屉投递 → 这里消费 → 关掉抽屉后立刻生效。
     * 用 `LaunchedEffect(键)` 而不是在组合里直接判断：置状态是副作用，
     * 写在组合体里会在同一帧反复触发重组。
     */
    LaunchedEffect(vm.requestedPanel) {
        vm.consumeRequestedPanel()?.let { target ->
            focusManager.clearFocus()
            keyboard?.hide()
            // 抽屉可能是在预设页之上投递的：先收掉那层，否则两块浮层叠在一起
            presetsOpen = false
            panel = target
        }
    }

    /*
     * `@` 面板的候选：**已启用的技能 + 所有 MCP 服务**。
     *
     * 每次技能 / 服务变化都重建 —— 数量是个位数，没必要做增量。
     * 用 collectAsStateWithLifecycle 而不是 collectAsState：退到后台时该停就停。
     */
    val mentionSkills by com.simplechat.app.skill.SkillStore.skills.collectAsStateWithLifecycle()
    val mentionServers by com.simplechat.app.mcp.McpStore.servers.collectAsStateWithLifecycle()
    val mentionItems = remember(mentionSkills, mentionServers) {
        Mentions.build(mentionSkills, mentionServers)
    }
    val workspaceInfo by com.simplechat.app.agent.Workspace.current.collectAsStateWithLifecycle()

    /*
     * 工作区 chip 的文案：**云端优先**。
     *
     * 云端环境是按会话绑的，本地工作区是全局的。两者同时存在时，用户此刻
     * 真正要看到的是"这条对话在哪干活" —— 那就是云环境。没有云环境才退回落本地。
     * 读 binding 这个 Flow 而不是直接调 envOf：环境被删/解绑时 chip 要立刻跟着变。
     */
    val cloudBinding by com.simplechat.app.agent.CloudEnvs.binding.collectAsStateWithLifecycle()
    /*
     * 待定绑定：这段对话还没有会话行（没发过言）时先选好的云环境。
     * chip 必须把它算进来，否则选完还是显示"工作区" —— 用户会以为没选上。
     */
    val pendingCloud by com.simplechat.app.agent.CloudEnvs.pending.collectAsStateWithLifecycle()
    val cloudLabel = remember(cloudBinding, pendingCloud, vm.currentConversationId) {
        com.simplechat.app.agent.CloudEnvs.envOf(vm.currentConversationId)?.name
            ?: com.simplechat.app.agent.CloudEnvs.pendingEnv()?.name
    }
    val boundCloudId = remember(cloudBinding, pendingCloud, vm.currentConversationId) {
        vm.currentConversationId?.let { cloudBinding[it] } ?: pendingCloud
    }
    /*
     * 顶栏标题下面那行灰字：这段对话**在哪干活**。
     *
     * 与输入框那颗 chip 同一口径（云端优先），但形态不同：chip 可点、能改绑，
     * 这行只读、只是状态。两者都在，是因为"抬头看到"和"低头改"是两种时刻 ——
     * 用户回看一条历史对话时不会去找输入框确认它在哪台机器上。
     *
     * 没选过工作区（NONE）时也显示「内置工作区」：工具确实落在私有目录，
     * 那不是一个需要用户知道的"未选择"状态，而是事实。
     */
    val workspaceMode = remember(cloudLabel, workspaceInfo) {
        when {
            cloudLabel != null -> Res.get(R.string.topbar_mode_cloud, cloudLabel)
            workspaceInfo.kind == com.simplechat.app.agent.WorkspaceKind.DEVICE ->
                Res.get(R.string.topbar_mode_device, workspaceInfo.label)
            com.simplechat.app.agent.Workspace.isInternalRoot(workspaceInfo.path) ->
                Res.get(R.string.topbar_mode_internal)
            else -> Res.get(R.string.topbar_mode_internal_named, workspaceInfo.label)
        }
    }
    val cloudEnvs by com.simplechat.app.agent.CloudEnvs.list.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<UiMessage?>(null) }
    /** 待确认的「回滚到此处」。回滚要动文件，不能点一下就执行。 */
    var rewindTarget by remember { mutableStateOf<UiMessage?>(null) }
    /** 工具卡里点了「预览」的 HTML 页面。本地与云端共用这一个槽。 */
    var preview by remember { mutableStateOf<PreviewRequest?>(null) }

    /**
     * 最后一次"看得见的人为操作"。
     *
     * 主动消息靠它判空闲。用**任意触摸**而不是"输入框有没有打字"：
     * 用户可能只是在读、在滚，那也是"人在"，不该被当成离开。
     */
    var lastTouchAt by remember { mutableStateOf(System.currentTimeMillis()) }

    /**
     * 两条编辑路径，对应两种真实需求：
     * - 用户消息：**复用输入栏**（官方做法）。一个发送键 = 保存并重发，
     *   不必让用户在"仅改文字 / 编辑并重发"之间做选择 —— 改了提问，
     *   旧回答本来就没意义了。
     * - AI 回复：弹窗二选一。「保存」只留一个新版本；「保存并发送」
     *   还会据此继续往下生成。
     */
    var editingMessage by remember { mutableStateOf<UiMessage?>(null) }
    var aiEditTarget by remember { mutableStateOf<UiMessage?>(null) }
    var inputBeforeEdit by remember { mutableStateOf("") }

    /** 待发送附件（图片 / 纯文本文件）。 */
    var pending by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    /** 输入栏上沿的模型快切弹窗。 */

    /** 附件相关的报错。与网络错误分开 —— 后者是 vm.banner，语义不同。 */
    var attachError by remember { mutableStateOf<String?>(null) }

    /*
     * ── 朗读 ────────────────────────────────────────────
     *
     * 语音输入（麦克风 / 长按说话）已整条移除：它要抢输入框的长按语义，
     * 在中文输入法下选词、移动光标都会被它打断，实际并不好用。
     * 这里只留朗读，它不占输入区。
     */
    var speechError by remember { mutableStateOf<String?>(null) }
    // 音色 / 通道 / 自定义 API 都来自设置：设置里换一个，回到对话页立即生效
    val speaker = rememberSpeechOutput(
        voiceName = vm.ttsVoiceName,
        officialConfig = vm.officialTts,
        channel = vm.ttsChannel,
        customConfig = vm.customTts,
    )
    /*
     * 朗读入口是否显示：由当前通道能否出声决定（见 isReadAloudSupported）。
     * 不可用时**整颗按钮不出现** —— 用户要求：不支持的 API 直接隐藏。
     */
    val readAloudAvailable = speaker.isReadAloudSupported(vm.ttsVoiceName)

    /*
     * 自动朗读：最后一条回复写完就念。
     *
     * 只念一次 —— 用 [autoSpoken] 记住念过的消息 id。否则滚动、切换主题、
     * 任何一次重组都会让这个 effect 重新判断一次，于是同一段话被反复朗读。
     */
    val autoSpoken = remember { mutableSetOf<String>() }
    LaunchedEffect(vm.autoReadAloud, vm.messages.size, vm.messages.lastOrNull()?.status) {
        if (!vm.autoReadAloud || !readAloudAvailable) return@LaunchedEffect
        val message = vm.messages.lastOrNull() ?: return@LaunchedEffect
        if (message.role != MessageRole.ASSISTANT) return@LaunchedEffect
        if (message.status != MessageStatus.DONE || message.content.isBlank()) return@LaunchedEffect
        if (!autoSpoken.add(message.id)) return@LaunchedEffect
        speaker.toggle(message.id, message.content)
    }

    // 朗读失败以 banner 告知：与附件报错同一套呈现
    LaunchedEffect(speaker.error) {
        speaker.error?.let {
            speechError = it
            speaker.consumeError()
        }
    }

    /** 全屏查看的图片：本条的全部图片 + 当前点开的那张。 */
    var viewingImage by remember { mutableStateOf<Pair<List<Attachment>, Attachment>?>(null) }

    /** 「选择模型」对话框。气泡里只放勾选的几个，勾选在对话框里做。 */
    var showModelPicker by remember { mutableStateOf(false) }

    /*
     * ⚠️⚠️ **输入状态必须按会话分开。**
     *
     * 上面那几个（草稿 / 待发附件 / 正在编辑的那条）原本是整屏一份的 `remember` ——
     * 切到别的会话，它们**全都跟过去** ✗。顺手按发送，内容就发进了另一个会话；
     * 更糟的是"正在编辑的那条"也跟过去 —— 那是会改错消息的。
     *
     * 做法：切换时**交换**一份（切走的存回它自己的会话、切进来的取出来）。
     * 是"留在各自的会话里"，不是"切走就清空" —— 草稿不该被吞掉。
     *
     * 这个 effect 声明在下面那个"切换会话"的 effect **之前**，所以它先跑，
     * 输入状态在溶解动画开始前就已经换好了。
     */
    val composerKey = vm.currentConversationId.orEmpty()
    val pendingByConversation = remember { mutableStateMapOf<String, List<Attachment>>() }
    val editingByConversation = remember { mutableStateMapOf<String, UiMessage?>() }
    val draftBeforeEditByConversation = remember { mutableStateMapOf<String, String>() }
    var composerOwner by remember { mutableStateOf<String?>(null) }

    /*
     * **要落库的那一份草稿。**
     *
     * 「修改输入」时输入框里是**编辑文本**，不是草稿 —— 存进去下次打开会变成一句
     * 没头没尾的话，而且它指向的消息也没了。所以编辑期间存的是编辑前那份。
     */
    fun draftToPersist(): String = if (editingMessage != null) inputBeforeEdit else input

    LaunchedEffect(vm.currentConversationId) {
        // ① 把上一份存回它自己的会话
        composerOwner?.let { previous ->
            vm.saveDraft(previous, draftToPersist())
            pendingByConversation[previous] = pending
            editingByConversation[previous] = editingMessage
            draftBeforeEditByConversation[previous] = inputBeforeEdit
        }
        // ② 取来这一份：草稿走库（进程被杀也还在），其余是瞬时状态、留在内存
        input = vm.loadDraft(composerKey).orEmpty()
        pending = pendingByConversation[composerKey].orEmpty()
        editingMessage = editingByConversation[composerKey]
        inputBeforeEdit = draftBeforeEditByConversation[composerKey].orEmpty()
        composerOwner = composerKey

        // ③ 这些是"指向某条消息"的瞬时状态 —— 跨会话留着是危险的，一律清掉
        aiEditTarget = null
        deleteTarget = null
        viewingImage = null
        attachError = null
        panel = InputPanelState.NONE
    }

    /*
     * 打字时**防抖落库**：停手 400ms 才写一次，与「会话标题」那条同档。
     *
     * ⚠️ 只在**换会话已经完成**之后才写 —— 换会话那一瞬间输入框里还是上一个会话
     * 的内容，而 `composerKey` 已经是新的了，这时候写会把别人的草稿覆盖掉。
     * 所以认 `composerOwner`（= 换会话 effect 收尾时才更新）。
     */
    LaunchedEffect(composerKey) {
        snapshotFlow { draftToPersist() }
            .collectLatest {
                if (composerOwner != composerKey) return@collectLatest
                delay(DraftSaveDelayMs)
                // 延迟期间可能又打了字，落库取**当下**这一份
                vm.saveDraft(composerKey, draftToPersist())
            }
    }

    /*
     * 退到后台 / 离开本页**立刻存一次**。
     *
     * 防抖最坏会丢最后 400ms，而"进程被杀"往往就发生在退到后台那一刻 ——
     * 这一下是真正兜底的那次。
     */
    val draftScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun flush() {
            draftScope.launch { composerOwner?.let { vm.saveDraft(it, draftToPersist()) } }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flush()
        }
    }

    /** 收起「修改输入」并还原编辑前的草稿。 */
    fun cancelEdit() {
        editingMessage = null
        input = inputBeforeEdit
        pending = emptyList()
    }

    /*
     * 返回键：**先收界面上的临时状态，最后才轮到退出 App**。
     *
     * 写在最后的优先级最高（`OnBackPressedDispatcher` 是后添加的先响应），
     * 所以顺序是：面板 < 消息索引 < 修改输入 < AI 回复编辑弹窗。
     *
     * ⚠️ 这几条不能省。Compose 的返回事件**没人消费时会一路落空到
     * Activity 的 `finish()`** —— 用户开着「对话设置」面板随手按个返回，
     * App 就直接没了。抽屉那边是同一个坑（见 `RootScreen` 的注释）。
     */
    /*
     * 消息索引（右滑/左滑拉出的那块面板）已整块移除：与会话侧边栏职责重复，
     * 而且它挂在消息区 + 输入区外层的水平手势会**吃掉输入框里拖动光标**的手势。
     */
    BackHandler(enabled = panel != InputPanelState.NONE) { panel = InputPanelState.NONE }
    BackHandler(enabled = editingMessage != null) { cancelEdit() }
    BackHandler(enabled = aiEditTarget != null) { aiEditTarget = null }



    // ── 导出当前对话 ──────────────────────────────────────
    val scope = rememberCoroutineScope()
    var exportHint by remember { mutableStateOf<String?>(null) }

    /** 两个导出口共用：拿到目标位置后写文件。 */
    suspend fun exportTo(uri: Uri, markdown: Boolean) {
        val text = if (markdown) {
            vm.exportCurrentConversationMarkdown()
        } else {
            vm.exportCurrentConversation()
        }
        if (text == null) {
            exportHint = Res.get(R.string.chat_export_empty)
            return
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray())
                } ?: error(Res.get(R.string.error_write_failed))
            }.isSuccess
        }
        exportHint = if (ok) Res.get(R.string.export_done) else Res.get(R.string.export_failed_short)
    }

    /** 导出文件名：会话标题去掉文件系统不认的字符。 */
    fun exportFileName(extension: String): String {
        val base = vm.conversationTitle()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // 40 个字符足够放下绝大多数标题；截断后把悬空的左括号/空白收掉，
            // 免得出现「…（副本 .md」这种切一半的名字
            .take(40)
            .trim()
            .trimEnd('（', '(', '[', '<', ' ', '·', '-', '_')
            .ifBlank { "conversation" }
        return "$base.$extension"
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch { exportTo(uri, markdown = false) }
    }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) scope.launch { exportTo(uri, markdown = true) }
    }

    // ── 附件 ──────────────────────────────────────────────
    //
    // 三个入口都用系统能力，且**都不需要申请任何权限**：
    // - 拍照走 ACTION_IMAGE_CAPTURE + FileProvider（相机 App 自己持有相机权限）
    // - 图片走 PickVisualMedia（系统相册选择器）
    // - 文件走 OpenDocument
    // 刻意不申请 READ_MEDIA_IMAGES 之类的权限 —— 为了一个附件功能
    // 弹一堆系统授权，性价比太差。

    /** 附件读取失败/超限时的统一反馈。 */
    fun onImportFailed(message: String?) {
        attachError = message ?: Res.get(R.string.attach_read_failed)
    }

    /*
     * 相机要往一个 URI 里写，所以得先在启动前把它建好、记下来，
     * 回来时才知道去读哪个文件（`TakePicture` 的回调只给一个 Boolean）。
     */
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val cameraPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = captureUri
        captureUri = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (!ok) {
            // 用户取消了拍摄 —— 中转文件已经在磁盘上，顺手清掉
            AttachmentImport.discardCapture(context, uri)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            AttachmentImport.image(context, uri)
                .onSuccess { pending = pending + it }
                .onFailure { onImportFailed(it.userMessage()) }
            AttachmentImport.discardCapture(context, uri)
        }
    }

    fun pickCamera() {
        val uri = runCatching { AttachmentImport.newCaptureUri(context) }.getOrNull()
        if (uri == null) {
            onImportFailed(Res.get(R.string.attach_camera_file_failed))
            return
        }
        captureUri = uri
        cameraPicker.launch(uri)
    }

    /*
     * **多选**：一次把要发的图都挑完，不用一张一张重复进相册。
     *
     * `PickMultipleVisualMedia` 与单选版吃同一个 `PickVisualMediaRequest`，
     * 回调换成 `List<Uri>`；上限 9 张是产品约束（再多一轮请求也只是烧 token）。
     * 逐张导入、逐张报错：一张坏了不拖累其余几张。
     */
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri ->
                AttachmentImport.image(context, uri)
                    .onSuccess { pending = pending + it }
                    .onFailure { onImportFailed(it.userMessage()) }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            /*
             * 一个入口认三种东西：PDF（渲染成图）、docx（抽文本）、
             * 以及其余一律按纯文本试。格式按**文件头**判定，不看扩展名。
             */
            /*
             * 任意文件：能识别的（图片 / PDF / docx / 纯文本）走专门路径，
             * 识别不了的（视频 / 压缩包 / APK）落盘成 FILE，发送时上传到工作区。
             */
            AttachmentImport.any(context, uri)
                .onSuccess { result ->
                    pending = pending + result.attachments
                    // 有损的地方要说出来（比如 PDF 只带了前 12 页）
                    result.note?.let { attachError = it }
                }
                .onFailure { onImportFailed(it.userMessage()) }
        }
    }

    /*
     * 选择本机文件夹当工作区。
     *
     * 用系统的 `OpenDocumentTree`，初始位置钉在 `/storage/emulated/0` ——
     * 自绘目录树要自己处理权限、排序、隐藏目录，还容易在 Android 11+ 上
     * 读不到 Android/data；系统选择器天然避开这些，而且用户认得它。
     */
    val workspaceTreePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            attachError = Res.get(R.string.workspace_pick_failed)
            return@rememberLauncherForActivityResult
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            // 没有「所有文件访问」时路径即使绑上，工具也读不到 —— 先说清楚
            attachError = Res.get(R.string.workspace_no_permission)
            return@rememberLauncherForActivityResult
        }
        com.simplechat.app.agent.Workspace
            .bind(path, com.simplechat.app.agent.WorkspaceKind.DEVICE)
            .onFailure { attachError = it.message }
    }

    /** 打开文件选择器。MIME 过滤刻意放到最宽，真正的把关在 `AttachmentImport.text`。 */
    fun pickFile() {
        /*
         * ⚠️ 这里刻意不用 MIME 过滤（传通配），而不是只挑 `text` 那一类。
         *
         * `.md` 在不同设备/提供者上被报成 `text/markdown`、`text/x-markdown`
         * 甚至 `application/octet-stream` —— 按 MIME 过滤会让用户在文件选择器里
         * **根本看不到自己的 md 文件**，那种"我的文件呢"比"选错了文件"
         * 更让人困惑。真正的把关放在 `AttachmentImport.text`：
         * 严格按 UTF-8 解码，二进制文件会直接报"看起来不是纯文本文件"。
         */
        filePicker.launch(arrayOf("*/*"))
    }

    /*
     * 滚动状态**按会话隔离**。
     *
     * 早先是一个 `rememberLazyListState()` 用到底。换会话时 LazyList 会按
     * "上一个会话的第一个可见条目**下标**"保持原位：新会话条数**更多**就停在
     * **中间**、**更少**就夹到**尾**、上一个恰好停在顶部就停在**头** ——
     * 这就是"有的从头、有的从尾、有的是随机中间"。
     *
     * ⚠️ 而且 `snapToBottomWhenReady()` 是**一次性**的：它跑完之后才发生那次重排，
     * 重排又把下标锚回去 —— 谁赢取决于时序，所以看起来是"随机"。
     * 换成按会话新建状态后起点恒为 0，"滚到底"就成了确定的事。
     *
     * 仍然用 `rememberSaveable`：`SaveableStateHolder` 靠它保住"从设置返回对话"的位置。
     */
    val listState = rememberSaveable(
        vm.currentConversationId,
        saver = LazyListState.Saver,
    ) {
        /*
         * ★ 下标给到"末尾之外"→ LazyList 布局时自动夹到最后一条 = **点进去就在底部**。
         * 官方同款（`ChatPageMessagesLoaded` 里用的是 `messages.size + 1`）。
         *
         * 这是**布局参数**，不是一次滚动：首帧就在底部，不需要测高度、不需要重试循环、
         * 不需要"贴底守卫"。之前 `ScrollToBottom.kt` 里写的"顶部为原点必须
         * scrollBy(超大) + 重试"是误诊 —— 那是"用滚动去追落点"的代价，不是方向的代价。
         */
        LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)
    }

    /*
     * 消息索引的内容（§34.3）—— **快照**：只在会话或条数变化时重建，不在流式期间
     * 反复重算（§34.5）。预览文本在这里算完，别在组合里现算（每次重组跑一遍
     * `take(N)` 是白烧）。
     *
     * 放到**后台线程**算：它要遍历整条路径（长会话几千条），而这件事与"首帧能看到
     * 消息"无关 —— 放在主线程上，正好和列表首次布局抢那一帧。
     */
    val density = LocalDensity.current
    /** 输入区实测高度（含系统栏内边距）—— 消息列表据此让出底部空间。 */
    var inputAreaHeight by remember { mutableStateOf(0) }
    val inputAreaPadding = with(density) { inputAreaHeight.toDp() }

    /** 正在切换会话。此时列表被整份替换，禁止切换逻辑之外的打扰（见下方切换 LaunchedEffect）。 */
    var switchingConversation by remember { mutableStateOf(false) }

    /**
     * 路径重建后要保住的位置：`(目标下标, 目标的末尾在屏上的 y, 目标的旧高度)`。
     *
     * "末尾"= 操作栏（`‹ n/m › 📋 🔄 ✏️ 🗑`）所在处 —— 用户对"在底部"的定义。
     * 摆回去见下方那个 `SideEffect` 与紧随其后的修正 effect。
     */
    var pathAnchor by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    /** 换会话期间列表还没就位 —— 用来决定要不要显示转圈。 */
    var loadingConversation by remember { mutableStateOf(false) }

    /** 真正显示转圈。 */
    var showLoading by remember { mutableStateOf(false) }

    /*
     * 转圈的时机：**慢过一小会儿才转**。
     *
     * 先前是"读完库之后才知道是不是超长对话"，可那时慢的地方已经过去了 ——
     * 用户看到的是一段没有反馈的空白。现在改成按**耗时**判：
     * 大多数会话几十毫秒就出来了，来不及转圈；真慢的（几百条消息、带图片）才会亮 ——
     * 这也正好是用户看到"侧边栏动画卡住"的那一段。
     */
    LaunchedEffect(loadingConversation) {
        if (!loadingConversation) {
            showLoading = false
            return@LaunchedEffect
        }
        delay(SpinnerDelayMs)
        showLoading = true
    }

    /**
     * 路径正在重建 —— 用它挡住"追加新消息就跟随到底部"。
     *
     * 这些操作会重建整条路径（旧分支的下游让位给新分支的），**条数可能变**；
     * 但那不是"来了新消息"。重建后由下面那个 `SideEffect` 把视口摆到列表末尾
     * （= 操作栏），不需要跟底逻辑再插一脚。
     */
    var pathRebuilding by remember { mutableStateOf(false) }

    val tail = vm.messages.lastOrNull()
    val tailStreaming = tail?.streaming == true
    val tailGrowth = tail?.let { it.content.length + it.reasoning.orEmpty().length } ?: 0

    /*
     * ⚠️⚠️ **「内容增长」和「追加新消息」是两件事，处理方式完全相反** ——
     * 把它们混在一起，就是上面那个死循环。
     *
     * - **内容增长**（流式往同一条里写字）：贴底时是**结构性**的 —— 尾条底边钉住、
     *   往上长，新字自己冒出来，不需要滚动代码；上翻时由 `MessageList` 的
     *   **尾条高度补偿**把视口钉在内容上（长高 Δ 就把 offset 也加 Δ），
     *   也不需要在这里订阅内容变化。
     * - **追加新消息**：这个**不是**结构性的 ✗ —— 新条目落在下标 0，其余条目整体后移，
     *   而 Compose 会按 key 把"你原来在看的那条"钉在原处，于是视口**不会**跟到底部。
     *   实测表现：**发完消息停在上一条回复的结尾，回复来了也看不见**。
     *
     * 所以这里只补"追加"这一半，而且刻意做成**低频、有界**的：只在**条数**变化时滚一次。
     * 绝不订阅内容变化 —— 那才是之前那个每 80ms 转一圈的死循环。
     */
    var detached by remember { mutableStateOf(false) }

    /*
     * 「别跟底」的状态。
     *
     * 置位（谁要求别跟）有三个来源：
     *   ① 手指把视口拖离底部（`DragInteraction.Start`）
     *   ② 切版本 / 删除的锚点（`anchorOnPathChange`）—— 用户在看某一条，不在等新内容
     *   ③ 展开 / 收起折叠块（`onFoldToggle`）—— 同理，视口要留在被他点的那一行上
     *
     * ⚠️ **解禁不能写成"只要在底部就清掉"**（本版一度就是那样，是真 bug）。
     * ②③ 都发生在**贴底**的状态下，而布局要下一帧才落定 —— 设完标记的那一帧
     * `isAtBottom()` 仍然为真，"在底部就清"会把刚设的标记当场撤销，
     * 跟底随即把视口拽回列表末尾。实测现象就是**切中间那条的分支又跑到最底下**。
     *
     * 所以解禁只有三条**明确**的路：
     *   ① 用户的手势（含惯性）**停稳后落在底部** → 他回来了，继续跟
     *   ② 点「回到底部」（见下面那个按钮）
     *   ③ 发消息 / 重新生成 / 重试 / 编辑重发 / 新对话 ——"我要看新的"
     *
     * ⚠️ 判据也不能是下标：追加那一刻下标会整体后移，会把"本来贴着底"误判成"离开了"。
     *
     * ⚠️ 也不能用 `isScrollInProgress && !atBottom`（更早的写法）。它成立的前提是
     * 跟底**每帧都落到**底部 —— 误判一次下一帧就清回来了。跟底改成**逼近式**
     * （见 `ScrollToBottom.followBottom`）之后不再落底，一次误判就会**卡死**：
     * 新字全停在屏外，还得手动滑到底才恢复。
     * 只有手指拖动会产生 `DragInteraction`，代码滚（跟底 / 锚点 / 回到底部）都不会。
     */
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> detached = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 等这一次手势（含惯性滑动）彻底停下，再看落点是不是底部
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    if (listState.isAtBottom()) detached = false
                }
                else -> Unit
            }
        }
    }

    /*
     * ★ 贴底跟随 —— 顶部为原点下**唯一**需要的滚动代码。
     *
     * 锚点钉顶边 → 长高不会推走已显示内容（所以**上翻阅读是稳的，零补偿**）。
     * 但**贴底时**新字长在视口**下方**，必须主动跟，否则它永远滚不出来 ——
     * 实测日志：落点是对的（`atBottom=true`），可内容一长高 `atBottom` 就变 false，
     * 而没有任何写入者，于是停在 `off=1158`，新字全在屏外。
     *
     * 官方同款（`StreamingAutoScrollEffect`）：逐帧循环，第一件事判
     * `isScrollInProgress` 让位，`detached`（用户上翻过）期间完全不跟。
     *
     * 这里的"晚一帧"是**无害**的：它补的是"把新长出来的字带进视口"，
     * 不是"钉住某个阅读位置" —— 晚一帧只是新字晚一帧出现，不会抖。
     * （这正是之前 `off += Δ` 失败、而这里可行的区别。）
     *
     * ⚠️ 一帧里做的是**逼近**，不是 `scrollBy(1e9)` 一把跳到位 —— 后者会把
     * "内容每 50ms 才来一批"直接暴露成"每 50ms 挪一个台阶"。取舍与实现在
     * `ScrollToBottom.followBottom`。
     */
    LaunchedEffect(listState) {
        while (true) {
            /*
             * 「贴底就粘住」：只要用户没主动上翻（`!detached`）、没在滚动、
             * 且下面真的还有内容（`canScrollForward`），就把它钉在末尾。
             *
             * 不只在流式时跑 —— 长消息的文字布局要好几帧才排出真实高度，
             * 只在流式时跑会漏掉"加载完但还在长"的那几帧（实测：第一次
             * atBottom=true 之后高度又长了一截，就再也没人跟了）。
             *
             * `pathRebuilding` / `switchingConversation` 期间让位 ——
             * 那两种是**故意**要把视口挪到别处去的，别和它们抢。
             */
            if (!detached && !pathRebuilding && !switchingConversation &&
                !listState.isScrollInProgress && listState.canScrollForward
            ) {
                // 向底部**逼近**一帧，不是一把跳过去 —— 见 ScrollToBottom.followBottom
                listState.followBottom()
            }
            withFrameNanos { }
        }
    }

    /*
     * 预热 Markdown 解析缓存（**后台线程**）。
     *
     * ⚠️ **只预热末尾这一条**，不是整个会话。
     *
     * 早先这里是 `vm.messages.asReversed().map { it.content }.forEach { warm(it) }` ——
     * 而 effect 的 key 是 `messages.size`：**每来一条新消息，就把全部历史重新解析一遍**。
     * 一千条的会话里，每轮回复都要在后台把上百万字重新解析一次，解析结果还因为缓存
     * 容量被反复冲刷（见 `MarkdownStateCache`）。这正是"长会话越聊越卡"的直接原因之一。
     *
     * 真正需要的只有"贴底打开时立刻能看到的那一条"，其余交给惰性渲染：
     * 滚进视口才解析，解析结果进 LRU 缓存。
     */
    LaunchedEffect(vm.currentConversationId, vm.messages.size) {
        val tail = vm.messages.lastOrNull() ?: return@LaunchedEffect
        // 超长消息不预热：解析它本身就要几百毫秒，预热反而把后台线程占满
        if (tail.content.length > WarmTailMaxChars) return@LaunchedEffect
        withContext(Dispatchers.Default) { warmMarkdownCache(tail.content) }
    }

    /** 追加新消息后跟到底部（用户没离开时）。 */
    LaunchedEffect(vm.messages.size) {
        if (switchingConversation) return@LaunchedEffect
        if (detached) return@LaunchedEffect
        /*
         * ⚠️ **路径重建不是"来了新消息"。**
         *
         * 切版本 / 重新生成 / 编辑并重发 / 重试都会重建整条路径（旧分支的下游
         * 让位给新分支的），条数因此可能变 —— 但用户是在看/对比某一条，
         * 不是在等新内容。跟底会把他从那里甩走。
         *
         * 这个 effect 声明在下面那个"摆回锚点"的 effect **之前**，所以先跑，
         * 也就能先看到这个标记（两个 effect 在同一帧按声明顺序启动）。
         */
        if (pathRebuilding) return@LaunchedEffect
        listState.scrollToBottom()
    }

    /*
     * 键盘弹起会抬高输入栏 → 列表底部留白跟着变大 → 整块正文被顶上去。
     *
     * **贴底时这是想要的**（最后一条要让出输入栏的位置）；但用户上翻读上文时
     * 不该被顶 —— 对齐官方：非贴底状态下正文不随键盘抬高。
     *
     * ⚠️ **补偿必须和留白变化落在同一帧**，否则会闪一下：
     * 留白变大那一帧内容先被顶上去、下一帧才被拉回来 —— 一帧的整屏位移，
     * 看起来就是闪。
     *
     * 所以用 `SideEffect` + `requestScrollToItem` 这一对：
     * - `SideEffect` 在**组合之后、布局之前**跑（`LaunchedEffect` 要等下一帧）
     * - `requestScrollToItem` 在**同一次测量**里生效，且不是动画
     *
     * 于是"被顶上去"的那一帧压根不会被画出来。
     */
    val padPx = inputAreaHeight
    var lastPadPx by remember { mutableStateOf(-1) }
    val padDelta = if (lastPadPx >= 0) padPx - lastPadPx else 0
    SideEffect {
        lastPadPx = padPx
        // 首次测量只记基线（0 → 实测值那一跳不是键盘引起的）
        if (padDelta == 0) return@SideEffect
        /*
         * ★ 顶部为原点：底部留白变化**不推**内容。
         *
         * 锚点是"最上面那条可见项的顶边"，它和底部留白无关 —— 非贴底时什么都不用做。
         * 只有贴底时要主动跟：留白变大 = 内容末尾离视口底更远 = 最后一条会被输入栏盖住，
         * 得重新贴到新的末尾。
         *
         * `isAtBottom()` 在这里读到的是**上一次布局**的结果 = 变化前的状态，正是要的。
         */
        if (listState.isAtBottom()) {
            scope.launch { listState.scrollToBottom() }
        }
    }

    /*
     * 路径重建后**停在原处**。
     *
     * 锚是**正序下标**（位置，不是身份）：重建后那条消息（或顶替它的那条）仍在
     * 同一个位置上 —— 切版本换的是同父的兄弟、重新生成加的是兄弟、编辑重发同理、
     * 删除则由下面那条顶上，深度都不变，它前面的那些一条没动。
     * 所以只需用**新的** size 折算成倒序下标。
     *
     * ⚠️ **必须和重建落在同一帧。** 用 `SideEffect`（组合之后、布局之前跑）
     * + `requestScrollToItem`（同一次测量里生效），而不是
     * `LaunchedEffect` + `scrollToItem`（要等下一帧）。这与键盘补偿是同一个坑，
     * 解法也一样（见上方那段 SideEffect 的说明）。
     *
     * 晚一帧为什么会"乱飞"：重建后条数往往变小（切版本会把旧分支的下游整段丢掉），
     * 而 LazyList 记的是**旧的倒序下标** —— 条数一变，那个下标已经不是这条消息了
     * （被切的那条会整体前移）。于是先按错的下标画出一帧、下一帧才被拉回来，
     * 看起来就是"窜一下 / 乱飞"。同一帧摆回去，这一帧压根不会被画出来。
     */
    val pathRev = vm.pathRevision
    val appliedPathRev = remember { intArrayOf(vm.pathRevision) }
    SideEffect {
        if (pathRev == appliedPathRev[0]) return@SideEffect
        appliedPathRev[0] = pathRev
        val anchor = pathAnchor
        if (anchor == null || vm.messages.isEmpty()) return@SideEffect
        val (target, endY, oldHeight) = anchor
        /*
         * **同一帧**先摆一次：用**旧高度**算偏移，让目标的末尾回到它原来的 y。
         *
         * ⚠️ 只在**目标顶边本来就在视口之上**时才能这么算：
         * `oldHeight - endY` 正是"顶边在视口上方多少"。目标比视口**短**时
         * （顶边本来就在屏上）这个值是**负**的 —— 而 `requestScrollToItem`
         * 只能表达"顶边在视口上方"，表达不了"在视口下方"。硬夹成 0 会退化成
         * "把顶边贴到视口顶边" = 往上跳。那种情况交给下面的相对修正。
         */
        val safe = target.coerceIn(0, vm.messages.size - 1)
        /*
         * ★ 第一帧只求**不越界**：把目标的顶边贴到视口顶边。
         *
         * 不能用"旧高度"去估偏移 —— 新版本比旧的短很多时，那个偏移会让目标
         * **整个跑到视口之上**，LazyList 只能钳到后面的条目上
         * （实测：请求 (7, 23658) 之后视口落到 item 8；`n` 还从 8 变 10 ——
         * 切到的兄弟版本自带下游）。
         *
         * 精确位置交给下面的修正 —— 那时才能读到目标的**新高度**。
         */
        listState.requestScrollToItem(safe, 0)
    }

    /*
     * `pathRebuilding` 的复位单独留在这里，而**不是**放进上面的 SideEffect。
     *
     * 上面那个"条数变化就跟到底部"的 effect 声明在前、同一帧先跑，
     * 它要靠 `pathRebuilding == true` 来跳过跟底。`SideEffect` 在应用阶段、
     * `LaunchedEffect` 在下一帧 —— 若在 SideEffect 里就把标记清掉，
     * 等 size effect 醒来时它已经是 false，于是又跟了一次底。
     */
    LaunchedEffect(vm.pathRevision) {
        pathRebuilding = false
        /*
         * ★ 路径重建的**精确修正**：新高度要等重建后那一帧的布局才知道，
         * 而 `SideEffect` 在布局**之前**跑 —— 同一帧只能用旧高度估。
         * 这里拿到新高度后把目标的末尾摆回它原来的 y。跑两帧，第二帧兜底。
         */
        val anchor = pathAnchor ?: return@LaunchedEffect
        val (target, endY, _) = anchor
        repeat(3) {
            withFrameNanos { }
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (info == null) {
                /*
                 * ★ 目标**完全不在视口里** —— 上一版就卡在这：第一帧的偏移越界，
                 * 视口落到了后面的条目上，修正从"可见条目"里找不到目标，
                 * 直接放弃，于是永远停在错的位置。
                 *
                 * 先把它拉进视口（顶边贴视口顶边），下一轮就能读到它的新高度。
                 */
                listState.scrollToItem(target, 0)
                return@repeat
            }
            val offset = (info.size - endY).coerceAtLeast(0)
            listState.scrollToItem(target, offset)
        }
    }

    /*
     * 切换会话：**直接落到最新一条** + 一次快速交叉溶解。
     *
     * 直接整份换内容会硬切一下（列表瞬间变样），快速连点会话时尤其明显。
     * 顺序是"先透明 → 等新内容就位 → 滚到底 → 再淡入"：换内容的那一瞬
     * 全程不可见，所以看起来就是一次溶解，而不是旧内容闪一下又变成新内容。
     *
     * 用 alpha 而不是 `Crossfade`：Crossfade 的出场分支会被重组，
     * 而两个分支读的是同一份实时列表 —— 淡出的那一份早就变成新内容了。
     */
    val dissolve = remember { Animatable(1f) }
    var previousConversationId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.currentConversationId) {
        val id = vm.currentConversationId
        val fromAnotherConversation = previousConversationId != null
        previousConversationId = id

        // 换会话 = 换了一份完全不同的内容，跟随状态一律重置
        pathAnchor = null
        pathRebuilding = false

        if (id == null) {
            switchingConversation = false
            dissolve.snapTo(1f)
            return@LaunchedEffect
        }

        /*
         * `switching` 兜个底：万一它没能回落（会话被删、任务被顶掉之类），
         * 消息区不能就这么停在透明状态 —— 那是"白屏"，比跳一下严重得多。
         */
        suspend fun awaitLoaded() = withTimeoutOrNull(SwitchLoadTimeoutMs) {
            snapshotFlow { vm.switching }.first { !it }
        }

        switchingConversation = true
        loadingConversation = fromAnotherConversation
        try {
            /*
             * 立刻透明，**不做淡出动画** —— 首次打开也一样。
             *
             * 顶部为原点之后，"落到最新一条"不再是布局参数一次到位的事：
             * `firstVisibleItemIndex = Int.MAX_VALUE` 只能夹到最后一条的**顶部**，
             * 而它的高度是逐帧才长到真实的。所以有一段"往上追"的过程，
             * 把它一起盖进透明里，用户就只看到一次干净的淡入。
             */
            dissolve.snapTo(0f)
            awaitLoaded()


            /*
             * ★ 首屏贴底必须**逐帧补**，不能只滚一次。
             *
             * 列表总高是随文字布局逐帧长到真实的：一次 `scrollBy(超大)` 只能夹到
             * **当时**已知内容的末尾，下一帧内容更高就又不到底了
             * （实测日志：滚完之后 fvi=9/off=0，停在最后一条的顶部）。
             * 所以在有界窗口内每帧补一次，直到真的贴底。
             */
            withTimeoutOrNull(BottomSettleMs) {
                // 等**稳定 3 帧**再收工：第一次 atBottom=true 不代表终点，
                // 长消息的文字布局还会再长几帧（实测差了一整截）。
                var stable = 0
                while (stable < 3) {
                    listState.scrollToBottom()
                    withFrameNanos { }
                    stable = if (listState.isAtBottom()) stable + 1 else 0
                }
            }
            dissolve.animateTo(1f, tween(SwitchFadeInMs, easing = MotionEasing))
        } finally {
            switchingConversation = false
            loadingConversation = false
        }
    }

    /** 收起键盘与焦点。 */
    fun dismissInput() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    /**
     * 记下**路径重建后要回到的位置**。只给「切版本 / 删除」用。
     *
     * 锚的是"**目标的下一条**"，目的是让目标的**操作栏（条目末尾）留在屏上** ——
     * 见函数体里的说明。
     *
     * 其余三个动作（重新生成 / 重试 / 编辑并重发）**一律不锚**：
     * 它们把这条换成一条**空的**流式回复，高度会先塌到十几像素
     * （实测 18355px → 12px），"原来的阅读位置"在新内容里根本不存在 ——
     * 硬锚只会把视口钳到内容末尾（往上跳）。而且用户按它们就是想看新回复，
     * 跟到底部才对。见各自调用点的说明。
     */
    fun anchorOnPathChange(message: UiMessage) {
        /*
         * ★ **锚点 = 发生操作那个操作栏在屏上的位置。**
         *
         * 用户对"在底部"的定义就是**操作栏可见**，而操作栏
         * （`‹ n/m › 📋 🔄 ✏️ 🗑`）就在条目**末尾**。所以这里记下
         * "目标末尾现在在屏上的 y"，重建后摆回原处。
         *
         * ⚠️ **不能锚"列表末尾"**：树模型下切到的兄弟版本**可能自己带着下游**
         * （在那个分支里继续聊过），目标就不一定是最后一条 —— 锚列表末尾会跳到
         * **对话末尾**，又是"乱跑"。
         *
         * ⚠️ **不能锚"目标的下一条"**：下游被丢掉时那条不存在，`coerceIn` 会把
         * 锚点夹回目标自己 → 视口被摆到目标的**顶边** → 超长消息"乱跑到很上面"。
         *
         * 只有"目标的末尾在屏上的 y"这一件事，对上面两种情况都成立。
         */
        val target = vm.messages.indexOfFirst { m -> m.id == message.id }
        val entry = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        pathAnchor = if (target >= 0 && entry != null) {
            Triple(target, entry.offset + entry.size, entry.size)
        } else {
            null
        }
        pathRebuilding = true
        /*
         * ★ **必须摘掉自动跟随**（`detached`）。
         *
         * 跟底循环的目标是**列表末尾**。目标不是最后一条时（树模型下切到的
         * 兄弟版本可能自带下游），两者方向不一致 —— 跟底会把刚钉好的操作栏
         * 又拽到**对话末尾**。实测现象就是"切中间那条 → 跳到很下面"。
         *
         * 最后一条永远暴露不了这个问题（它的末尾就是列表末尾），所以只拿
         * 最后一条验证会漏掉。回到「回到底部」按钮再点一下即恢复跟随。
         */
        detached = true
    }

    /**
     * 「保存并发送」。
     *
     * **不需要二次确认**：树模型下旧的那条（连同它整棵子树）原样留在旧分支里，
     * 一条都不会丢。想回到旧分支，切一下版本就行。
     */
    fun requestResend(target: UiMessage, text: String, attachments: List<Attachment>) {
        // 编辑并重发 = 从这条起重建路径，下游换成一条空的流式回复 → 同「重新生成」，不锚
        detached = false
        vm.editMessage(target, text, resend = true, attachments = attachments)
    }

    /** 展开面板时收起键盘 —— 键盘与面板同时占屏会让输入区几乎没有空间。 */
    fun openPanel(target: InputPanelState) {
        dismissInput()
        panel = if (panel == target) InputPanelState.NONE else target
    }

    /*
     * 工具审批回调只在这里接一次。
     *
     * 卡片在四层调用之下（ChatScreen → MessageList → ProcessTimeline → ToolCallCard），
     * 为它开一路参数，每个中间层都要声明一个自己用不到的东西。审批只有一个接收者，
     * 交给 CompositionLocal 更诚实。
     */
    /*
     * 主动消息的空闲计时。
     *
     * 30 秒一查：再密就是白耗电，再疏会让"刚好离开一会儿"也被判定成空闲。
     * 真正决定开不开口的是 `rollProactive`（按小时概率折算到这次检查）。
     */
    LaunchedEffect(Unit) {
        while (true) {
            delay(ProactiveTickMs)
            // 退到后台就不开口：这里不是"聊天"，是拿用户的额度在背后发请求
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            if (!vm.canSpeakProactively()) continue
            val idle = System.currentTimeMillis() - lastTouchAt
            if (idle < vm.proactiveIdleMillis()) continue
            if (vm.rollProactive(ProactiveTickMs)) {
                vm.proactiveSpeak()
                // 说过话就重新计时：否则下一次检查仍然满足空闲条件，会连着开口
                lastTouchAt = System.currentTimeMillis()
            }
        }
    }

    CompositionLocalProvider(
        LocalToolApproval provides { decision -> vm.resolveApproval(decision) },
    ) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBackground)
            .pointerInput(Unit) {
                // 不消费事件：这里只想知道"刚才有人碰过屏幕"
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        lastTouchAt = System.currentTimeMillis()
                    }
                }
            },
    ) {
        ChatTopBar(
            title = vm.conversationTitle(),
            // 绑了云端就显示云端环境名，否则是本地目录 —— 与输入栏那颗 chip 同一口径
            workspaceLabel = cloudLabel
                ?: workspaceInfo.label.takeIf { it.isNotBlank() }
                ?: Res.get(R.string.workspace_title),
            workspaceMode = workspaceMode,
            onOpenWorkspace = {
                dismissInput()
                panel = InputPanelState.NONE
                onOpenWorkspace()
            },
            onMenu = {
                dismissInput()
                onOpenDrawer()
            },
            onNewChat = {
                dismissInput()
                onNewConversation()
                panel = InputPanelState.NONE
                // 新对话 = "我要看新的"，恢复跟随
                detached = false
            },
            onOpenPresets = {
                // 先收键盘与面板：预设页要从底部升上来，底下的输入栏不该顶着键盘
                dismissInput()
                panel = InputPanelState.NONE
                presetsOpen = true
            },
            pinned = vm.currentPinned,
            /*
             * 「文件管理」= 当前工作区。它不一定在手机上（可能是云端），
             * 所以文案不能写死"本机文件" —— 点进去那页会自己说清楚在哪。
             */
            onOpenFiles = {
                dismissInput()
                onOpenFiles()
            },
            onOpenTerminal = {
                dismissInput()
                panel = InputPanelState.NONE
                onOpenTerminal()
            },
            onTogglePin = { vm.togglePin() },
            onDelete = {
                dismissInput()
                vm.deleteCurrentConversation()
                panel = InputPanelState.NONE
                detached = false
            },
            // 点顶栏任意处也收键盘
            modifier = Modifier.dismissKeyboardOnTouch(),
        )

        /*
         * 消息区 + **浮在它上面的**输入区。
         *
         * 输入栏不占列表的位，而是盖在列表上方 —— 长文滚动时正文会从输入栏
         * 底下穿过去，而不是被一道硬边界截断。列表底部用等高的 contentPadding
         * 让出空间，最后一条仍然滚得出来。
         */
        Box(
            modifier = Modifier
                .weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dissolve.value }
                    // 点消息区空白处收键盘；不消费事件，气泡照常可点
                    .dismissKeyboardOnTouch()
            ) {
                if (vm.messages.isEmpty()) {
                    // 空态也要避开输入栏，否则"一起创作吧"会压在输入框上
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = inputAreaPadding),
                    ) {
                        EmptyState(modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    // 这条对话绑的是哪台云端机器（没绑就是 null）——产物条与预览都要用
                    val boundCloud = com.simplechat.app.agent.CloudEnvs.envOf(vm.currentConversationId)
                    MessageList(
                        messages = vm.messages,
                        listState = listState,
                        /*
                         * 压缩分界线：位置与数字都从 ViewModel 来。
                         *
                         * 它替代了以前那条"正在后台整理…"的 banner ——
                         * 用户能在消息流里看到**从哪条开始**被摘要，以及压完省了多少。
                         */
                        compactionDivider = vm.compactionDivider,
                        /*
                         * 展开 / 收起**任何**折叠块（思考面板、超长用户气泡）时，
                         * 立刻停止自动跟底。
                         *
                         * 折叠块都在视口**下方**：跟底会马上把这一屏往上推，
                         * 于是"展开"这个动作先把**被点的那个按钮**顶走了 ——
                         * 用户点的就是它、视线也在它上面，它必须原地不动。
                         * 停下来之后新长出来的内容往**下**长，正是"掀开"的观感。
                         *
                         * 而且停下来是**持续的**：`detached` 只在用户重新滚回底部时
                         * 才被清掉（见跟底循环里那条恢复条件），所以展开后可以
                         * 慢慢往下读，不会被反复拽走。
                         *
                         * 反过来说，这也是"点开来看"与"等新内容"两种意图的分界：
                         * 点折叠 = 我在读已经有的东西；跟底只在流式那种
                         * "我在等新字"的场合才对。
                         */
                        onFoldToggle = { detached = true },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = inputAreaPadding + 12.dp,
                        ),
                        onCopy = { message -> copyToClipboard(context, message.content) },
                        onRegenerate = { message ->
                            /*
                             * ⚠️ **重新生成不锚**，交给跟底。
                             *
                             * 它把这条换成一条**空的**流式回复 —— 高度会先塌到十几像素
                             * （实测 18355px → 12px），此时"原来的阅读位置"在新内容里
                             * 根本不存在，锚不住；硬锚只会把视口钳到内容末尾 = 往上跳。
                             *
                             * 而用户按「重新生成」就是想**看新回复**，跟到底部才对。
                             * （对照：「切版本」是看已完成的兄弟版本，那里才锚。）
                             */
                            detached = false
                            vm.regenerate(message)
                        },
                        onContinue = { message ->
                            /*
                             * 续写**跟底**：新内容接在这条末尾，用户按它就是想看接下来写什么。
                             * 与「重新生成」同一个判断（那条把内容清空，锚不住）。
                             */
                            detached = false
                            vm.continueMessage(message)
                        },
                        // 朗读暂时下线（见 ReadAloudEnabled）：开关关着时按钮不出现、
                        // TTS 引擎也不会被初始化
                        speakingId = if (ReadAloudEnabled && readAloudAvailable) speaker.speakingId else null,
                        readAloudAvailable = ReadAloudEnabled && readAloudAvailable,
                        autoCollapseThinking = vm.autoCollapseThinking,
                        onSpeak = { message ->
                            // 朗读正文（不是时间线里的过程文字）：用户要听的是回答本身
                            if (ReadAloudEnabled) speaker.toggle(message.id, message.content)
                        },
                        onEdit = { message ->
                            if (message.role == MessageRole.USER) {
                                if (editingMessage == null) inputBeforeEdit = input
                                editingMessage = message
                                input = message.content
                                // 附件跟着进输入栏 —— 改错别字不该顺手把图丢了
                                pending = message.attachments
                                panel = InputPanelState.NONE
                            } else {
                                aiEditTarget = message
                            }
                        },
                        onDelete = { message -> deleteTarget = message },
                        onRewind = { message -> rewindTarget = message },
                        /*
                         * 云端会话的产物条。
                         *
                         * 远端文件在本地没有对应路径，所以识别只按"像不像文件路径"，
                         * 点开时先 download 到缓存再交给系统 —— 与工具卡预览同一个思路：
                         * 用户不需要知道文件在哪台机器上。
                         */
                        remoteWorkspace = boundCloud != null,
                        onOpenArtifact = boundCloud?.let { env ->
                            { path: String ->
                                scope.launch {
                                    runCatching {
                                        val bytes = com.simplechat.app.agent.RemoteWorkspaces.of(env).download(path)
                                        val dir = java.io.File(context.cacheDir, "artifacts").apply { mkdirs() }
                                        val file = java.io.File(dir, path.substringAfterLast('/'))
                                        file.writeBytes(bytes)
                                        com.simplechat.app.ui.common.ArtifactOpener
                                            .open(context, file.absolutePath)
                                            .getOrThrow()
                                    }.onFailure { error ->
                                        android.widget.Toast.makeText(
                                            context,
                                            Res.get(R.string.artifact_open_failed, error.message.orEmpty()),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        onPreviewFile = { path ->
                            /*
                             * 同一个按钮，两种来源：
                             * - 云端会话 → 从远端按需拉取（页面里的相对资源也走远端）
                             * - 本地 → 起一个指向工作区的服务
                             * 用户不需要知道区别，他只看见"预览"。
                             */
                            val cloud = com.simplechat.app.agent.CloudEnvs.envOf(vm.currentConversationId)
                            if (cloud != null) {
                                preview = PreviewRequest(
                                    source = com.simplechat.app.agent.CloudPreviewSource(
                                        com.simplechat.app.agent.RemoteWorkspaces.of(cloud),
                                        cloud.name,
                                        envId = cloud.id,
                                    ),
                                    entry = path,
                                    title = path.substringAfterLast('/'),
                                )
                            } else {
                                runCatching { com.simplechat.app.agent.Workspace.resolve(path) }
                                    .getOrNull()
                                    ?.let { file ->
                                        val ws = com.simplechat.app.agent.Workspace.rootOrNull()
                                        val base = if (ws != null &&
                                            file.absolutePath.startsWith(ws.absolutePath + java.io.File.separator)
                                        ) {
                                            ws
                                        } else {
                                            file.parentFile ?: file
                                        }
                                        preview = PreviewRequest(
                                            source = com.simplechat.app.agent.LocalPreviewSource(base),
                                            entry = runCatching {
                                                file.relativeTo(base).invariantSeparatorsPath
                                            }.getOrDefault(file.name),
                                            title = file.name,
                                        )
                                    }
                            }
                        },
                        onFork = { message -> vm.forkFrom(message) },
                        onVariantChange = { message, index ->
                            // 切版本也重建路径（旧分支下游让位）—— 锚住操作栏那一行，
                            // 换完视口不动，连点 `‹ ›` 才点得准。详见 anchorOnPathChange。
                            anchorOnPathChange(message)
                            vm.switchVariant(message, index)
                        },
                        onOpenImage = { message, attachment ->
                            viewingImage = message.attachments.filter { it.isImage } to attachment
                        },
                        onRetry = { message ->
                            // 同「重新生成」：换成一条空的流式回复 → 不锚，跟到底部看它写
                            detached = false
                            vm.retryMessage(message)
                        },
                    )
                }
            }

            /*
             * 回到底部。
             *
             * 只在离开底部时出现 —— 一直挂着会平白挡住正文，而它贴底时毫无用处。
             * 点它会**恢复自动跟随**：用户的意图显然是"我要看最新的"。
             */
            /*
             * 换会话时的白屏转圈。
             *
             * 只在"切换会话"且**等了一小会儿还没就位**时出现：短会话一闪而过，
             * 没必要闪个圈；长消息排版慢，这时给个明确的"在加载"，
             * 比让人看着列表自己往下滑要好。
             */
            if (showLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                    strokeWidth = 3.dp,
                    color = LocalChatColors.current.processText,
                )
            }

            /*
             * 安卓原生的那种细滚动条：滚动时出现、停手淡出。
             *
             * 放在消息区这一层、贴右缘；输入栏浮在它上面，淡出后也不碍事。
             */
            VerticalScrollBar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )

            val atBottom by rememberIsAtBottom(listState)
            BackToBottomButton(
                /*
                 * ★ 显隐判据里必须有 `detached`（用户主动离开底部），**不能只有 `!atBottom`**。
                 *
                 * `!atBottom` 在**跟随过程中**会以内容到达的节奏来回翻：每来一批字
                 * `canScrollForward` 变真 → 按钮冒出来；跟底把它吃掉 → 又变假 → 按钮消失。
                 * 表现就是"一触发跟底，按钮就不停闪烁"。
                 *
                 * `detached` 是**意图**而不是**位置**：跟随期间它一直是假，
                 * 所以跟底时这个按钮压根不存在 —— 正是要的。
                 * 留 `!atBottom` 只是为了避免"明明已经在底部还挂着一个回到底部"。
                 */
                visible = vm.messages.isNotEmpty() && detached && !atBottom,
                // 生成中在按钮外圈转一圈进度环；生成完自动消失
                generating = vm.isStreaming,
                onClick = {
                    // 用户显式要"看最新的" —— 这是少数几个该主动滚动的地方之一
                    detached = false
                    scope.launch { listState.scrollToBottom() }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = inputAreaPadding + 12.dp),
            )

            /*
             * 面板展开时，点消息区任意处即可关闭。
             *
             * 这层是**透明**的、只在面板展开时存在，并且会吃掉这一击 ——
             * 否则用户点空白处关面板，顺手还点开了底下那条消息。
             */
            if (panel != InputPanelState.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            dismissInput()
                            panel = InputPanelState.NONE
                        },
                )
            }

            /*
             * 输入栏**下面**那一层渐变：只铺屏幕最底部的一条，正文滚到这里就化开。
             *
             * 输入栏以上**一律不铺** —— 之前那层从输入栏顶边往上羽化的带子，
             * 会把正在读的那行字从中间洗掉，比硬边还难看。正文应该一直清晰到
             * 输入栏的边，然后被输入栏自己盖住。
             */
            if (inputAreaHeight > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(BottomFadeHeight)
                        .background(
                            Brush.verticalGradient(
                                /*
                                 * 起点不透明 0 而是 0.28，末段再收满：
                                 * 两段线性的渐变在中间那一段太"透"，正文走到这里
                                 * 会有半截字一直若隐若现。加中间站之后是**越往下越实**，
                                 * 而不是"忽然开始变淡"。
                                 */
                                colorStops = arrayOf(
                                    0f to colors.pageBackground.copy(alpha = 0f),
                                    0.35f to colors.pageBackground.copy(alpha = 0.28f),
                                    0.68f to colors.pageBackground.copy(alpha = 0.68f),
                                    1f to colors.pageBackground,
                                ),
                            ),
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 先量高度再让位：消息列表的底部留白要跟它一致
                    .onSizeChanged { inputAreaHeight = it.height }
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                vm.banner?.let { message ->
                    BannerBar(message = message, onDismiss = { vm.dismissBanner() })
                }

                /*
                 * 自动重试 / 断流续跑的进行提示。
                 *
                 * 不可关闭，也没有关闭的必要：它只在这一轮真正结束后消失。
                 * 没有这一行，那 1~16 秒的退避在用户看来就是"卡住不动了"。
                 */
                vm.retryHint?.let { hint ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.placeholder,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                        )
                    }
                }

                attachError?.let { message ->
                    BannerBar(message = message, onDismiss = { attachError = null })
                }

                speechError?.let { message ->
                    BannerBar(message = message, onDismiss = { speechError = null })
                }

                /*
                 * 发送队列：这一轮还在说，后面排着的话先摆在这里。
                 * 每条都能单独撤下来 —— 排队是"待会儿发"，不是"发出去了改不了"。
                 */
                val queued = vm.sendQueue.filter { it.conversationId == vm.currentConversationId }
                if (queued.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = AppIcons.ListChecks,
                                contentDescription = null,
                                tint = colors.placeholder,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = stringResource(R.string.queue_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.placeholder,
                            )
                        }
                        queued.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = AppIcons.Close,
                                    contentDescription = stringResource(R.string.queue_remove),
                                    tint = colors.placeholder,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { vm.removeQueued(item.id) },
                                )
                            }
                        }
                    }
                }

                // 待发送附件排在输入栏**上面**：先看到"我挂了什么"，再看到输入框
                PendingAttachmentsRow(
                    attachments = pending,
                    onRemove = { spec -> pending = pending - spec },
                    onOpen = { spec -> viewingImage = pending.filter { it.isImage } to spec },
                )

                InputBar(
                    text = input,
                    onTextChange = { next ->
                        /*
                         * 超长输入自动转 TXT。
                         *
                         * 判据是"长度突然长大了"：粘贴一份文档是**一次**事件，
                         * 而正常打字每敲一个字也满足 `next.length > input.length` ——
                         * 所以还要过阈值。跨过阈值那一刻转一次，之后输入框已经是空的。
                         *
                         * 编辑已有消息时不转：那是用户自己写过的长文，重发时
                         * 突然变成附件会让"我刚改的那句话去哪了"。
                         */
                        val grew = next.length > input.length
                        if (editingMessage == null && grew && next.length > AutoTxtChars) {
                            pending = pending + AttachmentImport.longText(next)
                            input = ""
                            attachError = Res.get(R.string.attach_auto_txt, next.length)
                        } else {
                            input = next
                        }
                        if (panel != InputPanelState.NONE) panel = InputPanelState.NONE
                    },
                    editingLabel = if (editingMessage != null) stringResource(R.string.msg_edit_input) else null,
                    onCancelEdit = { cancelEdit() },
                    // 面板展开时输入栏投影淡出，两块合看成一体
                    panelExpanded = panel != InputPanelState.NONE,
                    contextStats = vm.contextStats,
                    /*
                     * 三张小浮层的内容全在这里拼：输入栏只负责"贴着哪颗控件弹"，
                     * 不认识 ViewModel，也就不必为每个新字段改一次签名。
                     */
                    contextPanel = {
                        ContextMiniPanel(
                            stats = vm.contextStats,
                            compacting = vm.compacting,
                            onCompact = { vm.compactContext() },
                            onCancelCompact = { vm.cancelCompaction() },
                            onClearCompaction = { vm.clearCompaction() },
                        )
                    },
                    modelLabel = vm.settings.model.displayName.ifBlank { Res.get(R.string.api_model_unset) },
                    model = vm.settings.model,
                    modelPanel = { closeModelPanel ->
                        ModelMiniPanel(
                            settings = vm.settings,
                            onSettingsChange = { vm.updateSettings(it) },
                            models = vm.pickableModels,
                            onRefreshModels = { vm.refreshModels() },
                            refreshing = vm.refreshingModels,
                            hint = vm.modelHint,
                            // 先收起浮层再弹对话框：两层浮窗叠着，用户不知道该先关哪个
                            onPickModels = {
                                closeModelPanel()
                                showModelPicker = true
                            },
                            // 按配置分组：这几摞分别是"官方 / 中转 / 本地"，不是一个猜出来的家族
                            profiles = vm.modelProfiles,
                            activeProfileId = vm.activeProfileId,
                            onPickProfileModel = { profileId, modelId ->
                                vm.pickProfileModel(profileId, modelId)
                            },
                            modelsOfProfile = { vm.modelsOf(it) },
                        )
                    },
                    streaming = vm.isStreaming,
                    /*
                     * 审批胶囊的浮层内容。
                     *
                     * 之前这两项没传：`approvalPanel` 用的是默认空实现，于是点开
                     * 是一个**高度为零的菜单** —— 看起来就是"点了没反应"。
                     * 内容、当前档位与高亮都要从 ViewModel 来，缺一不可。
                     */
                    approvalAlert = vm.approvalMode == ApprovalMode.YOLO,
                    approvalPanel = { dismiss ->
                        ApprovalMiniPanel(
                            current = vm.approvalMode,
                            onPick = {
                                vm.pickApprovalMode(it)
                                dismiss()
                            },
                        )
                    },
                    onSend = {
                        val target = editingMessage
                        val attached = pending

                        /*
                         * 图片一律随请求发出，**不在本地按模型目录拦**。
                         *
                         * 目录里的 `supportsVision` 是猜测，而且经常错（中转站把同一个
                         * 模型名路由到带视觉的部署上）。拦住的结果是"明明能看图的模型
                         * 被说成不能看"。真不支持时服务端会返回明确错误，那条错误
                         * 会显示在这条回复上 —— 用户据此换模型，信息是真实的。
                         */
                        detached = false
                        if (target != null) {
                            // 输入栏的发送键就是「保存并发送」，不再让用户做二次选择
                            editingMessage = null
                            requestResend(target, input, attached)
                        } else {
                            vm.send(input, attached)
                        }
                        input = ""
                        pending = emptyList()
                        // 发出去的就不再是草稿 —— 立刻删行，不等防抖
                        draftScope.launch { composerOwner?.let { vm.clearDraft(it) } }
                        // 发完就收键盘：接下来是看回复，不是继续打字。
                        // 键盘占着屏幕会把刚发出的那条和开头几行回复一起顶掉。
                        dismissInput()
                    },
                    onStop = { vm.stop() },
                    canAttach = true,
                    onAttachClick = { openPanel(InputPanelState.ATTACH) },
                    attachPanelOpen = panel == InputPanelState.ATTACH,
                    pendingCount = pending.size,
                    mentionItems = mentionItems,
                    // 没绑工作区也要显示 chip：否则用户找不到"从哪儿开一个工作区"
                    workspaceLabel = cloudLabel
                        ?: workspaceInfo.label.takeIf { it.isNotBlank() }
                        ?: Res.get(R.string.workspace_title),
                    cloudBound = cloudLabel != null,
                    workspacePanel = { closeWorkspacePanel ->
                        WorkspaceMiniPanel(
                            currentLabel = cloudLabel
                                ?: workspaceInfo.label.takeIf { it.isNotBlank() }
                                ?: Res.get(R.string.workspace_title),
                            cloudBound = cloudLabel != null,
                            internalSelected = cloudLabel == null &&
                                workspaceInfo.kind == com.simplechat.app.agent.WorkspaceKind.INTERNAL,
                            internalPath = workspaceInfo.path.takeIf {
                                cloudLabel == null &&
                                    workspaceInfo.kind == com.simplechat.app.agent.WorkspaceKind.INTERNAL
                            },
                            noneSelected = cloudLabel == null &&
                                workspaceInfo.kind == com.simplechat.app.agent.WorkspaceKind.NONE,
                            cloudEnvs = cloudEnvs,
                            boundCloudId = boundCloudId,
                            onPickInternal = { name ->
                                com.simplechat.app.agent.Workspace
                                    .bindInternal(name)
                                    .onFailure { attachError = it.message }
                            },
                            onPickNone = {
                                com.simplechat.app.agent.Workspace.unbind()
                                // 云端也算"绑了东西"：切回不绑定要一起清，否则 chip 还挂着云环境
                                vm.currentConversationId?.let {
                                    com.simplechat.app.agent.CloudEnvs.unbind(it)
                                }
                                com.simplechat.app.agent.CloudEnvs.setPending(null)
                            },
                            onPickDevice = {
                                workspaceTreePicker.launch(
                                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                                )
                            },
                            onPickCloud = { env ->
                                /*
                                 * 没有会话行也能选：先记成"待定绑定"，发送时在
                                 * `ensureConversation` 里落到真实会话上。
                                 * 早先这里直接报"先发一条消息"，等于把工作区
                                 * 藏在一句闲话后面。
                                 */
                                val id = vm.currentConversationId
                                if (id == null) {
                                    com.simplechat.app.agent.CloudEnvs.setPending(env.id)
                                } else {
                                    com.simplechat.app.agent.CloudEnvs.bind(id, env.id)
                                }
                            },
                            onManageCloud = {
                                dismissInput()
                                panel = InputPanelState.NONE
                                onOpenCloud()
                            },
                            onClearCloud = {
                                val id = vm.currentConversationId
                                if (id == null) {
                                    com.simplechat.app.agent.CloudEnvs.setPending(null)
                                } else {
                                    com.simplechat.app.agent.CloudEnvs.unbind(id)
                                }
                            },
                            onPicked = closeWorkspacePanel,
                        )
                    },
                    onTextFieldFocused = { panel = InputPanelState.NONE },
                )

                if (showModelPicker) {
                    /*
                     * 选择"气泡里出现哪些模型"。
                     *
                     * 放在对话框里而不是塞进小气泡：气泡宽 248dp，几十个模型
                     * 只能滚动找，找完还要再点一次才能选中；勾选框列表一次能看
                     * 七八个，且"选完就定了"这件事只有对话框说得清。
                     */
                    ModelPickerDialog(
                        models = vm.allModels,
                        selected = vm.pinnedModelIds,
                        onDismiss = { showModelPicker = false },
                        onConfirm = { picked ->
                            vm.setPinnedModels(picked)
                            showModelPicker = false
                        },
                    )
                }

                // ── 面板零：附件 ────────────────────────────────
                // 与下面三个面板**同一个容器**（浮在输入栏上方的卡片），
                // 不是 Material 的 ModalBottomSheet —— 那个满宽、自带蒙版，
                // 摆在应用里和输入栏对不齐，是另一套语言。
                InputPanel(
                    visible = panel == InputPanelState.ATTACH,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    AttachmentPanelContent(
                        onCapture = {
                            // 先收面板再拉起相机，否则拍完回来它还挂着
                            panel = InputPanelState.NONE
                            pickCamera()
                        },
                        onPickImage = {
                            // 先收面板再拉起系统选择器，否则选完回来它还挂着
                            panel = InputPanelState.NONE
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onPickFile = {
                            panel = InputPanelState.NONE
                            pickFile()
                        },
                    )
                }

                // ── 面板三：对话设置 ────────────────────────────
                InputPanel(
                    visible = panel == InputPanelState.SETTINGS,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ConversationSettingsPanelContent(
                        settings = vm.settings,
                        onSettingsChange = { vm.updateSettings(it) },
                        // 只喂给底部那行"最终提示词"预览，列表本身已经不在这里了
                        presets = vm.presets,
                        conversationId = vm.currentConversationId,
                        title = vm.conversationTitle(),
                        /*
                         * 「新对话」也**可以**起名 —— 名字先当待用标题记下（§35），
                         * 建会话时一并落库，AI 起名那条路不再覆盖它。
                         */
                        onTitleChange = { vm.renameCurrentConversation(it) },
                        onExportMarkdown = if (vm.currentConversationId != null) {
                            {
                                exportHint = null
                                exportMarkdownLauncher.launch(exportFileName("md"))
                            }
                        } else {
                            null
                        },
                        onExportJson = if (vm.currentConversationId != null) {
                            {
                                exportHint = null
                                exportJsonLauncher.launch(exportFileName("json"))
                            }
                        } else {
                            null
                        },
                        exportHint = exportHint,
                    )
                }

                Spacer(Modifier.size(8.dp))
            }
        }
    }

    // ── 预设页（⋯ → 预设）──────────────────────────────
    if (presetsOpen) {
        PresetSheet(
            presets = vm.presets,
            enabledIds = vm.settings.enabledPresetIds,
            onToggle = { id, on ->
                /*
                 * 直接改一份新的列表再交给 `updateSettings`。
                 *
                 * `+` / `-` 就是这里的语义：勾上追加到末尾，取消时把那一条摘掉。
                 * 拼接顺序不看这个列表（看预设库的顺序），所以追加在末尾不会
                 * 让"后勾的排在后面"——位次角标已经把真实次序标出来了。
                 */
                val ids = vm.settings.enabledPresetIds
                val next = if (on) ids + id else ids - id
                vm.updateSettings(vm.settings.copy(enabledPresetIds = next))
            },
            onManage = {
                presetsOpen = false
                onOpenSettings()
            },
            onOpenChatSettings = {
                presetsOpen = false
                openPanel(InputPanelState.SETTINGS)
            },
            onDismiss = { presetsOpen = false },
        )
    }
    }

    // ── AI 回复编辑：只有这一处需要区分「保存」与「保存并发送」────

    aiEditTarget?.let { target ->
        AiEditDialog(
            initial = target.content,
            onDismiss = { aiEditTarget = null },
            onSave = { text ->
                aiEditTarget = null
                vm.editMessage(target, text, resend = false)
            },
            onSaveAndSend = { text ->
                aiEditTarget = null
                // AI 回复没有附件，照传（空列表）即可
                requestResend(target, text, target.attachments)
            },
        )
    }



    // ── 图片放大查看 ──────────────────────────────────────

    viewingImage?.let { (all, one) ->
        AttachmentViewerDialog(
            images = all,
            initial = one,
            onDismiss = { viewingImage = null },
        )
    }

    // ── 网页预览 ──────────────────────────────────────────

    /*
     * 用 Dialog 承载而不是塞进根 Column：预览页要占满整屏，而根布局是
     * 一个纵向 Column，直接放进去会被当成最后一行压扁。
     */
    preview?.let { request ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { preview = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            com.simplechat.app.ui.preview.WebPreviewScreen(
                source = request.source,
                entry = request.entry,
                title = request.title,
                onClose = { preview = null },
            )
        }
    }

    // ── 回滚确认 ──────────────────────────────────────────

    rewindTarget?.let { target ->
        // 先算清单再画窗口：用户要看见"哪几个文件、加几行、减几行"才点头
        val files = remember(target.id) { vm.rewindPreview(target) }
        com.simplechat.app.ui.common.AppRewindDialog(
            title = stringResource(R.string.msg_rewind_title),
            files = files,
            emptyText = stringResource(R.string.rewind_empty),
            note = stringResource(R.string.msg_rewind_note),
            confirmText = stringResource(R.string.msg_rewind),
            onDismiss = { rewindTarget = null },
            onConfirm = {
                rewindTarget = null
                // 回滚会把这条及其后的消息从活跃路径上摘掉，先锚住再动
                anchorOnPathChange(target)
                vm.rewindTo(target)
            },
        )
    }

    // ── 删除单条确认 ──────────────────────────────────────

    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.msg_delete_title),
            message = stringResource(R.string.msg_delete_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                // 删除同样重建路径：顶替它的那条会落在同一个位置上，锚住它
                anchorOnPathChange(target)
                vm.deleteMessage(target)
            },
        )
    }
}

/**
 * 「回到底部」悬浮按钮。
 *
 * 抽成独立组件而不是内联写：内联时它处在根 `Column` 的作用域里，
 * `AnimatedVisibility` 会解析到 **`ColumnScope` 的那个扩展**，
 * 而这里需要的是顶层版本（我们要的是"在 Box 里对齐"而不是"在 Column 里换行"）。
 */
@Composable
private fun BackToBottomButton(
    visible: Boolean,
    generating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) +
            scaleIn(initialScale = 0.85f, animationSpec = tween(180, easing = MotionEasing)),
        exit = fadeOut(tween(90)) +
            scaleOut(targetScale = 0.85f, animationSpec = tween(120)),
        modifier = modifier,
    ) {
        /*
         * 外层只负责"尺寸恒定为 32dp"。环画在它里面、但**不裁剪**，
         * 于是可以压到按钮边上；节点尺寸不变，环出现/消失时按钮落点一动不动。
         */
        Box(modifier = Modifier.size(32.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .shadow(
                        elevation = AppShadow.RaisedElevation,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = AppShadow.RaisedAmbient,
                        spotColor = AppShadow.RaisedSpot,
                    )
                    .clip(CircleShape)
                    .background(colors.card)
                    .border(1.dp, colors.outline, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_bottom),
                    tint = colors.processText,
                    modifier = Modifier.size(16.dp),
                )
            }

            /*
             * 环是**后面那个兄弟** → 画在按钮上层。
             * 这一条是"能压住按钮边缘"的前提，见 [GeneratingRing]。
             * 它没有手势修饰符，不会挡住按钮的点击。
             */
            if (generating) {
                GeneratingRing(Modifier.matchParentSize())
            }
        }
    }
}

/**
 * 进度环的几何。
 *
 * [RingOffset] 是环的**中心线**相对按钮边缘的外移量：
 * - `0` = 中心线正好压在边缘上（里侧一半压在按钮上、外侧一半露出来）
 * - 正值 = 往外让开（越大越松）
 * - 负值 = 往里压得更深
 *
 * 现在取 `0`：**不留空隙，且有一半压在按钮沿上** —— 环看着是"长在按钮边上"的。
 */
private val RingOffset = 0.dp
private val RingStroke = 2.dp

/** 进度环转一圈的时间。和 Material 的不确定进度环一个量级 —— 不慌不忙。 */
private const val RingSpinMs = 1100

/**
 * 生成中：一圈转动的进度环，画在按钮的**上层**。
 *
 * ### 两个几何上的坎
 *
 * 1. **节点尺寸必须是按钮那一份（32dp）。** 把盒子撑大再把按钮居中，盒子的右下角
 *    是钉在屏幕上的，按钮就会跟着往左上挪 —— 环一出现按钮跳一下。所以这里用
 *    `matchParentSize()`，环靠 `DrawScope` 默认**不裁剪**画到节点外面去。
 * 2. **必须画在按钮的后面那个兄弟位**（见调用处）。画在下面的话，按钮不透明的底
 *    和描边会把环压住，"压着按钮边缘"就做不到，只能往外让 —— 看着就是"有空隙"。
 *
 * 画法是「一圈淡色轨道 + 一段约 90° 的实色弧」：轨道让"环"始终完整，
 * 弧负责转。只画弧的话视觉上是一小段线在绕圈，读起来不像"进度"。
 */
@Composable
private fun GeneratingRing(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "generatingRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RingSpinMs, easing = LinearEasing)),
        label = "generatingRingAngle",
    )
    val density = LocalDensity.current
    val offset = with(density) { RingOffset.toPx() }
    val stroke = with(density) { RingStroke.toPx() }

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f + offset
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color.copy(alpha = 0.18f),
            radius = radius,
            center = center,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 复制到系统剪贴板。用平台 API 而不是 Compose 的剪贴板封装 —— 后者近年改过两轮签名。 */
private fun copyToClipboard(context: android.content.Context, text: String) {
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
}

/** 顶部错误条。可手动关闭，避免一直占据视线。 */
@Composable
private fun BannerBar(message: String, onDismiss: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(SoftShape)
            .background(colors.capsuleSelected)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(6.dp))
        IconCircleButton(
            icon = AppIcons.Close,
            contentDescription = stringResource(R.string.chat_banner_close),
            onClick = onDismiss,
            size = 26.dp,
            iconSize = 15.dp,
            tint = colors.processText,
        )
    }
}





/**
 * `content://com.android.externalstorage.documents/tree/primary%3ADownload` →
 * `/storage/emulated/0/Download`。
 *
 * 只处理 externalstorage 这一种 provider：系统选择器给的就是它。
 * 别的 provider（网盘、SAF 虚拟目录）没有真实路径，返回 null 让界面说清楚，
 * 而不是把 `content://` 当路径去 `File()`。
 */
private fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }
        .getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val type = parts[0]
    val relative = parts.getOrElse(1) { "" }
    val base = if (type.equals("primary", ignoreCase = true)) {
        android.os.Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$type"
    }
    return java.io.File(base, relative).absolutePath
}


/** 一次预览请求：来源 + 入口文件 + 标题。本地与云端共用。 */
private data class PreviewRequest(
    val source: com.simplechat.app.agent.PreviewSource,
    val entry: String,
    val title: String,
)
