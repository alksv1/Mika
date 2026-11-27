package com.wangzan101.mika.voice.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceTimeoutTest {
    @Test
    fun budgetIsThreeTimesAudioWithFloor() {
        assertEquals(30_000L, OnDeviceTimeout.budgetMs(5 * 16_000))
        assertEquals(60_000L, OnDeviceTimeout.budgetMs(20 * 16_000))
        assertEquals(30_000L, OnDeviceTimeout.budgetMs(0))
        assertEquals(60_000L, OnDeviceTimeout.budgetMs(20 * 8_000, rate = 8_000))
    }
}
