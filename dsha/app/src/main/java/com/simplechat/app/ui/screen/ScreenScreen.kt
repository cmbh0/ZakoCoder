package com.simplechat.app.ui.screen

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.agent.RootShell
import com.simplechat.app.agent.ScreenAutomation
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.clip
import com.simplechat.app.ui.settings.SettingsBanner
import com.simplechat.app.ui.settings.SettingsButton
import com.simplechat.app.ui.settings.SettingsButtonStyle
import com.simplechat.app.ui.settings.SettingsCheck
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsHint
import com.simplechat.app.ui.settings.SettingsRow
import com.simplechat.app.ui.settings.SettingsSectionLabel
import com.simplechat.app.ui.settings.SettingsTopBar

/**
 * 操作屏幕。
 *
 * 这一页的核心是**选一条通道**，不是"开无障碍"。
 *
 * 上一版把无障碍当成唯一入口，root 缩在下面一张卡里当"检测一下"——
 * 已经 root 的机器每次进来都先被要求去系统设置里勾无障碍，勾完重启还得再勾
 * 一次；而他们要的东西（`input` / `uiautomator` / `screencap`）用一次 su
 * 就能跑。所以这里把两条通道摆成平权的三选一：自动 / 无障碍 / Root，
 * 选谁就按谁执行，不偷偷fallback。
 *
 * 页面只做三件事：**选通道、拿授权、自检**。
 */
