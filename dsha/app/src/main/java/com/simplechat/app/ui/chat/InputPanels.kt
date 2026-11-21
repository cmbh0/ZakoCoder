package com.simplechat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.simplechat.app.R
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SegmentedSwitch
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlin.math.roundToInt

/** 输入栏下方的面板容器。输入栏会上移，和选图面板一致。 */
@Composable
fun InputPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalChatColors.current
    val shape = ContainerShape

    // 面板自己的投影也做淡入，和输入栏投影的淡出对得上
    val elevation by animateDpAsState(
        targetValue = if (visible) AppShadow.PanelElevation else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "inputPanelShadow",
    )

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = AppShadow.PanelAmbient,
                    spotColor = AppShadow.PanelSpot,
                )
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.outline, shape)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content,
        )
    }
}

/** 面板内的小节标题。 */
@Composable
fun PanelSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun PanelDivider(modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.divider),
    )
}

// ══════════════════════════════════════════════════════════
//  面板一：模型 + 思考强度
// ══════════════════════════════════════════════════════════

/**
 * 模型 + 思考浮层。
 *
 * 版式照 Operit 那张参考图，四件事从上往下排：
 * **开不开（开关）→ 开多大（五点滑块）→ 用哪个模型（列表）→ 去哪管理（管理配置）**。
 *
 * 与上一版的区别不只是好看：上一版是"输入栏底部的展开面板"，模型列表和滑块
 * 挤在输入框上方，展开就把对话顶掉半屏。现在是**从顶栏扣下来的浮层**，
 * 打完字想换脑子，手不用离开上半屏。
 *
 * 刻意没有"选择模型"这种大标题 —— 浮层里只有这一个主题，再声明一遍是浪费一行。
 */
