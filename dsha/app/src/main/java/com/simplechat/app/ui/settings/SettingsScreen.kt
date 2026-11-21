package com.simplechat.app.ui.settings

import android.content.Intent
import java.io.File
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import com.simplechat.app.BuildConfig
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.BubbleShapeOut
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.data.AppLanguage
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.Res
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.userMessage
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.ModelResolver
import com.simplechat.app.net.normalizeBaseUrl
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.net.DeepSeekTts
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.BrandBadge
import com.simplechat.app.ui.common.BrandIcons
import com.simplechat.app.ui.common.ModelBadge
import com.simplechat.app.ui.common.ModelMarks
import com.simplechat.app.ui.common.ProviderBadge
import com.simplechat.app.ui.common.ProfileAvatar
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuCheck
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.chat.ModelPickerDialog
import com.simplechat.app.ui.chat.Preset
import com.simplechat.app.ui.chat.SpeechVoice
import com.simplechat.app.ui.chat.loadSpeechVoices
import com.simplechat.app.ui.chat.rememberSpeechOutput
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.screenTransform
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import com.simplechat.app.ui.theme.ThemeMode
import com.simplechat.app.ui.theme.primary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置页内的一级 / 二级页面。
 *
 * [depth] 只用来判断转场方向：二级页从右侧进来，返回时原路退回。
 */
private enum class SettingsPage(val depth: Int) {
    ROOT(0),
    DATA(1),
    API(1),
    PRESETS(1),
    FONT(1),
    /** 朗读音色（系统 TTS）。 */
    VOICE(1),
    /** 定时任务。 */
    SCHEDULE(1),
    /** 人设编辑。 */
    PERSONA(1),
    /** 上下文压缩 + 记忆检索的可调参数。 */
    TUNING(1),
    /** 绘图 API 配置。 */
    IMAGE(1),
    ABOUT(1),
}

private sealed interface SettingsDialog {
    data object Provider : SettingsDialog
    data object BaseUrl : SettingsDialog
    data object Model : SettingsDialog
    /** 勾选"要出现在切换气泡里"的模型（多选）。 */
    data object PinnedModels : SettingsDialog
    /** 手动填写模型 id：端点没实现 `/models`、或列表里没有目标模型时的出口。 */
    data object ModelManual : SettingsDialog
    /** 模型配置列表（多套端点之间的切换 / 新增 / 重命名 / 删除）。 */
    data object Profiles : SettingsDialog
    data object ProfileNew : SettingsDialog
    /** 重命名**指定的**那套配置（不一定是当前在用的）。 */
    data class ProfileName(val profileId: String) : SettingsDialog
    /** 删除**指定的**那套配置。 */
    data class ProfileDelete(val profileId: String) : SettingsDialog
    data object ApiKey : SettingsDialog
    data object DeleteAll : SettingsDialog
    data object ImportMode : SettingsDialog
    /** 协议通道（自动 / OpenAI 兼容 / Anthropic 兼容）。 */
    data object ApiStyle : SettingsDialog
    /** 上下文档位（128K / 256K / 1M / 自定义）。 */
    data object Context : SettingsDialog
    /** 自定义上下文窗口的数值输入。 */
    data object ContextCustom : SettingsDialog
    /** 最大输出档位。 */
    data object MaxOutput : SettingsDialog
    /** 地区（时间地点提醒里的地点那一项）。 */
    data object Location : SettingsDialog
    /** 编辑预设；[preset] 为 null 表示新建。 */
    data class PresetEditor(val preset: Preset?) : SettingsDialog

    /**
     * 从文档导入的预设草稿。
     *
     * 走**同一个编辑器**而不是直接存进去：导入的正文可能很长、名字可能
     * 取错了，用户看一眼、改两笔再保存，比"导进来才发现不对再删掉"好。
     */
    data class PresetImport(val draft: com.simplechat.app.data.PresetImport.Draft) : SettingsDialog
    data class DeletePreset(val preset: Preset) : SettingsDialog
}

/**
 * 协议通道的**可选档位**（持久化的是枚举名）。
 *
 * 与 `net.ApiStyle` 刻意分开：那个是"实际走哪条"；这里的 `AUTO` 是用户意图
 *（"你看着办"），落库后由 `ApiStyle.resolve` 按端点解析。
 * 把 `AUTO` 塞进 `ApiStyle` 会让"实际通道"变成可选值，到处都是 null 判断。
 */
private enum class ApiStylePreference(val labelRes: Int) {
    AUTO(R.string.api_style_auto),
    OPENAI(R.string.api_style_openai),
    ANTHROPIC(R.string.api_style_anthropic),
    /** ChatGPT Codex / GPT-5 系使用的 Responses 协议。 */
    RESPONSES(R.string.api_style_responses),
    ;

    companion object {
        fun fromId(id: String?): ApiStylePreference =
            entries.firstOrNull { it.name == id } ?: AUTO
    }
}

/** 上下文档位的中文/英文标签资源。 */
private fun ModelResolver.ContextPreset.labelRes(): Int = when (this) {
    ModelResolver.ContextPreset.AUTO -> R.string.context_auto
    ModelResolver.ContextPreset.K128 -> R.string.context_128k
    ModelResolver.ContextPreset.K256 -> R.string.context_256k
    ModelResolver.ContextPreset.M1 -> R.string.context_1m
    ModelResolver.ContextPreset.CUSTOM -> R.string.context_custom
}

/** 字号档位。与官方一致：中间一档为「标准」。 */private val fontSizeSteps = listOf(
    0.85f to R.string.font_step_small,
    0.925f to R.string.font_step_smaller,
    1.0f to R.string.font_step_standard,
    1.10f to R.string.font_step_larger,
    1.20f to R.string.font_step_large,
    1.30f to R.string.font_step_largest,
)

