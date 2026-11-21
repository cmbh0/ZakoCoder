package com.simplechat.app.net.responses

import com.simplechat.app.net.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Responses API 的 SSE 解析器。
 *
 * 与 Chat Completions 的解析器分开：Responses 发的是**语义事件**
 * （`response.output_text.delta`、`response.function_call_arguments.delta` …），
 * 不是 `choices[0].delta`。两者只有 `[DONE]` 长得一样。
 *
 * 另外做了一层兼容：部分中转站把 `/responses` 当成 `/chat/completions`
 * 直接转发，回来的是 chat chunk。这里识别到 `choices` 就按 chat 形态解析，
 * 免得用户只看到一条空白回复。
 *
 * 纯解析，不发请求；单测直接喂 JSON 字符串。
 */
internal class ResponsesStreamParser(private val json: Json) {

    /** 每个输出项已累积的参数。key 是 output_index。 */
    private val arguments = mutableMapOf<Int, StringBuilder>()

    /** item_id → output_index；部分网关只在 delta 里给 item_id，不给 output_index。 */
    private val itemIndexes = mutableMapOf<String, Int>()
    private var nextIndex = 0

    fun parse(data: String): List<StreamEvent> {
        val raw = data.trim()
        if (raw.isEmpty()) return emptyList()
        if (raw == "[DONE]") return listOf(StreamEvent.Done)

        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        val obj = root as? JsonObject ?: return emptyList()

        // 中转站误把 Chat Completions 的 chunk 发回来
        if (obj.containsKey("choices")) return parseChatChunk(obj)

        return when (obj.str("type")) {
            "response.output_text.delta",
            "response.refusal.delta",
            -> obj.str("delta")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(StreamEvent.Content(it)) }
                .orEmpty()

            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> obj.str("delta")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(StreamEvent.Reasoning(it)) }
                .orEmpty()

            "response.output_item.added" -> outputItemAdded(obj)

            "response.function_call_arguments.delta" -> {
                val index = indexOf(obj)
                val piece = obj.str("delta").orEmpty()
                if (piece.isEmpty()) {
                    emptyList()
                } else {
                    arguments.getOrPut(index) { StringBuilder() }.append(piece)
                    listOf(StreamEvent.ToolCall(index, obj.str("call_id"), null, piece))
                }
            }

            "response.function_call_arguments.done" -> {
                val index = indexOf(obj)
                completeArguments(index, obj.str("arguments").orEmpty(), obj.str("call_id"))
            }

            "response.output_item.done" -> {
                val item = obj["item"] as? JsonObject ?: return emptyList()
                if (item.str("type") != "function_call") return emptyList()
                val index = indexOf(obj, fallbackItem = item)
                val id = item.str("call_id") ?: item.str("id")
                buildList {
                    id?.let { add(StreamEvent.ToolCall(index, it, item.str("name"), null)) }
                    addAll(completeArguments(index, item.str("arguments").orEmpty(), id))
                }
            }

            "response.completed", "response.incomplete" -> {
                val response = obj["response"] as? JsonObject
                buildList {
                    response?.let { usageOf(it) }?.let { add(it) }
                    add(StreamEvent.Done)
                }
            }

            "response.failed" -> {
                val response = obj["response"] as? JsonObject
                val error = response?.get("error") as? JsonObject
                listOf(
                    StreamEvent.Error(
                        message = error?.str("message")
                            ?: obj.str("message")
                            ?: "Responses 请求失败",
                    ),
                )
            }

            "error" -> {
                val error = obj["error"] as? JsonObject
                listOf(
                    StreamEvent.Error(
                        message = error?.str("message")
                            ?: obj.str("message")
                            ?: "Responses 返回错误",
                    ),
                )
            }

