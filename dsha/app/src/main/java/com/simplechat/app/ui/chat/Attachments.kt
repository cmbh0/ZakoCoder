package com.simplechat.app.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AttachmentFiles
import com.simplechat.app.data.Attachments
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 附件在界面上的三种样子。
 *
 * 全都**不引图片库**：附件就在数据库里躺着 base64，解码两步就完事，
 * 为一个"显示一张本地图"的需求拖进 Coil 不值得（设计文档 §8）。
 * 关键是**按显示尺寸采样解码** —— 直接解一张 1600×1600 的位图是 10MB 内存，
 * 在一屏聊天里滚两下就 OOM 了。
 */

/** 缩略图边长。 */
private val ThumbSize = 64.dp

/** 消息里图片的最大宽度 / 高度。 */
private val BubbleImageWidth = 220.dp
private val BubbleImageMaxHeight = 300.dp

/** 单张缩略图的边长；两列时会被可用宽度压小。 */
private val SquareImageSide = 168.dp

/** 缩略图的圆角。比气泡的 `SoftShape` 略大一点，方形才不会显得太硬。 */
private val SquareImageShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

// ══════════════════════════════════════════════════════════
//  解码
// ══════════════════════════════════════════════════════════

/**
 * 按需解码，**按显示尺寸采样**，并走一层内存 LRU。
 *
 * 在 IO 线程做；解码期间返回 null，调用方显示占位。
 *
 * ### 为什么必须有缓存
 *
 * `produceState` 的生命周期跟着**组合**走：消息滚出屏幕再滚回来，组合被销毁
 * 重建，于是同一张图又被解码一遍。快速上下翻历史时，一屏三四张 1600px 的图
 * 每张要读盘 + 两次 `BitmapFactory`，全排在 IO 线程上抢 CPU —— 表现就是
 * 滑动时一顿一顿，停下来才"突然变清晰"。缓存之后第二次起只是取一个引用。
 */
@Composable
fun rememberAttachmentImage(attachment: Attachment, targetPx: Int): ImageBitmap? {
    val context = LocalContext.current
    /*
     * key 里带上 `path`：落盘附件的 `data` 是空串，只用 data 当 key 会让
     * "换了一张图但 key 没变"的情况（同一消息里两张落盘图）命中旧结果。
     *
     * ⚠️ 这里**不要**直接拿整个 base64 去做 key 或 `remember` 的 key：
     * 那是一段几百 KB 的字符串，`equals` / `hashCode` 都是 O(n)，每条消息
     * 每次重组都算一遍等于把省下来的解码时间又还回去。取长度 + 头部指纹
     * 足够区分（同一会话里的图片文件名与开头字节几乎不可能同时撞上）。
     */
    val cacheKey = remember(attachment.path, attachment.data.length, attachment.name, targetPx) {
        attachmentCacheKey(attachment, targetPx)
    }
    val state by produceState<ImageBitmap?>(
        initialValue = AttachmentBitmapCache.get(cacheKey),
        cacheKey,
    ) {
        AttachmentBitmapCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            // 落盘的从文件读，内联的走 base64 —— 渲染层只认字节，不关心存哪
            val stored = if (attachment.isStored) AttachmentFiles.read(context, attachment.path) else null
            decodeSampled(stored ?: Base64.decode(attachment.data, Base64.NO_WRAP), targetPx)
                ?.also { AttachmentBitmapCache.put(cacheKey, it) }
        }
    }
    return state
}

/** 缓存的键：落盘看文件名，内联看长度 + 头部指纹 + 显示尺寸。 */
private fun attachmentCacheKey(attachment: Attachment, targetPx: Int): String {
    val identity = attachment.path?.takeIf { it.isNotBlank() }
        ?: "${attachment.name}:${attachment.data.length}:${attachment.data.take(48).hashCode()}"
    return "$identity@$targetPx"
}

/**
 * 解码结果的内存 LRU。
 *
 * 上限取堆的 1/8（夹在 16–96MB）：一台 8GB 的手机大约 48MB，够放十几张
 * 气泡图；再大就会和模型请求里的 base64 抢内存。按**像素字节数**计大小，
 * 不按条目数 —— 一张 1600px 和一张 200px 的缩略图不该占同一个配额。
 *
 * 淘汰时**不 recycle**：Compose 可能还握着上一帧的引用，提前回收会画出一块
 * 黑图。交给 GC，代价只是峰值时多活一小会儿。
 */
private object AttachmentBitmapCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024)
        .toInt()

    private val cache = object : android.util.LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

