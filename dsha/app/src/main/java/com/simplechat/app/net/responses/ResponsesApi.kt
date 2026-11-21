package com.simplechat.app.net.responses

import com.simplechat.app.net.ChatStreamLogTag
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.StreamMaxAttempts
import com.simplechat.app.net.StreamRetryDelayMs
import com.simplechat.app.net.anthropic.isRetryableStatus
import com.simplechat.app.net.httpErrorText
import com.simplechat.app.net.networkText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Responses API 的流式客户端（`POST /v1/responses`）。
 *
 * 与 [com.simplechat.app.net.SseClient] 分开：请求体由 [ResponsesPayload] 生成，
 * 流事件由 [ResponsesStreamParser] 解析。两者不共用 DTO —— Chat Completions
 * 与 Responses 的 `input` / `tools` / 事件名没有任何一处可以安全复用。
 *
 * 取消语义与其它通道一致：协程 Job 结束即 `call.cancel()`，阻塞中的
 * `readUtf8Line()` 立刻抛 IOException 退出。
 */
class ResponsesApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val parser = ResponsesStreamParser(json)

    fun stream(
        url: String,
        apiKey: String,
        payload: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Flow<StreamEvent> = flow {
        val collector = this
        val body = json.encodeToString(JsonObject.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)

        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val httpRequest = builder.build()

        var attempt = 0
        var emittedAny = false
        while (true) {
            attempt++
            val call = client.newCall(httpRequest)
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            var retryHttp = false
            try {
                call.execute().use { response ->
                    val source = response.body?.source()
                    if (!response.isSuccessful || source == null) {
                        val raw = runCatching { response.body?.string() }.getOrNull()
                        val detail = errorMessage(raw)
                        android.util.Log.w(ChatStreamLogTag, "Responses HTTP ${response.code}: $raw")
                        if (!emittedAny && attempt < StreamMaxAttempts &&
                            isRetryableStatus(response.code)
                        ) {
                            retryHttp = true
                            return@use
                        }
                        emit(
                            StreamEvent.Error(
                                message = httpErrorText(response.code, detail),
                                code = response.code,
                                retryable = isRetryableStatus(response.code),
                            ),
                        )
                        return@use
                    }

                    var done = false
                    var data = StringBuilder()
                    suspend fun dispatch() {
                        val raw = data.toString().trim()
                        data = StringBuilder()
                        if (raw.isEmpty()) return
                        val events = parser.parse(raw)
                        events.forEach { event ->
                            collector.emit(event)
                            emittedAny = true
                            if (event is StreamEvent.Done) done = true
                        }
                    }

                    while (!done) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line()
                        if (line == null) {
                            dispatch()
                            break
                        }
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith("event:") -> Unit
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trimStart())
                            }
                            /*
                             * 少数网关不按 SSE 包装，直接回一整行 JSON。识别到就
                             * 当一条事件解析，总比让用户盯着一片空白好。
                             */
                            line.trimStart().startsWith("{") -> {
                                data.append(line)
                                dispatch()
                            }
                            else -> Unit
                        }
                    }

                    if (!done) collector.emit(StreamEvent.Done)
                }
                if (retryHttp) {
                    delay(StreamRetryDelayMs)
                    continue
                }
                return@flow
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) return@flow
                if (!emittedAny && attempt < StreamMaxAttempts) {
                    android.util.Log.w(
                        ChatStreamLogTag,
                        "Responses stream reset before any event; retrying",
                        e,
                    )
                    delay(StreamRetryDelayMs)
                    continue
                }
                android.util.Log.w(ChatStreamLogTag, "Responses stream interrupted", e)
                emit(StreamEvent.Error(e.networkText(), retryable = true))
                return@flow
            } finally {
                handle?.dispose()
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 从错误响应里挖出服务端原话；没有就退回截断的原文。 */
    private fun errorMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val error = root["error"] as? JsonObject
            error?.get("message")?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw.take(200)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
