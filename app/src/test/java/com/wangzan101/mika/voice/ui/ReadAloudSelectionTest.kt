package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test

/** 换朗读模型时音色回到新模型自己的默认([selectReadAloud]);重选同一个不动。 */
class ReadAloudSelectionTest {
    private val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true).apply {
        upsertService(VoiceService("a", "A", VoiceAccount.None))
        upsertService(VoiceService("b", "B", VoiceAccount.None))
        upsertModel(VoiceModel("a", "m", "M", tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "http://a/v1")))
        upsertModel(VoiceModel("b", "m", "M", tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "http://b/v1", voice = "own")))
    }
    private val environment = voiceTestEnvironment(catalog)
    private val settings = VoiceSettings(FeatureFlagsCapsuleTest.MemoryPrefs())
    private val a = VoiceModelRef("a", "m", VoiceCapability.TTS)
    private val b = VoiceModelRef("b", "m", VoiceCapability.TTS)

    @Test fun `picking another model drops the voice chosen for the previous one`() {
        selectReadAloud(environment, settings, a)
        settings.setVoice("alloy")

        selectReadAloud(environment, settings, a)
        assertEquals("alloy", settings.state.value.voice)

        selectReadAloud(environment, settings, b)
        assertEquals(b, catalog.state.value.selections.tts)
        assertEquals("", settings.state.value.voice)
    }

    @Test fun `while the old settings are still in charge the voice is left alone`() {
        // 迁移没成功:朗读还按旧选择里记的音色读,这里选什么都不该把它清掉
        val legacy = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = false).apply {
            upsertService(VoiceService("a", "A", VoiceAccount.None))
            upsertModel(VoiceModel("a", "m", "M", tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "http://a/v1")))
        }
        settings.setVoice("mine")

        selectReadAloud(voiceTestEnvironment(legacy), settings, a)

        assertEquals("mine", settings.state.value.voice)
    }

    @Test fun `a failed catalog write keeps the old model and its voice`() {
        val backend = object : com.wangzan101.mika.voice.model.VoiceCatalogBackend {
            var value: String? = null
            var fail = false
            override fun read(): String? = value
            override fun write(value: String): Boolean {
                if (fail) return false
                this.value = value
                return true
            }
        }
        val store = VoiceCatalogStore(backend, migrated = true).apply {
            upsertService(VoiceService("a", "A", VoiceAccount.None))
            upsertService(VoiceService("b", "B", VoiceAccount.None))
            upsertModel(VoiceModel("a", "m", "M", tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "http://a/v1")))
            upsertModel(VoiceModel("b", "m", "M", tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = "http://b/v1")))
        }
        val env = voiceTestEnvironment(store)
        selectReadAloud(env, settings, a)
        settings.setVoice("alloy")
        backend.fail = true

        val error = runCatching { selectReadAloud(env, settings, b) }.exceptionOrNull()

        org.junit.Assert.assertTrue(error is IllegalStateException)
        assertEquals(a, store.state.value.selections.tts)
        assertEquals("alloy", settings.state.value.voice)
    }
}