private fun decodeSampled(bytes: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    var sample = 1
    var longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= targetPx) {
        sample *= 2
        longest /= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

// ══════════════════════════════════════════════════════════
//  待发送区（输入栏上方）
// ══════════════════════════════════════════════════════════

/** 待发送附件。图片是缩略图，文本文件是一条小卡片。 */
@Composable
fun PendingAttachment(spec: Attachment, onRemove: () -> Unit, onOpen: () -> Unit) {
    Box {
        AttachmentSurface(inUserBubble = false) {
            when (spec.kind) {
                Attachment.Kind.IMAGE -> ImageThumb(spec, ThumbSize, onClick = onOpen)
                // 文本与任意文件都长成一条卡片；文件点击后由 FileChip 自己拉起外部 App
                else -> FileChip(spec, onClick = onOpen)
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd))
    }
}

/** 输入栏上方那一排：横向可滚，附件多了也不会把输入栏撑高。 */
@Composable
fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { spec ->
            PendingAttachment(
                spec = spec,
                onRemove = { onRemove(spec) },
                onOpen = { onOpen(spec) },
            )
        }
    }
}

/** 右上角的小 ✕。深色圆底 + 白叉，压在任何底图上都看得清。 */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.66f))
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Close,
            contentDescription = stringResource(R.string.attach_remove),
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

@Composable
private fun ImageThumb(spec: Attachment, size: Dp, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val density = LocalDensity.current
    val target = with(density) { size.roundToPx() }
    val bitmap = rememberAttachmentImage(spec, target)

    Box(
        modifier = Modifier
            .size(size)
            .clip(SoftShape)
            .background(surface.background)
            .border(1.dp, surface.border, SoftShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = spec.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FileChip(spec: Attachment, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val context = LocalContext.current
    val binary = spec.kind == Attachment.Kind.FILE
    val video = binary && spec.mime.startsWith("video/")

    Row(
        modifier = Modifier
            .heightIn(min = ThumbSize)
            .widthIn(max = 220.dp)
            .clip(SoftShape)
            .background(surface.background)
            .border(1.dp, surface.border, SoftShape)
            .clickable {
                if (binary) openAttachment(context, spec) else onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when {
                video -> AppIcons.Play
                binary -> AppIcons.File
                else -> AppIcons.FileText
            },
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = spec.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (binary) {
                    formatFileSize(spec.size)
                } else {
                    stringResource(R.string.attach_char_count, spec.data.length)
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 1,
            )
        }
    }
}

/** 二进制附件的大小文案。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 用系统里的其它 App 打开一个落盘附件。
 *
 * 走 FileProvider 的临时读权限 —— 不申请存储权限，也不把私有路径直接塞进
 * `file://`（Android 7 起会抛 `FileUriExposedException`）。
 */
private fun openAttachment(context: android.content.Context, spec: Attachment) {
    val file = AttachmentFiles.fileOf(context, spec.path) ?: return
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }.getOrNull() ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, spec.mime.ifBlank { "*/*" })
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.attach_file_open_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
}

// ══════════════════════════════════════════════════════════
//  消息里
// ══════════════════════════════════════════════════════════

/**
 * 一条消息携带的附件（用户气泡里）。
 *
 * 图片按原始比例占位（用存下来的宽高先撑住），**避免解码完成后高度跳一下** ——
 * 聊天流里跳高度会连带把滚动位置顶掉。
 */
@Composable
fun MessageAttachments(
    attachments: List<Attachment>,
    onOpenImage: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    /** 在用户气泡里时换一套配色 —— 灰底灰框压在蓝气泡上很脏。 */
    inUserBubble: Boolean = false,
    /**
     * 正方形圆角缩略图。
     *
     * 用户自己发出去的图用这个：官方客户端里，发出去的图是**一排小方块**，
     * 而不是按原比例铺开的整图 —— 一条消息里放三张竖屏长图，按原比例排下来
     * 能把整个屏幕占满，聊天记录就没法看了。
     *
     * 缩略图只负责"让你认出是哪张"，要看全图点开就是整屏。
     */
    square: Boolean = false,
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.isImage }
    val files = attachments.filter { !it.isImage }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        // 用户消息一侧靠右对齐：图与气泡同为"从右边发出去"的东西
        horizontalAlignment = if (square) Alignment.End else Alignment.Start,
    ) {
        if (images.isNotEmpty()) {
            if (square) {
                SquareImageGrid(images, onOpenImage, inUserBubble)
            } else {
                images.forEach { spec ->
                    AttachmentSurface(inUserBubble = inUserBubble) {
                        BubbleImage(spec, onClick = { onOpenImage(spec) })
                    }
                }
            }
        }
        files.forEach { spec ->
            AttachmentSurface(inUserBubble = inUserBubble) {
                FileChip(spec, onClick = {})
            }
        }
    }
}

