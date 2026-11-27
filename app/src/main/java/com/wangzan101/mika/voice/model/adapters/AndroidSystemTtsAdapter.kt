package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.AndroidSystemTtsEngine
import com.wangzan101.mika.voice.SystemTtsEngine
import com.wangzan101.mika.voice.SystemTtsModel
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import java.io.File

/**
 * 内置 Android 系统 TTS:一次合成一个完整 WAV,非流式,能力声明不交给云端或第二套播放路径。
 * 用哪个引擎看绑定里的 [com.wangzan101.mika.voice.model.TtsBinding.engine](空 = 手机默认引擎)。
 */
class AndroidSystemTtsAdapter(
    private val engineFactory: (context: Context, enginePackage: String?) -> SystemTtsEngine =
        { context, enginePackage -> AndroidSystemTtsEngine(context, enginePackage) },
) : TtsAdapter {
    override val protocol = TtsProtocol.AndroidSystem

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
        val app = context.applicationContext
        val engine = binding.engine.trim().ifEmpty { null }
        return SystemTtsModel(
            context = app,
            engineFactory = { engineFactory(it, engine) },
            resolvedLanguage = { AppLanguage.resolved(app) },
            outputDir = { File(app.cacheDir, "voice") },
            maxChars = capabilities.maxChars,
        )
    }
}
