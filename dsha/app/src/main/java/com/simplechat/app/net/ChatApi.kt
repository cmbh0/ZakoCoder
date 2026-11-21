package com.simplechat.app.net

import com.simplechat.app.BuildConfig
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.anthropic.AnthropicRequest
import com.simplechat.app.net.anthropic.anthropicMessagesUrl
import com.simplechat.app.net.responses.ResponsesApi
import com.simplechat.app.net.responses.ResponsesPayload
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 对话接口门面。
 *
 * 负责请求构造与**参数条件化**（设计文档 §5.4）—— 这是协议层的硬约束，
 * 不做的话用户会以为参数生效了，其实被服务端静默忽略。
 */
class ChatApi(
    private val client: OkHttpClient,
    private val sseClient: SseClient,
    private val anthropicApi: AnthropicApi,
    private val responsesApi: ResponsesApi,
    private val json: Json,
) {

    /**
     * 「测试连接」专用的客户端。
     *
     * 不直接复用 [client]：它的 `readTimeout(0)` 是**无限等待** —— 那是给流式
     * 响应用的正确设置（模型思考几十秒不吐一个字也不能算断线）。可 `/models`
     * 不是流式接口：中转站把模型列表挂在网关后面不返回时，0 就变成了"永远转圈"。
     * 而协程取消**中断不了** OkHttp 里阻塞的 `execute()`，界面于是永远停在
     * 「测试中」，关掉设置再打开也一样 —— 请求还活着。
     *
     * 25 秒总超时足够任何正常网关返回模型列表，超时也会落到界面上。
     */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 走 Anthropic 兼容通道发起流式对话（扩展模式：搜索 / 读链接 / 引用 / 服务端标题）。
     *
     * 与 [streamChat] 并列而不是合并：两条通道的请求体、事件模型、结束信号都不同，
     * 合并只会让两套判断缠在一起（见 `AnthropicApi` 的注释）。
     */
    fun streamMessage(
        baseUrl: String,
        apiKey: String,
        request: AnthropicRequest,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Flow<StreamEvent> = anthropicApi.streamMessage(
        url = anthropicMessagesUrl(baseUrl),
        apiKey = apiKey,
        request = request,
        extraHeaders = extraHeaders,
    )

    /**
     * 发起流式对话。
     *
     * 参数条件化规则（v1.9.1 起改为**用户意图优先**）：
     * - `thinking` 是 DeepSeek 私有字段 → 只有官方域才发，其他端点不发
     *   （这不是能力白名单，是协议差异：发给不认识的网关只会 400）
     * - `reasoning_effort` 是标准字段 → **用户开了思考就发**。不再按
     *   `model.supportsThinking` 这类本地猜测去砍：目录里的标记经常落后于真实
     *   部署，砍掉了用户连"这个模型到底会不会思考"都试不出来。端点真不认识时，
     *   由下面的未知字段兜底或服务端报错来回答。
     * - 图片同理：一律随请求发出，**不在本地剥图**。模型不看图就让服务端把
     *   错误说清楚，用户看到的是真实原因，而不是一段"看不到图的回答"。
     * - `fast`：用户显式打开才发，默认整个字段不存在。
     * - 思考开启 → **省略 `temperature`**（思考模式下它会被静默忽略）
     * - 思考关闭 → **省略 `top_p`**（非思考模式下它恒为 1.0）
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: ModelInfo,
        messages: List<OutMessageDto>,
        thinkingEnabled: Boolean,
        reasoningEffort: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        provider: ProviderKind = ProviderKind.CUSTOM,
        /** 可调用的工具。为空表示这一轮是纯对话。 */
        tools: List<ToolSpecDto>? = null,
        /** 快速模式：原样透传给支持它的端点（GPT 系）。关闭时该字段不出现。 */
        fast: Boolean = false,
    ): Flow<StreamEvent> {
        val acceptsThinkingField = provider.supportsThinkingField
        val thinkingActive = thinkingEnabled

        val request = ChatRequestDto(
            model = model.id,
            messages = messages,
            stream = true,
            thinking = when {
                !acceptsThinkingField -> null
                thinkingEnabled -> ThinkingDto.Enabled
                else -> ThinkingDto.Disabled
            },
            reasoningEffort = if (thinkingActive) reasoningEffort else null,
            maxTokens = maxTokens,
            temperature = if (thinkingActive) null else temperature,
            topP = if (thinkingActive) topP else null,
            tools = tools?.takeIf { it.isNotEmpty() },
            // 有工具时用 auto：强制 required 会让模型在纯聊天时也硬塞一个调用
            toolChoice = if (tools.isNullOrEmpty()) null else "auto",
            fast = if (fast) true else null,
        )

        val url = chatCompletionsUrl(baseUrl)

        /*
         * 400「不认识的参数」兜底。
         *
         * 中转站背后可能是老版本 vLLM / 自建网关，对 `reasoning_effort`、
         * `top_p=1` 之类的边角字段照样挑剔。这些字段**全都不是必需的**，
         * 而 400 发生在任何内容下发之前 —— 剥掉扩展字段重发一次，用户看到的
         * 就是正常回答，而不是一句英文 400。
         */
        return flow<StreamEvent> {
            var stripped = false
            var emittedBody = false
            /*
             * 临时故障的退避重试：1s → 2s → 4s → 8s → 16s，最多 5 次。
             *
             * 服务不稳定是常态（中转站掉线、限流、网关 502），一次抖动就把整轮
             * 工作打断，Agent 任务尤其吃亏。这里只在"还一个字都没吐"时重试 ——
             * 已经下发的半截回答不能重来，否则界面上会有两份；那种情况交给上层
             * 走**续写**（见 ChatViewModel 的断流自动续跑）。
             */
            var transientRetries = 0

            suspend fun run(inner: ChatRequestDto) {
                sseClient.stream(
                    url = url,
                    apiKey = apiKey,
                    request = inner,
                    extraHeaders = extraHeaders,
                ).collect { event ->
                    if (event is StreamEvent.Content || event is StreamEvent.Reasoning) emittedBody = true
                    when {
                        !stripped && event is StreamEvent.Error && event.code == 400 &&
                            looksLikeUnknownField(event.message) -> {
                            stripped = true
                            run(inner.copy(thinking = null, reasoningEffort = null, userId = null, fast = null))
                        }
                        event is StreamEvent.Error && !emittedBody &&
                            transientRetries < MaxTransientRetries && isTransient(event) -> {
                            transientRetries++
                            emit(StreamEvent.Retrying(transientRetries, MaxTransientRetries))
                            kotlinx.coroutines.delay(1_000L shl (transientRetries - 1))
                            run(inner)
                        }
                        else -> emit(event)
                    }
                }
            }

            run(request)
        }
    }

    /**
     * 走 OpenAI Responses 通道发起流式对话（ChatGPT Codex / GPT-5 系原生协议）。
     *
     * 这**不是** `/chat/completions` 的别名：请求体由 [ResponsesPayload] 翻译成
     * `instructions + input`，工具定义是扁平结构，流回来的是语义事件。把它硬塞
     * 进 Chat 通道，服务端只会回 404 或一串无法解析的 SSE。
     */
    fun streamResponses(
        baseUrl: String,
        apiKey: String,
        model: ModelInfo,
        messages: List<OutMessageDto>,
        thinkingEnabled: Boolean,
        reasoningEffort: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        tools: List<ToolSpecDto>? = null,
    ): Flow<StreamEvent> {
        val payload = ResponsesPayload.build(
            model = model.id,
            messages = messages,
            tools = tools,
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = if (thinkingEnabled) reasoningEffort else null,
            maxTokens = maxTokens,
            temperature = if (thinkingEnabled) null else temperature,
            topP = if (thinkingEnabled) null else topP,
        )
        return responsesApi.stream(
            url = responsesUrl(baseUrl),
            apiKey = apiKey,
            payload = payload,
            extraHeaders = extraHeaders,
        )
    }

    /**
     * 拉取模型列表。OpenCode Go 的 `/models` 无需鉴权（设计文档 §5.2）。
     * 失败不抛异常，由调用方决定提示方式。
     *
     * 响应格式**不按单一 schema 硬解**：中转站（one-api / new-api / LiteLLM 等）
     * 返回的形态至少有四种，硬解会让"连接成功但列表是空的"：
     *
     * ```json
     * {"data":[{"id":"gpt-4o"}]}        // OpenAI 标准
     * {"data":["gpt-4o"]}               // 偷懒的网关
     * {"models":[{"name":"gpt-4o"}]}    // Ollama 风格
     * ["gpt-4o"]                        // 裸数组
     * ```
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 空地址拼出来是 "/models"，OkHttp 直接抛 IllegalArgumentException，
                // 报错会变成一句看不懂的 "Expected URL scheme"
                if (baseUrl.isBlank()) error(Res.get(R.string.error_base_url_required))
                val url = modelsUrl(baseUrl)
                val builder = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiKey")
                }
                probeClient.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 把服务端原话带出来：中转站的报错通常是"无可用渠道"这类，
                        // 只说 HTTP 401 用户根本不知道该改哪里
                        val detail = runCatching {
                            val element = json.parseToJsonElement(text)
                            element.jsonObject["error"]?.jsonObject?.get("message")
                                ?.jsonPrimitive?.contentOrNull
                        }.getOrNull() ?: text.take(160)
                        error("HTTP ${response.code}${if (detail.isBlank()) "" else " · $detail"}")
                    }
                    val ids = parseModelIds(text)
                    ids.ifEmpty { error("返回里没有模型（$url）") }
                }
            }
        }

    /** 尽力从任意一种 `.models` 响应里挖出 id 列表；顺序按服务端给的。 */
    private fun parseModelIds(text: String): List<String> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()

        fun idOf(element: JsonElement): String? {
            if (element is JsonPrimitive) return element.contentOrNull?.takeIf { it.isNotBlank() }
            val obj = element as? JsonObject ?: return null
            for (key in listOf("id", "name", "model")) {
                // 字段不是字符串（个别网关塞的是对象）时别炸，换下一个键试
                val value = runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (!value.isNullOrBlank()) return value
            }
            return null
        }

        fun idsOf(element: JsonElement?): List<String> =
            (element as? JsonArray)?.mapNotNull { idOf(it) }.orEmpty()

        return when (root) {
            is JsonArray -> idsOf(root)
            is JsonObject -> {
                // 标准形态：data / models 直接是数组
                listOfNotNull(root["data"], root["models"])
                    .firstOrNull { it is JsonArray }
                    ?.let { return idsOf(it) }
                // 嵌套形态：{"data":{"models":[…]}}
                val nested = root["data"] as? JsonObject
                listOfNotNull(nested?.get("models"), nested?.get("data"))
                    .firstNotNullOfOrNull { element -> idsOf(element).takeIf { it.isNotEmpty() } }
                    .orEmpty()
            }
            else -> emptyList()
        }.distinct()
    }

    companion object {

        /**
         * 官方要求客户端明确标识自身，不要使用宽泛（如 okhttp/x.y）的 User-Agent。
         *
         * 版本号**从 BuildConfig 取**，不写死 —— 写死之后每次改版本都会忘记同步，
         * 服务端看到的客户端版本就成了假的（改包名 / 定 0.9.0-beta 那次就漏了）。
         */
        val USER_AGENT: String = "DSHA/${BuildConfig.VERSION_NAME} (Android)"

        /**
         * 本地 OkHttpClient。
         *
         * 流式响应不能设 readTimeout —— 模型思考时可能几十秒不吐一个字节，
         * 设了会被误判断线。只保留连接与写超时。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                /*
                 * 已经显式带了 UA 的请求不要覆盖。
                 *
                 * 默认 UA 是 `DSHA/x.y.z`；调用方若在 per-request 头里给了
                 * 自己的 UA（网关要求的客户端标识），这里就原样放行。
                 */
                if (request.header("User-Agent") != null) {
                    chain.proceed(request)
                } else {
                    chain.proceed(
                        request.newBuilder().header("User-Agent", USER_AGENT).build(),
                    )
                }
            }
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            // 必须为 true：`stream` / `thinking.type` 这类字段一旦被当作默认值省略，
            // 请求语义就完全变了（见 ChatRequestDto.stream 的注释）。
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }
    }
}