/**
 * 设置页。
 *
 * 一级列表只放**入口**，具体编辑都进二级页面 —— 一级列表保持一屏内可扫完。
 * 外观例外：只有三个互斥选项，弹个菜单比切页面快。
 *
 * 每个一级入口配**各自不同**的图标（数据管理 / API 配置 / 外观 / 字体大小 /
 * 自动折叠思考 / 关于）；二级页面里的行不再配图标，避免图标语义打架。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /* 二级页面的出口。默认空实现，旧调用点（预览 / 测试）不必跟着改。 */
    onOpenSkills: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenRuntime: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenScreen: () -> Unit = {},
    onOpenCloud: () -> Unit = {},
    /* 设置首页「我」那一组要去的地方。 */
    onOpenProfile: () -> Unit = {},
    /** 关于页重看首次启动的功能导览。 */
    onReplayOnboarding: () -> Unit = {},
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()
    val store = container.settingsStore
    val repository = container.chatRepository

    var stored by remember { mutableStateOf(StoredSettings()) }
    // rememberSaveable：换语言会 recreate()（见 MainActivity），二级页要停在原地
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<StatusMessage?>(null) }
    // 「模型」弹窗里的拉取状态：拉取中 / 拉取结果都只在这个弹窗里显示
    var modelFetching by remember { mutableStateOf(false) }
    var modelHint by remember { mutableStateOf<StatusMessage?>(null) }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<File?>(null) }
    // 导入进度：null = 没在导入；0..1 = 解析进度（拷贝阶段是 indeterminate）
    val importProgress = remember { MutableStateFlow<Float?>(null) }
    val importPercent by importProgress.collectAsState()
    // 读文件 / 开包阶段：没有可用进度，界面给循环进度条 + 「读取中」
    var importReading by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }

    /*
     * 朗读音色。
     *
     * - 清单要**临时起一个 TTS 引擎**才拿得到（见 loadSpeechVoices），所以只在
     *   进二级页时读一次，读到就缓存到页面关闭；
     * - [speaker] 只用于「试听」与选完顺手念一句，不参与对话页的朗读。
     */
    var speechVoices by remember { mutableStateOf<List<SpeechVoice>?>(null) }

    /*
     * DeepSeek 官方 TTS（/v1/tools/tts，音色 stella / tide）。
     *
     * 只在「服务商 = DeepSeek 官方 + Base URL 指向官方域 + Key 已填」时可用 ——
     * 那个端点是官方域专有的。可用时列在音色页最前面；不可用时整块不出现，
     * 只留一句说明（不支持的 API 不做入口，也不在点了之后才报错）。
     */
    val officialTtsConfig = remember(stored.providerId, stored.apiKey) {
        if (DeepSeekTts.isOfficialTtsVoiceAvailable(stored.providerId, stored.apiKey)) {
            DeepSeekTts.Config(stored.apiKey)
        } else {
            null
        }
    }
    val officialVoices = remember(officialTtsConfig) {
        if (officialTtsConfig == null) {
            emptyList()
        } else {
            DeepSeekTts.Voice.entries.map { voice ->
                SpeechVoice(
                    name = DeepSeekTts.storageNameOf(voice.id),
                    label = Res.get(voice.labelRes),
                    // 官方合成走网络（不是设备引擎），标"需联网"符合用户预期
                    needsNetwork = true,
                )
            }
        }
    }

    // 自定义语音 API：配好端点与模型才算可用（见 StoredSettings.hasCustomTts）
    val customTtsConfig = remember(
        stored.ttsApiUrl,
        stored.ttsApiKey,
        stored.ttsApiModel,
        stored.ttsApiVoice,
        stored.ttsApiFormat,
        stored.ttsApiSpeed,
        stored.ttsApiHeaders,
    ) {
        if (stored.hasCustomTts) {
            com.simplechat.app.net.CustomTts.Config(
                url = stored.ttsApiUrl,
                apiKey = stored.ttsApiKey,
                model = stored.ttsApiModel,
                voice = stored.ttsApiVoice,
                format = stored.ttsApiFormat,
                speed = stored.ttsApiSpeed,
                headers = stored.ttsApiHeaders,
            )
        } else {
            null
        }
    }

    val speaker = rememberSpeechOutput(
        voiceName = stored.ttsVoiceName,
        officialConfig = officialTtsConfig,
        channel = stored.resolvedTtsChannel,
        customConfig = customTtsConfig,
    )
    /** 朗读相关的报错（引擎缺失 / 合成失败），显示在音色页的说明位置。 */
    var speechError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(speaker.error) {
        speaker.error?.let {
            speechError = it
            speaker.consumeError()
        }
    }
    LaunchedEffect(page) {
        if (page == SettingsPage.VOICE && speechVoices == null) {
            speechError = null
            /*
             * 加超时兜底：个别设备的 TTS 引擎初始化回调可能**永远不来**
             * （引擎被禁用 / 服务被冻结）。不设限的话这一页会永远停在「正在读取…」，
             * 而用户完全不知道该怎么办。超时按"没有可用引擎"处理，至少给一句提示。
             */
            speechVoices = withTimeoutOrNull(TtsVoiceLoadTimeoutMs) {
                loadSpeechVoices(context)
            } ?: emptyList()
        }
    }

    LaunchedEffect(Unit) { store.flow.collect { stored = it } }

    /*
     * 换了配置 / 服务商 / 地址 / Key，上一次「测试连接」的结论就不再成立。
     * 不清掉的话，一句「连接成功」会一直挂在一个根本没测过的端点下面。
     */
    LaunchedEffect(stored.activeProfileId, stored.providerId, stored.baseUrl, stored.apiKey) {
        testResult = null
    }
    LaunchedEffect(Unit) { repository.observePresets().collect { presets = it } }

    // 二级页面按返回键先回一级，而不是直接退出设置
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    val provider = runCatching { ProviderKind.valueOf(stored.providerId) }
        .getOrDefault(ProviderKind.DEEPSEEK)

    val themeMode = runCatching { ThemeMode.valueOf(stored.themeModeId) }
        .getOrDefault(ThemeMode.SYSTEM)

    val accentColor = runCatching { AccentColor.valueOf(stored.accentColorId) }
        .getOrDefault(AccentColor.BLUE)

    // ── 文件读写：导出 / 导入 ─────────────────────────────

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = repository.exportBackup()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error(Res.get(R.string.error_write_failed))
                }
            }
            dataMessage = outcome.fold(
                onSuccess = { Res.get(R.string.export_done) },
                onFailure = { Res.get(R.string.export_failed, it.userMessage()) },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            /*
             * **流式**落到缓存文件，不再整包读进内存。
             *
             * 实测一份 228MB 的 DeepSeek 导出包：`readBytes()` 会直接 OOM，
             * 或者卡到用户以为"点了没反应"。这里改成按块拷贝 —— 内存占用是常量，
             * 之后 `ZipFile` 直接按中央目录读这个文件。
             */
            importReading = true
            dataMessage = null
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "import-${System.currentTimeMillis()}.bak")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
                    } ?: error(Res.get(R.string.import_read_failed))
                    target
                }.getOrNull()
            }
            if (file == null || file.length() == 0L) {
                file?.delete()
                importReading = false
                dataMessage = Res.get(R.string.import_read_failed)
            } else {
                dataMessage = null
                importProgress.value = null
                importReading = false
                pendingImport = file
                dialog = SettingsDialog.ImportMode
            }
        }
    }

    fun startExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        dataMessage = null
        exportLauncher.launch("simplechat-$stamp.json")
    }

    /*
     * ZIP 归档：同一份备份 + 每个会话一份 Markdown 文稿。
     *
     * 与 JSON 导出并列而不是替换：JSON 好读好 diff，ZIP 好传好归档
     * （一个大文件里既有可导入的数据，也有能直接看的文稿）。
     */
    val archiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = repository.exportArchive()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error(Res.get(R.string.error_write_failed))
                }
            }
            dataMessage = outcome.fold(
                onSuccess = { Res.get(R.string.export_done) },
                onFailure = { Res.get(R.string.export_failed, it.userMessage()) },
            )
        }
    }

    fun startArchiveExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        dataMessage = null
        archiveLauncher.launch("simplechat-$stamp.zip")
    }

    // ── 页面 ──────────────────────────────────────────────

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.settingsBackground),
    ) {
        SettingsTopBar(
            title = when (page) {
                SettingsPage.ROOT -> stringResource(R.string.settings_title)
                SettingsPage.DATA -> stringResource(R.string.settings_data)
                SettingsPage.API -> stringResource(R.string.settings_api)
                SettingsPage.PRESETS -> stringResource(R.string.settings_presets)
                SettingsPage.FONT -> stringResource(R.string.settings_font_size)
                SettingsPage.VOICE -> stringResource(R.string.tts_voice)
                SettingsPage.SCHEDULE -> stringResource(R.string.schedule_title)
                SettingsPage.PERSONA -> stringResource(R.string.persona_title)
                SettingsPage.TUNING -> stringResource(R.string.tuning_title)
                SettingsPage.IMAGE -> stringResource(R.string.image_api_entry)
                SettingsPage.ABOUT -> stringResource(R.string.settings_about)
            },
            onBack = { if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT },
        )

        /*
         * 二级页面从右侧滑入、返回原路退回（见 ui/common/Motion.kt）。
         *
         * 滚动容器放在 AnimatedContent **里面**，有两个原因：
         *   1. 转场要在**整屏宽**上滑，放在外层 16dp 内边距里会从内容边缘"挤"出来；
         *   2. 顺带修掉一个真问题 —— 六个页面原先共用同一个 scroll state，
         *      从「设置」滚到底再进「数据管理」，进去就是滚到底的。
         *      现在每页各有一份，且用 SaveableStateHolder 存档，来回切不丢位置。
         */
        val pageStates = rememberSaveableStateHolder()

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                screenTransform(forward = targetState.depth > initialState.depth)
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = "settingsPage",
        ) { target ->
            pageStates.SaveableStateProvider(target) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        /*
                         * 左右 16dp：与消息区、输入栏同一条边线。
                         *
                         * 之前是 20dp，想"离屏幕远一点显得高级"，实际是从对话页点进
                         * 设置时，卡片左缘比输入框左缘缩进 4dp —— 一次点击之后整页
                         * 横向"错位"，那种说不上来的别扭就来自这里。
                         */
                        .padding(horizontal = 16.dp),
                ) {
                    when (target) {
                        SettingsPage.ROOT -> RootPage(
                            stored = stored,
                            presetCount = presets.size,
                            themeMode = themeMode,
                            accentColor = accentColor,
                            language = AppLanguage.fromId(stored.languageId),
                            onOpen = { page = it },
                            onThemeChange = { scope.launch { store.setThemeMode(it.name) } },
                            onAccentChange = { scope.launch { store.setAccentColor(it.name) } },
                            onLanguageChange = { scope.launch { store.setLanguage(it.name) } },
                            onToggleAutoCollapse = {
                                scope.launch { store.setAutoCollapseThinking(it) }
                            },
                            onOpenSkills = onOpenSkills,
                            onOpenMcp = onOpenMcp,
                            onOpenRuntime = onOpenRuntime,
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenPermissions = onOpenPermissions,
                            onOpenScreen = onOpenScreen,
                            onOpenCloud = onOpenCloud,
                            onToggleAutoRead = { scope.launch { store.setAutoReadAloud(it) } },
                            onOpenProfile = onOpenProfile,
                            onOpenPersona = { page = SettingsPage.PERSONA },
                        )

                        SettingsPage.DATA -> DataPage(
                            message = dataMessage,
                            progress = importPercent,
                            reading = importReading,
                            onExport = { startExport() },
                            onExportArchive = { startArchiveExport() },
                            onImport = {
                                // 全放行：Chatbox 的备份是 .zip，有的设备报的 MIME 还很不标准。
                                // 真正的判定放在解析那一步，认不出来会给出明确原因。
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            onDeleteAll = { dialog = SettingsDialog.DeleteAll },
                        )

                        SettingsPage.API -> ApiPage(
                            stored = stored,
                            provider = provider,
                            testing = testing,
                            testResult = testResult,
                            onPick = { dialog = it },
                            onTest = {
                                if (testing) return@ApiPage
                                // 没填 Key 就不必发请求了：结果一定是 401，用户看到的却是一句
                                // "连接失败：…" —— 直接说缺什么更有用
                                if (provider.requiresApiKey && !stored.hasKey) {
                                    testResult = StatusMessage(
                                        Res.get(R.string.api_test_need_key),
                                        SettingsTone.Warning,
                                    )
                                    return@ApiPage
                                }
                                testing = true
                                testResult = null
                                // 记下测的是哪一套：请求在路上时，用户可能已经切到别的配置了
                                val probedProfile = stored.activeProfileId
                                val probedUrl = stored.baseUrl.ifBlank { provider.defaultBaseUrl }
                                val probedKey = stored.apiKey
                                scope.launch {
                                    /*
                                     * 两道超时：OkHttp 的 25 秒在 [chatApi.probeClient] 里，
                                     * 这里再加一道协程兜底 —— 客户端超时管不到 DNS 卡死、
                                     * 也管不到某些 ROM 把 socket 挂住的情况。
                                     *
                                     * 没有这道兜底时，一次挂起的 `/models` 会让按钮
                                     * 永远停在「测试中」；关掉设置再打开，请求仍在后台活着。
                                     */
                                    val result = withTimeoutOrNull(30_000L) {
                                        container.chatApi.fetchModels(
                                            baseUrl = probedUrl,
                                            apiKey = probedKey,
                                        )
                                    }
                                    testing = false
                                    /*
                                     * 结果回来时已经不是那套配置了：既不落库（会把 A 的模型列表
                                     * 写进 B），也不显示（「连接成功」会挂在一个没测过的端点下面）。
                                     */
                                    if (stored.activeProfileId != probedProfile) return@launch
                                    if (result == null) {
                                        testResult = StatusMessage(
                                            Res.get(R.string.api_test_timeout),
                                            SettingsTone.Danger,
                                        )
                                        return@launch
                                    }
                                    // 拉到的列表**必须落库**：它就是「模型」选择器的数据源，
                                    // 早先只报了个条数、列表丢掉，于是自定义端点明明"连接成功"，
                                    // 点开「模型」却是一个只有标题的白框。
                                    result.onSuccess { ids -> store.setModelIds(ids) }
                                    testResult = result.fold(
                                        onSuccess = {
                                            StatusMessage(
                                                Res.get(R.string.api_test_ok, it.size),
                                                SettingsTone.Accent,
                                            )
                                        },
                                        onFailure = { error ->
                                            val detail = error.userMessage()
                                            // 404 不是"连不上"：端点活着，只是没实现 /models。
                                            // 这条区分很关键 —— 用户会以为自己的中转站彻底不能用
                                            if (detail.contains("404")) {
                                                StatusMessage(
                                                    Res.get(R.string.api_test_no_model_list),
                                                    SettingsTone.Warning,
                                                )
                                            } else {
                                                StatusMessage(
                                                    Res.get(R.string.api_test_failed, detail),
                                                    SettingsTone.Danger,
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )

                        SettingsPage.PRESETS -> PresetsPage(
                            presets = presets,
                            onEdit = { dialog = SettingsDialog.PresetEditor(it) },
                            onDelete = { dialog = SettingsDialog.DeletePreset(it) },
                            onImport = { dialog = SettingsDialog.PresetImport(it) },
                        )

                        SettingsPage.FONT -> FontPage(
                            stored = stored,
                            onFollowSystem = { scope.launch { store.setFollowSystemFont(it) } },
                            onScaleCommit = { scope.launch { store.setFontScale(it) } },
                        )

                        SettingsPage.TUNING -> TuningPage(
                            stored = stored,
                            onToggleCompact = { scope.launch { store.setAutoCompact(it) } },
                            onRatio = { scope.launch { store.setCompactRatio(it) } },
                            onToggleCompactByCount = {
                                scope.launch { store.setCompactByCount(it) }
                            },
                            onCompactCount = { scope.launch { store.setCompactCount(it) } },
                            onCompactRules = { scope.launch { store.setCompactRules(it) } },
                            onToggleLargeExempt = {
                                scope.launch { store.setCompactLargeWindowExempt(it) }
                            },
                            onToggleReserveOutput = {
                                scope.launch { store.setCompactReserveOutput(it) }
                            },
                            onToggleChunked = { scope.launch { store.setCompactChunked(it) } },
                        )
                        SettingsPage.IMAGE -> ImageApiPage(stored = stored, scope = scope, store = store)
                        SettingsPage.ABOUT -> AboutPage(onReplayOnboarding = onReplayOnboarding)

                        SettingsPage.SCHEDULE -> SchedulePage()
                        SettingsPage.PERSONA -> PersonaPage()
                        SettingsPage.VOICE -> VoicePage(
                            selectedName = stored.ttsVoiceName,
                            voices = speechVoices,
                            officialVoices = officialVoices,
                            officialAvailable = officialTtsConfig != null,
                            previewing = speaker.previewing,
                            error = speechError,
                            onSelect = { voice ->
                                scope.launch { store.setTtsVoice(voice?.name.orEmpty(), voice?.label.orEmpty()) }
                                // 选完顺手念一句：省得用户"选了但不知道变了没有"
                                speaker.preview(Res.get(R.string.tts_voice_preview_text), voice?.name.orEmpty())
                            },
                            onPreview = { voice ->
                                speaker.preview(Res.get(R.string.tts_voice_preview_text), voice?.name.orEmpty())
                            },
                            onPreviewCustom = {
                                speaker.preview(Res.get(R.string.tts_voice_preview_text), "")
                            },
                        )
                    }

                    Spacer(Modifier.size(32.dp))
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    // ── 弹窗 ──────────────────────────────────────────────

    // 先收进局部变量：dialog 是委托属性，直接 when(dialog) 无法智能转换
    val activeDialog = dialog
    when (activeDialog) {
        SettingsDialog.Profiles -> {
            val active = stored.profiles.firstOrNull { it.id == stored.activeProfileId }
                ?: stored.profiles.firstOrNull()
            ProfilesDialog(
                profiles = stored.profiles,
                activeId = active?.id,
                onDismiss = { dialog = null },
                onSelect = { profileId ->
                    dialog = null
                    scope.launch { store.setActiveProfile(profileId) }
                },
                onNew = { dialog = SettingsDialog.ProfileNew },
                onRename = { profileId -> dialog = SettingsDialog.ProfileName(profileId) },
                onDelete = { profileId -> dialog = SettingsDialog.ProfileDelete(profileId) },
            )
        }

        SettingsDialog.ProfileNew -> TextInputDialog(
            title = stringResource(R.string.profile_new),
            initial = "",
            placeholder = stringResource(R.string.model_profile_name_hint),
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                /*
                 * 新配置先建一个空壳，再切过去。
                 *
                 * 空壳的 baseUrl 留空 —— 请求组装那边有
                 * `baseUrl.ifBlank { provider.defaultBaseUrl }` 兜底，
                 * 用户只要去下面几行挑服务商、填 Key 就能用。
                 * 反过来"先填三样再创建"会让新增这一步重得像注册表单。
                 */
                val fallback = Res.get(R.string.model_profile_default_name) + " ${stored.profiles.size + 1}"
                scope.launch {
                    val profile = com.simplechat.app.data.ModelProfile(
                        id = java.util.UUID.randomUUID().toString().take(8),
                        name = value.trim().ifBlank { fallback },
                        providerId = ProviderKind.DEEPSEEK.name,
                        baseUrl = "",
                        apiKey = "",
                    )
                    store.upsertProfile(profile)
                    store.setActiveProfile(profile.id)
                }
            },
        )

        is SettingsDialog.ProfileName -> {
            val target = stored.profiles.firstOrNull { it.id == activeDialog.profileId }
            TextInputDialog(
                title = stringResource(R.string.profile_name_label),
                initial = target?.name.orEmpty(),
                placeholder = stringResource(R.string.model_profile_name_hint),
                // 取消 / 保存都回到列表：用户是从列表点进来的，多半还要接着切换或再改一套
                onDismiss = { dialog = SettingsDialog.Profiles },
                onConfirm = { value ->
                    dialog = SettingsDialog.Profiles
                    if (target != null) {
                        scope.launch { store.upsertProfile(target.copy(name = value.trim())) }
                    }
                },
            )
        }

        is SettingsDialog.ProfileDelete -> {
            val target = stored.profiles.firstOrNull { it.id == activeDialog.profileId }
            AppConfirmDialog(
                // 标题里写明删的是哪一套：此前只说"删除这套配置"，"这套"是谁要靠猜
                title = stringResource(
                    R.string.profile_delete_title,
                    target?.let { profileTitle(it) }.orEmpty(),
                ),
                message = stringResource(R.string.profile_delete_body),
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onDismiss = { dialog = SettingsDialog.Profiles },
                onConfirm = {
                    dialog = SettingsDialog.Profiles
                    if (target != null) scope.launch { store.removeProfile(target.id) }
                },
            )
        }

        SettingsDialog.Provider -> {
            val keyFree = stringResource(R.string.api_key_not_required)
            SettingsChoiceDialog(
                title = stringResource(R.string.label_provider),
                options = ProviderKind.entries.map { kind ->
                    SettingsChoice(
                        value = kind,
                        label = kind.displayName,
                        // 第二行是默认域名：十几个名字里有一半是英文缩写，域名比名字好认
                        detail = hostOf(kind.defaultBaseUrl).ifBlank { null },
                        tags = if (kind.requiresApiKey) emptyList() else listOf(keyFree),
                    )
                },
                selected = provider,
                onDismiss = { dialog = null },
                // 每家都有自己的徽标（此前只有 OpenAI / DeepSeek 有，其余十二家共用一枚）
                leading = { kind -> ProviderBadge(kind = kind, baseUrl = "", size = 30.dp) },
                onSelect = { picked ->
                    dialog = null
                    /*
                     * 点的就是当前这家时什么都不做。
                     *
                     * 换服务商会把地址重置成默认值、把模型清空（旧模型多半在新端点上
                     * 不存在）。但"打开看一眼，又点了一下当前项"不是换 —— 此前这样点
                     * 一下，自己填的中转地址和选好的模型就没了。
                     */
                    if (picked != provider) {
                        scope.launch {
                            store.setProvider(
                                providerId = picked.name,
                                baseUrl = picked.defaultBaseUrl,
                                // 换端点 = 清空模型：旧模型多半在新端点上不存在
                                modelId = "",
                            )
                        }
                    }
                },
            )
        }

        SettingsDialog.BaseUrl -> TextInputDialog(
            title = "Base URL",
            initial = stored.baseUrl.ifBlank { provider.defaultBaseUrl },
            placeholder = "https://api.openai.com/v1",
            hint = stringResource(R.string.api_base_url_hint),
            keyboardType = KeyboardType.Uri,
            mono = true,
            paste = true,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                // 存之前先规整：补协议、剥误粘的 /chat/completions、只填域名时补 /v1。
                // 用户填 `http://192.168.1.9:8080` 这类自建地址必须原样保留协议，
                // 不能被"补 https"的逻辑改写。
                scope.launch { store.setBaseUrl(normalizeBaseUrl(value)) }
            },
        )

        SettingsDialog.PinnedModels -> {
            val models = ModelCatalog.pickerModels(provider, stored.modelIds)
            ModelPickerDialog(
                models = models,
                selected = ModelCatalog.pinnedIds(provider, stored.pinnedModelIds),
                onDismiss = { dialog = null },
                onConfirm = { picked ->
                    dialog = null
                    scope.launch { store.setPinnedModels(provider.name, picked.toList()) }
                },
            )
        }

        SettingsDialog.Model -> {
            /*
             * 选项 = **这个端点拉到的模型**（落库的那份）。
             *
             * 早先只读 `knownModels(provider)`：自定义服务商的内置目录是空的，
             * 于是「测试连接」显示"可用模型 45 个"、点开「模型」却什么都没有 ——
             * 拉取结果根本没进界面。
             */
            val models = ModelCatalog.pickerModels(provider, stored.modelIds)
            val visionTag = stringResource(R.string.model_tag_vision)
            val thinkingTag = stringResource(R.string.model_tag_thinking)
            SettingsChoiceDialog(
                title = stringResource(R.string.label_model),
                options = models.map { model ->
                    SettingsChoice(
                        value = model,
                        label = model.displayName,
                        /*
                         * 第二行：真正要填进请求的 id（与显示名不同时）+ 能力。
                         *
                         * 中转站的显示名常常是"DeepSeek V4"，而请求里要的是
                         * `deepseek-v4-flash`，混起来会选错。能力写成文字而不是
                         * 行尾标签：模型名本身就长，再挤两个标签名字只剩半截。
                         */
                        detail = listOfNotNull(
                            model.id.takeIf { it != model.displayName },
                            visionTag.takeIf { model.supportsVision },
                            thinkingTag.takeIf { model.supportsThinking },
                        ).joinToString(" · ").ifBlank { null },
                    )
                },
                selected = models.firstOrNull { it.id == stored.modelId },
                emptyHint = stringResource(R.string.api_model_empty_hint),
                hint = if (modelFetching) {
                    stringResource(R.string.api_model_fetching)
                } else {
                    modelHint?.text
                },
                hintTone = if (modelFetching) {
                    SettingsTone.Neutral
                } else {
                    modelHint?.tone ?: SettingsTone.Neutral
                },
                // 四五十个模型时能搜；不到九个时搜索框自己不出现
                searchHint = stringResource(R.string.model_picker_search),
                leading = { model -> ModelBadge(model = model, size = 30.dp) },
                actions = listOf(
                    SettingsChoiceAction(
                        label = stringResource(
                            if (stored.modelIds.isEmpty()) R.string.api_model_fetch
                            else R.string.api_model_refetch,
                        ),
                        icon = AppIcons.Refresh,
                        onClick = {
                            // 拉取用**当前端点 + 当前 Key**，与「测试连接」同一条路径，
                            // 结果落库 —— 关掉弹窗、重启之后选择器里还在
                            if (!modelFetching) {
                                modelFetching = true
                                modelHint = null
                                scope.launch {
                                    val result = container.chatApi.fetchModels(
                                        baseUrl = stored.baseUrl.ifBlank { provider.defaultBaseUrl },
                                        apiKey = stored.apiKey,
                                    )
                                    result.onSuccess { store.setModelIds(it) }
                                    modelHint = result.fold(
                                        onSuccess = {
                                            StatusMessage(
                                                Res.get(R.string.api_model_fetch_ok, it.size),
                                                SettingsTone.Accent,
                                            )
                                        },
                                        onFailure = {
                                            StatusMessage(
                                                Res.get(R.string.api_model_fetch_failed, it.userMessage()),
                                                SettingsTone.Danger,
                                            )
                                        },
                                    )
                                    modelFetching = false
                                }
                            }
                        },
                    ),
                    SettingsChoiceAction(
                        label = stringResource(R.string.api_model_manual),
                        icon = AppIcons.Edit,
                        onClick = { dialog = SettingsDialog.ModelManual },
                    ),
                ),
                onDismiss = {
                    dialog = null
                    // 关掉就清提示：下次进来不该还挂着上一轮的"拉取失败"
                    modelHint = null
                },
                onSelect = { picked ->
                    dialog = null
                    modelHint = null
                    scope.launch { store.setModel(picked.id) }
                },
            )
        }

        SettingsDialog.ModelManual -> TextInputDialog(
            title = stringResource(R.string.api_model_manual),
            initial = stored.modelId,
            placeholder = "model-id",
            // 模型 id 差一个字符就是 404：等宽显示，且多半是从文档里复制来的
            mono = true,
            paste = true,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                val id = value.trim()
                // 空值等于"没选"，不要拿它覆盖已选模型
                if (id.isNotEmpty()) scope.launch { store.setModel(id) }
            },
        )

        SettingsDialog.ApiKey -> TextInputDialog(
            title = "API Key",
            initial = stored.apiKey,
            placeholder = "sk-…",
            secret = true,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                // 去掉首尾空白：从网页复制 Key 常带进一个空格或换行，带着它请求必然 401，
                // 而界面上（遮住的圆点）根本看不出多了一位
                scope.launch { store.setApiKey(value.trim()) }
            },
        )

        SettingsDialog.ApiStyle -> ChoiceDialog(
            title = stringResource(R.string.settings_api_style),
            options = ApiStylePreference.entries.map { it to stringResource(it.labelRes) },
            selected = ApiStylePreference.fromId(stored.apiStyleId),
            onDismiss = { dialog = null },
            onSelect = { picked ->
                dialog = null
                scope.launch { store.setApiStyle(picked.name) }
            },
        )

        SettingsDialog.Context -> ChoiceDialog(
            title = stringResource(R.string.settings_context),
            options = ModelResolver.ContextPreset.entries.map { it to stringResource(it.labelRes()) },
            selected = ModelResolver.ContextPreset.fromId(stored.contextPresetId),
            hint = stringResource(R.string.settings_context_hint),
            onDismiss = { dialog = null },
            onSelect = { picked ->
                if (picked == ModelResolver.ContextPreset.CUSTOM) {
                    // 不关弹窗，直接换一层输入框；取消会回到设置页
                    dialog = SettingsDialog.ContextCustom
                } else {
                    dialog = null
                    scope.launch { store.setContextPreset(picked.name) }
                }
            },
        )

        SettingsDialog.ContextCustom -> TextInputDialog(
            title = stringResource(R.string.context_custom_title),
            initial = stored.contextWindowCustom.takeIf { it > 0 }?.toString().orEmpty(),
            placeholder = stringResource(R.string.context_custom_placeholder),
            keyboardType = KeyboardType.Number,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                val tokens = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                if (tokens > 0) {
                    scope.launch { store.setContextPreset(ModelResolver.ContextPreset.CUSTOM.name, tokens) }
                }
            },
        )

        SettingsDialog.MaxOutput -> ChoiceDialog(
            title = stringResource(R.string.settings_max_output),
            options = ModelResolver.maxOutputOptions.map { value ->
                value to if (value == 0) {
                    stringResource(R.string.max_output_default)
                } else {
                    TokenEstimate.format(value)
                }
            },
            selected = stored.maxOutputTokens,
            onDismiss = { dialog = null },
            onSelect = { picked ->
                dialog = null
                scope.launch { store.setMaxOutputTokens(picked) }
            },
        )

        SettingsDialog.Location -> TextInputDialog(
            title = stringResource(R.string.settings_location),
            initial = stored.locationRegion,
            // 占位符要给出**可照抄的例子**，而不是复述"这是干什么用的"
            placeholder = stringResource(R.string.settings_location_placeholder),
            onDismiss = { dialog = null },
            onConfirm = { value ->
                dialog = null
                scope.launch { store.setLocationRegion(value) }
            },
        )

        SettingsDialog.DeleteAll -> AppConfirmDialog(
            title = stringResource(R.string.settings_delete_all_title),
            message = stringResource(R.string.settings_delete_all_body),
            confirmText = stringResource(R.string.settings_delete_all_confirm),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch {
                    val outcome = runCatching { repository.deleteAllConversations() }
                    dataMessage = outcome.fold(
                        onSuccess = { Res.get(R.string.settings_delete_all_done) },
                        onFailure = { Res.get(R.string.error_delete_failed, it.userMessage()) },
                    )
                }
            },
        )

        SettingsDialog.ImportMode -> ChoiceDialog(
            title = stringResource(R.string.import_mode_title),
            options = listOf(
                ImportMode.MERGE to stringResource(R.string.import_mode_merge),
                ImportMode.REPLACE_SAME to stringResource(R.string.import_mode_replace_same),
                ImportMode.RENAME to stringResource(R.string.import_mode_rename),
            ),
            selected = null,
            onDismiss = {
                dialog = null
                pendingImport = null
            },
            onSelect = { mode ->
                dialog = null
                val file = pendingImport
                pendingImport = null
                if (file == null) return@ChoiceDialog
                scope.launch {
                    dataMessage = Res.get(R.string.import_working)
                    importPercent
                    // 认得三种备份：本应用的 JSON / ZIP、Chatbox 的 ZIP / JSON、DeepSeek 导出
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) {
                            repository.importExternal(file, context.cacheDir, mode) { p ->
                                // 回调来自 IO 线程：StateFlow 是线程安全的，界面侧 collectAsState 收。
                                // 0 就先不给 —— 停在 0% 看着像卡死，等真有进展再显示百分比
                                if (p > 0f) importProgress.value = p.coerceIn(0f, 1f)
                            }
                        }
                    }
                    importProgress.value = null
                    // 大文件的临时副本用过就删，别占着缓存
                    withContext(Dispatchers.IO) { file.delete() }
                    dataMessage = outcome.fold(
                        // 结果文案由仓库层统一按"对账"口径生成（会话 4 个 · 归档共 5 个 · …），
                        // 界面只负责显示，不再自己拼数字
                        onSuccess = { (_, summary) -> summary },
                        onFailure = { Res.get(R.string.error_import_failed, it.userMessage()) },
                    )
                }
            },
        )

        is SettingsDialog.PresetEditor -> PresetEditorDialog(
            initial = activeDialog.preset,
            onDismiss = { dialog = null },
            onDelete = activeDialog.preset?.let { preset ->
                { dialog = SettingsDialog.DeletePreset(preset) }
            },
            onConfirm = { name, content ->
                val target = activeDialog.preset
                dialog = null
                scope.launch {
                    repository.upsertPreset(
                        preset = Preset(
                            id = target?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            content = content,
                        ),
                        // 新建排在末尾；编辑保持原有位置
                        order = target?.let { presets.indexOfFirst { p -> p.id == it.id } }
                            ?.takeIf { it >= 0 }
                            ?: presets.size,
                    )
                }
            },
        )

        is SettingsDialog.PresetImport -> PresetEditorDialog(
            initial = null,
            prefillName = activeDialog.draft.name,
            prefillContent = activeDialog.draft.content,
            prefillNote = activeDialog.draft.note,
            onDismiss = { dialog = null },
            onDelete = null,
            onConfirm = { name, content ->
                dialog = null
                scope.launch {
                    repository.upsertPreset(
                        preset = Preset(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            content = content,
                        ),
                        order = presets.size,
                    )
                }
            },
        )

        is SettingsDialog.DeletePreset -> AppConfirmDialog(
            title = stringResource(R.string.preset_delete_title, activeDialog.preset.name),
            message = stringResource(R.string.preset_delete_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                val id = activeDialog.preset.id
                dialog = null
                scope.launch {
                    /*
                     * 只删预设本身。
                     *
                     * 各会话里指向它的挂载行由外键 CASCADE 一起清掉
                     * （见 `ConversationPresetEntity` 的 `onDelete`）——
                     * 在应用层再逐个会话摘一遍，既慢又要处理并发。
                     */
                    repository.deletePreset(id)
                }
            },
        )

        null -> Unit
    }
}

