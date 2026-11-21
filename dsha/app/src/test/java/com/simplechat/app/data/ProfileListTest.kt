package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProfileList] 的编解码。
 *
 * 这一层的失败方式很隐蔽：解不出来不报错，只是列表变短 —— 用户看到的是
 * 「我配的中转不见了」，而不是一条异常。所以每一段都要有断言。
 *
 * **Key 在 JVM 上是空着的**：`SecretBox` 走 Android Keystore，单测里没有
 * 那套 Provider，`encrypt` 返回 null。这里不测加解密（那是 SecretBox 的事），
 * 只测结构能不能原样回来。
 */
class ProfileListTest {

    private fun sample(id: String = "a", name: String = "官方") = ModelProfile(
        id = id,
        name = name,
        providerId = "DEEPSEEK",
        baseUrl = "https://api.deepseek.com",
        apiKey = "sk-test",
        modelIds = listOf("deepseek-chat", "deepseek-reasoner"),
        modelId = "deepseek-chat",
    )

    @Test
    fun `往返保留所有非敏感字段`() {
        val decoded = ProfileList.decode(ProfileList.encode(listOf(sample())))

        assertEquals(1, decoded.size)
        val only = decoded.first()
        assertEquals("a", only.id)
        assertEquals("官方", only.name)
        assertEquals("DEEPSEEK", only.providerId)
        assertEquals("https://api.deepseek.com", only.baseUrl)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), only.modelIds)
        assertEquals("deepseek-chat", only.modelId)
    }

    @Test
    fun `多套配置保持顺序`() {
        val list = listOf(sample("a", "官方"), sample("b", "中转"), sample("c", "本地"))
        val decoded = ProfileList.decode(ProfileList.encode(list))
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id })
        assertEquals(listOf("官方", "中转", "本地"), decoded.map { it.name })
    }

    @Test
    fun `空字符串解出空列表`() {
        assertTrue(ProfileList.decode("").isEmpty())
        assertTrue(ProfileList.decode(null).isEmpty())
        assertTrue(ProfileList.decode("   ").isEmpty())
    }

    @Test
    fun `坏数据不抛异常`() {
        // 用户从别的版本回滚、或者 DataStore 写坏一半，都不能让 App 起不来
        assertTrue(ProfileList.decode("{not json}").isEmpty())
        assertTrue(ProfileList.decode("[1,2,3]").isEmpty())
        assertTrue(ProfileList.decode("""[{"name":"没有 id"}]""").isEmpty())
    }

    @Test
    fun `缺字段的条目用默认值补齐`() {
        val decoded = ProfileList.decode("""[{"id":"x"}]""")
        assertEquals(1, decoded.size)
        val only = decoded.first()
        assertEquals("x", only.id)
        assertEquals("", only.name)
        assertEquals("DEEPSEEK", only.providerId) // 兜底：不认识就当官方
        assertTrue(only.modelIds.isEmpty())
    }

    @Test
    fun `超过上限的部分被丢掉`() {
        val many = (1..ProfileList.MAX_PROFILES + 3).map { sample("p$it", "配置 $it") }
        val decoded = ProfileList.decode(ProfileList.encode(many))
        assertEquals(ProfileList.MAX_PROFILES, decoded.size)
    }

    @Test
    fun `合成的那条有固定 id`() {
        val synthetic = ProfileList.synthetic(
            providerId = "OPENAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            modelId = "gpt-4o",
            modelIds = listOf("gpt-4o"),
        )
        // 固定 id 是"老数据只合成一条"的前提；随机 id 会让每次读设置都多出一套配置
        assertEquals(ModelProfile.LEGACY_ID, synthetic.id)
        assertNotNull(ProfileList.decode(ProfileList.encode(listOf(synthetic))).firstOrNull())
    }
}
