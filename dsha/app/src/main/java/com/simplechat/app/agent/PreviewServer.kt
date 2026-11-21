package com.simplechat.app.agent

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * 预览内容从哪儿来。
 *
 * 本地与云端各一份实现，服务端只认这五个方法 —— 页面里的相对链接
 * （`./style.css`、`fetch('data.json')`、跳转到 `page2.html`）因此
 * 在两种工作区里行为一致，这就是"串联"的全部含义。
 */
interface PreviewSource {

    /** 界面上显示的名字（目录名 / 云环境名）。 */
    val label: String

    /** 缓存键：同一个 root 复用同一个服务实例，换了 root 才重启。 */
    val key: String

    /** 读一个文件的全部字节；不存在 / 不是文件 / 太大时返回 null。 */
    fun read(path: String): ByteArray?

    /** 列目录；不是目录或读不到时返回 null。 */
    fun list(path: String): List<PreviewEntry>?

    /** 给界面显示"根目录"用的短路径。 */
    fun rootLabel(): String
}

data class PreviewEntry(val name: String, val dir: Boolean)

/** 工作区里的一个真实目录。 */
class LocalPreviewSource(private val root: File) : PreviewSource {

    override val label: String = root.name.ifBlank { root.path }

    override val key: String = "local:" + runCatching { root.canonicalPath }.getOrDefault(root.path)

    override fun read(path: String): ByteArray? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        if (file.length() > MaxFileBytes) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    override fun list(path: String): List<PreviewEntry>? {
        val dir = resolve(path) ?: return null
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.name != "." && it.name != ".." }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { PreviewEntry(it.name, it.isDirectory) }
    }

    override fun rootLabel(): String = runCatching { Workspace.relative(root) }
        .getOrDefault(root.name)
        .ifBlank { root.name }

    /**
     * 路径越界一律拒绝。
     *
     * 这是整个预览服务唯一的攻击面：页面里一个 `../../../data/data/...`
     * 就能把 App 私有目录读出去。`canonicalFile` 之后再比前缀，
     * 软链接与 `..` 都在这一步被拍平。
     */
    private fun resolve(path: String): File? {
        val clean = path.trim().trimStart('/')
        if (clean.contains('\u0000')) return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(base, clean).canonicalFile }.getOrNull() ?: return null
        val basePath = base.path
        val targetPath = target.path
        if (targetPath != basePath && !targetPath.startsWith(basePath + File.separator)) return null
        return target
    }

    companion object {
        /** 单个文件上限 48 MB。HTML 项目里很少有更大的文件；视频预览另走系统播放器。 */
        const val MaxFileBytes = 48L * 1024 * 1024
    }
}

/**
 * 云端工作环境。
 *
 * 每次请求都走一次 `/fs/download` —— 不缓存。开发预览最需要的是"改完刷新就看见"，
 * 缓存一份旧内容比慢一点更让人恼火。
 */
class CloudPreviewSource(
    private val remote: RemoteWorkspace,
    override val label: String,
    /** 云环境 id。用它当缓存键，两个同名环境不会串到同一个服务上。 */
    envId: String = label,
) : PreviewSource {

    override val key: String = "cloud:$envId"

    override fun read(path: String): ByteArray? = runBlocking {
        val clean = path.trim().trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return@runBlocking null
        runCatching { remote.download(clean) }.getOrNull()?.takeIf { it.size <= 48 * 1024 * 1024 }
    }

    override fun list(path: String): List<PreviewEntry>? = runBlocking {
        val clean = path.trim().trimStart('/').ifEmpty { "." }
        runCatching { remote.list(clean) }
            .getOrNull()
            ?.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
            ?.map { PreviewEntry(it.name, it.isDir) }
    }

    override fun rootLabel(): String = "(云端) $label"
}

/**
 * 本地预览服务。
 *
 * 一个跑在 `127.0.0.1` 上的极小 HTTP 服务器：
 *
 * ```
 * GET /index.html          → 文件
 * GET /                    → index.html，没有就列目录
 * GET /assets/app.js       → 按扩展名给 Content-Type
 * ```
 *
 * 为什么不用 `file://`：现代 WebView 对 `file://` 的限制越来越多（fetch、
 * 模块脚本、跨目录相对路径都会被 CORS 拦），而"HTML 里 fetch 一个本地 json"
 * 是最常见的写法。给它一个真正的 http 源，浏览器就按正常网页对待。
 *
 * 只绑 loopback：同一 Wi-Fi 下的别人扫不到端口。用户要分享是另一个功能，
 * 不该由"预览"顺手打开。
 */
object PreviewServer {

    private var running: Running? = null

    /** 打开（或复用）一个服务。换了来源会先停掉旧的。 */
    @Synchronized
    fun open(source: PreviewSource): Running {
        running?.let { if (it.source.key == source.key) return it }
        running?.stop()
        return Running(source).also {
            it.start()
            running = it
        }
    }

    /** 停止当前服务（退出预览页时调用；下一次打开会重新起一个）。 */
    @Synchronized
    fun stop() {
        running?.stop()
        running = null
    }

    class Running(val source: PreviewSource) {

        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newFixedThreadPool(4)
        private val alive = AtomicBoolean(true)

        val port: Int = socket.localPort
        val baseUrl: String = "http://127.0.0.1:$port/"

        fun urlFor(path: String): String =
            baseUrl + path.trimStart('/').split('/').joinToString("/") { encodeSegment(it) }

        fun start() {
            val thread = Thread({
                while (alive.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { serve(client) }
                }
            }, "dsha-preview")
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            if (!alive.compareAndSet(true, false)) return
            runCatching { socket.close() }
            pool.shutdownNow()
        }