@Composable
fun ScreenScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ScreenAutomation.state.collectAsState()
    var probe by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var rootStatus by remember { mutableStateOf(RootShell.status) }
    var rootDetail by remember { mutableStateOf(RootShell.detail) }
    var rootChecking by remember { mutableStateOf(false) }
    var preference by remember { mutableStateOf(ScreenAutomation.preference) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            // 状态栏的留白由 SettingsTopBar 自己加
            .navigationBarsPadding(),
    ) {
        // 与设置里其它页同一个顶栏；右上角是"现在走的是哪条通道"
        SettingsTopBar(
            title = Res.get(R.string.screen_title),
            onBack = onBack,
            trailing = when (state.channel) {
                ScreenAutomation.CHANNEL_ACCESSIBILITY ->
                    Res.get(R.string.settings_screen_value_a11y)
                ScreenAutomation.CHANNEL_ROOT ->
                    Res.get(R.string.settings_screen_value_root)
                else -> Res.get(R.string.screen_disconnected)
            },
            trailingAccent = state.channel != ScreenAutomation.CHANNEL_NONE,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsHint(
                text = Res.get(R.string.screen_intro),
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )

            /*
             * 三选一。选中项右侧打勾，行内给一句"这条路要付什么代价"——
             * 用户选的不是名词，是"要不要去系统设置里手勾一次"。
             */
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 先裁再上色：圆角与别的卡片同一个值，点按的水波纹也不会溢出四个角
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card),
            ) {
                ChannelRow(
                    title = Res.get(R.string.screen_channel_auto),
                    hint = Res.get(R.string.screen_channel_auto_hint),
                    selected = preference == ScreenAutomation.PREF_AUTO,
                    onClick = {
                        preference = ScreenAutomation.PREF_AUTO
                        ScreenAutomation.setPreference(preference)
                        persist(scope, context, preference)
                    },
                )
                ChannelRow(
                    title = Res.get(R.string.screen_channel_a11y),
                    hint = Res.get(R.string.screen_channel_a11y_hint),
                    selected = preference == ScreenAutomation.PREF_ACCESSIBILITY,
                    onClick = {
                        preference = ScreenAutomation.PREF_ACCESSIBILITY
                        ScreenAutomation.setPreference(preference)
                        persist(scope, context, preference)
                    },
                )
                ChannelRow(
                    title = Res.get(R.string.screen_channel_root),
                    hint = Res.get(R.string.screen_channel_root_hint),
                    selected = preference == ScreenAutomation.PREF_ROOT,
                    onClick = {
                        preference = ScreenAutomation.PREF_ROOT
                        ScreenAutomation.setPreference(preference)
                        persist(scope, context, preference)
                    },
                )
            }

            // ── 识别管线 ────────────────────────────────
            SettingsSectionLabel(Res.get(R.string.screen_pipeline_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card),
            ) {
                ChannelRow(
                    title = Res.get(R.string.screen_pipeline_layout),
                    hint = Res.get(R.string.screen_pipeline_layout_hint),
                    selected = ScreenAutomation.pipeline == ScreenAutomation.PIPELINE_LAYOUT,
                    onClick = { persistPipeline(scope, context, ScreenAutomation.PIPELINE_LAYOUT) },
                )
                ChannelRow(
                    title = Res.get(R.string.screen_pipeline_vision),
                    hint = Res.get(R.string.screen_pipeline_vision_hint),
                    selected = ScreenAutomation.pipeline == ScreenAutomation.PIPELINE_VISION,
                    onClick = { persistPipeline(scope, context, ScreenAutomation.PIPELINE_VISION) },
                )
            }
            Text(
                text = Res.get(R.string.screen_pipeline_hint),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )

            /*
             * 选中通道的授权区。
             *
             * 只显示"你选的那条"，不再把另一条的能力摆在你面前 —— 那会让人
             * 以为还得两条都开。
             */
            Spacer(Modifier.height(20.dp))

            if (preference == ScreenAutomation.PREF_ROOT ||
                (preference == ScreenAutomation.PREF_AUTO && !ScreenAutomation.isConnected())
            ) {
                SectionCard(
                    title = Res.get(R.string.screen_root_title),
                    status = when {
                        rootChecking -> Res.get(R.string.screen_root_checking)
                        rootStatus == RootShell.Status.AVAILABLE -> Res.get(R.string.screen_root_available)
                        rootStatus == RootShell.Status.UNAVAILABLE -> Res.get(R.string.screen_root_unavailable)
                        else -> Res.get(R.string.screen_root_unknown)
                    },
                    statusOk = rootStatus == RootShell.Status.AVAILABLE,
                    body = Res.get(R.string.screen_root_body),
                    detail = rootDetail.take(160).takeIf {
                        rootStatus == RootShell.Status.UNAVAILABLE && it.isNotBlank()
                    },
                    action = Res.get(R.string.screen_root_grant),
                    actionFilled = rootStatus != RootShell.Status.AVAILABLE,
                    enabled = !rootChecking,
                    onAction = {
                        if (!rootChecking) {
                            rootChecking = true
                            scope.launch {
                                // 第一次点会拉起 Magisk / KernelSU 的授权弹窗
                                withContext(Dispatchers.IO) { RootShell.probe() }
                                rootStatus = RootShell.status
                                rootDetail = RootShell.detail
                                rootChecking = false
                                ScreenAutomation.refresh()
                            }
                        }
                    },
                )
            }

            if (preference == ScreenAutomation.PREF_ACCESSIBILITY ||
                (preference == ScreenAutomation.PREF_AUTO && ScreenAutomation.isConnected())
            ) {
                SectionCard(
                    title = Res.get(R.string.screen_service_name),
                    status = if (ScreenAutomation.isConnected()) {
                        Res.get(R.string.screen_root_available)
                    } else {
                        Res.get(R.string.settings_screen_value_off)
                    },
                    statusOk = ScreenAutomation.isConnected(),
                    body = Res.get(R.string.screen_service_body),
                    detail = null,
                    action = if (ScreenAutomation.isConnected()) {
                        Res.get(R.string.screen_open_settings)
                    } else {
                        Res.get(R.string.screen_enable)
                    },
                    actionFilled = !ScreenAutomation.isConnected(),
                    enabled = true,
                    onAction = { ScreenAutomation.openAccessibilitySettings(context) },
                )
            }

            // ── 自检 ────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            /*
             * 胶囊按钮，卡片色底。此前是 Material 的 Button 配 12dp 圆角、浅灰底 ——
             * 全应用只有这一页的按钮是圆角矩形，而且浅灰底压在灰色页面上几乎看不见。
             */
            SettingsButton(
                text = if (probing) Res.get(R.string.screen_probing) else Res.get(R.string.screen_probe),
                onClick = {
                    if (!probing) {
                        probing = true
                        scope.launch {
                            val text = withContext(Dispatchers.Default) {
                                runCatching { ScreenAutomation.run("observe", JsonObject(emptyMap())) }
                                    .map { raw -> Res.get(R.string.screen_probe_ok, raw.length) }
                                    .getOrElse { it.message ?: it.javaClass.simpleName }
                            }
                            probe = text
                            probing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                style = SettingsButtonStyle.Surface,
                loading = probing,
            )

            probe?.let {
                Spacer(Modifier.height(10.dp))
                // 自检结果可能是一段报错，允许长按复制
                SettingsBanner(text = it, selectable = true)
            }

            // ── 最近一次 ────────────────────────────────
            if (state.lastAt > 0) {
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                        .background(colors.card)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = Res.get(
                            R.string.screen_last_action,
                            ScreenAutomation.label(state.lastAction),
                            timeFormat.format(Date(state.lastAt)),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.failed) colors.danger else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = state.lastResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 8,
                    )
                }
            }

            // ── 边界：缩成一段，不再做四条项目符号 ─────────
            Spacer(Modifier.height(20.dp))
            SettingsHint(
                text = Res.get(R.string.screen_rules_compact),
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** 识别管线同理：先改内存里的值让界面立刻响应，再落库。 */
private fun persistPipeline(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    id: String,
) {
    ScreenAutomation.setPipeline(id)
    val app = context.applicationContext as? com.simplechat.app.App ?: return
    scope.launch { app.container.settingsStore.setScreenPipeline(id) }
}

/** 把选择写回 DataStore，下次冷启动照旧。 */
private fun persist(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    id: String,
) {
    val app = context.applicationContext as? com.simplechat.app.App ?: return
    scope.launch { app.container.settingsStore.setScreenChannel(id) }
}

/**
 * 三选一里的一行：标题 + 一句代价说明，选中的那行右侧一颗勾。
 *
 * 行和勾都是设置里通用的那一套（见 `SettingsKit.kt`）：勾是中性色 ——
 * 此前这里是主题色的勾，和设置里别处的单选不是一个口径。
 */
@Composable
private fun ChannelRow(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = hint,
        onClick = onClick,
        trailing = { SettingsCheck(selected = selected) },
    )
}

@Composable
private fun SectionCard(
    title: String,
    status: String,
    statusOk: Boolean,
    body: String,
    detail: String?,
    action: String,
    actionFilled: Boolean,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsDimens.CardCorner))
            .background(colors.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (statusOk) MaterialTheme.colorScheme.primary else colors.placeholder, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = if (statusOk) MaterialTheme.colorScheme.primary else colors.placeholder,
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = colors.placeholder,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }
        SettingsButton(
            text = action,
            onClick = onAction,
            style = if (actionFilled) SettingsButtonStyle.Primary else SettingsButtonStyle.Tonal,
            enabled = enabled,
        )
    }
}
