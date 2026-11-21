package com.simplechat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.chat.ChatScreen
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.MotionDurationMs
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.DrawerSurface
import com.simplechat.app.ui.common.SlideDrawer
import com.simplechat.app.ui.common.rememberSlideDrawerState
import com.simplechat.app.ui.common.screenTransform
import com.simplechat.app.ui.history.ConversationSearchPage
import com.simplechat.app.ui.history.HistoryDrawerContent
import com.simplechat.app.ui.history.HistoryViewModel
import com.simplechat.app.ui.onboarding.OnboardingScreen
import com.simplechat.app.ui.cloud.CloudFilesScreen
import com.simplechat.app.ui.files.FileManagerScreen
import com.simplechat.app.ui.cloud.CloudScreen
import com.simplechat.app.ui.screen.ScreenScreen
import com.simplechat.app.ui.settings.PermissionsScreen
import com.simplechat.app.ui.settings.SettingsScreen
import com.simplechat.app.ui.workspace.WorkspacePickerScreen
import com.simplechat.app.ui.workspace.WorkspaceScreen
import com.simplechat.app.ui.mcp.McpScreen
import com.simplechat.app.ui.runtime.RuntimeScreen
import com.simplechat.app.ui.skills.SkillsScreen
import com.simplechat.app.ui.terminal.TerminalScreen
import com.simplechat.app.ui.profile.ProfileScreen
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 顶层路由。
 *
 * 只有两个：对话页与设置页。**搜索不在其中** —— 它不是"另一个页面"，
 * 而是抽屉自己的一种状态（见 [RootScreen] 的 `searchMode`）。
 *
 * [depth] 用来判断转场方向：更深的一层从右侧进来、退出时原路返回。
 */
private enum class Route(val depth: Int) {
    CHAT(0),
    SETTINGS(1),

    // 二级页面。depth 与设置页同级：它们都是从设置进去的平级目的地，
    // 从任一个返回都该回到设置，而不是回到聊天
    TERMINAL(1),
    SKILLS(1),
    MCP(1),
    RUNTIME(1),
    /** 选工作区来源（三选一），从顶栏 / 输入栏 / 设置进。 */
    WORKSPACE_PICK(1),
    /** 工作区详情：当前绑定、云端、常用位置、目录浏览。 */
    WORKSPACE(1),
    PERMISSIONS(1),
    SCREEN(1),
    CLOUD(1),
    CLOUD_FILES(1),

    /** 双栏文件管理器。从对话页顶栏「文件管理」进。 */
    FILES(1),

    /** 本地资料。从抽屉直接进，所以返回目标是聊天而不是设置。 */
    PROFILE(1),
}

/**
 * 侧边栏平时的宽度（占屏宽）。
 *
 * **0.78** 只是比例上限，实际尺寸在 `SlideDrawer` 里按官方的规矩封顶：
 * 最宽 300dp、右侧至少让出 48dp。所以窄屏上它是 78%，宽屏上停在 300dp。
 */
private const val DrawerWidthFraction = 0.78f

/**
 * 顶层路由与侧拉抽屉。
 *
 * 刻意**不引 Navigation 组件** —— 页面就两个，一个状态枚举足够。
 *
 * ### 搜索为什么不是一条路由
 *
 * 点搜索框时**抽屉不关**：同一个面板从 78% 撑到满屏，内容换成搜索页。
 * 全程只有**一个**运动。
 *
 * 先前是"关掉抽屉（往左跑）+ 切到搜索路由（从右边推入）" ——
 * 两个方向相反的运动同时发生，看着就是各走各的。
 * 而搜索本来就该是"把抽屉拉开看清楚"，不是"跳到另一个页面"：
 * 它的搜索框、它的列表，都在同一块面板上。
 */