// ══════════════════════════════════════════════════════════
//  一级页面
// ══════════════════════════════════════════════════════════

@Composable
private fun RootPage(
    stored: StoredSettings,
    presetCount: Int,
    themeMode: ThemeMode,
    accentColor: AccentColor,
    language: AppLanguage,
    onOpen: (SettingsPage) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentColor) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onToggleAutoCollapse: (Boolean) -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenRuntime: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenScreen: () -> Unit,
    onOpenCloud: () -> Unit,
    /** 自动朗读开关：回复写完自动念。 */
    onToggleAutoRead: (Boolean) -> Unit = {},
    /** 打开个人资料页（头像 / 名字）。 */
    onOpenProfile: () -> Unit = {},
    /** 打开人设编辑页。 */
    onOpenPersona: () -> Unit = {},
) {
    var themeMenuOpen by remember { mutableStateOf(false) }
    var accentMenuOpen by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }

    val screenChannel by com.simplechat.app.agent.ScreenAutomation.state.collectAsState()
    val scheduleCount by com.simplechat.app.data.ScheduleStore.items.collectAsState()

    SettingsSectionLabel(stringResource(R.string.settings_group_account))

    SettingsCard {
        // 账号管理：头像 + 名字 + 箭头，点进去改头像 / 名字。
        // 官方把「账号管理」单独一级，这里同样 —— 头像是这行唯一有"实体"的东西，
        // 所以它替代了图标的位置。
        SettingsAccountRow(
            name = stored.userName,
            avatar = stored.userAvatar,
            onClick = onOpenProfile,
        )

        // 数据管理：与账号同组 —— 都属于"这台机器上的我"
        SettingsNavRow(
            icon = AppIcons.Database,
            label = stringResource(R.string.settings_data),
            value = stringResource(R.string.settings_data_summary),
            onClick = { onOpen(SettingsPage.DATA) },
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_app))

    SettingsCard {
        // 语言
        Box {
            SettingsNavRow(
                icon = AppIcons.Globe,
                label = stringResource(R.string.settings_language),
                value = when (language) {
                    AppLanguage.SYSTEM -> stringResource(R.string.value_follow_system)
                    AppLanguage.ZH -> stringResource(R.string.lang_chinese)
                    AppLanguage.EN -> stringResource(R.string.lang_english)
                },
                onClick = { languageMenuOpen = true },
            )
            AppMenu(
                expanded = languageMenuOpen,
                onDismissRequest = { languageMenuOpen = false },
            ) {
                listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.value_follow_system),
                    AppLanguage.ZH to stringResource(R.string.lang_chinese),
                    AppLanguage.EN to stringResource(R.string.lang_english),
                ).forEach { (option, label) ->
                    AppMenuItem(
                        label = label,
                        selected = option == language,
                        onClick = {
                            languageMenuOpen = false
                            onLanguageChange(option)
                        },
                    )
                }
            }
        }

        // 外观
        Box {
            SettingsNavRow(
                icon = AppIcons.Sun,
                label = stringResource(R.string.settings_appearance),
                value = when (themeMode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.value_follow_system)
                    ThemeMode.LIGHT -> stringResource(R.string.value_light)
                    ThemeMode.DARK -> stringResource(R.string.value_dark)
                },
                onClick = { themeMenuOpen = true },
            )
            AppMenu(
                expanded = themeMenuOpen,
                onDismissRequest = { themeMenuOpen = false },
            ) {
                ThemeMode.entries.forEach { mode ->
                    AppMenuItem(
                        label = when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.value_follow_system)
                            ThemeMode.LIGHT -> stringResource(R.string.value_light)
                            ThemeMode.DARK -> stringResource(R.string.value_dark)
                        },
                        selected = mode == themeMode,
                        onClick = {
                            themeMenuOpen = false
                            onThemeChange(mode)
                        },
                    )
                }
            }
        }

        // 字体大小
        SettingsNavRow(
            icon = AppIcons.FontSize,
            label = stringResource(R.string.settings_font_size),
            value = stringResource(
                fontSizeSteps
                    .minByOrNull { kotlin.math.abs(it.first - stored.fontScale) }
                    ?.second ?: R.string.font_step_standard,
            ),
            onClick = { onOpen(SettingsPage.FONT) },
        )

        // 个性化（主题色）：点开弹一排色块
        Box {
            SettingsNavRow(
                icon = AppIcons.Palette,
                label = stringResource(R.string.settings_personalize),
                value = accentColor.label,
                onClick = { accentMenuOpen = true },
            )
            AppMenu(
                expanded = accentMenuOpen,
                onDismissRequest = { accentMenuOpen = false },
                width = AccentMenuWidth,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AccentColor.entries.forEach { color ->
                        AccentSwatch(
                            color = color,
                            selected = color == accentColor,
                            onClick = {
                                accentMenuOpen = false
                                onAccentChange(color)
                            },
                        )
                    }
                }
            }
        }

        // 自动折叠思考内容
        SettingsSwitchRow(
            icon = AppIcons.ChevronUp,
            label = stringResource(R.string.settings_auto_collapse),
            checked = stored.autoCollapseThinking,
            onCheckedChange = onToggleAutoCollapse,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_voice))

    SettingsCard {
        // 朗读音色：系统 TTS 引擎提供，进二级页挑选（见 VoicePage）
        SettingsNavRow(
            icon = AppIcons.Volume,
            label = stringResource(R.string.tts_voice),
            value = stored.ttsVoiceLabel.ifBlank { stringResource(R.string.tts_voice_default) },
            onClick = { onOpen(SettingsPage.VOICE) },
        )

        // 自动朗读：默认关 —— 手机上突然出声是最容易吓到人的事
        SettingsSwitchRow(
            icon = AppIcons.Play,
            label = stringResource(R.string.tts_auto_read),
            checked = stored.autoReadAloud,
            onCheckedChange = onToggleAutoRead,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_dialog))

    SettingsCard {
        // 人设：一段"我是谁"，拼在系统提示词最前面
        SettingsNavRow(
            icon = AppIcons.Sparkles,
            label = stringResource(R.string.settings_persona),
            value = if (stored.userPersona.isBlank()) {
                stringResource(R.string.settings_persona_none)
            } else {
                stored.userPersona.lineSequence().first().take(16)
            },
            onClick = onOpenPersona,
        )

        /*
         * 审批档位只保留在输入栏那颗「审批」胶囊里。
         *
         * 设置里再来一份，等于同一个开关有两个入口，改了一边另一边看起来
         * 还没变；而真正需要它的时候（要发工具调用）人就在输入框前面。
         */

        /*
         * 这里原本有一枚「新对话默认启用（Agent）」的开关。
         *
         * 删掉的理由：它控制的是一个**没人会去关**的默认值 —— 关掉之后
         * 所有工具（终端、文件、绘图、MCP）一律消失，模型连"我能做什么"
         * 都看不到，而用户完全不知道是自己半年前关掉的那个开关干的。
         * Agent 模式现在恒为默认开，想临时关就从输入栏那颗胶囊关。
         */

        /*
         * 这里**只留一个入口**，不再重复那两个开关。
         *
         * 「自动压缩上下文」「记忆检索」原本这一页各有一个、参数页里又各有一个 ——
         * 同一个开关两个位置，改了一边另一边看着没变，本质上是两个真相。
         * 开关是"要不要"，滑杆是"要多少"，它们属于同一件事，必须待在一起：
         * 全部收进「上下文与记忆」，这一页只负责指路。
         */

        /*
         * 参数单独一页：滑杆塞进这张卡会把上面几个开关挤成一条缝。
         *
         * 副标题写的是**真正在用的两个阈值**：比例 + 自上次摘要后的用户消息数。
         * 不再显示"自动 · 200K"—— 自动推导已经删掉，写了反而对不上。
         */
        SettingsNavRow(
            icon = AppIcons.Sliders,
            label = stringResource(R.string.tuning_entry),
            value = stringResource(
                R.string.tuning_entry_value,
                (stored.compactRatio * 100).toInt(),
                stored.compactCount,
            ),
            onClick = { onOpen(SettingsPage.TUNING) },
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_tools))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Monitor,
            label = stringResource(R.string.settings_screen),
            value = when (screenChannel.channel) {
                com.simplechat.app.agent.ScreenAutomation.CHANNEL_ACCESSIBILITY ->
                    stringResource(R.string.settings_screen_value_a11y)
                com.simplechat.app.agent.ScreenAutomation.CHANNEL_ROOT ->
                    stringResource(R.string.settings_screen_value_root)
                else -> stringResource(R.string.settings_screen_value_off)
            },
            onClick = onOpenScreen,
        )

        SettingsNavRow(
            icon = AppIcons.Clock,
            label = stringResource(R.string.schedule_title),
            value = if (scheduleCount.isEmpty()) {
                stringResource(R.string.settings_schedule_none)
            } else {
                stringResource(R.string.settings_schedule_value, scheduleCount.size)
            },
            onClick = { onOpen(SettingsPage.SCHEDULE) },
        )

        SettingsNavRow(
            icon = AppIcons.Folder,
            label = stringResource(R.string.settings_workspace),
            value = com.simplechat.app.agent.Workspace.current.value.label,
            onClick = onOpenWorkspace,
        )

        SettingsNavRow(
            icon = AppIcons.Shield,
            label = stringResource(R.string.settings_permissions),
            value = null,
            onClick = onOpenPermissions,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_extension))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Puzzle,
            label = stringResource(R.string.settings_skills),
            value = null,
            onClick = onOpenSkills,
        )

        SettingsNavRow(
            icon = AppIcons.Wrench,
            label = stringResource(R.string.settings_mcp),
            value = null,
            onClick = onOpenMcp,
        )

        SettingsNavRow(
            icon = AppIcons.Server,
            label = stringResource(R.string.settings_runtime),
            value = null,
            onClick = onOpenRuntime,
        )

        SettingsNavRow(
            icon = AppIcons.Cloud,
            label = stringResource(R.string.settings_cloud),
            value = com.simplechat.app.agent.CloudEnvs.list.collectAsState().value
                .size.let { if (it == 0) stringResource(R.string.settings_cloud_hint) else "$it" },
            onClick = onOpenCloud,
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_model))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Api,
            label = stringResource(R.string.settings_api),
            value = null,
            onClick = { onOpen(SettingsPage.API) },
        )

        SettingsNavRow(
            icon = AppIcons.Scroll,
            label = stringResource(R.string.settings_presets),
            // 只显示"建了几条"：用哪几条是每个对话自己的事，设置页不该冒充全局状态
            value = if (presetCount == 0) {
                stringResource(R.string.preset_count_empty)
            } else {
                stringResource(R.string.preset_count_total, presetCount)
            },
            onClick = { onOpen(SettingsPage.PRESETS) },
        )

        /*
         * 绘图端点放在「模型与服务」里。
         *
         * 它和对话模型是同一类东西 —— 都是"这台机器接了哪些模型服务"，
         * 只是用途不同。
         *
         * ⚠️ 朗读**不**放这儿。它已经在「语音」页里有一整套（音色、通道、
         * 自定义 API、试听），这里再挂一行「朗读通道」就是同一个设置两个入口：
         * 从首页点进去的人会以为这是另一处配置，改完发现两边联动，反而更乱。
         * 朗读的入口只留语音页那一个。
         */
        SettingsNavRow(
            icon = AppIcons.Image,
            label = stringResource(R.string.image_api_entry),
            value = if (stored.hasImageApi) {
                stringResource(R.string.image_api_entry_on, stored.imageApiModel)
            } else {
                stringResource(R.string.image_api_entry_off)
            },
            onClick = { onOpen(SettingsPage.IMAGE) },
        )
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_about))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Info,
            label = stringResource(R.string.settings_about_title),
            value = null,
            onClick = { onOpen(SettingsPage.ABOUT) },
        )
    }

    // 底部留白：最后一张卡贴到导航栏会显得"没写完"
    Spacer(Modifier.height(28.dp))
}

