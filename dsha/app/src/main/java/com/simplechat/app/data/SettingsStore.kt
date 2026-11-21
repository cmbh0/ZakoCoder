package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.ProviderKind
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "simplechat_settings",
)

/** 语言档位（[AppLanguage] 的枚举名）。抽到文件级，供 [readStoredLanguageSync] 共用。 */
private val LANGUAGE_KEY = stringPreferencesKey("language_id")

/** 已下线的内置免费配置：固定 id 与供应商名，只用于读数据时清理。 */
private const val RETIRED_FREE_PROFILE_ID = "builtin-opencode-free"
private const val RETIRED_FREE_PROVIDER = "ZEN_FREE"

/** 已下线的服务商（2.2.6 起不再提供）：读数据时把它的配置降级为「自定义」。 */
private const val RETIRED_OPENCODE_PROVIDER = "OPENCODE_GO"

/**
 * 一套模型配置：**一个供应商端点 + 它的 Key + 它的模型**。
 *
 * 为什么要有这个类型 —— 早先整个 App 只有一套端点（`providerId` / `baseUrl` /
 * `apiKey` 三个平铺字段），于是「模型选择器按供应商分组」这件事根本无从谈起：
 * 列表里永远只有当前那一个端点拉到的模型，能分的只剩"按名字猜出来的家族"
 * （GPT / Claude / GLM / 其他）。

 * 真实用法是两三个端点同时活着：官方 DeepSeek 一把 Key、一个中转站一把 Key、
 * 本地 Ollama 不要 Key。用户想在同一张列表里看见它们，按端点分成几摞，
 * 点了哪个就用哪把 Key —— 而不是每次换模型都要进设置改一遍 Base URL。
 *
 * [apiKey] 在内存里是明文；落盘时逐条走 [SecretBox] 加密（见 [ProfileList]）。
 */
data class ModelProfile(
    /** 稳定标识。老数据迁移出来的那条固定叫 [LEGACY_ID]。 */
    val id: String,
    /** 用户起的名字，显示在选择器的分组标题上。空 = 用供应商名。 */
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String = "",
    /** 这个端点 `GET /models` 返回过的 id（或手动添加的）。 */
    val modelIds: List<String> = emptyList(),
    /** 这套配置上次用的模型。切回来时接着用它。 */
    val modelId: String = "",
    /**
     * 落盘时那一份密文。
     *
     * 存在的唯一理由是**防丢 Key**：写配置时要把整套重新编码，如果那一刻
     * Keystore 恰好不可用（`encrypt` 返回 null），没有这份旧密文就只剩
     * "写空"这一条路 —— 用户会发现自己所有配置的 Key 一次全没了。
     * 有了它，加密失败可以原样保留上一次的密文。
     */
    val keyCipher: String = "",
) {
    companion object {
        /**
         * 老数据合成出来的那条。**必须是固定字符串** —— 用随机 id 的话，
         * 每次读设置都会"发现"一条新配置，选择器里会多出一堆同名分组。
         */
        const val LEGACY_ID = "legacy"
    }
}

/**
 * [ModelProfile] 列表的持久化格式。
 *
 * 这里必须用 JSON：每条有 id / 名字 / 端点 / 密文 Key / 模型列表五段，
 * 换行分隔那套（[ModelIdList]）只能存一维字符串，硬塞会把 Key 和 id 混在一起。
 *
 * **Key 逐条加密**：`SecretBox.encrypt` 的结果是 base64，放进 JSON 只需要转义
 * 引号；解不开（换机、清数据）就当空 Key，不崩、也不会把密文当 Key 发出去。
 */
internal object ProfileList {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一个账号最多几套配置。够用，且拦住"误触新增"把 DataStore 撑大。 */
    const val MAX_PROFILES = 12

    fun encode(list: List<ModelProfile>): String = buildJsonArray {
        list.take(MAX_PROFILES).forEach { profile ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(profile.id))
                    put("name", JsonPrimitive(profile.name))
                    put("provider", JsonPrimitive(profile.providerId))
                    put("baseUrl", JsonPrimitive(profile.baseUrl))
                    /*
                     * 空 Key 就写空（用户主动清掉的情况）；有 Key 但加密失败时
                     * 退回上一份密文 —— 宁可密文旧一点，也不能把它抹成空。
                     */
                    val cipher = if (profile.apiKey.isBlank()) {
                        ""
                    } else {
                        SecretBox.encrypt(profile.apiKey) ?: profile.keyCipher
                    }
                    put("key", JsonPrimitive(cipher))
                    put("model", JsonPrimitive(profile.modelId))
                    put("models", JsonPrimitive(ModelIdList.encode(profile.modelIds)))
                },
            )
        }
    }.toString()

    fun decode(raw: String?): List<ModelProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(raw).jsonArray
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.str("id")
                if (id.isBlank()) return@mapNotNull null
                ModelProfile(
                    id = id,
                    name = obj.str("name"),
                    providerId = obj.str("provider").ifBlank { "DEEPSEEK" },
                    baseUrl = obj.str("baseUrl"),
                    apiKey = SecretBox.decrypt(obj.str("key")).orEmpty(),
                    keyCipher = obj.str("key"),
                    modelIds = ModelIdList.decode(obj.str("models")),
                    modelId = obj.str("model"),
                )
            }.take(MAX_PROFILES)
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.str(key: String): String =
        runCatching { (this[key] as? JsonPrimitive)?.content.orEmpty() }.getOrDefault("")

    /** 老的单端点设置合成出来的那一条。 */
    fun synthetic(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        modelIds: List<String>,
        keyCipher: String = "",
    ): ModelProfile = ModelProfile(
        id = ModelProfile.LEGACY_ID,
        name = "",
        providerId = providerId,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelIds = modelIds,
        modelId = modelId,
        keyCipher = keyCipher,
    )
}

/**
 * 「这个端点有哪些模型」的持久化格式（换行分隔）。
 *
 * 为什么不存 JSON：这里只有一串 id，没有结构；换行在模型 id 里不可能出现
 * （OpenAI 兼容端点用 `[a-zA-Z0-9._:/-]`），于是编解码就是两行 `map`，
 * 而 JSON 要引解析器、还要处理坏数据。读取侧对任何异常输入都只说"没有列表"。
 */
internal object ModelIdList {

    /**
     * 上限。正常端点几十个，这里只为拦住"网关把整张目录表倒出来"的极端情况 ——
     * 几千个 id 会撑大 DataStore，而选择器里也没人翻得到底。
     */
    const val MAX_IDS = 500

    fun encode(ids: List<String>): String = ids
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_IDS)
        .joinToString("\n")

    fun decode(raw: String?): List<String> = raw
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.take(MAX_IDS)
        ?.toList()
        .orEmpty()
}

/**
 * 同步读语言档位 —— 只为 `attachBaseContext`：那时一切异步入口都还没起来。
 * 值就一行，阻塞读在毫秒级；读不出来（IO 异常）按 [AppLanguage.SYSTEM] 处理，不拦启动。
 */
internal fun readStoredLanguageSync(context: Context): AppLanguage = runCatching {
    runBlocking {
        AppLanguage.fromId(context.settingsDataStore.data.first()[LANGUAGE_KEY])
    }
}.getOrDefault(AppLanguage.SYSTEM)

/** 朗读通道的 id。与 [StoredSettings.ttsChannelId] 一一对应。 */
object TtsChannel {
    /** 跟随可用性：有官方配置就用官方，否则系统引擎。 */
    const val AUTO = "AUTO"
    const val SYSTEM = "SYSTEM"
    const val DEEPSEEK = "DEEPSEEK"
    const val CUSTOM = "CUSTOM"
}

