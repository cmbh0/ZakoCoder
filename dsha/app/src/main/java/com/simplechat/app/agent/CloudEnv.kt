package com.simplechat.app.agent

import android.content.Context
import com.simplechat.app.data.SecretBox
import com.simplechat.app.net.ChatApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个云端工作环境。
 *
 * 第三种工作区形态：不装 Ubuntu、不占存储，把工作区放在一台**公网机器**上，
 * 通过 HTTP 读写文件、执行命令。适合手机上只想"给 AI 一块地方干活"的人。
 *
 * [baseUrl] 可能带 `?sharecode=...` 这类查询串（某些平台的公网网关用它鉴权），
 * 拼接口时必须把路径插在 host 后面、查询串保留在末尾 —— 直接字符串相加会变成
 * `/?sharecode=x/exec`，网关判成链接不完整。
 */
@Serializable
data class CloudEnv(
    val id: String,
    val name: String,
    /** HTTP 工作区服务的根地址。SSH 环境下为空。 */
    val baseUrl: String,
    /** HTTP 工作区服务的令牌。SSH 环境下为空。 */
    val token: String,
    val addedAt: Long = 0L,
    /**
     * 云端环境的接入方式：
     *
     * - [KIND_HTTP]：自建/托管的 HTTP 工作区服务（`cloud-workspace.zip` 那套）。
     * - [KIND_SSH]：直接 SSH 连一台 Linux 服务器。不需要在服务器上装任何东西，
     *   客户端把执行走 exec 通道、文件走 SFTP —— 能力与 HTTP 那套完全一致。
     *
     * 老版本存下来的 JSON 没有这个字段，反序列化时落到默认值 `http`，
     * 已配置的环境不会因为升级而失效。
     */
    val kind: String = KIND_HTTP,
    /** SSH 主机（域名或 IP）。 */
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** [AUTH_PASSWORD] 或 [AUTH_KEY]。 */
    val auth: String = AUTH_PASSWORD,
    /** 密码或私钥正文。 */
    val secret: String = "",
    /** 私钥口令（没有就留空）。 */
    val passphrase: String = "",
    /** 远端工作目录。空 = 登录用户的 home。 */
    val workdir: String = "",
    /**
     * 落盘时的令牌密文（[SecretBox]）。
     *
     * 内存里用的永远是 [token] 明文；写进 SharedPreferences 前换成这一份，
     * 读回来再解。Keystore 里的密钥不可导出，比裸存 JSON 强一档。
     * 加密失败（极少数机器没有可用 Keystore）时退回明文，不丢配置。
     */
    val tokenEnc: String = "",
    /** 落盘时的密码 / 私钥密文，规则同 [tokenEnc]。 */
    val secretEnc: String = "",
) {
    val isSsh: Boolean get() = kind == KIND_SSH

    /** 只用于展示的连接目标（去掉查询串里的凭据）。 */
    val hostLabel: String
        get() = if (isSsh) {
            val who = user.ifBlank { "root" }
            "$who@${host.trim().ifBlank { "?" }}:$port"
        } else {
            baseUrl.substringBefore('?').removePrefix("https://").removePrefix("http://").trimEnd('/')
        }

    companion object {
        const val KIND_HTTP = "http"
        const val KIND_SSH = "ssh"
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }
}

/**
 * 云端环境仓库。
 *
 * 两件事：
 * 1. **环境列表**（可增删）—— 落在 SharedPreferences 的 JSON 里，同步读，简单可靠。
 * 2. **会话绑定**（`conversationId -> envId`）—— 每个对话最多绑一个环境。
 *
 * 为什么按会话而不是全局：不同话题常常是不同项目。全局绑一个，切话题就得手动换，
 * 忘了换就会出现"让它改 A 项目的文件，它动了 B"——这种错误用户很难第一时间发现。
 * 一对一映射天然排除了"串环境"。
 */
object CloudEnvs {

    private const val PrefsName = "cloud_envs"
    private const val KeyList = "list"
    private const val KeyBind = "bind"
    private const val KeyPending = "pending"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val listSerializer = ListSerializer(CloudEnv.serializer())

    private var context: Context? = null

    private val _list = MutableStateFlow<List<CloudEnv>>(emptyList())
    val list: StateFlow<List<CloudEnv>> = _list.asStateFlow()

    /** `conversationId -> envId`。UI 需要跟着变，所以也是一个 Flow。 */
    private val _binding = MutableStateFlow<Map<String, String>>(emptyMap())
    val binding: StateFlow<Map<String, String>> = _binding.asStateFlow()

    /**
     * 「还没发过言的这段对话」先选好的环境。
     *
     * 绑定是按会话存的，而会话行要到第一条消息发出时才建 —— 早先的做法是
     * 在面板里直接拒绝（"先发一条消息才能绑定云端"），用户得先随便说一句话
     * 才能让 AI 在云上干活，这是一条纯属实现细节的门槛。
     *
     * 现在改成**待定槽**：没有会话行时先记在这里，[applyPending] 在会话第一次
     * 落库时把它搬成正式绑定。语义上等于"草稿对话也有工作区"。
     */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun init(app: Context) {
        context = app.applicationContext
        val prefs = app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        _list.value = runCatching {
            prefs.getString(KeyList, null)?.let { json.decodeFromString(listSerializer, it) }
        }.getOrNull().orEmpty().map(::decodeSecrets)
        _binding.value = runCatching {
            val raw = prefs.getString(KeyBind, null)
            if (raw.isNullOrBlank()) {
                emptyMap()
            } else {
                val obj = JSONObject(raw)
                obj.keys().asSequence().associateWith { key -> obj.optString(key) }
            }
        }.getOrElse { emptyMap() }
        _pending.value = prefs.getString(KeyPending, null)?.takeIf { it.isNotBlank() }
    }

