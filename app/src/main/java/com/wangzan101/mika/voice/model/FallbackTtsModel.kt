package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 朗读的备援链和密钥池轮换:每次合成先用链头,失败了由 [VoiceFailover] 决定换不换下一个;每个模型都走自己账号的
 * 密钥池([VoiceAccountResolver.withPooledKey])。备援用自己的默认音色(请求里的音色是给链头选的,别家多半不认),
 * 语速照用。流式朗读只在还没交出声音前换,已经开始播了就照常报错,不会一句话换个声音接着念。
 */
internal class FallbackTtsModel(
    private val chain: List<VoiceModelRef>,
    private val first: ResolvedVoiceModel,
    private val resolve: (VoiceModelRef) -> ResolvedVoiceModel,
    private val accounts: VoiceAccountResolver,
    private val create: (ResolvedVoiceModel) -> TtsModel,
    private val head: TtsModel,
) : TtsModel {
    override val name: String get() = head.name
    override val capabilities: TtsCapabilities get() = head.capabilities

    /** 建好的模型按「第几个 + 哪把密钥」留着:系统引擎、HTTP 客户端不必每句话重建。 */
    private val built = mutableMapOf<Pair<Int, String>, TtsModel>()

    @Synchronized
    private fun modelFor(index: Int, keyed: ResolvedVoiceModel): TtsModel {
        val key = index to keyed.account.credentials.apiKey
        if (index == 0 && keyed.account.credentials.apiKey == first.account.credentials.apiKey) return head
        return built.getOrPut(key) { create(keyed) }
    }

    private fun resolveAt(ref: VoiceModelRef): ResolvedVoiceModel = if (ref == chain.first()) first else resolve(ref)

    private fun TtsRequest.forIndex(index: Int): TtsRequest = if (index == 0) this else copy(voice = null)

    override suspend fun synthesize(request: TtsRequest): TtsResponse =
        VoiceFailover.run(chain, ::resolveAt) { resolved, index ->
            accounts.withPooledKey(resolved) { keyed -> modelFor(index, keyed).synthesize(request.forIndex(index)) }
        }

    /** 链头能边合成边播时才是 [StreamingTtsModel];备援不能流式的,整段合成好当一份完整音频交出。 */
    fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = flow {
        var started = false
        VoiceFailover.run(chain, ::resolveAt, mayContinue = { !started }) { resolved, index ->
            accounts.withPooledKey(resolved, mayRetry = { !started }) { keyed ->
                val model = modelFor(index, keyed)
                val piece = request.forIndex(index)
                if (model is StreamingTtsModel) {
                    model.streamAudio(piece).collect { part ->
                        // 先记下再交:下游出的错也算已经开始,不再换模型重来
                        started = true
                        emit(part)
                    }
                } else {
                    val response = model.synthesize(piece)
                    started = true
                    emit(TtsAudioPart.Complete(response))
                }
            }
        }
    }

    companion object {
        /** 链头能流式就给流式的外壳,否则普通的。 */
        fun of(
            chain: List<VoiceModelRef>,
            first: ResolvedVoiceModel,
            resolve: (VoiceModelRef) -> ResolvedVoiceModel,
            accounts: VoiceAccountResolver,
            create: (ResolvedVoiceModel) -> TtsModel,
        ): TtsModel {
            val head = create(first)
            val model = FallbackTtsModel(chain, first, resolve, accounts, create, head)
            if (head !is StreamingTtsModel) return model
            return object : StreamingTtsModel {
                override val name: String get() = model.name
                override val capabilities: TtsCapabilities get() = model.capabilities
                override suspend fun synthesize(request: TtsRequest): TtsResponse = model.synthesize(request)
                override fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = model.streamAudio(request)
            }
        }
    }
}
