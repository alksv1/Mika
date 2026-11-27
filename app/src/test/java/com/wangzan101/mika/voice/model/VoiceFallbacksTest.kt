package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class VoiceFallbacksTest {
    private fun ref(id: String) = VoiceModelRef("svc", id, VoiceCapability.STT)
    private val a = ref("a")
    private val b = ref("b")
    private val c = ref("c")
    private val d = ref("d")

    @Test fun `set appends, replaces, moves an existing entry and removes`() {
        assertEquals(listOf(a), VoiceFallbacks.set(emptyList(), 0, a))
        assertEquals(listOf(a, b), VoiceFallbacks.set(listOf(a), 1, b))
        assertEquals("目标位有人就替换", listOf(c, b), VoiceFallbacks.set(listOf(a, b), 0, c))
        assertEquals("已在链里的移到目标位,不重复", listOf(b, a), VoiceFallbacks.set(listOf(a, b), 0, b))
        assertEquals(listOf(b), VoiceFallbacks.set(listOf(a, b), 0, null))
        assertEquals("撤掉不存在的位不改", listOf(a), VoiceFallbacks.set(listOf(a), 2, null))
    }

    @Test fun `set refuses out of range slots and a full chain`() {
        assertNull(VoiceFallbacks.set(listOf(a), -1, b))
        assertNull(VoiceFallbacks.set(listOf(a), VoiceFallbacks.MAX, b))
        val full = listOf(a, b, c)
        assertEquals(VoiceFallbacks.MAX, full.size)
        assertEquals("满了还能替换", listOf(a, b, d), VoiceFallbacks.set(full, 2, d))
    }

    @Test fun `moved swaps neighbours and rejects bad indices`() {
        assertEquals(listOf(b, a, c), VoiceFallbacks.moved(listOf(a, b, c), 1, 0))
        assertEquals(listOf(a, c, b), VoiceFallbacks.moved(listOf(a, b, c), 1, 2))
        assertNull(VoiceFallbacks.moved(listOf(a, b), 1, 2))
        assertNull(VoiceFallbacks.moved(listOf(a, b), -1, 0))
    }

    @Test fun `normalized drops duplicates, the primary, wrong capability, unusable and overflow`() {
        val tts = VoiceModelRef("svc", "t", VoiceCapability.TTS)
        val chain = listOf(a, a, b, tts, c, d, ref("e"))
        val result = VoiceFallbacks.normalized(chain, primary = b, VoiceCapability.STT) { it != c }
        assertEquals(listOf(a, d, ref("e")), result)
    }

    @Test fun `only after-speech recognition can join or head an STT chain, any TTS can, realtime never`() {
        fun stt(protocol: SttProtocol) = VoiceModel("svc", "m", "M", stt = SttBinding(protocol))
        assertTrue(VoiceFallbacks.eligible(stt(SttProtocol.AudioTranscriptions), VoiceCapability.STT))
        assertTrue(VoiceFallbacks.eligible(stt(SttProtocol.DeepgramUpload), VoiceCapability.STT))
        assertFalse(VoiceFallbacks.eligible(stt(SttProtocol.AndroidSystem), VoiceCapability.STT))
        assertFalse(VoiceFallbacks.eligible(stt(SttProtocol.DoubaoStreaming), VoiceCapability.STT))
        assertFalse(VoiceFallbacks.eligible(stt(SttProtocol.BailianStreaming), VoiceCapability.STT))
        val tts = VoiceModel("svc", "t", "T", tts = TtsBinding(TtsProtocol.AndroidSystem))
        assertTrue(VoiceFallbacks.eligible(tts, VoiceCapability.TTS))
        assertFalse(VoiceFallbacks.eligible(tts, VoiceCapability.STT))
        val rt = VoiceModel("svc", "r", "R", realtime = RealtimeBinding(RealtimeProtocol.OpenAiRealtime))
        assertFalse(VoiceFallbacks.eligible(rt, VoiceCapability.REALTIME))
    }

    @Test fun `next model on rate limit, overload, network, timeout and auth, not on bad requests or cancel`() {
        assertTrue(VoiceFailover.canTryNext(ModelError.RateLimited(null)))
        assertTrue(VoiceFailover.canTryNext(ModelError.Server(503)))
        assertTrue(VoiceFailover.canTryNext(ModelError.Server(500)))
        assertTrue(VoiceFailover.canTryNext(ModelError.Network(SocketTimeoutException())))
        assertTrue(VoiceFailover.canTryNext(ModelError.Auth()))
        assertTrue("语音层包了一层也认", VoiceFailover.canTryNext(VoiceModelError.from(ModelError.Server(502))))
        assertTrue(VoiceFailover.canTryNext(SocketTimeoutException()))
        assertTrue(VoiceFailover.canTryNext(VoiceModelError.MissingCredentials()))

        assertFalse(VoiceFailover.canTryNext(ModelError.Server(400)))
        assertFalse(VoiceFailover.canTryNext(ModelError.BadResponse("x")))
        assertFalse(VoiceFailover.canTryNext(VoiceModelError.Unsupported(VoiceCapability.STT)))
        assertFalse(VoiceFailover.canTryNext(VoiceModelError.Cancelled()))
        assertFalse(VoiceFailover.canTryNext(CancellationException()))
    }

    @Test fun `run tries in order, skips unconfigured backups and stops on a non retryable error`() = runBlocking {
        val tried = mutableListOf<String>()
        val result = VoiceFailover.run(listOf("a", "broken", "c"), resolve = { if (it == "broken") error("not set up") else it }) { m, _ ->
            tried += m
            if (m == "a") throw ModelError.Server(503) else "ok:$m"
        }
        assertEquals("ok:c", result)
        assertEquals(listOf("a", "c"), tried)

        val bad = ModelError.Server(400)
        val thrown = assertThrows(ModelError.Server::class.java) {
            runBlocking { VoiceFailover.run(listOf("a", "b"), { it }) { m, _ -> tried += m; throw bad } }
        }
        assertSame(bad, thrown)
    }

    @Test fun `run reports the last real failure, a broken primary throws, mayContinue stops the chain`() = runBlocking {
        val last = ModelError.Server(502)
        val thrown = assertThrows(ModelError.Server::class.java) {
            runBlocking {
                VoiceFailover.run(listOf("a", "b", "missing"), { if (it == "missing") error("gone") else it }) { m, _ ->
                    throw if (m == "a") ModelError.Server(503) else last
                }
            }
        }
        assertSame(last, thrown)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { VoiceFailover.run(listOf("a", "b"), { if (it == "a") error("primary") else it }) { m, _ -> m } }
        }

        var calls = 0
        assertThrows(ModelError.Server::class.java) {
            runBlocking {
                VoiceFailover.run(listOf("a", "b"), { it }, mayContinue = { false }) { _, _ -> calls++; throw ModelError.Server(503) }
            }
        }
        assertEquals(1, calls)
    }

    @Test fun onDeviceIsEligible() {
        val pack = VoiceModel("on-device", "pack", "Pack", stt = SttBinding(SttProtocol.OnDevice, endpoint = "pack"))
        assertTrue("本机语音包既能当链头也能当备援", VoiceFallbacks.eligible(pack, VoiceCapability.STT))
    }
}
