package com.wangzan101.mika.task.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.device.ui.DeviceAccessSection
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons

/**
 * 「熄屏时运行」:列出计划熄屏、锁屏时按时跑要的三项(精确闹钟、电池策略、锁屏解锁),哪项没好点哪项去处理。
 * 只读状态、跳到系统标准设置页;解锁凭据在带 FLAG_SECURE 的对话框里用 [DeviceAccessSection] 设,和任务运行页同一套。
 * [showAlarm] 为 false 时不列精确闹钟(计划页顶上已有那张提示卡)。
 */
@Composable
internal fun ScreenOffSetupCard(settings: DeviceAccessSettings, showAlarm: Boolean = true) {
    val context = LocalContext.current
    val config by settings.config.collectAsState()
    // 从系统设置回来时重读:刚改的权限、电池策略、锁屏方式要立刻反映出来
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val system = remember(resumes) { readSystem(context) }
    val readiness = ScreenOffReadiness(system.exactAlarm, system.battery, unlockReadiness(config, system.deviceSecure))
    var configuring by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    fun open(vararg intents: Intent) {
        openFailed = !openFirst(context, *intents)
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    GroupLabel(tr(R.string.plans_screen_off_title))
    GroupCard {
        if (showAlarm) {
            ListRow(
                MikaIcons.Alarm, tr(R.string.plans_screen_off_alarm), first = true,
                onClick = { open(TriggerRuntime.exactAlarmSettings(context), appDetails) },
                modifier = Modifier.testTag("plan-screen-off-alarm"),
            ) {
                RowValue(tr(if (readiness.exactAlarm) R.string.state_on else R.string.state_off),
                    if (readiness.exactAlarm) ValueTone.Ok else ValueTone.Warn)
                RowChevron()
            }
        }
        ListRow(
            MikaIcons.Leaf, tr(R.string.plans_screen_off_battery), first = !showAlarm,
            subtitle = if (readiness.battery == PlanBatteryPolicy.Restricted) tr(R.string.plans_screen_off_battery_restricted_sub) else null,
            // 受限要在应用详情的电池页里改;其余去系统标准的「电池优化」列表
            onClick = {
                if (readiness.battery == PlanBatteryPolicy.Restricted) open(appDetails)
                else open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails)
            },
            modifier = Modifier.testTag("plan-screen-off-battery"),
        ) {
            when (readiness.battery) {
                PlanBatteryPolicy.Restricted -> RowValue(tr(R.string.plans_screen_off_battery_restricted), ValueTone.Bad)
                PlanBatteryPolicy.Managed -> RowValue(tr(R.string.plans_screen_off_battery_optimized))
                PlanBatteryPolicy.Unrestricted -> RowValue(tr(R.string.plans_screen_off_battery_unrestricted), ValueTone.Ok)
                PlanBatteryPolicy.Unknown -> Unit
            }
            RowChevron()
        }
        ListRow(
            MikaIcons.Lock, tr(R.string.plans_screen_off_unlock),
            subtitle = tr(when (readiness.unlock) {
                PlanUnlockReadiness.Ready -> R.string.plans_screen_off_ready
                PlanUnlockReadiness.NeedsCredential -> R.string.plans_screen_off_needs_setup
                PlanUnlockReadiness.Blocked -> R.string.plans_screen_off_blocked
                PlanUnlockReadiness.Off -> R.string.plans_screen_off_disabled
            }),
            onClick = { configuring = true },
            modifier = Modifier.testTag("plan-screen-off-unlock"),
        ) {
            when (readiness.unlock) {
                PlanUnlockReadiness.Ready -> RowValue(tr(R.string.state_on), ValueTone.Ok)
                PlanUnlockReadiness.NeedsCredential, PlanUnlockReadiness.Blocked -> RowValue(tr(R.string.plans_screen_off_fix), ValueTone.Warn)
                PlanUnlockReadiness.Off -> RowValue(tr(R.string.state_off))
            }
            RowChevron()
        }
    }
    FooterNote(tr(if (openFailed) R.string.plans_screen_off_open_failed else R.string.plans_screen_off_foot))

    if (configuring) ScreenOffUnlockDialog(settings, onDismiss = { configuring = false })
}

/**
 * 解锁设置对话框。对话框是独立窗口,活动窗口上的 FLAG_SECURE 管不到它:这里自己开 [SecureFlagPolicy.SecureOn],
 * 输入凭据时不进截图、录屏和最近任务缩略图。开关和凭据的保存失败由 [DeviceAccessSection] 自己显示。
 */
@Composable
private fun ScreenOffUnlockDialog(settings: DeviceAccessSettings, onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.plans_screen_off_unlock)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DeviceAccessSection(settings, localTasks = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("plan-screen-off-done")) { Text(tr(R.string.done)) } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

private data class SystemState(val exactAlarm: Boolean, val battery: PlanBatteryPolicy, val deviceSecure: Boolean)

private fun readSystem(context: Context): SystemState {
    val battery = runCatching {
        val activity = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        when {
            activity?.isBackgroundRestricted == true -> PlanBatteryPolicy.Restricted
            power == null -> PlanBatteryPolicy.Unknown
            power.isIgnoringBatteryOptimizations(context.packageName) -> PlanBatteryPolicy.Unrestricted
            else -> PlanBatteryPolicy.Managed
        }
    }.getOrDefault(PlanBatteryPolicy.Unknown)
    // 读不到就当设了锁屏密码:宁可多提示一句去存凭据,也不误报「已就绪」
    val secure = runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure }.getOrNull() ?: true
    return SystemState(TriggerRuntime.canScheduleExactly(context), battery, secure)
}

/** 依次尝试系统标准设置页,打开一个就停;都打不开返回 false。 */
private fun openFirst(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // 这个机型没有这一页,试下一页
        } catch (_: SecurityException) {
            // 个别系统不让第三方打开这一页,试下一页
        }
    }
    return false
}