// ══════════════════════════════════════════════════════════
//  二级：朗读音色
// ══════════════════════════════════════════════════════════

/**
 * 朗读音色选择。
 *
 * 为什么是"列表 + 试听"而不是下拉框：音色名是 `zh-cn-x-ccc-local` 这种机器标识，
 * 光看名字根本分不出区别 —— 唯一的判断方式就是**听一句**。所以每一行都带试听，
 * 选中后也顺手念一遍（见调用处）。
 */
@Composable
private fun VoicePage(
    selectedName: String,
    voices: List<SpeechVoice>?,
    /** DeepSeek 官方音色；空 = 当前服务商不支持官方朗读（整块不显示）。 */
    officialVoices: List<SpeechVoice>,
    officialAvailable: Boolean,
    previewing: Boolean,
    error: String?,
    onSelect: (SpeechVoice?) -> Unit,
    onPreview: (SpeechVoice?) -> Unit,
    /** 用当前自定义配置试听一句。 */
    onPreviewCustom: () -> Unit,
) {
    /*
     * 语言单独一组，排在音色**前面**。
     *
     * 音色名（`zh-cn-x-ccc-local`）是可选项，语言则是必选项：选了英文音色
     * 却让它念中文，引擎会退回默认音色，听感反而更差。先定语言，再挑音色。
     */
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { (context.applicationContext as App).container.settingsStore }
    val stored by store.flow.collectAsState(initial = StoredSettings())
    var langDialog by remember { mutableStateOf<String?>(null) }
    var channelDialog by remember { mutableStateOf(false) }

    /** 自定义 API 折叠区的展开状态。 */
    var customOpen by remember { mutableStateOf(false) }
    /** 正在编辑自定义 API 的哪个字段。 */
    var editingField by remember { mutableStateOf<String?>(null) }

    SettingsSectionLabel(stringResource(R.string.speech_language_section))
    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Globe,
            label = stringResource(R.string.tts_language),
            value = com.simplechat.app.data.SpeechLang.labelOf(stored.ttsLanguage),
            onClick = { langDialog = "tts" },
        )
        SettingsNavRow(
            icon = AppIcons.Mic,
            label = stringResource(R.string.stt_language),
            value = com.simplechat.app.data.SpeechLang.labelOf(stored.sttLanguage),
            onClick = { langDialog = "stt" },
        )
    }

    /*
     * 通道：系统 / 官方 / 自定义。
     *
     * 单独一组，排在音色之前 —— 它决定下面列的是哪一批音色。先选通道再选音色，
     * 才不会出现"挑了半天的音色，其实不在当前通道里"。
     */
    SettingsSectionLabel(stringResource(R.string.tts_channel))

    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Route,
            label = stringResource(R.string.tts_channel),
            value = when (stored.resolvedTtsChannel) {
                com.simplechat.app.data.TtsChannel.CUSTOM ->
                    stringResource(R.string.tts_channel_custom)

                com.simplechat.app.data.TtsChannel.DEEPSEEK ->
                    stringResource(R.string.tts_channel_deepseek)

                else -> stringResource(R.string.tts_channel_system)
            },
            onClick = { channelDialog = true },
        )
    }

    SettingsHint(
        text = stringResource(R.string.tts_channel_hint),
        modifier = Modifier.padding(top = 8.dp),
    )

    SettingsSectionLabel(stringResource(R.string.tts_voice))

    SettingsCard {
        VoiceRow(
            label = stringResource(R.string.tts_voice_default),
            selected = selectedName.isBlank(),
            onClick = { onSelect(null) },
        )
        voices?.forEach { voice ->
            VoiceRow(
                label = voice.label,
                selected = voice.name == selectedName,
                onClick = { onSelect(voice) },
                onPreview = { onPreview(voice) },
            )
        }
    }

    /*
     * 官方音色单独一组、排在系统音色**后面**：系统音色是"一定可用"的兜底，
     * 官方的音质更好但依赖服务商与网络。不支持时不显示这一组 —— 连入口都没有，
     * 用户就不会点了才发现不行。
     */
    if (officialVoices.isNotEmpty()) {
        SettingsSectionLabel(stringResource(R.string.tts_voice_official_section))
        SettingsCard {
            officialVoices.forEachIndexed { index, voice ->
                VoiceRow(
                    label = voice.label,
                    selected = voice.name == selectedName,
                    onClick = { onSelect(voice) },
                    onPreview = { onPreview(voice) },
                )
            }
        }
    }

    /*
     * 自定义语音 API —— 可折叠。
     *
     * 折叠起来只占一行，展开才是七项配置。默认收起：绝大多数人用系统音色，
     * 一进来就铺满一屏 URL / Key / 模型，会让人以为"这功能必须配才能用"。
     */
    SettingsSectionLabel(stringResource(R.string.tts_custom_section))

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { customOpen = !customOpen }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Sliders,
                contentDescription = null,
                tint = LocalChatColors.current.processText,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tts_custom_section),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (stored.hasCustomTts) {
                        stringResource(
                            R.string.tts_custom_summary,
                            stored.ttsApiModel,
                            stored.ttsApiVoice,
                        )
                    } else {
                        stringResource(R.string.tts_custom_summary_empty)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatColors.current.placeholder,
                )
            }
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = null,
                tint = LocalChatColors.current.placeholder,
                modifier = Modifier
                    .size(15.dp)
                    .rotate(if (customOpen) 180f else 0f),
            )
        }

        AnimatedVisibility(visible = customOpen) {
            Column {
                RowDivider()
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_url),
                    value = stored.ttsApiUrl,
                    placeholder = stringResource(R.string.tts_custom_url_placeholder),
                    onClick = { editingField = "url" },
                )
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_key),
                    value = if (stored.ttsApiKey.isNotBlank()) maskKey(stored.ttsApiKey) else "",
                    placeholder = "sk-…",
                    onClick = { editingField = "key" },
                )
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_model),
                    value = stored.ttsApiModel,
                    placeholder = stringResource(R.string.tts_custom_model_placeholder),
                    onClick = { editingField = "model" },
                )
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_voice),
                    value = stored.ttsApiVoice,
                    placeholder = stringResource(R.string.tts_custom_voice_placeholder),
                    onClick = { editingField = "voice" },
                )
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_format),
                    value = stored.ttsApiFormat,
                    placeholder = "mp3",
                    onClick = { editingField = "format" },
                )
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_speed),
                    value = "%.2f".format(stored.ttsApiSpeed),
                    placeholder = "1.0",
                    onClick = { editingField = "speed" },
                )
                CustomTtsField(
                    label = stringResource(R.string.tts_custom_headers),
                    value = stored.ttsApiHeaders.lineSequence().firstOrNull().orEmpty(),
                    placeholder = stringResource(R.string.tts_custom_headers_placeholder),
                    onClick = { editingField = "headers" },
                )
                RowDivider()
                SettingsActionRow(
                    label = if (previewing) {
                        stringResource(R.string.tts_custom_testing)
                    } else {
                        stringResource(R.string.tts_custom_test)
                    },
                    hint = null,
                    onClick = {
                        // 试听前先把通道切到自定义：否则点了还是系统音色在念
                        scope.launch {
                            store.setTtsChannel(com.simplechat.app.data.TtsChannel.CUSTOM)
                            onPreviewCustom()
                        }
                    },
                )
            }
        }
    }

    val colors = LocalChatColors.current
    val hint = when {
        // 报错优先：引擎缺失 / 合成失败时，"正在试听"之类的说明就是误导
        error != null -> error
        voices == null -> stringResource(R.string.tts_voice_loading)
        voices.isEmpty() && officialVoices.isEmpty() -> stringResource(R.string.tts_voice_empty)
        previewing -> stringResource(R.string.tts_voice_preview_running)
        else -> stringResource(R.string.tts_voice_hint)
    }
    SettingsHint(
        text = hint,
        modifier = Modifier.padding(top = 10.dp),
        // 引擎缺失 / 合成失败是出错，不是说明 —— 颜色上要分得开
        tone = if (error != null) SettingsTone.Danger else SettingsTone.Neutral,
    )

    langDialog?.let { which ->
        val current = if (which == "tts") stored.ttsLanguage else stored.sttLanguage
        ChoiceDialog(
            title = stringResource(
                if (which == "tts") R.string.tts_language else R.string.stt_language,
            ),
            options = com.simplechat.app.data.SpeechLang.options.map { (code, res) ->
                code to stringResource(res)
            },
            selected = current,
            onDismiss = { langDialog = null },
            onSelect = { code ->
                langDialog = null
                scope.launch {
                    if (which == "tts") store.setTtsLanguage(code) else store.setSttLanguage(code)
                }
            },
        )
    }

    if (channelDialog) {
        ChoiceDialog(
            title = stringResource(R.string.tts_channel),
            options = buildList {
                add(com.simplechat.app.data.TtsChannel.AUTO to stringResource(R.string.tts_channel_system))
                if (officialAvailable) {
                    add(
                        com.simplechat.app.data.TtsChannel.DEEPSEEK to
                            stringResource(R.string.tts_channel_deepseek),
                    )
                }
                add(
                    com.simplechat.app.data.TtsChannel.CUSTOM to
                        stringResource(R.string.tts_channel_custom),
                )
            },
            selected = stored.resolvedTtsChannel,
            onDismiss = { channelDialog = false },
            onSelect = { id ->
                channelDialog = false
                scope.launch {
                    store.setTtsChannel(id)
                    if (id == com.simplechat.app.data.TtsChannel.CUSTOM) customOpen = true
                }
            },
        )
    }

    when (editingField) {
        "url" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_url),
            initial = stored.ttsApiUrl,
            placeholder = stringResource(R.string.tts_custom_url_placeholder),
            hint = stringResource(R.string.tts_custom_url_hint),
            keyboardType = KeyboardType.Uri,
            mono = true,
            paste = true,
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                scope.launch {
                    store.setCustomTts(
                        value, stored.ttsApiKey, stored.ttsApiModel, stored.ttsApiVoice,
                        stored.ttsApiFormat, stored.ttsApiSpeed, stored.ttsApiHeaders,
                    )
                }
            },
        )

        "key" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_key),
            initial = stored.ttsApiKey,
            placeholder = "sk-…",
            secret = true,
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                scope.launch { store.setTtsApiKey(value) }
            },
        )

        "model" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_model),
            initial = stored.ttsApiModel,
            placeholder = stringResource(R.string.tts_custom_model_placeholder),
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                scope.launch {
                    store.setCustomTts(
                        stored.ttsApiUrl, stored.ttsApiKey, value, stored.ttsApiVoice,
                        stored.ttsApiFormat, stored.ttsApiSpeed, stored.ttsApiHeaders,
                    )
                }
            },
        )

        "voice" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_voice),
            initial = stored.ttsApiVoice,
            placeholder = stringResource(R.string.tts_custom_voice_placeholder),
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                scope.launch {
                    store.setCustomTts(
                        stored.ttsApiUrl, stored.ttsApiKey, stored.ttsApiModel, value,
                        stored.ttsApiFormat, stored.ttsApiSpeed, stored.ttsApiHeaders,
                    )
                }
            },
        )

        "format" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_format),
            initial = stored.ttsApiFormat,
            placeholder = "mp3",
            hint = "mp3 / wav / opus / pcm",
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                scope.launch {
                    store.setCustomTts(
                        stored.ttsApiUrl, stored.ttsApiKey, stored.ttsApiModel, stored.ttsApiVoice,
                        value.ifBlank { "mp3" }, stored.ttsApiSpeed, stored.ttsApiHeaders,
                    )
                }
            },
        )

        "speed" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_speed),
            initial = "%.2f".format(stored.ttsApiSpeed),
            placeholder = "1.0",
            hint = "0.5 – 2.0",
            keyboardType = KeyboardType.Decimal,
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                val speed = value.trim().toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
                scope.launch {
                    store.setCustomTts(
                        stored.ttsApiUrl, stored.ttsApiKey, stored.ttsApiModel, stored.ttsApiVoice,
                        stored.ttsApiFormat, speed, stored.ttsApiHeaders,
                    )
                }
            },
        )

        "headers" -> TextInputDialog(
            title = stringResource(R.string.tts_custom_headers),
            initial = stored.ttsApiHeaders,
            placeholder = stringResource(R.string.tts_custom_headers_placeholder),
            hint = stringResource(R.string.tts_custom_headers_hint),
            onDismiss = { editingField = null },
            onConfirm = { value ->
                editingField = null
                scope.launch {
                    store.setCustomTts(
                        stored.ttsApiUrl, stored.ttsApiKey, stored.ttsApiModel, stored.ttsApiVoice,
                        stored.ttsApiFormat, stored.ttsApiSpeed, value,
                    )
                }
            },
        )
    }

    if (!officialAvailable) {
        SettingsHint(
            text = stringResource(R.string.tts_voice_official_hint),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 自定义语音 API 的一行「标签 + 值」，点开弹输入框。 */
@Composable
private fun CustomTtsField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    // 与别的"点开填一个值"的行同一种：标题在左、当前值在右、行尾箭头
    SettingsRow(
        title = label,
        value = value.ifBlank { placeholder },
        chevron = true,
        onClick = onClick,
    )
}

/**
 * 音色列表的一行：名称 +（可选）试听 + 选中标记。
 *
 * 选中用的是设置里通用的那颗中性的勾，而且**没选中时也留着它的位置** ——
 * 此前勾是主题色、只在选中那行出现，于是试听按钮在选中行比别的行靠左一截。
 */
@Composable
private fun VoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    SettingsRow(
        title = label,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPreview != null) {
                    SettingsFieldButton(
                        icon = AppIcons.Play,
                        contentDescription = stringResource(R.string.tts_voice_preview),
                        onClick = onPreview,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                SettingsCheck(selected = selected)
            }
        },
    )
}

