package com.simplechat.app.agent

import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.FunctionSpecDto
import com.simplechat.app.net.MessageContent
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.ToolSpecDto
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Agent 运行时事件。UI 只依赖这一层，不认识工具与协议。 */
sealed interface AgentEvent {

    /** 本轮思考增量。 */
    data class Reasoning(val text: String) : AgentEvent

    /** 本轮正文增量。 */
    data class Content(val text: String) : AgentEvent

    /** 开始执行一个工具。 */
    data class ToolStarted(val call: AgentToolCall) : AgentEvent

    /**
     * 这次调用要用户点头才能继续。
     *
     * 与 [ToolStarted] 分开：卡片先出现（"它要做什么"），再变成等待态
     * （"要不要让它做"）。合成一个事件的话，界面只能二选一 ——
     * 要么看不到参数就弹窗，要么等批完才出现卡片。
     */
    data class ApprovalPending(val callId: String, val request: ApprovalRequest) : AgentEvent

    /** 审批结束（允许或拒绝都已记在随后的 [ToolFinished] 里）。 */
    data class ApprovalCleared(val callId: String) : AgentEvent

    /** 工具执行完毕。[ok] 为 false 时 [output] 是失败原因。 */
    data class ToolFinished(
        val callId: String,
        val name: String,
        val ok: Boolean,
        val output: String,
        val elapsedMs: Long,
        /** 写 / 改文件时的行级变更，给界面展示用，不进模型上下文。 */
        val diff: List<DiffLine> = emptyList(),
        /** 这次调用产出的本地文件（生成图片等），只给界面显示。 */
        val files: List<String> = emptyList(),
        /** 这步落盘的文件（报告 / 脚本 / 图）。只作记录，产物条由正文里的 @ 决定。 */
        val artifacts: List<String> = emptyList(),
    ) : AgentEvent

    /** 新一轮开始（第 1 轮是首次回答，第 2 轮起是"看完工具结果继续"）。 */
    data class RoundStart(val index: Int) : AgentEvent

    /** 上游抖了一下，正在自动重试这一轮的请求（不是失败）。 */
    data class Retrying(val attempt: Int, val max: Int) : AgentEvent

    /** 全部结束。 */
    data object Done : AgentEvent

    /** 失败。 */
    data class Failed(val message: String) : AgentEvent
}

/**
 * Agent 主循环。
 *
 * 形态是**多轮请求**：一轮回答里模型要么说话、要么调工具，调完工具再问一次。
 * 这个循环本身很短，复杂度全在边界上：
 *
 * - **轮数不设上限**：默认一直跑到模型自己停止调用工具为止。多步任务
 *   （读十几个文件、反复试命令）本来就不可预测步数，八轮切一刀只会逼用户
 *   说"继续"。真正的刹车是用户手上的停止键。
 * - **工具结果必须回传**：不给结果，模型下一轮会重复同一个调用。
 * - **assistant 消息要带 tool_calls**：这是协议要求，漏了会被网关判成非法序列。
 * - **失败不让循环断**：单个工具失败要把错误文本喂回去，让模型自己改参数重试 ——
 *   这是 Agent 比一次性问答强的地方。
 */
