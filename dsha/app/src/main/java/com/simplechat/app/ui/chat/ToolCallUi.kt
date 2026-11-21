package com.simplechat.app.ui.chat

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import com.simplechat.app.R
import com.simplechat.app.data.Res

/**
 * 一条工具调用在界面上的快照。
 *
 * 与 `AgentToolCall` 分开：那个是协议对象（id + JSON 字符串），这个是**渲染对象**
 * —— 已经把参数压成一行人话、把输出截断、把状态归一成三种。UI 不该碰 JSON。
 */
@Immutable
@Serializable
data class ToolCallUi(
    val id: String,
    /** 原始工具名（`read_file`）。显示时过一层中文映射。 */
    val name: String,
    /** 压成一行的摘要，例如 `notes/report.md`。 */
    val summary: String,
    val status: Status = Status.RUNNING,
    /** 完整输出。收起时不渲染，展开才显示。 */
    val output: String = "",
    val elapsedMs: Long = 0,
    /** 原始参数 JSON。展开时按 key/value 摊开 —— 用户要能核对"它到底传了什么"。 */
    val argsJson: String = "",
    /** 写 / 改文件的行级变更。空表示这个调用不涉及文件内容。 */
    val diff: List<com.simplechat.app.agent.DiffLine> = emptyList(),
    /**
     * 这次调用**产出的图片**（本地绝对路径）。
     *
     * 目前只有 `generate_image` 会填。渲染成图片卡而不是塞进输出文本：
     * 一张图的路径对用户没有意义，他要看的是那张图。
     */
    val images: List<String> = emptyList(),
    /**
     * 这次调用**落盘的文件**（本地绝对路径）。
     *
     * 只作记录：它说明"这步动过哪个文件"，随工具调用一起落库。
     * 消息末尾的产物条**不再读这里** —— 产物是模型自己在正文里 `@路径`
     * 点出来的那几个（见 `Artifacts`），写配置、落临时文件都只是过程。
     */
    val artifacts: List<String> = emptyList(),
    /**
     * 这次调用**实际请求的画面比例**（宽 / 高）。
     *
     * 只对 `generate_image` 有意义。生成中那块占位按它排版 —— 用户说"来张竖版
     * 壁纸"，等待时就该看见一个竖着的框，而不是一个永远 224×152 的横块
     * （那样等十几秒之后突然蹦出一张竖图，观感像"先画错了再换"）。
     */
    val aspectRatio: Float = 1f,
    /**
     * 风险等级。只有需要审批的调用才有值（只读工具永远不会走到审批那一步），
     * 卡片用它显示"要 root 权限"这类徽标 —— 用户点头前得知道自己在同意什么。
     */
    val risk: com.simplechat.app.agent.ToolRisk? = null,
    /**
     * 这个调用**发生在正文的第几个字符之后**。
     *
     * Agent 是"说一句 → 动一次手 → 接着说"的多轮循环。早先所有工具卡都被放在
     * 正文**上方**，读起来像"先列一遍干过什么，最后再说结论"，与真实顺序相反；
     * 而且多轮正文被拼成一大段，第一轮的"我去看看"和第二轮的"看完了"粘在一起。
     *
     * 记下这个偏移量，渲染时按它把正文切成若干段、把工具卡插回真正的位置：
     * ```
     * 段 0 ─ 工具卡 ─ 段 1 ─ 工具卡 ─ 段 2
     * ```
     * 气泡始终是**同一条消息**（`key = id` 不变），所以整个过程不会重建、不闪。
     */
    val afterChars: Int = 0,
    /**
     * 这次调用属于 Agent 的第几轮（0 起）。
     *
     * `-1` = 这条记录来自 2.2.6 及更早版本，没有轮次信息 —— 渲染时退回
     * 旧的「思考全在上、工具卡按正文偏移切」排版。**不能**把老数据当成
     * 第 0 轮：多轮对话里所有工具会挤成一串、所有思考会堆到最上面。
     */
    val round: Int = -1,
    /**
     * 该轮开始时正文已经写到第几个字符。
     *
     * 与 [afterChars]（工具开始时）配合，才能把「思考 → 说话 → 动手」
     * 切回真实顺序：思考挂在本轮正文的**前面**，工具卡挂在正文偏移上。
     */
    val roundStartChars: Int = 0,
    /**
     * 该轮思维链在 `reasoning` 里的段号（按 `ReasoningSeparator` 切分，0 起）。
     *
     * `-1` = 这一轮**没有思考**（模型直接动手）。
     *
     * 不能用 0 表示"没有"：早先某轮没思考、后面某轮才第一次思考时，两轮的
     * 段号都会是 0 —— 后一轮的思考会被错挂到前一轮的工具上面。
     */
    val reasoningIndex: Int = -1,
) {
    @Serializable
    enum class Status {
        /** 正在跑。 */
        RUNNING,

        /**
         * 等用户批准。**不是** RUNNING 的一种：它可能永远不动（用户去干别的了），
         * 也可能直接变成 FAILED（拒绝），转圈的动效在这两种情况下都会误导读者的判断。
         */
        AWAITING,

        OK,
        FAILED,
    }
}

/**
 * 工具名 → 一行中文。
 *
 * 为什么不在工具定义里就写中文：`description` 是给**模型**看的（英文更稳，
 * 各家模型对英文工具描述的理解一致性更好），而这里是给**人**看的。
 * 两件事分开，改文案不会影响调用质量。
 */
