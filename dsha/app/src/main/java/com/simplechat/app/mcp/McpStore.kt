package com.simplechat.app.mcp

import com.simplechat.app.agent.AgentTool
import com.simplechat.app.agent.ToolRisk
import com.simplechat.app.agent.ToolContext
import com.simplechat.app.agent.ToolOutcome
import com.simplechat.app.agent.Workspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** 持久化形态。和 [McpServerConfig] 分开，是因为磁盘格式要能长期稳定。 */
@Serializable
private data class StoredServer(
    val id: String,
    val name: String,
    val transport: String = "HTTP",
    val url: String = "",
    val command: String = "",
    val args: String = "",
    val env: String = "",
    val headers: String = "",
    val timeoutSeconds: Int = 60,
    val enabled: Boolean = true,
)

@Serializable
private data class StoredFile(val servers: List<StoredServer> = emptyList())

/** 一个服务端的连接状态，给界面显示。 */
sealed interface McpStatus {
    data object Idle : McpStatus
    data object Connecting : McpStatus
    data class Ready(val tools: Int, val serverName: String?) : McpStatus
    data class Failed(val message: String) : McpStatus
}

/**
 * MCP 服务端管理。
 *
 * 连接是**按需**的：不在启动时连所有服务端 —— 有的服务端启动要几秒，
 * 全连一遍会让冷启动变慢，而大多数对话根本不碰工具。
 * 真正第一次需要工具时才连，连上了缓存住。
 */
object McpStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpStatus>> = _status.asStateFlow()

    /** 已连接客户端缓存。key = server id。 */
    private val clients = mutableMapOf<String, McpClient>()
    private val toolCache = mutableMapOf<String, List<McpTool>>()

    /** 配置放 App 私有目录，不跟着工作区走（理由同 SkillStore）。 */
    private fun file(): File = File(Workspace.appDir(), "mcp.json")

    fun load() {
        val f = file()
        val stored = runCatching {
            if (f.isFile) json.decodeFromString(StoredFile.serializer(), f.readText()) else StoredFile()
        }.getOrDefault(StoredFile())
        _servers.value = stored.servers.map { it.toConfig() }
    }

    private fun save() {
        val payload = StoredFile(_servers.value.map { it.toStored() })
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(json.encodeToString(StoredFile.serializer(), payload))
        }
    }

    fun upsert(config: McpServerConfig) {
        val list = _servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) list[index] = config else list += config
        _servers.value = list
        save()
        // 配置变了，旧连接作废
        disconnect(config.id)
    }

    fun remove(id: String) {
        disconnect(id)
        _servers.value = _servers.value.filterNot { it.id == id }
        save()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val config = _servers.value.firstOrNull { it.id == id } ?: return
        upsert(config.copy(enabled = enabled))
    }

    fun disconnect(id: String) {
        clients.remove(id)?.close()
        toolCache.remove(id)
        _status.value = _status.value - id
    }

    fun disconnectAll() {
        clients.keys.toList().forEach { disconnect(it) }
    }

    /** 连接一个服务端（幂等）。 */
    suspend fun connect(config: McpServerConfig, http: OkHttpClient): Result<Int> = withContext(Dispatchers.IO) {
        clients[config.id]?.let { existing -> return@withContext Result.success(toolCache[config.id]?.size ?: 0) }
        _status.value = _status.value + (config.id to McpStatus.Connecting)
        val client = McpClient(config, http, Workspace.requireRoot())
        val result = client.connect().mapCatching {
            val tools = client.listTools().getOrElse { error(it.message ?: "拉取工具失败") }
            clients[config.id] = client
            toolCache[config.id] = tools
            _status.value = _status.value + (config.id to McpStatus.Ready(tools.size, client.serverName))
            tools.size
        }.onFailure {
            client.close()
            _status.value = _status.value + (config.id to McpStatus.Failed(it.message ?: "连接失败"))
        }
        result
    }

    /**
     * 试连一个尚未保存的配置。
     *
     * 和 [connect] 的区别是**不碰缓存、不改状态**：编辑器里点「测试」不该把
     * 一个还没保存的草稿塞进已连接列表，更不该让列表里的旧连接失效。
     * 返回服务端自报的名字与工具名（给用户看"这个端点到底是什么"）。
     */
    suspend fun test(
        config: McpServerConfig,
        http: OkHttpClient,
    ): Result<Pair<String?, List<String>>> = withContext(Dispatchers.IO) {
        val client = McpClient(config, http, Workspace.requireRoot())
        runCatching {
            client.connect().getOrThrow()
            val tools = client.listTools().getOrThrow()
            client.serverName to tools.map { it.name }
        }.also {
            client.close()
        }
    }

    /**
     * 把所有已启用且已连接服务端的工具包成 [AgentTool]，交给 Agent 循环。
     *
     * 工具名加 `mcp__<server>__` 前缀：不同服务端很可能都有 `search` 这种名字，
     * 不加前缀就会互相覆盖，模型还会分不清自己调的是哪个。
     */
    fun agentTools(serverIds: Set<String>? = null): List<AgentTool> = _servers.value
        .filter { it.enabled && (serverIds == null || it.id in serverIds) }
        .mapNotNull { config ->
            val client = clients[config.id] ?: return@mapNotNull null
            toolCache[config.id].orEmpty().map { tool ->
                McpToolAdapter(config, client, tool)
            }
        }
        .flatten()

    /** 已连接的服务端工具数（给设置页显示"已发现 N 个工具"）。 */
    fun toolCount(): Int = toolCache.values.sumOf { it.size }

    /**
     * 导出成标准 `mcpServers` 配置 —— 就是 [McpScreen] 那边 import 认的形态。
     *
     * 能导入就要能导出，否则一条配好的服务只能靠手抄 transfer 到另一台机器。
     * 导出的形状刻意与 Claude Desktop / Cursor 一致：不认识的键它们会忽略，
     * 但我们写的这几个是共同子集，粘过去就能用。
     */
    fun exportJson(configs: List<McpServerConfig> = _servers.value): String {
        val entries = buildJsonObject {
            val used = mutableSetOf<String>()
            configs.forEach { config ->
                // 重名会互相覆盖：第二个开始补序号，宁可名字丑也不能丢配置
                var key = config.name.ifBlank { config.id }
                if (!used.add(key)) {
                    var index = 2
                    while (!used.add("$key-$index")) index++
                    key = "$key-$index"
                }
                put(key, buildJsonObject {
                    if (config.transport == McpTransportKind.STDIO) {
                        put("command", config.command)
                        val argv = splitCommandLine(config.args)
                        if (argv.isNotEmpty()) {
                            put("args", buildJsonArray { argv.forEach { add(JsonPrimitive(it)) } })
                        }
                        val env = config.env.lineSequence()
                            .mapNotNull { line ->
                                val at = line.indexOf('=')
                                if (at <= 0) null else line.take(at).trim() to line.substring(at + 1).trim()
                            }
                            .toList()
                        if (env.isNotEmpty()) {
                            put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
                        }
                    } else {
                        put("url", config.url)
                        val headers = config.headers.lineSequence()
                            .mapNotNull { line ->
                                val at = line.indexOf(':')
                                if (at <= 0) null else line.take(at).trim() to line.substring(at + 1).trim()
                            }
                            .toList()
                        if (headers.isNotEmpty()) {
                            put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, v) } })
                        }
                    }
                    put("timeout", config.timeoutSeconds)
                })
            }
        }
        return Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("mcpServers", entries) },
        )
    }
}

