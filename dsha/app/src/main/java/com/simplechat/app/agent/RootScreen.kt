package com.simplechat.app.agent

import android.content.Context
import android.graphics.Bitmap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Root 通道的屏幕操作。
 *
 * 与无障碍那条路的**语义完全一致**：先 `observe` 拿节点 id，再按 id 点击 ——
 * 这里只是把"手上那套"从 AccessibilityNodeInfo 换成 `uiautomator dump` 出来的
 * XML，把 `performAction` 换成 `input tap x y`。
 *
 * 为什么不给模型一套"坐标工具"：坐标系在横竖屏、状态栏、分屏下全是坑，
 * 而且模型看不见节点就说不出"点右上角那个齿轮"。id 的契约必须两条通道一样，
 * 否则同一个任务在两种权限下会写成两套提示词。
 *
 * 已知边界：
 * - `input text` 只吃 ASCII。中文/emoji 需要 IME 注入，root 也做不到干净，
 *   所以遇到非 ASCII 直接拒绝，并让调用方回到无障碍通道 —— 比静默丢字好。
 * - 截屏走 `screencap`，不检测密码框（无障碍那条会）。所以截图前要求先 observe，
 *   有 `[已隐藏]` 节点就拒绝，这条规则在这里手动补。
 */
object RootScreen {

    private data class Node(
        val id: String,
        val text: String,
        val desc: String,
        val className: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private data class Snap(val token: String, val pkg: String, val nodes: List<Node>)

    private var snapshot: Snap? = null

    /** 单次观察最多带多少个节点。dump 出来的树能有上千个，全给模型没有意义。 */
    private const val MaxNodes = 120

    suspend fun execute(action: String, args: JsonObject, context: Context): String = when (action) {
        "observe" -> observe()
        "click" -> tap(context, args, long = false)
        "long_click" -> tap(context, args, long = true)
        "input_text" -> inputText(context, args)
        "swipe" -> swipe(context, args, scroll = false)
        "scroll" -> swipe(context, args, scroll = true)
        "back" -> {
            snapshot = null
            RootShell.exec("input keyevent 4")
            """{"requested":"back","next":"observe"}"""
        }
        "launch" -> launch(args)
        "screenshot" -> screenshot(context)
        "assert_text" -> assertText(args)
        "wait_until" -> waitUntil(args)
        else -> error("root 通道不支持的动作：$action")
    }

    /**
     * root 通道的"看屏"：`screencap` 写进应用私有目录，读完立刻删。
     *
     * 为什么不用 `screencap -p | base64`：一张 1080×2400 的 PNG 有 2–5 MB，
     * base64 再过一遍 shell 是十几 MB 的字符串，光拼接就能把主线程卡住。
     * 写文件走的是内核，代价固定；文件在私有目录里，只有本 App 能读。
     */
    /** 按屏幕坐标点一下（截图管线的落点）。 */
    suspend fun tapAt(context: Context, x: Int, y: Int) {
        com.simplechat.app.service.TouchTrace.tap(context, x.toFloat(), y.toFloat())
        RootShell.exec("input tap $x $y", 8_000)
    }

    suspend fun swipeAt(context: Context, sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Int) {
        com.simplechat.app.service.TouchTrace.swipe(
            context, sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), durationMs.toLong(),
        )
        RootShell.exec("input swipe $sx $sy $ex $ey $durationMs", 10_000)
    }

    suspend fun captureScreen(context: Context): Bitmap? {
        val file = File(context.filesDir, "screen/vision_capture.png").apply {
            parentFile?.mkdirs()
        }
        runCatching {
            RootShell.exec("screencap -p \"${file.absolutePath}\"; chmod 644 \"${file.absolutePath}\"", 20_000)
        }
        return ScreenVision.decodeAndDelete(file)
    }

    // ── 观察 ──────────────────────────────────────────────────

