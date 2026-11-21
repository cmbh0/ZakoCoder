package com.simplechat.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.ui.theme.LocalChatColors
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.withFrameNanos

// ══════════════════════════════════════════════════════════
//  布局刻度
// ══════════════════════════════════════════════════════════

/**
 * 全局间距刻度。
 *
 * 只有一个目的：**别让 12 / 14 / 16 混着用**。此前抽屉里搜索框内缩 16dp、
 * 会话行内缩 8dp，两个色块左右对不齐，一眼就能看出来。
 */
object Space {
    /** 屏幕左右安全内边距。所有页面的一级内容都从这里开始。 */
    val Screen = 16.dp

    /** 卡片 / 浮层 / 面板的内部内边距。 */
    val Card = 16.dp

    /** 行内元素之间（图标与文字、相邻按钮）。 */
    val Inline = 12.dp

    /** 组内元素的纵向间距。 */
    val Gap = 8.dp

    /** 分组之间的纵向间距。 */
    val Section = 24.dp
}

// ══════════════════════════════════════════════════════════
//  圆角规格：只有四档
// ══════════════════════════════════════════════════════════

/** 胶囊形（全圆角）。**所有**输入框、搜索框、带文字 / 带箭头的按钮一律用它。 */
val PillShape = RoundedCornerShape(percent = 50)

/**
 * 胶囊形搜索 / 输入条的**粗细**（内边距的纵向值）。
 *
 * 抽屉的搜索框与全屏搜索页的搜索框共用 —— 之前一处 10dp、一处 12dp，
 * 两条并排看明显一个粗一个细。**以搜索页那条为准**，横向内边距各留各的
 * （抽屉要与分组标题对齐，见 `HistoryDrawer.DrawerTextInset`）。
 */
val FieldThickness = 12.dp

/** 列表行、小卡片。 */
val SoftShape = RoundedCornerShape(14.dp)

/** 大卡片与浮层菜单。 */
val MenuShape = RoundedCornerShape(20.dp)

/**
 * 多行容器（输入栏、输入面板）。
 *
 * 28dp 是照 DeepSeek 2.8.0 的截图量的：1080px 宽、density 2.75 的机器上，
 * 输入框圆角约 78px ≈ 28dp。24dp 时四个角看起来"方了一点"，和里面全圆的
 * 胶囊并排放，视觉上不是一家人。
 */
val ContainerShape = RoundedCornerShape(28.dp)

/** 消息气泡：靠近发出侧的一角收紧，给内容一个方向感。 */
val BubbleShapeOut = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
val BubbleShapeIn = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)

// ══════════════════════════════════════════════════════════
//  滑块
// ══════════════════════════════════════════════════════════

