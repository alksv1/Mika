package com.wangzan101.mika.voice.cascade

import android.content.ContextWrapper
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.adapters.testPackEntry
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CascadeEngineListenTest {
    @Test fun callsNeverListenWithOnDevice() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.installVoicePack(testPackEntry("sense"))
        val pack = OnDeviceCatalog.ref("sense")
        catalog.setSttSelection(SttSelection.Model(pack))
        catalog.setPhoneVoice(engine = null, stt = pack, tts = null, ttsVoice = "")
        val engine = CascadeEngine(ContextWrapper(null), voiceTestEnvironment(catalog))

        // 第一版通话不用本机语音包(延迟太高),和只用手机自带识别一样得到「没有听写模型」
        assertNull(engine.listenRef(CallUse.InApp))
        assertNull(engine.listenRef(CallUse.Phone))
        assertEquals(StandardBlocker.NoListenModel, engine.blocker(CallUse.InApp))
        assertEquals(StandardBlocker.NoListenModel, engine.blocker(CallUse.Phone))
    }
}
