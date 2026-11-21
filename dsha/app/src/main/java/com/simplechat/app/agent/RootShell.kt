package com.simplechat.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Root 通道：`su -c` 的最小封装。
 *
 * 存在的理由很直白 —— 无障碍服务是**用户亲手勾选**才成立的授权，一旦没勾、
 * 或者被系统杀掉重连不上，"操作屏幕"整条链就断了。而 root 用户的机器上，
 * `input` / `uiautomator` / `screencap` 这些系统命令本来就能做同样的事，
 * 只是需要一次 `su`。
 *
 * 三条纪律：
 *
 * 1. **只检测，不索取**。没有交互式提权、不尝试刷入 su，`su -c id` 不通就是不通。
 * 2. **不缓存太久**。授权弹窗可能刚被点掉，所以 [probe] 可以随时重跑，结论写进
 *    [status] 供界面显示；真正执行前仍以一次实际调用为准。
 * 3. **输出要有上限**。`uiautomator dump` 能吐几百 KB，这里截断，避免把整段 XML
 *    塞进模型上下文。
 */
object RootShell {

    enum class Status { UNKNOWN, CHECKING, AVAILABLE, UNAVAILABLE }

    @Volatile
    var status: Status = Status.UNKNOWN
        private set

    /** 检测结果的一句话说明（`uid=0(root)` 或失败原因），界面直接显示。 */
    @Volatile
    var detail: String = ""
        private set

    /** 上一次检测的时刻，用来在界面上标"什么时候测的"。 */
    @Volatile
    var checkedAt: Long = 0L
        private set

    val available: Boolean get() = status == Status.AVAILABLE

    /** 探一次。失败不抛异常 —— 这是常态，不是错误。 */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        status = Status.CHECKING
        val result = runCatching { execRaw("id", 5000).trim() }
        val ok = result.getOrNull()?.contains("uid=0") == true
        status = if (ok) Status.AVAILABLE else Status.UNAVAILABLE
        detail = result.getOrElse { it.message ?: "su 不可用" }.lineSequence().firstOrNull().orEmpty()
        checkedAt = System.currentTimeMillis()
        ok
    }

    /**
     * 执行一条 root 命令，返回合并后的输出。
     *
     * 失败抛 [IllegalStateException]，消息会被工具层原样回给模型 ——
     * 模型看得懂"su 拒绝了"，比一个 exit code 有用。
     */
    suspend fun exec(command: String, timeoutMs: Long = 12_000): String = withContext(Dispatchers.IO) {
        execRaw(command, timeoutMs)
    }

    private suspend fun execRaw(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        // 上限 512KB：uiautomator dump 再大也没有意义
                        if (output.length < 512 * 1024) output.appendRange(buffer, 0, read)
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        // 可取消等待：停止时把 su 那一路一起杀掉（见 `awaitProcess`）
        if (!awaitProcess(process, (timeoutMs / 1000L).coerceAtLeast(1L))) {
            reader.join(600)
            throw IllegalStateException("root 命令超时（${timeoutMs / 1000}s）：$command")
        }
        reader.join(800)
        val text = output.toString()
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        if (exit != 0) {
            val trimmed = text.trim().take(400)
            throw IllegalStateException(
                if (trimmed.isBlank()) "root 命令失败（exit $exit）：$command" else trimmed,
            )
        }
        return text
    }
}
