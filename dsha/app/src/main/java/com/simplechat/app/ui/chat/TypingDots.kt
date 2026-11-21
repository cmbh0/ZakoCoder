package com.simplechat.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 「正在打字」的三个跳动圆点。
 *
 * ### 为什么必须有
 *
 * 请求发出去到第一个字到达之间有一段**空窗**，长思考模型这里是几秒到十几秒。
 * 原来这段时间界面上什么都不画 —— 用户看到的是"点了发送，没反应"，
 * 然后正文整段蹦出来，观感像"没有流式"。
 *
 * 三个点是这一步的最小信号：它不承诺进度，只说明"在生成"。
 * 一旦正文有字，它就该消失（由调用方判断 `answer.isBlank()`）。
 *
 * 错峰用 `delayMillis = i * 120` 而不是自己算相位：三点同频跳看着像呼吸灯，
 * 错开才是"打字"。`Reverse` 让透明度在原位往返，不产生位移抖动。
 */
@Composable
internal fun TypingDots(modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = index * 130,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "typingDot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(colors.placeholder.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

/** 给占位用的统一内边距：三个点的视觉重心与正文首行对齐。 */
@Composable
internal fun TypingDotsRow(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(vertical = 8.dp)) {
        TypingDots()
    }
}
