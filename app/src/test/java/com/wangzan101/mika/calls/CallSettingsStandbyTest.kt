package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSettingsStandbyTest {
    @Test fun disablingAndReenablingAutoAnswerDoesNotRestoreAnOldBackgroundGrant() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("enabled", true).putBoolean("auto_answer", true)
            .putBoolean(CallSettings.BACKGROUND_AUTHORIZED, true).commit()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(enabled = true, autoAnswer = false))
        assertFalse(prefs.getBoolean(CallSettings.BACKGROUND_AUTHORIZED, true))

        val reenabled = settings.save(settings.state.value.copy(
            audioVerified = true, autoAnswer = true, backgroundStandbyAuthorized = true,
        ))
        assertTrue(reenabled.autoAnswer)
        assertFalse(reenabled.backgroundStandbyAuthorized)
        assertFalse(prefs.getBoolean(CallSettings.BACKGROUND_AUTHORIZED, true))
        assertFalse(CallSettings(prefs).state.value.backgroundStandbyAuthorized)
    }

    @Test fun theStoredGrantSurvivesAReloadAndIsClearedOnlyByTurningStandbyOff() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("enabled", true).putBoolean("auto_answer", true)
            .putBoolean(CallSettings.BACKGROUND_AUTHORIZED, true).commit()
        // A new process (reboot, sticky restart, periodic check) reads the grant straight from storage.
        assertTrue(CallSettings.hasStoredStandbyAuthorization(prefs))
        CallSettings(prefs)
        assertTrue(CallSettings.hasStoredStandbyAuthorization(prefs))

        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(enabled = true, autoAnswer = false))
        assertFalse(CallSettings.hasStoredStandbyAuthorization(prefs))
    }
}
