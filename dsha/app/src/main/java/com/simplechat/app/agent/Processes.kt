package com.simplechat.app.agent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 进程的**可取消**等待。
 *
 * 为什么需要它：`Process.waitFor(timeout)` 是阻塞调用。协程 `cancel()` 只能
 * 在挂起点生效，打不断一个正卡在 `waitFor` 里的线程 —— 于是「停止」按下去，
 * 流是断了，可工具里那条 shell 还在跑，侧栏转圈也不停，直到命令自己结束或超时。
 *
 * 这里的做法：
 *  - 把等待放到独立守护线程，协程这一侧真正挂起；
 *  - 协程一旦被取消，**立刻** `destroyForcibly()` 杀进程（含子进程）；
 *  - 超时语义保持不变：先 `destroy()`，给 2 秒，再不退出才 forcibly。
 *
 * 返回 `true` 表示进程在超时内自行结束。
 */
suspend fun awaitProcess(process: Process, timeoutSeconds: Long): Boolean =
    suspendCancellableCoroutine { cont ->
        val waiter = Thread({
            val finished = runCatching {
                process.waitFor(timeoutSeconds.coerceAtLeast(1L), TimeUnit.SECONDS)
            }.getOrDefault(false)

            if (!finished) {
                runCatching { process.destroy() }
                val gone = runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)
                if (!gone) killProcessTree(process)
            }

            // 与取消竞争：已取消时 resume 会抛，直接忽略即可（结果没人要了）。
            runCatching { cont.resume(finished) }
        }, "await-process").apply { isDaemon = true }

        cont.invokeOnCancellation {
            // 用户停止 / 会话被清理 = 这条命令不该再跑下去。
            killProcessTree(process)
        }
        waiter.start()
    }

/**
 * 立刻终止进程。
 *
 * 不用 `ProcessHandle.descendants()`：那是 Java 9 的 API，Android 上没有，
 * 编译就过不去。先 `destroy()` 给它一次正常退出的机会，紧跟着
 * `destroyForcibly()` 兜底 —— 停止按钮要的是"现在就别跑了"。
 */
fun killProcessTree(process: Process) {
    runCatching { process.destroy() }
    runCatching { process.destroyForcibly() }
}