    private suspend fun observe(): String {
        val raw = RootShell.exec(
            "uiautomator dump /data/local/tmp/dsha_ui.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/dsha_ui.xml; rm -f /data/local/tmp/dsha_ui.xml",
            20_000,
        )
        val xml = raw.substringAfter('<', "").let { if (it.isBlank()) "" else "<$it" }
        if (xml.isBlank()) error("uiautomator dump 没有返回内容，可能当前界面不允许 dump（如系统弹窗）")

        val token = java.util.UUID.randomUUID().toString()
        val nodes = parse(xml)
        if (nodes.isEmpty()) error("当前界面没有可读控件")
        snapshot = Snap(token, packageOf(xml), nodes)

        val array = mutableListOf<JsonObject>()
        for (node in nodes.take(MaxNodes)) {
            array += buildJsonObject {
                put("id", node.id)
                put("text", node.text)
                put("desc", node.desc)
                put("class", node.className)
                putJsonArray("bounds") {
                    add(node.left); add(node.top); add(node.right); add(node.bottom)
                }
                put("clickable", node.clickable)
                put("editable", node.editable)
                put("scrollable", node.scrollable)
                put("enabled", true)
            }
        }
        return buildJsonObject {
            put("snapshot", token)
            put("package", snapshot?.pkg.orEmpty())
            put("channel", "root")
            put("nodes", JsonArray(array.toList()))
            put("truncated", nodes.size > MaxNodes)
        }.toString()
    }

    private fun packageOf(xml: String): String =
        Regex("\\bpackage=\"([^\"]+)\"").find(xml)?.groupValues?.get(1).orEmpty()

    private val tagPattern = Regex("<node\\b[^>]*>")
    private val boundsPattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    private fun parse(xml: String): List<Node> {
        val result = mutableListOf<Node>()
        for (tag in tagPattern.findAll(xml).map { it.value }) {
            val text = unescape(attr(tag, "text"))
            val desc = unescape(attr(tag, "content-desc"))
            val clickable = attr(tag, "clickable") == "true"
            val scrollable = attr(tag, "scrollable") == "true"
            val editable = attr(tag, "class").contains("EditText")
            val focusable = attr(tag, "focusable") == "true"
            // 没有文字、不能点、不能滚、也不是输入框的容器节点对模型没有意义
            if (text.isBlank() && desc.isBlank() && !clickable && !scrollable && !editable) continue
            val bounds = boundsPattern.find(attr(tag, "bounds")) ?: continue
            val (l, t, r, b) = bounds.destructured
            val left = l.toIntOrNull() ?: continue
            val top = t.toIntOrNull() ?: continue
            val right = r.toIntOrNull() ?: continue
            val bottom = b.toIntOrNull() ?: continue
            if (right - left <= 1 || bottom - top <= 1) continue
            result += Node(
                id = "n${result.size + 1}",
                text = text.take(200),
                desc = desc.take(120),
                className = attr(tag, "class").substringAfterLast('.'),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                clickable = clickable || focusable && text.isNotBlank(),
                editable = editable,
                scrollable = scrollable,
            )
        }
        return result
    }

