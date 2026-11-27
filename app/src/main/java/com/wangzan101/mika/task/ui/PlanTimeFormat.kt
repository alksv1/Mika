package com.wangzan101.mika.task.ui

import java.text.DateFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

data class Time12Hour(val hour12: Int, val minute: Int, val isPm: Boolean)

/** 距离下一次还有多久,拆成天、小时、分钟(分钟向上取整;满一天后不再写分钟),由界面按语言拼成文字。 */
data class DurationParts(val days: Long, val hours: Long, val minutes: Long)

/**
 * 时区选择器里的一项。[city] 是本地化的代表城市(取不到时用编号里的地名),[displayName] 是本地化的时区名,
 * [offsetLabel] 按建索引那一刻算(夏令时期间就是夏令时的偏移)。
 */
data class ZoneEntry(
    val id: String,
    val city: String,
    val displayName: String,
    val offsetLabel: String,
    val offsetSeconds: Int,
) {
    internal val haystack: String =
        listOf(id, id.replace('_', ' '), city, displayName, offsetLabel)
            .joinToString("\u0000").lowercase(Locale.ROOT)
}

/**
 * 计划编辑器与计划页共用的时间显示。只管显示,存储格式不变(钟点仍是 `HH:mm`)。
 * 12/24 小时制由调用方按系统设置传入;钟点格式取界面语言的短时间格式再换成要的小时制,上午/下午放哪边跟语言走。
 */
internal object PlanTimeFormat {
    fun to12Hour(time: LocalTime): Time12Hour {
        val raw = time.hour % 12
        return Time12Hour(hour12 = if (raw == 0) 12 else raw, minute = time.minute, isPm = time.hour >= 12)
    }

    fun from12Hour(hour12: Int, minute: Int, isPm: Boolean): LocalTime {
        require(hour12 in 1..12) { "hour12 must be 1..12, got $hour12" }
        require(minute in 0..59) { "minute must be 0..59, got $minute" }
        return LocalTime.of(hour12 % 12 + if (isPm) 12 else 0, minute)
    }