        private fun serve(client: Socket) {
            client.use { socket ->
                runCatching {
                    socket.soTimeout = 15_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = BufferedOutputStream(socket.getOutputStream())

                    val requestLine = readLine(input) ?: return
                    // 把剩余请求头读掉（最多 100 行）：不读会让某些客户端以为连接坏了
                    var guard = 0
                    while (guard++ < 100) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                    }

                    val parts = requestLine.split(' ')
                    if (parts.size < 2) return respond(output, 400, "text/plain; charset=utf-8", "Bad Request".toByteArray())
                    val method = parts[0].uppercase()
                    if (method != "GET" && method != "HEAD") {
                        return respond(output, 405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray())
                    }

                    val rawTarget = parts[1]
                    val rawPath = rawTarget.substringBefore('?').substringBefore('#')
                    val path = decodePath(rawPath)

                    if (path == null) {
                        return respond(output, 400, "text/plain; charset=utf-8", "Bad path".toByteArray())
                    }

                    val rel = path.trimStart('/')

                    /*
                     * 目录请求：先试 index.html，没有就列目录。
                     *
                     * 列目录不是"顺手加的"：一个没写 index 的项目（比如只有
                     * `dist/` 下一堆散页）如果只回 404，用户会以为预览坏了。
                     */
                    val body = if (rel.isEmpty() || path.endsWith('/')) {
                        val index = source.read(rel.trimEnd('/') + "/index.html".trimStart('/'))
                        if (index != null) index else directoryPage(rel)
                    } else {
                        source.read(rel) ?: directoryPage(rel)
                    }

                    if (body == null) {
                        return respond(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                    }

                    val mime = if (rel.isEmpty() || path.endsWith('/')) "text/html; charset=utf-8" else mimeOf(rel)
                    respond(output, 200, mime, body, headOnly = method == "HEAD")
                }.onFailure {
                    runCatching { client.close() }
                }
            }
        }

        /** 目录列表；不是目录时返回 null。 */
        private fun directoryPage(rel: String): ByteArray? {
            val entries = source.list(rel) ?: return null
            val base = if (rel.isEmpty()) "" else rel.trimEnd('/') + "/"
            val title = "/" + rel.trimEnd('/')
            val html = buildString {
                append("<!doctype html><meta charset=\"utf-8\">")
                /*
                 * 目录页必须带 <base>：用户可能访问的是 `/dist`（没有尾斜杠），
                 * 浏览器会把页面里的 `style.css` 解析成 `/style.css` 而不是 `/dist/style.css`。
                 * 一个标签就把这类"样式怎么没了"的玄学解释清楚。
                 */
                if (base.isNotEmpty()) {
                    append("<base href=\"").append(encodePath(base)).append("\">")
                }
                append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                append("<title>").append(escape(title)).append("</title>")
                append("<style>")
                append("body{font:15px -apple-system,system-ui,sans-serif;margin:0;padding:18px 16px 40px;color:#1d1d1f;background:#fff}")
                append("h1{font-size:16px;font-weight:600;margin:0 0 14px;color:#6b6b70;word-break:break-all}")
                append("a{display:flex;gap:10px;align-items:center;padding:11px 4px;border-bottom:1px solid #f0f0f2;color:#1d1d1f;text-decoration:none}")
                append("a:active{background:#f5f5f7}.n{flex:1;word-break:break-all}.d{color:#4a7dff;font-weight:600}")
                append("</style><h1>").append(escape(title)).append("</h1>")
                if (rel.isNotEmpty()) {
                    append("<a href=\"").append(encodePath(parentOf(rel))).append("\"><span class=\"n\">../</span></a>")
                }
                entries.forEach { entry ->
                    val href = encodePath(base + entry.name + if (entry.dir) "/" else "")
                    append("<a href=\"").append(href).append("\">")
                    append("<span class=\"n\">").append(escape(entry.name)).append(if (entry.dir) "/" else "").append("</span>")
                    if (entry.dir) append("<span class=\"d\">›</span>")
                    append("</a>")
                }
            }
            return html.toByteArray(Charsets.UTF_8)
        }

        private fun respond(
            output: OutputStream,
            code: Int,
            mime: String,
            body: ByteArray,
            headOnly: Boolean = false,
        ) {
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Cache-Control: no-store, max-age=0\r\n")
                // 页面内 fetch 自己的 json / 字体时要跨源；预览源本来只在本机
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            if (!headOnly) output.write(body)
            output.flush()
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
    }

    // ── 工具函数 ────────────────────────────────────────────

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return buffer.takeIf { it.isNotEmpty() }?.toString()
            if (byte == '\n'.code) break
            if (byte == '\r'.code) continue
            if (buffer.length > 8192) return null
            buffer.append(byte.toChar())
        }
        return buffer.toString()
    }

    /** URL 解码 + 基本校验。不解码 `..`，交给 source 的 canonical 检查。 */
    private fun decodePath(raw: String): String? {
        if (raw.length > 2048) return null
        val decoded = runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }.getOrNull() ?: return null
        if (decoded.any { it.code < 0x20 }) return null
        if (decoded.contains('\\')) return null
        return decoded
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun parentOf(rel: String): String {
        val trimmed = rel.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "" else trimmed.substring(0, index + 1)
    }

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val mimeTypes = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "xhtml" to "application/xhtml+xml",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/plain; charset=utf-8",
        "csv" to "text/plain; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "bmp" to "image/bmp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "pdf" to "application/pdf",
        "wasm" to "application/wasm",
        "zip" to "application/zip",
    )

    fun mimeOf(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return mimeTypes[ext] ?: "application/octet-stream"
    }

    /** 这个路径该不该用预览页打开（而不是文本编辑器）。 */
    fun isPreviewable(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext == "html" || ext == "htm" || ext == "xhtml"
    }
}
