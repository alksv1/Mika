package com.wangzan101.mika.task.ui

import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.settings.DeviceAccessConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOffReadinessTest {
    @Test
    fun bothSwitchesOffMeansPhoneStepsWaitForTheUser() {
        assertEquals(PlanUnlockReadiness.Off, unlockReadiness(DeviceAccessConfig(kind = CredentialKind.Pin), deviceSecure = true))
        assertEquals(PlanUnlockReadiness.Off, unlockReadiness(DeviceAccessConfig(), deviceSecure = false))
    }

    @Test
    fun eitherSwitchAuthorizesScheduledUnlockLikeTheRunDoes() {
        val pin = CredentialKind.Pin
        assertEquals(PlanUnlockReadiness.Ready, unlockReadiness(DeviceAccessConfig(localEnabled = true, kind = pin), deviceSecure = true))
        // 定时任务远程开关也算授权(RunPhoneStage 的 taskUnlockAuthorization)
        assertEquals(PlanUnlockReadiness.Ready, unlockReadiness(DeviceAccessConfig(enabled = true, kind = pin), deviceSecure = true))
    }

    @Test
    fun secureLockWithoutASavedCredentialNeedsSetupButASwipeLockDoesNot() {
        assertEquals(PlanUnlockReadiness.NeedsCredential, unlockReadiness(DeviceAccessConfig(localEnabled = true), deviceSecure = true))
        assertEquals(PlanUnlockReadiness.Ready, unlockReadiness(DeviceAccessConfig(localEnabled = true), deviceSecure = false))
    }

    @Test
    fun blockedFlagFollowsThePathTheRunWillTake() {
        val pin = CredentialKind.Pin
        // 本机开关开着走本机分流,只看本机的停用标记
        assertEquals(PlanUnlockReadiness.Blocked,
            unlockReadiness(DeviceAccessConfig(localEnabled = true, kind = pin, localBlocked = true), deviceSecure = true))
        assertEquals(PlanUnlockReadiness.Ready,
            unlockReadiness(DeviceAccessConfig(localEnabled = true, enabled = true, kind = pin, blocked = true), deviceSecure = true))
        // 只开远程开关时看远程的停用标记
        assertEquals(PlanUnlockReadiness.Blocked,
            unlockReadiness(DeviceAccessConfig(enabled = true, kind = pin, blocked = true), deviceSecure = true))
    }
}
