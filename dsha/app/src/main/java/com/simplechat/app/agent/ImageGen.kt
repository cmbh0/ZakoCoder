package com.simplechat.app.agent

import com.simplechat.app.data.Res
import com.simplechat.app.R
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 文生图（OpenAI 兼容 `POST {base}/images/generations`）。
 *
 * 为什么走"工具"而不是在客户端偷偷把回复换了：用户是**用语言**要图的
 * （"画一只戴墨镜的柴犬"），把这句话翻译成绘图提示词、决定尺寸、决定画几张，
 * 都是模型该做的判断。客户端只提供一个 `generate_image` 工具，什么时候用、
 * 提示词怎么写，交给它 —— 这也是 Operit 一类客户端的成熟做法。
 *
 * ### 没配置就不挂工具
 *
 * 配置为空时这个工具**根本不出现在工具表里**，模型看不到它，用户让它画图
 * 时它会直接说做不到。反过来，挂一个必定返回"没配置"的工具，只会让它
 * 反复重试、把一轮对话拖成几次失败。
 *
 * ### 比例，而不是"永远方图"
 *
 * 早先只暴露 `size`（WxH），描述里还写着"通常省略、让服务端选默认"——
 * 模型于是永远省略，服务端也就永远给 1024×1024。用户说"来张手机壁纸"、
 * "画个横版封面"，出来的还是方图，看起来像客户端写死了尺寸。
 *
 * 现在把**比例**提到第一入口：`aspect_ratio` 是模型能直接理解的东西
 * （用户说竖版，它就填 9:16），客户端再按模型族把它换算成服务端认的像素档位。
 * 换算表是必要的：`gpt-image-1` 只认三档、`dall-e-3` 只认三档，
 * 直接把 `16:9` 当 size 发过去就是 400。
 *
 * ### 两种响应都认
 *
 * 官方 `gpt-image-1` 只回 `b64_json`，`dall-e-3` 与不少网关回 `url`。
 * 请求里**不带 `response_format`**（`gpt-image-1` 不认这个字段，带了会 400），
 * 而是把两种形状都解析掉 —— 兼容性靠解析端做，比靠请求参数猜稳。
 *
 * ### 直链就直接显示，不落盘
 *
 * 回 `url` 时不下载：那张图本来就在 CDN 上，客户端再存一份到磁盘只是多占空间，
 * 还得处理"存哪儿、有没有权限"。界面直接按 URL 取图显示。
 *
 * 回 `b64_json` 的必须落地才能给 `BitmapFactory` 读，但落点是 **App 私有目录**
 * （`files/generated`），不是用户绑定的工作区 —— 工作区可能是存储根目录，
 * 普通用户没有 root、也不该为了看一张图去要「所有文件访问」。
 */
object ImageGen {

    data class Config(
        /** 完整端点，或只到 `/v1`，或纯 base（见 [endpointOf]）。 */
        val url: String,
        val apiKey: String,
        val model: String,
        val size: String,
    ) {
        val isReady: Boolean get() = url.isNotBlank() && model.isNotBlank()
    }

    /**
     * 一次出图的结果。
     *
     * [source] 是界面**直接可用**的来源：`https://…` 直链，或 App 私有目录里的
     * 文件路径。两种都由 `ui/common/ImageSource.kt` 统一加载，调用方不必区分。
     */
    data class Generated(
        val source: String,
        /** 宽 / 高。用于占位轮廓与卡片排版；服务端没给尺寸时退回请求值。 */
        val aspect: Float,
        /** 实际尺寸（`1024x1536`）；完全交给服务端时为 null。 */
        val size: String?,
    ) {
        val remote: Boolean
            get() = source.startsWith("http://") || source.startsWith("https://")
    }

    /** 服务端拒绝（带状态码与可读原因）。 */
    class ImageException(val code: Int, val detail: String?) :
        IOException("image HTTP $code: ${detail.orEmpty()}")

