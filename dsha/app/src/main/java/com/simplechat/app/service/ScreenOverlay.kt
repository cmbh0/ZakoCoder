package com.simplechat.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 操作屏幕时的顶部提示条。
 *
 * 为什么需要它：Agent 一旦开始点别人的界面，用户就**看不见**它在做什么了 ——
 * 屏幕上是它点开的页面，不是对话。一条常驻的小条解决两件事：
 * 知道现在是谁在动，以及"随时能停"的心理预期。
 *
 * 形态：**白底胶囊 + 一个脉动的圆点 + 一行灰字**。
 * 之前是深灰底白字，在自己 App 的浅色页面上像一条通知横幅；而它出现的位置
 * 恰恰是别人 App 的标题栏旁边 —— 白色、无边框感、只留一点投影，才像"系统状态"
 * 而不是"某个 App 的弹窗"。
 *
 * 停留策略：每次 [show] 都把倒计时重置为 [HoldMillis]。
 * 早先每次动作结束就 `hide()`，一次点击 200ms 就收 —— 用户只看到一条东西闪过去。
 * 现在只要还在连续操作就一直在，停手之后才慢慢淡出。
 *
 * 刻意用原生 View 而不是 Compose：这层要活在没有 Activity 的 WindowManager 里，
 * 为了一个圆角小条去引 `ComposeView` + 生命周期宿主不值当，
 * 而且任何一次组合异常都会变成"系统级崩溃"。
 *
 * 所有窗口操作都 `runCatching`：没有悬浮窗权限、被系统回收、窗口泄漏，
 * 都不该让一次工具调用失败 —— 提示是锦上添花，不是功能本身。
 */
object ScreenOverlay {

    /** 最后一次动作之后，胶囊再停留多久。太短会"闪"，太长会挡视野。 */
    private const val HoldMillis = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var dot: android.view.View? = null
    private var pulse: ValueAnimator? = null
    private var attached = false

    /** 常驻标记：Agent 轮次开始到结束之间为 true，此时不吃倒计时。 */
    @Volatile
    private var sticky = false

    private val dismiss = Runnable { hide() }

    fun show(context: Context, text: String) {
        val app = context.applicationContext
        main.post {
            if (!Settings.canDrawOverlays(app)) return@post
            runCatching {
                val manager = windowManager
                    ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .also { windowManager = it }
                val bar = root ?: build(app).also { root = it }
                label?.text = text
                if (!attached) {
                    manager.addView(bar, params(app))
                    attached = true
                    startPulse()
                }
                // 有新动作 → 倒计时重来
                main.removeCallbacks(dismiss)
                if (!sticky) main.postDelayed(dismiss, HoldMillis)
            }
        }
    }

    /**
     * 整轮 Agent 期间常驻。
     *
     * 与 [show] 的区别只有一条：**不挂倒计时**。模型在两屏之间思考十几秒时，
     * 胶囊不该自己消失 —— 那会让人以为它已经停了。
     */
    fun hold(context: Context, text: String) {
        sticky = true
        show(context, text)
    }

    /** 结束常驻；没有新动作就按正常倒计时收掉。 */
    fun release() {
        sticky = false
        main.post {
            main.removeCallbacks(dismiss)
            main.postDelayed(dismiss, HoldMillis)
        }
    }

    fun hide() {
        main.post {
            runCatching {
                if (!attached) return@post
                val manager = windowManager ?: return@post
                val bar = root ?: return@post
                manager.removeView(bar)
                attached = false
                stopPulse()
            }
        }
    }

    // ── 视图 ──────────────────────────────────────────────

    private fun build(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 7), dp(context, 14), dp(context, 7))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(0xF2FFFFFF.toInt())
                setStroke(dp(context, 1).coerceAtLeast(1), 0x14000000)
            }
            // 只留很淡的一层：重投影在白色页面上会变成一圈脏边
            elevation = dp(context, 4).toFloat()
        }

        dot = android.view.View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4D6BFE.toInt())
            }
        }
        row.addView(
            dot,
            LinearLayout.LayoutParams(dp(context, 6), dp(context, 6)).apply {
                marginEnd = dp(context, 8)
            },
        )

        label = TextView(context).apply {
            setTextColor(0xFF3A3D42.toInt())
            textSize = 12.5f
            includeFontPadding = false
        }
        row.addView(label)
        return row
    }

    private fun startPulse() {
        stopPulse()
        val view = dot ?: return
        pulse = ValueAnimator.ofFloat(1f, 0.25f, 1f).apply {
            duration = 1_200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        dot?.alpha = 1f
    }

    private fun params(context: Context): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不抢焦点、不吃触摸：它只是一条提示，绝不能挡住用户自己操作
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(context, 52)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