@Composable
fun ModelPanelContent(
    settings: ConversationSettings,
    onSettingsChange: (ConversationSettings) -> Unit,
    models: List<ModelInfo>,
    onRefreshModels: () -> Unit,
    refreshing: Boolean = false,
    hint: String? = null,
    agentEnabled: Boolean = false,
    onToggleAgent: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    /*
     * 不再按模型目录里的 `supportsThinking` 拦开关。
     *
     * 那个标记是本地猜的：新模型、中转站改过名的模型、自建端点全都会猜错，
     * 猜错的代价是用户**看不到**开关，而不是"开了没用"。协议本来就允许
     * 任何模型带 reasoning 参数 —— 服务端不支持会忽略或报错，那由请求失败
     * 那一条路径给出真实原因，比这里静默阉割清楚得多。
     */
    val supportsThinking = true
    val thinkingOn = settings.thinkingEnabled

    /* 关掉思考后，滑块停在"上次那一档"而不是弹回最左 —— 重新打开时手感是连续的。 */
    val effort = settings.reasoningLevel.takeIf { it.enabled } ?: ReasoningLevel.default

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 思考模式（开关）──────────────────────────────
        PanelToggleRow(
            icon = AppIcons.Brain,
            label = stringResource(R.string.model_panel_thinking_mode),
            checked = thinkingOn,
            enabled = supportsThinking,
            onCheckedChange = { on ->
                onSettingsChange(
                    settings.copy(
                        reasoningLevel = if (on) ReasoningLevel.default else ReasoningLevel.OFF,
                    ),
                )
            },
        )

        // ── 思考程度（五点滑块）──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Sliders,
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.model_panel_effort),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (supportsThinking) {
                    effort.label
                } else {
                    stringResource(R.string.input_model_unsupported)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (supportsThinking) colors.processText else colors.placeholder,
            )
        }

        EffortSlider(
            value = ReasoningLevel.sliderPositionOf(effort).toFloat(),
            onValueChange = { raw ->
                onSettingsChange(
                    settings.copy(reasoningLevel = ReasoningLevel.fromSliderPosition(raw.roundToInt())),
                )
            },
            enabled = thinkingOn,
            labels = ReasoningLevel.sliderOrder.map { it.label },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )

        // ── 两个开关 ─────────────────────────────────────
        PanelToggleRow(
            icon = AppIcons.Sparkles,
            label = stringResource(R.string.settings_agent),
            checked = agentEnabled,
            onCheckedChange = { onToggleAgent() },
        )

        Spacer(Modifier.height(10.dp))
        PanelDivider()
        Spacer(Modifier.height(10.dp))

        // ── 当前模型 ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.model_panel_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = settings.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.processText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── 配置行：左边默认配置，右边数量 + 刷新 ─────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SoftShape)
                .clickable(onClick = onRefreshModels)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.model_panel_default_config),
                style = MaterialTheme.typography.labelMedium,
                color = colors.processText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (refreshing) {
                    stringResource(R.string.models_refreshing)
                } else {
                    stringResource(R.string.model_panel_count, models.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = AppIcons.Refresh,
                contentDescription = stringResource(R.string.models_refresh),
                tint = if (refreshing) colors.placeholder else colors.processText,
                modifier = Modifier.size(14.dp),
            )
        }

        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.warning,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }

        // ── 模型列表 ─────────────────────────────────────
        if (models.isEmpty()) {
            Text(
                text = stringResource(R.string.models_empty),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 232.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                models.forEach { model ->
                    ModelRow(
                        model = model,
                        selected = model.id == settings.model.id,
                        onClick = { onSettingsChange(settings.copy(model = model)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── 管理配置（去设置页）──────────────────────────
        Text(
            text = stringResource(R.string.model_panel_manage),
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(SoftShape)
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 10.dp),
        )
    }
}

/**
 * 浮层里的开关行：图标 + 名称 + 右侧开关。
 *
 * 行高 40dp、左右各 10dp —— 和下面"当前模型""默认配置"两行的左缘对齐在
 * 同一个 x 上。这几行差 1~2dp 就看得出"没排好"。
 */
@Composable
private fun PanelToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.processText else colors.placeholder,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                colors.placeholder
            },
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 思考程度滑块。
 *
 * 参考图那条：**一条细轨 + 五个圆点**，选中的点胀成一颗实心圆，标签在轨道下方
 * 与点严格对齐。不用 `AppSlider` 是因为它那颗拇指是"描边圆 + 抬升"，适合
 * 调温度、字体这种连续值；思考档位是**离散的五格**，点阵比拇指更说得清
 * "只能落在这五处"。
 */
@Composable
private fun EffortSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    labels: List<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val lastIndex = (labels.size - 1).coerceAtLeast(1)
    val accent = MaterialTheme.colorScheme.primary
    val idle = colors.outline
    val edge = with(density) { 12.dp.toPx() }
    val knob = with(density) { 8.dp.toPx() }
    val dot = with(density) { 3.dp.toPx() }

    val changeCallback by rememberUpdatedState(onValueChange)

    fun emitAt(x: Float) {
        if (width <= 0f) return
        val usable = (width - edge * 2f).coerceAtLeast(1f)
        val f = ((x - edge) / usable).coerceIn(0f, 1f)
        changeCallback((f * lastIndex).roundToInt().toFloat())
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(enabled, width) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        emitAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            emitAt(change.position.x)
                            change.consume()
                        }
                        dragging = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            ) {
                val cy = size.height / 2f
                val startX = edge
                val endX = size.width - edge
                val step = (endX - startX) / lastIndex

                drawLine(
                    color = if (enabled) idle else idle.copy(alpha = 0.5f),
                    start = Offset(startX, cy),
                    end = Offset(endX, cy),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                val filledEnd = startX + step * value
                if (value > 0f && enabled) {
                    drawLine(
                        color = accent,
                        start = Offset(startX, cy),
                        end = Offset(filledEnd, cy),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                repeat(labels.size) { index ->
                    val cx = startX + step * index
                    val selected = index == value.roundToInt()
                    if (selected) {
                        // 选中：实心大圆 + 中心白点
                        drawCircle(
                            color = if (enabled) accent else idle,
                            radius = if (dragging) knob + 1.dp.toPx() else knob,
                            center = Offset(cx, cy),
                        )
                        drawCircle(
                            color = colors.card,
                            radius = dot,
                            center = Offset(cx, cy),
                        )
                    } else {
                        drawCircle(
                            color = if (enabled) idle else idle.copy(alpha = 0.5f),
                            radius = dot,
                            center = Offset(cx, cy),
                        )
                    }
                }
            }
        }

        // 标签与刻度严格对齐（复用 AppSlider 那套按实际横坐标定位的做法）
        var totalWidth by remember { mutableStateOf(0) }
        val labelWidths = remember { mutableStateMapOf<Int, Int>() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .onSizeChanged { totalWidth = it.width },
        ) {
            labels.forEachIndexed { index, text ->
                val labelWidth = labelWidths[index] ?: 0
                val tickX = edge + (totalWidth - edge * 2f) * (index.toFloat() / lastIndex)
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !enabled -> colors.placeholder.copy(alpha = 0.6f)
                        index == value.roundToInt() -> accent
                        else -> colors.placeholder
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (tickX - labelWidth / 2f).roundToInt(),
                                0,
                            )
                        }
                        .onSizeChanged { labelWidths[index] = it.width },
                )
            }
        }
    }
}

