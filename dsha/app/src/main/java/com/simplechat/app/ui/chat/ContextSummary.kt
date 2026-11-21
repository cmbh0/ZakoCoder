package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.data.TokenEstimate

/**
 * 上下文摘要：**整套抄自 Operit**（`FunctionalPrompts.SUMMARY_PROMPT` 与
 * `AIMessageManager.summarizeMemory`）。
 *
 * ### 为什么不再自己写一套
 *
 * 上一版是"把旧对话拼成一份 transcript，让模型写一段前情提要"，再自己定
 * 触发比例、保留条数、增量边界。每一处都要单独调，而且调好一个会破坏另一个：
 * 保留多了压缩没意义，保留少了人名开始漂；比例调高容易撞窗口，调低又会
 * 反复摘要同一段。
 *
 * Operit 的做法是**固定的四节结构 + 上一份摘要整段继承**：
 *
 * 1. 摘要不是"补充说明"，而是**唯一的历史参考**（它自己这么写的）；
 * 2. 每次生成的是**全新、自包含**的一份，上一次摘要作为输入并进去，
 *    所以不会出现"摘要的摘要"逐轮衰减；
 * 3. 输出必须落在四个固定小节里 —— 核心任务、互动与设定、对话历程、关键信息。
 *    模型没有"自由发挥成一段散文"的空间，路径、命令、人名这些硬信息
 *    才有地方落。
 * 4. 末尾附一份**对话回顾**：每条消息压成一行摘录。摘要负责"讲清楚发生了什么"，
 *    回顾负责"保留原话的语感"，两者不互相替代。
 *
 * 这里只做搬运，不做"优化"。要改就改在 [PromptCn] / [PromptEn] 本身，
 * 不要在调用侧再套一层自己的规则。
 */
internal object ContextSummary {

    /**
     * 触发摘要的 token 占用比例。Operit 的默认值就是 0.70
     * （`ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD`）。
     */
    const val TokenThreshold = 0.70f

    /**
     * 按条数触发的阈值：**自上次摘要之后的 user 消息数**。
     * Operit 默认 16（`DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD`）。
     */
    const val MessageCountThreshold = 16

    fun useEnglish(): Boolean = !Res.bool(R.bool.ui_is_cjk)

    /** 原文照抄，连分隔线与"不得更改格式"的措辞一起。 */
    private const val PromptCn = """你是负责生成对话摘要的AI助手。你的任务是根据"上一次的摘要"（如果提供）和"最近的对话内容"，生成一份全新的、独立的、全面的摘要。这份新摘要将完全取代之前的摘要，成为后续对话的唯一历史参考。

**必须严格遵循以下固定格式输出，不得更改格式结构：**

==========对话摘要==========

【核心任务状态】
[先交代用户最新需求的内容与情境类型（真实执行/角色扮演/故事/假设等），再说明当前所处步骤、已完成的动作、正在处理的事项以及下一步。]
[明确任务状态（已完成/进行中/等待中），列出未完成的依赖或所需信息；如在等待用户输入，说明原因与所需材料。]
[显式覆盖信息搜集、任务执行、代码编写或其他关键环节的状态，哪怕某环节尚未启动也要说明原因。]
[最后补充最近一次任务的进度拆解：哪些已完成、哪些进行中、哪些待处理。]

【互动情节与设定】
[如存在虚构或场景设定，概述名称、角色身份、背景约束及其来源，避免把剧情当成现实。]
[用1-2段概括近期关键互动：谁提出了什么、目的为何、采用何种表达方式、对任务或剧情的影响，以及仍需确认的事项。]
[若用户给出剧本/业务/策略等非技术内容，提炼要点并说明它们如何指导后续输出。]

【对话历程与概要】
[用不少于3段描述整体演进，每段包含“行动+目的+结果”，可涵盖技术、业务、剧情或策略等不同主题，需特别点名信息搜集、任务执行、代码编写等阶段的衔接；如涉及具体代码，可引用关键片段以辅助说明。]
[突出转折、已解决的问题和形成的共识，引用必要的路径、命令、场景节点或原话，确保读者能看懂上下文和因果关系。]

【关键信息与上下文】
- [信息点1：用户需求、限制、背景或引用的文件/接口/角色等，说明其具体内容及作用。]
- [信息点2：技术或剧本结构中的关键元素（函数、配置、日志、人物动机等）及其意义。]
- [信息点3：问题或创意的探索路径、验证结果与当前状态。]
- [信息点4：影响后续决策的因素，如优先级、情绪基调、角色约束、外部依赖、时间节点。]
- [信息点5+：补充其他必要细节，覆盖现实与虚构信息。每条至少两句：先述事实，再讲影响或后续计划。]

============================

**格式要求：**
1. 必须使用上述固定格式，包括分隔线、标题标识符【】、列表符号等，不得更改。
2. 标题"对话摘要"必须放在第一行，前后用等号分隔。
3. 每个部分必须使用【】标识符作为标题，标题后换行。
4. "核心任务状态"、"互动情节与设定"、"对话历程与概要"使用段落形式；方括号只为示例，实际输出不需保留.
5. "关键信息与上下文"使用列表格式，每个信息点以"- "开头.
6. 结尾使用等号分隔线.

**内容要求：**
1. 语言风格：专业、清晰、客观.
2. 内容长度：不要限制字数，根据对话内容的复杂程度和重要性，自行决定合适的长度。可以写得详细一些，确保重要信息不丢失。宁可内容多一点，也不要因为过度精简导致关键信息丢失或失真。每个部分都要具备充分篇幅，绝不能以一句话敷衍.
3. 信息完整性：优先保证信息的完整性和准确性，技术与非技术内容都需提供必要证据或引用.
4. 内容还原：摘要既要说明“过程如何推进”，也要写清“实际产出/讨论内容是什么”，必要时引用结果文本、结论、代码片段或参数，确保在没有原始对话的情况下依然能完全还原信息本身.
5. 目标：生成的摘要必须是自包含的。即使AI完全忘记了之前的对话，仅凭这份摘要也能够准确理解历史背景、当前状态、具体进度和下一步行动.
6. 时序重点：请先聚焦于最新一段对话（约占输入的最后30%），明确最新指令、问题和进展，再回顾更早的内容。若新消息与旧内容冲突或更新，应以最新对话为准，并解释差异."""

