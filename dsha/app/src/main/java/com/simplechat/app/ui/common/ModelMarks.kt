package com.simplechat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalIsDarkTheme

/**
 * 模型 / 服务商标识：能认出来的用**厂商官方 logo**，认不出来的用**统一的通用符号**。
 *
 * ### 为什么按 id 认而不是按服务商
 *
 * `ModelInfo` 里没有"厂商"这个字段，也不该有 —— 一个中转站可以同时代理
 * `gpt-4o`、`claude-sonnet-4`、`glm-4.6`，它们全都挂在"自定义服务商"下面。
 * 按服务商给图标，等于把三个不同的脑子画成同一个符号。
 *
 * 按 id / 显示名的关键字认，覆盖的是**模型自己是谁**这件事，与从哪条线路
 * 过来无关。认错的风险也有：`gpt-oss` 这类开源权重确实不是 OpenAI 官方端点，
 * 但它的家族还是 GPT —— 图标画对了，信息没有失真。
 *
 * ### 这一版补了什么
 *
 * - 上一版只认 10 家，智谱 / 豆包 / 文心 / 混元这些国内厂商全部落到"首字母"。
 *   现在 [BrandIcons] 补到 80 多枚（见那边的说明），规则表跟着补齐。
 * - 关键词改成**左边界匹配**：`yi-` 只认 `yi-large`，不再误中 `tongyi-…`；
 *   `gpt` 不再把 `360gpt` 认成 OpenAI。短关键词才敢往表里放。
 * - 多了两条入口：按**服务商**（[ofProvider]）和按**端点域名**（[ofHost]）。
 *   设置页要回答的是"我接的是哪一家"，那时手里只有服务商和 Base URL，没有模型。
 * - 认不出来不再画首字母，统一成 [AppIcons.ModelGeneric]（原因写在那枚图标上）。
 */
object ModelMarks {

    /** 认出来的结果。 */
    sealed interface Mark {
        /**
         * 厂商官方 logo。
         *
         * @param accent 品牌主色，只在徽标（[BrandBadge]）里用；
         *   null = 黑白标识（OpenAI、Grok、Ollama……），跟随正文色。
         */
        data class Logo(val vector: ImageVector, val accent: Color? = null) : Mark

        /** 认不出来。画什么由调用方定：模型是六边形，服务商是插头。 */
        data object Generic : Mark
    }

    /**
     * 一条识别规则：关键词 → 家族名 + 图标。
     *
     * 家族名是给**分组**用的（"按供应商折叠"那一版）：一屏几十个模型不分组，
     * 只能一行一行读；分成 DeepSeek / Claude / GPT 几摞，先认家门再认型号。
     */
    private class Rule(
        val label: String,
        val accent: Color?,
        val keys: List<String>,
        val vector: () -> ImageVector,
    )

