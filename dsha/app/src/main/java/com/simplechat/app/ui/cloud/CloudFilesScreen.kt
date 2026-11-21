package com.simplechat.app.ui.cloud

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.simplechat.app.agent.CloudEnvs
import com.simplechat.app.agent.RemoteEntry
import com.simplechat.app.agent.RemoteWorkspaces
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 云端工作区的文件浏览。
 *
 * 为什么单独一页而不是复用 [com.simplechat.app.ui.workspace.WorkspaceScreen]：
 * 那一页的模型是"路径 → java.io.File"，云端没有 File。硬塞进去会让每个分支
 * 都要判一次"这是本地还是云端"，两边都变难读。
 *
 * 只读：浏览 + 看文本内容。改文件走对话里的工具 —— 那才是能留下 diff、
 * 能被撤销的路径；在这一页做编辑，等于凭空多出一条没有历史记录的写入口。
 */
@Composable
fun CloudFilesScreen(
    envId: String?,
    onBack: () -> Unit,
) {
    val colors = LocalChatColors.current
    val env = remember(envId) { CloudEnvs.find(envId) }

    var path by remember(envId) { mutableStateOf(".") }
    var entries by remember(envId) { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember(envId) { mutableStateOf(true) }
    var error by remember(envId) { mutableStateOf<String?>(null) }
    var preview by remember(envId) { mutableStateOf<Preview?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    /*
     * 上传。
     *
     * 这是**任何文件**的正式通道：图片、APK、压缩包、模型权重都从这里进云机器。
     * 走 SAF 而不是自建选择器 —— 系统选择器已经处理了所有 provider（网盘、相册、
     * 其他应用的文件），自建只会覆盖其中一小部分。
     */
    var uploading by remember(envId) { mutableStateOf<String?>(null) }
    var progress by remember(envId) { mutableStateOf(0f) }
    var uploadNote by remember(envId) { mutableStateOf<String?>(null) }

    fun reload() {
        val target = env ?: return
        scope.launch {
            runCatching { RemoteWorkspaces.of(target).list(path) }
                .onSuccess { entries = it }
                .onFailure { error = it.message ?: "读取失败"; entries = emptyList() }
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = env ?: return@rememberLauncherForActivityResult
        scope.launch {
            uploadNote = null
            val name = queryDisplayName(context, uri) ?: "upload-${System.currentTimeMillis()}"
            val size = querySize(context, uri)
            if (size > UploadLimitBytes) {
                uploadNote = Res.get(R.string.cloud_upload_too_large, formatSize(UploadLimitBytes))
                return@launch
            }
            uploading = name
            progress = 0f
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                uploading = null
                uploadNote = Res.get(R.string.cloud_upload_failed, "读不到文件")
                return@launch
            }
            runCatching {
                RemoteWorkspaces.of(target).upload(dir = path, name = name, bytes = bytes) { p ->
                    progress = p
                }
            }.onSuccess {
                uploadNote = Res.get(R.string.cloud_upload_done, name, formatSize(it))
                reload()
            }.onFailure {
                val msg = it.message.orEmpty()
                // 404 在这里不是"地址填错"，而是"对端还是 v2 服务端"——给一句能动手的话
                uploadNote = if (msg.contains("404")) {
                    Res.get(R.string.cloud_upload_old_server)
                } else {
                    Res.get(R.string.cloud_upload_failed, msg)
                }
            }
            uploading = null
        }
    }

    LaunchedEffect(env?.id, path) {
        val target = env ?: return@LaunchedEffect
        loading = true
        error = null
        preview = null
        runCatching { RemoteWorkspaces.of(target).list(path) }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "读取失败"; entries = emptyList() }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(
                icon = AppIcons.ChevronLeft,
                contentDescription = Res.get(R.string.action_back),
                onClick = onBack,
                size = 36.dp,
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = env?.name ?: Res.get(R.string.cloud_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (path == ".") "/" else "/$path",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (path != ".") {
                IconCircleButton(
                    icon = AppIcons.ChevronUp,
                    contentDescription = Res.get(R.string.workspace_parent),
                    onClick = {
                        path = path.substringBeforeLast('/', "").ifEmpty { "." }
                    },
                    size = 36.dp,
                    iconSize = 18.dp,
                )
            }
            // 上传就挨着"返回上一级"：这两个动作都在回答"我要往这个目录里放/取东西"
            IconCircleButton(
                icon = AppIcons.Upload,
                contentDescription = Res.get(R.string.cloud_upload),
                onClick = { pickFile.launch(arrayOf("*/*")) },
                size = 36.dp,
                iconSize = 18.dp,
            )
        }

        if (uploading != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Res.get(R.string.cloud_uploading, uploading.orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.processText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                    )
                }
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = colors.fieldBackground,
                )
            }
        }

        uploadNote?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.processText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                env == null -> Hint(Res.get(R.string.cloud_files_no_env))

                preview != null -> {
                    val item = preview!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = Res.get(R.string.cloud_files_close),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { preview = null }
                                .padding(4.dp),
                        )
                    }
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.markdownCodeBlock, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .heightIn(max = 2000.dp),
                    )
                }

                else -> {
                    error?.let { Hint(it, danger = true) }
                    if (loading) Hint(Res.get(R.string.cloud_files_loading))
                    entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.card, RoundedCornerShape(10.dp))
                                .clickable {
                                    if (entry.isDir) {
                                        path = if (path == ".") entry.name else "$path/${entry.name}"
                                    } else {
                                        preview = Preview(entry.name, Res.get(R.string.cloud_files_loading))
                                        val target = env
                                        val full = if (path == ".") entry.name else "$path/${entry.name}"
                                        if (target != null) {
                                            /*
                                             * 预览要起协程：`read` 是挂起函数，
                                             * 而 clickable 的 lambda 不是挂起上下文。
                                             * 先占位"读取中"，回来再替换 —— 大文件也不会卡住界面。
                                             */
                                            scope.launch {
                                                // 只拉前 64 KB：这一页是"看一眼"，不是编辑器
                                                runCatching { RemoteWorkspaces.of(target).read(full, 64 * 1024) }
                                                    .onSuccess {
                                                        preview = Preview(entry.name, it.ifBlank { "（空文件）" })
                                                    }
                                                    .onFailure {
                                                        preview = Preview(entry.name, it.message.orEmpty())
                                                    }
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (entry.isDir) AppIcons.Folder else AppIcons.FileText,
                                contentDescription = null,
                                tint = if (entry.isDir) colors.processText else colors.placeholder,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!entry.isDir) {
                                Text(
                                    text = formatSize(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.placeholder,
                                )
                            }
                            if (entry.isDir) {
                                Icon(
                                    imageVector = AppIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.placeholder,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    if (!loading && error == null && entries.isEmpty()) {
                        Hint(Res.get(R.string.workspace_empty))
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

private data class Preview(val name: String, val content: String)

@Composable
private fun Hint(text: String, danger: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (danger) LocalChatColors.current.danger else LocalChatColors.current.placeholder,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 单次上传上限。与服务端 `MAX_UPLOAD` 的默认值对齐，客户端先挡一次省得白传。 */
private const val UploadLimitBytes = 256L * 1024 * 1024

/** 从 SAF 的 uri 里问出文件名。拿不到就返回 null，由调用方生成一个。 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
    }.getOrNull()

private fun querySize(context: android.content.Context, uri: Uri): Long =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) cursor.getLong(index) else 0L
                } else {
                    0L
                }
            }
    }.getOrNull() ?: 0L
