package com.wangzan101.mika.voice.ondevice

import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.wangzan101.mika.voice.Wav
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * sherpa-onnx 离线识别器(`OfflineRecognizer`,随 APK 的 AAR,只带 arm64-v8a)。
 * 清单里的 `family` 决定配哪种模型配置,文件按 `role` 填进配置;再接一种 sherpa-onnx 已支持的模型,
 * 在 [FAMILIES] 和 [modelConfig] 里各加一个分支。
 */
class SherpaOnnxBackend : OnDeviceSttBackend {
    override val id = ID

    override fun supports(family: String) = family in FAMILIES

    override fun abiSupported() = ABI in Build.SUPPORTED_ABIS

    override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine {
        if (!abiSupported()) throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "sherpa-onnx needs $ABI")
        val model = modelConfig(pack, options)
        pack.entry.files.map { File(pack.dir, it.name) }.firstOrNull { !it.isFile }?.let {
            throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${pack.entry.id} is missing ${it.name}")
        }
        val config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = Wav.RATE, featureDim = 80), modelConfig = model)
        // 原生侧拿不到模型时只返回空指针(require 抛 IllegalArgumentException),交给 OnDeviceEngines 记成文件损坏
        return Engine(OfflineRecognizer(assetManager = null, config = config), config, pack.entry.family)
    }

    /** 一个加载好的识别器。解码和释放互斥:不会在解码途中释放原生对象。 */
    private class Engine(
        private val recognizer: OfflineRecognizer,
        private val config: OfflineRecognizerConfig,
        private val family: String,
    ) : OnDeviceSttEngine {
        private val lock = Any()
        private var closed = false
        private var senseVoiceLanguage = config.modelConfig.senseVoice.language

        override suspend fun transcribe(pcm: ShortArray, language: String?): String =
            withContext(Dispatchers.Default) { synchronized(lock) { decode(pcm, language.orEmpty()) } }

        private fun decode(pcm: ShortArray, language: String): String {
            if (closed) throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "recognizer released")
            // SenseVoice 的语言只在识别器配置里(解码时读),换语言要改配置;空串是自动
            if (family == SENSE_VOICE && language != senseVoiceLanguage) {
                recognizer.setConfig(
                    config.copy(modelConfig = config.modelConfig.copy(senseVoice = config.modelConfig.senseVoice.copy(language = language))),
                )
                senseVoiceLanguage = language
            }
            val stream = recognizer.createStream()
            try {
                if (family == WHISPER) {
                    // Whisper 每段单独指定语言,空串是自动;默认上限每秒音频 6 个 token,语速快时会截断,放宽到 25
                    stream.setOption("language", language)
                    stream.setOption("max_tokens_per_second", "25")
                }
                stream.acceptWaveform(toFloat(pcm), Wav.RATE)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                recognizer.release()
            }
        }
    }

    companion object {
        const val ID = "sherpa-onnx"
        private const val ABI = "arm64-v8a"
        private const val SENSE_VOICE = "sense_voice"
        private const val WHISPER = "whisper"
        private const val DOLPHIN = "dolphin"
        private val FAMILIES = setOf(SENSE_VOICE, WHISPER, DOLPHIN)

        /** 按 family 把包里的文件(按 role)填进模型配置;语言先留空(自动),每次识别再指定。 */
        internal fun modelConfig(pack: InstalledVoicePack, options: EngineOptions): OfflineModelConfig {
            fun path(role: String) = pack.file(role).path
            val m = OfflineModelConfig(tokens = path("tokens"), numThreads = options.threads)
            when (pack.entry.family) {
                SENSE_VOICE -> m.senseVoice = OfflineSenseVoiceModelConfig(model = path("model"), language = "")
                WHISPER -> m.whisper = OfflineWhisperModelConfig(encoder = path("encoder"), decoder = path("decoder"), language = "", task = "transcribe")
                DOLPHIN -> m.dolphin = OfflineDolphinModelConfig(model = path("model"))
                else -> throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "sherpa-onnx family ${pack.entry.family}")
            }
            return m
        }

        internal fun toFloat(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }
    }
}
