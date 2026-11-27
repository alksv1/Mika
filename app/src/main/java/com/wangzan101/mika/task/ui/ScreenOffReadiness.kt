package com.wangzan101.mika.task.ui

import com.wangzan101.mika.settings.DeviceAccessConfig

/** 系统给 Mika 的电池策略。受限时闹钟和前台服务都可能被拦,计划到点不一定能跑。 */
internal enum class PlanBatteryPolicy { Restricted, Managed, Unrestricted, Unknown }

/**
 * 锁屏时定时任务里的手机步骤能不能自己解锁(#852 的机制:定时任务本机或远程开关任一开着即可解锁,
 * 本机开关优先;都没开时手机步骤等用户解锁)。
 */
internal enum class PlanUnlockReadiness {
    /** 开关开着;设了锁屏密码的也已存好凭据。 */
    Ready,
    /** 开关开着,但设了锁屏密码却没存凭据:到点会解锁失败,转为等用户解锁。 */
    NeedsCredential,
    /** 上次解锁失败后停用,要用户重新核对凭据。 */
    Blocked,
    /** 两个开关都没开:手机步骤等用户解锁,提醒、网页、沙箱步骤不受影响。 */
    Off,
}

/** 熄屏时计划能不能按时跑的三项条件。只读系统与设置,不改任何东西。 */
internal data class ScreenOffReadiness(
    val exactAlarm: Boolean,
    val battery: PlanBatteryPolicy,
    val unlock: PlanUnlockReadiness,
)

/** 按任务运行时实际走的分流判断:本机开关开着走本机,否则看远程开关(见 RunPhoneStage.assembleGate)。 */
internal fun unlockReadiness(config: DeviceAccessConfig, deviceSecure: Boolean): PlanUnlockReadiness {
    if (!config.localEnabled && !config.enabled) return PlanUnlockReadiness.Off
    val blocked = if (config.localEnabled) config.localBlocked else config.blocked
    return when {
        blocked -> PlanUnlockReadiness.Blocked
        deviceSecure && config.kind == null -> PlanUnlockReadiness.NeedsCredential
        else -> PlanUnlockReadiness.Ready
    }
}
