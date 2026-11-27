package com.wangzan101.mika.task

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.a11y.MikaAccessibilityService
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PermissionCommands
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RepairPhase
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.MikaPause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「暂停 Mika」:用户亲手让 Mika 整个停下,直到亲手恢复。
 *
 * 暂停做的事只有这些,界面上的说法也只承诺这些:
 * - 写下持久的暂停标记([MikaPause]),之后任务服务、定时、渠道、电脑交来的任务、工作流、自动修复都按它挡住新工作;
 * - 停下在跑和排着的任务,持久排队清空;
 * - 停下内置助手和配对监听,看门狗不再把它起回来;
 * - 关掉 Mika 的无障碍服务和无线调试。
 * 配对钥匙、端口缓存和「已配对」标记一律不动:暂停不是解除配对,[BuiltinShellControl.unpair] 在这里绝不能调。
 *
 * 有两件事 App 不一定做得了,恢复时做不成的要列成步骤交给用户:
 * - 无障碍服务:App 有修改系统设置的授权、或助手重连上时,自动修复([com.wangzan101.mika.core.device.shell.AccessibilityRepair])
 *   会把它打开;没有这两条路(或修了没连上)时,安卓只让用户在系统设置里打开。所以恢复后先等修的那一边有个结果
 *   ([awaitAccessibilityRepair]),再决定要不要列这一步;
 * - 无线调试的开关只有拿到修改系统设置的授权时才写得进去,写不进去就得用户去开发者选项里打开。
 *
 * 暂停标记的写盘([MikaPause.set] 同步落盘)放在 IO 线程,不卡主线程;其余步骤在主线程做。
 *
 * 由 PR #804(Vagif Novruzov)的「Banking mode」改写而来。
 */
