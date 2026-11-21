package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.service.ScheduleScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 定时任务的本地存储。
 *
 * 为什么不进 Room：一张表、几十条记录、没有查询需求 —— 一个 JSON 数组足够，
 * 也省掉一次 schema 迁移。真到了要按时间范围查历史的那天再搬。
 *
 * 写入后**立刻重排闹钟**（[ScheduleScheduler.rearm]）：存储与系统闹钟必须
 * 始终一致，否则会出现"列表里有、到点不响"这种最难查的状态。
 */
object ScheduleStore {

    private const val PREFS = "dsha_schedule"
    private const val KEY = "items"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ScheduleItem.serializer())

    private var app: Context? = null

    private val _items = MutableStateFlow<List<ScheduleItem>>(emptyList())
    val items: StateFlow<List<ScheduleItem>> = _items.asStateFlow()

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val raw = prefs().getString(KEY, null)
        _items.value = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        }
        ScheduleScheduler.rearm(requireContext(), _items.value)
    }

    /** 按时间排序后的列表（界面直接渲染这份）。 */
    val sorted: List<ScheduleItem>
        get() = _items.value.sortedWith(compareBy({ it.hour }, { it.minute }))

    fun add(
        title: String,
        hour: Int,
        minute: Int,
        repeat: Repeat,
        note: String = "",
        atMillis: Long = 0L,
        days: List<Int> = emptyList(),
        action: ScheduleAction = ScheduleAction.REMIND,
        command: String = "",
        quietOnSuccess: Boolean = false,
    ): ScheduleItem {
        val item = ScheduleItem(
            id = UUID.randomUUID().toString().take(8),
            title = title.trim().ifBlank {
                if (action == ScheduleAction.COMMAND) "定时命令" else "提醒"
            },
            note = note.trim(),
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            repeat = repeat,
            days = days.distinct().sorted(),
            atMillis = if (repeat == Repeat.ONCE) {
                atMillis.takeIf { it > 0 } ?: defaultOnceAt(hour, minute)
            } else {
                0L
            },
            enabled = true,
            createdAt = System.currentTimeMillis(),
            action = action,
            command = command.trim(),
            quietOnSuccess = quietOnSuccess,
        )
        commit(_items.value + item)
        return item
    }

    fun remove(id: String) {
        ScheduleScheduler.cancel(requireContext(), id)
        commit(_items.value.filterNot { it.id == id })
    }

    /**
     * 就地改一条。
     *
     * 编辑而不是"删了再建"：id 不变，闹钟的 PendingIntent 就不用重排两次；
     * `lastFiredAt` 这类历史也保留着。
     */
    fun update(item: ScheduleItem) {
        val clean = item.copy(
            title = item.title.trim().ifBlank {
                if (item.action == ScheduleAction.COMMAND) "定时命令" else "提醒"
            },
            note = item.note.trim(),
            command = item.command.trim(),
            hour = item.hour.coerceIn(0, 23),
            minute = item.minute.coerceIn(0, 59),
            days = item.days.distinct().sorted(),
            atMillis = if (item.repeat == Repeat.ONCE) {
                item.atMillis.takeIf { it > System.currentTimeMillis() }
                    ?: defaultOnceAt(item.hour, item.minute)
            } else {
                0L
            },
        )
        commit(_items.value.map { if (it.id == clean.id) clean else it })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        commit(
            _items.value.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            },
        )
    }

    /** 响过之后回写：一次性任务自动停用。 */
    fun markFired(id: String) {
        val now = System.currentTimeMillis()
        commit(
            _items.value.map { item ->
                if (item.id != id) return@map item
                if (item.repeat == Repeat.ONCE) {
                    item.copy(enabled = false, lastFiredAt = now)
                } else {
                    item.copy(lastFiredAt = now)
                }
            },
            rearm = false,
        )
    }

    fun find(id: String): ScheduleItem? = _items.value.firstOrNull { it.id == id }

    private fun commit(next: List<ScheduleItem>, rearm: Boolean = true) {
        _items.value = next
        prefs().edit().putString(KEY, json.encodeToString(serializer, next)).apply()
        if (rearm) ScheduleScheduler.rearm(requireContext(), next)
    }

    private fun defaultOnceAt(hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun requireContext(): Context = app ?: error("ScheduleStore 未初始化")
}
