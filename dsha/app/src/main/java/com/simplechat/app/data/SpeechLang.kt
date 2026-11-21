package com.simplechat.app.data

import com.simplechat.app.R
import java.util.Locale

/**
 * 朗读 / 语音识别用哪种语言。
 *
 * 为什么要一个进程级单例：两个控制器（[SpeechOutputController]、[VoiceInputController]）
 * 都活在 Compose 里，而"语言"是**系统引擎的初始化参数** —— 每次改完都要重新
 * 挑音色、重建识别意图。让它们各自去读 DataStore 会多出两条订阅；这里存一份
 * 内存值，设置页写、App 启动时灌一次，读取是纯内存操作。
 *
 * `auto` 保留旧行为：中文界面用中文，其余跟系统。
 */
object SpeechLang {

    const val AUTO = "auto"

    @Volatile
    var tts: String = AUTO

    @Volatile
    var stt: String = AUTO

    /** 支持的语言码 → 名称。顺序即设置页顺序。 */
    val options: List<Pair<String, Int>> = listOf(
        AUTO to R.string.speech_lang_auto,
        "zh" to R.string.speech_lang_zh,
        "en" to R.string.speech_lang_en,
        "ja" to R.string.speech_lang_ja,
    )

    fun localeOf(code: String): Locale = when (code) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.US
        "ja" -> Locale.JAPAN
        // auto：界面是中文就用中文，否则交给系统
        else -> if (Res.bool(R.bool.ui_is_cjk)) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
    }

    fun ttsLocale(): Locale = localeOf(tts)

    /** 识别器要的是 BCP-47 标签，不是 Locale 对象。 */
    fun sttTag(): String = localeOf(stt).toLanguageTag()

    fun labelOf(code: String): String =
        Res.get(options.firstOrNull { it.first == code }?.second ?: R.string.speech_lang_auto)
}
