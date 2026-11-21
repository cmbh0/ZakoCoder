package com.simplechat.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI 兼容协议里的工具定义。
 *
 * 只实现 function 一种类型 —— 这是所有中转站都认的最小交集。
 * `strict` 一律不发：不少网关不认识它，会直接 400。
 */
@Serializable
data class ToolSpecDto(
    val type: String = "function",
    val function: FunctionSpecDto,
)

@Serializable
data class FunctionSpecDto(
    val name: String,
    val description: String,
    /** JSON Schema。用 `JsonElement` 承载，避免为每个工具写一层 DTO。 */
    val parameters: JsonElement,
)

/**
 * 模型返回的工具调用。
 *
 * 流式下它是**分片到达**的：第一片带 `id` + `function.name`，后续片只带
 * `arguments` 的字符串片段，`index` 标识属于第几个调用。合并规则见
 * [ToolCallAccumulator]。
 */
@Serializable
data class ToolCallDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDto? = null,
)

@Serializable
data class FunctionCallDto(
    val name: String? = null,
    /** 未完成的 JSON 字符串；分片拼接后才是完整对象。 */
    val arguments: String? = null,
)

/** 回传给模型的工具执行结果（`role = "tool"`）。 */
@Serializable
data class ToolResultDto(
    @SerialName("tool_call_id") val toolCallId: String,
    val role: String = "tool",
    val content: String,
    val name: String? = null,
)