/** 把 MCP 工具适配成 Agent 工具。 */
private class McpToolAdapter(
    private val config: McpServerConfig,
    private val client: McpClient,
    private val tool: McpTool,
) : AgentTool {

    override val name: String = sanitize("mcp__${config.id}__${tool.name}")

    override val description: String =
        if (tool.description.isBlank()) "来自 MCP 服务 ${config.name} 的工具" else tool.description

    override val parameters: JsonObject = tool.schema

    override val mutating: Boolean = true

    /** 第三方工具：本机无法审计它背后做什么，审批时单独成一档。 */
    override val risk = ToolRisk.MCP

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome =
        client.callTool(tool.name, args).fold(
            onSuccess = { ToolOutcome.ok(it) },
            onFailure = { ToolOutcome.fail(it.message ?: "MCP 调用失败") },
        )

    /** 工具名只允许字母数字下划线连字符，服务端 id 里可能有别的字符。 */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
}

private fun StoredServer.toConfig(): McpServerConfig = McpServerConfig(
    id = id,
    name = name,
    transport = runCatching { McpTransportKind.valueOf(transport) }.getOrDefault(McpTransportKind.HTTP),
    url = url,
    command = command,
    args = args,
    env = env,
    headers = headers,
    timeoutSeconds = timeoutSeconds,
    enabled = enabled,
)

private fun McpServerConfig.toStored(): StoredServer = StoredServer(
    id = id,
    name = name,
    transport = transport.name,
    url = url,
    command = command,
    args = args,
    env = env,
    headers = headers,
    timeoutSeconds = timeoutSeconds,
    enabled = enabled,
)

/**
 * 把配置里那串 `args` 拆回数组。
 *
 * 配置层为了好填，存的是一行文本；标准 MCP 要的是数组。不做彻底的分词器，
 * 只认两种引号 —— 覆盖 `--path "my dir"` 这种最常见的写法，其余按空白切。
 * 反斜杠转义不处理：配置界面里本来就没人写它。
 */
private fun splitCommandLine(raw: String): List<String> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    text.forEach { ch ->
        when {
            quote != null && ch == quote -> quote = null
            quote == null && (ch == '"' || ch == '\'') -> {
                quote = ch
                started = true
            }
            quote == null && ch.isWhitespace() -> {
                if (started || current.isNotEmpty()) {
                    result += current.toString()
                    current.setLength(0)
                    started = false
                }
            }
            else -> {
                current.append(ch)
                started = true
            }
        }
    }
    if (started || current.isNotEmpty()) result += current.toString()
    return result
}