// ══════════════════════════════════════════════════════════
//  二级：数据管理
// ══════════════════════════════════════════════════════════

@Composable
private fun DataPage(
    message: String?,
    /** 确定进度：null = 还没开始或已经结束；0..1 = 已解析的会话占比。 */
    progress: Float? = null,
    /** 无进度阶段（读文件、开包）—— 给循环进度条，别让界面看着像卡死。 */
    reading: Boolean = false,
    onExport: () -> Unit,
    onExportArchive: () -> Unit,
    onImport: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_group_backup))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.settings_export_all),
            hint = stringResource(R.string.settings_export_all_summary),
            icon = AppIcons.Download,
            onClick = onExport,
        )
        SettingsActionRow(
            label = stringResource(R.string.settings_export_archive),
            hint = stringResource(R.string.settings_export_archive_summary),
            icon = AppIcons.Archive,
            onClick = onExportArchive,
        )
        SettingsActionRow(
            label = stringResource(R.string.settings_import_all),
            hint = stringResource(R.string.settings_import_all_summary),
            icon = AppIcons.Upload,
            onClick = onImport,
            // 导入期间不能重复点：同一份包并发解析会把内存和数据库都顶爆
            enabled = !reading && progress == null,
        )
    }

    /*
     * 进度条。大包（几百 MB）要走上分钟，没有进度的话界面上就是"点了没反应"——
     * 这正是先前那份 228MB 导出包给人的印象。
     *
     * 解析阶段按"已处理多少条会话"给确定进度；拷贝与收尾阶段只有 indeterminate。
     */
    if (reading || progress != null) {
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadH)) {
            /*
             * 两个阶段用两种进度条：
             * - **读取中**（落盘、开 ZIP、读索引）：无从得知比例 → 循环进度条；
             * - **导入中**（逐条会话）：按已处理会话数给百分比。
             *
             * 先前读文件阶段也塞了个 0% 的确定进度条，几十秒不动 —— 看着就是"卡死"。
             */
            if (reading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    // 颜色跟主题走：Material 默认的轨道色在这套配色里是一条发紫的灰
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = LocalChatColors.current.outline,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.import_reading),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatColors.current.placeholder,
                )
            } else if (progress != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = LocalChatColors.current.outline,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.import_progress, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatColors.current.placeholder,
                )
            }
        }
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_reset))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.settings_delete_all),
            hint = stringResource(R.string.settings_destructive_hint),
            icon = AppIcons.Trash,
            destructive = true,
            onClick = onDeleteAll,
        )
    }

    /*
     * 导出 / 导入 / 清空的结果。
     *
     * 用提示条而不是卡片外的一行灰字：这句话是用户刚才那个动作的**结果**
     * （「已导出」「导入失败：…」），灰字夹在说明文字里很容易被当成又一句说明。
     */
    message?.let {
        Spacer(Modifier.size(12.dp))
        SettingsBanner(text = it, selectable = true)
    }
}

// ══════════════════════════════════════════════════════════
//  二级：API 配置
// ══════════════════════════════════════════════════════════

/** 一句状态 + 它是好消息还是坏消息（「测试连接」「拉取模型列表」的结果）。 */
private data class StatusMessage(val text: String, val tone: SettingsTone)

/** 地址里的主机名那一截：`https://api.openai.com/v1` → `api.openai.com`。 */
private fun hostOf(url: String): String = url
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

/**
 * API 配置页。
 *
 * 版面按**用户脑子里的顺序**排，而不是按字段表：
 *
 *  1. **现在用的是哪一套**（顶上的大卡）—— 服务商徽标、配置名、指向哪个域名、
 *     还缺什么。此前这页是五行一模一样的灰字，「能不能用」要把每一行的值
 *     读一遍自己判断；
 *  2. **连接**：服务商 → 地址 → Key → 测试。填的顺序就是排的顺序，「测试连接」
 *     收在这张卡的最后，结果用颜色区分成败（此前是一行灰字，成功失败一个样）；
 *  3. **模型**：现在用哪个、切换面板里摆哪几个；
 *  4. **上下文与输出**、**高级**：多数人不用碰的放最后。
 *
 * 每一行都有自己的图标：这页有九个入口，全是「标题 + 灰色的值」时，
 * 找某一项只能从上往下逐行读标题。
 */
@Composable
private fun ApiPage(
    stored: StoredSettings,
    provider: ProviderKind,
    testing: Boolean,
    testResult: StatusMessage?,
    onPick: (SettingsDialog) -> Unit,
    onTest: () -> Unit,
) {
    val baseUrl = stored.baseUrl.ifBlank { provider.defaultBaseUrl }
    val keyMissing = provider.requiresApiKey && !stored.hasKey
    val modelMissing = stored.modelId.isBlank()
    val model = ModelCatalog.find(provider, stored.modelId)

    Spacer(Modifier.size(10.dp))

    /*
     * 「当前配置」这张卡是**多端点**的入口，所以必须排在最前面：下面几行
     * （服务商 / 地址 / Key / 模型）描述的是**当前这一套**，而这张卡决定
     * "当前是哪一套"。顺序反过来，用户会以为改服务商就是换配置 ——
     * 实际是改掉了某套配置里的供应商。
     */
    SettingsCard {
        ApiProfileHeader(
            stored = stored,
            provider = provider,
            baseUrl = baseUrl,
            keyMissing = keyMissing,
            modelMissing = modelMissing,
            onClick = { onPick(SettingsDialog.Profiles) },
        )
    }

    // ── 连接 ────────────────────────────────────────────
    SettingsSectionLabel(stringResource(R.string.api_group_connection))

    SettingsCard {
        SettingsRow(
            title = stringResource(R.string.label_provider),
            icon = AppIcons.Server,
            value = provider.displayName,
            chevron = true,
            onClick = { onPick(SettingsDialog.Provider) },
        )
        SettingsRow(
            title = "Base URL",
            icon = AppIcons.Link,
            // 去掉 https://：值这一列只有 180dp，省下来的宽度留给真正有区别的那一截。
            // http:// 不去 —— 明文连接是值得被看见的
            value = baseUrl.removePrefix("https://"),
            chevron = true,
            onClick = { onPick(SettingsDialog.BaseUrl) },
        )
        SettingsRow(
            title = "API Key",
            icon = AppIcons.Key,
            value = when {
                stored.hasKey -> maskKey(stored.apiKey)
                !provider.requiresApiKey -> stringResource(R.string.api_key_not_required)
                else -> stringResource(R.string.api_key_unset)
            },
            valueTone = if (keyMissing) SettingsTone.Warning else SettingsTone.Neutral,
            chevron = true,
            onClick = { onPick(SettingsDialog.ApiKey) },
        )
        /*
         * 「测试连接」是一颗真正的按钮，不再是一行字。
         *
         * 此前它和上面的入口行长得一样（只是没有箭头），用户要靠读文字才知道
         * "这一行点下去是执行，不是进下一层"。进行中的状态也只是把字换成
         * 「正在测试…」，没有任何在动的东西。
         */
        Box(
            modifier = Modifier.padding(start = RowPadH, end = RowPadH, top = 4.dp, bottom = 14.dp),
        ) {
            SettingsButton(
                text = stringResource(if (testing) R.string.api_testing else R.string.api_test),
                onClick = onTest,
                modifier = Modifier.fillMaxWidth(),
                style = SettingsButtonStyle.Tonal,
                loading = testing,
                icon = AppIcons.Bolt,
            )
        }
    }

    if (testResult != null) {
        Spacer(Modifier.size(10.dp))
        SettingsBanner(
            text = testResult.text,
            tone = testResult.tone,
            // 报错原文允许长按复制：用户要把它发给别人看
            selectable = testResult.tone == SettingsTone.Danger,
        )
    }

    // ── 模型 ────────────────────────────────────────────
    SettingsSectionLabel(stringResource(R.string.label_model))

    SettingsCard {
        // 行首是**这个模型自己家**的标志（中转站上跑 Claude，这里就是 Claude）；
        // 认不出来的用通用模型图标
        val modelGlyph = remember(stored.modelId) {
            (ModelMarks.ofModelId(stored.modelId) as? ModelMarks.Mark.Logo)?.vector
        } ?: AppIcons.ModelGeneric
        val capabilities = if (modelMissing) {
            emptyList<String>()
        } else {
            listOfNotNull(
                stringResource(R.string.model_tag_vision).takeIf { model.supportsVision },
                stringResource(R.string.model_tag_thinking).takeIf { model.supportsThinking },
            )
        }
        SettingsRow(
            title = stringResource(R.string.api_model_current),
            subtitle = capabilities.joinToString(" · ").ifBlank { null },
            icon = modelGlyph,
            // 自定义服务商初始没有模型，空串不该显示成一行空白
            value = stored.modelId.ifBlank { stringResource(R.string.api_model_unset) },
            valueTone = if (modelMissing) SettingsTone.Warning else SettingsTone.Neutral,
            chevron = true,
            onClick = { onPick(SettingsDialog.Model) },
        )
        /*
         * 「切换面板里的模型」单独一行，与上面的「当前模型」分开。
         *
         * 两者解决的不是同一件事：上面是"这一段对话用哪个脑子"，下面是
         * "切换面板里摆哪几个备选"。合在一起做，用户每次换模型都要面对
         * 一屏勾选框，而勾选框根本不影响这一次的选择。
         */
        SettingsRow(
            title = stringResource(R.string.api_model_switcher),
            icon = AppIcons.ListChecks,
            value = run {
                val pinned = ModelCatalog.pinnedIds(provider, stored.pinnedModelIds)
                val total = ModelCatalog.pickerModels(provider, stored.modelIds).size
                if (pinned.isEmpty()) {
                    stringResource(R.string.model_picker_all_hint, total)
                } else {
                    stringResource(R.string.model_picker_count, pinned.size, total)
                }
            },
            chevron = true,
            onClick = { onPick(SettingsDialog.PinnedModels) },
        )
    }
    SettingsHint(
        text = stringResource(R.string.api_model_switcher_hint),
        modifier = Modifier.padding(top = 8.dp),
    )

    /*
     * 上下文与输出。
     *
     * 上下文窗口单独给一档「跟随模型」：多数人不该关心这个数字，但一旦
     * 用的是中转站 / 私有部署，模型目录里的窗口常常是错的 —— 那时需要手动锁死。
     */
    SettingsSectionLabel(stringResource(R.string.api_group_limits))

    SettingsCard {
        val preset = ModelResolver.ContextPreset.fromId(stored.contextPresetId)
        val effectiveWindow = ModelResolver.contextWindowFor(
            base = model,
            presetId = stored.contextPresetId,
            custom = stored.contextWindowCustom,
        )
        SettingsRow(
            title = stringResource(R.string.settings_context),
            icon = AppIcons.Layers,
            value = when (preset) {
                ModelResolver.ContextPreset.AUTO ->
                    stringResource(R.string.context_auto) + " · " + TokenEstimate.format(effectiveWindow)
                ModelResolver.ContextPreset.CUSTOM ->
                    stringResource(R.string.context_tokens, TokenEstimate.format(effectiveWindow))
                else -> stringResource(preset.labelRes())
            },
            chevron = true,
            onClick = { onPick(SettingsDialog.Context) },
        )
        SettingsRow(
            title = stringResource(R.string.settings_max_output),
            icon = AppIcons.Gauge,
            value = if (stored.maxOutputTokens <= 0) {
                stringResource(R.string.max_output_default)
            } else {
                stringResource(R.string.context_tokens, TokenEstimate.format(stored.maxOutputTokens))
            },
            chevron = true,
            onClick = { onPick(SettingsDialog.MaxOutput) },
        )
    }

    /*
     * 高级：通道与地区。
     *
     * 协议通道决定走哪条端到端路径，地区只进时间地点提醒。
     * 两者都不是必填项 —— 不碰它们，原来的行为一点不变。
     */
    SettingsSectionLabel(stringResource(R.string.api_group_advanced))

    SettingsCard {
        SettingsRow(
            title = stringResource(R.string.settings_api_style),
            icon = AppIcons.Route,
            value = stringResource(ApiStylePreference.fromId(stored.apiStyleId).labelRes),
            chevron = true,
            onClick = { onPick(SettingsDialog.ApiStyle) },
        )
        SettingsRow(
            title = stringResource(R.string.settings_location),
            icon = AppIcons.MapPin,
            value = stored.locationRegion.ifBlank {
                stringResource(R.string.settings_location_hint_short)
            },
            chevron = true,
            onClick = { onPick(SettingsDialog.Location) },
        )
    }
}

/**
 * API 页顶上的「当前配置」：徽标 + 配置名 + 域名 + 状态标签，整张可点（切换 / 管理配置）。
 *
 * 状态标签回答的是"现在能不能发消息"：缺 Key、没选模型各给一个提醒色的标签，
 * 两样都齐了才是「已就绪」。它**不代表连通** —— 连不连得上要点「测试连接」，
 * 这里只说配置齐不齐。
 */
@Composable
private fun ApiProfileHeader(
    stored: StoredSettings,
    provider: ProviderKind,
    baseUrl: String,
    keyMissing: Boolean,
    modelMissing: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val active = stored.profiles.firstOrNull { it.id == stored.activeProfileId }
        ?: stored.profiles.firstOrNull()
    val name = active?.name?.takeIf { it.isNotBlank() } ?: provider.displayName
    val host = hostOf(baseUrl)
    // 没起名的配置，标题就是服务商名 —— 副标题里不再重复一遍
    val detail = if (name == provider.displayName || host.isBlank()) {
        host.ifBlank { provider.displayName }
    } else {
        provider.displayName + " · " + host
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = RowPadH, end = RowPadH, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderBadge(kind = provider, baseUrl = baseUrl, size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    // 标签一般两三个，放得下；极窄的屏上宁可横向滑，也不要把卡片撑成两行
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (keyMissing) {
                    SettingsTag(
                        text = stringResource(R.string.api_state_no_key),
                        tone = SettingsTone.Warning,
                    )
                }
                if (modelMissing) {
                    SettingsTag(
                        text = stringResource(R.string.api_state_no_model),
                        tone = SettingsTone.Warning,
                    )
                }
                if (!keyMissing && !modelMissing) {
                    SettingsTag(
                        text = stringResource(R.string.api_state_ready),
                        tone = SettingsTone.Accent,
                    )
                }
                if (stored.profiles.size > 1) {
                    SettingsTag(
                        text = stringResource(R.string.api_profile_count, stored.profiles.size),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        SettingsChevron()
    }
}

// ══════════════════════════════════════════════════════════
//  二级：预设（系统提示词库）
// ══════════════════════════════════════════════════════════

/**
 * 预设 = **一段可命名的系统提示词**，像插件一样可插拔。
 *
 * 这一页只做**管理**：看有哪些、新建、改、删。
 *
 * 这里**没有开关**，也刻意不做。预设是"这段对话用什么语气"的一部分，
 * 而语气天然跟着话题走 —— 做成全局开关，用户在 A 对话里开的"冷叙事"
 * 会静默改写 B 对话，B 那边连个痕迹都没有。所以开关放在每个对话
 * 输入栏右上角的「⋯」里，切一次话题换一份。
 *
 * 拼接顺序 = **列表顺序**（即创建顺序），不按勾选的先后 —— 列表是用户
 * 唯一看得见的顺序，按它拼接才可预期。
 */
@Composable
private fun PresetsPage(
    presets: List<Preset>,
    onEdit: (Preset?) -> Unit,
    onDelete: (Preset) -> Unit,
    onImport: (com.simplechat.app.data.PresetImport.Draft) -> Unit,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }

    /*
     * 文件选择器：**不限类型**。
     *
     * 预设的本质就是一段文字，所以任何能读成文字的文件都该能导进来。
     * 按 MIME 白名单过滤（只让 .md / .txt 过）会直接让用户"根本看不到
     * 自己的文件" —— 同一份内容存成 .json 或 .yaml 就选不到了。
     * 读不出来的时候再报错，那时报的是"这是二进制 / 这是 PDF"，比
     * "列表里根本没有它"要好定位得多。
     */
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null
        scope.launch {
            com.simplechat.app.data.PresetImport.read(context, uri).fold(
                onSuccess = { draft ->
                    if (draft.content.isBlank()) {
                        importError = context.getString(R.string.err_preset_empty)
                    } else {
                        onImport(draft)
                    }
                },
                onFailure = { error -> importError = error.userMessage() },
            )
        }
    }

    SettingsSectionLabel(stringResource(R.string.settings_group_presets))

    /*
     * 说明卡。
     *
     * 这一页现在只管"写"，不管"开" —— 不写清楚，用户会在这里找半天开关，
     * 然后得出"预设没用"的结论（上一版就是这么翻车的）。
     */
    SettingsCard {
        Text(
            text = stringResource(R.string.preset_intro),
            style = MaterialTheme.typography.labelMedium.copy(lineHeight = 20.sp),
            color = colors.placeholder,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }

    Spacer(Modifier.size(10.dp))

    if (presets.isEmpty()) {
        /*
         * 空态。只是一行灰字"还没有预设"太敷衍 —— 用户第一次进来时
         * 既不知道预设是什么，也不知道下一步该点哪。
         */
        SettingsCard {
            SettingsEmptyState(
                // 与设置首页「预设」入口同一枚图标：进来看到的和点进来之前看到的是同一个东西
                icon = AppIcons.Scroll,
                title = stringResource(R.string.preset_empty),
                body = stringResource(R.string.preset_empty_hint),
            )
        }
    } else {
        SettingsCard {
            // 6dp 内边距 + 每行 8dp 圆角：让每一行看起来是一块独立的模块，
            // 而不是被横线切开的一串表格
            Column(modifier = Modifier.padding(6.dp)) {
                presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onEdit = { onEdit(preset) },
                    )
                }
            }
        }
    }

    Spacer(Modifier.size(10.dp))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.preset_import),
            hint = stringResource(R.string.preset_import_hint),
            icon = AppIcons.Download,
            onClick = { importPicker.launch(arrayOf("*/*")) },
        )
    }

    importError?.let { message ->
        SettingsHint(
            text = message,
            modifier = Modifier.padding(top = 6.dp),
            tone = SettingsTone.Danger,
        )
    }

    Spacer(Modifier.size(10.dp))

    SettingsCard {
        SettingsActionRow(
            label = stringResource(R.string.preset_new),
            hint = stringResource(R.string.preset_new_hint),
            icon = AppIcons.Plus,
            onClick = { onEdit(null) },
        )
    }

    Spacer(Modifier.size(16.dp))

    SettingsHint(text = stringResource(R.string.preset_order_hint))
}

