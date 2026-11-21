package com.simplechat.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import com.simplechat.app.R
import com.simplechat.app.agent.Workspace
import com.simplechat.app.agent.WorkspaceKind
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.settings.SettingsBarAction
import com.simplechat.app.ui.settings.SettingsButton
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsHint
import com.simplechat.app.ui.settings.SettingsRow
import com.simplechat.app.ui.settings.SettingsTone
import com.simplechat.app.ui.settings.SettingsTopBar
import androidx.compose.foundation.layout.Box

/**
 * 工作区绑定。
 *
 * 三种来源共存，切换只影响"Agent 和终端能看到哪个目录"：
 * ```
 * 内置   files/workspace        永远可用，不用任何权限
 * 本机   /storage/emulated/0/... 需要「所有文件访问」
 * 容器   .../ubuntu/rootfs/app   Ubuntu 里那个目录的宿主机路径
 * ```
 *
 * 为什么绑定的是**路径**而不是 SAF 的 tree URI：终端要 `cd`、Shell 要 `cwd`、
 * 脚本要真实路径 —— SAF URI 一个都给不了。代价是要「所有文件访问」，
 * 所以这一页把"没授权时怎么办"直接写在脸上。
 */
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    /** 当前对话 id。云端环境是**按会话**绑的，没有它这一页就只能管本地目录。 */
    conversationId: String? = null,
    onOpenCloud: () -> Unit = {},
    /**
     * 从选择页的「选择文件夹」进来。
     *
     * 此时这一页只做一件事：走目录、绑当前目录。云端、当前绑定、常用位置全部收起来 ——
     * 那些是"管理"的动作，而用户此刻在"挑一个文件夹"，多出来的每一块都是干扰。
     */
    browseOnly: Boolean = false,
    /** 初始浏览目录。空 = 从当前绑定开始。 */
    startPath: String? = null,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val current by Workspace.current.collectAsState()
    val cloudBinding by com.simplechat.app.agent.CloudEnvs.binding.collectAsState()
    val boundCloud = remember(cloudBinding, conversationId) {
        com.simplechat.app.agent.CloudEnvs.envOf(conversationId)
    }

    var browsePath by remember {
        mutableStateOf(
            startPath?.takeIf { it.isNotBlank() }
                ?: current.path.takeIf { it.isNotBlank() }
                ?: "/storage/emulated/0",
        )
    }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualPath by remember { mutableStateOf(false) }

    LaunchedEffect(browsePath) {
        error = null
        entries = withContext(Dispatchers.IO) {
            val dir = File(browsePath)
            if (!dir.isDirectory) {
                error = Res.get(R.string.workspace_not_dir)
                emptyList()
            } else {
                runCatching {
                    dir.listFiles().orEmpty()
                        .filter { it.isDirectory && it.canRead() }
                        .sortedBy { it.name.lowercase() }
                }.getOrElse {
                    error = Res.get(R.string.workspace_no_permission)
                    emptyList()
                }
            }
        }
    }

    fun bind(path: String, kind: WorkspaceKind) {
        val result = Workspace.bind(path, kind)
        error = result.exceptionOrNull()?.message
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            // 状态栏的留白由 SettingsTopBar 自己加
            .navigationBarsPadding(),
    ) {
        /*
         * 与设置里其它页同一个顶栏；「手动输入路径」是它右侧的图标动作。
         * 此前这里是一条手写的顶栏：36dp 灰底圆按钮 + 左对齐标题，
         * 从设置点进来会看到返回箭头换了个样子。
         */
        SettingsTopBar(
            title = Res.get(
                if (browseOnly) R.string.workspace_pick_device else R.string.workspace_title,
            ),
            onBack = onBack,
            actions = {
                SettingsBarAction(
                    icon = AppIcons.Edit,
                    contentDescription = Res.get(R.string.workspace_input_path),
                    onClick = { manualPath = true },
                )
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            /*
             * 从选择页进来时这三块全部收起。留下的只有"走目录 + 绑到这儿"，
             * 也就是用户点「选择文件夹」时想看的那一件事。
             */
            if (!browseOnly) {
                // ── 云端环境（按会话绑定，优先于下面的本地目录） ────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                        .background(colors.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = AppIcons.Cloud,
                            contentDescription = null,
                            tint = if (boundCloud != null) MaterialTheme.colorScheme.primary else colors.placeholder,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = Res.get(R.string.workspace_cloud_section),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                            modifier = Modifier.weight(1f),
                        )
                        SmallAction(
                            if (boundCloud != null) Res.get(R.string.workspace_cloud_switch)
                            else Res.get(R.string.workspace_cloud_bind),
                            onOpenCloud,
                        )
                    }
                    if (boundCloud != null) {
                        Text(
                            text = boundCloud.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = Res.get(R.string.workspace_cloud_bound_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        SmallAction(Res.get(R.string.workspace_cloud_unbind)) {
                            conversationId?.let { com.simplechat.app.agent.CloudEnvs.unbind(it) }
                        }
                    } else {
                        Text(
                            text = Res.get(R.string.workspace_cloud_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.placeholder,
                        )
                    }
                }

                // ── 当前绑定 ────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                        .background(colors.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = Res.get(R.string.workspace_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                    )
                    Text(
                        text = current.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = current.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (current.kind) {
                            WorkspaceKind.NONE -> Res.get(R.string.workspace_kind_none)
                            WorkspaceKind.INTERNAL -> Res.get(R.string.workspace_kind_internal)
                            WorkspaceKind.DEVICE -> Res.get(R.string.workspace_kind_device)
                            WorkspaceKind.CONTAINER -> Res.get(R.string.workspace_kind_container)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                /*
                 * 内置工作区列表。
                 *
                 * 创建入口在对话页的工作区浮层里，管理入口在这里 —— 两边看到的
                 * 是同一个目录（`files/workspace`），删掉之后浮层里也立刻消失，
                 * 因为都走 `Workspace.internalFolders()`。
                 */
                var folders by remember { mutableStateOf(Workspace.internalFolders()) }
                var deleting by remember { mutableStateOf<File?>(null) }

                Text(
                    text = Res.get(R.string.workspace_pick_internal),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    modifier = Modifier.padding(start = SettingsDimens.RowPadH, top = 6.dp),
                )
                if (folders.isEmpty()) {
                    SettingsHint(text = Res.get(R.string.workspace_internal_empty))
                } else {
                    folders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                                .background(colors.card)
                                .clickable { bind(folder.absolutePath, WorkspaceKind.INTERNAL) }
                                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = AppIcons.Folder,
                                contentDescription = null,
                                tint = colors.processText,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconCircleButton(
                                icon = AppIcons.Trash,
                                contentDescription = Res.get(R.string.workspace_delete),
                                onClick = { deleting = folder },
                                size = 34.dp,
                                iconSize = 15.dp,
                                tint = colors.placeholder,
                            )
                        }
                    }
                }

                deleting?.let { folder ->
                    AppConfirmDialog(
                        title = Res.get(R.string.workspace_delete),
                        message = Res.get(R.string.workspace_delete_confirm, folder.name),
                        confirmText = Res.get(R.string.action_delete),
                        onDismiss = { deleting = null },
                        onConfirm = {
                            Workspace.deleteInternal(folder)
                                .onFailure { error = it.message }
                            folders = Workspace.internalFolders()
                            deleting = null
                        },
                        destructive = true,
                    )
                }

                SettingsHint(text = Res.get(R.string.workspace_hint))

                Row(
                    modifier = Modifier.padding(start = SettingsDimens.RowPadH),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallAction(Res.get(R.string.workspace_open_permissions), onOpenPermissions)
                    SmallAction(Res.get(R.string.workspace_reset)) {
                        Workspace.unbind()
                        browsePath = Workspace.requireRoot().absolutePath
                    }
                }

                // ── 快捷位置 ────────────────────────────────
                Text(
                    text = Res.get(R.string.workspace_shortcuts),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    modifier = Modifier.padding(start = SettingsDimens.RowPadH, top = 6.dp),
                )
                remember { Workspace.candidates() }.forEach { info ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                            .background(colors.card)
                            .clickable { browsePath = info.path }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = info.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = info.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ── 浏览 ────────────────────────────────────
            Spacer(Modifier.height(4.dp))
            Text(
                text = Res.get(R.string.workspace_browse),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(start = SettingsDimens.RowPadH),
            )
            Text(
                text = browsePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = SettingsDimens.RowPadH),
            )

            val parent = File(browsePath).parentFile
            if (parent != null) {
                TextRow("..", Res.get(R.string.workspace_parent)) { browsePath = parent.absolutePath }
            }
            entries.forEach { dir ->
                TextRow(dir.name, null) { browsePath = dir.absolutePath }
            }
            if (entries.isEmpty() && error == null) {
                SettingsHint(
                    text = Res.get(R.string.workspace_empty),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            error?.let {
                SettingsHint(text = it, tone = SettingsTone.Danger)
            }

            // 胶囊按钮（全应用"带文字的按钮 = 胶囊"）；此前是 Material 的 Button 配 12dp 圆角
            SettingsButton(
                text = Res.get(R.string.workspace_bind_here),
                onClick = {
                    bind(
                        browsePath,
                        if (browsePath.startsWith(Workspace.appDir().absolutePath)) {
                            WorkspaceKind.INTERNAL
                        } else {
                            WorkspaceKind.DEVICE
                        },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )

            SettingsHint(
                text = Res.get(R.string.workspace_container_hint),
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }

    if (manualPath) {
        AppTextInputDialog(
            title = Res.get(R.string.workspace_input_path),
            initial = browsePath,
            onDismiss = { manualPath = false },
            onConfirm = { value ->
                browsePath = value.trim()
                manualPath = false
                if (value.trim().isNotEmpty()) {
                    Workspace.bind(value.trim(), WorkspaceKind.DEVICE)
                }
            },
        )
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            // 胶囊形，且先裁再上色：水波纹跟着形状走，不再是一个露出四角的方块
            .clip(PillShape)
            .background(colors.capsuleSelected)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 目录浏览里的一行（一个子目录，或「..」回上一层）。
 *
 * 用的是设置里通用的那种行：行首文件夹图标、行尾一根箭头。此前行首和行尾各是
 * 一颗 24dp 的灰底圆按钮，而且子目录的图标是「数据库」—— 和它是什么对不上。
 */
@Composable
private fun TextRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsDimens.CardCorner))
            .background(colors.card),
    ) {
        SettingsRow(
            title = title,
            subtitle = subtitle,
            icon = if (title == "..") AppIcons.ArrowUpDir else AppIcons.Folder,
            chevron = true,
            onClick = onClick,
        )
    }
}
