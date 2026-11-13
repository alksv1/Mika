package com.wangzan101.mika.settings

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsPersonalStartTest {
    @Test fun defaultsOff() {
        assertFalse(FeatureFlags(MemoryPrefs()).personalStart())
    }

    @Test fun choiceIsKept() {
        val prefs = MemoryPrefs()
        FeatureFlags(prefs).setPersonalStart(true)
        assertTrue(FeatureFlags(prefs).personalStart())
    }

    /** 设置页拨开关后侧栏入口要跟着变,不能等 App 重开。 */
    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(MemoryPrefs())
        assertFalse(flags.personalStartChanges().first())
        val seen = mutableListOf<Boolean>()
        val job = launch { flags.personalStartChanges().take(2).toList(seen) }
        runCurrent()
        flags.setPersonalStart(true)
        job.join()
        assertEquals(listOf(false, true), seen)
    }
}