/**
 * 一条预设：名字 + 两行摘要 + 右箭头。点整行进编辑。
 *
 * 早先右侧是个开关，现在开关搬进对话里了 —— 这里换成箭头，
 * 明确"点进去改内容"，而不是"点一下就生效"。
 */
@Composable
private fun PresetRow(
    preset: Preset,
    onEdit: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onEdit)
            .padding(start = 12.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = preset.content,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp),
                color = colors.placeholder,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        SettingsChevron()
    }
}

/** 预设编辑器：名称 + 提示词正文。 */
@Composable
private fun PresetEditorDialog(
    initial: Preset?,
    onDismiss: () -> Unit,
    /** 编辑已有预设时才给；新建时为 null（没有东西可删）。 */
    onDelete: (() -> Unit)?,
    onConfirm: (name: String, content: String) -> Unit,
    /** 从文档导入时的预填值；编辑已有预设时不用。 */
    prefillName: String = "",
    prefillContent: String = "",
    /** 导入时做了什么处理，如实告诉用户。 */
    prefillNote: String? = null,
) {
    val colors = LocalChatColors.current
    var name by remember { mutableStateOf(initial?.name?.takeIf { it.isNotBlank() } ?: prefillName) }
    var content by remember { mutableStateOf(initial?.content?.takeIf { it.isNotBlank() } ?: prefillContent) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    val canSave = name.isNotBlank() && content.isNotBlank()

    /*
     * 键盘弹起时整张卡往上让（`decorFitsSystemWindows = false` + `imePadding()`）。
     *
     * 这个弹窗有一个最高 300dp 的正文框，此前键盘一出来，底下的「取消 / 保存」
     * 就被盖在键盘后面 —— 写完一大段提示词，得先收起键盘才点得到保存。
     */
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = if (initial == null) stringResource(R.string.preset_new) else stringResource(R.string.preset_edit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(14.dp))

            FieldBox {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.preset_name_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            if (!prefillNote.isNullOrBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = prefillNote,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = LocalChatColors.current.placeholder,
                )
            }

            Spacer(Modifier.size(10.dp))

            /*
             * 正文框放进 `weight(1f, fill = false)`：平时按内容长高（最高 300dp），
             * 键盘占掉半屏时自己缩回去 —— 被挤掉的是正文框的高度，而不是底下的按钮。
             */
            Box(modifier = Modifier.weight(1f, fill = false)) {
                FieldBox {
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 300.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box {
                                if (content.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.preset_body_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.placeholder,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.size(18.dp))

            /*
             * 底部：[删除] 靠左，[取消] [保存] 靠右。
             *
             * 删除挪进编辑器，是因为列表行右侧已经被开关占了 —— 一颗叉号
             * 挨着一个开关，误触率比"多一步确认"更烦人。这里点删除仍然会
             * 弹那个二次确认，不会一按就没。
             */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextAction(
                        text = stringResource(R.string.preset_delete_action),
                        destructive = true,
                        onClick = onDelete,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                TextAction(
                    text = stringResource(R.string.action_save),
                    primary = true,
                    enabled = canSave,
                    onClick = { onConfirm(name.trim(), content.trim()) },
                )
            }
        }
    }
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MenuShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

// ══════════════════════════════════════════════════════════
//  二级：字体大小
// ══════════════════════════════════════════════════════════

@Composable
private fun FontPage(
    stored: StoredSettings,
    onFollowSystem: (Boolean) -> Unit,
    onScaleCommit: (Float) -> Unit,
) {
    val colors = LocalChatColors.current
    val follow = stored.followSystemFont

    // 拖动时只改本地状态做即时预览，松手才落盘 —— 否则一次拖动几十次写 DataStore
    var index by remember(stored.fontScale) {
        mutableStateOf(
            fontSizeSteps.indexOfFirst { it.first == stored.fontScale }.takeIf { it >= 0 } ?: 2,
        )
    }
    val previewScale = fontSizeSteps[index].first
    val baseDensity = LocalDensity.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.size(24.dp))

        // ── 预览：真实气泡，用当前档位的字号渲染 ──────────
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * previewScale,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.font_preview_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onUserBubble,
                    modifier = Modifier
                        .clip(BubbleShapeOut)
                        .background(colors.userBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        Spacer(Modifier.size(20.dp))

        Text(
            text = stringResource(R.string.font_scale_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.size(24.dp))

        // ── 底部控制区 ────────────────────────────────────
        SettingsCard {
            SettingsSwitchRow(
                label = stringResource(R.string.value_follow_system),
                icon = AppIcons.FontSize,
                checked = follow,
                onCheckedChange = onFollowSystem,
            )
        }

        Spacer(Modifier.size(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (follow) stringResource(R.string.value_follow_system) else stringResource(fontSizeSteps[index].second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (follow) colors.placeholder else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "A",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.placeholder,
            )
        }

        AppSlider(
            value = index.toFloat(),
            onValueChange = { index = it.toInt().coerceIn(0, fontSizeSteps.lastIndex) },
            intervals = fontSizeSteps.lastIndex,
            enabled = !follow,
            valueRange = 0f..fontSizeSteps.lastIndex.toFloat(),
            onValueChangeFinished = { onScaleCommit(fontSizeSteps[index].first) },
        )
    }
}

// ══════════════════════════════════════════════════════════
//  二级：上下文与记忆
// ══════════════════════════════════════════════════════════

/**
 * 上下文与摘要的参数。
 *
 * 默认值与 Operit 完全一致：token 0.70 触发、自上次摘要后 16 条用户消息触发、
 * 摘要固定四节。这里只留**真的会改变行为**的几个数。
 *
 * "保留最近几条"已经删掉：摘要之后的消息本来就全部照常发送，
 * 保留区只会成为摘要的盲区 —— 既不进摘要，又占着窗口。
 */
@Composable
private fun TuningPage(
    stored: StoredSettings,
    onToggleCompact: (Boolean) -> Unit,
    onRatio: (Float) -> Unit,
    onToggleCompactByCount: (Boolean) -> Unit,
    onCompactCount: (Int) -> Unit,
    onCompactRules: (String) -> Unit,
    onToggleLargeExempt: (Boolean) -> Unit,
    onToggleReserveOutput: (Boolean) -> Unit,
    onToggleChunked: (Boolean) -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.tuning_compact_group))

    SettingsCard {
        SettingsSwitchRow(
            icon = AppIcons.Collapse,
            label = stringResource(R.string.settings_auto_compact),
            checked = stored.autoCompact,
            onCheckedChange = onToggleCompact,
        )
        SettingsSliderRow(
            icon = AppIcons.Sliders,
            label = stringResource(R.string.tuning_ratio),
            value = stored.compactRatio,
            range = 0.10f..0.95f,
            intervals = 17,
            enabled = stored.autoCompact,
            onCommit = onRatio,
            number = NumberEdit(
                value = kotlin.math.round(stored.compactRatio * 100).toInt(),
                suffix = "%",
                range = 10..95,
                onCommit = { onRatio(it / 100f) },
            ),
        )
        SettingsSwitchRow(
            icon = AppIcons.MessageSquare,
            label = stringResource(R.string.tuning_compact_by_count),
            checked = stored.compactByCount,
            onCheckedChange = onToggleCompactByCount,
        )
        SettingsSliderRow(
            icon = AppIcons.MessageSquare,
            label = stringResource(R.string.tuning_compact_count),
            value = stored.compactCount.toFloat(),
            range = 4f..200f,
            // 不画刻度：196 个刻度会连成一条实线。这个范围也不需要吸附到整档，
            // 松手时按整数落库（见 onCommit 的 round）
            intervals = 0,
            enabled = stored.autoCompact && stored.compactByCount,
            onCommit = { onCompactCount(kotlin.math.round(it).toInt()) },
            number = NumberEdit(
                value = stored.compactCount,
                suffix = stringResource(R.string.tuning_count_unit),
                range = 4..200,
                onCommit = onCompactCount,
            ),
        )
    }

    Text(
        text = stringResource(R.string.tuning_compact_hint),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
        color = LocalChatColors.current.placeholder,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )

    /*
     * 三个"什么时候压"的开关。
     *
     * 它们不改变触发**比例**本身，而是回答三个更实际的问题：
     *
     * - 大窗口（≥512K）要不要也按比例压？1M 的模型装得下，压了纯亏。
     * - 窗口里要不要给输出留位置？窗口是输入输出共享的。
     * - 太长的会话要不要分段总结？一次塞几十万 token，128K 的模型直接拒收。
     */
    SettingsCard {
        SettingsSwitchRow(
            icon = AppIcons.Expand,
            label = stringResource(R.string.tuning_compact_large_exempt),
            checked = stored.compactLargeWindowExempt,
            onCheckedChange = onToggleLargeExempt,
        )
        SettingsSwitchRow(
            icon = AppIcons.Sliders,
            label = stringResource(R.string.tuning_compact_reserve_output),
            checked = stored.compactReserveOutput,
            onCheckedChange = onToggleReserveOutput,
        )
        SettingsSwitchRow(
            icon = AppIcons.ListChecks,
            label = stringResource(R.string.tuning_compact_chunked),
            checked = stored.compactChunked,
            onCheckedChange = onToggleChunked,
        )
    }

    Text(
        text = stringResource(R.string.tuning_compact_policy_hint),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
        color = LocalChatColors.current.placeholder,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )

    /*
     * 摘要规则：模型对**末尾的显式要求**服从度最高，所以这段会被拼到摘要
     * 提示词的最后（见 `ContextSummary.systemPrompt`）。
     */
    SettingsCard {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.tuning_compact_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.tuning_compact_rules_hint),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = LocalChatColors.current.placeholder,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            // 无 key：每次输入都以本地 state 为准。带 key 的话，DataStore 回写一次
            // `stored` 就变一次，Composable 被重建、光标直接跳回开头。
            var rules by remember { mutableStateOf(stored.compactRules) }
            FieldBox {
                BasicTextField(
                    value = rules,
                    onValueChange = {
                        rules = it
                        onCompactRules(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp, max = 200.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (rules.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.tuning_compact_rules_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalChatColors.current.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }

    Spacer(Modifier.size(24.dp))
}

/**
 * 行尾那个可编辑的数字。
 *
 * 滑杆只能给"整档"的值，而压缩阈值这种参数经常需要填一个具体数
 * （比如 0.83、37 条）。所以右边是一个真的输入框：点一下就能改，
 * 回车或失焦时夹到合法区间并落盘。
 */
private data class NumberEdit(
    val value: Int,
    val suffix: String,
    val range: IntRange,
    val onCommit: (Int) -> Unit,
)

/**
 * 「标题 + 当前值（可编辑）+ 一条滑杆」。
 *
 * 拖动过程中只改本地草稿，**松手才落盘** —— 每帧写一次 DataStore 会明显卡，
 * 而且会把用户拖过的每一个中间值都记一遍历史。
 */
@Composable
private fun SettingsSliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    intervals: Int,
    enabled: Boolean = true,
    number: NumberEdit? = null,
    onCommit: (Float) -> Unit,
) {
    val colors = LocalChatColors.current
    var draft by remember(value) { mutableStateOf(value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = RowPadH, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(RowIconColumn), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else colors.placeholder,
                modifier = Modifier.size(RowIcon),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.placeholder,
            modifier = Modifier.weight(1f),
        )
        if (number != null) {
            NumberPill(number = number, enabled = enabled)
        }
    }
    AppSlider(
        value = draft,
        onValueChange = { draft = it },
        valueRange = range,
        intervals = intervals,
        enabled = enabled,
        onValueChangeFinished = { onCommit(draft) },
        modifier = Modifier.padding(start = RowPadH - 4.dp, end = 12.dp, bottom = 6.dp),
    )
}

/**
 * 药丸形数字输入。
 *
 * 两个细节：
 * - **只吃数字**，并且截断到 3 位 —— 中文输入法、粘贴带单位都能被挡在外面。
 * - **失焦也算提交**。用户点空白处收起键盘是常态，不提交的话他以为改了，
 *   实际还是旧值。
 */
@Composable
private fun NumberPill(
    number: NumberEdit,
    enabled: Boolean,
) {
    val colors = LocalChatColors.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    /*
     * 用 `TextFieldValue` 而不是纯 String：点进来时把旧值**全选**。
     * 不选的话光标停在末尾，用户按 "8" 会得到 "708" —— 再被夹成 95，
     * 看起来像输入框坏了。
     */
    var draft by remember { mutableStateOf(TextFieldValue(number.value.toString())) }
    // 已经提交过的值：回车会先 commit 再 clearFocus，失焦回调会再走一遍 ——
    // 没有这道去重，同一个数会往 DataStore 写两次
    var committed by remember { mutableStateOf(number.value) }

    // 滑杆拖动会改 value：没在输入时跟着走，正在输入时别抢光标
    LaunchedEffect(number.value) {
        if (!focused) {
            val text = number.value.toString()
            draft = TextFieldValue(text, selection = TextRange(text.length))
            committed = number.value
        }
    }

    fun commit() {
        val parsed = draft.text.trim().toIntOrNull()
        val clamped = (parsed ?: committed).coerceIn(number.range.first, number.range.last)
        val text = clamped.toString()
        draft = TextFieldValue(text, selection = TextRange(text.length))
        if (clamped != committed) {
            committed = clamped
            number.onCommit(clamped)
        }
    }

    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(if (enabled) colors.fieldBackground else colors.fieldBackground.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { value ->
                val digits = value.text.filter { it.isDigit() }.take(3)
                draft = value.copy(text = digits, selection = TextRange(digits.length))
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.placeholder,
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .widthIn(min = 18.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        focused = true
                        // 全选旧值：点一下就能直接改成新数
                        draft = draft.copy(selection = TextRange(0, draft.text.length))
                    } else if (focused) {
                        focused = false
                        commit()
                    }
                },
        )
        Text(
            text = number.suffix,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colors.placeholder,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  二级：绘图
// ══════════════════════════════════════════════════════════

/**
 * 绘图服务配置。
 *
 * 结构与「模型服务」页刻意保持一致：状态卡 → 连接 → 模型 → 出图参数。
 * 用户在同一个 App 里学一次"怎么配一个服务"，两处都适用 ——
 * 之前这里是四行光秃秃的文字，连图标都没有，看起来像未完成的草稿。
 *
 * 三个**不做**的事，都是踩过之后才明白的：
 * - 不放「测试出图」：按张计费，点一下就是真金白银；
 * - **不预填模型名**：各家网关的 id 千差万别，预填 `gpt-image-1` 的结果是
 *   用户直接保存、之后每次出图都 404，而界面上看起来"已经配好了"；
 * - **默认不传尺寸**：不同模型支持的尺寸集合完全不一样，把选择权交给服务端
 *   才是真正的"自动"。
 */
@Composable
private fun ImageApiPage(
    stored: StoredSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    store: com.simplechat.app.data.SettingsStore,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val container = remember { (context.applicationContext as App).container }

    /** 正在编辑哪个字段；null = 没开弹窗。 */
    var editing by remember { mutableStateOf<String?>(null) }
    var pickingModel by remember { mutableStateOf(false) }
    var pickingSize by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchHint by remember { mutableStateOf<StatusMessage?>(null) }

    fun save(
        url: String = stored.imageApiUrl,
        key: String = stored.imageApiKey,
        model: String = stored.imageApiModel,
        size: String = stored.imageApiSize,
    ) {
        scope.launch { store.setImageApi(url, key, model, size) }
    }

    val configured = stored.hasImageApi
    val host = stored.imageApiUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
    /*
     * 拉模型列表用的 base。
     *
     * 用户可能把完整端点（`…/v1/images/generations`）粘进来，而 `/models`
     * 挂在它的**上一层**。不剥掉这一段，拉取就会 404，而用户完全不知道
     * 是自己的地址写法导致的。
     */
    val modelsBase = stored.imageApiUrl.trim()
        .removeSuffix("/images/generations")
        .trimEnd('/')

    // ── 状态 ──────────────────────────────────────────────
    Spacer(Modifier.size(10.dp))
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RowPadH, end = RowPadH, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * 与「API 配置」页顶上那张卡同一个版式：徽标 + 名字 + 域名 + 状态标签。
             *
             * 徽标跟着**模型**走（flux → Flux、dall-e → OpenAI）；认不出模型再看域名；
             * 都认不出就是一枚画框。
             */
            val mark = remember(stored.imageApiModel, stored.imageApiUrl) {
                val byModel = ModelMarks.ofModelId(stored.imageApiModel)
                if (byModel is ModelMarks.Mark.Logo) byModel else ModelMarks.ofHost(stored.imageApiUrl)
            }
            BrandBadge(mark = mark, size = 46.dp, fallback = AppIcons.Image)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (configured) {
                        stored.imageApiModel
                    } else {
                        stringResource(R.string.image_api_entry_off)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (configured) {
                        host.ifBlank { stored.imageApiUrl }
                    } else {
                        stringResource(R.string.image_api_ready_hint)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.placeholder,
                    // 没配置时这一行是说明文字，允许折行；配好之后是域名，一行够了
                    maxLines = if (configured) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (configured) {
                    SettingsTag(
                        text = stringResource(R.string.image_api_ready),
                        tone = SettingsTone.Accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // ── 连接 ──────────────────────────────────────────────
    SettingsSectionLabel(stringResource(R.string.image_api_group_connection))
    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Link,
            label = stringResource(R.string.image_api_url),
            value = host.ifBlank { stringResource(R.string.image_api_entry_off) },
            onClick = { editing = "url" },
        )
        SettingsNavRow(
            icon = AppIcons.Key,
            label = stringResource(R.string.image_api_key),
            value = if (stored.imageApiKey.isNotBlank()) {
                maskKey(stored.imageApiKey)
            } else {
                stringResource(R.string.api_key_unset)
            },
            onClick = { editing = "key" },
        )
    }

    // ── 模型 ──────────────────────────────────────────────
    SettingsSectionLabel(stringResource(R.string.image_api_group_model))
    SettingsCard {
        val imageModelGlyph = remember(stored.imageApiModel) {
            (ModelMarks.ofModelId(stored.imageApiModel) as? ModelMarks.Mark.Logo)?.vector
        } ?: AppIcons.ModelGeneric
        SettingsNavRow(
            icon = imageModelGlyph,
            label = stringResource(R.string.image_api_model),
            value = stored.imageApiModel.ifBlank {
                stringResource(R.string.image_api_model_unset)
            },
            onClick = {
                fetchHint = null
                pickingModel = true
            },
        )
    }

    // ── 出图 ──────────────────────────────────────────────
    SettingsSectionLabel(stringResource(R.string.image_api_group_output))
    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Sliders,
            label = stringResource(R.string.image_api_size),
            value = stored.imageApiSize.ifBlank {
                stringResource(R.string.image_api_size_auto)
            },
            onClick = { pickingSize = true },
        )
    }

    SettingsHint(
        text = stringResource(R.string.image_api_hint),
        modifier = Modifier.padding(top = 10.dp),
    )

    // ── 弹窗 ──────────────────────────────────────────────
    if (pickingModel) {
        // 当前值排最前：它是"将被替换的那一个"，放在列表第一行最不容易选错
        val options = buildList {
            if (stored.imageApiModel.isNotBlank()) add(stored.imageApiModel)
            stored.imageApiModels.filter { it != stored.imageApiModel }.forEach { add(it) }
        }
        SettingsChoiceDialog(
            title = stringResource(R.string.image_api_model),
            options = options.map { SettingsChoice(value = it, label = it) },
            selected = stored.imageApiModel.takeIf { it.isNotBlank() },
            emptyHint = stringResource(R.string.image_api_model_empty),
            hint = if (fetching) {
                stringResource(R.string.api_model_fetching)
            } else {
                fetchHint?.text ?: stringResource(R.string.image_api_model_hint)
            },
            hintTone = if (fetching) {
                SettingsTone.Neutral
            } else {
                fetchHint?.tone ?: SettingsTone.Neutral
            },
            searchHint = stringResource(R.string.model_picker_search),
            leading = { id -> ModelBadge(model = ModelInfo(id = id), size = 30.dp) },
            actions = listOf(
                SettingsChoiceAction(
                    label = stringResource(
                        if (stored.imageApiModels.isEmpty()) R.string.api_model_fetch
                        else R.string.api_model_refetch,
                    ),
                    icon = AppIcons.Refresh,
                    onClick = {
                        if (!fetching) {
                            fetching = true
                            fetchHint = null
                            scope.launch {
                                val result = container.chatApi.fetchModels(
                                    baseUrl = modelsBase,
                                    apiKey = stored.imageApiKey,
                                )
                                result.onSuccess {
                                    store.setImageApiModels(it)
                                    fetchHint = StatusMessage(
                                        Res.get(R.string.api_model_fetch_ok, it.size),
                                        SettingsTone.Accent,
                                    )
                                }
                                result.onFailure {
                                    fetchHint = StatusMessage(
                                        Res.get(R.string.api_model_fetch_failed, it.userMessage()),
                                        SettingsTone.Danger,
                                    )
                                }
                                fetching = false
                            }
                        }
                    },
                ),
                SettingsChoiceAction(
                    label = stringResource(R.string.api_model_manual),
                    icon = AppIcons.Edit,
                    onClick = {
                        pickingModel = false
                        editing = "model"
                    },
                ),
            ),
            onDismiss = {
                pickingModel = false
                fetchHint = null
            },
            onSelect = { picked ->
                pickingModel = false
                fetchHint = null
                save(model = picked)
            },
        )
    }

    if (pickingSize) {
        ChoiceDialog(
            title = stringResource(R.string.image_api_size),
            /*
             * 比例放在像素前面。
             *
             * 像素是"服务端自己的事"：同一个 1024×1536，gpt-image 认、
             * dall-e-3 不认；而比例是用户和模型都能直接理解的东西，
             * 客户端会按模型换算成合法档位。只有确实要某组像素时才往下选。
             */
            options = listOf(
                "" to stringResource(R.string.image_api_size_auto),
                "1:1" to "1:1",
                "4:3" to "4:3",
                "3:4" to "3:4",
                "16:9" to "16:9",
                "9:16" to "9:16",
                "1024x1024" to "1024 × 1024",
                "1536x1024" to "1536 × 1024",
                "1024x1536" to "1024 × 1536",
            ),
            selected = stored.imageApiSize,
            hint = stringResource(R.string.image_api_size_hint),
            actions = listOf(
                stringResource(R.string.image_api_size_custom) to {
                    pickingSize = false
                    editing = "size"
                },
            ),
            onDismiss = { pickingSize = false },
            onSelect = { picked ->
                pickingSize = false
                save(size = picked)
            },
        )
    }

    when (editing) {
        "url" -> TextInputDialog(
            title = stringResource(R.string.image_api_url),
            initial = stored.imageApiUrl,
            placeholder = "https://api.openai.com/v1",
            hint = stringResource(R.string.image_api_url_hint),
            keyboardType = KeyboardType.Uri,
            mono = true,
            paste = true,
            onDismiss = { editing = null },
            onConfirm = { value -> editing = null; save(url = value.trim()) },
        )

        "key" -> TextInputDialog(
            title = stringResource(R.string.image_api_key),
            initial = stored.imageApiKey,
            placeholder = "sk-…",
            secret = true,
            onDismiss = { editing = null },
            onConfirm = { value -> editing = null; save(key = value.trim()) },
        )

        "model" -> TextInputDialog(
            title = stringResource(R.string.image_api_model),
            initial = stored.imageApiModel,
            placeholder = stringResource(R.string.image_api_model_manual_hint),
            mono = true,
            paste = true,
            onDismiss = { editing = null },
            onConfirm = { value -> editing = null; save(model = value.trim()) },
        )

        "size" -> TextInputDialog(
            title = stringResource(R.string.image_api_size),
            initial = stored.imageApiSize,
            placeholder = "1024x1024",
            hint = stringResource(R.string.image_api_size_custom_hint),
            onDismiss = { editing = null },
            onConfirm = { value -> editing = null; save(size = value.trim()) },
        )
    }
}

@Composable
private fun AboutPage(onReplayOnboarding: () -> Unit) {
    /*
     * 软件日志藏在关于页最底下。
     *
     * 它是"出问题时才用"的东西：开关、导出、路径，对日常使用没有任何价值，
     * 摆在设置首页只会多占一格注意力。放在这里——找得到，但要往下翻。
     *
     * 导出仍然走系统「保存到…」：日志里可能有服务器地址与工具参数，
     * 不该由 App 自己决定往哪儿写。
     */
    val logContext = LocalContext.current
    val logScope = rememberCoroutineScope()
    var logEnabled by remember { mutableStateOf(com.simplechat.app.util.AppLog.isEnabled()) }
    val logExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        logScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = com.simplechat.app.util.AppLog.export(logContext)
                        ?: error("export failed")
                    logContext.contentResolver.openOutputStream(uri)?.use { out ->
                        zip.inputStream().use { it.copyTo(out) }
                    } ?: error("open failed")
                    zip.delete()
                }.isSuccess
            }
            android.widget.Toast.makeText(
                logContext,
                if (ok) logContext.getString(R.string.log_export_done)
                else logContext.getString(R.string.log_export_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    SettingsSectionLabel(stringResource(R.string.about_version))

    /*
     * 作者卡片。
     *
     * 头像随包发（`drawable-nodpi/ic_author.png`），不走网络 —— 关于页是最不该
     * "打开时还要等一张图"的页面，离线打开也该是完整的。
     */
    AuthorCard()

    SettingsCard {
        InfoRow(label = stringResource(R.string.about_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        InfoRow(label = stringResource(R.string.about_package), value = BuildConfig.APPLICATION_ID)
        InfoRow(label = stringResource(R.string.about_app_name), value = "DSHA")
    }

    Spacer(Modifier.size(16.dp))
    SettingsCard {
        SettingsNavRow(
            icon = AppIcons.Play,
            label = stringResource(R.string.onboarding_replay),
            value = stringResource(R.string.onboarding_replay_summary),
            onClick = onReplayOnboarding,
        )
    }

    /*
     * 项目地址单独一组，不与上面的版本信息混排：
     * 这一行是**可点**的（点开跳到仓库），而上面几行是只读信息 ——
     * 混在一张卡里会出现「有的能点有的不能点」。
     */
    SettingsSectionLabel(stringResource(R.string.about_project))

    SettingsCard {
        LinkRow(
            label = stringResource(R.string.about_project_url),
            value = ProjectUrl,
            url = ProjectUrl,
        )
        LinkRow(
            label = stringResource(R.string.about_upstream),
            value = UpstreamUrl,
            url = UpstreamUrl,
        )
    }

    SettingsSectionLabel(stringResource(R.string.about_licenses))

    SettingsCard {
        InfoRow(label = stringResource(R.string.about_icon), value = "Lucide (ISC)")
        // 模型 / 服务商的品牌标志：Lobe Icons 为主，早先那十来枚来自 Simple Icons
        InfoRow(
            label = stringResource(R.string.about_brand_icons),
            value = "Lobe Icons (MIT) · Simple Icons (CC0)",
        )
        InfoRow(label = stringResource(R.string.about_libs_net), value = "OkHttp · kotlinx.serialization")
        InfoRow(label = stringResource(R.string.about_libs_storage), value = "Room · DataStore · Jetpack Compose")
        InfoRow(label = "Markdown", value = "multiplatform-markdown-renderer (Apache-2.0)")
        InfoRow(label = stringResource(R.string.about_libs_parsing), value = "JetBrains markdown · Highlights")
        InfoRow(label = stringResource(R.string.about_libs_crypto), value = "Android Keystore (AES-256-GCM)")
        InfoRow(label = stringResource(R.string.about_libs_math), value = "latex-renderer (MIT)")
        InfoRow(label = stringResource(R.string.about_libs_diagram), value = "Mermaid (MIT)")
        InfoRow(label = "DeepSeek Chat Demo", value = "MIT")
        InfoRow(label = "Simple-Agent", value = "MIT")
    }

    Spacer(Modifier.size(16.dp))

    /*
     * 参考项目。
     *
     * 这一块不是为了"好看"：Ubuntu 终端环境、proot 的启动参数、截图识别管线
     * 这几处都是从这两个项目里学来的（见各自那几段注释）。把出处写在
     * 用户能找到的地方，既是致谢，也让后来的人知道该去哪儿看实现。
     */
    SettingsSectionLabel(stringResource(R.string.about_refs))

    SettingsCard {
        RefProjectRow(
            name = "ZakoCoder",
            desc = stringResource(R.string.about_ref_zakocoder),
            url = "https://github.com/AYssu/ZakoCoder",
            icon = AppIcons.Terminal,
        )
        RefProjectRow(
            name = "Operit",
            desc = stringResource(R.string.about_ref_operit),
            url = "https://github.com/AAswordman/Operit",
            icon = AppIcons.Bolt,
        )
    }

    Spacer(Modifier.size(16.dp))

    SettingsHint(text = stringResource(R.string.about_ai_disclaimer))

    /*
     * 诊断（日志）—— 整页最后一块。
     *
     * 用最淡的分组标题、最下面一张卡，不做任何强调：这是给"要给别人反馈问题"
     * 的人准备的工具，不是给日常使用的开关。
     */
    SettingsSectionLabel(stringResource(R.string.settings_group_diag))

    SettingsCard {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_debug_log),
            checked = logEnabled,
            onCheckedChange = { on ->
                com.simplechat.app.util.AppLog.setEnabled(logContext, on)
                logEnabled = on
            },
            icon = AppIcons.Terminal,
        )
        SettingsNavRow(
            icon = AppIcons.Download,
            label = stringResource(R.string.settings_export_log),
            value = if (logEnabled) null else stringResource(R.string.settings_debug_log_off),
            onClick = {
                // 先补一条记录：日志关着时导出包是空的，里面那句 README 会说明原因
                com.simplechat.app.util.AppLog.i("Settings", "导出日志")
                val name = "dsha-logs-" +
                    java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                        .format(java.util.Date()) + ".zip"
                logExportLauncher.launch(name)
            },
        )
    }

    Spacer(Modifier.size(24.dp))
}

/**
 * 参考项目一行：图标 + 名字 + 一句说明，有链接时整行可点。
 *
 * 与 [SettingsNavRow] 分开写，是因为这里需要**两行文字**（名字 + 说明），
 * 而 SettingsNavRow 的右侧是"当前值"那种单行结构。硬塞进去会变成
 * "ZakoCoder ｜ 一个 Android 上的……"这种读不通的排版。
 */
@Composable
private fun RefProjectRow(
    name: String,
    desc: String,
    url: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (url != null) {
                    Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ),
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = RowPadH, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(RowIconColumn), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(RowIcon),
            )
        }
        Spacer(Modifier.width(RowIconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.placeholder,
            )
        }
        if (url != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = AppIcons.ExternalLink,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * 作者卡片：头像 + 名字 + 主页。
 *
 * 做成一张"人"的卡片而不是一行链接，是因为这是整个设置里唯一一处
 * 指向具体某个人的地方 —— 一行灰字 `github.com/cmbh0` 传达不了这件事。
 */
@Composable
private fun AuthorCard() {
    val colors = LocalChatColors.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 圆角、左右内边距与别的卡片一致：这张卡只是内容特别，不是另一种容器
            .clip(RoundedCornerShape(CardCorner))
            .background(colors.card)
            .clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(AuthorUrl),
                        ),
                    )
                }
            }
            .padding(horizontal = RowPadH, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_author),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "cmbh0",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = stringResource(R.string.about_author_role),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
            )
        }
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  基础组件
// ══════════════════════════════════════════════════════════

@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    /** 右侧状态文字（如 MCP 的"3 个工具"）。空则不占位。 */
    trailing: String? = null,
    /** 右侧文字用主色（"已连接"这类需要强调的状态）。 */
    trailingAccent: Boolean = false,
    /**
     * 右侧的图标动作（新增、编辑），里面放 [SettingsBarAction]。
     *
     * 有了它，从设置点进去的**每一页**都能用这同一个顶栏 —— 此前云端、工作区
     * 因为右上角要放按钮，各自手写了一条（36dp 灰底圆按钮 + 左对齐标题），
     * 和设置里其它页并排看是两套东西。
     */
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    /*
     * 顶栏：**一根细箭头 + 居中标题**，不带灰色圆底。
     *
     * 之前是"灰底圆圈里的箭头"，那是 Material 工具条的语言；官方设置页只是
     * 一根细箭头和一个标题 —— 一眼就像系统设置，而不是一个弹出来的次级面板。
     * 灰底圆圈还会让顶栏发重，下面所有行都显得挤。
     */
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(SettingsBarHeight),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp)
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.ChevronLeft,
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        // 标题居中。两侧留白**对称**：右边有图标动作时两边一起加宽，
        // 标题才不会被挤偏，长标题也不会压到按钮上。
        val side = if (actions != null) 100.dp else 56.dp
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = side, end = side),
        )

        if (trailing != null || actions != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    // 图标动作自带 44dp 点击区，贴边 6dp 与左侧返回箭头对称
                    .padding(end = if (actions != null) 6.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (trailing != null) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trailingAccent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            colors.placeholder
                        },
                    )
                }
                if (actions != null) {
                    if (trailing != null) Spacer(Modifier.width(4.dp))
                    actions()
                }
            }
        }
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    val colors = LocalChatColors.current
    /*
     * 分组标题：13sp 常规、灰、不带全大写。
     *
     * 间距是这一页"透气"的主要来源 —— 上 26 下 8。之前上 16 下 6，
     * 组与组挤在一起，九张卡看起来像一整片。
     */
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = colors.placeholder,
        modifier = Modifier.padding(start = RowPadH, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCorner))
            .background(colors.card),
    ) {
        content()
    }
}