/**
 * 一行模型。
 *
 * 参考图的口径：**左边名字、右边它的真实 id**，选中整行浅色高亮。
 * 上一版右边写上下文窗口 —— 那个数字对"选哪个脑子"没有帮助，
 * 而 id 至少能对上你自己在服务端看到的名字。
 */
@Composable
private fun ModelRow(
    model: ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .then(
                if (selected) Modifier.background(colors.capsuleSelected) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = model.id,
            style = MaterialTheme.typography.labelSmall,
            color = colors.placeholder,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  面板二：对话统计 + 上下文压缩
// ══════════════════════════════════════════════════════════

/**
 * 统计面板。
 *
 * 所有 token 数都是**本地估算**（`TokenEstimate`），面板里会写明 ——
 * 让用户以为这是精确值，比给个粗略值更糟。
 */
@Composable
fun ContextPanelContent(
    stats: ContextStats,
    compaction: CompactionEntity?,
    compacting: Boolean,
    compactPreview: String,
    onCompact: () -> Unit,
    onCancelCompact: () -> Unit,
    onClearCompaction: () -> Unit,
    onUpdateSummary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 占用 ──────────────────────────────────────────
        PanelSectionTitle(
            text = stringResource(R.string.stats_context_usage),
            trailing = {
                Text(
                    text = "${stats.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        stats.ratio >= 0.9f -> colors.danger
                        stats.ratio >= 0.7f -> colors.warning
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            },
        )

        Spacer(Modifier.size(8.dp))

        UsageBar(ratio = stats.ratio)

        Spacer(Modifier.size(8.dp))

        Text(
            text = stringResource(
                R.string.stats_tokens_estimate,
                TokenEstimate.format(stats.usedTokens),
                TokenEstimate.format(stats.windowTokens),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = colors.placeholder,
        )

        if (stats.compactedTokenSaving > 0) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(
                    R.string.stats_compacted_saving,
                    TokenEstimate.format(stats.compactedTokenSaving),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── 统计 ──────────────────────────────────────────
        PanelSectionTitle(text = stringResource(R.string.stats_title))

        Spacer(Modifier.size(10.dp))

        StatRow(stringResource(R.string.stats_total_tokens), "${TokenEstimate.format(stats.totalTokens)} tokens")
        StatRow(stringResource(R.string.stats_message_count), stringResource(R.string.stats_messages, stats.messageCount))
        StatRow(
            label = stringResource(R.string.stats_avg_ttft),
            value = stats.avgTtftMs?.let { "$it ms" } ?: "—",
        )
        StatRow(
            label = stringResource(R.string.stats_avg_tps),
            value = stats.avgTps?.let { "${it.roundToInt()} tok/s" } ?: "—",
        )
        StatRow(
            label = stringResource(R.string.stats_total_thinking),
            value = stats.totalThinkingSeconds?.let { stringResource(R.string.stats_seconds, it) } ?: "—",
        )

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── 压缩 ──────────────────────────────────────────
        CompactionSection(
            compaction = compaction,
            compacting = compacting,
            compactPreview = compactPreview,
            onCompact = onCompact,
            onCancelCompact = onCancelCompact,
            onClearCompaction = onClearCompaction,
            onUpdateSummary = onUpdateSummary,
        )
    }
}

/** 线性占用条。只读，不可拖。 */
@Composable
private fun UsageBar(ratio: Float) {
    val colors = LocalChatColors.current
    val accent = when {
        ratio >= 0.9f -> colors.danger
        ratio >= 0.7f -> colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(PillShape)
            .background(colors.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(PillShape)
                .background(accent),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.placeholder,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CompactionSection(
    compaction: CompactionEntity?,
    compacting: Boolean,
    compactPreview: String,
    onCompact: () -> Unit,
    onCancelCompact: () -> Unit,
    onClearCompaction: () -> Unit,
    onUpdateSummary: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var editing by remember { mutableStateOf(false) }
    var draft by remember(compaction?.id) { mutableStateOf(compaction?.summary.orEmpty()) }

    PanelSectionTitle(text = stringResource(R.string.compact_title))

    Spacer(Modifier.size(6.dp))

    Text(
        text = stringResource(R.string.compact_note),
        style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder,
    )

    Spacer(Modifier.size(12.dp))

    when {
        // ── 正在压缩 ─────────────────────────────────────
        compacting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.compact_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                CapsuleButton(text = stringResource(R.string.action_cancel), onClick = onCancelCompact)
            }
            if (compactPreview.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                ReadOnlyBox(text = compactPreview, maxHeight = 160.dp)
            }
        }

        // ── 已有摘要 ─────────────────────────────────────
        compaction != null -> {
            Text(
                text = stringResource(R.string.compact_summarized),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(8.dp))

            if (editing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SoftShape)
                        .background(colors.fieldBackground)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    CapsuleButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = {
                            draft = compaction.summary
                            editing = false
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    CapsuleButton(
                        text = stringResource(R.string.action_save),
                        selected = true,
                        onClick = {
                            onUpdateSummary(draft)
                            editing = false
                        },
                    )
                }
            } else {
                ReadOnlyBox(text = compaction.summary, maxHeight = 200.dp)
                Spacer(Modifier.size(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapsuleButton(
                        text = stringResource(R.string.action_edit),
                        leadingIcon = AppIcons.Edit,
                        onClick = {
                            draft = compaction.summary
                            editing = true
                        },
                    )
                    CapsuleButton(
                        text = stringResource(R.string.compact_redo),
                        leadingIcon = AppIcons.Refresh,
                        onClick = onCompact,
                    )
                    CapsuleButton(
                        text = stringResource(R.string.compact_discard),
                        leadingIcon = AppIcons.Close,
                        onClick = onClearCompaction,
                    )
                }
            }
        }

        // ── 尚未压缩 ─────────────────────────────────────
        else -> {
            CapsuleButton(
                text = stringResource(R.string.compact_start),
                leadingIcon = AppIcons.Collapse,
                /*
                 * ⚠️ 这里**不要** `selected = true`。
                 *
                 * `selected` 的语义是"这个选项当前是开着的"，而这个分支的前提恰恰是
                 * **还没压缩** —— 顶着一个选中态等于告诉用户"已经压过了"。
                 * 之前它的底色是淡蓝、不扎眼；换成别的主题色之后一眼就是错的。
                 *
                 * 这一屏只有它一个按钮、下面还跟着说明，本来就是视觉重心，
                 * 不需要再借选中态来强调。
                 */
                onClick = onCompact,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.compact_desc),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }
    }
}

@Composable
private fun ReadOnlyBox(text: String, maxHeight: androidx.compose.ui.unit.Dp) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.processText,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  面板四：对话设置
// ══════════════════════════════════════════════════════════

/**
 * 对话设置面板：对话模式 / 系统提示词 / 采样参数。
 *
 * 参数区严格按服务端真实行为置灰（设计文档 §5.4）：
 * `temperature` 思考模式下**静默失效**，`top_p` 反而只在思考模式下生效。
 * 这两条不说明白，用户会以为参数生效了其实没有。
 */
/**
 * 对话名称输入框。
 *
 * ### 为什么要自己存一份草稿
 *
 * 不能直接把库里的标题当输入值：那个值是**异步观察**回来的
 * （抽屉改名、AI 起名都会让 Flow 重发），而流式期间会话行还会被 checkpoint
 * 反复更新 —— 直接绑库，打字打到一半就可能被旧值盖回去。
 *
 * 所以草稿留在本地、落库走防抖；顶栏与抽屉由库那边统一同步。
 *
 * [conversationId] 只当 `remember` 的 key：换会话要换一份草稿。「新对话」时是 `null`，
 * 那一份草稿跟它的待用标题一样，都挂在还没建的那个会话名下。
 */
@Composable
private fun NameField(
    conversationId: String?,
    title: String,
    onTitleChange: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var draft by remember(conversationId) { mutableStateOf(title) }
    var focused by remember(conversationId) { mutableStateOf(false) }

    /*
     * 外部改名（抽屉 / AI 起名）时跟上，但**正在输入时不打断**。
     *
     * 刻意只把 `title` 当 key、不把 `focused` 一起放进去：失焦那一瞬间库里
     * 往往还是旧值（防抖刚落库），那时重跑会把用户刚打的字盖回去。
     */
    LaunchedEffect(title) {
        if (!focused) draft = title
    }

    // 停手 400ms 落库 —— 与「会话补充提示词」同一档。理由也一样：
    // 逐字写库会让抽屉里的排序和标题一路乱跳
    LaunchedEffect(draft) {
        if (draft == title) return@LaunchedEffect
        delay(400)
        onTitleChange(draft)
    }

    PanelSectionTitle(text = stringResource(R.string.input_conversation_name))
    Spacer(Modifier.size(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.input_conversation_name_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.placeholder,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun ConversationSettingsPanelContent(
    settings: ConversationSettings,
    onSettingsChange: (ConversationSettings) -> Unit,
    /** 只用于底部那行"最终提示词"预览 —— 拼接结果必须和真正发出去的一致。 */
    presets: List<Preset>,
    /** 当前会话 id；未落库的新对话为 null。 */
    conversationId: String?,
    /** 当前标题。**从库来**（见 `ChatViewModel.observeTitle`），不是本地副本。 */
    title: String,
    /** 改名回调；未落库的新对话传 null（还没有会话可改）。 */
    onTitleChange: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** 有会话可导出时为非空；未落库的新对话传 null。 */
    onExportMarkdown: (() -> Unit)? = null,
    onExportJson: (() -> Unit)? = null,
    exportHint: String? = null,
) {
    val colors = LocalChatColors.current
    var promptPreviewExpanded by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {

        /*
         * ── ⓪ 对话名称 ────────────────────────────────────
         *
         * 放在**最上面**：它是这个面板里唯一"改完立刻在别处看得见"的项
         * （顶栏 + 抽屉），而下面的系统提示词、预设都只影响下一轮请求。
         *
         * 到这里为止，标题一共有**四种**确定方式：抽屉里重命名、AI 用 flash
         * 档起名、这个框、以及**「新对话」的待用标题**（发出第一条消息之前
         * 就起好的名，见 §35）。前三者都只写库、界面统一由 `observeTitle` 回流；
         * 待用标题是例外 —— 那时库里还没有行，内存里这份就是唯一的真相。
         */
        if (onTitleChange != null) {
            NameField(
                conversationId = conversationId,
                title = title,
                onTitleChange = onTitleChange,
            )
            Spacer(Modifier.size(12.dp))
        }

        /*
         * 对话模式切换**暂时撤下**：扮演模式是远期功能，现在把两个选项
         * 并列摆出来，等于让用户在两件当前不等价的事情之间做选择。
         * 默认创作模式，`ConversationMode` 与 `SegmentedSwitch` 都保留给以后。
         */

        /*
         * ── ① 补充提示词 ──────────────────────────────────
         *
         * 预设的勾选**不在这个面板里了** —— 它是独立的一页（⋮ → 预设），
         * 每条有名字、摘要、开关和生效位次。这里只剩"这个对话额外要说的
         * 一句话"，以及下面那行最终提示词预览。
         *
         * 预览仍然把预设拼进来（`composedSystemPrompt(presets)`）：
         * 它存在的意义就是"实际发出去的是什么"，少拼一段就成了假的。
         */
        PanelSectionTitle(text = stringResource(R.string.input_extra_prompt))
        Spacer(Modifier.size(8.dp))

        val fieldShape = SoftShape
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(fieldShape)
                .background(colors.fieldBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = settings.systemPrompt,
                onValueChange = { onSettingsChange(settings.copy(systemPrompt = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 140.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (settings.systemPrompt.isEmpty()) {
                            Text(
                                text = stringResource(R.string.input_extra_prompt_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.placeholder,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        // 最终提示词预览：创作工具最怕黑盒，必须能一眼看到实际发出去的内容
        Spacer(Modifier.size(10.dp))
        val composed = settings.composedSystemPrompt(presets)
        Row(
            modifier = Modifier
                .clip(PillShape)
                .clickable { promptPreviewExpanded = !promptPreviewExpanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (composed.isBlank()) {
                    stringResource(R.string.input_prompt_empty)
                } else {
                    stringResource(R.string.input_prompt_preview)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (promptPreviewExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }

        AnimatedVisibility(visible = promptPreviewExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SoftShape)
                    .background(colors.fieldBackground)
                    .padding(12.dp),
            ) {
                Text(
                    text = composed.ifBlank { stringResource(R.string.input_prompt_blank) },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.processText,
                )
            }
        }

        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(16.dp))

        // ── ③ 采样参数 ────────────────────────────────────
        PanelSectionTitle(text = stringResource(R.string.input_sampling))

        Spacer(Modifier.size(8.dp))

        ParamSlider(
            label = stringResource(R.string.input_param_temperature),
            valueText = formatFloat(settings.temperature),
            value = settings.temperature,
            range = 0f..2f,
            enabled = !settings.thinkingEnabled,
            disabledHint = stringResource(R.string.input_param_temp_disabled),
            onValueChange = { onSettingsChange(settings.copy(temperature = it)) },
        )

        ParamSlider(
            label = "top_p",
            valueText = formatFloat(settings.topP),
            value = settings.topP,
            range = 0.95f..1f,
            enabled = settings.thinkingEnabled,
            disabledHint = stringResource(R.string.input_param_top_p_disabled),
            onValueChange = { onSettingsChange(settings.copy(topP = it)) },
        )

        Spacer(Modifier.size(4.dp))
        // 快速模式：一个布尔参数，跟滑块同一组，但不需要拖 —— 用开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.input_param_fast),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.input_param_fast_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }
            AppSwitch(
                checked = settings.fast,
                onCheckedChange = { onSettingsChange(settings.copy(fast = it)) },
            )
        }

        // ── 导出当前对话 ──────────────────────────────────
        Spacer(Modifier.size(16.dp))
        PanelDivider()
        Spacer(Modifier.size(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.input_export_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (onExportMarkdown != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        colors.placeholder
                    },
                )
                Text(
                    text = exportHint ?: stringResource(R.string.input_export_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }
            if (onExportMarkdown != null) {
                // 两种导出服务两种用途，用菜单分开（见仓库里 exportConversationMarkdown 的注释）
                Box {
                    CapsuleButton(
                        text = stringResource(R.string.input_export),
                        leadingIcon = AppIcons.Download,
                        onClick = { exportMenuOpen = true },
                    )
                    AppMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false },
                    ) {
                        AppMenuItem(stringResource(R.string.input_export_markdown), AppIcons.Feather) {
                            exportMenuOpen = false
                            onExportMarkdown()
                        }
                        AppMenuItem(stringResource(R.string.input_export_json), AppIcons.Database) {
                            exportMenuOpen = false
                            onExportJson?.invoke()
                        }
                    }
                }
            }
        }
    }
}

/** 带条件说明的参数滑块。禁用时会明确写出原因，而不是只置灰。 */
@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    disabledHint: String,
    onValueChange: (Float) -> Unit,
) {
    val colors = LocalChatColors.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.placeholder,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.placeholder,
            )
        }

        AppSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
        )

        if (!enabled) {
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

private fun formatFloat(value: Float): String =
    ((value * 100f).roundToInt() / 100f).toString()
