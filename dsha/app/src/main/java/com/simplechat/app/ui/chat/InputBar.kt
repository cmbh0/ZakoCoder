package com.simplechat.app.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.OverlayGate
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.data.Res
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.ModelMark
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.ProgressRing
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 输入区。
 *
 * 第二行布局：
 *
 * ```
 * [ ◎ ] [ 模型名 ⌄ ] [ 🛡 普通 ]  ………  [ ⊕ ] [ ↑/■ ]
 *  上下文     换模型        审批档位            附件   发送/停止
 * ```
 *
 * 三颗都是**小浮层**（`MiniPanels`），不是半屏面板、更不是对话框：
 * 点一下在控件上方扣出一张小纸片，点外面就走。理由很简单 —— 它们问的
 * 都是"一句话就能问完"的事，给半屏是浪费，也给错了分量。
 *
 * 模型为什么回到这里：上一版把它搬到了顶栏标题上（两行：会话名 + 模型名）。
 * 那是错的，顶栏是"我在哪段对话里"，模型是"现在用哪个脑子"；而且换模型时
 * 人的视线本来就在输入栏。
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    /**
     * 当前审批档位的名字（普通 / 提权 / 全放行）。
     *
     * 这里**不放模型选择** —— 模型和思考档位搬去顶栏浮层了（见 ChatTopBar）。
     * 输入栏第二行只剩两颗：还剩多少上下文、AI 现在能走多远。
     * Agent 开关也不在这里：那是"这一轮要不要挂工具"，属于浮层里的一个开关，
     * 挂在输入栏等于逼人每次发消息都确认一遍。
     */
    approvalLabel: String = "",
    /** 档位是"提权"或"全放行"时高亮 —— 这是唯一还能提醒他的地方。 */
    approvalAlert: Boolean = false,
    /** 审批浮层内容。由调用方注入 —— 输入栏只管"从哪儿弹出"。 */
    approvalPanel: @Composable (dismiss: () -> Unit) -> Unit = {},
    /** 当前模型名，显示在第二行那颗胶囊上。 */
    modelLabel: String = "",
    /**
     * 当前模型本身（不只是名字）。
     *
     * 胶囊上要画厂商图标 —— 名字是 `deepseek-v4-flash` 还是 `gpt-4o`，
     * 读起来都是"一串字母"，而图标一眼就是"这是谁家的脑子"。
     * 传整个 [ModelInfo] 而不是一个图标参数：认哪家的标是 `ModelMarks` 的事，
     * 输入栏不该知道这套规则。
     */
    model: ModelInfo? = null,
    /** 模型浮层内容（模型列表 + 思考档位 + 联网）。 */
    /** 模型浮层。参数是"关掉这张浮层"——里面要弹对话框时先收起它。 */
    modelPanel: @Composable (() -> Unit) -> Unit = { _ -> },
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 附件入口是否显示。关掉是为了不给"点了没反应"的控件留位置。 */
    canAttach: Boolean = true,
    /** 点 ⊕：由调用方展开附件面板（图片 / 纯文本文件）。 */
    onAttachClick: () -> Unit,
    /** 附件面板是否展开 —— 决定 ⊕ 要不要转成 ✕。 */
    attachPanelOpen: Boolean = false,
    /** 已挂上的附件数量。>0 时即使没打字也允许发送。 */
    pendingCount: Int = 0,
    onTextFieldFocused: () -> Unit = {},
    /** 非空即处于「修改输入」态：顶部显示该标题 + 取消按钮。 */
    editingLabel: String? = null,
    onCancelEdit: () -> Unit = {},
    /** 下方有面板展开时，输入栏的投影淡出（两块合看成一体）。 */
    panelExpanded: Boolean = false,
    /** 上下文占用。圆环 + 点开的小浮层。 */
    contextStats: ContextStats,
    /** 上下文浮层内容。 */
    contextPanel: @Composable () -> Unit = {},
    /** `@` 面板的候选（已启用的技能 + MCP 服务）。空列表则不弹面板。 */
    mentionItems: List<MentionItem> = emptyList(),
    /** 当前绑定的工作区名字。null 表示不显示那条 chip。 */
    workspaceLabel: String? = null,
    /** chip 是否指向云端环境（换图标，让人一眼看出"东西不在本机"）。 */
    cloudBound: Boolean = false,
    /** 点 chip 弹出的工作区浮层（内置 / 本机目录 / 云端）。参数是"选完了，收起"。 */
    workspacePanel: @Composable (() -> Unit) -> Unit = { _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val shape = ContainerShape
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    /*
     * 三颗控件共用一张浮层的"当前开的是哪一张"。
     *
     * 不用三个独立的 Boolean：那样理论上能同时开三张，而且每开一张都要去关另外两张，
     * 三个开关之间互相写对方，状态很容易对不上。一个枚举没有这个问题。
     */
    var bubble by remember { mutableStateOf(Bubble.NONE) }

    /** 全屏输入是否打开。它和 [bubble] 互斥 —— 打开时浮层先收起。 */
    var fullscreenEditor by remember { mutableStateOf(false) }
    /*
     * 全屏关掉之后把焦点还给输入栏。
     *
     * Dialog 是独立窗口，它一关，主窗口的输入框就丢了焦点、键盘也收了 ——
     * 用户点"完成"回来，光标不在，还得再点一下输入框才能接着写。
     * `restoreFocus` 只在"确实开过全屏"之后才为真，否则首次进入页面
     * 也会抢一次焦点、把键盘弹出来。
     */
    var restoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(fullscreenEditor) {
        if (fullscreenEditor) {
            restoreFocus = true
        } else if (restoreFocus) {
            withFrameNanos { }
            runCatching {
                focusRequester.requestFocus()
                keyboard?.show()
            }
            restoreFocus = false
        }
    }

    /*
     * 「再点一次原控件」的仲裁器，工作区那张浮层也用同一个。
     *
     * 为什么不能只写 `if (this == target) NONE else target`：
     * 浮层是 Popup。点原控件时，系统先给 Popup 发一次窗口外触摸
     * （ACTION_OUTSIDE → onDismissRequest 把状态置 NONE），**同一次手指**
     * 接着落到下面的控件上、又走一遍 toggle —— 此时读到的是 NONE，
     * 于是重新打开。用户看到的是"气泡闪一下没关"。
     *
     * 两条派发路径的先后系统不保证，所以不猜顺序，只记时间：刚被"点外面"
     * 关掉的 300ms 内忽略控件上的 toggle。人手不可能在 300ms 内点两处，
     * 不会误吞真实操作；过了这窗口，点击照常打开。
     */
    val bubbleGate = remember { OverlayGate() }

    // 面板展开 → 投影淡出；收起 → 淡入。否则接缝处会同时压着两条阴影。
    val shadowElevation by animateDpAsState(
        targetValue = if (panelExpanded) 0.dp else AppShadow.PanelElevation,
        animationSpec = tween(durationMillis = 180),
        label = "inputBarShadow",
    )

    /*
     * 进入编辑态时自动聚焦并**拉起输入法**。
     *
     * 只调 requestFocus() 是不够的 —— 它只给焦点，系统输入法不会自己出来，
     * 表现为「点了消息、光标在闪、但键盘没弹」。必须显式 show()，
     * 且要等一帧让焦点真正落到输入框上，否则 show() 会被忽略。
     */
    LaunchedEffect(editingLabel) {
        if (editingLabel == null) return@LaunchedEffect
        runCatching {
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }

    /*
     * 输入框的值提到最外层：`@` 面板要靠它的光标位置判断"是不是正在输入 @ 查询"。
     *
     * `LaunchedEffect(text)` 负责"外部换文本"（恢复草稿 / 切会话）时把光标钉到末尾 ——
     * 那是"接着往下写"的语境；用户自己打字时光标不动。
     */
    var fieldValue by remember { mutableStateOf(TextFieldValue(text = text)) }
    LaunchedEffect(text) {
        if (fieldValue.text != text) {
            fieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }

    /** 光标前正在输入的 @ 查询；null 表示面板不该出现。 */
    val mentionQuery = Mentions.query(fieldValue.text, fieldValue.selection.end)
    val mentionCandidates = remember(mentionQuery, mentionItems) {
        mentionQuery?.let { Mentions.filter(mentionItems, it.second) }.orEmpty()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // ── @ 能力包面板 ───────────────────────────
        if (mentionQuery != null && mentionCandidates.isNotEmpty()) {
            MentionPanel(
                items = mentionCandidates,
                onPick = { item ->
                    val at = mentionQuery.first
                    val cursor = fieldValue.selection.end
                    val newText = buildString {
                        append(fieldValue.text.substring(0, at))
                        append(item.token)
                        append(' ')
                        append(fieldValue.text.substring(cursor))
                    }
                    val caret = at + item.token.length + 1
                    fieldValue = TextFieldValue(text = newText, selection = TextRange(caret))
                    onTextChange(newText)
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        /*
         * 工作区标签：**输入框外面**的左上角。
         *
         * 原来是塞在输入框内部第一行的 —— 那一行本该是打字的地方，结果先看到
         * 一个灰标签，输入区被往下挤。搬到外面之后，"这段对话落在哪"仍然是
         * 一眼可见，但不占输入框的任何一行。
         *
         * 形态照 Harness 的做法：小图标 + 名字 + 右箭头，没有底色和描边 ——
         * 它不是按钮，是状态；点它才变成"改绑"的动作。
         */
        if (workspaceLabel != null) {
            /*
             * 工作区 chip：点开是**一张浮层**，不再跳整页。
             *
             * 它离输入框只有一行，改绑之后下一步就是打字；把人送去另一个页面
             * 再退回来，等于为了换一个抽屉先出趟门。
             */
            var workspaceOpen by remember { mutableStateOf(false) }
            /*
             * 箭头要跟着开合翻转。
             *
             * 之前它是一颗固定的 `v`：点开浮层之后形状不变，看起来像"点了个
             * 没反应的图标"。180° 旋转过来变成 `^`，同一个符号同时承担
             * "现在开着"和"再点一下收起"两件事。
             */
            val chevron by animateFloatAsState(
                targetValue = if (workspaceOpen) 180f else 0f,
                animationSpec = tween(160),
                label = "workspaceChevron",
            )
            Box {
                Row(
                    modifier = Modifier
                        .clip(PillShape)
                        .clickable {
                            if (bubbleGate.acceptToggle()) {
                                workspaceOpen = !workspaceOpen
                            }
                        }
                        .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (cloudBound) AppIcons.Globe else AppIcons.Database,
                        contentDescription = null,
                        tint = colors.placeholder,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = workspaceLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = AppIcons.ChevronDown,
                        contentDescription = stringResource(R.string.workspace_title),
                        tint = colors.placeholder,
                        modifier = Modifier
                            .size(11.dp)
                            .rotate(chevron),
                    )
                }
                AppMenu(
                    expanded = workspaceOpen,
                    onDismissRequest = {
                        bubbleGate.markClosed()
                        workspaceOpen = false
                    },
                    width = WorkspaceBubbleWidth,
                    alignEnd = false,
                    /*
                     * 这张浮层里有输入框（"新建内置文件夹"要起名字），
                     * 必须可获焦 —— 否则窗口拿不到输入法连接，键盘不会出来。
                     */
                    focusable = true,
                ) {
                    workspacePanel { workspaceOpen = false }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 与菜单同一个投影族：先 shadow 再 clip，投影才不会被自家圆角裁掉
                .shadow(
                    elevation = shadowElevation,
                    shape = ContainerShape,
                    clip = false,
                    ambientColor = AppShadow.PanelAmbient,
                    spotColor = AppShadow.PanelSpot,
                )
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.outline, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ── 「修改输入」标题行 ─────────────────────────
            if (editingLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = editingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.placeholder,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircleButton(
                        icon = AppIcons.Close,
                        contentDescription = stringResource(R.string.input_cancel_edit),
                        onClick = onCancelEdit,
                        size = 26.dp,
                        iconSize = 14.dp,
                        tint = colors.processText,
                    )
                }
            }

            /*
             * 「文本已经超出可视区」的判据。
             *
             * `BasicTextField` 的 value 版本没有 `onTextLayout`（那个回调在
             * TextFieldState 版上），拿不到"是否溢出"的精确信号。这里按内容估算：
             * 6 行中文约 130 字、英文约 260 字，取 160 字作阈值，再并上换行数。
             *
             * 估早了只是多一颗按钮（它不遮挡文字，右侧留了位）；
             * 估晚了用户就得多滚两下 —— 两害相权，取早。
             */
            val longText = fieldValue.text.length > 160 ||
                fieldValue.text.count { it == '\n' } >= 5
            Box(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = {
                        fieldValue = it
                        if (it.text != text) onTextChange(it.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // 按钮出现时给右边让出 30dp，文字不会跑到图标底下
                        .padding(end = if (longText) 30.dp else 0.dp)
                        .heightIn(min = 26.dp, max = 160.dp)
                        .focusRequester(focusRequester)
                        // 用户点回输入框时收起面板，避免键盘与面板同时占屏
                        .onFocusChanged { if (it.isFocused) onTextFieldFocused() },
                    textStyle = inputFieldTextStyle(),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.input_hint),
                                    style = inputFieldTextStyle(),
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
                /*
                 * 全屏入口：只在长文时出现。
                 *
                 * 放在右上角而不是输入栏底部那排图标里：底部是"发送 / 附件"这类
                 * **对整条消息**的动作，而它是"对这段文字"的动作，位置应该贴着文字。
                 * 用淡底 + 小尺寸，不抢正文的注意力。
                 */
                if (longText) {
                    IconCircleButton(
                        icon = AppIcons.Expand,
                        contentDescription = stringResource(R.string.input_fullscreen_title),
                        onClick = {
                            bubble = Bubble.NONE
                            fullscreenEditor = true
                        },
                        size = 26.dp,
                        iconSize = 14.dp,
                        tint = colors.processText,
                        background = colors.card.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }

            if (fullscreenEditor) {
                FullScreenInputDialog(
                    value = fieldValue,
                    onValueChange = {
                        fieldValue = it
                        if (it.text != text) onTextChange(it.text)
                    },
                    onDismiss = { fullscreenEditor = false },
                )
            }

            Spacer(Modifier.size(12.dp))

            /*
             * 左组（上下文环 / 模型 / Agent）**可以压缩**，右组（附件 / 发送）不可。
             *
             * 原来是一个平铺的 Row，左侧全是固定宽：窄屏上模型名一长，整行就溢出，
             * 最先掉出屏幕的正是发送键 —— 那是最不能消失的一个键。给左组 weight，
             * 溢出就只会压模型名（它本来就有 ellipsis），按钮留在原地。
             */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                /*
                 * 第二行三个入口，**都是"点开一张小纸片"**，不是"换一整屏"。
                 *
                 * 顺序按"先看状态、再改设置"：上下文（还剩多少）→ 模型（用哪个脑子）
                 * → 审批（能走多远）。三颗都用同一个 `bubble` 状态管着，
                 * 同时只会开一张 —— 三张浮层叠在一起没有任何意义。
                 */
                Box {
                    ContextRing(
                        stats = contextStats,
                        expanded = bubble == Bubble.CONTEXT,
                        onClick = {
                            if (bubbleGate.acceptToggle()) bubble = bubble.toggle(Bubble.CONTEXT)
                        },
                    )
                    AppMenu(
                        expanded = bubble == Bubble.CONTEXT,
                        onDismissRequest = {
                            bubbleGate.markClosed()
                            bubble = Bubble.NONE
                        },
                        width = ContextBubbleWidth,
                        alignEnd = false,
                    ) {
                        contextPanel()
                    }
                }

                /*
                 * 模型胶囊是这一行**唯一可以被压缩**的东西：名字长的时候它先缩，
                 * 缩到极限再省略号。环和盾都是固定宽度，⊕ / ↑ 更不能动 ——
                 * 让名字去挤别人，挤掉的必然是发送键。
                 */
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    ModelChip(
                        label = modelLabel,
                        model = model,
                        expanded = bubble == Bubble.MODEL,
                        onClick = {
                            if (bubbleGate.acceptToggle()) bubble = bubble.toggle(Bubble.MODEL)
                        },
                    )
                    AppMenu(
                        expanded = bubble == Bubble.MODEL,
                        onDismissRequest = {
                            bubbleGate.markClosed()
                            bubble = Bubble.NONE
                        },
                        width = ModelBubbleWidth,
                        alignEnd = false,
                    ) {
                        modelPanel { bubble = Bubble.NONE }
                    }
                }

                Box {
                    ApprovalChip(
                        label = approvalLabel,
                        alert = approvalAlert,
                        expanded = bubble == Bubble.APPROVAL,
                        onClick = {
                            if (bubbleGate.acceptToggle()) bubble = bubble.toggle(Bubble.APPROVAL)
                        },
                    )
                    AppMenu(
                        expanded = bubble == Bubble.APPROVAL,
                        onDismissRequest = {
                            bubbleGate.markClosed()
                            bubble = Bubble.NONE
                        },
                        width = ApprovalBubbleWidth,
                        alignEnd = false,
                    ) {
                        // 传一个"收起浮层"的回调：选完一档就该关，而不是让用户再点一次外面
                        approvalPanel { bubble = Bubble.NONE }
                    }
                }
            }

            Spacer(Modifier.width(6.dp))

                /*
                 * 附件入口。图标是**圆圈加号**（⊕），与顶栏的「新建对话」区分开 ——
                 * 后者是气泡加号。同一个符号不要承担两种动作。
                 *
                 * 展开时整体转 45°：加号变成叉，"再点一下是收起"就不用另外说明。
                 * 转的是**整个按钮** —— 圆形是对称的，看起来就是里头的加号在转，
                 * 比"两帧之间换一个图标"顺得多。
                 */
                if (canAttach) {
                    val spin by animateFloatAsState(
                        targetValue = if (attachPanelOpen) 45f else 0f,
                        animationSpec = tween(200, easing = MotionEasing),
                        label = "attachSpin",
                    )
                    /*
                     * ⊕ 是**描边圆**，和模型 / Agent 两颗胶囊同一个线宽与灰度。
                     *
                     * 之前用 IconCircleButton 直接画了一枚 24dp 的实心图标 ——
                     * 图标自己带一圈粗边，再叠 32dp 的触控格，看起来比旁边胖一圈。
                     * 现在外面这圈由 border 画，里面只剩一个 18dp 的细加号。
                     */
                    Box(
                        modifier = Modifier
                            .size(RowButtonSize)
                            .clip(CircleShape)
                            .background(if (attachPanelOpen) colors.capsuleSelected else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (attachPanelOpen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    colors.outline
                                },
                                shape = CircleShape,
                            )
                            .clickable(onClick = onAttachClick)
                            .graphicsLayer { rotationZ = spin },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.Plus,
                            contentDescription = if (attachPanelOpen) {
                                stringResource(R.string.input_attach_collapse)
                            } else {
                                stringResource(R.string.input_attach_add)
                            },
                            tint = if (attachPanelOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                /*
                 * 发送键**没内容就不出场**。
                 *
                 * DeepSeek 那边空输入时右侧只有一颗 ⊕，箭头是等你有话说才出现的。
                 * 常驻一颗灰底箭头等于在喊"点我"，点了却没反应 —— 那是最差的一种空状态。
                 * 仍然保留 32dp 的位置占位（`AnimatedVisibility` 会收宽度，这里用
                 * scale + alpha 淡入，行高不变），所以出现时不会把左边的胶囊挤一下。
                 */
                val sendable = streaming || text.isNotBlank() || pendingCount > 0
                androidx.compose.animation.AnimatedVisibility(
                    visible = sendable,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = tween(140, easing = MotionEasing),
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.7f,
                        animationSpec = tween(140, easing = MotionEasing),
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = tween(120, easing = MotionEasing),
                    ) + androidx.compose.animation.scaleOut(
                        targetScale = 0.7f,
                        animationSpec = tween(120, easing = MotionEasing),
                    ),
                ) {
                    SendButton(
                        streaming = streaming,
                        enabled = sendable,
                        onClick = { if (streaming) onStop() else onSend() },
                    )
                }
            }
        }
    }
}

/** 输入栏第二行的统一控件高度。 */
private val RowButtonSize = 32.dp

/** 第二行能开的三张小浮层 —— 同时只有一张。 */
private enum class Bubble { NONE, CONTEXT, MODEL, APPROVAL }

private fun Bubble.toggle(target: Bubble): Bubble = if (this == target) Bubble.NONE else target

/**
 * 「点外面关掉」与「再点控件 toggle」的仲裁器。
 *
 * 见 `InputBar` 里的用法注释：同一次手指会先后触发两条路径，只记时间、
 * 不猜顺序。窗口 300ms —— 比一次点击的最长间隔略长，比人手换目标快得多。
 */

/**
 * 审批档位胶囊：盾牌 + 当前档位名。
 *
 * 这是从顶栏 ⋯ 菜单、抽屉底部一路搬下来的第三个位置。搬到这里是因为它**高频**：
 * 「这一轮能不能跑 shell / 能不能写文件」是每次让 Agent 动手前唯一的心理关卡，
 * 却藏在两级菜单里。模型选择反而低频 —— 那个上顶栏了。
 *
 * 高亮规则：只有「提权」「全放行」才染色。普通档是透明的 ——
 * 如果三档都是彩色的，"现在其实很危险"这件事就没有视觉上的分量了。
 */
@Composable
private fun ApprovalChip(
    label: String,
    alert: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val content = when {
        alert -> colors.warning
        expanded -> MaterialTheme.colorScheme.onSurface
        else -> colors.processText
    }
    val title = stringResource(R.string.approval_chip)

    Row(
        modifier = modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (expanded) colors.fieldBackground else Color.Transparent)
            /*
             * 描边永远用主题那一档，不跟着档位变颜色。
             *
             * 「提权 / 全放行」原本把描边染成警示橙 —— 一圈橙边在输入栏里
             * 太吵，而且旁边那颗上下文环、模型胶囊都是淡描边，只有它是
             * 彩色会显得像"报错"。危险感交给图标和文字的颜色就够了。
             */
            .border(
                width = 1.dp,
                color = colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$title $label"
                role = Role.Button
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = AppIcons.Shield,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label.ifBlank { title },
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * 模型胶囊：当前模型名 + 一颗小箭头。
 *
 * 只放名字，不放图标 —— 第二行已经有环、盾、⊕、↑ 四颗图形了，再来一颗星
 * 就成了"图标墙"，而模型名本身是最有信息量的那个元素。
 *
 * 宽度封顶 128dp：名字长的（`DeepSeek Reasoner`）截断加省略号，
 * 但不能把右侧的 ⊕ / ↑ 挤出去。
 */
@Composable
private fun ModelChip(
    label: String,
    model: ModelInfo?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val content = if (expanded) MaterialTheme.colorScheme.onSurface else colors.processText
    /*
     * 箭头要跟着开合翻转。
     *
     * 之前它是一颗固定的 `v`：浮层弹出来之后形状不变，同一个符号既不说
     * "开着"也不说"点一下能收"，用户只能靠浮层本身判断。翻成 `^`，
     * 一颗箭头同时承担"当前状态"和"再点一下的动作"。
     */
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(160),
        label = "modelChevron",
    )

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (expanded) colors.fieldBackground else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (expanded) colors.processText else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (model != null) {
            ModelMark(model = model, size = 13.dp, tint = content)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 100.dp),
        )
        Icon(
            imageVector = AppIcons.ChevronDown,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier
                .size(13.dp)
                .rotate(chevron),
        )
    }
}

/**
 * 上下文占用环。
 *
 * 只画环、不带数字：数字（12k）在环里塞不下，塞在旁边就成了"胶囊"，
 * 一个胶囊在第二行里比三个按钮还宽。而"用了多少"这件事，环的弧度已经说清楚了；
 * 要看精确值点开面板，那里有百分比和 token 数。
 *
 * 颜色三档（常规 / ≥70% / ≥90%）保留 —— 它是"该压缩了"唯一的**不点开**就能看到的信号。
 */
@Composable
private fun ContextRing(
    stats: ContextStats,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val accent = when {
        stats.ratio >= 0.9f -> colors.danger
        stats.ratio >= 0.7f -> colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val label = stringResource(R.string.stats_context_usage)

    /*
     * 不再套一圈常驻描边。
     *
     * 描边 + 内环 = 两个同心圆，第二个圆在 32dp 里什么也没说，只是让这颗控件
     * 看起来比旁边的按钮大一号。环自己就是形状，点开时才给一层淡底说明状态。
     */
    Box(
        modifier = Modifier
            .size(RowButtonSize)
            .clip(CircleShape)
            .background(if (expanded) colors.capsuleSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = "${stats.percent}%"
            },
        contentAlignment = Alignment.Center,
    ) {
        /*
         * 26dp 的环，几乎撑满 32dp 的触控格。
         *
         * 上一版是 16dp —— 在第二行里像一颗没对准焦的小点，和旁边同高的胶囊
         * 不成比例。DeepSeek 那颗环直径约 32dp、线宽 2dp，未用时整圈都是浅灰，
         * 所以它看起来是"空环按钮"而不是"进度条"。这里照这个比例来：
         * 外径 26dp、线宽 2dp，剩余的那 3dp 是给点击态留的呼吸。
         */
        ProgressRing(
            progress = stats.ratio,
            color = accent,
            trackColor = colors.outline,
            size = 26.dp,
            strokeWidth = 2.dp,
        )
    }
}

/**
 * 发送按钮。三态：
 * - 空闲且无内容：浅灰，不可点
 * - 有内容：品牌蓝实心，向上箭头
 * - 流式中：品牌蓝实心，停止方块
 */
@Composable
private fun SendButton(
    streaming: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val background = if (enabled) MaterialTheme.colorScheme.primary else colors.fieldBackground
    val content = if (enabled) Color.White else colors.placeholder

    Box(
        modifier = Modifier
            .size(RowButtonSize)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (streaming) AppIcons.Stop else AppIcons.ArrowUp,
            contentDescription = if (streaming) stringResource(R.string.input_stop) else stringResource(R.string.input_send),
            tint = content,
            modifier = Modifier.size(if (streaming) 16.dp else 19.dp),
        )
    }
}

/** 无背景的圆形图标按钮，48dp 触控目标。等价于 [IconCircleButton] 的大尺寸预设。 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier,
) {
    IconCircleButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = 48.dp,
        iconSize = 24.dp,
        tint = tint,
    )
}

/**
 * 输入框专用文本样式。
 *
 * Compose 的插入光标高度**等于行高**，不是字高。正文用的 `bodyLarge` 是
 * 16sp / 28sp（对着官方 Web 端的 16/28 反推出来的），光标于是被拉成 28dp ——
 * 比字高近一倍，看着就是一根竖条杵在输入框里。
 *
 * 压到 22sp（1.375 倍）之后光标回到正常比例，多行输入的行距也仍然松快。
 * **只改输入框，正文不动**：气泡里的 28 行高是给长文阅读用的，删不得。
 */
@Composable
private fun inputFieldTextStyle() = LocalTextStyle.current.merge(
    MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 22.sp,
    ),
)
