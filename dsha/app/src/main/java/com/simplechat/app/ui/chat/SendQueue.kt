package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import java.util.UUID

/**
 * 一条**排好队、等上一轮说完再发**的消息。
 *
 * 为什么要有它：Agent 一轮任务动辄几十秒，用户想到下一句时只能干等，
 * 或者更糟 —— 打了一大段字，点发送，被 `isStreaming` 直接吞掉（什么都不发生）。
 * 排队把这段等待变成"先记着，轮到就发"。
 *
 * [conversationId] 把队列按会话隔开：在 A 里排的话，切到 B 不会突然发出去；
 * 切回 A 时它才继续。为 null 表示排队时还在"新对话"页。
 */
data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String?,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
) {
    /** 队列里那一行预览：没打字只发图时也得有话说。 */
    val preview: String
        get() = text.ifBlank { "（${attachments.size} 个附件）" }.replace('\n', ' ')
}