    /**
     * 关键字 → 图标。**顺序有意义**：先匹配到的赢。
     *
     * 排序口径：
     * 1. 厂商自己的型号名在前（`deepseek-r1-distill-llama` 是 DeepSeek，不是 Llama）；
     * 2. OpenAI 排在中段 —— `gpt` / `codex` 这类词会被别家的型号夹带
     *    （`gpt-5-codex-spark`），所以它得排在"星火"这种更宽的词前面；
     * 3. 最后才是"命名空间前缀"（`google/`、`microsoft/`）：只知道是谁家发的，
     *    不知道具体哪个系列时的兜底。
     *
     * `o1/o3/o4` 用正则单独判，不放进这张表 —— 它们太短。
     */
    private val rules: List<Rule> = listOf(
        Rule("DeepSeek", BrandIcons.Tint.DeepSeek, listOf("deepseek", "深度求索")) { BrandIcons.DeepSeek },
        Rule("Claude", BrandIcons.Tint.Claude, listOf("claude", "anthropic", "sonnet", "opus", "haiku")) {
            BrandIcons.Claude
        },
        Rule("Gemma", BrandIcons.Tint.Gemma, listOf("gemma")) { BrandIcons.Gemma },
        Rule(
            "Gemini",
            BrandIcons.Tint.Gemini,
            listOf("gemini", "bard", "imagen", "veo-", "nano-banana", "learnlm"),
        ) { BrandIcons.Gemini },
        Rule("Grok", null, listOf("grok", "xai", "x-ai")) { BrandIcons.Grok },
        Rule(
            "Qwen",
            BrandIcons.Tint.QWen,
            listOf("qwen", "qwq", "qvq", "tongyi", "通义", "wan2", "wanx"),
        ) { BrandIcons.QWen },
        Rule("Kimi", null, listOf("kimi", "moonshot", "月之暗面")) { BrandIcons.Kimi },
        Rule(
            "GLM",
            BrandIcons.Tint.Zhipu,
            listOf("glm", "chatglm", "zhipu", "智谱", "cogview", "cogvideo", "codegeex", "z-ai", "zai-"),
        ) { BrandIcons.Zhipu },
        Rule(
            "Doubao",
            BrandIcons.Tint.Doubao,
            listOf("doubao", "豆包", "seedream", "seedance", "seededit", "skylark"),
        ) { BrandIcons.Doubao },
        Rule("ERNIE", BrandIcons.Tint.Wenxin, listOf("ernie", "wenxin", "文心", "eb-instant")) {
            BrandIcons.Wenxin
        },
        Rule("Hunyuan", BrandIcons.Tint.Hunyuan, listOf("hunyuan", "混元")) { BrandIcons.Hunyuan },
        Rule(
            "MiniMax",
            BrandIcons.Tint.MiniMax,
            listOf("minimax", "abab", "hailuo", "speech-0"),
        ) { BrandIcons.MiniMax },
        Rule("Yi", BrandIcons.Tint.Yi, listOf("yi-", "01-ai", "零一万物")) { BrandIcons.Yi },
        Rule("Baichuan", BrandIcons.Tint.Baichuan, listOf("baichuan", "百川")) { BrandIcons.Baichuan },
        Rule("Step", BrandIcons.Tint.StepFun, listOf("step-", "stepfun", "阶跃")) { BrandIcons.StepFun },
        Rule("SenseNova", BrandIcons.Tint.SenseNova, listOf("sensechat", "sensenova", "商汤")) {
            BrandIcons.SenseNova
        },
        Rule(
            "Mistral",
            BrandIcons.Tint.Mistral,
            listOf(
                "mistral", "mixtral", "codestral", "pixtral", "ministral",
                "magistral", "devstral", "voxtral",
            ),
        ) { BrandIcons.Mistral },
        Rule("NVIDIA", BrandIcons.Tint.Nvidia, listOf("nemotron")) { BrandIcons.Nvidia },
        Rule(
            "Llama",
            BrandIcons.Tint.Meta,
            listOf("llama", "codellama", "meta-llama", "meta ai"),
        ) { BrandIcons.Meta },
        Rule("Phi", BrandIcons.Tint.Microsoft, listOf("phi-", "phi3", "phi4", "wizardlm")) {
            BrandIcons.Microsoft
        },
        Rule("Perplexity", BrandIcons.Tint.Perplexity, listOf("sonar", "pplx", "perplexity")) {
            BrandIcons.Perplexity
        },
        Rule("Cohere", BrandIcons.Tint.Cohere, listOf("command-", "cohere", "c4ai")) { BrandIcons.Cohere },
        Rule("Nova", BrandIcons.Tint.Nova, listOf("nova-")) { BrandIcons.Nova },
        Rule("AI21", null, listOf("jamba", "ai21", "j2-")) { BrandIcons.AI21 },
        Rule("Liquid", null, listOf("lfm")) { BrandIcons.Liquid },
        Rule("Solar", BrandIcons.Tint.Upstage, listOf("solar", "upstage")) { BrandIcons.Upstage },
        Rule("Granite", null, listOf("granite")) { BrandIcons.IBM },
        Rule("Mercury", null, listOf("mercury")) { BrandIcons.Inception },
        Rule("LongCat", BrandIcons.Tint.LongCat, listOf("longcat")) { BrandIcons.LongCat },
        Rule("KAT", null, listOf("kat-coder", "kwaipilot")) { BrandIcons.KwaiPilot },
        Rule("Pangu", BrandIcons.Tint.Huawei, listOf("pangu", "盘古")) { BrandIcons.Huawei },
        Rule("v0", null, listOf("v0-")) { BrandIcons.V0 },
        // ── 绘图 / 视频 / 语音 ──────────────────────────────
        Rule("FLUX", null, listOf("flux", "black-forest")) { BrandIcons.Flux },
        Rule(
            "Stability",
            BrandIcons.Tint.Stability,
            listOf("stable-diffusion", "sdxl", "sd3", "stability"),
        ) { BrandIcons.Stability },
        Rule("Midjourney", null, listOf("midjourney", "mj-", "mj_")) { BrandIcons.Midjourney },
        Rule("Recraft", null, listOf("recraft")) { BrandIcons.Recraft },
        Rule("Kling", null, listOf("kling", "可灵")) { BrandIcons.Kling },
        Rule("Jimeng", BrandIcons.Tint.Jimeng, listOf("jimeng", "即梦")) { BrandIcons.Jimeng },
        Rule("Luma", null, listOf("luma", "ray-")) { BrandIcons.Luma },
        Rule("Pika", null, listOf("pika")) { BrandIcons.Pika },
        Rule("Runway", null, listOf("runway", "gen4")) { BrandIcons.Runway },
        Rule("Vidu", BrandIcons.Tint.Vidu, listOf("vidu")) { BrandIcons.Vidu },
        Rule("ElevenLabs", null, listOf("eleven")) { BrandIcons.ElevenLabs },
        // ── OpenAI：排在这里，见上面的排序口径 ───────────────
        Rule(
            OpenAiLabel,
            null,
            listOf(
                "gpt", "openai", "chatgpt", "davinci", "codex", "dall-e",
                "whisper", "sora", "tts-1", "babbage", "omni-moderation",
            ),
        ) { BrandIcons.OpenAI },
        // "星火"的英文名太常见，放在 OpenAI 后面，并且只认带型号后缀的写法
        Rule(
            "Spark",
            BrandIcons.Tint.Spark,
            listOf(
                "sparkdesk", "spark-lite", "spark-pro", "spark-max", "spark-ultra", "spark-4",
                "spark4", "generalv", "星火", "讯飞", "xfyun", "iflytek",
            ),
        ) { BrandIcons.Spark },
        // ── 只知道发布者，不知道系列 ─────────────────────────
        Rule("Google", BrandIcons.Tint.Google, listOf("google/", "google-", "palm")) { BrandIcons.Google },
        Rule("Microsoft", BrandIcons.Tint.Microsoft, listOf("microsoft/", "mai-")) { BrandIcons.Microsoft },
        Rule("NVIDIA", BrandIcons.Tint.Nvidia, listOf("nvidia")) { BrandIcons.Nvidia },
        Rule("Amazon", BrandIcons.Tint.AWS, listOf("amazon", "titan")) { BrandIcons.AWS },
        Rule("Llama", BrandIcons.Tint.Meta, listOf("meta/", "facebook/")) { BrandIcons.Meta },
        Rule("Hugging Face", BrandIcons.Tint.HuggingFace, listOf("huggingface", "hf.co")) {
            BrandIcons.HuggingFace
        },
    )

