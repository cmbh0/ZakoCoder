package com.simplechat.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.agent.Artifacts
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.Res
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.ui.common.ReadAloudEnabled
import com.simplechat.app.ui.common.FoldAnimMs
import com.simplechat.app.ui.common.BubbleShapeOut
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuDivider
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.rememberPressPosition
import com.simplechat.app.ui.markdown.MarkdownText
import com.simplechat.app.ui.markdown.MarkdownWithMath
import com.simplechat.app.ui.markdown.warmMarkdownCache
import com.simplechat.app.ui.theme.ChatColors
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.common.PillShape
import androidx.compose.animation.animateContentSize
import com.simplechat.app.ui.common.MotionEasing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.itemsIndexed

/**
 * 消息列表。
 *
 * 性能约定（§8）：
 * - 稳定 `key`（消息 id）
 * - 稳定 `contentType`（按角色区分），避免跨类型复用导致的重组
 *
 * 每条消息下方都有**同一套操作栏**（用户消息也有）：复制 / 重新生成 /
 * 编辑 / 删除，有多个版本时右侧补 `‹ n/m ›`。
 * 长按消息弹菜单 —— 与操作栏功能一致，但少一次精确点击（拇指友好）。
 */
@Composable
fun MessageList(
    messages: List<UiMessage>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /**
     * 列表内边距。
     *
     * 底部要留给**浮在列表之上的输入栏** —— 输入栏不再占位、而是盖在消息区上面，
     * 不给这块留白的话最后一条消息会永远被压在输入栏底下，滚不出来。
     */
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    onCopy: (UiMessage) -> Unit = {},
    onRegenerate: (UiMessage) -> Unit = {},
    /** 继续生成：接着这条回复往下写（只有扩展通道能续，见 `ContinuePolicy`）。 */
    onContinue: (UiMessage) -> Unit = {},
    /** 朗读这条回复（系统 / DeepSeek 官方 TTS）。 */
    onSpeak: (UiMessage) -> Unit = {},
    /**
     * 当前音色**能不能出声**。
     *
     * 官方音色只在「服务商 = DeepSeek 官方 + Key 已填」时可用；不可用时
     * 整颗朗读按钮不显示 —— 点下去再弹一句"不支持"是最差的两种体验的组合。
     */
    readAloudAvailable: Boolean = true,
    /** 正在朗读的那条消息 id。 */
    speakingId: String? = null,
    onEdit: (UiMessage) -> Unit = {},
    onFork: (UiMessage) -> Unit = {},
    /** 回滚到这条消息之前：撤销它的文件改动并把对话截到它之前。 */
    onRewind: (UiMessage) -> Unit = {},
    /** 工具写了 HTML 时点「预览」：路径是工作区相对路径。 */
    onPreviewFile: (String) -> Unit = {},
    onDelete: (UiMessage) -> Unit = {},
    onVariantChange: (UiMessage, Int) -> Unit = { _, _ -> },
    onOpenImage: (UiMessage, Attachment) -> Unit = { _, _ -> },
    onRetry: (UiMessage) -> Unit = {},
    /**
     * 用户展开 / 收起某个折叠块（思考面板、超长用户气泡）时调用。
     *
     * 只当**信号**用，不带数值 —— 具体怎么处理由调用方定（`ChatScreen` 用它
     * 停止自动跟底，见那里的说明）。早先这里是 `onFoldResize(Δpx)`，
     * 在"底部为原点"的时代给视口做开环补偿；改回顶部为原点后锚定天然正确，
     * 那份补偿已无用（调用方传的是 `{}`），只剩下这套 Δ 记账在空转。
     */
    onFoldToggle: () -> Unit = {},
    /**
     * 思考内容是否在正文开始时自动收起（全局显示设置）。
     *
     * 只决定**初态**：开着 → 收成一行摘要；关掉 → 保持展开。
     * 它并进 [AssistantBlock] 里 `remember` 的 key，所以设置一改，
     * 连历史消息的初态也跟着变（用户在设置里刚改完，回头看旧会话该是新行为）。
     */
    autoCollapseThinking: Boolean = true,
    /**
     * 上下文压缩分界线；null = 这个会话既没压缩过、也不在压缩中。
     *
     * 分界线**不占独立条目**：它挂在被摘要覆盖的最后一条消息下面，
     * 这样列表的 key / contentType 仍是"一条消息一个"，滚动位置不会因为它
     * 出现或消失而整体错位。
     */
    compactionDivider: CompactionDividerState? = null,
    /**
     * 这条对话绑在**云端**工作区。
     *
     * 产物在远端，本地没有同路径文件 —— 不能再拿"本地存不存在"当判据，
     * 否则云端会话里 AI 明明 @ 了文件，末尾却是空的。
     */
    remoteWorkspace: Boolean = false,
    /**
     * 打开一个产物。为空时按本地文件打开（[ArtifactOpener]）。
     * 云端会话由调用方注入：先下载到缓存，再交给系统打开。
     */
    onOpenArtifact: ((String) -> Unit)? = null,
) {
    /*
     * 「谁被碰过」。
     *
     * 碰一下 AI 回复，它的**模型名与 token 数**就出现在操作栏旁边；
     * 手指一动（开始滚动）或碰到别的消息就换人。
     *
     * 做成**瞬态**是刻意的：常驻会在每条回复下面都挂一行没人读的小字，
     * 而这两条信息属于"偶尔想看一眼"。
     *
     * 只给 AI 回复 —— 用户消息那行没有操作栏（本来就刻意不放图标），
     * 而且它的点击已经是「编辑」，没有余量再挂一个手势。
     */
    var peekedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) peekedId = null }
    }

    /*
     * 上翻历史时**在后台把它上方那几条先解析好**。
     *
     * 打开会话时只预热了尾部（见 `ChatViewModel` 的 `WarmTailMessages`）——
     * 再往上滑，每一条都是第一次进视口，Markdown 的同步解析就落在主线程上。
     * 一条长回复解析几十毫秒，快速连滑几条就是肉眼可见的一顿。
     *
     * 这里不改变任何渲染顺序，也不异步化同步解析：只是提前把同一份块缓存填好。
     * 真滚到那里时仍然走 `markdownStateOf`，命中即返回。
     *
     * 用 `rememberUpdatedState` 拿列表：effect 只随 `listState` 启动一次，
     * 直接捕获参数会永远读打开会话那一刻的旧列表。
     */
    val latestMessages by rememberUpdatedState(messages)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (first, scrolling) ->
                if (!scrolling) return@collect
                /*
                 * 预热素材在主线程取好：实参是 `SnapshotStateList`（声明类型只是
                 * `List`），后台线程读它同样会撞上并发修改。这里只取待解析的
                 * **字符串**，后台线程不再碰状态列表。
                 */
                val end = (first - 1).coerceAtLeast(0)
                val start = (end - WarmBeforeItems).coerceAtLeast(0)
                val texts = (end downTo start).mapNotNull { index ->
                    latestMessages.getOrNull(index)
                        ?.content
                        ?.takeIf { it.length in 1..WarmMaxChars }
                }
                if (texts.isEmpty()) return@collect
                withContext(Dispatchers.Default) {
                    texts.forEach { warmMarkdownCache(it) }
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        /*
         * ⚠️ **顶部为原点（对齐官方）。这一行是流式不滑动的根。**
         *
         * 锚点钉在条目的**顶边**，而流式消息永远是**最后一条** ——
         * 它长高是长在自己的**底边**，也就是长在视口**下方**：
         * 屏上已显示的内容**纹丝不动**，不需要任何补偿代码。
         *
         * 反过来用 `reverseLayout = true`（锚点钉**底边**）：同一条消息在底边
         * 继续长 = 把已显示内容整体往上顶。实测 0.5 秒滑走 861px 且手拦不住，
         * 这正是之前四轮都想"补偿掉"的那个现象 —— 它不该补，该换方向。
         *
         * 落点"点进对话就是底部"由 `LazyListState(initialFirstVisibleItemIndex =
         * Int.MAX_VALUE)` 解决（下标给到末尾之外，LazyList 自动夹到最后一条）。
         * 那是**布局参数**而不是一次滚动，所以首帧就在底部。
         */
        reverseLayout = false,
    ) {
        itemsIndexed(
            /*
             * 正序喂进去：最旧一条在下标 0（= 视觉顶部），最新的在最后。
             * 与列表方向一致，不再需要 `asReversed()`。
             */
            items = messages,
            key = { _, m -> m.id },
            contentType = { _, m -> m.role },
        ) { index, message ->
            Column(
                /*
                 * 条目级过渡：新消息淡入、删除淡出、位置变化带位移。
                 *
                 * 没有这一层时，发出去的瞬间新气泡"啪"地插进来，上面所有
                 * 内容一次性弹高；删除则是整屏瞬间塌陷。`animateItem` 只对
                 * **位置与进出**做插值，不碰条目内部的高度变化 —— 流式那条
                 * 消息照旧是连续长高，不会被它拖慢。
                 *
                 * 分界线也在这层里面：压缩完成那一瞬间它从一行变两行，
                 * 高度变化跟着条目一起插值，而不是把下面所有消息顶一下。
                 */
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(),
            ) {
                MessageRow(
                    message = message,
                    peeked = peekedId == message.id,
                    /*
                     * 能不能续写由**位置 + 状态**共同决定（见 `ContinuePolicy`）。
                     * 「最后一条」在这里判最准：列表本身就是活跃路径。
                     */
                    canContinue = canContinueReply(message, isLastOnPath = index == messages.lastIndex),
                    speakingId = speakingId,
                    readAloudAvailable = readAloudAvailable,
                    autoCollapseThinking = autoCollapseThinking,
                    /*
                     * 折叠/展开**不需要任何视口补偿**（顶部为原点）。
                     *
                     * 锚点是"最上面那条可见项的顶边"，LazyList 按 (下标, 偏移) 保持不动：
                     * 展开的东西在锚点**之上** → 视口完全不动；在锚点**之下** → 往下长，
                     * 那正是展开该有的样子。
                     *
                     * 唯一会破坏它的是**自动跟底**：展开让内容变高，`canScrollForward`
                     * 立刻变真，跟底那一帧就把整屏往上推 —— 被点的那个按钮于是跑掉。
                     * 所以这里只需要通知外层"用户在看东西了，别再跟"，见 [onFoldToggle]。
                     */
                    onFoldToggle = onFoldToggle,
                    onTogglePeek = {
                        peekedId = if (peekedId == message.id) null else message.id
                    },
                    onCopy = { onCopy(message) },
                    onRegenerate = { onRegenerate(message) },
                    onContinue = { onContinue(message) },
                    onSpeak = { onSpeak(message) },
                    onEdit = { onEdit(message) },
                    onFork = { onFork(message) },
                    onRewind = { onRewind(message) },
                    onPreviewFile = onPreviewFile,
                    onDelete = { onDelete(message) },
                    onVariantChange = { onVariantChange(message, it) },
                    onOpenImage = { onOpenImage(message, it) },
                    onRetry = { onRetry(message) },
                    remoteWorkspace = remoteWorkspace,
                    onOpenArtifact = onOpenArtifact,
                )
                if (compactionDivider?.anchorId == message.id) {
                    CompactionDivider(state = compactionDivider)
                }
            }
        }
    }
}

