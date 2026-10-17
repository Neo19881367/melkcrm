package ir.melkcrm.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.LocalDate

class JalaliCalendarTest {
    @Test fun knownDates() {
        assertEquals(JalaliDate(1403, 1, 1), JalaliDate.from(LocalDate.of(2024, 3, 20)))
        assertEquals(JalaliDate(1404, 1, 1), JalaliDate.from(LocalDate.of(2025, 3, 21)))
        assertEquals(JalaliDate(1403, 12, 30), JalaliDate.from(LocalDate.of(2025, 3, 20)))
        assertEquals(JalaliDate(1360, 5, 26), JalaliDate.from(LocalDate.of(1981, 8, 17)))
        assertEquals(LocalDate.of(2026, 10, 17), JalaliDate(1405, 7, 25).toLocalDate())
        assertEquals(LocalDate.of(1979, 2, 11), JalaliDate(1357, 11, 22).toLocalDate())
    }

    @Test fun matchesReferenceImplementationOnThousandsOfDates() {
        val lines = javaClass.getResource("/jalali-vectors.csv")!!.readText().trim().lines()
        assertTrue(lines.size > 3000)
        for (line in lines) {
            val (gy, gm, gd, jy, jm, jd) = line.split(',').map { it.toInt() }
            val g = LocalDate.of(gy, gm, gd)
            assertEquals(JalaliDate(jy, jm, jd), JalaliDate.from(g), "toJalali $line")
            assertEquals(g, JalaliDate(jy, jm, jd).toLocalDate(), "toGregorian $line")
        }
    }

    @Test fun leapYearsMatchReference() {
        val expected = javaClass.getResource("/jalali-leap-years.txt")!!.readText().trim().split(',').map { it.toInt() }.toSet()
        for (y in 1279..1479) assertEquals(y in expected, JalaliCalendar.isLeapYear(y), "year $y")
        assertTrue(JalaliCalendar.isLeapYear(1399))
        assertTrue(JalaliCalendar.isLeapYear(1403))
        assertFalse(JalaliCalendar.isLeapYear(1404))
        assertTrue(JalaliCalendar.isLeapYear(1408)) // 1407 is not leap: the 33-year cycle shifts
        assertFalse(JalaliCalendar.isLeapYear(1407))
    }

    @Test fun monthLengths() {
        assertEquals(31, JalaliCalendar.monthLength(1404, 1))
        assertEquals(31, JalaliCalendar.monthLength(1404, 6))
        assertEquals(30, JalaliCalendar.monthLength(1404, 7))
        assertEquals(29, JalaliCalendar.monthLength(1404, 12))
        assertEquals(30, JalaliCalendar.monthLength(1403, 12))
        assertNull(JalaliDate.of(1404, 12, 30))
        assertNull(JalaliDate.of(1404, 7, 31))
        assertFailsWith<IllegalArgumentException> { JalaliDate(1404, 13, 1) }
    }

    @Test fun roundTripEveryDayForFiftyYears() {
        var d = LocalDate.of(1990, 1, 1)
        var prev: JalaliDate? = null
        while (d.year < 2040) {
            val j = JalaliDate.from(d)
            assertEquals(d, j.toLocalDate())
            if (prev != null) assertEquals(j, prev.plusDays(1))
            prev = j
            d = d.plusDays(1)
        }
    }

    @Test fun plusMonthsClampsToMonthLength() {
        assertEquals(JalaliDate(1404, 7, 30), JalaliDate(1404, 6, 31).plusMonths(1))
        assertEquals(JalaliDate(1404, 12, 29), JalaliDate(1403, 12, 30).plusMonths(12))
        assertEquals(JalaliDate(1403, 12, 1), JalaliDate(1404, 1, 1).plusMonths(-1))
    }

    @Test fun parseAcceptsPersianDigits() {
        assertEquals(JalaliDate(1403, 12, 30), JalaliDate.parse("۱۴۰۳/۱۲/۳۰"))
        assertEquals(JalaliDate(1404, 1, 5), JalaliDate.parse("1404-01-05"))
        assertNull(JalaliDate.parse("1404/12/30"))
        assertNull(JalaliDate.parse("abc"))
    }

    @Test fun weekdayIsSaturdayFirst() {
        // 2026-10-17 is a Saturday
        assertEquals(0, JalaliDate.from(LocalDate.of(2026, 10, 17)).dayOfWeekIndex())
        assertEquals(6, JalaliDate.from(LocalDate.of(2026, 10, 23)).dayOfWeekIndex())
    }

    private operator fun <T> List<T>.component6() = this[5]
}
