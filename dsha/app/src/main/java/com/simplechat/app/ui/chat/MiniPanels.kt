package com.simplechat.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.simplechat.app.agent.CloudEnv
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.agent.ApprovalMode
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ModelMark
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlin.math.roundToInt

/**
 * 输入栏三颗控件弹出来的**迷你浮层**。
 *
 * 和设置页、对话设置那种"面板"不是一回事：那两块会占掉半屏，因为它们要改的东西
 * 多；而这里三颗控件问的都是**一句话就能问完**的事 —— 现在用的是哪个模型、
 * AI 能走多远、上下文还剩多少。给它们半屏是浪费，也给错了分量。
 *
 * 所以：宽度 224~268dp，贴在触发控件上方，无遮罩、无标题栏、无确认按钮，
 * 点外面就走。行高 30~34dp，字号 13/11sp。
 *
 * 配色上也刻意**不用品牌蓝**：浮层里出现蓝底蓝字，会让人以为"这一项被选中了"
 * 或者"这里有个链接"，而实际上它只是在说明状态。选中态用**浅灰底 + 对勾**，
 * 危险项用 danger，其余一律 onSurface / placeholder 两级灰。
 */

// ══════════════════════════════════════════════════════════
//  模型
// ══════════════════════════════════════════════════════════

/** 模型浮层宽度：够放「名字 + id」两列，又不至于在小屏上顶到边。 */
val ModelBubbleWidth = 248.dp

