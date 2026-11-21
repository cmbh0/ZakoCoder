package com.simplechat.app.agent

import androidx.compose.runtime.Immutable
import com.simplechat.app.R
import com.simplechat.app.data.Res
import kotlinx.serialization.Serializable

/**
 * 一个工具调用的风险类别。
 *
 * 分类不按"工具名"而按"**会发生什么**"—— 模型随时能造出新工具（MCP、技能脚本），
 * 而它们的后果只有这几类：读、写、跑、要 root、出网、动屏幕、第三方。
 * 策略按类别判定，就不必每接一个新工具都去补一条规则。
 */
@Serializable
enum class ToolRisk {

    /** 只读：读文件、列目录、读技能。没有副作用，永远自动放行。 */
    READ,

    /** 写盘：写 / 改 / 删文件、建目录。 */
    WRITE,

    /** 执行：跑 shell 命令、跑 Python、跑技能脚本。 */
    EXEC,

    /**
     * 高权限执行：`sudo` / root。
     *
     * 单独成一档而不是并进 [EXEC]：`rm -rf /usr` 和 `ls` 都是"执行命令"，
     * 但一个是把自己锁在门外，一个是看一眼目录。混在一起只有两种结局 ——
     * 要么每条命令都问你，要么全放行。
     */
    PRIVILEGED,

    /** 出网：抓网页、发请求。会把当前上下文里的东西带出去。 */
    NETWORK,

    /** 第三方 MCP 工具。它们背后做什么，本机无法审计。 */
    MCP,

    /** 设备屏幕操作：读屏、点按、输入。 */
    SCREEN,
}

/**
 * 工具审批预设。
 *
 * 三种而不是"一个开关"：真实使用里只有三种人 ——
 * 想每次看清楚再点（普通）、信任文件操作但不想被琐事打断（高权限）、
 * 在隔离环境里放手让它跑（全自动）。
 */
enum class ApprovalMode(val id: String) {

    /** 读自动放行；写、执行、高权限、MCP、屏幕逐次询问。 */
    NORMAL("NORMAL"),

    /** 应用内工具（含 sudo）自动放行；MCP、屏幕仍询问。 */
    ELEVATED("ELEVATED"),

    /** 全部放行。**只能由用户显式确认后进入**，模型与技能都不能自己开。 */
    YOLO("YOLO");

    companion object {
        fun fromId(id: String?): ApprovalMode =
            entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/** 一次待批准的调用。给界面看，也用来回填"它到底要干什么"。 */
@Immutable
data class ApprovalRequest(
    /** 对应的工具调用 id，用于把这块 UI 挂回那张卡片。 */
    val callId: String,
    /** 工具名（`run_shell`、`write_file`……）。 */
    val tool: String,
    val risk: ToolRisk,
    /** 压成一行的摘要：命令首行 / 文件路径。 */
    val summary: String,
    /** 完整参数 JSON。展开卡片时用户要能核对。 */
    val argsJson: String,
)

/** 用户对一次调用的答复。 */
sealed interface ApprovalDecision {

    /** 只放行这一次。 */
    data object Allow : ApprovalDecision

    /** 本次会话内同类风险不再询问。 */
    data object AllowForSession : ApprovalDecision

    /** 拒绝。模型会收到"用户拒绝了"的失败回执，自己决定要不要换个做法。 */
    data object Deny : ApprovalDecision
}

/**
 * 审批门。
 *
 * Runner 不关心界面怎么问 —— 它只知道"这里要停下来等人"。
 * 实现放在 ViewModel：把请求丢进 UI 状态，挂起等一个 Deferred 完成。
 */
fun interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): ApprovalDecision
}

/**
 * 当前生效的审批策略。
 *
 * [sessionAllowed] 是"本次会话内不再问"的记忆 —— 只活在内存里：
 * 用户点过一次"总是允许执行"，意思显然是"这一轮别烦我"，不是"以后永久放开"。
 * 永久的放开就是换预设，那件事必须走 [ApprovalMode]。
 */
@Immutable
data class ApprovalPolicy(
    val mode: ApprovalMode = ApprovalMode.NORMAL,
    val sessionAllowed: Set<ToolRisk> = emptySet(),
) {

    fun needsApproval(risk: ToolRisk): Boolean {
        if (risk in sessionAllowed) return false
        return when (mode) {
            ApprovalMode.YOLO -> false
            // 高权限 = 应用内工具全放，但代外部的两样仍问：
            // MCP 背后是别人的服务，屏幕是用户眼前这台设备。
            ApprovalMode.ELEVATED -> risk == ToolRisk.MCP || risk == ToolRisk.SCREEN
            ApprovalMode.NORMAL -> risk != ToolRisk.READ
        }
    }

    fun allowForSession(risk: ToolRisk): ApprovalPolicy =
        copy(sessionAllowed = sessionAllowed + risk)
}

/** 风险类别 → 一句人话，用在审批条上。 */
fun ToolRisk.label(): String = Res.get(
    when (this) {
        ToolRisk.READ -> R.string.approval_risk_read
        ToolRisk.WRITE -> R.string.approval_risk_write
        ToolRisk.EXEC -> R.string.approval_risk_exec
        ToolRisk.PRIVILEGED -> R.string.approval_risk_privileged
        ToolRisk.NETWORK -> R.string.approval_risk_network
        ToolRisk.MCP -> R.string.approval_risk_mcp
        ToolRisk.SCREEN -> R.string.approval_risk_screen
    },
)