    /** 端点补全：三种写法都认，理由同 `CustomTts.endpointOf`。 */
    fun endpointOf(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.endsWith("/images/generations") -> trimmed
            Regex("/v\\d+[a-z]*$").containsMatchIn(trimmed) -> "$trimmed/images/generations"
            else -> "$trimmed/v1/images/generations"
        }
    }

    // ══════════════════════════════════════════════════════
    //  比例 → 像素
    // ══════════════════════════════════════════════════════

    private val PixelsPattern = Regex("^(\\d{2,4})\\s*[x×*]\\s*(\\d{2,4})$")
    private val RatioPattern = Regex("^(\\d+(?:\\.\\d+)?)\\s*[:/]\\s*(\\d+(?:\\.\\d+)?)$")

    /**
     * 常见出图档位。
     *
     * 取的是各家**交集里的常见值**：OpenAI 的 1024 系、SDXL 的 1024 系、
     * 豆包/即梦的 1152/1280 系、Kolors 的 1024 系。挑"最接近目标比例"的一档，
     * 而不是按比例硬算一个任意宽高 —— 任意宽高在严格的网关上直接 400。
     */
    private val CommonSizes = listOf(
        1024 to 1024,
        1280 to 720,
        720 to 1280,
        1024 to 768,
        768 to 1024,
        1152 to 864,
        864 to 1152,
        1536 to 1024,
        1024 to 1536,
        1792 to 1024,
        1024 to 1792,
        1248 to 832,
        832 to 1248,
    )

    /** `1024x1536` → (1024, 1536)。不是像素写法就返回 null。 */
    fun pixelsOf(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim() ?: return null
        val m = PixelsPattern.find(text) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    /**
     * 任意写法 → 宽高比（宽 / 高）。
     *
     * 认 `16:9`、`16/9`、`1024x1536`、`1.78`，也认几个常见词（模型偶尔会
     * 把 `portrait` 这类词塞进来）。认不出（含 `auto`、空串）返回 null。
     */
    fun parseRatio(raw: String?): Float? {
        val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (text == "auto" || text == "自动") return null

        pixelsOf(text)?.let { (w, h) -> return (w.toFloat() / h).coerceIn(0.2f, 5f) }

        RatioPattern.find(text)?.let { m ->
            val w = m.groupValues[1].toFloatOrNull() ?: return@let
            val h = m.groupValues[2].toFloatOrNull() ?: return@let
            if (w > 0f && h > 0f) return (w / h).coerceIn(0.2f, 5f)
        }

        when (text) {
            "square" -> return 1f
            "portrait", "vertical", "竖版", "竖图" -> return 0.75f
            "landscape", "horizontal", "横版", "横图" -> return 1.33f
        }

        text.toFloatOrNull()?.let { return it.takeIf { v -> v in 0.2f..5f } }
        return null
    }

    /**
     * 目标比例 → 该模型**确实能接受**的像素尺寸。
     *
     * `gpt-image-1` 与 `dall-e-3` 只有三档，必须映射；其余模型走通用档位表，
     * 挑比例最接近的一档（用对数距离，横竖对称 —— 直接减差会让竖图吃亏）。
     */
    fun sizeFor(model: String, ratio: Float): Pair<Int, Int> {
        val m = model.lowercase()
        val portrait = ratio < 0.87f
        val landscape = ratio > 1.15f
        return when {
            m.contains("dall-e-3") || m.contains("dalle") -> when {
                landscape -> 1792 to 1024
                portrait -> 1024 to 1792
                else -> 1024 to 1024
            }

            m.contains("gpt-image") -> when {
                landscape -> 1536 to 1024
                portrait -> 1024 to 1536
                else -> 1024 to 1024
            }

            else -> CommonSizes.minByOrNull { (w, h) ->
                abs(ln((w.toFloat() / h) / ratio))
            } ?: (1024 to 1024)
        }
    }

    /**
     * 决定这次请求带什么 `size`（null = 不带，交给服务端）。
     *
     * 优先级刻意是 **对话里的要求 > 设置里的固定值**：
     * 用户在设置里选了 1024×1024，转头说"来张竖版壁纸" —— 该听后者。
     * 设置是默认值，不是禁令。
     */
    fun resolveSize(
        model: String,
        aspectRatio: String?,
        requestedSize: String?,
        fallback: String?,
    ): Pair<Int, Int>? {
        pixelsOf(requestedSize)?.let { (w, h) -> return normalize(model, w, h) }
        parseRatio(requestedSize)?.let { return sizeFor(model, it) }
        parseRatio(aspectRatio)?.let { return sizeFor(model, it) }
        pixelsOf(fallback)?.let { (w, h) -> return normalize(model, w, h) }
        parseRatio(fallback)?.let { return sizeFor(model, it) }
        return null
    }

    /**
     * 把用户/模型给的像素收进服务端能接受的档位。
     *
     * 只有 OpenAI 那两个模型族需要收：它们对尺寸是白名单，不是范围。
     * 其余模型原样透传（网关通常按范围校验），只做 256–4096 的边界保护。
     */
    fun normalize(model: String, width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceIn(256, 4096)
        val h = height.coerceIn(256, 4096)
        val m = model.lowercase()
        val strict = m.contains("gpt-image") || m.contains("dall-e") || m.contains("dalle")
        return if (strict) sizeFor(model, w.toFloat() / h) else w to h
    }

    /** 请求体（纯函数，单测钉住字段名）。[size] 已由 [resolveSize] 归一，null 表示不带。 */
    fun requestBody(config: Config, prompt: String, size: String?, count: Int): String =
        buildJsonObject {
            put("model", config.model)
            put("prompt", prompt)
            if (!size.isNullOrBlank()) put("size", size)
            put("n", count.coerceIn(1, 4))
        }.toString()

    /**
     * 生成一张图。
     *
     * 只取第一张：一次画四张再让用户挑，是"选择困难"而不是"能力"；
     * 真要更多，模型可以再调一次工具。
     *
     * [dir] 必须是 **App 私有目录**（见 `Workspace.appDir`）。直链结果不落盘。
     */
    suspend fun generate(
        config: Config,
        prompt: String,
        size: String?,
        aspectRatio: String?,
        dir: File,
    ): Generated = withContext(Dispatchers.IO) {
        val endpoint = endpointOf(config.url)
        if (endpoint.isEmpty()) throw IOException("绘图端点为空")

        val pixels = resolveSize(config.model, aspectRatio, size, config.size)
        val sizeText = pixels?.let { "${it.first}x${it.second}" }
        val wantedAspect = pixels?.let { it.first.toFloat() / it.second }
            ?: parseRatio(aspectRatio)
            ?: parseRatio(size)
            ?: 1f

        val builder = Request.Builder().url(endpoint).header("Accept", "application/json")
        if (config.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${config.apiKey}")
        val request = builder
            .post(requestBody(config, prompt, sizeText, 1).toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
            if (!response.isSuccessful) throw ImageException(response.code, serverMessageOf(body))

            val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw IOException("响应不是 JSON")
            val first = parsed["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IOException("响应里没有 data")

            // 服务端回的尺寸最可信：它可能忽略我们请求的值
            val served = pixelsOf(first["size"]?.jsonPrimitive?.contentOrNull)
            val aspect = served?.let { it.first.toFloat() / it.second } ?: wantedAspect
            val servedText = served?.let { "${it.first}x${it.second}" } ?: sizeText

            val name = "gen-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4)}"
            first["b64_json"]?.jsonPrimitive?.contentOrNull?.let { encoded ->
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                    ?: throw IOException("b64_json 解不开")
                dir.mkdirs()
                val file = File(dir, "$name.png")
                file.writeBytes(bytes)
                return@use Generated(file.absolutePath, aspect, servedText)
            }
            val url = first["url"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("响应里既没有 b64_json 也没有 url")
            /*
             * 直链**立刻下载**，不留到显示时再取。
             *
             * 上一版把 url 原样塞给界面：服务端那条链接常常几分钟就过期，
             * 或者是内网域名、或者要求带 Referer —— 界面上于是只剩一个空框，
             * 而且**不会有任何报错**，因为"加载失败"发生在图片组件里，
             * 跟这次工具调用毫无关系。用户看到的就是"生成了，但没有图"。
             *
             * 下载失败也要抛出去：宁可让这次调用红一条原因，
             * 也不要给一个永远转圈的空卡。
             */
            val bytes = runCatching {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.bytes() ?: throw IOException("响应为空")
                }
            }.getOrElse { e ->
                throw IOException("图片已生成但下载失败（$url）：${e.message ?: e.javaClass.simpleName}")
            }
            if (bytes.isEmpty()) throw IOException("图片已生成但下载回来是空的（$url）")
            dir.mkdirs()
            val file = File(dir, "$name.png")
            file.writeBytes(bytes)
            return@use Generated(file.absolutePath, aspect, servedText)
        }
    }

    /** 从错误体里取一句人话（OpenAI 风格 `{"error":{"message":…}}` 或原文）。 */
    internal fun serverMessageOf(raw: String): String? {
        val text = raw.trim().takeUnless { it.isEmpty() } ?: return null
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val message = parsed?.get("error")?.let { it as? JsonObject }
            ?.get("message")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("message")?.jsonPrimitive?.contentOrNull
        return (message ?: text).take(200)
    }

    /**
     * 构造挂进工具表的 `generate_image`。
     *
     * `aspect_ratio` 放在 `size` 前面、并且写清楚"用户提到竖版/横版就必须填"：
     * 模型对参数的取舍基本跟着描述里的示例走，一条含糊的"可选"等于永远不填。
     */
    fun tool(config: Config): AgentTool = object : AgentTool {

        override val name = "generate_image"

        override val description =
            "Generate an image from a text description. Use this whenever the user asks you " +
                "to draw, paint, design a picture, illustrate something, or produce any visual " +
                "content. Write the prompt yourself in concrete visual terms (subject, style, " +
                "lighting, composition, colors); do not just repeat the user's sentence. " +
                "You MUST also decide the aspect ratio from what the user asked for — a phone " +
                "wallpaper is 9:16, a cover/poster is 3:4 or 2:3, a banner is 16:9, a square " +
                "avatar is 1:1. Never leave it defaulted when the user implied a shape."

        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "Detailed visual description of the image to generate.")
                }
                putJsonObject("aspect_ratio") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3").forEach { add(it) }
                    }
                    put(
                        "description",
                        "Shape of the picture. Infer it from the user's wording: " +
                            "手机壁纸/竖版/人像 → 9:16 or 3:4; 横版/封面/banner → 16:9 or 3:2; " +
                            "头像/方图 → 1:1. Default 1:1 only when nothing implies a shape.",
                    )
                }
                putJsonObject("size") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional exact pixels like 1024x1536, only when the user named a " +
                            "concrete resolution. Prefer aspect_ratio for everything else.",
                    )
                }
            }
            putJsonArray("required") { add("prompt") }
        }

        override val mutating: Boolean get() = false

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val prompt = args.str("prompt")?.trim().orEmpty()
            if (prompt.isBlank()) return ToolOutcome.fail("prompt 不能为空：请写清楚画面内容")
            val size = args.str("size")
            val ratio = args.str("aspect_ratio")
            return try {
                /*
                 * 出图失败自动补一次：这一步常常只是网关抖了一下（连接重置、
                 * 读超时），让模型再调一次等于白白多一轮对话。
                 * 只对网络类异常重试 —— `ImageException` 是服务端明确拒绝
                 * （参数不对、内容被拦、余额不足），原样再发一遍没意义。
                 */
                /*
                 * 落点固定在 App 私有目录：私有目录永远可写，也不会往用户的
                 * 相册/文件管理器里堆中间产物。
                 */
                val outDir = File(Workspace.appDir(), "generated")
                val generated = try {
                    generate(config, prompt, size, ratio, outDir)
                } catch (e: IOException) {
                    kotlinx.coroutines.delay(1_200)
                    generate(config, prompt, size, ratio, outDir)
                }
                ToolOutcome.ok(
                    output = buildString {
                        append("图片已生成")
                        generated.size?.let { append("（$it）") }
                        append("，已直接显示给用户，不需要再下载或读取：")
                        append(generated.source)
                    },
                    files = listOf(generated.source),
                    artifacts = listOf(generated.source),
                )
            } catch (e: ImageException) {
                ToolOutcome.fail(
                    Res.get(R.string.image_gen_failed, e.code.toString() + " " + e.detail.orEmpty()),
                )
            } catch (e: Throwable) {
                ToolOutcome.fail(Res.get(R.string.image_gen_failed, e.message.orEmpty()))
            }
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 出图本来就慢（10–60 秒），读超时给足。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
