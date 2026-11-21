package com.simplechat.app.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文里的 `@路径` → 产物条。
 *
 * 这套规则的用户可见后果很直接：@ 漏了，用户拿不到文件；@ 误判了，
 * 消息末尾会冒出一排点了打不开的条目。所以边界用例比正例更重要 ——
 * 邮箱、`@2x`、代码注解、链接，全都不能被当成产物。
 */
class ArtifactsTest {

    private val root: File = File("/data/user/0/com.simplechat.app/files/workspace")

    private fun parse(content: String) = Artifacts.parse(content, root)

    @Test
    fun `相对路径按工作区根解析`() {
        assertEquals(
            listOf(File(root, "notes/report.md").path),
            parse("报告写好了，在 @notes/report.md 里。"),
        )
    }

    @Test
    fun `只有文件名也认`() {
        assertEquals(listOf(File(root, "report.md").path), parse("产物：@report.md"))
    }

    @Test
    fun `前导斜杠同样按工作区根解析`() {
        assertEquals(listOf(File(root, "out/chart.png").path), parse("图在 @/out/chart.png"))
    }

    @Test
    fun `邮箱不是产物`() {
        assertTrue(parse("有问题发 foo@bar.com").isEmpty())
    }

    @Test
    fun `代码注解不是产物`() {
        assertTrue(parse("把 @Composable 加在函数上").isEmpty())
    }

    @Test
    fun `图片倍率不是产物`() {
        assertTrue(parse("切图放在 icon@2x 目录").isEmpty())
    }

    @Test
    fun `链接不是产物`() {
        assertTrue(parse("参考 @https://example.com/a.md").isEmpty())
    }

    @Test
    fun `中文标点会截断路径`() {
        assertEquals(
            listOf(File(root, "notes/a.md").path),
            parse("见 @notes/a.md。后面没有别的了"),
        )
    }

    @Test
    fun `markdown 包裹不影响解析`() {
        assertEquals(
            listOf(File(root, "notes/a.md").path),
            parse("**@notes/a.md** 和 `@notes/a.md`"),
        )
    }

    @Test
    fun `重复引用只出现一次`() {
        assertEquals(
            listOf(File(root, "notes/a.md").path),
            parse("@notes/a.md 再提一次 @notes/a.md"),
        )
    }

    @Test
    fun `越界路径丢弃`() {
        assertTrue(parse("@../../etc/passwd.md").isEmpty())
    }

    @Test
    fun `没有艾特就没有产物`() {
        assertTrue(parse("都做完了，文件在 notes 目录里。").isEmpty())
    }

    // ── 正文里那份 @ 要被收掉 ────────────────────────────────
    //
    // 界面末尾已经有文件卡片，正文里再留一遍路径就是同一句话说两遍。

    private fun strip(content: String) = Artifacts.strip(content, root)

    @Test
    fun `单独成行的艾特整行消失`() {
        assertEquals("写好了。", strip("写好了。\n\n@notes/report.md"))
    }

    @Test
    fun `行内艾特只删路径不改句子`() {
        assertEquals("报告写好了，在 里。", strip("报告写好了，在 @notes/report.md 里。"))
    }

    @Test
    fun `只有标点的残行一并丢掉`() {
        assertEquals("已完成：", strip("已完成：\n@out/a.md"))
    }

    @Test
    fun `普通艾特一个字都不动`() {
        val text = "有问题发 foo@bar.com，或者看 @Composable 的写法。"
        assertEquals(text, strip(text))
    }

    @Test
    fun `连续空行收成一个`() {
        assertEquals("第一段\n\n第二段", strip("第一段\n\n@a/b.md\n\n第二段"))
    }

    @Test
    fun `包在强调里的艾特不留空星号`() {
        assertEquals("产物：", strip("产物：**@notes/a.md**"))
    }

    @Test
    fun `包在行内代码里的艾特不留空反引号`() {
        assertEquals("产物：", strip("产物：`@notes/a.md`"))
    }

    // ── 云端：本地没有那些文件 ────────────────────────────────

    @Test
    fun `云端按路径形状认产物`() {
        assertEquals(
            listOf("notes/a.md"),
            Artifacts.parse("产物在 @notes/a.md", root = null, remote = true),
        )
    }

    @Test
    fun `云端也能收掉正文里的艾特`() {
        assertEquals("产物在", Artifacts.strip("产物在\n@notes/a.md", root = null, remote = true))
    }
}
