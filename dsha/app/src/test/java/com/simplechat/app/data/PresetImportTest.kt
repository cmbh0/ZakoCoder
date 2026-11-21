package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设导入的解析单测。
 *
 * 核心约定只有一条：**什么格式都能进**。
 * 所以这些用例里大部分在验证"兜底路径"—— 纯文本原样变正文。
 */
class PresetImportTest {

    @Test
    fun `plain text becomes the content as is`() {
        val draft = PresetImport.fromText("notes.txt", "你是一个严谨的后端工程师。")
        assertEquals("notes", draft.name)
        assertEquals("你是一个严谨的后端工程师。", draft.content)
    }

    @Test
    fun `file name without extension becomes the name`() {
        val draft = PresetImport.fromText("/some/dir/My Character Card.txt", "内容")
        assertEquals("My Character Card", draft.name)
    }

    @Test
    fun `markdown heading becomes the name and is removed from the body`() {
        val draft = PresetImport.fromText("card.md", "# 夜鸦\n\n你住在旧港区，经营一家只在深夜开门的当铺。")
        assertEquals("夜鸦", draft.name)
        assertTrue(draft.content.startsWith("你住在旧港区"))
        assertTrue(!draft.content.contains("# 夜鸦"))
    }

    @Test
    fun `json with name and description is understood`() {
        val json = """{"name": "Nova", "description": "一个在轨道站工作的导航 AI。"}"""
        val draft = PresetImport.fromText("card.json", json)
        assertEquals("Nova", draft.name)
        assertEquals("一个在轨道站工作的导航 AI。", draft.content)
    }

    @Test
    fun `json without known keys falls back to the whole text`() {
        val json = """{"foo": "bar", "baz": [1, 2, 3]}"""
        val draft = PresetImport.fromText("card.json", json)
        assertEquals("card", draft.name)
        assertTrue(draft.content.contains("foo"))
    }

    @Test
    fun `json array is not mistaken for an object`() {
        val json = """["a", "b"]"""
        val draft = PresetImport.fromText("list.json", json)
        assertEquals("list", draft.name)
        assertTrue(draft.content.startsWith("["))
    }

    @Test
    fun `yaml front matter is understood`() {
        val text = "---\nname: 陆行鸟\ndescription: 一只在风暴里飞了三十年的鸟。\n---\n\n正文里还有别的设定。"
        val draft = PresetImport.fromText("bird.yaml", text)
        assertEquals("陆行鸟", draft.name)
        assertTrue(draft.content.contains("风暴"))
    }

    @Test
    fun `carriage returns are normalized`() {
        val draft = PresetImport.fromText("a.txt", "第一行\r\n第二行\r第三行")
        assertEquals("第一行\n第二行\n第三行", draft.content)
    }

    @Test
    fun `byte order mark is stripped`() {
        val draft = PresetImport.fromText("a.txt", "\uFEFF内容")
        assertEquals("内容", draft.content)
    }

    @Test
    fun `blank content is reported as empty`() {
        val draft = PresetImport.fromText("a.txt", "   \n  ")
        assertEquals("", draft.content)
    }

    @Test
    fun `very long content is truncated`() {
        val long = "字".repeat(PresetImport.MAX_CONTENT_CHARS + 100)
        val draft = PresetImport.fromText("a.txt", long)
        assertEquals(PresetImport.MAX_CONTENT_CHARS, draft.content.length)
        assertTrue(draft.note != null)
    }
}
