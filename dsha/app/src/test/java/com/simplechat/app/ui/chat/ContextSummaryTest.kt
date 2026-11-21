package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要机制的回归测试。
 *
 * 这些断言钉的不是"文案好不好看"，而是**机制不能被悄悄改回去**：
 * 四节结构、上一份摘要继承、对话回顾、以及 Operit 的两个默认阈值。
 * 上一版就是每次"优化一下"就多一个参数，最后没人说得清到底按什么压。
 */
class ContextSummaryTest {

    @Test
    fun `默认阈值与 Operit 一致`() {
        assertEquals(0.70f, ContextSummary.TokenThreshold, 0.0001f)
        assertEquals(16, ContextSummary.MessageCountThreshold)
    }

    @Test
    fun `中文提示词包含四个固定小节`() {
        val prompt = ContextSummary.systemPrompt(previousSummary = null, rules = "", english = false)
        listOf("【核心任务状态】", "【互动情节与设定】", "【对话历程与概要】", "【关键信息与上下文】")
            .forEach { assertTrue("缺少小节：$it", prompt.contains(it)) }
        // 固定分隔线与"唯一历史参考"的定位，是这套提示词的核心约束
        assertTrue(prompt.contains("==========对话摘要=========="))
        assertTrue(prompt.contains("成为后续对话的唯一历史参考"))
    }

    @Test
    fun `英文提示词同样四节`() {
        val prompt = ContextSummary.systemPrompt(previousSummary = null, rules = "", english = true)
        listOf(
            "[Core Task Status]",
            "[Interaction & Scenario]",
            "[Conversation Progress & Overview]",
            "[Key Information & Context]",
        ).forEach { assertTrue("missing section: $it", prompt.contains(it)) }
        assertTrue(prompt.contains("==========Conversation Summary=========="))
    }

    @Test
    fun `上一份摘要作为继承段接在提示词末尾`() {
        val prompt = ContextSummary.systemPrompt(
            previousSummary = "用户在做安卓客户端，已决定用 Compose。",
            rules = "",
            english = false,
        )
        val at = prompt.indexOf("上一次的摘要（用于继承上下文）")
        assertTrue(at > 0)
        assertTrue(prompt.substring(at).contains("已决定用 Compose"))
        // 继承段必须在模板正文之后：它是附加输入，不是对模板的替换
        assertTrue(at > prompt.indexOf("关键信息与上下文"))
    }

    @Test
    fun `自定义规则排在最后`() {
        val prompt = ContextSummary.systemPrompt(
            previousSummary = "旧摘要",
            rules = "变量名与命令必须原样保留",
            english = false,
        )
        assertTrue(prompt.endsWith("变量名与命令必须原样保留"))
    }

    @Test
    fun `对话回顾每条消息一行并带说话人`() {
        val review = ContextSummary.dialogueReview(
            listOf(
                UiMessage(id = "1", role = MessageRole.USER, content = "帮我看下构建为什么失败"),
                UiMessage(id = "2", role = MessageRole.ASSISTANT, content = "是 R8 把反射用的类裁掉了"),
            ),
            english = false,
        )
        assertTrue(review.startsWith("对话回顾"))
        assertTrue(review.contains("- 用户: 帮我看下构建为什么失败"))
        assertTrue(review.contains("- AI: 是 R8"))
    }

    @Test
    fun `工具调用在回顾里压成一行`() {
        val review = ContextSummary.dialogueReview(
            listOf(
                UiMessage(
                    id = "2",
                    role = MessageRole.ASSISTANT,
                    content = "我看一下文件",
                    toolCalls = listOf(
                        ToolCallUi(
                            id = "call-1",
                            name = "read_file",
                            summary = "app/build.gradle.kts",
                            status = ToolCallUi.Status.OK,
                        ),
                    ),
                ),
            ),
            english = false,
        )
        assertTrue(review.contains("工具 read_file app/build.gradle.kts → 成功"))
    }

    @Test
    fun `工作区附件块不进入回顾`() {
        val review = ContextSummary.dialogueReview(
            listOf(
                UiMessage(
                    id = "1",
                    role = MessageRole.USER,
                    content = "<workspace_attachment>一整份文件树</workspace_attachment>\n把这里改成 snake_case",
                ),
            ),
            english = false,
        )
        assertFalse(review.contains("一整份文件树"))
        assertTrue(review.contains("把这里改成 snake_case"))
    }

    @Test
    fun `回顾超预算时丢最早条目并标注`() {
        val messages = (1..200).map { index ->
            UiMessage(
                id = "$index",
                role = MessageRole.USER,
                content = "第 $index 条：" + "很长的一句话".repeat(40),
            )
        }
        val review = ContextSummary.dialogueReview(messages, english = false)
        assertTrue(review.contains("较早的"))
        // 最近的必须留下
        assertTrue(review.contains("第 200 条"))
        assertFalse(review.contains("第 1 条："))
    }

    @Test
    fun `摘要指令是独立的一条 user`() {
        assertEquals("请按照要求总结对话内容", ContextSummary.userMessage(english = false))
        assertEquals(
            "Please summarize the conversation as instructed.",
            ContextSummary.userMessage(english = true),
        )
    }
}