    private const val PromptEn = """You are an AI assistant responsible for generating a conversation summary. Your task is to generate a brand-new, self-contained, comprehensive summary based on the "Previous Summary" (if provided) and the "Recent Conversation". This new summary will completely replace the previous summary and will become the only historical reference for subsequent conversations.

**You MUST follow the fixed output format below strictly. Do NOT change the structure.**

==========Conversation Summary==========

[Core Task Status]
[First describe the user's latest request and the scenario type (real execution / roleplay / story / hypothetical, etc.), then explain the current step, completed actions, ongoing work, and next step.]
[Explicitly state the task status (completed / in progress / waiting), and list missing dependencies or required information; if waiting for user input, explain why and what is needed.]
[Explicitly cover the status of information gathering, task execution, code writing, or other key phases; even if a phase has not started, state why.]
[Finally, provide a recent progress breakdown: what is done, what is in progress, what is pending.]

[Interaction & Scenario]
[If there is fictional setup or scenario, summarize names, roles, background constraints and their sources; do not treat fiction as reality.]
[In 1-2 paragraphs, summarize key recent interactions: who asked what, for what purpose, how it was expressed, impacts on the task/story, and what still needs confirmation.]
[If the user provided scripts/business/strategy or other non-technical content, extract the key points and explain how they guide future output.]

[Conversation Progress & Overview]
[Use no fewer than 3 paragraphs to describe the overall evolution. Each paragraph should include "action + intent + result". You may cover technical, business, story, or strategy topics. Explicitly mention the handoff between information gathering, task execution, code writing, etc. If relevant, quote key code snippets.]
[Highlight turning points, resolved issues, and agreements reached. Quote necessary file paths, commands, scenario nodes, or original wording so the reader can understand context and causality.]

[Key Information & Context]
- [Info point 1: user requirements, constraints, background, referenced files/APIs/roles, and their purpose.]
- [Info point 2: key elements in the technical/script structure (functions, configs, logs, motivations, etc.) and their meaning.]
- [Info point 3: exploration path, verification results, and current status.]
- [Info point 4: factors affecting future decisions, such as priorities, emotional tone, role constraints, external dependencies, deadlines.]
- [Info point 5+: any other necessary details covering both real and fictional information. Each point must have at least two sentences: state the fact, then explain its impact or next plan.]

=======================================

**Formatting requirements:**
1. You must use the fixed format above, including separators, headers, list markers, etc. Do not change them.
2. The title "Conversation Summary" must be on the first line, surrounded by '='.
3. Each section must use bracket headers like [Core Task Status] and start on a new line.
4. "Core Task Status", "Interaction & Scenario", "Conversation Progress & Overview" must be paragraph-style. Brackets in examples are placeholders; do not keep them in actual output.
5. "Key Information & Context" must be a list, each item starting with "- ".
6. End with the separator line.

**Content requirements:**
1. Style: professional, clear, objective.
2. Length: do not limit length. Decide an appropriate length based on complexity and importance. Prefer being detailed to avoid missing key information.
3. Completeness: prioritize completeness and accuracy. Provide evidence/quotes when needed.
4. Reconstruction: the summary must describe both "how the process progressed" and "what the actual outputs/discussion were". Quote resulting text, conclusions, code snippets, or parameters when needed.
5. Goal: the summary must be self-contained so that even if the AI forgets the original conversation, it can fully reconstruct context, current status, progress, and next actions.
6. Recency: focus first on the most recent part of the conversation (about the last 30% of input), then review earlier content. If new messages conflict with old content, use the latest messages and explain the differences."""