@Composable
private fun RowDivider(inset: androidx.compose.ui.unit.Dp = RowDividerInset) {
    val colors = LocalChatColors.current
    /*
     * 0.7dp 而不是 1dp：1dp 在 3x 屏上会渲染成 3px 的实线，
     * 和官方那种"几乎看不见的一根"差一档。
     */
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(0.7.dp)
            .background(colors.divider),
    )
}

/** 主题色菜单的宽度：七个 30dp 色块 + 间距，比默认菜单宽。 */
private val AccentMenuWidth = 266.dp

/**
 * 读系统音色列表的超时。
 *
 * `TextToSpeech` 的初始化回调在个别设备上可能永远不来（引擎被禁用 / 服务被冻结）。
 * 不设上限的话，音色页会永远停在「正在读取…」—— 用户既看不到音色、也不知道该怎么办。
 */
private const val TtsVoiceLoadTimeoutMs = 4_000L

/**
 * 主题色菜单里的一个色块。
 *
 * 选中的那个在**外面**套一圈描边，而不是压着色块边缘画 ——
 * 色块本身不因为"选中"而变形，只是外圈标出"当前是这个"。
 *
 * 描边色用 `onSurface`（浅色下近黑、深色下近白）：不写死黑色，
 * 否则深色主题下选中态就看不见了。
 */
