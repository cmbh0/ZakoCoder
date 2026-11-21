package com.simplechat.app.net

import androidx.annotation.StringRes
import com.simplechat.app.R
import com.simplechat.app.data.Res

/**
 * 服务商预设。列出的只是**端点**，Key 与模型都由用户那套配置决定（见设计文档 §5.2）。
 *
 * 统一走 OpenAI 兼容 `/chat/completions`，只做这一个适配器。
 */
enum class ProviderKind(
    @StringRes private val nameRes: Int,
    val defaultBaseUrl: String,
    /** 模型列表接口是否需要鉴权。本机服务的 `/models` 可以直接拉。 */
    val modelListNeedsAuth: Boolean = true,
    /**
     * 这条通道是否**接受 `thinking: {type: …}` 这个字段**。
     *
     * `thinking` 是 DeepSeek 的私有扩展，不是 OpenAI 标准字段。标准 OpenAI
     * （`api.openai.com`）与绝大多数严格实现的兼容端点（vLLM / Ollama / one-api
     * 的严格模式）会直接回 400 `Unrecognized request argument`。
     * 所以只对认这个字段的两家发，其余一律走标准字段（`reasoning_effort`）。
     */
    val supportsThinkingField: Boolean = false,
    /**
     * 这条通道是否需要 API Key。
     *
     * 本机服务（Ollama / LM Studio）不需要 Key —— 早先"没填 Key 就不让发"的判断
     * 把它们一起拦了，用户会以为软件坏了。
     */
    val requiresApiKey: Boolean = true,
) {
    /*
     * 只保留**端点**，不再带默认模型。
     *
     * 以前每家都写了一个 `defaultModel`，换服务商时自动填上它 —— 于是模型列表
     * 里永远混着几个用户没选过、也删不掉的条目（它们来自内置目录，不是端点
     * 真实返回的）。现在换服务商 = 清空当前模型，用户从 `/models` 拉到的真实
     * 列表里自己挑。
     */

    /** 标准 OpenAI 官方端点。协议就是 OpenAI 自己，扩展字段一概不发。 */
    OPENAI(
        nameRes = R.string.provider_name_openai,
        defaultBaseUrl = "https://api.openai.com/v1",
    ),
    DEEPSEEK(
        nameRes = R.string.provider_name_deepseek,
        defaultBaseUrl = "https://api.deepseek.com",
        supportsThinkingField = true,
    ),
    /* ── 国内常用 ─────────────────────────────────────────── */

    /** 智谱 GLM。OpenAI 兼容层在 `/api/paas/v4`，不是 `/v1`。 */
    ZHIPU(
        nameRes = R.string.provider_name_zhipu,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
    ),
    /** 月之暗面 Kimi。 */
    MOONSHOT(
        nameRes = R.string.provider_name_moonshot,
        defaultBaseUrl = "https://api.moonshot.cn/v1",
    ),
    /** 阿里云百炼（通义千问的兼容模式）。 */
    DASHSCOPE(
        nameRes = R.string.provider_name_dashscope,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    ),
    /** 硅基流动：一个 Key 通吃几十个开源模型。 */
    SILICONFLOW(
        nameRes = R.string.provider_name_siliconflow,
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
    ),

    /* ── 聚合与海外 ───────────────────────────────────────── */

    OPENROUTER(
        nameRes = R.string.provider_name_openrouter,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
    ),
    GROQ(
        nameRes = R.string.provider_name_groq,
        defaultBaseUrl = "https://api.groq.com/openai/v1",
    ),
    XAI(
        nameRes = R.string.provider_name_xai,
        defaultBaseUrl = "https://api.x.ai/v1",
    ),
    MISTRAL(
        nameRes = R.string.provider_name_mistral,
        defaultBaseUrl = "https://api.mistral.ai/v1",
    ),
    /** Gemini 的 OpenAI 兼容层。原生协议不接，只走这一条，少一套适配器。 */
    GEMINI(
        nameRes = R.string.provider_name_gemini,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
    ),

    /* ── 本地 ─────────────────────────────────────────────── */

    /** 本机 Ollama。默认端口 11434，不需要 Key。 */
    OLLAMA(
        nameRes = R.string.provider_name_ollama,
        defaultBaseUrl = "http://localhost:11434/v1",
        modelListNeedsAuth = false,
        requiresApiKey = false,
    ),
    /** LM Studio。默认端口 1234，不需要 Key。 */
    LMSTUDIO(
        nameRes = R.string.provider_name_lmstudio,
        defaultBaseUrl = "http://localhost:1234/v1",
        modelListNeedsAuth = false,
        requiresApiKey = false,
    ),

    CUSTOM(
        nameRes = R.string.provider_name_custom,
        defaultBaseUrl = "",
    ),
    ;

    /** 显示名，随界面语言取（`provider_name_*`）。 */
    val displayName: String get() = Res.get(nameRes)
}

