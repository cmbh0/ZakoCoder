package com.simplechat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.ProcessTextStyle

/**
 * 过程流里的一步。
 *
 * 「一步」不是「一条消息」—— 一条 AI 回复里有多次往返，真实顺序是
 *
 * ```
 * 思考 → 说话 → 工具 → 思考 → 说话 → 工具 → … → 结论
 * ```
 *
 * 旧渲染把每一段思考都提到最上面、再按「第 N 次思考」编号，等于把顺序
 * 打乱后重新编号：读者看到的是「想了三次，然后做了三件事」，而不是
 * 「想完就做、做完再想」。这里按发生顺序排开，思考与工具各自留在原位。
 */
sealed interface ProcessStep {

    /** 列表 key：流式期间每一步的位置不能因为前面的文本变长而重建。 */
    val key: String

    /**
     * 一段思考。
     *
     * [active] 只可能是最后一段 —— 只有它还在写。前面的段落一律静态灰字，
     * 不为它们挂动画（一屏三段同时呼吸，看起来像三个进度条）。
     */
    data class Thinking(
        val index: Int,
        val text: String,
        val active: Boolean,
    ) : ProcessStep {
        override val key: String get() = "thinking-$index"
    }

    /** 过程里的一句说明（「我先看一下这个文件」）。不是最终结论。 */
    data class Text(
        val text: String,
        override val key: String,
    ) : ProcessStep

    /** 一串连续的调用。同一条消息里可以出现多串，被思考或说明隔开。 */
    data class Tools(
        val calls: List<ToolCallUi>,
        override val key: String,
    ) : ProcessStep
}

/**
 * 一条 AI 回复拆出来的「过程」与「结论」。
 *
 * [answer] 单独拿出来：它不属于过程区，不该跟着思考一起折叠，也不该落进
 * 过程区那段灰字里。过程区只放「它是怎么做到的」。
 */
data class ProcessFlow(
    val steps: List<ProcessStep> = emptyList(),
    val answer: String = "",
    val answerStreaming: Boolean = false,
    val hasThinking: Boolean = false,
    val thinkingStreaming: Boolean = false,
    val thinkingSeconds: Long? = null,
    val stopped: Boolean = false,
) {
    val hasProcess: Boolean get() = steps.isNotEmpty()
}

/**
 * 把落库的四个字段还原成**有序**的过程流。
 *
 * ### 顺序从哪里来
 *
 * 只靠正文偏移（[ToolCallUi.afterChars]）排不出多轮：第二轮开始时正文可能
 * 还是空的，偏移与第一轮开头撞在一起。所以发送侧另外记了
 * [ToolCallUi.round]、[ToolCallUi.roundStartChars] 与 [ToolCallUi.reasoningIndex]：
 *
 * ```
 * 第 r 轮： 思考[ridx] → 正文（本轮起点到工具起点）→ 工具
 * ```
 *
 * 老数据（`round < 0`）没有这些坐标，退回旧排法：思考全在上、正文按
 * `afterChars` 切段。行为与升级前一致，不会把历史消息排乱。
 */
