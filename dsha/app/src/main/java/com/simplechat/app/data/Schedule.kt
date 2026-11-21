package com.simplechat.app.data

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * 一条定时任务。
 *
 * 两种动作：
 * - [ScheduleAction.REMIND]：到点弹一条通知，只此而已；
 * - [ScheduleAction.COMMAND]：到点执行一条 shell 命令，输出写进通知。
 *
 * 为什么不做"到点自动发消息给模型"：那要在没有界面的情况下起一轮对话、
 * 烧 token、可能连着失败十次，而用户看不到任何过程。要自动回应，让他到点
 * 回了通知再说 —— 但"跑个备份脚本 / 清一下缓存"这类事，确实只有定时器能做。
 *
 * `atMillis` 只对 [Repeat.ONCE] 有意义；重复任务只存 `hour`/`minute`，
 * 每次触发后由 [nextTrigger] 现算下一次 —— 存"下一次时间戳"会在时区、
 * 夏令时、手动改表之后全部失准。
 */
@Serializable
data class ScheduleItem(
    val id: String,
    val title: String,
    val note: String = "",
    val hour: Int = 9,
    val minute: Int = 0,
    val repeat: Repeat = Repeat.ONCE,
    /**
     * `WEEKLY` 用哪几天。元素是 [Calendar] 的 `DAY_OF_WEEK`（1=周日 … 7=周六）。
     * 空 = 没选过，按"每周今天"处理。
     */
    val days: List<Int> = emptyList(),
    /** `ONCE` 的绝对时刻；其余模式忽略。 */
    val atMillis: Long = 0L,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val lastFiredAt: Long = 0L,
    /** 到点做什么。老数据没有这个字段，默认提醒。 */
    val action: ScheduleAction = ScheduleAction.REMIND,
    /** `COMMAND` 要执行的那条命令（`sh -c`，可用管道与 `&&`）。 */
    val command: String = "",
    /**
     * 命令成功时不发通知。
     *
     * 每日备份这类任务，成功是默认结果 —— 每天准时弹一条"成功"只会让人关掉通知，
     * 顺带把真正的失败也关掉。
     */
    val quietOnSuccess: Boolean = false,
) {
    /** `09:05` */
    val clock: String get() = "%02d:%02d".format(hour, minute)
}

@Serializable
enum class ScheduleAction {
    REMIND,
    COMMAND,
    ;

    val label: String
        get() = when (this) {
            REMIND -> "提醒"
            COMMAND -> "运行命令"
        }
}

@Serializable
enum class Repeat {
    /** 只响一次。 */
    ONCE,

    /** 每天。 */
    DAILY,

    /** 周一到周五。 */
    WEEKDAYS,

    /** 每周的某几天（见 [ScheduleItem.days]）。 */
    WEEKLY,
    ;

    val label: String
        get() = when (this) {
            ONCE -> "仅一次"
            DAILY -> "每天"
            WEEKDAYS -> "工作日"
            WEEKLY -> "每周"
        }
}

/**
 * 下一次触发时刻（毫秒）；不该再响就返回 null。
 *
 * 全部用 [Calendar] 而不是 `java.time`：minSdk 24 没开 desugaring，
 * `LocalDateTime` 在 Android 8 以下会崩。
 */
fun ScheduleItem.nextTrigger(from: Long = System.currentTimeMillis()): Long? {
    if (!enabled) return null
    if (repeat == Repeat.ONCE) return atMillis.takeIf { it > from }

    val cal = Calendar.getInstance().apply {
        timeInMillis = from
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
    if (repeat == Repeat.WEEKDAYS) {
        while (
            cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
            cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        ) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    if (repeat == Repeat.WEEKLY) {
        /*
         * 往后找最近一个"选中的星期"。
         *
         * 最多看 8 天：今天这个时刻已过（上面加过一天），再加上最多七天的一周，
         * 一定能落在一个选中日上 —— 前提是 days 非空，空则退回"每周今天"。
         */
        val days = days.ifEmpty { listOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) }
        var guard = 0
        while (cal.get(Calendar.DAY_OF_WEEK) !in days && guard < 8) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
    }
    return cal.timeInMillis
}

/** 星期几的短名，用于列表里的"每周一三五"。 */
fun dayLabel(day: Int): String = when (day) {
    Calendar.MONDAY -> "一"
    Calendar.TUESDAY -> "二"
    Calendar.WEDNESDAY -> "三"
    Calendar.THURSDAY -> "四"
    Calendar.FRIDAY -> "五"
    Calendar.SATURDAY -> "六"
    else -> "日"
}

/** 一组星期几 → `一三五`（按周一到周日排序）。 */
fun daysLabel(days: List<Int>): String {
    if (days.isEmpty()) return "每天"
    val order = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    )
    return order.filter { it in days }.joinToString("") { dayLabel(it) }
}