/**
 * 模型能力。驱动 UI（例如不具备识图能力时不显示输入区的 ⊕）与参数条件显示（§5.4）。
 *
 * 能力不全的模型（尤其自定义端点）由用户在设置里手动勾选覆盖。
 */
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val supportsVision: Boolean = false,
    val supportsThinking: Boolean = false,
    /** 上下文窗口，用于占用进度条与压缩阈值（§9.1.1③）。 */
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    /** 该模型的最大输出 token 数；0 = 未知，交给用户设置或服务端默认。 */
    val maxOutputTokens: Int = 0,
) {
    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 128_000
    }
}

/** 已知模型的能力表。未知模型回落到保守默认值，可在设置里手动覆盖。 */
object ModelCatalog {

    /**
     * 标准 OpenAI 官方目录。
     *
     * 只收**确定存在**的那几个，且刻意不夸大能力：识图只给 `gpt-4o*` / `gpt-5*`，
     * 思考只给 o 系与 gpt-5 系（`reasoning_effort` 对它们有效）。
     * `/models` 拉回来的未知 id 一律按保守值处理，用户可在设置里手动改。
     */
    private val openAi = listOf(
        ModelInfo(
            id = "gpt-4o-mini",
            displayName = "GPT-4o mini",
            supportsVision = true,
            contextWindow = 128_000,
        ),
        ModelInfo(
            id = "gpt-4o",
            displayName = "GPT-4o",
            supportsVision = true,
            contextWindow = 128_000,
        ),
        ModelInfo(
            id = "gpt-4.1",
            displayName = "GPT-4.1",
            supportsVision = true,
            contextWindow = 1_047_576,
        ),
        ModelInfo(
            id = "gpt-4.1-mini",
            displayName = "GPT-4.1 mini",
            supportsVision = true,
            contextWindow = 1_047_576,
        ),
        ModelInfo(
            id = "o4-mini",
            displayName = "o4-mini",
            supportsVision = true,
            supportsThinking = true,
            contextWindow = 200_000,
        ),
        ModelInfo(
            id = "o3",
            displayName = "o3",
            supportsVision = true,
            supportsThinking = true,
            contextWindow = 200_000,
        ),
    )

    private val deepSeek = listOf(
        ModelInfo(
            id = "deepseek-flash",
            displayName = "DeepSeek V4.1 Flash",
            supportsVision = true,
            supportsThinking = true,
            contextWindow = 1_000_000,
        ),
        ModelInfo(
            id = "deepseek-v4-pro",
            displayName = "DeepSeek V4 Pro",
            supportsVision = false,
            supportsThinking = true,
            contextWindow = 1_000_000,
        ),
    )

    fun knownModels(kind: ProviderKind): List<ModelInfo> = when (kind) {
        ProviderKind.OPENAI -> openAi
        ProviderKind.DEEPSEEK -> deepSeek
        // 其余渠道没有内置目录：模型一律以端点 `/models` 为准
        else -> emptyList()
    }

    /**
     * 全部内置能力表按 id 索引（[deepSeek] 在前，同一 id 以先出现的为准）。
     *
     * 第三方端点（自建网关 / 中转站）常常原样代理官方模型，id 和官方**一模一样**：
     * `deepseek-flash`、`glm-5.2`、`qwen3.8-max`… 这时能力表当然该照样生效。
     * 早先只查「当前服务商」那张表，自定义服务商那张是空的 —— 于是一个明明是
     * 1M 上下文、带识图与思考的 `deepseek-flash`，被当成 128K 的未知模型：
     * 上下文占用条按 128K 算（显示 80% 实际才 10%）、压缩阈值提前触发、
     * 图片被当成"模型不支持"直接摘掉。
     *
     * ⚠️ 不能直接 `associateBy`：同一个 id 可能两张表都收，而 `associateBy`
     * 是**后写覆盖前写**。这里显式「先到先得」，语义才和"官方表优先"一致。
     */
    private val allKnown: Map<String, ModelInfo> by lazy {
        buildMap {
            openAi.forEach { putIfAbsent(it.id, it) }
            deepSeek.forEach { putIfAbsent(it.id, it) }
        }
    }

