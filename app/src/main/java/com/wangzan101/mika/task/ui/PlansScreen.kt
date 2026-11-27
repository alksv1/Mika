package com.wangzan101.mika.task.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.core.trigger.CONDITION_COOLDOWN
import com.wangzan101.mika.core.run.PauseFlag
import com.wangzan101.mika.core.trigger.CONDITION_NOT_MET
import com.wangzan101.mika.core.trigger.CONDITION_PLAN_EDITED
import com.wangzan101.mika.core.trigger.WORKFLOW_BUSY
import com.wangzan101.mika.core.trigger.WORKFLOW_EARLY_STOP
import com.wangzan101.mika.core.trigger.WORKFLOW_TEMPLATE_DELETED
import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSource
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.trigger.canToggle
import com.wangzan101.mika.core.trigger.toCopyPlanDraft
import com.wangzan101.mika.core.trigger.upcoming
import com.wangzan101.mika.task.ExactAlarmGuide
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.Instant
import com.wangzan101.mika.i18n.uiLocale
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.settings.ui.models.refresh

/**
 * 已保存的定时任务:分「进行中」「已停用」两段,每条三行(做什么、什么时候与下次、上次结果),右边开关。
 * 点一行升起操作面板，可查看执行记录。右上角可手动新建；保存后让调度器重新布置系统闹钟。
 */
@Composable
fun PlansScreen(context: Context, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    BackHandler(onBack = onBack)
    var snapshot by remember { mutableStateOf(TriggerRuntime.snapshot(context)) }
    var creating by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TriggerDefinition?>(null) }
    var copying by remember { mutableStateOf<TriggerDefinition?>(null) }
    var deleting by remember { mutableStateOf<TriggerDefinition?>(null) }
    var acting by remember { mutableStateOf<TriggerDefinition?>(null) }
    var viewingHistory by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val deviceAccess = remember(context) {
        (context.applicationContext as? com.wangzan101.mika.MikaApp)?.deviceAccess?.settings
    }
    // 从系统设置回来时重读:刚开了权限,提示就该消失
    var exact by remember { mutableStateOf(TriggerRuntime.canScheduleExactly(context)) }
    LifecycleResumeEffect(Unit) {
        exact = TriggerRuntime.canScheduleExactly(context)
        snapshot = TriggerRuntime.snapshot(context)
        onPauseOrDispose {}
    }
    fun refresh() { snapshot = TriggerRuntime.snapshot(context) }
    fun act(block: () -> Unit = {}): Boolean =
        runCatching { block(); refresh(); error = null }
            .onFailure { error = it.message ?: tr(R.string.op_failed) }
            .isSuccess

    val (running, stopped) = snapshot.triggers.sortedBy { it.nextAtMs }.partition { it.enabled }
    val shown = if (segment == 0) running else stopped

    SubPage(
        tr(R.string.drawer_plans), onBack,
        right = {
            BarIconButton(MikaIcons.Plus, tr(R.string.plans_add), {
                creating = ConversationStore.shared(context).newConversation()
            })
        },
    ) {
        if (!exact) {
            StateCard(
                MikaIcons.Alarm, ExactAlarmGuide.TITLE, tr(R.string.plans_alarm_body),
                tone = TileTone.Warn,
            ) { MikaButton(tr(R.string.turn_on_go), { act { context.startActivity(TriggerRuntime.exactAlarmSettings(context)) } }, kind = ButtonKind.Primary, size = ButtonSize.Small) }
        }
        SegmentedControl(
            listOf(tr(R.string.plans_running_n, running.size), tr(R.string.plans_stopped_n, stopped.size)), segment, { segment = it },
            tags = listOf("plans-segment-running", "plans-segment-stopped"),
        )
        error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
        if (shown.isEmpty()) {
            GroupLabel("")
            EmptyNote(
                if (snapshot.triggers.isEmpty()) tr(R.string.plans_empty)
                else if (segment == 0) tr(R.string.plans_none_running) else tr(R.string.plans_none_stopped),
            )
        } else {
            GroupLabel(trPlural(R.plurals.settings_count_tasks, shown.size))
            GroupCard {
                shown.forEachIndexed { i, plan ->
                    val last = snapshot.windows.filter { it.triggerId == plan.id }.maxByOrNull { it.dueAtMs }
                    PlanRow(plan, last, first = i == 0, canToggle = plan.canToggle(snapshot.windows),
                        onClick = { acting = plan }, onToggle = { on -> act { TriggerRuntime.setEnabled(context, plan.id, on) } })
                }
            }
        }
        FooterNote(tr(R.string.plans_footer))
        // 精确闹钟没开时顶上已有提示卡,这里只列电池和解锁
        if (deviceAccess != null) ScreenOffSetupCard(deviceAccess, showAlarm = false)
    }

    acting?.let { plan ->
        ActionSheet(
            plan.reminderText ?: plan.goal,
            listOf(
                SheetAction(tr(R.string.plans_open_chat)) { onOpenConversation(plan.threadId) },
                SheetAction(tr(R.string.plans_run_history)) {
                    if (act()) viewingHistory = plan.id
                },
                SheetAction(tr(R.string.plans_run_now)) {
                    if (act { TriggerRuntime.runNow(context, plan.id) }) {
                        Toast.makeText(context, tr(R.string.plans_started_toast), Toast.LENGTH_SHORT).show()
                    }
                },
                SheetAction(tr(R.string.plans_edit_time)) { editing = plan },
                SheetAction(tr(R.string.plans_copy)) { copying = plan },
                SheetAction(tr(R.string.confirm_delete), destructive = true) { deleting = plan },
            ),
            onDismiss = { acting = null },
        )
    }

    viewingHistory?.let { id ->
        PlanRunHistoryDialog(snapshot, id, onDismiss = { viewingHistory = null })
    }

    creating?.let { threadId ->
        PlanEditorDialog(
            original = null,
            newThreadId = threadId,
            onDismiss = { creating = null },
            onSave = { plan ->
                if (act { TriggerRuntime.schedule(context, plan) }) creating = null
            },
            deviceAccess = deviceAccess,
        )
    }

    copying?.let { plan ->
        val draft = remember(plan.id) { plan.toCopyPlanDraft(System.currentTimeMillis()) }
        PlanEditorDialog(
            original = null,
            newThreadId = plan.threadId,
            onDismiss = { copying = null },
            onSave = { copy ->
                if (act { TriggerRuntime.schedule(context, copy) }) {
                    segment = if (copy.enabled) 0 else 1
                    copying = null
                }
            },
            initialDraft = draft,
            deviceAccess = deviceAccess,
        )
    }

    editing?.let { plan ->
        PlanEditorDialog(
            original = plan,
            newThreadId = plan.threadId,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (act { TriggerRuntime.schedule(context, updated, replaceExisting = true) }) editing = null
            },
            deviceAccess = deviceAccess,
        )
    }
    deleting?.let { plan ->
        MikaDialog(onDismissRequest = { deleting = null }, title = { Text(tr(R.string.plans_delete_title)) },
            text = { Text(plan.goal) },
            confirmButton = { TextButton(onClick = { act { TriggerRuntime.remove(context, plan.id) }; deleting = null }) {
                Text(tr(R.string.confirm_delete), color = MikaTheme.colors.bad)
            } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr(R.string.cancel)) } })
    }
}

