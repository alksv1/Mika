package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.agent.FailoverPolicy
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.settings.movedFallbacks
import kotlinx.coroutines.CancellationException

/**
 * 语音的备援链([VoiceSelections.sttFallbacks] / [VoiceSelections.ttsFallbacks])怎么改。纯函数,
 * [VoiceCatalogStore] 和界面都按它算;规则和对话模型的备援链([com.wangzan101.mika.settings.setFallback])一样。
 */
object VoiceFallbacks {
    /** 一条链最多几个备援。语音是边说边等的,链太长最坏要等很久。 */
    const val MAX = 3

    /**
     * 只收整段上传识别的模型:松手后把整段录音交出去转写,失败了同一段录音可以交给下一个。
     * 边说边出字的流式识别(和系统识别)在说话过程中就连着服务,中途换不了,不进链也不当链头。
     */
    val UTTERANCE_STT_PROTOCOLS: Set<SttProtocol> = setOf(
        SttProtocol.AudioTranscriptions,
        SttProtocol.ChatInputAudio,
        SttProtocol.GeminiInline,
        SttProtocol.ElevenLabsUpload,
        SttProtocol.DeepgramUpload,
        SttProtocol.AzureShortAudio,
        SttProtocol.TencentSentence,
        SttProtocol.XfyunDictation,
        // 本机语音包也是松手后整段转写:能当链头,也能当云端模型的备援
        SttProtocol.OnDevice,
    )

    /** [model] 能不能进 [capability] 的备援链(也是这条链能不能挂在它后面)。实时通话没有备援链。 */
    fun eligible(model: VoiceModel, capability: VoiceCapability): Boolean = when (capability) {
        VoiceCapability.STT -> model.stt?.protocol in UTTERANCE_STT_PROTOCOLS
        VoiceCapability.TTS -> model.tts != null
        VoiceCapability.REALTIME -> false
    }

    /**
     * 改第 [slot] 位:[ref] 为 null 是撤掉这一位(后面的往前补);已在链里的是移到目标位(不重复占位);
     * 新的在目标位有人时替换、链没满时接在末尾。越界或链满返回 null。
     */
    fun set(chain: List<VoiceModelRef>, slot: Int, ref: VoiceModelRef?): List<VoiceModelRef>? {
        if (slot < 0 || slot >= MAX) return null
        return when {
            ref == null -> if (slot in chain.indices) chain.toMutableList().apply { removeAt(slot) } else chain
            ref in chain -> {
                val rest = chain.filter { it != ref }
                rest.toMutableList().apply { add(minOf(slot, rest.size), ref) }
            }
            slot in chain.indices -> chain.toMutableList().apply { this[slot] = ref }
            chain.size < MAX -> chain + ref
            else -> null
        }
    }

    /** 第 [from] 位挪到第 [to] 位;和对话备援链同一个算法。越界为 null。 */
    fun moved(chain: List<VoiceModelRef>, from: Int, to: Int): List<VoiceModelRef>? = movedFallbacks(chain, from, to)

    /**
     * 存之前收拾一遍:去重,去掉链头 [primary] 自己、能力不对的、[usable] 说已经不在目录里或不能进链的,截到 [MAX]。
     */
    fun normalized(
        chain: List<VoiceModelRef>,
        primary: VoiceModelRef?,
        capability: VoiceCapability,
        usable: (VoiceModelRef) -> Boolean,
    ): List<VoiceModelRef> = chain
        .asSequence()
        .distinct()
        .filter { it.capability == capability && it != primary && usable(it) }
        .take(MAX)
        .toList()
}

/**
 * 语音这一步失败后要不要换链上的下一个:判断只有这一处。服务商返回的错误([ModelError],可能被语音层包了一层)
 * 交给对话模型链同一个判断 [FailoverPolicy.canFailover](限流、5xx、网络、鉴权、额度、模型不在……换;
 * 内容被拒、请求不对、回复解析不了不换)。语音自己的分类里:超时、网络、限流、鉴权、这个模型没配好
 * (缺凭据、缺地址)、设备上的引擎不在,都是这个模型/账号的事,换;取消、回复解析不了不换。
 */
object VoiceFailover {
    fun canTryNext(error: Throwable): Boolean {
        if (error is CancellationException || error is VoiceModelError.Cancelled) return false
        val modelError = generateSequence(error) { it.cause }.take(8).firstOrNull { it is ModelError }
        if (modelError != null) return FailoverPolicy.canFailover(modelError)
        return when (VoiceModelError.from(error).category) {
            VoiceErrorCategory.TIMEOUT,
            VoiceErrorCategory.NETWORK,
            VoiceErrorCategory.RATE_LIMITED,
            VoiceErrorCategory.AUTH_FAILED,
            VoiceErrorCategory.MISSING_CREDENTIALS,
            VoiceErrorCategory.INCOMPLETE_CONFIG,
            VoiceErrorCategory.SYSTEM_UNAVAILABLE -> true
            VoiceErrorCategory.UNSUPPORTED,
            VoiceErrorCategory.PROTOCOL_ERROR,
            VoiceErrorCategory.CANCELLED -> false
        }
    }

    /**
     * 按 [candidates] 的顺序试:每个先 [resolve](读账号;没配好的备援直接跳过,链头没配好照常抛),再 [attempt]。
     * 失败了 [canTryNext] 说换、且 [mayContinue] 还允许(比如流式朗读还没交出声音)才试下一个;
     * 都不行抛最后一次真正发出去的请求的错误,没发出去过才抛配置错误。
     */
    suspend fun <C, M, T> run(
        candidates: List<C>,
        resolve: (C) -> M,
        mayContinue: () -> Boolean = { true },
        attempt: suspend (model: M, index: Int) -> T,
    ): T {
        require(candidates.isNotEmpty())
        var lastAttemptError: Throwable? = null
        var lastConfigError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            val model = try {
                resolve(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (index == 0) throw e
                lastConfigError = e
                continue
            }
            try {
                return attempt(model, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastAttemptError = e
                if (!canTryNext(e) || !mayContinue()) throw e
            }
        }
        throw lastAttemptError ?: lastConfigError!!
    }
}
