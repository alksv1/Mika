package com.wangzan101.mika.task.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanDatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    // DatePicker 以 UTC 零点表示一天
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("plan-date-ok"),
                onClick = {
                    state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
            ) { Text(tr(R.string.done)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    ) {
        DatePicker(state, Modifier.testTag("plan-date-picker"), showModeToggle = false)
    }
}

/**
 * 钟点选择:滚轮。小时制跟系统设置([use24Hour] 由调用方按 `DateFormat.is24HourFormat` 传入),这里不另给开关;
 * 12 小时制的上午/下午用界面语言的写法,放在小时前还是后也跟语言。数字顺序固定从左到右(钟点在 RTL 语言里也这么写)。
 */
@Composable
internal fun PlanTimePickerDialog(
    initial: LocalTime,
    title: String,
    use24Hour: Boolean,
    onDismiss: () -> Unit,
    onPicked: (LocalTime) -> Unit,
) {
    val locale = uiLocale()
    val start = PlanTimeFormat.to12Hour(initial)
    var hour24 by remember(initial) { mutableIntStateOf(initial.hour) }
    var hour12Index by remember(initial) { mutableIntStateOf(start.hour12 - 1) }
    var amPmIndex by remember(initial) { mutableIntStateOf(if (start.isPm) 1 else 0) }
    var minute by remember(initial) { mutableIntStateOf(initial.minute) }
    val periodFirst = remember(locale) { PlanTimeFormat.dayPeriodFirst(locale) }
    val periods = remember(locale) { PlanTimeFormat.amPm(locale) }

    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (use24Hour) {
                        NumberWheel(24, hour24, "plan-time-hour", { hour24 = it }, Modifier.weight(1f))
                        Colon()
                        NumberWheel(60, minute, "plan-time-minute", { minute = it }, Modifier.weight(1f))
                    } else {
                        val period: @Composable RowScope.() -> Unit = {
                            LabelWheel(periods, amPmIndex, "plan-time-ampm", { amPmIndex = it }, Modifier.weight(1f))
                        }
                        if (periodFirst) { period(); Spacer(Modifier.width(6.dp)) }
                        LabelWheel(
                            (1..12).map { String.format(Locale.ROOT, "%d", it) }, hour12Index, "plan-time-hour12",
                            { hour12Index = it }, Modifier.weight(1f),
                        )
                        Colon()
                        NumberWheel(60, minute, "plan-time-minute", { minute = it }, Modifier.weight(1f))
                        if (!periodFirst) { Spacer(Modifier.width(6.dp)); period() }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.fillMaxWidth().testTag("plan-time-ok"),
                onClick = {
                    onPicked(
                        if (use24Hour) LocalTime.of(hour24, minute)
                        else PlanTimeFormat.from12Hour(hour12Index + 1, minute, amPmIndex == 1),
                    )
                },
            ) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun Colon() {
    Text(":", style = MikaType.Title, color = MikaTheme.colors.ink3, modifier = Modifier.padding(horizontal = 6.dp))
}

/** 每格 48dp:既是滚轮的行高,也是点按一格的最小触控尺寸。 */
private val WheelItem = 48.dp

@Composable
private fun NumberWheel(count: Int, initial: Int, tag: String, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = remember(count) { List(count) { String.format(Locale.ROOT, "%02d", it) } }
    LabelWheel(labels, initial, tag, onValueChange, modifier)
}

@Composable
private fun LabelWheel(
    labels: List<String>,
    initial: Int,
    tag: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val itemPx = with(LocalDensity.current) { WheelItem.roundToPx() }
    val last = (labels.size - 1).coerceAtLeast(0)
    val scroll = rememberScrollState(initial.coerceIn(0, last) * itemPx)
    val scope = rememberCoroutineScope()
    val selected by remember(labels.size, itemPx) {
        derivedStateOf { (scroll.value.toFloat() / itemPx).roundToInt().coerceIn(0, last) }
    }
    // 松手后吸附到最近的一格
    LaunchedEffect(scroll, itemPx, labels.size) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val target = selected * itemPx
                if (scroll.value != target) scroll.animateScrollTo(target)
            }
        }
    }
    LaunchedEffect(selected) { onValueChange(selected) }
    Box(modifier.height(WheelItem * 5f), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(WheelItem).background(c.fill, MikaShapes.Tile))
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(WheelItem * 2f))
            labels.forEachIndexed { index, text ->
                Box(
                    Modifier.fillMaxWidth().height(WheelItem)
                        .clip(MikaShapes.Tile)
                        .clickable { scope.launch { scroll.animateScrollTo(index * itemPx) } }
                        .testTag("$tag-$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        style = MikaType.Title.copy(fontFeatureSettings = "tnum"),
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selected) c.ink else c.ink3,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(WheelItem * 2f))
        }
    }
}
