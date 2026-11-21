package com.simplechat.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.simplechat.app.MainActivity
import com.simplechat.app.R
import com.simplechat.app.agent.Shell
import com.simplechat.app.agent.Workspace
import com.simplechat.app.data.Res
import com.simplechat.app.data.ScheduleAction
import com.simplechat.app.data.ScheduleStore
import com.simplechat.app.util.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 到点执行命令。
 *
 * 为什么是**前台服务**而不是在广播里 `goAsync()` 跑完：`BroadcastReceiver`
 * 只有十秒左右的可执行窗口，而备份、同步、批处理这类命令动辄几十秒。
 * 在广播里硬跑会被系统记一次 ANR，进程也可能在后台被收走。
 *
 * 用 `shortService` 类型：它的上限是三分钟，正好覆盖 [TimeoutSeconds]
 * （120 秒），也不需要额外申请 `FOREGROUND_SERVICE_*` 的细分类权限。
 *
 * 通知分两条：执行中的常驻条（不可取消）与结果条（可点回 App）。
 * `quietOnSuccess` 只压掉**成功**的那条 —— 失败必须说话，否则用户永远不知道
 * 他的每日备份已经三周没成功了。
 */
class ScheduleRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { ScheduleStore.init(this) }
        val item = ScheduleStore.find(id)
        if (item == null || !item.enabled || item.action != ScheduleAction.COMMAND) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 先亮出"正在执行"：命令可能跑一两分钟，什么都不显示会让人以为没触发
        startForeground(FOREGROUND_ID, progress(item.title))

        scope.launch {
            val cwd = Workspace.rootOrNull() ?: File(filesDir, "workspace").apply { mkdirs() }
            val result = runCatching {
                Shell.run(item.command, cwd = cwd, timeoutSeconds = TimeoutSeconds)
            }.getOrElse {
                AppLog.e(LogTag, "定时命令异常：${it.message}")
                Shell.Result(
                    stdout = "",
                    stderr = it.message.orEmpty(),
                    exitCode = -1,
                    timedOut = false,
                    truncated = false,
                )
            }
            val ok = result.exitCode == 0 && !result.timedOut
            AppLog.i(
                LogTag,
                "定时命令 ${item.title} exit=${result.exitCode} 超时=${result.timedOut} " +
                    "耗时输出=${result.stdout.length}",
            )
            if (ok && item.quietOnSuccess) {
                // 成功且要求安静：什么都不发，只把"跑过了"记下来
            } else {
                notifyResult(item.id, item.title, ok, result)
            }
            ScheduleStore.markFired(item.id)
            ScheduleScheduler.rescheduleAfterFire(this@ScheduleRunService, item)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 执行中的常驻通知：不可取消、没有点击行为（点了也没意义）。 */
    private fun progress(title: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(Res.get(R.string.schedule_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 结果通知：成功给前几行输出，失败给退出码与 stderr。 */
    private fun notifyResult(
        id: String,
        title: String,
        ok: Boolean,
        result: Shell.Result,
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel()
        val body = buildString {
            if (result.timedOut) append(Res.get(R.string.schedule_timeout)).append('\n')
            val text = result.stdout.trim().ifBlank { result.stderr.trim() }
            append(text.take(MaxBodyChars))
            if (text.length > MaxBodyChars) append("\n…")
            if (text.isBlank()) append(Res.get(R.string.schedule_no_output))
        }
        val open = android.app.PendingIntent.getActivity(
            this,
            id.hashCode(),
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (ok) {
                    Res.get(R.string.schedule_notify_command_ok, title)
                } else {
                    Res.get(R.string.schedule_notify_command_fail, title, result.exitCode)
                },
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(id.hashCode() + ResultOffset, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                Res.get(R.string.schedule_channel_command),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = Res.get(R.string.schedule_action_command_hint) },
        )
    }

    companion object {
        const val EXTRA_ID = "schedule_command_id"
        private const val CHANNEL = "dsha_schedule_command"
        private const val FOREGROUND_ID = 4101
        private const val ResultOffset = 9000
        private const val LogTag = "Schedule"
        private const val MaxBodyChars = 600

        /**
         * 命令上限。与 `shortService` 的三分钟留出余量：超时后
         * `Shell.run` 会 kill 掉整棵进程树，服务还来得及发结果通知。
         */
        const val TimeoutSeconds = 120L

        fun intent(context: Context, id: String): Intent =
            Intent(context, ScheduleRunService::class.java).putExtra(EXTRA_ID, id)
    }
}
