package com.simplechat.app.ui.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.agent.PythonInstaller
import com.simplechat.app.agent.UbuntuEnv
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.SnakeProgressRing
import com.simplechat.app.ui.settings.SettingsTopBar
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.simplechat.app.ui.settings.SettingsTextAction
import com.simplechat.app.ui.settings.SettingsDimens

/**
 * 运行环境。
 *
 * 只有**一块**：Ubuntu 环境 —— 本机的一台"真 Linux"。装了之后 AI 能
 * `apt install`、能编译、能跑任意脚本，Python 也在里面（`python3` 自带）。
 * 代价是一次 30 MB 的下载与约 300 MB 磁盘。
 *
 * Python 不再单列安装项，也不再有一键装常用包：那两条路要么是"只剩装 Python"
 * 的半截环境，要么绕过 apt 往工作区里塞 wheel，出问题时比 Ubuntu 本身还难查。
 * 要什么库，让 AI 在 Ubuntu 里 `apt install` / `pip install` 就行。
 *
 * 界面上只回答两个问题：**有没有**、**要不要现在装**。
 */
@Composable
fun RuntimeScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()

    // ── Ubuntu ───────────────────────────────────────────────
    var ubuntuReady by remember { mutableStateOf(UbuntuEnv.isReady()) }
    /*
     * "文件在" 与 "能跑" 是两件事，界面上要分开：
     * - 安装按钮看 [ubuntuReady]（装好且测出过启动档位）；
     * - 卸载按钮看这个（只要目录还在就给用户一条退路）。
     * 之前两者共用一个布尔，导致"解压成功但启动失败"的环境**卸载不掉** ——
     * 不能跑，又不能删，只能去应用信息里清数据。
     */
    var ubuntuInstalled by remember { mutableStateOf(UbuntuEnv.isInstalled()) }
    var ubuntuLabel by remember { mutableStateOf(UbuntuEnv.versionLabel().orEmpty()) }
    var ubuntuBusy by remember { mutableStateOf(false) }
    var ubuntuProgress by remember { mutableStateOf(0f) }
    var ubuntuStage by remember { mutableStateOf("") }
    var ubuntuError by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val unsupported = remember { UbuntuEnv.unsupportedReason() }

    /*
     * Python 的状态**单独**一行，不跟 Ubuntu 混在一起。
     *
     * 之前 Python 只是"装 Ubuntu 时顺带装一下"，装没装上界面上完全看不出来：
     * 卡片显示"Ubuntu 22.04.3 · 已就绪"，用户在终端里敲 `python3` 却得到
     * command not found —— 两边对不上，用户只能自己猜。
     *
     * 现在 Python 有自己的状态、自己的版本号、自己的按钮。
     */
    var pythonStatus by remember { mutableStateOf<PythonInstaller.Status?>(null) }
    var pythonVersion by remember { mutableStateOf("") }
    var pythonBusy by remember { mutableStateOf(false) }
    var pythonStage by remember { mutableStateOf("") }
    var pythonError by remember { mutableStateOf<String?>(null) }
    var repairBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ubuntuReady = UbuntuEnv.isReady()
            ubuntuInstalled = UbuntuEnv.isInstalled()
            ubuntuLabel = UbuntuEnv.versionLabel().orEmpty()
            pythonStatus = PythonInstaller.diagnose()
            pythonVersion = PythonInstaller.version()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(
            title = Res.get(R.string.runtime_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Ubuntu ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Res.get(R.string.runtime_ubuntu),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (ubuntuBusy) {
                        SnakeProgressRing(
                            progress = ubuntuProgress,
                            size = 22.dp,
                            strokeWidth = 2.5.dp,
                            showPercent = false,
                        )
                    } else {
                        Text(
                            text = when {
                                unsupported != null -> Res.get(R.string.runtime_ubuntu_unsupported)
                                ubuntuReady -> listOfNotNull(
                                    ubuntuLabel.ifBlank { "Ubuntu" },
                                    UbuntuEnv.launchLabel(),
                                ).joinToString(" · ")
                                else -> Res.get(R.string.runtime_ubuntu_absent)
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = when {
                                unsupported != null -> colors.danger
                                ubuntuReady -> colors.processText
                                else -> colors.placeholder
                            },
                        )
                    }
                }

                Spacer(Modifier.size(6.dp))
                Text(
                    text = unsupported
                        ?: Res.get(R.string.runtime_ubuntu_desc),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = colors.placeholder,
                )

                if (ubuntuBusy) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = UbuntuUi.stageText(ubuntuStage, ubuntuProgress),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.processText,
                    )
                }

                ubuntuError?.let { message ->
                    Spacer(Modifier.size(8.dp))
                    /*
                     * 用 SelectionContainer 包住：这段是**给人看的诊断**，
                     * 用户要能把原文复制出来发过来。截图里的文字既不能选也不能复制，
                     * 我们只能靠肉眼辨认，信息量差一大截。
                     */
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = colors.danger,
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CapsuleButton(
                        text = when {
                            ubuntuBusy -> Res.get(R.string.runtime_ubuntu_installing)
                            ubuntuReady -> Res.get(R.string.runtime_ubuntu_reinstall)
                            else -> Res.get(R.string.runtime_ubuntu_install)
                        },
                        enabled = !ubuntuBusy && unsupported == null,
                        onClick = {
                            ubuntuError = null
                            ubuntuBusy = true
                            ubuntuProgress = 0f
                            ubuntuStage = ""
                            scope.launch {
                                val result = UbuntuEnv.install(force = true) { fraction, stage ->
                                    ubuntuProgress = fraction
                                    ubuntuStage = stage
                                }
                                ubuntuBusy = false
                                result.onFailure { ubuntuError = it.message ?: Res.get(R.string.error_install_failed_short) }
                                ubuntuReady = UbuntuEnv.isReady()
                                ubuntuInstalled = UbuntuEnv.isInstalled()
                                ubuntuLabel = UbuntuEnv.versionLabel().orEmpty()
                            }
                        },
                        leadingIcon = AppIcons.Download,
                    )
                    if (ubuntuInstalled && !ubuntuBusy) {
                        Spacer(Modifier.size(10.dp))
                        SettingsTextAction(
                            text = Res.get(R.string.runtime_ubuntu_remove),
                            onClick = { confirmRemove = true },
                            destructive = true,
                        )
                    }
                }
            }

            Spacer(Modifier.size(10.dp))

            // ── Python ───────────────────────────────────────
            if (ubuntuInstalled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                        .background(colors.card)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Res.get(R.string.runtime_python),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (pythonBusy) {
                            SnakeProgressRing(
                                progress = 0f,
                                size = 22.dp,
                                strokeWidth = 2.5.dp,
                                showPercent = false,
                            )
                        } else {
                            Text(
                                text = when {
                                    pythonVersion.isNotBlank() -> pythonVersion
                                    pythonStatus?.ready == true -> Res.get(R.string.runtime_python_ready)
                                    else -> Res.get(R.string.runtime_python_absent)
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (pythonStatus?.ready == true) {
                                    colors.processText
                                } else {
                                    colors.placeholder
                                },
                            )
                        }
                    }

                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = pythonStatus?.summary ?: Res.get(R.string.runtime_python_desc),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = colors.placeholder,
                    )

                    pythonError?.let { message ->
                        Spacer(Modifier.size(8.dp))
                        /*
                         * 用 SelectionContainer 包住：这段是**给人看的诊断**，
                         * 用户要能把原文复制出来发过来。
                         */
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                ),
                                color = colors.danger,
                            )
                        }
                    }

                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CapsuleButton(
                            text = when {
                                pythonBusy -> Res.get(R.string.runtime_python_installing)
                                pythonStatus?.ready == true -> Res.get(R.string.runtime_python_reinstall)
                                else -> Res.get(R.string.runtime_python_install)
                            },
                            enabled = !pythonBusy && !repairBusy,
                            onClick = {
                                pythonError = null
                                pythonBusy = true
                                scope.launch {
                                    val result = PythonInstaller.install { line ->
                                        pythonStage = line
                                    }
                                    pythonBusy = false
                                    result.onFailure { pythonError = it.message ?: Res.get(R.string.error_install_failed_short) }
                                    pythonStatus = PythonInstaller.diagnose()
                                    pythonVersion = PythonInstaller.version()
                                }
                            },
                            leadingIcon = AppIcons.Download,
                        )
                        if (ubuntuInstalled && !pythonBusy && !repairBusy) {
                            Spacer(Modifier.size(10.dp))
                            SettingsTextAction(
                                text = Res.get(R.string.runtime_repair),
                                onClick = {
                                    pythonError = null
                                    repairBusy = true
                                    scope.launch {
                                        val result = UbuntuEnv.repairEnvironment { line ->
                                            pythonStage = line
                                        }
                                        repairBusy = false
                                        result.onFailure { pythonError = it.message ?: Res.get(R.string.error_repair_failed_short) }
                                        ubuntuReady = UbuntuEnv.isReady()
                                        pythonStatus = PythonInstaller.diagnose()
                                        pythonVersion = PythonInstaller.version()
                                    }
                                },
                            )
                        }
                    }
                    if (pythonBusy || repairBusy) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = pythonStage.ifBlank { Res.get(R.string.runtime_working) },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = colors.processText,
                        )
                    }
                }
            }

            Spacer(Modifier.size(4.dp))
        }
    }

    if (confirmRemove) {
        AppConfirmDialog(
            title = Res.get(R.string.runtime_ubuntu_remove),
            message = Res.get(R.string.runtime_ubuntu_remove_hint),
            confirmText = Res.get(R.string.runtime_ubuntu_remove),
            destructive = true,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                scope.launch {
                    UbuntuEnv.uninstall()
                    ubuntuReady = UbuntuEnv.isReady()
                    ubuntuInstalled = UbuntuEnv.isInstalled()
                    ubuntuLabel = ""
                    pythonStatus = null
                    pythonVersion = ""
                    pythonError = null
                }
            },
        )
    }
}

/** 把"现在在干什么"翻成一行给用户看的话。 */
private object UbuntuUi {
    fun stageText(stage: String, progress: Float): String =
        if (stage.isBlank()) Res.get(R.string.runtime_preparing) else "$stage  ${(progress * 100).toInt()}%"
}