    private fun attr(tag: String, name: String): String =
        Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1).orEmpty()

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#10;", " ")
        .replace("&apos;", "'")

    // ── 动作 ──────────────────────────────────────────────────

    private fun resolve(args: JsonObject): Node {
        val snap = snapshot ?: error("请先 screen_observe 再操作")
        val token = args.argText("snapshot").orEmpty()
        if (token.isNotBlank() && token != snap.token) error("快照已过期，请重新 screen_observe")
        val id = args.argText("id") ?: error("缺少 id")
        return snap.nodes.firstOrNull { it.id == id }
            ?: error("找不到节点 $id，界面可能已经变了，请重新 screen_observe")
    }

    private suspend fun tap(context: Context, args: JsonObject, long: Boolean): String {
        val node = resolve(args)
        snapshot = null
        if (long) {
            com.simplechat.app.service.TouchTrace.longPress(
                context, node.centerX.toFloat(), node.centerY.toFloat(),
            )
        } else {
            com.simplechat.app.service.TouchTrace.tap(
                context, node.centerX.toFloat(), node.centerY.toFloat(),
            )
        }
        if (long) {
            RootShell.exec(
                "input swipe ${node.centerX} ${node.centerY} ${node.centerX} ${node.centerY} 700",
            )
            return """{"accepted":"long_click","next":"observe"}"""
        }
        RootShell.exec("input tap ${node.centerX} ${node.centerY}")
        return """{"accepted":"click","next":"observe"}"""
    }

    private suspend fun inputText(context: Context, args: JsonObject): String {
        val node = resolve(args)
        val text = args.argText("text").orEmpty()
        require(text.isNotEmpty()) { "缺少 text" }
        require(text.length <= 2000) { "输入内容过长（上限 2000 字）" }
        if (text.any { it.code > 0x7E }) {
            error("root 通道的 input text 只能输入 ASCII；中文请改用无障碍服务（设置 → 操作屏幕）")
        }
        snapshot = null
        // 先点一下拿到焦点，再整体输入：部分输入框不聚焦时不接收按键
        com.simplechat.app.service.TouchTrace.input(
            context, node.centerX.toFloat(), node.centerY.toFloat(), text,
        )
        RootShell.exec("input tap ${node.centerX} ${node.centerY}")
        Thread.sleep(200)
        RootShell.exec("input text ${shellQuote(text)}")
        return """{"accepted":"input_text","next":"observe"}"""
    }

    private suspend fun swipe(context: Context, args: JsonObject, scroll: Boolean): String {
        val node = resolve(args)
        snapshot = null
        val cx = node.centerX
        val cy = node.centerY
        val ratio = if (scroll) 0.35f else 0.4f
        val (fromX, fromY, toX, toY) = when (args.argText("direction") ?: "up") {
            "down" -> listOf(cx, (node.top + node.height * 0.25f).toInt(), cx, (node.bottom - node.height * 0.25f).toInt())
            "left" -> listOf((node.right - node.width * 0.2f).toInt(), cy, (node.left + node.width * 0.2f).toInt(), cy)
            "right" -> listOf((node.left + node.width * 0.2f).toInt(), cy, (node.right - node.width * 0.2f).toInt(), cy)
            else -> listOf(cx, (node.bottom - node.height * ratio).toInt(), cx, (node.top + node.height * ratio).toInt())
        }
        com.simplechat.app.service.TouchTrace.swipe(
            context, fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat(), 320,
        )
        RootShell.exec("input swipe $fromX $fromY $toX $toY 320")
        return """{"requested":"${if (scroll) "scroll" else "swipe"}","next":"observe"}"""
    }

    private suspend fun launch(args: JsonObject): String {
        val pkg = args.argText("package") ?: error("缺少 package")
        require(pkg.matches(Regex("^[a-zA-Z0-9_.]+$"))) { "包名不合法：$pkg" }
        snapshot = null
        RootShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        return """{"launched":"$pkg","next":"observe"}"""
    }

    private suspend fun screenshot(context: Context): String {
        val snap = snapshot
        if (snap != null && snap.nodes.any { it.text == "[已隐藏]" }) {
            error("当前界面有密码输入框，不截屏")
        }
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        trim(dir)
        val name = "root-${SimpleDateFormat("HHmmss", Locale.US).format(Date())}.png"
        val file = File(dir, name)
        RootShell.exec("screencap -p ${shellQuote(file.absolutePath)}", 20_000)
        if (!file.exists() || file.length() == 0L) error("截屏失败（文件为空）")
        return buildJsonObject {
            put("screenshot", file.absolutePath)
            put("package", snap?.pkg.orEmpty())
            put("bytes", file.length())
        }.toString()
    }

    private fun trim(dir: File) {
        runCatching {
            dir.listFiles().orEmpty().filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .drop(6)
                .forEach { it.delete() }
        }
    }

    private suspend fun assertText(args: JsonObject): String {
        val expected = args.argText("text").orEmpty()
        require(expected.isNotBlank()) { "缺少 text" }
        observe()
        val found = snapshot?.nodes.orEmpty().any { it.text.contains(expected) || it.desc.contains(expected) }
        if (!found) error("屏幕上没有找到「$expected」")
        return """{"found":"${expected}"}"""
    }

    private suspend fun waitUntil(args: JsonObject): String {
        val expected = args.argText("text").orEmpty()
        require(expected.isNotBlank()) { "缺少 text" }
        val timeout = (args.argText("timeout")?.toIntOrNull() ?: 8).coerceIn(1, 60)
        val deadline = System.currentTimeMillis() + timeout * 1000L
        while (System.currentTimeMillis() < deadline) {
            runCatching { observe() }
            if (snapshot?.nodes.orEmpty().any { it.text.contains(expected) || it.desc.contains(expected) }) {
                return """{"found":"$expected"}"""
            }
            Thread.sleep(600)
        }
        error("等了 ${timeout}s 仍没有出现「$expected」")
    }

    /** shell 单引号包裹：内部单引号写成 `'\''`。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
