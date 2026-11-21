package com.simplechat.app.agent

import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 内置浏览器：搜索 + 读网页。
 *
 * 这两条工具是「内置浏览器」技能包的实体。技能开关决定它们挂不挂给模型 ——
 * 关掉之后模型连这两个名字都看不到，不是"看到了但调用失败"。
 *
 * 搜索走 **Bing 的 RSS 输出**（`?format=rss`）而不是抓结果页：
 * RSS 是稳定的结构，`<item>` 里就是标题、链接、摘要；而结果页的 class 名
 * 每个季度都在变，抓它等于给自己排一个必然失败的维护任务。
 *
 * 抓网页走 OkHttp + 自研的 [HtmlExtract]，不引 Jsoup（体积换不来多少精度）。
 * 出口是手机自己的网络 —— 云端工作区不复用这里，那会把用户的出口 IP 暴露成服务器。
 */
object WebTools {

    /** 单页正文上限。再长模型也读不完，只会把上下文挤掉。 */
    private const val MaxText = 24_000

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    fun all(client: OkHttpClient): List<AgentTool> = listOf(Search(client), Open(client))

    /** 一条搜索结果。 */
    data class Hit(val title: String, val url: String, val snippet: String)

    // ── 搜索 ─────────────────────────────────────────────────

    private class Search(private val client: OkHttpClient) : AgentTool {
        override val name = "web_search"
        override val description =
            "用 Bing 搜索互联网。需要**当前**信息（新闻、版本号、报错的最新解法、某个库的文档在哪）时用它，" +
                "不要凭训练数据里的印象回答。搜索结果是标题 + 链接 + 摘要；" +
                "需要正文时再对其中一两条调 web_open。回答里要带上用到的链接。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string"); put("description", "搜索词，用自然语言或关键词都行") }
                putJsonObject("count") { put("type", "integer"); put("description", "要几条结果，默认 8，最多 20") }
            }
            putJsonArray("required") { add("query") }
        }
        override val risk = ToolRisk.NETWORK

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val query = args.str("query") ?: return ToolOutcome.fail("缺少 query")
            val count = (args.int("count") ?: 8).coerceIn(1, 20)
            val hits = runCatching { search(client, query) }
                .getOrElse { return ToolOutcome.fail("搜索失败：${it.message ?: "网络错误"}") }
            if (hits.isEmpty()) {
                return ToolOutcome.ok("「$query」没有搜到结果。换个说法、去掉引号，或直接 web_open 一个已知网址。")
            }
            val body = buildString {
                appendLine("Bing · 「$query」共 ${hits.size} 条：")
                hits.take(count).forEachIndexed { index, hit ->
                    appendLine()
                    appendLine("${index + 1}. ${hit.title}")
                    appendLine("   ${hit.url}")
                    if (hit.snippet.isNotBlank()) appendLine("   ${hit.snippet}")
                }
                append("\n要读正文就对其中一条调 web_open。")
            }
            return ToolOutcome.ok(body)
        }
    }

    // ── 读网页 ───────────────────────────────────────────────

    private class Open(private val client: OkHttpClient) : AgentTool {
        override val name = "web_open"
        override val description =
            "打开一个网页，返回它的正文（已去掉导航、脚本、广告）。用户给了链接、或 web_search 之后要读细节时用它。" +
                "返回的是正文纯文本，不是 HTML 源码。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") { put("type", "string") }
                putJsonObject("max") {
                    put("type", "integer")
                    put("description", "最多返回多少字符，默认 12000，上限 40000")
                }
            }
            putJsonArray("required") { add("url") }
        }
        override val risk = ToolRisk.NETWORK

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            val raw = args.str("url") ?: return ToolOutcome.fail("缺少 url")
            val url = normalize(raw)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ToolOutcome.fail("只支持 http/https 链接：$raw")
            }
            val limit = (args.int("max") ?: 12_000).coerceIn(500, 40_000)

            return withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@use ToolOutcome.fail("HTTP ${response.code}：${response.message}")
                        }
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        if (bytes.isEmpty()) return@use ToolOutcome.fail("页面是空的（0 字节）")
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        val charset = charsetOf(bytes, type)
                        val text = bytes.toString(charset)

                        val (title, body) = when {
                            type.contains("html") || text.trimStart().startsWith("<") -> {
                                HtmlExtract.titleOf(text) to HtmlExtract.toText(text)
                            }
                            type.contains("json") -> null to text
                            else -> null to text
                        }
                        val cleaned = body.trim()
                        if (cleaned.isEmpty()) {
                            return@use ToolOutcome.fail("页面里没有可读正文（可能是纯 JS 渲染的应用页）：$url")
                        }
                        val clipped = cleaned.length > limit
                        val output = buildString {
                            if (!title.isNullOrBlank()) appendLine("标题：$title")
                            appendLine("网址：$url")
                            appendLine("字数：${cleaned.length}")
                            appendLine()
                            append(if (clipped) cleaned.take(limit) else cleaned)
                            if (clipped) append("\n\n…（已截断，原文 ${cleaned.length} 字；需要更多就调大 max）")
                        }
                        ToolOutcome.ok(output)
                    }
                }.getOrElse { error ->
                    val reason = when (error) {
                        is IOException -> error.message ?: "网络不可达"
                        else -> error.message ?: error.javaClass.simpleName
                    }
                    ToolOutcome.fail("抓取失败：$reason（网址：$url）")
                }
            }
        }
    }

    // ── 实现细节 ─────────────────────────────────────────────

    /** 用户粘进来的链接常常没有协议头，直接补 https。 */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().removeSurrounding("<", ">")
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> trimmed
        }
    }

    /**
     * Bing RSS 搜索。
     *
     * `cn.bing.com` 与 `www.bing.com` 都试：前者在国内网络下通常更快，
     * 但部分地区会返回空 feed；后者反之。两个都空才算失败。
     */
    suspend fun search(client: OkHttpClient, query: String, count: Int = 10): List<Hit> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val candidates = listOf(
                "https://cn.bing.com/search?format=rss&count=$count&q=$encoded",
                "https://www.bing.com/search?format=rss&count=$count&q=$encoded",
            )
            var lastError: Throwable? = null
            for (url in candidates) {
                val feed = runCatching { fetchText(client, url) }
                    .onFailure { lastError = it }
                    .getOrNull()
                if (feed.isNullOrBlank()) continue
                val hits = parseRss(feed)
                if (hits.isNotEmpty()) return@withContext hits
            }
            lastError?.let { throw IOException(it.message ?: "搜索请求失败") }
            emptyList()
        }

    private fun fetchText(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return bytes.toString(charsetOf(bytes, response.header("Content-Type").orEmpty()))
        }
    }

    /** RSS 里只有三个字段有用；不用 XML 解析器，正则足够且不引依赖。 */
    fun parseRss(feed: String): List<Hit> {
        val items = Regex("(?is)<item>(.*?)</item>").findAll(feed).map { it.groupValues[1] }
        return items.mapNotNull { block ->
            val title = tagValue(block, "title") ?: return@mapNotNull null
            val link = tagValue(block, "link") ?: return@mapNotNull null
            if (link.isBlank() || !link.startsWith("http")) return@mapNotNull null
            Hit(
                title = title.trim(),
                url = link.trim(),
                snippet = tagValue(block, "description")?.trim().orEmpty(),
            )
        }.distinctBy { it.url }.toList()
    }

    private fun tagValue(block: String, tag: String): String? {
        val match = Regex("(?is)<$tag[^>]*>(.*?)</$tag>").find(block) ?: return null
        var value = match.groupValues[1].trim()
        value = value.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        return HtmlExtract.decodeEntities(value).replace(Regex("<[^>]+>"), "").trim()
    }

    /**
     * 猜编码。
     *
     * 中文站还有相当一部分是 GBK，而 OkHttp 只看 `Content-Type` 里的 charset ——
     * 头部没写时它按 UTF-8 解，结果是一整页乱码。这里读 `<meta charset>` 兜一层。
     */
    private fun charsetOf(bytes: ByteArray, contentType: String): Charset {
        val header = Regex("(?i)charset=([a-z0-9_-]+)").find(contentType)?.groupValues?.getOrNull(1)
        if (header != null) return safeCharset(header)
        val head = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1)
        val meta = Regex("(?i)<meta[^>]+charset=[\"']?([a-z0-9_-]+)").find(head)?.groupValues?.getOrNull(1)
        return meta?.let(::safeCharset) ?: Charsets.UTF_8
    }

    private fun safeCharset(name: String): Charset = runCatching { Charset.forName(name) }
        .getOrElse { Charsets.UTF_8 }
}
