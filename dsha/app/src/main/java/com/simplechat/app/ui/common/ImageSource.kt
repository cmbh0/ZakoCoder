package com.simplechat.app.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 一张"图片来源"的加载器。
 *
 * 图片可能来自三处，界面不该关心是哪一处：
 * ```
 * https://…            绘图 API 回的直链（主流网关都是这种）
 * /data/…/generated/x   本地文件（API 回 b64_json 时落到私有目录）
 * data:image/png;base64,… 极少数网关把图塞在 data URI 里
 * ```
 *
 * ### 为什么要支持直链
 *
 * 早先只要 API 回 `url`，客户端也先下载到磁盘再从磁盘读。那一步没有任何收益：
 * 图在 CDN 上本来就是可访问的，下载只是多占一份空间，还多出一个"存哪儿、
 * 有没有权限"的问题 —— 工作区指向存储根目录时，写不进去，卡片就永远
 * 显示"图片读取失败"。
 *
 * 现在直链直接交给这里按需取字节，内存里留一张解码后的位图，不落盘。
 *
 * ### 缓存
 *
 * 用 `LruCache` 按字节数限容（堆的 1/8）。默认的 `LruCache` 按条数算，
 * 十张 4 MB 的图就能把堆吃穿 —— 按 `byteCount` 计才拦得住。
 */
private val ImageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

private val ImageCache: LruCache<String, Bitmap> by lazy {
    val limit = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    object : LruCache<String, Bitmap>(limit) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
}

/** `data:` URI / http(s) / 本地路径都认；取不到返回 null，不抛。 */
private fun bytesOf(source: String): ByteArray? = runCatching {
    when {
        source.startsWith("http://") || source.startsWith("https://") -> {
            val request = Request.Builder()
                .url(source)
                // 部分图床对空 UA 直接 403
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) DSHA")
                .get()
                .build()
            ImageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        }

        source.startsWith("data:") -> {
            val comma = source.indexOf(',')
            if (comma < 0) return null
            val head = source.substring(0, comma)
            val payload = source.substring(comma + 1)
            if (!head.contains("base64", ignoreCase = true)) return null
            Base64.getDecoder().decode(payload)
        }

        else -> File(source).takeIf { it.isFile }?.readBytes()
    }
}.getOrNull()

/**
 * 按最长边采样解码。
 *
 * 先只读文件头拿尺寸，再按 2 的幂降采样：生成图常见 1024–2048 宽，
 * 全解一张 ARGB_8888 就是 4–16 MB，列表里摆三张开始 GC。
 */
private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= maxSide) sample *= 2

    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/**
 * 记住一张图。[maxSide] 是解码后的最长边上限（缩略图给 768，点开给 1600）。
 *
 * 同一来源 + 同一档尺寸只解一次；换档位（点开大图）会各自缓存，
 * 关掉弹窗后仍留在 LRU 里。
 */
@Composable
fun rememberImageSource(source: String, maxSide: Int = 768): ImageBitmap? {
    val key = "$source@$maxSide"
    return produceState<ImageBitmap?>(
        initialValue = ImageCache.get(key)?.asImageBitmap(),
        key1 = key,
    ) {
        if (value != null) return@produceState
        val bitmap = withContext(Dispatchers.IO) {
            ImageCache.get(key) ?: runCatching {
                bytesOf(source)?.let { decodeSampled(it, maxSide) }
            }.getOrNull()?.also { ImageCache.put(key, it) }
        }
        value = bitmap?.asImageBitmap()
    }.value
}

/**
 * 带状态的加载：[Loading] / [Ready] / [Failed]。
 *
 * 卡片需要区分"还在取图"和"取不到" —— 都画成一个空框，用户没法判断
 * 是网络慢还是链接已经过期（网关的临时 URL 常见一小时失效）。
 */
sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val image: ImageBitmap) : ImageLoadState
    data object Failed : ImageLoadState
}

@Composable
fun rememberImageLoad(source: String, maxSide: Int = 768): ImageLoadState {
    val key = "$source@$maxSide"
    return produceState<ImageLoadState>(
        initialValue = ImageCache.get(key)
            ?.let { ImageLoadState.Ready(it.asImageBitmap()) }
            ?: ImageLoadState.Loading,
        key1 = key,
    ) {
        val hit = ImageCache.get(key)
        if (hit != null) {
            value = ImageLoadState.Ready(hit.asImageBitmap())
            return@produceState
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { bytesOf(source)?.let { decodeSampled(it, maxSide) } }
                .getOrNull()
                ?.also { ImageCache.put(key, it) }
        }
        value = if (bitmap != null) ImageLoadState.Ready(bitmap.asImageBitmap()) else ImageLoadState.Failed
    }.value
}

/**
 * 取原图的字节，给"保存到…"用。
 *
 * 不复用解码后的位图再压缩：那样存下来的是**已经缩过**的版本，
 * 而用户点保存的意思是"把这张图给我"，不是"给我一张它的缩略图"。
 */
suspend fun imageBytesOf(source: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { bytesOf(source) }.getOrNull()
}
