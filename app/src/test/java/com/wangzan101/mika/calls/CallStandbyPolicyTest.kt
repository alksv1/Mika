package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStandbyPolicyTest {
    @Test fun ordinaryOutgoingRetainsLeaseWhenActivityLosesForegroundAndIncomingIsDisabled() {
        val config = CallConfig(enabled = true, autoAnswer = false)
        assertFalse(CallStandbyPolicy.optedIn(config, userUnlocked = true))
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = false, interactive = false, keyguardShowing = true))
        assertTrue(CallStandbyPolicy.foregroundOutgoing(true, config.enabled, false, CallDirection.OUTGOING))
        assertTrue(CallStandbyPolicy.acceptsStart(false, true, true))
    }

    @Test fun endingWorkOrDisablingFeatureReleasesTemporaryLease() {
        assertTrue(CallStandbyPolicy.foregroundOutgoing(true, true, false, CallDirection.OUTGOING))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, true, false, null))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, false, false, CallDirection.OUTGOING))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(false, true, false, CallDirection.OUTGOING))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, true, false, CallDirection.INCOMING))
        assertTrue(CallStandbyPolicy.foregroundOutgoing(true, true, true, null))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, false, true, null))
    }

    @Test fun systemRestartOrStaleStartCannotReplayTemporaryOutgoingWork() {
        // Even a stale in-memory work reference cannot authorize a system/null-Intent restart.
        assertFalse(CallStandbyPolicy.acceptsStart(false, false, true))
        assertFalse(CallStandbyPolicy.acceptsStart(false, true, false))
        assertFalse(CallStandbyPolicy.acceptsStart(false, false, false))
        assertTrue(CallStandbyPolicy.acceptsStart(true, false, false))
        assertTrue(CallStandbyPolicy.acceptsStart(true, true, false))
    }

    @Test fun visibleActivityBehindKeyguardIsNotAnInteractiveForeground() {
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = true, interactive = true, keyguardShowing = true))
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = true, interactive = false, keyguardShowing = false))
        assertTrue(CallStandbyPolicy.interactiveForeground(visible = true, interactive = true, keyguardShowing = false))
    }

    @Test fun lockedIncomingRequiresActualStandbyAndItsNotificationAfterFirstUnlock() {
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(true, false, false, true))
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(true, false, true, false))
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(false, false, true, true))
        assertTrue(CallStandbyPolicy.allowsAutomaticAnswer(true, false, true, true))
        assertTrue(CallStandbyPolicy.allowsAutomaticAnswer(true, true, false, false))
    }

    @Test fun stickyStandbyRequiresExplicitAutoAnswerAndVerifiedAudio() {
        val enabled = CallConfig(enabled = true, autoAnswer = true, audioVerified = true, credentialsConfigured = true)
        assertTrue(CallStandbyPolicy.optedIn(enabled, userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled, userUnlocked = false))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(autoAnswer = false), userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(audioVerified = false), userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(credentialsConfigured = false), userUnlocked = true))
    }

    @Test fun activeCallWakeLockIsBoundedByConfiguredDurationAndCleanupAllowance() {
        assertEquals(130_000L, CallStandbyPolicy.activeWakeBudgetMs(120))
        assertEquals(610_000L, CallStandbyPolicy.activeWakeBudgetMs(Int.MAX_VALUE))
        // 不限时长(0)按最大档给预算,运行中的看门狗会在锁自然到期后重新拿
        assertEquals(610_000L, CallStandbyPolicy.activeWakeBudgetMs(0))
    }

    @Test fun foregroundRestoreWaitsForTheLateHelperAndDoesNotRetryAfterFailure() {
        val resume = CallStandbyResumeGate()
        assertFalse(resume.take(visible = true, eligible = false))
        assertTrue(resume.take(visible = true, eligible = true))
        assertFalse(resume.take(visible = true, eligible = false))
        assertFalse(resume.take(visible = true, eligible = true))
        assertFalse(resume.take(visible = false, eligible = true))
        assertTrue(resume.take(visible = true, eligible = true))
    }

    private val ready = StandbyStartSnapshot(
        authorized = true, autoAnswerReady = true, notificationsAllowed = true,
        interactiveForeground = true, systemAllowsScheduledStart = true,
    )

    @Test fun bothTriggersShareTheGrantReadinessAndBusyChecks() {
        val blockers = listOf(
            ready.copy(authorized = false), ready.copy(autoAnswerReady = false), ready.copy(notificationsAllowed = false),
            ready.copy(configuring = true), ready.copy(starting = true), ready.copy(running = true),
            ready.copy(callWork = true), ready.copy(lockedDial = true), ready.copy(liveCall = true),
            ready.copy(unobservedManagedCall = true), ready.copy(outgoingForegroundWork = true),
        )
        for (trigger in StandbyTrigger.entries) {
            assertTrue(trigger.name, CallStandbyPolicy.mayRequestIncomingStandby(ready, trigger))
            blockers.forEach { assertFalse("$trigger $it", CallStandbyPolicy.mayRequestIncomingStandby(it, trigger)) }
        }
    }

    @Test fun onlyHowAndroidPermitsTheStartDiffersByTrigger() {
        val hidden = ready.copy(interactiveForeground = false)
        assertFalse(CallStandbyPolicy.mayRequestIncomingStandby(hidden, StandbyTrigger.Visible))
        assertTrue(CallStandbyPolicy.mayRequestIncomingStandby(hidden, StandbyTrigger.Scheduled))

        val noExemption = ready.copy(systemAllowsScheduledStart = false)
        assertTrue(CallStandbyPolicy.mayRequestIncomingStandby(noExemption, StandbyTrigger.Visible))
        assertFalse(CallStandbyPolicy.mayRequestIncomingStandby(noExemption, StandbyTrigger.Scheduled))
    }

    @Test fun permissionLossMakesTheScheduledCheckWaitAndItsReturnRearms() {
        val lost = ready.copy(notificationsAllowed = false, systemAllowsScheduledStart = false)
        assertFalse(CallStandbyPolicy.mayRequestIncomingStandby(lost, StandbyTrigger.Scheduled))
        assertTrue(CallStandbyPolicy.mayRequestIncomingStandby(lost.copy(notificationsAllowed = true,
            systemAllowsScheduledStart = true), StandbyTrigger.Scheduled))
    }

    @Test fun batteryExemptionIsRequiredForAScheduledStartOnlyFromAndroid12() {
        assertTrue(CallStandbyPolicy.systemAllowsScheduledStart(30, backgroundRestricted = false, ignoringBatteryOptimizations = false))
        assertFalse(CallStandbyPolicy.systemAllowsScheduledStart(31, backgroundRestricted = false, ignoringBatteryOptimizations = false))
        assertTrue(CallStandbyPolicy.systemAllowsScheduledStart(31, backgroundRestricted = false, ignoringBatteryOptimizations = true))
        assertFalse(CallStandbyPolicy.systemAllowsScheduledStart(29, backgroundRestricted = true, ignoringBatteryOptimizations = true))
        assertFalse(CallStandbyPolicy.systemAllowsScheduledStart(34, backgroundRestricted = true, ignoringBatteryOptimizations = true))
    }
}
