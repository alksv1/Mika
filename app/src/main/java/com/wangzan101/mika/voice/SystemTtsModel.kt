package com.wangzan101.mika.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * 用系统 [TextToSpeech] 合成完整 WAV 的非流式声音模型。每次合成新建一个引擎、初始化后合成到临时文件、
 * 等 [SystemTtsEngine.setOnUtteranceProgressListener] 的完成回调再读出字节,完成、出错或取消都一定 stop/shutdown。
 * 交回 [TtsResponse] 后由现有 [VoicePlayer] 播放,不另开播放路径或后台服务。初始化和合成各有上限,
 * 引擎不响应或中途死掉时按超时失败。用哪个引擎由 [engineFactory] 决定(适配器按目录里的 [com.wangzan101.mika.voice.model.TtsBinding.engine] 建)。
 */
class SystemTtsModel(
    private val context: Context,
    private val engineFactory: (Context) -> SystemTtsEngine,
    private val resolvedLanguage: () -> AppLanguage,
    private val outputDir: () -> File,
    private val maxChars: Int = TtsCapabilities().maxChars,
    private val initTimeoutMs: Long = INIT_TIMEOUT_MS,
    private val synthesisTimeoutMs: Long = SYNTHESIS_TIMEOUT_MS,
) : TtsModel {
    override val name: String = "system-tts"
    override val capabilities: TtsCapabilities = TtsCapabilities(voices = null, maxChars = maxChars, formats = setOf("wav"))

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val engine = engineFactory(context)
        return try {
            val ready = CompletableDeferred<Int>()
            engine.init { ready.complete(it) }
            // 引擎没响应(进程起不来、被杀)时不一直等
            val status = withTimeoutOrNull(initTimeoutMs) { ready.await() }
                ?: throw VoiceModelError.Protocol(
                    VoiceErrorCategory.TIMEOUT, "System text-to-speech engine did not respond",
                )
            if (status != TextToSpeech.SUCCESS) {
                throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech engine is unavailable",
                )
            }

            // 用户选的音色自带语言,用上了就不再按界面语言挑;这个引擎没有它(换过引擎、旧设置留下的别家音色名、
            // 音色数据被删)时按界面语言用引擎默认的音色
            val voiceName = request.voice?.trim().orEmpty()
            val voiceApplied = voiceName.isNotEmpty() && when (engine.setVoice(voiceName)) {
                SystemVoiceResult.Applied -> true
                SystemVoiceResult.NotOffered -> false
                SystemVoiceResult.Failed -> throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech could not use the selected voice",
                )
            }
            if (!voiceApplied) {
                val locale = resolvedLanguage().speechTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
                when (engine.setLanguage(locale)) {
                    TextToSpeech.LANG_MISSING_DATA -> throw VoiceModelError.Protocol(
                        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech language data is missing",
                    )
                    TextToSpeech.LANG_NOT_SUPPORTED -> throw VoiceModelError.Protocol(
                        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech does not support this language",
                    )
                }
            }
            engine.setSpeechRate(request.speed ?: 1f)

            val dir = outputDir()
            dir.mkdirs()
            val file = File(dir, "mika-tts-${System.nanoTime()}.wav")
            val utteranceId = "mika-tts-${System.nanoTime()}"

            try {
                val complete = CompletableDeferred<Unit>()
                engine.setOnUtteranceProgressListener(
                    onDone = { complete.complete(Unit) },
                    onError = {
                        complete.completeExceptionally(
                            VoiceModelError.Protocol(
                                VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech synthesis failed",
                            ),
                        )
                    },
                )
                if (engine.synthesizeToFile(request.text, file, utteranceId) != TextToSpeech.SUCCESS) {
                    throw VoiceModelError.Protocol(
                        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech synthesis failed",
                    )
                }
                // 合成中途引擎死掉时进度回调不会再来
                withTimeoutOrNull(synthesisTimeoutMs) { complete.await() }
                    ?: throw VoiceModelError.Protocol(
                        VoiceErrorCategory.TIMEOUT, "System text-to-speech engine stopped responding",
                    )

                val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                if (bytes.isEmpty()) {
                    throw VoiceModelError.Protocol(
                        VoiceErrorCategory.PROTOCOL_ERROR, "System text-to-speech produced no audio",
                    )
                }
                TtsResponse(bytes, "audio/wav")
            } finally {
                runCatching { file.delete() }
            }
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    companion object {
        const val INIT_TIMEOUT_MS = 10_000L
        const val SYNTHESIS_TIMEOUT_MS = 60_000L
    }
}
