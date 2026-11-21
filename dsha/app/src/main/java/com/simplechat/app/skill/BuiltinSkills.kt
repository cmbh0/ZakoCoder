package com.simplechat.app.skill

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置技能：装好就会在，用户可以在技能页关掉。
 *
 * 为什么做成"技能"而不是"一直挂着的工具"：联网搜索会改变模型的默认行为
 * （更倾向于查资料、回答更长、更慢），这该由用户决定。做成技能包，开关就是
 * 技能页那一行 —— 不需要为它单开一个设置项，也不会在关掉之后还留个尾巴。
 *
 * 这里的"安装"是幂等的：目录不存在就写一份 SKILL.md，**只在首次**把 id 加进
 * 启用列表。用户后来关掉了，下次启动不会替他重新打开 —— 那是最招人烦的一种
 * "智能"。
 */
object BuiltinSkills {

    const val Browser = "browser"

    /** 时间戳：给每条用户消息加"[什么时候说的]"。注入在请求层，不污染正文。 */
    const val Timestamp = "timestamp"

    /** 主动消息：用户长时间不开口时，AI 自己找话说。 */
    const val Proactive = "proactive"

    /** 已经播种过哪些内置技能。放在技能目录里，跟着技能一起搬迁。 */
    private const val SeedFile = ".seeded"

    /** 内置技能的默认正文。用户可以直接编辑 `skills/browser/SKILL.md` 改它。 */
    private val BrowserSkill = """
        ---
        name: 内置浏览器
        description: 联网搜索（Bing）并读取网页正文。需要最新信息、查文档、核实事实时用它。
        version: 1.0
        ---

        # 内置浏览器

        你有两件工具：`web_search` 与 `web_open`。

        ## 什么时候该用

        - 用户问到**会变的东西**：版本号、价格、新闻、谁在任、某个库的最新 API。
        - 用户给了链接，或明确说"查一下""搜一下""看看官网"。
        - 你准备说"截至我的训练数据"——**先搜**。这句话对用户没有价值。
        - 报错信息、陌生的包名、不确定的函数签名。

        不要用它读用户本地的文件，那用 `read_file` / `search_content`。

        ## 怎么搜

        1. 先用**关键词**，不要写成长句。`kotlin compose animateFloatAsState 卡顿` 好过
           `我想知道为什么 Kotlin 的 Compose 里 animateFloatAsState 会卡`。
        2. 一次没搜到就换说法再试一次（同义词 / 英文 / 去掉限定词），不要连搜五遍同一句。
        3. 摘要够回答就直接答；需要细节时挑**一到两条**最相关的 `web_open`，不要十条全开。
        4. 优先官方文档、GitHub、厂商博客；论坛与内容农场放在最后。

        ## 怎么答

        - 用了哪条就带哪条链接，写在对应句子后面，不要堆在末尾。
        - 搜索结果的摘要可能与正文矛盾 —— 以正文为准。
        - 打不开的页面就说打不开，不要拿摘要冒充正文内容。
        - 搜不到就直说搜不到，然后给出你知道的部分，并标明那是旧信息。

        ## 边界

        - 抓不到需要登录 / 纯 JS 渲染的页面。遇到这种，告诉用户"这个页面抓不到正文"，
          再试官网的另一个入口或文档站。
        - 单页正文有上限（默认 12000 字）。长文档分段读没有意义 —— 挑你要的那一节，
          或者让用户把关键段落贴过来。
    """.trimIndent()

