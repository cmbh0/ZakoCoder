package com.simplechat.app.ui.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.skill.BuiltinSkills
import com.simplechat.app.skill.Skill
import com.simplechat.app.skill.SkillStore
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.rememberFileSaver
import com.simplechat.app.ui.settings.SettingsTopBar
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.launch
import com.simplechat.app.ui.settings.SettingsTextAction
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsEmptyState
import com.simplechat.app.ui.settings.SettingsHint

/**
 * 技能管理。
 *
 * 版式与 MCP 页一致：**一张分组卡**，每行一个技能，右边开关，点行看详情。
 * 有可调参数的技能在名字后面挂一颗滑杆图标 —— 它本身就是入口，点开直接
 * 调参，不必先进详情再找。
 *
 * 底部的两个"添加"做成行，而不是两颗并排的大胶囊：它们属于同一个分组，
 * 和大按钮摆在一起会显得页面重心在"添加"而不是"我有哪些技能"。
 */
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skills by SkillStore.skills.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Skill?>(null) }
    var tuning by remember { mutableStateOf(false) }

    val store = remember { (context.applicationContext as App).container.settingsStore }
    var stored by remember { mutableStateOf(StoredSettings()) }
    LaunchedEffect(Unit) {
        SkillStore.refresh()
        store.flow.collect { stored = it }
    }

    val pickZip = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                message = Res.get(R.string.error_read_failed_short)
                return@launch
            }
            val result = SkillStore.installZip(bytes)
            message = result.fold(
                onSuccess = { ids -> Res.get(R.string.skills_installed, ids.size) },
                onFailure = { it.message ?: Res.get(R.string.error_install_failed_short) },
            )
        }
    }

    /*
     * 导出：先打包成 zip，再让用户挑落点。
     *
     * 打包在 IO 线程做（技能里可能有几百 KB 的脚本），选位置必须在主线程 ——
     * 所以顺序是"协程里打包 → 回到主线程调起选择器"。
     */
    val saveSkillZip = rememberFileSaver(mime = "application/zip") { ok ->
        message = if (ok) {
            Res.get(R.string.export_done)
        } else {
            Res.get(R.string.error_write_failed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(title = Res.get(R.string.skills_title), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            message?.let {
                SettingsHint(text = it, modifier = Modifier.padding(vertical = 4.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 圆角与设置里其它卡片同一个值（此前这张是 14dp，比别的页大一圈）
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card),
            ) {
                if (skills.isEmpty()) {
                    // 设置里通用的空态；图标与设置首页「技能」入口是同一枚拼图
                    SettingsEmptyState(
                        icon = AppIcons.Puzzle,
                        title = Res.get(R.string.skills_empty_title),
                        body = Res.get(R.string.skills_empty),
                    )
                } else {
                    skills.forEachIndexed { index, skill ->
                        if (index > 0) SkillDivider()
                        SkillRow(
                            skill = skill,
                            hasTuning = skill.id == BuiltinSkills.Proactive,
                            onToggle = { enabled ->
                                scope.launch { SkillStore.setEnabled(skill.id, enabled) }
                            },
                            onOpen = { detail = skill },
                            onTune = { tuning = true },
                        )
                    }
                }
                if (skills.isNotEmpty()) SkillDivider()
                ActionRow(
                    icon = AppIcons.Upload,
                    label = Res.get(R.string.skills_install_zip),
                    onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) },
                )

            }

            Spacer(Modifier.height(10.dp))
            SettingsHint(text = Res.get(R.string.skills_footer_hint))
            Spacer(Modifier.size(24.dp))
        }
    }

    detail?.let { skill ->
        SkillDetailDialog(
            skill = skill,
            hasTuning = skill.id == BuiltinSkills.Proactive,
            onTune = {
                detail = null
                tuning = true
            },
            onDelete = {
                detail = null
                scope.launch { SkillStore.delete(skill.id) }
            },
            onExport = {
                val id = skill.id
                detail = null
                scope.launch {
                    val result = SkillStore.exportZip(id)
                    result.fold(
                        onSuccess = { bytes -> saveSkillZip("$id.zip", bytes) },
                        onFailure = { message = it.message ?: Res.get(R.string.export_failed_short) },
                    )
                }
            },
            onDismiss = { detail = null },
        )
    }

    if (tuning) {
        ProactiveTuningDialog(
            chance = stored.proactiveChance,
            idleMinutes = stored.proactiveIdleMinutes,
            onChance = { scope.launch { store.setProactiveChance(it) } },
            onIdle = { scope.launch { store.setProactiveIdle(it) } },
            onDismiss = { tuning = false },
        )
    }


}