    /**
     * 摘要的系统提示词。
     *
     * 上一份摘要接在**提示词末尾**（Operit 的 `appendPreviousSummary`），
     * 而不是混进对话历史里：它是"要继承的上下文"，不是用户说过的话。
     * 用户自定义规则再往后接（Operit 的 `globalRules`），因为模型对末尾
     * 显式要求的服从度最高。
     */
    fun systemPrompt(
        previousSummary: String?,
        rules: String,
        english: Boolean = useEnglish(),
    ): String {
        val base = if (english) PromptEn else PromptCn
        val withPrevious = if (previousSummary.isNullOrBlank()) {
            base
        } else if (english) {
            base + "\n\n" + """
                Previous Summary (to inherit context):
                ${previousSummary.trim()}
                Please merge the key information from the previous summary with the new conversation and generate a brand-new, more complete summary.
            """.trimIndent()
        } else {
            base + "\n\n" + """
                上一次的摘要（用于继承上下文）：
                ${previousSummary.trim()}
                请将以上摘要中的关键信息，与本次新的对话内容相融合，生成一份全新的、更完整的摘要。
            """.trimIndent()
        }
        val extra = rules.trim()
        return if (extra.isEmpty()) withPrevious else withPrevious + "\n\n" + extra
    }

    /** 摘要请求的最后一条 user 指令（Operit 的 `summaryUserMessage`）。 */
    fun userMessage(english: Boolean = useEnglish()): String =
        if (english) "Please summarize the conversation as instructed." else "请按照要求总结对话内容"

    // ── 分段摘要（长会话） ───────────────────────────────────────

