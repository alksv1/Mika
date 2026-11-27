package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.task.PauseState
import com.wangzan101.mika.task.ResumeStep
import com.wangzan101.mika.task.planResumeSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「暂停 Mika」:持久标记是唯一的真相来源,写盘成功才算数;恢复步骤按真实状态算。
 * 暂停/恢复本身要读系统(无障碍列表、无线调试开关),不在 JVM 单测里跑;助手的投影由 core 的
 * BuiltinShellEnginePauseTest 覆盖。
 */
class MikaPauseTest {
    @Test
    fun setWritesToDiskBeforeTheValueChanges() {
        val disk = mutableListOf<Boolean>()
        val pause = MikaPause(false) { disk += it; true }
        assertTrue(pause.set(true))
        assertTrue(pause.paused())
        assertTrue(pause.state.value)
        assertEquals(listOf(true), disk)
        // 同一个值不再写盘。
        assertTrue(pause.set(true))
        assertEquals(listOf(true), disk)
    }

    @Test
    fun failedWriteLeavesTheFlagUnchanged() {
        val pause = MikaPause(false) { false }
        assertFalse(pause.set(true))
        assertFalse(pause.paused())
        val paused = MikaPause(true) { false }
        assertFalse(paused.set(false))
        assertTrue(paused.paused())
    }

    @Test
    fun restartReadsThePersistedFlag() {
        var disk = false
        MikaPause(disk) { disk = it; true }.set(true)
        assertTrue(MikaPause(disk) { disk = it; true }.paused())
    }

    private fun state(
        accessibility: AccessibilityState = AccessibilityState.On,
        wirelessDebugging: Boolean = true,
        helper: BuiltinShellStatus = BuiltinShellStatus.Running,
        helperPaired: Boolean = true,
    ) = PauseState(
        paused = false,
        accessibility = accessibility,
        wirelessDebugging = wirelessDebugging,
        helper = helper,
        helperPaired = helperPaired,
    )

    @Test
    fun everythingBackOnNeedsNoManualStep() {
        assertTrue(planResumeSteps(state()).isEmpty())
    }

    @Test
    fun accessibilityOffNeedsTheSettingsStep() {
        assertEquals(listOf(ResumeStep.OpenAccessibilitySettings), planResumeSteps(state(accessibility = AccessibilityState.Off)))
        // 服务在列表里却没在跑也一样:用户得去系统设置里亲手打开。
        assertEquals(
            listOf(ResumeStep.OpenAccessibilitySettings),
            planResumeSteps(state(accessibility = AccessibilityState.EnabledNotRunning)),
        )
    }

    @Test
    fun wirelessOffNeedsTheDeveloperOptionsStepOnlyForAPairedHelper() {
        assertEquals(listOf(ResumeStep.OpenWirelessDebuggingPage), planResumeSteps(state(wirelessDebugging = false)))
        // 经 Shizuku 或 root 起助手、从没配对过无线调试:不要求打开它。
        assertTrue(planResumeSteps(state(wirelessDebugging = false, helperPaired = false)).isEmpty())
    }

    @Test
    fun lostPairingNeedsRePairing() {
        assertEquals(
            listOf(ResumeStep.RePairHelper),
            planResumeSteps(state(helper = BuiltinShellStatus.NotPaired, helperPaired = false)),
        )
    }

    @Test
    fun pausedHelperWithSavedPairingNeedsNoRePairing() {
        // 暂停后恢复:助手还是 Paused、配对还在,不用重新配对。
        assertTrue(planResumeSteps(state(helper = BuiltinShellStatus.Paused)).isEmpty())
    }
}