@Composable
private fun SkillRow(
    skill: Skill,
    hasTuning: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onTune: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.fieldBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Puzzle,
                contentDescription = null,
                tint = if (skill.enabled) MaterialTheme.colorScheme.primary else colors.placeholder,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (skill.version.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "v${skill.version}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = colors.placeholder,
                    )
                }
                if (hasTuning) {
                    Spacer(Modifier.width(6.dp))
                    // 可调参数的入口：一颗小图标，点它直接开参数面板
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(colors.fieldBackground)
                            .clickable(onClick = onTune),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.Sliders,
                            contentDescription = Res.get(R.string.skill_tuning_open),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = colors.placeholder,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        AppSwitch(checked = skill.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SkillDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .height(0.7.dp)
            .background(LocalChatColors.current.divider),
    )
}

@Composable
private fun SkillDetailDialog(
    skill: Skill,
    hasTuning: Boolean,
    onTune: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 弹窗外框与设置里其它弹窗同一套：28dp 圆角、20dp 内边距
                .clip(ContainerShape)
                .background(colors.card)
                .padding(SettingsDimens.DialogPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (skill.version.isNotBlank()) {
                    Text(
                        text = "v${skill.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                    )
                }
            }
            Text(
                text = skill.description.ifBlank { Res.get(R.string.skill_no_desc) },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.processText,
                modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            )
            if (hasTuning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PillShape)
                        .background(colors.fieldBackground)
                        .clickable(onClick = onTune)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.Sliders,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Res.get(R.string.skill_tuning_open),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.placeholder,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextAction(
                    text = Res.get(R.string.skills_delete),
                    onClick = onDelete,
                    destructive = true,
                    compact = true,
                )
                SettingsTextAction(
                    text = Res.get(R.string.skills_export),
                    onClick = onExport,
                    primary = true,
                    compact = true,
                )
                Spacer(Modifier.weight(1f))
                SettingsTextAction(
                    text = Res.get(R.string.action_close),
                    onClick = onDismiss,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ProactiveTuningDialog(
    chance: Int,
    idleMinutes: Int,
    onChance: (Int) -> Unit,
    onIdle: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    var draftChance by remember(chance) { mutableStateOf(chance.toFloat()) }
    var draftIdle by remember(idleMinutes) { mutableStateOf(idleMinutes.toFloat()) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 弹窗外框与设置里其它弹窗同一套：28dp 圆角、20dp 内边距
                .clip(ContainerShape)
                .background(colors.card)
                .padding(SettingsDimens.DialogPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = Res.get(R.string.skill_tuning_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            TuningSlider(
                title = Res.get(R.string.skill_proactive_chance),
                value = "${draftChance.toInt()}%",
            ) {
                AppSlider(
                    value = draftChance,
                    onValueChange = { draftChance = it },
                    valueRange = 0f..100f,
                    intervals = 20,
                    onValueChangeFinished = { onChance(draftChance.toInt()) },
                )
            }
            TuningSlider(
                title = Res.get(R.string.skill_proactive_idle),
                value = Res.get(R.string.skill_proactive_idle_value, draftIdle.toInt()),
            ) {
                AppSlider(
                    value = draftIdle,
                    onValueChange = { draftIdle = it },
                    valueRange = 5f..120f,
                    intervals = 23,
                    onValueChangeFinished = { onIdle(draftIdle.toInt()) },
                )
            }
            Text(
                text = Res.get(R.string.skill_tuning_hint),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = colors.placeholder,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SettingsTextAction(
                    text = Res.get(R.string.action_done),
                    onClick = onDismiss,
                    primary = true,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun TuningSlider(
    title: String,
    value: String,
    slider: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.processText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        slider()
    }
}
