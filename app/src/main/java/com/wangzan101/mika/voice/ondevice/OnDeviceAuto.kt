package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import java.util.Locale

/**
 * 「自动」在手机自带识别用不了时挑哪个语音包:推荐给这次识别语言的那个装了就用它,
 * 否则用清单里第一个装了的(模型不支持这种语言时由它自己判断),都没装就是 null。
 * 只在手机自带识别不能用时才问这里(见 [com.wangzan101.mika.voice.SpeechResolver])。
 */
object OnDeviceAuto {

    /**
     * [speechTag] 是识别语言(BCP-47),按它换算成界面语言去找推荐;没给时按 [app](跟随系统时按系统语言)。
     * [installed] 判断一个包能不能直接用。
     */
    fun pick(
        speechTag: String?,
        app: AppLanguage,
        catalog: VoicePackCatalog,
        installed: (VoicePackEntry) -> Boolean,
    ): VoicePackEntry? {
        val language = speechTag?.takeIf { it.isNotBlank() }?.let { OnDeviceLanguage.appLanguageFor(Locale.forLanguageTag(it)) } ?: app
        val recommended = OnDeviceLanguage.recommended(catalog, language)
        if (recommended != null && installed(recommended)) return recommended
        return catalog.packs.firstOrNull(installed)
    }

    /**
     * App 里「装了」的意思:文件在(旧版本也算,照样能用)、这台设备跑得了它的引擎、语音目录里登记了它。
     * 只看目录和文件大小,不算校验和,界面每次取卡片标签都会调。没有语音包环境(测试)或清单读不了时为 null。
     */
    fun installedPack(environment: VoiceModelEnvironment, speechTag: String?): String? {
        val store = environment.voicePacks ?: return null
        val backends = environment.voicePackBackends ?: return null
        val catalog = try {
            environment.voicePackCatalog?.invoke()
        } catch (_: Exception) {
            null
        } ?: return null
        val registered = environment.catalog.state.value.models
            .filter { it.serviceId == OnDeviceCatalog.SERVICE_ID }
            .mapTo(HashSet()) { it.id }
        return pick(speechTag, AppLanguage.System, catalog) { entry ->
            entry.id in registered &&
                backends.find(entry.backend)?.abiSupported() == true &&
                store.installedAny(entry) != null
        }?.id
    }
}