/**
 * 自定义滑块。
 *
 * 与 Material3 `Slider` 的区别（都是实测反馈出来的）：
 * - 指示器是**正圆**，按下时**放大**（触感反馈）；
 * - **点击轨道可直接跳转**，而不是只能拖动 —— Material 的 Slider 在
 *   离散档位下点轨道只走一格，甚至没反应；
 * - 刻度线画在轨道上下两侧，一眼能看出有几档。
 *
 * @param intervals 档位间隔数；0 表示连续无刻度。实际取值由调用方自行吸附。
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    intervals: Int = 0,
    enabled: Boolean = true,
    /** 手势结束时回调一次。用于"拖动时只预览、松手才落盘"。 */
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * 每个刻度的文案。传了就画在轨道下方，**中心与刻度严格对齐**。
     *
     * 不要在调用方用 `Row + weight(1f)` 自己排 —— 那样标签中心在
     * `(i+0.5)/n`，而刻度在 `i/n`，天然错半个格。
     */
    tickLabels: List<String>? = null,
    /** 高亮第几个刻度文案。 */
    selectedTick: Int = -1,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current

    var pressed by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0f) }
    // pointerInput 的 block 只在 key 变化时重建，直接闭包捕获会拿到旧 lambda
    val finishedCallback by rememberUpdatedState(onValueChangeFinished)
    /*
     * `onValueChange` 同理，而且这条更隐蔽：
     *
     * 调用方（设置页）常常把初值放在 `remember(value)` 里当草稿。第一次拖完、
     * 值落库后，`remember(value)` 会**换一个新的 MutableState**，而 pointerInput
     * 捕获的还是旧那个 —— 于是拇指照旧画在旧状态上，手势再也改不动任何东西。
     * 表现就是"滑一下之后就跟焊死了一样"。
     */
    val changeCallback by rememberUpdatedState(onValueChange)

    val thumbIdle = with(density) { 9.dp.toPx() }
    val thumbActive = with(density) { 13.dp.toPx() }
    val trackThin = with(density) { 3.dp.toPx() }
    val trackThick = with(density) { 5.dp.toPx() }
    val tickHeight = with(density) { 7.dp.toPx() }
    val edge = with(density) { 16.dp.toPx() }

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = colors.outline
    val thumbFill = colors.card
    val tickColor = colors.placeholder

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun emitAt(x: Float) {
        if (width <= 0f) return
        val usable = (width - edge * 2f).coerceAtLeast(1f)
        val f = ((x - edge) / usable).coerceIn(0f, 1f)
        changeCallback(valueRange.start + f * span)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .onSizeChanged { width = it.width.toFloat() }
                /*
                 * key 只放**值**，不放 range 对象。
                 *
                 * `0.4f..0.95f` 每次重组都会 new 一个 ClosedFloatingPointRange，
                 * 把它当 key 等于每一帧都重启 pointerInput —— 拖动中的手势被
                 * 取消在最开始的几个像素上，表现就是"怎么滑都不动"。
                 */
                .pointerInput(enabled, valueRange.start, valueRange.endInclusive, width) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        emitAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            emitAt(change.position.x)
                            change.consume()
                        }
                        pressed = false
                        finishedCallback?.invoke()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
            val cy = size.height / 2f
            val startX = edge
            val endX = size.width - edge
            val radius = if (pressed) thumbActive else thumbIdle
            val thumbX = startX + (endX - startX) * fraction
            val lineWidth = if (pressed) trackThick else trackThin

            // 刻度：先画，被轨道覆盖中段，两端露出来当"齿"
            if (intervals > 0) {
                repeat(intervals + 1) { index ->
                    val tx = startX + (endX - startX) * (index.toFloat() / intervals)
                    drawLine(
                        color = tickColor,
                        start = Offset(tx, cy - tickHeight / 2f),
                        end = Offset(tx, cy + tickHeight / 2f),
                        strokeWidth = trackThin,
                        cap = StrokeCap.Round,
                    )
                }
            }

            drawLine(
                color = idleColor,
                start = Offset(startX, cy),
                end = Offset(endX, cy),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = if (enabled) activeColor else idleColor,
                start = Offset(startX, cy),
                end = Offset(thumbX, cy),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )

            drawCircle(color = thumbFill, radius = radius, center = Offset(thumbX, cy))
            drawCircle(
                color = if (enabled) activeColor else idleColor,
                radius = radius,
                center = Offset(thumbX, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            }
        }

        if (tickLabels != null) {
            Spacer(Modifier.height(6.dp))
            SliderTickLabels(
                labels = tickLabels,
                selectedIndex = selectedTick,
                edgePx = edge,
                selectedColor = activeColor,
                normalColor = tickColor,
            )
        }
    }
}

/**
 * 刻度文案：逐个按**刻度的实际横坐标**居中，而不是用 `Row + weight` 平分。
 *
 * 后者的标签中心在 `(i+0.5)/n`，刻度在 `i/n` —— 会稳定错开半格，
 * 看上去就是"high / max 没对上刻度"。
 */
