package com.simplechat.app.ui.chat

import com.simplechat.app.net.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词的拼接规则。
 *
 * 预设是**会话级**的：用哪几条写在 `ConversationSettings.enabledPresetIds`
 * 里，拼接顺序却由预设列表决定（不是勾选先后）。这一层错了的现象是
 * "模型的态度时对时错"，从界面上几乎无法定位，所以把规则钉死。
 */
class PresetPromptTest {

    private fun settings(
        persona: String = "",
        extra: String = "",
        enabled: List<String> = emptyList(),
    ) = ConversationSettings(
        model = ModelInfo(id = "test-model"),
        persona = persona,
        systemPrompt = extra,
        enabledPresetIds = enabled,
    )

    private fun preset(id: String, content: String) = Preset(id = id, name = id, content = content)

    @Test
    fun `按预设列表的顺序拼接，不看开关的先后`() {
        val library = listOf(
            preset("a", "A"),
            preset("b", "B"),
            preset("c", "C"),
        )
        // 用户先开 c、再开 a：提示词里仍是列表顺序（A 在 C 前）
        val text = settings(enabled = listOf("c", "a")).composedSystemPrompt(library)
        assertEquals("A\n\nC", text)
    }

    @Test
    fun `悬空 id 直接跳过`() {
        val library = listOf(preset("a", "A"))
        val text = settings(enabled = listOf("gone", "a")).composedSystemPrompt(library)
        assertEquals("A", text)
    }

    @Test
    fun `空正文不留下空行`() {
        val library = listOf(preset("a", "A"), preset("blank", "   "))
        val text = settings(enabled = listOf("a", "blank")).composedSystemPrompt(library)
        assertEquals("A", text)
    }

    @Test
    fun `人设最前，会话补充最后`() {
        val library = listOf(preset("a", "A"))
        val text = settings(persona = "我是老张", extra = "这次用中文", enabled = listOf("a"))
            .composedSystemPrompt(library)
        assertEquals("我是老张\n\nA\n\n这次用中文", text)
    }

    @Test
    fun `什么都没开时为空串`() {
        val library = listOf(preset("a", "A"))
        assertEquals("", settings().composedSystemPrompt(library))
    }

    @Test
    fun `重复 id 只算一次`() {
        val library = listOf(preset("a", "A"))
        val text = settings(enabled = listOf("a", "a")).composedSystemPrompt(library)
        // 上层（SettingsStore）已经去重，这里再兜一次：同一段提示词发两遍
        // 会让模型把那一条的权重放大
        assertEquals("A", text)
    }

    @Test
    fun `空白预设库不会产出分隔符`() {
        val text = settings(persona = "  ", extra = "  ").composedSystemPrompt(emptyList())
        assertTrue(text.isEmpty())
    }
}
