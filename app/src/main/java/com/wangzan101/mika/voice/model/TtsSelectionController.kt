package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel

/**
 * Maps a catalog TTS protocol to the settings protocol family used for capability hints. Vendor protocols
 * deliver WAV/PCM only, the same output family as the device engine.
 */
internal fun TtsProtocol.settingsProtocol(): com.wangzan101.mika.settings.TtsProtocol = when (this) {
    TtsProtocol.OpenAiSpeech -> com.wangzan101.mika.settings.TtsProtocol.OpenAiSpeech
    TtsProtocol.GeminiTts -> com.wangzan101.mika.settings.TtsProtocol.GeminiTts
    else -> com.wangzan101.mika.settings.TtsProtocol.AndroidSystem
}

/** Reads and writes only TTS selection; resolution and protocol work happen when speech is actually used. */
class TtsSelectionController(private val environment: VoiceModelEnvironment) {
    internal val catalog: VoiceCatalogStore get() = environment.catalog
    val migrated: Boolean get() = environment.catalog.state.value.legacyMigrationComplete

    fun current(): VoiceModelRef? = environment.catalog.state.value.selections.tts

    fun select(ref: VoiceModelRef?) {
        environment.catalog.setTtsSelection(ref)
    }

    /**
     * 全新安装的默认选择:目录还没迁移、且没有朗读选择时,落在内置系统 TTS 上。
     * 迁移产生的选择(含云端)从不被改写;迁移后的目录交给迁移结果。
     */
    internal fun ensureDefault() {
        val snapshot = environment.catalog.state.value
        if (!snapshot.legacyMigrationComplete && snapshot.selections.tts == null) {
            environment.catalog.setTtsSelection(SYSTEM_TTS_REF)
        }
    }

    /** Package of the device engine the built-in system TTS uses; empty is the device default. */
    fun systemEngine(): String = systemModel()?.tts?.engine.orEmpty()

    /**
     * Points the built-in system TTS at another device engine (empty = device default). Returns whether the engine
     * changed; a failed catalog write throws and leaves the old engine. Callers reset the picked voice only after this
     * returns true, because voice names belong to one engine.
     */
    fun setSystemEngine(engine: String): Boolean {
        val model = systemModel() ?: throw VoiceModelError.IncompleteConfig("System TTS is not registered")
        val binding = model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        val next = engine.trim()
        if (binding.engine == next) return false
        environment.catalog.upsertModel(model.copy(tts = binding.copy(engine = next)))
        return true
    }

    private fun systemModel(): VoiceModel? = environment.catalog.state.value.models.firstOrNull {
        it.serviceId == SYSTEM_TTS_REF.serviceId && it.id == SYSTEM_TTS_REF.modelId
    }

    fun requireModel(ref: VoiceModelRef): ResolvedVoiceModel {
        val snapshot = environment.catalog.state.value
        if (snapshot.error != null) throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        if (ref.capability != VoiceCapability.TTS) throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
            ?: throw VoiceModelError.IncompleteConfig("Selected TTS model is not registered")
        if (model.tts == null) throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        val service = snapshot.services.firstOrNull { it.id == model.serviceId }
            ?: throw VoiceModelError.IncompleteConfig("Selected TTS service is not registered")
        return environment.accountResolver.resolve(service, model)
    }

    /** 只建 [ref] 这一个模型(试听、测试用它:试的是哪个就是哪个,不悄悄换别的)。 */
    fun createTts(context: Context, ref: VoiceModelRef, capabilities: TtsCapabilities): TtsModel {
        val model = requireModel(ref)
        return environment.registry.tts(model).create(context, model, capabilities)
    }

    /**
     * 真正朗读时用:[backups](一般是 [backupsFor])不为空、或借用的对话账号有多把密钥时,包成 [FallbackTtsModel]
     * 按备援链和密钥池依次试;否则和 [createTts] 一样。链头没配好照常抛。
     */
    fun createSpeaker(
        context: Context,
        ref: VoiceModelRef,
        capabilities: TtsCapabilities,
        backups: List<VoiceModelRef> = backupsFor(ref),
    ): TtsModel {
        val model = requireModel(ref)
        val chain = listOf(ref) + backups.filter { it != ref }.take(VoiceFallbacks.MAX)
        val create = { resolved: ResolvedVoiceModel -> environment.registry.tts(resolved).create(context, resolved, capabilities) }
        if (chain.size == 1 && !environment.accountResolver.pooled(model)) return create(model)
        return FallbackTtsModel.of(chain, model, ::requireModel, environment.accountResolver, create)
    }

    /** [ref] 的备援链:它是朗读选中的那个模型时是 [VoiceSelections.ttsFallbacks],否则(比如 AI 电话自己选的朗读模型)没有。 */
    fun backupsFor(ref: VoiceModelRef): List<VoiceModelRef> {
        val selections = environment.catalog.state.value.selections
        return if (selections.tts == ref) selections.ttsFallbacks else emptyList()
    }

    companion object {
        val SYSTEM_TTS_REF = VoiceModelRef("android-system", "system-tts", VoiceCapability.TTS)

        /** 和内置系统识别共用同一个 android-system 服务,不另建服务。 */
        val SYSTEM_TTS_SERVICE = SttSelectionController.SYSTEM_STT_SERVICE

        val SYSTEM_TTS_MODEL = VoiceModel(
            serviceId = "android-system",
            id = "system-tts",
            displayName = "Android system speech synthesis",
            tts = TtsBinding(TtsProtocol.AndroidSystem),
        )
    }
}