    fun add(
        name: String,
        baseUrl: String,
        token: String,
        kind: String = CloudEnv.KIND_HTTP,
        host: String = "",
        port: Int = 22,
        user: String = "",
        auth: String = CloudEnv.AUTH_PASSWORD,
        secret: String = "",
        passphrase: String = "",
        workdir: String = "",
    ): CloudEnv {
        val env = CloudEnv(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.ifBlank { if (kind == CloudEnv.KIND_SSH) "SSH 服务器" else "云端环境" },
            baseUrl = if (kind == CloudEnv.KIND_SSH) "" else normalize(baseUrl),
            token = if (kind == CloudEnv.KIND_SSH) "" else token.trim(),
            addedAt = System.currentTimeMillis(),
            kind = if (kind == CloudEnv.KIND_SSH) CloudEnv.KIND_SSH else CloudEnv.KIND_HTTP,
            host = host.trim(),
            port = port.takeIf { it in 1..65535 } ?: 22,
            user = user.trim(),
            auth = if (auth == CloudEnv.AUTH_KEY) CloudEnv.AUTH_KEY else CloudEnv.AUTH_PASSWORD,
            secret = secret,
            passphrase = passphrase,
            workdir = workdir.trim(),
        )
        _list.value = _list.value + env
        persistList()
        return env
    }

    fun remove(id: String) {
        _list.value = _list.value.filterNot { it.id == id }
        persistList()
        // 连接池里那条会话也要断掉：环境没了，端口和凭据都不该再挂着
        RemoteWorkspaces.forget(id)
        // 环境没了，绑定必须一起清 —— 留着会得到一个"指向不存在"的会话
        _binding.value = _binding.value.filterValues { it != id }
        persistBinding()
        if (_pending.value == id) setPending(null)
    }

    fun find(id: String?): CloudEnv? = _list.value.firstOrNull { it.id == id }

    /** 当前会话绑定的环境；没绑就是 null（走本地工作区）。 */
    fun envOf(conversationId: String?): CloudEnv? =
        conversationId?.let { _binding.value[it] }?.let(::find)

    /** 待定环境。没选过就是 null。 */
    fun pendingEnv(): CloudEnv? = _pending.value?.let(::find)

    /** 选一个"等下发给这条新对话"的环境；传 null 清掉。 */
    fun setPending(envId: String?) {
        _pending.value = envId?.takeIf { id -> _list.value.any { it.id == id } }
        val editor = context?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)?.edit() ?: return
        val id = _pending.value
        if (id == null) editor.remove(KeyPending) else editor.putString(KeyPending, id)
        editor.apply()
    }

    /**
     * 会话第一次落库时把待定绑定搬过去。
     *
     * 返回搬过去的那个环境（没搬就是 null）—— 调用方据此决定要不要再补一次
     * "当前工作区在云上"的落盘。
     */
    fun applyPending(conversationId: String): CloudEnv? {
        val env = pendingEnv() ?: run {
            // 待定的环境被删了：槽位留着没意义，顺手清掉
            if (_pending.value != null) setPending(null)
            return null
        }
        bind(conversationId, env.id)
        setPending(null)
        return env
    }

    fun bind(conversationId: String, envId: String) {
        _binding.value = _binding.value + (conversationId to envId)
        persistBinding()
    }

    fun unbind(conversationId: String) {
        _binding.value = _binding.value - conversationId
        persistBinding()
    }

    /**
     * 规整用户粘进来的地址。
     *
     * 只做三件必要的事：补协议、去尾部斜杠、去掉误粘的 `/exec`。
     * **不动查询串** —— 那里面常常就是访问凭据，动一下链接就打不开了。
     */
    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
        val query = value.substringAfter('?', "")
        var host = value.substringBefore('?').trimEnd('/')
        for (suffix in listOf("/exec", "/fs", "/fs/list", "/fs/read", "/fs/write", "/health")) {
            if (host.endsWith(suffix)) {
                host = host.removeSuffix(suffix)
                break
            }
        }
        return if (query.isEmpty()) host else "$host?$query"
    }

    private fun persistList() {
        val prefs = context?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) ?: return
        runCatching {
            val safe = _list.value.map(::encodeSecrets)
            prefs.edit().putString(KeyList, json.encodeToString(listSerializer, safe)).apply()
        }
    }

    /**
     * 写盘前把凭据换成密文。
     *
     * 只加密"凭据"两样：令牌、密码/私钥。名字、地址、工作目录保持明文 ——
     * 用户要能在备份和日志里认出来这是哪台机器，凭据才需要藏。
     */
    private fun encodeSecrets(env: CloudEnv): CloudEnv {
        val tokenEnc = env.token.takeIf { it.isNotBlank() }
            ?.let { runCatching { SecretBox.encrypt(it) }.getOrNull() }
        val secretEnc = env.secret.takeIf { it.isNotBlank() }
            ?.let { runCatching { SecretBox.encrypt(it) }.getOrNull() }
        return env.copy(
            token = if (tokenEnc == null) env.token else "",
            tokenEnc = tokenEnc.orEmpty(),
            secret = if (secretEnc == null) env.secret else "",
            secretEnc = secretEnc.orEmpty(),
        )
    }

    /** 读盘后解密。老数据里 secret/token 本来就是明文，原样保留即可。 */
    private fun decodeSecrets(env: CloudEnv): CloudEnv {
        val token = env.token.ifBlank {
            runCatching { SecretBox.decrypt(env.tokenEnc) }.getOrDefault("")
        }
        val secret = env.secret.ifBlank {
            runCatching { SecretBox.decrypt(env.secretEnc) }.getOrDefault("")
        }
        return env.copy(token = token, secret = secret, tokenEnc = "", secretEnc = "")
    }

    private fun persistBinding() {
        val prefs = context?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) ?: return
        val obj = JSONObject()
        _binding.value.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KeyBind, obj.toString()).apply()
    }
}

