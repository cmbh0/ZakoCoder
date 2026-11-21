package com.simplechat.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 软件日志。
 *
 * 默认**关闭**：正常使用不产生任何文件，也不改动 CPU / IO 的节奏。
 * 打开之后记两类东西：
 *
 * - **运行日志**：工具调用、网络请求、流式异常、屏幕操作……一行一条，按天分文件；
 * - **崩溃日志**：未捕获异常完整堆栈，单独存 `crash-*.log`。
 *
 * ### 为什么开关不放在 DataStore
 *
 * 崩溃可能发生在第一帧之前、DataStore 的协程还没跑起来的时候。开关必须能在
 * [init] 里**同步**读到，所以单独用 SharedPreferences 存一份镜像；DataStore
 * 那边只在设置页显示时读同一个值（见 `SettingsScreen`）。
 *
 * ### 写盘策略
 *
 * 所有写入排进**单线程队列**，调用方（包括主线程与网络回调）只做一次入队。
 * 单文件超过 2MB 轮转成 `.1`，保留 7 天 —— 日志是给用户导出来排查问题的，
 * 不该自己长成几十兆。
 */
object AppLog {

    private const val PrefsName = "app_log"
    private const val KeyEnabled = "enabled"

    private const val MaxBytes = 2L * 1024 * 1024
    private const val KeepDays = 7

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US)

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsha-log").apply { isDaemon = true }
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var crashHandlerInstalled = false

    /** App 启动时调用一次。同步读开关，装崩溃钩子。 */
    fun init(context: Context) {
        val app = context.applicationContext
        logDir = File(app.filesDir, "logs")
        enabled = app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .getBoolean(KeyEnabled, false)
        if (enabled) {
            logDir?.mkdirs()
            installCrashHandler(app)
            writer.execute { prune() }
            i("AppLog", "日志已开启 · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
    }

    fun isEnabled(): Boolean = enabled

    /** 设置页切换。关闭时保留已有日志（用户可能还想导出），只是不再写新的。 */
    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        enabled = on
        app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .edit().putBoolean(KeyEnabled, on).apply()
        if (on) {
            logDir?.mkdirs()
            installCrashHandler(app)
            i("AppLog", "日志已开启")
        } else {
            i("AppLog", "日志已关闭")
        }
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    /**
     * 导出：把 logs 目录打成一个 zip，返回文件。
     *
     * 没有日志时也返回一个包含说明文件的 zip —— 用户点"导出"却什么都没发生，
     * 比导出一个空包更让人困惑。
     */
    fun export(context: Context): File? = runCatching {
        val dir = logDir ?: File(context.filesDir, "logs").also { logDir = it }
        val out = File(context.cacheDir, "dsha-logs-${System.currentTimeMillis()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            if (files.isEmpty()) {
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(
                    "日志是关闭的，或还没有产生任何记录。\n".toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
            } else {
                files.sortedBy { it.name }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        out
    }.getOrNull()

    fun clear() {
        writer.execute {
            logDir?.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    // ── 写入 ──────────────────────────────────────────────

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        if (!enabled) return
        val line = buildString {
            append(stamp.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (error != null) {
                append('\n').append(error.stackTraceToString())
            }
        }
        writer.execute { appendLine(line) }
    }

    /** 崩溃路径必须**同步**写完，进程马上就没了。 */
    private fun writeFatal(tag: String, message: String, error: Throwable) {
        val line = buildString {
            append(stamp.format(Date()))
            append(" F/").append(tag).append(": ").append(message)
            append('\n').append(error.stackTraceToString())
        }
        runCatching { appendLine(line) }
    }

    private fun appendLine(line: String) {
        val dir = logDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "app-${dayStamp.format(Date())}.log")
            if (file.length() > MaxBytes) {
                val rolled = File(dir, file.name + ".1")
                runCatching { rolled.delete() }
                runCatching { file.renameTo(rolled) }
            }
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    private fun prune() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - KeepDays * 24L * 60 * 60 * 1000
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                writeFatal("Crash", "未捕获异常 · thread=${thread.name}", error)
                val dir = logDir
                if (dir != null) {
                    val crash = File(dir, "crash-${System.currentTimeMillis()}.log")
                    crash.writeText(
                        buildString {
                            append("time: ").append(Date()).append('\n')
                            append("thread: ").append(thread.name).append('\n')
                            append("device: ").append(Build.MANUFACTURER).append(' ')
                                .append(Build.MODEL).append('\n')
                            append("android: ").append(Build.VERSION.RELEASE)
                                .append(" (").append(Build.VERSION.SDK_INT).append(")\n\n")
                            append(error.stackTraceToString())
                        },
                        Charsets.UTF_8,
                    )
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
