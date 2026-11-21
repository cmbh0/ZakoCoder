package com.simplechat.app.agent

import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.ToolCallDto
import com.simplechat.app.net.FunctionCallDto

/** 一个已经拼装完整的工具调用。 */
data class AgentToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 把流式分片拼成完整调用。
 *
 * 协议规定：同一轮里模型可以并行发多个调用（`index` 区分），每个调用的
 * `arguments` 是 **JSON 字符串的分片**，可能断在任意位置 —— 包括断在
 * `{"pa` 这种半个 key 上。所以只能按 index 累积原始字符串，等流结束再解析。
 *
 * 另一个坑：`id` 只在第一个分片出现，后续分片是 null。拼的时候要"有就覆盖、
 * 没有就保留"，不能用 null 去冲掉已有的值。
 */
class ToolCallAccumulator {

    private val ids = mutableMapOf<Int, String>()
    private val names = mutableMapOf<Int, String>()
    private val args = mutableMapOf<Int, StringBuilder>()

    val isEmpty: Boolean get() = names.isEmpty()

    fun accept(event: StreamEvent.ToolCall) {
        event.id?.let { ids[event.index] = it }
        event.name?.let { names[event.index] = it }
        event.arguments?.let { piece ->
            args.getOrPut(event.index) { StringBuilder() }.append(piece)
        }
    }

    /** 也接受非流式形态（整条 ToolCallDto），便于统一处理。 */
    fun accept(call: ToolCallDto) {
        accept(
            StreamEvent.ToolCall(
                index = call.index,
                id = call.id,
                name = call.function?.name,
                arguments = call.function?.arguments,
            ),
        )
    }

    /**
     * 产出最终调用列表。
     *
     * - 没有名字的分片一律丢弃（属于解析残渣）
     * - 没有 id 的补一个本地 id：某些网关省掉 id，但回传 `tool_call_id` 时
     *   又要求非空，两处都得照顾
     * - `arguments` 为空时给 `{}`，让工具走"缺参数"分支，而不是让 JSON 解析炸掉
     */
    fun build(): List<AgentToolCall> = names.keys.sorted().mapNotNull { index ->
        val name = names[index] ?: return@mapNotNull null
        val raw = args[index]?.toString()?.trim()
        AgentToolCall(
            id = ids[index]?.takeIf { it.isNotBlank() } ?: "call_$index",
            name = name,
            argumentsJson = if (raw.isNullOrBlank()) "{}" else raw,
        )
    }

    /** 回传给模型的助手消息里要原样带上，不能丢。 */
    fun toDto(): List<ToolCallDto> = build().mapIndexed { i, call ->
        ToolCallDto(
            index = i,
            id = call.id,
            type = "function",
            function = FunctionCallDto(name = call.name, arguments = call.argumentsJson),
        )
    }
}
