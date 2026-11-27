package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class VoicePresetsTest {
    private fun store() = VoiceCredentialStore(
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

    private fun manager(catalog: VoiceCatalogStore, store: VoiceCredentialStore = store()) = VoiceAccountManager(catalog, store)

    @Test fun `volc takes one key for both input and calls and honours the plan`() {
        val plan = VoicePresets.plan(VoicePreset.Volc(apiKey = " k ", sttPlan = VolcSttPlan.Concurrent))
        assertEquals("k", plan.credentials?.apiKey)
        val stt = plan.models.single { it.stt != null }
        assertEquals(DoubaoProtocol.CONCURRENT, stt.stt?.resourceId)
        val realtime = plan.models.single { it.realtime != null }
        assertEquals(VolcRealtimeConfig.MODEL, realtime.id)
        assertEquals(RealtimeProtocol.VolcDuplex, realtime.realtime?.protocol)
    }

    @Test fun `volc accepts app id and token, and only what is ticked`() {
        val plan = VoicePresets.plan(VoicePreset.Volc(appId = "a", accessToken = "t", realtime = false))
        assertEquals("a", plan.credentials?.appId)
        assertEquals(listOf(VoiceCapability.STT), plan.refs.map { it.capability })
        assertThrows(VoiceModelError.IncompleteConfig::class.java) { VoicePresets.plan(VoicePreset.Volc(appId = "a")) }
        assertThrows(VoiceModelError.IncompleteConfig::class.java) {
            VoicePresets.plan(VoicePreset.Volc(apiKey = "k", stt = false, realtime = false))
        }
    }

    @Test fun `bailian is one key and a region`() {
        val plan = VoicePresets.plan(VoicePreset.Bailian("k", BailianRegion.Singapore))
        assertEquals("bailian:singapore", plan.service.id)
        assertEquals(BailianRegion.Singapore.endpoint(""), plan.models.single().stt?.endpoint)
    }

    @Test fun `openai compatible merges capabilities on one model id and derives the realtime socket`() {
        val plan = VoicePresets.plan(
            VoicePreset.OpenAiCompatible("", "https://api.example.com/v1/", "k", sttModel = "m", ttsModel = "m", realtimeModel = "rt"),
        )
        assertEquals("api.example.com", plan.service.name)
        assertEquals(2, plan.models.size)
        val m = plan.models.first { it.id == "m" }
        assertEquals(setOf(VoiceCapability.STT, VoiceCapability.TTS), m.capabilities)
        assertEquals("https://api.example.com/v1", m.tts?.endpoint)
        assertEquals("wss://api.example.com/v1/realtime", plan.models.first { it.id == "rt" }.realtime?.endpoint)
        assertEquals("wss://h:8443/realtime", VoicePresets.realtimeEndpoint(URI("https://h:8443")))
    }

    @Test fun `openai compatible takes a plain http address on the local network and still keeps its key`() {
        val plan = VoicePresets.plan(
            VoicePreset.OpenAiCompatible("", "HTTP://192.168.1.5:8000/v1/", "k", sttModel = "listen", ttsModel = "speak"),
        )
        assertEquals("http://192.168.1.5:8000/v1", plan.models.first { it.id == "listen" }.stt?.endpoint?.lowercase())
        assertEquals("http://192.168.1.5:8000/v1", plan.models.first { it.id == "speak" }.tts?.endpoint?.lowercase())
        assertTrue(plan.service.account is VoiceAccount.VoiceOwned)
        assertEquals("k", plan.credentials?.apiKey)
        assertThrows(VoiceModelError.IncompleteConfig::class.java) {
            VoicePresets.plan(VoicePreset.OpenAiCompatible("", "http://192.168.1.5:8000/v1", " ", ttsModel = "speak"))
        }
    }

    @Test fun `openai compatible keeps the voices entered for reading and for realtime`() {
        val plan = VoicePresets.plan(
            VoicePreset.OpenAiCompatible(
                "", "http://192.168.1.5:8000/v1", "k", ttsModel = "speak", realtimeModel = "live",
                ttsVoice = " zh_female ", realtimeVoice = " marin2 ",
            ),
        )
        assertEquals("zh_female", plan.models.first { it.id == "speak" }.tts?.voice)
        assertEquals("marin2", plan.models.first { it.id == "live" }.realtime?.speaker)
        // 没填的:朗读不记音色(官方地址有它自己的默认),实时语音用协议的默认音色
        val bare = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "https://api.example.com/v1", "k", ttsModel = "speak", realtimeModel = "live"))
        assertEquals("", bare.models.first { it.id == "speak" }.tts?.voice)
        assertEquals(com.wangzan101.mika.calls.OpenAiCallProtocol.VOICE, bare.models.first { it.id == "live" }.realtime?.speaker)
    }

    @Test fun `openai compatible rejects an address that is not http or has no host`() {
        listOf("192.168.1.5:8000/v1", "ws://192.168.1.5:8000/v1", "ftp://h/v1", "http:///v1", "http://", "").forEach { address ->
            val error = assertThrows(address, VoiceModelError.IncompleteConfig::class.java) {
                VoicePresets.plan(VoicePreset.OpenAiCompatible("", address, "k", sttModel = "m"))
            }
            assertEquals(address, "Address must start with http:// or https://", error.detail)
        }
    }

    @Test fun `openai compatible refuses a query, fragment or user info instead of guessing what they mean`() {
        listOf("https://h/v1?key=k", "https://h/v1#x", "https://user:pw@h/v1", "http://u@192.168.1.5:8000").forEach { address ->
            val error = assertThrows(address, VoiceModelError.IncompleteConfig::class.java) {
                VoicePresets.plan(VoicePreset.OpenAiCompatible("", address, "k", sttModel = "m"))
            }
            assertEquals(address, "Address must not contain a query, fragment or user info", error.detail)
            assertFalse(address, error.detail.contains("pw") || error.detail.contains("key=k"))
        }
    }

    @Test fun `an explicit default port names the same service as no port`() {
        val plain = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "https://api.example.com/v1", "k", ttsModel = "s"))
        val explicit = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "https://api.example.com:443/v1", "k", ttsModel = "s"))
        val http = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "http://192.168.1.5:80", "k", ttsModel = "s"))
        assertEquals(plain.service, explicit.service)
        assertEquals("compat:192.168.1.5", http.service.id)
    }

    @Test fun `an openai compatible address with a path saves, and its key reads back`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val store = store()
        val plan = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "https://api.example.com/v1", "k", ttsModel = "speak"))
        val ref = VoicePresets.apply(manager(catalog, store), catalog, plan).single()
        val account = catalog.state.value.services.single { it.id == ref.serviceId }.account as VoiceAccount.VoiceOwned
        assertTrue(account.secretId, VoiceCredentialStore.validSecretId(account.secretId))
        assertEquals("k", store.read(account.secretId)?.apiKey)
        // 同一个地址再存一次(换 Key)还是那一个服务、那一个凭据编号
        val again = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "https://api.example.com/v1/", "k2", ttsModel = "speak"))
        assertEquals(plan.service, again.service)
    }

    @Test fun `credential ids stay as they were when valid and never collide when rewritten`() {
        assertEquals("preset:compat:api.example.com", VoicePresets.secretId("compat:api.example.com"))
        assertEquals("preset:compat:192.168.1.5", VoicePresets.secretId("compat:192.168.1.5"))
        val rewritten = listOf("compat:h/v1", "compat:h/v2", "compat:h/v1/x", "compat:h/v_1", "compat:[::1]/v1", "compat:h/" + "p".repeat(400))
            .map(VoicePresets::secretId)
        rewritten.forEach { assertTrue(it, VoiceCredentialStore.validSecretId(it)) }
        assertEquals(rewritten.size, rewritten.toSet().size)
        assertTrue(rewritten.none { it == VoicePresets.secretId("compat:h_v1") })
    }

    @Test fun `the ids saved data hangs on are exactly these`() {
        // 目录和凭据存储按这些编号找已存的服务和 Key。算法(或凭据编号允许的字符)一改,
        // 同一个地址就换了编号,已存的 Key 找不到:这里把具体的值钉住,要改先想好怎么迁移。
        val plan = VoicePresets.plan(VoicePreset.OpenAiCompatible("", "http://192.168.1.5:8000/v1", "k", sttModel = "m"))
        assertEquals("compat:192.168.1.5:8000/v1", plan.service.id)
        assertEquals("preset:compat:192.168.1.5:8000_v1.3c022df15ad7", (plan.service.account as VoiceAccount.VoiceOwned).secretId)
        assertEquals("preset:compat:api.example.com_v1.2d5a55ee3296", VoicePresets.secretId("compat:api.example.com/v1"))
        assertEquals("preset:compat:api.example.com", VoicePresets.secretId("compat:api.example.com"))
        assertEquals("selfhosted:192.168.1.5:10095", VoicePresets.plan(VoicePreset.SelfHostedStt("192.168.1.5:10095")).service.id)
    }

    @Test fun `services on one host and different ports do not replace each other`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val manager = manager(catalog)
        fun save(address: String, key: String, stt: String = "", tts: String = "") =
            VoicePresets.apply(manager, catalog, VoicePresets.plan(VoicePreset.OpenAiCompatible("", address, key, sttModel = stt, ttsModel = tts)))
        save("http://192.168.1.5:8000/v1", "k1", tts = "speak")
        save("http://192.168.1.5:9000/v1", "k2", stt = "listen")
        val compat = catalog.state.value.services.filter { it.id.startsWith("compat:") }
        assertEquals(listOf("192.168.1.5:8000", "192.168.1.5:9000"), compat.map { it.name })
        assertEquals(2, compat.map { (it.account as VoiceAccount.VoiceOwned).secretId }.toSet().size)
        assertEquals(setOf("speak", "listen"), catalog.state.value.models.filter { it.serviceId.startsWith("compat:") }.map { it.id }.toSet())
    }

    @Test fun `an address without a port, or with the default one, keeps the service id it always had`() {
        fun id(address: String) = VoicePresets.plan(VoicePreset.OpenAiCompatible("", address, "k", sttModel = "m")).service.id
        assertEquals("compat:api.example.com/v1", id("https://api.example.com/v1"))
        assertEquals("compat:api.example.com/v1", id("https://api.example.com:443/v1"))
        assertEquals("compat:192.168.1.5/v1", id("http://192.168.1.5:80/v1"))
        assertEquals("compat:192.168.1.5:8000/v1", id("http://192.168.1.5:8000/v1"))
        assertEquals("compat:api.example.com:8443", id("https://api.example.com:8443"))
    }

    @Test fun `self hosted keeps the recognition language chosen, automatic by default`() {
        fun language(preset: VoicePreset.SelfHostedStt) = VoicePresets.plan(preset).models.single().stt?.language
        assertEquals("", language(VoicePreset.SelfHostedStt("192.168.1.5:10095")))
        assertEquals("yue", language(VoicePreset.SelfHostedStt("192.168.1.5:10095", com.wangzan101.mika.voice.AsrLanguage.Cantonese)))
    }

    @Test fun `self hosted needs no account`() {
        val plan = VoicePresets.plan(VoicePreset.SelfHostedStt("192.168.1.5:10095"))
        assertEquals(VoiceAccount.None, plan.service.account)
        assertNull(plan.credentials)
        assertTrue(plan.models.single().stt?.endpoint.orEmpty().startsWith("ws://192.168.1.5:10095"))
    }

    @Test fun `saving a preset again replaces its models instead of adding copies`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val manager = manager(catalog)
        VoicePresets.apply(manager, catalog, VoicePresets.plan(VoicePreset.Volc(apiKey = "k")))
        assertEquals(2, catalog.state.value.models.count { it.serviceId == VoicePresets.VOLC_SERVICE })
        VoicePresets.apply(manager, catalog, VoicePresets.plan(VoicePreset.Volc(apiKey = "k", realtime = false)))
        val left = catalog.state.value.models.filter { it.serviceId == VoicePresets.VOLC_SERVICE }
        assertEquals(listOf(VoicePresets.VOLC_STT_MODEL), left.map { it.id })
    }
}