/** 构造消息 `content`。文本 → 字符串；带图 → content block 数组。 */
object MessageContent {

    fun text(text: String): JsonElement = JsonPrimitive(text)

    /**
     * 带图片的 content。
     *
     * ⚠️ 图片**只允许出现在 user 消息中**，放在 system / assistant 会返回 400（§11.3）。
     */
    fun withImages(text: String, images: List<ImageInput>): JsonElement = buildJsonArray {
        if (text.isNotBlank()) {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", image.dataUrl)
                        put("detail", image.detail)
                    }
                },
            )
        }
    }
}

/**
 * 图片输入。[dataUrl] 形如 `data:image/jpeg;base64,...`。
 *
 * `detail` 的实际取值由调用方决定（见 `ui/chat/RequestContent.kt` 的 `ImageDetail`）——
 * 本项目固定用 `high`：`low` 会把图缩到 512×512，截图里的小字会糊掉，
 * 而"看截图里的文字"恰恰是最常用的场景。
 */
data class ImageInput(
    val dataUrl: String,
    val detail: String = "high",
)

/**
 * 400 的报错像不像「参数不认识」。
 *
 * 只看文案，不做结构化判断：各家网关的字段名（`Unrecognized request argument`、
 * `extra fields not permitted`、`Expecting value`…）根本没有统一标准。
 * 判错的代价很小 —— 剥掉可选字段重发一次，本来这一轮也已经失败了。
 */
/** 单轮请求里临时故障的最大重试次数（退避 1s→16s）。 */
private const val MaxTransientRetries = 5

/**
 * 值得重试的临时故障。
 *
 * 除了限流与 5xx，还包含 408（请求超时）、409/425（上游并发与过早），
 * 以及 code 0 —— 那是本地连接断开 / socket 超时，不是服务端拒绝。
 * 明确的 4xx 业务错误（400 参数、401 鉴权、403 拒绝）不重试：再试也是同样结果。
 */
private fun isTransient(event: StreamEvent.Error): Boolean =
    event.code == 429 || event.code in 500..599 || event.code == 0 ||
        event.code == 408 || event.code == 409 || event.code == 425

private fun looksLikeUnknownField(message: String): Boolean {
    val text = message.lowercase()
    return listOf(
        "unrecognized",
        "unknown",
        "unsupported",
        "not permitted",
        "extra field",
        "unexpected",
        "invalid request",
        "不支持",
        "无法识别",
        "未知参数",
    ).any { it in text }
}
