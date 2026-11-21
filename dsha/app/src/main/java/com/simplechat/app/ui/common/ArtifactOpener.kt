package com.simplechat.app.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 打开 AI 交付的文件产物。
 *
 * 走 `FileProvider` + `ACTION_VIEW`：产物可能在工作区、也可能在 App 私有目录
 * （生成的图就落在 `files/generated`），第三方应用拿不到真实路径，只能拿到
 * 一个**临时授权**的 `content://`。provider 早已把这几片目录都开了
 * （见 `res/xml/file_paths.xml`），所以这里不需要再复制一份。
 *
 * `ClipData` 那一行不是可有可无：部分 ROM 上只加 FLAG 授权不生效，
 * 把同一个 uri 放进 ClipData 才真的会授予读权限。
 */
object ArtifactOpener {

    fun open(context: Context, path: String): Result<Unit> = runCatching {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(path))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@runCatching
        }
        val file = File(path)
        require(file.isFile) { "文件不存在或已被移动：${file.name}" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(null, uri)
        }
        context.startActivity(intent)
    }

    /** 按扩展名猜 MIME；认不出来就通配，让系统自己挑应用。 */
    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "md", "markdown" -> "text/markdown"
                "kt", "java", "py", "js", "ts", "json", "xml", "yml", "yaml", "sh", "txt", "log" -> "text/plain"
                "html", "htm" -> "text/html"
                else -> "*/*"
            }
    }
}
