package com.simplechat.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容 `/chat/completions` 的传输层数据类。
 *
 * 注意：这里只放 **Chat Completions** 的传输层数据类。
 * Anthropic 与 Responses 各自有独立适配器，不共用这些角色：
 * 前者的消息是块数组，后者的 input 是条目数组。硬复用只会得到 400。
 */

@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<OutMessageDto>,
    /**
     * ⚠️ **不能给默认值**。
     *
     * 序列化器配置了 `encodeDefaults = false`，若此处写成 `= true`，
     * kotlinx.serialization 会因为「值等于默认值」而**整个省略该字段**，
     * 服务端随即返回非流式的一整块 JSON —— 表现是「流跑完了但内容为空、也不报错」。
     */
    val stream: Boolean,
    /** DeepSeek 思考模式开关。不支持的模型不能带此字段。 */
    val thinking: ThinkingDto? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /** 仅用于 KVCache 隔离与内容安全标识，可选。 */
    @SerialName("user_id") val userId: String? = null,
    /**
     * 可调用的工具。null 时不发这个字段 —— 带了空数组，个别网关会报
     * "tools must not be empty"。
     */
    val tools: List<ToolSpecDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    /**
     * 快速模式（GPT 系端点的 `fast` 参数）。
     *
     * **默认 null 而不是 false**：序列化配置是 `encodeDefaults = false`，
     * 但这里真正要的是"没开就根本不存在这个字段" —— 不认识的网关看到
     * `fast: false` 一样会 400，只有打开开关的用户才需要它出现在请求里。
     */
    val fast: Boolean? = null,
)

@Serializable
data class ThinkingDto(val type: String) {
    companion object {
        val Enabled = ThinkingDto("enabled")
        val Disabled = ThinkingDto("disabled")
    }
}

/**
 * 发送给模型的消息。
 *
 * [content] 用 `JsonElement` 承载：纯文本时是 `JsonPrimitive`，
 * 带图片时是 content block 数组（图片**只允许出现在 user 消息中**，见设计文档 §11.3）。
 */
@Serializable
data class OutMessageDto(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement,
    /**
     * 助手消息里携带的工具调用（`role = "assistant"` 时）。
     *
     * 工具调用必须**原样回传**给模型：模型靠它把自己的请求和之后的结果对上，
     * 丢掉之后它会在下一轮重复调用同一个工具。
     */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    /** 工具结果消息对应的调用 id（`role = "tool"` 时必填）。 */
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /** 工具名，部分网关要求带。 */
    val name: String? = null,
)

@Serializable
data class ChatChunkDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
data class ChunkChoiceDto(
    val index: Int = 0,
    val delta: DeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    /** 思维链增量。与 `content` 同级（设计文档 §11.5）。 */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** 工具调用分片。与 `content` 同级。 */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** DeepSeek 的缓存命中 token 数，用于成本可视化（可选字段）。 */
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int? = null,
)

/** 错误响应体。 */
@Serializable
data class ErrorEnvelopeDto(val error: ErrorBodyDto? = null)

@Serializable
data class ErrorBodyDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

/** `GET /models` 响应（OpenCode Go 的模型列表无需鉴权即可访问）。 */
@Serializable
data class ModelListDto(val data: List<ModelEntryDto> = emptyList())

@Serializable
data class ModelEntryDto(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)
