package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import java.util.Locale

/** 语音识别语言标签和离线模型语言代码之间的对应,以及按界面语言推荐语音包。 */
object OnDeviceLanguage {

    /** 一些模型(Whisper)还用旧代码:希伯来语 iw、爪哇语 jw。 */
    private val modelAliases = mapOf("he" to "iw", "jv" to "jw")

    /** 语言标签里的语言部分(去掉文字体系、地区),旧代码换成现行代码:`zh-Hans-CN` → `zh`,`in-ID` → `id`。 */
    fun languagePart(speechTag: String?): String? {
        if (speechTag.isNullOrBlank()) return null
        return Locale.forLanguageTag(speechTag).language.takeIf { it.isNotEmpty() }?.let(AppLanguage::modernCode)
    }

    /** 给模型用的语言代码;模型不支持这种语言或没给语言时返回 null,表示让模型自己判断。 */
    fun pick(speechTag: String?, languages: List<String>): String? {
        val code = languagePart(speechTag) ?: return null
        if (code in languages) return code
        return modelAliases[code]?.takeIf { it in languages }
    }

    /** 按界面语言推荐的语音包;跟随系统时按 [system] 换算成支持的某种界面语言(不支持的落英文)。 */
    fun recommended(catalog: VoicePackCatalog, app: AppLanguage, system: Locale = Locale.getDefault()): VoicePackEntry? {
        val tag = (if (app == AppLanguage.System) appLanguageFor(system) else app).tag ?: return null
        return catalog.packs.firstOrNull { tag in it.recommendedFor }
    }

    /**
     * [system] 对应的界面语言,和 [AppLanguage.resolved] 跟随系统时的结果一致,但不需要 Context、不依赖 ICU:
     * 中文没写文字体系时,台湾、香港、澳门按繁体,其余按简体。
     */
    internal fun appLanguageFor(system: Locale): AppLanguage =
        AppLanguage.fromTag(system.toLanguageTag()) { tag ->
            if (Locale.forLanguageTag(tag).country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
        }
}