@Composable
private fun PlanRunHistoryDialog(snapshot: TriggerSnapshot, planId: String, onDismiss: () -> Unit) {
    val windows = remember(snapshot, planId) { snapshot.recentWindows(planId, 10) }
    val is24Hour = systemUses24Hour()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.plans_run_history)) },
        text = {
            if (windows.isEmpty()) {
                Text(tr(R.string.plans_never_run))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    windows.forEachIndexed { index, window ->
                        ListRow(
                            icon = null,
                            title = formatPlanTime(window.claimedAtMs, is24Hour),
                            subtitle = windowLabel(window),
                            first = index == 0,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr(R.string.ok_got_it)) } },
    )
}

@Composable
private fun PlanRow(plan: TriggerDefinition, last: TriggerWindow?, first: Boolean, canToggle: Boolean,
    onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val status = when {
        plan.enabled -> tr(R.string.drawer_running)
        last?.state == TriggerWindowState.Delivered -> tr(R.string.plans_done)
        last?.state == TriggerWindowState.Skipped -> tr(R.string.plans_checked)
        last?.state == TriggerWindowState.Failed -> tr(R.string.step_failed)
        last?.state == TriggerWindowState.Unknown -> tr(R.string.plans_result_pending)
        else -> tr(R.string.chat_paused)
    }
    val repeat = when {
        plan.calendarTime != null -> {
            val days = if (plan.calendarDays.isEmpty()) tr(R.string.plans_every_day) else plan.calendarDays.joinToString(tr(R.string.list_sep)) {
                if (it in 1..7) java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()) else "?"
            }
            "$days ${plan.calendarClocks().joinToString(tr(R.string.list_sep))} (${plan.calendarZone})"
        }
        plan.intervalMs != null -> trPlural(R.plurals.plans_every_minutes, (plan.intervalMs!! / 60_000).toInt())
        else -> tr(R.string.plans_once)
    }
    val is24Hour = systemUses24Hour()
    val nextMs = upcoming(plan, System.currentTimeMillis(), 1).firstOrNull() ?: plan.nextAtMs
    val when_ = if (plan.enabled) tr(R.string.plans_next, repeat, formatPlanTime(nextMs, is24Hour)) else "$repeat · $status"
    val subtitle = plan.conditionGoal?.let {
        tr(if (plan.notifyWhen == ConditionNotify.EachTimeMet) R.string.plans_condition_each else R.string.plans_condition, it, when_)
    } ?: when_
    val sourceSubtitle = "${planSourceLabel(plan.source)} · $subtitle"
    val c = MikaTheme.colors
    val detailColor = when {
        last?.state == TriggerWindowState.Delivered -> c.ok
        !plan.enabled && last?.state == TriggerWindowState.Failed -> c.bad
        !plan.enabled && last?.state == TriggerWindowState.Unknown -> c.warn
        else -> c.ink3
    }
    ListRow(
        if (plan.workflowTemplateId != null) MikaIcons.Route else null,
        plan.reminderText ?: plan.goal, sourceSubtitle, first = first,
        detail = last?.let { lastResult(plan, it, is24Hour) } ?: tr(R.string.plans_never_run),
        detailColor = detailColor,
        onClick = onClick,
    ) { MikaSwitch(plan.enabled, onToggle, enabled = canToggle) }
}

