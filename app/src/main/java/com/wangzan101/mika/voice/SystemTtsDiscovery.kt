package com.wangzan101.mika.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** 手机上登记的一个语音合成引擎。 */
data class SystemTtsEngineInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean = false,
)

/** 引擎提供的一个音色;[name] 是 [android.speech.tts.Voice.getName],存进设置的就是它。 */
data class SystemTtsVoiceInfo(
    val name: String,
    val locale: Locale? = null,
    val displayName: String = name,
)

/**
 * 列出手机上的语音合成引擎和某个引擎的音色,只用公开接口:引擎按 [TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE]
 * 查服务(清单的 `<queries>` 里登记了这个 action),音色用 [TextToSpeech.getVoices]。不认任何具体厂商的包名。
 */
object SystemTtsDiscovery {
    /** 等引擎初始化的上限;冷启动的引擎要先拉起进程。 */
    const val VOICES_TIMEOUT_MS = 5_000L

    /** 已装的引擎,默认引擎在前,其余按名字排。查不到时为空。 */
    suspend fun engines(context: Context): List<SystemTtsEngineInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
        val defaultPackage = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        } catch (e: RuntimeException) {
            null
        }
        val engines = services.mapNotNull { info ->
            val pkg = info.serviceInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            SystemTtsEngineInfo(pkg, label, isDefault = pkg == defaultPackage)
        }.distinctBy { it.packageName }
        sortEngines(engines)
    }

    internal fun sortEngines(engines: List<SystemTtsEngineInfo>): List<SystemTtsEngineInfo> =
        engines.sortedWith(compareByDescending<SystemTtsEngineInfo> { it.isDefault }.thenBy { it.label.lowercase() })

    /**
     * [enginePackage] 的音色(空 = 默认引擎);只列已经装好数据的,和 [preferred] 同语言的在前。
     * 引擎没装、初始化失败或超时都返回空表,查完一定 shutdown。
     */
    suspend fun voices(context: Context, enginePackage: String, preferred: Locale?): List<SystemTtsVoiceInfo> =
        withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Int>()
            val listener = TextToSpeech.OnInitListener { ready.complete(it) }
            val tts = try {
                if (enginePackage.isBlank()) TextToSpeech(context.applicationContext, listener)
                else TextToSpeech(context.applicationContext, listener, enginePackage)
            } catch (e: RuntimeException) {
                return@withContext emptyList()
            }
            try {
                val status = withTimeoutOrNull(VOICES_TIMEOUT_MS) { ready.await() }
                if (status != TextToSpeech.SUCCESS) return@withContext emptyList()
                val voices = try {
                    tts.voices.orEmpty().filterNot { it.notInstalled() }.map { v ->
                        val language = v.locale?.displayName?.takeIf { it.isNotBlank() }
                        SystemTtsVoiceInfo(v.name, v.locale, if (language != null) "$language · ${v.name}" else v.name)
                    }
                } catch (e: RuntimeException) {
                    emptyList()
                }
                sortVoices(voices, preferred)
            } finally {
                runCatching { tts.shutdown() }
            }
        }

    internal fun sortVoices(voices: List<SystemTtsVoiceInfo>, preferred: Locale?): List<SystemTtsVoiceInfo> =
        voices.sortedWith(
            compareByDescending<SystemTtsVoiceInfo> { preferred != null && it.locale?.language == preferred.language }
                .thenBy { it.displayName.lowercase() },
        )
}
