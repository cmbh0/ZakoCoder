package com.simplechat.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ProfileAvatar
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuDivider
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.common.rememberPressPosition
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 抽屉内**所有全宽元素**共用的左右内边距。
 *
 * 搜索框、分组标题、会话行高亮都从它取值 —— 之前三处各写各的
 * （16 / 16 / 8dp），高亮块和搜索框左右对不齐，很显眼。
 */
private val DrawerInset = Space.Screen

/** 块内文字的左边界（搜索框占位符、分组标题、会话标题共用，保证一条竖线对齐）。 */
private val DrawerTextInset = Space.Screen + Space.Inline

/**
 * 侧拉会话列表。
 *
 * 结构（对齐 DeepSeek 抽屉）：
 * ```
 * [ 🔍 搜索对话内容… ]
 * 今天                    ⚙
 *   置顶的会话（浅蓝底）
 *   会话 …
 * 30 天内
 *   …
 * [ 头像 名字              … ]   ← 点行进设置，「…」里是账号管理
 * ```
 */
@Composable
fun HistoryDrawerContent(
    conversations: List<ConversationEntity>,
    currentConversationId: String?,
    selectionMode: Boolean,
    selectedIds: List<String>,
    onSearchClick: () -> Unit,
    onClick: (ConversationEntity) -> Unit,
    onEnterSelection: (ConversationEntity) -> Unit,
    onToggleSelection: (String) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onTogglePin: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    /** 长按菜单里的「对话设置」：打开该会话并展开设置面板。 */
    onOpenChatSettings: (ConversationEntity) -> Unit = {},
    onExitSelection: () -> Unit,
    onPinSelected: (Boolean) -> Unit,
    onDeleteSelected: () -> Unit,
    /** 本地资料：名字与头像。空名字显示「未设置」。 */
    userName: String,
    userAvatar: String,
    /** 点底部那一行 → 直接进设置。 */
    onOpenProfile: () -> Unit,
    /** 「…」菜单里的账号管理：只改头像与名字。 */
    onOpenAccount: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            /*
             * 用 `sidebar` 而不是 `card`。
             *
             * 这一层原来铺的是卡片白，把 `DrawerSurface` 的 `sidebar` 底整个盖掉了 ——
             * 于是侧边栏和页面同色，只剩那条边框在区分，深色下更是两层都近黑。
             * 官方 chat-demo 的侧边栏底色本来就和页面差一档，这里必须让它透上来。
             */
            .background(colors.sidebar),
    ) {
        Spacer(Modifier.statusBarsPadding())

        // ── 搜索框：点击进入全屏搜索页 ────────────────────
        SearchBox(onClick = onSearchClick)

        if (selectionMode) {
            SelectionHeader(count = selectedIds.size)
        }

        // ── 列表 ─────────────────────────────────────────
        val grouped = remember(conversations) { groupConversations(conversations) }

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.hist_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 2.dp,
                    bottom = 8.dp,
                ),
            ) {
                grouped.forEachIndexed { index, (group, items) ->
                    item(key = "header-${group.name}") {
                        GroupHeader(
                            label = group.label,
                            detail = groupDetail(items, group),
                            // 只在第一组右侧放一颗「管理」：它属于整个列表，不属于某一组。
                            // 放每组会变成四个重复按钮，放底部又和用户行挤在一起。
                            onManage = if (index == 0) {
                                { conversations.firstOrNull()?.let(onEnterSelection) }
                            } else {
                                null
                            },
                        )
                    }
                    items(items = items, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            current = conversation.id == currentConversationId,
                            selectionMode = selectionMode,
                            selected = conversation.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    onToggleSelection(conversation.id)
                                } else {
                                    onClick(conversation)
                                }
                            },
                            onEnterSelection = { onEnterSelection(conversation) },
                            onRename = { onRename(conversation) },
                            onTogglePin = { onTogglePin(conversation) },
                            onDelete = { onDelete(conversation) },
                            onOpenChatSettings = { onOpenChatSettings(conversation) },
                        )
                    }
                }
            }
        }

        // ── 底部：多选操作栏 / 个人资料入口 ──────────────────
        /*
         * 抽屉底部**只有一个入口**：用户自己。
         *
         * 上一版这里是「设置」一行 + 一条分割线 + 「头像名字」一行。
         * 两行说的是两个地方，用户每次都要先判断"我要改的东西在哪边"——
         * 而实际上设置页里也有一份资料行，等于同一个入口摆了两处。
         *
         * 现在合并：点自己 → 资料页 → 资料页里那一行「设置」进总设置。
         * 也没有分割线了：整块就是一个人，不需要用线把自己和自己分开。
         */
        if (selectionMode) {
            SelectionActionBar(
                count = selectedIds.size,
                onPin = { onPinSelected(true) },
                onDelete = onDeleteSelected,
                onCancel = onExitSelection,
            )
        } else {
            /*
             * 底部：头像 + 名字 + 「…」。
             *
             * 官方就这三样，没有分割线、没有第二行副标题、也没有右箭头。
             * 点整行进设置；「…」里放账号管理与设置 —— 箭头换成菜单，
             * 是因为这里已经到底了，不需要再提示"还有一页"。
             */
            var userMenu by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(SoftShape)
                    .clickable(onClick = onOpenProfile)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(uri = userAvatar, name = userName, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = userName.ifBlank { stringResource(R.string.profile_default_name) },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = if (userName.isBlank()) {
                        colors.placeholder
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { userMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.More,
                            contentDescription = stringResource(R.string.profile_more),
                            tint = colors.processText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    AppMenu(
                        expanded = userMenu,
                        onDismissRequest = { userMenu = false },
                    ) {
                        AppMenuItem(
                            label = stringResource(R.string.settings_account_manage),
                            icon = AppIcons.Users,
                        ) {
                            userMenu = false
                            onOpenAccount()
                        }
                        AppMenuItem(
                            label = stringResource(R.string.settings_title),
                            icon = AppIcons.Settings,
                        ) {
                            userMenu = false
                            onOpenProfile()
                        }
                    }
                }
            }
        }

        // 手势条（"小白条"）不遮挡设置入口 —— 抽屉自己铺满整屏，底部必须自己让位
        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(2.dp))
    }
}