/** 持久化的全局配置。 */
data class StoredSettings(
    /** 首次安装落在 DeepSeek 预设上；模型留空，由用户从端点 `/models` 里挑。 */
    val providerId: String = ProviderKind.DEEPSEEK.name,
    val baseUrl: String = ProviderKind.DEEPSEEK.defaultBaseUrl,
    val modelId: String = "",
    val apiKey: String = "",
    val reasoningLevelId: String = "OFF",
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    /** 快速模式：给支持 `fast` 参数的端点（GPT 系）用，默认关。 */
    val fast: Boolean = false,
    /** 历史遗留的最大输出（新版用 [maxOutputTokens]，这里保留兼容旧数据）。 */
    val maxTokens: Int = 0,
    /** 上下文档位：AUTO / K128 / K256 / M1 / CUSTOM（见 `ModelResolver.ContextPreset`）。 */
    val contextPresetId: String = "AUTO",
    /** 自定义上下文窗口（token），仅 CUSTOM 档位生效。 */
    val contextWindowCustom: Int = 0,
    /** 最大输出 token；0 = 交给服务端默认。 */
    val maxOutputTokens: Int = 0,
    /**
     * 自动压缩：上下文用到七成、或自上次摘要后攒够 16 条用户消息时，
     * 先把旧对话整理成摘要。
     *
     * 两个阈值都是 Operit 的默认值（`ModelConfigDefaults`）。关掉之后不会
     * 自动触发，但输入栏那颗上下文环里仍可手动压 —— 开关管的是"要不要
     * 替我决定"，不是"能不能压"。
     */
    val autoCompact: Boolean = true,
    /**
     * 按消息条数触发压缩。
     *
     * token 比例管不住「一百条短对白」这种会话：每条几十字，累计占用不高，
     * 但轮次太多，模型对开头指令的注意力照样会被稀释。所以是双阈值，
     * 任一满足即压 —— 与 Operit 的做法一致。
     */
    val compactByCount: Boolean = true,
    /** 条数阈值：**自上次摘要之后**的用户消息数（Operit 默认 16）。 */
    val compactCount: Int = 16,
    /**
     * 自定义摘要规则：追加到压缩提示词末尾。
     *
     * Operit 的 `summaryCustomRules` 就是这个位置。默认四节模板已经覆盖
     * "发生了什么 / 关键事实 / 未解决"，这里只写领域约束，例如
     * 「变量名与命令必须原样保留」。
     */
    val compactRules: String = "",
    /**
     * 大窗口豁免：窗口 ≥ 512K 时**不按比例、也不按条数**自动压。
     *
     * 默认开。1M 上下文的模型本来就装得下，把它当 128K 的模型压，
     * 换来的是"上下文一直很干净"，赔上的是摘要质量 —— 四节摘要压不出
     * 70 万 token 的细节，早先定下的东西最先被磨掉。
     *
     * 只剩一条保底：占用真的越过可用预算时仍然会压（那种情况下
     * 不压就是让请求被服务端拒掉）。
     */
    val compactLargeWindowExempt: Boolean = true,
    /**
     * 是否从窗口里预留本次请求的输出 token。
     *
     * 默认开。窗口是"输入 + 输出"共享的：窗口 1M、输出预留 128K 时，
     * 输入真正能用的是 872K。不预留就等于默认输出不占位置 ——
     * 占用显示 70 万、请求实际要 82 万，已经超了。
     */
    val compactReserveOutput: Boolean = true,
    /**
     * 长会话**分段总结再合并**。
     *
     * 默认开。摘要请求本身也要过一遍模型的窗口：一次塞 70 万 token，
     * 128K 的模型直接被服务端拒掉，1M 的模型勉强收下但质量很差
     * （注意力被摊薄）。分段之后每段都是一段正常的对话，
     * 最后再把几段摘要合成一份。
     */
    val compactChunked: Boolean = true,
    /**
     * 上下文占用到多少比例时自动压缩（Operit 默认 0.70）。
     *
     * 不再按模型窗口自动推导：同一个会话换个模型就换一套阈值，用户看到的
     * 行为会变得不可预期。一个数，调了就是调了。
     */
    val compactRatio: Float = 0.70f,
    /**
     * 「AI 主动发消息」：每小时触发一次的基准概率（百分比）。
     *
     * 0 = 永不主动；100 = 只要空闲满 [proactiveIdleMinutes] 就一定开口。
     * 默认 25 —— 平均四小时一次，吵不到人，又确实会让 App"活着"。
     */
    val proactiveChance: Int = 25,
    /** 主动消息的最小空闲时长（分钟）。用户刚放下手机就开口是最烦的。 */
    val proactiveIdleMinutes: Int = 20,
    /** 首次引导是否看过。看过之后再也不弹 —— 引导只讲一次，不讲第二遍。 */
    val onboardingDone: Boolean = false,
    /**
     * 「操作屏幕」优先走哪条通道：`AUTO` / `ACCESSIBILITY` / `ROOT`。
     *
     * 为什么要有这个开关：两条通道互不隶属。无障碍要用户去系统设置里手勾，
     * 断了还得重勾；root 只要一次 `su` 授权，重启也还在。**已经 root 的机器
     * 没有理由逼人再去开无障碍** —— 让用户自己指定，比替他"智能选择"更省事。
     * `AUTO` 时先看无障碍（节点带指纹校验，误点风险小），没有再走 root。
     */
    val screenChannelId: String = "AUTO",

    /** 屏幕识别管线：`LAYOUT`（无障碍节点树）/ `VISION`（截图 + 坐标）。 */
    val screenPipelineId: String = "LAYOUT",

    /**
     * Agent 模式：给模型挂上工具（读写文件、跑命令、跑 Python、抓网页、MCP）。
     *
     * 默认关。开着的话每一轮请求都会带上工具定义（约 1k token），
     * 而绝大多数对话根本不碰工具 —— 让用户自己按需打开，比默认开着省得多。
     */
    val agentMode: Boolean = true,
    /** Agent 单轮最多几次工具调用往返。0 = 用内置默认（8）。 */
    val agentMaxRounds: Int = 0,
    /**
     * 工具审批预设（[com.simplechat.app.agent.ApprovalMode] 的 id）。
     *
     * 默认 `NORMAL`：写盘、执行命令、动屏幕都要先问一句。
     * 换成 `YOLO` 必须由用户在界面上显式确认 —— 这是一个"跳过所有确认"的开关，
     * 它不该有任何自动的路径能打开。
     */
    val approvalModeId: String = "NORMAL",
    /** 内嵌终端是否允许访问工作区外（默认关闭，只读工作区）。 */
    val terminalSandboxed: Boolean = true,

    /** 本地资料：名字。只存本机，不上传。 */
    val userName: String = "",
    /**
     * 用户人设：一段"我是谁"的自述，拼在系统提示词最前面。
     *
     * 与会话级 `systemPrompt` 的区别是**作用域** —— 会话提示词只对那一段对话
     * 生效（换个话题就该换），人设是"我一直是这样"，每段对话都该带着。
     * 空串 = 不注入，一个 token 都不多发。
     */
    val userPersona: String = "",
    /** 本地头像（content:// 或 file://）。空 = 用名字首字画一个圆。 */
    val userAvatar: String = "",
    /** 外观：SYSTEM / LIGHT / DARK。 */
    val themeModeId: String = "SYSTEM",
    /** 主题色：AccentColor.name，见 ui/theme/Theme.kt。 */
    val accentColorId: String = "BLUE",
    /** 字号倍率。 */
    val fontScale: Float = 1.0f,
    /** 字号是否跟随系统辅助功能设置。 */
    val followSystemFont: Boolean = true,
    /** 思考内容是否自动折叠（关闭则默认展开，需手动收起）。 */
    val autoCollapseThinking: Boolean = true,
    /** 新对话默认模式：CREATIVE / ROLEPLAY。 */
    val defaultModeId: String = "CREATIVE",
    /** 界面语言：AppLanguage 的枚举名，见 data/LocaleHelper.kt。 */
    val languageId: String = "SYSTEM",
    /**
     * 协议通道：`AUTO` / `OPENAI` / `ANTHROPIC` / `RESPONSES`。
     *
     * `AUTO` 按端点解析 —— 带 `/responses` 或 Codex 路径走 Responses，
     * DeepSeek 官方走扩展通道，其余走 OpenAI 兼容（见 `ApiStyle`）。
     */
    val apiStyleId: String = "AUTO",
    /**
     * 地区（用于「时间地点提醒」里的地点那一项），自由文本，留空则不填。
     *
     * 不做省/市二级联动：那套 13KB 的数据与三级选择器，收益远小于维护成本，
     * 而提醒里这一项本来就只是给模型一个「用户在哪儿」的粗略印象。
     */
    val locationRegion: String = "",
    /**
     * 朗读音色。
     *
     * - [ttsVoiceName]：系统 TTS 引擎里的 `Voice.name`（机器标识，用来精确选中）。
     *   空串 = 跟随系统默认（按界面语言挑一个本地音色）。
     * - [ttsVoiceLabel]：选中时给用户看的名字（地区 + 是否联网）。**单独存一份**，
     *   因为音色名是 `zh-cn-x-ccc-local` 这种机器 ID，界面不可能现算，而每次进设置页
     *   都去初始化一次 TTS 引擎只为显示一个名字也不划算。
     */
    val ttsVoiceName: String = "",
    val ttsVoiceLabel: String = "",

    /**
     * 朗读 / 识别的语言（`auto` / `zh` / `en` / `ja`）。
     *
     * `auto` = **跟界面语言**：早先只有这一种行为，中文界面挑中文音色、
     * 英文界面挑英文音色。但对"界面用英文、耳朵想听中文"或者需要练听力的场景
     * 就不对了 —— 所以拆成两个独立设置，TTS 与 STT 各管各的。
     */
    val ttsLanguage: String = "auto",
    val sttLanguage: String = "auto",

    /**
     * 当前端点 `/models` 拉回来的模型 id（[ModelIdList] 编解码）。
     *
     * 存下来而不是只放内存：拉取动作发生在「测试连接」或对话面板的刷新里，
     * 而用户随后进设置页选模型可能已经过了一次重启 —— 列表一丢，选择器就只剩
     * 内置能力表（自定义端点干脆是空的），于是"能拉到模型，却选不到模型"。
     * 端点（服务商 / Base URL）一换就清空，见 [SettingsStore.setProvider]。
     */
    val modelIds: List<String> = emptyList(),

    /**
     * 所有已配置的端点。
     *
     * 库里没有时会用平铺字段合成一条（id = [ModelProfile.LEGACY_ID]），
     * 见 [SettingsStore.flow]。
     */
    val profiles: List<ModelProfile> = emptyList(),

    /** 当前生效的那套配置。空 = 取 [profiles] 第一条。 */
    val activeProfileId: String = "",

    /**
     * 用户勾选"要出现在切换气泡里"的模型，元素格式 `KIND:modelId`。
     *
     * 为什么带服务商前缀：DeepSeek 与 OpenCode Go 有大量同名 id（`deepseek-v4-pro`
     * 两边都有），不带前缀的话在 A 服务商勾过的模型会莫名其妙出现在 B 的列表里。
     * 空 = 没选过，界面按"全部可选"处理 —— 升级上来的用户不该突然一个模型都看不到。
     */
    val pinnedModelIds: List<String> = emptyList(),

    /**
     * 回复结束后自动朗读。
     *
     * 默认**关**：手机上突然出声是最容易吓到人的一件事，而朗读本身是"我要听"
     * 才会用的功能。开着的人自己会去打开，默认开着的人只会去关掉。
     */
    val autoReadAloud: Boolean = false,

    /**
     * 朗读通道：`SYSTEM` / `DEEPSEEK` / `CUSTOM`。
     *
     * 系统引擎永远可用（设备装了 TTS 就行），DeepSeek 官方音质更好但只在官方端点，
     * 自定义走任意 OpenAI 兼容 `/audio/speech` —— 三条各有各的适用面，
     * 用哪个由用户指定，而不是按"哪个能用"悄悄替他换。
     */
    val ttsChannelId: String = TtsChannel.AUTO,

    /** 自定义朗读 API：完整端点（含 `/audio/speech`），也接受只填 base。 */
    val ttsApiUrl: String = "",

    /** 自定义朗读的 Key（密文，见 [SecretBox]）。 */
    val ttsApiKey: String = "",

    /** 模型名（`tts-1` / `gpt-4o-mini-tts` / `FunAudioLLM/CosyVoice2-0.5B`…）。 */
    val ttsApiModel: String = "tts-1",

    /** 音色名（`alloy` / `nova` / `中文女`…）。不同服务商取值完全不同，只能手填。 */
    val ttsApiVoice: String = "alloy",

    /** 返回音频格式：`mp3` / `wav` / `opus` / `pcm`。 */
    val ttsApiFormat: String = "mp3",

    /** 语速倍率（0.5–2.0），绝大多数实现都认。 */
    val ttsApiSpeed: Float = 1.0f,

    /** 自定义朗读的附加请求头（一行一个 `Key: Value`），给需要额外鉴权的网关用。 */
    val ttsApiHeaders: String = "",

    /**
     * 绘图 API：完整端点（`…/v1/images/generations`）。
     *
     * 空 = 没配置 —— 此时 `generate_image` 工具**不挂载**，模型看不到它，
     * 用户让它画图时它会直接说做不到，而不是挂一个必定失败的工具。
     */
    val imageApiUrl: String = "",

    /** 绘图 Key（密文）。 */
    val imageApiKey: String = "",

    /**
     * 绘图模型（`gpt-image-1` / `dall-e-3` / `flux-schnell`…）。
     *
     * **默认留空**，不预填任何模型名。各家网关的模型 id 千差万别，
     * 预填一个 `gpt-image-1` 的结果是：用户直接点保存，然后每次出图都 404，
     * 而界面上看起来"我已经配好了"。空着就是"还没配"，语义才对。
     */
    val imageApiModel: String = "",

    /**
     * 出图尺寸；**空 = 自动**（请求里不带 `size` 字段，由服务端按模型自己决定）。
     *
     * 这也是默认值：不同模型支持的尺寸集合完全不一样（`dall-e-3` 只认三档、
     * `gpt-image-1` 认 `auto`、网关上的 flux 可能认任意宽高）。客户端硬塞一个
     * `1024x1024` 只会让一部分服务直接 400。
     */
    val imageApiSize: String = "",

    /** 从 `/models` 拉到的绘图模型列表（给选择器用，不是事实来源）。 */
    val imageApiModels: List<String> = emptyList(),

) {
    /**
     * 当前生效的配置对象。
     *
     * 放在类体里而不是构造参数里 —— data class 的主构造只接受属性声明，
     * 计算属性写进去直接是语法错误（`get()` 会被当成参数名）。
     */
    val activeProfile: ModelProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    /** 绘图是否已配置：端点与模型都在才算。 */
    val hasImageApi: Boolean get() = imageApiUrl.isNotBlank() && imageApiModel.isNotBlank()

    /** 自定义朗读是否配置完整。 */
    val hasCustomTts: Boolean get() = ttsApiUrl.isNotBlank() && ttsApiModel.isNotBlank()

    /** 当前实际走哪条朗读通道（把"没配好的 CUSTOM"退回系统，避免点了没声）。 */
    val resolvedTtsChannel: String
        get() = when (ttsChannelId) {
            TtsChannel.CUSTOM -> if (hasCustomTts) TtsChannel.CUSTOM else TtsChannel.SYSTEM
            TtsChannel.DEEPSEEK -> TtsChannel.DEEPSEEK
            else -> TtsChannel.SYSTEM
        }

    /**
     * 这一套配置**实际走的是不是扩展通道**（联网搜索等能力的唯一前提）。
     *
     * 判据与请求时用的是同一个 [`ApiStyle.resolve`]：配置档位 + 端点共同决定。
     * 用在设置页与输入面板上"要不要显示联网搜索" —— 只有扩展通道有
     * `deepseek_web_search` 这个工具，OpenAI 兼容通道没有对应入口，
     * 那就别把一个打开了也没用的开关摆在用户面前。
     */
    val resolvedApiStyle: ApiStyle
        get() = ApiStyle.resolve(
            kind = runCatching { ProviderKind.valueOf(providerId) }.getOrDefault(ProviderKind.DEEPSEEK),
            configuredId = apiStyleId,
            baseUrl = baseUrl,
        )

    /** 当前通道是否支持联网搜索。 */
    val supportsWebSearch: Boolean get() = resolvedApiStyle == ApiStyle.ANTHROPIC
}

