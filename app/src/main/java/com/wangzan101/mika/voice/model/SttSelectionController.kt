package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.ProviderSpeech
import com.wangzan101.mika.voice.model.adapters.SttHooks
import com.wangzan101.mika.voice.model.adapters.UtteranceTranscribe
import com.wangzan101.mika.voice.model.adapters.categorizedSession
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.core.model.ModelError
import java.net.SocketTimeoutException

/** Reads and writes only STT selection; resolution and protocol work happen when recognition is actually used. */
class SttSelectionController(private val environment: VoiceModelEnvironment) {
    internal val catalog: VoiceCatalogStore get() = environment.catalog
    val migrated: Boolean get() = environment.catalog.state.value.legacyMigrationComplete

    fun current(): SttSelection = environment.catalog.state.value.selections.stt

    fun select(choice: SpeechChoice) {
        val selection = when (choice) {
            SpeechChoice.Auto -> SttSelection.Auto
            SpeechChoice.Off -> SttSelection.Off
            SpeechChoice.System -> SttSelection.Model(SYSTEM_STT_REF)
            else -> throw IllegalArgumentException("Select a catalog model ref for this STT choice")
        }
        environment.catalog.setSttSelection(selection)
    }

    fun select(ref: VoiceModelRef) {
        environment.catalog.setSttSelection(SttSelection.Model(ref))
    }

    fun requireModel(ref: VoiceModelRef): ResolvedVoiceModel {
        if (ref == SYSTEM_STT_REF) {
            return environment.accountResolver.resolve(SYSTEM_STT_SERVICE, SYSTEM_STT_MODEL)
        }
        val snapshot = environment.catalog.state.value
        if (snapshot.error != null) throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        if (ref.capability != VoiceCapability.STT) throw VoiceModelError.Unsupported(VoiceCapability.STT)
        val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
            ?: throw VoiceModelError.IncompleteConfig("Selected STT model is not registered")
        if (model.stt == null) throw VoiceModelError.Unsupported(VoiceCapability.STT)
        val service = snapshot.services.firstOrNull { it.id == model.serviceId }
            ?: throw VoiceModelError.IncompleteConfig("Selected STT service is not registered")
        return environment.accountResolver.resolve(service, model)
    }

    fun spec(ref: VoiceModelRef): SpeechEngineSpec = spec(requireModel(ref))

    internal fun spec(model: ResolvedVoiceModel): SpeechEngineSpec = environment.registry.stt(model).spec(model)

    /**
     * 开一次识别。[backups] 是这一次的备援链(运行时由调用方传 [backupsFor],试听/测试不传,只试选中的那个)。
     * 链头是整段上传识别、且有备援或借用的对话账号有多把密钥时,松手后的这段录音按链和密钥池依次转写
     * ([transcribeChain]);其余照旧交给协议自己的会话。
     */
    fun start(
        context: Context,
        ref: VoiceModelRef,
        language: String?,
        hooks: SttHooks,
        backups: List<VoiceModelRef> = emptyList(),
    ): SpeechSession {
        val model = requireModel(ref)
        val adapter = environment.registry.stt(model)
        val chained = VoiceFallbacks.eligible(model.model, VoiceCapability.STT) &&
            (backups.isNotEmpty() || environment.accountResolver.pooled(model))
        if (!chained) return adapter.start(context, model, spokenLanguage(model, language), hooks)
        adapter.spec(model)
        val candidates = listOf(ref) + backups.filter { it != ref }.take(VoiceFallbacks.MAX)
        val transcribe = transcribeChain(context, candidates, language, first = model)
        return categorizedSession {
            ProviderSpeech(
                hooks.recorder ?: PcmRecorder(),
                transcribe,
                timeoutMs = ProviderSpeech.TRANSCRIBE_TIMEOUT_MS * candidates.size,
            )
        }
    }

