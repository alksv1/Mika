package com.wangzan101.mika.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机冒烟:走真实 [AndroidSystemTtsEngine] 合成一个完整 WAV。设备没有可用引擎或缺语言数据时,
 * 断言得到的是显式的系统不可用结果(而不是崩溃或换成云端);语言恰好支持时不报错直接通过。
 */
@RunWith(AndroidJUnit4::class)
class SystemTtsDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun synthesize(language: AppLanguage, text: String, engine: String? = null, voice: String? = null) =
        SystemTtsModel(
            context = context,
            engineFactory = { AndroidSystemTtsEngine(it, engine) },
            resolvedLanguage = { language },
            outputDir = { File(context.cacheDir, "voice") },
        ).synthesize(TtsRequest(text, voice = voice))

    /** 失败只许是显式的系统不可用,不许崩溃、卡住或换成云端。 */
    private fun assertOkOrUnavailable(result: Result<com.wangzan101.mika.core.model.TtsResponse>) {
        val error = result.exceptionOrNull()
        if (error != null) {
            assertTrue(error is VoiceModelError)
            assertTrue((error as VoiceModelError).category in setOf(VoiceErrorCategory.SYSTEM_UNAVAILABLE, VoiceErrorCategory.TIMEOUT))
            return
        }
        assertEquals("audio/wav", result.getOrThrow().mime)
        assertTrue(result.getOrThrow().bytes.isNotEmpty())
    }

    @Test
    fun installedEnginesAreListedAndTheDefaultComesFirst() = runBlocking {
        val engines = SystemTtsDiscovery.engines(context)
        if (engines.isEmpty()) return@runBlocking
        assertEquals(engines.map { it.packageName }.distinct(), engines.map { it.packageName })
        val defaults = engines.indexOfFirst { it.isDefault }
        assertTrue(defaults <= 0)
    }

    @Test
    fun eachInstalledEngineListsVoicesAndSynthesizesWithItsFirstVoice() = runBlocking {
        for (engine in SystemTtsDiscovery.engines(context)) {
            val voices = SystemTtsDiscovery.voices(context, engine.packageName, java.util.Locale.ENGLISH)
            val voice = voices.firstOrNull()?.name
            assertOkOrUnavailable(runCatching {
                synthesize(AppLanguage.resolved(context), "Hello, this is a system speech test.", engine.packageName, voice)
            })
        }
    }

    @Test
    fun aMissingEnginePackageFallsBackToTheDefaultEngine() = runBlocking {
        assertOkOrUnavailable(runCatching {
            synthesize(AppLanguage.resolved(context), "Hello.", engine = "com.example.not.installed.tts")
        })
    }

    @Test
    fun aVoiceTheEngineDoesNotOfferStillSpeaksWithTheDefaultVoice() = runBlocking {
        assertOkOrUnavailable(runCatching { synthesize(AppLanguage.resolved(context), "Hello.", voice = "alloy") })
    }

    @Test
    fun systemEngineSynthesizesNonEmptyWavForTheResolvedLanguage() = runBlocking {
        val result = runCatching { synthesize(AppLanguage.resolved(context), "Hello, this is a system speech test.") }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            assertTrue(error is VoiceModelError)
            assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
            return@runBlocking
        }
        val response = result.getOrThrow()
        assertEquals("audio/wav", response.mime)
        assertTrue(response.bytes.isNotEmpty())
    }

    @Test
    fun unsupportedLanguageReturnsTheExplicitCapabilityFailure() = runBlocking {
        val error = runCatching {
            synthesize(AppLanguage.Japanese, "こんにちは。")
        }.exceptionOrNull() ?: return@runBlocking

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
    }
}