/** 云端返回的一条目录项。 */
data class RemoteEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

/** 一页文件内容。[startLine] 是 1 起。 */
data class RemotePage(
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int,
)

/** 一条内容搜索命中。`before`/`after` 是请求了 context 时才有的上下文行。 */
data class RemoteGrepMatch(
    val path: String,
    val line: Int,
    val text: String,
    val before: List<String> = emptyList(),
    val after: List<String> = emptyList(),
)

/**
 * `/fs/grep` 的结果。
 *
 * [files] 是"哪个文件命中几条"的汇总 —— 先给模型看这个，它才知道该读哪个，
 * 而不是从两百条扁平行里自己数。这和 rg 的输出形态一致。
 */
data class RemoteGrepResult(
    val matches: List<RemoteGrepMatch> = emptyList(),
    val files: List<Pair<String, Int>> = emptyList(),
    val scanned: Int = 0,
    val truncated: Boolean = false,
    val ms: Int = 0,
)

/** `/fs/patch` 的一处改动。`all=true` 表示替换所有出现处。 */
data class RemoteHunk(val old: String, val new: String, val all: Boolean = false)

/** `/exec` 的结果。`code != 0` 不代表请求失败 —— 命令跑完了，只是自己退非零。 */
data class RemoteExecResult(
    val stdout: String,
    val stderr: String,
    val code: Int,
    val cwd: String,
    /** 这条命令是不是以 root 跑的。 */
    val elevated: Boolean = false,
)

/**
 * 云端机器的自我介绍。
 *
 * 让模型在第一轮就知道"这台机器上有什么、能不能提权"，而不是靠 `which` 一个个试。
 * 每次对话只取一次，缓存在 [RemoteExec] 的实例里。
 */
data class RemoteSystem(
    val root: Boolean = false,
    val user: String = "",
    val sudo: Boolean = false,
    val sudoReason: String = "",
    val distro: String = "",
    val kernel: String = "",
    val python: String = "",
    val tools: Map<String, String> = emptyMap(),
    /**
     * 服务端自报的能力。旧版服务没有这一项 —— 客户端据此决定
     * "要不要给模型挂 search_content 这些工具"，而不是挂上去再吃 404。
     */
    val capabilities: Set<String> = emptySet(),
) {
    /** 一段给模型看的短说明。太长会挤占上下文，这里只列结论。 */
    fun describe(): String = buildString {
        append(distro.ifBlank { "Linux" })
        if (kernel.isNotBlank()) append("（内核 $kernel）")
        append("，用户 ").append(user.ifBlank { "?" })
        append(if (root) "，当前已是 root" else if (sudo) "，可用 sudo 提权" else "，无法提权")
        if (tools.isNotEmpty()) {
            append("\n可用命令：").append(tools.keys.sorted().joinToString(" "))
        }
        if (!root && !sudo && sudoReason.isNotBlank()) {
            append("\n提权不可用的原因：").append(sudoReason)
        }
    }
}

/**
 * 云端工作环境的 HTTP 客户端。
 *
 * 只认 [CloudEnv] 里的 baseUrl + token，其余什么都不假设：
 * 服务端可以是自建 VPS、托管的容器服务、或者任何跑得动
 * `python3 server.py` 的地方 —— 只要有一个常驻的 HTTPS 根地址。
 */
/**
 * 一片可以读写的远端工作区。
 *
 * 两种实现：
 * - [RemoteExec]：HTTP 工作区服务（`cloud-workspace.zip`）。
 * - [SshWorkspace]：SSH + SFTP 直连一台 Linux。
 *
 * 工具层只认这个接口 —— 模型看到的世界不该因为"云是 HTTP 还是 SSH"而改变。
 * 方法集合与 HTTP 服务端点对点对应，换实现不影响任何一个工具的参数与文案。
 */
interface RemoteWorkspace {

    val env: CloudEnv

    suspend fun ping(): Boolean

