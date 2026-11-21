package com.simplechat.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuDivider
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.OverlayGate

/**
 * 顶栏：`☰` 标题 ｜ 新建对话 · 更多。
 *
 * 标题**只是标题**。上一版把它做成了模型入口（两行：会话名 + 模型名 + chevron），
 * 那是错的：顶栏是"我在哪段对话里"的位置信息，模型是"这台机器现在用哪个脑子"，
 * 两者放一起，每次抬头看标题都会以为自己在点一个下拉。
 *
 * 模型回到输入栏了 —— 那是你真正要换模型时视线所在的地方。
 *
 * 标题下面那行灰字是**地点**：云端环境 / 内置工作区 / 本机目录。
 * 跟标题同一块地方、字号小一档、不换色 —— 它是"这段对话在哪干活"的
 * 持续状态，不是又一条可点的东西。工作区胶囊在输入框上方，那行灰字
 * 负责"抬头就在"，两者说的是同一件事，不必点开才知道。
 */
@Composable
fun ChatTopBar(
    title: String,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    /** 当前会话是否已置顶 —— 决定菜单里那一行显示"置顶"还是"取消置顶"。 */
    pinned: Boolean = false,
    /** 打开当前工作区的文件（"文件管理"）。 */
    onOpenFiles: () -> Unit = {},
    /** 终端。按会话隔离，从对话页直接进。 */
    onOpenTerminal: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onDelete: () -> Unit = {},
    /** 打开「设置工作区」：内置 / 本机目录 / 云端，三选一。 */
    onOpenWorkspace: () -> Unit = {},
    /** 当前工作区名字。同样写进菜单项 —— 文件落在哪儿是随时要能确认的事。 */
    workspaceLabel: String = "",
    /**
     * 标题下面那行灰字：这段对话落在哪儿（如「云端 · 香港节点」「内置工作区」）。
     *
     * 空串则不占位。由调用方拼好整句 —— 顶栏不该知道"云端优先还是本地优先"
     * 那套规则，那是 `Workspace` / `CloudEnvs` 的事。
     */
    workspaceMode: String = "",
    /**
     * 本对话的预设页。
     *
     * 菜单里**只放这一项**，不放"对话设置"。预设是用户每次换话题真的要动的东西
     * （语气、文风、角色），而标题 / 采样参数 / 导出是偶尔改一次的设置 ——
     * 两者摆在一起，前者会被后者的"设置"味盖掉。后者挪到预设页底部一行灰字里。
     */
    onOpenPresets: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var moreOpen by remember { mutableStateOf(false) }
    /*
     * 「再点一次就收起」。
     *
     * 原来的 onClick 是 `moreOpen = true` —— 点第二下时，Popup 先被系统判为
     * 窗口外触摸而 dismiss，同一次手指接着落到按钮上又把它设回 true，
     * 于是菜单**永远关不掉**（用户看到的是"点了还是开着"）。
     * 这里用 OverlayGate 吞掉紧随 dismiss 的那一次点击，其余情况正常 toggle。
     * 输入栏的几颗浮层早就这么做了，顶栏是漏掉的一处。
     */
    val moreGate = remember { OverlayGate() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarAction(AppIcons.Menu, stringResource(R.string.topbar_history), onMenu)

        /*
         * 标题不做成可点的块。`weight(1f)` + 单行省略就够了 ——
         * 它现在除了"看"没有第二个语义。
         *
         * 两行都钉死行高：56dp 的顶栏里，标题 20sp + 副标题 12sp = 32dp，
         * 上下各留 12dp。交给默认行高（titleMedium 是 1.5 倍）会撑到 40dp，
         * 副标题一出现整条顶栏就变高、下面的列表跟着跳一格。
         */
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (workspaceMode.isNotBlank()) {
                Text(
                    text = workspaceMode,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                    ),
                    color = LocalChatColors.current.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 加号在气泡里：与 DeepSeek 顶栏同一个符号，也是"新开一段对话"最直接的隐喻
        TopBarAction(AppIcons.PlusCircle, stringResource(R.string.topbar_new_chat), onNewChat)

        /* 三点。菜单位置不自己算 —— AppMenu 默认贴锚点右缘弹出。 */
        Box {
            TopBarAction(
                icon = AppIcons.More,
                contentDescription = stringResource(R.string.topbar_more),
                onClick = {
                    if (moreGate.acceptToggle()) moreOpen = !moreOpen
                },
            )
            AppMenu(
                expanded = moreOpen,
                onDismissRequest = {
                    moreGate.markClosed()
                    moreOpen = false
                },
            ) {
                /*
                 * 「预设」放在第一项。
                 *
                 * 它回答的是"这段对话用什么语气"—— 换话题时最常动的一件，
                 * 而且只作用于当前会话。做成独立的一页（见 [PresetSheet]）：
                 * 每条预设都有名字、摘要、开关和生效位次，不再挤在输入栏上方
                 * 那张三百像素高的小卡片里。
                 */
                AppMenuItem(
                    label = stringResource(R.string.preset_sheet_title),
                    icon = AppIcons.Prompt,
                    onClick = {
                        moreOpen = false
                        onOpenPresets()
                    },
                )
                AppMenuItem(
                    label = stringResource(R.string.topbar_terminal),
                    icon = AppIcons.Terminal,
                    onClick = {
                        moreOpen = false
                        onOpenTerminal()
                    },
                )
                AppMenuItem(
                    label = stringResource(R.string.topbar_files),
                    icon = AppIcons.Folder,
                    onClick = {
                        moreOpen = false
                        onOpenFiles()
                    },
                )
                // 分隔线固定接在危险操作之前 —— 没有"有没有会话"这类分支，结构稳定
                AppMenuDivider()
                AppMenuItem(
                    label = stringResource(R.string.topbar_delete),
                    icon = AppIcons.Trash,
                    destructive = true,
                    onClick = {
                        moreOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 顶栏图标按钮，48dp 触控目标。 */
@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(22.dp),
        )
    }
}
