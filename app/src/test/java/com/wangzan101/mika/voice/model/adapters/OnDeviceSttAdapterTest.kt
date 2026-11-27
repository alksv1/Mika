package com.wangzan101.mika.voice.model.adapters

import android.content.ContextWrapper
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.StreamingRecorder
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.VoiceAdapterRegistry
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceFailover
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import com.wangzan101.mika.voice.ondevice.EngineOptions
import com.wangzan101.mika.voice.ondevice.InstalledVoicePack
import com.wangzan101.mika.voice.ondevice.OnDeviceBackends
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ondevice.OnDeviceEngines
import com.wangzan101.mika.voice.ondevice.OnDeviceSttBackend
import com.wangzan101.mika.voice.ondevice.OnDeviceSttEngine
import com.wangzan101.mika.voice.ondevice.OnDeviceSttException
import com.wangzan101.mika.voice.ondevice.OnDeviceTimeout
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackFile
import com.wangzan101.mika.voice.ondevice.VoicePackLicense
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** 假的本机后端:加载立刻完成,识别按 [transcribe] 给结果;记下每次识别收到的语言。 */
internal class FakeOnDeviceBackend(
    var abi: Boolean = true,
    var loadFailure: OnDeviceSttException? = null,
    var transcribe: suspend (ShortArray, String?) -> String = { _, _ -> "离线识别" },
) : OnDeviceSttBackend {
    val languages: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val calls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val closes = AtomicInteger()
    override val id = "fake"
    override fun supports(family: String) = family == "f"
    override fun abiSupported() = abi
    override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine {
        loadFailure?.let { throw it }
        return object : OnDeviceSttEngine {
            override suspend fun transcribe(pcm: ShortArray, language: String?): String {
                languages += language
                calls += pcm.size
                return this@FakeOnDeviceBackend.transcribe(pcm, language)
            }
            override fun close() { closes.incrementAndGet() }
        }
    }
}

internal fun testPackEntry(id: String = "pack", languages: List<String> = listOf("zh", "en")) = VoicePackEntry(
    id = id, version = 1, backend = "fake", family = "f", displayName = "Pack $id",
    languages = languages, recommendedFor = emptyList(),
    files = listOf(VoicePackFile("m.onnx", "model", 3, "00".repeat(32))),
    license = VoicePackLicense("MIT", "LICENSE", "test"), upstream = "https://example.invalid/",
)

/** 照 [VoicePackStore] 的目录布局把 [entry] 的文件写到 [root] 下(大小和清单一致)。 */
internal fun installTestPack(root: File, entry: VoicePackEntry) {
    val dir = File(File(root, entry.id), entry.version.toString()).apply { mkdirs() }
    entry.files.forEach { File(dir, it.name).writeBytes(ByteArray(it.size.toInt())) }
}