@Composable
fun ModelMiniPanel(
    settings: ConversationSettings,
    onSettingsChange: (ConversationSettings) -> Unit,
    models: List<ModelInfo>,
    onRefreshModels: () -> Unit,
    refreshing: Boolean = false,
    hint: String? = null,
    /** 右上角加号：打开"选择模型"对话框（挑选气泡里出现哪些）。 */
    onPickModels: () -> Unit = {},
    /**
     * 所有配置。**非空时优先按配置分组**（用户要的就是这个）。
     *
     * 空列表只在一种情况下出现：调用方还没来得及把配置传进来 —— 这时回落到
     * 旧的"按模型家族分组"，界面不会空着。
     */
    profiles: List<com.simplechat.app.data.ModelProfile> = emptyList(),
    /** 当前生效的配置 id。 */
    activeProfileId: String = "",
    /** 跨配置选模型：先切配置、再切模型。 */
    onPickProfileModel: (String, String) -> Unit = { _, _ -> },
    /** 某套配置里可显示的模型（由调用方按同一套勾选规则过滤）。 */
    modelsOfProfile: (com.simplechat.app.data.ModelProfile) -> List<ModelInfo> = { emptyList() },
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    // 与 InputPanels 一致：思考开关不再受模型目录白名单限制
    val supportsThinking = true
    val thinkingOn = settings.thinkingEnabled
    val effort = settings.reasoningLevel.takeIf { it.enabled } ?: ReasoningLevel.default

    /*
     * 按家族分组。
     *
     * 一个中转站能同时代理 GPT / Claude / GLM / Qwen 几十个模型，平铺下来
     * 是一列看不出关系的 id。分组的价值不是"更整齐"，而是让人**先缩小范围**：
     * 想看 Claude 就只展开那一摞，其余四摞各占一行。
     *
     * 排序沿用规则表的顺序（常见家族在前），「其他」压到最后 —— 名字里
     * 认不出门派的通常是自己部署或小众模型，翻它们的概率最低。
     */
    /*
     * 分组：**有配置就按配置，没配置才按家族**。
     *
     * 上一版只有家族这一条路，于是用户看到的是"DeepSeek / GPT / Claude / 其他"
     * —— 而这些名字是我们从模型 id 里猜出来的，跟他配的端点毫无关系。他真正
     * 想说的是：这几摞分别是"官方"、"那家中转"、"本地 Ollama"，点哪个就用哪把 Key。
     */
    val groups = remember(models, profiles, activeProfileId) {
        if (profiles.isNotEmpty()) {
            profiles.map { profile ->
                ModelRowGroup(
                    key = profile.id,
                    title = profile.name.ifBlank {
                        runCatching { com.simplechat.app.net.ProviderKind.valueOf(profile.providerId) }
                            .getOrNull()?.displayName
                            ?: profile.providerId
                    },
                    detail = profileDetail(profile),
                    profileId = profile.id,
                    models = modelsOfProfile(profile),
                )
            }
        } else {
            models
                .groupBy { com.simplechat.app.ui.common.ModelMarks.familyOf(it) }
                .toList()
                .sortedBy { (_, list) ->
                    list.minOf { com.simplechat.app.ui.common.ModelMarks.familyRank(it) }
                }
                .map { (family, list) -> ModelRowGroup(family, family, "", null, list) }
        }
    }

    /*
     * 默认只展开**当前模型所在那一组**：其余组收起，整张纸片一眼能看完。
     * 换模型之后不自动跟过去 —— 用户已经用手指定了展开哪一组，替他改是干扰。
     */
    /*
     * 默认只展开**当前模型所在那一组**：其余组收起，整张纸片一眼能看完。
     * 换模型之后不自动跟过去 —— 用户已经用手指定了展开哪一组，替他改是干扰。
     */
    var openGroups by remember(groups) {
        mutableStateOf(
            run {
                val current = groups.firstOrNull { group ->
                    group.models.any { it.id == settings.model.id } &&
                        (group.profileId == null || group.profileId == activeProfileId)
                } ?: groups.firstOrNull()
                setOfNotNull(current?.key)
            },
        )
    }

    /* 模型参数默认收起：它改的是"怎么想"，不是"用哪个"，不该抢模型列表的位置。 */
    var paramsOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {

        /*
         * 顶行：当前模型的图标 + 名字，右边两颗按钮 —— **刷新** 和 **加号**。
         *
         * 加号取代了原来底部的「选择模型…」和「管理配置」两行文字：
         * 那两行各占 30dp，把一张本该"扫一眼"的纸片撑成了列表页；
         * 而且"再加一个模型"是个**动作**，动作就该是角落里的一个符号。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelMark(
                model = settings.model,
                size = 16.dp,
                tint = colors.processText,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = settings.model.displayName,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (refreshing) {
                    stringResource(R.string.models_refreshing)
                } else {
                    stringResource(R.string.model_panel_count, models.size)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.placeholder,
            )
            Spacer(Modifier.width(2.dp))
            MiniIconButton(
                icon = AppIcons.Refresh,
                description = stringResource(R.string.models_refresh),
                tint = if (refreshing) colors.placeholder else colors.processText,
                onClick = onRefreshModels,
            )
            MiniIconButton(
                icon = AppIcons.Plus,
                description = stringResource(R.string.model_picker_open),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onPickModels,
            )
        }

        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.warning,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // ── 模型列表（按供应商折叠）─────────────────────────
        if (groups.all { it.models.isEmpty() }) {
            Text(
                text = stringResource(R.string.models_empty),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 232.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                groups.forEach { group ->
                    val list = group.models
                    val open = group.key in openGroups
                    /*
                     * 组头。图标 + 家族名 + 数量 + 箭头。
                     *
                     * 数量不是装饰：收起的一摞里到底有几个模型，是决定"要不要
                     * 点开"的唯一依据 —— 只有一个就不用翻了。
                     */
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 32.dp)
                            .clip(SoftShape)
                            .clickable {
                                openGroups = if (open) openGroups - group.key else openGroups + group.key
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = remember(group.key, list) {
                            list.firstOrNull()
                                ?.let { com.simplechat.app.ui.common.ModelMarks.familyIcon(it) }
                        }
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = colors.processText,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            // 认不出家族的那一组（「其他」）：与行内的通用模型标识同一枚
                            Icon(
                                imageVector = AppIcons.ModelGeneric,
                                contentDescription = null,
                                tint = colors.processText,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colors.processText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            /*
                             * 第二行写"这套配置是什么"：域名，或者"未配置 Key"。
                             *
                             * 两个同名配置（都叫「官方」）靠这一行才分得开；
                             * 而"未配置 Key"必须写在**点进去之前** —— 选完才发现
                             * 发不出去，是最容易让人骂人的一次交互。
                             */
                            if (group.detail.isNotBlank()) {
                                Text(
                                    text = group.detail,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = colors.placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.model_group_count, list.size),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = colors.placeholder,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = AppIcons.ChevronDown,
                            contentDescription = null,
                            tint = colors.placeholder,
                            modifier = Modifier
                                .size(12.dp)
                                .rotate(if (open) 180f else 0f),
                        )
                    }

                    AnimatedVisibility(
                        visible = open,
                        enter = expandVertically(tween(170)) + fadeIn(tween(120)),
                        exit = shrinkVertically(tween(150)) + fadeOut(tween(90)),
                    ) {
                        Column {
                            list.forEach { model ->
                                /*
                                 * 缩进 18dp：让"这些属于上面那一摞"不用解释。
                                 * 缩进而不是画竖线 —— 浮层只有 248dp 宽，
                                 * 一条竖线会把本来就窄的行再切掉一截。
                                 */
                                Box(modifier = Modifier.padding(start = 18.dp)) {
                                    /*
                                     * 选中 = 同一个模型 **且** 同一套配置。
                                     *
                                     * 只比 model id 会出事：两家中转都代理
                                     * `deepseek-v4-pro`，光比 id 两行会同时高亮，
                                     * 用户看不出自己到底在用哪一把 Key。
                                     */
                                    val selected = model.id == settings.model.id &&
                                        (group.profileId == null || group.profileId == activeProfileId)
                                    MiniChoiceRow(
                                        title = model.displayName,
                                        trailing = model.id.takeIf { it != model.displayName },
                                        selected = selected,
                                        leading = {
                                            ModelMark(
                                                model = model,
                                                size = 14.dp,
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    colors.processText
                                                },
                                            )
                                        },
                                        onClick = {
                                            if (group.profileId != null) {
                                                onPickProfileModel(group.profileId, model.id)
                                            } else {
                                                onSettingsChange(settings.copy(model = model))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        MiniDivider()

        /*
         * ── 模型参数（折叠）────────────────────────────────
         *
         * 原来"思考开关 + 五格滑块"常驻在列表下面，占了纸片一半的高度 ——
         * 而换模型和调思考档位是**两件事**，前者每次都做，后者偶尔做。
         * 收成一行，标题右侧直接写出当前档位（"思考 · 中"），不点开也知道。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .clip(SoftShape)
                .clickable { paramsOpen = !paramsOpen }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Sliders,
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.model_params),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.processText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    !supportsThinking -> stringResource(R.string.input_model_unsupported)
                    thinkingOn -> stringResource(R.string.model_params_on, effort.label)
                    else -> stringResource(R.string.model_params_off)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.placeholder,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(if (paramsOpen) 180f else 0f),
            )
        }

        AnimatedVisibility(
            visible = paramsOpen,
            enter = expandVertically(tween(170)) + fadeIn(tween(120)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(90)),
        ) {
            Column {
                // ── 思考：开关 + 五格 ────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.model_panel_thinking_mode),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                        color = if (supportsThinking) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.placeholder
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (supportsThinking) effort.label else "—",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.placeholder,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppSwitch(
                        checked = thinkingOn,
                        onCheckedChange = { on ->
                            if (supportsThinking) {
                                onSettingsChange(
                                    settings.copy(
                                        reasoningLevel = if (on) ReasoningLevel.default else ReasoningLevel.OFF,
                                    ),
                                )
                            }
                        },
                        enabled = supportsThinking,
                    )
                }

                MiniEffortSlider(
                    value = ReasoningLevel.sliderPositionOf(effort).toFloat(),
                    labels = ReasoningLevel.sliderOrder.map { it.label },
                    enabled = thinkingOn && supportsThinking,
                    onValueChange = { raw ->
                        onSettingsChange(
                            settings.copy(reasoningLevel = ReasoningLevel.fromSliderPosition(raw.roundToInt())),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )

            }
        }
    }
}

/**
 * 迷你五点滑块。
 *
 * 和 `InputPanels.EffortSlider` 是同一套画法（细轨 + 五点，选中点胀成实心、
 * 中心留白），只是**缩了一档**：轨道高 20dp、旋钮 6dp、点 2dp、标签 10sp。
 * 浮层宽 264，五格每格 60dp，仍然分得清。
 */
@Composable
private fun MiniEffortSlider(
    value: Float,
    labels: List<String>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }

    val lastIndex = (labels.size - 1).coerceAtLeast(1)
    val accent = MaterialTheme.colorScheme.onSurface
    val idle = colors.outline
    val edge = with(density) { 7.dp.toPx() }
    val knob = with(density) { 6.dp.toPx() }
    val dot = with(density) { 2.dp.toPx() }

    /*
     * 旋钮位置是**连续量**，不是档位序号。
     *
     * 早先每次移动都 `roundToInt()` 再回传给上层，旋钮只能在五个点上跳 ——
     * 手指在中间走，旋钮一格一格地蹦，手感就是"卡卡的"。
     * 现在：拖动时旋钮严格跟手（snap 到手指），只有**跨过格线**才提交一次档位；
     * 松手再补一段 120ms 的吸附动画。档位仍是离散的（模型只认五档），
     * 但手指看到的是连续移动。
     */
    var fraction by remember { mutableStateOf(value) }
    var lastSent by remember { mutableStateOf(value.roundToInt()) }

    LaunchedEffect(value, dragging) {
        if (!dragging) {
            fraction = value
            lastSent = value.roundToInt()
        }
    }

    val knobPosition by animateFloatAsState(
        targetValue = if (dragging) fraction else value,
        animationSpec = if (dragging) snap() else tween(120),
        label = "effortKnob",
    )

    // 与 AppSlider 同一个坑：拖过一次后父级换了状态实例，旧闭包就写不动了
    val changeCallback by rememberUpdatedState(onValueChange)

    fun emitAt(x: Float, totalWidth: Float) {
        if (totalWidth <= 0f) return
        val usable = (totalWidth - edge * 2f).coerceAtLeast(1f)
        val raw = ((x - edge) / usable).coerceIn(0f, 1f) * lastIndex
        fraction = raw
        val index = raw.roundToInt()
        if (index != lastSent) {
            lastSent = index
            changeCallback(index.toFloat())
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val total = size.width.toFloat()
                        dragging = true
                        emitAt(down.position.x, total)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            emitAt(change.position.x, total)
                            change.consume()
                        }
                        dragging = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
                val cy = size.height / 2f
                val startX = edge
                val endX = size.width - edge
                val step = (endX - startX) / lastIndex
                val knobX = startX + step * knobPosition

                drawLine(
                    color = if (enabled) idle else idle.copy(alpha = 0.5f),
                    start = Offset(startX, cy),
                    end = Offset(endX, cy),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (knobPosition > 0f && enabled) {
                    drawLine(
                        color = accent.copy(alpha = 0.55f),
                        start = Offset(startX, cy),
                        end = Offset(knobX, cy),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                repeat(labels.size) { index ->
                    val cx = startX + step * index
                    if (index == knobPosition.roundToInt()) {
                        // 选中点隐在旋钮下面：旋钮本身已经说明了"现在在这儿"
                    } else {
                        drawCircle(
                            color = if (enabled) idle else idle.copy(alpha = 0.5f),
                            radius = dot,
                            center = Offset(cx, cy),
                        )
                    }
                }
                // 旋钮最后画，永远压在最上层，拖动时不会被格点切开
                drawCircle(
                    color = if (enabled) accent else idle,
                    radius = if (dragging) knob + 1.dp.toPx() else knob,
                    center = Offset(knobX, cy),
                )
                drawCircle(
                    color = colors.menuBackground,
                    radius = dot,
                    center = Offset(knobX, cy),
                )
            }
        }

        var totalWidth by remember { mutableStateOf(0) }
        val labelWidths = remember { mutableStateMapOf<Int, Int>() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(13.dp)
                .onSizeChanged { totalWidth = it.width },
        ) {
            labels.forEachIndexed { index, text ->
                val labelWidth = labelWidths[index] ?: 0
                val tickX = edge + (totalWidth - edge * 2f) * (index.toFloat() / lastIndex)
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = when {
                        !enabled -> colors.placeholder.copy(alpha = 0.6f)
                        index == knobPosition.roundToInt() -> MaterialTheme.colorScheme.onSurface
                        else -> colors.placeholder
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset((tickX - labelWidth / 2f).roundToInt(), 0) }
                        .onSizeChanged { labelWidths[index] = it.width },
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  工具审批
// ══════════════════════════════════════════════════════════

/** 审批浮层宽度：三行「名字 + 一句代价」，230 刚好，不再宽。 */
val ApprovalBubbleWidth = 218.dp

@Composable
fun ApprovalMiniPanel(
    current: ApprovalMode,
    onPick: (ApprovalMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    /*
     * 全放行的二次确认**就在这张浮层里换一屏**，不再叠一个对话框。
     *
     * 叠对话框的问题是：浮层还在下面开着，用户要连点两次"关"，而这两层说的
     * 是同一件事。换屏之后，取消 = 退回三档，确认 = 生效，一步一层。
     */
    var confirming by remember { mutableStateOf(false) }

    /*
     * 高度上限 + 可滚动。
     *
     * 这张浮层比另外两张高（三档各带一句说明）。贴着输入栏弹出时，如果键盘
     * 正开着，可用高度只有到键盘那一线 —— 超过的部分会被 Popup 的窗口裁掉，
     * 表现就是"最后一项看得见一半、点不到"。给一个上限并允许滚动，
     * 裁切就不会发生。
     */
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (!confirming) {
            Text(
                text = stringResource(R.string.approval_mode_title),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp),
            )
            ApprovalMode.entries.forEach { mode ->
                MiniChoiceRow(
                    title = stringResource(
                        when (mode) {
                            ApprovalMode.NORMAL -> R.string.approval_mode_normal
                            ApprovalMode.ELEVATED -> R.string.approval_mode_elevated
                            ApprovalMode.YOLO -> R.string.approval_mode_yolo
                        },
                    ),
                    subtitle = stringResource(
                        when (mode) {
                            ApprovalMode.NORMAL -> R.string.approval_mode_normal_desc
                            ApprovalMode.ELEVATED -> R.string.approval_mode_elevated_desc
                            ApprovalMode.YOLO -> R.string.approval_mode_yolo_desc
                        },
                    ),
                    selected = mode == current,
                    danger = mode == ApprovalMode.YOLO,
                    leading = {
                        Icon(
                            imageVector = when (mode) {
                                ApprovalMode.NORMAL -> AppIcons.Lock
                                ApprovalMode.ELEVATED -> AppIcons.Warning
                                ApprovalMode.YOLO -> AppIcons.Bolt
                            },
                            contentDescription = null,
                            tint = if (mode == ApprovalMode.YOLO) colors.danger else colors.processText,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                    onClick = {
                        if (mode == ApprovalMode.YOLO && current != ApprovalMode.YOLO) {
                            confirming = true
                        } else {
                            onPick(mode)
                        }
                    },
                )
            }
        } else {
            Text(
                text = stringResource(R.string.approval_yolo_confirm_title),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = colors.danger,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
            Text(
                text = stringResource(R.string.approval_yolo_confirm_body),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = colors.processText,
                    modifier = Modifier
                        .clip(SoftShape)
                        .clickable { confirming = false }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
                Text(
                    text = stringResource(R.string.approval_yolo_confirm_ok),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.danger,
                    modifier = Modifier
                        .clip(SoftShape)
                        .clickable { onPick(ApprovalMode.YOLO) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  上下文
// ══════════════════════════════════════════════════════════

/** 上下文浮层宽度。比模型那张窄 —— 里面没有两列文本。 */
val ContextBubbleWidth = 214.dp

@Composable
fun ContextMiniPanel(
    stats: ContextStats,
    compacting: Boolean = false,
    onCompact: () -> Unit = {},
    onCancelCompact: () -> Unit = {},
    onClearCompaction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val accent = when {
        stats.ratio >= 0.9f -> colors.danger
        stats.ratio >= 0.7f -> colors.warning
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_context_usage),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${stats.percent}%",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = accent,
            )
        }

        Spacer(Modifier.height(6.dp))

        // 占用条：4dp，比面板里那条细一半
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(4.dp)
                .clip(PillShape)
                .background(colors.outline),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(stats.ratio.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(PillShape)
                    .background(accent),
            )
        }

        Spacer(Modifier.height(5.dp))

        Text(
            text = stringResource(
                R.string.stats_tokens_estimate,
                TokenEstimate.format(stats.usedTokens),
                TokenEstimate.format(stats.windowTokens),
            ),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = colors.placeholder,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        MiniDivider()

        MiniStatRow(
            stringResource(R.string.stats_message_count),
            stringResource(R.string.stats_messages, stats.messageCount),
            icon = AppIcons.MessageSquare,
        )
        MiniStatRow(
            stringResource(R.string.stats_total_tokens),
            TokenEstimate.format(stats.totalTokens),
            icon = AppIcons.Database,
        )
        MiniStatRow(
            stringResource(R.string.stats_avg_tps),
            stats.avgTps?.let { "${it.roundToInt()} tok/s" } ?: "—",
            icon = AppIcons.Sparkles,
        )

        MiniDivider()

        /*
         * 压缩的两个状态都留在这里，但都只占一行。
         *
         * 完整版面板里那套「预览 / 改摘要 / 放弃」没有搬过来 —— 那些是
         * 看过摘要之后才需要做的决定，塞进 226dp 的小纸片只会挤成一团。
         * 这里只留"开始 / 停止 / 清掉"三个动作，够用且不打断阅读。
         */
        if (compacting) {
            Text(
                text = stringResource(R.string.compact_running),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
            )
            MiniActionRow(
                icon = AppIcons.Close,
                label = stringResource(R.string.action_cancel),
                onClick = onCancelCompact,
            )
        } else {
            if (stats.hasCompaction) {
                Text(
                    text = stringResource(
                        R.string.stats_compacted_saving,
                        TokenEstimate.format(stats.compactedTokenSaving),
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.processText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp, bottom = 1.dp),
                )
            }
            MiniActionRow(
                icon = AppIcons.Archive,
                label = stringResource(
                    if (stats.hasCompaction) R.string.compact_redo else R.string.compact_start,
                ),
                onClick = onCompact,
            )
            if (stats.hasCompaction) {
                MiniActionRow(
                    icon = AppIcons.Trash,
                    label = stringResource(R.string.compact_discard),
                    muted = true,
                    onClick = onClearCompaction,
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  共用小件
// ══════════════════════════════════════════════════════════

/**
 * 一行单选。
 *
 * 选中 = **只有右侧对勾**，没有底色。
 *
 * 上一版选中行铺了一层 `fieldBackground`（浅灰）。在"白底卡片"里这一层灰
 * 并不读作"选中"，而是读作"这块区域按不动"——像禁用。对勾已经足够说明
 * 当前值，行背景保持和卡片同色，整张纸片才是一张纸。
 */
/**
 * 一行「已有内置文件夹」。
 *
 * 结构是 `[文件夹] 名字 ………… [勾] [垃圾桶]`。
 * 删除键只有 30dp 见方，但靠右边缘 —— 手指从右边过来时不会先碰到整行。
 */
@Composable
private fun InternalFolderRow(
    name: String,
    selected: Boolean,
    onOpen: () -> Unit,
    /** 为 null 时不显示垃圾桶（根目录行、新建行）。 */
    onDelete: (() -> Unit)?,
    leading: androidx.compose.ui.graphics.vector.ImageVector = AppIcons.Folder,
    /** 新建入口用：图标与文字都淡一档，和"已有文件夹"区分开。 */
    muted: Boolean = false,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .heightIn(min = 34.dp)
            .clip(SoftShape)
            .clickable(onClick = onOpen)
            .padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leading,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else colors.placeholder,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (muted) colors.processText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(2.dp))
        } else if (onDelete != null) {
            // 没有勾时留出与勾等宽的空档，垃圾桶一列始终对齐
            Spacer(Modifier.width(14.dp))
        }
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Trash,
                    contentDescription = stringResource(R.string.workspace_delete),
                    tint = colors.placeholder,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** 删除确认条：**长在行下面**，不弹新窗口。 */
@Composable
private fun DeleteFolderRow(
    name: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 2.dp, top = 2.dp, bottom = 4.dp)
            .clip(SoftShape)
            .background(colors.danger.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.workspace_delete_confirm, name),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = colors.danger,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.placeholder,
                modifier = Modifier
                    .clip(SoftShape)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Text(
                text = stringResource(R.string.action_delete),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.danger,
                modifier = Modifier
                    .clip(SoftShape)
                    .clickable(onClick = onConfirm)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun MiniChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: String? = null,
    danger: Boolean = false,
    /** 行尾是否带一枚可旋转的展开箭头（工作区里的「内置工作区」用）。 */
    expandable: Boolean = false,
    expanded: Boolean = false,
    /**
     * 只切换展开态，不改变选中。
     *
     * 箭头单独吃掉这一下点击：折叠区一旦展开，行主体再点就是「选中 + 展开」，
     * 而用户想收起来时点的往往正是箭头 —— 两条路径必须分开，否则
     * 「点一下收回去」永远做不到（这是之前那个 bug 的直接成因）。
     */
    onToggle: (() -> Unit)? = null,
    /** 行首的小图标。模型行放厂商 logo，审批行放盾 / 锁 / 闪电。 */
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    val titleColor = when {
        danger -> colors.danger
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 36dp 是最低触控高度。浮层里一行只有三十来 dp 时，拇指按下去
            // 命中率会明显下降 —— 尤其审批那张，一行挨一行。
            .heightIn(min = if (subtitle == null) 34.dp else 40.dp)
            .clip(SoftShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (subtitle == null) 6.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(7.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 108.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        if (expandable) {
            /*
             * 箭头的热区是 30dp 见方，而不是图标本身的 12dp。
             *
             * 12dp 的图标当按钮用，十次里能点空三次 —— 用户会以为"这点不动"，
             * 然后去点行主体，又被"选中 + 展开"接住。热区放大之后，
             * 「点箭头 = 纯折叠」这条路径才真的可用。
             */
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = onToggle ?: onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.placeholder,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
        }
        Icon(
            imageVector = AppIcons.Check,
            contentDescription = null,
            tint = if (selected) colors.processText else Color.Transparent,
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * 浮层底部的整行动作：**图标 + 居中的文字**。
 *
 * 之前是纯文字。一张浮层里如果只有"文字行"和"文字行"，看着就像三行说明 ——
 * 用户不会知道最下面那两行是可以点的。加一枚 13dp 的图标，动作感就回来了，
 * 而且不用给按钮铺底色。
 */
@Composable
private fun MiniActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(SoftShape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (muted) colors.placeholder else colors.processText,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (muted) colors.placeholder else colors.processText,
        )
    }
}

/**
 * 浮层里的一颗小图标按钮：24dp 见方的圆，图标 14dp。
 *
 * 用 `Box` 而不是 `IconButton`：后者的最小触控格是 48dp，在一张 30dp 行的
 * 纸片里会把整行顶高。
 */
@Composable
private fun MiniIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun MiniSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MiniStatRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colors.placeholder,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MiniDivider() {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .height(1.dp)
            .background(colors.divider),
    )
}


// ══════════════════════════════════════════════════════════
//  工作区
// ══════════════════════════════════════════════════════════

/** 工作区浮层宽度：够放「名字 + host」两列，又不至于在窄屏上顶边。 */
val WorkspaceBubbleWidth = 292.dp

/**
 * 输入框上方那颗工作区 chip 点开的小纸片。
 *
 * 三件事都在这里做完，不再跳一整个页面：
 * 1. 内置工作区（应用私有目录），可以**起一个文件夹名**；
 * 2. 手机文件夹 —— 拉起系统目录选择器，从 `/storage/emulated/0` 里挑；
 * 3. 已配置的云端环境。
 *
 * 早先点 chip 会进一张占满屏的「设置工作区」页，用户选完还要返回对话页；
 * 而这件事的本质只是"换一个落文件的抽屉"，一张纸片就够了。
 */
@Composable
fun WorkspaceMiniPanel(
    currentLabel: String,
    cloudBound: Boolean,
    /** 当前绑的是不是内置目录（含命名子文件夹）。 */
    internalSelected: Boolean,
    /** 当前绑定的内置目录绝对路径；没绑内置时传 null。用来在列表里打勾。 */
    internalPath: String? = null,
    /** 当前没有任何绑定（普通对话）。 */
    noneSelected: Boolean = false,
    cloudEnvs: List<CloudEnv>,
    boundCloudId: String?,
    onPickInternal: (String?) -> Unit,
    onPickDevice: () -> Unit,
    onPickCloud: (CloudEnv) -> Unit,
    onManageCloud: () -> Unit,
    /** 退回"不绑定"：不落文件，只聊天。 */
    onPickNone: () -> Unit = {},
    /** 解除当前会话的云端绑定，回到本地目录。 */
    onClearCloud: () -> Unit = {},
    /** 任何一项选完之后的收尾（收起浮层）。 */
    onPicked: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    /*
     * 已创建的内置工作区。
     *
     * 之前这里只有"内置工作区"和"新建内置文件夹"两行 —— 建出来的文件夹
     * 在文件系统里真实存在，界面上却再也看不到，更删不掉。列表是它们的
     * 唯一出口，所以删除也必须长在这一行上。
     *
     * 读一次放在 state 里：删完要立刻重读，不然被删的那行还挂在界面上。
     */
    var folders by remember { mutableStateOf(com.simplechat.app.agent.Workspace.internalFolders()) }
    var pendingDelete by remember { mutableStateOf<java.io.File?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    /*
     * 文件夹列表默认**收起**。
     *
     * 这张浮层是"选工作区"，不是"管理文件夹"—— 建过七八个项目之后，
     * 展开的列表会把「本机目录」「云端环境」压到看不见的地方，
     * 每次换工作区都得先滚一段自己并不想看的东西。
     *
     * 例外：当前正绑着某个命名子文件夹时展开 —— 否则浮层里找不到
     * "我现在在哪"，而这件事比"列表别太长"更要紧。
     */
    /*
     * 展开状态**不能**用 `remember(internalPath)`：点标题行会立刻把绑定改成
     * 内置根，key 一变状态就重置成"收起" —— 用户看到的是"点一下闪一下"。
     * 初始是否展开只看首次进入时的绑定；之后的选中变化只负责"展开"，不负责"收起"。
     */
    /*
     * 折叠态用 `rememberSaveable`：`remember` 只在这个 composable 的实例里活着，
     * 而浮层每次重新弹出都可能重建实例 —— 重建一次状态就掉回初始值，
     * 用户看到的是"收不起来"。存进 saveable 之后，重建也还是原来那一边。
     */
    var foldersOpen by rememberSaveable {
        mutableStateOf(
            internalPath != null &&
                !com.simplechat.app.agent.Workspace.isInternalRoot(internalPath),
        )
    }
    LaunchedEffect(internalPath) {
        if (internalPath != null &&
            !com.simplechat.app.agent.Workspace.isInternalRoot(internalPath)
        ) {
            foldersOpen = true
        }
    }

    /*
     * 点「新建内置文件夹」就直接把光标放进输入框并拉起键盘。
     *
     * 只让输入框"能聚焦"是不够的：那需要用户再点一下那个 32dp 高的格子，
     * 而它看起来和旁边那些**只能看**的行长得一样 —— 用户不会知道要点。
     * 自动聚焦之后，"这里可以打字"是键盘自己说的。
     */
    LaunchedEffect(naming) {
        if (!naming) return@LaunchedEffect
        runCatching {
            nameFocus.requestFocus()
            // 等一帧：焦点真正落到输入框上之后再 show()，否则会被系统忽略
            withFrameNanos { }
            keyboard?.show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        /*
         * 顶部那行「工作区 …… 当前绑定」已经删掉。
         *
         * 它左右各挂一个同样的词：左边是分类名，右边是当前值，而值在多数情况下
         * 就是「工作区」三个字 —— 看起来像标题重复渲染了两遍。选中态本身已经在
         * 第一行打了勾，这行除了占高没有别的信息。
         */
        MiniChoiceRow(
            title = stringResource(R.string.workspace_pick_none),
            subtitle = stringResource(R.string.workspace_pick_none_desc),
            selected = noneSelected && !cloudBound,
            leading = {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = null,
                    tint = colors.processText,
                    modifier = Modifier.size(15.dp),
                )
            },
            onClick = {
                onPickNone()
                onPicked()
            },
        )

        /*
         * 内置工作区：**这一行本身就是折叠控件**。
         *
         * 早先是"选中行"和"已创建文件夹组头"上下两行 —— 两个箭头、两次点击，
         * 用户要先把工作区切过去，再去另一行展开列表。现在合成一行：
         *
         * - 行尾一枚箭头，点行 = 选中内置根目录 **并** 展开；
         * - 已经在内置根上再点 = 收回；
         * - 展开区里才是已创建的文件夹与「新建」入口。
         *
         * 选中子文件夹时标题副行显示「内置工作区 · 名字」—— 收起状态下
         * 也必须能一眼看出"我现在在哪个子目录"。
         */
        val onInternalRoot = internalPath != null &&
            com.simplechat.app.agent.Workspace.isInternalRoot(internalPath)
        val selectedFolderName = internalPath
            ?.takeIf { internalSelected && !onInternalRoot }
            ?.substringAfterLast('/')

        MiniChoiceRow(
            title = stringResource(R.string.workspace_pick_internal),
            subtitle = if (selectedFolderName != null) {
                stringResource(R.string.workspace_pick_internal_named, selectedFolderName)
            } else {
                stringResource(R.string.workspace_pick_internal_desc)
            },
            selected = internalSelected,
            expandable = true,
            expanded = foldersOpen,
            leading = {
                Icon(
                    imageVector = AppIcons.Database,
                    contentDescription = null,
                    tint = if (internalSelected) MaterialTheme.colorScheme.primary else colors.processText,
                    modifier = Modifier.size(15.dp),
                )
            },
            onClick = {
                /*
                 * 展开着就先收起来 —— **不管当前绑定是什么**。
                 *
                 * 之前这里判的是"站在根目录上才允许收起"，一旦绑定路径与
                 * `internalPath` 有一帧不同步（选中后状态是异步回来的），
                 * 条件就永远不成立：点一次展开，再点还是展开，收不回去。
                 * 折叠这件事只该看 `foldersOpen`，不该看选中的是哪个目录。
                 */
                if (foldersOpen) {
                    foldersOpen = false
                } else {
                    if (!internalSelected) onPickInternal(null)
                    foldersOpen = true
                }
            },
            onToggle = { foldersOpen = !foldersOpen },
        )

        AnimatedVisibility(
            visible = foldersOpen,
            enter = expandVertically(tween(170)) + fadeIn(tween(120)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(90)),
        ) {
            Column {
                /*
                 * 根目录也列出来：标题行点了只"选中根"，但如果用户是从某个
                 * 子文件夹切过来的，这里需要一个明确的落点，而不是让他猜
                 * "再点一次标题会不会回去"。
                 */
                InternalFolderRow(
                    name = stringResource(R.string.workspace_internal_root),
                    selected = onInternalRoot,
                    onOpen = {
                        onPickInternal(null)
                        onPicked()
                    },
                    onDelete = null,
                )

                folders.forEach { folder ->
                    InternalFolderRow(
                        name = folder.name,
                        selected = internalPath != null && internalPath == folder.absolutePath,
                        onOpen = {
                            onPickInternal(folder.name)
                            onPicked()
                        },
                        onDelete = { pendingDelete = folder },
                    )
                }

                pendingDelete?.let { folder ->
                    DeleteFolderRow(
                        name = folder.name,
                        onCancel = { pendingDelete = null },
                        onConfirm = {
                            com.simplechat.app.agent.Workspace.deleteInternal(folder)
                                .onFailure { deleteError = it.message }
                            folders = com.simplechat.app.agent.Workspace.internalFolders()
                            pendingDelete = null
                            onPicked()
                        },
                    )
                }

                deleteError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = colors.danger,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    )
                }

                if (naming) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(SoftShape)
                                .background(colors.fieldBackground)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (name.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.workspace_folder_name_hint),
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                    color = colors.placeholder,
                                )
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it.take(24) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(nameFocus),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.workspace_create),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = if (name.isBlank()) colors.placeholder else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(SoftShape)
                                .clickable(enabled = name.isNotBlank()) {
                                    onPickInternal(name.trim())
                                    naming = false
                                    name = ""
                                    /*
                                     * 刚建出来的那个必须看得见 —— 列表收起时建完
                                     * 直接消失，用户会以为没建成，然后重复建第二个。
                                     */
                                    folders = com.simplechat.app.agent.Workspace.internalFolders()
                                    foldersOpen = true
                                    onPicked()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    InternalFolderRow(
                        name = stringResource(R.string.workspace_new_internal),
                        selected = false,
                        leading = AppIcons.FolderPlus,
                        muted = true,
                        onOpen = { naming = true },
                        onDelete = null,
                    )
                }
            }
        }

        MiniChoiceRow(
            title = stringResource(R.string.workspace_pick_device),
            subtitle = "/storage/emulated/0",
            selected = false,
            leading = {
                Icon(
                    imageVector = AppIcons.FolderOpen,
                    contentDescription = null,
                    tint = colors.processText,
                    modifier = Modifier.size(15.dp),
                )
            },

            onClick = {
                onPickDevice()
                onPicked()
            },
        )

        if (cloudBound) {
            MiniChoiceRow(
                title = stringResource(R.string.workspace_cloud_unbind),
                subtitle = stringResource(R.string.workspace_cloud_unbind_hint),
                selected = false,
                danger = true,
            leading = {
                Icon(
                    imageVector = AppIcons.Globe,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(15.dp),
                )
            },

                onClick = {
                    onClearCloud()
                    onPicked()
                },
            )
        }

        MiniDivider()

        if (cloudEnvs.isEmpty()) {
            Text(
                text = stringResource(R.string.workspace_cloud_empty),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            cloudEnvs.forEach { env ->
                MiniChoiceRow(
                    /*
                     * 标题只用**用户自己起的名字**。
                     *
                     * 早先副标题挂的是 `hostLabel` —— 部署在托管平台上时那串是
                     * `workbuddy-xxxx.hf.space` 之类，用户看到的"名字"就成了
                     * 平台域名，而不是他填的「东京小机器」。主机名属于管理页的
                     * 技术细节，选工作区时不需要它。
                     */
                    title = env.name,
                    subtitle = stringResource(R.string.workspace_pick_cloud_desc),
                    selected = cloudBound && env.id == boundCloudId,
                    onClick = {
                        onPickCloud(env)
                        onPicked()
                    },
                )
            }
        }

        Text(
            text = stringResource(R.string.workspace_manage_cloud),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = colors.processText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(SoftShape)
                .clickable {
                    onManageCloud()
                    onPicked()
                }
                .padding(vertical = 8.dp),
        )
    }
}

/**
 * 模型面板里的一摞。
 *
 * [profileId] 非空表示这一摞对应一套配置，点里面的模型要**先切配置**；
 * 为空是回落路径（按模型家族分组），行为与旧版一致。
 */
private data class ModelRowGroup(
    val key: String,
    val title: String,
    val detail: String,
    val profileId: String?,
    val models: List<ModelInfo>,
)

/**
 * 组头第二行：这套配置"是什么"。
 *
 * 优先报"未配置 Key" —— 它比域名重要得多：没有 Key 的配置点进去必然失败，
 * 而域名只是用来区分同名的两套配置。
 */
private fun profileDetail(profile: com.simplechat.app.data.ModelProfile): String {
    val kind = runCatching { com.simplechat.app.net.ProviderKind.valueOf(profile.providerId) }.getOrNull()
    if (kind?.requiresApiKey != false && profile.apiKey.isBlank()) {
        return Res.get(R.string.profile_no_key)
    }
    val host = profile.baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .trim()
    return host.ifBlank { kind?.displayName.orEmpty() }
}
