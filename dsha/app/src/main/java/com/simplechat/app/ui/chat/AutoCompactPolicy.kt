package com.simplechat.app.ui.chat

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 自动压缩的**判定中枢**。
 *
 * ### 为什么把它从 ChatViewModel 里抽出来
 *
 * 触发条件以前散在两处：`autoCompactIfNeeded()` 算比例、`compactContext()` 算
 * 预算，两边各算各的，于是出现一个要命的循环：
 *
 * - 触发线 = `窗口 × 0.70`
 * - 压缩时保留的预算 = `窗口 × 0.85`（"保住最近 30%"）
 *
 * 压完之后占用还是约 85% > 70% → **下一轮又触发** → 每轮都把同一段新对话
 * 重写一遍，摘要越来越像"刚才发生了什么"，早先的设定反而被挤掉。
 * 1M 窗口下这个循环尤其明显：0.70 × 1M = 70 万，压完还剩 85 万，
 * 于是**每一轮都在压**，而模型给出的摘要质量每轮都在掉。
 *
 * 现在只有一个地方算账：[decide]。它同时给出**触发线**与**压完之后的目标线**，
 * 目标线**低于**触发线 —— 压完就落到线下，不会立刻再压。
 *
 * ### 三条修正
 *
 * 1. **按"可用输入预算"算，不是按标称窗口算。**
 *    窗口里要先扣掉本次请求要预留的输出（`max_tokens`），再扣掉 token 估算的
 *    误差余量。原来直接拿 0.70 乘标称窗口，等于默认"输出不占窗口" ——
 *    窗口 1M、输出 128K、占用 70 万时，请求实际要 82 万，**已经超了**。
 *
 * 2. **阈值随窗口分档。**
 *    32K 的模型压到 70% 是"快要溢出了"；1M 的模型压到 70% 是"才用了三分之二"。
 *    同一个比例套在量级差 30 倍的窗口上，含义完全不同。
 *    窗口越大越晚压 —— 摘要是有损的，窗口还剩一大截时压缩，损失大于收益。
 *
 * 3. **大窗口豁免。**
 *    超过 [LARGE_WINDOW]（512K）时，默认**不按比例、也不按条数**自动压，
 *    只有真的快满了（[Decision.hardLimitTokens]）才压。
 *    这条是给"1M 上下文的模型还需要自动压缩"这个问题准备的：
 *    1M 窗口的模型本来就装得下，摘要反而是画蛇添足。
 *
 * 纯函数，不依赖 Android —— 可以直接跑单测。
 */
internal object AutoCompactPolicy {

    /** 大窗口门槛：窗口到这个尺寸就启用「大窗口豁免」。 */
    const val LARGE_WINDOW = 512 * 1024

    /** 用户没设 `max_tokens` 时，按窗口的这个比例预留输出。 */
    private const val OUTPUT_RESERVE_RATIO = 0.05f

    private const val OUTPUT_RESERVE_MIN = 4_096
    private const val OUTPUT_RESERVE_MAX = 131_072

    /** token 估算的误差余量：估少了会直接溢出，估多了会白压一次。 */
    private const val SLACK_RATIO = 0.03f
    private const val SLACK_MIN = 4_096

    /**
     * 压完之后要落到的位置：触发线的这个比例。
     *
     * **必须小于 1**，否则压完还在线上，下一轮立刻又触发（见类注释）。
     * 0.72 留了一小段余量，避免"压完正好卡在边界上"时因为估算误差反复横跳。
     */
    private const val TARGET_RATIO = 0.72f

    /** 触发原因。界面上要拿它解释"为什么现在压 / 为什么现在不压"。 */
    enum class Reason {
        /** 不压。 */
        NONE,

        /** 占用达到触发线。 */
        RATIO,

        /** 自上次摘要之后攒够了条数。 */
        COUNT,

        /** 两条同时成立。 */
        RATIO_AND_COUNT,

        /**
         * 快满了：占用已经越过可用预算，哪怕什么都关了也必须压。
         *
         * 这是**保底**而不是"压缩策略" —— 它对应的是"再发下去请求会被服务端拒绝"。
         */
        HARD_LIMIT,
    }

