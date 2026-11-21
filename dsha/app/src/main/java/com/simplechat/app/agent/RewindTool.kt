package com.simplechat.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `rewind_files`：让 AI 自己撤销改过的文件。
 *
 * 这是把"回滚"从界面手势变成一个**可组合的工具**：
 * AI 试了一种改法，跑测试挂了，它可以自己说"我退回去换个思路"，
 * 而不是把半个坏掉的仓库留给用户收拾。
 *
 * 三种粒度：
 * - 不给参数：撤销**上一轮**消息造成的改动（最常见的"刚才那步不算"）
 * - `turns: N`：往前 N 轮
 * - `message_id`：精确到某条消息（界面上"回滚到此处"同一条路径）
 *
 * `path` 把范围收窄到一个文件 —— 用户说"只把 main.kt 退回去"时用。
 * 云端会话里同一套工具照常工作：快照在远端读写，路径也是远端的。
 */
object RewindTool : AgentTool {

    override val name = "rewind_files"

    override val description =
        "把文件恢复到之前的状态（撤销你自己改过的内容）。用户说「撤销刚才的修改」「回滚」「恢复原样」" +
            "「刚才那版不对，退回去」时用。默认撤销**上一轮**对话造成的文件改动；" +
            "turns 往前推多轮，message_id 精确到某条消息，path 只回滚一个文件。\n" +
            "只回滚你通过 write_file / edit_file / patch_file 做的改动 —— " +
            "不要用它撤销用户自己手动改的东西，也不要拿它当「删除文件」用。"

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "只回滚这个文件（相对工作区）。留空 = 回滚这一范围内所有文件")
            }
            putJsonObject("turns") {
                put("type", "integer")
                put("description", "往前回滚几轮，默认 1（只撤销上一轮）。与 message_id 二选一")
            }
            putJsonObject("message_id") {
                put("type", "string")
                put("description", "回滚到这条消息之前（含这条消息造成的改动）。一般不需要手填")
            }
            putJsonObject("dry_run") {
                put("type", "boolean")
                put("description", "true = 只列出会改哪些文件，不实际动。用户问「会影响哪些文件」时先跑这个")
            }
        }
    }

    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val conversationId = ctx.conversationId
        if (conversationId.isBlank()) return ToolOutcome.fail("没有会话上下文，无法回滚")

        val scope = ctx.scope
        val cloud = ctx.remote != null && scope.startsWith("cloud:")

        val rawPath = args.str("path")?.trim()?.takeIf { it.isNotEmpty() }
        val path = rawPath?.let {
            if (cloud) {
                it.removePrefix("file://").trimStart('/').ifEmpty { null }
            } else {
                runCatching { Workspace.resolve(it).absolutePath }.getOrNull()
                    ?: return ToolOutcome.fail("路径越界：$it")
            }
        }

        val turns = args.int("turns") ?: 1
        val messageId = args.str("message_id")?.trim()?.takeIf { it.isNotEmpty() }
        val dryRun = args.bool("dry_run") == true

        val ids = if (messageId != null) {
            Rewind.messageIdsSince(conversationId, messageId, scope)
        } else {
            /*
             * 不排除当前消息：用户说"退回去"时，往往就是这一轮刚改坏。
             * 当前轮若还没有任何文件改动，集合自然落到上一轮。
             */
            Rewind.recentMessageIds(conversationId, scope, turns)
        }

        if (ids.isEmpty()) {
            return ToolOutcome.ok(
                if (messageId != null) {
                    "这条消息没有产生可回滚的文件改动。"
                } else {
                    "没有找到可回滚的文件改动。可能这些修改是用户手动做的，或来自命令（run_shell），不在快照范围内。"
                },
            )
        }

        val report = Rewind.rewind(
            conversationId = conversationId,
            messageIds = ids,
            actorMessageId = Rewind.ACTOR,
            scope = scope,
            pathFilter = path,
            remote = ctx.remote,
            dryRun = dryRun,
        )

        if (report.changed == 0 && report.failed.isEmpty() && report.missing.isEmpty()) {
            return ToolOutcome.ok("没有需要回滚的文件改动。")
        }
        return ToolOutcome.ok(report.summary())
    }
}