    /**
     * 同一段录音按 [candidates] 依次转写:每个模型走自己账号的密钥池([VoiceAccountResolver.withPooledKey]),
     * 失败了由 [VoiceFailover] 决定换不换下一个。每个模型最多等 [ProviderSpeech.TRANSCRIBE_TIMEOUT_MS],
     * 超时当这个模型连不上,换下一个。只收整段上传识别的模型,别的跳过。
     */
    internal fun transcribeChain(
        context: Context,
        candidates: List<VoiceModelRef>,
        language: String?,
        first: ResolvedVoiceModel? = null,
        attemptTimeoutMs: Long = ProviderSpeech.TRANSCRIBE_TIMEOUT_MS,
    ): UtteranceTranscribe = { wav ->
        VoiceFailover.run(
            candidates,
            resolve = { candidate ->
                val resolved = if (candidate == candidates.first() && first != null) first else requireModel(candidate)
                if (!VoiceFallbacks.eligible(resolved.model, VoiceCapability.STT)) {
                    throw VoiceModelError.Unsupported(VoiceCapability.STT, resolved.model.stt?.protocol?.name)
                }
                resolved
            },
        ) { resolved, _ ->
            environment.accountResolver.withPooledKey(resolved) { keyed ->
                val transcribe = environment.registry.stt(keyed).utterance(context, keyed, spokenLanguage(keyed, language))
                    ?: throw VoiceModelError.Unsupported(VoiceCapability.STT, keyed.model.stt?.protocol?.name)
                withTimeoutOrNull(attemptTimeoutMs) { transcribe(wav) }
                    // 和网络超时同一种错误:界面照「连不上」处理,换不换下一个也按网络错误判断
                    ?: throw ModelError.Network(SocketTimeoutException("transcription timed out"))
            }
        }
    }

    /** 这次识别交给服务的语言。 */
    private fun spokenLanguage(model: ResolvedVoiceModel, language: String?): String? {
        val binding = model.model.stt ?: throw VoiceModelError.Unsupported(VoiceCapability.STT)
        return when (binding.protocol) {
            // 语言是这个服务自己的设置(接入时在表单里选),不跟界面语言走;空 = 自动,往下传空串就是不指定
            in SERVICE_LANGUAGE_PROTOCOLS -> binding.language
            // 还没有选语言的地方:设过的(旧设置迁移来的)用设的,没设的跟调用方给的语言
            in EXPLICIT_ASR_LANGUAGE_PROTOCOLS -> binding.language.ifBlank { null } ?: language
            else -> language
        }
    }

    /**
     * [ref] 的备援链:它是语音输入选中的那个模型时是 [VoiceSelections.sttFallbacks],否则(比如 AI 电话自己选的听写模型)没有。
     * 链头不是整段上传识别的,也没有。
     */
    fun backupsFor(ref: VoiceModelRef): List<VoiceModelRef> {
        val snapshot = environment.catalog.state.value
        val selections = snapshot.selections
        if ((selections.stt as? SttSelection.Model)?.ref != ref) return emptyList()
        val head = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId } ?: return emptyList()
        if (!VoiceFallbacks.eligible(head, VoiceCapability.STT)) return emptyList()
        return selections.sttFallbacks
    }

    /** Only batch protocols can transcribe an existing file; system and streaming selections return null. */
    fun batchModel(): ResolvedVoiceModel? {
        if (!migrated) return null
        val ref = (current() as? SttSelection.Model)?.ref ?: return null
        val model = requireModel(ref)
        return model.takeIf { it.model.stt?.protocol in BATCH_PROTOCOLS }
    }

    companion object {
        val SYSTEM_STT_REF = VoiceModelRef("android-system", "system-stt", VoiceCapability.STT)
        val SYSTEM_STT_SERVICE = VoiceService("android-system", "Android", VoiceAccount.None)
        val SYSTEM_STT_MODEL = VoiceModel(
            serviceId = "android-system",
            id = "system-stt",
            displayName = "Android system speech recognition",
            stt = SttBinding(SttProtocol.AndroidSystem),
        )

        private val BATCH_PROTOCOLS = setOf(
            SttProtocol.AudioTranscriptions,
            SttProtocol.ChatInputAudio,
            SttProtocol.GeminiInline,
        )
        /** The service's own language setting decides, blank meaning automatic; the app language is never used. */
        private val SERVICE_LANGUAGE_PROTOCOLS = setOf(SttProtocol.FunAsrStreaming)

        /** A language set on the binding wins; blank follows the caller's language. */
        private val EXPLICIT_ASR_LANGUAGE_PROTOCOLS = setOf(SttProtocol.BailianStreaming)
    }
}
