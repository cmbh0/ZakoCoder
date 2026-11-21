package com.simplechat.app.agent

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 命令执行。
 *
 * 用 `sh -c` 而不是自己拆参数：模型天然按 shell 语法写命令（管道、重定向、`&&`），
 * 拆开反而要自己实现一遍 shell 语义，还容易出错。
 *
 * 三条硬约束，都是为了不让一次工具调用把 App 拖死：
 * - **超时**：默认 30 秒，超时 kill -9 整棵进程树
 * - **输出上限**：超长输出截断并标注，否则一次 `cat` 大文件就能把上下文烧光
 * - **工作目录固定**在工作区内，`cd` 只在这个进程内有效
 */
object Shell {

    /** 单次执行的输出上限（字符）。超出部分丢弃并附一行说明。 */
    private const val MaxOutputChars = 24_000

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
    ) {
        /** 给模型看的合并文本。空输出也要有话说，否则模型以为工具挂了。 */
        fun asToolOutput(): String = buildString {
            append("exit=").append(exitCode)
            if (timedOut) append("（超时被终止）")
            if (stdout.isNotBlank()) {
                append("\n--- stdout ---\n").append(stdout.trimEnd())
            }
            if (stderr.isNotBlank()) {
                append("\n--- stderr ---\n").append(stderr.trimEnd())
            }
            if (stdout.isBlank() && stderr.isBlank()) append("\n（无输出）")
            if (truncated) append("\n（输出过长，已截断）")
        }
    }

    suspend fun run(
        command: String,
        cwd: File = Workspace.requireRoot(),
        timeoutSeconds: Long = 30,
    ): Result = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(cwd)
                .redirectErrorStream(false)
                .apply {
                    environment()["HOME"] = cwd.absolutePath
                    environment()["TMPDIR"] = cwd.absolutePath
                    // 让多数 CLI 关掉交互式彩色输出，减少无意义控制字符
                    environment()["TERM"] = "dumb"
                    environment()["NO_COLOR"] = "1"
                }
                .start()
        } catch (e: Exception) {
            return@withContext Result(
                stdout = "",
                stderr = "无法启动 shell：${e.message}",
                exitCode = -1,
                timedOut = false,
                truncated = false,
            )
        }

        // 读流必须并发，否则管道缓冲写满会互相等死
        val outReader = Reader(process.inputStream.bufferedReader(), MaxOutputChars)
        val errReader = Reader(process.errorStream.bufferedReader(), MaxOutputChars)
        val outThread = Thread(outReader, "shell-out").apply { isDaemon = true; start() }
        val errThread = Thread(errReader, "shell-err").apply { isDaemon = true; start() }

        /*
         * 可取消等待（见 `awaitProcess`）：用户按「停止」时这条命令要**立刻**死，
         * 而不是继续阻塞到超时。早先直接 `waitFor(timeout)`，协程取消打不断它。
         */
        val finished = awaitProcess(process, timeoutSeconds)
        outThread.join(1_000)
        errThread.join(1_000)

        Result(
            stdout = outReader.text(),
            stderr = errReader.text(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
            truncated = outReader.truncated || errReader.truncated,
        )
    }

    /** 边读边计数，到上限就停止累积（但仍然把流读空，避免子进程阻塞）。 */
    private class Reader(private val source: java.io.BufferedReader, private val limit: Int) : Runnable {
        private val buffer = StringBuilder()
        @Volatile var truncated = false

        override fun run() {
            runCatching {
                val chunk = CharArray(4096)
                while (true) {
                    val read = source.read(chunk)
                    if (read <= 0) break
                    if (buffer.length < limit) {
                        val room = limit - buffer.length
                        buffer.append(chunk, 0, minOf(read, room))
                        if (read > room) truncated = true
                    } else {
                        truncated = true
                    }
                }
            }
        }

        fun text(): String = buffer.toString()
    }
}
