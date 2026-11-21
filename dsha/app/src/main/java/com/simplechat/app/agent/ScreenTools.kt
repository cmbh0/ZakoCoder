package com.simplechat.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 操作屏幕的工具集。
 *
 * 与文件类工具的区别：这些动作**不可撤销**。点错一个"删除并退出"就回不来了，
 * 所以整套工具被设计成"先看后动"：
 *
 * ```
 * screen_observe  →  拿到 snapshot + 节点 id 列表
 * screen_click    →  必须带 snapshot/id，节点指纹对不上就拒绝
 * screen_observe  →  每次动作之后重新看
 * ```
 *
 * 为什么不让模型直接发坐标：坐标系在横竖屏、状态栏高度、分屏下全是坑，
 * 而节点树本来就带着"这个按钮叫什么、能不能点"。能用名字点的，绝不用坐标。
 */
object ScreenTools {

    /**
     * 按**当前识别管线**给出这一轮能用的工具。
     *
     * 两条管线的工具不是"都能用、自己挑"，而是**互斥的两套**：
     *
     * | 布局管线 | 截图管线 |
     * |---|---|
     * | `observe` / `click` / `input` / `scroll` / `swipe` | `observe` / `tap` / `swipe` / `screenshot` |
     *
     * 早先两套工具同时挂着，模型看到节点树里读不到东西（游戏、自绘界面）就会
     * 自己去调 `screen_tap`，或者反过来在节点树界面里截图硬看 —— 相当于
     * 把用户选好的"方式"临时换掉。用户看到的是"我明明选了布局识别，它却在截图"。
     *
     * 现在**没挂那个工具**，模型就没有那条路。要换方式只能由用户在设置里换。
     */
    fun all(): List<AgentTool> {
        val vision = ScreenAutomation.pipeline == ScreenAutomation.PIPELINE_VISION
        val set = if (vision) {
            listOf(Observe, Tap, Swipe, Shot, Back, Launch, Wait)
        } else {
            listOf(Observe, Click, Input, Scroll, Swipe, Back, Launch, Wait)
        }
        return set.map { ScreenRisk(it) }
    }

    /**
     * 统一把这一族标成 [ToolRisk.SCREEN]。
     *
     * 不逐个写 `override val risk`：九个工具漏一个，就是"这个操作不问、那个问"，
     * 而漏掉的那个往往正是最危险的一个。用装饰器一次性盖掉，加新工具也自动带上。
     */
    private class ScreenRisk(private val delegate: AgentTool) : AgentTool by delegate {
        override val risk = ToolRisk.SCREEN
    }

    private object Observe : AgentTool {
        override val name = "screen_observe"
        override val description =
            "看当前手机屏幕。布局识别管线返回可见控件（文字、按钮、位置）；截图识别管线返回一张截图" +
                "与屏幕分辨率，适合游戏等没有控件树的界面。操作别的 App 之前先调用它，" +
                "每次点击/输入/滑动之后也要重新调用，因为界面已经变了。"

        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome =
            run("observe", args)
    }

    private object Tap : AgentTool {
        override val name = "screen_tap"
        override val description =
            "按屏幕坐标点一下。只在截图识别管线（游戏 / 自绘界面）里使用：" +
                "坐标是屏幕物理像素，原点在左上角，范围见 screen_observe 返回的 screen 字段。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "number"); put("description", "屏幕 x 像素") }
                putJsonObject("y") { put("type", "number"); put("description", "屏幕 y 像素") }
            }
            putJsonArray("required") { add("x"); add("y") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome =
            ScreenAutomation.execute("click", args)
    }

    private object Click : AgentTool {
        override val name = "screen_click"
        override val description = "点击屏幕上某个控件。参数来自最近一次 screen_observe 返回的 snapshot 与 id。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("snapshot") { put("type", "string"); put("description", "observe 返回的快照 id") }
                putJsonObject("id") { put("type", "string"); put("description", "要点击的节点 id，如 n3") }
            }
            putJsonArray("required") { add("snapshot"); add("id") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("click", args)
    }

    private object Input : AgentTool {
        override val name = "screen_input"
        override val description = "把文字填进屏幕上某个输入框（整体替换原内容）。参数来自最近一次 screen_observe。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("snapshot") { put("type", "string") }
                putJsonObject("id") { put("type", "string") }
                putJsonObject("text") { put("type", "string"); put("description", "要写入的完整内容") }
            }
            putJsonArray("required") { add("snapshot"); add("id"); add("text") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("input_text", args)
    }

    private object Scroll : AgentTool {
        override val name = "screen_scroll"
        override val description = "滚动一个可滚动列表。direction=up 表示看下面的内容（手指上滑）。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("snapshot") { put("type", "string") }
                putJsonObject("id") { put("type", "string") }
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "up / down / left / right")
                }
            }
            putJsonArray("required") { add("snapshot"); add("id") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("scroll", args)
    }

    private object Swipe : AgentTool {
        override val name = "screen_swipe"
        override val description =
            "在某个控件范围内做一次滑动手势（用于列表不响应滚动、或需要拖拽时）。" +
                "截图识别管线下改传 x1/y1/x2/y2 四个屏幕坐标。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("snapshot") { put("type", "string") }
                putJsonObject("id") { put("type", "string") }
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "up / down / left / right")
                }
                putJsonObject("x1") { put("type", "number"); put("description", "截图管线：起点 x") }
                putJsonObject("y1") { put("type", "number"); put("description", "截图管线：起点 y") }
                putJsonObject("x2") { put("type", "number"); put("description", "截图管线：终点 x") }
                putJsonObject("y2") { put("type", "number"); put("description", "截图管线：终点 y") }
            }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("swipe", args)
    }

    private object Back : AgentTool {
        override val name = "screen_back"
        override val description = "按一次系统返回键。走错页面时用它退回去，比重新启动 App 轻。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("back", args)
    }

    private object Launch : AgentTool {
        override val name = "screen_launch"
        override val description = "按包名启动一个 App（例如 com.tencent.mm）。不知道包名时先 screen_observe 看当前界面，或问用户。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("package") { put("type", "string"); put("description", "Android 包名") }
            }
            putJsonArray("required") { add("package") }
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("launch", args)
    }

    private object Shot : AgentTool {
        override val name = "screen_screenshot"
        override val description =
            "截一张当前屏幕。截图识别管线下会直接把图给你看；布局管线下返回文件路径（不会进上下文）。" +
                "只在节点树读不出关键信息（画布、图片、游戏）时才用。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        override val mutating = true

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("screenshot", args)
    }

    private object Wait : AgentTool {
        override val name = "screen_wait"
        override val description = "等待屏幕上出现某段文字（比如加载完成、消息发出）。超时返回失败，别原地反复重试同一次等待。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string"); put("description", "要等待出现的文字") }
                putJsonObject("timeout") { put("type", "integer"); put("description", "最多等几秒，默认 8") }
            }
            putJsonArray("required") { add("text") }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome = run("wait_until", args)
    }

    private suspend fun run(action: String, args: JsonObject): ToolOutcome =
        runCatching { ScreenAutomation.execute(action, args) }
            .getOrElse { ToolOutcome.fail(it.message ?: "屏幕操作失败") }
}
