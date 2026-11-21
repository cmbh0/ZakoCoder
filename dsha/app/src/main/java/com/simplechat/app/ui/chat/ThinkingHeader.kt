package com.simplechat.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ShimmerText
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 思考栏的**唯一**标题行 —— 两个协议通道共用。
 *
 * ### 为什么要有这个文件
 *
 * 同一件事（"思考过没有、用了多久"）原先在两个面板里各写了一套：
 * 扩展通道（`TimelinePanel`）是「🧠 已思考（用时 3 秒）」，OpenAI 通道
 * 的旧思考卡是「已深度思考 · 3 秒」且没有图标、左边还差 8dp。
 * 同一个 App 里同一行信息两种长相，看起来像两个功能。
 *
 * 现在措辞、图标、间距、点击热区只有一份（[thinkingLabel] 管措辞）。
 * 两个面板各自的**差异**只留在该留的地方：扩展通道多出搜索/浏览步骤，
 * OpenAI 通道只有纯思维链。
 *
 * @param label    标题文案，用 [thinkingLabel] 生成。
 * @param enabled  能否点开折叠区。
 * @param running  还在跑 —— 标题走流光、图标亮主色。**只要模型没停就一直亮**，
 *                 不是只在第一段思维链里亮：工具调用之间的空档也是"它在干活"。
 */
@Composable
internal fun ThinkingHeader(
    label: String,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    running: Boolean = false,
) {
    val colors = LocalChatColors.current

    /*
     * 箭头用一个图标 + 旋转，而不是两个图标硬切。
     *
     * 硬切的中间帧是"上箭头消失、下箭头出现"，两个不同形状在一帧内替换，
     * 眼睛看到的是闪。旋转则是同一个形状转过去，和开关滑块是同一类动作。
     */
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "thinkingChevron",
    )

    /*
     * 热区是**整行**，不是"文字宽度 + 8dp"。
     *
     * 上一版只有那行字本身能点：收起时它只有 60dp 宽，右边全是死的 ——
     * 手指按在字的右侧一点点就没反应，感受上就是"点了不展开"。
     * 现在整行铺满（`fillMaxWidth`），点击落在哪都算。
     */
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        /*
         * 标题左边**不挂图标**。
         *
         * 上一版在这里放过一颗 Sparkles，理由是想让"想法"和"答案"一眼可分；
         * 但官方 Chat Demo 的思考栏就是一行字，前面什么都没有。星星法杖
         * 还带一层"魔法/生成"的暗示，和"模型在推理"不是一回事。删掉之后
         * 标题与正文共用同一条左边界，块更像一块文字，而不是一个功能入口。
         */
        ShimmerText(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            active = running,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (enabled) {
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.process_collapse else R.string.process_expand,
                ),
                tint = colors.processText.copy(alpha = if (running) 0.9f else 0.65f),
                modifier = Modifier
                    .size(15.dp)
                    .rotate(chevron),
            )
        }
    }
}

/**
 * 思考栏标题文案 —— **两个通道共用这一套措辞**。
 *
 * 改为 `Res.get` 而不是 `stringResource`：调用点要传字符串给 [ThinkingHeader]，
 * 而在组合外取字符串更省事（[Res.get] 内部走 `LocalContext`，见它的实现）。
 */
internal fun thinkingLabel(
    running: Boolean,
    seconds: Long?,
    stopped: Boolean = false,
): String = when {
    running -> Res.get(R.string.thinking_running)
    stopped -> Res.get(R.string.thinking_stopped)
    seconds != null -> Res.get(R.string.thinking_done_seconds, seconds)
    else -> Res.get(R.string.thinking_done)
}
