package com.simplechat.app.ui.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 内置部署包的存在性。
 *
 * 这一页的按钮读的是 `assets/cloud-workspace.zip`，而它**曾经一次都没进过包**：
 * 代码写好了、文案写好了，资产没放进去，`runCatching` 把异常吃掉，点「保存」
 * 什么都不会发生 —— 用户看到的就是"部署包不见了"。这种缺失编译不报错、
 * 界面也不报错，只能靠一条盯着资产的测试挡住。
 *
 * 单测的工作目录是模块目录（`app/`），所以直接按源码路径读，不需要 Robolectric。
 */
class CloudBundleTest {

    private val bundle = File("src/main/assets/cloud-workspace.zip")

    @Test
    fun `bundle is packaged`() {
        assertTrue(
            "assets/cloud-workspace.zip 不存在。它必须随 APK 一起打包，否则「保存部署包」与「复制部署文本」都是空点。",
            bundle.isFile,
        )
        assertTrue("部署包不该是空文件", bundle.length() > 1_000)
    }

    @Test
    fun `bundle carries the files deploy text needs`() {
        val names = ZipFile(bundle).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        // 复制文本会按扩展名挑 md/py/sh：这五个缺一个，粘给对方的说明就残一段
        for (required in listOf("server.py", "run.sh", "DEPLOY.md")) {
            assertTrue("部署包缺少 $required（实际：$names）", required in names)
        }
    }

    @Test
    fun `text entries decode as utf8`() {
        ZipFile(bundle).use { zip ->
            var textEntries = 0
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("md", "py", "sh", "json", "txt", "toml", "yaml", "yml")) continue
                val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
                assertTrue("${entry.name} 解出来是空的", text.isNotBlank())
                textEntries++
            }
            // server.py / run.sh / DEPLOY.md / README.md / client-example.sh 五份
            assertEquals("可读文本文件数量不对", 5, textEntries)
        }
    }

    /**
     * 部署包里的服务端必须**至少**是能把 AI 的调用接住的那一版。
     *
     * 线上曾经跑着 v3：AI 发 `command`、服务端只认 `cmd`，回来一句
     * 「cmd 必须是非空字符串」，模型困惑、用户以为环境坏了。
     * 这两处兼容写在 server.py 里，退回去就等于把那条死路重新铺一遍。
     */
    @Test
    fun `server accepts the names models actually use`() {
        val source = ZipFile(bundle).use { zip ->
            val entry = zip.getEntry("server.py") ?: error("部署包里没有 server.py")
            zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
        }
        assertTrue("server.py 少了传输信封", source.contains("_unwrap"))
        assertTrue("server.py 少了命令别名", source.contains("\"command\", \"script\""))
        assertTrue("server.py 少了装包类型归一化", source.contains("INSTALL_ALIASES"))
        assertTrue("server.py 少了上传压缩的自述", source.contains("upload_encoding"))
    }
}