    /**
     * 窗口分档：给触发比例与条数阈值打的加成。
     *
     * 理由：触发线的**绝对值**才是真正决定"什么时候会溢出"的东西，
     * 而摘要的质量随要总结的内容变长而下降。窗口越大，越应该把压缩推到
     * 真正必要的那一刻。
     */
    private enum class Tier(val maxWindow: Int, val ratioBonus: Float, val countFactor: Float) {
        SMALL(64 * 1024, 0.00f, 1.0f),
        MEDIUM(128 * 1024, 0.04f, 1.0f),
        LARGE(256 * 1024, 0.08f, 1.5f),
        XLARGE(512 * 1024, 0.12f, 2.0f),
        HUGE(1024 * 1024, 0.16f, 3.0f),
        ;

        companion object {
            fun of(window: Int): Tier = entries.firstOrNull { window <= it.maxWindow } ?: HUGE
        }
    }

    /**
     * 判定结果。
     *
     * 界面用这些数字解释当前状态：「已用 42 万 / 触发线 90 万 / 还差 48 万」。
     */
    data class Decision(
        /** 该不该现在压。 */
        val shouldCompact: Boolean,
        val reason: Reason,
        /** 模型的标称上下文窗口。 */
        val windowTokens: Int,
        /** 可用输入预算 = 窗口 − 输出预留 − 估算余量。 */
        val usableTokens: Int,
        /** 触发线。占用越过它就压。 */
        val triggerTokens: Int,
        /** 压完之后要落到的位置。**低于**触发线。 */
        val targetTokens: Int,
        /** 快满的保底线：越过它就必须压，与任何设置无关。 */
        val hardLimitTokens: Int,
        /** 生效的触发比例（用户设的基础比例 + 窗口分档加成）。 */
        val effectiveRatio: Float,
        /** 生效的条数阈值（用户设的基础条数 × 窗口分档系数）。 */
        val effectiveCount: Int,
        /** 是否被「大窗口豁免」挡掉了比例/条数触发。 */
        val ratioExempt: Boolean,
        /** 当前占用。 */
        val usedTokens: Int,
        /** 自上次摘要之后的用户消息条数。 */
        val messagesSinceCompaction: Int,
    ) {
        /** 距离触发线还差多少。已经是负数就是已经越线了。 */
        val remainingTokens: Int get() = triggerTokens - usedTokens

        /** 占用百分比（0–100），按**可用预算**算，不是按标称窗口。 */
        val usedPercent: Int
            get() = if (usableTokens <= 0) 0 else (usedTokens * 100L / usableTokens).toInt().coerceIn(0, 999)

        /** 触发线占可用预算的百分比，给界面显示用。 */
        val triggerPercent: Int
            get() = if (usableTokens <= 0) 0 else (triggerTokens * 100L / usableTokens).toInt().coerceIn(0, 200)
    }