    /**
     * 把要总结的消息按 token 预算切成若干段。
     *
     * ### 为什么需要分段
     *
     * 摘要请求**本身也要过一遍模型的窗口**。要总结 80 万 token 的对话时，
     * 那个请求就是 80 万 token 输入 + 若干 token 输出 —— 1M 的模型勉强够，
     * 128K 的模型直接被服务端拒掉，于是"压缩"成了压垮上下文的最后一步。
     *
     * 窗口越大越危险：1M 窗口下 0.70 触发线 = 70 万 token，一次性发出去
     * 就算塞得进，模型对着 70 万字的上下文做单次压缩，质量也远比
     * 分几段各自压缩再合并要差 —— 注意力被摊薄，细节大量丢失。
     *
     * 所以：**分段总结，再合并**（map-reduce）。每段都是一段正常的对话，
     * 模型能认真读；最后再把几段摘要合成一份。
     *
     * 切分点落在**用户消息边界**上，不能切在中间 —— 否则会出现
     * "一个没有问题的回答"这种半截消息，摘要里会凭空多出半个诉求。
     */
    fun chunkByTokens(
        messages: List<UiMessage>,
        budgetTokens: Int,
    ): List<List<UiMessage>> {
        if (messages.isEmpty()) return emptyList()
        val budget = budgetTokens.coerceAtLeast(8_000)

        /*
         * 先按**回合**分组：一条 user 消息 + 它后面紧跟的 assistant 消息。
         *
         * 切分点必须落在回合之间。切在回合中间会出现"一个没有问题的回答"，
         * 摘要模型看到半截回答，很可能把它当成用户最后的要求。
         *
         * 回合的切法也保证了**不丢消息**：每一段要么整轮进去，要么整轮不进去。
         */
        val turns = mutableListOf<List<UiMessage>>()
        var pending = mutableListOf<UiMessage>()
        for (message in messages) {
            if (message.role == MessageRole.USER && pending.isNotEmpty()) {
                turns += pending.toList()
                pending = mutableListOf()
            }
            pending += message
        }
        if (pending.isNotEmpty()) turns += pending.toList()

        val chunks = mutableListOf<List<UiMessage>>()
        var current = mutableListOf<UiMessage>()
        var currentTokens = 0
        for (turn in turns) {
            val tokens = TokenEstimate.ofAll(turn.map { it.content })
            if (current.isNotEmpty() && currentTokens + tokens > budget) {
                chunks += current.toList()
                current = mutableListOf()
                currentTokens = 0
            }
            current += turn
            currentTokens += tokens
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    /**
     * 分段摘要里每一段用的系统提示词。
     *
     * 与整段摘要的提示词**同一套四节结构**，只是多一段"这是第几段"的交代，
     * 并且**不带上一次的摘要** —— 上一次摘要要留到最后合并时才参与，
     * 否则每段都继承一遍旧摘要，误差会段段放大。
     */
    fun chunkPrompt(
        chunkIndex: Int,
        chunkTotal: Int,
        rules: String,
        english: Boolean = useEnglish(),
    ): String {
        val base = if (english) PromptEn else PromptCn
        val notice = if (english) {
            """
            This is part $chunkIndex of $chunkTotal of a long conversation.
            Summarize only this part, using the fixed format above.
            Key facts, paths, names and commands must be recorded verbatim.
            Other parts will be summarized separately and merged afterwards,
            so do not speculate about content outside this part.
            """.trimIndent()
        } else {
            """
            这是一段很长的对话中的第 $chunkIndex 段（共 $chunkTotal 段）。
            只总结这一段，格式仍然使用上面的固定结构。
            关键事实、路径、人名、命令必须原样记录。
            其余段落会分别总结后再合并，因此不要推测本段之外的内容。
            """.trimIndent()
        }
        val extra = rules.trim()
        return if (extra.isEmpty()) base + "\n\n" + notice else base + "\n\n" + notice + "\n\n" + extra
    }

    /**
     * 合并用的系统提示词：把几段摘要合成**一份**自包含的摘要。
     *
     * 这一次**要**带上一次摘要 —— 它与各段摘要一起，构成"全部已知信息"，
     * 合并结果要能单独取代它们。
     */
    fun mergePrompt(
        previousSummary: String?,
        chunkCount: Int,
        rules: String,
        english: Boolean = useEnglish(),
    ): String {
        val base = if (english) PromptEn else PromptCn
        val notice = if (english) {
            """
            The conversation was too long to summarize in one pass.
            Below are $chunkCount partial summaries, each covering one segment of the conversation.
            Merge them into ONE new self-contained summary following the fixed format above.
            Keep every key fact, path, name, command and pending item that appears in any part.
            When parts conflict, prefer the one from the later segment and say so.
            """.trimIndent()
        } else {
            """
            这段对话太长，无法一次总结完毕。
            下面是 $chunkCount 份分段摘要，每份对应对话的一段。
            请把它们合并成**一份**全新的、自包含的摘要，格式仍然使用上面的固定结构。
            任何一段里出现的关键事实、路径、人名、命令、未完成事项都要保留。
            若各段之间有冲突，以靠后一段为准，并说明差异。
            """.trimIndent()
        }
        val previous = previousSummary?.trim().orEmpty()
        val head = if (previous.isEmpty()) {
            base
        } else if (english) {
            base + "\n\n" + """
                Previous Summary (to inherit context):
                $previous
                Please merge the key information from the previous summary with the partial summaries and generate a brand-new, more complete summary.
            """.trimIndent()
        } else {
            base + "\n\n" + """
                上一次的摘要（用于继承上下文）：
                $previous
                请将以上摘要中的关键信息，与下面的分段摘要相融合，生成一份全新的、更完整的摘要。
            """.trimIndent()
        }
        val extra = rules.trim()
        return if (extra.isEmpty()) head + "\n\n" + notice else head + "\n\n" + notice + "\n\n" + extra
    }

    /** 合并请求里那条 user 指令：把各段摘要摆出来，请模型合并。 */
    fun mergeUserMessage(
        chunkSummaries: List<String>,
        english: Boolean = useEnglish(),
    ): String {
        val header = if (english) "Partial summaries to merge:" else "以下是各段摘要，请合并："
        val body = chunkSummaries.mapIndexed { index, summary ->
            val label = if (english) "Part ${index + 1}" else "第 ${index + 1} 段"
            "---- $label ----\n${summary.trim()}"
        }.joinToString("\n\n")
        val tail = if (english) {
            "Now produce the single merged summary in the fixed format."
        } else {
            "现在请按固定格式产出唯一一份合并后的摘要。"
        }
        return "$header\n\n$body\n\n$tail"
    }

    /** 对话回顾的小标题。 */
    fun reviewHeader(english: Boolean = useEnglish()): String =
        if (english) "Dialogue Review" else "对话回顾"

    /**
     * 对话回顾：每条消息一行摘录，附在摘要正文之后。
     *
     * Operit 会对 assistant 的工具块做专门压缩（`condenseAssistantForReview`）。
     * 我们的工具过程不在正文 XML 里，而是在 [UiMessage.toolCalls] 上，所以
     * 这里直接按结构生成「工具名 + 参数摘要 + 状态」，比解析 XML 更准。
     */
    fun dialogueReview(messages: List<UiMessage>, english: Boolean = useEnglish()): String {
        val labelUser = if (english) "user" else "用户"
        val labelAi = "AI"
        val omitted = if (english) "[Empty]" else "[空]"

        /*
         * 每条消息先各自成块，再按预算从**最近**的一条往前收。
         *
         * Operit 对条数不设上限，因为它默认 16 条用户消息就会压一次；
         * 但 token 阈值先触发时可能是上百条，回顾会涨到几万字符。
         * 这里给一个总预算，超了只丢最早的条目 —— 正文摘要本来就覆盖了
         * 早期内容，回顾的作用是保住最近的语感。
         */
        val blocks = messages.map { message ->
            buildString {
                val speaker = if (message.role == MessageRole.USER) labelUser else labelAi
                val body = if (message.role == MessageRole.USER) {
                    condense(pruneUser(message.content, english), head = 240, tail = 96)
                } else {
                    condense(message.content, head = 280, tail = 120)
                }
                append("- ").append(speaker).append(": ").append(body.ifBlank { omitted }).append('\n')
                if (message.role == MessageRole.ASSISTANT) {
                    message.toolCalls.forEach { call ->
                        append("  - ")
                        append(if (english) "tool " else "工具 ")
                        append(call.name)
                        if (call.summary.isNotBlank()) {
                            append(" ").append(condense(call.summary, head = 72, tail = 32))
                        }
                        append(" → ").append(
                            when (call.status) {
                                ToolCallUi.Status.OK -> if (english) "ok" else "成功"
                                ToolCallUi.Status.FAILED -> if (english) "failed" else "失败"
                                ToolCallUi.Status.AWAITING -> if (english) "awaiting approval" else "等待确认"
                                else -> if (english) "running" else "进行中"
                            },
                        )
                        append('\n')
                    }
                }
            }
        }

        val kept = ArrayDeque<String>()
        var total = 0
        for (block in blocks.asReversed()) {
            if (total + block.length > ReviewBudgetChars && kept.isNotEmpty()) break
            kept.addFirst(block)
            total += block.length
        }

        return buildString {
            append(reviewHeader(english))
            append('\n')
            if (kept.size < blocks.size) {
                val skipped = blocks.size - kept.size
                append(if (english) "- ($skipped earlier messages omitted)\n" else "- （较早的 $skipped 条已省略）\n")
            }
            kept.forEach { append(it) }
        }.trimEnd()
    }

    /** 对话回顾的总字符预算（约 12k token）。超出只丢最早的条目。 */
    private const val ReviewBudgetChars = 24_000

    /**
     * 头尾截断（Operit 的 `condenseHeadTail`）。
     *
     * 只保留开头与结尾：开头一般是"我要做什么"，结尾一般是结论与下一步，
     * 中间的过程性叙述是摘要正文该覆盖的部分。
     */
    private fun condense(text: String, head: Int, tail: Int): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= head + tail + 3) return normalized
        if (head <= 0) return "..." + normalized.takeLast(tail)
        if (tail <= 0) return normalized.take(head) + "..."
        return normalized.take(head) + "..." + normalized.takeLast(tail)
    }

    /**
     * 用户消息里的工作区附件块要摘掉（Operit 的 `pruneUserMessageForReview`）：
     * 那里面是整份文件清单，一行就能吃掉几百字，回顾里没有价值。
     */
    private fun pruneUser(text: String, english: Boolean): String = text
        .replace(
            Regex("<workspace_attachment>[\\s\\S]*?</workspace_attachment>"),
            if (english) "[workspace attachments omitted]" else "[工作区附件已省略]",
        )
        .trim()
}