/** 多选模式下的底部操作栏。 */
@Composable
private fun SelectionActionBar(
    count: Int,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalChatColors.current
    val enabled = count > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarAction(
            label = stringResource(R.string.action_pin),
            icon = AppIcons.Pin,
            enabled = enabled,
            onClick = onPin,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            label = stringResource(R.string.action_delete),
            icon = AppIcons.Trash,
            enabled = enabled,
            tint = colors.danger,
            onClick = onDelete,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            label = stringResource(R.string.action_cancel),
            icon = AppIcons.Close,
            enabled = true,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BarAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = LocalChatColors.current
    val color = when {
        !enabled -> colors.placeholder
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  子组件
// ══════════════════════════════════════════════════════════

@Composable
private fun SearchBox(onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerInset, vertical = 8.dp)
            .height(40.dp)
            .clip(PillShape)
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.Search,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.search_hint),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.placeholder,
            /*
             * 单行 + 省略：抽屉宽度是变量（0.42 时只有 165dp），
             * 这句占位一旦允许换行，就会折成"搜索对话内 / 容…"两行，
             * 把搜索框撑高一截，看起来很坏。
             */
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectionHeader(count: Int) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.hist_selected_count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 分组大标题。
 *
 * 比原来（labelMedium + placeholder 灰）重半档：现在的分组是**工作区**，
 * 是这批对话的身份，不是时间刻度。身份要能被扫到，不能只是分层留白。
 * 但没有做成整条色带 —— 抽屉里已经有搜索框和底部两行，再多一个重块会散。
 */
@Composable
private fun GroupHeader(
    label: String,
    /** 组内具体工作区，见 `groupDetail`。null = 不显示第二行。 */
    detail: String? = null,
    onManage: (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DrawerTextInset, end = DrawerInset, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                ),
                color = colors.placeholder,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.placeholder.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onManage != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onManage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Sliders,
                    contentDescription = stringResource(R.string.hist_multi_select),
                    tint = colors.placeholder,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    conversation: ConversationEntity,
    current: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onEnterSelection: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onOpenChatSettings: () -> Unit = {},
    highlightQuery: String = "",
    /** 搜索命中正文时的一行片段；仅标题命中时为 null。 */
    snippet: String? = null,
    /** 搜索页不支持多选，隐藏该项以免点了没反应。 */
    showMultiSelect: Boolean = true,
) {
    val colors = LocalChatColors.current
    // 高亮跟着主题色（Material 的 primary 就是设置页里选的品牌色）
    val highlightColor = MaterialTheme.colorScheme.primary
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    // 长按落点：菜单跟手从这里弹出，而不是贴到行边缘
    val (pressPosition, pressListener) = rememberPressPosition()

    /*
     * 多选态：**通栏 + 直角**。
     *
     * 圆角高亮是"单条会话被选中"的语言，一条一块、互不相干。多选不是这样 ——
     * 用户一次勾三行，眼睛要看的是"这一整段被选中了"。带圆角时相邻两行之间
     * 会露出两道缺口，色带被打成一节一节；改成直角并让行与行直接贴合，
     * 连着的选中行就拼成一整块矩形，边界干净。
     *
     * 同时关掉点击涟漪：多选的反馈就是那块底色本身，再叠一圈水波只是噪声。
     */
    val rowShape = if (selectionMode) RectangleShape else SoftShape
    val outerInset = if (selectionMode) 0.dp else DrawerInset
    val innerStart = 12.dp + if (selectionMode) DrawerInset else 0.dp

    // 内边距放在外层 Box 上：菜单的锚点因此也内缩 DrawerInset，
    // 弹出的左边界与搜索框、分组标题对齐，而不是贴到屏幕边缘
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(pressListener)
            .padding(horizontal = outerInset),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .background(
                    when {
                        /*
                         * 多选态只认"勾没勾"。
                         *
                         * 当前会话那层浅色是"你正停在这里"的标记，和"被选中"是
                         * 两件事；多选时两者叠在一起，用户分不清哪几行会被删。
                         */
                        selectionMode -> if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            Color.Transparent
                        }

                        selected || current -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else -> Color.Transparent
                    },
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = if (selectionMode) null else LocalIndication.current,
                    onClick = onClick,
                    onLongClick = {
                        // 多选态下长按＝切换勾选；否则弹出上下文菜单
                        if (selectionMode) onClick() else menuOpen = true
                    },
                )
                // 多选态通栏了，文字仍要停在与搜索框同一条竖线上
                .padding(start = innerStart, end = 12.dp)
                // 单行时保持 48dp 的节奏；带片段时（搜索页）让它长高
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) AppIcons.CircleCheck else AppIcons.Circle,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else colors.placeholder,
                    modifier = Modifier.size(19.dp),
                )
            }
            /*
             * 这一条还在跑 -> 标题左边一颗小环。
             *
             * 用户切走之后那一轮并没有停（见 ChatViewModel.detachRun），
             * 但界面上没有任何迹象，他只能猜"是不是还在写"。环就是"还在写"。
             * 尺寸按 13dp 走，和正文行高对齐；比它大就会把每行撑高，
             * 侧栏看起来像一堆气泡。
             */
            if (com.simplechat.app.ui.chat.ActiveRuns.isRunning(conversation.id)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightedTitle(conversation.title, highlightQuery, highlightColor),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = if (current && !selectionMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snippet != null) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = highlightedTitle(snippet, highlightQuery, highlightColor),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 长按菜单：与对话页**同一个** AppMenu（同宽、同行高、同圆角、同内边距）
        AppMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            touchPoint = pressPosition(),
        ) {
            AppMenuItem(stringResource(R.string.action_rename), AppIcons.Edit) {
                menuOpen = false
                onRename()
            }
            AppMenuItem(stringResource(R.string.input_settings), AppIcons.Sliders) {
                menuOpen = false
                onOpenChatSettings()
            }
            AppMenuItem(
                label = if (conversation.pinned) {
                    stringResource(R.string.action_unpin)
                } else {
                    stringResource(R.string.action_pin)
                },
                icon = if (conversation.pinned) AppIcons.PinOff else AppIcons.Pin,
            ) {
                menuOpen = false
                onTogglePin()
            }
            if (showMultiSelect) {
                AppMenuItem(stringResource(R.string.hist_multi_select), AppIcons.ListChecks) {
                    menuOpen = false
                    onEnterSelection()
                }
            }
            // 分隔线固定接在危险操作之前 —— 结构不随数据变化
            AppMenuDivider()
            AppMenuItem(stringResource(R.string.action_delete), AppIcons.Trash, destructive = true) {
                menuOpen = false
                onDelete()
            }
        }
    }
}