    /** o1 / o3 / o4 / o4-mini：OpenAI 的推理系列，名字就是两个字符。 */
    private val oSeries = Regex("(^|[^a-z0-9])o[1-9](-|$| )")

    /**
     * 关键词是否出现在 [haystack] 里，并且**左边是词边界**。
     *
     * 只约束左边：`mistral` 要能认出 `mistralai/…`，`gpt` 要能认出 `gpt4o`。
     * 中文关键词不需要边界 —— 中文里没有"词中间"这回事。
     */
    private fun hit(haystack: String, key: String): Boolean {
        val first = key[0]
        val needsBoundary = first in 'a'..'z' || first in '0'..'9'
        var from = 0
        while (from <= haystack.length) {
            val at = haystack.indexOf(key, from)
            if (at < 0) return false
            if (!needsBoundary || at == 0) return true
            val prev = haystack[at - 1]
            if (!(prev in 'a'..'z' || prev in '0'..'9')) return true
            from = at + 1
        }
        return false
    }

    private fun ruleOf(model: ModelInfo): Rule? {
        val haystack = (model.id + " " + model.displayName).lowercase()
        // 先过一遍带厂商名的规则：`deepseek-…-o1-style` 这种自定义命名不该被 o 系抢走
        val named = rules.firstOrNull { rule -> rule.keys.any { hit(haystack, it) } }
        if (named != null) return named
        if (oSeries.containsMatchIn(haystack)) return rules.first { it.label == OpenAiLabel }
        return null
    }

