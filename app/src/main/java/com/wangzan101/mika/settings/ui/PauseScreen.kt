package com.wangzan101.mika.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.MikaPause
import com.wangzan101.mika.task.MikaPauseControl
import com.wangzan101.mika.task.PauseResult
import com.wangzan101.mika.task.ResumeStep
import com.wangzan101.mika.task.planResumeSteps
import kotlinx.coroutines.launch
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 「暂停 Mika」页:一键停下 Mika 的全部工作,再一键恢复。
 *
 * 暂停停下任务、挡住新任务和定时、关掉 Mika 的无障碍服务和无线调试、停下内置助手;无线调试的配对留着,
 * 恢复时直接重连,不用新的配对码。无障碍服务能自动修复时先等它修完;修不成才把它列成一步交给用户。
 * 每当暂停标记、助手状态或无障碍实例变了都重读系统,界面永远是实测,不是上次记的。
 */
@Composable
fun PauseScreen(onBack: () -> Unit, onFix: (Fix) -> Unit) {
    val context = LocalContext.current
    val control = remember { MikaPauseControl.create(context) }
    val phone = remember { PhoneServices.of(context) }
    val paused by MikaPause.get(context).state.collectAsState()
    val shellStatus by phone.shellControl.status.collectAsState()
    val a11yInstance by ScreenAccessibility.instance.collectAsState()
    var state by remember { mutableStateOf(control.snapshot()) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 点了恢复、无障碍还在等自动修复的结果:这期间不把「去系统设置打开」列成一步
    var a11yRestoring by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(paused, shellStatus, a11yInstance) { state = control.snapshot() }

    val helperText = when (state.helper) {
        BuiltinShellStatus.Running -> stringResource(R.string.pause_helper_running)
        BuiltinShellStatus.Paused -> stringResource(R.string.pause_helper_paused)
        BuiltinShellStatus.Connecting -> stringResource(R.string.pause_helper_connecting)
        else -> stringResource(R.string.pause_helper_not_running)
    }
    val helperTone = when (state.helper) {
        BuiltinShellStatus.Running -> ValueTone.Ok
        BuiltinShellStatus.Paused -> ValueTone.Plain
        else -> ValueTone.Bad
    }
    val wirelessText = stringResource(if (state.wirelessDebugging) R.string.state_on else R.string.state_off)
    val (a11yText, a11yTone) = StatusCopy.accessibilityValue(state.accessibility)
    // 没暂停时,按真实状态算出恢复后还剩哪些步骤要用户亲手做。
    val resumeSteps = if (state.paused) emptyList() else planResumeSteps(state).let { steps ->
        if (a11yRestoring) steps - ResumeStep.OpenAccessibilitySettings else steps
    }

    PageScaffold(stringResource(R.string.pause_title), onBack) {
        StateCard(
            MikaIcons.Power,
            stringResource(if (state.paused) R.string.pause_paused_title else R.string.pause_active_title),
            stringResource(if (state.paused) R.string.pause_paused_body else R.string.pause_active_body),
            if (state.paused) TileTone.Ok else TileTone.Warn,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.pause_explain),
            style = MikaType.Caption, color = MikaTheme.colors.ink2,
        )
        Spacer(Modifier.height(12.dp))

        GroupLabel(stringResource(R.string.pause_status_label))
        GroupCard {
            ListRow(
                MikaIcons.Shield, stringResource(R.string.pause_a11y_label), null, first = true,
            ) { RowValue(a11yText, a11yTone) }
            ListRow(
                MikaIcons.Wrench, stringResource(R.string.pause_wireless_label), null,
            ) { RowValue(wirelessText, if (state.paused && state.wirelessDebugging) ValueTone.Bad else ValueTone.Plain) }
            ListRow(
                MikaIcons.Phone, stringResource(R.string.pause_helper_label), null,
            ) { RowValue(helperText, helperTone) }
        }
        Spacer(Modifier.height(12.dp))

        GroupCard {
            if (state.paused) {
                ListRow(
                    MikaIcons.Power, stringResource(R.string.pause_resume),
                    stringResource(R.string.pause_resume_sub), first = true,
                    onClick = click@{
                        if (busy) return@click
                        busy = true
                        scope.launch {
                            try {
                                val result = control.resume()
                                state = control.snapshot()
                                if (result == null) {
                                    notice = context.getString(R.string.pause_save_failed)
                                    return@launch
                                }
                                notice = null
                                if (ResumeStep.OpenAccessibilitySettings in result.steps) {
                                    a11yRestoring = true
                                    try { control.awaitAccessibilityRepair() } finally { a11yRestoring = false }
                                    state = control.snapshot()
                                }
                                if (planResumeSteps(state).isEmpty()) notice = context.getString(R.string.pause_resumed)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { }
            } else {
                ListRow(
                    MikaIcons.Power, stringResource(R.string.pause_action),
                    stringResource(R.string.pause_action_sub), first = true,
                    onClick = click@{
                        if (busy) return@click
                        busy = true
                        scope.launch {
                            try {
                                val result = control.pause()
                                state = control.snapshot()
                                notice = result?.let { pauseNotice(context, it) }
                                    ?: context.getString(R.string.pause_save_failed)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { }
            }
        }

        if (!state.paused && a11yRestoring) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pause_a11y_restoring),
                style = MikaType.Caption, color = MikaTheme.colors.ink2,
            )
        }

        // 恢复后还剩的、App 自己做不成的步骤:每一步都是一个直达系统设置的按钮。
        if (!state.paused && resumeSteps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pause_resume_note),
                style = MikaType.Caption, color = MikaTheme.colors.ink2,
            )
            Spacer(Modifier.height(12.dp))
            GroupCard {
                resumeSteps.forEachIndexed { index, step ->
                    val (icon, label, fix) = when (step) {
                        ResumeStep.OpenAccessibilitySettings ->
                            Triple(MikaIcons.Shield, stringResource(R.string.pause_step_a11y), Fix.OpenAccessibility)
                        ResumeStep.OpenWirelessDebuggingPage ->
                            Triple(MikaIcons.Wrench, stringResource(R.string.pause_step_wireless), Fix.OpenWirelessDebugging)
                        ResumeStep.RePairHelper ->
                            Triple(MikaIcons.Phone, stringResource(R.string.pause_step_repair), Fix.OpenPhoneSetup)
                    }
                    ListRow(
                        icon, label, null, first = index == 0,
                        onClick = { onFix(fix) },
                    ) { }
                }
            }
        }

        // 助手已配对、却还没连上(例如刚恢复、正在等 Wi-Fi):手动再连一次。
        if (!state.paused && state.helperPaired &&
            state.helper != BuiltinShellStatus.Running && state.helper != BuiltinShellStatus.Connecting
        ) {
            Spacer(Modifier.height(12.dp))
            GroupCard {
                ListRow(
                    MikaIcons.Refresh, stringResource(R.string.pause_reconnect), null, first = true,
                    onClick = { onFix(Fix.RetryPhoneHelper) },
                ) { }
            }
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
    }
}

/** 暂停做完后的一句交代:停了任务就先说;没关掉的逐项说要用户去哪里关。 */
private fun pauseNotice(context: Context, result: PauseResult): String = buildList {
    if (result.tasksStopped) add(context.getString(R.string.pause_tasks_stopped))
    if (result.complete) add(context.getString(R.string.pause_done))
    if (result.accessibilityNeedsManual) add(context.getString(R.string.pause_a11y_manual))
    if (result.wirelessNeedsManual) add(context.getString(R.string.pause_wireless_manual))
}.joinToString(" ")
