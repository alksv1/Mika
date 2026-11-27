package com.wangzan101.mika.voice.ui

import android.content.Context
import android.content.ContextWrapper
import android.speech.tts.TextToSpeech
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.voice.SystemTtsEngine
import com.wangzan101.mika.voice.SystemTtsEngineInfo
import com.wangzan101.mika.voice.SystemVoiceResult
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.ResolvedAccount
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceCatalogBackend
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.adapters.AndroidSystemTtsAdapter
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** 系统朗读的引擎选择:存在目录里、跨重启、旧数据兼容、和音色的先后,以及适配器把它交给引擎。 */
class SystemTtsEngineChoiceTest {
    private class FlakyBackend(initial: String? = null) : VoiceCatalogBackend {
        var value: String? = initial
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (failWrites) return false
            this.value = value
            return true
        }
    }

    private fun systemBinding(catalog: VoiceCatalogStore): TtsBinding? = catalog.state.value.models.first {
        it.serviceId == TtsSelectionController.SYSTEM_TTS_REF.serviceId && it.id == TtsSelectionController.SYSTEM_TTS_REF.modelId
    }.tts

    @Test fun engineIsStoredOnTheSystemModelAndSurvivesRestart() {
        val backend = MemoryVoiceCatalogBackend()
        val env = voiceTestEnvironment(VoiceCatalogStore(backend))
        assertEquals("", env.tts.systemEngine())

        assertTrue(env.tts.setSystemEngine("com.example.tts"))
        assertFalse(env.tts.setSystemEngine("com.example.tts"))

        // 重启:环境初始化会按当前版本重写内置绑定,但要留着引擎
        val restarted = voiceTestEnvironment(VoiceCatalogStore(MemoryVoiceCatalogBackend(backend.value)))
        assertEquals("com.example.tts", restarted.tts.systemEngine())
        assertEquals(TtsProtocol.AndroidSystem, systemBinding(restarted.catalog)?.protocol)
    }

    @Test fun builtinBindingIsRepairedWithoutLosingTheEngine() {
        val backend = MemoryVoiceCatalogBackend()
        val env = voiceTestEnvironment(VoiceCatalogStore(backend))
        env.catalog.upsertModel(
            TtsSelectionController.SYSTEM_TTS_MODEL.copy(tts = TtsBinding(TtsProtocol.AndroidSystem, voice = "stale", engine = "com.example.tts")),
        )

        val restarted = voiceTestEnvironment(VoiceCatalogStore(MemoryVoiceCatalogBackend(backend.value)))

        assertEquals(TtsBinding(TtsProtocol.AndroidSystem, engine = "com.example.tts"), systemBinding(restarted.catalog))
    }

    @Test fun snapshotSavedBeforeEnginesExistedReadsAsTheDefaultEngine() {
        val old = """{"schemaVersion":1,"services":[{"id":"android-system","name":"Android system","account":{"type":"None"}}],""" +
            """"models":[{"serviceId":"android-system","id":"system-tts","displayName":"Android system speech synthesis",""" +
            """"tts":{"protocol":"AndroidSystem","endpoint":"","region":"","voice":"","voices":[],"voiceNames":{}}}],""" +
            """"selections":{"tts":{"serviceId":"android-system","modelId":"system-tts","capability":"TTS"}},"legacyMigrationComplete":true}"""
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(old), migrated = true)
        assertNull(catalog.state.value.error)

        val env = voiceTestEnvironment(catalog)

        assertEquals("", env.tts.systemEngine())
        assertEquals(TtsSelectionController.SYSTEM_TTS_REF, env.tts.current())
    }

    @Test fun changingTheEngineClearsThePickedVoiceOnlyAfterTheWrite() {
        val env = voiceTestEnvironment(VoiceCatalogStore(MemoryVoiceCatalogBackend()))
        val settings = VoiceSettings(MemoryPrefs())
        settings.setVoice("en-us-x-voice-local")

        assertTrue(chooseSystemTtsEngine(env.tts, settings, "com.example.tts"))

        assertEquals("com.example.tts", env.tts.systemEngine())
        assertEquals("", settings.state.value.voice)
    }

    @Test fun repickingTheSameEngineKeepsTheVoice() {
        val env = voiceTestEnvironment(VoiceCatalogStore(MemoryVoiceCatalogBackend()))
        env.tts.setSystemEngine("com.example.tts")
        val settings = VoiceSettings(MemoryPrefs())
        settings.setVoice("v1")

        assertFalse(chooseSystemTtsEngine(env.tts, settings, "com.example.tts"))

        assertEquals("v1", settings.state.value.voice)
    }

    @Test fun aFailedCatalogWriteKeepsTheOldEngineAndVoice() {
        val backend = FlakyBackend()
        val env = voiceTestEnvironment(VoiceCatalogStore(backend))
        val settings = VoiceSettings(MemoryPrefs())
        settings.setVoice("v1")
        backend.failWrites = true

        assertFalse(chooseSystemTtsEngine(env.tts, settings, "com.example.tts"))

        assertEquals("", env.tts.systemEngine())
        assertEquals("v1", settings.state.value.voice)
    }

    @Test fun adapterHandsTheBindingEngineToTheEngineFactory() = runTest {
        val packages = mutableListOf<String?>()
        val adapter = AndroidSystemTtsAdapter { _, pkg ->
            packages += pkg
            FailingEngine()
        }
        val app = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir"))
        }
        for (engine in listOf("com.example.tts", " ")) {
            val model = TtsSelectionController.SYSTEM_TTS_MODEL.copy(tts = TtsBinding(TtsProtocol.AndroidSystem, engine = engine))
            val resolved = ResolvedVoiceModel(TtsSelectionController.SYSTEM_TTS_SERVICE, model, ResolvedAccount.None, "fp")
            val error = runCatching { adapter.create(app, resolved, TtsCapabilities()).synthesize(TtsRequest("hi")) }.exceptionOrNull()
            assertTrue(error is VoiceModelError)
        }

        assertEquals(listOf("com.example.tts", null), packages)
    }

    @Test fun duplicateEngineNamesAreToldApart() {
        val labels = engineLabels(
            listOf(
                SystemTtsEngineInfo("com.example.a", "Speech"),
                SystemTtsEngineInfo("com.example.b", "Speech"),
                SystemTtsEngineInfo("com.example.c", "Other"),
            ),
        )

        assertEquals("Speech (com.example.a)", labels["com.example.a"])
        assertEquals("Speech (com.example.b)", labels["com.example.b"])
        assertEquals("Other", labels["com.example.c"])
    }

    /** 初始化就失败的引擎:合成在用到界面语言之前就停下。 */
    private class FailingEngine : SystemTtsEngine {
        override fun init(onInit: (Int) -> Unit) = onInit(TextToSpeech.ERROR)
        override fun setLanguage(locale: Locale) = TextToSpeech.ERROR
        override fun setSpeechRate(rate: Float) = TextToSpeech.ERROR
        override fun setVoice(voiceName: String) = SystemVoiceResult.Failed
        override fun setOnUtteranceProgressListener(onDone: (String) -> Unit, onError: (String) -> Unit) {}
        override fun synthesizeToFile(text: String, file: File, utteranceId: String) = TextToSpeech.ERROR
        override fun stop() {}
        override fun shutdown() {}
    }
}