    /**
     * 按 id 找能力：**先看当前服务商的那张表，再看全局表**，都没有就用保守默认值。
     *
     * 顺序不能反：同一张表里同一个 id 的能力更可信（官方目录是实测路由结果），
     * 全局表只负责"换到自建端点时也认得出这个模型"。
     *
     * 全局表都没有的 id 一律回落到 [ModelInfo] 默认值（128K / 不支持识图与思考）。
     * **刻意不按 id 的字面去猜**：把上下文窗口猜大，压缩就不会及时触发，最后是
     * 服务端 400（`maximum context length`）；猜小只是早点压缩。识图/思考同理 ——
     * 猜错会直接发出模型不接受的参数。这三样都必须靠"确知"或用户手动填。
     */
    fun find(kind: ProviderKind, modelId: String): ModelInfo {
        val canonical = ModelWhitelist.canonicalId(modelId)
        // 精确匹配（含渠道前缀的 id 也要认：中转站会把 `codebuddy/xxx` 原样塞回来）
        knownModels(kind).firstOrNull { it.id.equals(modelId, true) || it.id.equals(canonical, true) }
            ?.let { return it.withId(modelId) }
        allKnown[modelId]?.let { return it.withId(modelId) }

        /*
         * 白名单：`codebuddy/deepseek-v4.1-flash` 这类带前缀的 id 走关键词匹配。
         * 放在最后而不是最前 —— 用户/厂商目录里明确写了的能力更可信。
         */
        ModelWhitelist.match(modelId)?.let { return ModelWhitelist.toModelInfo(modelId, it) }

        return ModelInfo(id = modelId)
    }

    /** 显示名与能力沿用目录，但**请求里必须用用户填的原 id**（带前缀的那种）。 */
    private fun ModelInfo.withId(modelId: String): ModelInfo =
        if (id == modelId) this else copy(id = modelId)

    /**
     * 把运行时拉取到的模型 id 与已知能力表合并。
     * 已知的保留能力（含别的服务商表里认得的），未知的用保守默认值补进来。
     */
    fun merge(kind: ProviderKind, remoteIds: List<String>): List<ModelInfo> {
        if (remoteIds.isEmpty()) return knownModels(kind)
        return remoteIds.map { find(kind, it) }
    }

    /**
     * 设置页「模型」选择器要列出的模型。
     *
     * 与 [merge] 的差别是**不丢任何一边**：
     *
     * - 远端拉到的 id 排在最前 —— 它们是这个端点**真实可用**的模型，用户换端点
     *   之后找的就是这些；顺序按服务端给的来（中转站常把主力模型放前面）。
     * - 已知能力表里没被远端覆盖的条目补在后面。有些网关的 `/models` 只回一部分、
     *   或者干脆不实现这个端点（返回 404），不能让用户因此一个都选不到。
     *
     * `CUSTOM` 没有内置目录，于是「远端列表为空」时这里就是空 —— 界面必须给出
     * 空态提示和一个手动填写的入口，而不是弹一个只有标题的白框。
     */
    fun pickerModels(kind: ProviderKind, remoteIds: List<String>): List<ModelInfo> {
        val ids = remoteIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        /*
         * 只列**这个端点真实返回过**的模型。
         *
         * 以前这里还会把内置目录补在后面，理由是"有些网关不实现 /models"。
         * 代价是列表里永远躺着几个用户没选过、也删不掉的条目 —— 用户的原话是
         * "添加了就没办法删除"。现在拉不到就空着，界面给"手动填写"入口，
         * 那种情况下用户填什么就是什么。
         */
        return ids.map { find(kind, it) }
    }

