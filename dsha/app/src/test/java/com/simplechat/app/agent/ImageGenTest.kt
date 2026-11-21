package com.simplechat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘图的"比例 → 尺寸"换算。
 *
 * 这层是纯函数，出错的表现却很远：模型填了 9:16，客户端算错一个档位，
 * 服务端给回一张方图，用户看到的是"说了竖版还是方的"。
 */
class ImageGenTest {

    private val config = ImageGen.Config(
        url = "https://api.example.com/v1",
        apiKey = "sk-test",
        model = "gpt-image-1",
        size = "",
    )

    // ── 解析 ──────────────────────────────────────────────

    @Test
    fun `pixels 写法被识别`() {
        assertEquals(1024 to 1536, ImageGen.pixelsOf("1024x1536"))
        assertNull(ImageGen.pixelsOf("16:9"))
        assertNull(ImageGen.pixelsOf("auto"))
    }

    @Test
    fun `比例写法都认`() {
        assertEquals(16f / 9f, ImageGen.parseRatio("16:9")!!, 0.001f)
        assertEquals(16f / 9f, ImageGen.parseRatio("16/9")!!, 0.001f)
        assertEquals(1f, ImageGen.parseRatio("1024x1024")!!, 0.001f)
        assertEquals(0.75f, ImageGen.parseRatio("portrait")!!, 0.001f)
        assertNull(ImageGen.parseRatio("auto"))
        assertNull(ImageGen.parseRatio(""))
    }

    // ── 模型族 ────────────────────────────────────────────

    @Test
    fun `gpt-image 只有三档`() {
        assertEquals(1024 to 1536, ImageGen.sizeFor("gpt-image-1", 9f / 16f))
        assertEquals(1536 to 1024, ImageGen.sizeFor("gpt-image-1", 16f / 9f))
        assertEquals(1024 to 1024, ImageGen.sizeFor("gpt-image-1", 1f))
    }

    @Test
    fun `dall-e-3 有竖版档`() {
        assertEquals(1024 to 1792, ImageGen.sizeFor("dall-e-3", 9f / 16f))
        assertEquals(1792 to 1024, ImageGen.sizeFor("dall-e-3", 16f / 9f))
    }

    @Test
    fun `通用模型按比例挑最近的档位`() {
        // 16:9 在候选表里有精确对应
        assertEquals(1280 to 720, ImageGen.sizeFor("flux-schnell", 16f / 9f))
        assertEquals(720 to 1280, ImageGen.sizeFor("flux-schnell", 9f / 16f))
        assertEquals(1024 to 768, ImageGen.sizeFor("flux-schnell", 4f / 3f))
    }

    @Test
    fun `严格模型会把任意像素收进白名单`() {
        assertEquals(1024 to 1536, ImageGen.normalize("gpt-image-1", 768, 1344))
        // 非严格模型原样透传
        assertEquals(768 to 1344, ImageGen.normalize("flux-schnell", 768, 1344))
    }

    // ── 优先级 ────────────────────────────────────────────

    @Test
    fun `对话里的比例优先于设置里的固定尺寸`() {
        val size = ImageGen.resolveSize(
            model = "gpt-image-1",
            aspectRatio = "9:16",
            requestedSize = null,
            fallback = "1024x1024",
        )
        assertEquals(1024 to 1536, size)
    }

    @Test
    fun `模型给的明确像素最高优先`() {
        val size = ImageGen.resolveSize(
            model = "flux-schnell",
            aspectRatio = "9:16",
            requestedSize = "768x1344",
            fallback = "1024x1024",
        )
        assertEquals(768 to 1344, size)
    }

    @Test
    fun `什么都没有时不传 size`() {
        assertNull(
            ImageGen.resolveSize(
                model = "gpt-image-1",
                aspectRatio = null,
                requestedSize = null,
                fallback = "",
            ),
        )
    }

    @Test
    fun `设置里写比例也能换算`() {
        assertEquals(
            1024 to 1024,
            ImageGen.resolveSize("gpt-image-1", null, null, "1:1"),
        )
    }

    // ── 请求体 ────────────────────────────────────────────

    @Test
    fun `size 为空时请求体不带这个字段`() {
        val body = ImageGen.requestBody(config, "a cat", null, 1)
        assertTrue(body.contains("\"model\":\"gpt-image-1\""))
        assertTrue(body.contains("\"prompt\":\"a cat\""))
        assertTrue(!body.contains("\"size\""))
    }

    @Test
    fun `size 有值时原样透传`() {
        val body = ImageGen.requestBody(config, "a cat", "1024x1536", 1)
        assertTrue(body.contains("\"size\":\"1024x1536\""))
    }

    @Test
    fun `端点三种写法都能补全`() {
        assertEquals(
            "https://api.x.com/v1/images/generations",
            ImageGen.endpointOf("https://api.x.com/v1"),
        )
        assertEquals(
            "https://api.x.com/v1/images/generations",
            ImageGen.endpointOf("https://api.x.com/v1/images/generations"),
        )
        assertEquals(
            "https://api.x.com/v1/images/generations",
            ImageGen.endpointOf("https://api.x.com"),
        )
    }
}
