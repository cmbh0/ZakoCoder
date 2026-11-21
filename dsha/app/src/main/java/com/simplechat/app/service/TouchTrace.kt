package com.simplechat.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.simplechat.app.agent.RootShell
import com.simplechat.app.util.AppLog
import kotlin.math.hypot

/**
 * AI 动手时的**触摸轨迹**。
 *
 * 为什么要有它：模型点一下屏幕只需要 200ms，用户什么也看不见 —— 只看到界面
 * 自己跳了。尤其在这种场景里（"帮我点开始匹配"），人会怀疑是 App 抽风，
 * 而不是"AI 在替我操作"。
 *
 * 画法：
 * - **点按**：一圈扩散的环 + 一个渐隐的实心点，像水波；
 * - **滑动**：一条从起点向终点生长的高亮线，头部带光点，画完停留 300ms 再淡出。
 *
 * 图层优先级：无障碍服务在的时候用 `TYPE_ACCESSIBILITY_OVERLAY`（不要额外权限、
 * 天生悬浮在别的应用之上）；root 通道没有无障碍时退回 `TYPE_APPLICATION_OVERLAY`，
 * 没有悬浮窗权限就安静地不画 —— 轨迹是提示，不该让一次点击因此失败。
 */
object TouchTrace {

    private const val TapLife = 520f
    private const val SwipeLife = 620f
    private const val LongLife = 1100f
    private const val InputLife = 1400f
    private const val Accent = 0xFF4D6BFE.toInt()

    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var view: TraceView? = null
    private var attached = false

    fun tap(context: Context, x: Float, y: Float) {
        post(context) { it.tap(x, y) }
    }

    fun swipe(context: Context, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        post(context) { it.swipe(sx, sy, ex, ey, durationMs) }
    }

    /** 长按：一圈"按住不动"的收缩环，比点按多停留一会儿。 */
    fun longPress(context: Context, x: Float, y: Float) {
        post(context) { it.longPress(x, y) }
    }

    /** 输入文字：落点 + 一枚显示输入内容的小标签。 */
    fun input(context: Context, x: Float, y: Float, text: String) {
        post(context) { it.input(x, y, text) }
    }

    /** 整轮结束或动作失败时立刻擦干净（窗口留着复用，见 [clear] 的说明）。 */
    fun clear() {
        main.post {
            view?.clearAll()
        }
    }

    /**
     * 确保能画（root 通道下顺手补上悬浮窗授权）。
     *
     * 这是"轨迹不显示"最常见的根因：用户走 root 通道，没有无障碍服务，
     * 而 `Settings.canDrawOverlays` 为 false —— 早先的代码在这种情况下
     * **安静地什么都不画**，于是"AI 点了屏幕但看不到任何轨迹"。
     *
     * root 可用时直接用 `appops` 把这项权限授给本应用：这是用户自己的设备、
     * 自己授权的 root，而他期待的正是"看得见 AI 在点哪里"。失败也不报错 ——
     * 轨迹只是提示，不该让一次点击因此失败。
     */
    suspend fun ensureOverlay(context: Context) {
        if (ScreenService.current() != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Settings.canDrawOverlays(context)) return
        if (overlayGrantTried) return
        overlayGrantTried = true
        runCatching {
            if (!RootShell.available) RootShell.probe()
            if (!RootShell.available) return@runCatching
            val pkg = context.packageName
            RootShell.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow", 8_000)
            AppLog.i(LogTag, "已用 root 授予悬浮窗权限，轨迹可以绘制")
        }.onFailure {
            AppLog.w(LogTag, "补悬浮窗权限失败：${it.message}")
        }
    }

    @Volatile
    private var overlayGrantTried = false

    private const val LogTag = "TouchTrace"