/** 列表里的时间:日期用界面语言的中等格式,钟点跟系统的 12/24 小时制。 */
internal fun formatPlanTime(ms: Long, use24Hour: Boolean): String {
    val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(uiLocale()).format(zdt)
    return "$date ${PlanTimeFormat.formatTime(zdt.toLocalTime(), use24Hour, uiLocale())}"
}

/** 计划是谁建的,列表里标出来源好追溯。 */
private fun planSourceLabel(source: TriggerSource): String = when (source) {
    TriggerSource.User -> tr(R.string.plans_source_user)
    TriggerSource.Model -> tr(R.string.plans_source_model)
    TriggerSource.External -> tr(R.string.plans_source_external)
}
/** 上次结果:时间、结果;那次检查记下了值时一起显示(检查失败、结果未知的那一次没有值,不拿更早的充数)。 */
private fun lastResult(plan: TriggerDefinition, w: TriggerWindow, use24Hour: Boolean): String {
    val value = plan.lastValue?.takeIf { w.state in setOf(TriggerWindowState.Skipped, TriggerWindowState.ActionDispatched, TriggerWindowState.Delivered) }
    return if (value != null) tr(R.string.plans_last_value, formatPlanTime(w.dueAtMs, use24Hour), windowLabel(w), value)
    else tr(R.string.plans_last, formatPlanTime(w.dueAtMs, use24Hour), windowLabel(w))
}

private fun windowLabel(w: TriggerWindow): String = when (w.state) {
    TriggerWindowState.Delivered -> tr(R.string.plans_delivered)
    TriggerWindowState.Skipped -> when (w.reason) {
        CONDITION_NOT_MET -> tr(R.string.plans_not_met)
        CONDITION_COOLDOWN -> tr(R.string.plans_cooldown)
        CONDITION_PLAN_EDITED -> tr(R.string.trace_cancelled)
        PauseFlag.REASON -> tr(R.string.plans_skipped_paused)
        WORKFLOW_EARLY_STOP -> tr(R.string.plans_workflow_early_stop)
        WORKFLOW_BUSY -> tr(R.string.plans_workflow_busy)
        else -> tr(R.string.plans_unchanged)
    }
    TriggerWindowState.Failed -> if (w.reason == WORKFLOW_TEMPLATE_DELETED) tr(R.string.plans_workflow_deleted) else tr(R.string.step_failed)
    TriggerWindowState.Unknown -> tr(R.string.plans_result_pending)
    TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched -> tr(R.string.plans_running_now)
}
