package com.simplechat.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.agent.Workspace
import com.simplechat.app.agent.WorkspaceKind
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors
import androidx.compose.ui.draw.clip
import com.simplechat.app.ui.settings.SettingsChevron
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsTopBar

/**
 * 「设置工作区」—— 选一种来源，而不是直接掉进目录浏览器。
 *
 * 早先点顶栏的工作区，进去就是一棵目录树加一堆按钮：云端、当前绑定、常用位置、
 * 浏览、绑定，全在一页。想用内置目录的人（大多数）要先滚过三张卡才能找到按钮。
 *
 * 这一页只回答一个问题：**文件放哪儿**。三种来源并列，选完再进各自的页面：
 * ```
 * 内置   应用私有目录，不需要任何权限      → 直接生效，返回
 * 设备   从存储里挑一个目录               → 进目录浏览，选到哪绑到哪
 * 云端   连一台远端机器                   → 进云端环境页
 * ```
 *
 * 版式取参考图的意思（居中标题 + 一列大块选项），配色按本项目来：没有色块堆叠，
 * 只有描边和一层很淡的底 —— 和设置页同一个语言。
 */
@Composable
fun WorkspacePickerScreen(
    onBack: () -> Unit,
    /** 已绑定别的目录时，点当前这张卡看详情。 */
    onOpenDetail: () -> Unit,
    onPickDevice: () -> Unit,
    onPickCloud: () -> Unit,
    onPickedInternal: () -> Unit,
) {
    val colors = LocalChatColors.current
    val current by Workspace.current.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            // 状态栏的留白由 SettingsTopBar 自己加
            .navigationBarsPadding(),
    ) {
        // 这一页的标题在正文里（大字），顶栏只留返回箭头 —— 但箭头的样子、位置
        // 与设置里其它页是同一个
        SettingsTopBar(title = "", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsDimens.ScreenPadding),
        ) {
            Spacer(Modifier.size(28.dp))

            // ── 标题区 ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(colors.capsuleSelected, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(18.dp))
            Text(
                text = Res.get(R.string.workspace_pick_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = Res.get(R.string.workspace_pick_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.placeholder,
            )

            Spacer(Modifier.size(26.dp))

            // ── 三种来源 ──────────────────────────────────────
            PickerCard(
                icon = AppIcons.Database,
                title = Res.get(R.string.workspace_pick_internal),
                description = Res.get(R.string.workspace_pick_internal_desc),
                onClick = {
                    Workspace.bind(
                        Workspace.internalPath(),
                        WorkspaceKind.INTERNAL,
                        Res.get(R.string.workspace_pick_internal),
                    )
                    onPickedInternal()
                },
            )
            Spacer(Modifier.size(10.dp))
            PickerCard(
                icon = AppIcons.Folder,
                title = Res.get(R.string.workspace_pick_device),
                description = Res.get(R.string.workspace_pick_device_desc),
                onClick = onPickDevice,
            )
            Spacer(Modifier.size(10.dp))
            PickerCard(
                // 与设置首页「云端环境」入口同一枚图标
                icon = AppIcons.Cloud,
                title = Res.get(R.string.workspace_pick_cloud),
                description = Res.get(R.string.workspace_pick_cloud_desc),
                onClick = onPickCloud,
            )

            // ── 当前绑定 ──────────────────────────────────────
            Spacer(Modifier.size(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDetail)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val plain = current.kind == WorkspaceKind.NONE
                    Text(
                        text = if (plain) {
                            Res.get(R.string.workspace_pick_plain)
                        } else {
                            Res.get(R.string.workspace_pick_current, current.label)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 未设置时不显示路径：那个路径是内部实现，不是用户选过的东西
                    if (!plain) {
                        Text(
                            text = current.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.placeholder,
                    modifier = Modifier.size(14.dp),
                )
            }

            Spacer(Modifier.size(20.dp))
        }
    }
}

/**
 * 一个来源。
 *
 * 图标放在左边而不是居中：三种竖排时，"图标一行、文字一行"每张要吃掉 140dp，
 * 一屏放不下第三张，用户得滚动才知道有云端这个选项 —— 恰恰是最需要被看到的那一项。
 * 横排把三张压进一屏，代价是少一点"引导页"的仪式感，但这本来就是个设置项。
 */
@Composable
private fun PickerCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            // 与设置里的卡片同一种容器：同一个圆角、不描边（灰底上的白卡不需要再勾一圈线），
            // 先裁再上色，水波纹不会溢出四个角
            .clip(RoundedCornerShape(SettingsDimens.CardCorner))
            .background(colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.capsuleSelected, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }
        SettingsChevron()
    }
}