/** 搜索时高亮匹配片段。高亮色由调用方给 —— **跟着主题色走**，写死品牌蓝会在换主题色后脱节。 */
private fun highlightedTitle(
    title: String,
    query: String,
    highlightColor: Color,
) = buildAnnotatedString {
    if (query.isBlank()) {
        append(title)
        return@buildAnnotatedString
    }
    val start = title.indexOf(query, ignoreCase = true)
    if (start < 0) {
        append(title)
        return@buildAnnotatedString
    }
    val end = start + query.length
    append(title.substring(0, start))
    withStyle(SpanStyle(color = highlightColor)) {
        append(title.substring(start, end))
    }
    append(title.substring(end))
}

/**
 * 按时间分组。组内按最近更新倒序。
 *
 * 空组整组不出现 —— 一份一个组都没有的分组标题，等于什么都没说。
 */
fun groupConversations(
    conversations: List<ConversationEntity>,
): List<Pair<HistoryGroup, List<ConversationEntity>>> =
    HistoryGroup.entries
        .map { group ->
            group to conversations
                .filter { groupOf(it) == group }
                .sortedWith(
                    compareByDescending<ConversationEntity> { it.pinned }
                        .thenByDescending { it.updatedAt },
                )
        }
        .filter { it.second.isNotEmpty() }
