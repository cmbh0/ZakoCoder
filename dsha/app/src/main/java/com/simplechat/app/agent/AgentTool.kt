package com.simplechat.app.agent

import com.simplechat.app.net.ImageInput
import kotlinx.serialization.json.JsonObject

/**
 * 一个可被模型调用的工具。
 *
 * 刻意做成接口而不是枚举：MCP 的工具、Skill 里的脚本工具都要能挂进来，
 * 它们的能力是**运行时才知道**的，枚举写不下。
 */
interface AgentTool {

    /** 模型看到的名字。必须匹配 `^[a-zA-Z0-9_-]{1,64}$`，否则部分网关拒绝。 */
    val name: String

    /** 给模型看的说明。写清楚"什么时候该用"，比写清"做什么"更影响调用质量。 */
    val description: String

    /** 参数的 JSON Schema。 */
    val parameters: JsonObject

    /**
     * 这个工具是否会改动磁盘 / 执行命令。
     *
     * 只读工具可以并发跑（模型一次发三个 `read_file` 很常见）；
     * 写操作必须串行，否则两个 `write_file` 打同一个文件会互相覆盖。
     */
    val mutating: Boolean get() = false

    /**
     * 风险类别，决定这次调用要不要先问用户（见 [ApprovalPolicy]）。
     *
     * 默认按 [mutating] 推断 —— 大多数工具不是读就是写。执行类、高权限类、
     * 第三方类各自覆盖成自己的档位：它们和"写文件"要问的问题不一样。
     */
    val risk: ToolRisk get() = if (mutating) ToolRisk.WRITE else ToolRisk.READ

    /**
     * 这一次调用的实际风险 —— 同一个工具可以因为参数不同而升降档。
     *
     * 唯一的用例是 `run_shell`：`sudo apt install` 和 `ls -la` 是同一个工具，
     * 但它们该不该问用户，答案显然不一样。
     */
    fun riskOf(args: JsonObject): ToolRisk = risk

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome
}

/** 工具执行时的上下文。 */
data class ToolContext(
    /** 工作区根目录。 */
    val workspace: java.io.File,
    /** 本轮对话 id，供按会话隔离的工具使用。 */
    val conversationId: String,
    /**
     * 这一轮 AI 回复的消息 id。
     *
     * 文件快照靠它归组：回滚"到这条消息之前"就是撤销从它开始的所有记录。
     * 没有它，快照只能按时间排，而同一轮里连改十个文件时分不出边界。
     */
    val messageId: String = "",
    /**
     * 云端工作环境。非空时文件工具操作的是那台机器，快照也要走远端读写。
     *
     * 放在上下文里而不是让工具各自持有：`Rewind` 在 [AgentRunner] 统一拦截
     * 写工具，它拿不到具体的工具实例，只能从这里知道"这是哪片磁盘"。
     */
    val remote: RemoteWorkspace? = null,
    /** 快照作用域（`local` / `cloud:<envId>`）。与 [remote] 配套。 */
    val scope: String = "local",
    /** 取消信号。工具内部的长循环应当检查它。 */
    val isActive: () -> Boolean = { true },
)

/** 工具执行结果。 */
sealed interface ToolOutcome {

    /**
     * 成功。[output] 会原样进模型上下文，所以要写得像"回执"而不是"日志"。
     *
     * [diff] 是**给人看的**，不进模型上下文。写 / 改文件的工具把它填上，
     * 界面展开时就能看到"到底动了哪几行" —— 用户的原话是
     * "点开看不到 diff 很难受"。为空表示这个工具没有可展示的变更。
     */
    data class Ok(
        val output: String,
        val diff: List<DiffLine> = emptyList(),
        /**
         * 这个结果附带的图片（目前只有截图识别管线用）。
         *
         * 为什么要挂在工具结果上而不是单独发一条消息：模型发起工具调用后，
         * 紧跟着必须是 tool 结果；图片只能进 **user** 消息（协议限制），
         * 所以由 [AgentRunner] 在 tool 结果之后补一条"这是刚才的屏幕"的
         * user 消息，图与结果在语义上是同一次观察的产物。
         */
        val images: List<ImageInput> = emptyList(),
        /**
         * 这次调用**产出的图片**，给界面显示用，不进模型上下文。
         *
         * 每项是一个**界面能直接加载的来源**：CDN 直链（`https://…`）或
         * App 私有目录里的文件路径。目前唯一的来源是 `generate_image`。
         *
         * 为什么不塞进 `images`：那是要回灌给模型的视觉输入，一张图就是几千
         * token，而生成图往往只需要用户看见、不需要模型再看一遍。
         */
        val files: List<String> = emptyList(),
        /**
         * 这次调用**落盘的文件**（绝对路径）。
         *
         * 与 [files] 的分工：`files` 是"要显示出来的图片"，`artifacts` 是
         * "这步写了哪些文件" —— 报告、脚本、生成的图、打好的包都算。
         *
         * **不直接进产物条**：产物只认模型在正文里 `@路径` 交付的那几个
         * （见 `Artifacts`）。这里是"动过的文件"的账本，写配置、落临时文件
         * 不该在消息末尾长出一排文件条。
         */
        val artifacts: List<String> = emptyList(),
    ) : ToolOutcome

    /** 失败。模型看到 [message] 后通常会自己改参重试。 */
    data class Failed(val message: String) : ToolOutcome

    companion object {
        fun ok(
            output: String,
            diff: List<DiffLine> = emptyList(),
            images: List<ImageInput> = emptyList(),
            files: List<String> = emptyList(),
            artifacts: List<String> = emptyList(),
        ): ToolOutcome = Ok(output.take(MaxOutput), diff, images, files, artifacts)

        fun fail(message: String): ToolOutcome = Failed(message)

        /** 单次工具回执的上限。超过就截断 —— 上下文是共享资源。 */
        private const val MaxOutput = 24_000
    }
}

/** 参数取值的小工具：缺参数、类型不对都返回 null，由调用方给一句人话。 */
fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it != "null" }

fun JsonObject.int(key: String): Int? = str(key)?.trim()?.toIntOrNull()

fun JsonObject.bool(key: String): Boolean? = str(key)?.trim()?.lowercase()?.let {
    when (it) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