    /**
     * 这个模型属于哪一家（用于分组标题）。
     *
     * 认不出来一律归到「其他」—— 一个中转站的自研模型本来就不属于任何一家，
     * 硬塞进最近的类别只会误导。
     */
    fun familyOf(model: ModelInfo): String =
        ruleOf(model)?.label ?: Res.get(R.string.model_group_other)

    /** 家族图标；认不出来时返回 null（调用方用 [AppIcons.ModelGeneric] 兜底）。 */
    fun familyIcon(model: ModelInfo): ImageVector? = ruleOf(model)?.vector?.invoke()

    /** 分组排序键：认出来的按规则表顺序，认不出来的统一排最后。 */
    fun familyRank(model: ModelInfo): Int {
        val rule = ruleOf(model) ?: return rules.size
        // 同名规则（Llama / NVIDIA 各有两条）取第一条的位置，分组才不会被拆成两摞
        return rules.indexOfFirst { it.label == rule.label }
    }

    fun of(model: ModelInfo): Mark {
        val rule = ruleOf(model) ?: return Mark.Generic
        return Mark.Logo(rule.vector(), rule.accent)
    }

    /** 只有一个 id 字符串时（绘图模型、手填的模型名）。 */
    fun ofModelId(id: String): Mark = of(ModelInfo(id = id))

    // ── 服务商 / 端点 ────────────────────────────────────────

    /**
     * 服务商的标识。
     *
     * 内置服务商各有各的 logo；「自定义」则看 Base URL 的域名 —— 用户填的
     * `https://api.siliconflow.cn/v1` 明明是硅基流动，没理由只给一个通用图标。
     */
    fun ofProvider(kind: ProviderKind, baseUrl: String = ""): Mark = when (kind) {
        ProviderKind.OPENAI -> Mark.Logo(BrandIcons.OpenAI)
        ProviderKind.DEEPSEEK -> Mark.Logo(BrandIcons.DeepSeek, BrandIcons.Tint.DeepSeek)
        ProviderKind.ZHIPU -> Mark.Logo(BrandIcons.Zhipu, BrandIcons.Tint.Zhipu)
        ProviderKind.MOONSHOT -> Mark.Logo(BrandIcons.Kimi)
        ProviderKind.DASHSCOPE -> Mark.Logo(BrandIcons.Bailian, BrandIcons.Tint.Bailian)
        ProviderKind.SILICONFLOW -> Mark.Logo(BrandIcons.SiliconFlow, BrandIcons.Tint.SiliconFlow)
        ProviderKind.OPENROUTER -> Mark.Logo(BrandIcons.OpenRouter, BrandIcons.Tint.OpenRouter)
        ProviderKind.GROQ -> Mark.Logo(BrandIcons.Groq, BrandIcons.Tint.Groq)
        ProviderKind.XAI -> Mark.Logo(BrandIcons.XAI)
        ProviderKind.MISTRAL -> Mark.Logo(BrandIcons.Mistral, BrandIcons.Tint.Mistral)
        ProviderKind.GEMINI -> Mark.Logo(BrandIcons.Gemini, BrandIcons.Tint.Gemini)
        ProviderKind.OLLAMA -> Mark.Logo(BrandIcons.Ollama)
        ProviderKind.LMSTUDIO -> Mark.Logo(BrandIcons.LmStudio, BrandIcons.Tint.LmStudio)
        // 自定义（以及以后新增、这里还没来得及配图标的渠道）：按域名认
        else -> ofHost(baseUrl)
    }

