package com.wangzan101.mika.voice.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.model.VoiceAccountResolver
import com.wangzan101.mika.voice.model.VoiceAdapterRegistry
import com.wangzan101.mika.voice.model.VoiceCatalogBackend
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCredentialStore
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.ondevice.EngineOptions
import com.wangzan101.mika.voice.ondevice.InstalledVoicePack
import com.wangzan101.mika.voice.ondevice.OnDeviceBackends
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ondevice.OnDeviceSttBackend
import com.wangzan101.mika.voice.ondevice.OnDeviceSttEngine
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackFile
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller
import com.wangzan101.mika.voice.ondevice.VoicePackLicense
import com.wangzan101.mika.voice.ondevice.VoicePackStatus
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 离线语音包页:卡片随下载走(未下载 → 下载中、读屏读得到进度 → 已安装),按流量计费先问;
 * 选择列表里文件不在的语音包标「需要下载」;没引擎时的说明页给「下载离线语音包」。
 * 不联网:下载由假的安装操作驱动,文件是测试自己写进临时目录的。
 */
@RunWith(AndroidJUnit4::class)
class VoicePacksScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "voice-packs-ui-test").also { it.deleteRecursively() }

    @After fun clean() {
        root.deleteRecursively()
    }

    private val pack = VoicePackEntry(
        id = "test-pack", version = 1, backend = "fake", family = "f", displayName = "测试语音包",
        languages = listOf("zh", "en"), recommendedFor = listOf("zh-Hans"),
        files = listOf(VoicePackFile("m.onnx", "model", 100, "00".repeat(32))),
        license = VoicePackLicense("MIT", "LICENSE", "test"), upstream = "https://example.invalid/",
    )

    /** 假的安装操作:下载只把进度置成「下载中」,之后由测试推。 */
    private class FakeOps(override val packs: List<VoicePackEntry>) : VoicePackOps {
        var preflightResult: VoicePackInstaller.Preflight = VoicePackInstaller.Preflight.Ready
        val statuses = mutableMapOf<String, VoicePackStatus>()
        val flow = MutableStateFlow(DownloadProgress(DownloadPhase.Idle, 0, -1))
        val started = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        override fun runnable(entry: VoicePackEntry) = true
        override fun status(entry: VoicePackEntry) = statuses[entry.id] ?: VoicePackStatus.NotInstalled
        override fun preflight(entry: VoicePackEntry) = preflightResult
        override fun download(entry: VoicePackEntry): Boolean {
            started += entry.id
            flow.value = DownloadProgress(DownloadPhase.Downloading, 0, entry.totalBytes)
            return true
        }
        override fun progress(entry: VoicePackEntry): StateFlow<DownloadProgress> = flow
        override fun cancel(entry: VoicePackEntry) {
            flow.value = DownloadProgress(DownloadPhase.Idle, 0, -1)
        }
        override suspend fun delete(entry: VoicePackEntry) {
            deleted += entry.id
            statuses[entry.id] = VoicePackStatus.NotInstalled
        }
        override suspend fun import(entry: VoicePackEntry, inputs: List<VoicePackInstaller.ImportInput>) =
            VoicePackInstaller.ImportResult.Installed
        override fun downloadUrls(entry: VoicePackEntry) = "https://example.invalid/m.onnx"
    }

    private fun showSection(ops: FakeOps) = compose.setContent {
        MikaTheme {
            val scope = rememberCoroutineScope()
            val vm = androidx.compose.runtime.remember { VoicePacksViewModel(ops, pack, scope, copy = {}) }
            androidx.compose.foundation.layout.Column { VoicePacksSection(vm) }
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun stateContains(text: String) = SemanticsMatcher("stateDescription contains $text") {
        it.config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true
    }

    @Test fun cardFollowsDownloadToInstalledAndTalkBackReadsProgress() {
        val ops = FakeOps(listOf(pack))
        showSection(ops)
        compose.onNodeWithTag("voice-pack-recommended-test-pack").assertExists()
        compose.onNodeWithTag("voice-pack-status-test-pack").assertTextEquals("未下载")

        compose.onNodeWithTag("voice-pack-download-test-pack").performClick()
        assertEquals(listOf("test-pack"), ops.started)
        ops.flow.value = DownloadProgress(DownloadPhase.Downloading, 50, 100)
        waitTag("voice-pack-progress-test-pack")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("voice-pack-progress-test-pack").fetchSemanticsNodes()
                .any { stateContains("50%").matches(it) }
        }
        compose.onNodeWithTag("voice-pack-cancel-test-pack").assertExists()

        ops.statuses["test-pack"] = VoicePackStatus.Installed
        ops.flow.value = DownloadProgress(DownloadPhase.Ready, 100, 100)
        waitTag("voice-pack-delete-test-pack")
        compose.onNodeWithTag("voice-pack-progress-test-pack").assertDoesNotExist()
        compose.onNodeWithText("已安装", substring = true).assertExists()
    }

    /** 装好的包(内容坏了也还是「已安装」):除了删除还能「重新下载」,点了开始下载、不删,选择照留。 */
    @Test fun installedCardOffersDownloadAgainWithoutDeleting() {
        val ops = FakeOps(listOf(pack)).apply { statuses["test-pack"] = VoicePackStatus.Installed }
        showSection(ops)
        compose.onNodeWithTag("voice-pack-delete-test-pack").assertExists()
        compose.onNodeWithTag("voice-pack-redownload-test-pack").assertTextEquals("重新下载")
        compose.onNodeWithTag("voice-pack-redownload-test-pack").performClick()
        assertEquals(listOf("test-pack"), ops.started)
        assertEquals(emptyList<String>(), ops.deleted)
        waitTag("voice-pack-cancel-test-pack")
        // 下载中不再给删除、重新下载;下完回到已安装
        compose.onNodeWithTag("voice-pack-redownload-test-pack").assertDoesNotExist()
        ops.flow.value = DownloadProgress(DownloadPhase.Ready, 100, 100)
        waitTag("voice-pack-redownload-test-pack")
        compose.onNodeWithText("已安装", substring = true).assertExists()
        assertEquals(emptyList<String>(), ops.deleted)
    }

    @Test fun meteredNetworkAsksBeforeDownloading() {
        val ops = FakeOps(listOf(pack)).apply { preflightResult = VoicePackInstaller.Preflight.ConfirmMetered(pack.totalBytes) }
        showSection(ops)
        compose.onNodeWithTag("voice-pack-download-test-pack").performClick()
        compose.onNodeWithText("用流量下载？").assertExists()
        assertEquals(emptyList<String>(), ops.started)
        compose.onNodeWithTag("voice-pack-metered-confirm").performClick()
        assertEquals(listOf("test-pack"), ops.started)
    }

    private class FakeBackend : OnDeviceSttBackend {
        override val id = "fake"
        override fun supports(family: String) = true
        override fun abiSupported() = true
        override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine = error("测试不加载模型")
    }

    private fun environment(): VoiceModelEnvironment {
        val credentials = VoiceCredentialStore.create(context)
        val catalog = VoiceCatalogStore(
            object : VoiceCatalogBackend {
                override fun read(): String? = null
                override fun write(value: String): Boolean = true
            },
            migrated = true,
        )
        return VoiceModelEnvironment(
            catalog = catalog,
            accountResolver = VoiceAccountResolver(ProviderStore.get(context), credentials),
            registry = VoiceAdapterRegistry(),
            credentials = credentials,
            credentialStore = credentials,
            voicePacks = VoicePackStore(root),
            voicePackCatalog = { VoicePackCatalog("https://example.invalid/", listOf(pack)) },
            voicePackBackends = OnDeviceBackends(listOf(FakeBackend())),
        )
    }

    /** 选择里留着本机语音包、文件却被删了(比如从备份恢复):回到页面后那一项标「需要下载」。 */
    @Test fun pickerMarksAMissingPackNeedsDownload() {
        val modelFile = File(root, "test-pack/1/m.onnx").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
        val env = environment()
        env.catalog.installVoicePack(pack)
        var resumed by mutableIntStateOf(0)
        compose.setContent {
            MikaTheme {
                val snapshot by env.catalog.state.collectAsState()
                val option = voiceOptions(VoiceCapability.STT, snapshot, emptyList()).single { OnDeviceCatalog.isOnDevice(it.ref) }
                assertEquals(VoiceSource.OnDevice, option.source)
                val badge = voicePackBadge(env, snapshot, resumed)
                Text(badge(option) ?: "无", Modifier.testTag("badge"))
            }
        }
        compose.onNodeWithTag("badge").assertTextEquals("无")
        modelFile.delete()
        resumed++
        compose.onNodeWithTag("badge").assertTextEquals("需要下载")
    }

    private class UnavailableVoice(val why: SpeechEngineSpec.Why) : com.wangzan101.mika.voice.VoiceSource {
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.Unavailable(why)
        override fun start(language: String?): SpeechSession = error("不可用的引擎不该起会话")
    }

    private fun showSheet(why: SpeechEngineSpec.Why, opened: MutableList<String>) = compose.setContent {
        MikaTheme {
            val scope = rememberCoroutineScope()
            val vh = androidx.compose.runtime.remember { VoiceHold(UnavailableVoice(why), scope).also { it.sheet = VoiceSheet.Unavailable(why) } }
            VoiceSheetHost(vh, onPickSpeech = { opened += "pick" }, onOpenVoicePacks = { opened += "packs" }, voicePacksAvailable = { true })
        }
    }

    @Test fun missingPackSheetLeadsToVoicePacks() {
        val opened = mutableListOf<String>()
        showSheet(SpeechEngineSpec.Why.VoicePackMissing, opened)
        waitTag("voice-sheet")
        compose.onNodeWithText("下载离线语音包").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("packs"), opened)
    }

    @Test fun noEngineSheetAlsoOffersVoicePacks() {
        val opened = mutableListOf<String>()
        showSheet(SpeechEngineSpec.Why.SystemNoResponse, opened)
        waitTag("voice-sheet-voice-packs")
        compose.onNodeWithTag("voice-sheet-voice-packs").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("packs"), opened)
    }
}
