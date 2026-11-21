package com.simplechat.app.ui.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.simplechat.app.R
import com.simplechat.app.agent.Artifacts
import com.simplechat.app.data.Res
import com.simplechat.app.net.ModelInfo

/**
 * 会话模式。由「创作模式 / 扮演模式」的差异驱动系统提示词模板与空态文案。
 *
 * - [CREATIVE] 创作模式：AI 作为写作助手产出台词与正文
 * - [ROLEPLAY] 扮演模式：AI 扮演角色，以对话与动作推进剧情
 *
 * 文案（名称 / 空态标题 / 说明）随界面语言取（`mode_*`）。
 */
enum class ConversationMode(
    @StringRes private val titleRes: Int,
    @StringRes private val emptyTitleRes: Int,
    @StringRes private val captionRes: Int,
) {
    CREATIVE(
        titleRes = R.string.mode_creative_title,
        emptyTitleRes = R.string.mode_creative_empty,
        captionRes = R.string.mode_creative_caption,
    ),
    ROLEPLAY(
        titleRes = R.string.mode_roleplay_title,
        emptyTitleRes = R.string.mode_roleplay_empty,
        captionRes = R.string.mode_roleplay_caption,
    ),
    ;

    val title: String get() = Res.get(titleRes)

    val emptyTitle: String get() = Res.get(emptyTitleRes)

    val caption: String get() = Res.get(captionRes)
}

enum class MessageRole { USER, ASSISTANT }

/** 消息状态。真实流式需要区分「正常结束 / 用户中断 / 出错」三种终态。 */
enum class MessageStatus { DONE, STREAMING, STOPPED, ERROR }

/**
 * 思考档位。**开关与强度合并为一个滑块**，3 档：
 *
 * ```
 * 关闭  ────  high  ────  max
 *  ↑ 最左即关闭
 * ```
 *
 * 滑到最左即关闭思考（发 `thinking.type = disabled`，不带 `reasoning_effort`）；
 * 其余档位发送对应的 `reasoning_effort`。
 */
enum class ReasoningLevel(val apiValue: String?, @StringRes private val labelRes: Int) {
    OFF(null, R.string.reasoning_off),
    LOW("low", R.string.reasoning_low),
    MEDIUM("medium", R.string.reasoning_medium),
    HIGH("high", R.string.reasoning_high),
    XHIGH("xhigh", R.string.reasoning_xhigh),
    MAX("max", R.string.reasoning_max),
    ;

    /** 档位名。OFF 随界面语言，其余是发给 API 的字面值本身。 */
    val label: String get() = Res.get(labelRes)

    val enabled: Boolean get() = this != OFF

    companion object {
        /**
         * 滑块档位顺序，索引即滑块位置。
         *
         * **不含 OFF** —— 开关与档位是两件事：`思考模式` 那个 switch 决定开不开，
         * 这条滑块决定开多大。合成一条"滑到最左就是关"的滑块，会把
         * "关掉"和"用最弱档"压成同一个动作，而它们不是一回事。
         *
         * 五档对齐参考图：low / medium / high / xhigh / max。
         */
        val sliderOrder: List<ReasoningLevel> = listOf(LOW, MEDIUM, HIGH, XHIGH, MAX)

        /** 开关打开时落到哪一档 —— 没记过上次选择就用 medium，中间值最不容易出错。 */
        val default: ReasoningLevel = MEDIUM

        fun fromSliderPosition(position: Int): ReasoningLevel =
            sliderOrder[position.coerceIn(0, sliderOrder.lastIndex)]

        fun sliderPositionOf(level: ReasoningLevel): Int =
            sliderOrder.indexOf(level).takeIf { it >= 0 }
                ?: sliderOrder.indexOf(default)
    }
}

/**
 * 预设：**一段可命名的系统提示词**。
 *
 * 刻意不做结构化字段、不兼容外部角色卡格式 —— 保持泛用性（设计文档 §9.1.1①）。
 * M4 会迁移到 Room 的 `presets` 表。
 */
@Immutable
data class Preset(
    val id: String,
    val name: String,
    val content: String,
)

/**
 * 会话级设置。驱动输入栏两个面板与请求构造。
 *
 * 参数条件化规则（设计文档 §5.4）：思考开启时 `temperature` 不生效、`top_p` 才生效。
 */