/**
 * 单条消息 = 内容 + 操作栏。
 *
 * 操作栏对用户消息**右对齐**、对 AI 消息左对齐，跟随内容方向，
 * 免得用户消息下面挂一排左边距对齐的图标，视觉上"跑偏"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: UiMessage,
    modifier: Modifier = Modifier,
    peeked: Boolean,
    canContinue: Boolean,
    speakingId: String?,
    readAloudAvailable: Boolean,
    autoCollapseThinking: Boolean,
    onTogglePeek: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
    onFork: () -> Unit,
    onRewind: () -> Unit,
    onPreviewFile: (String) -> Unit,
    onDelete: () -> Unit,
    onVariantChange: (Int) -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onRetry: () -> Unit,
    onFoldToggle: () -> Unit,
    /** 云端工作区：产物只按路径形状认，见 `MessageList` 的同名参数。 */
    remoteWorkspace: Boolean = false,
    /** 打开产物；null = 走本地文件。 */
    onOpenArtifact: ((String) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    /** 这条回复是否处于"选中复制"模式（见菜单里的同名项）。 */
    var selecting by remember(message.id) { mutableStateOf(false) }
    val isUser = message.role == MessageRole.USER
    // 流式未结束时不对这条给出编辑/删除入口，避免和落库打架
    val actionable = !message.streaming

    // 长按落点：菜单跟手从这里弹出，而不是从内容左边缘
    val (pressPosition, pressListener) = rememberPressPosition()

    // 菜单作为**插槽**注入内容内部：Popup 以父节点为锚，
    // 落点坐标因此与锚点同坐标系，直接相加即可。
    val menu: @Composable () -> Unit = {
        AppMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            touchPoint = pressPosition(),
        ) {
            AppMenuItem(stringResource(R.string.action_copy), AppIcons.Copy) {
                menuOpen = false
                onCopy()
            }
            if (!isUser) {
                /*
                 * 部分选中复制：进入"可选"状态，正文交给 SelectionContainer。
                 *
                 * 不在菜单里直接复制：用户要的往往是回复里**一段**代码或一句话，
                 * 先复制全文再让他去别处删，比多一步麻烦得多。
                 */
                AppMenuItem(stringResource(R.string.msg_select_copy), AppIcons.ListChecks) {
                    menuOpen = false
                    selecting = true
                }
                AppMenuItem(stringResource(R.string.msg_regenerate), AppIcons.Refresh) {
                    menuOpen = false
                    onRegenerate()
                }
                /*
                 * 回滚：只给 AI 消息。
                 *
                 * 用户消息的"回滚"就是编辑重发 —— 那条路径本来就会先还原文件
                 * （见 ChatViewModel.editMessage）。在用户气泡上再放一个同名项，
                 * 两条路会看起来像两种不同的操作。
                 */
                AppMenuItem(stringResource(R.string.msg_rewind), AppIcons.RotateCcw) {
                    menuOpen = false
                    onRewind()
                }
                // 续写：只在真能续的时候出现（见 ContinuePolicy 的五条否决）
                if (canContinue) {
                    AppMenuItem(stringResource(R.string.msg_continue), AppIcons.Play) {
                        menuOpen = false
                        onContinue()
                    }
                }
            }
            AppMenuItem(stringResource(R.string.action_edit), AppIcons.Edit) {
                menuOpen = false
                onEdit()
            }
            // 危险操作固定分隔 —— 不随「有几个版本」这类数据变化
            AppMenuDivider()
            AppMenuItem(stringResource(R.string.action_delete), AppIcons.Trash, destructive = true) {
                menuOpen = false
                onDelete()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when (message.role) {
            MessageRole.USER -> UserBubble(
                message = message,
                interactive = actionable,
                // 点一下就进「修改输入」（对齐官方入口习惯）
                onClick = onEdit,
                onLongClick = { menuOpen = true },
                onOpenImage = onOpenImage,
                menu = menu,
                pressListener = pressListener,
                onFoldToggle = onFoldToggle,
            )

            MessageRole.ASSISTANT -> AssistantBlock(
                message = message,
                interactive = actionable,
                selecting = selecting,
                onExitSelect = { selecting = false },
                autoCollapseThinking = autoCollapseThinking,
                // 点一下 = 看这条是谁写的、占多少 token（再点收起）
                onClick = onTogglePeek,
                onLongClick = { menuOpen = true },
                onOpenImage = onOpenImage,
                onRetry = onRetry,
                onPreviewFile = onPreviewFile,
                menu = menu,
                pressListener = pressListener,
                // 与用户气泡同一条补偿：展开思考内容时"上方固定、向下展开"
                onFoldToggle = onFoldToggle,
                remoteWorkspace = remoteWorkspace,
                onOpenArtifact = onOpenArtifact,
            )
        }

        /*
         * 用户消息**不放操作栏** —— 一个气泡下面挂一排图标太吵。
         * 点气泡即可编辑、长按有菜单。只有存在多个版本时，才补一个
         * 极简的 `‹ 2/3 ›`，否则旧版本在界面上就彻底够不着了。
         */
        AnimatedVisibility(
            visible = actionable && isUser && message.variantCount > 1,
            enter = fadeIn(tween(160)) + expandVertically(tween(200)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(160)),
        ) {
            Column {
                Spacer(Modifier.size(MessageGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    VariantSwitcher(
                        index = message.variantIndex,
                        count = message.variantCount,
                        onChange = onVariantChange,
                    )
                }
            }
        }

        /*
         * 操作栏在定稿那一刻出现。硬切会让下面整段内容瞬间被推高 ——
         * 用一次展开接住它，读起来是"回复写完了，工具长出来了"。
         */
        AnimatedVisibility(
            visible = actionable && !isUser,
            enter = fadeIn(tween(180)) + expandVertically(tween(210)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(160)),
        ) {
            Column {
                Spacer(Modifier.size(MessageGap))
                MessageActions(
                    message = message,
                    peeked = peeked,
                    canContinue = canContinue,
                    speaking = speakingId == message.id,
                    readAloudAvailable = readAloudAvailable,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onContinue = onContinue,
                    onSpeak = onSpeak,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onVariantChange = onVariantChange,
                )
            }
        }
    }
}

/**
 * 用户消息：右对齐浅蓝气泡，黑字。最大宽度约屏宽 82%。
 *
 * 交互（点击进编辑 / 长按出菜单）挂在**气泡自己**身上，且 `clip` 在
 * `combinedClickable` **之前** —— 水波纹才会被裁成气泡的形状。
 * 之前把点击挂在整行 `fillMaxWidth()` 的 Box 上，水波纹横贯全屏，
 * 和气泡形状完全对不上。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    message: UiMessage,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onOpenImage: (Attachment) -> Unit = {},
    menu: @Composable () -> Unit = {},
    /** 旁听落点用（不消费事件），菜单据此跟手弹出。 */
    pressListener: Modifier = Modifier,
    /** 用户展开 / 收起这条气泡时调用 —— 原样转达给外层（见 `MessageList` 的 `onFoldToggle`）。 */
    onFoldToggle: () -> Unit = {},
) {
    val colors = LocalChatColors.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.82f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            /*
             * ⚠️ 这里**不能用 `clip`** —— 见设计文档 §31。
             *
             * `clip` 会给气泡挂一层**带裁剪的图形层**。超长消息的气泡能有几十万像素高，
             * 一旦超过平台的图形层上限，这层就废了：内容照画，
             * **命中测试却只认到某个高度为止** —— 表现正是"气泡上半能点、下半点不动"，
             * 长按同样没反应。
             *
             * 实测（1 万行刻度消息，13.6 万 px 深处）：
             * `clip` 在 → 点不动；去掉 `clip` → 立刻点得动；只去水波纹 → 仍点不动。
             * 也就是说：**凶手是裁剪层，水波纹是清白的。**
             *
             * 形状改由 `background(color, shape)` 画 —— 画一个圆角矩形，**不产生图形层**，
             * 视觉与 `clip` 完全一致。按压反馈同理：水波纹要靠 `clip` 才能裁成圆角，
             * 于是换成**按下时铺一层同形状的浅色** —— 形状天然正确，也不需要层。
             */
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val fadePx = with(LocalDensity.current) { UserBubbleFadeRadius.toPx() }
            /*
             * 折叠状态要在**气泡这一层**就用到（底部那条渐隐画在气泡上），
             * 所以提到最外面声明 —— 放进正文块里的话这里够不着。
             */
            var expanded by remember(message.id) { mutableStateOf(false) }
            var folded by remember(message.id) { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    // 落点只在这一层旁听：附件与气泡共用同一个坐标系，
                    // 菜单锚点才和手指位置对得上
                    .then(pressListener),
                horizontalAlignment = Alignment.End,
            ) {
                /*
                 * 附件在气泡**外面**、气泡**上面** —— 与官方一致。
                 *
                 * 早先是塞在气泡内部的：图片被蓝底整个包住，看起来像"贴在气泡上的
                 * 一张纸"，而官方是图片独立成块、文字气泡跟在下面。两者层级一眼
                 * 就能分开 —— 图片是"我给它看的东西"，气泡是"我说的话"。
                 *
                 * 长按仍然出菜单：只有附件、没有文字的消息（最常见的那种）
                 * 不能因此失去删除 / 复制的入口。
                 */
                if (message.attachments.isNotEmpty()) {
                    MessageAttachments(
                        attachments = message.attachments,
                        onOpenImage = onOpenImage,
                        inUserBubble = true,
                        // 自己发出去的图：正方形圆角缩略图，靠右 — 与官方一致
                        square = true,
                        modifier = Modifier
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = interactive,
                                onClick = {},
                                onLongClick = onLongClick,
                            ),
                    )
                    if (message.content.isNotBlank()) Spacer(Modifier.size(6.dp))
                }
                if (message.content.isNotBlank()) {
                Box(
                    modifier = Modifier
                    // 右下角收紧，给"从右发出"一个方向感
                    .background(colors.userBubble, BubbleShapeOut)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = interactive,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                // 按下时的浅色层：垫在正文**下面**，正文颜色不受影响
                if (pressed) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(colors.onUserBubble.copy(alpha = 0.07f), BubbleShapeOut),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.content.isNotBlank()) {
                        /*
                         * 超长正文的**两态渲染**（根因与实测见设计文档 §31）：
                         *
                         * - **收起态**：`maxLines` 卡住高度 —— 高度有界，命中测试就不会丢；
                         * - **展开态**：切成多块 `Text` 渲染（`textChunks`），
                         *   和 AI 回复走同一套路子，几十万像素也整条点得动。
                         *
                         * ⚠️ 顺带记住那条判据：**别给高度无界的节点挂 `clip` / `graphicsLayer`** ——
                         * 超长气泡上那层图形层会失效，命中测试只认到某个高度为止。
                         */
                        val chunks = remember(message.content) { textChunks(message.content) }

                        Box {
                            /*
                             * 收起态：正文往**右下角**化开。
                             *
                             * 正文是按 `maxLines` **硬裁**的，不加这层就能看到一条齐刷刷的断口；
                             * 以右下角为圆心做径向渐变，正好把断口那一段"吃掉"，
                             * 同时让视线顺着往右读，暗示"还有下文"。
                             *
                             * ⚠️ **层级是这里唯一要紧的事。** 渐隐必须画在
                             * **只包正文的这个 Column** 上 —— 它的下一个兄弟才是右下角那个箭头，
                             * 于是箭头天然压在遮罩上方。
                             *
                             * 早先把它画到外面那层 Column（同时装着正文和箭头）上，就错了：
                             * `drawWithContent` 在**所有后代之后**绘制，箭头也被一起盖住。
                             * 兄弟先后顺序只在**同一层**里作数，跨层不成立 —— 这正是踩的那一脚。
                             */
                            Column(
                                modifier = Modifier
                                    /*
                                     * 展开 / 缩回的动画。
                                     *
                                     * 挂在这一层（正文容器）上：外层气泡尺寸跟着它走，
                                     * 于是整条气泡平滑地长高 / 收回。
                                     *
                                     * `animateContentSize` 只是把**量出来的尺寸**做插值并裁剪，
                                     * 文字本身不重排 —— 所以 20 万字也是一次"掀开"，
                                     * 而不是让眼睛去追一段疯狂滚动的文字。
                                     *
                                     * ⚠️ **只在可折叠的长消息上挂**：普通消息的高度不需要插值，
                                     * 而高度被插值意味着列表在动画期间反复重锚 —— 那是另一种"跳"。
                                     */
                                    .then(
                                        if (folded) {
                                            Modifier.animateContentSize(
                                                animationSpec = tween(
                                                    durationMillis = FoldAnimMs,
                                                    easing = MotionEasing,
                                                ),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .then(
                                    if (folded && !expanded) {
                                        Modifier.drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(colors.userBubble, Color.Transparent),
                                                    center = Offset(size.width, size.height),
                                                    radius = fadePx,
                                                ),
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                            ) {
                                if (expanded) {
                                    /*
                                     * 展开态：切成多块。
                                     * 外面套一个**无间距**的 Column —— 外层那个
                                     * `spacedBy(8.dp)` 是给"附件 ↔ 正文"用的，
                                     * 块之间加间距会凭空多出一堆空行。
                                     */
                                    chunks.forEach { chunk ->
                                        Text(
                                            text = chunk,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.onUserBubble,
                                        )
                                    }
                                } else {
                                    /*
                                     * ⚠️ 收起态这个 `Text` **必须始终被组合**。
                                     *
                                     * 它的 `onTextLayout` 是唯一能判定"是否超行"的
                                     * 信号源。先前把它放在 `if (folded)` 里，
                                     * 而 `folded` 初始为 false —— 于是这个 Text
                                     * 永远不会被组合、`folded` 永远是 false，
                                     * 折叠功能**从来没生效过**（一个自锁的死循环）。
                                     */
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onUserBubble,
                                        maxLines = UserBubbleCollapsedLines,
                                        overflow = TextOverflow.Clip,
                                        onTextLayout = { folded = it.didOverflowHeight },
                                    )
                                }
                            }

                            if (folded) {
                                /*
                                 * 右下角折叠按钮：**半透明胶囊**。
                                 *
                                 * 用半透明而不是实心底 —— 实心圆贴在气泡角上像一块硬片，太丑；
                                 * 半透明让下面的气泡与淡出的正文透出来，边缘自然化开。
                                 *
                                 * ⚠️ 但它必须有底色（哪怕半透明）：右下角正是渐隐最深的地方，
                                 * 只剩一根线的话会糊在淡出的文字里，看着像被盖住。
                                 * 它在渐隐**之后**绘制（是渐隐那一层的下一个兄弟），所以压在遮罩上方。
                                 */
                                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                                    IconCircleButton(
                                        icon = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                        contentDescription = if (expanded) stringResource(R.string.md_collapse) else stringResource(R.string.msg_expand_all),
                                        onClick = {
                                            expanded = !expanded
                                            // 先通知外层别跟底，否则展开的高度立刻被吃掉、按钮跑掉
                                            onFoldToggle()
                                        },
                                        size = UserBubbleFoldButtonSize,
                                        width = UserBubbleFoldButtonWidth,
                                        shape = PillShape,
                                        iconSize = 14.dp,
                                        background = colors.card.copy(alpha = 0.72f),
                                        tint = colors.processText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
                }
                /*
                 * 菜单挂在最外层 Column 上，而不是气泡里。
                 *
                 * 纯附件消息没有气泡（`if (content.isNotBlank())` 不成立），
                 * 菜单若留在气泡内就永远不会被组合 —— 那条消息于是失去
                 * 长按菜单（删除 / 复制都点不到）。挂在这里，两种情况都有。
                 */
                menu()
            }
        }
    }
}

/**
 * AI 消息：左对齐、无气泡，过程面板 + 正文。
 *
 * 长按出菜单，水波纹裁成圆角块（不再是整行矩形）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantBlock(
    message: UiMessage,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    /** 正文进入可选状态（长按菜单 →「选中复制」）。 */
    selecting: Boolean = false,
    onExitSelect: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onOpenImage: (Attachment) -> Unit = {},
    onRetry: () -> Unit = {},
    menu: @Composable () -> Unit = {},
    pressListener: Modifier = Modifier,
    /**
     * 用户展开 / 收起思考面板时调用 —— 原样转达给 `ChatScreen`（见 `MessageList` 的同名参数）。
     */
    onFoldToggle: () -> Unit = {},
    /** 思考内容是否自动收起；见 `MessageList` 的同名参数。 */
    autoCollapseThinking: Boolean = true,
    /** 工具卡里的「预览」回调，见 `MessageList` 的同名参数。 */
    onPreviewFile: (String) -> Unit = {},
    /** 云端工作区：产物只按路径形状认，见 `MessageList` 的同名参数。 */
    remoteWorkspace: Boolean = false,
    /** 打开产物；null = 走本地文件。 */
    onOpenArtifact: ((String) -> Unit)? = null,
) {
    /*
     * 初态由设置决定（`TODO.md` 里那条"只写不读"的开关就是这里接上的）：
     * 开 = 收起（一行的摘要），关 = 展开（全文可见，想收自己点）。
     *
     * 设置并进 `remember` 的 key：用户刚改完设置回头看旧会话，看到的就是新行为。
     * 代价是切换设置会把手动展开/收起的状态重置一次 —— 可接受，
     * 因为那一刻用户要看的正是"这个开关到底有没有用"。
     */
    var reasoningExpanded by remember(autoCollapseThinking) {
        androidx.compose.runtime.mutableStateOf(!autoCollapseThinking)
    }
    /*
     * 这条回复的**过程流**。
     *
     * 一条 AI 回复里有多次往返：思考 → 说话 → 工具 → 思考 → …。这里按
     * 真实顺序把它们排成一条流（见 [buildProcessFlow]），而不是把每段思考
     * 提到最上面编号。`toolCalls` 里的轮次坐标来自发送侧，落库之后仍然有效。
     */
    val flow = remember(
        message.content,
        message.reasoning,
        message.toolCalls,
        message.streaming,
        message.reasoningDone,
        message.reasoningSeconds,
        message.status,
    ) {
        buildProcessFlow(
            content = message.content,
            reasoning = message.reasoning,
            calls = message.toolCalls,
            streaming = message.streaming,
            reasoningStreaming = message.reasoningStreaming,
            thinkingSeconds = message.reasoningSeconds,
            stopped = message.status == MessageStatus.STOPPED,
        )
    }

    /*
     * 扩展通道的回复带**内容块时间线**，渲染成「思考 / 搜索 / 浏览 / 正文」的交错结构；
     * OpenAI 通道与老数据的 `timeline` 为空，继续走原来的「思考栏 + 正文」。
     *
     * 两条路并存而不是二选一替换：老会话还得能看，而 OpenAI 通道本来就产不出
     * 搜索块 —— 强行统一只会让老消息丢内容。
     */
    val timeline = remember(message.timeline, message.streaming) {
        if (message.timeline.isEmpty()) {
            null
        } else {
            buildTimelineView(message.timeline, message.streaming)
        }
    }
    /** 当前打开的来源列表；null 表示不显示弹窗。 */
    var sourceSheet by remember { mutableStateOf<List<SourceRef>?>(null) }

    /*
     * 正文：扩展通道走时间线推出来的那份（含引用角标），其余直接就是 `content`。
     * 角标占位换真链接这一步很便宜（纯字符串替换），但**不能省** ——
     * 没换的话点角标会去打不开的 `#cite-N`。
     */
    val rawAnswer = if (timeline == null) {
        flow.answer
    } else {
        remember(timeline) { resolveCitationLinks(timeline.answer, timeline.sources) }
    }
    /*
     * 正文里已经 @ 过、并且末尾会挂成卡片的那些路径，这里抹掉。
     *
     * 用户的意思是：**不要正文里再念一遍文件名**，底下有卡片就够了。
     * 流式期间不动 —— 文件可能还没落盘，这一刻删掉、下一刻又冒出来会闪。
     */
    val answer = remember(rawAnswer, message.streaming, remoteWorkspace) {
        if (message.streaming) rawAnswer else Artifacts.strip(rawAnswer, remote = remoteWorkspace)
    }
    /** 结论还在写：过程区（思考 / 工具）不算，只有正文在长。 */
    val answerStreaming = if (timeline == null) flow.answerStreaming else message.streaming

    /*
     * ⚠️ 同样**不能用 `clip`** —— 见 `UserBubble` 上方与设计文档 §31。
     * AI 回复也可能很长，一层带裁剪的图形层同样会让下半部分点不动。
     * 按压反馈改用「按下时给同形状的背景上色」，不产生图形层。
     */
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (pressed) LocalChatColors.current.fieldBackground else Color.Transparent,
                SoftShape,
            )
            .then(pressListener)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = interactive,
                onClick = onClick,
                /*
                 * 选择模式下长按归 `SelectionContainer`：再让父层抢走，
                 * 用户按下去只会看到菜单，永远选不中一个字。
                 */
                onLongClick = { if (!selecting) onLongClick() },
            )
            .padding(2.dp),
    ) {
        if (timeline != null) {
            if (timeline.hasSteps) {
                TimelinePanel(
                    view = timeline,
                    streaming = message.streaming,
                    expanded = reasoningExpanded,
                    onToggleExpand = {
                        reasoningExpanded = !reasoningExpanded
                        onFoldToggle()
                    },
                    onOpenSources = { sourceSheet = it },
                )
                Spacer(Modifier.size(MessageGap))
            }
        } else if (flow.hasProcess) {
            /*
             * 过程区：一段思考、一句说明、一串工具，**按实际发生的顺序**排。
             *
             * 思考不再是独立卡片，也不再有「第 N 次思考」—— 它就是一段普通灰字，
             * 左边不挂圆点、不挂竖线（官方 Chat Demo 的形态）。工具行回到两次
             * 思考之间，第二次的思考就落在第一次工具行的正下方。
             */
            ProcessTimeline(
                flow = flow,
                expanded = reasoningExpanded,
                onToggleExpand = {
                    reasoningExpanded = !reasoningExpanded
                    onFoldToggle()
                },
                running = message.streaming,
                onPreview = onPreviewFile,
            )
            if (flow.answer.isNotBlank()) Spacer(Modifier.size(MessageGap))
        }

        /*
         * 正文走 Markdown，**流式期间也是实时 Markdown**（不是纯文本降级）——
         * 两阶段渲染会让定稿那一刻整段重排，版式跳得很难看。
         *
         * 流式的开销靠两道闸压住：上游按固定节奏采样（见 `MarkdownText`），
         * 下游逐块渲染、只有尾块重解析（见 `MarkdownTextBlocks`）。
         */
        /*
         * 首字未到时的占位：三个跳动的点。
         *
         * 只在「正在生成 + 一个字都还没有 + 没有思考面板可看」时出现 ——
         * 有思考面板时那片区域已经有自己的动效，再叠一层点就是重复。
         * 正文一来它就消失，不占位、不闪。
         */
        val answerBody: @Composable () -> Unit = {
            /*
             * 结论单独渲染，不混进过程区那段灰字：它是回答，不是过程。
             *
             * 首字未到时的三个点只在「整条消息还没有任何东西可看」时出现 ——
             * 已经有思考或工具卡时，过程区本身就是进度，再叠一层点是重复。
             */
            if (message.streaming && answer.isBlank() && !flow.hasProcess) {
                TypingDotsRow()
            } else if (answer.isNotBlank()) {
                MarkdownWithMath(
                    content = answer,
                    streaming = answerStreaming,
                )
            }
        }

        /*
         * 选中复制：只在用户主动进入后包一层 [SelectionContainer]。
         *
         * 常驻包着会让**每一次长按**都变成系统文本选择，长按菜单就没法弹了 ——
         * 而"重新生成 / 编辑 / 删除"都在那个菜单里。所以它是一个模式：
         * 菜单里点进来，点「完成」出去。
         */
        if (selecting) {
            SelectionContainer {
                answerBody()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.msg_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatColors.current.placeholder,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.msg_select_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(SoftShape)
                        .clickable(onClick = onExitSelect)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        } else {
            answerBody()
        }

        /*
         * 产物条：这一轮 AI **自己 @ 出来**的文件，挂在正文末尾。
         *
         * 两个硬条件（都在 `Message.artifacts` 里实现）：
         * - 对话**结束之后**才出现 —— 流式中恒为空，模型还没说完，清单可能变；
         * - 模型没 @ 就没有 —— 不再拿工具调用"写过哪些文件"自动兜底。
         *
         * `remember` 是为了别在每次重组时重扫整段正文：正则扫几万字不贵，但
         * 流式期间一帧扫一次就白烧电了。
         */
        val artifacts = remember(message.content, message.status, remoteWorkspace) {
            message.artifacts(remoteWorkspace)
        }
        if (artifacts.isNotEmpty()) {
            val context = LocalContext.current
            Spacer(Modifier.size(8.dp))
            ArtifactBar(
                paths = artifacts,
                onOpen = { path ->
                    val custom = onOpenArtifact
                    if (custom != null) {
                        custom(path)
                    } else {
                        com.simplechat.app.ui.common.ArtifactOpener.open(context, path).onFailure {
                            android.widget.Toast.makeText(
                                context,
                                Res.get(R.string.artifact_open_failed, it.message.orEmpty()),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }

        // 正文下方的来源条：与正文角标同一套编号，两条路都能点开
        if (timeline != null && timeline.sources.isNotEmpty()) {
            Spacer(Modifier.size(10.dp))
            SourcesStrip(
                sources = timeline.sources,
                onOpenSources = { sourceSheet = it },
            )
        }

        sourceSheet?.let { sources ->
            SourceSheet(sources = sources, onDismiss = { sourceSheet = null })
        }

        message.errorMessage?.let { error ->
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatColors.current.danger,
                    modifier = Modifier.weight(1f),
                )
                /*
                 * 重试按钮**无条件**出现，不做"可重试才显示"的判断。
                 *
                 * `StreamEvent.retryable` 判断的是传输层值不值得自动重试，
                 * 而这里是用户在**手动**点 —— 联网恢复了、API Key 改对了、
                 * 换了个模型，任何一种都可能让刚才的错误不再发生。
                 * 按 retryable 藏起来反而会把路堵死。
                 */
                Spacer(Modifier.size(10.dp))
                CapsuleButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry,
                    leadingIcon = AppIcons.Refresh,
                )
            }
        }

        menu()
    }
}

/** 消息下方操作栏：复制 / 重新生成 / 编辑 / 删除，以及版本切换器。 */
@Composable
private fun MessageActions(
    message: UiMessage,
    peeked: Boolean,
    canContinue: Boolean,
    speaking: Boolean,
    readAloudAvailable: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onVariantChange: (Int) -> Unit,
) {
    val colors = LocalChatColors.current

    /*
     * 一整行按钮**同一个尺寸、同一个节奏**。
     *
     * 先前的毛病是版本切换器用 26dp/14dp、操作图标用 30dp/16dp，
     * 同一行里两种大小并排，chevron 明显小一圈、还挤在一起。
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // 版本切换器放在最前，紧贴内容一侧，和 ‹ n/m › 的语义一致
        if (message.variantCount > 1) {
            VariantSwitcher(
                index = message.variantIndex,
                count = message.variantCount,
                onChange = onVariantChange,
            )
            Spacer(Modifier.size(ActionGap))
        }

        ActionIcon(AppIcons.Copy, stringResource(R.string.action_copy), onCopy, colors)
        if (message.role == MessageRole.ASSISTANT) {
            ActionIcon(AppIcons.Refresh, stringResource(R.string.msg_regenerate), onRegenerate, colors)
            // 续写紧挨着「重新生成」：两者是同一件事的两个方向（重来 / 接着写）
            if (canContinue) {
                ActionIcon(AppIcons.Play, stringResource(R.string.msg_continue), onContinue, colors)
            }
            // 朗读：进行中换成停止图标，按同一下即停。
            // 不可用时整颗按钮不出现（见 ReadAloudEnabled / readAloudAvailable）——
            // 留着但点了没反应更糟；弹一句"不支持"也一样糟。
            if (ReadAloudEnabled && readAloudAvailable) {
                ActionIcon(
                    if (speaking) AppIcons.Stop else AppIcons.Volume,
                    stringResource(if (speaking) R.string.tts_stop else R.string.tts_speak),
                    onSpeak,
                    colors,
                )
            }
        }
        ActionIcon(AppIcons.Edit, stringResource(R.string.action_edit), onEdit, colors)
        ActionIcon(AppIcons.Trash, stringResource(R.string.action_delete), onDelete, colors)

        /*
         * 模型名 · token 数 —— **碰一下才出现，就在按钮旁边**。
         *
         * 用 `weight(1f, fill = false)` 而不是加一个 Spacer：
         * 让它在按钮之后的**剩余宽度**里取，长模型名会自动省略号，
         * 而不是把按钮挤出屏幕。
         */
        AnimatedVisibility(
            visible = peeked,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            MessageMeta(
                message = message,
                modifier = Modifier.padding(start = ActionGap + 8.dp),
            )
        }
    }
}

/**
 * 「这条是谁写的、占多少 token」。
 *
 * token 数**算的是它进上下文的那部分**：正文 + 附件。
 * 思维链不计 —— 它只在本地展示，构造请求时不回传（§11.1），
 * 算进去会让这个数字与实际占用对不上。
 */
@Composable
private fun MessageMeta(message: UiMessage, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    val tokens = TokenEstimate.of(message.content) +
        attachmentTokenEstimate(message.attachments)

    // 老数据（v3 以前）没有 model 这一列 → 只显示 token 数，不猜一个模型填上去
    val label = listOfNotNull(
        message.model?.takeIf { it.isNotBlank() },
        "$tokens tokens",
    ).joinToString(" · ")

    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 操作栏统一尺寸 —— 改这里一处，整行跟着变。 */
private val ActionButtonSize = 30.dp
private val ActionIconSize = 16.dp
private val ActionGap = 2.dp

/**
 * 消息内部各段之间的间距：思考栏 ↔ 正文 ↔ 操作栏。
 *
 * **只有一个值** —— 上下两处用同一常量，间距才是真的统一。
 * 先前是「思考栏→正文 14dp、正文→操作栏 6dp」，两头不一致。
 */
private val MessageGap = 12.dp

/** 滚动预热：当前视口上方预解析几条。一屏大约 3–5 条，6 条够垫一屏。 */
private const val WarmBeforeItems = 6

/** 超过这个长度的消息不预热：解析它本身就要几百毫秒，会把后台线程占满。 */
private const val WarmMaxChars = 20_000

/** 用户消息收起时显示几行。超过就折叠 —— 参照官方。 */
private val UserBubbleCollapsedLines = 10

/** 折叠时右下角渐隐的半径。要够大才有"化开"的感觉，太小就成了一条硬边。 */
private val UserBubbleFadeRadius = 140.dp

/*
 * 折叠刻度（行数 / 渐隐半径 / 胶囊尺寸）见各自调用点；
 * 折叠**时长**在 `ui/common/Motion.kt` 的 `FoldAnimMs` —— 思考面板用的是同一个。
 */

/** 折叠按钮的高。比操作栏的图标按钮小一档 —— 它是挂在气泡里的，不该抢戏。 */
private val UserBubbleFoldButtonSize = 22.dp

/** 折叠按钮的宽。比高宽出一截 —— 做成**胶囊**，不是圆。 */
private val UserBubbleFoldButtonWidth = 40.dp

@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    colors: ChatColors,
) {
    IconCircleButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        size = ActionButtonSize,
        iconSize = ActionIconSize,
        tint = colors.processText,
    )
}

/** 版本切换器 `‹ 2/3 ›`，对应槽位模型的多版本。 */
@Composable
fun VariantSwitcher(
    index: Int,
    count: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 与操作栏同一尺寸规格，不再自成一套
        IconCircleButton(
            icon = AppIcons.ChevronLeft,
            contentDescription = stringResource(R.string.msg_variant_prev),
            onClick = { onChange(index - 1) },
            size = ActionButtonSize,
            iconSize = ActionIconSize,
            enabled = index > 0,
            tint = if (index > 0) {
                MaterialTheme.colorScheme.onBackground
            } else {
                colors.placeholder
            },
        )
        Text(
            text = "${index + 1}/$count",
            style = MaterialTheme.typography.labelSmall,
            color = colors.processText,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        IconCircleButton(
            icon = AppIcons.ChevronRight,
            contentDescription = stringResource(R.string.msg_variant_next),
            onClick = { onChange(index + 1) },
            size = ActionButtonSize,
            iconSize = ActionIconSize,
            enabled = index < count - 1,
            tint = if (index < count - 1) {
                MaterialTheme.colorScheme.onBackground
            } else {
                colors.placeholder
            },
        )
    }
}
