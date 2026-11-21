package com.simplechat.app.agent

import java.io.File

/**
 * 从 AI 回复正文里解析**它自己 @ 出来的**交付文件。
 *
 * ### 为什么不再从工具调用推
 *
 * 上一版把 `write_file` / `edit_file` 的每个落盘路径都算成产物：模型改一个
 * 配置文件、写一段临时脚本，末尾就长出一排文件条。用户的原话是"必须是他
 * 自己艾特出来"——产物是**交付**，不是**副作用**。改过什么文件，工具行里
 * 本来就写着；只有模型明确说"这个给你"的那几个，才该出现在产物条上。
 *
 * ### 认什么样
 *
 * ```
 * @notes/report.md       ✅ 相对工作区的路径
 * @report.md             ✅ 只有文件名
 * @/tmp/out.png          ✅ 前导斜杠按工作区根解析（与工具同一套规则）
 * foo@bar.com            ❌ 邮箱：`@` 前是字母
 * icon@2x.png            ❌ 同上，`@` 前是字母
 * @Composable            ❌ 没有扩展名，是代码里的注解
 * ```
 *
 * 只认"像文件"的 token：必须带扩展名（1–8 位字母数字）；带 `://` 的一律
 * 丢掉（那是链接，不是产物）。解析结果按出现顺序去重。
 *
 * ### 为什么不在流式期间解析
 *
 * 模型写一段就 @ 一段时，产物条会一层层长出来、把正文往下顶。调用方只在
 * 消息结束后取 [parse] 的结果（见 `Message.artifacts`）。
 */
object Artifacts {

    /**
     * `@` 前面不能是 ASCII 字母 / 数字 / 下划线 / 点 / 连字符 —— 挡掉邮箱、
     * 图片倍率（`@2x`）、以及 `file@name` 这类粘在一起的标识符。
     *
     * 但**允许中文直接贴**（"产物在@notes/x.md"）：中文写作不留空格很常见，
     * 要求必须空格会把一半的 @ 漏掉。
     */
    private val Mention = Regex(
        """(?<![A-Za-z0-9_.\-])@([^\s@`"'()\[\]{}<>，。、；：！？「」『』（）*|\\]+)""",
    )

    /**
     * @param root 工作区根。默认取当前绑定；未绑定时返回空列表。
     * @param remote 会话绑在**云端**工作区：产物在远端，本地没有对应文件，
     *   所以只按"像不像文件路径"认，不做存在性校验。返回的是工作区相对路径。
     */
    fun parse(
        content: String,
        root: File? = Workspace.rootOrNull(),
        remote: Boolean = false,
    ): List<String> {
        if (content.indexOf('@') < 0) return emptyList()
        if (!remote && root == null) return emptyList()
        val seen = LinkedHashSet<String>()
        Mention.findAll(content).forEach { match ->
            val token = match.groupValues[1].trimEnd('.', ',', ';', ':', '!', '?', '。', '，')
            if (!looksLikeFile(token)) return@forEach
            if (remote) {
                seen += token.trimStart('/')
            } else {
                val file = resolve(root!!, token) ?: return@forEach
                seen += file.path
            }
        }
        return seen.toList()
    }

    /**
     * 把正文里**已经被认成产物**的那些 `@路径` 抹掉。
     *
     * 消息末尾已经挂了一排文件卡片，正文里再留一遍 `@notes/report.md`，
     * 就是同一件事说两遍 —— 用户的原话是"艾特的那段文本不应该还在"。
     *
     * 只删**能解析到真实文件**的 token：人名、邮箱、`@Composable`、`icon@2x.png`
     * 这些一个都不动（判据与 [parse] 完全一致，两边不会各认一套）。
     *
     * 删除之后顺手收尾，避免留下"空壳行"：
     * - 一行里只剩被删的 token 和标点 → 整行丢掉，不留空行；
     * - 行内残留的连续空格压成一个；
     * - 连续空行压成一个，首尾空行去掉。
     *
     * **流式期间不要调用**：文件可能还没落盘，这一刻删掉、下一刻又冒出来，会闪。
     */
    fun strip(
        content: String,
        root: File? = Workspace.rootOrNull(),
        remote: Boolean = false,
    ): String {
        if (content.indexOf('@') < 0) return content
        if (!remote && root == null) return content
        val removed = ArrayList<String>()
        Mention.findAll(content).forEach { match ->
            val token = match.groupValues[1].trimEnd('.', ',', ';', ':', '!', '?', '。', '，')
            if (looksLikeFile(token) && (remote || resolve(root!!, token) != null)) {
                removed += match.value
            }
        }
        if (removed.isEmpty()) return content

        val kept = ArrayList<String>()
        /*
         * 逐行在原串上处理，**不能用 [replaced]**：那一版里 token 已经被
         * 换成空串，`contains(raw)` 永远为假，"这行被动过"就判不出来 ——
         * 空格不会压、只剩标点的残行也不会被丢掉。
         */
        content.lines().forEach { line ->
            var current = line
            var touched = false
            removed.forEach { raw ->
                if (current.contains(raw)) {
                    current = current.replace(raw, "")
                    touched = true
                }
            }
            if (touched) {
                /*
                 * 模型常用 `**@a.md**` 或 `` `@a.md` `` 包路径。路径一删，
                 * 就只剩一对空星号 / 空反引号 —— 不收拾的话正文里会留下
                 * `产物：****` 这种东西。
                 */
                current = current.replace(EmptyMarks, "").replace(Spaces, " ").trimEnd()
                val trimmed = current.trim()
                // 只剩标点 / 星号 / 冒号的残行，整行丢掉
                if (trimmed.isEmpty() || trimmed.none { it.isLetterOrDigit() }) return@forEach
                current = trimmed
            }
            kept += current
        }

        val out = StringBuilder(content.length)
        var blank = 0
        kept.forEach { line ->
            if (line.isBlank()) {
                blank++
                if (blank == 1 && out.isNotEmpty()) out.append('\n')
            } else {
                blank = 0
                out.append(line).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    /** 行内连续空格 / 制表符。 */
    private val Spaces = Regex("[ \\t]{2,}")

    /** 路径被删后剩下的空强调标记：`****`、`` `` ``。 */
    private val EmptyMarks = Regex("(\\*{2,}|`{2,})")

    /** 必须有一个像扩展名的尾巴；带协议的当链接处理。 */
    private fun looksLikeFile(token: String): Boolean {
        if (token.length < 3) return false
        if (token.contains("://")) return false
        val name = token.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return false
        val ext = name.substring(dot + 1)
        if (ext.length > 8) return false
        return ext.all { it.isLetterOrDigit() }
    }

    /**
     * 与 `Workspace.resolve` 同一套规则：前导 `/` 也按工作区根解析，越界丢弃。
     *
     * 这里不直接用 `Workspace.resolve`，是为了让 [parse] 能接收显式的 root ——
     * 单元测试不需要先初始化全局绑定。
     */
    private fun resolve(root: File, token: String): File? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val cleaned = token.removePrefix("file://").trim()
        if (cleaned.isEmpty()) return null
        val candidate = if (cleaned.startsWith("/")) {
            File(base, cleaned.trimStart('/'))
        } else {
            File(base, cleaned)
        }
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val target = canonical.path
        if (target != base.path && !target.startsWith(base.path + File.separator)) return null
        return canonical
    }
}
