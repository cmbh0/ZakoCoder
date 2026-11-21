package com.simplechat.app.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 「另存为」——让用户挑一个位置，把一段字节写成文件。
 *
 * 为什么不自己往下载目录里写：Android 10 起 App 不能随便往公共目录落文件，
 * 想那么做就得申请「所有文件访问」——为了导出一个 20 KB 的 zip 去要这个权限，
 * 代价完全不成比例。系统文件选择器（SAF）把写入权限一次性授给那一个文件，
 * 拿到的是 `content://` 流，不需要任何存储权限。
 *
 * 返回的函数签名刻意做成 `(文件名, 字节) -> Unit`：调用点只需要在按钮的
 * `onClick` 里算出内容，剩下的（选位置、写流、失败提示）都在这儿。
 */
@Composable
fun rememberFileSaver(
    mime: String = "application/octet-stream",
    onResult: (Boolean) -> Unit = {},
): (fileName: String, bytes: ByteArray) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)

    // 从 launch 到回调之间，待写的字节先挂在这里 —— 回调拿不到业务参数
    var pending by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mime),
    ) { uri ->
        val item = pending
        pending = null
        // 用户取消了：不算失败，什么都不说
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(item.second) }
                ?: error("无法写入所选位置")
        }.isSuccess
        callback(ok)
    }

    return remember(launcher) {
        { fileName, bytes ->
            pending = fileName to bytes
            launcher.launch(fileName)
        }
    }
}
