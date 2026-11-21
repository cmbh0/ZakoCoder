package com.simplechat.app.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回滚内核。
 *
 * 这是整个 App 里唯一会**改用户文件**的代码之一，所以不省测试：
 * 新建的要删掉、改过的要还原、回滚本身还能再回滚。
 */
class RewindTest {

    private lateinit var base: File
    private lateinit var workspace: File
    private val conversation = "conv-test"
    private val scope = "local"

    @Before
    fun setUp() {
        base = kotlin.io.path.createTempDirectory("dsha-rewind").toFile()
        workspace = File(base, "ws").apply { mkdirs() }
        Rewind.testRoot = File(base, "rewind")
    }

    @After
    fun tearDown() {
        Rewind.testRoot = null
        Rewind.forget(conversation)
        base.deleteRecursively()
    }

    /** 模拟一次写工具：先快照，再落盘，最后入账。 */
    private fun write(path: File, content: String, messageId: String) = runBlocking {
        val entry = Rewind.capture(
            conversationId = conversation,
            messageId = messageId,
            toolCallId = "call-${path.name}",
            scope = scope,
            path = path.absolutePath,
        )
        path.parentFile?.mkdirs()
        path.writeText(content)
        Rewind.commit(conversation, entry)
    }

    @Test
    fun `新建的文件回滚后被删除`() = runBlocking {
        val file = File(workspace, "new.md")
        write(file, "hello", "m1")

        val report = Rewind.rewind(conversation, setOf("m1"), scope = scope)

        assertFalse(file.exists())
        assertEquals(1, report.deleted.size)
        assertEquals(0, report.failed.size)
    }

    @Test
    fun `改过的文件回滚后恢复原内容`() = runBlocking {
        val file = File(workspace, "a.txt")
        file.writeText("v1")
        write(file, "v2", "m1")

        val report = Rewind.rewind(conversation, setOf("m1"), scope = scope)

        assertEquals("v1", file.readText())
        assertEquals(1, report.restored.size)
    }

    @Test
    fun `同一条消息改两次 回滚取最早那一份`() = runBlocking {
        val file = File(workspace, "a.txt")
        file.writeText("v1")
        write(file, "v2", "m1")
        write(file, "v3", "m1")

        Rewind.rewind(conversation, setOf("m1"), scope = scope)

        // 最早那条记录的备份是 v1，不是 v2 —— 顺序算错就会退回一半
        assertEquals("v1", file.readText())
    }

    @Test
    fun `回滚本身可以再回滚`() = runBlocking {
        val file = File(workspace, "a.txt")
        file.writeText("v1")
        write(file, "v2", "m1")

        Rewind.rewind(conversation, setOf("m1"), scope = scope)
        assertEquals("v1", file.readText())

        // 回滚动作记在 ACTOR 名下；再回滚一次应回到回滚前（v2）
        Rewind.rewind(conversation, setOf(Rewind.ACTOR), scope = scope)
        assertEquals("v2", file.readText())
    }

    @Test
    fun `删除的文件回滚后恢复`() = runBlocking {
        val file = File(workspace, "gone.txt")
        file.writeText("important")
        // 模拟删除：快照之后把文件删掉
        val entry = Rewind.capture(conversation, "m1", null, scope, file.absolutePath)
        file.delete()
        Rewind.commit(conversation, entry)

        Rewind.rewind(conversation, setOf("m1"), scope = scope)

        assertTrue(file.exists())
        assertEquals("important", file.readText())
    }

    @Test
    fun `只回滚指定文件`() = runBlocking {
        val a = File(workspace, "a.txt").apply { writeText("a1") }
        val b = File(workspace, "b.txt").apply { writeText("b1") }
        write(a, "a2", "m1")
        write(b, "b2", "m1")

        Rewind.rewind(conversation, setOf("m1"), scope = scope, pathFilter = a.absolutePath)

        assertEquals("a1", a.readText())
        assertEquals("b2", b.readText())
    }

    @Test
    fun `消息边界之后的改动才被撤销`() = runBlocking {
        val file = File(workspace, "a.txt").apply { writeText("v1") }
        write(file, "v2", "m1")
        write(file, "v3", "m2")

        Rewind.rewind(conversation, setOf("m2"), scope = scope)

        // 只撤 m2 → 回到 m2 之前的 v2；m1 那次改动还在
        assertEquals("v2", file.readText())
    }

    @Test
    fun `没有记录时回滚是空操作`() = runBlocking {
        val report = Rewind.rewind(conversation, setOf("nobody"), scope = scope)
        assertEquals(0, report.changed)
    }

    @Test
    fun `消息集合可以按最近的改动反推`() {
        val file = File(workspace, "a.txt").apply { writeText("v0") }
        write(file, "v1", "m1")
        write(file, "v2", "m2")

        val recent = Rewind.recentMessageIds(conversation, scope, turns = 1)
        assertEquals(setOf("m2"), recent)

        val two = Rewind.recentMessageIds(conversation, scope, turns = 2)
        assertEquals(setOf("m2", "m1"), two)
    }
}
