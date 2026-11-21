package com.simplechat.app.mcp

import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** MCP 服务的连接方式。 */
enum class McpTransportKind { HTTP, STDIO }

data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransportKind,
    /** HTTP 方式：完整的 endpoint。 */
    val url: String = "",
    /** STDIO 方式：启动命令（交给 sh -c）。 */
    val command: String = "",
    /** STDIO 方式：命令参数，按 shell 规则拼在 command 后面。 */
    val args: String = "",
    /** STDIO 方式：环境变量，形如 `KEY=VALUE`，一行一个。 */
    val env: String = "",
    /** HTTP 额外头，形如 `Authorization: Bearer xxx`，一行一个。 */
    val headers: String = "",
    /** 单次请求 / 工具调用超时（秒）。标准客户端默认 60，慢服务可以调大。 */
    val timeoutSeconds: Int = 60,
    val enabled: Boolean = true,
)

/** 一个从服务端发现的工具。 */
data class McpTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

/**
 * MCP 客户端（JSON-RPC 2.0 / Streamable HTTP / stdio）。
 *
 * 只实现三件让模型能干活的事：`initialize` / `tools/list` / `tools/call`。
 * resources、prompts、sampling 都不做 —— 它们要额外的界面（选资源、填提示），
 * 而这里只需要"模型能调外部工具"。
 *
 * ### 这里对齐的是协议，不是某个 SDK
 *
 * 上一版有几个地方是"能跑但不对"，换一个严格实现的服务端就废：
 *
 * 1. **没有 `Mcp-Session-Id`**。Streamable HTTP 从 2025-03-26 起，服务端在
 *    `initialize` 的响应头里下发会话 id，之后**每个**请求都要带回去；不带，
 *    严格的实现直接 404 / 400。
 * 2. **没有 `MCP-Protocol-Version` 头**。2025-06-18 起，除 initialize 之外的
 *    每个 HTTP 请求都必须带这个头，值是协商出来的版本。
 * 3. **SSE 只取最后一条 `data:`**。一条响应里可能先来一条 notification、再来
 *    真正的 result；取"最后一条"纯属碰运气。现在按 JSON-RPC 的 `id` 匹配。
 * 4. **`tools/list` 不翻页**。带 `nextCursor` 的服务端只会给出第一页，
 *    工具少了模型就会说"没有这个功能"。
 *
 * ### 两种传输
 *
 * - **HTTP（Streamable）**：POST 一条 JSON-RPC；响应可能是 `application/json`，
 *   也可能是 `text/event-stream`。通知（`notifications/…`）按规范应返回 202，
 *   没有响应体 —— 这一条以前会被当成空响应直接丢掉。
 * - **STDIO**：起子进程，一行一条 JSON。
 */