/**
 * 正方形缩略图网格。
 *
 * 一列 / 两列两种排法：单图给 168dp（看得清画面），多图两列、每格取
 * 可用宽度的一半 —— 三张以上长图也就占两行，不会把气泡顶上屏幕外。
 */
@Composable
private fun SquareImageGrid(
    images: List<Attachment>,
    onOpenImage: (Attachment) -> Unit,
    inUserBubble: Boolean,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val gap = 6.dp
        val columns = if (images.size == 1) 1 else 2
        val side = if (columns == 1) {
            SquareImageSide
        } else {
            minOf((maxWidth - gap) / 2, SquareImageSide)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.End,
        ) {
            images.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { spec ->
                        AttachmentSurface(inUserBubble = inUserBubble) {
                            BubbleImage(
                                spec,
                                onClick = { onOpenImage(spec) },
                                square = true,
                                side = side,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 附件的底板配色。
 *
 * 两种场合各一套：
 * - 常规（输入栏上方、AI 侧）：浅灰底 + 描边，跟页面其它卡片一致
 * - **用户气泡内**：用正文色的低透明度去压蓝底。灰底灰框在蓝气泡上像贴了张
 *   打了补丁的纸，而低透明度黑既保持了气泡的整体感，又仍然分得出"这是一块内容"。
 */
@Composable
private fun AttachmentSurface(
    inUserBubble: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    val background = if (inUserBubble) colors.onUserBubble.copy(alpha = 0.06f) else colors.fieldBackground
    val border = if (inUserBubble) colors.onUserBubble.copy(alpha = 0.14f) else colors.outline
    CompositionLocalProvider(LocalAttachmentSurface provides AttachmentSurfaceColors(background, border)) {
        content()
    }
}

private data class AttachmentSurfaceColors(val background: Color, val border: Color)

private val LocalAttachmentSurface = staticCompositionLocalOf {
    AttachmentSurfaceColors(Color.Unspecified, Color.Unspecified)
}

@Composable
private fun BubbleImage(
    spec: Attachment,
    onClick: () -> Unit,
    /** 正方形裁剪（用户消息缩略图）。 */
    square: Boolean = false,
    /** 正方形时边长；非正方形忽略。 */
    side: Dp = 0.dp,
) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val density = LocalDensity.current
    val target = with(density) {
        (if (square) side else BubbleImageWidth).roundToPx()
    }
    val bitmap = rememberAttachmentImage(spec, target)

    val ratio = if (spec.width > 0 && spec.height > 0) {
        spec.width.toFloat() / spec.height.toFloat()
    } else {
        1f
    }

    /*
     * 两条分支的差别不只是形状：
     *
     * - 方形缩略图 `Crop` —— 长图会被裁掉上下，但缩略图的任务是"认出是哪张"，
     *   不是"看清全文"，裁掉反而让一行里每张都是同样的尺寸、对着齐；
     * - 非方形（输入栏预览、AI 侧）仍然 `Fit` 原比例 —— 那是"确认要发什么"，
     *   裁掉会让人以为发出去的是裁剪版。
     */
    val shapeModifier = if (square) {
        Modifier
            .size(side)
            .clip(SquareImageShape)
    } else {
        Modifier
            .widthIn(max = BubbleImageWidth)
            .heightIn(max = BubbleImageMaxHeight)
            .aspectRatio(ratio)
            .clip(SoftShape)
    }

    Box(
        modifier = shapeModifier
            .background(surface.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = spec.name,
                contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  附件面板
// ══════════════════════════════════════════════════════════

/**
 * 点 `⊕` 展开的附件面板内容。
 *
 * ⚠️ 它**不是** `ModalBottomSheet`，而是和思考档位 / 统计 / 对话设置
 * 完全同一个容器（`InputPanel`）：浮在输入栏上方的一块圆角卡片，
 * 左右留 16dp、和输入栏同宽、共用同一层阴影与点击外部关闭。
 *
 * 一开始用了 Material 的 `ModalBottomSheet` —— 它自带满宽、自带蒙版、自带
 * 拖拽把手，摆在应用里像个外来的东西：左右顶到屏幕边、和输入栏对不齐，
 * 与下面那几个面板是两套语言。**同一件事只该有一套容器。**
 */
@Composable
fun AttachmentPanelContent(
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    /*
     * 三个格子，和官方那套一致：拍照 / 相册 / 文件。
     *
     * 等宽而不是按内容自适应 —— 三个入口的地位是平等的，
     * 谁宽谁窄会让人觉得有一个"更主要"。
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AttachmentTile(
            label = stringResource(R.string.attach_camera),
            icon = AppIcons.Camera,
            onClick = onCapture,
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            label = stringResource(R.string.attach_gallery),
            icon = AppIcons.Image,
            onClick = onPickImage,
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            label = stringResource(R.string.attach_file),
            icon = AppIcons.FileText,
            onClick = onPickFile,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AttachmentTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = modifier
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  全屏查看
// ══════════════════════════════════════════════════════════

/** 文本附件预览：只读，最多显示 2 万字（再长排版本身就会卡）。 */
@Composable
private fun TextAttachmentDialog(spec: Attachment, onDismiss: () -> Unit) {
    val colors = LocalChatColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.pageBackground)
                .systemBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = spec.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(SoftShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                text = stringResource(R.string.attach_char_count, spec.data.length),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(SoftShape)
                    .background(colors.card)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                Text(
                    text = if (spec.data.length > TextPreviewLimit) {
                        spec.data.take(TextPreviewLimit) + "\n\n……（预览截断）"
                    } else {
                        spec.data
                    },
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private const val TextPreviewLimit = 20_000

/**
 * 点开看大图 —— **全屏**，纯查看，不做保存/分享（那是系统相册的事）。
 *
 * ### 为什么是整屏而不是对话框
 *
 * 先前是个居中的圆角卡片：图片被塞进固定高度的盒子里（min 160 / max 520），
 * 竖图两侧、横图上下都会留出一大块底色 —— 那块空白既不是图片也不是背景，
 * 看着就像"图没加载完"。看图这件事本来就是"尽量占满"，
 * 所以改成整屏铺满：图片按 `Fit` 缩放居中，点任意处关闭，
 * 多张时左右可翻。
 *
 * 背景用近黑而不是主题色：图片本身可能是浅底，深底才看得清边界。
 */
@Composable
fun AttachmentViewerDialog(
    images: List<Attachment>,
    initial: Attachment,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current

    /*
     * 文本附件走自己的预览：它不是"图"，塞进图片 Pager 只会得到一片空白。
     * 自动转成 TXT 的超长输入尤其需要一个"我看看它到底存了什么"的出口。
     */
    if (initial.kind == Attachment.Kind.TEXT) {
        TextAttachmentDialog(initial, onDismiss)
        return
    }

    // 全屏尺寸解码：屏幕最长边的像素数，够清楚又不至于把原图解进内存
    val target = with(density) { 1080.dp.roundToPx() }

    val pages = images.ifEmpty { listOf(initial) }
    val startIndex = pages.indexOfFirst { it.name == initial.name && it.data == initial.data }
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { pages.size }
    val current = pages.getOrNull(pagerState.currentPage) ?: initial

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 自己处理系统栏：整屏看图不该被默认的 insets 再缩一圈
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackground)
                // 点任意处关闭（看图时最直觉的退出方式）
                .clickable(onClick = onDismiss),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val attachment = pages[page]
                // 每页各解各的：Pager 只组合当前页与左右邻页，不会把整组图读进内存
                val pageBitmap = rememberAttachmentImage(attachment, target)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pageBitmap != null) {
                        Image(
                            bitmap = pageBitmap,
                            contentDescription = attachment.name,
                            // Fit：整张图都在屏幕内，不裁切；空白由深底兜住，不显眼
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.Image,
                            contentDescription = null,
                            tint = ViewerTextDim,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            // ── 顶栏：关闭 + 计数 ──────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = ViewerText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ViewerText,
                    )
                    Spacer(Modifier.size(12.dp))
                }
            }

            // ── 底栏：文件名 · 尺寸 · 大小 ─────────────────
            Text(
                text = buildString {
                    append(current.name)
                    if (current.width > 0) append(" · ${current.width}×${current.height}")
                    // 落盘的附件 `data` 是空的，字节数要问文件系统（否则显示成 0 B）
                    append(" · ${Attachments.formatSize(AttachmentFiles.sizeOf(context, current))}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = ViewerTextDim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 全屏看图的底色：近黑。图片可能是浅底，深底才看得清边界。 */
private val ViewerBackground = Color(0xF2000000)

/** 顶栏 / 底栏文字。 */
private val ViewerText = Color(0xFFEDEDED)

/** 次要文字（尺寸、大小）。 */
private val ViewerTextDim = Color(0xFF9E9E9E)
