package com.simplechat.app.agent

/**
 * HTML → 可读文本。
 *
 * 不引 Jsoup（多 400 KB 体积），也不追求"精确解析 DOM" —— 这里只有两个消费者：
 * `web_open` 把网页交给模型、`web_search` 把摘要结果压平。它们要的都是
 * "把导航栏和脚本扔掉，留下人话"。
 *
 * 做的四件事：
 * 1. 删掉 script / style / nav / footer / aside / form / svg / iframe；
 * 2. 标题与列表项带上 `#`、`-`，让结构在纯文本里还看得出来；
 * 3. 块级标签换行，行内标签不动（否则中文会被切成一堆碎行）；
 * 4. 反转义实体，含 `&#123;` 与 `&#x1F600;` 这两种数字形式。
 */
object HtmlExtract {

    /** 整块丢掉、内容不外泄的标签。 */
    private val DropBlocks = listOf(
        "script", "style", "noscript", "svg", "canvas", "iframe", "form",
        "nav", "header", "footer", "aside", "template",
    )

    private val TitleBlock = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>")
    private val ListItem = Regex("(?is)<li[^>]*>(.*?)</li>")
    private val BlockBreak = Regex(
        "(?i)</?(p|div|section|article|main|tr|table|ul|ol|dl|dt|dd|blockquote|pre|figure|figcaption|br|hr)\\b[^>]*>",
    )
    private val CellBreak = Regex("(?i)</t[dh]>")
    private val AllTags = Regex("<[^>]+>")
    private val ManyBlank = Regex("\n[ \t]*\n(?:[ \t]*\n)+")
    private val TrailingSpace = Regex("[ \t\u00A0]+")

    /**
     * @param keepLinks 保留 `<a href>` 的地址。给"读文档、找参考链接"用；
     *   关掉后正文更干净，但模型就引用不了来源了。
     */
    fun toText(html: String, keepLinks: Boolean = true): String {
        var text = html

        DropBlocks.forEach { tag ->
            text = text.replace(Regex("(?is)<$tag\\b.*?</$tag>"), " ")
        }
        text = text.replace(Regex("(?is)<!--.*?-->"), " ")

        // 链接：`<a href="x">y</a>` → `y (x)`。只留绝对地址，相对路径对模型没用。
        if (keepLinks) {
            text = text.replace(Regex("(?is)<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")) { match ->
                val href = match.groupValues[1].trim()
                val label = match.groupValues[2].replace(AllTags, "").trim()
                when {
                    label.isEmpty() -> " "
                    !href.startsWith("http") || label.contains(href) -> " $label "
                    else -> " $label ($href) "
                }
            }
        }

        text = TitleBlock.replace(text) { match ->
            val level = match.groupValues[1].toIntOrNull() ?: 1
            val inner = match.groupValues[2].replace(AllTags, "").trim()
            if (inner.isEmpty()) "\n" else "\n\n" + "#".repeat(level) + " " + inner + "\n"
        }
        text = ListItem.replace(text) { match ->
            val inner = match.groupValues[1].replace(AllTags, "").trim()
            if (inner.isEmpty()) "" else "\n- " + inner
        }
        text = CellBreak.replace(text, " | ")
        text = BlockBreak.replace(text, "\n")
        text = AllTags.replace(text, "")

        text = decodeEntities(text)
        text = text.replace(TrailingSpace, " ")
        text = text.lineSequence().joinToString("\n") { it.trim() }
        text = ManyBlank.replace(text, "\n\n")
        return text.trim()
    }

    /** `<title>` 里的标题，搜索/引用时用得上。 */
    fun titleOf(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.getOrNull(1)
            ?.let { decodeEntities(it).trim() }
            ?.takeIf { it.isNotEmpty() }

    private val Named = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×", "divide" to "÷", "deg" to "°", "plusmn" to "±",
        "laquo" to "«", "raquo" to "»", "bull" to "•", "dagger" to "†",
    )

    fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        var text = raw
        text = Regex("&#x([0-9a-fA-F]{1,6});").replace(text) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { code -> codePoint(code) } ?: match.value
        }
        text = Regex("&#([0-9]{1,7});").replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.let { code -> codePoint(code) } ?: match.value
        }
        text = Regex("&([a-zA-Z]{2,8});").replace(text) { match ->
            Named[match.groupValues[1].lowercase()] ?: match.value
        }
        return text
    }

    private fun codePoint(code: Int): String =
        if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
}
