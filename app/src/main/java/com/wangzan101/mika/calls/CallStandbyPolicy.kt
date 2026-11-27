package com.wangzan101.mika.calls

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal object CallStandbyPolicy {
    /** A temporary lease belongs to work, not to Activity visibility or the incoming opt-in. */
    fun foregroundOutgoing(userUnlocked: Boolean, enabled: Boolean, lockedPending: Boolean, workDirection: CallDirection?): Boolean =
        userUnlocked && enabled && (lockedPending || workDirection == CallDirection.OUTGOING)

    /** Process/system restart may restore incoming standby only; an outgoing request is never replayed. */
    fun acceptsStart(incomingAuthorized: Boolean, explicitRequest: Boolean, outgoingWork: Boolean): Boolean =
        incomingAuthorized || (explicitRequest && outgoingWork)

    fun optedIn(config: CallConfig, userUnlocked: Boolean): Boolean =
        userUnlocked && config.enabled && config.autoAnswer && config.audioVerified && config.credentialsConfigured

    fun interactiveForeground(visible: Boolean, interactive: Boolean, keyguardShowing: Boolean): Boolean =
        visible && interactive && !keyguardShowing

    fun allowsAutomaticAnswer(userUnlocked: Boolean, interactiveForeground: Boolean, serviceRunning: Boolean, notificationsAllowed: Boolean): Boolean =
        userUnlocked && (interactiveForeground || serviceRunning && notificationsAllowed)

    /**
     * The one rule for asking Android to (re)start the owner's incoming standby without a tap on the
     * standby button. Both triggers need the stored grant, a ready auto-answer setup, notifications
     * and nothing else in progress. Only how Android lets the start happen differs: [StandbyTrigger.Visible]
     * needs Mika in the interactive foreground, [StandbyTrigger.Scheduled] needs Android to let a job
     * start a foreground service. Outgoing work is never restarted from here.
     */
    fun mayRequestIncomingStandby(s: StandbyStartSnapshot, trigger: StandbyTrigger): Boolean {
        val common = s.authorized && s.autoAnswerReady && s.notificationsAllowed && !s.configuring &&
            !s.starting && !s.running && !s.callWork && !s.lockedDial && !s.liveCall && !s.unobservedManagedCall &&
            !s.outgoingForegroundWork
        return common && when (trigger) {
            StandbyTrigger.Visible -> s.interactiveForeground
            StandbyTrigger.Scheduled -> s.systemAllowsScheduledStart
        }
    }

    /**
     * Without a battery-optimisation exemption Android 12+ refuses a foreground-service start from a
     * job; earlier versions only need the app not to be background-restricted.
     */
    fun systemAllowsScheduledStart(sdk: Int, backgroundRestricted: Boolean, ignoringBatteryOptimizations: Boolean): Boolean =
        !backgroundRestricted && (sdk < 31 || ignoringBatteryOptimizations)

    /** 不限时长(0)按最大档给预算;锁自然到期后由运行中的看门狗重新拿。 */
    fun activeWakeBudgetMs(maxCallSeconds: Int): Long =
        (if (maxCallSeconds <= 0) CallPolicy.MAX_SECONDS else maxCallSeconds.coerceIn(15, CallPolicy.MAX_SECONDS)) * 1_000L + 10_000L
}

/** A failed automatic restore must not spin; user retry is always available. */
internal class CallStandbyResumeGate {
    private var attemptedWhileVisible = false

    fun take(visible: Boolean, eligible: Boolean): Boolean {
        if (!visible) attemptedWhileVisible = false
        if (!visible || !eligible || attemptedWhileVisible) return false
        attemptedWhileVisible = true
        return true
    }
}

/** Waits for this existing service request only; never starts or retries an Android service. */
internal suspend fun awaitCallForegroundReady(isStarting: () -> Boolean, isReady: () -> Boolean): Boolean =
    withTimeoutOrNull(5_250L) {
        while (isStarting()) delay(25L)
        isReady()
    } ?: false

internal enum class StandbyTrigger { Visible, Scheduled }

/** What [CallStandbyPolicy.mayRequestIncomingStandby] needs to know, read from the runtime at one moment. */
internal data class StandbyStartSnapshot(
    val authorized: Boolean,
    val autoAnswerReady: Boolean,
    val notificationsAllowed: Boolean,
    val configuring: Boolean = false,
    val starting: Boolean = false,
    val running: Boolean = false,
    val callWork: Boolean = false,
    val lockedDial: Boolean = false,
    val liveCall: Boolean = false,
    val unobservedManagedCall: Boolean = false,
    val outgoingForegroundWork: Boolean = false,
    val interactiveForeground: Boolean = false,
    val systemAllowsScheduledStart: Boolean = false,
)
