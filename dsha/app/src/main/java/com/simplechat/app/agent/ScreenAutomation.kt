package com.simplechat.app.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.simplechat.app.net.ImageInput
import com.simplechat.app.service.ScreenOverlay
import com.simplechat.app.service.ScreenService
import com.simplechat.app.service.TouchTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 「操作屏幕」的门面。
 *
 * 工具层只跟它打交道，不直接摸 [ScreenService] —— 这样"服务没开 / 没连上"
 * 这类状态就能在一处判断，并给出**能照着做**的提示，而不是一句空指针。
 *
 * 所有动作完成后都会把状态写进 [state]：界面上那条"正在做什么"的小条
 * 读的就是它，用户看得见模型的手在哪儿。
 */
object ScreenAutomation {

    /** 两条通道的名字。写进 [State.channel]，界面与工具回执都读它。 */
    const val CHANNEL_NONE = "none"
    const val CHANNEL_ACCESSIBILITY = "accessibility"
    const val CHANNEL_ROOT = "root"

    /**
     * 用户为「操作屏幕」指定的优先通道（设置页里选）。
     *
     * `AUTO` 是"哪条通用哪条"，其余两个是**硬指定** —— 选了 ROOT 就不该
     * 偷偷退回无障碍，否则用户会以为自己的选择没生效。
     */
    const val PREF_AUTO = "AUTO"
    const val PREF_ACCESSIBILITY = "ACCESSIBILITY"
    const val PREF_ROOT = "ROOT"

    /**
     * 识别管线。
     *
     * - [PIPELINE_LAYOUT]：无障碍节点树。默认。快、准、有文本，但只对有布局的界面有效。
     * - [PIPELINE_VISION]：截图 + 坐标。给游戏 / 自绘界面用 —— 那些界面
     *   在节点树里往往只有一个空 SurfaceView，布局识别什么也看不见。
     */
    const val PIPELINE_LAYOUT = "LAYOUT"
    const val PIPELINE_VISION = "VISION"

    @Volatile
    var pipeline: String = PIPELINE_LAYOUT
        private set

    fun setPipeline(id: String) {
        pipeline = if (id == PIPELINE_VISION) PIPELINE_VISION else PIPELINE_LAYOUT
    }

