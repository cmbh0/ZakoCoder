package com.simplechat.app.net

/**
 * 模型上下文 / 最大输出的**解析器**。
 *
 * 三层来源，优先级从高到低：
 *
 * 1. **用户设置**（三档 128K / 256K / 1M 或自定义）；
 * 2. **内置白名单**（按模型 id 关键词匹配，见 [ModelWhitelist]）；
 * 3. **能力表**（`ModelCatalog`）+ 保守默认 128K。
 *
 * 白名单单独存在的原因：中转站的模型 id 带渠道前缀（`codebuddy/deepseek-v4.1-flash`、
 * `openrouter/deepseek/deepseek-v4.1-flash:free`），直接字符串相等永远匹配不上，
 * 于是 1M 上下文的模型被当成 128K —— 上下文条永远满、压缩阈值提前触发。
 */
object ModelResolver {

    const val CONTEXT_128K = 131_072
    const val CONTEXT_256K = 262_144
    const val CONTEXT_1M = 1_048_576

    /** 上下文档位（持久化的是 name）。 */
    enum class ContextPreset {
        /** 跟随模型：白名单 > 能力表 > 128K。 */
        AUTO,
        K128,
        K256,
        M1,
        CUSTOM,
        ;

        val window: Int?
            get() = when (this) {
                AUTO, CUSTOM -> null
                K128 -> CONTEXT_128K
                K256 -> CONTEXT_256K
                M1 -> CONTEXT_1M
            }

        companion object {
            fun fromId(id: String?): ContextPreset =
                entries.firstOrNull { it.name == id } ?: AUTO
        }
    }

    /** 最大输出的常用档位（0 = 不指定，交给服务端默认）。 */
    val maxOutputOptions = listOf(0, 8_192, 16_384, 32_768, 65_536, 131_072, 393_216)

    /**
     * 按用户设置算出生效的上下文窗口。
     *
     * 自定义值有下限：小于 4K 的窗口连"系统提示 + 一轮对白"都装不下，
     * 压缩逻辑会在每轮都触发，表现为一直转圈。
     */
    fun contextWindowFor(base: ModelInfo, presetId: String?, custom: Int): Int {
        val preset = ContextPreset.fromId(presetId)
        preset.window?.let { return it }
        if (preset == ContextPreset.CUSTOM) {
            return custom.coerceIn(4_096, 4_000_000)
        }
        return base.contextWindow
    }
}

/**
 * 内置模型白名单。
 *
 * 目前只钉死一个：`DeepSeek-V4.1-Flash`，上下文 1M（1,048,576），最大输出 384K（393,216）。
 * 关键词匹配而不是精确匹配，是为了吃下各种渠道前缀与后缀：
 * `codebuddy/deepseek-v4.1-flash`、`deepseek-v4.1-flash-2026`、
 * `openrouter/deepseek/deepseek-v4.1-flash:free` 都该命中同一条规则。
 */
object ModelWhitelist {

    data class Entry(
        val keys: List<String>,
        val contextWindow: Int,
        val maxOutput: Int,
        val label: String,
        /** 是否吃图。默认 true —— 2026 年的主力模型基本都能读图，误判成"不能"的代价是用户每次发消息都被警告。 */
        val supportsVision: Boolean = true,
    )

    private val entries = listOf(
        Entry(
            keys = listOf("deepseek-v4.1-flash", "deepseek-v4-1-flash", "deepseek-v4.1"),
            contextWindow = 1_048_576,
            maxOutput = 393_216,
            label = "DeepSeek V4.1 Flash",
        ),
        Entry(
            keys = listOf("deepseek-v4-flash"),
            contextWindow = 1_048_576,
            maxOutput = 393_216,
            label = "DeepSeek V4 Flash",
        ),
        Entry(
            keys = listOf("deepseek-v4-pro"),
            contextWindow = 1_048_576,
            maxOutput = 131_072,
            label = "DeepSeek V4 Pro",
        ),
    )

    /**
     * 去掉渠道前后缀，只留模型本名。
     *
     * `codebuddy/deepseek-v4.1-flash` → `deepseek-v4.1-flash`
     * `openrouter/deepseek/deepseek-v4.1-flash:free` → `deepseek-v4.1-flash`
     */
    fun canonicalId(modelId: String): String {
        var id = modelId.trim().lowercase()
        id = id.substringAfterLast('/')
        id = id.substringBefore(':')
        return id
    }

    fun match(modelId: String): Entry? {
        val id = canonicalId(modelId)
        if (id.isEmpty()) return null
        return entries.firstOrNull { entry -> entry.keys.any { it in id } }
    }

    /** 把白名单规则套成一条 [ModelInfo]（能力表里没有该 id 时用）。 */
    fun toModelInfo(modelId: String, entry: Entry): ModelInfo = ModelInfo(
        id = modelId,
        displayName = entry.label,
        supportsVision = entry.supportsVision,
        supportsThinking = true,
        contextWindow = entry.contextWindow,
        maxOutputTokens = entry.maxOutput,
    )
}
