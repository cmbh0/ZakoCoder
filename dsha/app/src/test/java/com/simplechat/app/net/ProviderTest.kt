package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 层回归测试。
 *
 * 覆盖地址拼接、协议通道判定与模型能力表。服务商列表本身不再测 ——
 * 内置渠道越少，越不该在"某种渠道必须可用"上做假设。
 */
class ProviderTest {

    // ── 地址拼接 ──────────────────────────────────────────

    @Test
    fun `DeepSeek 地址拼接`() {
        // 只写 host 时统一补 /v1：官方两种写法都认，补上后与 OpenAI SDK 的默认行为一致
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            chatCompletionsUrl("  https://api.deepseek.com  "),
        )
    }

    @Test
    fun `中转端点地址拼接保留 v1 前缀`() {
        assertEquals(
            "https://gw.example.com/v1/chat/completions",
            chatCompletionsUrl("https://gw.example.com/v1"),
        )
    }

    @Test
    fun `已是完整路径时不重复拼接`() {
        val full = "https://example.com/v1/chat/completions"
        assertEquals(full, chatCompletionsUrl(full))
    }

    @Test
    fun `models 地址拼接`() {
        assertEquals(
            "https://gw.example.com/v1/models",
            modelsUrl("https://gw.example.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            modelsUrl("https://api.deepseek.com/"),
        )
    }

    @Test
    fun `Responses 地址拼接并剥掉误粘的完整路径`() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            responsesUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            responsesUrl("https://api.openai.com/v1/responses"),
        )
        assertEquals(
            "https://gw.example.com/backend-api/codex/responses",
            responsesUrl("https://gw.example.com/backend-api/codex"),
        )
    }

    @Test
    fun `显式 Responses 配置不会被 AUTO 改回 chat`() {
        assertEquals(
            ApiStyle.RESPONSES,
            ApiStyle.resolve(ProviderKind.OPENAI, "RESPONSES", "https://api.openai.com/v1"),
        )
        assertEquals(
            ApiStyle.RESPONSES,
            ApiStyle.resolve(
                ProviderKind.CUSTOM,
                "AUTO",
                "https://gw.example.com/backend-api/codex",
            ),
        )
    }

    // ── 模型能力表 ────────────────────────────────────────

    @Test
    fun `DeepSeek 官方仅两个模型且能力正确`() {
        val models = ModelCatalog.knownModels(ProviderKind.DEEPSEEK)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.id })
        // 实测：/models 只返回这两个；且只有 flash 支持识图
        assertTrue(models.first { it.id == "deepseek-flash" }.supportsVision)
        assertTrue(!models.first { it.id == "deepseek-v4-pro" }.supportsVision)
        assertTrue(models.all { it.supportsThinking })
    }

    @Test
    fun `未知模型回落到保守默认能力`() {
        val info = ModelCatalog.find(ProviderKind.CUSTOM, "some-unknown-model")
        assertEquals("some-unknown-model", info.id)
        assertTrue(!info.supportsVision)
        assertTrue(!info.supportsThinking)
    }

    @Test
    fun `远程模型列表与能力表合并时保留已知能力`() {
        val merged = ModelCatalog.merge(
            ProviderKind.CUSTOM,
            listOf("deepseek-flash", "brand-new-model"),
        )
        assertEquals(2, merged.size)
        // 已知模型保留能力（全局表认得的 id 在自定义端点上照样生效）
        assertTrue(merged.first { it.id == "deepseek-flash" }.supportsThinking)
        assertEquals(1_000_000, merged.first { it.id == "deepseek-flash" }.contextWindow)
        // 未知模型使用保守默认
        assertEquals("brand-new-model", merged[1].id)
        assertTrue(!merged[1].supportsVision)
    }

    @Test
    fun `远程列表为空时回落到已知目录`() {
        val merged = ModelCatalog.merge(ProviderKind.DEEPSEEK, emptyList())
        assertEquals(2, merged.size)
    }

    // ── 设置页「模型」选择器 ──────────────────────────────

    @Test
    fun `自定义端点拉到的模型按服务端顺序全部进选择器`() {
        val models = ModelCatalog.pickerModels(
            ProviderKind.CUSTOM,
            listOf("gpt-5-mini", "gpt-5", "gpt-5-mini"),
        )
        assertEquals(listOf("gpt-5-mini", "gpt-5"), models.map { it.id })
    }

    @Test
    fun `选择器只列远端真实返回的模型`() {
        val models = ModelCatalog.pickerModels(
            ProviderKind.DEEPSEEK,
            listOf("deepseek-v4-pro"),
        )
        /*
         * 不再补内置目录：补进去的条目用户没选过、也删不掉
         * （原话是"添加了就没办法删除"）。拉不到就空着，界面给手动填写入口。
         */
        assertEquals(listOf("deepseek-v4-pro"), models.map { it.id })
    }

    @Test
    fun `没有远端列表时选择器为空`() {
        assertEquals(0, ModelCatalog.pickerModels(ProviderKind.DEEPSEEK, emptyList()).size)
        assertTrue(ModelCatalog.pickerModels(ProviderKind.CUSTOM, emptyList()).isEmpty())
    }

    // ── 第三方端点上的能力表 ──────────────────────────────

    @Test
    fun `第三方端点上的已知模型仍然带上能力`() {
        // 中转站原样代理官方模型（截图里的 totokens.cc 就是），id 一模一样
        val info = ModelCatalog.find(ProviderKind.CUSTOM, "deepseek-flash")
        assertEquals(1_000_000, info.contextWindow)
        assertTrue(info.supportsVision)
        assertTrue(info.supportsThinking)
    }

    @Test
    fun `完全未知的模型仍用保守默认值`() {
        val info = ModelCatalog.find(ProviderKind.CUSTOM, "totally-unknown-model")
        assertEquals(ModelInfo.DEFAULT_CONTEXT_WINDOW, info.contextWindow)
        assertTrue(!info.supportsVision)
        assertTrue(!info.supportsThinking)
    }

    @Test
    fun `选择器里的第三方模型也带上全局能力`() {
        val model = ModelCatalog.pickerModels(ProviderKind.CUSTOM, listOf("deepseek-v4-pro")).single()
        assertEquals(1_000_000, model.contextWindow)
    }
}