@Immutable
data class ConversationSettings(
    val mode: ConversationMode = ConversationMode.CREATIVE,
    /** 会话级补充提示词，与预设拼接后构成最终系统提示词。 */
    val systemPrompt: String = "",
    /**
     * 本会话启用的预设 id。
     *
     * **会话级，不跟着人走**：写小说那条对话要"冷叙事"，问代码那条不需要；
     * 一旦做成全局开关，用户在 A 话题里开的东西会静默改变 B 话题的语气，
     * 而 B 那边连个痕迹都没有。所以开关放在输入栏右上角「⋯」面板里，
     * 切一次会话就换一份，谁也影响不到谁。
     *
     * 存的是 id 而不是正文：预设正文随时可改，改了不必回写会话。
     * 悬空 id（预设已删）在组装提示词时自然落空。
     */
    val enabledPresetIds: List<String> = emptyList(),
    /**
     * 用户人设（全局）：拼在**最前面**。
     *
     * 顺序有讲究 —— 人设说的是"和我说话的人是谁、我该怎么称呼他"，
     * 它是对后面所有规则的前提；放在预设后面会被当成补充说明，
     * 模型容易只执行最近那一段。
     */
    val persona: String = "",
    val model: ModelInfo,
    /** 思考档位；[ReasoningLevel.OFF] 即关闭思考。 */
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    /** 思考模式下不生效，UI 需置灰（官方：静默忽略，不报错）。 */
    val temperature: Float = 1.0f,
    /** 仅思考模式生效，有效范围 0.95–1.0。 */
    val topP: Float = 1.0f,
    val maxTokens: Int? = null,
    /**
     * 快速模式（透传 `fast` 参数，主要给 GPT 系端点）。
     *
     * 默认关闭：不支持的端点看到这个字段会 400，所以只有用户显式打开才发。
     */
    val fast: Boolean = false,
) {
    /**
     * 实际是否开启思考：**只看用户选的档位**。
     *
     * 早先还乘上 `model.supportsThinking`，那是目录里的猜测值 —— 新模型、
     * 中转站别名经常标错，一开就把用户的思考开关静默作废，界面上还显示"已开启"。
     * 现在开了就是开了，端点不支持由它自己报错（或走未知字段兜底）。
     */
    val thinkingEnabled: Boolean get() = reasoningLevel.enabled

    val reasoningEffort: String?
        get() = if (thinkingEnabled) reasoningLevel.apiValue else null

    /**
     * 最终系统提示词：人设 + 已启用预设 + 会话补充。用于预览，避免黑盒。
     *
     * [enabledPresetIds] 取自**本会话**（见字段说明），所以预览出来的
     * 就是下一轮真的会发出去的那段话。
     *
     * **顺序按预设列表走**，不按勾选的先后。理由是"可预期"：列表是用户
     * 唯一看得见的顺序，无论什么时候开、开几次，拼出来的提示词都一样；
     * 而按勾选顺序排的话，同一条预设的权重会随"这次先点哪个"漂移。
     *
     * 悬空 id（预设已被删）自然落空；空正文也跳过 —— 一条只写了名字的预设
     * 不该在提示词里留下一段空行。
     */
    fun composedSystemPrompt(presets: List<Preset>): String {
        val enabled = enabledPresetIds.toSet()
        val parts = buildList {
            if (persona.isNotBlank()) add(persona.trim())
            presets.forEach { preset ->
                if (preset.id in enabled) {
                    val content = preset.content.trim()
                    if (content.isNotEmpty()) add(content)
                }
            }
            if (systemPrompt.isNotBlank()) add(systemPrompt.trim())
        }
        return parts.joinToString("\n\n")
    }
}

/**
 * 当前会话的上下文占用与性能统计。
 *
 * 所有 token 数都是**本地估算**（见 `TokenEstimate`），用于给出量级感，
 * 不用于计费。
 */
@Immutable
data class ContextStats(
    /** 下一次请求实际会占用的 token（含系统提示词、摘要、未被排除的消息）。 */
    val usedTokens: Int,
    /** 模型的上下文窗口。 */
    val windowTokens: Int,
    /** 本地消息条数。 */
    val messageCount: Int,
    /** 本地全部内容的 token（含被排除、被压缩掉的）。 */
    val totalTokens: Int,
    /** 压缩省下的 token 估算；未压缩为 0。 */
    val compactedTokenSaving: Int,
    /** 平均首字延迟。 */
    val avgTtftMs: Long?,
    /** 平均生成速度（token/秒，估算）。 */
    val avgTps: Float?,
    /** 累计思考时长（秒）。 */
    val totalThinkingSeconds: Long?,
    val hasCompaction: Boolean,
) {
    /** 占用比例，0f..1f。 */
    val ratio: Float
        get() = if (windowTokens <= 0) 0f else (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f)

    /** 占用百分比，整数。 */
    val percent: Int get() = (ratio * 100).toInt()
}

/**
 * 思维链分段符（ASCII Record Separator）。
 *
 * Agent 一次回复里可能往返多轮，每轮的思维链各算一段。落库仍只有一个
 * `reasoning` 列，用这个不可见字符分段：渲染侧按它切开、各给一个标题，
 * 旧数据没有它就自然是一段。
 *
 * 选 `\u001E` 而不是 `---` 这类可见标记：模型有时会在思考里自己写分隔线，
 * 用可见标记会把一段思考切碎；控制字符不会出现在正常输出里。
 */
