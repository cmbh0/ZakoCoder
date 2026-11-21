package com.simplechat.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.LocalChatColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「贪吃蛇」进度环。
 *
 * 和普通进度环的区别只在**末端那颗头**：弧线顶端一个圆点，随着进度沿着圆周爬。
 * 这一点点差别带来的观感变化很大 —— 普通环是"填了多少"，有头的环是"走到哪了"，
 * 后者才像一个正在发生的过程。
 *
 * 进度没到 100% 时，弧线永远差一截才闭环，所以视觉上确实是"一条蛇在绕圈"。
 */
@Composable
fun SnakeProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 4.dp,
    showPercent: Boolean = true,
    /** 不确定进度时用：头一直在转，不给百分比。 */
    indeterminate: Boolean = false,
    /**
     * 弧与头的颜色。
     *
     * 默认跟主题主色；压缩分界线这类**状态色**需要单独指定（压缩完成是红色），
     * 不能因为换主题色就把"刚刚压缩过"的信号也换掉。
     */
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val colors = LocalChatColors.current
    val primary = color

    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420),
        label = "snake-progress",
    )

    // 不确定态：头自己转，比"停着不动"诚实得多
    val spin = rememberInfiniteTransition(label = "snake-spin")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "snake-spin-angle",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val radius = arcSize.width / 2f
            val centerX = this.size.width / 2f
            val centerY = this.size.height / 2f

            drawArc(
                color = colors.outline,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            val sweep = if (indeterminate) 96f else animated * 360f
            val start = if (indeterminate) spinAngle - 90f else -90f

            if (sweep > 0.5f) {
                drawArc(
                    color = primary,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            /*
             * 头。角度用「起始角 + 已扫角度」算，注意 canvas 的 0° 在 3 点方向、
             * 顺时针为正，而数学坐标是 0° 在 3 点、逆时针为正 —— 所以 sin 前面
             * 要取负，否则头会走在弧的反方向（第一版就是这么错的）。
             */
            if (sweep > 0.5f) {
                val endAngle = start + sweep
                val radians = endAngle * PI / 180.0
                val headX = centerX + radius * cos(radians).toFloat()
                val headY = centerY - radius * sin(radians).toFloat()
                // 光晕 + 实心点：小尺寸下只有实心点会显得"粘在线上"
                drawCircle(color = primary.copy(alpha = 0.18f), radius = stroke * 2.1f, center = Offset(headX, headY))
                drawCircle(color = primary, radius = stroke * 1.15f, center = Offset(headX, headY))
            }
        }

        if (showPercent && !indeterminate) {
            Text(
                text = "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
