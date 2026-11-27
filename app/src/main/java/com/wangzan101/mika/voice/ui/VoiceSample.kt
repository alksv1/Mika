package com.wangzan101.mika.voice.ui

import android.content.Context
import android.content.res.Configuration
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import java.util.Locale

/** 界面有这种语言的翻译:试听句子能用音色自己的语言说。 */
internal fun hasUiTranslation(locale: Locale): Boolean {
    val code = AppLanguage.modernCode(locale.language)
    return code.isNotEmpty() && AppLanguage.entries.any { it.language == code }
}

/**
 * 试听/测试用的一句话,来自字符串资源 voice_try_sample。音色带语言、且界面有这种语言的翻译时用那种语言,
 * 否则(没给语言、界面没有这种语言)用当前界面语言,不为任何语言在代码里写死句子。
 */
internal fun voiceSample(context: Context, voiceLocale: Locale? = null): String {
    val locale = voiceLocale?.takeIf(::hasUiTranslation) ?: return tr(R.string.voice_try_sample)
    val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
    return context.createConfigurationContext(config).getString(R.string.voice_try_sample)
}
