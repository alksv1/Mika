package com.wangzan101.mika.task.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.trigger.PlanValidation
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** 编辑器里正在选哪个钟点。 */
internal sealed interface TimeTarget {
    data class FirstRun(val date: LocalDate) : TimeTarget
    data class Clock(val index: Int?) : TimeTarget
    data object WindowStart : TimeTarget
    data object WindowEnd : TimeTarget
}

/** 一段时长的本地化写法(「15 分钟」「2 hr, 30 min」),交给 ICU 按语言拼。 */
internal fun formatDuration(deltaMs: Long, locale: Locale): String {
    val parts = PlanTimeFormat.durationParts(deltaMs)
    val measures = buildList {
        if (parts.days > 0) add(Measure(parts.days, MeasureUnit.DAY))
        if (parts.hours > 0) add(Measure(parts.hours, MeasureUnit.HOUR))
        if (parts.minutes > 0 || isEmpty()) add(Measure(parts.minutes, MeasureUnit.MINUTE))
    }
    return MeasureFormat.getInstance(ULocale.forLocale(locale), MeasureFormat.FormatWidth.SHORT)
        .formatMeasures(*measures.toTypedArray())
}

/** 日期、钟点那样的大块点按区:整块可点,至少 48dp 高。 */
@Composable
internal fun PickerTapTile(
    icon: ImageVector,
    label: String,
    value: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(MikaShapes.Card)
            .background(c.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c.ink2)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MikaType.Caption, color = c.ink3)
            Text(value, style = MikaType.Body, fontWeight = FontWeight.Medium, color = c.ink)
        }
    }
}

/** 一排小按钮:按钮本身 32dp 高,外面各留出 48dp 的触控区。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

@Composable
internal fun Chip(text: String, selected: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    MikaButton(
        text, onClick,
        modifier = Modifier.minimumInteractiveComponentSize().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        kind = if (selected) ButtonKind.Primary else ButtonKind.Tonal,
        size = ButtonSize.Small,
    )
}

/**
 * 快捷时间:「15 分钟后」「1 小时后」「今晚 9 点」「明早 9 点」。现在几点在点下去那一刻再取,
 * 不用编辑器打开时的时间(开着编辑器放一会儿再点,也是从点的那一刻往后算)。
 */
@Composable
internal fun QuickTimeChips(zone: ZoneId, use24Hour: Boolean, locale: Locale, onPick: (LocalDateTime) -> Unit) {
    fun now() = Instant.now().atZone(zone).withSecond(0).withNano(0)
    val tonight = LocalTime.of(21, 0)
    val morning = LocalTime.of(9, 0)
    val showTonight = now().toLocalTime().isBefore(tonight)
    ChipRow {
        Chip(tr(R.string.plans_quick_in, formatDuration(15 * 60_000L, locale)), tag = "plan-quick-15m") {
            onPick(now().plusMinutes(15).toLocalDateTime())
        }
        Chip(tr(R.string.plans_quick_in, formatDuration(60 * 60_000L, locale)), tag = "plan-quick-1h") {
            onPick(now().plusHours(1).toLocalDateTime())
        }
        if (showTonight) {
            Chip(tr(R.string.plans_quick_tonight, PlanTimeFormat.formatTime(tonight, use24Hour, locale)), tag = "plan-quick-tonight") {
                val at = now()
                // 编辑器开着过了 21 点再点:改成明晚,不给出一个已经过去的时间
                val day = if (at.toLocalTime().isBefore(tonight)) at.toLocalDate() else at.toLocalDate().plusDays(1)
                onPick(day.atTime(tonight))
            }
        }
        Chip(tr(R.string.plans_quick_tomorrow_morning, PlanTimeFormat.formatTime(morning, use24Hour, locale)), tag = "plan-quick-tomorrow") {
            onPick(now().toLocalDate().plusDays(1).atTime(morning))
        }
    }
}

/** 每天/每周的钟点:一个钟点一块,点钟点改、点叉删;每个点按区至少 48dp。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClockChips(
    entries: List<LocalTime>,
    use24Hour: Boolean,
    locale: Locale,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEachIndexed { index, time ->
            Row(
                Modifier.height(48.dp).clip(MikaShapes.Card).background(c.fill),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.fillMaxHeight().clickable(role = Role.Button) { onEdit(index) }
                        .padding(start = 14.dp, end = 4.dp).testTag("plan-editor-clock-$index"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MikaIcons.Clock, null, Modifier.size(14.dp), tint = c.ink3)
                    Spacer(Modifier.width(6.dp))
                    Text(PlanTimeFormat.formatTime(time, use24Hour, locale), style = MikaType.Body, color = c.ink2)
                }
                Box(
                    Modifier.fillMaxHeight().width(48.dp).clickable(role = Role.Button) { onRemove(index) }
                        .testTag("plan-editor-clock-remove-$index"),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, tr(R.string.plans_remove_time), Modifier.size(14.dp), tint = c.ink3) }
            }
        }
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            MikaButton(
                "＋ " + tr(R.string.plans_add_time),
                onAdd,
                modifier = Modifier.testTag("plan-editor-clock-add"),
                kind = ButtonKind.Tonal,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
internal fun planValidationText(reason: PlanValidation): String = tr(when (reason) {
    PlanValidation.MissingContent -> R.string.plans_error_content
    PlanValidation.ContentTooLong -> R.string.plans_error_content_long
    PlanValidation.ConditionTooLong -> R.string.plans_error_condition_long
    PlanValidation.ReminderWithCondition -> R.string.plans_error_reminder_condition
    PlanValidation.InvalidFirstTime -> R.string.plans_error_time
    PlanValidation.TimeNotFuture -> R.string.plans_need_future
    PlanValidation.InvalidInterval -> R.string.plans_error_interval
    PlanValidation.InvalidClock -> R.string.plans_error_clock
    PlanValidation.InvalidZone -> R.string.plans_error_zone
    PlanValidation.InvalidWeekdays -> R.string.plans_error_weekdays
    PlanValidation.InvalidCooldown -> R.string.plans_error_cooldown
    PlanValidation.InvalidExecutionWindow -> R.string.plans_error_window
})