class MikaPauseControl private constructor(
    private val app: Context,
    private val pause: MikaPause,
    private val shell: BuiltinShellControl,
    private val canWriteSecureSettings: () -> Boolean,
) {
    companion object {
        private const val HANDOFF_MS = 1_500L
        /** 助手重连(连接中)加上自动修复最坏的情形,留足余量;过了就照实列出步骤。 */
        private const val REPAIR_WAIT_MS = 60_000L

        fun create(context: Context): MikaPauseControl {
            val app = context.applicationContext
            return MikaPauseControl(app, MikaPause.get(app), PhoneServices.of(app).shellControl) {
                app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /** 此刻的真实状态:无障碍、无线调试、助手都直接读系统,不读缓存。 */
    fun snapshot(): PauseState {
        val instanceRunning = ScreenAccessibility.instance.value != null
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
        val me = ComponentName(app, MikaAccessibilityService::class.java)
        val accessibility = when {
            instanceRunning -> AccessibilityState.On
            me in enabled -> AccessibilityState.EnabledNotRunning
            else -> AccessibilityState.Off
        }
        return PauseState(
            paused = pause.paused(),
            accessibility = accessibility,
            wirelessDebugging = DeveloperSettings.wirelessDebugging(app.contentResolver),
            helper = shell.status.value,
            helperPaired = shell.paired,
        )
    }

    /**
     * 暂停:先把标记写进磁盘(写不进去就什么都不动,返回 null),再停任务、停助手、关无障碍、关无线调试。
     * 标记先行,所以之后每个异步交接点重新读到的都是「暂停」,中途出错也保持暂停。
     * 在主线程调用([ScreenAccessibility.disableSelf] 是无障碍服务的方法);写盘那一下切到 IO 线程。
     * 一旦开始就做完(不随界面离开而取消),不会出现标记写了、后面的步骤没做的半截状态。
     */
    suspend fun pause(): PauseResult? = withContext(NonCancellable) { pauseNow() }

    private suspend fun pauseNow(): PauseResult? {
        val tasksRunning = (app as? MikaApp)?.taskState?.runs?.value?.isNotEmpty() ?: false
        if (!withContext(Dispatchers.IO) { pause.set(true) }) return null
        val before = snapshot()
        TaskService.pauseAll(app)
        (app as? MikaApp)?.workflows?.haltForPause()
        // 停助手和配对监听:看门狗从这一刻起不再重起它;配对钥匙和端口缓存不动。
        shell.pauseChanged()
        runCatching { PhoneServices.of(app).pairing.end() }
        // 无障碍:服务在跑时能自己关掉自己;在列表里却没在跑时只能由用户去系统设置里关。
        var a11yOff = before.accessibility == AccessibilityState.Off
        var a11yNeedsManual = false
        if (!a11yOff) {
            val instance = ScreenAccessibility.instance.value
            if (instance != null) {
                a11yOff = runCatching { instance.disableSelf() }.isSuccess
                a11yNeedsManual = !a11yOff
            } else {
                a11yNeedsManual = true
            }
        }
        // 无线调试:只关开关,不解除配对;写不进去时提示用户去开发者选项里亲手关。
        var wirelessOff = !before.wirelessDebugging
        var wirelessNeedsManual = false
        if (!wirelessOff) {
            wirelessOff = canWriteSecureSettings() &&
                DeveloperSettings.turnOffWirelessDebugging(app.contentResolver) &&
                !DeveloperSettings.wirelessDebugging(app.contentResolver)
            wirelessNeedsManual = !wirelessOff
        }
        return PauseResult(
            tasksStopped = tasksRunning,
            accessibilityOff = a11yOff,
            accessibilityNeedsManual = a11yNeedsManual,
            wirelessOff = wirelessOff,
            wirelessNeedsManual = wirelessNeedsManual,
            helperPaused = shell.status.value == BuiltinShellStatus.Paused,
        )
    }

    /**
     * 恢复:先清掉标记(写不进去返回 null,保持暂停),已配对时把无线调试打开(写得进去时),再让助手按已配对的连接重连。
     * 定时重新排一次闹钟(暂停期间到期的窗口照常记跳过、推进到下一次),不补跑。
     * 暂停期间被跳过的定时、被回绝的消息和任务都不补跑。配对丢了才需要重新配对,平时绝不走那一步。
     * 返回时无障碍可能还在自动修复中:要等它有结果再列步骤的,接着调 [awaitAccessibilityRepair]。
     * 在主线程调用;写盘、写开关和排闹钟切到 IO 线程。和 [pause] 一样一旦开始就做完。
     */
    suspend fun resume(): ResumeResult? = withContext(NonCancellable) { resumeNow() }

    private suspend fun resumeNow(): ResumeResult? {
        if (!withContext(Dispatchers.IO) { pause.set(false) }) return null
        val before = snapshot()
        if (!before.wirelessDebugging && before.helperPaired && canWriteSecureSettings()) {
            withContext(Dispatchers.IO) { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) }
        }
        shell.pauseChanged()
        withContext(Dispatchers.IO) { runCatching { TriggerRuntime.rearm(app) } }
        // 再读一次系统:写开关可能被系统在不允许的 Wi-Fi 上撤回,以实测为准。
        return ResumeResult(planResumeSteps(snapshot()))
    }

    /** 自动修复此刻能不能替用户把无障碍打开:用户没关「替我打开无障碍」,且 App 能直接写系统设置或助手配过对、在跑。 */
    private fun accessibilityRepairPossible(): Boolean {
        val phone = PhoneServices.of(app)
        if (!phone.recoverySettings.consent.value.wants(RecoveryCapability.Accessibility)) return false
        return canWriteSecureSettings() || shell.paired || shell.status.value == BuiltinShellStatus.Running
    }

    /**
     * 恢复后等无障碍的自动修复有个结果:服务连上了,或修的那一边停下(放弃、没路可修、助手没连上),最多 [REPAIR_WAIT_MS]。
     * 没有可修的路时立刻返回。暂停标记刚清时修的那一边要过一会儿才接手,所以先留 [HANDOFF_MS] 给它。
     */
    suspend fun awaitAccessibilityRepair() {
        if (ScreenAccessibility.instance.value != null || !accessibilityRepairPossible()) return
        val repair = PhoneServices.of(app).recovery.accessibilityRepair
        val handedOff = flow { emit(false); delay(HANDOFF_MS); emit(true) }
        withTimeoutOrNull(REPAIR_WAIT_MS) {
            combine(ScreenAccessibility.instance, shell.status, repair.phase, handedOff) { service, helper, phase, waited ->
                service != null || (waited && phase == RepairPhase.Idle && helper != BuiltinShellStatus.Connecting)
            }.first { it }
        }
    }

}

/** 暂停相关的真实状态。 */
data class PauseState(
    val paused: Boolean,
    val accessibility: AccessibilityState,
    val wirelessDebugging: Boolean,
    val helper: BuiltinShellStatus,
    val helperPaired: Boolean,
)

/** 暂停一次做完之后,哪些关掉了、哪些还要用户亲手做。 */
data class PauseResult(
    val tasksStopped: Boolean,
    val accessibilityOff: Boolean,
    val accessibilityNeedsManual: Boolean,
    val wirelessOff: Boolean,
    val wirelessNeedsManual: Boolean,
    val helperPaused: Boolean,
) {
    /** 说好要关的都关掉了。 */
    val complete: Boolean get() = accessibilityOff && wirelessOff && helperPaused
}

/** 恢复一次做完之后,还有哪些步骤要用户亲手做。 */
data class ResumeResult(val steps: List<ResumeStep>)

/** 恢复时 App 自己做不成、必须用户亲手做的步骤。 */
enum class ResumeStep {
    /** 去系统设置里打开 Mika 的无障碍服务:自动修复没路可走或修了没连上时才需要。 */
    OpenAccessibilitySettings,
    /** 去开发者选项里打开无线调试:App 写不进去开关时才需要。 */
    OpenWirelessDebuggingPage,
    /** 配对已经丢了:重新走一遍配对流程。平时不出现。 */
    RePairHelper,
}

/** 纯函数:按恢复后的真实状态,算出还有哪些步骤要用户亲手做。无线调试只在助手靠它的配对连接时才要求打开;经 Shizuku 或 root 起的助手不提配对。 */
fun planResumeSteps(state: PauseState): List<ResumeStep> = buildList {
    if (state.accessibility != AccessibilityState.On) add(ResumeStep.OpenAccessibilitySettings)
    if (state.helperPaired && !state.wirelessDebugging) add(ResumeStep.OpenWirelessDebuggingPage)
    if (state.helper == BuiltinShellStatus.NotPaired) add(ResumeStep.RePairHelper)
}
