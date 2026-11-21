package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ImageInput
import com.simplechat.app.net.MessageContent
import kotlinx.serialization.json.JsonElement

/**
 * 组装一条 **user** 消息的 `content`。
 *
 * 三种形态，按"能简单就简单"排：
 *
 * | 附件 | content |
 * |---|---|
 * | 无 | 纯字符串 |
 * | 只有文本文件 | 纯字符串（附件内容按定界块拼进去） |
 * | 有图片 | content block 数组 |
 *
 * ### 为什么图片必须走数组
 *
 * 官方 Vision 指南明确：图片只能出现在 **user** 消息里，且必须写成
 * `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`。
 * 放进 system / assistant 会直接 400。
 *
 * ### 图片会随每一轮请求重发
 *
 * 接口是无状态的，历史里的图片每次都要重新带上（这也是把它存进数据库、
 * 而不是用完即弃的原因）。token 账要按"每轮都算"来看。
 *
 * 抽成纯函数是为了能单测：这里错一处，表现是"模型对图片视而不见"
 * 或者"请求直接 400"，从界面上完全看不出是哪一环。
 */
fun userContent(text: String, attachments: List<Attachment>): JsonElement {
    if (attachments.isEmpty()) return MessageContent.text(text)

    /*
     * 任意文件也按"文本块"拼进正文 —— 它的 `toPromptBlock()` 只放一条
     * 「已保存到工作区 uploads/xxx」的说明，不塞二进制内容。
     */
    val textFiles = attachments.filter {
        it.kind == Attachment.Kind.TEXT || it.kind == Attachment.Kind.FILE
    }
    // 只发有内容的图片：落盘附件的 data 要靠 `prepareRequestAttachments` 读回，
    // 万一漏了那一步，空 base64 发上去只会 400（见 AnthropicRequestBuilder 的同一条注释）
    val images = attachments.filter { it.isImage && it.data.isNotBlank() }

    val body = buildString {
        append(text)
        textFiles.forEach { file ->
            if (isNotEmpty()) appendLine()
            appendLine()
            append(file.toPromptBlock())
        }
    }.trim()

    if (images.isEmpty()) return MessageContent.text(body)

    return MessageContent.withImages(
        text = body,
        images = images.map { ImageInput(it.toDataUrl(), detail = ImageDetail) },
    )
}

/**
 * 图片精度。
 *
 * 官方给了四档，这里固定用 `high`（保留原图，服务端再统一缩到约 1300×1300 等效）。
 *
 * 不用 `low` 的原因很实际：`low` 会把图**缩到 512×512**，
 * 而本项目最常见的图片用法就是"截图里有一段文字，帮我看看" ——
 * 512×512 下小字直接糊成一团。
 * 代价可控：每张图上限 1024 token，且只有带图的会话才会付这个成本。
 */
const val ImageDetail = "high"

/**
 * 附件折算的 token 估算，喂给上下文占用圆环。
 *
 * - 文本附件：按正文一样的规则估
 * - 图片：按官方给的**上限 1024/张** 记。实际通常低于这个数，
 *   但占用圆环宁可高估 —— 它要提示的是"快满了"，低估会让人措手不及。
 */
fun attachmentTokenEstimate(attachments: List<Attachment>): Int {
    if (attachments.isEmpty()) return 0
    return attachments.sumOf { attachment ->
        when {
            attachment.isImage -> ImageTokenUpperBound
            // 文件只贡献一条路径说明（几十 token），给一个保守的固定值
            attachment.isFile -> FileBlockTokenEstimate
            else -> com.simplechat.app.data.TokenEstimate.of(attachment.data)
        }
    }
}

/**
 * 这条历史消息值不值得发出去（OpenAI 兼容通道）。
 *
 * 历史里确实可能出现"什么都没有"的消息：回复出错 / 被中断后正文与思考都为空、
 * 或者一条既没正文也没附件的空 user。DeepSeek 官方对 `content: ""` 比较宽松，
 * 但不少兼容网关会直接 400（`content must not be empty`）——
 * 一条空消息让整段历史发不出去，代价远大于少发一条。
 *
 * 纯函数，有单测（`RequestContentTest`）。
 */
internal fun isSendableOutgoing(message: UiMessage): Boolean =
    message.content.isNotBlank() ||
        // 只有工具过程、正文还没来得及写的轮次也要留下（见 outgoingAssistantText）
        message.toolCalls.isNotEmpty() ||
        message.attachments.any { attachment ->
            when {
                attachment.kind == Attachment.Kind.TEXT -> attachment.data.isNotBlank()
                attachment.kind == Attachment.Kind.FILE -> attachment.path != null
                attachment.isImage -> attachment.data.isNotBlank()
                else -> false
            }
        }

/**
 * 一条 **assistant** 历史消息真正要发给模型的正文。
 *
 * 为什么需要它（强制截断失忆的根因）：
 * Agent 模式下模型常常"说一句 → 动一次手 → 接着说"。用户在中途按停止时，
 * 这一轮**正文可能一个字都还没写**，全部成果都在 `toolCalls` 里。早先
 * `orderedOutgoingMessages` 只按 `content.isNotBlank()` 筛，这种轮次被整条丢掉 ——
 * 下一轮模型完全不知道自己刚才改过哪个文件、跑过哪条命令，只能重来一遍，
 * 表现为"失忆 + token 翻几倍"。
 *
 * 所以这里补一条规则：**有正文就发正文；正文为空但动过手，就把动手的过程
 * 写成一段简短纪要发出去**。被用户中断的轮次再附一句明确的中断说明，
 * 免得模型把半截话当成完整结论接着往下推。
 *
 * 只影响发给 API 的内容，本地消息与界面渲染一个字不动。
 */
internal fun outgoingAssistantText(message: UiMessage): String {
    val body = message.content
    val stopped = message.status == MessageStatus.STOPPED
    if (body.isNotBlank()) {
        return if (stopped) "$body\n\n（这一轮回复被用户中途停止，以上是不完整的内容。）" else body
    }
    if (message.toolCalls.isEmpty()) return body

    val steps = message.toolCalls.take(12).joinToString("\n") { call ->
        val state = when (call.status) {
            ToolCallUi.Status.OK -> "成功"
            ToolCallUi.Status.FAILED -> "失败"
            ToolCallUi.Status.RUNNING, ToolCallUi.Status.AWAITING -> "未完成"
        }
        val summary = call.summary.ifBlank { call.name }
        val output = call.output.trim().take(600)
        buildString {
            append("- ").append(call.name).append(" ").append(summary).append("：").append(state)
            if (output.isNotEmpty()) append("\n  输出摘要：").append(output)
        }
    }
    val more = message.toolCalls.size - 12
    return buildString {
        append("（上一轮我执行了这些工具，正文尚未写出：\n")
        append(steps)
        if (more > 0) append("\n- …另有 ").append(more).append(" 次调用")
        append("\n）")
        if (stopped) append("\n（这一轮被用户中途停止，未执行完的步骤不要当作已完成。）")
    }
}

/** 官方 Vision 指南给出的单图 token 上限。 */
const val ImageTokenUpperBound = 1024

/** 任意文件在上下文里只占一条「路径 + 大小」说明。 */
private const val FileBlockTokenEstimate = 120