fun buildProcessFlow(
    content: String,
    reasoning: String?,
    calls: List<ToolCallUi>,
    streaming: Boolean,
    reasoningStreaming: Boolean,
    thinkingSeconds: Long?,
    stopped: Boolean,
): ProcessFlow {
    /*
     * ⚠️ **不能先 filter 再按索引取**。
     *
     * `reasoningIndex` 是发送侧按"这一轮有没有思考"数的段号。某一轮的思考
     * 恰好只有空白（模型只吐了一个换行就调工具）时，filter 会把这一段删掉，
     * 后面所有段号左移一位 —— 于是第二轮的思考被挂到第一轮的工具下面。
     * 这里保留空段占位，渲染时再跳过。
     */
    val segments = reasoning.orEmpty()
        .split(ReasoningSeparator)
        .map { it.trim() }
    val lastThinking = segments.indexOfLast { it.isNotEmpty() }

    val steps = ArrayList<ProcessStep>(segments.size + calls.size * 2)
    var thinkCount = 0
    var textCount = 0

    fun addThinking(index: Int) {
        if (index < 0 || index !in segments.indices || segments[index].isEmpty()) return
        steps += ProcessStep.Thinking(
            index = thinkCount++,
            text = segments[index],
            active = reasoningStreaming && index == lastThinking,
        )
    }

    if (calls.isEmpty()) {
        segments.indices.forEach { addThinking(it) }
        return ProcessFlow(
            steps = steps,
            answer = content.trim('\n'),
            answerStreaming = streaming && !reasoningStreaming,
            hasThinking = lastThinking >= 0,
            thinkingStreaming = reasoningStreaming && lastThinking >= 0,
            thinkingSeconds = thinkingSeconds,
            stopped = stopped,
        )
    }

    var cursor = 0
    fun addText(until: Int) {
        val cut = until.coerceIn(cursor, content.length)
        if (cut <= cursor) return
        val chunk = content.substring(cursor, cut).trim('\n')
        cursor = cut
        if (chunk.isNotBlank()) steps += ProcessStep.Text(chunk, "text-${textCount++}")
    }

    val legacy = calls.any { it.round < 0 }
    val runs = ArrayList<MutableList<ToolCallUi>>()
    calls.forEach { call ->
        val last = runs.lastOrNull()
        val sameRun = last != null && if (legacy) {
            // 老数据没有轮次：同一次工具批次的 afterChars 相同
            last.first().afterChars == call.afterChars
        } else {
            last.first().round == call.round
        }
        if (sameRun) last.add(call) else runs += mutableListOf(call)
    }

    if (legacy) {
        // 升级前的排版：所有思考在上，正文按工具偏移切段
        segments.indices.forEach { addThinking(it) }
        runs.forEach { run ->
            val first = run.first()
            addText(first.afterChars)
            steps += ProcessStep.Tools(run.toList(), "tools-${first.id}")
        }
    } else {
        val used = BooleanArray(segments.size)
        runs.forEach { run ->
            val first = run.first()
            val seg = first.reasoningIndex
            if (seg in segments.indices && !used[seg]) {
                used[seg] = true
                addThinking(seg)
            }
            /*
             * 本轮正文的起点来自 `roundStartChars`，终点是本轮第一次调用的
             * `afterChars`。中间这段就是模型在动手之前说的话。
             */
            addText(first.roundStartChars)
            addText(first.afterChars)
            steps += ProcessStep.Tools(run.toList(), "tools-${first.id}")
            cursor = maxOf(cursor, first.afterChars.coerceIn(0, content.length))
        }
        /*
         * 最后一轮没有工具，它的思考只能排在所有工具之后 —— 这正是
         * 「看完工具返回、再想一遍、再给结论」的真实顺序。
         */
        segments.indices.forEach { if (!used[it]) addThinking(it) }
    }

    return ProcessFlow(
        steps = steps,
        answer = content.substring(cursor.coerceIn(0, content.length)).trim('\n'),
        answerStreaming = streaming && !reasoningStreaming,
        hasThinking = lastThinking >= 0,
        thinkingStreaming = reasoningStreaming && lastThinking >= 0,
        thinkingSeconds = thinkingSeconds,
        stopped = stopped,
    )
}

/**
 * 过程区：一个总标题 + 按真实顺序排开的思考 / 说明 / 工具。
 *
 * ### 为什么不再是一张卡片、一条顶边流光
 *
 * 卡片把「过程」与「正文」隔成两个盒子，多轮工具时屏幕上会叠出四五张
 * 长得一样的卡；顶边那条主色流光更像进度条，和本项目的灰阶过程语言不是
 * 一回事。现在连竖线与圆点也一并去掉，只剩标题 + 缩进都没有的灰字 ——
 * 这正是 DeepSeek 官方 Chat Demo 的思考块：过程是一条线性的文字记录，
 * 不是一组卡片，也不该被装饰成时间轴。
 *
 * 标题只出现**一次**，写总用时；不再有「第 N 次思考」。
 */
