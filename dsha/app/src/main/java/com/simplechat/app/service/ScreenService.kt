package com.simplechat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.simplechat.app.agent.Workspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 「操作屏幕」的无障碍服务。
 *
 * 它只做一件事：把**当前前台**界面的无障碍节点整理成结构化快照，并按快照里的
 * 节点 id 执行点击 / 输入 / 滑动。不做 OCR、不做图像识别 —— 那些方案又慢又贵，
 * 而 Android 的节点树已经带文本与可点击范围，够用了。
 *
 * ### 三条安全边界
 *
 * 1. **只看前台**：`rootInActiveWindow` 拿到的就是用户正看着的那一屏，
 *    不存在"偷偷读后台应用"。
 * 2. **快照过期不执行**：点击必须带上 observe 返回的 `snapshot` 与 `id`，
 *    并且节点指纹（文本 + 位置 + 类名）要对得上。否则界面早翻页了，
 *    按坐标点下去就是误触。
 * 3. **密码框一律不读不点**：`isPassword` 的节点文本替换成 `[已隐藏]`，
 *    连同它的子节点一起；操作该类节点直接报错。
 *
 * 服务本身不缓存任何东西到磁盘 —— 截图除外（存 `files/screen/shots`，只留最近 20 张）。
 */
class ScreenService : AccessibilityService() {