internal fun testOnDeviceAdapter(
    root: File,
    backend: FakeOnDeviceBackend,
    vararg entries: VoicePackEntry,
    engines: OnDeviceEngines = OnDeviceEngines(OnDeviceBackends(listOf(backend)), CoroutineScope(SupervisorJob() + Dispatchers.Default)),
    budgetMs: (Int) -> Long = { OnDeviceTimeout.budgetMs(it) },
) = OnDeviceSttAdapter(
    catalog = { VoicePackCatalog("https://example.invalid/release/", entries.toList()) },
    store = VoicePackStore(root),
    engines = engines,
    backends = OnDeviceBackends(listOf(backend)),
    budgetMs = budgetMs,
)

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceSttAdapterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ContextWrapper(null)
    private val root by lazy { tmp.newFolder("voice-models") }

    private fun resolved(adapter: OnDeviceSttAdapter, entry: VoicePackEntry): ResolvedVoiceModel {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.installVoicePack(entry)
        val env = voiceTestEnvironment(catalog, registry = VoiceAdapterRegistry(sttAdapters = listOf(adapter)))
        return env.stt.requireModel(OnDeviceCatalog.ref(entry.id))
    }

    /** 加载要等 [ms](虚拟时间)才开始的调度器:模拟慢机器上加载模型。 */
    private fun TestScope.slowLoad(ms: Long) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            backgroundScope.launch {
                delay(ms)
                block.run()
            }
        }
    }

    private fun TestScope.engines(backend: FakeOnDeviceBackend, loadDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) =
        OnDeviceEngines(OnDeviceBackends(listOf(backend)), backgroundScope, loadDispatcher = loadDispatcher)

    private fun wavOf(seconds: Int) = Wav.encode(ShortArray(Wav.RATE * seconds))

    @Test fun languageMappedFromSpeechTag() = runBlocking {
        val entry = testPackEntry(languages = listOf("zh", "en", "yue"))
        installTestPack(root, entry)
        val backend = FakeOnDeviceBackend()
        val adapter = testOnDeviceAdapter(root, backend, entry)
        val model = resolved(adapter, entry)

        assertEquals("离线识别", adapter.utterance(context, model, "zh-CN")(wavOf(1)))
        adapter.utterance(context, model, "en-US")(wavOf(1))
        adapter.utterance(context, model, "ja-JP")(wavOf(1))
        adapter.utterance(context, model, "zh-Hant-TW")(wavOf(1))

        assertEquals("模型支持的语言就指定;不支持的交给模型自动判断", listOf("zh", "en", null, "zh"), backend.languages)
        assertEquals(SpeechEngineSpec.OnDevice(entry.id), adapter.spec(model))
    }

    @Test fun budgetUsesAudioLength() = runTest {
        val entry = testPackEntry()
        installTestPack(root, entry)
        // 每段识别要 45 秒:10 秒的录音预算 30 秒(下限),不够;20 秒的录音预算 60 秒,够
        val backend = FakeOnDeviceBackend(transcribe = { _, _ -> delay(45_000); "慢" })
        val adapter = testOnDeviceAdapter(root, backend, entry, engines = engines(backend))
        val model = resolved(adapter, entry)

        assertEquals("慢", adapter.utterance(context, model, "zh-CN")(wavOf(20)))
        try {
            adapter.utterance(context, model, "zh-CN")(wavOf(10))
            fail("10 秒录音的预算只有 30 秒")
        } catch (e: VoiceModelError.OnDeviceFailed) {
            assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, e.category)
            assertTrue("超时不是网络错误", generateSequence<Throwable>(e) { it.cause }.none { it is ModelError })
        }
    }

    @Test fun loadTimeNotCountedInBudget() = runTest {
        val entry = testPackEntry()
        installTestPack(root, entry)
        val backend = FakeOnDeviceBackend(transcribe = { _, _ -> delay(1_000); "好了" })
        // 加载 45 秒:超过识别预算的下限 30 秒,但在加载上限之内
        val loadMs = 45_000L
        assertTrue(loadMs > OnDeviceTimeout.MIN_MS && loadMs < OnDeviceTimeout.LOAD_TIMEOUT_MS)
        val adapter = testOnDeviceAdapter(root, backend, entry, engines = engines(backend, slowLoad(loadMs)))
        val model = resolved(adapter, entry)

        val start = currentTime
        assertEquals("好了", adapter.utterance(context, model, "zh-CN")(wavOf(2)))
        assertTrue(currentTime - start >= loadMs)
    }

    @Test fun externalRecorderRefused() = runBlocking {
        val entry = testPackEntry()
        installTestPack(root, entry)
        val adapter = testOnDeviceAdapter(root, FakeOnDeviceBackend(), entry)
        val model = resolved(adapter, entry)
        val feed = object : StreamingRecorder {
            override val chunks: ReceiveChannel<ShortArray> = Channel()
            override val level: StateFlow<Float> = MutableStateFlow(0f)
            override fun start() = true
            override fun stop() = ShortArray(0)
            override fun cancel() {}
        }

        val session = adapter.start(context, model, "zh-CN", SttHooks(recorder = feed))

        assertEquals(
            SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.OnDeviceUnsupported)),
            session.events.first(),
        )
    }

    @Test fun unsupportedAbiThrowsUnsupported() {
        val entry = testPackEntry()
        installTestPack(root, entry)
        val adapter = testOnDeviceAdapter(root, FakeOnDeviceBackend(abi = false), entry)
        val model = resolved(adapter, entry)
        try {
            adapter.spec(model)
            fail("这台设备跑不了")
        } catch (e: VoiceModelError.Unsupported) {
            assertEquals(VoiceCapability.STT, e.requested)
        }
    }

    @Test fun missingPackOrEntryThrowsPackMissing() = runBlocking {
        val entry = testPackEntry()
        val adapter = testOnDeviceAdapter(root, FakeOnDeviceBackend(), entry)
        val model = resolved(adapter, entry)
        try {
            adapter.spec(model)
            fail("没装")
        } catch (e: VoiceModelError.PackMissing) {
            assertEquals(entry.id, e.packId)
            assertEquals(VoiceErrorCategory.INCOMPLETE_CONFIG, e.category)
        }
        // 清单里已经没有这个包
        installTestPack(root, entry)
        val gone = testOnDeviceAdapter(root, FakeOnDeviceBackend())
        try {
            gone.utterance(context, model, "zh-CN")(wavOf(1))
            fail("清单里没有")
        } catch (_: VoiceModelError.PackMissing) {
        }
    }

    @Test fun releasedEngineWhilePackStillInstalledIsNotPackMissing() = runBlocking {
        val entry = testPackEntry()
        installTestPack(root, entry)
        val backend = FakeOnDeviceBackend(transcribe = { _, _ ->
            throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack released")
        })
        val adapter = testOnDeviceAdapter(root, backend, entry)
        val model = resolved(adapter, entry)
        try {
            adapter.utterance(context, model, "zh-CN")(wavOf(1))
            fail()
        } catch (e: VoiceModelError) {
            assertFalse("包还装着,不能说「需要下载」", e is VoiceModelError.PackMissing)
            assertTrue(e is VoiceModelError.OnDeviceFailed)
        }
    }

    @Test fun onDeviceErrorsMapByKind() {
        fun of(kind: OnDeviceSttException.Kind) = VoiceModelError.from(OnDeviceSttException(kind, "x"), "p")
        assertTrue(of(OnDeviceSttException.Kind.Missing) is VoiceModelError.PackMissing)
        for (kind in listOf(OnDeviceSttException.Kind.Corrupt, OnDeviceSttException.Kind.OutOfMemory,
            OnDeviceSttException.Kind.Timeout, OnDeviceSttException.Kind.Unsupported, OnDeviceSttException.Kind.Failed)) {
            val e = of(kind)
            assertTrue(e is VoiceModelError.OnDeviceFailed)
            assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, e.category)
        }
        // 原因链里有服务商错误也不带上:本机失败永远不当成 ModelError.Network
        val wrapped = VoiceModelError.OnDeviceFailed("x", RuntimeException(ModelError.Network()))
        assertTrue(generateSequence<Throwable>(wrapped) { it.cause }.none { it is ModelError })
    }
    /** 真正的原生解码会占住线程、取消不了:到点要立刻换下一个,原生模型等解码结束才关。 */
    @Test fun blockingDecodeIsBoundedByBudgetAndEngineOutlivesIt() = runBlocking {
        val entry = testPackEntry()
        installTestPack(root, entry)
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(1)
        val backend = FakeOnDeviceBackend(transcribe = { _, _ ->
            started.countDown()
            // 线程阻塞,不是挂起:模拟 JNI 解码
            unblock.await(10, TimeUnit.SECONDS)
            "晚了"
        })
        val engines = OnDeviceEngines(OnDeviceBackends(listOf(backend)), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val adapter = testOnDeviceAdapter(root, backend, entry, engines = engines, budgetMs = { 200 })
        val model = resolved(adapter, entry)
        try {
            val begin = System.nanoTime()
            val error = runCatching { adapter.utterance(context, model, "zh-CN")(wavOf(1)) }.exceptionOrNull()
            val tookMs = (System.nanoTime() - begin) / 1_000_000
            assertTrue("要报本机失败,实际 $error", error is VoiceModelError.OnDeviceFailed)
            assertTrue("预算 200 ms,用了 $tookMs ms", tookMs < 2_000)
            assertTrue("本机超时要换下一个", VoiceFailover.canTryNext(error!!))
            assertTrue(started.await(1, TimeUnit.SECONDS))

            // 上一次还卡着:下一句不排队等它,直接报本机失败换下一个
            val next = runCatching { adapter.utterance(context, model, "zh-CN")(wavOf(1)) }.exceptionOrNull()
            assertTrue("实际 $next", next is VoiceModelError.OnDeviceFailed)
            assertTrue(VoiceFailover.canTryNext(next!!))
            assertEquals("第二句没碰原生模型", 1, backend.calls.size)

            // 内存紧张要释放:解码还在用原生模型,不能关
            engines.release()
            assertEquals(0, backend.closes.get())
        } finally {
            unblock.countDown()
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (backend.closes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("解码结束后才关", 1, backend.closes.get())
    }

    @Test fun unexpectedErrorsBecomeOnDeviceFailed() = runBlocking {
        val entry = testPackEntry()
        installTestPack(root, entry)
        // 调用方还在等,却拿到取消(比如加载被取消):算本机失败,不能让会话一直卡着
        val cancelled = FakeOnDeviceBackend(transcribe = { _, _ -> throw java.util.concurrent.CancellationException("load cancelled") })
        val a = testOnDeviceAdapter(root, cancelled, entry)
        val e1 = runCatching { a.utterance(context, resolved(a, entry), "zh-CN")(wavOf(1)) }.exceptionOrNull()
        assertTrue("实际 $e1", e1 is VoiceModelError.OnDeviceFailed)
        assertTrue(VoiceFailover.canTryNext(e1!!))

        // 别的意外异常:也换下一个,不当成协议错误
        val b = testOnDeviceAdapter(root, FakeOnDeviceBackend(), entry, budgetMs = { throw IllegalStateException("boom") })
        val e2 = runCatching { b.utterance(context, resolved(b, entry), "zh-CN")(wavOf(1)) }.exceptionOrNull()
        assertTrue("实际 $e2", e2 is VoiceModelError.OnDeviceFailed)
        assertTrue(VoiceFailover.canTryNext(e2!!))
        assertTrue(generateSequence<Throwable>(e2) { it.cause }.none { it is ModelError })
    }

    @Test fun callerCancellationStillPropagates() = runBlocking {
        val entry = testPackEntry()
        installTestPack(root, entry)
        val started = CompletableDeferred<Unit>()
        val backend = FakeOnDeviceBackend(transcribe = { _, _ -> started.complete(Unit); awaitCancellation() })
        val adapter = testOnDeviceAdapter(root, backend, entry)
        val model = resolved(adapter, entry)
        var thrown: Throwable? = null
        val call = launch(Dispatchers.Default) {
            try {
                adapter.utterance(context, model, "zh-CN")(wavOf(1))
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        started.await()
        call.cancelAndJoin()
        assertTrue("调用方自己取消,不报本机失败,实际 $thrown", thrown is kotlinx.coroutines.CancellationException)
    }
}