@Composable
fun ProcessTimeline(
    flow: ProcessFlow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    running: Boolean,
    modifier: Modifier = Modifier,
    onPreview: (String) -> Unit = {},
) {
    /*
     * 流式期间允许临时收起（模型有时会连想几分钟，那一段一直占着半屏）。
     * 定稿后的展开状态仍由设置与用户点击决定（`expanded`）。
     */
    var userCollapsed by remember { mutableStateOf(false) }
    val anyActive = flow.steps.any { it is ProcessStep.Thinking && it.active }
    /*
     * 两种"展开"：
     *
     * - `showAll`：用户要看的（设置默认 + 点击），所有段落都展开
     * - `activeVisible`：正在写的那一段强制展开
     *
     * 分开是为了避免"新一轮开始思考时，前几轮的思考全部重新铺开" ——
     * 那样每进入一轮，上面所有内容都会往下跳一次。已写完的段落只在
     * `showAll` 时出现。
     */
    val showAll = expanded && !userCollapsed
    val activeVisible = anyActive && !userCollapsed
    val open = showAll || activeVisible

    Column(modifier = modifier.fillMaxWidth()) {
        if (flow.hasThinking) {
            ThinkingHeader(
                label = processTitle(flow),
                expanded = open,
                enabled = true,
                onToggle = {
                    when {
                        !open -> {
                            userCollapsed = false
                            if (!expanded) onToggleExpand()
                        }
                        activeVisible && !showAll -> userCollapsed = true
                        else -> {
                            userCollapsed = true
                            if (expanded) onToggleExpand()
                        }
                    }
                },
                // 标题流光只在"真的在干活、还没开始写答案"时亮
                running = flow.thinkingStreaming || (running && flow.answer.isBlank()),
            )
            Spacer(Modifier.height(2.dp))
        }

        if (flow.steps.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                flow.steps.forEach { step ->
                    key(step.key) {
                        when (step) {
                            is ProcessStep.Thinking -> AnimatedVisibility(
                                visible = showAll || (step.active && activeVisible),
                                enter = expandVertically(tween(180)) + fadeIn(tween(120)),
                                exit = shrinkVertically(tween(160)) + fadeOut(tween(90)),
                            ) {
                                ThinkingBody(
                                    text = step.text,
                                    active = step.active,
                                )
                            }

                            is ProcessStep.Text -> Text(
                                text = step.text,
                                style = ProcessTextStyle,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            is ProcessStep.Tools -> ToolRunStrip(
                                calls = step.calls,
                                onPreview = onPreview,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 过程标题：进行中、被中断、已结束三种话，一套措辞。 */
private fun processTitle(flow: ProcessFlow): String = when {
    flow.thinkingStreaming -> Res.get(R.string.thinking_running)
    flow.stopped && flow.answer.isBlank() -> Res.get(R.string.thinking_stopped)
    flow.thinkingSeconds != null -> Res.get(R.string.thinking_done_seconds, flow.thinkingSeconds)
    else -> Res.get(R.string.thinking_done)
}

/**
 * 一段思考：**一栏灰字，左边什么都不挂**。
 *
 * 官方 Chat Demo 的思考块没有圆点、没有竖线、没有图标 —— 装饰越多，
 * 越像在说"这里有个进度条"。过程区只靠「标题一行 + 正文一段」与答案
 * 区分，剩下的交给灰度与字号。
 *
 * 正在写的那段高度封顶（约 12 行），底部对齐、上方渐隐 —— 思考写到几千字时
 * 整屏都是灰字会把正文挤没；封顶之后它更像一扇"日志窗口"，最新的字从下往上走。
 * 定稿后展开则是全文，不再裁切。
 */
@Composable
private fun ThinkingBody(
    text: String,
    active: Boolean,
) {
    val colors = LocalChatColors.current
    Box(modifier = Modifier.fillMaxWidth()) {
        // 流式时只排尾巴那一段；定稿后展开是全文
        val tail = remember(text) { visibleTailOf(text) }
        val body = if (active) tail else text
        if (!active) {
            Text(
                text = body,
                style = ProcessTextStyle.copy(lineHeight = 22.sp),
                color = colors.processText,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Box
        }
        var contentPx by remember { mutableIntStateOf(0) }
        val maxPx = with(LocalDensity.current) { ThinkingTailHeight.toPx() }
        val overflowing = contentPx > maxPx + 1
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ThinkingTailHeight)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .fillMaxWidth()
                    .onSizeChanged { contentPx = it.height },
            ) {
                Text(
                    text = body,
                    style = ProcessTextStyle.copy(lineHeight = 22.sp),
                    color = colors.processText,
                )
            }
            // 上方渐隐：只在真的被裁过时出现，否则会把第一行洗淡
            if (overflowing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.pageBackground, Color.Transparent),
                            ),
                        ),
                )
            }
        }
    }
}

/** 流式思考的高度上限：约 12 行，超过就只留尾巴。 */
private val ThinkingTailHeight = 280.dp

/** 尾巴最多保留多少字符 —— 只排看得见的部分，避免每 50ms 重排整段思考。 */
private const val TailKeepChars = 2_400

/** 从行的边界切出末尾一段，切出来的每一行与整段渲染一致。 */
private fun visibleTailOf(reasoning: String): String {
    if (reasoning.length <= TailKeepChars) return reasoning
    val cut = reasoning.length - TailKeepChars
    val newline = reasoning.indexOf('\n', cut)
    return if (newline >= 0) reasoning.substring(newline + 1) else reasoning.substring(cut)
}