class AgentRunner(
    private val chatApi: ChatApi,
    private val tools: List<AgentTool>,
    private val maxRounds: Int = Int.MAX_VALUE,
    /**
     * 审批策略。
     *
     * 默认 [ApprovalMode.NORMAL]：**没有任何调用会因为"忘了传参数"而被静默放行**。
     * 想让工具放手跑，必须由调用方明确传 YOLO —— 这条默认值是整条链路的地基。
     */
    private val policy: ApprovalPolicy = ApprovalPolicy(),
    /**
     * 审批门。为 null 时按策略直接放行（无界面场景 / 单元测试）。
     *
     * 注意：null 不等于"什么都允许"—— 策略仍会先算一遍，只是没人可问，
     * 于是按 [ApprovalMode.YOLO] 的语义执行。这是刻意的降级，不是漏洞：
     * 没有界面就没有人可以批准，拒绝一切会让工具彻底不可用。
     */
    private val gate: ApprovalGate? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 哪些写工具会被文件快照接管。
     *
     * 只认这三个：它们都带一个明确的 `path` 参数，改的是**一个**已知文件。
     * `run_shell` / `python` 也能写文件，但路径藏在命令里，事前无法知道要备份谁 ——
     * 硬猜只会把"回滚"做成一个不可信的功能（Operit 也是同一档取舍）。
     */
    private val rewindTools = setOf("write_file", "edit_file", "patch_file")

    /**
     * 从工具参数里取出这次要改的文件路径。
     *
     * 云端路径不能走 [Workspace.resolve] —— 那是手机上的目录，和远端毫无关系。
     * 这里只做和 `CloudTools.clean` 一致的去前导斜杠，剩下的交给远端。
     */
    private fun rewindTarget(name: String, args: JsonObject, cloud: Boolean): String? {
        if (name !in rewindTools) return null
        val raw = (args["path"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return if (cloud) {
            raw.removePrefix("file://").trimStart('/').ifEmpty { null }
        } else {
            runCatching { Workspace.resolve(raw).absolutePath }.getOrNull()
        }
    }

    fun run(
        baseUrl: String,
        apiKey: String,
        model: ModelInfo,
        provider: ProviderKind,
        history: List<OutMessageDto>,
        thinkingEnabled: Boolean,
        reasoningEffort: String?,
        maxTokens: Int?,
        temperature: Double?,
        topP: Double?,
        extraHeaders: Map<String, String> = emptyMap(),
        conversationId: String,
        /** 快速模式，原样透传给支持 `fast` 的端点。 */
        fast: Boolean = false,
        /** 这一轮 AI 回复的消息 id。文件快照按它归组（见 [Rewind]）。 */
        messageId: String = "",
        /** 非空 = 这一轮操作的是云端工作环境。 */
        remote: RemoteWorkspace? = null,
        /** 快照作用域，`local` 或 `cloud:<envId>`。 */
        scope: String = "local",
        /**
         * 协议通道。Agent 走 Chat Completions 或 Responses；Anthropic 的块协议
         * 仍只用于普通对话，不在这条工具循环里混用。
         */
        apiStyle: ApiStyle = ApiStyle.OPENAI,
    ): Flow<AgentEvent> = callbackFlow {
        val specs = tools.map { it.toSpec() }
        val messages = history.toMutableList()

        /**
         * 本轮的策略副本。
         *
         * 用户点"总是允许"时只改这一份 —— 不动全局设置，也不跨轮遗留：
         * 下一轮重新从调用方给的 [policy] 开始。
         */
        var active = policy

        var emptyRounds = 0
        /** 思考阶段里被丢弃的工具调用数。 */
        var droppedThinkingCalls = 0
        /** 相同（工具名 + 参数）的失败次数，用来在第二次失败时给一句"别再试了"。 */
        val failCounts = HashMap<String, Int>()

        try {
            for (round in 1..maxRounds) {
                trySend(AgentEvent.RoundStart(round))
                val accumulator = ToolCallAccumulator()
                val text = StringBuilder()
                var reasoningChars = 0
                var failed: String? = null
                /** 是否出现过"调用之后还有思考"—— 见下面那段作废规则。 */
                var reasoningAfterCall = false
                var sawCallDelta = false

                val stream = if (apiStyle == ApiStyle.RESPONSES) {
                    chatApi.streamResponses(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        messages = messages,
                        thinkingEnabled = thinkingEnabled,
                        reasoningEffort = reasoningEffort,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        extraHeaders = extraHeaders,
                        tools = specs,
                    )
                } else {
                    chatApi.streamChat(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        messages = messages,
                        thinkingEnabled = thinkingEnabled,
                        reasoningEffort = reasoningEffort,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        extraHeaders = extraHeaders,
                        provider = provider,
                        tools = specs,
                        fast = fast,
                    )
                }
                stream.collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            text.append(event.text)
                            trySend(AgentEvent.Content(event.text))
                        }
                        is StreamEvent.Reasoning -> {
                            reasoningChars += event.text.length
                            if (sawCallDelta) reasoningAfterCall = true
                            trySend(AgentEvent.Reasoning(event.text))
                        }
                        is StreamEvent.ToolCall -> {
                            sawCallDelta = true
                            accumulator.accept(event)
                        }
                        is StreamEvent.Error -> failed = event.message
                        is StreamEvent.Retrying -> trySend(
                            AgentEvent.Retrying(event.attempt, event.max),
                        )
                        else -> Unit
                    }
                }

                failed?.let {
                    trySend(AgentEvent.Failed(it))
                    close()
                    return@callbackFlow
                }

                /*
                 * 思考阶段里发出来的工具调用**一律作废**。
                 *
                 * 这是模型自己的毛病，不是用户的：它在 `reasoning_content` 还没写完时
                 * 就把 tool_calls 吐了出来 —— 那一批参数是半成品（路径没定、命令没拼完），
                 * 照着跑只会把工作区改坏。判据是**顺序**：只要在收到工具调用增量之后
                 * 还有思考增量，说明这段调用夹在思考中间，整批丢掉，让它想完再说。
                 *
                 * 正常模型是"想完 → 说话 → 调工具"，思考增量不会出现在调用之后，
                 * 所以这条规则对它们完全无感。
                 */
                var calls = accumulator.build()
                if (reasoningAfterCall && calls.isNotEmpty()) {
                    droppedThinkingCalls += calls.size
                    trySend(
                        AgentEvent.ToolFinished(
                            callId = calls.first().id,
                            name = calls.first().name,
                            ok = false,
                            output = "（思考阶段发出的 ${calls.size} 个调用已作废：思考还没结束就动手，参数是半成品。" +
                                "想清楚之后重新发一次。）",
                            elapsedMs = 0,
                        ),
                    )
                    calls = emptyList()
                }
                if (calls.isEmpty()) {
                    /*
                     * 只想了、什么都没说。
                     *
                     * 推理模型偶尔会这样结束一轮：思考通道吐了几百字，正文为空，
                     * 也没有工具调用。直接 Done 等于给用户一条空白回复。
                     * 这里补一句"你还没说完"再试一次 —— 只试一次，第二次还这样
                     * 就认了，别把一次对话拖成无限重试。
                     */
                    if (text.isEmpty() && reasoningChars > 0 && emptyRounds < 1) {
                        emptyRounds++
                        messages += OutMessageDto(
                            role = "user",
                            content = MessageContent.text(
                                "（系统提示）你上一轮只输出了思考过程，没有给出任何回答，也没有调用工具。" +
                                    "现在请继续：要么直接回答用户，要么调用工具去取证。不要只输出思考。",
                            ),
                        )
                        continue
                    }
                    // 没有工具调用 = 这一轮就是最终回答
                    trySend(AgentEvent.Done)
                    close()
                    return@callbackFlow
                }

                // 助手这轮既有话又有调用：两样都要回传，否则模型看不到自己刚才说了什么
                messages += OutMessageDto(
                    role = "assistant",
                    content = MessageContent.text(text.toString()),
                    toolCalls = accumulator.toDto(),
                )

                /*
                 * 没绑工作区时**不能**用 requireRoot —— 它会直接抛异常，
                 * 把"普通对话"整条链打断。这里退到一个必然存在的目录：
                 * 这一轮根本没有文件工具（见 ChatViewModel.localTools），
                 * 这个值只是让 ToolContext 构造得出来。
                 */
                val context = ToolContext(
                    workspace = Workspace.rootOrNull() ?: java.io.File("/"),
                    conversationId = conversationId,
                    messageId = messageId,
                    remote = remote,
                    scope = scope,
                )

                /*
                 * 一次执行一个调用，返回（结果, 耗时）。
                 *
                 * 抽成函数是为了让"并发那一批"和"串行那些"共用同一段实现 ——
                 * 两份写法迟早会漂移（一份忘了把异常兜住，另一份忘了算耗时）。
                 */
                suspend fun invoke(call: AgentToolCall): Triple<ToolOutcome, Long, Long> {
                    val started = System.currentTimeMillis()
                    val tool = tools.firstOrNull { it.name == call.name }
                        ?: return Triple(ToolOutcome.fail("没有名为 ${call.name} 的工具"), 0, started)

                    val args = parseArgs(call.argumentsJson)
                    val risk = tool.riskOf(args)

                    /*
                     * 审批发生在**执行之前**，而不是在模型吐完调用之后统一问一遍。
                     *
                     * 放在这里是因为并发：一组只读调用会同时进这个函数，如果审批在
                     * 外层做，一次弹三个框，用户根本对不上号。挪进来的好处是 ——
                     * 只有真正要动手的那一个会停下来问，界面上的卡片状态也正好是
                     * "这一张在等"。
                     */
                    if (active.needsApproval(risk)) {
                        val request = ApprovalRequest(
                            callId = call.id,
                            tool = tool.name,
                            risk = risk,
                            summary = summarize(tool.name, args),
                            argsJson = call.argumentsJson,
                        )
                        trySend(AgentEvent.ApprovalPending(call.id, request))
                        val decision = gate?.request(request) ?: ApprovalDecision.Allow
                        trySend(AgentEvent.ApprovalCleared(call.id))
                        when (decision) {
                            ApprovalDecision.Deny -> return Triple(
                                ToolOutcome.fail(
                                    "用户拒绝了这次调用（${tool.name}）。不要原样重试；" +
                                        "如果他给了新要求就照做，没给就先说明你想做什么、为什么需要它。",
                                ),
                                System.currentTimeMillis() - started,
                                started,
                            )
                            // 只在这一轮内放宽，不写回设置 —— 见 active 的注释
                            ApprovalDecision.AllowForSession -> active = active.allowForSession(risk)
                            ApprovalDecision.Allow -> Unit
                        }
                    }

                    /*
                     * 写文件之前先拍快照。
                     *
                     * 统一挂在**执行入口**，而不是让每个写工具自己记：
                     * 本地与云端是两套工具类，各记一次迟早会漏一个；
                     * 而且"失败就不该留下记录"这条规则只有在这里能保证 ——
                     * 工具自己不知道调用最终是成功还是抛异常。
                     */
                    val target = if (messageId.isNotBlank()) rewindTarget(tool.name, args, remote != null) else null
                    val snapshot = target?.let { path ->
                        runCatching {
                            Rewind.capture(conversationId, messageId, call.id, scope, path, remote)
                        }.getOrNull()
                    }

                    val outcome = runCatching {
                        tool.execute(args, context)
                    }.getOrElse {
                        ToolOutcome.fail("工具异常：${it.message ?: it.javaClass.simpleName}")
                    }

                    if (outcome is ToolOutcome.Ok) {
                        Rewind.commit(conversationId, snapshot)
                    } else {
                        Rewind.discard(conversationId, snapshot)
                    }

                    return Triple(outcome, System.currentTimeMillis() - started, started)
                }

                fun report(call: AgentToolCall, outcome: ToolOutcome, elapsed: Long) {
                    val ok = outcome is ToolOutcome.Ok
                    var output = when (outcome) {
                        is ToolOutcome.Ok -> outcome.output
                        is ToolOutcome.Failed -> outcome.message
                    }
                    /*
                     * 同一个调用（工具名 + 参数完全一致）连续失败时，第二次就点破。
                     *
                     * 模型最常见的坏习惯是"原样重试" —— 文件没变、参数没变，
                     * 结果当然一样。一句话把它推向"先看清现状"，比让它试到第五次
                     * 再放弃便宜得多。
                     */
                    if (!ok) {
                        val key = call.name + "\u0001" + call.argumentsJson
                        val times = (failCounts[key] ?: 0) + 1
                        failCounts[key] = times
                        if (times >= 2 && !output.contains("[重复失败]")) {
                            output += "\n\n[重复失败] 同样的调用（工具与参数都没变）已经失败 $times 次。" +
                                "不要原样再试：先用 read_file / list_dir 看清现状，或换一种做法。"
                        }
                    }
                    val diff = (outcome as? ToolOutcome.Ok)?.diff.orEmpty()
                    val files = (outcome as? ToolOutcome.Ok)?.files.orEmpty()
                    val artifacts = (outcome as? ToolOutcome.Ok)?.artifacts.orEmpty()
                    trySend(
                        AgentEvent.ToolFinished(
                            call.id, call.name, ok, output, elapsed, diff, files, artifacts,
                        ),
                    )
                    messages += OutMessageDto(
                        role = "tool",
                        content = MessageContent.text(if (ok) output else "错误：$output"),
                        toolCallId = call.id,
                        name = call.name,
                    )
                    /*
                     * 图片只能进 user 消息（协议限制，放 tool 里会被 400）。
                     * 截图识别的结果就跟在这儿：模型下一轮既看得到文字回执，
                     * 也看得到那张图本身。
                     */
                    val images = (outcome as? ToolOutcome.Ok)?.images.orEmpty()
                    if (images.isNotEmpty()) {
                        messages += OutMessageDto(
                            role = "user",
                            content = MessageContent.withImages("（上一步观察到的屏幕截图）", images),
                        )
                    }
                }

                /*
                 * 连续的只读调用**并发跑**。
                 *
                 * 模型一次发三个 `read_file` 是常态。串行在本地没什么感觉，
                 * 但云端每个调用都是一次公网往返 —— 三个串起来就是三秒空白。
                 * 并发之后总耗时约等于最慢的那一个，"像本地"主要就靠这一步。
                 *
                 * 三条约束：
                 * - 只并发**明确只读**的工具（白名单，不是 `!mutating`）：
                 *   MCP 工具的 mutating 默认是 false，但它们背后可能是写操作，
                 *   并发跑等于赌运气。
                 * - 只并发**连续**的一段：中间的写操作必须等前面的读完，
                 *   否则"先写后读"的顺序会被打乱。
                 * - 事件仍按调用顺序发：`ToolStarted` 全发完后才逐个发 `ToolFinished`，
                 *   界面上卡片不会跳来跳去。
                 */
                val runnable = calls

                var cursor = 0
                while (cursor < runnable.size) {
                    val current = runnable[cursor]
                    if (isParallelSafe(current.name)) {
                        var end = cursor
                        while (end + 1 < runnable.size && isParallelSafe(runnable[end + 1].name)) end++
                        val group = runnable.subList(cursor, end + 1)

                        group.forEach { trySend(AgentEvent.ToolStarted(it)) }
                        val results = if (group.size == 1) {
                            listOf(invoke(group[0]))
                        } else {
                            coroutineScope {
                                group.map { call -> async { invoke(call) } }.map { it.await() }
                            }
                        }
                        group.forEachIndexed { index, call ->
                            val (outcome, elapsed, _) = results[index]
                            report(call, outcome, elapsed)
                        }
                        cursor = end + 1
                    } else {
                        trySend(AgentEvent.ToolStarted(current))
                        val (outcome, elapsed, _) = invoke(current)
                        report(current, outcome, elapsed)
                        cursor++
                    }
                }
                // 继续下一轮：让模型看结果接着说
            }

            trySend(AgentEvent.Failed("已达到 $maxRounds 轮工具调用上限，先停在这里。可以把任务拆小一些再继续。"))
            close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(AgentEvent.Failed(e.message ?: "Agent 执行失败"))
            close()
        }

        awaitClose { }
    }

    /**
     * 能不能和相邻的只读调用一起并发跑。
     *
     * 白名单而不是"除了 mutating 都行"：MCP 工具的 `mutating` 默认 false，
     * 但它们背后可能是任意写操作，并发等于赌运气。
     */
    private fun isParallelSafe(name: String): Boolean = when (name) {
        "read_file", "list_dir", "read_skill", "web_fetch" -> true
        else -> false
    }

    /** 参数 JSON 解析。模型偶尔吐出带尾逗号或单引号的半成品，宽松模式下仍可能失败。 */
    private fun parseArgs(raw: String): JsonObject {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    /**
     * 审批条上那一行摘要：路径 / 命令行 / URL。
     *
     * 和界面里的 `toolSummary` 是同一套取法，但**各留一份** —— 那个函数在 UI 层，
     * 这个在 Runner 层；为了一行摘要让 agent 包去依赖 UI 包，是把分层反过来。
     */
    private fun summarize(name: String, args: JsonObject): String {
        fun flat(key: String): String =
            args.str(key).orEmpty().lineSequence().firstOrNull()?.trim().orEmpty()

        val raw = when (name) {
            "run_shell" -> flat("command")
            "python" -> flat("code")
            "read_file", "write_file", "edit_file", "list_dir" -> flat("path")
            "web_fetch" -> flat("url")
            else -> flat("path").ifEmpty { flat("command") }.ifEmpty { flat("url") }
        }
        return if (raw.length > 90) raw.take(90) + "…" else raw
    }

    private fun AgentTool.toSpec(): ToolSpecDto = ToolSpecDto(
        function = FunctionSpecDto(
            name = name,
            description = description,
            parameters = parameters,
        ),
    )
}
