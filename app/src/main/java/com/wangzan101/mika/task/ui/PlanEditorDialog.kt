package com.wangzan101.mika.task.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.PlanDraft
import com.wangzan101.mika.core.trigger.PlanExecutionWindow
import com.wangzan101.mika.core.trigger.PlanRepeat
import com.wangzan101.mika.core.trigger.formatPlanClock
import com.wangzan101.mika.core.trigger.PlanValidationException
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.editedTrigger
import com.wangzan101.mika.core.trigger.formatPlanEditorTime
import com.wangzan101.mika.core.trigger.newPlanDraft
import com.wangzan101.mika.core.trigger.normalizedClockText
import com.wangzan101.mika.core.trigger.parsePlanEditorTime
import com.wangzan101.mika.core.trigger.planClockEntries
import com.wangzan101.mika.core.trigger.toPlanDraft
import com.wangzan101.mika.core.trigger.upcoming
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.UUID

/** Full-screen schedule editor shared by new, copied, and edited plans. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditorDialog(
    original: TriggerDefinition?,
    newThreadId: String,
    onDismiss: () -> Unit,
    onSave: (TriggerDefinition) -> Unit,
    initialDraft: PlanDraft? = null,
    deviceAccess: DeviceAccessSettings? = null,
    /** 小时制跟系统设置,编辑器里不另给开关;测试可固定成某一种。 */
    use24Hour: Boolean = systemUses24Hour(),
) {
    val locale = uiLocale()
    // 时区选择器要用的本地化索引建起来较慢,编辑器一打开就在后台预热,点开选择器时直接有列表。
    LaunchedEffect(locale) { withContext(Dispatchers.Default) { PlanZoneIndex.get(locale) } }

    val newId = remember(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    var draft by remember(original?.id) {
        mutableStateOf(original?.toPlanDraft() ?: initialDraft ?: newPlanDraft(System.currentTimeMillis()))
    }
    var attempted by remember(original?.id) { mutableStateOf(false) }
    var pickingDate by remember(original?.id) { mutableStateOf(false) }
    var pickingTime by remember(original?.id) { mutableStateOf<TimeTarget?>(null) }
    var pickingZone by remember(original?.id) { mutableStateOf(false) }
    var conditionExpanded by remember(original?.id) { mutableStateOf(draft.condition.isNotBlank()) }

    val nowMs = System.currentTimeMillis()
    val pickerZone = runCatching { ZoneId.of(draft.zone.trim()) }.getOrElse { ZoneId.systemDefault() }
    val todayInZone = Instant.ofEpochMilli(nowMs).atZone(pickerZone).toLocalDate()
    val parsedFirst = parsePlanEditorTime(draft.firstAt, pickerZone.id)
        ?: Instant.ofEpochMilli(nowMs).atZone(pickerZone).plusHours(1).withSecond(0).withNano(0).toLocalDateTime()
    val clockEntries = planClockEntries(draft.clock).orEmpty()
    val candidate = runCatching { editedTrigger(draft, original, newId, newThreadId, nowMs) }
    val preview = candidate.getOrNull()

    if (pickingDate) {
        PlanDatePickerDialog(
            initial = parsedFirst.toLocalDate(),
            onDismiss = { pickingDate = false },
            onPicked = { date ->
                pickingDate = false
                draft = draft.copy(firstAt = formatPlanEditorTime(LocalDateTime.of(date, parsedFirst.toLocalTime())))
            },
        )
    }
    pickingTime?.let { target ->
        PlanTimePickerDialog(
            initial = when (target) {
                is TimeTarget.FirstRun -> parsedFirst.toLocalTime()
                is TimeTarget.Clock -> target.index?.let { clockEntries.getOrNull(it) }
                    ?: clockEntries.lastOrNull()?.plusHours(1)
                    ?: LocalTime.of(9, 0)
                TimeTarget.WindowStart -> runCatching { LocalTime.parse(draft.windowStart.trim()) }.getOrNull() ?: LocalTime.of(22, 0)
                TimeTarget.WindowEnd -> runCatching { LocalTime.parse(draft.windowEnd.trim()) }.getOrNull() ?: LocalTime.of(2, 0)
            },
            title = tr(R.string.plans_pick_time),
            use24Hour = use24Hour,
            onDismiss = { pickingTime = null },
            onPicked = { time ->
                when (target) {
                    is TimeTarget.FirstRun ->
                        draft = draft.copy(firstAt = formatPlanEditorTime(LocalDateTime.of(target.date, time)))
                    is TimeTarget.Clock -> {
                        val updated = clockEntries.toMutableList()
                        val index = target.index
                        if (index != null && index in updated.indices) updated[index] = time else updated.add(time)
                        draft = draft.copy(clock = normalizedClockText(updated))
                    }
                    TimeTarget.WindowStart -> draft = draft.copy(windowStart = formatPlanClock(time))
                    TimeTarget.WindowEnd -> draft = draft.copy(windowEnd = formatPlanClock(time))
                }
                pickingTime = null
            },
        )
    }
    if (pickingZone) {
        PlanTimezonePickerDialog(
            currentZoneId = pickerZone.id,
            onDismiss = { pickingZone = false },
            onPicked = { zoneId ->
                draft = draft.copy(zone = zoneId)
                pickingZone = false
            },
        )
    }

    val titleRes = when {
        original != null -> R.string.plans_edit_title
        initialDraft != null -> R.string.plans_copy
        else -> R.string.plans_create_title
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        PageBackground {
            Column(Modifier.fillMaxSize().imePadding()) {
                TopBar(
                    title = tr(titleRes),
                    left = MikaIcons.Close,
                    onLeft = onDismiss,
                    leftLabel = tr(R.string.cancel),
                    right = {
                        MikaButton(
                            tr(R.string.save),
                            onClick = {
                                val saved = runCatching {
                                    editedTrigger(draft, original, newId, newThreadId, System.currentTimeMillis())
                                }
                                if (saved.isFailure) attempted = true
                                else onSave(saved.getOrThrow())
                            },
                            modifier = Modifier.testTag("plan-editor-save"),
                            kind = ButtonKind.Primary,
                            size = ButtonSize.Small,
                        )
                    },
                )
                KeyboardDismissArea {
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(horizontal = MikaSpace.Page),
                    ) {
                        Spacer(Modifier.height(MikaSpace.S8))
                        SegmentedControl(
                            listOf(tr(R.string.plans_task_mode), tr(R.string.plans_reminder_mode)),
                            if (draft.reminder) 1 else 0,
                            onSelect = { selected ->
                                val isReminder = selected == 1
                                draft = draft.copy(
                                    reminder = isReminder,
                                    condition = if (isReminder) "" else draft.condition,
                                    cooldownMinutes = if (isReminder) "" else draft.cooldownMinutes,
                                )
                                if (isReminder) conditionExpanded = false
                            },
                            tags = listOf("plan-editor-task", "plan-editor-reminder"),
                        )
                        Spacer(Modifier.height(MikaSpace.S12))
                        GroupCard {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                OutlinedTextField(
                                    value = draft.content,
                                    onValueChange = { draft = draft.copy(content = it) },
                                    modifier = Modifier.fillMaxWidth().testTag("plan-editor-content").keepsKeyboard(),
                                    label = { Text(tr(if (draft.reminder) R.string.plans_content_reminder else R.string.plans_content_task)) },
                                    minLines = 2,
                                    maxLines = 5,
                                )
                            }
                        }

                        GroupLabel(tr(R.string.plans_time))
                        GroupCard {
                            Column(Modifier.padding(14.dp)) {
                                SegmentedControl(
                                    listOf(
                                        tr(R.string.plans_once),
                                        tr(R.string.plans_repeat_interval),
                                        tr(R.string.plans_every_day),
                                        tr(R.string.plans_repeat_weekly),
                                    ),
                                    draft.repeat.ordinal,
                                    onSelect = { selected ->
                                        val repeat = PlanRepeat.entries[selected]
                                        val todayDay = Instant.now().atZone(pickerZone).dayOfWeek.value
                                        draft = draft.copy(
                                            repeat = repeat,
                                            weekdays = if (repeat == PlanRepeat.Weekly && draft.weekdays.isEmpty()) setOf(todayDay)
                                            else draft.weekdays,
                                        )
                                    },
                                    tags = listOf("plan-editor-once", "plan-editor-interval", "plan-editor-daily", "plan-editor-weekly"),
                                )
                                Spacer(Modifier.height(12.dp))

                                if (draft.repeat in setOf(PlanRepeat.Once, PlanRepeat.Interval)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        PickerTapTile(
                                            icon = MikaIcons.Calendar,
                                            label = tr(R.string.plans_date),
                                            value = PlanTimeFormat.formatFriendlyDate(
                                                parsedFirst.toLocalDate(),
                                                todayInZone,
                                                tr(R.string.plans_today),
                                                tr(R.string.plans_tomorrow),
                                                locale,
                                            ),
                                            tag = "plan-editor-first-date-pick",
                                            modifier = Modifier.weight(1f),
                                            onClick = { pickingDate = true },
                                        )
                                        PickerTapTile(
                                            icon = MikaIcons.Clock,
                                            label = tr(R.string.plans_time),
                                            value = PlanTimeFormat.formatTime(parsedFirst.toLocalTime(), use24Hour, locale),
                                            tag = "plan-editor-first-time-pick",
                                            modifier = Modifier.weight(1f),
                                            onClick = { pickingTime = TimeTarget.FirstRun(parsedFirst.toLocalDate()) },
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    QuickTimeChips(
                                        zone = pickerZone,
                                        use24Hour = use24Hour,
                                        locale = locale,
                                        onPick = { picked ->
                                            draft = draft.copy(firstAt = formatPlanEditorTime(picked))
                                        },
                                    )
                                    val firstAtMs = parsedFirst.atZone(pickerZone).toInstant().toEpochMilli()
                                    if (draft.repeat == PlanRepeat.Once && firstAtMs <= nowMs) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clip(MikaShapes.Tile)
                                                .background(MikaTheme.colors.warn.copy(alpha = 0.12f))
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                tr(R.string.plans_time_past_hint),
                                                style = MikaType.Caption,
                                                color = MikaTheme.colors.warn,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(
                                                modifier = Modifier.testTag("plan-editor-use-tomorrow"),
                                                onClick = {
                                                    val tomorrow = LocalDateTime.of(todayInZone.plusDays(1), parsedFirst.toLocalTime())
                                                    draft = draft.copy(firstAt = formatPlanEditorTime(tomorrow))
                                                },
                                            ) { Text(tr(R.string.plans_use_tomorrow)) }
                                        }
                                    }
                                }

                                if (draft.repeat == PlanRepeat.Interval) {
                                    Spacer(Modifier.height(12.dp))
                                    ChipRow {
                                        listOf(15, 30, 60, 120, 360).forEach { mins ->
                                            Chip(
                                                formatDuration(mins * 60_000L, locale),
                                                selected = draft.intervalMinutes.trim() == mins.toString(),
                                                tag = "plan-editor-interval-$mins",
                                            ) { draft = draft.copy(intervalMinutes = mins.toString()) }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = draft.intervalMinutes,
                                        onValueChange = { draft = draft.copy(intervalMinutes = it) },
                                        modifier = Modifier.fillMaxWidth().testTag("plan-editor-interval-minutes").keepsKeyboard(),
                                        label = { Text(tr(R.string.plans_interval_minutes)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                }

                                if (draft.repeat in setOf(PlanRepeat.Daily, PlanRepeat.Weekly)) {
                                    ClockChips(
                                        entries = clockEntries,
                                        use24Hour = use24Hour,
                                        locale = locale,
                                        onEdit = { pickingTime = TimeTarget.Clock(it) },
                                        onRemove = { index ->
                                            val updated = clockEntries.toMutableList().apply { removeAt(index) }
                                            draft = draft.copy(clock = normalizedClockText(updated))
                                        },
                                        onAdd = { pickingTime = TimeTarget.Clock(null) },
                                    )
                                }

                                if (draft.repeat == PlanRepeat.Weekly) {
                                    Spacer(Modifier.height(12.dp))
                                    ChipRow {
                                        (1..7).forEach { day ->
                                            Chip(
                                                DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale),
                                                selected = day in draft.weekdays,
                                                tag = "plan-editor-weekday-$day",
                                            ) {
                                                draft = draft.copy(weekdays = draft.weekdays.toMutableSet().apply {
                                                    if (!add(day)) remove(day)
                                                })
                                            }
                                        }
                                    }
                                    ChipRow {
                                        // 「工作日」「周末」按周一至周五、周六周日算(ISO 星期,与存储一致)
                                        listOf(
                                            R.string.plans_every_day to setOf(1, 2, 3, 4, 5, 6, 7),
                                            R.string.plans_weekdays_workdays to setOf(1, 2, 3, 4, 5),
                                            R.string.plans_weekdays_weekends to setOf(6, 7),
                                        ).forEach { (label, days) ->
                                            Chip(tr(label), selected = draft.weekdays == days) { draft = draft.copy(weekdays = days) }
                                        }
                                    }
                                }
                            }
                        }

                        if (!draft.reminder && deviceAccess != null) {
                            ScreenOffSetupCard(deviceAccess)
                        }

                        if (!draft.reminder) {
                            GroupLabel(tr(R.string.plans_smart_condition))
                            GroupCard {
                                ListRow(
                                    icon = MikaIcons.Search,
                                    title = tr(R.string.plans_smart_condition),
                                    subtitle = tr(R.string.plans_smart_condition_sub),
                                    first = true,
                                    onClick = { conditionExpanded = !conditionExpanded },
                                ) {
                                    MikaSwitch(
                                        checked = conditionExpanded,
                                        onCheckedChange = { on ->
                                            conditionExpanded = on
                                            if (!on) draft = draft.copy(condition = "", cooldownMinutes = "")
                                        },
                                        modifier = Modifier.testTag("plan-editor-condition-toggle"),
                                    )
                                }
                                AnimatedVisibility(conditionExpanded) {
                                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                                        OutlinedTextField(
                                            value = draft.condition,
                                            onValueChange = { draft = draft.copy(condition = it) },
                                            modifier = Modifier.fillMaxWidth().testTag("plan-editor-condition").keepsKeyboard(),
                                            label = { Text(tr(R.string.plans_condition_optional)) },
                                            placeholder = { Text(tr(R.string.plans_condition_placeholder)) },
                                            minLines = 2,
                                            maxLines = 4,
                                        )
                                        if (draft.condition.isNotBlank()) {
                                            Spacer(Modifier.height(10.dp))
                                            SegmentedControl(
                                                listOf(tr(R.string.plans_notify_change), tr(R.string.plans_notify_each)),
                                                if (draft.notifyWhen == ConditionNotify.BecomesMet) 0 else 1,
                                                onSelect = {
                                                    draft = draft.copy(
                                                        notifyWhen = if (it == 0) ConditionNotify.BecomesMet else ConditionNotify.EachTimeMet,
                                                    )
                                                },
                                                tags = listOf("plan-editor-notify-change", "plan-editor-notify-each"),
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = draft.cooldownMinutes,
                                                onValueChange = { draft = draft.copy(cooldownMinutes = it) },
                                                modifier = Modifier.fillMaxWidth().testTag("plan-editor-cooldown").keepsKeyboard(),
                                                label = { Text(tr(R.string.plans_cooldown_minutes)) },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!draft.reminder) {
                            GroupLabel(tr(R.string.plans_execution_window))
                            GroupCard {
                                Column(Modifier.padding(14.dp)) {
                                    SegmentedControl(
                                        listOf(
                                            tr(R.string.plans_window_anytime),
                                            tr(R.string.plans_window_off_peak),
                                            tr(R.string.plans_window_custom),
                                        ),
                                        draft.executionWindow.ordinal,
                                        onSelect = { draft = draft.copy(executionWindow = PlanExecutionWindow.entries[it]) },
                                        tags = listOf("plan-editor-window-anytime", "plan-editor-window-off-peak", "plan-editor-window-custom"),
                                    )
                                    when (draft.executionWindow) {
                                        PlanExecutionWindow.DeepSeekOffPeak -> {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                tr(R.string.plans_window_off_peak_hint),
                                                style = MikaType.Caption,
                                                color = MikaTheme.colors.ink3,
                                            )
                                        }
                                        PlanExecutionWindow.Custom -> {
                                            Spacer(Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = draft.executionZone,
                                                onValueChange = { draft = draft.copy(executionZone = it) },
                                                modifier = Modifier.fillMaxWidth().testTag("plan-editor-window-zone").keepsKeyboard(),
                                                label = { Text(tr(R.string.plans_zone)) },
                                                singleLine = true,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = draft.windowStart,
                                                onValueChange = { draft = draft.copy(windowStart = it) },
                                                modifier = Modifier.fillMaxWidth().testTag("plan-editor-window-start").keepsKeyboard(),
                                                label = { Text(tr(R.string.plans_window_start)) },
                                                singleLine = true,
                                                trailingIcon = {
                                                    Box(
                                                        Modifier.size(48.dp).clip(CircleShape)
                                                            .clickable(role = Role.Button) { pickingTime = TimeTarget.WindowStart }
                                                            .testTag("plan-editor-window-start-pick"),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(MikaIcons.Clock, tr(R.string.plans_pick_time), Modifier.size(20.dp), tint = MikaTheme.colors.ink3)
                                                    }
                                                },
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = draft.windowEnd,
                                                onValueChange = { draft = draft.copy(windowEnd = it) },
                                                modifier = Modifier.fillMaxWidth().testTag("plan-editor-window-end").keepsKeyboard(),
                                                label = { Text(tr(R.string.plans_window_end)) },
                                                singleLine = true,
                                                trailingIcon = {
                                                    Box(
                                                        Modifier.size(48.dp).clip(CircleShape)
                                                            .clickable(role = Role.Button) { pickingTime = TimeTarget.WindowEnd }
                                                            .testTag("plan-editor-window-end-pick"),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(MikaIcons.Clock, tr(R.string.plans_pick_time), Modifier.size(20.dp), tint = MikaTheme.colors.ink3)
                                                    }
                                                },
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                tr(R.string.plans_window_custom_hint, draft.executionZone),
                                                style = MikaType.Caption,
                                                color = MikaTheme.colors.ink3,
                                            )
                                        }
                                        PlanExecutionWindow.Anytime -> Unit
                                    }
                                }
                            }
                        }

                        GroupLabel(tr(R.string.plans_more_options))
                        GroupCard {
                            val zoneLabel = remember(pickerZone.id, locale) { zoneSummary(pickerZone.id, locale) }
                            ListRow(
                                icon = MikaIcons.Globe,
                                title = tr(R.string.plans_timezone),
                                subtitle = zoneLabel,
                                first = true,
                                onClick = { pickingZone = true },
                                modifier = Modifier.testTag("plan-editor-zone"),
                            ) { RowChevron() }
                            ListRow(
                                icon = null,
                                title = tr(R.string.plans_when_idle),
                                subtitle = tr(R.string.plans_when_idle_sub),
                                onClick = { draft = draft.copy(whenIdle = !draft.whenIdle) },
                            ) {
                                MikaSwitch(draft.whenIdle, { draft = draft.copy(whenIdle = it) }, Modifier.testTag("plan-editor-idle"))
                            }
                        }

                        Spacer(Modifier.height(MikaSpace.S12))
                        GroupCard {
                            ListRow(
                                icon = null,
                                title = tr(R.string.plans_enabled),
                                subtitle = tr(R.string.plans_enabled_sub),
                                first = true,
                                onClick = { draft = draft.copy(enabled = !draft.enabled) },
                            ) {
                                MikaSwitch(draft.enabled, { draft = draft.copy(enabled = it) }, Modifier.testTag("plan-editor-enabled"))
                            }
                        }

                        preview?.let { plan ->
                            Spacer(Modifier.height(MikaSpace.S12))
                            val upcomingRuns = upcoming(plan, nowMs, 3)
                            GroupCard {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    upcomingRuns.firstOrNull()?.let { firstAtMs ->
                                        val firstZdt = Instant.ofEpochMilli(firstAtMs).atZone(pickerZone)
                                        val friendly = "${PlanTimeFormat.formatFriendlyDate(firstZdt.toLocalDate(), todayInZone, tr(R.string.plans_today), tr(R.string.plans_tomorrow), locale)} · ${PlanTimeFormat.formatTime(firstZdt.toLocalTime(), use24Hour, locale)}"
                                        val rel = formatDuration(firstAtMs - nowMs, locale)
                                        Text(
                                            tr(R.string.plans_next_in, friendly, rel),
                                            style = MikaType.Body,
                                            fontWeight = FontWeight.Medium,
                                            color = MikaTheme.colors.ink,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Text(tr(R.string.plans_preview), style = MikaType.Caption, color = MikaTheme.colors.ink3)
                                    upcomingRuns.forEachIndexed { index, at ->
                                        val zdt = Instant.ofEpochMilli(at).atZone(pickerZone)
                                        val formatted = "${PlanTimeFormat.formatFriendlyDate(zdt.toLocalDate(), todayInZone, tr(R.string.plans_today), tr(R.string.plans_tomorrow), locale)} ${PlanTimeFormat.formatTime(zdt.toLocalTime(), use24Hour, locale)}"
                                        Text(
                                            "${index + 1}  $formatted (${pickerZone.id})",
                                            Modifier.testTag("plan-editor-preview-$index"),
                                            style = MikaType.Caption,
                                            color = MikaTheme.colors.ink3,
                                        )
                                    }
                                }
                            }
                        }

                        if (attempted && preview == null) {
                            val reason = (candidate.exceptionOrNull() as? PlanValidationException)?.reason
                            Spacer(Modifier.height(MikaSpace.S8))
                            Text(
                                reason?.let { planValidationText(it) } ?: tr(R.string.op_failed),
                                Modifier.testTag("plan-editor-error"),
                                style = MikaType.Caption,
                                color = MikaTheme.colors.bad,
                            )
                        }
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

/** 系统设置里的 12/24 小时制。 */
@Composable
internal fun systemUses24Hour(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}
