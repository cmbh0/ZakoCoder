package com.simplechat.app.ui.common

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地头像。
 *
 * 不引 Coil：整个 App 只有这一处要显示本地图片，为了它加一个图片库
 * （以及它的磁盘缓存、OkHttp 依赖）不划算。这里直接 `BitmapFactory` 解码，
 * 尺寸按 2 倍显示尺寸做 `inSampleSize` 采样 —— 头像只有 32dp，解一张
 * 4000×3000 的原图进内存没有意义。
 *
 * 没设头像时画首字圆：比一个灰色人形剪影更像个"人"，
 * 也省掉一套占位资源。
 */
@Composable
fun ProfileAvatar(
    uri: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            if (uri.isNullOrBlank()) return@withContext null
            runCatching {
                /*
                 * 绝对路径走 FileInputStream，content:// 才走 ContentResolver。
                 *
                 * 头像选中后会**复制进私有目录**，存下来的是 `/data/.../profile_avatar`。
                 * 早先这里一律 `Uri.parse(uri)` + `openInputStream` —— 没有 scheme 的
                 * 裸路径会被 ContentResolver 当成未知 Uri 抛 FileNotFoundException，
                 * 于是选完图、存完档，头像仍然只是一个首字母。用户看到的就是
                 * "根本没有自定义头像"。
                 */
                fun open() = if (uri.startsWith("/")) {
                    java.io.File(uri).inputStream()
                } else {
                    context.contentResolver.openInputStream(Uri.parse(uri))
                }
                val targetPx = (size.value * 3).toInt().coerceAtLeast(96)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                open()?.use {
                    BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        val initial = name.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}
