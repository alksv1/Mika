package com.wangzan101.mika.voice.model

import android.content.ContextWrapper
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.Transcriber
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.voice.model.adapters.OpenAiSpeechTtsAdapter
import com.wangzan101.mika.voice.model.adapters.ProviderSttAdapter
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「OpenAI 兼容服务」表单存下的不加密地址(自己在局域网跑的服务)一路走到请求:从表单的输入到目录、
 * 账号解析、朗读和转文字的适配器,请求发到那个 http 地址,带着填的 Key。
 */
class OpenAiCompatibleLocalServiceTest {
    private val server = MockWebServer().apply { start() }
    private val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
    private val credentials = VoiceCredentialStore(
        object : VoiceCredentialBackend {
            val values = mutableMapOf<String, String>()
            override fun read(secretId: String): String? = values[secretId]
            override fun write(secretId: String, value: String): Boolean { values[secretId] = value; return true }
            override fun delete(secretId: String) { values.remove(secretId) }
        },
        object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
            override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
        },
    )
    private val noChatAccounts = object : VoiceProviderAccountSource {
        override fun provider(id: String): Provider? = null
        override fun activeApiKey(id: String): String? = null
        override fun hasKey(id: String): Boolean = false
        override fun keyRevision(id: String): Long = 0L
        override fun headers(provider: Provider): Map<String, String> = emptyMap()
    }
    private val resolver = VoiceAccountResolver(noChatAccounts, credentials)

    @After fun stop() = server.shutdown()

    /** 表单里填地址、Key 和模型名,存进目录,再照运行时那样解析出来。 */
    private fun saved(sttModel: String = "", ttsModel: String = "", ttsVoice: String = ""): ResolvedVoiceModel {
        val address = server.url("/v1").toString()
        assertTrue(address, address.startsWith("http://"))
        val plan = VoicePresets.plan(VoicePreset.OpenAiCompatible("", address, "lan-key", sttModel = sttModel, ttsModel = ttsModel, ttsVoice = ttsVoice))
        val ref = VoicePresets.apply(VoiceAccountManager(catalog, credentials), catalog, plan).single()
        val snapshot = catalog.state.value
        val model = snapshot.models.single { it.serviceId == ref.serviceId && it.id == ref.modelId }
        return resolver.resolve(snapshot.services.single { it.id == ref.serviceId }, model)
    }

    @Test fun `read aloud reaches the plain http address with the key`() = runBlocking {
        val wav = ByteArray(44).also {
            "RIFF".toByteArray().copyInto(it, 0)
            "WAVE".toByteArray().copyInto(it, 8)
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))

        val tts = OpenAiSpeechTtsAdapter(http = OkHttpClient())
            .create(ContextWrapper(null), saved(ttsModel = "speak"), TtsCapabilities(formats = setOf("wav")))
        val part = (tts as StreamingTtsModel).streamAudio(TtsRequest("hello", voice = "tester", format = "wav")).single()

        assertEquals("audio/wav", (part as TtsAudioPart.Complete).response.mime)
        val request = server.takeRequest()
        assertFalse(request.requestUrl!!.isHttps)
        assertEquals("/v1/audio/speech", request.path)
        assertEquals("Bearer lan-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"model\":\"speak\""))
    }

    private fun wavReply() = MockResponse().setHeader("Content-Type", "audio/wav").setBody(
        Buffer().write(ByteArray(44).also {
            "RIFF".toByteArray().copyInto(it, 0)
            "WAVE".toByteArray().copyInto(it, 8)
        }),
    )

    private fun speaker(model: ResolvedVoiceModel) = OpenAiSpeechTtsAdapter(http = OkHttpClient())
        .create(ContextWrapper(null), model, TtsCapabilities(formats = setOf("wav")))

    @Test fun `a test or a reading with no voice picked uses the voice entered with the service`() = runBlocking {
        val tts = speaker(saved(ttsModel = "speak", ttsVoice = " zh_female "))
        // 「保存并测试」「试听」发的就是不带音色的请求
        server.enqueue(wavReply())
        tts.synthesize(TtsRequest("hello"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"voice\":\"zh_female\""))
        // 流式朗读走的是另一个入口,同样补上
        server.enqueue(wavReply())
        (tts as StreamingTtsModel).streamAudio(TtsRequest("hello", voice = "")).single()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"voice\":\"zh_female\""))
        // 用户在朗读页选过音色的,照用户选的
        server.enqueue(wavReply())
        tts.synthesize(TtsRequest("hello", voice = "picked"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"voice\":\"picked\""))
    }

    @Test fun `without a voice anywhere the request is refused before it is sent`() = runBlocking {
        val tts = speaker(saved(ttsModel = "speak"))
        val error = runCatching { tts.synthesize(TtsRequest("hello")) }.exceptionOrNull()
        assertTrue(error.toString(), error is VoiceModelError)
        assertEquals(0, server.requestCount)
    }

    @Test fun `speech to text reaches the plain http address with the key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))

        val model = saved(sttModel = "listen")
        val spec = ProviderSttAdapter(SttProtocol.AudioTranscriptions).spec(model) as SpeechEngineSpec.Provider
        // 照按住说话时的接法写的(那一段要录音,这里不跑):地址取自适配器给出的服务商,Key 取自解析出的账号
        val text = Transcriber(OkHttpClient())
            .transcribe(spec.provider, model.account.credentials.apiKey.ifBlank { null }, spec.model, spec.method, Wav.silence(100), null)

        assertEquals("你好", text)
        val request = server.takeRequest()
        assertFalse(request.requestUrl!!.isHttps)
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer lan-key", request.getHeader("Authorization"))
    }
}