    /**
     * 把「勾选过的模型」从整份目录里筛出来 —— 气泡里只显示这一部分。
     *
     * 三种情况都要能走：
     * - 没勾过（空集）：返回全部。升级上来的用户不该突然一个模型都看不到。
     * - 勾过且命中：只返回勾中的，顺序沿用目录顺序。
     * - 勾过但一个都不在了（换了端点 / 服务端下线了模型）：回落到全部，
     *   否则用户会看到一个空列表，而原因在另一个页面里。
     */
    fun visibleModels(
        kind: ProviderKind,
        remoteIds: List<String>,
        pinned: List<String>,
        currentModelId: String? = null,
    ): List<ModelInfo> {
        val all = pickerModels(kind, remoteIds)
        val selected = pinnedIds(kind, pinned)
        val base = if (selected.isEmpty()) all else all.filter { it.id in selected }.ifEmpty { all }

        /*
         * 当前正在用的那个**永远留在列表里**。
         *
         * 用户完全可能在设置里勾完就忘了把当前模型也勾上（或者换服务商之后
         * 原来那个不在了）。这时气泡里一个高亮都没有 —— 看起来像"当前模型不知道是哪个"，
         * 而实际原因藏在另一个页面。补进来，代价只是列表多一行。
         */
        val current = currentModelId?.takeIf { it.isNotBlank() }
        if (current != null && base.none { it.id == current }) {
            return listOf(find(kind, current)) + base
        }
        return base
    }

    /** 从 `KIND:id` 形式的记录里挑出属于 [kind] 的 id。 */
    fun pinnedIds(kind: ProviderKind, pinned: List<String>): Set<String> {
        val prefix = kind.name + ":"
        return pinned.filter { it.startsWith(prefix) }
            .mapTo(LinkedHashSet()) { it.removePrefix(prefix) }
    }
}

/** 服务商配置。 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    /** 空 id = 还没选模型。**故意没有默认值**：默认模型是删不掉的那种脏数据。 */
    val model: ModelInfo = ModelInfo(id = ""),
)

/**
 * 通信协议通道（v2.1 起为三条：Chat Completions / Anthropic / Responses）。
 *
 * | 通道 | 端点 | 能拿到什么 |
 * |---|---|---|
 * | [OPENAI] | `/chat/completions` | 正文、`reasoning_content`、`reasoning_effort` 三档思考强度 |
 * | [ANTHROPIC] | `/anthropic/v1/messages` | 联网搜索、链接阅读、正文引用、服务端标题、时间地点提醒 |
 * | [RESPONSES] | `/responses` | ChatGPT Codex / GPT-5 系原生的 Responses 协议，含函数调用与思考摘要 |
 *
 * 官方 Chat Demo 的全部扩展能力只在 Anthropic 通道上提供，而 OpenAI 兼容通道
 * 覆盖面最广（自建端点 / 各类中转）。Responses 是 OpenAI 新协议，不能靠
 * `/chat/completions` 冒充 —— 它的事件模型与工具调用结构都不同，所以单独一条。
 */
enum class ApiStyle {
    OPENAI,
    ANTHROPIC,
    RESPONSES;

    companion object {
        fun fromId(id: String?): ApiStyle? = when (id) {
            "OPENAI" -> OPENAI
            "ANTHROPIC" -> ANTHROPIC
            "RESPONSES" -> RESPONSES
            // "AUTO" 与未知值都交给下面的按端点解析
            else -> null
        }

        /**
         * 实际走哪条通道。
         *
         * `AUTO` 的判据是**端点**而不是"当前模型"：
         * - `provider = DEEPSEEK` 且 Base URL 仍指向官方端点 → 扩展通道；
         * - 其余（自建、以及把 DeepSeek 的 Base URL 改成自家网关的）→ OpenAI 兼容。
         *
         * 为什么还要看 Base URL：`provider = DEEPSEEK` 只是个"预设"，
         * 用户可以把它改成任何地址。那种情况下扩展端点不存在，猜过去就是**每一次请求都 404**，
         * 而"没拿到搜索能力"只是少个功能 —— 两害相权，默认走覆盖面最广的那条。
         * 用户确实需要扩展能力时，在设置里把通道显式设成 Anthropic 即可。
         *
         * Responses 同理，只有**用户显式选择**或 Base URL 本身就写着 `/responses`
         * 才走它。不能因为模型叫 gpt-5 就猜 —— 同一个模型在多数中转站上仍然只
         * 提供 `/chat/completions`。
         */
        fun resolve(kind: ProviderKind, configuredId: String?, baseUrl: String): ApiStyle =
            fromId(configuredId) ?: run {
                val normalized = baseUrl.trim().trimEnd('/')
                val official = normalized.contains("api.deepseek.com")
                when {
                    normalized.endsWith("/responses") -> RESPONSES
                    normalized.contains("backend-api/codex") -> RESPONSES
                    kind == ProviderKind.DEEPSEEK && official -> ANTHROPIC
                    else -> OPENAI
                }
            }
    }
}