@Composable
private fun SliderTickLabels(
    labels: List<String>,
    selectedIndex: Int,
    edgePx: Float,
    selectedColor: Color,
    normalColor: Color,
) {
    var totalWidth by remember { mutableIntStateOf(0) }
    val labelWidths = remember { mutableStateMapOf<Int, Int>() }
    val lastIndex = (labels.size - 1).coerceAtLeast(1)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { totalWidth = it.width },
    ) {
        labels.forEachIndexed { index, text ->
            val labelWidth = labelWidths[index] ?: 0
            val tickX = edgePx +
                (totalWidth - edgePx * 2f) * (index.toFloat() / lastIndex)

            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (index == selectedIndex) selectedColor else normalColor,
                maxLines = 1,
                modifier = Modifier
                    // 让文案的中心落在刻度上
                    .offset { IntOffset((tickX - labelWidth / 2f).roundToInt(), 0) }
                    .onSizeChanged { labelWidths[index] = it.width },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  进度环
// ══════════════════════════════════════════════════════════

/**
 * 环形进度。用于「上下文占用」这类**常驻**的少量信息。
 *
 * 比线性进度条省横向空间：一个环 + 4 个字符就能说清"1M 里用了 12k"，
 * 而输入栏第二行本来就很挤。
 */
@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.Unspecified,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
) {
    val colors = LocalChatColors.current
    val resolvedTrack = if (trackColor == Color.Unspecified) colors.outline else trackColor
    val sweep = (progress.coerceIn(0f, 1f)) * 360f

    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        // 显式走 DrawScope.size —— 外层还有个同名参数 size: Dp，直接写会歧义
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)

        drawArc(
            color = resolvedTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (sweep > 0f) {
            drawArc(
                color = color,
                // 从 12 点方向顺时针
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  开关
// ══════════════════════════════════════════════════════════

/**
 * 紧凑胶囊开关 —— **全应用唯一的开关**。
 *
 * 不用 Material3 `Switch`：它默认 52×32dp，在设置列表和输入面板里比行标题还抢眼，
 * 而且深色下描边偏粗、滑块带一圈投影。这里 38×22dp，轨道胶囊形、滑块正圆 ——
 * 与「图标按钮是圆、带字按钮是胶囊」的规则保持一致。
 *
 * ⚠️ 想改开关大小**只改这里**。此前设置页和输入面板各写了一个 M3 `Switch`，
 * 于是同一个列表里出现了两种大小（52dp 与 40dp），一眼就看出不齐。
 *
 * 用 `toggleable` 而不是 `clickable`：读屏才会念"开关，已选中"，而不是"按钮"。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalChatColors.current
    /*
     * 40×24 / 拇指 20。与官方客户端接近 —— 38×22 那一档在设置页里
     * 和 16sp 的标题并排时会显得"开关比字还小"，看着像没长开。
     */
    val trackWidth = 36.dp
    val trackHeight = 21.dp
    val thumbSize = 17.dp
    val padding = 2.dp

    /*
     * 滑块用弹簧，不用 tween。
     *
     * 开关是"手动过去的一个小东西"，tween 的匀速-减速在 21dp 的行程上
     * 只够看出"挪了一下"；带一点回弹才有被拨动的实感。dampingRatio 0.62
     * 是最轻的一档 —— 再低就开始左右晃，在设置列表里非常吵。
     */
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - padding else padding,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 640f),
        label = "switchThumb",
    )

    /*
     * 轨道颜色单独过渡。
     *
     * 之前 trackColor 是直接按状态取的常量：滑块还在半路上滑，底色已经
     * 整块换成主色了，两者不同步。现在颜色 240ms 跟上，滑块 300ms 左右到位，
     * 读起来是"滑块把颜色带过去的"。
     */
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.divider
            checked -> MaterialTheme.colorScheme.primary
            // 关闭态的轨道用描边色而不是另一种灰：深色下才和卡片分得开
            else -> colors.outline
        },
        animationSpec = tween(240, easing = MotionEasing),
        label = "switchTrack",
    )

    // 按下去的时候滑块略微胀一点，松开回弹 —— 触摸就有了"抓住了"的反馈
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.14f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "switchThumbScale",
    )

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .clip(PillShape)
            .background(trackColor)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                // 显式给回默认水波纹：带 interactionSource 的重载默认 indication = null
                indication = LocalIndication.current,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbSize)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .clip(CircleShape)
                .background(if (enabled) Color.White else colors.placeholder),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  按钮：只有两种形状