    /**
     * 「时间戳」。
     *
     * 真正的注入发生在请求组装那一层（`ChatViewModel.buildRequestMessages`）——
     * 技能只是一份"告诉模型这串前缀是什么意思"的说明书。为什么不在正文里改：
     * 用户看到的每一条必须还是他自己写的那句话。
     */
    private val TimestampSkill = """
        ---
        name: 时间戳
        description: 每条消息带上发送时间（含星期），让你知道"现在几点""隔了多久"。
        version: 1.0
        ---

        # 时间戳

        用户每条消息前面会有一个方括号前缀，例如：

        ```
        [2026-11-18 00:07 周二] 帮我看看这个问题
        ```

        那是**这条消息发出的本地时间**，不是内容的一部分。历史消息也带各自的
        时间戳，所以你能看出对话的时间跨度。

        ## 该怎么用

        - 判断"现在"：用户问"今天几号""现在几点""是不是快下班了"，直接看最后一条
          消息的时间戳，不要凭训练数据回答。
        - 判断间隔：两条消息的时间差就是真实的间隔。隔了三天再接着说，语气该像
          隔了三天。
        - 时段感：凌晨、清早、深夜说话的口气不一样，"这么晚还没睡"这种话只在
          时间对得上时才说。
        - 定时任务、提醒、"明天"这类相对时间，以**最新时间戳**为基准推算。

        ## 边界

        - 不要复述时间戳，也不要把它当成用户说的话引用。
        - 时间戳是设备本地时间，可能与服务器时区不同 —— 有疑问时以它为准。
    """.trimIndent()

    /**
     * 「AI 主动发消息」。
     *
     * 调度在客户端（`ChatScreen` 的空闲计时 + `ChatViewModel.proactiveSpeak`），
     * 概率在技能页可调。这里只讲"被叫醒时该怎么开口" —— 这一段直接决定
     * 主动消息是"像个人"还是"像个推送"。
     */
    private val ProactiveSkill = """
        ---
        name: AI 主动发消息
        description: 用户长时间不说话时，你可以自己开口找他聊。概率与空闲时长可调。
        version: 1.0
        ---

        # AI 主动发消息

        当对话安静了很久，系统可能给你一次"主动开口"的机会。这时你会收到一条
        系统提示（而不是用户的话），要求你说点什么。

        ## 开口的原则

        - **像人一样短**。一两句，最多三句。主动消息是"路过时搭句话"，不是推送。
        - **有来由**。接着之前的话题、之前的约定、或者当下的时间（深夜、饭点、
          周末），不要凭空开始一个新话题。
        - **不追问、不催**。不要说"你还在吗""怎么不回复"，那是在索取注意力。
        - **给一个轻松的落点**。一句感想、一个刚想到的点、一个"要不要继续"的
          邀请，都可以；不要逼对方立刻回答。
        - **不重复**。同一件事只提一次；上次主动说过的话不要再搬出来。

        ## 不该主动的时候

        - 上一轮你自己刚说完、用户还没回应 —— 那是"追问"，不是"主动"。
        - 对话里明显在等用户处理正事（报错、正在生成）。
        - 用户明确说过"别打扰我""我要睡了"。

        ## 语气

        与当前人设一致。如果是角色扮演，保持在角色里；如果是普通助手，
        用自己的口吻说话，不要突然变成客服。
    """.trimIndent()

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        runCatching {
            val root = SkillStore.rootDir()
            val seeded = readSeeded(root)
            val fresh = mutableListOf<String>()

            val builtins = listOf(
                Browser to BrowserSkill,
                Timestamp to TimestampSkill,
                Proactive to ProactiveSkill,
            )
            builtins.forEach { (id, body) ->
                val dir = File(root, id)
                if (!dir.isDirectory || !File(dir, "SKILL.md").isFile) {
                    dir.mkdirs()
                    File(dir, "SKILL.md").writeText(body)
                }
                if (id !in seeded) fresh += id
            }

            if (fresh.isNotEmpty()) {
                writeSeeded(root, seeded + fresh)
                // 只对**首次**播种的技能打开开关；已经播过的不碰用户的决定
                fresh.forEach { id ->
                    if (File(root, id).isDirectory) SkillStore.setEnabled(id, true)
                }
            }
            SkillStore.refresh()
            Unit
        }
    }

    /** 技能是否启用。技能列表还没加载时按"未启用"处理 —— 保守，不会误挂工具。 */
    fun enabled(id: String): Boolean =
        SkillStore.skills.value.any { it.id == id && it.enabled }

    private fun readSeeded(root: File): Set<String> {
        val file = File(root, SeedFile)
        if (!file.isFile) return emptySet()
        return runCatching {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeSeeded(root: File, ids: Collection<String>) {
        runCatching {
            File(root, SeedFile).writeText(ids.sorted().joinToString("\n") + "\n")
        }
    }
}
