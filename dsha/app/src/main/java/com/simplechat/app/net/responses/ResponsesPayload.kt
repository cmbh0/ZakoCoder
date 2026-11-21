package com.simplechat.app.net.responses

import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.ToolSpecDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 把项目内部的「聊天消息」翻译成 OpenAI Responses 请求体。
 *
 * 为什么不复用 `ChatRequestDto`：两套协议只是**长得像**，结构并不一样 ——
 * Responses 的 `input` 是条目数组（消息 / function_call / function_call_output），
 * 工具定义是扁平的 `{type:function,name,...}`，系统提示词走 `instructions`。
 * 硬塞进 Chat Completions 的 DTO，最终会得到一串服务端看不懂的 role。
 *
 * 纯函数，方便单测。这里错了的表现只有 400，从界面上看不出是哪一层。
 */
internal object ResponsesPayload {

    fun build(
        model: String,
        messages: List<OutMessageDto>,
        tools: List<ToolSpecDto>? = null,
        thinkingEnabled: Boolean = false,
        reasoningEffort: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)

        /*
         * system / developer 统一并进 instructions。
         *
         * Responses 的 input 也允许 role=system，但不少兼容网关只认 instructions；
         * 这里走最保守的一条，且保证角色设定不会被拆成多条。
         */
        val instructions = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { plainText(it.content) }
            .trim()
        if (instructions.isNotEmpty()) put("instructions", instructions)

        put("input", buildJsonArray {
            messages.filter { it.role != "system" }.forEach { message ->
                when (message.role) {
                    "user" -> add(messageItem("user", userContent(message.content)))
                    "assistant" -> {
                        val text = plainText(message.content)
                        if (text.isNotBlank()) {
                            add(messageItem("assistant", outputContent(text)))
                        }
                        message.toolCalls.orEmpty().forEach { call ->
                            addJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id.orEmpty().ifBlank { "call_${call.index}" })
                                put("name", call.function?.name.orEmpty())
                                put("arguments", call.function?.arguments.orEmpty().ifBlank { "{}" })
                            }
                        }
                    }
                    "tool" -> addJsonObject {
                        put("type", "function_call_output")
                        put(
                            "call_id",
                            message.toolCallId.orEmpty().ifBlank { message.name.orEmpty() },
                        )
                        put("output", plainText(message.content))
                    }
                }
            }
        })

        /*
         * 工具定义：Responses 是扁平的，不套 `function`。
         * `strict` 一律不发 —— 很多兼容网关不认识它。
         */
        if (!tools.isNullOrEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        put("parameters", tool.function.parameters)
                    }
                }
            })
            put("tool_choice", "auto")
        }

        /*
         * 思考与采样参数的取舍与 Chat 通道一致：
         * 开了思考就不再发 temperature/top_p（Responses 会直接拒绝或静默忽略）。
         */
        if (thinkingEnabled) {
            putJsonObject("reasoning") {
                put("effort", reasoningEffort ?: "medium")
                put("summary", "auto")
            }
        } else {
            temperature?.let { put("temperature", it) }
            topP?.let { put("top_p", it) }
        }
        maxTokens?.takeIf { it > 0 }?.let { put("max_output_tokens", it) }
    }

    /** 一条 Responses message 条目。 */
    private fun messageItem(role: String, content: JsonArray): JsonObject =
        buildJsonObject {
            put("type", "message")
            put("role", role)
            put("content", content)
        }

    /** user 输入：文本 + 图片，全部转成 Responses 的 input_* 块。 */
    private fun userContent(content: JsonElement): JsonArray = buildJsonArray {
        when (content) {
            is JsonArray -> content.forEach { element ->
                val obj = element as? JsonObject
                val type = obj?.get("type")?.jsonPrimitive?.contentOrNull
                when (type) {
                    "text", "input_text" -> {
                        val text = obj?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isNotEmpty()) addJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        }
                    }
                    "image_url", "input_image" -> {
                        val url = if (type == "image_url") {
                            obj?.get("image_url")?.let { image ->
                                (image as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                                    ?: image.jsonPrimitive.contentOrNull
                            }
                        } else {
                            obj?.get("image_url")?.jsonPrimitive?.contentOrNull
                        }.orEmpty()
                        if (url.isNotEmpty()) addJsonObject {
                            put("type", "input_image")
                            put("image_url", url)
                            (obj?.get("detail") as? JsonPrimitive)?.contentOrNull?.let {
                                put("detail", it)
                            }
                        }
                    }
                    else -> {
                        // 不认识的块不原样透传：Responses 对它不认识的 type 会整条拒绝
                        val text = obj?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isNotEmpty()) addJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        }
                    }
                }
            }
            else -> {
                val text = plainText(content)
                if (text.isNotEmpty()) addJsonObject {
                    put("type", "input_text")
                    put("text", text)
                }
            }
        }
    }

    /** assistant 历史：必须是 output_text，input_text 会被协议拒绝。 */
    private fun outputContent(text: String): JsonArray = buildJsonArray {
        addJsonObject {
            put("type", "output_text")
            put("text", text)
        }
    }

    /** 从任意形态的 content 里取纯文本。 */
    private fun plainText(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { element ->
            val obj = element as? JsonObject ?: return@joinToString ""
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text" || type == "input_text" || type == "output_text") {
                obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            } else {
                ""
            }
        }
        else -> ""
    }
}