// ══════════════════════════════════════════════════════════

/**
 * **纯图标按钮 = 圆形。**
 *
 * 全应用只有这一种圆形按钮实现。此前的毛病是圆形 / 胶囊 / 圆角矩形混用，
 * 界面上同一行里三种形状并存，很乱。
 */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    enabled: Boolean = true,
    /** 非空则铺一层底色。 */
    background: Color? = null,
    tint: Color? = null,
    /** 描边。与「图标 + 箭头」的胶囊入口保持同一套视觉语言。 */
    outlined: Boolean = false,
    outlineColor: Color? = null,
    /** 形状。默认圆形；配合更宽的 [width] 传 `PillShape` 就是胶囊。 */
    shape: Shape = CircleShape,
    /** 宽。默认与 [size] 相同（正方形/圆）；胶囊形要显式给一个更宽的值。 */
    width: Dp = size,
) {
    val colors = LocalChatColors.current
    val resolvedTint = tint ?: when {
        !enabled -> colors.placeholder
        else -> MaterialTheme.colorScheme.onSurface
    }
    /*
     * 按下去缩一点再弹回来。
     *
     * 图标按钮没有文字，点没点中全靠这一下反馈。用 0.90 而不是 0.96：
     * 这个尺寸（24~36dp）上 4% 根本看不出来，10% 才够形成"按进去"的错觉。
     * 弹簧回弹比 tween 更像真实按钮 —— 松手时多走一点点再停。
     */
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "iconPress",
    )
    Box(
        modifier = modifier
            .width(width)
            .height(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .then(
                if (outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = outlineColor ?: colors.outline,
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            /*
             * 水波纹换成缩放：两套反馈同时出现时，圆的边界先被波纹撑破，
             * 再叠一个缩放，看起来像抖了一下。
             */
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * **带文字的按钮 = 胶囊。**
 *
 * 用于「深度思考」「创作模式」这类标签，以及各处确认按钮。
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** true = 描边（默认）；false = 无边框的裸胶囊。 */
    outlined: Boolean = true,
) {
    val colors = LocalChatColors.current
    val contentColor = when {
        !enabled -> colors.placeholder
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderColor = if (selected) MaterialTheme.colorScheme.primary else colors.outline

    // 与图标按钮同一档按压反馈（见 IconCircleButton），只是幅度小一点：
    // 胶囊比圆按钮更宽，同比例缩放会显得整块在晃
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "capsulePress",
    )

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(PillShape)
            .background(if (selected) colors.capsuleSelected else Color.Transparent)
            .then(
                if (outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = if (enabled) borderColor else colors.divider,
                        shape = PillShape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  长按菜单
// ══════════════════════════════════════════════════════════

/**
 * 菜单宽度。**固定值** —— 宽度随内容浮动会让同一组件在每处长不一样。
 *
 * 从 214 收到 196：官方那条长按菜单在 1080 宽的机器上只占约 190dp，
 * 四周还留着一大圈对话。菜单是"点一下就走的临时物"，它占的面积
 * 应该比内容多一点，而不是比屏幕的一半还多。
 */
val MenuWidth = 196.dp

/** 菜单项固定行高。官方七项一屏不挤，靠的是 44 而不是 50。 */
private val MenuItemHeight = 44.dp

/** 菜单与锚点的间隙。 */
private val MenuGap = 8.dp

/**
 * 为阴影预留的绘制空间。
 *
 * `Popup` 的窗口尺寸就是内容尺寸，阴影画在内容之外的部分会被直接裁掉 ——
 * 表现是浮层边缘"阴影断了"。预留一圈后阴影才能自然扩散。
 */
/**
 * 阴影的预留边距。
 *
 * 这个值直接决定 Popup 窗口的大小：26dp 时窗口四边各多出 26dp 的**纯透明像素**，
 * 每开一次菜单都要多合成一大圈。收到 14dp —— 阴影仍然完整（elevation 12 的
 * 扩散半径不到 14），窗口却小了将近一半，首帧明显轻。
 */
private val MenuShadowBleed = 14.dp

/**
 * 投影规格。**两档，全应用统一**。
 *
 * 关键：`elevation` 只决定**扩散范围**，浓淡由 `ambient/spot` 的 alpha
 * 单独控制 —— 两者拆开才谈得上"范围大但很淡"。
 *
 * 之前就栽在这里：输入栏用框架默认色（偏黑），菜单却用了 4% 的极淡色，
 * 于是「输入栏太浓、菜单看不见」，方向完全反了。
 */
object AppShadow {
    /**
     * 贴底大面板（输入栏、输入面板）。
     *
     * 面积大，同样的 alpha 会比小卡片显眼得多，所以两档之间保持一倍以上差距。
     * 数值是实机来回比对出来的：再低就"没有"，再高就"发脏"。
     */
    val PanelElevation = 8.dp
    val PanelAmbient = Color.Black.copy(alpha = 0.05f)
    val PanelSpot = Color.Black.copy(alpha = 0.10f)

    /** 浮层（菜单）与抽屉：需要明确"浮起来"。 */
    val RaisedElevation = 14.dp
    val RaisedAmbient = Color.Black.copy(alpha = 0.16f)
    val RaisedSpot = Color.Black.copy(alpha = 0.22f)
}

/**
 * 菜单定位。两条规则，按触发方式区分：
 *
 * - **点击控件触发**（外观、模型列表…）：贴在锚点下方，且**右边缘对齐锚点右边缘**。
 *   左对齐会让菜单从控件左侧"长出来"，和控件右对齐的布局（数值 + 箭头）对不上。
 * - **长按触发**：**跟手** —— 从手指落点弹出，而不是从控件左边缘。
 *
 * 横向越界钳回窗口内，下方放不下则翻到上方。
 */
private class AppMenuPositionProvider(
    private val gapPx: Int,
    /** 浮层内为阴影预留的空白（上下左右各一份，px）。 */
    private val bleedPx: Int,
    /** 长按落点（相对锚点，px）。非空即"跟手"模式。 */
    private val touchPoint: IntOffset?,
    /** 右对齐锚点右边缘。 */
    private val alignEnd: Boolean,
    /** 回报菜单最终落在落点的**哪一侧**（true = 被翻到了上方），用于选缩放支点。 */
    private val onSide: (above: Boolean) -> Unit = {},
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // popupContentSize 含 bleed，先还原出**卡片本身**的尺寸
        val cardWidth = popupContentSize.width - bleedPx * 2
        val cardHeight = popupContentSize.height - bleedPx * 2

        // 让卡片连同阴影一起留在窗口内
        val minX = bleedPx
        val maxX = (windowSize.width - cardWidth - bleedPx).coerceAtLeast(minX)
        val minY = bleedPx
        val maxY = (windowSize.height - cardHeight - bleedPx).coerceAtLeast(minY)

        // ── 横向 ───────────────────────────────────────────
        /*
         * 长按跟手时**以落点为水平中心**。
         *
         * 早先是"左边缘对齐落点"，于是菜单整个挂在手指右边，
         * 而缩放的支点又取自身中线 —— 两个"中心"差着半个菜单宽，
         * 看起来就像"从菜单左边长出来"。居中之后，菜单中线 = 落点 = 支点。
         */
        val desiredX = when {
            touchPoint != null -> anchorBounds.left + touchPoint.x - cardWidth / 2
            alignEnd -> anchorBounds.right - cardWidth
            else -> anchorBounds.left
        }
        val cardX = desiredX.coerceIn(minX, maxX)

        // ── 纵向：优先在下方 ────────────────────────────────
        val belowY = when {
            touchPoint != null -> anchorBounds.top + touchPoint.y + gapPx
            else -> anchorBounds.bottom + gapPx
        }
        val cardY = if (belowY + cardHeight <= maxY + bleedPx) {
            belowY
        } else {
            // 翻到上方：长按模式下以落点为准，否则以锚点顶部为准
            val aboveY = when {
                touchPoint != null -> anchorBounds.top + touchPoint.y - cardHeight - gapPx
                else -> anchorBounds.top - cardHeight - gapPx
            }
            aboveY
        }
        // 告诉上层"最后落在落点的哪一侧"（长按跟手时缩放支点要靠它）
        if (touchPoint != null) onSide(cardY != belowY)
        val clampedY = cardY.coerceIn(minY, maxY)

        return IntOffset(cardX - bleedPx, clampedY - bleedPx)
    }
}

/**
 * 触摸本区域时收起输入法与焦点，**不消费事件**。
 *
 * 用 `PointerEventPass.Initial` 读一眼就走：被点的控件（消息气泡、按钮）
 * 照常收到事件。若改用 `clickable`，会把落在这一层上的点击**吃掉**，
 * 气泡就点不动了。
 *
 * 挂在消息区、顶栏这类"非输入"区域上；**不要**挂在输入栏自身，
 * 否则点输入框反而会把键盘收掉。
 */
@Composable
fun Modifier.dismissKeyboardOnTouch(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            }
        }
    }
}

/**
 * 记录最近一次按下的落点（相对当前节点，px），**不消费事件**。
 *
 * `combinedClickable` 的 `onLongClick` 不提供坐标，所以只能自己旁听一次
 * 按下事件 —— 用 `PointerEventPass.Initial` 在 Main 之前读一眼就走，
 * 不 `consume()`，点击、水波纹、长按都照常工作。
 *
 * @return `first` 取当前落点，`second` 是必须挂到目标节点上的 Modifier
 */
@Composable
fun rememberPressPosition(): Pair<() -> IntOffset, Modifier> {
    val position = remember { mutableStateOf(IntOffset.Zero) }

    val listener = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) {
                    position.value = IntOffset(
                        x = change.position.x.roundToInt(),
                        y = change.position.y.roundToInt(),
                    )
                }
            }
        }
    }

    return ({ position.value }) to listener
}

