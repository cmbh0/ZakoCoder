package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动压缩策略的单测。
 *
 * 锁住的是**行为**，不是实现：换掉档位表、换掉常量，只要行为不变，
 * 这些用例就应当照样通过。
 */
class AutoCompactPolicyTest {

    private fun decide(
        window: Int,
        used: Int,
        output: Int = 0,
        ratio: Float = 0.70f,
        count: Int = 16,
        byCount: Boolean = true,
        exempt: Boolean = true,
        reserveOutput: Boolean = true,
        since: Int = 0,
        eligible: Int = 100,
    ) = AutoCompactPolicy.decide(
        windowTokens = window,
        outputTokens = output,
        usedTokens = used,
        userRatio = ratio,
        userCount = count,
        byCount = byCount,
        largeWindowExempt = exempt,
        reserveOutput = reserveOutput,
        messagesSinceCompaction = since,
        eligibleMessages = eligible,
    )

    // ── 1M 窗口不该被自动压缩 ────────────────────────────────

    @Test
    fun `1M window with exemption does not compact at 70 percent`() {
        val decision = decide(window = 1_048_576, used = 700_000)
        assertFalse("1M 窗口不该在七成就压", decision.shouldCompact)
        assertTrue(decision.ratioExempt)
        assertEquals(AutoCompactPolicy.Reason.NONE, decision.reason)
    }

    @Test
    fun `1M window with exemption still compacts when it is really full`() {
        // 可用预算 ≈ 1M − 输出预留 − 估算余量，填满它必须压
        val decision = decide(window = 1_048_576, used = 1_048_576, output = 32_768)
        assertTrue(decision.shouldCompact)
        assertEquals(AutoCompactPolicy.Reason.HARD_LIMIT, decision.reason)
    }

    @Test
    fun `1M window without exemption compacts later than a small model`() {
        val big = decide(window = 1_048_576, used = 700_000, exempt = false)
        val small = decide(window = 32_768, used = 700_000, exempt = false)
        assertTrue("大窗口的触发线应当更高", big.triggerTokens > small.triggerTokens)
        assertTrue(big.effectiveRatio > small.effectiveRatio)
    }

    // ── 目标线必须低于触发线 ────────────────────────────────

    @Test
    fun `target is below the trigger so compaction cannot loop`() {
        for (window in listOf(16_384, 32_768, 131_072, 262_144, 1_048_576)) {
            val decision = decide(window = window, used = 0, exempt = false)
            assertTrue(
                "窗口 $window：目标线 $decision 必须在触发线之下",
                decision.targetTokens < decision.triggerTokens,
            )
        }
    }

    @Test
    fun `after compaction the usage is below the trigger`() {
        val window = 131_072
        val decision = decide(window = window, used = 0, exempt = false)
        // 模拟"压完之后"的占用：落在目标线上
        val after = decide(window = window, used = decision.targetTokens, exempt = false)
        assertFalse("压完之后不应该立刻又触发", after.shouldCompact)
    }

    // ── 可用预算要扣掉输出 ──────────────────────────────────

    @Test
    fun `output reservation shrinks the usable budget`() {
        val withReserve = decide(window = 1_048_576, used = 0, output = 131_072, reserveOutput = true)
        val without = decide(window = 1_048_576, used = 0, output = 131_072, reserveOutput = false)
        assertTrue(
            "预留输出后可用预算应当更小：${withReserve.usableTokens} vs ${without.usableTokens}",
            withReserve.usableTokens < without.usableTokens,
        )
    }

    @Test
    fun `usable budget always leaves at least half the window`() {
        val decision = decide(window = 32_768, used = 0, output = 1_048_576)
        assertTrue(decision.usableTokens >= 32_768 / 2)
    }

    // ── 条数阈值随窗口放大 ──────────────────────────────────

    @Test
    fun `message count threshold grows with the window`() {
        val small = decide(window = 32_768, used = 0, since = 0)
        val big = decide(window = 1_048_576, used = 0, since = 0, exempt = false)
        assertTrue("大窗口的条数阈值应当更高", big.effectiveCount > small.effectiveCount)
    }

    @Test
    fun `count trigger fires when enough messages accumulated`() {
        val decision = decide(window = 32_768, used = 0, since = 16, exempt = false)
        assertTrue(decision.shouldCompact)
        assertEquals(AutoCompactPolicy.Reason.COUNT, decision.reason)
    }

    // ── 消息太少不压 ────────────────────────────────────────

    @Test
    fun `a couple of messages are not worth compacting`() {
        val decision = decide(window = 32_768, used = 32_768, eligible = 2)
        assertFalse(decision.shouldCompact)
    }

    // ── 边界 ────────────────────────────────────────────────

    @Test
    fun `zero window disables everything`() {
        val decision = decide(window = 0, used = 999_999)
        assertFalse(decision.shouldCompact)
        assertEquals(AutoCompactPolicy.Reason.NONE, decision.reason)
    }

    @Test
    fun `remaining tokens go negative once the trigger is crossed`() {
        val decision = decide(window = 32_768, used = 30_000, exempt = false)
        assertTrue(decision.remainingTokens < 0)
    }
}