/**
 * 配置存储（DataStore Preferences）。
 *
 * **API Key 不落明文**：加解密走 [SecretBox]（Android Keystore 里的
 * 不可导出 AES-256 密钥 + AES-GCM），DataStore 里只存密文。
 * 密钥随应用卸载 / 清除数据一起消失，届时解不开只会当"未设置"处理，
 * 不会崩，也不会把密文当 Key 发出去。
 */
class SettingsStore(private val context: Context) {

    val flow: Flow<StoredSettings> = context.settingsDataStore.data.map { prefs ->
        /*
         * 多套配置的解析顺序很关键：**先定 profiles，再由 active 覆盖平铺字段。**
         *
         * 请求组装（ChatViewModel 里那六处）读的一直是 `stored.baseUrl` /
         * `stored.apiKey`，一行都没改 —— 多端点是在这里"投影"成单端点的。
         * 好处是切换配置不需要通知任何调用方：`flow` 一变，下一次请求自然用新的。
         *
         * 库里没有 profiles（老用户第一次升级）时，用平铺的四个字段合成一条，
         * 固定 id = LEGACY_ID。**只合并不写库**：等到用户真的动了配置才落盘，
         * 免得一次升级就把所有老数据重写成新格式。
         */
        val profiles = readProfiles(prefs)
        val active = profiles.firstOrNull { it.id == prefs[Keys.ACTIVE_PROFILE] }
            ?: profiles.firstOrNull()

        StoredSettings(
            providerId = active?.providerId ?: StoredSettings().providerId,
            baseUrl = active?.baseUrl ?: StoredSettings().baseUrl,
            modelId = active?.modelId ?: StoredSettings().modelId,
            apiKey = active?.apiKey ?: readApiKey(prefs),
            reasoningLevelId = prefs[Keys.REASONING] ?: StoredSettings().reasoningLevelId,
            temperature = prefs[Keys.TEMPERATURE] ?: StoredSettings().temperature,
            topP = prefs[Keys.TOP_P] ?: StoredSettings().topP,
            fast = prefs[Keys.FAST] ?: StoredSettings().fast,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: StoredSettings().maxTokens,
            contextPresetId = prefs[Keys.CONTEXT_PRESET] ?: StoredSettings().contextPresetId,
            contextWindowCustom = prefs[Keys.CONTEXT_CUSTOM] ?: StoredSettings().contextWindowCustom,
            maxOutputTokens = prefs[Keys.MAX_OUTPUT] ?: StoredSettings().maxOutputTokens,
            userPersona = prefs[Keys.USER_PERSONA] ?: StoredSettings().userPersona,
            autoCompact = prefs[Keys.AUTO_COMPACT] ?: StoredSettings().autoCompact,
            compactRatio = prefs[Keys.COMPACT_RATIO] ?: StoredSettings().compactRatio,
            compactByCount = prefs[Keys.COMPACT_BY_COUNT] ?: StoredSettings().compactByCount,
            compactCount = prefs[Keys.COMPACT_COUNT] ?: StoredSettings().compactCount,
            compactRules = prefs[Keys.COMPACT_RULES] ?: StoredSettings().compactRules,
            compactLargeWindowExempt = prefs[Keys.COMPACT_LARGE_EXEMPT]
                ?: StoredSettings().compactLargeWindowExempt,
            compactReserveOutput = prefs[Keys.COMPACT_RESERVE_OUTPUT]
                ?: StoredSettings().compactReserveOutput,
            compactChunked = prefs[Keys.COMPACT_CHUNKED] ?: StoredSettings().compactChunked,
            proactiveChance = prefs[Keys.PROACTIVE_CHANCE] ?: StoredSettings().proactiveChance,
            proactiveIdleMinutes = prefs[Keys.PROACTIVE_IDLE] ?: StoredSettings().proactiveIdleMinutes,
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: StoredSettings().onboardingDone,
            screenChannelId = prefs[Keys.SCREEN_CHANNEL] ?: StoredSettings().screenChannelId,
            screenPipelineId = prefs[Keys.SCREEN_PIPELINE] ?: StoredSettings().screenPipelineId,
            agentMode = prefs[Keys.AGENT_MODE] ?: StoredSettings().agentMode,
            agentMaxRounds = prefs[Keys.AGENT_ROUNDS] ?: StoredSettings().agentMaxRounds,
            approvalModeId = prefs[Keys.APPROVAL_MODE] ?: StoredSettings().approvalModeId,
            terminalSandboxed = prefs[Keys.TERMINAL_SANDBOX] ?: StoredSettings().terminalSandboxed,
            userName = prefs[Keys.PROFILE_NAME] ?: StoredSettings().userName,
            userAvatar = prefs[Keys.PROFILE_AVATAR] ?: StoredSettings().userAvatar,
            themeModeId = prefs[Keys.THEME_MODE] ?: StoredSettings().themeModeId,
            accentColorId = prefs[Keys.ACCENT_COLOR] ?: StoredSettings().accentColorId,
            fontScale = prefs[Keys.FONT_SCALE] ?: StoredSettings().fontScale,
            followSystemFont = prefs[Keys.FOLLOW_SYSTEM_FONT]
                ?: StoredSettings().followSystemFont,
            autoCollapseThinking = prefs[Keys.AUTO_COLLAPSE_THINKING]
                ?: StoredSettings().autoCollapseThinking,
            defaultModeId = prefs[Keys.DEFAULT_MODE] ?: StoredSettings().defaultModeId,
            languageId = prefs[LANGUAGE_KEY] ?: StoredSettings().languageId,
            apiStyleId = prefs[Keys.API_STYLE] ?: StoredSettings().apiStyleId,
            locationRegion = prefs[Keys.LOCATION] ?: StoredSettings().locationRegion,
            ttsVoiceName = prefs[Keys.TTS_VOICE] ?: StoredSettings().ttsVoiceName,
            ttsVoiceLabel = prefs[Keys.TTS_VOICE_LABEL] ?: StoredSettings().ttsVoiceLabel,
            ttsLanguage = prefs[Keys.TTS_LANGUAGE] ?: StoredSettings().ttsLanguage,
            sttLanguage = prefs[Keys.STT_LANGUAGE] ?: StoredSettings().sttLanguage,
            modelIds = active?.modelIds ?: ModelIdList.decode(prefs[Keys.MODEL_IDS]),
            profiles = profiles,
            activeProfileId = active?.id.orEmpty(),
            pinnedModelIds = ModelIdList.decode(prefs[Keys.PINNED_MODELS]),
            autoReadAloud = prefs[Keys.AUTO_READ] ?: StoredSettings().autoReadAloud,
            ttsChannelId = prefs[Keys.TTS_CHANNEL] ?: StoredSettings().ttsChannelId,
            ttsApiUrl = prefs[Keys.TTS_API_URL] ?: StoredSettings().ttsApiUrl,
            ttsApiKey = readSecret(prefs, Keys.TTS_API_KEY_ENC),
            ttsApiModel = prefs[Keys.TTS_API_MODEL] ?: StoredSettings().ttsApiModel,
            ttsApiVoice = prefs[Keys.TTS_API_VOICE] ?: StoredSettings().ttsApiVoice,
            ttsApiFormat = prefs[Keys.TTS_API_FORMAT] ?: StoredSettings().ttsApiFormat,
            ttsApiSpeed = prefs[Keys.TTS_API_SPEED] ?: StoredSettings().ttsApiSpeed,
            ttsApiHeaders = prefs[Keys.TTS_API_HEADERS] ?: StoredSettings().ttsApiHeaders,
            imageApiUrl = prefs[Keys.IMAGE_API_URL] ?: StoredSettings().imageApiUrl,
            imageApiKey = readSecret(prefs, Keys.IMAGE_API_KEY_ENC),
            imageApiModel = prefs[Keys.IMAGE_API_MODEL] ?: StoredSettings().imageApiModel,
            imageApiSize = prefs[Keys.IMAGE_API_SIZE] ?: StoredSettings().imageApiSize,
            imageApiModels = prefs[Keys.IMAGE_API_MODELS]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),

        )
    }

    /**
     * 读出所有配置（含"老数据合成"这条路径）。
     *
     * 读与写必须用同一套合成规则，否则第一次写入会把合成出来的那条丢掉，
     * 用户升级后会发现"我的 Key 没了"。
     */
    private fun readProfiles(prefs: Preferences): List<ModelProfile> {
        // 先清洗已保存的：里面可能还留着已下线功能的配置
        val saved = ProfileList.decode(prefs[Keys.PROFILES]).mapNotNull(::retireRemovedProvider)
        val base = if (saved.isNotEmpty()) {
            saved
        } else if (hasLegacyEndpoint(prefs)) {
            /*
             * 老版本升级：只有平铺字段时合成一条，**排在第一位**。
             * 用户的 Key 和端点是他自己的选择，升级不该悄悄换掉当前供应商。
             */
            listOf(
                ProfileList.synthetic(
                    providerId = prefs[Keys.PROVIDER] ?: ProviderKind.DEEPSEEK.name,
                    baseUrl = prefs[Keys.BASE_URL] ?: ProviderKind.DEEPSEEK.defaultBaseUrl,
                    apiKey = readApiKey(prefs),
                    modelId = prefs[Keys.MODEL].orEmpty(),
                    modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),
                    keyCipher = prefs[Keys.API_KEY_ENC].orEmpty(),
                ),
            ).mapNotNull(::retireRemovedProvider)
        } else {
            emptyList()
        }
        return base
    }

    /**
     * 清理已下线功能留下的数据。
     *
     * 2.2.2 及更早版本随包带了一条「内置 OpenCode 免费池」配置（固定 id），
     * 它靠已经删掉的身份头与 preamble 注入才可用；留着只会变成一条必然 401
     * 的配置，所以读取时直接丢弃。其它地方若仍把供应商写成 `ZEN_FREE`，
     * 改成 `CUSTOM` 并保留端点 —— 用户填过 Key 的地址还能继续用，
     * 而枚举里已经没有这个名字，原样传下去会被 `valueOf` 回落成 DeepSeek。
     *
     * 2.2.6 起整个 OpenCode Go 渠道下线：它的匿名额度已经用不了，留着只是
     * 一个点了必然失败的入口。旧配置同样降级成 `CUSTOM` —— 端点与模型都保留，
     * 用户若自己有可用的中转地址，填一把 Key 就能接着用。
     */
    private fun retireRemovedProvider(profile: ModelProfile): ModelProfile? = when {
        profile.id == RETIRED_FREE_PROFILE_ID -> null
        profile.providerId == RETIRED_FREE_PROVIDER ||
            profile.providerId == RETIRED_OPENCODE_PROVIDER ->
            profile.copy(providerId = ProviderKind.CUSTOM.name)
        else -> profile
    }

    /**
     * 是否存在"老版本平铺配置"。
     *
     * 只看 endpoint 相关键，不看 `onboarding_done` 之类的普通设置 ——
     * 否则一个只改过主题的用户升级后，会凭空多出一条 DeepSeek 配置。
     */
    private fun hasLegacyEndpoint(prefs: Preferences): Boolean =
        prefs[Keys.PROVIDER] != null ||
            prefs[Keys.BASE_URL] != null ||
            prefs[Keys.MODEL] != null ||
            prefs[Keys.MODEL_IDS] != null ||
            prefs[Keys.API_KEY_ENC] != null

    /**
     * 把一套配置"投影"到平铺字段上。
     *
     * 平铺字段仍然要写：老代码路径（以及回滚到旧版本的用户）只认它们。
     * 两套同时维护的成本就是这几行，换来的是**不需要改任何请求组装代码**。
     */
    private fun writeProfile(prefs: androidx.datastore.preferences.core.MutablePreferences, profile: ModelProfile) {
        prefs[Keys.PROVIDER] = profile.providerId
        prefs[Keys.BASE_URL] = profile.baseUrl
        prefs[Keys.MODEL] = profile.modelId
        if (profile.apiKey.isBlank()) {
            prefs.remove(Keys.API_KEY_ENC)
        } else {
            /*
             * 加密失败时**什么都别做** —— 保留上一次的密文。
             * 原来这里用 `?.let`，失败等于"静默删掉 Key"，
             * 而用户看到的只是"我的 Key 不见了"，没有任何线索。
             */
            SecretBox.encrypt(profile.apiKey)?.let { prefs[Keys.API_KEY_ENC] = it }
        }
        val ids = ModelIdList.encode(profile.modelIds)
        if (ids.isEmpty()) prefs.remove(Keys.MODEL_IDS) else prefs[Keys.MODEL_IDS] = ids
        // 明文槽位彻底弃用
        prefs.remove(Keys.API_KEY)
    }

    /** 切换到某一套配置。 */
    suspend fun setActiveProfile(profileId: String) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs)
            val target = list.firstOrNull { it.id == profileId } ?: return@edit
            prefs[Keys.PROFILES] = ProfileList.encode(list)
            prefs[Keys.ACTIVE_PROFILE] = target.id
            writeProfile(prefs, target)
        }
    }

    /** 新增或覆盖一套配置。 */
    suspend fun upsertProfile(profile: ModelProfile) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == profile.id }
            if (index >= 0) list[index] = profile else list.add(profile)
            prefs[Keys.PROFILES] = ProfileList.encode(list)
            if (prefs[Keys.ACTIVE_PROFILE].isNullOrBlank()) {
                prefs[Keys.ACTIVE_PROFILE] = list.first().id
            }
            if (prefs[Keys.ACTIVE_PROFILE] == profile.id) writeProfile(prefs, profile)
        }
    }

    /**
     * 删掉一套配置。
     *
     * **不允许删到零**：多端点是为了方便，不是让用户把唯一那把 Key 删掉后
     * 界面变成"未配置"却不知道为什么。要清空就用「清除全部数据」。
     */
    suspend fun removeProfile(profileId: String) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs)
            if (list.size <= 1) return@edit
            val left = list.filterNot { it.id == profileId }
            if (left.isEmpty()) return@edit
            prefs[Keys.PROFILES] = ProfileList.encode(left)
            if (prefs[Keys.ACTIVE_PROFILE] == profileId || prefs[Keys.ACTIVE_PROFILE].isNullOrBlank()) {
                prefs[Keys.ACTIVE_PROFILE] = left.first().id
                writeProfile(prefs, left.first())
            }
        }
    }

    /**
     * 切换模型 —— **同时切配置**。
     *
     * 模型选择器现在是跨配置的：点了另一套配置下的模型，意味着"用那个端点、
     * 那个模型"，所以 `activeProfileId` 必须跟着走。不跟着走的话，请求会拿
     * A 端点的 Key 去请求 B 端点的模型 id。
     */
    suspend fun setActiveModel(profileId: String, modelId: String) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == profileId }
            if (index < 0) return@edit
            list[index] = list[index].copy(modelId = modelId)
            prefs[Keys.PROFILES] = ProfileList.encode(list)
            prefs[Keys.ACTIVE_PROFILE] = profileId
            writeProfile(prefs, list[index])
        }
    }

    /** 记下某套配置拉到的模型列表。 */
    suspend fun setProfileModels(profileId: String, ids: List<String>) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == profileId }
            if (index < 0) return@edit
            list[index] = list[index].copy(modelIds = ids)
            prefs[Keys.PROFILES] = ProfileList.encode(list)
            if (prefs[Keys.ACTIVE_PROFILE] == profileId) writeProfile(prefs, list[index])
        }
    }

    suspend fun setProvider(providerId: String, baseUrl: String, modelId: String) {
        context.settingsDataStore.edit { prefs ->
            val trimmed = baseUrl.trim()
            // 换了端点，旧端点拉到的模型列表就不再成立
            val endpointChanged = prefs[Keys.PROVIDER] != providerId ||
                prefs[Keys.BASE_URL] != trimmed
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == (prefs[Keys.ACTIVE_PROFILE] ?: list.first().id) }
            if (index >= 0) {
                val old = list[index]
                list[index] = old.copy(
                    providerId = providerId,
                    baseUrl = trimmed,
                    modelId = modelId,
                    modelIds = if (endpointChanged) emptyList() else old.modelIds,
                )
                prefs[Keys.PROFILES] = ProfileList.encode(list)
                prefs[Keys.ACTIVE_PROFILE] = list[index].id
                writeProfile(prefs, list[index])
            } else {
                if (endpointChanged) prefs.remove(Keys.MODEL_IDS)
                prefs[Keys.PROVIDER] = providerId
                prefs[Keys.BASE_URL] = trimmed
                prefs[Keys.MODEL] = modelId
            }
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        context.settingsDataStore.edit { prefs ->
            // 只改大小写 / 前后空格不算换端点；真的改了才丢掉旧列表
            val changed = prefs[Keys.BASE_URL].orEmpty().trim() != trimmed
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == (prefs[Keys.ACTIVE_PROFILE] ?: list.first().id) }
            if (index >= 0) {
                val old = list[index]
                list[index] = old.copy(
                    baseUrl = trimmed,
                    modelIds = if (changed) emptyList() else old.modelIds,
                )
                prefs[Keys.PROFILES] = ProfileList.encode(list)
                prefs[Keys.ACTIVE_PROFILE] = list[index].id
                writeProfile(prefs, list[index])
            } else {
                if (changed) prefs.remove(Keys.MODEL_IDS)
                prefs[Keys.BASE_URL] = trimmed
            }
        }
    }

    suspend fun setModel(modelId: String) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == (prefs[Keys.ACTIVE_PROFILE] ?: list.first().id) }
            if (index >= 0) {
                list[index] = list[index].copy(modelId = modelId)
                prefs[Keys.PROFILES] = ProfileList.encode(list)
                writeProfile(prefs, list[index])
            } else {
                prefs[Keys.MODEL] = modelId
            }
        }
    }

    /** 记下这个端点 `GET /models` 返回的 id（测试连接 / 对话面板刷新时调用）。 */
    suspend fun setModelIds(ids: List<String>) {
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == (prefs[Keys.ACTIVE_PROFILE] ?: list.first().id) }
            if (index >= 0) {
                list[index] = list[index].copy(modelIds = ids)
                prefs[Keys.PROFILES] = ProfileList.encode(list)
                writeProfile(prefs, list[index])
            } else {
                val encoded = ModelIdList.encode(ids)
                if (encoded.isEmpty()) {
                    prefs.remove(Keys.MODEL_IDS)
                } else {
                    prefs[Keys.MODEL_IDS] = encoded
                }
            }
        }
    }

    /**
     * 记住"要出现在气泡里的模型"。
     *
     * 直接覆盖整份列表：勾选动作本来就是"当前全集的子集"，增量合并会让
     * "取消勾选"变成一件做不成的事。
     */
    suspend fun setPinnedModels(kind: String, ids: List<String>) {
        context.settingsDataStore.edit { prefs ->
            val prefix = kind.trim().uppercase() + ":"
            val keep = ModelIdList.decode(prefs[Keys.PINNED_MODELS]).filterNot { it.startsWith(prefix) }
            val fresh = ids.map { prefix + it }.filter { it.length > prefix.length }
            val merged = (keep + fresh).distinct()
            if (merged.isEmpty()) {
                prefs.remove(Keys.PINNED_MODELS)
            } else {
                prefs[Keys.PINNED_MODELS] = ModelIdList.encode(merged)
            }
        }
    }

    suspend fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        context.settingsDataStore.edit { prefs ->
            val list = readProfiles(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == (prefs[Keys.ACTIVE_PROFILE] ?: list.first().id) }
            if (index >= 0) {
                list[index] = list[index].copy(apiKey = trimmed)
                prefs[Keys.PROFILES] = ProfileList.encode(list)
                writeProfile(prefs, list[index])
            } else {
                if (trimmed.isEmpty()) {
                    prefs.remove(Keys.API_KEY_ENC)
                } else {
                    SecretBox.encrypt(trimmed)?.let { prefs[Keys.API_KEY_ENC] = it }
                }
            }
            // 明文槽位彻底弃用，写入时顺手清掉历史遗留
            prefs.remove(Keys.API_KEY)
        }
    }

    /**
     * 把 v1.7 之前留下的**明文** Key 就地加密（一次性）。
     *
     * 启动时调用一次即可。老版本直接把 Key 写在 `api_key` 里，
     * 升级后那份明文必须尽快消失，否则"加密存储"只是新数据的待遇。
     */
    suspend fun migrateLegacyApiKey() {
        context.settingsDataStore.edit { prefs ->
            val legacy = prefs[Keys.API_KEY]
            if (!legacy.isNullOrBlank() && prefs[Keys.API_KEY_ENC] == null) {
                SecretBox.encrypt(legacy)?.let { prefs[Keys.API_KEY_ENC] = it }
            }
            prefs.remove(Keys.API_KEY)
        }
    }

    suspend fun setReasoningLevel(levelId: String) {
        context.settingsDataStore.edit { it[Keys.REASONING] = levelId }
    }

    suspend fun setTemperature(value: Float) {
        context.settingsDataStore.edit { it[Keys.TEMPERATURE] = value }
    }

    suspend fun setTopP(value: Float) {
        context.settingsDataStore.edit { it[Keys.TOP_P] = value }
    }

    /** 快速模式开关。只在这一个入口写，请求层直接读 `StoredSettings.fast`。 */
    suspend fun setFast(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.FAST] = value }
    }

    suspend fun setMaxTokens(value: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_TOKENS] = value }
    }

    /** 上下文档位；选 CUSTOM 时同时写入自定义值。 */
    suspend fun setContextPreset(presetId: String, custom: Int = 0) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CONTEXT_PRESET] = presetId
            if (custom > 0) prefs[Keys.CONTEXT_CUSTOM] = custom
        }
    }

    suspend fun setMaxOutputTokens(value: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_OUTPUT] = value.coerceAtLeast(0) }
    }

    suspend fun setAgentMode(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.AGENT_MODE] = value }
    }

    suspend fun setApprovalMode(modeId: String) {
        context.settingsDataStore.edit { it[Keys.APPROVAL_MODE] = modeId }
    }

    suspend fun setAgentMaxRounds(value: Int) {
        context.settingsDataStore.edit { it[Keys.AGENT_ROUNDS] = value.coerceIn(0, 32) }
    }

    suspend fun setTerminalSandboxed(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TERMINAL_SANDBOX] = value }
    }

    suspend fun setProfile(name: String, avatar: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PROFILE_NAME] = name.trim()
            prefs[Keys.PROFILE_AVATAR] = avatar
        }
    }

    /** 只换头像。选完图立刻落库，不等用户再点「保存」。 */
    suspend fun setAvatar(avatar: String) {
        context.settingsDataStore.edit { it[Keys.PROFILE_AVATAR] = avatar }
    }

    suspend fun setOnboardingDone() {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    /** 屏幕操作优先通道：AUTO / ACCESSIBILITY / ROOT。 */
    suspend fun setScreenChannel(id: String) {
        context.settingsDataStore.edit { it[Keys.SCREEN_CHANNEL] = id }
    }

    suspend fun setScreenPipeline(id: String) {
        com.simplechat.app.agent.ScreenAutomation.setPipeline(id)
        context.settingsDataStore.edit { it[Keys.SCREEN_PIPELINE] = id }
    }

    suspend fun setAutoCompact(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_COMPACT] = enabled }
    }

    /** 压缩触发比例。夹在 0.10~0.95：再低会一直压，再高等于不压。 */
    suspend fun setCompactRatio(ratio: Float) {
        context.settingsDataStore.edit { it[Keys.COMPACT_RATIO] = ratio.coerceIn(0.10f, 0.95f) }
    }

    /** 按条数触发压缩。 */
    suspend fun setCompactByCount(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COMPACT_BY_COUNT] = enabled }
    }

    suspend fun setCompactCount(count: Int) {
        // Operit 的默认是 16。范围放宽到 4~200：滑杆只给常用区间，
        // 特殊会话（几十条短对白）要能直接填一个更大的数。
        context.settingsDataStore.edit { it[Keys.COMPACT_COUNT] = count.coerceIn(4, 200) }
    }

    /** 自定义摘要规则（追加到压缩提示词）。 */
    suspend fun setCompactRules(text: String) {
        context.settingsDataStore.edit { it[Keys.COMPACT_RULES] = text.trim().take(2_000) }
    }

    /** 大窗口豁免：窗口 ≥ 512K 时不按比例/条数自动压。 */
    suspend fun setCompactLargeWindowExempt(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COMPACT_LARGE_EXEMPT] = enabled }
    }

    /** 是否从窗口里预留输出 token。 */
    suspend fun setCompactReserveOutput(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COMPACT_RESERVE_OUTPUT] = enabled }
    }

    /** 长会话是否分段总结再合并。 */
    suspend fun setCompactChunked(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COMPACT_CHUNKED] = enabled }
    }

    suspend fun setProactiveChance(percent: Int) {
        context.settingsDataStore.edit { it[Keys.PROACTIVE_CHANCE] = percent.coerceIn(0, 100) }
    }

    suspend fun setProactiveIdle(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.PROACTIVE_IDLE] = minutes.coerceIn(5, 240) }
    }

    suspend fun setUserPersona(text: String) {
        context.settingsDataStore.edit { it[Keys.USER_PERSONA] = text.trim() }
    }

    suspend fun setThemeMode(modeId: String) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = modeId }
    }

    suspend fun setAccentColor(colorId: String) {
        context.settingsDataStore.edit { it[Keys.ACCENT_COLOR] = colorId }
    }

    suspend fun setFontScale(scale: Float) {
        context.settingsDataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setFollowSystemFont(follow: Boolean) {
        context.settingsDataStore.edit { it[Keys.FOLLOW_SYSTEM_FONT] = follow }
    }

    suspend fun setAutoCollapseThinking(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_COLLAPSE_THINKING] = enabled }
    }

    suspend fun setDefaultMode(modeId: String) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_MODE] = modeId }
    }

    suspend fun setLanguage(languageId: String) {
        context.settingsDataStore.edit { it[LANGUAGE_KEY] = languageId }
    }

    suspend fun setApiStyle(styleId: String) {
        context.settingsDataStore.edit { it[Keys.API_STYLE] = styleId }
    }

    suspend fun setLocationRegion(region: String) {
        // 存**洗过**的值：逗号会把时间地点提醒的五个字段挤成六段（见 normalizeRegion）
        context.settingsDataStore.edit { it[Keys.LOCATION] = normalizeRegion(region) }
    }

    suspend fun setTtsLanguage(code: String) {
        SpeechLang.tts = code
        context.settingsDataStore.edit { it[Keys.TTS_LANGUAGE] = code }
    }

    suspend fun setSttLanguage(code: String) {
        SpeechLang.stt = code
        context.settingsDataStore.edit { it[Keys.STT_LANGUAGE] = code }
    }

    /** 朗读音色：名字用来精确选中，标签用来在设置页显示（见 [StoredSettings.ttsVoiceName]）。 */
    suspend fun setTtsVoice(name: String, label: String) {
        context.settingsDataStore.edit { prefs ->
            if (name.isBlank()) {
                prefs.remove(Keys.TTS_VOICE)
                prefs.remove(Keys.TTS_VOICE_LABEL)
            } else {
                prefs[Keys.TTS_VOICE] = name
                prefs[Keys.TTS_VOICE_LABEL] = label
            }
        }
    }

    /** 回复结束后自动朗读。 */
    suspend fun setAutoReadAloud(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_READ] = value }
    }

    /** 朗读通道（见 [TtsChannel]）。 */
    suspend fun setTtsChannel(id: String) {
        context.settingsDataStore.edit { it[Keys.TTS_CHANNEL] = id }
    }

    /**
     * 自定义朗读的一整组参数。
     *
     * 一起写而不是拆成七个方法：它们只在同一个表单里被提交，
     * 分开写会让"改了三项、其中一项没保存"这种事变成可能。
     */
    suspend fun setCustomTts(
        url: String,
        apiKey: String,
        model: String,
        voice: String,
        format: String,
        speed: Float,
        headers: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TTS_API_URL] = url.trim()
            prefs[Keys.TTS_API_MODEL] = model.trim()
            prefs[Keys.TTS_API_VOICE] = voice.trim()
            prefs[Keys.TTS_API_FORMAT] = format.trim()
            prefs[Keys.TTS_API_SPEED] = speed.coerceIn(0.5f, 2f)
            prefs[Keys.TTS_API_HEADERS] = headers.trim()
            writeSecret(prefs, Keys.TTS_API_KEY_ENC, apiKey)
        }
    }

    /** 只改自定义朗读的 Key（设置页的 Key 单独一个编辑框）。 */
    suspend fun setTtsApiKey(apiKey: String) {
        context.settingsDataStore.edit { writeSecret(it, Keys.TTS_API_KEY_ENC, apiKey) }
    }

    /** 绘图 API 的一整组参数（同 [setCustomTts]，一次提交）。 */
    suspend fun setImageApi(url: String, apiKey: String, model: String, size: String) {
        context.settingsDataStore.edit { prefs ->
            // 换了端点，旧端点拉到的模型列表就不再成立
            if (prefs[Keys.IMAGE_API_URL]?.trim() != url.trim()) prefs.remove(Keys.IMAGE_API_MODELS)
            prefs[Keys.IMAGE_API_URL] = url.trim()
            prefs[Keys.IMAGE_API_MODEL] = model.trim()
            prefs[Keys.IMAGE_API_SIZE] = size.trim()
            writeSecret(prefs, Keys.IMAGE_API_KEY_ENC, apiKey)
        }
    }

    /** 拉取到的绘图模型列表（换端点会自动清空，见 [setImageApi]）。 */
    suspend fun setImageApiModels(models: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.IMAGE_API_MODELS] = models.joinToString("\n")
        }
    }



    /** 清空全部配置（「删除所有数据」时一并调用）。 */
    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    /** 读 API Key：优先密文，其次兼容老版本的明文。 */
    private fun readApiKey(prefs: Preferences): String {
        prefs[Keys.API_KEY_ENC]?.takeIf { it.isNotBlank() }?.let { return SecretBox.decrypt(it) }
        return prefs[Keys.API_KEY].orEmpty()
    }

    /** 读任意一条密文；解不开（密钥随重装丢失）就当没设置，不崩。 */
    private fun readSecret(prefs: Preferences, key: Preferences.Key<String>): String =
        prefs[key]?.takeIf { it.isNotBlank() }?.let { SecretBox.decrypt(it) }.orEmpty()

    /** 写密文；空串就把槽位删掉，不存一条空密文。 */
    private fun writeSecret(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String,
    ) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.remove(key)
        } else {
            SecretBox.encrypt(trimmed)?.let { prefs[key] = it }
        }
    }

    private object Keys {
        val PROVIDER = stringPreferencesKey("provider_id")

        /** 多套配置的 JSON（见 [ProfileList]）。空 = 还没迁移到多端点。 */
        val PROFILES = stringPreferencesKey("model_profiles")

        /** 当前生效的配置 id。 */
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model_id")

        /** API Key 的**密文**（base64）。 */
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")

        /** 历史遗留的明文槽位，只读一次用于迁移，之后不再写入。 */
        val API_KEY = stringPreferencesKey("api_key")
        val REASONING = stringPreferencesKey("reasoning_level")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val FAST = booleanPreferencesKey("fast_mode")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val CONTEXT_PRESET = stringPreferencesKey("context_preset")
        val CONTEXT_CUSTOM = intPreferencesKey("context_custom")
        val MAX_OUTPUT = intPreferencesKey("max_output")
        val USER_PERSONA = stringPreferencesKey("user_persona")

        /** 启用的预设 id，换行分隔（id 里不含换行，编解码就是两次 split）。 */
        val AUTO_COMPACT = booleanPreferencesKey("auto_compact")
        val COMPACT_RATIO = floatPreferencesKey("compact_ratio")
        val COMPACT_BY_COUNT = booleanPreferencesKey("compact_by_count")
        val COMPACT_COUNT = intPreferencesKey("compact_count")
        val COMPACT_RULES = stringPreferencesKey("compact_rules")
        val COMPACT_LARGE_EXEMPT = booleanPreferencesKey("compact_large_window_exempt")
        val COMPACT_RESERVE_OUTPUT = booleanPreferencesKey("compact_reserve_output")
        val COMPACT_CHUNKED = booleanPreferencesKey("compact_chunked")
        val PROACTIVE_CHANCE = intPreferencesKey("proactive_chance")
        val PROACTIVE_IDLE = intPreferencesKey("proactive_idle")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SCREEN_CHANNEL = stringPreferencesKey("screen_channel")
        val SCREEN_PIPELINE = stringPreferencesKey("screen_pipeline")
        val AGENT_MODE = booleanPreferencesKey("agent_mode")
        val AGENT_ROUNDS = intPreferencesKey("agent_rounds")
        val APPROVAL_MODE = stringPreferencesKey("approval_mode")
        val TERMINAL_SANDBOX = booleanPreferencesKey("terminal_sandbox")
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val PROFILE_AVATAR = stringPreferencesKey("profile_avatar")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FOLLOW_SYSTEM_FONT = booleanPreferencesKey("follow_system_font")
        val AUTO_COLLAPSE_THINKING = booleanPreferencesKey("auto_collapse_thinking")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val API_STYLE = stringPreferencesKey("api_style")
        val LOCATION = stringPreferencesKey("location_region")

        /** 朗读音色：机器标识；空 = 系统默认。 */
        val TTS_VOICE = stringPreferencesKey("tts_voice_name")

        /** 朗读音色的人类可读名字（仅用于显示）。 */
        val TTS_VOICE_LABEL = stringPreferencesKey("tts_voice_label")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val STT_LANGUAGE = stringPreferencesKey("stt_language")

        /** 当前端点 `/models` 拉回来的 id，换行分隔（[ModelIdList]）。 */
        val MODEL_IDS = stringPreferencesKey("model_ids")

        /** 勾选要出现在切换气泡里的模型（`KIND:id`，换行分隔）。 */
        val PINNED_MODELS = stringPreferencesKey("pinned_models")

        /** 自动朗读：回复结束就念。 */
        val AUTO_READ = booleanPreferencesKey("auto_read_aloud")

        /** 朗读通道（[TtsChannel]）。 */
        val TTS_CHANNEL = stringPreferencesKey("tts_channel")

        val TTS_API_URL = stringPreferencesKey("tts_api_url")
        val TTS_API_KEY_ENC = stringPreferencesKey("tts_api_key_enc")
        val TTS_API_MODEL = stringPreferencesKey("tts_api_model")
        val TTS_API_VOICE = stringPreferencesKey("tts_api_voice")
        val TTS_API_FORMAT = stringPreferencesKey("tts_api_format")
        val TTS_API_SPEED = floatPreferencesKey("tts_api_speed")
        val TTS_API_HEADERS = stringPreferencesKey("tts_api_headers")

        val IMAGE_API_URL = stringPreferencesKey("image_api_url")
        val IMAGE_API_KEY_ENC = stringPreferencesKey("image_api_key_enc")
        val IMAGE_API_MODEL = stringPreferencesKey("image_api_model")
        val IMAGE_API_SIZE = stringPreferencesKey("image_api_size")
        val IMAGE_API_MODELS = stringPreferencesKey("image_api_models")

    }
}
