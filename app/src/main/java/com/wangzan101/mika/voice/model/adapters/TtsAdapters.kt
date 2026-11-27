package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.OpenAiSpeechModel
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.model.ResolvedAccount
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface TtsAdapter {
    val protocol: TtsProtocol
    fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel
}

private class CategorizedTtsModel(
    private val delegate: TtsModel,
) : TtsModel {
    override val name: String get() = delegate.name
    override val capabilities: TtsCapabilities get() = delegate.capabilities
    override suspend fun synthesize(request: TtsRequest): TtsResponse = categorizedCall {
        delegate.synthesize(request)
    }
}

private class CategorizedStreamingTtsModel(
    private val delegate: StreamingTtsModel,
) : StreamingTtsModel {
    override val name: String get() = delegate.name
    override val capabilities: TtsCapabilities get() = delegate.capabilities
    override suspend fun synthesize(request: TtsRequest): TtsResponse = categorizedCall {
        delegate.synthesize(request)
    }

    override fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = try {
        delegate.streamAudio(request).catch { error ->
            if (error is CancellationException) throw error
            throw VoiceModelError.from(error)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw VoiceModelError.from(error)
    }
}

internal fun categorized(delegate: TtsModel): TtsModel = when (delegate) {
    is StreamingTtsModel -> CategorizedStreamingTtsModel(delegate)
    else -> CategorizedTtsModel(delegate)
}

private fun <T> categorizedCreation(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw VoiceModelError.from(error)
}

private suspend fun <T> categorizedCall(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw VoiceModelError.from(error)
}

class OpenAiSpeechTtsAdapter(
    private val http: OkHttpClient = ttsHttp,
) : TtsAdapter {
    override val protocol = TtsProtocol.OpenAiSpeech

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.ttsBinding(protocol)
        val provider = model.legacyProvider(binding.endpoint)
        if (OpenAiSpeechModel.endpointOf(provider.baseUrl) == null) {
            throw VoiceModelError.IncompleteConfig("OpenAI Speech endpoint is invalid")
        }
        val delegate = categorizedCreation {
            OpenAiSpeechModel(
                baseUrl = provider.baseUrl,
                apiKey = model.account.credentials.apiKey.ifBlank { null },
                model = model.model.id,
                capabilities = capabilities,
                http = http,
                headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty(),
            )
        }
        return categorized(withDefaultVoice(delegate, binding.voice))
    }
}

/**
 * 请求里没带音色时,用 [voice]:一般是这个模型自己记着的那个(接入时填的)。自己部署的服务各有各的音色名,不带就被拒;
 * 用户在朗读页选过音色的,请求里带着,照用户选的。[voice] 为空时原样不动。
 */
internal fun withDefaultVoice(delegate: TtsModel, voice: String): TtsModel {
    val default = voice.trim()
    if (default.isEmpty()) return delegate
    fun TtsRequest.filled(): TtsRequest = if (this.voice.isNullOrBlank()) copy(voice = default) else this
    return when (delegate) {
        is StreamingTtsModel -> object : StreamingTtsModel {
            override val name: String get() = delegate.name
            override val capabilities: TtsCapabilities get() = delegate.capabilities
            override suspend fun synthesize(request: TtsRequest): TtsResponse = delegate.synthesize(request.filled())
            override fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = delegate.streamAudio(request.filled())
        }
        else -> object : TtsModel {
            override val name: String get() = delegate.name
            override val capabilities: TtsCapabilities get() = delegate.capabilities
            override suspend fun synthesize(request: TtsRequest): TtsResponse = delegate.synthesize(request.filled())
        }
    }
}

class GeminiTtsAdapter(
    private val http: OkHttpClient = ttsHttp,
) : TtsAdapter {
    override val protocol = TtsProtocol.GeminiTts

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.ttsBinding(protocol)
        val provider = model.legacyProvider(binding.endpoint, forceGemini = true)
        val key = model.account.credentials.apiKey
        if (key.isBlank()) throw VoiceModelError.MissingCredentials()
        val delegate = categorizedCreation {
            GeminiTtsModel(
                baseUrl = provider.baseUrl,
                apiKey = key,
                model = model.model.id,
                capabilities = capabilities,
                http = http,
                headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers.orEmpty(),
            )
        }
        // 这个协议的请求必须带音色。模型没记音色的(从对话账号接入的),用它音色表的第一个
        val voice = binding.voice.ifBlank { VoiceVoices.tts(model.model).firstOrNull().orEmpty() }
        return categorized(withDefaultVoice(delegate, voice))
    }
}

private val ttsHttp: OkHttpClient
    get() = ProviderStore.modelHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

internal fun ResolvedVoiceModel.ttsBinding(protocol: TtsProtocol): TtsBinding {
    val binding = model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
    if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
    return binding
}
