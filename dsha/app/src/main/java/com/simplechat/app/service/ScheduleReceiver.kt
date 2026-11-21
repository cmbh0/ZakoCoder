package com.simplechat.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.simplechat.app.MainActivity
import com.simplechat.app.R
import com.simplechat.app.agent.Shell
import com.simplechat.app.agent.Workspace
import com.simplechat.app.data.ScheduleAction
import com.simplechat.app.data.ScheduleStore
import com.simplechat.app.util.AppLog

/**
 * 到点触发。
 *
 * 两条路：
 * - [ScheduleAction.REMIND]：发一条通知，只此而已；
 * - [ScheduleAction.COMMAND]：把活交给 [ScheduleRunService] 前台跑，
 *   这条广播本身立刻返回 —— 广播只有十秒窗口，跑不了备份这种活。
 *
 * 通知点开直接进 App；重复任务在这里顺手把下一次排上
 * （见 [ScheduleScheduler.rescheduleAfterFire]）。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        // 进程可能是被闹钟叫醒的，Store 还没初始化过
        runCatching { ScheduleStore.init(context) }
        val item = ScheduleStore.find(id) ?: return
        if (!item.enabled) return

        if (item.action == ScheduleAction.COMMAND && item.command.isNotBlank()) {
            /*
             * 精确闹钟触发的广播属于前台服务启动的豁免场景，正常都能起来。
             * 但省电策略激进的机器仍可能拒绝 —— 那时退回"直接在广播里跑"：
             * 短命令还能跑完，长命令会超时，但至少不是什么都不做。
             */
            val started = runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    ScheduleRunService.intent(context, item.id),
                )
            }.isSuccess
            if (started) return
            runCatching { AppLog.w("Schedule", "前台服务启动失败，改为就地执行") }
            val pending = goAsync()
            Thread {
                try {
                    val cwd = Workspace.rootOrNull()
                        ?: java.io.File(context.filesDir, "workspace").apply { mkdirs() }
                    val result = kotlinx.coroutines.runBlocking {
                        Shell.run(item.command, cwd = cwd, timeoutSeconds = 10)
                    }
                    notify(context, item.id, item.title, result.stdout.trim().take(600))
                } catch (t: Throwable) {
                    notify(context, item.id, item.title, t.message.orEmpty())
                } finally {
                    ScheduleStore.markFired(item.id)
                    ScheduleScheduler.rescheduleAfterFire(context, item)
                    pending.finish()
                }
            }.start()
            return
        }

        notify(context, item.id, item.title, item.note)
        ScheduleStore.markFired(id)
        ScheduleScheduler.rescheduleAfterFire(context, item)
    }

    private fun notify(context: Context, id: String, title: String, note: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.schedule_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.schedule_channel_desc)
                    enableVibration(true)
                },
            )
        }

        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(note.ifBlank { context.getString(R.string.schedule_notify_body) })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(note.ifBlank { context.getString(R.string.schedule_notify_body) }),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        runCatching { manager.notify(id.hashCode(), notification) }
    }

    companion object {
        const val EXTRA_ID = "schedule_id"
        private const val CHANNEL = "dsha_schedule"

        /** 供「权限管理」判断通知是否被关掉。 */
        const val CHANNEL_ID = CHANNEL
    }
}
