package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.voice.model.FakeVoiceOwnedAccountSource
import com.wangzan101.mika.voice.model.FakeVoiceProviderAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.VoiceAccountResolver
import com.wangzan101.mika.voice.model.VoiceAdapterRegistry
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.adapters.FakeOnDeviceBackend
import com.wangzan101.mika.voice.model.adapters.installTestPack
import com.wangzan101.mika.voice.model.adapters.testPackEntry

class OnDeviceAutoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(id: String, recommendedFor: List<String>) = VoicePackEntry(
        id = id, version = 1, backend = "fake", family = "f", displayName = id,
        languages = listOf("zh", "en"), recommendedFor = recommendedFor,
        files = listOf(VoicePackFile("m.onnx", "model", 3, "00".repeat(32))),
        license = VoicePackLicense("MIT", "LICENSE", "test"), upstream = "https://example.invalid/",
    )

    private val catalog = VoicePackCatalog(
        "https://example.invalid/",
        listOf(entry("sense", listOf("zh-Hans", "en")), entry("whisper", listOf("tr")), entry("dolphin", listOf("vi"))),
    )

    private fun installed(vararg ids: String): (VoicePackEntry) -> Boolean = { it.id in ids }

    @Test fun autoUsesRecommendedPack() {
        // 两个都装了:按识别语言推荐的那个优先,不按清单顺序
        assertEquals("whisper", OnDeviceAuto.pick("tr-TR", AppLanguage.English, catalog, installed("sense", "whisper"))?.id)
        assertEquals("dolphin", OnDeviceAuto.pick("vi-VN", AppLanguage.English, catalog, installed("sense", "dolphin"))?.id)
    }

    @Test fun recommendationFollowsAppLanguageWhenNoSpeechTag() {
        assertEquals("whisper", OnDeviceAuto.pick(null, AppLanguage.Turkish, catalog, installed("sense", "whisper"))?.id)
    }

    @Test fun autoFallsBackToAnyPack() {
        // 推荐的(sense)没装:用清单里第一个装了的
        assertEquals("whisper", OnDeviceAuto.pick("zh-CN", AppLanguage.SimplifiedChinese, catalog, installed("dolphin", "whisper"))?.id)
    }

    @Test fun nothingInstalledPicksNothing() {
        assertNull(OnDeviceAuto.pick("zh-CN", AppLanguage.SimplifiedChinese, catalog, installed()))
    }

    @Test fun installedPackNeedsFilesSupportedDeviceAndRegistration() {
        val root = tmp.newFolder("voice-models")
        val pack = testPackEntry("sense")
        val voiceCatalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        fun env(abi: Boolean) = VoiceModelEnvironment(
            catalog = voiceCatalog,
            accountResolver = VoiceAccountResolver(FakeVoiceProviderAccountSource(), FakeVoiceOwnedAccountSource()),
            registry = VoiceAdapterRegistry(),
            credentials = FakeVoiceOwnedAccountSource(),
            voicePacks = VoicePackStore(root) { Long.MAX_VALUE },
            voicePackCatalog = { VoicePackCatalog("https://example.invalid/", listOf(pack)) },
            voicePackBackends = OnDeviceBackends(listOf(FakeOnDeviceBackend(abi = abi))),
        )

        voiceCatalog.installVoicePack(pack)
        assertNull("files missing", OnDeviceAuto.installedPack(env(abi = true), "zh-CN"))
        installTestPack(root, pack)
        assertEquals("sense", OnDeviceAuto.installedPack(env(abi = true), "zh-CN"))
        assertNull("device cannot run it", OnDeviceAuto.installedPack(env(abi = false), "zh-CN"))
        voiceCatalog.removeVoicePack("sense")
        assertNull("not registered in the voice catalog", OnDeviceAuto.installedPack(env(abi = true), "zh-CN"))
    }

    @Test fun environmentWithoutPacksPicksNothing() {
        val env = VoiceModelEnvironment(
            catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true),
            accountResolver = VoiceAccountResolver(FakeVoiceProviderAccountSource(), FakeVoiceOwnedAccountSource()),
            registry = VoiceAdapterRegistry(),
            credentials = FakeVoiceOwnedAccountSource(),
        )
        assertNull(OnDeviceAuto.installedPack(env, "zh-CN"))
    }
}