@Composable
fun RootScreen() {
    val container = (LocalContext.current.applicationContext as App).container
    val scope = rememberCoroutineScope()

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            chatApi = container.chatApi,
            settingsStore = container.settingsStore,
            repository = container.chatRepository,
        ),
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(container.chatRepository),
    )

    val conversations by historyViewModel.conversations.collectAsStateWithLifecycle()
    val searchHits by historyViewModel.searchHits.collectAsStateWithLifecycle()
    val drawerState = rememberSlideDrawerState()

    /*
     * `rememberSaveable` 而不是 `remember`：换语言是 `recreate()` 整页重建，
     * 普通 `remember` 会把导航状态丢掉 —— 表现就是"切个语言，人被送回对话页"。
     * 存档过一次实例状态，重建后就还停在原来那一页（设置）。
     */
    var route by rememberSaveable { mutableStateOf(Route.CHAT) }

    /** 抽屉是否处于搜索态。见类注释：它是抽屉的状态，不是一条路由。 */
    var searchMode by remember { mutableStateOf(false) }

    /*
     * 工作区页面有两个入口（输入框的 chip / 设置里的行），返回目标不同。
     * 记一下"从哪儿进来的"，返回时才不会把人甩到另一条路上。
     */
    var workspaceReturn by remember { mutableStateOf(Route.CHAT) }
    /** 工作区详情页是"管理"还是"挑一个文件夹"。挑完就该退回去，不是留在详情里。 */
    var workspaceBrowse by remember { mutableStateOf(false) }
    var cloudReturn by remember { mutableStateOf(Route.SETTINGS) }
    /* 资料页从哪儿来的：抽屉（回聊天）还是设置（回设置）。 */
    var profileReturn by remember { mutableStateOf(Route.CHAT) }
    /** 「文件管理」进的是哪个环境。null = 没有云环境，走本地工作区页。 */
    var filesEnvId by remember { mutableStateOf<String?>(null) }

    /** 文件管理器的起始目录。空 = 还没进过，按外部存储根处理。 */
    var filesStart by remember { mutableStateOf<java.io.File?>(null) }

    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    // 首次进入播种内置预设
    remember { historyViewModel.seedPresets(); Unit }

    /*
     * 首次启动的引导。
     *
     * 它**不是 Dialog、也不是一条路由**：主页面先完整组装出来，引导作为最后一层
     * 叠在同一个 Compose 树里。这样卡片背后永远是真实的对话页 —— 主题、滚动位置、
     * 背景都和用户接下来要用的那个界面一模一样。背景由本文件的 blur 统一处理，
     * 引导层自己不加任何蒙版色。
     */
    var showOnboarding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showOnboarding = !container.settingsStore.flow.first().onboardingDone
    }
    /** 关抽屉时顺带退出多选，避免下次打开还停在选择态。 */
    fun closeDrawer() {
        historyViewModel.exitSelection()
        drawerState.close(scope)
    }

    /*
     * 返回键。优先级**从低到高**书写 ——
     * `OnBackPressedDispatcher` 是后添加的先响应，所以写在最后的优先级最高。
     *
     *   ① 设置页 → 回对话页
     *   ② 抽屉打开 → 关抽屉
     *   ③ 搜索态 → 退出搜索（抽屉保持打开）
     */

    BackHandler(enabled = route != Route.CHAT) {
        // 二级页面返回设置，设置返回聊天 —— 直接回聊天会把用户从"我刚在看的那一页"
        // 甩得太远，想改回上一项还要重走一遍
        route = when (route) {
            Route.WORKSPACE -> workspaceReturn
            Route.WORKSPACE_PICK -> workspaceReturn
            // 终端是从对话页进的（设置里那条入口已删）—— 回它自己的来处
            Route.TERMINAL -> Route.CHAT
            Route.SKILLS, Route.MCP, Route.RUNTIME,
            Route.PERMISSIONS, Route.SCREEN -> Route.SETTINGS
            // 云端环境有两个入口（工作区页 / 设置页），回到进来时那一页
            Route.CLOUD -> cloudReturn
            Route.CLOUD_FILES -> Route.CHAT
            Route.FILES -> Route.CHAT
            Route.PROFILE -> Route.CHAT
            else -> Route.CHAT
        }
    }

    /*
     * ⚠️ 这条不能省，而且**不是** Material3 帮我们做的。
     *
     * 实测：抽屉打开时按返回键，`ModalNavigationDrawer` 不消费、上面那条也
     * 是 disabled，事件一路落空到 Activity → 直接 `finish()`，用户被丢出 App。
     * （旧版本同样如此，只是没人从抽屉里按过返回键。）
     */
    BackHandler(enabled = drawerState.isOpen) {
        closeDrawer()
    }

    // 搜索态按返回 → 回抽屉列表。抽屉**保持打开** —— 用户本来就是从那儿进来的。
    BackHandler(enabled = searchMode) {
        historyViewModel.updateQuery("")
        searchMode = false
    }

    // ── 侧边栏：搜索时从半屏撑满 ──────────────────────────
    val sheetFraction by animateFloatAsState(
        targetValue = if (searchMode) 1f else DrawerWidthFraction,
        animationSpec = tween(MotionDurationMs, easing = MotionEasing),
        label = "sheetFraction",
    )

    /*
     * 侧边栏：**覆盖在主页面上**，页面原地不动，规格照官方 chat-demo。
     *
     * 主页面原样（不缩放、不位移、不变窄），抽屉从左滑出盖住大部分；
     * 没有蒙版、没有投影，只有一档底色差和一条 1px 右边框
     * （见 `SlideDrawer` 的类注释）。拖动 / 点空白 / 返回键三条路径
     * 都走同一个状态。
     */
    /*
     * 引导打开时把**整个主界面**（含抽屉）做一次高斯模糊。
     *
     * 不是盖一层半透明黑，也不是用系统跨窗口模糊：那两者都会改变背景亮度。
     * 这里对内容本身做 blur，颜色一点不变 —— 深色仍是深色、浅色仍是浅色，
     * 只是清晰度降下去。12–26dp 的过渡让卡片出现时背景不是"啪"地一下糊掉。
     */
    val onboardingBlur by animateDpAsState(
        targetValue = if (showOnboarding) 26.dp else 0.dp,
        animationSpec = tween(320, easing = MotionEasing),
        label = "onboardingBlur",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .blur(onboardingBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded),
    ) {
    SlideDrawer(
        state = drawerState,
        widthFraction = sheetFraction,
        drawerContent = {
            DrawerSurface() {
                /*
                 * 列表 ↔ 搜索交叉淡入。
                 *
                 * 宽度正在变，内容如果硬切会像"闪一下"；
                 * 叠一层淡入淡出，视觉上就是"同一块面板换了个内容"。
                 */
                Crossfade(
                    targetState = searchMode,
                    modifier = Modifier.fillMaxSize(),
                    animationSpec = tween(MotionDurationMs, easing = MotionEasing),
                    label = "drawerContent",
                ) { searching ->
                    if (searching) {
                        ConversationSearchPage(
                            hits = searchHits,
                            currentConversationId = chatViewModel.currentConversationId,
                            query = historyViewModel.query,
                            onQueryChange = { historyViewModel.updateQuery(it) },
                            onBack = {
                                historyViewModel.updateQuery("")
                                searchMode = false
                            },
                            onClick = { conversation ->
                                historyViewModel.updateQuery("")
                                searchMode = false
                                closeDrawer()
                                chatViewModel.openConversation(conversation.id)
                            },
                            onRename = { renameTarget = it },
                            onTogglePin = { historyViewModel.togglePin(it) },
                            onDelete = { deleteTarget = it },
                        )
                    } else {
                        HistoryDrawerContent(
                            conversations = conversations,
                            currentConversationId = chatViewModel.currentConversationId,
                            selectionMode = historyViewModel.selectionMode,
                            selectedIds = historyViewModel.selectedIds.toList(),
                            onSearchClick = { searchMode = true },
                            onClick = { conversation ->
                                closeDrawer()
                                chatViewModel.openConversation(conversation.id)
                            },
                            onEnterSelection = { historyViewModel.enterSelection(it.id) },
                            onToggleSelection = { historyViewModel.toggleSelection(it) },
                            onRename = { renameTarget = it },
                            onTogglePin = { historyViewModel.togglePin(it) },
                            onDelete = { deleteTarget = it },
                            onOpenChatSettings = { conversation ->
                                closeDrawer()
                                chatViewModel.openConversation(conversation.id)
                                chatViewModel.requestPanel(
                                    com.simplechat.app.ui.chat.InputPanelState.SETTINGS,
                                )
                            },
                            onExitSelection = { historyViewModel.exitSelection() },
                            onPinSelected = { pinned -> historyViewModel.pinSelected(pinned) },
                            onDeleteSelected = { confirmBatchDelete = true },

                            userName = chatViewModel.profileName,
                            userAvatar = chatViewModel.profileAvatar,
                            /*
                             * 抽屉底部那一行「我」= **直接进设置**。
                             *
                             * 之前点它先进一张"个人资料"页，那页里再有一行「设置」，
                             * 等于把总入口藏在一层壳后面 —— 用户点一下以为到了，
                             * 结果还要再点一下。现在账号资料是设置里的「账户 → 账号管理」，
                             * 抽屉这一点直达设置。
                             */
                            onOpenProfile = {
                                closeDrawer()
                                route = Route.SETTINGS
                            },
                            onOpenAccount = {
                                closeDrawer()
                                profileReturn = Route.SETTINGS
                                route = Route.PROFILE
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            /*
             * 底层内容：对话页 / 设置页。
             *
             * 页面切换统一走 screenTransform（ui/common/Motion.kt）。
             * SaveableStateHolder 保住各页自己的滚动位置 —— 页面离开组合后
             * `rememberSaveable` 的存档会一并丢掉，于是"从设置返回对话就得重新滚到底"。
             * 存档本来就是导航组件该管的事，我们不引导航组件，就得自己接上。
             */
            val pageStates = rememberSaveableStateHolder()

            /*
             * 转场期间两页都在，若底层没有实色，交叠的那几帧会透出 Activity 的
             * 白底 —— 用户看到的就是"闪一下"。铺一层页面底色，闪烁消失。
             */
            Box(modifier = Modifier.fillMaxSize().background(LocalChatColors.current.pageBackground)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    screenTransform(forward = targetState.depth > initialState.depth)
                },
                label = "route",
            ) { target ->
                pageStates.SaveableStateProvider(target) {
                    when (target) {
                        Route.CHAT -> ChatScreen(
                            vm = chatViewModel,
                            onOpenDrawer = { drawerState.open(scope) },
                            onNewConversation = {
                                chatViewModel.newConversation()
                            },
                            onOpenWorkspace = {
                                workspaceReturn = Route.CHAT
                                route = Route.WORKSPACE_PICK
                            },
                            /*
                             * 同一个入口，两种去处：绑了云环境就浏览那台机器上的文件，
                             * 否则还是本地工作区。判断放在这里而不是 ChatScreen 里 ——
                             * 它本来就不该知道路由长什么样。
                             */
                            onOpenFiles = {
                                val cloudEnv = com.simplechat.app.agent.CloudEnvs
                                    .envOf(chatViewModel.currentConversationId)
                                if (cloudEnv != null) {
                                    filesEnvId = cloudEnv.id
                                    route = Route.CLOUD_FILES
                                } else {
                                    /*
                                     * 本地工作区不再进「工作区设置页」——那一页回答的是
                                     * “根目录绑在哪”，点「文件管理」的人想的是“看文件”。
                                     * 直接落到双栏管理器，起点是工作区根目录；想看
                                     * 绑定情况的人从设置页进。
                                     */
                                    filesStart = com.simplechat.app.agent.Workspace.rootOrNull()
                                    route = Route.FILES
                                }
                            },
                            onOpenSettings = { route = Route.SETTINGS },
                            onOpenCloud = {
                                cloudReturn = Route.CHAT
                                route = Route.CLOUD
                            },
                            onOpenTerminal = { route = Route.TERMINAL },
                        )

                        Route.SETTINGS -> SettingsScreen(
                            onBack = { route = Route.CHAT },
                            onOpenSkills = { route = Route.SKILLS },
                            onOpenMcp = { route = Route.MCP },
                            onOpenRuntime = { route = Route.RUNTIME },
                            onOpenWorkspace = {
                                workspaceReturn = Route.SETTINGS
                                route = Route.WORKSPACE_PICK
                            },
                            onOpenPermissions = { route = Route.PERMISSIONS },
                            onOpenScreen = { route = Route.SCREEN },
                            onOpenCloud = {
                                cloudReturn = Route.SETTINGS
                                route = Route.CLOUD
                            },
                            onOpenProfile = {
                                profileReturn = Route.SETTINGS
                                route = Route.PROFILE
                            },
                            onReplayOnboarding = {
                                // 引导讲的是对话页；从设置里重看也先回到那张页面，
                                // 否则卡片后面是设置列表，第一页“直接说就行”就对不上。
                                route = Route.CHAT
                                showOnboarding = true
                            },
                        )

                        Route.TERMINAL -> TerminalScreen(onBack = { route = Route.CHAT })
                        Route.SKILLS -> SkillsScreen(onBack = { route = Route.SETTINGS })
                        Route.MCP -> McpScreen(onBack = { route = Route.SETTINGS })
                        Route.RUNTIME -> RuntimeScreen(onBack = { route = Route.SETTINGS })
                        Route.PROFILE -> ProfileScreen(
                            onBack = { route = profileReturn },
                        )
                        Route.WORKSPACE_PICK -> WorkspacePickerScreen(
                            onBack = { route = workspaceReturn },
                            onOpenDetail = {
                                workspaceBrowse = false
                                route = Route.WORKSPACE
                            },
                            onPickDevice = {
                                workspaceBrowse = true
                                workspaceReturn = Route.WORKSPACE_PICK
                                route = Route.WORKSPACE
                            },
                            onPickCloud = {
                                cloudReturn = Route.WORKSPACE_PICK
                                route = Route.CLOUD
                            },
                            // 内置：绑定动作在选择页里已经做完，这里只负责退回去
                            onPickedInternal = { route = workspaceReturn },
                        )
                        Route.WORKSPACE -> WorkspaceScreen(
                            onBack = { route = workspaceReturn },
                            onOpenPermissions = { route = Route.PERMISSIONS },
                            conversationId = chatViewModel.currentConversationId,
                            onOpenCloud = {
                                cloudReturn = Route.WORKSPACE
                                route = Route.CLOUD
                            },
                            browseOnly = workspaceBrowse,
                        )
                        Route.CLOUD -> CloudScreen(
                            onBack = { route = cloudReturn },
                            conversationId = chatViewModel.currentConversationId,
                        )
                        Route.CLOUD_FILES -> CloudFilesScreen(
                            envId = filesEnvId,
                            onBack = { route = Route.CHAT },
                        )
                        Route.FILES -> FileManagerScreen(
                            start = filesStart ?: android.os.Environment.getExternalStorageDirectory(),
                            onBack = { route = Route.CHAT },
                        )
                        Route.PERMISSIONS -> PermissionsScreen(onBack = { route = Route.SETTINGS })
                        Route.SCREEN -> ScreenScreen(onBack = { route = Route.SETTINGS })
                    }
                }
            }
            }

        }
    }
    }

    if (showOnboarding) {
        OnboardingScreen(
            onDone = {
                showOnboarding = false
                scope.launch { container.settingsStore.setOnboardingDone() }
            },
        )
    }

    // ── 弹窗 ─────────────────────────────────────────────

    renameTarget?.let { target ->
        AppTextInputDialog(
            title = stringResource(R.string.hist_rename_title),
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { value ->
                historyViewModel.rename(target.id, value)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.hist_delete_title),
            message = stringResource(R.string.hist_delete_body),
            confirmText = stringResource(R.string.hist_delete_confirm),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                // 删的正是当前正在看的会话 → 立刻回到空态，
                // 否则对话页还挂着一个已经不存在的会话
                if (chatViewModel.currentConversationId == target.id) {
                    chatViewModel.newConversation()
                }
                historyViewModel.delete(target.id)
                deleteTarget = null
            },
        )
    }

    if (confirmBatchDelete) {
        val count = historyViewModel.selectedIds.size
        AppConfirmDialog(
            title = stringResource(R.string.hist_delete_selected_title, count),
            message = stringResource(R.string.hist_delete_selected_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmBatchDelete = false },
            onConfirm = {
                // 当前会话也在被删之列 → 同样立刻回空态
                if (chatViewModel.currentConversationId in historyViewModel.selectedIds) {
                    chatViewModel.newConversation()
                }
                historyViewModel.deleteSelected()
                confirmBatchDelete = false
            },
        )
    }
}
