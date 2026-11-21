package com.simplechat.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.simplechat.app.service.ScreenService
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 截图识别管线。
 *
 * 与"布局识别"的分工：布局识别读无障碍节点树，快、准、有文本，但**没有节点树
 * 的界面它一无所知** —— 游戏画面（Unity / UE 的 SurfaceView）、视频、自绘 Canvas
 * 都是这种。这条管线直接看像素：给模型一张截图 + 分辨率 + 缩放比，让它自己
 * 判断按钮在哪，再按坐标点。
 *
 * 三条硬约束：
 *
 * 1. **不落盘**。截图只存在于内存里，编成 JPEG 塞进请求上下文就丢。用户要求
 *    "不保存到本地"不是为了省空间 —— 截的是别人 App 的界面，可能是聊天记录、
 *    支付页，留一份在 `files/` 里没有任何好处。
 * 2. **坐标必须是屏幕像素**。图会按 [MaxSide] 缩小（否则一次请求几 MB），
 *    所以 JSON 里同时给 `image` 与 `screen` 两套尺寸和一个 `scale`。模型被明确
 *    告知"点按请用 screen 坐标"，换算在客户端做。
 * 3. **密码框不截**。无障碍通道能识别 `isPassword`，这条通道在截图前也先做一次
 *    节点扫描（能扫到就拦），扫不到时退化为"当前是 DSHA 自己"的检查。
 */
object ScreenVision {

    /** 长边上限。1080×2400 的屏会缩到 720×1600 左右，按钮仍看得清，带宽省一半。 */
    private const val MaxSide = 1600

    /** JPEG 质量。80 在截图文字上仍清晰，体积约为 PNG 的 1/5。 */
    private const val Quality = 80

    data class Shot(
        val base64: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val densityDpi: Int,
        val packageName: String,
    ) {
        val scale: Float get() = if (screenWidth > 0) imageWidth.toFloat() / screenWidth else 1f

        /** 给模型看的一段元信息，跟图一起发过去。 */
        fun prompt(): String = buildString {
            appendLine("这是当前手机屏幕的截图。")
            appendLine("截图尺寸：${imageWidth}×${imageHeight}；屏幕物理尺寸：${screenWidth}×${screenHeight}（像素）；密度：${densityDpi}dpi。")
            appendLine("图像相对屏幕缩放：${String.format(java.util.Locale.US, "%.3f", scale)}。")
            appendLine("你要点某处时，请用**屏幕物理像素**给出坐标（0..$screenWidth, 0..$screenHeight），不要用截图里的像素。")
            append("先用一句话描述你看到了什么、要点的目标在哪，再调用 screen_tap。")
        }
    }

    /** 当前有没有可用的看屏能力。 */
    fun available(): Boolean = ScreenService.isConnected() || RootShell.available

    /**
     * 抓一张。返回 null 表示这条通道现在拿不到图（没开服务 / root 不可用 / 安全界面）。
     */
    suspend fun capture(context: Context): Shot? {
        /*
         * 只走用户选定的那条通道。
         *
         * 早先是「无障碍先试，不行退 root」—— 那等于把"切换通道"这件事
         * 写死在截图里：用户明明选了 Root，拿到的却是无障碍的图（分辨率、
         * 裁切、是否含状态栏都不一样），而界面上看不出任何差别。
         *
         * 通道不可用时返回 null，由调用方给出「去设置里开一下」的说明 ——
         * 这比默默换一条路诚实，也更好排查。
         */
        val bitmap = when (ScreenAutomation.activeChannel()) {
            ScreenAutomation.CHANNEL_ACCESSIBILITY -> ScreenService.current()?.captureScreen()
            ScreenAutomation.CHANNEL_ROOT -> RootScreen.captureScreen(context)
            else -> null
        } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val scaled = downscale(bitmap)
                val scaledWidth = scaled.width
                val scaledHeight = scaled.height
                val jpeg = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, Quality, out)
                    out.toByteArray()
                }
                if (scaled !== bitmap) scaled.recycle()
                Shot(
                    base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                    imageWidth = scaledWidth,
                    imageHeight = scaledHeight,
                    screenWidth = bitmap.width,
                    screenHeight = bitmap.height,
                    densityDpi = context.resources.displayMetrics.densityDpi,
                    packageName = "",
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= MaxSide) return source
        val ratio = MaxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 工具回执里的那一小段 JSON（图本身走 [ToolOutcome.Ok.images]）。 */
    fun describe(shot: Shot): String = buildJsonObject {
        put("mode", "vision")
        put("image", "${shot.imageWidth}x${shot.imageHeight}")
        put("screen", "${shot.screenWidth}x${shot.screenHeight}")
        put("density_dpi", shot.densityDpi)
        put("scale", String.format(java.util.Locale.US, "%.3f", shot.scale))
        put("hint", "点按用 screen 坐标；图已附在下一步的 user 消息里")
    }.toString()

    /** root 通道：screencap 写进应用私有目录，读完立刻删。 */
    internal fun decodeAndDelete(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull().also { runCatching { file.delete() } }
}