/**
 * 菜单弹出 / 收起的时长。
 *
 * 一路收下来：140 → 110 → 70。理由写在 [AppMenu] 里 —— 弹出的第一帧
 * 就已经是可见的（55% 不透明），这 70ms 只是把它"补满"，不再承担
 * "从无到有"的职责。
 */
private const val MenuAnimMs = 70

/**
 * 圆角卡片菜单 —— **全应用唯一的菜单实现**。
 *
 * 刻意不用 Material3 的 `DropdownMenu`：
 * - 它内部会注入 `MenuDefaults.VerticalPadding`（8dp），我们改不掉，
 *   于是菜单顶部/底部总有一道多余的空白；
 * - 行高固定 48dp、宽度随内容浮动，抽屉和对话页两处就会长得不一样。
 *
 * 自绘 `Popup` 后这三件事都由我们说了算，抽屉与对话页的菜单因此能完全同形。
 *
 * ### 为什么 `focusable = false`
 *
 * 上一版是 `focusable = true`。代价在真机上很直观：**点一下模型胶囊，
 * 要等一秒多才看见浮层**。原因不是 Compose 慢，而是「可获焦的窗口」
 * 在 Android 上要抢焦点 —— 抢焦点会连坐三件事：输入法收起、Activity
 * 重新布局（消息列表跟着重排）、窗口切换动画。三步串起来正好是一秒多。
 *
 * 浮层里没有任何东西需要焦点：没有输入框，没有需要键盘的东西。所以
 * 改成不可获焦。点外部关闭靠 `FLAG_WATCH_OUTSIDE_TOUCH` 送来的
 * ACTION_OUTSIDE，与焦点无关；返回键另用 `BackHandler` 接（见下）。
 *
 * ### 为什么需要一个 `mounted`
 *
 * `Popup` 是**独立窗口**，原先的 `if (!expanded) return` 会让它在一帧内消失，
 * 根本没有做退出动画的余地。这里多留一拍：`expanded` 变 false 后先把退出动画
 * 播完，再把 Popup 摘掉。
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = MenuWidth,
    /** 长按触发时传落点（px，相对锚点）→ 菜单跟手弹出。 */
    touchPoint: IntOffset? = null,
    /** 点击控件触发时默认右对齐锚点右边缘。 */
    alignEnd: Boolean = true,
    /**
     * 是否让浮层窗口可获焦。
     *
     * 默认 `false`：多数菜单只有文字行，**不可获焦**才能做到"点外面就关、
     * 且不抢输入框焦点"。但里面一旦放了 `BasicTextField`，不可获焦的窗口
     * 就收不到输入法连接 —— 表现为"输入框在闪、键盘不出来、字打不进去"
     * （工作区浮层里"新建内置文件夹"那一格就是这样）。
     *
     * 所以由调用方声明：**有输入框的那张必须传 true**。
     */
    focusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val bleedPx = with(density) { MenuShadowBleed.roundToPx() }

    /*
     * 支点的 y 要跟着"菜单落在落点上方还是下方"走 —— 位置提供者才知道，
     * 所以用一个普通容器接它回报的值。
     *
     * 刻意**不用** Compose 状态：`graphicsLayer` 的 lambda 每帧都会重跑
     * （`progress.value` 在变），届时自然会读到最新值；而写成状态会在
     * layout 期间触发重组，得不偿失。
     */
    val aboveAnchor = remember { booleanArrayOf(false) }

    val positionProvider = remember(gapPx, bleedPx, touchPoint, alignEnd, aboveAnchor) {
        AppMenuPositionProvider(gapPx, bleedPx, touchPoint, alignEnd) { above ->
            aboveAnchor[0] = above
        }
    }

    var mounted by remember { mutableStateOf(expanded) }
    val progress = remember { Animatable(0f) }

    /*
     * 返回键。`focusable = false` 之后 Popup 收不到 KEYCODE_BACK，
     * 只能由宿主窗口的返回分发来接。enabled 只在展开时为真 ——
     * 否则十几张未展开的浮层会各自抢一次返回键。
     */
    BackHandler(enabled = expanded) { onDismissRequest() }

    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            /*
             * **不再等待帧。**
             *
             * 等帧的初衷是"让窗口先上屏再播动画"，但窗口上屏本来就在
             * 这一帧的 measure/layout 里完成；多等一帧只是让用户多看
             * 一帧空白。现在改为：立刻把进度推到 0.7（此时已经 85% 不透明），
             * 剩下的 70ms 用来补满。视觉上是"点下去就出现"。
             */
            progress.snapTo(0.7f)
            progress.animateTo(1f, tween(MenuAnimMs, easing = MotionEasing))
        } else {
            // 收起比展开更快：关的时候用户已经在看别处了，多留一帧都是拖
            progress.animateTo(0f, tween(90, easing = MotionEasing))
            mounted = false
        }
    }

    if (!mounted) return

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            /*
             * 不可获焦，见类注释。`dismissOnBackPress` 随之失效，
             * 由上面的 BackHandler 接管。
             */
            focusable = focusable,
            dismissOnClickOutside = true,
            dismissOnBackPress = false,
        ),
    ) {
        Column(
            modifier = modifier
                // 先留出阴影的绘制空间：Popup 的窗口会裁掉超出内容的部分，
                // 不预留的话阴影就在边缘被切掉一道
                .padding(MenuShadowBleed)
                .width(width)
                .graphicsLayer {
                    /*
                     * 只到 1f 为止 —— Popup 的窗口就是内容尺寸，放大只会被裁掉。
                     *
                     * 起点从 0.6/alpha 0 提到 0.94/alpha 0.85：第一帧就得是
                     * "已经在那里"的样子。真正从 0 起手的那一帧是完全透明的，
                     * 即使只有 16ms，叠上窗口创建也会被感知成"点了没反应"。
                     */
                    val s = 0.94f + 0.06f * progress.value
                    scaleX = s
                    scaleY = s
                    alpha = 0.85f + 0.15f * progress.value
                    /*
                     * 支点 = **贴着手指的那个角**。
                     *
                     * 纵向：看菜单位于落点的**哪一侧** —— 在下方取 0（顶），
                     * 被翻到上方取 **1（底）**，只有底边才贴着手指。
                     *
                     * 横向：**取中点**，不跟手指。
                     * 跟手时菜单左边缘虽起于落点，但它挺宽，长按又多在右半屏
                     * （消息气泡右对齐），`desiredX` 会被 `coerceIn` 钳回窗口内 ——
                     * 左边缘离手指可能差几百像素，拿它当支点必然"左右不对"。
                     * 菜单宽度固定、位置被钳过，横向支点本来就没有可靠信息，
                     * 索性从中间缩放。（点击触发的菜单贴着锚点右缘，仍取右上。）
                     */
                    transformOrigin = TransformOrigin(
                        if (touchPoint != null) 0.5f else 1f,
                        if (touchPoint != null && aboveAnchor[0]) 1f else 0f,
                    )
                }
                .shadow(
                    elevation = AppShadow.RaisedElevation,
                    shape = MenuShape,
                    clip = false,
                    ambientColor = AppShadow.RaisedAmbient,
                    spotColor = AppShadow.RaisedSpot,
                )
                .clip(MenuShape)
                .background(colors.menuBackground)
                .border(1.dp, colors.outline, MenuShape)
                // 总上下内边距只有 4dp，之前是 6 + Material 的 8
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

/** 菜单项：图标 + 文字，固定 [MenuItemHeight] 行高。 */
@Composable
fun AppMenuItem(
    label: String,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    /** 单选菜单里的选中态：右侧显示品牌色勾。 */
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val contentColor = if (destructive) colors.danger else MaterialTheme.colorScheme.onSurface

    // 左右各留 4dp，水波纹才是圆角块而不是顶到卡片圆角上
    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MenuItemHeight)
                .clip(SoftShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = label,
                /*
                 * 15sp。拆包量出来的：官方菜单"文件管理"四字宽 170px，
                 * 除以 2.75 的 density 正好 15.5，取 15 比 16 更贴。
                 */
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = true：文字吃掉剩余空间，勾才会被顶到最右边
                modifier = Modifier.weight(1f),
            )
            AppMenuCheck(selected = selected)
        }
    }
}

/**
 * 菜单分组分隔线。
 *
 * 用法固定：**接在危险操作之前**。这样「有没有版本」「有几个版本」
 * 这类数据差异不会改变菜单的结构。
 */
@Composable
fun AppMenuDivider() {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(1.dp)
            .background(colors.divider),
    )
}

/** 菜单里的选中勾（用于外观 / 模型等单选菜单）。 */
@Composable
fun AppMenuCheck(selected: Boolean) {
    Box(modifier = Modifier.size(17.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = AppIcons.Check,
            contentDescription = null,
            /*
             * 选中态用 onSurface，不用品牌色。
             *
             * 蓝勾在灰白菜单里是最跳的一笔，看起来像"可点链接"；而这里要说的是
             * "当前是这一项"，一颗中性的勾已经够了 —— 官方客户端的单选也是这个口径。
             */
            tint = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
            modifier = Modifier.size(15.dp),
        )
    }
}


