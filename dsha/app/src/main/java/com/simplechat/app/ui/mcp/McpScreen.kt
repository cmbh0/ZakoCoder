package com.simplechat.app.ui.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.mcp.McpServerConfig
import com.simplechat.app.mcp.McpStatus
import com.simplechat.app.mcp.McpStore
import com.simplechat.app.mcp.McpTransportKind
import com.simplechat.app.net.ChatApi
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.rememberFileSaver
import com.simplechat.app.ui.settings.SettingsCard
import com.simplechat.app.ui.settings.SettingsSectionLabel
import com.simplechat.app.ui.settings.SettingsTopBar
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import com.simplechat.app.ui.settings.SettingsTextAction
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsEmptyState
import com.simplechat.app.ui.settings.SettingsHint

/**
 * MCP 服务管理。
 *
 * ### 这一版对齐的是**标准配置形态**，不是某个 App 的界面
 *
 * Hermes / Claude Desktop / Cursor 的 MCP 配置只有两种：
 *
 * ```json
 * { "mcpServers": {
 *     "fs":   { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"], "env": {"KEY":"v"} },
 *     "docs": { "url": "https://mcp.example.com/mcp", "headers": { "Authorization": "Bearer …" } }
 * } }
 * ```
 *
 * 所以编辑器拆成**远程（HTTP）**与**本地（stdio）**两套字段，各自只显示自己需要的；
 * 另外给一个「粘贴 JSON 导入」——用户从任何文档 / 别的客户端复制一段配置就能进来，
 * 不必在四个输入框之间手工搬。
 *
 * ### 本地 MCP 在 Android 上意味着什么
 *
 * 没有 `npx` / `uvx` 的系统镜像跑不起来 Node 或 Python 服务。这里的 stdio 是
 * **交给设备上的 shell 去起进程**，所以命令可以是：
 *
 * - 内置 Ubuntu 里的 `/usr/bin/node /root/server.js`（先在终端里装好）；
 * - Termux 里的 `com.termux/.../bash -lc 'npx …'`；
 * - 或者任何设备上真实存在的可执行文件。
 *
 * 这一点必须写在界面上，否则"填了 npx 但连不上"会被误读成 App 的 bug。
 */
