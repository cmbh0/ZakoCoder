package com.simplechat.app.data

import com.simplechat.app.ui.chat.ToolCallUi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 工具调用的落库编解码。
 *
 * 早先的判断是"工具调用是过程，不是内容" —— 重开 App 只要还能读到结论，
 * 中间读过哪个文件就不必留。实际用下来这个判断是错的：用户回头翻一段
 * 对话，想知道的恰恰是"它到底动了哪些文件、跑了哪条命令"。过程也是记录。
 *
 * 所以现在写进 `messages.toolCalls`（JSON 数组），随消息一起读回来。
 */
object ToolCalls {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 单条输出最多留多少字符。
     *
     * 工具输出在 UI 上本来就会截断显示，但原始字符串可能几十 KB（一次
     * `ls -R` 就能上万行）。一条回复里十几次调用，全量存下来能让数据库
     * 从几 MB 涨到几十 MB —— 而用户展开卡片时看的是前几百行。
     *
     * 4000 字大约是一屏半的可读输出，超出部分对"回顾"没有价值。
     */
    private const val MaxOutputChars = 4000

    /** 空列表编码成 null：和 `timeline` 一致，"没有"只有一种表示。 */
    fun encode(calls: List<ToolCallUi>): String? {
        if (calls.isEmpty()) return null
        val trimmed = calls.map { call ->
            if (call.output.length <= MaxOutputChars) {
                call
            } else {
                call.copy(output = call.output.take(MaxOutputChars) + "\n…（输出过长，已截断）")
            }
        }
        return runCatching { json.encodeToString(trimmed) }.getOrNull()
    }

    fun decode(raw: String?): List<ToolCallUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ToolCallUi>>(raw)
        }.getOrDefault(emptyList()).map { call ->
            /*
             * 「等待批准」不能原样恢复。
             *
             * 那条调用当时卡在审批上，App 一关，批准通道就没了 —— 留着
             * AWAITING 会渲染成一个永远转圈、点"允许"也没反应的卡片。
             * 归成 FAILED 并写明原因，用户能看懂发生了什么。
             */
            if (call.status == ToolCallUi.Status.AWAITING) {
                call.copy(
                    status = ToolCallUi.Status.FAILED,
                    output = call.output.ifBlank { "（等待审批时 App 关闭，这次调用没有执行）" },
                )
            } else {
                call
            }
        }
    }
}
