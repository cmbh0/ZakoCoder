package com.simplechat.app.net

import com.simplechat.app.net.DeepSeekTts
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 任意 **OpenAI 兼容** 的语音合成（`POST {base}/audio/speech`）。
 *
 * 为什么要有它：系统 TTS 的音色取决于设备装了哪个引擎，听感差异很大；
 * DeepSeek 官方 TTS 音质好，但只有官方端点有。而 OpenAI 的 `/audio/speech`
 * 已经成了事实标准 —— 硅基流动、OneAPI、各类中转、本地 GPT-SoVITS 网关
 * 都照着这个形状实现，于是"填一个地址、一个 Key、一个音色"就够覆盖绝大多数服务。
 *
 * ```json
 * { "model": "tts-1", "input": "…", "voice": "alloy", "response_format": "mp3", "speed": 1.0 }
 * ```
 *
 * 响应是**二进制音频**（mp3 / wav / opus / pcm）。这里不做解码：
 * 除 `pcm` 外一律落临时文件交给 `MediaPlayer`，它对容器的处理比我们可靠得多。
 */
object CustomTts {

    /**
     * 一次朗读的接入参数。
     *
     * [headers] 是"一行一个 `Key: Value`"：有些网关不认 `Authorization`，
     * 要求 `X-API-Key` 或自定义签名头 —— 不给这个口子，那类服务就永远接不进来。
     */
    data class Config(
        val url: String,
        val apiKey: String,
        val model: String,
        val voice: String,
        val format: String,
        val speed: Float,
        val headers: String = "",
    ) {
        val isReady: Boolean get() = url.isNotBlank() && model.isNotBlank()
    }

    /**
     * 端点补全。
     *
     * 用户可能填三种东西：完整端点、`…/v1`、纯 base。三种都认 ——
     * 让用户去查"到底要不要带 `/audio/speech`"是最没必要的摩擦。
     */
    fun endpointOf(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.endsWith("/audio/speech") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/audio/speech"
            // 已经带了版本段（/v1beta、/openai/v1 …）但没到具体接口
            Regex("/v\\d+[a-z]*$").containsMatchIn(trimmed) -> "$trimmed/audio/speech"
            else -> "$trimmed/v1/audio/speech"
        }
    }

    /** 请求体（纯函数，单测钉住字段名）。 */
    fun requestBody(config: Config, text: String): String = buildJsonObject {
        put("model", config.model)
        put("input", text)
        put("voice", config.voice)
        put("response_format", config.format.ifBlank { "mp3" })
        put("speed", config.speed.coerceIn(0.5f, 2f))
    }.toString()

    /** `Key: Value` 逐行 → 头列表。坏行直接跳过，不拦整次合成。 */
    fun parseHeaders(raw: String): List<Pair<String, String>> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
        .map { line ->
            line.substringBefore(':').trim() to line.substringAfter(':').trim()
        }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        .toList()

    /**
     * 合成一段文字，边收边写给 [sink]（IO 线程回调）。
     *
     * 不在这里落盘：调用方可能是播放器（要文件）也可能是"测试"按钮（只要字节数）。
     *
     * @throws TtsException 服务端非 2xx。
     */
    suspend fun synthesize(
        config: Config,
        text: String,
        sink: (bytes: ByteArray, length: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val endpoint = endpointOf(config.url)
        if (endpoint.isEmpty()) throw IOException("TTS 端点为空")
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "audio/*, application/octet-stream")
        if (config.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        parseHeaders(config.headers).forEach { (name, value) -> builder.header(name, value) }

        val request = builder
            .post(requestBody(config, text).toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = runCatching { response.body?.string() }.getOrNull()
                throw DeepSeekTts.TtsException(response.code, DeepSeekTts.serverMessageOf(raw))
            }
            val body = response.body ?: throw IOException("空响应")
            body.byteStream().use { stream ->
                val buffer = ByteArray(ChunkBytes)
                while (true) {
                    ensureActive()
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    sink(buffer, read)
                }
            }
        }
    }

    /** 把整段合成结果落成一个临时文件（后缀按格式），返回文件。 */
    suspend fun download(config: Config, text: String, dir: File): File = withContext(Dispatchers.IO) {
        val suffix = config.format.lowercase().let { if (it == "pcm") "pcm" else it }
        dir.mkdirs()
        val file = File.createTempFile("tts-", ".$suffix", dir)
        file.outputStream().use { out ->
            try {
                synthesize(config, text) { bytes, length -> out.write(bytes, 0, length) }
            } catch (t: Throwable) {
                runCatching { file.delete() }
                throw t
            }
        }
        file
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val ChunkBytes = 16 * 1024

    /**
     * 单次合成的超时给得比较宽：长文合成本来就慢，而音频是边下边写的，
     * 30 秒的读超时会在"服务端很慢但确实在吐字节"时误杀。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
