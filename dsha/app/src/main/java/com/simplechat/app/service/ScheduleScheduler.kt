package com.simplechat.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.simplechat.app.data.Repeat
import com.simplechat.app.data.ScheduleItem
import com.simplechat.app.data.nextTrigger

/**
 * 把 [ScheduleItem] 落到系统闹钟上。
 *
 * 三条口径：
 *
 * 1. **一律 RTC_WAKEUP**。用户设的是"几点提醒我"，不是"开机多久后"；
 *    设备睡着也必须醒过来响。
 * 2. **能精确就精确，不能就降级**。Android 12+ 的精确闹钟要单独授权，
 *    没授权时用 `setAndAllowWhileIdle`（系统可能挪几分钟）——
 *    比"什么都不响"好，也比为了它去申请一个用户看不懂的权限好。
 * 3. **每次重排前先取消同一个 PendingIntent**。`FLAG_UPDATE_CURRENT` 已经
 *    覆盖同 id 的旧闹钟，但显式取消能保证"改了时间之后不会响两次"。
 */
object ScheduleScheduler {

    private const val ACTION = "com.simplechat.app.SCHEDULE"

    fun rearm(context: Context, items: List<ScheduleItem>) {
        items.forEach { item ->
            if (item.enabled && item.nextTrigger() != null) schedule(context, item) else cancel(context, item.id)
        }
    }

    fun schedule(context: Context, item: ScheduleItem) {
        val at = item.nextTrigger() ?: return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context, item.id)
        cancel(context, item.id)
        runCatching {
            if (canExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(pending(context, id)) }
    }

    /** 精确闹钟权限是否可用。Android 11 及以下一律可用。 */
    fun canExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    /**
     * 重复任务的下一次：响完立刻排。
     *
     * 放在 Receiver 里做，而不是等下一次打开 App —— 否则"每天 9 点"在
     * 响过一次之后就永久停了（App 可能几天不被打开）。
     */
    fun rescheduleAfterFire(context: Context, item: ScheduleItem) {
        if (item.repeat == Repeat.ONCE) return
        schedule(context, item)
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION)
            .putExtra(ScheduleReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
