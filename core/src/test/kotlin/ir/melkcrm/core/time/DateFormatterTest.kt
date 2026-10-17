package ir.melkcrm.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDate
import java.time.ZonedDateTime

class DateFormatterTest {
    private val fa = DisplaySettings(AppLanguage.PERSIAN)
    private val en = DisplaySettings(AppLanguage.ENGLISH)
    private val day = LocalDate.of(2025, 3, 20).toEpochDay()

    @Test fun sameStoredValueDisplaysPerLanguage() {
        assertEquals("۱۴۰۳/۱۲/۳۰", DateFormatter.formatDate(day, fa))
        assertEquals("2025-03-20", DateFormatter.formatDate(day, en))
        assertEquals("۳۰ اسفند ۱۴۰۳", DateFormatter.formatLongDate(day, fa))
        assertEquals("20 Mar 2025", DateFormatter.formatLongDate(day, en))
        assertEquals("پنجشنبه ۳۰ اسفند ۱۴۰۳", DateFormatter.formatLongDate(day, fa, withWeekday = true))
    }

    @Test fun calendarCanBeChosenIndependentlyOfLanguage() {
        val enJalali = DisplaySettings(AppLanguage.ENGLISH, calendar = CalendarSystem.JALALI)
        assertEquals("30 Esfand 1403", DateFormatter.formatLongDate(day, enJalali))
    }

    @Test fun parseRoundTripsInBothCalendars() {
        assertEquals(day, DateFormatter.parseDate("۱۴۰۳/۱۲/۳۰", fa))
        assertEquals(day, DateFormatter.parseDate("2025-03-20", en))
    }

    @Test fun timestampsAreUtcAndShownInTehranTime() {
        val millis = ZonedDateTime.of(2026, 10, 17, 20, 45, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        // 20:45 UTC is 00:15 next day in Tehran (UTC+3:30)
        assertEquals("۱۴۰۵/۰۷/۲۶ ۰۰:۱۵", DateFormatter.formatDateTime(millis, fa))
        assertEquals(LocalDate.of(2026, 10, 18).toEpochDay(), AgencyTime.epochDayOf(millis))
    }

    @Test fun monthBuckets() {
        assertEquals(1403 to 12, DateFormatter.yearMonthOf(day, CalendarSystem.JALALI))
        assertEquals(2025 to 3, DateFormatter.yearMonthOf(day, CalendarSystem.GREGORIAN))
        assertEquals(JalaliDate(1403, 12, 1).toEpochDay(), DateFormatter.startOfMonth(day, CalendarSystem.JALALI))
    }

    @Test fun buildingAgeUsesTheStoredCalendar() {
        val today = LocalDate.of(2026, 10, 17).toEpochDay() // 1405
        assertEquals(10, AgencyTime.buildingAge(1395, CalendarSystem.JALALI, today))
        assertEquals(10, AgencyTime.buildingAge(2016, CalendarSystem.GREGORIAN, today))
    }
}