/**
 * 把用户填的 Base URL 规整成可直接拼路径的形态。
 *
 * 中转站地址是重灾区，实测常见的四种写法都得能认：
 *
 * | 用户填的 | 规整后 |
 * |---|---|
 * | `https://relay.example.com` | `https://relay.example.com/v1` |
 * | `https://relay.example.com/` | `https://relay.example.com/v1` |
 * | `https://relay.example.com/v1/chat/completions` | `https://relay.example.com/v1` |
 * | `192.168.1.9:8080` | `http://192.168.1.9:8080/v1` |
 *
 * 三条规则：
 * 1. **认协议，也允许没有协议** —— 没写协议的按主机形态补：本机/内网补 `http://`，
 *    公网域名补 `https://`。这是自建中转站最常踩的坑：填了 `http://` 却被
 *    "只支持 https" 的假设改写成 https，于是永远连不上。
 * 2. **剥掉误粘的接口路径**（`/chat/completions`、`/models`）——用户常常把
 *    文档里整条接口地址复制过来。
 * 3. **只有主机没有路径时补 `/v1`** —— OpenAI 兼容端点的事实标准前缀。
 *    已经带了 `/v1`、`/zen/go/v1` 这类路径的**一律不动**。
 */
fun normalizeBaseUrl(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return ""

    if (!s.startsWith("http://", ignoreCase = true) &&
        !s.startsWith("https://", ignoreCase = true)
    ) {
        s = (if (looksLikeLocalHost(s)) "http://" else "https://") + s
    }

    s = s.trimEnd('/')
    // 误粘的接口路径：剥掉之后自己再拼，避免出现 /v1/models/models
    for (suffix in listOf("/chat/completions", "/responses", "/completions", "/models")) {
        if (s.endsWith(suffix, ignoreCase = true)) {
            s = s.dropLast(suffix.length).trimEnd('/')
            break
        }
    }

    // 只剩 scheme + host[:port] 时补 /v1
    val afterScheme = s.substringAfter("://", "")
    if (!afterScheme.contains('/')) s = "$s/v1"
    return s
}

/** 无协议时判断该补 http 还是 https：本机、内网段、带端口的主机名都按 http 处理。 */
private fun looksLikeLocalHost(value: String): Boolean {
    val hostPort = value.substringBefore('/')
    val host = hostPort.substringBefore(':').lowercase()
    if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
    if (host.startsWith("10.") || host.startsWith("192.168.")) return true
    if (host.startsWith("172.")) {
        val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        if (second in 16..31) return true
    }
    // 带端口又不像域名（没有点）→ 内网服务
    return hostPort.contains(':') && !host.contains('.')
}

/** 拼装 `/chat/completions` 地址，兼容用户填不填 `/v1`、末尾带不带斜杠。 */
fun chatCompletionsUrl(baseUrl: String): String {
    val base = normalizeBaseUrl(baseUrl)
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}

/** 拼装 `/models` 地址。 */
fun modelsUrl(baseUrl: String): String {
    val base = normalizeBaseUrl(baseUrl)
    return if (base.endsWith("/models")) base else "$base/models"
}

/**
 * 拼装 Responses API 地址。
 *
 * 标准路径是 `/v1/responses`。如果用户把文档里的完整地址粘进来
 * （`…/v1/responses`），[normalizeBaseUrl] 会先剥掉再拼回来，结果仍然正确；
 * Codex 的自建网关常常是 `…/backend-api/codex`，也只需补一个 `/responses`。
 */
fun responsesUrl(baseUrl: String): String {
    val base = normalizeBaseUrl(baseUrl)
    return if (base.endsWith("/responses")) base else "$base/responses"
}
/**
 * Base URL 是否指向 **DeepSeek 官方域**。
 *
 * 官方扩展能力（Anthropic 扩展字段、服务端标题、`/v1/tools/tts` 语音）只在
 * `api.deepseek.com` 上提供；用户把 Base URL 改成自建网关后，这些字段/端点
 * 发出去只会 400 或 404。所有"要不要发官方专有东西"的判断都走这一个函数。
 */
fun isOfficialDeepSeekEndpoint(baseUrl: String): Boolean =
    baseUrl.trim().trimEnd('/').contains("api.deepseek.com")
