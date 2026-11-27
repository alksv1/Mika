package com.wangzan101.mika.voice.model

import android.content.ContextWrapper
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.activeApiKey
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import javax.crypto.KeyGenerator

/**
 * 语音备援链和密钥池对着本地假服务真跑一遍:借用对话账号的语音模型和对话模型用同一个 [KeyPoolManager.shared] 池,
 * 429 记冷却换下一把;整个模型过载(5xx)才换链上的下一个模型。
 */
class VoiceFailoverSimulationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().apply { start() }
    private val context = ContextWrapper(null)
    private val wav = Wav.silence(80)
    private val store by lazy { ProviderStore(tmp.newFolder(), AesGcmKeyCipher { aes }) }
    private val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
    private val trace: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After fun tearDown() {
        server.shutdown()
    }

    private fun sttModel(path: String, keys: String, model: String): VoiceModelRef {
        val p = store.connect(path, ProviderConfig.Kind.OpenAiCompat, server.url("/$path/v1").toString().trimEnd('/'), keys, emptyList())
        KeyPoolManager.shared.reset(p.id)
        val service = VoiceService("provider:${p.id}", path, VoiceAccount.ExistingProvider(p.id))
        catalog.upsertService(service)
        catalog.upsertModel(VoiceModel(service.id, model, model, stt = SttBinding(SttProtocol.AudioTranscriptions)))
        return VoiceModelRef(service.id, model, VoiceCapability.STT)
    }

    private fun providerId(ref: VoiceModelRef) = ref.serviceId.removePrefix("provider:")

    @Test fun `throttled key rotates inside the shared chat pool, then an overloaded model fails over to the backup`() = runBlocking {
        val primary = sttModel("primary", "k1, k2", "whisper-1")
        val backup = sttModel("backup", "b1", "whisper-2")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = request.getHeader("Authorization").orEmpty().removePrefix("Bearer ")
                val where = request.path.orEmpty().substringAfter('/').substringBefore('/')
                trace += "$where:$key"
                return when {
                    where == "primary" && key == "k1" -> MockResponse().setResponseCode(429).addHeader("Retry-After", "60")
                        .setBody("""{"error":{"message":"rate limited"}}""")
                    where == "primary" -> MockResponse().setResponseCode(503).setBody("""{"error":{"message":"overloaded"}}""")
                    else -> MockResponse().setBody("""{"text":"hello from backup"}""")
                }
            }
        }
        val env = voiceTestEnvironment(catalog, ProviderStoreVoiceAccountSource(store))
        catalog.setSttSelection(SttSelection.Model(primary))
        catalog.setFallback(VoiceCapability.STT, 0, backup)
        assertEquals(listOf(backup), env.stt.backupsFor(primary))
        assertTrue(env.accountResolver.pooled(env.stt.requireModel(primary)))

        val text = env.stt.transcribeChain(context, listOf(primary) + env.stt.backupsFor(primary), "en")(wav)

        assertEquals("hello from backup", text)
        val primaryKeys = trace.filter { it.startsWith("primary:") }.map { it.substringAfter(':') }
        assertEquals("先 k1 被限流,换 k2;k2 过载不是密钥的事,不再换密钥", listOf("k1", "k2"), primaryKeys.distinct())
        assertEquals("b1", trace.last().substringAfter(':'))
        // 对话那边看到的是同一个池:k1 在冷却,只剩 k2 可用
        val id = providerId(primary)
        assertEquals(1, KeyPoolManager.shared.activeKeyCount(id))
        assertEquals("k2", store.activeApiKey(id))
    }

    @Test fun `bad request on the primary does not fail over`() = runBlocking {
        val primary = sttModel("primary", "k1", "whisper-1")
        val backup = sttModel("backup", "b1", "whisper-2")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                trace += request.path.orEmpty()
                return MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad audio"}}""")
            }
        }
        val env = voiceTestEnvironment(catalog, ProviderStoreVoiceAccountSource(store))

        assertThrows(ModelError::class.java) {
            runBlocking { env.stt.transcribeChain(context, listOf(primary, backup), "en")(wav) }
        }
        assertTrue(trace.none { it.startsWith("/backup/") })
    }

    @Test fun `a model that never answers times out and the backup transcribes`() = runBlocking {
        val primary = sttModel("primary", "k1", "whisper-1")
        val backup = sttModel("backup", "b1", "whisper-2")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/primary/")) {
                    MockResponse().setBody("""{"text":"late"}""").setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                } else MockResponse().setBody("""{"text":"on time"}""")
        }
        val env = voiceTestEnvironment(catalog, ProviderStoreVoiceAccountSource(store))

        val text = env.stt.transcribeChain(context, listOf(primary, backup), "en", attemptTimeoutMs = 300)(wav)
        assertEquals("on time", text)
    }

    private class FakeTts(
        override val name: String,
        val calls: MutableList<String>,
        val fail: Throwable? = null,
        val failAfterFirstChunk: Throwable? = null,
    ) : StreamingTtsModel {
        override val capabilities = TtsCapabilities()
        override suspend fun synthesize(request: TtsRequest): TtsResponse {
            calls += "$name:${request.voice}"
            fail?.let { throw it }
            return TtsResponse(name.toByteArray(), "audio/wav")
        }
        override fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = flow {
            calls += "$name:stream:${request.voice}"
            fail?.let { throw it }
            emit(TtsAudioPart.Pcm(byteArrayOf(1), 24_000, 1))
            failAfterFirstChunk?.let { throw it }
            emit(TtsAudioPart.Pcm(byteArrayOf(2), 24_000, 1))
        }
    }

    private fun ttsChain(models: Map<String, FakeTts>): Pair<List<VoiceModelRef>, VoiceModelEnvironment> {
        val provider = voiceTestProvider()
        catalog.upsertService(VoiceService("provider:p1", "P", VoiceAccount.ExistingProvider("p1")))
        val refs = models.keys.map { id ->
            catalog.upsertModel(VoiceModel("provider:p1", id, id, tts = TtsBinding(TtsProtocol.OpenAiSpeech)))
            VoiceModelRef("provider:p1", id, VoiceCapability.TTS)
        }
        return refs to voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider))
    }

    private fun speaker(refs: List<VoiceModelRef>, env: VoiceModelEnvironment, models: Map<String, FakeTts>): TtsModel =
        FallbackTtsModel.of(refs, env.tts.requireModel(refs.first()), env.tts::requireModel, env.accountResolver) {
            models.getValue(it.model.id)
        }

    @Test fun `overloaded read-aloud model fails over and the backup uses its own default voice`() = runBlocking {
        val calls = mutableListOf<String>()
        val models = linkedMapOf(
            "primary" to FakeTts("primary", calls, fail = ModelError.Server(503)),
            "backup" to FakeTts("backup", calls),
        )
        val (refs, env) = ttsChain(models)

        val response = speaker(refs, env, models).synthesize(TtsRequest("hi", voice = "alloy"))

        assertEquals("backup", String(response.bytes))
        assertEquals(listOf("primary:alloy", "backup:null"), calls)
    }

    @Test fun `streaming read-aloud fails over before the first chunk but not after playback started`() = runBlocking {
        val calls = mutableListOf<String>()
        val beforeStart = linkedMapOf(
            "primary" to FakeTts("primary", calls, fail = ModelError.RateLimited(null)),
            "backup" to FakeTts("backup", calls),
        )
        val (refs, env) = ttsChain(beforeStart)
        val streaming = speaker(refs, env, beforeStart) as StreamingTtsModel
        assertEquals(2, streaming.streamAudio(TtsRequest("hi")).toList().size)
        assertEquals(listOf("primary:stream:null", "backup:stream:null"), calls)

        calls.clear()
        val cut = ModelError.Server(502)
        val midway = linkedMapOf(
            "primary" to FakeTts("primary", calls, failAfterFirstChunk = cut),
            "backup" to FakeTts("backup", calls),
        )
        val chained = speaker(refs, env, midway) as StreamingTtsModel
        val parts = mutableListOf<TtsAudioPart>()
        val thrown = assertThrows(ModelError.Server::class.java) {
            runBlocking { chained.streamAudio(TtsRequest("hi")).collect { parts += it } }
        }
        assertSame(cut, thrown)
        assertEquals(1, parts.size)
        assertEquals("已经开始播了不换模型", listOf("primary:stream:null"), calls)
    }

    @Test fun `try and test paths build only the selected model, the speaker follows the chain`() {
        val (refs, env) = ttsChain(linkedMapOf("primary" to FakeTts("primary", mutableListOf()), "backup" to FakeTts("backup", mutableListOf())))
        catalog.setTtsSelection(refs[0])
        catalog.setFallback(VoiceCapability.TTS, 0, refs[1])
        assertEquals(listOf(refs[1]), env.tts.backupsFor(refs[0]))
        assertEquals("不是朗读选中的模型就没有链", emptyList<VoiceModelRef>(), env.tts.backupsFor(refs[1]))
        assertFalse(env.tts.createTts(context, refs[0], TtsCapabilities()) is FallbackTtsModel)
    }
}
