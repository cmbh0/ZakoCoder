package com.simplechat.app.ui.chat

import com.simplechat.app.mcp.McpServerConfig
import com.simplechat.app.skill.Skill

/** 能被 @ 点名的两类东西。内置工具不参与 —— 它们本来就在。 */
enum class MentionKind(val prefix: String) {
    SKILL("skill"),
    MCP("mcp"),
}

/** @ 面板里的一行。 */
data class MentionItem(
    val kind: MentionKind,
    val key: String,
    val title: String,
    val subtitle: String,
) {
    /** 落进输入框的原文。带前缀是为了让模型一眼看出这是技能还是 MCP。 */
    val token: String get() = "@${kind.prefix}:$key"
}

/** 一条被点名的引用。 */
data class MentionRef(val kind: MentionKind, val key: String)

/**
 * `@` 引用的解析与生成。
 *
 * ### 为什么带前缀
 *
 * 输入框里落的是 `@skill:pdf`、`@mcp:mcp123456`，不是光秃秃的 `@pdf`。
 * 原因有两个：技能与 MCP 的名字可能重名；更重要的是**模型能直接读懂这一行** ——
 * 不需要再解释"我 @ 的这个东西是什么"。
 *
 * ### 引用了就不用再解释
 *
 * 真正让模型"自觉调用"的不是 @ 这个符号，而是发送时附上的那段指令（见 [directive]）：
 * 点名了哪个技能、该先读它的说明；点名了哪个 MCP、该调哪一族工具。
 * 符号只是入口，提示词才是效果。
 */
object Mentions {

    /** 面板里最多列几项。再多就要滚动，而滚动时键盘还在，屏幕上放不下。 */
    const val MaxPanelItems = 6

    fun build(skills: List<Skill>, servers: List<McpServerConfig>): List<MentionItem> {
        val items = mutableListOf<MentionItem>()
        skills.filter { it.enabled }.forEach {
            items += MentionItem(
                kind = MentionKind.SKILL,
                key = it.id,
                title = it.name,
                subtitle = it.description,
            )
        }
        servers.forEach {
            items += MentionItem(
                kind = MentionKind.MCP,
                key = it.id,
                title = it.name,
                subtitle = if (it.url.isNotBlank()) it.url else it.command,
            )
        }
        return items
    }

    /**
     * 光标前正在输入的 @ 查询。
     *
     * 返回 `(at 的下标, 已输入的词)`；不在 @ 语境里返回 null。
     * 只认**行首或空白之后**的 @ —— 邮箱、代码里的注解不该弹面板。
     */
    fun query(text: String, cursor: Int): Pair<Int, String>? {
        val end = cursor.coerceIn(0, text.length)
        val before = text.substring(0, end)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        val between = before.substring(at + 1)
        // @ 之后不能再有空白：有就说明这个 @ 早结束了，用户在写别的东西
        if (between.any { it.isWhitespace() } || between.length > 32) return null
        if (at > 0 && !before[at - 1].isWhitespace()) return null
        return at to between
    }

    fun filter(items: List<MentionItem>, term: String): List<MentionItem> {
        if (term.isBlank()) return items.take(MaxPanelItems)
        val lower = term.lowercase()
        return items
            .filter { it.key.lowercase().contains(lower) || it.title.lowercase().contains(lower) }
            .take(MaxPanelItems)
    }

    private val TokenPattern =
        Regex("@(skill|mcp):([A-Za-z0-9_\\-\\u4e00-\\u9fa5.]+)")

    /** 从一条消息里抽出所有引用（去重，保持出现顺序）。 */
    fun parse(text: String): List<MentionRef> = TokenPattern.findAll(text)
        .mapNotNull { match ->
            val kind = when (match.groupValues[1]) {
                "skill" -> MentionKind.SKILL
                "mcp" -> MentionKind.MCP
                else -> null
            } ?: return@mapNotNull null
            MentionRef(kind, match.groupValues[2])
        }
        .distinct()
        .toList()

    /**
     * 拼进系统提示词的那一段。
     *
     * 只在**有引用时**才追加：没有 @ 的时候一个字都不占。
     */
    fun directive(refs: List<MentionRef>, skills: List<Skill>, servers: List<McpServerConfig>): String {
        if (refs.isEmpty()) return ""
        val lines = mutableListOf<String>()
        refs.forEach { ref ->
            when (ref.kind) {
                MentionKind.SKILL -> {
                    val skill = skills.firstOrNull { it.id == ref.key }
                    if (skill != null) {
                        lines += "- 技能「${skill.name}」（id=${skill.id}）：用户点名要用它。" +
                            "先调用 read_skill(id=\"${skill.id}\") 读它的完整说明，再按里面的步骤做。"
                    }
                }
                MentionKind.MCP -> {
                    val server = servers.firstOrNull { it.id == ref.key }
                    if (server != null) {
                        lines += "- MCP 服务「${server.name}」（id=${server.id}）：用户点名要用它。" +
                            "相关操作优先调 mcp__${server.id}__ 开头的工具，不要改用 shell 或网页抓取硬凑。"
                    }
                }
            }
        }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("## 用户点名的能力")
            appendLine("用户在这一轮消息里用 @ 点名了下面这些能力包。这是明确的偏好，不是提示：")
            lines.forEach { appendLine(it) }
            append("如果确实用不上，说一句为什么，不要装作没看见。")
        }
    }
}
