package com.simplechat.app.agent

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 预览服务。
 *
 * 三个必须成立的约定：
 * - 相对链接能取到（这就是"串联"）
 * - 目录能列（没有 index 的项目不至于一片 404）
 * - **越界拿不到**（页面里一个 `../` 不能把 App 私有目录读出去）
 */
class PreviewServerTest {

    private lateinit var root: File
    private lateinit var server: PreviewServer.Running

    @Before
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("dsha-preview").toFile()
        File(root, "index.html").writeText("<h1>home</h1>")
        File(root, "style.css").writeText("body{}")
        File(root, "assets").mkdirs()
        File(root, "assets/app.js").writeText("console.log(1)")
        File(root, "sub").mkdirs()
        File(root, "sub/page.html").writeText("<p>page</p>")
        // 根目录之外的文件 —— 下面用它验证越界被拦
        File(root.parentFile, "secret-${root.name}.txt").writeText("top-secret")

        server = PreviewServer.open(LocalPreviewSource(root))
    }

    @After
    fun tearDown() {
        PreviewServer.stop()
        File(root.parentFile, "secret-${root.name}.txt").delete()
        root.deleteRecursively()
    }

    private fun get(path: String): Pair<Int, ByteArray> {
        val connection = URL(server.baseUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return code to bytes
    }

    @Test
    fun `按文件名取页面`() {
        val (code, body) = get("index.html")
        assertEquals(200, code)
        assertEquals("<h1>home</h1>", body.toString(Charsets.UTF_8))
    }

    @Test
    fun `根路径回落到 index`() {
        val (code, body) = get("")
        assertEquals(200, code)
        assertTrue(body.toString(Charsets.UTF_8).contains("home"))
    }

    @Test
    fun `子目录里的资源按相对路径取到`() {
        val (code, body) = get("assets/app.js")
        assertEquals(200, code)
        assertTrue(body.toString(Charsets.UTF_8).contains("console.log"))
    }

    @Test
    fun `没有 index 的目录会列出来`() {
        val (code, body) = get("sub/")
        assertEquals(200, code)
        val html = body.toString(Charsets.UTF_8)
        assertTrue(html.contains("page.html"))
        // 列表里的链接带子目录前缀，点进去还在同一层
        assertTrue(html.contains("sub/page.html"))
    }

    @Test
    fun `越界路径被拒绝`() {
        // 编码过的 ../，确保它到服务端才被解码
        val secret = "../secret-${root.name}.txt"
        val encoded = secret.replace("..", "%2e%2e").replace("/", "%2f")
        val (code, _) = get(encoded)
        assertEquals(404, code)
    }

    @Test
    fun `不存在的文件返回 404`() {
        val (code, _) = get("nope.html")
        assertEquals(404, code)
    }

    @Test
    fun `扩展名决定 Content-Type`() {
        val connection = URL(server.baseUrl + "style.css").openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        assertEquals("text/css; charset=utf-8", connection.contentType)
        connection.disconnect()
    }

    @Test
    fun `html 识别为可预览`() {
        assertTrue(PreviewServer.isPreviewable("a.html"))
        assertTrue(PreviewServer.isPreviewable("dir/b.HTM"))
        assertNotNull(PreviewServer.mimeOf("a.webp"))
        assertEquals(false, PreviewServer.isPreviewable("a.md"))
    }
}