    data class State(
        val connected: Boolean = false,
        /** 当前走的是哪条通道：`accessibility` / `root`。 */
        val channel: String = CHANNEL_NONE,
        val busy: Boolean = false,
        val lastAction: String = "",
        val lastResult: String = "",
        val lastAt: Long = 0L,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前偏好通道。设置页写、[activeChannel] 与 [run] 读。 */
    @Volatile
    var preference: String = PREF_AUTO
        private set

    /** 改偏好并立刻重算状态（界面上那颗"当前走哪条"的字要跟着变）。 */
    fun setPreference(id: String) {
        preference = when (id) {
            PREF_ACCESSIBILITY, PREF_ROOT -> id
            else -> PREF_AUTO
        }
        refresh()
    }

    /**
     * 按偏好算出**这一次**该走哪条通道，返回 [CHANNEL_NONE] 表示都不通。
     *
     * 无障碍优先的理由只在 AUTO 下成立：结构化节点能按文本定位，比坐标点
     * 误点风险小一个量级。用户显式选了 ROOT 时就不讲这套了。
     */
    fun activeChannel(): String = when (preference) {
        PREF_ROOT -> if (RootShell.available) CHANNEL_ROOT else CHANNEL_NONE
        PREF_ACCESSIBILITY -> if (ScreenService.isConnected()) CHANNEL_ACCESSIBILITY else CHANNEL_NONE
        else -> when {
            ScreenService.isConnected() -> CHANNEL_ACCESSIBILITY
            RootShell.available -> CHANNEL_ROOT
            else -> CHANNEL_NONE
        }
    }

    /**
     * 应用上下文。
     *
     * 工具层（[ScreenTools]）拿不到 Activity / Context，而这层又必须能拉起
     * 系统设置页、能挂悬浮窗 —— 于是在 `Application.onCreate` 里灌一次。
     */
    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        // root 探测是几条进程调用，放后台：Application.onCreate 不能等它
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { RootShell.probe() }
            refresh()
        }
    }

    /**
     * 这条能力现在能不能用 —— **任意一条通道通就算**。
     *
     * 聊天页据此决定要不要挂屏幕工具：以前只看无障碍，root 用户的工具列表里
     * 干脆没有这几个工具，模型连"我能操作屏幕"都不知道。
     */
    fun isAvailable(): Boolean = isConnected() || RootShell.available

    private fun requireContext(): Context = app ?: error("ScreenAutomation 未初始化")

    /** 重新读两条通道的状态，并决定下一次动作用哪条。 */
    fun refresh() {
        val connected = ScreenService.isConnected()
        _state.value = _state.value.copy(connected = connected, channel = activeChannel())
    }

    fun isConnected(): Boolean = ScreenService.isConnected()

    /** 系统设置里这一项是否被勾选（连不上时用来区分"没开"与"刚被杀"）。 */
    fun isEnabledInSettings(context: Context = requireContext()): Boolean {
        val component = ComponentName(context, ScreenService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun openAccessibilitySettings(context: Context = requireContext()) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 执行一个动作。
     *
     * 失败抛 [IllegalStateException]，消息直接进工具回执 —— 模型看得懂
     * "先开启无障碍服务"这种话，然后会改用别的办法，而不是重试同一个调用。
     */
    suspend fun run(action: String, args: JsonObject): String {
        val context = requireContext()
        /*
         * 通道选择：无障碍优先，root 兜底。
         *
         * 为什么不是"谁快用谁"：无障碍拿到的是**结构化节点 + 指纹校验**，
         * 点错的风险比坐标小一个数量级。root 是备用轮胎，不是备选轮胎。
         */
        /*
         * 通道由**用户的选择**决定，不是由"谁能用"决定。
         *
         * 指定了 ROOT 就只走 root：设备没授权时给一句能照着做的提示，而不是
         * 悄悄换成无障碍 —— 那样用户会以为自己的设置没保存。
         */
        val channel = activeChannel()
        val service = if (channel == CHANNEL_ACCESSIBILITY) ScreenService.current() else null
        if (channel == CHANNEL_NONE || (channel == CHANNEL_ACCESSIBILITY && service == null)) {
            throw IllegalStateException(noChannelMessage(context))
        }
        _state.value = _state.value.copy(
            busy = true,
            lastAction = action,
            channel = if (service != null) CHANNEL_ACCESSIBILITY else CHANNEL_ROOT,
        )
        ScreenOverlay.show(context, label(action))
        return try {
            val result = if (service != null) {
                service.execute(action, args)
            } else {
                RootScreen.execute(action, args, context)
            }
            _state.value = _state.value.copy(
                busy = false,
                connected = ScreenService.isConnected(),
                lastResult = result.take(600),
                lastAt = System.currentTimeMillis(),
                failed = false,
            )
            result
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                busy = false,
                connected = ScreenService.isConnected(),
                lastResult = error.message ?: "操作失败",
                lastAt = System.currentTimeMillis(),
                failed = true,
            )
            throw if (error is IllegalStateException) error else IllegalStateException(error.message ?: "操作失败")
        } finally {
            /*
             * 不在这里 hide。
             *
             * 一次点击 200ms 就收，用户只看到一条东西闪过去 —— 这就是"胶囊
             * 一闪而过"的全部原因。现在由 [ScreenOverlay] 自己按最后动作时间
             * 倒计时收，连续操作时一直在。真正的收口在 [endRound]。
             */
        }
    }

    /**
     * 工具层入口：按当前管线决定"看"和"点"怎么做。
     *
     * 布局管线原样转给 [run]；截图管线自己实现 observe / click / swipe 三个动作，
     * 其余（back / launch / wait / input）仍然走节点树 —— 它们本来就不依赖坐标。
     */
    suspend fun execute(action: String, args: JsonObject): ToolOutcome {
        /*
         * 动手之前先把"能画轨迹"这件事办好。
         *
         * 走 root 通道、又没有悬浮窗权限时，轨迹层挂不上去，用户只看到界面自己跳了 ——
         * 那正是"AI 操作没有轨迹"最常见的成因。这里在每次动作前补一次（内部有缓存，
         * 只会真正尝试一次），失败也不影响动作本身。
         */
        app?.let { runCatching { TouchTrace.ensureOverlay(it) } }
        // 一张截图只服务一次动作：旧的先清掉，目录里永远只有最新这一张
        if (action == "screenshot") purgeShots()
        if (pipeline != PIPELINE_VISION) {
            /*
             * 布局管线下也允许按坐标点（`screen_tap`）。
             *
             * 有些控件在节点树里没有可点区域（自绘按钮、WebView 里的 canvas），
             * 但用户/模型从截图里能看出位置 —— 这条兜底比"只能点了才叫能用"宽。
             */
            if (action == "click") {
                val (x, y) = point(args) ?: return ToolOutcome.ok(run(action, args))
                val context = requireContext()
                tapPoint(context, x, y)
                return ToolOutcome.ok("""{"tapped":[${x.toInt()},${y.toInt()}],"next":"observe"}""")
            }
            return ToolOutcome.ok(run(action, args))
        }
        val context = requireContext()
        when (action) {
            "observe", "screenshot" -> {
                if (!isAvailable()) {
                    return ToolOutcome.fail(noChannelMessage(context))
                }
                val shot = ScreenVision.capture(context)
                    ?: return ToolOutcome.fail(
                        "这一屏拿不到截图（可能是安全界面、系统拒绝了截屏，或所选通道不支持截图）",
                    )
                lastScreen = shot.screenWidth to shot.screenHeight
                return ToolOutcome.ok(
                    ScreenVision.describe(shot),
                    images = listOf(ImageInput("data:image/jpeg;base64,${shot.base64}")),
                )
            }
            "click" -> {
                val (x, y) = point(args) ?: return ToolOutcome.fail("截图管线需要 x / y 坐标")
                _state.value = _state.value.copy(busy = true, lastAction = "click")
                ScreenOverlay.show(context, label("click"))
                tapPoint(context, x, y)
                _state.value = _state.value.copy(
                    busy = false,
                    lastAt = System.currentTimeMillis(),
                    lastResult = "已点击 ($x, $y)",
                    failed = false,
                )
                return ToolOutcome.ok("""{"tapped":[${x.toInt()},${y.toInt()}],"next":"observe"}""")
            }
            "swipe" -> {
                val (x1, y1) = point(args, "x1", "y1") ?: return ToolOutcome.fail("需要起点 x1 / y1")
                val (x2, y2) = point(args, "x2", "y2") ?: return ToolOutcome.fail("需要终点 x2 / y2")
                swipePoint(context, x1, y1, x2, y2)
                return ToolOutcome.ok("""{"swiped":true,"next":"observe"}""")
            }
        }
        return ToolOutcome.ok(run(action, args))
    }

    /** 最近一次截图对应的屏幕尺寸，用来把越界坐标夹回来。 */
    @Volatile
    private var lastScreen: Pair<Int, Int>? = null

    private fun point(args: JsonObject, kx: String = "x", ky: String = "y"): Pair<Float, Float>? {
        val rawX = (args[kx] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: return null
        val rawY = (args[ky] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: return null
        val (w, h) = lastScreen ?: (1080 to 2400)
        // 允许模型给 0..1 的比例：有些模型就爱这么给，换算一下比报错重试便宜
        val x = if (rawX in 0f..1f) rawX * w else rawX
        val y = if (rawY in 0f..1f) rawY * h else rawY
        return x.coerceIn(0f, w - 1f) to y.coerceIn(0f, h - 1f)
    }

    private suspend fun tapPoint(context: Context, x: Float, y: Float) {
        /*
         * 严格按用户选的通道走 —— **这里不能"谁通用谁"**。
         *
         * 之前写的是 `service != null ? 无障碍 : root`，于是一个选了 Root 的
         * 用户，只要无障碍碰巧连着，点击就走了无障碍；反过来也一样。
         * 用户看到的只是"我选的通道没生效"，而从日志里几乎查不出来。
         */
        when (activeChannel()) {
            CHANNEL_ACCESSIBILITY -> {
                val service = ScreenService.current()
                    ?: throw IllegalStateException(noChannelMessage(context))
                service.tapAt(x, y)
            }
            CHANNEL_ROOT -> RootScreen.tapAt(context, x.toInt(), y.toInt())
            else -> throw IllegalStateException(noChannelMessage(context))
        }
    }

    private suspend fun swipePoint(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) {
        when (activeChannel()) {
            CHANNEL_ACCESSIBILITY -> {
                val service = ScreenService.current()
                    ?: throw IllegalStateException(noChannelMessage(context))
                service.swipeAt(x1, y1, x2, y2)
            }
            CHANNEL_ROOT -> RootScreen.swipeAt(context, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), 320)
            else -> throw IllegalStateException(noChannelMessage(context))
        }
    }

    /**
     * 通道不可用时的说明。
     *
     * 必须是"照着做就能好"的一句话：模型会把它原样转述给用户，
     * 所以不能是"操作失败"这种没法行动的句子。
     */
    fun noChannelMessage(context: Context): String = when {
        preference == PREF_ROOT ->
            "「操作屏幕」被指定为 Root 通道，但当前没有 su 授权：请到 设置 → 工具与扩展 → 操作屏幕，点「授权 Root」"
        preference == PREF_ACCESSIBILITY && isEnabledInSettings(context) ->
            "无障碍服务已勾选但连接断开，请在系统设置里关闭再重新打开；或把通道改成 Root"
        preference == PREF_ACCESSIBILITY ->
            "「操作屏幕」被指定为无障碍通道，但服务没有开启：请到 设置 → 工具与扩展 → 操作屏幕 打开，或改用 Root 通道"
        isEnabledInSettings(context) ->
            "无障碍服务已勾选但连接断开，请在系统设置里关闭再重新打开；或使用 Root 通道"
        else ->
            "「操作屏幕」还没开启：请到 设置 → 工具与扩展 → 操作屏幕 授予 Root 或开启无障碍服务"
    }

    /** 屏幕工具的名字前缀。用来判断"这一轮到底有没有动屏幕"。 */
    private const val TOOL_PREFIX = "screen_"

    fun isScreenTool(name: String): Boolean = name.startsWith(TOOL_PREFIX)

    /**
     * 一轮 Agent 开始：胶囊转常驻。
     *
     * 从"模型决定动屏幕"那一刻起就挂上，直到整轮结束 —— 中间的空档（等模型
     * 想下一步、等界面刷新）也一直有东西在，用户才不会以为它停了。
     */
    fun beginRound() {
        val context = app ?: return
        /*
         * 新一轮开始，上一轮留下的截图已经没有意义了。
         *
         * 截图里可能有聊天记录、支付页、别人的界面 —— 留在私有目录里既占空间，
         * 也没有任何用处（模型只读当轮那一张）。这里先清一次，配合 [endRound]
         * 的清理，目录里最多只活着一轮的量。
         */
        purgeShots()
        TouchTrace.clear()
        ScreenOverlay.hold(context, "正在操作屏幕")
    }

    /** 一轮 Agent 结束：恢复倒计时，几秒后自己收掉，并把触摸轨迹擦干净。 */
    fun endRound() {
        ScreenOverlay.release()
        TouchTrace.clear()
        // 模型读完了 → 立刻销毁，不留到下一轮
        purgeShots()
    }

    /**
     * 删掉所有落盘的截图。
     *
     * 两条落点都要覆盖：
     * - 无障碍通道写 `files/screen/shots`（[com.simplechat.app.service.ScreenService]）；
     * - root 通道写 `cache/shots`，外加 `files/screen` 下的临时图。
     *
     * 只删 png，不整目录 `deleteRecursively` —— 那个目录里理论上不该有别的东西，
     * 但万一有，也不该被一次清理带走。
     */
    fun purgeShots() {
        val context = app ?: return
        listOf(
            java.io.File(context.filesDir, "screen/shots"),
            java.io.File(context.cacheDir, "shots"),
            java.io.File(context.filesDir, "screen"),
        ).forEach { dir ->
            runCatching {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".png", ignoreCase = true)) file.delete()
                }
            }
        }
    }

    /** 给悬浮条 / 工具卡用的中文动作名。 */
    fun label(action: String): String = when (action) {
        "observe" -> "正在读取屏幕"
        "click" -> "正在点击"
        "long_click" -> "正在长按"
        "input_text" -> "正在输入"
        "swipe" -> "正在滑动"
        "scroll" -> "正在滚动"
        "back" -> "正在返回"
        "screenshot" -> "正在截屏"
        "launch" -> "正在启动应用"
        "assert_text" -> "正在核对界面"
        "wait_until" -> "正在等待界面"
        else -> "正在操作屏幕"
    }
}

/** 从 JsonObject 取字符串（工具参数直接用这份，避免再引 org.json）。 */
internal fun JsonObject.argText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