    private class Host(val key: String, val accent: Color?, val vector: () -> ImageVector)

    /**
     * 域名片段 → 标识。同样是**先到先得**，所以 `openai.azure.com` 要排在 `openai.com` 前面。
     *
     * 只收"看域名就能确定是谁"的；各家自建的中转站域名五花八门，认不出来才是对的。
     */
    private val hosts: List<Host> = listOf(
        Host("azure.com", BrandIcons.Tint.Azure) { BrandIcons.Azure },
        Host("openai.com", null) { BrandIcons.OpenAI },
        Host("deepseek.com", BrandIcons.Tint.DeepSeek) { BrandIcons.DeepSeek },
        Host("anthropic.com", BrandIcons.Tint.Claude) { BrandIcons.Claude },
        Host("googleapis.com", BrandIcons.Tint.Gemini) { BrandIcons.Gemini },
        Host("api.x.ai", null) { BrandIcons.XAI },
        Host("bigmodel.cn", BrandIcons.Tint.Zhipu) { BrandIcons.Zhipu },
        Host("api.z.ai", null) { BrandIcons.ZAI },
        Host("moonshot.", null) { BrandIcons.Kimi },
        Host("kimi.com", null) { BrandIcons.Kimi },
        Host("dashscope", BrandIcons.Tint.Bailian) { BrandIcons.Bailian },
        Host("aliyuncs.com", BrandIcons.Tint.Bailian) { BrandIcons.Bailian },
        Host("siliconflow", BrandIcons.Tint.SiliconFlow) { BrandIcons.SiliconFlow },
        Host("openrouter.ai", BrandIcons.Tint.OpenRouter) { BrandIcons.OpenRouter },
        Host("groq.com", BrandIcons.Tint.Groq) { BrandIcons.Groq },
        Host("mistral.ai", BrandIcons.Tint.Mistral) { BrandIcons.Mistral },
        Host("volces.com", BrandIcons.Tint.Volcengine) { BrandIcons.Volcengine },
        Host("volcengine", BrandIcons.Tint.Volcengine) { BrandIcons.Volcengine },
        Host("baidubce.com", BrandIcons.Tint.BaiduCloud) { BrandIcons.BaiduCloud },
        Host("qianfan", BrandIcons.Tint.BaiduCloud) { BrandIcons.BaiduCloud },
        Host("hunyuan", BrandIcons.Tint.Hunyuan) { BrandIcons.Hunyuan },
        Host("tencentcloud", BrandIcons.Tint.TencentCloud) { BrandIcons.TencentCloud },
        Host("cloud.tencent", BrandIcons.Tint.TencentCloud) { BrandIcons.TencentCloud },
        Host("minimax", BrandIcons.Tint.MiniMax) { BrandIcons.MiniMax },
        Host("stepfun", BrandIcons.Tint.StepFun) { BrandIcons.StepFun },
        Host("baichuan", BrandIcons.Tint.Baichuan) { BrandIcons.Baichuan },
        Host("lingyiwanwu", BrandIcons.Tint.Yi) { BrandIcons.Yi },
        Host("xf-yun", BrandIcons.Tint.Spark) { BrandIcons.Spark },
        Host("xfyun", BrandIcons.Tint.Spark) { BrandIcons.Spark },
        Host("sensenova", BrandIcons.Tint.SenseNova) { BrandIcons.SenseNova },
        Host("together.", BrandIcons.Tint.Together) { BrandIcons.Together },
        Host("fireworks.ai", BrandIcons.Tint.Fireworks) { BrandIcons.Fireworks },
        Host("perplexity.ai", BrandIcons.Tint.Perplexity) { BrandIcons.Perplexity },
        Host("cohere.", BrandIcons.Tint.Cohere) { BrandIcons.Cohere },
        Host("nvidia.com", BrandIcons.Tint.Nvidia) { BrandIcons.Nvidia },
        Host("amazonaws.com", BrandIcons.Tint.AWS) { BrandIcons.AWS },
        Host("cloudflare.com", BrandIcons.Tint.Cloudflare) { BrandIcons.Cloudflare },
        Host("github.ai", null) { BrandIcons.GitHub },
        Host("githubcopilot", null) { BrandIcons.GitHub },
        Host("huggingface.co", BrandIcons.Tint.HuggingFace) { BrandIcons.HuggingFace },
        Host("cerebras.ai", BrandIcons.Tint.Cerebras) { BrandIcons.Cerebras },
        Host("sambanova.ai", BrandIcons.Tint.SambaNova) { BrandIcons.SambaNova },
        Host("novita.ai", BrandIcons.Tint.Novita) { BrandIcons.Novita },
        Host("hyperbolic.xyz", BrandIcons.Tint.Hyperbolic) { BrandIcons.Hyperbolic },
        Host("nebius", null) { BrandIcons.Nebius },
        Host("chutes.ai", null) { BrandIcons.Chutes },
        Host("modelscope.cn", BrandIcons.Tint.ModelScope) { BrandIcons.ModelScope },
        Host("gitee.com", null) { BrandIcons.GiteeAI },
        Host("aihubmix.com", BrandIcons.Tint.AiHubMix) { BrandIcons.AiHubMix },
        Host("302.ai", BrandIcons.Tint.AI302) { BrandIcons.AI302 },
        Host("ppinfra.com", BrandIcons.Tint.PPIO) { BrandIcons.PPIO },
        Host("ppio.", BrandIcons.Tint.PPIO) { BrandIcons.PPIO },
        Host("qiniu", BrandIcons.Tint.Qiniu) { BrandIcons.Qiniu },
        Host("qnaigc", BrandIcons.Tint.Qiniu) { BrandIcons.Qiniu },
        Host("infini-ai.com", BrandIcons.Tint.Infinigence) { BrandIcons.Infinigence },
        Host("sophnet.com", BrandIcons.Tint.SophNet) { BrandIcons.SophNet },
        Host("poe.com", BrandIcons.Tint.Poe) { BrandIcons.Poe },
        Host("vercel", null) { BrandIcons.Vercel },
        Host("v0.dev", null) { BrandIcons.V0 },
        Host("fal.run", BrandIcons.Tint.Fal) { BrandIcons.Fal },
        Host("fal.ai", BrandIcons.Tint.Fal) { BrandIcons.Fal },
        Host("replicate.com", null) { BrandIcons.Replicate },
        Host("bfl.ai", null) { BrandIcons.Flux },
        Host("bfl.ml", null) { BrandIcons.Flux },
        Host("stability.ai", BrandIcons.Tint.Stability) { BrandIcons.Stability },
        Host("recraft.ai", null) { BrandIcons.Recraft },
        Host("klingai", null) { BrandIcons.Kling },
        Host("elevenlabs.io", null) { BrandIcons.ElevenLabs },
        Host("inceptionlabs", null) { BrandIcons.Inception },
        Host("upstage.ai", BrandIcons.Tint.Upstage) { BrandIcons.Upstage },
        Host("liquid.ai", null) { BrandIcons.Liquid },
        Host("ai21.com", null) { BrandIcons.AI21 },
        Host("longcat", BrandIcons.Tint.LongCat) { BrandIcons.LongCat },
        Host("streamlake", null) { BrandIcons.KwaiPilot },
        Host("huaweicloud", BrandIcons.Tint.Huawei) { BrandIcons.Huawei },
        Host("runwayml", null) { BrandIcons.Runway },
        Host("lumalabs", null) { BrandIcons.Luma },
        // 本机运行时：没有域名，靠默认端口认
        Host("ollama", null) { BrandIcons.Ollama },
        Host(":11434", null) { BrandIcons.Ollama },
        Host(":1234", BrandIcons.Tint.LmStudio) { BrandIcons.LmStudio },
    )

