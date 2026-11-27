package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.about.MirrorSettings
import com.wangzan101.mika.about.UpdateMirror
import com.wangzan101.mika.download.VerifiedDownload
import com.wangzan101.mika.voice.model.VoiceCatalogBackend
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 语音包的下载、导入、删除。下载地址照真的拼(镜像、Release、上游)、照真的过白名单,
 * 测试用的 HTTP 客户端把请求改发到 MockWebServer,按「主机 + 路径」回内容。
 */
class VoicePackInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    /** 客户端实际请求的原始地址(改发前)。 */
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    /** 「主机 + 路径」→ 回什么;没有的回 404。 */
    private val routes = Collections.synchronizedMap(mutableMapOf<String, () -> MockResponse>())

    private val root by lazy { File(tmp.root, "voice-models") }
    private var free = 10L * 1024 * 1024 * 1024
    private val store by lazy { VoicePackStore(root, freeSpace = { free }) }
    private val catalogBackend = MemoryBackend()
    private val voiceCatalog = VoiceCatalogStore(catalogBackend)
    private var metered = false
    private var mirrors = MirrorSettings.OFF
    private var abi = true

    private val modelData = "MODELDATA!".toByteArray() // 10
    private val tokensData = "tok\n".toByteArray() // 4
    private val licenseData = "MIT".toByteArray() // 3

    private val http by lazy {
        OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            requested += url.toString()
            chain.proceed(chain.request().newBuilder().url(server.url("/" + url.host + url.encodedPath)).build())
        }.build()
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path!!.removePrefix("/")]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        root.walkTopDown().forEach { it.setWritable(true) }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val commitA = "a".repeat(40)
    private val commitB = "b".repeat(40)

    private fun entry(version: Int = 2) = VoicePackEntry(
        id = "pk", version = version, backend = "fake", family = "f", displayName = "Pack",
        languages = listOf("en"), recommendedFor = listOf("en"),
        files = listOf(
            VoicePackFile("model.onnx", "model", modelData.size.toLong(), sha(modelData)),
            VoicePackFile("tokens.txt", "tokens", tokensData.size.toLong(), sha(tokensData)),
            VoicePackFile(
                "LICENSE", "license", licenseData.size.toLong(), sha(licenseData),
                url = "https://raw.githubusercontent.com/o/r/$commitB/LICENSE",
            ),
        ),
        license = VoicePackLicense("MIT", "LICENSE", "x"),
        upstream = "https://huggingface.co/o/r/resolve/$commitA/",
    )

    private val release = "https://github.com/wangzan101/Mika/releases/download/voice-models-v1/"
    private fun catalog(e: VoicePackEntry = entry()) = VoicePackCatalog(release, listOf(e))

    private fun data(f: VoicePackFile) = when (f.name) {
        "model.onnx" -> modelData
        "tokens.txt" -> tokensData
        else -> licenseData
    }

    /** 每个文件的 Release 地址都回正确内容。 */
    private fun serveRelease(e: VoicePackEntry = entry()) {
        e.files.forEach { f ->
            val path = "github.com/wangzan101/Mika/releases/download/voice-models-v1/${e.id}--${f.name}"
            routes[path] = { MockResponse().setBody(okio.Buffer().write(data(f))) }
        }
    }

    private fun backends() = OnDeviceBackends(listOf(object : OnDeviceSttBackend {
        override val id = "fake"
        override fun supports(family: String) = family == "f"
        override fun abiSupported() = abi
        override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine =
            FakeEngine(pack.entry.version).also { loaded += it }
    }))

    private class FakeEngine(val version: Int) : OnDeviceSttEngine {
        @Volatile var closed = false
        override suspend fun transcribe(pcm: ShortArray, language: String?) = "hi"
        override fun close() { closed = true }
    }

    private val loaded = Collections.synchronizedList(mutableListOf<FakeEngine>())

    private fun installer(
        scope: CoroutineScope,
        e: VoicePackEntry = entry(),
        engines: OnDeviceEngines = OnDeviceEngines(backends(), scope),
    ) = VoicePackInstaller(
        catalog = catalog(e),
        store = store,
        download = VerifiedDownload(http, scope),
        mirrors = { mirrors },
        metered = { metered },
        voiceCatalog = voiceCatalog,
        engines = engines,
        backends = backends(),
    )

    private fun hasModel() = voiceCatalog.state.value.models.any { it.serviceId == OnDeviceCatalog.SERVICE_ID && it.id == "pk" }
    private fun stagingOf(e: VoicePackEntry) = File(root, ".staging/${e.id}-${e.version}")
    private fun versionDir(version: Int) = File(root, "pk/$version")

    /** 按清单内容把 [e] 的文件写进 `pk/<version>`(装好一个旧版本)。 */
    private fun installManually(version: Int) {
        val dir = versionDir(version).apply { mkdirs() }
        entry(version).files.forEach { File(dir, it.name).writeBytes(data(it)) }
    }

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun input(name: String, bytes: ByteArray) = VoicePackInstaller.ImportInput(name) { bytes.inputStream() }

    // ---- 来源 ----

    @Test fun sourcesOrderMirrorsReleaseUpstream() = runTest {
        val e = entry()
        mirrors = MirrorSettings(true, listOf(
            UpdateMirror("1", "One", "https://m1.example.com/"),
            UpdateMirror("2", "Two", "https://m2.example.com/"),
        ))
        val i = installer(this)
        val rel = release + "pk--model.onnx"
        assertEquals(
            listOf("https://m1.example.com/$rel", "https://m2.example.com/$rel", rel, e.upstream + "model.onnx"),
            i.sources(e, e.files[0]),
        )
        // 许可文件的上游是它自己固定提交的原文地址
        assertEquals("https://raw.githubusercontent.com/o/r/$commitB/LICENSE", i.sources(e, e.files[2]).last())
        mirrors = mirrors.copy(enabled = false)
        assertEquals(listOf(rel, e.upstream + "model.onnx"), i.sources(e, e.files[0]))
    }

    @Test fun untrustedMirrorIsDropped() = runTest {
        val e = entry()
        mirrors = MirrorSettings(true, listOf(UpdateMirror("1", "Plain", "http://plain.example.com/")))
        val rel = release + "pk--model.onnx"
        assertEquals(listOf(rel, e.upstream + "model.onnx"), installer(this).sources(e, e.files[0]))
    }

    @Test fun downloadUrlsListsReleaseUrls() = runTest {
        assertEquals(
            listOf("model.onnx", "tokens.txt", "LICENSE").joinToString("\n") { release + "pk--$it" },
            installer(this).downloadUrls(entry()),
        )
    }

    // ---- 下载前检查 ----

    @Test fun preflightUnsupportedDeviceFirst() = runTest {
        abi = false
        free = 0
        metered = true
        assertEquals(VoicePackInstaller.Preflight.UnsupportedDevice, installer(this).preflight(entry()))
    }

    @Test fun preflightNoSpaceBeforeMetered() = runTest {
        val e = entry()
        free = 100
        metered = true
        assertEquals(
            VoicePackInstaller.Preflight.NoSpace(e.totalBytes + VoicePackStore.SPACE_MARGIN, 100),
            installer(this).preflight(e),
        )
    }

    @Test fun preflightMeteredAsksWithSize() = runTest {
        val e = entry()
        metered = true
        assertEquals(VoicePackInstaller.Preflight.ConfirmMetered(e.totalBytes), installer(this).preflight(e))
        metered = false
        assertEquals(VoicePackInstaller.Preflight.Ready, installer(this).preflight(e))
    }

    // ---- 下载 ----

    @Test fun downloadCommitsAndRegistersModel() = runTest {
        val e = entry()
        serveRelease(e)
        val i = installer(this)
        assertTrue(i.download(e))
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
        assertNotNull(store.installed(e))
        assertTrue(hasModel())
        assertFalse(stagingOf(e).exists())
        assertFalse(i.busy(e))
        assertTrue(requested.all { it.startsWith(release) })
    }

    @Test fun releaseFailureFallsBackToUpstream() = runTest {
        val e = entry()
        serveRelease(e)
        routes.remove("github.com/wangzan101/Mika/releases/download/voice-models-v1/pk--model.onnx")
        routes["huggingface.co/o/r/resolve/$commitA/model.onnx"] = { MockResponse().setBody(okio.Buffer().write(modelData)) }
        val i = installer(this)
        i.download(e)
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
        assertTrue(requested.contains(e.upstream + "model.onnx"))
    }

    @Test fun failedDownloadAbandonsStagingAndFreesThePack() = runTest {
        val e = entry()
        val i = installer(this)
        assertTrue(i.download(e))
        advanceUntilIdle()
        assertEquals(DownloadPhase.Failed, i.progress(e).value.phase)
        assertFalse(stagingOf(e).exists())
        assertNull(store.installed(e))
        assertFalse(hasModel())
        assertFalse(i.busy(e))
        // 失败后能重试
        serveRelease(e)
        assertTrue(i.download(e))
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
    }

    /** 改名进安装目录失败(相当于提交时出错):旧版本照用,staging 清掉,目录不变。 */
    @Test fun killedDuringCommitKeepsOldVersion() = runTest {
        installManually(1)
        val e = entry(2)
        serveRelease(e)
        val idDir = File(root, "pk")
        idDir.setWritable(false)
        assumeFalse("needs a non-root user", idDir.canWrite())
        val i = installer(this, e)
        i.download(e)
        advanceUntilIdle()
        idDir.setWritable(true)
        assertEquals(DownloadPhase.Failed, i.progress(e).value.phase)
        assertEquals(versionDir(1), store.installedAny(e)?.dir)
        assertNull(store.installed(e))
        assertFalse(stagingOf(e).exists())
        assertFalse(hasModel())
    }

    /** 新版本已经改名生效、删旧版本时出错:commit 抛错,但包已装好,算成功。 */
    @Test fun commitThrowingAfterInstallCountsAsInstalled() = runTest {
        installManually(1)
        val e = entry(2)
        serveRelease(e)
        versionDir(1).setWritable(false)
        assumeFalse("needs a non-root user", versionDir(1).canWrite())
        val i = installer(this, e)
        i.download(e)
        advanceUntilIdle()
        versionDir(1).setWritable(true)
        assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
        assertEquals(versionDir(2), store.installed(e)?.dir)
        assertTrue(hasModel())
        assertFalse(stagingOf(e).exists())
    }

    // ---- 慢下载:取消、删除、同一个包只有一个操作 ----

    private fun serveSlowModel(e: VoicePackEntry) {
        serveRelease(e)
        routes["github.com/wangzan101/Mika/releases/download/voice-models-v1/pk--model.onnx"] = {
            MockResponse().setBody(okio.Buffer().write(modelData)).throttleBody(1, 1, TimeUnit.SECONDS)
        }
    }

    private fun withRealScope(block: suspend (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking { block(scope) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun cancelAbandonsStaging() = withRealScope { scope ->
        val e = entry()
        serveSlowModel(e)
        val i = installer(scope)
        assertTrue(i.download(e))
        waitFor { i.progress(e).value.doneBytes > 0 }
        i.cancel(e)
        assertEquals(DownloadPhase.Idle, i.progress(e).value.phase)
        waitFor { !i.busy(e) }
        assertFalse(stagingOf(e).exists())
        assertFalse(hasModel())
    }

    @Test fun onePackOneOperationAtATime() = withRealScope { scope ->
        val e = entry()
        serveSlowModel(e)
        val i = installer(scope)
        assertTrue(i.download(e))
        assertTrue(i.busy(e))
        assertFalse("second download of the same pack", i.download(e))
        val zip = zip(*e.files.map { it.name to data(it) }.toTypedArray())
        assertEquals(VoicePackInstaller.ImportResult.Busy, i.import(e, listOf(input("pk.zip", zip))))
        i.cancel(e)
        waitFor { !i.busy(e) }
        assertEquals(VoicePackInstaller.ImportResult.Installed, i.import(e, listOf(input("pk.zip", zip))))
    }

    @Test fun deleteDuringDownloadCancelsAndCleans() = withRealScope { scope ->
        installManually(1)
        voiceCatalog.installVoicePack(entry(1))
        val e = entry(2)
        serveSlowModel(e)
        val i = installer(scope, e)
        assertTrue(i.download(e))
        waitFor { i.progress(e).value.doneBytes > 0 }
        i.delete(e)
        assertEquals(DownloadPhase.Idle, i.progress(e).value.phase)
        assertFalse(i.busy(e))
        assertFalse(stagingOf(e).exists())
        assertFalse(File(root, "pk").exists())
        assertEquals(VoicePackStatus.NotInstalled, store.status(e))
        assertFalse(hasModel())
    }

    // ---- 导入 ----

    @Test fun importZipWithAndWithoutSubdir() = runTest {
        val e = entry()
        val i = installer(this)
        val withDir = zip(*e.files.map { "pk/${it.name}" to data(it) }.toTypedArray())
        assertEquals(VoicePackInstaller.ImportResult.Installed, i.import(e, listOf(input("pk.zip", withDir))))
        assertNotNull(store.installed(e))
        assertTrue(hasModel())

        i.delete(e)
        assertNull(store.installed(e))
        val flat = zip(*e.files.map { it.name to data(it) }.toTypedArray())
        assertEquals(VoicePackInstaller.ImportResult.Installed, i.import(e, listOf(input("whatever.zip", flat))))
        assertNotNull(store.installed(e))
        assertFalse(stagingOf(e).exists())
    }

    /** 分别选的几个文件:清单名,或 Release 附件名(`<包id>--<文件名>`)都认。 */
    @Test fun importSeparateFilesIncludingReleaseNames() = runTest {
        val e = entry()
        val i = installer(this)
        val result = i.import(e, listOf(
            input("model.onnx", modelData),
            input("pk--tokens.txt", tokensData),
            input("pk--LICENSE", licenseData),
        ))
        assertEquals(VoicePackInstaller.ImportResult.Installed, result)
        assertNotNull(store.installed(e))
    }

    @Test fun importReportsMissingAndMismatchByName() = runTest {
        val e = entry()
        val i = installer(this)
        assertEquals(
            VoicePackInstaller.ImportResult.Missing(listOf("tokens.txt")),
            i.import(e, listOf(input("model.onnx", modelData), input("LICENSE", licenseData))),
        )
        assertFalse(stagingOf(e).exists())
        assertEquals(
            VoicePackInstaller.ImportResult.Mismatch("tokens.txt"),
            i.import(e, listOf(input("model.onnx", modelData), input("tokens.txt", "TOK\n".toByteArray()), input("LICENSE", licenseData))),
        )
        assertFalse(stagingOf(e).exists())
        assertNull(store.installed(e))
        assertFalse(hasModel())
        assertFalse(i.busy(e))
    }

    @Test fun importIgnoresExtraFiles() = runTest {
        val e = entry()
        val i = installer(this)
        val z = zip(
            "README.md" to "hi".toByteArray(),
            "pk/" to ByteArray(0),
            *e.files.map { "pk/${it.name}" to data(it) }.toTypedArray(),
            "other.bin" to ByteArray(100),
        )
        assertEquals(VoicePackInstaller.ImportResult.Installed, i.import(e, listOf(input("pk.zip", z), input("notes.txt", "x".toByteArray()))))
        assertEquals(setOf("model.onnx", "tokens.txt", "LICENSE"), store.installed(e)!!.dir.list()!!.toSet())
    }

    /** zip 里带 `..`、绝对路径、别的子目录的条目一律不认,也不往 staging 外面写。 */
    @Test fun zipSlipEntriesAreIgnored() = runTest {
        val e = entry()
        val i = installer(this)
        val bad = "EVIL".toByteArray()
        val z = zip(
            "../model.onnx" to bad,
            "../../evil.txt" to bad,
            "/tokens.txt" to bad,
            "pk/../LICENSE" to bad,
            "other/pk/model.onnx" to bad,
            "pk/sub/model.onnx" to bad,
            "..\\model.onnx" to bad,
            "model.onnx" to modelData,
            "LICENSE" to licenseData,
        )
        assertEquals(VoicePackInstaller.ImportResult.Missing(listOf("tokens.txt")), i.import(e, listOf(input("pk.zip", z))))
        assertFalse(File(root, "model.onnx").exists())
        assertFalse(File(root, ".staging/model.onnx").exists())
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(root, "evil.txt").exists())
    }

    /** 比清单大的条目:写到清单大小就停,报对不上(防 zip 炸弹)。 */
    @Test fun oversizedEntryIsMismatch() = runTest {
        val e = entry()
        val i = installer(this)
        val z = zip(
            "model.onnx" to ByteArray(5_000_000),
            "tokens.txt" to tokensData,
            "LICENSE" to licenseData,
        )
        assertEquals(VoicePackInstaller.ImportResult.Mismatch("model.onnx"), i.import(e, listOf(input("pk.zip", z))))
        assertFalse(stagingOf(e).exists())
        assertEquals(VoicePackInstaller.ImportResult.Mismatch("model.onnx"),
            i.import(e, listOf(input("model.onnx", ByteArray(11)), input("tokens.txt", tokensData), input("LICENSE", licenseData))))
    }

    @Test fun importNeedsSpace() = runTest {
        val e = entry()
        free = 10
        val result = installer(this).import(e, listOf(input("model.onnx", modelData)))
        assertEquals(VoicePackInstaller.ImportResult.NoSpace(e.totalBytes + VoicePackStore.SPACE_MARGIN, 10), result)
    }

    @Test fun importUnreadableZipFailsAndCleans() = runTest {
        val e = entry()
        val i = installer(this)
        val broken = VoicePackInstaller.ImportInput("pk.zip") { throw java.io.IOException("gone") }
        assertTrue(i.import(e, listOf(broken)) is VoicePackInstaller.ImportResult.Failed)
        assertFalse(stagingOf(e).exists())
        assertFalse(i.busy(e))
    }

    // ---- 复审修正 ----

    /** 启动补登记:文件装着、目录里没有模型的包补上;选择不动;已登记、没装的都不写。 */
    @Test fun startupRestoresInstalledPackMissingFromCatalog() {
        installManually(1)
        val e = entry(2)
        val other = e.copy(id = "absent")
        voiceCatalog.setSttSelection(com.wangzan101.mika.voice.model.SttSelection.Off)
        val before = voiceCatalog.state.value.selections
        VoicePackInstaller.restoreInstalled(VoicePackCatalog(release, listOf(e, other)), store, voiceCatalog)
        assertTrue(hasModel())
        assertFalse(voiceCatalog.state.value.models.any { it.id == "absent" })
        assertEquals(before, voiceCatalog.state.value.selections)
        val writes = catalogBackend.writes
        VoicePackInstaller.restoreInstalled(VoicePackCatalog(release, listOf(e, other)), store, voiceCatalog)
        assertEquals("nothing missing: no catalog write", writes, catalogBackend.writes)
    }

    /** 升级装好新版本后,已加载的旧版本模型立刻释放,不和新的同时占内存。 */
    @Test fun upgradeReleasesTheLoadedOldEngine() {
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            installManually(1)
            val e = entry(2)
            serveRelease(e)
            val engines = OnDeviceEngines(backends(), engineScope, idleMs = 3_600_000)
            runBlocking { engines.engine(InstalledVoicePack(entry(1), versionDir(1))) }
            assertFalse(loaded.single().closed)
            runTest {
                val i = installer(this, e, engines)
                i.download(e)
                advanceUntilIdle()
                assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
            }
            waitFor { loaded.single().closed }
        } finally {
            engineScope.cancel()
        }
    }

    /**
     * 装好的包大小对、内容坏了(加载报「语音包已损坏」),卡片上点「重新下载」:同一版本重新下载、提交,
     * 选择和语音目录里的模型照留(不经过删除),已加载的那份模型释放,下次用到加载新文件。
     */
    @Test fun sameVersionRedownloadKeepsSelectionAndReleasesTheEngine() {
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val e = entry(2)
            installManually(2)
            voiceCatalog.installVoicePack(e)
            voiceCatalog.setSttSelection(com.wangzan101.mika.voice.model.SttSelection.Model(OnDeviceCatalog.ref("pk")))
            val before = voiceCatalog.state.value.selections
            // 大小一样、内容不对:store.status 只看大小,还是 Installed
            File(versionDir(2), "model.onnx").writeBytes("XXXXXXXXXX".toByteArray())
            assertEquals(VoicePackStatus.Installed, store.status(e))
            serveRelease(e)
            val engines = OnDeviceEngines(backends(), engineScope, idleMs = 3_600_000)
            runBlocking { engines.engine(InstalledVoicePack(e, versionDir(2))) }
            assertFalse(loaded.single().closed)
            runTest {
                val i = installer(this, e, engines)
                assertTrue(i.download(e))
                advanceUntilIdle()
                assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
            }
            assertTrue(modelData.contentEquals(File(versionDir(2), "model.onnx").readBytes()))
            assertFalse(File(root, "pk/2.previous").exists())
            assertTrue(hasModel())
            assertEquals(before, voiceCatalog.state.value.selections)
            waitFor { loaded.single().closed }
        } finally {
            engineScope.cancel()
        }
    }

    /** 同一版本重新下载、改名失败:旧的那份挪回原处照用,但这是失败,不能当成装好了。 */
    @Test fun sameVersionRedownloadRenameFailureIsFailure() = runTest {
        installManually(2)
        val e = entry(2)
        serveRelease(e)
        val stagingRoot = File(root, ".staging")
        val last = "github.com/wangzan101/Mika/releases/download/voice-models-v1/pk--LICENSE"
        // 最后一个文件开始下时把 .staging 设成只读:文件照样写进 staging 自己的目录,但 staging 改名不出去
        routes[last] = {
            stagingRoot.setWritable(false)
            MockResponse().setBody(okio.Buffer().write(licenseData))
        }
        val i = installer(this, e)
        assertTrue(i.download(e))
        advanceUntilIdle()
        val readOnly = !stagingRoot.canWrite()
        stagingRoot.setWritable(true)
        assumeTrue("needs a non-root user", readOnly)
        assertEquals(DownloadPhase.Failed, i.progress(e).value.phase)
        assertEquals(versionDir(2), store.installed(e)?.dir)
        assertFalse(hasModel())
        assertFalse(i.busy(e))
    }

    /** scope 在任务开跑前就被取消:锁照样放开,staging 不留,之后还能再下。 */
    @Test fun cancelledScopeDoesNotLeakThePackLock() = runTest {
        val e = entry()
        serveRelease(e)
        val dead = CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })
        val i = installer(dead, e, OnDeviceEngines(backends(), this))
        assertTrue(i.download(e))
        assertFalse(i.busy(e))
        assertFalse(stagingOf(e).exists())
        assertTrue(i.download(e))
        assertFalse(i.busy(e))
    }

    /** staging 在下载任务里(后台)建,不在调用 download 的线程上:调用返回时还没建。 */
    @Test fun stagingIsPreparedInsideTheJob() = runTest {
        val e = entry()
        serveRelease(e)
        // 上次残留的文件:要被任务里的 stagingDir 清掉,而不是被当成已校验跳过
        stagingOf(e).mkdirs()
        File(stagingOf(e), "model.onnx").writeBytes(modelData)
        val i = installer(this, e)
        assertTrue(i.download(e))
        assertTrue("stale staging untouched until the job runs", File(stagingOf(e), "model.onnx").exists())
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, i.progress(e).value.phase)
        assertEquals(3, requested.size)
        assertNotNull(store.installed(e))
    }

    private class MemoryBackend : VoiceCatalogBackend {
        private var value: String? = null
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            writes++
            this.value = value
            return true
        }
    }
}