const val ReasoningSeparator = '\u001E'

/** 展示模型；M4 接入 Room 后由实体映射而来。 */
@Immutable
data class UiMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    /** 附件（图片 / 纯文本文件）。见 [com.simplechat.app.data.Attachment]。 */
    val attachments: List<com.simplechat.app.data.Attachment> = emptyList(),
    val reasoning: String? = null,
    val reasoningSeconds: Long? = null,
    /**
     * 思维链是否已经写完（正文已经开始）。
     *
     * 只在流式期间有意义，**不落库** —— 流式中的消息不会被重新读回来
     * （进程被杀时 `resolveDanglingStreams` 会把它们改成中断态）。
     */
    val reasoningDone: Boolean = false,
    val status: MessageStatus = MessageStatus.DONE,
    /** 失败原因，仅 [MessageStatus.ERROR] 时非空。 */
    val errorMessage: String? = null,
    /**
     * 这条由哪个模型产生（`ModelInfo.id`）。
     *
     * 只有 AI 回复有值；用户消息与 v3 以前的老数据为 null ——
     * 那时没记，界面上就**不显示**，而不是拿当前模型猜一个。
     */
    val model: String? = null,
    /** 父亲消息 id；空串表示这是当前分支的根（§17）。 */
    val parentId: String = "",
    /**
     * 落库时间（毫秒）。
     *
     * 「时间戳」技能要用它给每条消息加「什么时候说的」；0 表示未知（流式中
     * 手工构造的那条、或极老的数据），注入时会跳过。
     */
    val createdAt: Long = 0L,
    /** 同父兄弟间的版本号，0 起。 */
    val variantIndex: Int = 0,
    /** 同父兄弟总数，用于渲染 `‹ n/m ›`。 */
    val variantCount: Int = 1,
    /** 首字延迟，用于性能仪表（设计文档 §9.1 P1）。 */
    val ttftMs: Long? = null,
    /** 每秒输出 token 数估算。 */
    val tps: Float? = null,
    /**
     * 内容块时间线（仅 Anthropic 兼容通道有值）。
     *
     * 空 = 这条回复没有结构（OpenAI 兼容通道、或老数据），界面走原来的
     * 「思考栏 + 正文」渲染。非空 = 按块渲染：思考 / 搜索 / 浏览网页 / 正文 + 引用角标。
     */
    val timeline: List<com.simplechat.app.net.ReplyBlock> = emptyList(),
    /**
     * 结束原因（`end_turn` / `max_tokens` / `tool_use` / `refusal`）。
     *
     * 只有 Anthropic 通道会写，**仅作为记录**：不据此删改任何已经收到的内容，
     * 也不据此否决续写。客户端只做透传 —— 服务端发来什么就显示什么、存什么。
     * （唯一用到它的是 `max_tokens`：表示"被上限截断"，可以续写。）
     */
    val stopReason: String? = null,
    /**
     * 这条回复里执行过的工具调用（Agent 模式）。
     *
     * **会落库**（`messages.toolCalls`，见 `data/ToolCalls`）。最初判断
     * "过程不落库"——重开 App 只要还能读到结论就够了；实际用下来是错的：
     * 用户回头翻一段对话，最想确认的恰恰是"它改了哪些文件、跑了哪条命令"。
     *
     * 输出做了截断（单条 4000 字），所以恢复出来的展开详情可能带
     * "已截断"标记 —— 比整条丢失好。
     */
    val toolCalls: List<ToolCallUi> = emptyList(),
) {
    val streaming: Boolean get() = status == MessageStatus.STREAMING

    /**
     * 这一轮**交付给用户**的文件（去重、保持出现顺序）。
     *
     * 只认模型自己在正文里 `@路径` 点出来的那几个 —— 不是"写过就算"。
     * 工具调用的 `artifacts` 只在卡片里记着"动过哪些文件"，不再往产物条上汇总：
     * 改配置、写临时脚本都是过程，交付才是结果。
     *
     * **流式期间恒为空**：产物条只在对话结束后出现，模型写到一半时正文里
     * 的 @ 不算数（可能后面还会改口）。
     *
     * @param remote 云端工作区：产物在远端，本地没有对应文件可查，
     *   只按路径形状认，返回工作区相对路径。
     */
    fun artifacts(remote: Boolean = false): List<String> =
        if (streaming) emptyList() else Artifacts.parse(content, remote = remote)

    /**
     * 思维链是否**还在**生成。
     *
     * 刻意不等于 [streaming] —— 后者是"整条回复还在生成"（思维链 + 正文），
     * 而思维链结束得早得多。用错的后果：正文都快写完了，思考栏还挂在
     * 「正在思考」的尾巴模式上，看起来模型还在想。
     */
    val reasoningStreaming: Boolean get() = streaming && !reasoningDone
}
