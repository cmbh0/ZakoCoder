package com.simplechat.app.ui.chat

import androidx.compose.runtime.mutableStateMapOf

/**
 * 正在跑 AI 的会话登记表。
 *
 * 存在的理由只有一个：**侧栏要能看见"哪条会话还在干活"**。
 * 用户切走之后那一轮并没有停（见 `ChatViewModel.detachRun`），
 * 但界面上再也看不到任何动静 —— 没有这个登记表，他只能靠猜。
 *
 * 值是一组**轮次号**（`ChatViewModel.streamEpoch`），不是布尔值、也不是计数：
 *
 * - 布尔值挡不住同一会话里的新旧两轮 —— 旧协程的 `finally` 是取消后异步跑的，
 *   会在新流已经登记之后才来撤销，转圈当场灭掉；
 * - 计数能挡住上一条，但挡不住"登记被重复调用"：切回后台会话时重登一次，
 *   计数变 2，退场只减 1，那一格永远留着。
 *
 * 轮次号天然幂等：同一个轮次重复登记只有一个元素，退场只摘掉自己那一格。
 */
object ActiveRuns {

    private val runs = mutableStateMapOf<String, Set<Long>>()

    fun start(conversationId: String, epoch: Long) {
        // 整体赋值而不是改集合内部：SnapshotStateMap 只观察自身结构变化，
        // 往它存着的 Set 里 add 不会触发侧栏重组。
        runs[conversationId] = runs[conversationId].orEmpty() + epoch
    }

    fun finish(conversationId: String, epoch: Long) {
        val left = runs[conversationId].orEmpty() - epoch
        if (left.isEmpty()) runs.remove(conversationId) else runs[conversationId] = left
    }

    /** 这条会话是不是正在跑。 */
    fun isRunning(conversationId: String): Boolean = !runs[conversationId].isNullOrEmpty()

    /** 正在跑的会话数（侧栏排序/角标将来可能用得上）。 */
    val count: Int get() = runs.size
}
