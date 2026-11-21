package com.simplechat.app.permission

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.simplechat.app.service.ScreenService

/** App 真正会用到的系统能力。顺序就是权限页里的顺序。 */
enum class AppPermission {
    /** 通知：任务提醒、后台长任务的进度。 */
    NOTIFICATIONS,

    /** 所有文件访问：工作区绑到存储卡上的真实目录。 */
    ALL_FILES,

    /** 悬浮窗：操作屏幕时那条"正在做什么"的提示。 */
    OVERLAY,

    /** 无障碍：读屏幕节点 + 点击输入。整个"操作屏幕"的地基。 */
    ACCESSIBILITY,

    /** 电池优化白名单：长任务不被系统掐断。 */
    BATTERY,
}

/** 一项权限的当前状态。[granted] 为 null 表示"系统没给出明确答案"。 */
data class PermissionState(val permission: AppPermission, val granted: Boolean?)

/**
 * 权限查询与跳转。
 *
 * 这里**只读状态、只跳设置页**，绝不替用户点"允许"：
 * 无障碍与所有文件访问都是系统级开关，只能由用户在系统界面上亲手打开 ——
 * 这是 Android 的约定，也是这类能力唯一站得住的授权来源。
 */
class AppPermissions(private val context: Context) {

    fun snapshot(): List<PermissionState> = AppPermission.entries.map { permission ->
        PermissionState(permission, runCatching { granted(permission) }.getOrNull())
    }

    fun granted(permission: AppPermission): Boolean = when (permission) {
        AppPermission.NOTIFICATIONS ->
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

        AppPermission.ALL_FILES ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            }

        AppPermission.OVERLAY -> Settings.canDrawOverlays(context)

        AppPermission.ACCESSIBILITY ->
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty().split(':').any {
                ComponentName.unflattenFromString(it) == ComponentName(context, ScreenService::class.java)
            }

        AppPermission.BATTERY ->
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /** 打开这一项对应的系统页面。失败返回 false（界面据此提示"请手动去设置里找"）。 */
    fun open(permission: AppPermission): Boolean {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val intents = buildList {
            when (permission) {
                AppPermission.NOTIFICATIONS -> {
                    add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                AppPermission.ALL_FILES -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
                        add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                AppPermission.OVERLAY -> add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
                AppPermission.ACCESSIBILITY -> add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                AppPermission.BATTERY -> {
                    add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            // 兜底：所有失败都退到"应用详情"，用户总能从那儿再点进具体某项
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // 继续试下一个
            }
        }
        return false
    }
}