    suspend fun exec(
        cmd: String,
        cwd: String? = null,
        timeoutSec: Int = 300,
        sudo: Boolean = false,
    ): RemoteExecResult

    suspend fun sysInfo(): RemoteSystem?

    suspend fun list(path: String): List<RemoteEntry>

    suspend fun read(path: String, maxChars: Int = 400_000): String

    suspend fun readPage(path: String, offset: Int, limit: Int): RemotePage

    suspend fun write(path: String, content: String, append: Boolean = false): List<DiffLine>

    suspend fun edit(path: String, old: String, new: String): List<DiffLine>

    suspend fun grep(
        pattern: String,
        path: String = ".",
        glob: String? = null,
        regex: Boolean = true,
        caseSensitive: Boolean = false,
        max: Int = 200,
        context: Int = 0,
    ): RemoteGrepResult

    suspend fun glob(pattern: String, path: String = ".", max: Int = 300): List<RemoteEntry>

    suspend fun patch(path: String, hunks: List<RemoteHunk>): List<DiffLine>

    suspend fun tree(path: String = ".", depth: Int = 2): String

    suspend fun install(kind: String, packages: List<String>, mirror: String = "tuna"): Pair<Boolean, String>

    suspend fun upload(
        dir: String,
        name: String,
        bytes: ByteArray,
        onProgress: ((Float) -> Unit)? = null,
    ): Long

    suspend fun uploadFile(
        dir: String,
        name: String,
        file: File,
        onProgress: ((Float) -> Unit)? = null,
    ): Long

    suspend fun download(path: String): ByteArray

    suspend fun remove(path: String)

    suspend fun mkdir(path: String)

    suspend fun exists(path: String): Boolean

    /** 释放长连接。HTTP 实现是空操作。 */
    fun close() {}
}

/**
 * 按接入方式挑实现，并且**按环境复用**。
 *
 * SSH 建一次连接要几百毫秒，而一轮对话里工具会被调用十几次 —— 每次新建一条
 * TCP + 密钥交换，手感就是"每步都卡一下"。这里按 `env.id` 缓存实例，
 * 实例内部自己维持会话与重连。环境被删除时 [forget] 会把它关掉。
 */
object RemoteWorkspaces {

    private val cache = HashMap<String, RemoteWorkspace>()

    fun of(env: CloudEnv): RemoteWorkspace = synchronized(cache) {
        cache.getOrPut(env.id) {
            if (env.isSsh) SshWorkspace(env) else RemoteExec(env)
        }
    }

    fun forget(envId: String) {
        val gone = synchronized(cache) { cache.remove(envId) }
        runCatching { gone?.close() }
    }
}

class RemoteExec(override val env: CloudEnv) : RemoteWorkspace {

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 超过这个大小就不再 gzip 上传。
         *
         * 4 MB 以上基本都是二进制（图片、视频、APK、压缩包），WAF 的文本规则
         * 本来也匹配不到；而压一份几百 MB 的文件既要临时空间又要 CPU，
         * 手机上是实打实的负担。
         */
        private const val GZIP_UNDER = 4L * 1024 * 1024