    /** [locale] 的钟点格式,换成 [use24Hour] 要的小时制;引号里的字面文字(如加拿大法语的 'h')原样保留。 */
    fun timePattern(locale: Locale, use24Hour: Boolean): String {
        val base = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        val hasDayPeriod = unquoted(base).any { it in DAY_PERIOD }
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < base.length) {
            val ch = base[i]
            if (ch == '\'') {
                quoted = !quoted
                out.append(ch)
                i++
                continue
            }
            if (quoted) {
                out.append(ch)
                i++
                continue
            }
            var j = i
            while (j < base.length && base[j] == ch) j++
            when (ch) {
                in HOURS -> out.append(
                    when {
                        use24Hour -> "HH"
                        (ch == 'h' || ch == 'K') && j - i >= 2 -> "hh"
                        else -> "h"
                    },
                )
                in DAY_PERIOD -> if (!use24Hour) out.append('a')
                else -> out.append(base, i, j)
            }
            i = j
        }
        val pattern = out.toString().trim { it.isWhitespace() || Character.isSpaceChar(it) }
        return when {
            use24Hour || hasDayPeriod -> pattern
            dayPeriodLeads(locale) -> "a$pattern"
            else -> "$pattern a"
        }
    }

    fun formatTime(time: LocalTime, use24Hour: Boolean, locale: Locale): String =
        DateTimeFormatter.ofPattern(timePattern(locale, use24Hour), locale).format(time)

    /** 12 小时制下,这种语言把上午/下午写在小时前面(选择器据此排滚轮)。 */
    fun dayPeriodFirst(locale: Locale): Boolean {
        val p = unquoted(timePattern(locale, use24Hour = false))
        val a = p.indexOf('a')
        return a >= 0 && a < p.indexOf('h')
    }

    /** 本地化的「上午」「下午」,下标 0 是上午。 */
    fun amPm(locale: Locale): List<String> = DateFormatSymbols.getInstance(locale).amPmStrings.toList()

    fun formatFriendlyDate(date: LocalDate, today: LocalDate, todayLabel: String, tomorrowLabel: String, locale: Locale): String =
        when (date) {
            today -> todayLabel
            today.plusDays(1) -> tomorrowLabel
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        }

    fun durationParts(deltaMs: Long): DurationParts {
        val totalMinutes = (deltaMs.coerceAtLeast(0L) + 59_999L) / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = totalMinutes / 60 % 24
        return DurationParts(days, hours, if (days > 0) 0 else totalMinutes % 60)
    }

    fun zoneOffsetLabel(zoneId: ZoneId, now: Instant = Instant.now()): String {
        val offset = zoneId.rules.getOffset(now)
        return if (offset.totalSeconds == 0) "GMT" else "GMT$offset"
    }

    /**
     * 选择器里可选的时区:地理区域下的 IANA 编号和 UTC,不列 Etc/、SystemV/、US/ 这类别名;按此刻偏移、再按城市排。
     * 城市名由 [cityName] 给(安卓上用 ICU 的本地化城市名);整份在后台线程建一次,搜索只在这份里筛。
     */
    fun zoneIndex(
        locale: Locale,
        now: Instant = Instant.now(),
        availableIds: Set<String> = ZoneId.getAvailableZoneIds(),
        cityName: (String) -> String? = { null },
    ): List<ZoneEntry> = availableIds.asSequence()
        .filter { id -> id == "UTC" || REGIONS.any { id.startsWith(it) } }
        .mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull() }
        .map { zone ->
            ZoneEntry(
                id = zone.id,
                city = cityName(zone.id)?.takeIf { it.isNotBlank() } ?: zone.id.substringAfterLast('/').replace('_', ' '),
                displayName = zone.getDisplayName(TextStyle.FULL, locale),
                offsetLabel = zoneOffsetLabel(zone, now),
                offsetSeconds = zone.rules.getOffset(now).totalSeconds,
            )
        }
        .sortedWith(compareBy<ZoneEntry> { it.offsetSeconds }.thenBy { it.city })
        .toList()

    /**
     * 按编号、城市、时区名筛;输入的是偏移(「+8」「GMT+8」「UTC+5:30」「+08:00」)时按偏移精确筛,
     * 「+1」不会把 +10、+11 也带出来。当前选中的排第一。
     */
    fun searchZones(index: List<ZoneEntry>, query: String, preferredZoneId: String): List<ZoneEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        val offset = parseOffsetQuery(q)
        val matched = when {
            q.isEmpty() -> index
            offset != null -> index.filter { it.offsetSeconds == offset }
            else -> index.filter { q in it.haystack }
        }
        val preferred = matched.firstOrNull { it.id == preferredZoneId } ?: return matched
        return listOf(preferred) + matched.filter { it !== preferred }
    }

    private val HOURS = setOf('h', 'H', 'k', 'K')
    private val DAY_PERIOD = setOf('a', 'b', 'B')
    private val REGIONS = listOf(
        "Africa/", "America/", "Antarctica/", "Arctic/", "Asia/", "Atlantic/", "Australia/", "Europe/", "Indian/", "Pacific/",
    )

    /** 中日韩的 12 小时制把上午/下午写在前面(「上午9:05」)。只在这种语言默认 24 小时制、要补上午/下午时用。 */
    private fun dayPeriodLeads(locale: Locale) = locale.language in setOf("zh", "ja", "ko")

    private val OFFSET_QUERY = Regex("""^(?:gmt|utc)?\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?$""")

    /** 「+8」「gmt-5」「utc+5:30」「+0545」→ 偏移秒数;不像偏移的返回 null。 */
    internal fun parseOffsetQuery(query: String): Int? {
        val m = OFFSET_QUERY.matchEntire(query.trim().lowercase(Locale.ROOT)) ?: return null
        val hours = m.groupValues[2].toInt()
        val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (hours > 18 || minutes > 59) return null
        val total = hours * 3600 + minutes * 60
        return if (m.groupValues[1] == "-") -total else total
    }

    private fun unquoted(pattern: String): String =
        pattern.split('\'').filterIndexed { i, _ -> i % 2 == 0 }.joinToString("")
}
