package com.wangzan101.mika.voice.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 只测按 role 拼配置这一段(纯 Kotlin 数据类,不加载原生库)。 */
class SherpaOnnxBackendTest {

    private val catalog = VoicePackCatalog.parse(
        File("src/main/assets/voice-models/catalog.json").readText(),
    ) { _, _ -> true }

    private fun installed(id: String) = InstalledVoicePack(catalog.entry(id)!!, File("/packs/$id/1"))

    @Test
    fun everyBundledPackIsSupported() {
        val backend = SherpaOnnxBackend()
        catalog.packs.forEach {
            assertEquals(it.id, SherpaOnnxBackend.ID, it.backend)
            assertTrue(it.id, backend.supports(it.family))
        }
        assertFalse(backend.supports("paraformer"))
    }

    @Test
    fun senseVoiceMapsModelAndTokensByRole() {
        val m = SherpaOnnxBackend.modelConfig(installed("sensevoice-small-int8"), EngineOptions(3))
        assertEquals("/packs/sensevoice-small-int8/1/model.int8.onnx", m.senseVoice.model)
        assertEquals("", m.senseVoice.language)
        assertEquals("/packs/sensevoice-small-int8/1/tokens.txt", m.tokens)
        assertEquals(3, m.numThreads)
        assertEquals("", m.whisper.encoder)
    }

    @Test
    fun whisperMapsEncoderDecoderAndAutoLanguage() {
        val m = SherpaOnnxBackend.modelConfig(installed("whisper-base-int8"), EngineOptions(2))
        assertEquals("/packs/whisper-base-int8/1/base-encoder.int8.onnx", m.whisper.encoder)
        assertEquals("/packs/whisper-base-int8/1/base-decoder.int8.onnx", m.whisper.decoder)
        assertEquals("", m.whisper.language)
        assertEquals("transcribe", m.whisper.task)
        assertEquals("/packs/whisper-base-int8/1/base-tokens.txt", m.tokens)
    }

    @Test
    fun dolphinMapsModel() {
        val m = SherpaOnnxBackend.modelConfig(installed("dolphin-base-ctc-int8"), EngineOptions(4))
        assertEquals("/packs/dolphin-base-ctc-int8/1/model.int8.onnx", m.dolphin.model)
        assertEquals("/packs/dolphin-base-ctc-int8/1/tokens.txt", m.tokens)
        assertEquals("", m.senseVoice.model)
    }

    @Test
    fun unknownFamilyIsUnsupported() {
        val p = installed("dolphin-base-ctc-int8")
        try {
            SherpaOnnxBackend.modelConfig(p.copy(entry = p.entry.copy(family = "paraformer")), EngineOptions(1))
            fail()
        } catch (e: OnDeviceSttException) {
            assertEquals(OnDeviceSttException.Kind.Unsupported, e.kind)
        }
    }

    @Test
    fun pcmToFloatScalesBy32768() {
        val f = SherpaOnnxBackend.toFloat(shortArrayOf(0, 16384, Short.MIN_VALUE, Short.MAX_VALUE))
        assertEquals(0f, f[0])
        assertEquals(0.5f, f[1])
        assertEquals(-1f, f[2])
        assertEquals(32767f / 32768f, f[3])
    }
}