class McpClient(
    private val config: McpServerConfig,
    private val http: OkHttpClient,
    private val workDir: File,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private var nextId = 1
    private val lock = Mutex()

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    /** 服务端下发的会话 id（HTTP 才有）。 */
    @Volatile
    private var sessionId: String? = null

    /** 协商出来的协议版本。服务端可能回一个比我们新的版本，照它的来。 */
    @Volatile
    private var protocolVersion: String = ProtocolVersion

    @Volatile
    var serverName: String? = null
        private set

    @Volatile
    var serverVersion: String? = null
        private set

    /** 建立连接并完成握手。失败抛异常，由上层显示成一行红字。 */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(timeoutMs()) {
                handshake()
            }
        }
    }

    /** 握手本身抽出来，给上面那层超时包住。 */
    private suspend fun handshake() {
        run {
            if (config.transport == McpTransportKind.STDIO) startProcess()
            val result = call(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", ProtocolVersion)
                    /*
                     * 能力声明。我们只用工具，所以只声明 roots（让服务端知道
                     * 有个"工作区"这个概念），不声明 sampling —— 声明了却
                     * 不支持，服务端可能真的发 `sampling/createMessage` 过来。
                     */
                    putJsonObject("capabilities") {
                        putJsonObject("roots") { put("listChanged", false) }
                    }
                    putJsonObject("clientInfo") {
                        put("name", "DSHA")
                        put("version", "1.4.0")
                    }
                },
            )
            val info = result["serverInfo"]?.jsonObject
            serverName = info?.get("name")?.jsonPrimitive?.contentOrNullSafe()
            serverVersion = info?.get("version")?.jsonPrimitive?.contentOrNullSafe()
            result["protocolVersion"]?.jsonPrimitive?.contentOrNullSafe()?.let { protocolVersion = it }
            // 握手之后必须发一条 notification，否则严格的实现会拒绝后续请求
            notify("notifications/initialized", buildJsonObject { })
            Unit
        }
    }

    /**
     * 拉工具清单，**翻完所有页**。
     *
     * `nextCursor` 是规范里的分页游标：一个服务端工具多的时候（几十上百个）
     * 只会给一页，剩下的要靠游标继续要。不翻页的表现很隐蔽 —— 连接成功、
     * 工具只剩前 20 个，用户以为"这个服务没有那个工具"。
     */
    suspend fun listTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        runCatching {
            val collected = mutableListOf<McpTool>()
            var cursor: String? = null
            var guard = 0
            do {
                val params = buildJsonObject {
                    cursor?.let { put("cursor", it) }
                }
                val result = call("tools/list", params)
                val array = result["tools"] as? JsonArray
                array?.forEach { element ->
                    val obj = element as? JsonObject ?: return@forEach
                    val name = obj["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@forEach
                    collected += McpTool(
                        name = name,
                        description = obj["description"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                        schema = obj["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
                    )
                }
                cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
                guard++
            } while (cursor != null && guard < 20)
            collected
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val result = call(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
            /*
             * 标准形态是 `content: [{type:"text", text:"..."}]`。此外：
             * - `isError: true` 表示工具自己失败了（不是协议错误），要让模型看见；
             * - 有些实现直接回一个对象（structuredContent），没有 content 数组。
             */
            val content = result["content"] as? JsonArray
            val text = content?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                obj["text"]?.jsonPrimitive?.contentOrNullSafe()
            }?.joinToString("\n")
            val body = text?.takeIf { it.isNotBlank() } ?: result.toString()
            if (result["isError"]?.jsonPrimitive?.contentOrNullSafe() == "true") {
                error(body)
            }
            body
        }
    }

    fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        process = null
        // 规范建议客户端显式结束会话；失败无所谓，服务端会自己过期
        val session = sessionId
        if (config.transport == McpTransportKind.HTTP && !session.isNullOrBlank()) {
            runCatching {
                val request = Request.Builder()
                    .url(config.url)
                    .delete()
                    .header("Mcp-Session-Id", session)
                    .build()
                http.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                    }
                })
            }
        }
        sessionId = null
    }

    // ── JSON-RPC ─────────────────────────────────────────────

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = send(payload.toString(), id)
        val obj = json.parseToJsonElement(response) as? JsonObject
            ?: error("响应不是 JSON 对象")
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val message = error["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "未知错误"
            val code = error["code"]?.jsonPrimitive?.contentOrNullSafe()
            error("$method 失败：$message" + if (code != null) "（$code）" else "")
        }
        return obj["result"] as? JsonObject ?: buildJsonObject { }
    }

    private suspend fun notify(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        // 通知没有 id，也就没有响应体。失败不抛：规范里它本来就是 fire-and-forget
        runCatching { send(payload.toString(), null) }
    }

    private suspend fun send(payload: String, expectedId: Int?): String = lock.withLock {
        when (config.transport) {
            McpTransportKind.HTTP -> sendHttp(payload, expectedId)
            McpTransportKind.STDIO -> sendStdio(payload, expectedId)
        }
    }

    private suspend fun sendHttp(payload: String, expectedId: Int?): String = withTimeout(timeoutMs()) {
        val builder = Request.Builder()
            .url(config.url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            // 两个都声明：服务端可以回一份普通 JSON，也可以回一条 SSE 流
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion)
        sessionId?.takeIf { it.isNotBlank() }?.let { builder.header("Mcp-Session-Id", it) }
        parseHeaders().forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.build()).execute().use { response ->
            // 会话 id 只在 initialize 的响应里下发，之后每个响应都可能刷新
            response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

            val body = response.body?.string().orEmpty()

            if (response.code == 404 && sessionId != null) {
                // 会话过期。清掉，让上层重连一次（界面会显示"连接失败"，再点连接即可）
                sessionId = null
                error("会话已过期（HTTP 404）。已清除会话，请重新点一次「连接」。")
            }
            if (!response.isSuccessful) error("HTTP ${response.code}：${body.take(200)}")

            // 通知：规范要求 202 Accepted 且无响应体
            if (expectedId == null) return@withTimeout ""

            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("text/event-stream", true) || looksLikeSse(body)) {
                return@withTimeout parseSse(body, expectedId)
            }
            if (body.isBlank()) error("服务端返回了空响应（HTTP ${response.code}）")
            body
        }
    }

    /**
     * 从 SSE 里挑出**这次请求**的那条响应。
     *
     * 一条流里可能是：先是 `notifications/message`（服务端日志），然后才是
     * `id: 3` 的 result。按 id 匹配是唯一可靠的做法。
     */
    private fun parseSse(body: String, expectedId: Int): String {
        val events = mutableListOf<String>()
        val data = StringBuilder()
        body.lineSequence().forEach { line ->
            when {
                line.isBlank() -> {
                    if (data.isNotEmpty()) {
                        events += data.toString()
                        data.setLength(0)
                    }
                }

                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
                // event: / id: / retry: / 注释行都不影响载荷
                else -> Unit
            }
        }
        if (data.isNotEmpty()) events += data.toString()

        var fallback: String? = null
        events.forEach { raw ->
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@forEach
            val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe()
            if (id == expectedId.toString()) return raw
            if (obj.containsKey("result") || obj.containsKey("error")) fallback = raw
        }
        return fallback ?: error("SSE 响应里没有 id=$expectedId 的应答")
    }

    private fun looksLikeSse(body: String): Boolean =
        body.startsWith("event:") || body.startsWith("data:") || body.contains("\ndata:")

    private suspend fun sendStdio(payload: String, expectedId: Int?): String = withTimeout(timeoutMs()) {
        val out = writer ?: error("进程未启动")
        out.write(payload)
        out.write("\n")
        out.flush()
        if (expectedId == null) return@withTimeout ""
        val input = reader ?: error("进程未启动")
        /*
         * 跳过服务端主动推的通知（没有 id 的行），只取 id 对得上的那条。
         *
         * 写成 `while (true) { ... return@withTimeout line }` 是不行的：编译器把
         * 整个 lambda 的最后表达式看作这个循环，推断成 Unit，与声明的 String 冲突。
         * 用 break + 循环外的 return 就清楚了。
         */
        var answer: String? = null
        while (answer == null) {
            val line = input.readLine() ?: error("服务进程已退出")
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe()
            if (id == expectedId.toString() && (obj.containsKey("result") || obj.containsKey("error"))) {
                answer = line
            }
        }
        answer
    }

    private fun startProcess() {
        val command = config.command.ifBlank { error("STDIO 需要填写启动命令") }
        val args = config.args.trim()
        val full = if (args.isEmpty()) command else "$command $args"
        val builder = ProcessBuilder("/system/bin/sh", "-c", full)
            .directory(workDir)
            .redirectErrorStream(false)
        /*
         * 环境变量按行读，只接受 `KEY=VALUE` 形式。
         *
         * 标准 MCP 配置里的 env 是 JSON 对象；这里用同一套语义的纯文本形态，
         * 粘贴 npm / Python 服务的配置时一行一个，肉眼能核对。
         */
        parseEnv().forEach { (key, value) -> builder.environment()[key] = value }
        val proc = builder.start()
        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
    }

    /** 超时毫秒。夹在 5–300 秒：太短会把慢服务误判成坏，太长会让界面干等。 */
    private fun timeoutMs(): Long =
        config.timeoutSeconds.coerceIn(5, 300).toLong() * 1_000L

    private fun parseEnv(): Map<String, String> = config.env.lineSequence()
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1)
        }
        .filter { it.first.isNotBlank() }
        .toMap()

    private fun parseHeaders(): Map<String, String> = config.headers.lineSequence()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .toMap()

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    companion object {
        /**
         * 我们实现的协议版本。
         *
         * 2025-06-18 是 Streamable HTTP 的现行版本（会话 id + 版本头）。
         * 服务端如果只认更老的版本，会在 initialize 的 result 里回它自己的版本，
         * 我们以它为准 —— 后续请求的 `MCP-Protocol-Version` 头就带那个值。
         */
        const val ProtocolVersion = "2025-06-18"
    }
}
