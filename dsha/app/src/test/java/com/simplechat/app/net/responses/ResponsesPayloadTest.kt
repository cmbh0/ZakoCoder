package com.simplechat.app.net.responses

import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.FunctionCallDto
import com.simplechat.app.net.FunctionSpecDto
import com.simplechat.app.net.MessageContent
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.ToolCallDto
import com.simplechat.app.net.ToolSpecDto
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses 请求体翻译的回归测试。
 *
 * 这一层错了只会得到 400，界面上完全看不出是哪一环；所以把「system → instructions」、
 * 「assistant 工具调用 → function_call」、「工具结果 → function_call_output」逐条钉死。
 */
class ResponsesPayloadTest {

    private val json = ChatApi.defaultJson()

    private fun message(
        role: String,
        content: String,
        toolCalls: List<ToolCallDto>? = null,
        toolCallId: String? = null,
        name: String? = null,
    ) = OutMessageDto(
        role = role,
        content = MessageContent.text(content),
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        name = name,
    )

    @Test
    fun `system 进入 instructions，不出现在 input`() {
        val payload = ResponsesPayload.build(
            model = "gpt-5",
            messages = listOf(
                message("system", "你是角色 A"),
                message("user", "继续"),
            ),
        )
        assertEquals("你是角色 A", payload["instructions"]?.jsonPrimitive?.content)
        val input = payload["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具调用与工具结果按 Responses 条目回传`() {
        val payload = ResponsesPayload.build(
            model = "gpt-5",
            messages = listOf(
                message("user", "查一下"),
                message(
                    "assistant",
                    "",
                    toolCalls = listOf(
                        ToolCallDto(
                            index = 0,
                            id = "call_1",
                            type = "function",
                            function = FunctionCallDto("read_file", """{"path":"a.txt"}"""),
                        ),
                    ),
                ),
                message("tool", "内容", toolCallId = "call_1", name = "read_file"),
            ),
            tools = listOf(
                ToolSpecDto(
                    function = FunctionSpecDto(
                        name = "read_file",
                        description = "读文件",
                        parameters = json.parseToJsonElement("""{"type":"object"}"""),
                    ),
                ),
            ),
        )
        val input = payload["input"]!!.jsonArray
        assertEquals("function_call", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[1].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)

        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", tool["name"]?.jsonPrimitive?.content)
        assertFalse(tool.containsKey("function"))
    }

    @Test
    fun `思考开启时发 reasoning，并且不发 temperature`() {
        val payload = ResponsesPayload.build(
            model = "gpt-5",
            messages = listOf(message("user", "想一下")),
            thinkingEnabled = true,
            reasoningEffort = "high",
            temperature = 0.8,
            topP = 0.9,
        )
        assertEquals("high", payload["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("temperature"))
        assertFalse(payload.containsKey("top_p"))
    }

    @Test
    fun `图片转成 input_image`() {
        val payload = ResponsesPayload.build(
            model = "gpt-5",
            messages = listOf(
                message("user", "看这张").copy(
                    content = MessageContent.withImages(
                        "看这张",
                        listOf(com.simplechat.app.net.ImageInput("data:image/jpeg;base64,AA==")),
                    ),
                ),
            ),
        )
        val content = payload["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" })
        assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "input_text" })
    }
}

/**
 * Responses 流事件的解析测试。
 */
class ResponsesStreamParserTest {

    private val parser = ResponsesStreamParser(ChatApi.defaultJson())

    @Test
    fun `正文增量`() {
        val events = parser.parse(
            """{"type":"response.output_text.delta","delta":"雨是从傍晚开始下的"}""",
        )
        assertEquals(listOf(StreamEvent.Content("雨是从傍晚开始下的")), events)
    }

    @Test
    fun `思考摘要增量`() {
        val events = parser.parse(
            """{"type":"response.reasoning_summary_text.delta","delta":"先理顺关系"}""",
        )
        assertEquals(listOf(StreamEvent.Reasoning("先理顺关系")), events)
    }

    @Test
    fun `函数调用分片`() {
        val added = parser.parse(
            """{"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"call_9","name":"read_file","arguments":""}}""",
        )
        assertEquals(listOf(StreamEvent.ToolCall(2, "call_9", "read_file", null)), added)

        val delta = parser.parse(
            """{"type":"response.function_call_arguments.delta","output_index":2,"delta":"{\"path\":\"a.txt\"}"}""",
        )
        assertEquals(
            listOf(StreamEvent.ToolCall(2, null, null, """{"path":"a.txt"}""")),
            delta,
        )
    }

    @Test
    fun `没有增量时由 done 补齐完整参数`() {
        parser.parse(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"call_1","name":"run_shell","arguments":""}}""",
        )
        val done = parser.parse(
            """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"call_1","name":"run_shell","arguments":"{\"command\":\"ls\"}"}}""",
        )
        assertEquals(
            listOf(
                // 第一条把 id/name 交给累加器：正常流里 added 已经发过一次，
                // done 再发是一次幂等覆盖（累加器按 index 存 map）。
                StreamEvent.ToolCall(1, "call_1", "run_shell", null),
                // 第二条只带参数尾巴。done 的 call_id 一并放进来 ——
                // 覆盖成同一个值，不会产生第二个调用。
                StreamEvent.ToolCall(1, "call_1", null, """{"command":"ls"}"""),
            ),
            done,
        )
    }

    @Test
    fun `completed 带用量并结束`() {
        val events = parser.parse(
            """{"type":"response.completed","response":{"usage":{"input_tokens":12,"output_tokens":34,"input_tokens_details":{"cached_tokens":5}}}}""",
        )
        assertEquals(listOf(StreamEvent.Usage(12, 34, 5), StreamEvent.Done), events)
    }

    @Test
    fun `中转站误发 chat chunk 时仍能解析`() {
        val events = parser.parse(
            """{"choices":[{"index":0,"delta":{"content":"兼容"}}]}""",
        )
        assertEquals(listOf(StreamEvent.Content("兼容")), events)
    }

    @Test
    fun `错误事件带出服务端原话`() {
        val events = parser.parse(
            """{"type":"response.failed","response":{"error":{"message":"model not found"}}}""",
        )
        assertEquals(
            listOf(StreamEvent.Error("model not found")),
            events,
        )
    }
}