            else -> emptyList()
        }
    }

    /** `response.output_item.added`：把函数名与首个参数片交给累加器。 */
    private fun outputItemAdded(obj: JsonObject): List<StreamEvent> {
        val item = obj["item"] as? JsonObject ?: return emptyList()
        if (item.str("type") != "function_call") return emptyList()
        val index = indexOf(obj, fallbackItem = item)
        val id = item.str("call_id") ?: item.str("id")
        val name = item.str("name")
        val initial = item.str("arguments").orEmpty()
        if (initial.isNotEmpty()) {
            arguments.getOrPut(index) { StringBuilder() }.append(initial)
        }
        return listOf(StreamEvent.ToolCall(index, id, name, initial.takeIf { it.isNotEmpty() }))
    }

    /**
     * 补上 `arguments.done` 里比增量多出来的尾巴。
     *
     * 正常流里 delta 已经拼完整，这里返回空；只有网关不发 delta 或中途丢片时，
     * 才会把完整参数补发一次 —— 工具调用不能因为少一个右括号而整轮失败。
     */
    private fun completeArguments(
        index: Int,
        full: String,
        id: String?,
    ): List<StreamEvent> {
        if (full.isEmpty()) return emptyList()
        val accumulated = arguments.getOrPut(index) { StringBuilder() }
        val existing = accumulated.toString()
        val tail = when {
            existing == full -> ""
            full.startsWith(existing) -> full.removePrefix(existing)
            existing.startsWith(full) -> ""
            /*
             * 两边对不上（网关把参数改写过 / 发了两份不同的完整串）：
             * 不再重复追加，交给模型自己从工具报错里恢复，避免拼出一个坏 JSON。
             */
            else -> ""
        }
        if (tail.isEmpty()) return emptyList()
        accumulated.append(tail)
        return listOf(StreamEvent.ToolCall(index, id, null, tail))
    }

    /** 兼容 Chat Completions 形态（中转站误路由时）。 */
    private fun parseChatChunk(obj: JsonObject): List<StreamEvent> {
        val out = mutableListOf<StreamEvent>()
        obj["choices"]?.let { choices ->
            if (choices is JsonArray) choices.forEach { element ->
                val choice = element as? JsonObject ?: return@forEach
                val delta = choice["delta"] as? JsonObject
                delta?.str("content")?.takeIf { it.isNotEmpty() }
                    ?.let { out += StreamEvent.Content(it) }
                delta?.str("reasoning_content")?.takeIf { it.isNotEmpty() }
                    ?.let { out += StreamEvent.Reasoning(it) }
                delta?.get("tool_calls")?.let { calls ->
                    if (calls is JsonArray) calls.forEach { callElement ->
                        val call = callElement as? JsonObject ?: return@forEach
                        val fn = call["function"] as? JsonObject
                        out += StreamEvent.ToolCall(
                            index = call["index"]?.jsonPrimitive?.intOrNull ?: 0,
                            id = call.str("id"),
                            name = fn?.str("name"),
                            arguments = fn?.str("arguments"),
                        )
                    }
                }
                if (choice.str("finish_reason") != null) out += StreamEvent.Done
            }
        }
        obj["usage"]?.let { usage ->
            // usageOf 返回可空：直接 `out += usageOf(...)` 会被推成
            // `out = out + null`（List<StreamEvent?>），既可空类型不符、
            // 又因为 out 是 val 直接报"cannot be reassigned"。
            if (usage is JsonObject) usageOf(usage)?.let { out += it }
        }
        return out
    }

    private fun usageOf(response: JsonObject): StreamEvent.Usage? {
        val usage = response["usage"] as? JsonObject ?: return null
        val prompt = usage.int("input_tokens") ?: usage.int("prompt_tokens") ?: 0
        val completion = usage.int("output_tokens") ?: usage.int("completion_tokens") ?: 0
        val cached = (usage["input_tokens_details"] as? JsonObject)?.int("cached_tokens")
        return StreamEvent.Usage(prompt, completion, cached)
    }

    private fun indexOf(obj: JsonObject, fallbackItem: JsonObject? = null): Int {
        val itemId = fallbackItem?.str("id") ?: obj.str("item_id")
        obj.int("output_index")?.let { index ->
            // 记住 item_id → index：后面的 done 事件可能只给 item_id，不给 output_index
            if (!itemId.isNullOrBlank()) itemIndexes[itemId] = index
            return index
        }
        if (!itemId.isNullOrBlank()) {
            itemIndexes[itemId]?.let { return it }
            val assigned = nextIndex++
            itemIndexes[itemId] = assigned
            return assigned
        }
        return 0
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