@Composable
private fun AccentSwatch(
    color: AccentColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 色块展示的是**当前主题模式下**的那个档位（深色下用提亮过的那一档）
    val dark = LocalIsDarkTheme.current
    val swatchDesc = stringResource(R.string.a11y_accent_color, color.label)
    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.primary(dark)),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            )
        }
        /*
         * 点击层单独一个**圆形**节点，而且放在最上面。
         *
         * ⚠️ 不能把 `clickable` 挂到外层 Box 上：水波纹的形状就是**节点的裁剪形状**，
         * 而外层恰恰**不能裁剪**（选中那圈描边是外溢到 30dp 之外的，裁了就会缺一圈）——
         * 于是波纹就成了一个方块。这里让一层 30dp 的圆来吃点击，波纹自然是圆的。
         */
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .semantics { contentDescription = swatchDesc }
                .clickable(onClick = onClick),
        )
    }
}

/*
 * 设置页的度量。
 *
 * 数值都在 `SettingsDimens`（见 SettingsKit.kt）里，**全体设置页共用那一份** ——
 * 行高 50、左右 16、图标 19dp 装在 28dp 的固定列里、卡片圆角 10。
 * 这里只留几个文件内的短名字，免得这个文件里两百多处调用点跟着改名。
 *
 * 图标列为什么是固定宽度：账号行放的是头像、其它行是线性图标。若让它们各占
 * 各的宽度，两种行的**文字起点会差十几个 dp** —— 整页看过去就是"歪的"。
 */
private val RowPadH = SettingsDimens.RowPadH
private val RowMinHeight = SettingsDimens.RowMinHeight
private val RowPadV = SettingsDimens.RowPadV
private val RowIconColumn = SettingsDimens.IconColumn
private val RowIcon = SettingsDimens.Icon
private val RowIconGap = SettingsDimens.IconGap

/** 顶栏高度（不含状态栏）。 */
private val SettingsBarHeight = SettingsDimens.BarHeight

/** 卡片圆角。官方是 10，比 20 的"大圆角卡片"更像系统设置。 */
private val CardCorner = SettingsDimens.CardCorner

/** 分隔线起点：与文字左缘对齐。 */
private val RowDividerInset = 16.dp

/*
 * 下面四种行都只是 `SettingsRow`（见 SettingsKit.kt）的固定搭配。
 *
 * 留着这几个名字，是因为这个文件和别的页面（MCP / 技能 / 运行环境 / 个人资料）
 * 有上百处调用；真正决定长相的只有 `SettingsRow` 一处 —— 此前四种行各写各的 Row，
 * 标题有的 Medium 有的 Normal、图标到文字有的 10dp 有的 12dp、箭头有的 13dp
 * 有的 14dp，改一处漏三处。
 */

/** 入口行：图标 + 标题 +（可选）当前值 + 箭头。 */
@Composable
fun SettingsNavRow(
    icon: ImageVector? = null,
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = label,
        icon = icon,
        value = value,
        chevron = true,
        onClick = onClick,
    )
}

/**
 * 账号行：头像 + 「账号管理」+ 名字 + 箭头。
 *
 * 头像占的是图标的位置，装在同一个固定列里 —— 文字起点与其它行在一条竖线上。
 *
 * 名字放右侧当成"当前值"，而不是当标题：标题固定是「账号管理」，
 * 用户扫一眼就知道这行通往哪里；自己的名字属于状态，不属于入口名。
 */
@Composable
private fun SettingsAccountRow(
    name: String,
    avatar: String,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.settings_account_manage),
        leading = {
            Box(modifier = Modifier.size(RowIconColumn), contentAlignment = Alignment.Center) {
                ProfileAvatar(uri = avatar, name = name, size = 30.dp)
            }
        },
        value = name.ifBlank { stringResource(R.string.profile_default_name) },
        valueMaxWidth = 150.dp,
        chevron = true,
        onClick = onClick,
    )
}

/** 动作行：标题 +（可选）说明，点下去就执行，所以不带箭头。 */
@Composable
private fun SettingsActionRow(
    label: String,
    hint: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
    /** 导入进行中之类的"暂时不能点"。 */
    enabled: Boolean = true,
    /** 与 [SettingsNavRow] 同一个图标列；不传就退化成纯文字行。 */
    icon: ImageVector? = null,
) {
    SettingsRow(
        title = label,
        subtitle = hint,
        icon = icon,
        destructive = destructive,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 开关行。整行可点（不必瞄准右边那颗开关）。 */
@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    SettingsRow(
        title = label,
        icon = icon,
        onClick = { onCheckedChange(!checked) },
        trailing = { AppSwitch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/**
 * 仓库地址。写成常量而不是资源：它是**代码里的一个事实**，不随界面语言变，
 * 也不该被翻译（翻成别的语言反而点不开）。改仓库时只改这一处。
 */
/** 项目主页（DSHA）。 */
private const val ProjectUrl = "https://github.com/cmbh0/DshAengt"

/** 作者主页。关于页那张卡片点进去就是这里。 */
private const val AuthorUrl = "https://github.com/cmbh0"

/** 上游：这个 App 的形态与交互大量参考它。署名不是客套，是许可证要求也是事实。 */
private const val UpstreamUrl = "https://github.com/Pokanshuu/Simple-Chat"

/**
 * 可点击的信息行 —— 样式与 [InfoRow] 一致，只是值用主色、整行可点。
 *
 * 用系统的 `ACTION_VIEW` 交给浏览器：应用内 WebView 打开 GitHub 只会带来
 * 一个返回键更别扭的界面，而"在浏览器里打开"也符合用户对这个动作的预期。
 */
@Composable
private fun LinkRow(label: String, value: String, url: String) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                // 没有浏览器时不要崩：提示一句就够了
                runCatching { context.startActivity(intent) }
            }
            // 行高、内边距、字号与 SettingsRow 同一套：信息行只是"值可以折行"的那种行
            .heightIn(min = RowMinHeight)
            .padding(horizontal = RowPadH, vertical = RowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 只读信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高、内边距、字号与 SettingsRow 同一套：信息行只是"值可以折行"的那种行
            .heightIn(min = RowMinHeight)
            .padding(horizontal = RowPadH, vertical = RowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        /*
         * 值这一列**必须撑满剩余宽度**（`weight(1f)` 默认 `fill = true`），
         * 否则它只按自然宽度排版，右边缘随文字长短飘 ——
         * 短值停在半路，看起来就像"有的左对齐有的右对齐"。
         *
         * 撑满之后 `TextAlign.End` 才真的把每个值的右边缘对到卡片同一条线上。
         */
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = colors.placeholder,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  弹窗
// ══════════════════════════════════════════════════════════

/** 配置的显示名：没起名就用服务商的名字。 */
private fun profileTitle(profile: com.simplechat.app.data.ModelProfile): String {
    val kind = runCatching { ProviderKind.valueOf(profile.providerId) }.getOrNull()
    return profile.name.ifBlank { kind?.displayName ?: profile.providerId }
}

/**
 * 这套配置缺不缺 Key。
 *
 * 没 Key 的配置切过去必然失败，这件事必须在**选中之前**写在行上。
 */
private fun profileLacksKey(profile: com.simplechat.app.data.ModelProfile): Boolean {
    val kind = runCatching { ProviderKind.valueOf(profile.providerId) }.getOrNull()
    return kind?.requiresApiKey != false && profile.apiKey.isBlank()
}

/**
 * 这套配置实际指向的主机名（没填地址就是服务商默认的那个）。
 *
 * 两套都叫「官方」时，靠域名那一截才分得开。
 */
private fun profileHost(profile: com.simplechat.app.data.ModelProfile): String {
    val kind = runCatching { ProviderKind.valueOf(profile.providerId) }.getOrNull()
    return hostOf(profile.baseUrl.ifBlank { kind?.defaultBaseUrl.orEmpty() })
}

/**
 * 模型配置列表：切换 / 新增 / 重命名 / 删除。
 *
 * **重命名和删除长在每一行上**，点哪行的就是改哪行。
 *
 * 此前这两个动作在列表底部、只作用于"当前选中的那套"：想删一套不用的配置，
 * 得先切过去（于是当前对话的服务商跟着变了）、再回来点删除 —— 而且「删除这套配置」
 * 里的"这套"指的是谁，弹窗里根本没写。
 *
 * 只剩一套时不给删除按钮：至少要留一套，按钮在那儿点了没反应才是真的别扭。
 */
@Composable
private fun ProfilesDialog(
    profiles: List<com.simplechat.app.data.ModelProfile>,
    activeId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_list_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                profiles.forEach { profile ->
                    val current = profile.id == activeId
                    val kind = runCatching { ProviderKind.valueOf(profile.providerId) }.getOrNull()
                    val lacksKey = profileLacksKey(profile)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickable { onSelect(profile.id) }
                            .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderBadge(
                            kind = kind ?: ProviderKind.CUSTOM,
                            baseUrl = profile.baseUrl,
                            size = 34.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profileTitle(profile),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.sp,
                                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // fill = false：名字短时标签紧跟其后，而不是被顶到最右
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (current) {
                                    Spacer(Modifier.width(6.dp))
                                    SettingsTag(
                                        text = stringResource(R.string.profile_current),
                                        tone = SettingsTone.Accent,
                                    )
                                }
                            }
                            Text(
                                text = if (lacksKey) {
                                    stringResource(R.string.profile_no_key)
                                } else {
                                    profileHost(profile)
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                ),
                                color = if (lacksKey) colors.warning else colors.placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        SettingsFieldButton(
                            icon = AppIcons.Edit,
                            contentDescription = stringResource(R.string.profile_rename),
                            onClick = { onRename(profile.id) },
                        )
                        if (profiles.size > 1) {
                            SettingsFieldButton(
                                icon = AppIcons.Trash,
                                contentDescription = stringResource(R.string.profile_delete),
                                onClick = { onDelete(profile.id) },
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(colors.divider),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onNew)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.profile_new),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 单选弹窗的短名字：把 `值 to 文字` 的列表转给 `SettingsChoiceDialog`（见 SettingsKit.kt）。
 *
 * 只有文字的简单单选（协议通道、上下文档位、导入方式）用它；要行首徽标、
 * 第二行说明、搜索的（服务商、模型）直接调 `SettingsChoiceDialog`。
 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    /** 一个选项都没有时显示的话。给 null 就是只留标题（其余调用方没有空列表）。 */
    emptyHint: String? = null,
    /** 列表下面的一行小字（拉取中 / 拉取结果）。 */
    hint: String? = null,
    /** 列表下面多出来的动作（例如「拉取模型列表」「手动填写模型」），与选中状态无关。 */
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    SettingsChoiceDialog(
        title = title,
        options = options.map { (value, label) -> SettingsChoice(value = value, label = label) },
        selected = selected,
        onDismiss = onDismiss,
        onSelect = onSelect,
        emptyHint = emptyHint,
        hint = hint,
        actions = actions.map { (label, onClick) ->
            SettingsChoiceAction(label = label, onClick = onClick)
        },
    )
}

/**
 * 单值输入弹窗的短名字，实现在 `SettingsInputDialog`（见 SettingsKit.kt）：
 * 自动聚焦、光标在末尾、键盘不挡按钮、密钥可显示、可一键粘贴。
 *
 * @param paste 左下角给一个「粘贴」。密钥默认就给 —— 没有人手打 Key。
 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    secret: Boolean = false,
    /** 输入框下面的一行说明（例如 Base URL 的格式约定）。 */
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    paste: Boolean = secret,
) {
    SettingsInputDialog(
        title = title,
        initial = initial,
        placeholder = placeholder,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        secret = secret,
        hint = hint,
        keyboardType = keyboardType,
        mono = mono,
        paste = paste,
    )
}

@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    SettingsTextAction(
        text = text,
        onClick = onClick,
        primary = primary,
        destructive = destructive,
        enabled = enabled,
    )
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "••••"
    return key.take(4) + "••••" + key.takeLast(4)
}



