package com.simplechat.app.ui.cloud

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.simplechat.app.R
import com.simplechat.app.agent.CloudEnv
import com.simplechat.app.agent.CloudEnvs
import com.simplechat.app.agent.RemoteWorkspaces
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.SegmentedSwitch
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.common.PillShape
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.settings.SettingsBarAction
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsTextAction
import com.simplechat.app.ui.settings.SettingsTopBar

/**
 * 云端工作环境。
 *
 * 这一页解决的是"我没有 Ubuntu、也不想装"的场景：把一份几十 KB 的部署包
 * 交给任意一个能部署服务的 AI，它会给你一条公网地址和一个令牌，填回来就能用。
 *
 * 三件刻意的事：
 * - 绑定是**按对话**的（`conversationId`），不是全局的。一个话题一个环境，不串。
 * - 令牌输入框不显示回显（`password`），并且列表里只展示 host，不展示令牌。
 * - "测试连接"必须真的发一次请求 —— 只看格式对不对没有意义，用户要的是"通没通"。
 */
@Composable
fun CloudScreen(
    onBack: () -> Unit,
    conversationId: String?,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val envs by CloudEnvs.list.collectAsState()
    val binding by CloudEnvs.binding.collectAsState()
    val boundId = conversationId?.let { binding[it] }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CloudEnv?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var exporting by remember { mutableStateOf(false) }
    /** 上一次导出的落点。导出成功后一直留着 —— 三颗按钮要反复用同一个文件。 */
    var bundle by remember { mutableStateOf<ExportedBundle?>(null) }
    var deleting by remember { mutableStateOf<CloudEnv?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            // 状态栏的留白由 SettingsTopBar 自己加
            .navigationBarsPadding(),
    ) {
        // 与设置里其它页同一个顶栏；「添加环境」是它右侧的图标动作
        SettingsTopBar(
            title = Res.get(R.string.cloud_title),
            onBack = onBack,
            actions = {
                SettingsBarAction(
                    icon = AppIcons.Plus,
                    contentDescription = Res.get(R.string.cloud_add),
                    onClick = { adding = true },
                )
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = Res.get(R.string.cloud_intro),
                style = MaterialTheme.typography.bodySmall,
                color = colors.placeholder,
            )

            // ── 已配置的环境 ────────────────────────────
            envs.forEach { env ->
                val isBound = env.id == boundId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                        .background(colors.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = AppIcons.Cloud,
                            contentDescription = null,
                            tint = if (isBound) MaterialTheme.colorScheme.primary else colors.placeholder,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = env.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        /*
                         * 接入方式做成小徽标，而不是第二行。
                         *
                         * 两种环境在能力上完全一样，用户唯一需要一眼分清的是
                         * "这条走的是 SSH 还是 HTTP" —— 出问题时排查方向完全不同。
                         */
                        Text(
                            text = if (env.isSsh) "SSH" else "HTTP",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.processText,
                            modifier = Modifier
                                .background(colors.fieldBackground, PillShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        if (isBound) {
                            Text(
                                text = Res.get(R.string.cloud_bound_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = buildString {
                            append(env.hostLabel)
                            if (env.isSsh && env.workdir.isNotBlank()) append(" · ").append(env.workdir)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (conversationId != null) {
                            CloudAction(
                                label = if (isBound) Res.get(R.string.cloud_unbind_here)
                                else Res.get(R.string.cloud_bind_here),
                                primary = !isBound,
                            ) {
                                if (isBound) CloudEnvs.unbind(conversationId)
                                else CloudEnvs.bind(conversationId, env.id)
                            }
                        }
                        CloudAction(
                            label = if (testing == env.id) Res.get(R.string.cloud_testing)
                            else Res.get(R.string.cloud_test),
                        ) {
                            if (testing != null) return@CloudAction
                            testing = env.id
                            testResult = null
                            scope.launch {
                                val attempt = runCatching { RemoteWorkspaces.of(env).ping() }
                                val ok = attempt.getOrDefault(false)
                                testResult = if (ok) {
                                    true to Res.get(R.string.cloud_test_ok)
                                } else {
                                    /*
                                     * 探活失败要给出下一步，而不是一句"失败"。
                                     * 这里只打一次 `echo`：ping 走的是根路径，
                                     * 有些网关不给根路径放行，但 /exec 是通的。
                                     */
                                    val viaExec = runCatching {
                                        RemoteWorkspaces.of(env).exec("echo dsha-ok").stdout.contains("dsha-ok")
                                    }.getOrDefault(false)
                                    if (viaExec) {
                                        true to Res.get(R.string.cloud_test_ok_exec)
                                    } else {
                                        /*
                                         * SSH 的失败原因必须原样给出：密码错、端口不通、
                                         * 用户没权限，三件事的下一步动作完全不同。
                                         */
                                        val reason = attempt.exceptionOrNull()?.message.orEmpty()
                                        false to if (reason.isBlank()) {
                                            Res.get(R.string.cloud_test_fail)
                                        } else {
                                            Res.get(R.string.cloud_test_fail_reason, reason.take(200))
                                        }
                                    }
                                }
                                testing = null
                            }
                        }
                        CloudAction(Res.get(R.string.cloud_edit)) { editing = env }
                        CloudAction(Res.get(R.string.cloud_delete), danger = true) {
                            deleting = env
                        }
                    }
                }
            }

            testResult?.let { (ok, text) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else colors.danger,
                )
            }

            // ── 部署包 ──────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = Res.get(R.string.cloud_deploy_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = Res.get(R.string.cloud_deploy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.placeholder,
                )
                Text(
                    text = Res.get(R.string.cloud_deploy_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.placeholder,
                )

                /*
                 * 只留两条：**复制文本**、保存压缩包。
                 *
                 * 之前是「用其他应用打开 / 分享给 AI / 复制路径」—— 三条都在
                 * 绕开"我要把话交给另一个 AI"这件事：打开文件管理器要用户自己
                 * 找包、解压、再复制；分享只能发文件，对面 AI 还得会解压。
                 * 真正省事的是把 DEPLOY.md 和 server.py **拼成一段文本**塞进剪贴板，
                 * 粘到任何对话框里就能直接用。
                 */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CloudAction(
                        label = if (exporting) {
                            Res.get(R.string.cloud_deploy_working)
                        } else {
                            Res.get(R.string.cloud_deploy_copy_text)
                        },
                        primary = true,
                    ) {
                        if (exporting) return@CloudAction
                        exporting = true
                        scope.launch {
                            val text = runCatching { buildDeployText(context) }.getOrNull()
                            exporting = false
                            if (text.isNullOrBlank()) {
                                toast(context, Res.get(R.string.cloud_deploy_copy_failed))
                                return@launch
                            }
                            copyText(context, text)
                            toast(context, Res.get(R.string.cloud_deploy_copied_text))
                        }
                    }
                    CloudAction(Res.get(R.string.cloud_deploy_save)) {
                        if (exporting) return@CloudAction
                        exporting = true
                        scope.launch {
                            val outcome = runCatching { exportBundle(context) }
                            exporting = false
                            val result = outcome.getOrNull()
                            if (result == null) {
                                /*
                                 * 以前这里直接 return —— 资产缺失（`assets/cloud-workspace.zip`
                                 * 没打进包）时按钮点下去**什么都不会发生**，看起来像"点了没用"。
                                 * 现在把原因带出来：是读不到包，还是写不进目录，一眼能分。
                                 */
                                val reason = outcome.exceptionOrNull()?.message.orEmpty().take(120)
                                toast(
                                    context,
                                    if (reason.isBlank()) {
                                        Res.get(R.string.cloud_deploy_copy_failed)
                                    } else {
                                        Res.get(R.string.cloud_deploy_save_failed, reason)
                                    },
                                )
                                return@launch
                            }
                            bundle = result
                            toast(
                                context,
                                Res.get(R.string.cloud_deploy_saved, result.file.absolutePath),
                            )
                        }
                    }
                }
                /*
                 * 路径要**一直显示**，不是导出后才出现。
                 *
                 * 这段话同时承担"示范"：告诉用户文件会在哪儿、用什么打开、
                 * 打开之后能做什么。老张原话是"需要时会进行示范" —— 那就是
                 * 这里两行小字，不用弹教学浮层。
                 */
                bundle?.let { exported ->
                    Text(
                        text = if (exported.public) {
                            Res.get(R.string.cloud_deploy_path_public, exported.file.absolutePath)
                        } else {
                            Res.get(R.string.cloud_deploy_path_private, exported.file.absolutePath)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (exported.public) colors.placeholder else colors.warning,
                    )
                }
            }

            Text(
                text = Res.get(R.string.cloud_footer),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    /*
     * 删除要确认：环境里存着地址和令牌，删了只能重新找部署它的那个 AI 要。
     * 顺带把"当前对话正绑着它"这件事说出来 —— 删完这条对话会退回本地工作区。
     */
    deleting?.let { env ->
        com.simplechat.app.ui.common.AppConfirmDialog(
            title = Res.get(R.string.cloud_delete_title),
            message = Res.get(
                if (boundId == env.id) R.string.cloud_delete_bound_body else R.string.cloud_delete_body,
                env.name,
            ),
            confirmText = Res.get(R.string.cloud_delete),
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                if (boundId == env.id) conversationId?.let { CloudEnvs.unbind(it) }
                CloudEnvs.remove(env.id)
                deleting = null
            },
        )
    }

    if (adding || editing != null) {
        CloudEnvDialog(
            initial = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onConfirm = { draft ->
                val target = editing
                if (target != null) {
                    // 编辑＝删掉旧的加新的：环境 id 会变，绑定要跟着搬
                    val wasBound = binding.entries.filter { it.value == target.id }.map { it.key }
                    CloudEnvs.remove(target.id)
                    val fresh = CloudEnvs.add(
                        name = draft.name,
                        baseUrl = draft.url,
                        token = draft.token,
                        kind = draft.kind,
                        host = draft.host,
                        port = draft.port,
                        user = draft.user,
                        auth = draft.auth,
                        secret = draft.secret,
                        passphrase = draft.passphrase,
                        workdir = draft.workdir,
                    )
                    wasBound.forEach { CloudEnvs.bind(it, fresh.id) }
                } else {
                    val fresh = CloudEnvs.add(
                        name = draft.name,
                        baseUrl = draft.url,
                        token = draft.token,
                        kind = draft.kind,
                        host = draft.host,
                        port = draft.port,
                        user = draft.user,
                        auth = draft.auth,
                        secret = draft.secret,
                        passphrase = draft.passphrase,
                        workdir = draft.workdir,
                    )
                    /*
                     * 从对话里点进来、且这个会话还没绑任何环境 → 直接绑上。
                     * 用户加完还要再点一次"绑定"，是把一步的事拆成两步。
                     */
                    if (conversationId != null && boundId == null) {
                        CloudEnvs.bind(conversationId, fresh.id)
                    }
                }
                adding = false
                editing = null
            },
        )
    }
}

/** 小号文字按钮，与工作区页保持同一种视觉。 */
@Composable
private fun CloudAction(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            danger -> colors.danger
            primary -> MaterialTheme.colorScheme.primary
            else -> colors.processText
        },
        modifier = Modifier
            // 先裁再上色：水波纹跟着胶囊走，不再是一个露出四角的方块
            .clip(PillShape)
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 添加 / 编辑环境。
 *
 * 顶部先选接入方式：**HTTP 服务**（自建/托管的 cloud-workspace）或
 * **SSH 服务器**（直连一台 Linux）。两种方式下面填的东西完全不同，
 * 所以不是"多一个可选字段"，而是整块切换。
 */
@Composable
private fun CloudEnvDialog(
    initial: CloudEnv?,
    onDismiss: () -> Unit,
    onConfirm: (EnvDraft) -> Unit,
) {
    val colors = LocalChatColors.current
    var kind by remember { mutableStateOf(initial?.kind ?: CloudEnv.KIND_HTTP) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user?.ifBlank { "root" } ?: "root") }
    var auth by remember { mutableStateOf(initial?.auth ?: CloudEnv.AUTH_PASSWORD) }
    var secret by remember { mutableStateOf(initial?.secret.orEmpty()) }
    var passphrase by remember { mutableStateOf(initial?.passphrase.orEmpty()) }
    var workdir by remember { mutableStateOf(initial?.workdir.orEmpty()) }

    val ssh = kind == CloudEnv.KIND_SSH
    val canSave = if (ssh) {
        host.isNotBlank() && user.isNotBlank() && secret.isNotBlank()
    } else {
        url.isNotBlank()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // 弹窗圆角与全应用其它弹窗同一个值（ContainerShape）
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (initial == null) Res.get(R.string.cloud_add) else Res.get(R.string.cloud_edit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SegmentedSwitch(
                options = listOf(CloudEnv.KIND_HTTP, CloudEnv.KIND_SSH),
                selected = kind,
                onSelect = { kind = it },
                label = {
                    if (it == CloudEnv.KIND_SSH) {
                        Res.get(R.string.cloud_kind_ssh)
                    } else {
                        Res.get(R.string.cloud_kind_http)
                    }
                },
            )
            CloudField(
                value = name,
                placeholder = Res.get(R.string.cloud_field_name),
                onValueChange = { name = it },
            )

            if (ssh) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(2f)) {
                        CloudField(
                            value = host,
                            placeholder = Res.get(R.string.cloud_field_host),
                            onValueChange = { host = it },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        CloudField(
                            value = port,
                            placeholder = Res.get(R.string.cloud_field_port),
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        )
                    }
                }
                CloudField(
                    value = user,
                    placeholder = Res.get(R.string.cloud_field_user),
                    onValueChange = { user = it },
                )
                SegmentedSwitch(
                    options = listOf(CloudEnv.AUTH_PASSWORD, CloudEnv.AUTH_KEY),
                    selected = auth,
                    onSelect = { auth = it },
                    label = {
                        if (it == CloudEnv.AUTH_KEY) {
                            Res.get(R.string.cloud_auth_key)
                        } else {
                            Res.get(R.string.cloud_auth_password)
                        }
                    },
                )
                CloudField(
                    value = secret,
                    placeholder = if (auth == CloudEnv.AUTH_KEY) {
                        Res.get(R.string.cloud_field_key)
                    } else {
                        Res.get(R.string.cloud_field_password)
                    },
                    onValueChange = { secret = it },
                    singleLine = auth != CloudEnv.AUTH_KEY,
                    minHeight = if (auth == CloudEnv.AUTH_KEY) 88.dp else 20.dp,
                )
                if (auth == CloudEnv.AUTH_KEY) {
                    CloudField(
                        value = passphrase,
                        placeholder = Res.get(R.string.cloud_field_passphrase),
                        onValueChange = { passphrase = it },
                    )
                }
                CloudField(
                    value = workdir,
                    placeholder = Res.get(R.string.cloud_field_workdir),
                    onValueChange = { workdir = it },
                )
                Text(
                    text = Res.get(R.string.cloud_ssh_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            } else {
                CloudField(
                    value = url,
                    placeholder = Res.get(R.string.cloud_field_url),
                    onValueChange = { url = it },
                )
                CloudField(
                    value = token,
                    placeholder = Res.get(R.string.cloud_field_token),
                    onValueChange = { token = it },
                )
                Text(
                    text = Res.get(R.string.cloud_field_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SettingsTextAction(
                    text = Res.get(R.string.action_cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                /*
                 * 必填项没填完时「保存」是灰的、点不动。
                 *
                 * 此前它一直是亮的，点下去却什么都不发生 —— 用户分不清是没点上、
                 * 卡住了，还是哪一项填错了。
                 */
                SettingsTextAction(
                    text = Res.get(R.string.action_save),
                    primary = true,
                    enabled = canSave,
                    onClick = {
                        onConfirm(
                            EnvDraft(
                                name = name.trim(),
                                kind = kind,
                                url = url.trim(),
                                token = token.trim(),
                                host = host.trim(),
                                port = port.toIntOrNull() ?: 22,
                                user = user.trim(),
                                auth = auth,
                                secret = secret,
                                passphrase = passphrase,
                                workdir = workdir.trim(),
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** 对话框的产物。字段多，用 data class 传比十一个位置参数清楚。 */
private data class EnvDraft(
    val name: String,
    val kind: String,
    val url: String,
    val token: String,
    val host: String,
    val port: Int,
    val user: String,
    val auth: String,
    val secret: String,
    val passphrase: String,
    val workdir: String,
)

@Composable
private fun CloudField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val colors = LocalChatColors.current
    /*
     * 标签与输入框必须在**同一条竖线**上。
     *
     * 上一版这里是两个并列的 composable，自己不成组 —— 放进 Row 之后
     * （「主机 + 端口」那一行），标签被当成 Row 的兄弟子项，和输入框抢同一条
     * 水平位置：字号小的标签被压到输入框底下，看着就是"文字被控件盖住"。
     * 包一层 Column，标签永远在上、输入框永远在下，放哪儿都对。
     */
    Column(modifier = Modifier.fillMaxWidth()) {
    Text(
        text = placeholder,
        style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder,
    )
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 单行是胶囊；多行（私钥）是 20dp 圆角 —— 胶囊拉到五六行高会变成一个大椭圆
            .clip(if (singleLine) PillShape else MenuShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = singleLine,
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = minHeight)) { inner() }
            },
        )
    }
    }
}

/**
 * 内置部署包 → 导出成一份真实文件。
 *
 * 包本身在 `assets/cloud-workspace.zip`（编译器打进去，删 App 才消失），
 * 但 assets 是只读的、也没有路径 —— 而"用其他应用打开"必须给它一条真路径。
 * 所以每次点按钮都覆盖写一份：几十 KB，不值得做版本比对。
 */
private suspend fun exportBundle(context: Context): ExportedBundle = withContext(Dispatchers.IO) {
    val dir = exportDir(context)
    val target = File(dir, "DSHA-云端工作区部署包.zip")
    context.assets.open("cloud-workspace.zip").use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    ExportedBundle(file = target, public = dir != File(context.filesDir, "exports"))
}

/**
 * 导出的落点。
 *
 * 优先公共 `Download/DSHA/` —— 那是 MT 管理器、系统文件管理器唯一能**直接定位**
 * 的地方：按下"用其他应用打开"，它打开的就是这个文件夹，用户能看到文件躺在哪。
 * 私有目录 `/Android/data/<包名>/files/exports/` 在 Android 11+ 上第三方文件管理器
 * 根本进不去（除非有 root），传过去只能看到一个孤零零的流。
 *
 * 拿不到「所有文件访问」权限时回落到私有目录：分享照样能用（FileProvider 会授权），
 * 只是"定位到文件夹"这一步做不到 —— 所以界面上把路径一起显示出来，让用户自己判断。
 */
private fun exportDir(context: Context): File {
    val public = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "DSHA",
    )
    val managed = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        Environment.isExternalStorageManager()
    if (managed) {
        public.mkdirs()
        if (public.canWrite()) return public
    }
    return File(context.filesDir, "exports").apply { mkdirs() }
}

/** 导出结果：文件本体 + 是不是落在公共目录（决定提示文案怎么说）。 */
private class ExportedBundle(val file: File, val public: Boolean)

/**
 * 「用其他应用打开」。
 *
 * 与「分享给 AI」是两件事，所以拆成两个入口：
 *
 * - 分享走 `ACTION_SEND` —— 目标是聊天软件，用户挑一个对象发出去。
 * - 打开走 `ACTION_VIEW` —— 目标是**文件管理器 / 解压工具**（MT 管理器、
 *   ZArchiver…）。MT 收到 `content://` 会解出真实路径、直接跳到它所在目录，
 *   这就是老张要的"直接定位到路径，然后在里面用"。
 *
 * `ClipData` 那两行是必须的：只给 `FLAG_GRANT_READ_URI_PERMISSION` 时，
 * 部分 ROM 上的 MT 仍会报"无法读取"，因为授权是跟着 ClipData 走的。
 */
private fun openWithOtherApp(context: Context, bundle: ExportedBundle) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle.file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/zip")
        clipData = android.content.ClipData.newRawUri(bundle.file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(Intent.EXTRA_TITLE, bundle.file.name)
    }
    /*
     * 没装任何能开 zip 的 App 时 startActivity 会抛 ActivityNotFoundException。
     * 这里不 catch 成静默失败 —— 交给调用方提示"装一个文件管理器"，
     * 比什么都不发生强。
     */
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 拉起系统分享面板。用户自己选发给谁（微信、邮箱、任意 AI 客户端）。 */
private fun shareFile(context: Context, bundle: ExportedBundle) {
    val file = bundle.file
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newRawUri(file.name, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        putExtra(
            Intent.EXTRA_TEXT,
            "把压缩包解开，按里面的 DEPLOY.md 部署到公网，然后告诉我访问地址和令牌。",
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * 把部署包里的说明与代码拼成一段**可直接粘贴的文本**。
 *
 * 只取文本类文件（md / py / sh / json / txt），二进制一律跳过 ——
 * 剪贴板里塞一串乱码既没用，也会让对面模型读偏。
 */
private suspend fun buildDeployText(context: Context): String = withContext(Dispatchers.IO) {
    val builder = StringBuilder()
    /*
     * 开场白直接说"发布"，不说"找一台有公网地址的机器"。
     *
     * 后者把没有公网 IP 的读者逼向临时隧道（cloudflared / ngrok），
     * 而拿到这段文字的 AI 通常自己就有发布能力 —— 一句话就能省掉
     * 一个隔天失效的地址。
     */
    builder.appendLine(
        "请把下面的服务发布到公网，完成后把访问地址和令牌发给我。" +
            "你如果有发布 / 托管能力（能跑一个单端口 HTTP 服务），直接用它；" +
            "临时隧道（cloudflared / ngrok 免费版）重启即换域名，不要当作交付地址。",
    )
    builder.appendLine()
    java.util.zip.ZipInputStream(context.assets.open("cloud-workspace.zip")).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("md", "py", "sh", "json", "txt", "toml", "yaml", "yml")) continue
            val text = zip.readBytes().toString(Charsets.UTF_8)
            builder.appendLine("===== $name =====")
            builder.appendLine(text.trim())
            builder.appendLine()
        }
    }
    builder.toString()
}

/** 把任意文本塞进剪贴板。 */
private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("deploy", text))
}

/** 把绝对路径塞进剪贴板 —— 用户可以粘到 MT 的地址栏，或直接发给 AI。 */
private fun copyPath(context: Context, bundle: ExportedBundle) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", bundle.file.absolutePath))
}

/** 一句轻提示。这里的失败都不值得弹对话框 —— 用户刚点完，焦点还在按钮上。 */
private fun toast(context: Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}
