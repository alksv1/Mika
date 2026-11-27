package com.wangzan101.mika.voice.ondevice

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceEnginesTest {

    private class FakeEngine : OnDeviceSttEngine {
        @Volatile var closed = false
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null
        /** 关原生模型时抛出(模拟原生 close 出错)。 */
        var closeFailure: Throwable? = null
        override suspend fun transcribe(pcm: ShortArray, language: String?): String {
            gate?.await()
            failure?.let { throw it }
            check(!closed) { "transcribe on a closed engine" }
            return "hello"
        }
        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private class FakeBackend(private val onLoad: () -> Unit = {}) : OnDeviceSttBackend {
        val loads = java.util.Collections.synchronizedList(mutableListOf<FakeEngine>())
        var nextGate: CompletableDeferred<Unit>? = null
        override val id = "fake"
        override fun supports(family: String) = family == "f"
        override fun abiSupported() = true
        override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine {
            onLoad()
            return FakeEngine().also { it.gate = nextGate; loads += it }
        }
    }

    private fun pack(id: String = "p", version: Int = 1) = InstalledVoicePack(
        VoicePackEntry(
            id = id, version = version, backend = "fake", family = "f", displayName = id,
            languages = listOf("en"), recommendedFor = emptyList(),
            files = listOf(VoicePackFile("m.onnx", "model", 1, "00")),
            license = VoicePackLicense("MIT", "LICENSE", "test"), upstream = "https://example.invalid/",
        ),
        File("/nonexistent/$id/$version"),
    )

    private fun TestScope.engines(backend: FakeBackend, idleMs: Long = OnDeviceEngines.IDLE_MS) =
        OnDeviceEngines(
            OnDeviceBackends(listOf(backend)), backgroundScope, idleMs,
            loadDispatcher = StandardTestDispatcher(testScheduler),
        )

    private suspend fun expectKind(kind: OnDeviceSttException.Kind, block: suspend () -> Unit) {
        try {
            block()
            fail("expected OnDeviceSttException($kind)")
        } catch (e: OnDeviceSttException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun loadsOncePerPackVersion() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend)
        val a = async { engines.engine(pack()) }
        val b = async { engines.engine(pack()) }
        assertSame(a.await(), b.await())
        assertSame(a.await(), engines.engine(pack()))
        assertEquals(1, backend.loads.size)

        // 新版本替换旧版本:旧的关掉,新的重新加载
        engines.engine(pack(version = 2))
        assertEquals(2, backend.loads.size)
        assertTrue(backend.loads[0].closed)
    }

    @Test
    fun prewarmStartsTheLoadAndEngineReusesIt() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend)
        engines.prewarm(pack())
        runCurrent()
        assertEquals(1, backend.loads.size)
        engines.engine(pack())
        assertEquals(1, backend.loads.size)
    }

    @Test
    fun loadTimeoutMapsToTimeout() = runTest {
        val stuck = CountDownLatch(1)
        val backend = FakeBackend { stuck.await() }
        // 真线程上卡住的加载;等待用测试时钟,一下就到 LOAD_TIMEOUT_MS。
        // 等真线程时测试时钟会一直往前跳,闲置释放设成永不,免得晚到的模型刚装上就被闲置释放
        val engines = OnDeviceEngines(
            OnDeviceBackends(listOf(backend)), backgroundScope, idleMs = Long.MAX_VALUE, loadDispatcher = Dispatchers.IO,
        )
        try {
            expectKind(OnDeviceSttException.Kind.Timeout) { engines.engine(pack()) }
            assertTrue(currentTime >= OnDeviceTimeout.LOAD_TIMEOUT_MS)
            // 那次加载还卡着:后来的调用直接报超时,不再等一整个上限
            val before = currentTime
            expectKind(OnDeviceSttException.Kind.Timeout) { engines.engine(pack()) }
            assertEquals(before, currentTime)
        } finally {
            stuck.countDown()
        }
        // 超时后晚到的加载结果留着用,不重复加载
        val late = withContext(Dispatchers.IO) { engines.engine(pack()) }
        assertEquals("hello", late.transcribe(ShortArray(1), null))
        assertEquals(1, backend.loads.size)
    }

    @Test
    fun oomMapsToOutOfMemory() = runTest {
        val engines = engines(FakeBackend { throw OutOfMemoryError("boom") })
        expectKind(OnDeviceSttException.Kind.OutOfMemory) { engines.engine(pack()) }
    }

    @Test
    fun otherLoadErrorsMapToCorruptAndRetry() = runTest {
        var fail = true
        val backend = FakeBackend { if (fail) throw IllegalArgumentException("bad model") }
        val engines = engines(backend)
        expectKind(OnDeviceSttException.Kind.Corrupt) { engines.engine(pack()) }
        fail = false
        engines.engine(pack())
        assertEquals(1, backend.loads.size)
    }

    @Test
    fun backendErrorsKeepTheirKind() = runTest {
        val engines = engines(FakeBackend { throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "no tokens") })
        expectKind(OnDeviceSttException.Kind.Missing) { engines.engine(pack()) }
    }

    @Test
    fun unknownBackendIsUnsupported() = runTest {
        val engines = OnDeviceEngines(OnDeviceBackends(emptyList()), backgroundScope)
        expectKind(OnDeviceSttException.Kind.Unsupported) { engines.engine(pack()) }
    }

    @Test
    fun releaseClosesEngines() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend)
        engines.engine(pack("a"))
        engines.engine(pack("b"))
        engines.release("a")
        assertTrue(backend.loads[0].closed)
        assertFalse(backend.loads[1].closed)
        engines.release()
        assertTrue(backend.loads[1].closed)
        engines.engine(pack("a"))
        assertEquals(3, backend.loads.size)
    }

    @Test
    fun idleReleaseAfterIdleMs() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend, idleMs = 1_000)
        val engine = engines.engine(pack())
        advanceTimeBy(900)
        engine.transcribe(ShortArray(1), null)    // 用一次,重新计时
        advanceTimeBy(900)
        runCurrent()
        assertFalse(backend.loads[0].closed)
        advanceTimeBy(101)
        runCurrent()
        assertTrue(backend.loads[0].closed)
        assertNotSame(engine, engines.engine(pack()))
        assertEquals(2, backend.loads.size)
    }

    @Test
    fun releaseWhileTranscribingKeepsTheTextAndFailsTheNextCall() = runTest {
        val backend = FakeBackend().apply { nextGate = CompletableDeferred() }
        val engines = engines(backend)
        val engine = engines.engine(pack())
        val call = async { runCatching { engine.transcribe(ShortArray(1), null) } }
        runCurrent()
        engines.release()
        assertFalse("must not close under an in-flight decode", backend.loads[0].closed)
        backend.loads[0].gate!!.complete(Unit)
        // 解码已经做完(原生对象等它结束才关):这次说的话照样拿到
        assertEquals("hello", call.await().getOrThrow())
        assertTrue(backend.loads[0].closed)
        // 关掉之后再用同一个句柄:OnDeviceSttException
        expectKind(OnDeviceSttException.Kind.OutOfMemory) { engine.transcribe(ShortArray(1), null) }

        backend.nextGate = null
        assertEquals("hello", engines.engine(pack()).transcribe(ShortArray(1), null))
        assertEquals(2, backend.loads.size)
    }

    @Test
    fun releaseOfOnePackWhileTranscribingKeepsTheText() = runTest {
        val backend = FakeBackend().apply { nextGate = CompletableDeferred() }
        val engines = engines(backend)
        val engine = engines.engine(pack())
        val call = async { runCatching { engine.transcribe(ShortArray(1), null) } }
        runCurrent()
        engines.release("p")
        backend.loads[0].gate!!.complete(Unit)
        assertEquals("hello", call.await().getOrThrow())
        assertTrue(backend.loads[0].closed)
        expectKind(OnDeviceSttException.Kind.Missing) { engine.transcribe(ShortArray(1), null) }
    }

    @Test
    fun nativeCloseErrorsDoNotEscapeReleaseOrIdle() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend, idleMs = 1_000)
        engines.engine(pack("a")).transcribe(ShortArray(1), null)
        engines.engine(pack("b")).transcribe(ShortArray(1), null)
        backend.loads.forEach { it.closeFailure = IllegalStateException("native close failed") }
        // 系统内存紧张时在主线程调:原生 close 出错不能往外抛
        engines.release("a")
        assertTrue(backend.loads[0].closed)
        // 闲置到时在 scope 里关:出错也不能把 scope 里的异常抛出来(backgroundScope 会让测试失败)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(backend.loads[1].closed)
        // 关过之后照常重新加载
        assertEquals("hello", engines.engine(pack("b")).transcribe(ShortArray(1), null))
        assertEquals(3, backend.loads.size)
    }

    @Test
    fun decodeErrorsMapToFailedAndKeepTheirKind() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend)
        val engine = engines.engine(pack())
        // 模型已经加载成功,解码出错不是「文件损坏」:不让用户去重新下载
        backend.loads[0].failure = IllegalStateException("native decode failed")
        expectKind(OnDeviceSttException.Kind.Failed) { engine.transcribe(ShortArray(1), null) }
        backend.loads[0].failure = OnDeviceSttException(OnDeviceSttException.Kind.Missing, "gone")
        expectKind(OnDeviceSttException.Kind.Missing) { engine.transcribe(ShortArray(1), null) }
        // 出错不拆模型,下次照常用
        backend.loads[0].failure = null
        assertEquals("hello", engine.transcribe(ShortArray(1), null))
    }

    @Test
    fun closingTheHandleKeepsTheSharedEngine() = runTest {
        val backend = FakeBackend()
        val engines = engines(backend)
        engines.engine(pack()).use { it.transcribe(ShortArray(1), null) }
        assertFalse(backend.loads[0].closed)
        assertEquals("hello", engines.engine(pack()).transcribe(ShortArray(1), null))
        assertEquals(1, backend.loads.size)
    }

    @Test
    fun installedPackFileByRole() {
        val p = pack()
        assertEquals(File(p.dir, "m.onnx"), p.file("model"))
        try {
            p.file("tokens")
            fail()
        } catch (e: OnDeviceSttException) {
            assertEquals(OnDeviceSttException.Kind.Missing, e.kind)
        }
    }

    @Test
    fun registryFindsBackendsAndFamilies() {
        val backends = OnDeviceBackends(listOf(FakeBackend()))
        assertTrue(backends.supports("fake", "f"))
        assertFalse(backends.supports("fake", "other"))
        assertFalse(backends.supports("nope", "f"))
        assertEquals(null, backends.find("nope"))
    }

    @Test
    fun defaultOptionsCapThreadsAtFour() {
        val t = EngineOptions.default().threads
        assertTrue(t in 1..4)
    }
}
