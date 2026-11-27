package com.wangzan101.mika.task.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class PlanTimeFormatTest {
    @Test
    fun roundTripsAll24HoursThrough12HourAmPm() {
        for (h in 0..23) {
            for (m in listOf(0, 15, 30, 59)) {
                val original = LocalTime.of(h, m)
                val converted = PlanTimeFormat.to12Hour(original)
                assertTrue(converted.hour12 in 1..12)
                assertEquals(h >= 12, converted.isPm)
                assertEquals(original, PlanTimeFormat.from12Hour(converted.hour12, converted.minute, converted.isPm))
            }
        }
    }

    @Test
    fun handlesMidnightAndNoonEdgeCases() {
        assertEquals(Time12Hour(12, 0, isPm = false), PlanTimeFormat.to12Hour(LocalTime.MIDNIGHT))
        assertEquals(LocalTime.MIDNIGHT, PlanTimeFormat.from12Hour(12, 0, isPm = false))
        assertEquals(Time12Hour(12, 0, isPm = true), PlanTimeFormat.to12Hour(LocalTime.NOON))
        assertEquals(LocalTime.NOON, PlanTimeFormat.from12Hour(12, 0, isPm = true))
        assertEquals(LocalTime.of(0, 30), PlanTimeFormat.from12Hour(12, 30, isPm = false))
        assertEquals(LocalTime.of(12, 30), PlanTimeFormat.from12Hour(12, 30, isPm = true))
    }

    /** 窄不换行空格(JDK 新版 CLDR 在 AM/PM 前用它)按普通空格比。 */
    private fun fmt(time: LocalTime, use24Hour: Boolean, locale: Locale) =
        PlanTimeFormat.formatTime(time, use24Hour, locale).replace('\u202F', ' ').replace('\u00A0', ' ')

    @Test
    fun followsTheRequestedHourCycleInsteadOfTheLocaleDefault() {
        val morning = LocalTime.of(9, 5)
        val evening = LocalTime.of(21, 30)
        // 英语默认 12 小时制;系统设成 24 小时制时照样给 24 小时
        assertEquals("9:05 AM", fmt(morning, use24Hour = false, Locale.US))
        assertEquals("9:30 PM", fmt(evening, use24Hour = false, Locale.US))
        assertEquals("09:05", fmt(morning, use24Hour = true, Locale.US))
        assertEquals("21:30", fmt(evening, use24Hour = true, Locale.US))
        // 德语默认 24 小时制;系统设成 12 小时制时补上上午/下午,写在后面
        assertEquals("21:30", fmt(evening, use24Hour = true, Locale.GERMANY))
        assertEquals("h:mm a", PlanTimeFormat.timePattern(Locale.GERMANY, use24Hour = false))
    }

    @Test
    fun dayPeriodGoesWhereTheLanguagePutsIt() {
        // 中文默认 24 小时制:换成 12 小时制时「上午」在前
        assertEquals("21:30", fmt(LocalTime.of(21, 30), use24Hour = true, Locale.SIMPLIFIED_CHINESE))
        assertEquals("上午9:05", fmt(LocalTime.of(9, 5), use24Hour = false, Locale.SIMPLIFIED_CHINESE))
        assertTrue(PlanTimeFormat.dayPeriodFirst(Locale.SIMPLIFIED_CHINESE))
        // 韩语本身就是「오전 9:05」;24 小时制时去掉上午/下午和多出来的空格
        assertEquals("09:05", fmt(LocalTime.of(9, 5), use24Hour = true, Locale.KOREA))
        assertTrue(PlanTimeFormat.dayPeriodFirst(Locale.KOREA))
        assertFalse(PlanTimeFormat.dayPeriodFirst(Locale.US))
        assertEquals(listOf("上午", "下午"), PlanTimeFormat.amPm(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun keepsQuotedLiteralsWhenChangingTheHourCycle() {
        // 加拿大法语写「21 h 30」,引号里的 h 是字面文字,不能当成小时字段换掉
        val pattern24 = PlanTimeFormat.timePattern(Locale.CANADA_FRENCH, use24Hour = true)
        val pattern12 = PlanTimeFormat.timePattern(Locale.CANADA_FRENCH, use24Hour = false)
        if ("'h'" in pattern24) {
            assertTrue(pattern12, "'h'" in pattern12)
            assertEquals("21 h 30", fmt(LocalTime.of(21, 30), use24Hour = true, Locale.CANADA_FRENCH))
        }
        assertTrue(pattern12, pattern12.replace("'h'", "").contains('a'))
        assertFalse(pattern24, pattern24.replace("'h'", "").contains('a'))
    }

    @Test
    fun formatsFriendlyDateAndSplitsDurations() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals("Today", PlanTimeFormat.formatFriendlyDate(today, today, "Today", "Tomorrow", Locale.US))
        assertEquals("Tomorrow", PlanTimeFormat.formatFriendlyDate(today.plusDays(1), today, "Today", "Tomorrow", Locale.US))
        assertTrue(PlanTimeFormat.formatFriendlyDate(today.plusDays(5), today, "Today", "Tomorrow", Locale.US).contains("30"))
        assertEquals(DurationParts(0, 0, 15), PlanTimeFormat.durationParts(15 * 60_000L))
        assertEquals(DurationParts(0, 2, 30), PlanTimeFormat.durationParts(150 * 60_000L))
        // 不足一分钟向上取整;满一天后只写天和小时
        assertEquals(DurationParts(0, 0, 1), PlanTimeFormat.durationParts(1_000L))
        assertEquals(DurationParts(1, 2, 0), PlanTimeFormat.durationParts(26 * 3_600_000L + 59 * 60_000L))
        assertEquals(DurationParts(0, 0, 0), PlanTimeFormat.durationParts(-5_000L))
    }

    @Test
    fun indexesOnlyRegionalZonesAndSearchesByCityNameOrOffset() {
        val now = Instant.parse("2026-01-15T12:00:00Z")
        val index = PlanTimeFormat.zoneIndex(Locale.US, now, cityName = { id -> if (id == "Asia/Shanghai") "上海" else null })
        assertFalse(index.any { it.id.startsWith("Etc/") || it.id.startsWith("SystemV/") || it.id.startsWith("US/") })
        assertTrue(index.any { it.id == "UTC" })
        // 按偏移排
        assertEquals(index.map { it.offsetSeconds }.sorted(), index.map { it.offsetSeconds })

        assertTrue(PlanTimeFormat.searchZones(index, "tokyo", "UTC").any { it.id == "Asia/Tokyo" })
        assertTrue(PlanTimeFormat.searchZones(index, "new york", "UTC").any { it.id == "America/New_York" })
        assertEquals("Asia/Shanghai", PlanTimeFormat.searchZones(index, "上海", "UTC").single().id)
        for (q in listOf("+8", "GMT+8", "utc+8", "+08:00")) {
            val hits = PlanTimeFormat.searchZones(index, q, "UTC")
            assertTrue(q, hits.any { it.id == "Asia/Shanghai" })
            assertTrue(q, hits.all { it.offsetSeconds == 8 * 3600 })
        }
        assertTrue(PlanTimeFormat.searchZones(index, "-5", "UTC").any { it.id == "America/New_York" })
        // 半小时偏移
        assertTrue(PlanTimeFormat.searchZones(index, "+5:30", "UTC").any { it.id == "Asia/Kolkata" })

        val all = PlanTimeFormat.searchZones(index, "", "Europe/London")
        assertEquals("Europe/London", all.first().id)
        assertEquals(index.size, all.size)
        assertEquals("GMT", PlanTimeFormat.zoneOffsetLabel(ZoneId.of("UTC"), now))
    }

    @Test
    fun offsetsFollowDaylightSavingAtTheGivenInstant() {
        val winter = PlanTimeFormat.zoneIndex(Locale.US, Instant.parse("2026-01-15T12:00:00Z")).first { it.id == "America/New_York" }
        val summer = PlanTimeFormat.zoneIndex(Locale.US, Instant.parse("2026-07-15T12:00:00Z")).first { it.id == "America/New_York" }
        assertEquals("GMT-05:00", winter.offsetLabel)
        assertEquals("GMT-04:00", summer.offsetLabel)
    }
}
