package com.simplechat.app.agent

import com.simplechat.app.data.Repeat
import com.simplechat.app.data.ScheduleStore
import com.simplechat.app.data.nextTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务工具。
 *
 * 存在的理由：用户说"每天早上八点提醒我吃药"时，模型不能只回一句
 * "好的，你需要自己在设置里添加" —— 它能调工具，就该把这件事真的办掉。
 *
 * 三个工具对应三件事：**加、看、删**。没有"改"，改 = 删了重加 ——
 * 改时间是个复合操作，多一个工具就多一个模型会用错的面。
 */
object ScheduleTools {

    fun all(): List<AgentTool> = listOf(Add, ListAll, Remove)

    private object Add : AgentTool {
        override val name = "schedule_add"
        override val description =
            "创建一个定时提醒（到点发系统通知）。用户说\"每天早上八点提醒我喝水\"这类话时直接调用，" +
                "先把时间换算成 24 小时制 HH:mm。创建前先 schedule_list 看有没有重复的提醒。"

        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "提醒标题，一句话说清要做什么，如「喝水」「开会」")
                }
                putJsonObject("time") {
                    put("type", "string")
                    put("description", "24 小时制 HH:mm，如 08:30")
                }
                putJsonObject("repeat") {
                    put("type", "string")
                    put("enum", buildJsonArray { add("once"); add("daily"); add("weekdays") })
                    put("description", "once=只提醒一次（默认）；daily=每天；weekdays=周一到周五")
                }
                putJsonObject("note") {
                    put("type", "string")
                    put("description", "可选补充说明，会显示在通知正文里")
                }
            }
            putJsonArray("required") { add("title"); add("time") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val title = args.argText("title")?.trim().orEmpty()
            if (title.isBlank()) return ToolOutcome.fail("缺少 title")
            val raw = args.argText("time") ?: return ToolOutcome.fail("缺少 time（HH:mm）")
            val parsed = parseClock(raw) ?: return ToolOutcome.fail("时间格式不对：$raw，请用 HH:mm")
            val repeat = when (args.argText("repeat")?.lowercase()) {
                "daily" -> Repeat.DAILY
                "weekdays" -> Repeat.WEEKDAYS
                else -> Repeat.ONCE
            }

            val item = ScheduleStore.add(
                title = title,
                hour = parsed.first,
                minute = parsed.second,
                repeat = repeat,
                note = args.argText("note").orEmpty(),
            )
            val next = item.nextTrigger()
            return ToolOutcome.ok(
                buildJsonObject {
                    put("id", item.id)
                    put("title", item.title)
                    put("time", item.clock)
                    put("repeat", repeat.label)
                    put(
                        "next",
                        next?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) }
                            ?: "不会再响",
                    )
                }.toString(),
            )
        }
    }

    private object ListAll : AgentTool {
        override val name = "schedule_list"
        override val description = "列出所有定时提醒及其下一次触发时间。用户问\"我设了哪些提醒\"时用它。"

        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val items = ScheduleStore.items.value
            if (items.isEmpty()) return ToolOutcome.ok("""{"count":0,"items":[]}""")
            val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return ToolOutcome.ok(
                buildJsonObject {
                    put("count", items.size)
                    put("items", buildJsonArray {
                        items.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { item ->
                            add(
                                buildJsonObject {
                                    put("id", item.id)
                                    put("title", item.title)
                                    put("time", item.clock)
                                    put("repeat", item.repeat.label)
                                    put("enabled", item.enabled)
                                    put(
                                        "next",
                                        item.nextTrigger()?.let { format.format(Date(it)) } ?: "不会再响",
                                    )
                                },
                            )
                        }
                    })
                }.toString(),
            )
        }
    }

    private object Remove : AgentTool {
        override val name = "schedule_remove"
        override val description = "删除一条定时提醒。id 来自 schedule_list。"

        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "string"); put("description", "schedule_list 返回的 id") }
            }
            putJsonArray("required") { add("id") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val id = args.argText("id") ?: return ToolOutcome.fail("缺少 id")
            val item = ScheduleStore.find(id) ?: return ToolOutcome.fail("没有 id=$id 的提醒")
            ScheduleStore.remove(id)
            return ToolOutcome.ok("""{"removed":"${item.title}","id":"$id"}""")
        }
    }

    /** 接受 `8:30` / `08:30` / `8：30`（中文冒号）。 */
    private fun parseClock(raw: String): Pair<Int, Int>? {
        val normalized = raw.trim().replace('：', ':').replace("点", ":")
        val parts = normalized.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
