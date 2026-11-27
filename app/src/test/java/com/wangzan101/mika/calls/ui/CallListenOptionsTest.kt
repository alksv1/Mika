package com.wangzan101.mika.calls.ui

import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceSelections
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ui.VoiceSource
import com.wangzan101.mika.voice.ui.voiceOptions
import com.wangzan101.mika.voice.model.VoiceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通话的听写只用云端:手机自带识别和本机语音包都不出现在通话的选择列表里,也不算通话能用的听写。 */
class CallListenOptionsTest {
    private val pack = VoiceModel(OnDeviceCatalog.SERVICE_ID, "sense", "SenseVoice Small", stt = SttBinding(SttProtocol.OnDevice, endpoint = "sense"))
    private val cloud = VoiceModel("stt-doubao", "doubao", "Doubao", stt = SttBinding(SttProtocol.DoubaoStreaming))
    private val snapshot = VoiceCatalogSnapshot(
        services = listOf(
            SttSelectionController.SYSTEM_STT_SERVICE, OnDeviceCatalog.SERVICE,
            VoiceService("stt-doubao", "Doubao", VoiceAccount.VoiceOwned("s")),
        ),
        models = listOf(SttSelectionController.SYSTEM_STT_MODEL, pack, cloud),
    )

    @Test fun callPickersNeverOfferVoicePacks() {
        assertEquals(listOf("doubao"), callListenOptions(snapshot, emptyList()).map { it.ref.modelId })
    }

    @Test fun voicePackSelectionIsNotACallListenModel() {
        assertTrue(VoiceSelections(stt = SttSelection.Model(OnDeviceCatalog.ref("sense"))).listenUnusableForCalls())
        assertFalse(VoiceSelections(stt = SttSelection.Model(cloud.let { com.wangzan101.mika.voice.model.VoiceModelRef(it.serviceId, it.id, VoiceCapability.STT) })).listenUnusableForCalls())
    }

    @Test fun voicePacksAreLabelledOnDeviceOffline() {
        val option = voiceOptions(VoiceCapability.STT, snapshot, emptyList()).single { it.ref.serviceId == OnDeviceCatalog.SERVICE_ID }
        assertEquals(VoiceSource.OnDevice, option.source)
    }
}
