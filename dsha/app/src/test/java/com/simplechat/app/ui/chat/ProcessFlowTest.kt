package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过程流的顺序。
 *
 * 这里钉的不是"好不好看"，而是**多轮 Agent 的真实顺序**：
 * 一条回复里可能来回好几次，每轮都是「先想 → 再说 → 再动手」。
 * 旧实现把每段思考都提到最上面编号，等于把顺序打乱后重排；
 * 这些用例保证思考永远落在它那一轮的工具上方、下一轮思考落在上一轮工具下方。
 */
class ProcessFlowTest {

    private fun call(
        id: String,
        after: Int = 0,
        round: Int = 0,
        roundStart: Int = 0,
        reasoningIndex: Int = 0,
    ) = ToolCallUi(
        id = id,
        name = "read_file",
        summary = "$id.md",
        afterChars = after,
        round = round,
        roundStartChars = roundStart,
        reasoningIndex = reasoningIndex,
    )

    private fun flow(
        content: String,
        reasoning: String?,
        calls: List<ToolCallUi> = emptyList(),
        streaming: Boolean = false,
    ) = buildProcessFlow(
        content = content,
        reasoning = reasoning,
        calls = calls,
        streaming = streaming,
        reasoningStreaming = false,
        thinkingSeconds = 8,
        stopped = false,
    )

    @Test
    fun `second round thinking sits below the first tool`() {
        val result = flow(
            content = "结论",
            reasoning = "想第一步\u001E看完结果再想一步",
            calls = listOf(call("a", after = 0, round = 0, reasoningIndex = 0)),
        )

        assertEquals(3, result.steps.size)
        assertEquals("想第一步", (result.steps[0] as ProcessStep.Thinking).text)
        assertEquals("a", (result.steps[1] as ProcessStep.Tools).calls.single().id)
        assertEquals("看完结果再想一步", (result.steps[2] as ProcessStep.Thinking).text)
        assertEquals("结论", result.answer)
    }

    @Test
    fun `thinking always precedes the words of its own round`() {
        val result = flow(
            content = "我先看看\n\n看完了，结论是 A",
            reasoning = "想第一步\u001E想第二步",
            calls = listOf(
                call("a", after = 4, round = 0, roundStart = 0, reasoningIndex = 0),
                call("b", after = 15, round = 1, roundStart = 6, reasoningIndex = 1),
            ),
        )

        assertEquals(
            listOf("think:想第一步", "text:我先看看", "tool:a", "think:想第二步", "text:看完了，结论是 A", "tool:b"),
            result.steps.map { describe(it) },
        )
        // 最后一次工具之后没有正文：结论为空，不是把工具前的说明重复一遍
        assertEquals("", result.answer)
    }

    @Test
    fun `a round with no thinking still keeps its text above its tool`() {
        val result = flow(
            content = "直接开始\n\n收工",
            reasoning = null,
            calls = listOf(call("a", after = 4, round = 0)),
        )

        assertEquals(
            listOf("text:直接开始", "tool:a"),
            result.steps.map { describe(it) },
        )
        assertEquals("收工", result.answer)
    }

    @Test
    fun `a tool from a round without thinking does not steal the first thinking segment`() {
        val result = flow(
            content = "结论",
            reasoning = "后来才想",
            calls = listOf(
                call("a", after = 0, round = 0, reasoningIndex = -1),
                call("b", after = 0, round = 1, reasoningIndex = 0),
            ),
        )

        assertEquals(
            listOf("tool:a", "think:后来才想", "tool:b"),
            result.steps.map { describe(it) },
        )
    }

    @Test
    fun `thinking without tools becomes a process step above the answer`() {
        val result = flow(content = "答案", reasoning = "只想了一步")

        assertEquals(1, result.steps.size)
        assertTrue(result.steps.single() is ProcessStep.Thinking)
        assertEquals("答案", result.answer)
        assertTrue(result.hasThinking)
    }

    @Test
    fun `only the last thinking segment is active while streaming`() {
        val result = buildProcessFlow(
            content = "",
            reasoning = "第一段\u001E第二段",
            calls = listOf(call("a", after = 0, round = 0, reasoningIndex = 0)),
            streaming = true,
            reasoningStreaming = true,
            thinkingSeconds = null,
            stopped = false,
        )

        val thinking = result.steps.filterIsInstance<ProcessStep.Thinking>()
        assertFalse(thinking[0].active)
        assertTrue(thinking[1].active)
        assertTrue(result.thinkingStreaming)
    }

    @Test
    fun `legacy records keep the old layout instead of scrambling history`() {
        val result = flow(
            content = "开头\n\n结尾",
            reasoning = "旧思考一\u001E旧思考二",
            calls = listOf(
                // 2.2.6 及更早落库：没有 round，只能按偏移切
                call("a", after = 2, round = -1),
                call("b", after = 6, round = -1),
            ),
        )

        assertEquals(
            listOf("think:旧思考一", "think:旧思考二", "text:开头", "tool:a", "text:结尾", "tool:b"),
            result.steps.map { describe(it) },
        )
    }

    @Test
    fun `a middle round without thinking stays between the two thinking blocks`() {
        val result = flow(
            content = "结论",
            reasoning = "第一轮\u001E第三轮",
            calls = listOf(
                call("a", after = 0, round = 0, reasoningIndex = 0),
                call("b", after = 0, round = 1, reasoningIndex = -1),
                call("c", after = 0, round = 2, reasoningIndex = 1),
            ),
        )

        assertEquals(
            listOf("think:第一轮", "tool:a", "tool:b", "think:第三轮", "tool:c"),
            result.steps.map { describe(it) },
        )
    }

    @Test
    fun `a whitespace-only round does not shift later thinking onto the wrong tool`() {
        val result = flow(
            content = "结论",
            // 第 2 轮只吐了一个空格就开始调工具；它占一个段号，但不该渲染
            reasoning = "第一段\u001E \u001E第三段",
            calls = listOf(
                call("a", after = 0, round = 0, reasoningIndex = 0),
                call("b", after = 0, round = 1, reasoningIndex = 1),
            ),
        )

        assertEquals(
            listOf("think:第一段", "tool:a", "tool:b", "think:第三段"),
            result.steps.map { describe(it) },
        )
    }

    @Test
    fun `empty separator segments do not create empty thinking blocks`() {
        val result = flow(
            content = "答案",
            reasoning = "\u001E\u001E真正的思考\u001E",
        )

        assertEquals(1, result.steps.size)
        assertEquals("真正的思考", (result.steps.single() as ProcessStep.Thinking).text)
    }

    private fun describe(step: ProcessStep): String = when (step) {
        is ProcessStep.Thinking -> "think:${step.text}"
        is ProcessStep.Text -> "text:${step.text}"
        is ProcessStep.Tools -> "tool:" + step.calls.joinToString(",") { it.id }
    }
}