fun toolDisplayName(name: String): String = when {
    name == "read_file" -> Res.get(R.string.tool_read_file)
    name == "write_file" -> Res.get(R.string.tool_write_file)
    name == "edit_file" -> Res.get(R.string.tool_edit_file)
    name == "list_dir" -> Res.get(R.string.tool_list_dir)
    name == "run_shell" -> Res.get(R.string.tool_run_shell)
    name == "python" -> Res.get(R.string.tool_python)
    name == "web_fetch" -> Res.get(R.string.tool_web_fetch)
    name == "generate_image" -> Res.get(R.string.tool_generate_image)
    // 本机 Ubuntu 那一族：不映射的话卡片标题会直接显示 `linux_shell` 这种内部名
    name == "linux_shell" -> "Ubuntu 终端"
    name == "install_packages" -> "安装依赖"
    name == "linux_env" -> "Ubuntu 环境状态"
    name.startsWith("mcp__") -> "MCP · " + name.split("__").getOrNull(2).orEmpty()
    else -> name
}

/**
 * 工具调用 → 顶部活动气泡里的**进行时**文案。
 *
 * 和 `toolDisplayName`（名词，用于卡片标题）刻意分开：气泡要回答"它此刻在干什么"，
 * 所以是"正在读取屏幕 / 正在点击 (320, 800)"，不是"屏幕观察"。
 * 屏幕类工具优先显示坐标与输入内容 —— 这两个是用户最能对上号的细节。
 */
fun activityLabel(name: String, argsJson: String): String {
    val args = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            as? kotlinx.serialization.json.JsonObject
    }.getOrNull()

    fun str(key: String): String? =
        (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.takeIf { it.isNotBlank() }

    fun coord(key: String): Int? = str(key)?.toFloatOrNull()?.toInt()

    return when (name) {
        "screen_observe", "screen_screenshot" -> "正在读取屏幕"
        "screen_click", "screen_tap" -> {
            val x = coord("x")
            val y = coord("y")
            if (x != null && y != null) "正在点击 ($x, $y)" else "正在点击"
        }
        "screen_input" -> {
            val text = str("text").orEmpty()
            if (text.isBlank()) "正在输入" else "正在输入「${text.take(12)}」"
        }
        "screen_scroll", "screen_swipe" -> "正在滑动屏幕"
        "screen_back" -> "正在返回"
        "screen_launch" -> "正在打开应用"
        "screen_wait" -> "正在等界面刷新"
        "run_shell" -> "正在执行命令"
        "read_file", "list_dir" -> "正在读文件"
        "write_file", "edit_file" -> "正在写文件"
        "web_fetch" -> "正在打开网页"
        "web_search" -> "正在搜索"
        "python" -> "正在运行 Python"
        "linux_shell" -> "正在 Ubuntu 里执行命令"
        "install_packages" -> "正在安装依赖"
        "linux_env" -> "正在检查 Ubuntu 环境"
        "generate_image" -> "正在绘制图片"
        else -> if (name.startsWith("mcp__")) "正在调用 MCP 工具" else "正在执行 " + toolDisplayName(name)
    }
}

/** 工具名 → 参数里最该显示的那一项。空串表示没有可显示的摘要。 */
fun toolSummary(name: String, argsJson: String): String {
    val args = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonObject
    }.getOrNull() ?: return ""
    fun str(key: String): String? =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    return when (name) {
        "read_file", "write_file", "edit_file", "list_dir" -> str("path").orEmpty()
        "run_shell" -> str("command").orEmpty().lineSequence().firstOrNull().orEmpty()
        "python" -> str("code").orEmpty().lineSequence().firstOrNull()?.take(60).orEmpty()
        "web_fetch" -> str("url").orEmpty()
        else -> str("path") ?: str("query") ?: str("url") ?: str("command") ?: ""
    }.replace("\n", " ").take(80)
}

/**
 * `generate_image` 这次**实际会请求**的画面比例（宽 / 高）。
 *
 * 为什么不直接读 `aspect_ratio` 就完事：`gpt-image-1` 只认三档，模型给它
 * 9:16 时真实请求的是 1024×1536（0.67），占位框若按 0.5625 画，等图出来
 * 会明显变一次。这里走一遍和 [com.simplechat.app.agent.ImageGen.resolveSize]
 * 完全相同的换算，占位与结果才是同一个形状。
 *
 * 非绘图调用恒为 1f（方），调用方不必再判工具名。
 */
fun imageAspectOf(
    name: String,
    argsJson: String,
    model: String?,
    fallbackSize: String?,
): Float {
    if (name != "generate_image") return 1f
    val args = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            as? kotlinx.serialization.json.JsonObject
    }.getOrNull()
    fun str(key: String): String? =
        (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.takeIf { it.isNotBlank() }

    val ratioArg = str("aspect_ratio")
    val sizeArg = str("size")
    val pixels = com.simplechat.app.agent.ImageGen.resolveSize(
        model = model.orEmpty(),
        aspectRatio = ratioArg,
        requestedSize = sizeArg,
        fallback = fallbackSize,
    )
    return pixels?.let { it.first.toFloat() / it.second }
        ?: com.simplechat.app.agent.ImageGen.parseRatio(ratioArg)
        ?: com.simplechat.app.agent.ImageGen.parseRatio(sizeArg)
        ?: 1f
}
