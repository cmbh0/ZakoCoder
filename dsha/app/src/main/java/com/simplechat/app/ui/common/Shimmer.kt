package com.simplechat.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 运行中的「流光」。
 *
 * ### 为什么不是呼吸
 *
 * 呼吸（透明度 0.55 ↔ 1.0）说明的是"这东西还活着"，但一眼看上去更像是
 * 字在闪 —— 尤其在两行灰字并排时（工具名 + 摘要），两段一起明暗，
 * 观感是整块区域在抖。流光是一条高光从左到右扫过：**方向明确**，
 * 不改变字的位置和粗细，眼睛读到的是"还在往前跑"。
 *
 * ### 为什么高光不直接用白色
 *
 * 浅色主题下白字高光等于看不见。高光统一取「基色 → 正文色」的插值：
 * 深色下正文近白（变亮），浅色下正文近黑（变深）。两套主题都是"更清楚"，
 * 而不是"更白"。
 */
@Composable
fun rememberShimmerBrush(
    active: Boolean,
    base: Color,
    periodMs: Int = ShimmerPeriodMs,
    /** 高光强度 0..1。整段正文用低强度，标题/工具名用高强度。 */
    strength: Float = 0.82f,
): Brush {
    if (!active) return SolidColor(base)

    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPhase",
    )

    /*
     * 用屏幕宽度当扫光行程。
     *
     * 拿不到文字自身的宽度（TextStyle.brush 的坐标是绝对像素），
     * 而宽度要等排版后才知道 —— 那会把动画拖到第二帧。
     * 屏幕宽足够覆盖任何一行，且不同长度的文字只是"被扫过的时间不同"，
     * 看不出起点不一致。
     */
    val widthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val highlight = lerp(base, MaterialTheme.colorScheme.onSurface, strength)
    val band = widthPx * 0.30f
    val head = -band + (widthPx + band * 2f) * phase

    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(head - band, 0f),
        end = Offset(head + band, 0f),
    )
}

/**
 * 一行会流光的灰字。
 *
 * 只在 [active] 时每帧重组 —— 重组范围限于这个叶子节点，
 * 不会把上面的标题行、卡片一起拖进每帧重组。
 */
@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /**
     * 温和一档：高光更淡、扫得更慢。
     *
     * 用在**长篇正文**上（思考尾巴）。同样的强度放在一整段字上会像"字在发亮"，
     * 而不是"有一条光扫过" —— 强度降下来、周期拉长，才接近纸张反光的感觉。
     */
    gentle: Boolean = false,
) {
    if (!active) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }
    val brush = rememberShimmerBrush(
        active = true,
        base = color,
        periodMs = if (gentle) GentleShimmerPeriodMs else ShimmerPeriodMs,
        strength = if (gentle) 0.5f else 0.82f,
    )
    Text(
        text = text,
        style = style.copy(brush = brush),
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 流光一个来回的时长。1.6s：看得出在动，又不至于晃眼。 */
private const val ShimmerPeriodMs = 1_600

/** 正文用的慢速流光。 */
private const val GentleShimmerPeriodMs = 2_600
