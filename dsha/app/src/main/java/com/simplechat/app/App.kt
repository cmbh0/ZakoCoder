package com.simplechat.app

import android.app.Application
import android.content.Context
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.responses.ResponsesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(newBase: Context) {
        // 先按语言设置包一层：数据层 / ViewModel 取词（Res）都走这条路。
        // wrapAppBase 会把**未包装**的 base 记给 LocaleHelper —— 换语言后 Res 要从它现算。
        super.attachBaseContext(LocaleHelper.wrapAppBase(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        Res.init(this)
        // 日志最早初始化：它要装崩溃钩子，晚一步就可能漏掉启动期的崩溃
        runCatching { com.simplechat.app.util.AppLog.init(this) }
        container = AppContainer(this)

        /*
         * 工作区是 Agent / 终端 / 技能 / MCP 的共同地基，必须在任何界面之前绑好。
         *
         * 放在 Application 而不是 ViewModel：终端页和技能页都可能先于聊天页被打开
         * （比如从设置直接进），那时如果还没人绑，`Workspace.requireRoot()` 会抛。
         */
        runCatching { com.simplechat.app.agent.Workspace.init(this, filesDir) }
        // 云端环境：会话 -> 环境的绑定要在聊天页之前可读（输入框的 chip 直接用）
        runCatching { com.simplechat.app.agent.CloudEnvs.init(this) }
        // 屏幕工具与悬浮提示都需要一个长期存活的 Context（它们活在 Activity 之外）
        runCatching { com.simplechat.app.agent.ScreenAutomation.init(this) }
        /*
         * 定时任务：init 里会读盘并**重排全部闹钟**。
         *
         * 必须在这里做，不能等设置页被打开 —— 闹钟是系统的，App 被杀死后
         * 依然要响；而"上次重排是什么时候"没人记得，每次进程起来重排一遍最保险。
         */
        runCatching { com.simplechat.app.data.ScheduleStore.init(this) }

        // 把 v1.7 之前留在 DataStore 里的**明文** API Key 就地加密。
        // 一次性动作，放在进程启动时做，不与任何界面竞争。
        CoroutineScope(Dispatchers.IO).launch {
            /*
             * 先把「操作屏幕」的通道偏好灌进 ScreenAutomation，再让它 probe。
             *
             * 顺序有讲究：probe 结束时会 refresh，那时如果偏好还没读出来，
             * 界面会先显示"自动"下的结果，用户改过的选择要等到下次进设置页
             * 才回来 —— 看起来就是"设置没保存"。
             */
            runCatching {
                val stored = container.settingsStore.flow.first()
                com.simplechat.app.agent.ScreenAutomation.setPreference(stored.screenChannelId)
                com.simplechat.app.agent.ScreenAutomation.setPipeline(stored.screenPipelineId)
            }
            /*
             * 语音语言：TTS / STT 各自的初始化参数，进程内只读一次。
             *
             * 放在这里而不是各控制器里订阅：控制器可能被重建（换会话、切主题），
             * 每次重建都读一遍 DataStore 没必要，也会让"设置刚改完"出现一秒延迟。
             */
            runCatching {
                val stored = container.settingsStore.flow.first()
                com.simplechat.app.data.SpeechLang.tts = stored.ttsLanguage
                com.simplechat.app.data.SpeechLang.stt = stored.sttLanguage
            }
            container.settingsStore.migrateLegacyApiKey()
            // MCP 配置是文件，技能是目录 —— 都只需要读一次，之后由各自的 Store 维护
            runCatching { com.simplechat.app.mcp.McpStore.load() }
            // 内置技能先播种（首次装好就把「内置浏览器」写入并启用），再扫描目录
            runCatching { com.simplechat.app.skill.BuiltinSkills.ensureSeeded() }
            runCatching { com.simplechat.app.skill.SkillStore.refresh() }
        }
    }
}

/**
 * 手写依赖容器。不引入 Hilt / Koin —— 依赖少到这程度，框架是负担。
 * 各 Store 与 Repository 随里程碑逐步填充。
 */
class AppContainer(private val app: Application) {

    val json: Json by lazy { ChatApi.defaultJson() }

    val httpClient: OkHttpClient by lazy { ChatApi.defaultClient() }

    private val sseClient: SseClient by lazy { SseClient(httpClient, json) }

    private val anthropicApi: AnthropicApi by lazy { AnthropicApi(httpClient, json) }

    private val responsesApi: ResponsesApi by lazy { ResponsesApi(httpClient, json) }

    val chatApi: ChatApi by lazy { ChatApi(httpClient, sseClient, anthropicApi, responsesApi, json) }

    val settingsStore: SettingsStore by lazy { SettingsStore(app) }

    val database: AppDatabase by lazy { AppDatabase.build(app) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database, app) }
}
