package com.simplechat.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simplechat.app.data.ScheduleStore

/**
 * 开机重排。
 *
 * `setExactAndAllowWhileIdle` 的闹钟**不会**跨重启保留 —— 关机前设的
 * "每天早上 8 点"重启之后就没了。这里在开机广播里把所有启用的任务重新挂上。
 *
 * 只处理 BOOT_COMPLETED：`QUICKBOOT_POWERON` 之类的厂商私有广播不追，
 * 追了也覆盖不全，反而让人以为"我一定响"。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // init 内部会 rearm 全部启用的任务
        runCatching { ScheduleStore.init(context) }
    }
}
