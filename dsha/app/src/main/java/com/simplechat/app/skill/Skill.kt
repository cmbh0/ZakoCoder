package com.simplechat.app.skill

import java.io.File

/**
 * 一个技能（Skill）。
 *
 * 形态就是一个目录 + 一份 `SKILL.md`：
 *
 * ```
 * files/skills/pdf-report/
 *   ├── SKILL.md          ← 元信息 + 使用说明（会进模型上下文）
 *   ├── scripts/build.py
 *   └── template.html
 * ```
 *
 * 为什么不用数据库：技能是**文件产物**，用户会想直接从电脑丢进来、想用文本编辑器
 * 改、想打包发给别人。塞进 SQLite 之后这些都做不了，还得再写一套导入导出。
 */
data class Skill(
    /** 目录名，也是唯一标识。 */
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    /** 正文（`SKILL.md` 去掉 front matter 后的部分）。启用后会进系统提示词。 */
    val body: String,
    val dir: File,
    val enabled: Boolean,
) {
    /**
     * 给模型看的摘要：只说"什么时候用"，不贴全文。
     *
     * 末尾那句 `read_skill(id=…)` 是**入口**：技能在 App 私有目录，不在工作区里，
     * `read_file` 到不了。不给这条路径，模型"知道有这个技能却打不开"。
     */
    fun promptLine(): String = "- $name：$description（用 read_skill(id=\"$id\") 读说明）"

    val hasScripts: Boolean
        get() = File(dir, "scripts").isDirectory
}

/**
 * `SKILL.md` 解析。
 *
 * 只认 `key: value` 这一种 front matter 写法 —— 不引 YAML 库（多 300 KB 体积
 * 只为读六个字段不值得），也不支持嵌套。真实技能包的 front matter 基本都是平的。
 */
object SkillParser {

    private val FrontMatter = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n", RegexOption.DOT_MATCHES_ALL)

    fun parse(id: String, dir: File, enabled: Boolean): Skill? {
        val file = File(dir, "SKILL.md").takeIf { it.isFile } ?: File(dir, "skill.md").takeIf { it.isFile }
            ?: return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val match = FrontMatter.find(raw)
        val meta = match?.groupValues?.getOrNull(1).orEmpty()
        val body = if (match != null) raw.substring(match.range.last + 1) else raw

        val fields = meta.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val name = fields["name"]?.takeIf { it.isNotBlank() }
            ?: body.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: id

        return Skill(
            id = id,
            name = name,
            description = fields["description"].orEmpty(),
            version = fields["version"].orEmpty(),
            body = body.trim(),
            dir = dir,
            enabled = enabled,
        )
    }
}