    private var snapshot: Snapshot? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // 不订阅任何事件：界面变化靠模型主动 observe，事件流只会白耗电
            eventTypes = 0
        }
        serviceGeneration.incrementAndGet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        snapshot = null
        generation.incrementAndGet()
    }

    override fun onDestroy() {
        instance = null
        snapshot = null
        generation.incrementAndGet()
        super.onDestroy()
    }

    /** 主动断开当前快照（用户停止 / 切换目标时）。 */
    fun reset() {
        snapshot = null
        generation.incrementAndGet()
    }

    /**
     * 执行一个动作。返回值是给模型看的 JSON 文本（成功）或抛异常（失败）。
     *
     * 所有调用都走主线程：无障碍 API 不是线程安全的。
     */
    suspend fun execute(action: String, args: JsonObject): String = withContext(Dispatchers.Main.immediate) {
        // 启动应用不需要"当前界面"：用户很可能正停在 DSHA 里说"帮我打开微信"
        if (action == "launch") return@withContext launch(args.str("package").orEmpty())

        val root = foregroundRoot()
        val actualPackage = root.packageName?.toString().orEmpty()
        if (actualPackage == packageName) error("不能操作 DSHA 自己，请先切到目标应用")
        if (actualPackage in BlockedPackages || isPermissionController(actualPackage)) {
            error("该界面（$actualPackage）涉及系统权限，出于安全不操作")
        }

        when (action) {
            "observe" -> observe(root)
            "assert_text" -> {
                val expected = args.str("text").orEmpty()
                require(expected.isNotBlank()) { "缺少 text" }
                val collected = collect(root)
                if (collected.nodes.none { !it.sensitive && (it.text.contains(expected) || it.description.contains(expected)) }) {
                    error("屏幕上没有找到「$expected」")
                }
                """{"assertion":"passed"}"""
            }
            "wait_until" -> waitUntil(root, args.str("text").orEmpty(), (args.int("timeout") ?: 8).coerceIn(1, 30) * 1000L)
            "screenshot" -> screenshot(actualPackage, root)
            "back" -> {
                snapshot = null
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) error("返回失败")
                """{"requested":"back","next":"observe"}"""
            }
            "scroll" -> scroll(root, args)
            "swipe" -> swipe(root, args)
            "click", "long_click", "input_text" -> tapLike(root, action, args)
            else -> error("不支持的动作：$action")
        }
    }

    // ── 观察 ──────────────────────────────────────────────────

    private fun observe(root: AccessibilityNodeInfo): String {
        val collected = collect(root)
        val token = UUID.randomUUID().toString()
        val array = mutableListOf<JsonObject>()
        for (node in collected.nodes.take(MaxNodes)) {
            array += buildJsonObject {
                put("id", node.id)
                put("text", if (node.sensitive) "[已隐藏]" else node.text)
                put("desc", if (node.sensitive) "" else node.description)
                put("class", node.className.substringAfterLast('.'))
                put(
                    "bounds",
                    buildJsonArray {
                        add(node.bounds.left)
                        add(node.bounds.top)
                        add(node.bounds.right)
                        add(node.bounds.bottom)
                    },
                )
                put("clickable", node.clickable)
                put("editable", node.editable)
                put("scrollable", node.scrollable)
                put("enabled", node.enabled)
            }
        }
        var payload = buildJsonObject {
            put("snapshot", token)
            put("package", root.packageName?.toString().orEmpty())
            put("nodes", JsonArray(array.toList()))
            put("truncated", collected.truncated || array.size < collected.nodes.size)
        }.toString()
        // 60KB 预算：超出就从头裁节点，宁可少给几个，也不要发一个被网关截断的 JSON
        while (payload.toByteArray(Charsets.UTF_8).size > MaxPayload && array.size > 1) {
            array.removeAt(array.size - 1)
            payload = buildJsonObject {
                put("snapshot", token)
                put("package", root.packageName?.toString().orEmpty())
                put("nodes", JsonArray(array.toList()))
                put("truncated", true)
            }.toString()
        }
        snapshot = Snapshot(token, System.currentTimeMillis(), root.packageName?.toString().orEmpty(), root.windowId,
            collected.nodes.take(array.size))
        return payload
    }

    private fun collect(root: AccessibilityNodeInfo): Collected {
        val nodes = mutableListOf<Node>()
        var visited = 0
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, sensitiveParent: Boolean) {
            if (++visited > MaxVisited || path.size > 20) { truncated = true; return }
            val sensitive = sensitiveParent || node.isPassword
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser) {
                nodes += Node(
                    id = "n${nodes.size + 1}",
                    path = path,
                    fingerprint = fingerprint(node),
                    sensitive = sensitive,
                    text = if (sensitive) "" else node.text?.toString().orEmpty().take(200),
                    description = if (sensitive) "" else node.contentDescription?.toString().orEmpty().take(120),
                    className = node.className?.toString().orEmpty().take(120),
                    bounds = bounds,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                )
            }
            for (index in 0 until node.childCount.coerceAtMost(64)) {
                if (visited >= MaxVisited) { truncated = true; break }
                node.getChild(index)?.let { visit(it, path + index, sensitive) }
            }
        }
        visit(root, emptyList(), false)
        return Collected(nodes, truncated)
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return listOf(
            node.packageName, node.className, node.viewIdResourceName, bounds.toShortString(),
            node.isPassword, node.isEditable,
            if (node.isPassword) "" else node.text?.toString(),
            if (node.isPassword) "" else node.contentDescription?.toString(),
        ).joinToString("\u001f")
    }

    // ── 动作 ──────────────────────────────────────────────────

    private fun tapLike(root: AccessibilityNodeInfo, action: String, args: JsonObject): String {
        val node = resolve(root, args)
        snapshot = null
        // 先画轨迹再动手：用户看到的是"AI 的手落在这儿"，而不是界面自己跳了
        val bounds = Rect().also(node::getBoundsInScreen)
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val typed = args.str("text").orEmpty()
        when (action) {
            "input_text" -> TouchTrace.input(this, cx, cy, typed)
            "long_click" -> TouchTrace.longPress(this, cx, cy)
            else -> TouchTrace.tap(this, cx, cy)
        }
        val accepted = when (action) {
            "input_text" -> {
                if (!node.isEditable) error("这个节点不可编辑")
                val text = typed
                require(text.length <= 2000 && '\u0000' !in text) { "输入内容不合法" }
                // 先聚焦再整体替换：分两步是因为部分输入框 ACTION_SET_TEXT 需要先拿到焦点
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
                )
            }
            "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            else -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!accepted) error("系统拒绝了这次操作（节点可能已不可点）")
        return """{"accepted":"$action","next":"observe"}"""
    }

    private fun scroll(root: AccessibilityNodeInfo, args: JsonObject): String {
        val node = resolve(root, args)
        if (!node.isScrollable) error("这个节点不能滚动")
        val direction = when (args.str("direction") ?: "up") {
            // 语义按"手指方向"而不是"内容方向"：up = 手指上滑 = 看下面的内容
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else -> error("direction 只能是 up/down/left/right")
        }
        snapshot = null
        if (!node.performAction(direction)) error("滚动失败")
        return """{"requested":"scroll","next":"observe"}"""
    }

    private suspend fun swipe(root: AccessibilityNodeInfo, args: JsonObject): String {
        val node = resolve(root, args)
        val bounds = Rect().also(node::getBoundsInScreen)
        val direction = args.str("direction") ?: "up"
        require(direction in setOf("up", "down", "left", "right")) { "direction 只能是 up/down/left/right" }
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val dx = bounds.width() * 0.28f
        val dy = bounds.height() * 0.28f
        val (sx, sy, ex, ey) = when (direction) {
            "up" -> listOf(x, y + dy, x, y - dy)
            "down" -> listOf(x, y - dy, x, y + dy)
            "left" -> listOf(x + dx, y, x - dx, y)
            else -> listOf(x - dx, y, x + dx, y)
        }
        snapshot = null
        TouchTrace.swipe(this, sx, sy, ex, ey, 350)
        gesture(sx, sy, ex, ey, 350)
        return """{"requested":"swipe","next":"observe"}"""
    }

    private fun launch(packageName: String): String {
        require(packageName.isNotBlank() && '.' in packageName) { "包名不合法" }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: error("找不到 $packageName 的启动入口")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        snapshot = null
        return """{"launched":"$packageName","next":"observe"}"""
    }

    private suspend fun waitUntil(root: AccessibilityNodeInfo, text: String, timeoutMs: Long): String {
        require(text.isNotBlank()) { "缺少 text" }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = rootInActiveWindow ?: root
            if (collect(current).nodes.any { !it.sensitive && (it.text.contains(text) || it.description.contains(text)) }) {
                return """{"waited":true,"text":"${text.replace("\"", "'")}"}"""
            }
            kotlinx.coroutines.delay(150)
        }
        error("等待超时：${timeoutMs / 1000} 秒内没有出现「$text」")
    }

    /**
     * 解析快照里的节点。
     *
     * 走"快照 token → 路径 → 指纹校验"三步：只认路径会点错（列表滚动后同一位置
     * 换了内容），只认文本又点不到纯图标按钮。
     */
    private fun resolve(root: AccessibilityNodeInfo, args: JsonObject): AccessibilityNodeInfo {
        val observed = snapshot ?: error("请先 observe，再用返回的 snapshot 与 id 操作")
        if (observed.token != args.str("snapshot")) error("快照已失效，请重新 observe")
        if (System.currentTimeMillis() - observed.createdAt > SnapshotTtlMs) error("快照超过 60 秒，请重新 observe")
        if (observed.packageName != root.packageName?.toString() || observed.windowId != root.windowId) {
            error("界面已经切换，请重新 observe")
        }
        val remembered = observed.nodes.firstOrNull { it.id == args.str("id") }
            ?: error("快照里没有这个 id")
        if (remembered.sensitive) error("这是密码/隐私节点，不操作")

        var node: AccessibilityNodeInfo = root
        for (index in remembered.path) {
            node = node.getChild(index) ?: error("控件已消失，请重新 observe")
        }
        if (fingerprint(node) != remembered.fingerprint) error("控件内容已变化，请重新 observe")
        if (!node.isVisibleToUser || !node.isEnabled) error("控件不可见或已禁用")
        var parent = node.parent
        var depth = 0
        while (parent != null && ++depth <= 40) {
            if (parent.isPassword) error("所在容器是密码输入区，不操作")
            parent = parent.parent
        }
        return node
    }

    private fun foregroundRoot(): AccessibilityNodeInfo {
        val root = rootInActiveWindow ?: error("拿不到当前界面，确认无障碍服务已开启")
        root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: error("当前界面没有包名")
        return root
    }

    private suspend fun gesture(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long) {
        val epoch = generation.get()
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (!continuation.isActive) return
                    if (generation.get() == epoch) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException("操作已中断"))
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手势被系统取消"))
                }
            }, null)
            if (!accepted && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("手势没有被受理"))
            }
        }
    }

    private suspend fun screenshot(packageName: String, root: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT < 30) error("系统版本低于 Android 11，不支持截屏")
        val collected = collect(root)
        if (collected.nodes.any { it.sensitive }) error("当前界面有密码输入框，不截屏")

        val bitmap = takeShot(root)
        return withContext(Dispatchers.IO) {
            val dir = File(Workspace.appDir(), "screen/shots").apply { mkdirs() }
            trimShots(dir)
            val file = File(dir, "${System.currentTimeMillis()}.png")
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            } finally {
                bitmap.recycle()
            }
            buildJsonObject {
                put("screenshot", file.absolutePath)
                put("package", packageName)
                put("bytes", file.length())
            }.toString()
        }
    }

    /**
     * 只在内存里截一张，**不落盘**（截图识别管线用）。
     *
     * 与 [screenshot] 共用同一段 TakeScreenshotCallback：两条路唯一的区别是
     * 拿到 Bitmap 之后一个写文件、一个直接交给模型。
     */
    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Main.immediate) {
        runCatching {
            if (Build.VERSION.SDK_INT < 30) return@runCatching null
            val root = rootInActiveWindow ?: return@runCatching null
            // 与布局管线同一条边界：不截自己、不截系统权限页
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName || pkg in BlockedPackages) return@runCatching null
            if (collect(root).nodes.any { it.sensitive }) return@runCatching null
            takeShot(root)
        }.getOrNull()
    }

    /** 按屏幕坐标点一下（截图管线的落点）。 */
    suspend fun tapAt(x: Float, y: Float) = withContext(Dispatchers.Main.immediate) {
        TouchTrace.tap(this@ScreenService, x, y)
        gesture(x, y, x, y, 60)
        snapshot = null
    }

    suspend fun swipeAt(x1: Float, y1: Float, x2: Float, y2: Float) = withContext(Dispatchers.Main.immediate) {
        TouchTrace.swipe(this@ScreenService, x1, y1, x2, y2, 320)
        gesture(x1, y1, x2, y2, 320)
        snapshot = null
    }

    private suspend fun takeShot(root: AccessibilityNodeInfo): Bitmap {
        val window = root.window ?: error("拿不到窗口信息")
        val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val copied = try {
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped?.recycle() }
                    } catch (_: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    if (!continuation.isActive) { copied?.recycle(); return }
                    if (copied == null) continuation.resumeWithException(IllegalStateException("截屏失败"))
                    else continuation.resume(copied)
                }

                override fun onFailure(errorCode: Int) {
                    val hint = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "这是安全界面（如支付 / 密码），系统禁止截屏"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏太频繁，稍等一秒再试"
                        else -> "截屏失败（$errorCode）"
                    }
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(hint))
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                takeScreenshotOfWindow(window.id, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }
        return bitmap
    }

    /** 只留最近 20 张：截图是给模型当证据的，不是相册。 */
    private fun trimShots(dir: File) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
        // 只留最近几张：一轮操作里模型最多回看上一张，留多了纯属堆积
        files.drop(6).forEach { runCatching { it.delete() } }
    }

    private fun isPermissionController(pkg: String): Boolean =
        pkg.contains("permissioncontroller", ignoreCase = true) ||
            pkg.contains("packageinstaller", ignoreCase = true)

    private data class Node(
        val id: String,
        val path: List<Int>,
        val fingerprint: String,
        val sensitive: Boolean,
        val text: String,
        val description: String,
        val className: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
    )

    private data class Collected(val nodes: List<Node>, val truncated: Boolean)

    private data class Snapshot(
        val token: String,
        val createdAt: Long,
        val packageName: String,
        val windowId: Int,
        val nodes: List<Node>,
    )

    companion object {
        @Volatile
        private var instance: ScreenService? = null

        /** 服务连接计数：界面用它判断"开关开了但没连上"这种中间态。 */
        val serviceGeneration = java.util.concurrent.atomic.AtomicLong()

        fun isConnected(): Boolean = instance != null

        fun current(): ScreenService? = instance

        private const val MaxVisited = 300
        private const val MaxNodes = 80
        private const val MaxPayload = 60_000
        private const val SnapshotTtlMs = 60_000L

        /** 永不操作的界面：系统 UI 与权限弹窗 —— 误点一下可能就是"允许"。 */
        private val BlockedPackages = setOf("com.android.systemui", "android")
    }
}

/** 从 JsonObject 取字符串，null / 空串都算没有。 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()
