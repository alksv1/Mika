package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.voice.FailedSession
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.ProviderSpeech
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.ondevice.InstalledVoicePack
import com.wangzan101.mika.voice.ondevice.OnDeviceBackends
import com.wangzan101.mika.voice.ondevice.OnDeviceEngines
import com.wangzan101.mika.voice.ondevice.OnDeviceLanguage
import com.wangzan101.mika.voice.ondevice.OnDeviceSttException
import com.wangzan101.mika.voice.ondevice.OnDeviceTimeout
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本机离线识别(已安装的语音包)。和整段上传识别一样:按着录音,松手后整段转写,所以它能当备援链的链头,
 * 也能接在云端模型后面。只认 [com.wangzan101.mika.voice.ondevice.OnDeviceSttBackend] /
 * [com.wangzan101.mika.voice.ondevice.OnDeviceSttEngine] 两个接口,不知道具体是哪个推理库。
 *
 * 失败都换成 [VoiceModelError]:包不在是 [VoiceModelError.PackMissing],其余是 [VoiceModelError.OnDeviceFailed],
 * 都会换链上的下一个,也都不是网络错误。[budgetMs] 只为测试可换,默认按录音长短([OnDeviceTimeout.budgetMs])。
 */
class OnDeviceSttAdapter(
    private val catalog: () -> VoicePackCatalog,
    private val store: VoicePackStore,
    private val engines: OnDeviceEngines,
    private val backends: OnDeviceBackends,
    private val budgetMs: (samples: Int) -> Long = { OnDeviceTimeout.budgetMs(it) },
) : SttAdapter {
    override val protocol = SttProtocol.OnDevice

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        val packId = packId(model)
        val entry = entry(packId)
        if (backends.find(entry.backend)?.abiSupported() != true) {
            throw VoiceModelError.Unsupported(VoiceCapability.STT, protocol.name)
        }
        installed(entry)
        return SpeechEngineSpec.OnDevice(packId)
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        // 通话的声音从外面送进来;第一版通话不用本机语音包(出字太慢)
        if (hooks.recorder != null) return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.OnDeviceUnsupported))
        prewarm(model)
        val transcribe = utterance(context, model, languageTag(context, language))
        // 转写的上限由 utterance 自己按录音长短算,不套整段上传那 30 秒(到点会被当成「连不上」)
        return categorizedSession { ProviderSpeech(PcmRecorder(), transcribe, timeoutMs = Long.MAX_VALUE) }
    }

    /** 按下说话时就在后台开始加载模型,和录音同时进行;出错留到转写时再报。 */
    override fun prewarm(model: ResolvedVoiceModel) {
        val pack = runCatching { installed(entry(packId(model))) }.getOrNull() ?: return
        engines.prewarm(pack)
    }

    override fun utterance(context: Context, model: ResolvedVoiceModel, language: String?): UtteranceTranscribe {
        val packId = packId(model)
        return { wav ->
            // 每次现查:文件后来才下好也能直接用,删掉了就报「需要下载」
            val entry = entry(packId)
            val pack = installed(entry)
            try {
                val pcm = Wav.decode(wav)
                val code = OnDeviceLanguage.pick(language, entry.languages)
                // 等模型加载(有自己的上限)不算在识别预算里
                val engine = engines.engine(pack)
                val budget = budgetMs(pcm.size)
                // 原生解码在 OnDeviceEngines 里单独跑,这里到点就能返回,不等它
                withTimeoutOrNull(budget) { engine.transcribe(pcm, code) }
                    // 记成本机失败,不是网络超时:慢机器上不该显示「连不上」
                    ?: throw VoiceModelError.OnDeviceFailed("On-device recognition took longer than $budget ms")
            } catch (e: OnDeviceSttException) {
                throw mapped(e, entry)
            } catch (e: VoiceModelError) {
                throw e
            } catch (e: CancellationException) {
                // 调用方自己被取消就照常往外传;调用方还在等,却拿到取消(比如加载被取消),算这一次本机失败,换下一个
                currentCoroutineContext().ensureActive()
                throw VoiceModelError.OnDeviceFailed("On-device recognition was cancelled", e)
            } catch (e: Exception) {
                // 音频解不开、选语言出错等意外:也算本机失败,一定能换下一个(否则会被当成协议错误停在这里)
                throw VoiceModelError.OnDeviceFailed("On-device recognition failed: ${e.message}", e)
            }
        }
    }

    /**
     * 文件不在([OnDeviceSttException.Kind.Missing])只有包这时真的没装才说「需要下载」;
     * 包还装着(模型被释放、换版本时正在识别)就是这一次本机失败,换下一个,不让用户去重新下载。
     */
    private fun mapped(error: OnDeviceSttException, entry: VoicePackEntry): VoiceModelError =
        if (error.kind == OnDeviceSttException.Kind.Missing && store.installedAny(entry) != null) {
            VoiceModelError.OnDeviceFailed(error.message ?: "On-device model was released", error)
        } else {
            VoiceModelError.from(error, entry.id)
        }

    private fun packId(model: ResolvedVoiceModel): String =
        model.sttBinding(protocol).endpoint.ifBlank { model.model.id }

    private fun entry(packId: String): VoicePackEntry {
        val packs = try {
            catalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 随 APK 的清单读不出来是打包问题,算本机不可用,不当成网络错误
            throw VoiceModelError.OnDeviceFailed("Voice pack catalog is unavailable", e)
        }
        return packs.entry(packId) ?: throw VoiceModelError.PackMissing(packId)
    }

    private fun installed(entry: VoicePackEntry): InstalledVoicePack =
        store.installedAny(entry) ?: throw VoiceModelError.PackMissing(entry.id)
}