        /**
         * exec 的超时给 330s：服务端自己 300s 就 504 了，客户端必须比它**更晚**放弃 ——
         * 否则先断的是我们这边，用户看到的是"网络错误"而不是"命令超时"，查错方向全反。
         */
        private val client: OkHttpClient by lazy {
            ChatApi.defaultClient().newBuilder()
                .readTimeout(330, TimeUnit.SECONDS)
                .build()
        }
    }

    /** 服务端公开的探活接口，不需要凭据。 */
    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/")).get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use false
                /*
                 * 顺手记下服务端的能力。`envelope` 是 v4 才有的字段，它同时
                 * 代表两件事：请求能装信封、上传能 gzip。
                 *
                 * 老服务端没有这个字段 —— 那就全走明文、传原始字节。这不是
                 * "降级体验"，是**不写坏文件**：v3 收到 gzip 上传会原样落盘，
                 * 用户拿到一个打不开的包，而且全程没有任何报错。
                 */
                val caps = runCatching { JSONObject(text).optJSONArray("envelope") }.getOrNull()
                uploadGzip = caps != null
                capsKnown = true
                if (caps == null) envelope = false
                true
            }
        }.getOrDefault(false)
    }

    /** 上传前确保知道服务端能力 —— 直接上传而不看版本，会把文件写坏。 */
    private suspend fun ensureCaps() {
        if (capsKnown) return
        ping()
    }

    /**
     * 执行一条命令。
     *
     * [sudo] 为真时服务端会用 root 身份跑（本身是 root 就直接跑，否则 `sudo -n`）。
     * 没有免密 sudo 时服务端返回 403 —— 这里会抛出去，由工具层翻成人话，
     * 而不是让模型以为"命令跑了只是没输出"。
     */
    override suspend fun exec(
        cmd: String,
        cwd: String?,
        timeoutSec: Int,
        sudo: Boolean,
    ): RemoteExecResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("cmd", cmd)
            cwd?.let { put("cwd", it) }
            put("timeout", timeoutSec)
            if (sudo) put("sudo", true)
        }
        val text = post("/exec", body.toString())
        val obj = JSONObject(text)
        RemoteExecResult(
            stdout = obj.optString("stdout"),
            stderr = obj.optString("stderr"),
            code = obj.optInt("exit_code", 0),
            cwd = obj.optString("cwd", cwd.orEmpty()),
            elevated = obj.optBoolean("elevated", false),
        )
    }

    /** 环境自述。失败不抛 —— 拿不到就退化成"未知"，不该挡住干活。 */
    override suspend fun sysInfo(): RemoteSystem? = withContext(Dispatchers.IO) {
        runCatching {
            val obj = JSONObject(post("/sys/info", "{}"))
            val tools = obj.optJSONObject("tools")
            RemoteSystem(
                root = obj.optBoolean("root"),
                user = obj.optString("user"),
                sudo = obj.optBoolean("sudo"),
                sudoReason = obj.optString("sudo_reason"),
                distro = obj.optString("distro"),
                kernel = obj.optString("kernel"),
                python = obj.optString("python"),
                tools = tools?.let { map ->
                    map.keys().asSequence().associateWith { map.optString(it) }
                }.orEmpty(),
                capabilities = obj.optJSONArray("capabilities")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
                }.orEmpty(),
            )
        }.getOrNull()
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val text = post("/fs/list", JSONObject().put("path", path).toString())
        val array = JSONObject(text).optJSONArray("entries") ?: JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            RemoteEntry(
                name = item.optString("name"),
                isDir = item.optBoolean("dir"),
                size = item.optLong("size"),
                mtime = item.optLong("mtime"),
            )
        }
    }

    override suspend fun read(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val text = post(
            "/fs/read",
            JSONObject().put("path", path).put("max", maxChars).toString(),
        )
        JSONObject(text).optString("content")
    }

    /**
     * 按行读一页。
     *
     * 切分交给服务端：一个 3000 行的文件传全量再本地切，在手机上要多花
     * 一次完整的下行 —— 而模型通常只要其中几十行。
     */
    override suspend fun readPage(path: String, offset: Int, limit: Int): RemotePage =
        withContext(Dispatchers.IO) {
            val text = post(
                "/fs/read",
                JSONObject()
                    .put("path", path)
                    .put("offset", offset)
                    .put("limit", limit)
                    .toString(),
            )
            val obj = JSONObject(text)
            RemotePage(
                content = obj.optString("content"),
                startLine = obj.optInt("start_line", offset),
                endLine = obj.optInt("end_line", offset),
                totalLines = obj.optInt("total_lines", 0),
            )
        }

    override suspend fun write(path: String, content: String, append: Boolean): List<DiffLine> =
        withContext(Dispatchers.IO) {
            val text = post(
                "/fs/write",
                JSONObject()
                    .put("path", path)
                    .put("content", content)
                    .put("encoding", "utf-8")
                    .put("append", append)
                    .toString(),
            )
            parseDiff(JSONObject(text).optJSONArray("diff"))
        }

    /**
     * 服务端一体化编辑：读 → 校验唯一 → 替换 → 写，一次往返。
     *
     * 客户端分两步做同样的事要 **两次 RTT + 两次全文传输**，云端 RTT 是这里的
     * 主要成本。这条路径是"像本地一样顺"的关键。
     */
    override suspend fun edit(path: String, old: String, new: String): List<DiffLine> =
        withContext(Dispatchers.IO) {
            val text = post(
                "/fs/edit",
                JSONObject().put("path", path).put("old", old).put("new", new).toString(),
            )
            parseDiff(JSONObject(text).optJSONArray("diff"))
        }

    /**
     * 内容搜索。
     *
     * 为什么不交给模型拼 `grep -rn`：GNU 与 busybox 的参数不一致，写错时返回
     * 0 行，模型会当成"确实没有"然后开始凭印象改代码。走端点则要么有结果、
     * 要么有一句明确的报错。
     */
    override suspend fun grep(
        pattern: String,
        path: String,
        glob: String?,
        regex: Boolean,
        caseSensitive: Boolean,
        max: Int,
        context: Int,
    ): RemoteGrepResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pattern", pattern)
            .put("path", path)
            .put("regex", regex)
            .put("case", caseSensitive)
            .put("max", max)
            .put("context", context)
        glob?.takeIf { it.isNotBlank() }?.let { body.put("glob", it) }
        val obj = JSONObject(post("/fs/grep", body.toString()))

        val array = obj.optJSONArray("matches") ?: JSONArray()
        val matches = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RemoteGrepMatch(
                path = item.optString("path"),
                line = item.optInt("line"),
                text = item.optString("text"),
                before = item.optJSONArray("before").toStringList(),
                after = item.optJSONArray("after").toStringList(),
            )
        }
        val fileArray = obj.optJSONArray("files") ?: JSONArray()
        val files = (0 until fileArray.length()).mapNotNull { index ->
            val item = fileArray.optJSONObject(index) ?: return@mapNotNull null
            item.optString("path") to item.optInt("count")
        }
        RemoteGrepResult(
            matches = matches,
            files = files,
            scanned = obj.optInt("files_scanned"),
            truncated = obj.optBoolean("truncated"),
            ms = obj.optInt("ms"),
        )
    }

    /** 按双星号通配符找文件。零层目录也能匹配：双星号加斜杠的写法会命中根目录的 `a.py`。 */
    override suspend fun glob(pattern: String, path: String, max: Int): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val text = post(
                "/fs/glob",
                JSONObject().put("pattern", pattern).put("path", path).put("max", max).toString(),
            )
            val array = JSONObject(text).optJSONArray("paths") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val relative = item.optString("path")
                RemoteEntry(
                    name = relative,
                    isDir = false,
                    size = item.optLong("size"),
                    mtime = 0L,
                )
            }
        }

    /** 多处改动一次落盘。任意一处失败则整批不写，不会留下改了一半的文件。 */
    override suspend fun patch(path: String, hunks: List<RemoteHunk>): List<DiffLine> =
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            hunks.forEach { hunk ->
                array.put(
                    JSONObject()
                        .put("old", hunk.old)
                        .put("new", hunk.new)
                        .put("all", hunk.all),
                )
            }
            val text = post(
                "/fs/patch",
                JSONObject().put("path", path).put("hunks", array).toString(),
            )
            parseDiff(JSONObject(text).optJSONArray("diff"))
        }

    /** 浅层目录树，给模型一个"这个项目长什么样"的总体印象。 */
    override suspend fun tree(path: String, depth: Int): String = withContext(Dispatchers.IO) {
        val text = post(
            "/fs/tree",
            JSONObject().put("path", path).put("depth", depth).toString(),
        )
        val array = JSONObject(text).optJSONArray("lines") ?: JSONArray()
        (0 until array.length()).joinToString("\n") { array.optString(it) }
    }

    /** 装依赖。返回 (是否成功, 日志尾巴)。 */
    override suspend fun install(kind: String, packages: List<String>, mirror: String): Pair<Boolean, String> =        withContext(Dispatchers.IO) {
            val array = JSONArray().apply { packages.forEach { put(it) } }
            val raw = runCatching {
                post(
                    "/sys/install",
                    JSONObject()
                        .put("kind", kind)
                        .put("packages", array)
                        .put("mirror", mirror)
                        .toString(),
                )
            }
            raw.fold(
                onSuccess = { text ->
                    val obj = JSONObject(text)
                    obj.optBoolean("ok") to obj.optString("log")
                },
                onFailure = { error ->
                    // 500 会抛出来，但响应体里的 log 才是真正的原因，尽量捞回来
                    false to (error.message ?: "安装失败")
                },
            )
        }

    /**
     * 把一段字节传上去。
     *
     * 为什么是**原始字节**而不是 JSON + base64：图片、APK、压缩包动辄几十 MB，
     * base64 要多带 33%，而手机流量与内存都吃紧。服务端在 `/fs/upload` 上
     * 直接流式落盘，只从 URL 上取目标目录与文件名（都已 URL 编码）。
     *
     * [onProgress] 在写入网络流时按块回调 —— 大文件上传没有进度条的话，
     * 用户不知道是在传还是卡死了。
     */
    override suspend fun upload(
        dir: String,
        name: String,
        bytes: ByteArray,
        onProgress: ((Float) -> Unit)?,
    ): Long = withContext(Dispatchers.IO) {
        ensureCaps()
        /*
         * 小文件先压一遍。
         *
         * WAF 拦的是**文本里的代码形状**（`print(1+1)` 就够触发），不拦二进制；
         * 压过之后它没得匹配，顺便还省一趟流量。大文件是图片 / 视频 / APK，
         * 本来就是二进制、压缩收益小、内存代价大 —— 原样发。
         */
        val useGzip = uploadGzip && bytes.size <= GZIP_UNDER
        val payload = if (useGzip) gzipBytes(bytes) else bytes
        var sent = 0L
        val total = payload.size.toLong().coerceAtLeast(1L)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = payload.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                val chunk = 64 * 1024
                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(chunk, payload.size - offset)
                    sink.write(payload, offset, size)
                    offset += size
                    sent += size
                    onProgress?.invoke((sent.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        sendUpload(dir, name, body, useGzip, bytes.size.toLong())
    }

    /**
     * 真正发上传请求。
     *
     * [compressed] 只影响一个请求头 —— 服务端据此决定要不要解压。
     */
    private fun sendUpload(
        dir: String,
        name: String,
        body: RequestBody,
        compressed: Boolean,
        fallbackSize: Long,
    ): Long {
        val builder = Request.Builder()
            .url(url("/fs/upload", mapOf("dir" to dir, "name" to name)))
            .post(body)
            .header("Authorization", "Bearer ${env.token}")
            .header("X-Exec-Token", env.token)
        if (compressed) builder.header("Content-Encoding", "gzip")
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(describe(response.code, text))
            return runCatching { JSONObject(text).optLong("size") }.getOrDefault(fallbackSize)
        }
    }

    private fun gzipBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * 上传一个**磁盘上的文件**（不经过内存）。
     *
     * 给附件用：视频动辄几百 MB，先读成 `ByteArray` 会把手机的堆直接撑爆。
     * 进度回调按 OkHttp 写入的字节估算 —— 只用于界面提示，不保证精确。
     */
    override suspend fun uploadFile(
        dir: String,
        name: String,
        file: File,
        onProgress: ((Float) -> Unit)?,
    ): Long = withContext(Dispatchers.IO) {
        ensureCaps()
        val useGzip = uploadGzip && file.length() <= GZIP_UNDER
        val source = if (useGzip) gzipToTemp(file) ?: file else file
        try {
            val total = file.length().coerceAtLeast(1L)
            var sent = 0L
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = source.length()

                override fun writeTo(sink: BufferedSink) {
                    source.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            sent += read
                            onProgress?.invoke((sent.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            sendUpload(dir, name, body, useGzip, total)
        } finally {
            if (source !== file) runCatching { source.delete() }
        }
    }

    /**
     * 把文件压到一份临时文件。
     *
     * 不压到内存：上传的东西可能是一段录屏或一个数据集，几十上百 MB 读进堆
     * 就是一次 OOM。压缩失败时返回 null，调用方原样发原始文件 ——
     * 多一次被网关拦的风险，总好过整个上传失败。
     */
    private fun gzipToTemp(file: File): File? = runCatching {
        val temp = File.createTempFile("dsha-upload-", ".gz")
        GZIPOutputStream(temp.outputStream().buffered()).use { gzip ->
            file.inputStream().use { input -> input.copyTo(gzip) }
        }
        temp
    }.getOrNull()

    /** 把云端的文件拉回手机（二进制）。 */
    override suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("/fs/download", mapOf("path" to path)))
            .get()
            .header("Authorization", "Bearer ${env.token}")
            .header("X-Exec-Token", env.token)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(describe(response.code, response.body?.string().orEmpty()))
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 删一个文件或空目录。 */
    override suspend fun remove(path: String) = withContext(Dispatchers.IO) {
        post("/fs/remove", JSONObject().put("path", path).toString())
        Unit
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun parseDiff(array: JSONArray?): List<DiffLine> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = when (item.optString("t")) {
                "+" -> DiffLine.Kind.ADD
                "-" -> DiffLine.Kind.REMOVE
                else -> DiffLine.Kind.SAME
            }
            DiffLine(kind, item.optString("s"))
        }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        post("/fs/mkdir", JSONObject().put("path", path).toString())
        Unit
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = post("/fs/stat", JSONObject().put("path", path).toString())
            JSONObject(text).optBoolean("exists")
        }.getOrDefault(false)
    }

    /**
     * 传输信封：把请求体压成 gzip + base64，让公网网关**没东西可匹配**。
     *
     * ### 为什么非做不可
     *
     * 公网网关（阿里云 WAF）按明文匹配请求体，规则粗到不讲道理：
     *
     * | 请求体 | 结果 |
     * |---|---|
     * | `hello world` | 200 |
     * | `deadbeef` | 200 |
     * | `import q as Q` | 200 |
     * | **`print(1+1)`** | **403** |
     *
     * 只要出现"标识符(参数+参数)"这个形状，就被判成命令注入。AI 写一行再
     * 正常不过的 Python 都会被打回来，而它收到的是一页 HTML，只能猜成
     * "没权限"，然后加 `sudo`、把命令拆短、包成 .sh —— 越改越像攻击。
     *
     * 鉴权已经在前面做完了（sharecode + token），这一层不该再管内容。
     * 所以把整个 JSON 压成 `{"_e":"gz64","d":"<base64>"}`：网关看到的是一串
     * 随机字符，什么规则都不命中；服务端解开后拿到的还是原来那个 dict。
     *
     * ### 三种情况的处理
     *
     * 1. **服务端 v4+**：全程走信封，一次往返。
     * 2. **服务端 v3**（没这层）：第一次会 400，就地关掉开关退回明文，
     *    之后不再试 —— 旧服务不会因为客户端升级而不可用。
     * 3. **明文被网关拦（403）**：立刻换信封重发一次。服务端支持就自愈，
     *    不支持就还是原来的错误。用户不需要知道这中间发生了什么。
     */
    @Volatile
    private var envelope = true

    /**
     * 上传要不要 gzip。**默认关**，只有服务端自报支持时才开。
     *
     * 与 [envelope] 不同，这个不能靠"试一次看报错"来降级：不认
     * `Content-Encoding: gzip` 的老服务端会把压缩字节**当成文件内容**原样写盘，
     * 返回 200，然后用户得到一个打不开的文件 —— 没有报错，只有损坏。
     * 所以宁可保守：先探活，服务端说支持才压。
     */
    @Volatile
    private var uploadGzip = false

    @Volatile
    private var capsKnown = false

    private class Reply(val code: Int, val text: String, val raw: String)

    private fun post(path: String, body: String): String {
        var wrap = envelope
        var reply = send(path, body, wrap)

        /*
         * 信封被 400 打回来：**不看文案，直接明文重发一次**。
         *
         * 早先只认"信封"/"请求体非法"两种字样 —— 那是 v4 服务端的说法。
         * 实测 v3 服务端收到信封不会说这两个词，它把 `{"_e":...}` 当成正常
         * payload，然后在业务校验里报「cmd 必须是非空字符串」「kind 只能是
         * apt / pip / npm」。客户端认不出这句话，就一直拿信封撞墙，
         * 用户看到的是"AI 连命令都发不出去"。
         *
         * 400 本身就是"请求体不合法"，信封是这里唯一可疑的东西 ——
         * 明文再发一次，成本只有一次往返；对 v4 服务端，真参数错误会得到
         * 同一条 400（错误信息还更准确），不会掩盖问题。
         */
        if (wrap && reply.code == 400) {
            envelope = false
            wrap = false
            reply = send(path, body, false)
        }

        /*
         * 明文被网关拦了：换信封再来一次。
         *
         * 这一步不看版本、不做协商 —— 因为网关拦不拦跟服务端版本无关，
         * 而多花的那一次往返只在真被拦的时候发生。
         */
        if (!wrap && reply.code in setOf(403, 405, 406, 451) && looksLikeGatewayBlock(reply.raw)) {
            val retry = send(path, body, true)
            if (retry.code in 200..299) {
                envelope = true
                return retry.text
            }
        }

        if (reply.code !in 200..299) {
            throw IOException(describe(reply.code, reply.raw))
        }
        return reply.text
    }

    private fun send(path: String, body: String, wrap: Boolean): Reply {
        val payload = if (wrap) pack(body) else body
        val builder = Request.Builder()
            .url(url(path))
            .post(payload.toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer ${env.token}")
            // 公网网关会用自己的 JWT 覆盖 Authorization，服务端因此还认这个可透传的头
            .header("X-Exec-Token", env.token)
        if (wrap) builder.header("X-DSHA-Envelope", "1")

        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            return Reply(response.code, if (wrap) unpack(raw) else raw, raw)
        }
    }

    private fun pack(body: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val encoded = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return JSONObject().put("_e", "gz64").put("d", encoded).toString()
    }

    private fun unpack(text: String): String {
        if (text.isBlank() || !text.startsWith("{")) return text
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return text
        if (obj.optString("_e") != "gz64") return text
        val bytes = runCatching {
            android.util.Base64.decode(obj.optString("d"), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return text
        return runCatching {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { text }
    }

    /** 状态码翻成人话 —— 模型拿到"401"只会瞎猜，拿到"令牌不对"才知道该改什么。 */
    private fun describe(code: Int, body: String): String = when (code) {
        401 -> "云端环境拒绝了凭据（401）：令牌不对或已失效。"
        403, 405, 406, 451 -> if (looksLikeGatewayBlock(body)) {
            /*
             * 走到这里说明**信封也没绕过去**：请求体已经压成 base64，网关还能
             * 拦，只剩三种可能 —— 请求体超过它的单次上限、URL 里带了它不认的
             * 字符、或者触发了频次限制。
             *
             * 不要再教模型"把命令改短、拆成 .sh"：那是加信封之前的老办法，
             * 现在只会让它把一条正常的命令拆成五条，越拆越慢，最后还是被拦。
             */
            "这次请求被公网网关拦下了（$code）。请求已经封装重发过一次、仍然被拦，" +
                "说明不是内容规则的问题，多半是：体积太大（超过网关单次上限）、" +
                "短时间请求太多、或者地址里带了它不认的字符。" +
                "把单步操作改小一点、隔几秒再试；传大文件请用上传接口而不是 write_file。"
        } else {
            "云端拒绝了这次操作（$code）：${body.take(200).ifBlank { "没有权限" }}"
        }
        404 -> "云端地址上找不到这个接口（404）：确认填的是服务根地址，不是某个子路径。"
        502, 503, 504 -> "云端服务暂时不可达（$code）：可能正在启动，稍后重试。"
        else -> "云端返回 $code：${body.take(200).ifBlank { "无内容" }}"
    }

    /**
     * 这个响应是不是网关的拦截页。
     *
     * 判据是"像 HTML"而不是精确匹配某家厂商的错误码 —— 阿里云、腾讯云、
     * Cloudflare 的拦截页各不相同，但都带 `<html`、并且正文里会出现
     * blocked / firewall / 拦截 之类的词。JSON 接口本身不会返回这些。
     */
    private fun looksLikeGatewayBlock(body: String): Boolean {
        if (body.isBlank()) return false
        val head = body.take(1200)
        val html = head.contains("<html", ignoreCase = true) ||
            head.contains("<title", ignoreCase = true) ||
            head.contains("<!DOCTYPE", ignoreCase = true)
        if (!html) return false
        return Regex(
            "waf|firewall|blocked|forbidden|security|拦截|阻断|安全|风控|访问被拒",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(head)
    }

    /**
     * 拼接口地址。
     *
     * 路径插在 host 后面，**查询串留在末尾**。这是这类公网网关的硬规矩：
     * `base + "/exec"` 会把 `?sharecode=ABC` 变成 `?sharecode=ABC/exec`，
     * 凭据被污染，网关直接拦成"链接不完整"。
     *
     * [params] 追加在原有查询串之后（用 `&`），同样不破坏凭据。
     */
    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val host = env.baseUrl.substringBefore('?').trimEnd('/')
        val query = env.baseUrl.substringAfter('?', "")
        val parts = buildList {
            if (query.isNotEmpty()) add(query)
            params.forEach { (key, value) ->
                add("$key=" + java.net.URLEncoder.encode(value, "UTF-8"))
            }
        }
        val tail = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        return host + path + tail
    }
}
