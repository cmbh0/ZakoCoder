package com.simplechat.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.ui.common.ShimmerText
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 压缩分界线要显示的全部事实。
 *
 * 单独抽一个不可变类型，而不是给 [MessageList] 加六个参数：
 * 分界线只在一处渲染，但它读的是「压缩状态 + 上下文统计 + 覆盖条数」三份数据，
 * 拆成散参数之后调用方每加一个字段都要改一遍签名。
 */
@Immutable
data class CompactionDividerState(
    /** 分界线挂在哪条消息之后 —— 也就是摘要覆盖到的最后一条。 */
    val anchorId: String,
    /** 被摘要覆盖的本地消息条数（含首条）。 */
    val coveredCount: Int,
    /** 压缩后下一次请求实际会占用的 token。 */
    val usedTokens: Int,
    /** 模型窗口，0 = 未知。 */
    val windowTokens: Int,
    /** 压缩省下的 token；不是正数时不显示"前后对比"。 */
    val savedTokens: Int,
    val compacting: Boolean,
)

/**
 * 上下文压缩分界线。
 *
 * ### 它是什么
 *
 * 一条横穿消息流的细线，把「已被摘要的对话」与「仍按原文发送的对话」分开。
 * 位置由 `throughMessageId` 精确锚定 —— 不是输入栏上方那种飘着的提示条，
 * 用户随时能看见"从这里往上，模型记住的是摘要"。
 *
 * ### 为什么全是灰的
 *
 * 之前完成态整行转红，理由是"让重写这件事一眼可见"。真机上看着不对：
 * 聊天流里的红色是**错误**的意思（发送失败、工具失败都用它），而压缩是
 * 一次成功的维护动作 —— 用户看到红字的第一反应是"出什么事了"，
 * 第二反应是"这灰白灰白的什么鬼"（11sp 小字 + 0.72 透明度的红，在深色
 * 背景上就是一层发灰的粉）。
 *
 * 现在只有灰阶，与思考区、工具行同一套语言：**过程是灰的，结论是正文色**。
 * 进行中靠灰字流光表示"还在动"，不再挂那颗转圈的环 —— 那颗环属于输入栏
 * 的上下文占用，放在这里既重复又抢眼。
 */
@Composable
fun CompactionDivider(
    state: CompactionDividerState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    /*
     * 线的颜色比正文淡一档：它是分隔，不是内容。
     * `processBar` 是过程区那条竖线用的灰，同一族，视觉上不会打架。
     */
    val line = colors.processBar.copy(alpha = 0.30f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            // 两种状态的行数不同（进行中一行、完成两行）：让高度自己长，别整屏弹一下
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientRule(
                modifier = Modifier.weight(1f),
                color = line,
                fadeToLeft = true,
            )
            Spacer(Modifier.width(10.dp))

            if (state.compacting) {
                /*
                 * 进行中：灰字流光。
                 *
                 * 不用 CircularProgressIndicator / SnakeProgressRing：
                 * 一次压缩通常十几秒，一圈环在这段时间里除了"在转"什么都
                 * 说明不了；文字已经把事说清楚了，流光负责"它还活着"。
                 */
                ShimmerText(
                    text = stringResource(R.string.compact_divider_running),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.processText,
                    active = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.compact_divider_done, state.coveredCount),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.processText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))
            GradientRule(
                modifier = Modifier.weight(1f),
                color = line,
                fadeToLeft = false,
            )
        }

        if (!state.compacting) {
            Spacer(Modifier.height(3.dp))
            /*
             * 第二行只写数字，不写句子。
             *
             * "压缩省下约 N tokens" 这类完整句在分界线里会挤掉两侧的横线，
             * 而读者在聊天流里扫一眼只需要"多少 → 多少"。
             */
            Text(
                text = if (state.savedTokens > 0) {
                    stringResource(
                        R.string.compact_divider_tokens,
                        TokenEstimate.format(state.usedTokens + state.savedTokens),
                        TokenEstimate.format(state.usedTokens),
                    )
                } else {
                    stringResource(
                        R.string.compact_divider_usage,
                        TokenEstimate.format(state.usedTokens),
                        TokenEstimate.format(state.windowTokens),
                    )
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                // 比主行再淡一档：数字是注释，不是标题
                color = colors.placeholder,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 一条从中间向外淡出的细线。
 *
 * 用渐变而不是整条实线：分界线两端不该有"切开屏幕"的硬边，
 * 它只是把一段对话和下一段轻轻分开。
 */
@Composable
private fun GradientRule(
    modifier: Modifier,
    color: Color,
    fadeToLeft: Boolean,
) {
    val colors = listOf(
        // 透明端用同色 alpha=0，而不是 Color.Transparent：后者是黑色透明，
        // 渐变中段会掺进一层灰。
        if (fadeToLeft) color.copy(alpha = 0f) else color,
        if (fadeToLeft) color else color.copy(alpha = 0f),
    )
    Spacer(
        modifier = modifier
            .height(1.dp)
            .background(Brush.horizontalGradient(colors)),
    )
}