    private fun post(context: Context, block: (TraceView) -> Unit) {
        val app = context.applicationContext
        main.post {
            runCatching {
                val v = ensureView(app) ?: run {
                    AppLog.w(
                        LogTag,
                        "轨迹层挂不上：无障碍服务未运行且没有悬浮窗权限",
                    )
                    return@post
                }
                block(v)
                v.invalidate()
            }
        }
    }

    private fun ensureView(app: Context): TraceView? {
        view?.let { if (attached) return it }
        /*
         * 优先找无障碍服务的实例：它自带 overlay 权限，还能跨应用显示。
         * 没有就走普通悬浮窗，需要用户在设置里给过权限。
         */
        val service = ScreenService.current()
        val context = service ?: app
        val type = if (service != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) return null
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return null
        val v = TraceView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return runCatching {
            wm.addView(v, params)
            manager = wm
            view = v
            attached = true
            v
        }.getOrNull()
    }

    /** 只摘掉指定那一层：旧实例的延时任务不该把新挂上的窗口一起收走。 */
    private fun detachIf(target: TraceView?) {
        if (target != null && view === target) detach()
    }

    private fun detach() {
        val v = view ?: return
        val wm = manager ?: return
        runCatching { wm.removeViewImmediate(v) }
        view = null
        attached = false
    }

    private enum class Kind { TAP, SWIPE, LONG, INPUT }

    /** 一个事件。点按是"零长度"的滑动，但长按与输入有自己的画法。 */
    private class Event(
        val sx: Float,
        val sy: Float,
        val ex: Float,
        val ey: Float,
        val duration: Long,
        val startAt: Long,
        val kind: Kind,
        val text: String = "",
    )

    private class TraceView(context: Context) : View(context) {