    /**
     * 算这次要不要压。
     *
     * @param windowTokens 模型标称上下文窗口
     * @param outputTokens 本次请求预留的输出 token（0 = 没设，按窗口比例兜底）
     * @param usedTokens 当前实际占用
     * @param userRatio 用户设的基础触发比例（0.10–0.95）
     * @param userCount 用户设的基础条数阈值
     * @param byCount 是否启用条数触发
     * @param largeWindowExempt 大窗口豁免开关
     * @param reserveOutput 是否预留输出 token（关掉 = 按整个窗口算）
     * @param messagesSinceCompaction 自上次摘要之后的用户消息条数
     * @param eligibleMessages 本次可被摘要的消息条数（太少就不值得压）
     */
    fun decide(
        windowTokens: Int,
        outputTokens: Int = 0,
        usedTokens: Int = 0,
        userRatio: Float = 0.70f,
        userCount: Int = 16,
        byCount: Boolean = true,
        largeWindowExempt: Boolean = true,
        reserveOutput: Boolean = true,
        messagesSinceCompaction: Int = 0,
        eligibleMessages: Int = Int.MAX_VALUE,
    ): Decision {
        if (windowTokens <= 0) {
            return Decision(
                shouldCompact = false,
                reason = Reason.NONE,
                windowTokens = windowTokens,
                usableTokens = 0,
                triggerTokens = 0,
                targetTokens = 0,
                hardLimitTokens = 0,
                effectiveRatio = userRatio,
                effectiveCount = userCount,
                ratioExempt = false,
                usedTokens = usedTokens,
                messagesSinceCompaction = messagesSinceCompaction,
            )
        }

        val tier = Tier.of(windowTokens)

        /* ── 可用输入预算 ────────────────────────────────── */
        val reserve = if (reserveOutput) {
            val raw = if (outputTokens > 0) {
                outputTokens
            } else {
                (windowTokens * OUTPUT_RESERVE_RATIO).roundToInt()
            }
            raw.coerceIn(OUTPUT_RESERVE_MIN, min(OUTPUT_RESERVE_MAX, windowTokens / 2))
        } else {
            0
        }
        val slack = max(SLACK_MIN, (windowTokens * SLACK_RATIO).roundToInt())
        // 可用预算至少留一半窗口：留太少的话，"压缩"本身都装不下要总结的内容
        val usable = (windowTokens - reserve - slack).coerceAtLeast(max(4_096, windowTokens / 2))

        /* ── 触发线与目标线 ──────────────────────────────── */
        val effectiveRatio = (userRatio + tier.ratioBonus).coerceIn(0.10f, 0.98f)
        val trigger = (usable * effectiveRatio).roundToInt().coerceIn(1, usable)
        val target = (trigger * TARGET_RATIO).roundToInt().coerceIn(1, trigger)
        val effectiveCount = max(1, (userCount * tier.countFactor).roundToInt())

        val ratioExempt = largeWindowExempt && windowTokens >= LARGE_WINDOW

        /* ── 判定 ────────────────────────────────────────── */
        val overRatio = !ratioExempt && usedTokens >= trigger
        val overCount = byCount && !ratioExempt && messagesSinceCompaction >= effectiveCount
        val overHard = usedTokens >= usable

        val reason = when {
            overHard -> Reason.HARD_LIMIT
            overRatio && overCount -> Reason.RATIO_AND_COUNT
            overRatio -> Reason.RATIO
            overCount -> Reason.COUNT
            else -> Reason.NONE
        }

        /* ── 够不够压 ────────────────────────────────────── */
        /*
         * 消息太少就别压：一条"在吗"整理成摘要，既丢信息又白花一次请求。
         * 阈值取 4 —— 少于四条，压出来的摘要比原文还长。
         */
        val enough = eligibleMessages >= 4

        return Decision(
            shouldCompact = enough && reason != Reason.NONE,
            reason = reason,
            windowTokens = windowTokens,
            usableTokens = usable,
            triggerTokens = trigger,
            targetTokens = target,
            hardLimitTokens = usable,
            effectiveRatio = effectiveRatio,
            effectiveCount = effectiveCount,
            ratioExempt = ratioExempt,
            usedTokens = usedTokens,
            messagesSinceCompaction = messagesSinceCompaction,
        )
    }

    /**
     * 摘要请求一次能吃多少内容。
     *
     * 摘要请求本身也要过一遍模型的窗口 —— 要总结 80 万 token 的对话，
     * 那个请求就会先被服务端拒掉，"压缩"反而成了压垮上下文的那一下。
     * 所以超长的会话要**分段总结再合并**（见 [ContextSummary.chunkByTokens]）。
     */
    const val SUMMARY_CHUNK_TOKENS = 90_000

    /**
     * 一次压缩最多摊成几段。
     *
     * 8 段 × 9 万 = 72 万 token 的总结量，够覆盖 1M 窗口里"触发线以下"的
     * 绝大部分。再多就该人工介入了 —— 那说明这段对话需要的不是摘要，
     * 是另开一个会话。
     */
    const val MAX_SUMMARY_CHUNKS = 8

    /**
     * 摘要相对原文的收缩比（经验值）。
     *
     * 四节固定结构的摘要通常是原文的 15%–25%，取 18% 居中。
     * 它只用来**估算**"压完之后会剩多少"，估偏一点不影响正确性 ——
     * 真压完之后的实际占用由 [compactionFloorTokens] 重新量。
     */
    const val SUMMARY_SHRINK_RATIO = 0.18f

    /**
     * 一次摘要请求的目标产出长度（token）。
     *
     * 摘要请求的输出也要占窗口：输入 90 万 + 输出 8 万 = 98 万 > 窗口 1M，
     * 于是"压缩"在最后一步失败，前面的摘要全白做。
     */
    const val SUMMARY_OUTPUT_TOKENS = 16_384
}