    /** 按端点地址认服务商。空串、认不出来的域名都是 [Mark.Generic]。 */
    fun ofHost(url: String): Mark {
        val text = url.trim().lowercase()
        if (text.isEmpty()) return Mark.Generic
        val host = hosts.firstOrNull { text.contains(it.key) } ?: return Mark.Generic
        return Mark.Logo(host.vector(), host.accent)
    }

    private const val OpenAiLabel = "OpenAI"
}

/**
 * 画一枚模型标识（**行内**的小图标，灰阶）。
 *
 * [size] 默认 15dp —— 与浮层里其他行首图标同高（上下文面板的图标也是这个尺寸）。
 * 认不出厂商时画 [AppIcons.ModelGeneric]，同样吃 [tint]，一排下来高矮粗细一致。
 */
@Composable
fun ModelMark(
    model: ModelInfo,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    tint: Color = LocalChatColors.current.processText,
) {
    val mark = remember(model.id, model.displayName) { ModelMarks.of(model) }
    Icon(
        imageVector = when (mark) {
            is ModelMarks.Mark.Logo -> mark.vector
            is ModelMarks.Mark.Generic -> AppIcons.ModelGeneric
        },
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * 徽标：圆底 + 居中的标识。设置页里回答"这是哪一家"的那种大一号图标。
 *
 * 和行内的 [ModelMark] 是两种东西：
 * - 行内标识跟着文字排，一屏十几个，必须是灰阶，否则模型名会被颜色淹掉；
 * - 徽标一屏只有一两个（当前服务商、当前模型），这里才值得把品牌色亮出来 ——
 *   图标染品牌色，底是同一色相极淡的一档，看上去就是那家的"头像"。
 *
 * 黑白标识（[ModelMarks.Mark.Logo.accent] 为 null）和认不出来的，底色统一用
 * 输入框那一档灰，不硬凑颜色。
 *
 * @param fallback 认不出来时画什么：模型用六边形，服务商 / 端点用插头
 * @param colored  false = 整枚走灰阶（"未配置"这类状态，不该比已配置的更显眼）
 */
@Composable
fun BrandBadge(
    mark: ModelMarks.Mark,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    fallback: ImageVector = AppIcons.ModelGeneric,
    colored: Boolean = true,
) {
    val colors = LocalChatColors.current
    val dark = LocalIsDarkTheme.current
    val logo = mark as? ModelMarks.Mark.Logo
    val accent = if (colored) logo?.accent else null

    val tint = when {
        logo == null -> colors.processText
        accent == null -> MaterialTheme.colorScheme.onSurface
        else -> accent.readableOn(dark)
    }
    val container = if (accent == null) {
        colors.fieldBackground
    } else {
        // 深色下淡底要更实一点：10% 的品牌色压在近黑的卡片上几乎看不出来
        tint.copy(alpha = if (dark) 0.20f else 0.10f)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = logo?.vector ?: fallback,
            contentDescription = null,
            tint = tint,
            // 0.54：圆里放方形 logo，再大四个角就顶到圆边了
            modifier = Modifier.size((size.value * 0.54f).dp),
        )
    }
}

/** 模型徽标。 */
@Composable
fun ModelBadge(
    model: ModelInfo,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    colored: Boolean = true,
) {
    val mark = remember(model.id, model.displayName) { ModelMarks.of(model) }
    BrandBadge(
        mark = mark,
        modifier = modifier,
        size = size,
        fallback = AppIcons.ModelGeneric,
        colored = colored,
    )
}

/** 服务商徽标：先按服务商认，「自定义」再按 Base URL 的域名认。 */
@Composable
fun ProviderBadge(
    kind: ProviderKind,
    baseUrl: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    colored: Boolean = true,
) {
    val mark = remember(kind, baseUrl) { ModelMarks.ofProvider(kind, baseUrl) }
    BrandBadge(
        mark = mark,
        modifier = modifier,
        size = size,
        fallback = AppIcons.Api,
        colored = colored,
    )
}

/**
 * 品牌色在当前主题下是否够醒目；深色主题里偏暗的颜色往白色提一档。
 *
 * 只处理深色这一侧：品牌主色普遍偏深（深蓝、墨绿、紫），压在浅色卡片上都没问题，
 * 压在 #1A1A1C 上就会糊成一团。提亮而不是直接换成白色 —— 色相还在，认得出是谁。
 */
private fun Color.readableOn(dark: Boolean): Color {
    if (!dark) return this
    val luma = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luma < 0.5f) lerp(this, Color.White, 0.38f) else this
}
