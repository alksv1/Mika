package com.wangzan101.mika.voice.model

import com.wangzan101.mika.voice.model.adapters.AndroidSystemSttAdapter
import com.wangzan101.mika.voice.model.adapters.AndroidSystemTtsAdapter
import com.wangzan101.mika.voice.model.adapters.BailianSttAdapter
import com.wangzan101.mika.voice.model.adapters.DoubaoSttAdapter
import com.wangzan101.mika.voice.model.adapters.FunAsrSttAdapter
import com.wangzan101.mika.voice.model.adapters.GeminiTtsAdapter
import com.wangzan101.mika.voice.model.adapters.OpenAiRealtimeAdapter
import com.wangzan101.mika.voice.model.adapters.OpenAiSpeechTtsAdapter
import com.wangzan101.mika.voice.model.adapters.ProviderSttAdapter
import com.wangzan101.mika.voice.model.adapters.RealtimeAdapter
import com.wangzan101.mika.voice.model.adapters.SttAdapter
import com.wangzan101.mika.voice.model.adapters.TtsAdapter
import com.wangzan101.mika.voice.model.adapters.VendorSttAdapter
import com.wangzan101.mika.voice.model.adapters.VendorTtsAdapter
import com.wangzan101.mika.voice.model.adapters.VolcDuplexRealtimeAdapter

class VoiceAdapterRegistry(
    sttAdapters: List<SttAdapter> = defaultSttAdapters(),
    ttsAdapters: List<TtsAdapter> = listOf(
        OpenAiSpeechTtsAdapter(),
        GeminiTtsAdapter(),
        AndroidSystemTtsAdapter(),
    ) + VendorTtsAdapter.PROTOCOLS.map(::VendorTtsAdapter),
    realtimeAdapters: List<RealtimeAdapter> = listOf(
        VolcDuplexRealtimeAdapter(),
        OpenAiRealtimeAdapter(),
    ),
) {
    private val stt = sttAdapters.associateBy { it.protocol }
    private val tts = ttsAdapters.associateBy { it.protocol }
    private val realtime = realtimeAdapters.associateBy { it.protocol }

    fun stt(model: ResolvedVoiceModel): SttAdapter {
        val binding = model.model.stt ?: throw VoiceModelError.Unsupported(VoiceCapability.STT)
        return stt[binding.protocol] ?: throw VoiceModelError.Unsupported(VoiceCapability.STT, binding.protocol.name)
    }

    fun tts(model: ResolvedVoiceModel): TtsAdapter {
        val binding = model.model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        return tts[binding.protocol] ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
    }

    companion object {
        /**
         * 不依赖设备的识别协议。本机语音包([com.wangzan101.mika.voice.model.adapters.OnDeviceSttAdapter])
         * 要用 App 的文件目录和共用的模型,由 [VoiceModelEnvironment.get] 另外加上。
         */
        fun defaultSttAdapters(): List<SttAdapter> = listOf(
            AndroidSystemSttAdapter(),
            ProviderSttAdapter(SttProtocol.AudioTranscriptions),
            ProviderSttAdapter(SttProtocol.ChatInputAudio),
            ProviderSttAdapter(SttProtocol.GeminiInline),
            DoubaoSttAdapter(),
            FunAsrSttAdapter(),
            BailianSttAdapter(),
        ) + VendorSttAdapter.PROTOCOLS.map(::VendorSttAdapter)
    }

    fun realtime(model: ResolvedVoiceModel): RealtimeAdapter {
        val binding = model.model.realtime ?: throw VoiceModelError.Unsupported(VoiceCapability.REALTIME)
        return realtime[binding.protocol]
            ?: throw VoiceModelError.Unsupported(VoiceCapability.REALTIME, binding.protocol.name)
    }
}
