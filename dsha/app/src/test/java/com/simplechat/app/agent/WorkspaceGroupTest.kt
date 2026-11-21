package com.simplechat.app.agent

import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.history.HistoryGroup
import com.simplechat.app.ui.history.groupDetail
import com.simplechat.app.ui.history.groupOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区分组：普通对话 → 本地 → 云端，置顶单独在最前。
 *
 * 这些断言看起来琐碎，但它们保护的是一个**用户可见的排序契约**：
 * 未绑定工作区的对话必须排在任何工作区之前，否则侧栏读起来像一堆
 * 混在一起的条目，分类就白做了。
 */
class WorkspaceGroupTest {

    private fun conversation(
        id: String,
        kind: String = "NONE",
        label: String = "",
        pinned: Boolean = false,
        updatedAt: Long = 1_000L,
    ) = ConversationEntity(
        id = id,
        title = id,
        mode = "CREATIVE",
        provider = "DEEPSEEK",
        model = "deepseek-chat",
        createdAt = 0L,
        updatedAt = updatedAt,
        pinned = pinned,
        workspaceKind = kind,
        workspaceLabel = label,
    )

    @Test
    fun `没有工作区的是普通对话`() {
        assertEquals(HistoryGroup.PLAIN, groupOf(conversation("a", "NONE")))
        assertEquals(HistoryGroup.PLAIN, groupOf(conversation("b", "")))
    }

    @Test
    fun `内置与本机文件夹都算本地工作区`() {
        assertEquals(HistoryGroup.LOCAL, groupOf(conversation("a", "INTERNAL")))
        assertEquals(HistoryGroup.LOCAL, groupOf(conversation("b", "DEVICE")))
        assertEquals(HistoryGroup.LOCAL, groupOf(conversation("c", "CONTAINER")))
    }

    @Test
    fun `云端不分 HTTP 还是 SSH`() {
        assertEquals(HistoryGroup.CLOUD, groupOf(conversation("a", "CLOUD")))
    }

    @Test
    fun `置顶优先于一切分类`() {
        assertEquals(HistoryGroup.PINNED, groupOf(conversation("a", "CLOUD", pinned = true)))
        assertEquals(HistoryGroup.PINNED, groupOf(conversation("b", "NONE", pinned = true)))
    }

    @Test
    fun `分组顺序是 置顶 普通 本地 云端`() {
        val order = HistoryGroup.entries.map { it.name }
        assertEquals(listOf("PINNED", "PLAIN", "LOCAL", "CLOUD"), order)
    }

    @Test
    fun `组标题下的说明只列出现过的工作区`() {
        val items = listOf(
            conversation("a", "DEVICE", "Download/proj"),
            conversation("b", "INTERNAL", "内置工作区"),
        )
        val detail = groupDetail(items, HistoryGroup.LOCAL)
        assertTrue(detail!!.contains("Download/proj"))
        assertTrue(detail.contains("内置工作区"))
        // 普通对话没有工作区可说，不该编一个出来
        assertEquals(null, groupDetail(items, HistoryGroup.PLAIN))
    }

    @Test
    fun `老版本存下的云端配置默认按 HTTP 读`() {
        // 升级前落盘的 JSON 里没有 kind/host 这些字段
        val legacy = """{"id":"abc","name":"东京","baseUrl":"https://x.example.com","token":"t"}"""
        val env = Json { ignoreUnknownKeys = true }.decodeFromString(CloudEnv.serializer(), legacy)
        assertEquals(CloudEnv.KIND_HTTP, env.kind)
        assertEquals(false, env.isSsh)
        assertEquals("x.example.com", env.hostLabel)
    }

    @Test
    fun `SSH 环境的展示名带上用户与端口`() {
        val env = CloudEnv(
            id = "1",
            name = "vps",
            baseUrl = "",
            token = "",
            kind = CloudEnv.KIND_SSH,
            host = "10.0.0.2",
            port = 2222,
            user = "ubuntu",
        )
        assertEquals("ubuntu@10.0.0.2:2222", env.hostLabel)
    }
}