        private val events = mutableListOf<Event>()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = Accent
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Accent
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3.5f)
            strokeCap = Paint.Cap.ROUND
            color = Accent
        }
        private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(7f)
            color = Accent
            alpha = 40
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xE6181A1F.toInt()
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(11f)
        }

        private val ticker = object : Runnable {
            override fun run() {
                invalidate()
                if (events.isNotEmpty()) postInvalidateOnAnimation()
            }
        }

        fun tap(x: Float, y: Float) {
            events += Event(x, y, x, y, 0, System.currentTimeMillis(), Kind.TAP)
            schedule()
        }

        fun swipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
            events += Event(
                sx, sy, ex, ey, durationMs.coerceIn(80, 2000),
                System.currentTimeMillis(), Kind.SWIPE,
            )
            schedule()
        }

        fun longPress(x: Float, y: Float) {
            events += Event(x, y, x, y, 0, System.currentTimeMillis(), Kind.LONG)
            schedule()
        }

        fun input(x: Float, y: Float, text: String) {
            events += Event(
                x, y, x, y, 0,
                System.currentTimeMillis(), Kind.INPUT, text.take(24),
            )
            schedule()
        }

        fun clearAll() {
            events.clear()
            invalidate()
        }

        private fun schedule() {
            removeCallbacks(ticker)
            post(ticker)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val dead = mutableListOf<Event>()
            for (e in events) {
                val elapsed = now - e.startAt
                when (e.kind) {
                    Kind.TAP -> {
                        if (elapsed > TapLife) { dead += e; continue }
                        val t = (elapsed / TapLife).coerceIn(0f, 1f)
                        val radius = dp(10f) + dp(34f) * t
                        ring.alpha = ((1f - t) * 210).toInt()
                        canvas.drawCircle(e.sx, e.sy, radius, ring)
                        fill.alpha = ((1f - t) * 190).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(5f) * (1f - t * 0.4f), fill)
                    }

                    Kind.SWIPE -> {
                        val drawMs = e.duration + SwipeLife
                        if (elapsed > drawMs) { dead += e; continue }
                        val travel = (elapsed.toFloat() / e.duration).coerceIn(0f, 1f)
                        val hx = e.sx + (e.ex - e.sx) * travel
                        val hy = e.sy + (e.ey - e.sy) * travel
                        val fade = if (elapsed <= e.duration) 1f
                        else 1f - (elapsed - e.duration) / SwipeLife
                        glow.alpha = (45 * fade).toInt()
                        canvas.drawLine(e.sx, e.sy, hx, hy, glow)
                        line.alpha = (230 * fade).toInt()
                        canvas.drawLine(e.sx, e.sy, hx, hy, line)
                        // 起点小圈 + 头部光点
                        ring.alpha = (150 * fade).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(7f), ring)
                        head.alpha = (240 * fade).toInt()
                        canvas.drawCircle(hx, hy, dp(4.5f), head)
                    }

                    Kind.LONG -> {
                        if (elapsed > LongLife) { dead += e; continue }
                        val t = (elapsed / LongLife).coerceIn(0f, 1f)
                        // 前 25% 快速收拢，之后几乎不动 —— "按住"的体感
                        val press = (t / 0.25f).coerceIn(0f, 1f)
                        val fade = 1f - ((t - 0.7f) / 0.3f).coerceIn(0f, 1f)
                        ring.alpha = (220 * fade).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(26f) - dp(9f) * press, ring)
                        glow.alpha = (60 * fade).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(15f), glow)
                        fill.alpha = (200 * fade).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(5f), fill)
                    }

                    Kind.INPUT -> {
                        if (elapsed > InputLife) { dead += e; continue }
                        val fade = 1f - ((elapsed - 700f) / (InputLife - 700f)).coerceIn(0f, 1f)
                        val t = (elapsed / 240f).coerceIn(0f, 1f)
                        ring.alpha = ((1f - t) * 180 * fade).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(9f) + dp(26f) * t, ring)
                        fill.alpha = (200 * fade).toInt()
                        canvas.drawCircle(e.sx, e.sy, dp(4.5f), fill)
                        if (e.text.isNotEmpty()) {
                            drawLabel(canvas, e.sx, e.sy - dp(24f), "输入 " + e.text, fade)
                        }
                    }
                }
            }
            if (dead.isNotEmpty()) events.removeAll(dead)
            if (events.isEmpty()) {
                removeCallbacks(ticker)
                /*
                 * 停手一秒多再摘窗口，而不是 60ms。
                 *
                 * 连续动作用户会看到好几次点击，每次都 add/remove 一次悬浮窗既费时
                 * 又容易出现"第一下没画出来"（窗口还没挂上，事件已经过了）。
                 * 一秒多的滞留对用户无感（窗口完全透明且不吃触摸），
                 * 但能让一轮里的所有动作复用同一个图层。
                 */
                main.postDelayed({ if (events.isEmpty()) detachSafely() }, 1_200)
            }
        }

        private fun detachSafely() {
            if (events.isNotEmpty()) return
            runCatching { TouchTrace.detachIf(this) }
        }

        /** 输入内容的标签：圆角深色底 + 白字，靠近落点但不遮住它。 */
        private fun drawLabel(canvas: Canvas, cx: Float, bottomY: Float, text: String, fade: Float) {
            if (fade <= 0f) return
            labelPaint.textSize = dp(11f)
            val padH = dp(8f)
            val padV = dp(5f)
            val width = labelPaint.measureText(text) + padH * 2
            val height = dp(11f) * 1.5f + padV * 2
            val left = (cx - width / 2).coerceAtLeast(dp(4f))
            val top = (bottomY - height).coerceAtLeast(dp(4f))
            labelBg.alpha = (200 * fade).toInt()
            canvas.drawRoundRect(
                left, top, left + width, top + height,
                height / 2, height / 2, labelBg,
            )
            labelPaint.alpha = (245 * fade).toInt()
            canvas.drawText(text, left + padH, top + height - padV - dp(2f), labelPaint)
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density

        /** 供外部算距离用（目前只用于日志与未来扩展）。 */
        fun distance(sx: Float, sy: Float, ex: Float, ey: Float): Float =
            hypot(ex - sx, ey - sy)
    }
}
