package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.ondevice.OnDeviceLanguage
import com.wangzan101.mika.voice.ondevice.OnDeviceSttException
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackFile
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller
import com.wangzan101.mika.voice.ondevice.VoicePackLicense
import com.wangzan101.mika.voice.ondevice.VoicePackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePacksViewModelTest {

    private fun entry(id: String, recommendedFor: List<String> = emptyList()) = VoicePackEntry(
        id = id, version = 1, backend = "fake", family = "f", displayName = id,
        languages = listOf("zh", "en"), recommendedFor = recommendedFor,
        files = listOf(VoicePackFile("m.onnx", "model", 100, "00".repeat(32))),
        license = VoicePackLicense("MIT", "LICENSE", "test"), upstream = "https://example.invalid/",
    )

    private class FakeOps(
        override val packs: List<VoicePackEntry>,
        var runnableIds: Set<String> = packs.map { it.id }.toSet(),
        var preflightResult: VoicePackInstaller.Preflight = VoicePackInstaller.Preflight.Ready,
    ) : VoicePackOps {
        val statuses = mutableMapOf<String, VoicePackStatus>()
        val flows = mutableMapOf<String, MutableStateFlow<DownloadProgress>>()
        val started = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var importResult: VoicePackInstaller.ImportResult = VoicePackInstaller.ImportResult.Installed

        fun flow(id: String) = flows.getOrPut(id) { MutableStateFlow(DownloadProgress(DownloadPhase.Idle, 0, -1)) }
        override fun runnable(entry: VoicePackEntry) = entry.id in runnableIds
        override fun status(entry: VoicePackEntry) = statuses[entry.id] ?: VoicePackStatus.NotInstalled
        override fun preflight(entry: VoicePackEntry) = preflightResult
        override fun download(entry: VoicePackEntry): Boolean {
            started += entry.id
            flow(entry.id).value = DownloadProgress(DownloadPhase.Downloading, 0, entry.totalBytes)
            return true
        }
        override fun progress(entry: VoicePackEntry): StateFlow<DownloadProgress> = flow(entry.id)
        override fun cancel(entry: VoicePackEntry) {
            flow(entry.id).value = DownloadProgress(DownloadPhase.Idle, 0, -1)
        }
        override suspend fun delete(entry: VoicePackEntry) {
            deleted += entry.id
            statuses[entry.id] = VoicePackStatus.NotInstalled
        }
        override suspend fun import(entry: VoicePackEntry, inputs: List<VoicePackInstaller.ImportInput>): VoicePackInstaller.ImportResult {
            if (importResult == VoicePackInstaller.ImportResult.Installed) statuses[entry.id] = VoicePackStatus.Installed
            return importResult
        }
        override fun downloadUrls(entry: VoicePackEntry) = "https://example.invalid/${entry.id}--m.onnx"
    }

    private fun TestScope.vm(ops: VoicePackOps, recommended: VoicePackEntry? = null, copied: MutableList<String> = mutableListOf()) =
        VoicePacksViewModel(ops, recommended, backgroundScope, copy = { copied += it }, io = UnconfinedTestDispatcher(testScheduler))

    @Test fun recommendedPackFirst() = runTest(UnconfinedTestDispatcher()) {
        val packs = listOf(entry("sense", listOf("zh-Hans")), entry("whisper", listOf("tr")), entry("dolphin", listOf("vi")))
        val recommended = OnDeviceLanguage.recommended(VoicePackCatalog("https://example.invalid/", packs), AppLanguage.Turkish)
        val vm = vm(FakeOps(packs), recommended)
        val cards = vm.cards.value
        assertEquals(listOf("whisper", "sense", "dolphin"), cards.map { it.entry.id })
        assertTrue(cards.first().recommended)
        assertFalse(cards.drop(1).any { it.recommended })
    }

    @Test fun meteredConfirmationFlow() = runTest(UnconfinedTestDispatcher()) {
        val pack = entry("sense")
        val ops = FakeOps(listOf(pack), preflightResult = VoicePackInstaller.Preflight.ConfirmMetered(pack.totalBytes))
        val vm = vm(ops)
        vm.download(pack)
        // 按流量计费:先问,不开始
        assertEquals(VoicePackPrompt.Metered(pack, 100), vm.prompt.value)
        assertTrue(ops.started.isEmpty())
        // 取消:什么都不下
        vm.dismissPrompt()
        assertNull(vm.prompt.value)
        assertTrue(ops.started.isEmpty())
        // 再点、确认:开始下载,卡片上有进度
        vm.download(pack)
        vm.confirmMetered()
        assertNull(vm.prompt.value)
        assertEquals(listOf("sense"), ops.started)
        assertEquals(DownloadPhase.Downloading, vm.cards.value.single().progress?.phase)
        // 取消下载:进度没了
        vm.cancel(pack)
        assertNull(vm.cards.value.single().progress)
    }

    @Test fun noSpaceShowsMessageWithoutStarting() = runTest(UnconfinedTestDispatcher()) {
        val pack = entry("sense")
        val ops = FakeOps(listOf(pack), preflightResult = VoicePackInstaller.Preflight.NoSpace(needed = 500, free = 10))
        val vm = vm(ops)
        vm.download(pack)
        assertEquals(VoicePackNotice.NoSpace(500, 10), vm.notice.value)
        assertNull(vm.prompt.value)
        assertTrue(ops.started.isEmpty())
        assertNull(vm.cards.value.single().progress)
    }

    @Test fun unsupportedDeviceHidesSection() = runTest(UnconfinedTestDispatcher()) {
        val ops = FakeOps(listOf(entry("sense"), entry("whisper")), runnableIds = emptySet())
        val vm = vm(ops)
        assertFalse(vm.visible)
        assertTrue(vm.cards.value.isEmpty())
    }

    /**
     * 装好的包内容坏了(大小对,状态还是 Installed):「重新下载」走同一条下载路,同一版本覆盖装好的那份;
     * 不经过删除(删除会把选择改回「自动」、从备援链里去掉),下好后不用重新选。
     */
    @Test fun redownloadOnInstalledPackDownloadsWithoutDeleting() = runTest(UnconfinedTestDispatcher()) {
        val pack = entry("sense")
        val ops = FakeOps(listOf(pack)).apply { statuses["sense"] = VoicePackStatus.Installed }
        val vm = vm(ops)
        assertEquals(VoicePackStatus.Installed, vm.cards.value.single().status)
        vm.download(pack)
        assertEquals(listOf("sense"), ops.started)
        assertTrue(ops.deleted.isEmpty())
        assertEquals(DownloadPhase.Downloading, vm.cards.value.single().progress?.phase)
        // 下完:还是装好的,一直没删过
        ops.flow("sense").value = DownloadProgress(DownloadPhase.Ready, 100, 100)
        assertEquals(VoicePackStatus.Installed, vm.cards.value.single().status)
        assertNull(vm.cards.value.single().progress)
        assertTrue(ops.deleted.isEmpty())
    }

    @Test fun downloadFinishingRefreshesStatus() = runTest(UnconfinedTestDispatcher()) {
        val pack = entry("sense")
        val ops = FakeOps(listOf(pack))
        val vm = vm(ops)
        vm.download(pack)
        ops.statuses["sense"] = VoicePackStatus.Installed
        ops.flow("sense").value = DownloadProgress(DownloadPhase.Ready, 100, 100)
        val card = vm.cards.value.single()
        assertEquals(VoicePackStatus.Installed, card.status)
        assertNull(card.progress)
    }

    @Test fun importDeleteAndCopyUpdateCardsAndNotices() = runTest(UnconfinedTestDispatcher()) {
        val pack = entry("sense")
        val ops = FakeOps(listOf(pack))
        val copied = mutableListOf<String>()
        val vm = vm(ops, copied = copied)

        ops.importResult = VoicePackInstaller.ImportResult.Missing(listOf("m.onnx"))
        vm.import(pack, emptyList())
        assertEquals(VoicePackNotice.Imported(VoicePackInstaller.ImportResult.Missing(listOf("m.onnx"))), vm.notice.value)
        assertEquals(VoicePackStatus.NotInstalled, vm.cards.value.single().status)

        ops.importResult = VoicePackInstaller.ImportResult.Installed
        vm.import(pack, emptyList())
        assertEquals(VoicePackStatus.Installed, vm.cards.value.single().status)

        vm.delete(pack)
        assertEquals(listOf("sense"), ops.deleted)
        assertEquals(VoicePackStatus.NotInstalled, vm.cards.value.single().status)

        vm.copyUrls(pack)
        assertEquals(listOf("https://example.invalid/sense--m.onnx"), copied)
        assertEquals(VoicePackNotice.Copied, vm.notice.value)
    }

    private var savedTexts: com.wangzan101.mika.i18n.UiTexts? = null

    @Before fun saveTexts() {
        savedTexts = UiText.source
    }

    @After fun restoreTexts() {
        UiText.source = savedTexts
    }

    private fun onDeviceFailure(kind: OnDeviceSttException.Kind) =
        VoiceCopy.failure(SpeechFailure.Provider(VoiceModelError.from(OnDeviceSttException(kind, "native detail"), packId = "p")))

    @Test fun onDeviceFailureShowsCorruptPackCopy() {
        UiText.source = ResFileTexts.chinese
        assertEquals("语音包已损坏，请重新下载", onDeviceFailure(OnDeviceSttException.Kind.Corrupt))
        // 损坏包在别的错误里面(加载失败的包装)也认得出
        val wrapped = VoiceModelError.OnDeviceFailed("On-device recognition failed", RuntimeException(OnDeviceSttException(OnDeviceSttException.Kind.Corrupt, "bad")))
        assertEquals("语音包已损坏，请重新下载", VoiceCopy.failure(SpeechFailure.Provider(wrapped)))
        UiText.source = ResFileTexts.english
        assertFalse(onDeviceFailure(OnDeviceSttException.Kind.Corrupt).contains("native detail"))
        // 本机语音包没装:和开不了会话时同一句
        assertEquals(VoiceCopy.unavailable(SpeechEngineSpec.Why.VoicePackMissing), VoiceCopy.failure(SpeechFailure.Provider(VoiceModelError.PackMissing("x"))))
    }

    /** 超时、内存不足、设备跑不了、解码出错、取消、模型被释放:重新下载没用,只说请重试,也不露英文原因。 */
    @Test fun otherOnDeviceFailuresShowGenericRetryCopy() {
        UiText.source = ResFileTexts.chinese
        for (kind in listOf(OnDeviceSttException.Kind.Timeout, OnDeviceSttException.Kind.OutOfMemory, OnDeviceSttException.Kind.Unsupported, OnDeviceSttException.Kind.Failed)) {
            assertEquals(kind.name, "离线识别失败，请重试", onDeviceFailure(kind))
        }
        val plain = listOf(
            VoiceModelError.OnDeviceFailed("On-device recognition took longer than 30000 ms"),
            VoiceModelError.OnDeviceFailed("On-device recognition was cancelled", kotlinx.coroutines.CancellationException("x")),
            VoiceModelError.OnDeviceFailed("On-device model was released", IllegalStateException("released")),
        )
        plain.forEach { assertEquals(it.detail, "离线识别失败，请重试", VoiceCopy.failure(SpeechFailure.Provider(it))) }
        UiText.source = ResFileTexts.english
        val english = onDeviceFailure(OnDeviceSttException.Kind.Timeout)
        assertFalse(english.contains("native detail"))
        assertFalse(english == VoiceCopy.failure(SpeechFailure.Provider(VoiceModelError.from(OnDeviceSttException(OnDeviceSttException.Kind.Corrupt, "x"), "p"))))
    }

    @Test fun voicePackHintActionOnlyForNoEngineCases() {
        assertTrue(VoiceCopy.offersVoicePacks(SpeechEngineSpec.Why.NoSystemNoModel))
        assertTrue(VoiceCopy.offersVoicePacks(SpeechEngineSpec.Why.SystemNoResponse))
        assertTrue(VoiceCopy.offersVoicePacks(SpeechEngineSpec.Why.VoicePackMissing))
        assertFalse(VoiceCopy.offersVoicePacks(SpeechEngineSpec.Why.Off))
        assertFalse(VoiceCopy.offersVoicePacks(SpeechEngineSpec.Why.OnDeviceUnsupported))
    }
}