@Composable
fun McpScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()
    val servers by McpStore.servers.collectAsState()
    val status by McpStore.status.collectAsState()
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var importing by remember { mutableStateOf(false) }

    var message by remember { mutableStateOf<String?>(null) }
    val saveJson = rememberFileSaver(mime = "application/json") { ok ->
        message = if (ok) Res.get(R.string.export_done) else Res.get(R.string.error_write_failed)
    }

    fun newDraft(transport: McpTransportKind = McpTransportKind.HTTP) = McpServerConfig(
        id = "mcp" + System.currentTimeMillis().toString().takeLast(6),
        name = "",
        transport = transport,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(title = Res.get(R.string.mcp_title), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 导出结果只在这里说一句：成功不必弹窗，失败要说清是"没写进去"
            message?.let {
                SettingsHint(text = it, modifier = Modifier.padding(vertical = 4.dp))
            }

            if (servers.isEmpty()) {
                /*
                 * 空态只说"这是什么、接下来该点哪"。
                 *
                 * 早先这里并排放了「添加」与「导入」两个按钮 —— 和下面那张管理卡
                 * 完全重复，等于同一件事在一屏里出现两次。现在统一由下面那张卡承载。
                 */
                SettingsCard {
                    // 设置里通用的空态；图标与设置首页「MCP」入口是同一枚
                    SettingsEmptyState(
                        icon = AppIcons.Wrench,
                        title = Res.get(R.string.mcp_empty_title),
                        body = Res.get(R.string.mcp_empty),
                    )
                }
                Spacer(Modifier.height(4.dp))
            } else {
                SettingsSectionLabel(Res.get(R.string.mcp_section_servers))
                SettingsCard {
                    servers.forEachIndexed { index, config ->
                        if (index > 0) McpDivider()
                        McpRow(
                            config = config,
                            state = status[config.id],
                            onToggle = { enabled ->
                                McpStore.setEnabled(config.id, enabled)
                                if (enabled) {
                                    scope.launch { McpStore.connect(config, ChatApi.defaultClient()) }
                                } else {
                                    McpStore.disconnect(config.id)
                                }
                            },
                            onClick = { editing = config },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            /*
             * 三个操作**单独成一张卡**。
             *
             * 早先它们和服务器列表共用一张卡，一条分隔线之隔 —— 看起来像"第四台服务器"，
             * 而且列表越长、越难意识到那是"加新的"。分开之后这一页和别的设置页
             * 是同一个骨架：段标题 → 卡片 → 段标题 → 卡片。
             */
            SettingsSectionLabel(Res.get(R.string.mcp_section_manage))
            SettingsCard {
                if (servers.isEmpty()) {
                    AddRow(Res.get(R.string.mcp_add_first), icon = AppIcons.Plus) { editing = newDraft() }
                } else {
                    AddRow(Res.get(R.string.mcp_add), icon = AppIcons.Plus) { editing = newDraft() }
                }
                McpDivider()
                AddRow(
                    Res.get(R.string.mcp_import_json),
                    icon = AppIcons.FileText,
                ) { importing = true }
                if (servers.isNotEmpty()) {
                    McpDivider()
                    AddRow(
                        Res.get(R.string.mcp_export_all),
                        icon = AppIcons.Download,
                    ) {
                        saveJson("mcp-servers.json", McpStore.exportJson().toByteArray())
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = Res.get(R.string.mcp_footer_hint),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.size(24.dp))
        }
    }

    editing?.let { draft ->
        McpEditor(
            draft = draft,
            isNew = servers.none { it.id == draft.id },
            onDismiss = { editing = null },
            onSave = { updated ->
                McpStore.upsert(updated)
                editing = null
            },
            onDelete = {
                McpStore.remove(draft.id)
                editing = null
            },
            onExport = { config ->
                val name = config.name.ifBlank { config.id }.replace(Regex("[^\\p{L}\\p{N}._-]"), "-")
                saveJson("$name.json", McpStore.exportJson(listOf(config)).toByteArray())
            },
        )
    }

    if (importing) {
        McpImportDialog(
            onDismiss = { importing = false },
            onImport = { list ->
                list.forEach { McpStore.upsert(it) }
                importing = false
            },
        )
    }
}

/**
 * 一个已保存的 MCP 服务。
 *
 * 副标题按连接状态变：连上了写"服务端名 · N 个工具"，失败写原因。
 * 服务端名很重要 —— 用户填的显示名（"我的知识库"）与实际连上的进程未必是一回事，
 * 看不到真名就没法确认自己接的是不是想接的那个。
 */
@Composable
private fun McpRow(
    config: McpServerConfig,
    state: McpStatus?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标列与其它设置页同一套宽度（24dp 槽 + 18dp 图标）：
        // 页与页之间切换时，左边那条看不见的竖线要对得上
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (config.transport == McpTransportKind.HTTP) {
                    AppIcons.Globe
                } else {
                    AppIcons.Terminal
                },
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val (line, warn) = when (state) {
                is McpStatus.Ready -> {
                    val name = state.serverName?.takeIf { it.isNotBlank() } ?: config.name
                    Res.get(R.string.mcp_status_ready, name, state.tools) to false
                }

                McpStatus.Connecting -> Res.get(R.string.mcp_status_connecting) to false
                is McpStatus.Failed -> state.message to true
                else -> (config.url.ifBlank { config.command }).ifBlank {
                    Res.get(R.string.mcp_status_idle)
                } to false
            }
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (warn) MaterialTheme.colorScheme.error else colors.placeholder,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        AppSwitch(checked = config.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun AddRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = AppIcons.Plus,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun McpDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .height(0.7.dp)
            .background(LocalChatColors.current.divider),
    )
}

@Composable
private fun McpSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        color = LocalChatColors.current.placeholder,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun McpTextButton(
    label: String,
    primary: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            // 与 SettingsTextAction 同一种长相（胶囊点按区、同一档字号），只是多一个可选图标
            .clip(PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (primary) MaterialTheme.colorScheme.primary else colors.processText,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(15.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (primary) MaterialTheme.colorScheme.primary else colors.processText,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  编辑器
// ══════════════════════════════════════════════════════════

@Composable
private fun McpEditor(
    draft: McpServerConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onDelete: () -> Unit,
    onExport: (McpServerConfig) -> Unit,
) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var url by remember(draft.id) { mutableStateOf(draft.url) }
    var command by remember(draft.id) { mutableStateOf(draft.command) }
    var args by remember(draft.id) { mutableStateOf(draft.args) }
    var timeout by remember(draft.id) { mutableStateOf(draft.timeoutSeconds.toString()) }
    var transport by remember(draft.id) { mutableStateOf(draft.transport) }

    val headerRows = remember(draft.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(parsePairs(draft.headers, ':'))
            if (isEmpty()) add("" to "")
        }
    }
    val envRows = remember(draft.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(parsePairs(draft.env, '='))
            if (isEmpty()) add("" to "")
        }
    }

    var testing by remember(draft.id) { mutableStateOf(false) }
    var testResult by remember(draft.id) { mutableStateOf<String?>(null) }
    var testOk by remember(draft.id) { mutableStateOf(false) }

    fun build(): McpServerConfig = draft.copy(
        name = name.ifBlank { "MCP" },
        transport = transport,
        url = url.trim(),
        command = command.trim(),
        args = args.trim(),
        headers = encodePairs(headerRows, ':'),
        env = encodePairs(envRows, '='),
        timeoutSeconds = timeout.toIntOrNull()?.coerceIn(5, 300) ?: 60,
    )

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // 弹窗外框与设置里其它弹窗同一套：28dp 圆角、20dp 内边距
                .clip(ContainerShape)
                .background(colors.card)
                .padding(SettingsDimens.DialogPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (isNew) Res.get(R.string.mcp_add) else Res.get(R.string.mcp_edit_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Column(
                Modifier
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                McpField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = Res.get(R.string.mcp_name),
                    singleLine = true,
                    minHeight = 42.dp,
                )

                // 传输方式：两个胶囊。本地/远程是两种完全不同的配置，先选这个。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        McpTransportKind.HTTP to Res.get(R.string.mcp_transport_http),
                        McpTransportKind.STDIO to Res.get(R.string.mcp_transport_stdio),
                    ).forEach { (kind, label) ->
                        val active = transport == kind
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = if (active) MaterialTheme.colorScheme.primary else colors.processText,
                            modifier = Modifier
                                // 先裁再上色：水波纹跟着胶囊走
                                .clip(PillShape)
                                .background(if (active) colors.capsuleSelected else colors.fieldBackground)
                                .clickable {
                                    transport = kind
                                    testResult = null
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                if (transport == McpTransportKind.HTTP) {
                    McpField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = Res.get(R.string.mcp_endpoint),
                        singleLine = true,
                        minHeight = 42.dp,
                    )
                    McpKeyValueEditor(
                        title = Res.get(R.string.mcp_headers),
                        rows = headerRows,
                        keyHint = "Authorization",
                        valueHint = "Bearer …",
                        addLabel = Res.get(R.string.mcp_add_header),
                    )
                } else {
                    McpField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = Res.get(R.string.mcp_command),
                        singleLine = true,
                        minHeight = 42.dp,
                    )
                    McpField(
                        value = args,
                        onValueChange = { args = it },
                        placeholder = Res.get(R.string.mcp_args),
                        singleLine = true,
                        minHeight = 42.dp,
                    )
                    McpKeyValueEditor(
                        title = Res.get(R.string.mcp_env),
                        rows = envRows,
                        keyHint = "API_KEY",
                        valueHint = "…",
                        addLabel = Res.get(R.string.mcp_add_env),
                    )
                    Text(
                        text = Res.get(R.string.mcp_stdio_hint),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                        color = colors.placeholder,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Res.get(R.string.mcp_timeout),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = colors.processText,
                        modifier = Modifier.weight(1f),
                    )
                    McpField(
                        value = timeout,
                        onValueChange = { timeout = it.filter { ch -> ch.isDigit() }.take(3) },
                        placeholder = "60",
                        singleLine = true,
                        minHeight = 36.dp,
                        modifier = Modifier.width(84.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = Res.get(R.string.mcp_seconds),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.placeholder,
                    )
                }

                Text(
                    text = Res.get(R.string.mcp_protocol_hint),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                    color = colors.placeholder,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    McpTextButton(
                        if (testing) Res.get(R.string.mcp_testing) else Res.get(R.string.mcp_test),
                    ) {
                        if (testing) return@McpTextButton
                        val config = build()
                        if (config.transport == McpTransportKind.HTTP && config.url.isBlank()) {
                            testOk = false
                            testResult = Res.get(R.string.mcp_need_url)
                            return@McpTextButton
                        }
                        if (config.transport == McpTransportKind.STDIO && config.command.isBlank()) {
                            testOk = false
                            testResult = Res.get(R.string.mcp_need_command)
                            return@McpTextButton
                        }
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = McpStore.test(config, ChatApi.defaultClient())
                            testOk = result.isSuccess
                            testResult = result.fold(
                                onSuccess = { (server, tools) ->
                                    val head = Res.get(R.string.mcp_test_ok, server ?: config.name, tools.size)
                                    if (tools.isEmpty()) head
                                    else head + "\n" + tools.take(8).joinToString("、") +
                                        if (tools.size > 8) " …" else ""
                                },
                                onFailure = { Res.get(R.string.mcp_test_fail, it.message ?: "") },
                            )
                            testing = false
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }

                testResult?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = if (testOk) MaterialTheme.colorScheme.primary else colors.danger,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isNew) {
                    SettingsTextAction(
                        text = Res.get(R.string.action_delete),
                        onClick = onDelete,
                        destructive = true,
                        compact = true,
                    )
                }
                if (!isNew) {
                    SettingsTextAction(
                        text = Res.get(R.string.skills_export),
                        onClick = { onExport(build()) },
                        primary = true,
                        compact = true,
                    )
                }
                Spacer(Modifier.weight(1f))
                SettingsTextAction(
                    text = Res.get(R.string.action_cancel),
                    onClick = onDismiss,
                    compact = true,
                )
                Spacer(Modifier.width(4.dp))
                SettingsTextAction(
                    text = Res.get(R.string.action_save),
                    onClick = { onSave(build()) },
                    primary = true,
                    compact = true,
                )
            }
        }
    }
}

/**
 * 键值对编辑器。
 *
 * 为什么不用一个大文本框：请求头与环境变量本质是**一组键值**，写成
 * `Authorization: Bearer x` 的多行文本时，漏一个冒号就会静默变成"没有这个头"，
 * 用户只能看着连接失败猜。拆成两个格子，键和值各自有 placeholder。
 */
@Composable
private fun McpKeyValueEditor(
    title: String,
    rows: MutableList<Pair<String, String>>,
    keyHint: String,
    valueHint: String,
    addLabel: String,
) {
    val colors = LocalChatColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colors.placeholder,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
        rows.forEachIndexed { index, (key, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                McpField(
                    value = key,
                    onValueChange = { rows[index] = it to rows[index].second },
                    placeholder = keyHint,
                    singleLine = true,
                    minHeight = 36.dp,
                    modifier = Modifier.weight(0.42f),
                )
                Spacer(Modifier.width(6.dp))
                McpField(
                    value = value,
                    onValueChange = { rows[index] = rows[index].first to it },
                    placeholder = valueHint,
                    singleLine = true,
                    minHeight = 36.dp,
                    modifier = Modifier.weight(0.58f),
                )
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (rows.size == 1) rows[0] = "" to "" else rows.removeAt(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = Res.get(R.string.action_delete),
                        tint = colors.placeholder,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Text(
            text = addLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { rows.add("" to "") }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** 弹窗里的输入框：浅灰底、无描边。 */
@Composable
private fun McpField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            // 单行是胶囊、多行是 20dp 圆角：与设置里其它输入框同一套
            .clip(if (singleLine) PillShape else MenuShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  JSON 导入
// ══════════════════════════════════════════════════════════

@Composable
private fun McpImportDialog(
    onDismiss: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit,
) {
    val colors = LocalChatColors.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // 弹窗外框与设置里其它弹窗同一套：28dp 圆角、20dp 内边距
                .clip(ContainerShape)
                .background(colors.card)
                .padding(SettingsDimens.DialogPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = Res.get(R.string.mcp_import_json),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Res.get(R.string.mcp_import_hint),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = colors.placeholder,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 300.dp)
                    .clip(MenuShape)
                    .background(colors.fieldBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "{\n  \"mcpServers\": {\n    \"docs\": { \"url\": \"https://…/mcp\", \"headers\": { \"Authorization\": \"Bearer …\" } }\n  }\n}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = colors.placeholder,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.danger,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                McpTextButton(Res.get(R.string.action_cancel)) { onDismiss() }
                Spacer(Modifier.width(4.dp))
                McpTextButton(Res.get(R.string.mcp_import_action), primary = true) {
                    val parsed = runCatching { parseMcpJson(text) }
                        .getOrElse {
                            error = Res.get(R.string.mcp_import_bad, it.message ?: "")
                            return@McpTextButton
                        }
                    if (parsed.isEmpty()) {
                        error = Res.get(R.string.mcp_import_empty)
                    } else {
                        onImport(parsed)
                    }
                }
            }
        }
    }
}

/**
 * 解析标准 `mcpServers` 配置。
 *
 * 接受三种常见形态：
 * 1. `{ "mcpServers": { "name": {...} } }`（Claude Desktop / Hermes / Cursor）；
 * 2. 直接是 server map `{ "name": {...} }`；
 * 3. 单个 server 对象 `{ "command": ..., "args": [...] }`（对话里抄来的一段）。
 *
 * 不认识的键**忽略**而不是报错：标准里还有 `timeout` / `alwaysAllow` 等，
 * 我们只取能映射到本客户端的字段。只校验"至少有一个能连的入口"。
 */
private fun parseMcpJson(raw: String): List<McpServerConfig> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()

    val map: Map<String, JsonObject> = when {
        root["mcpServers"] is JsonObject ->
            (root["mcpServers"] as JsonObject).mapNotNull { (k, v) ->
                (v as? JsonObject)?.let { k to it }
            }.toMap()

        root["command"] is JsonPrimitive || root["url"] is JsonPrimitive ->
            mapOf(((root["name"] as? JsonPrimitive)?.content ?: "MCP") to root)

        else -> root.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
    }

    return map.mapNotNull { (key, obj) ->
        val command = (obj["command"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val url = (obj["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (command.isBlank() && url.isBlank()) return@mapNotNull null

        val args = (obj["args"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.joinToString(" ")
            .orEmpty()

        val env = (obj["env"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { "$k=$it" } }
            ?.joinToString("\n")
            .orEmpty()

        val headers = (obj["headers"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { "$k: $it" } }
            ?.joinToString("\n")
            .orEmpty()

        val timeout = (obj["timeout"] as? JsonPrimitive)?.let { primitive ->
            primitive.content.toIntOrNull()
                ?: primitive.content.toDoubleOrNull()?.toInt()
        } ?: 60

        McpServerConfig(
            id = "mcp" + System.currentTimeMillis().toString().takeLast(6) +
                (key.hashCode() and 0xFF),
            name = key.ifBlank { "MCP" },
            transport = if (command.isNotBlank()) McpTransportKind.STDIO else McpTransportKind.HTTP,
            url = url,
            command = command,
            args = args,
            env = env,
            headers = headers,
            timeoutSeconds = timeout.coerceIn(5, 300),
            enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }
}

/** `K: V` / `K=V` 文本 ↔ 键值行。空行与缺分隔符的行直接丢掉。 */
private fun parsePairs(raw: String, separator: Char): List<Pair<String, String>> =
    raw.lineSequence().mapNotNull { line ->
        val idx = line.indexOf(separator)
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toList()

private fun encodePairs(rows: List<Pair<String, String>>, separator: Char): String =
    rows.filter { it.first.isNotBlank() }
        .joinToString("\n") { (key, value) ->
            if (separator == ':') "$key: $value" else "$key=$value"
        }
