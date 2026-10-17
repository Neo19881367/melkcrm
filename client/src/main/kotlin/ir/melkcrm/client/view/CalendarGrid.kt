package ir.melkcrm.client.view

import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.JalaliDate
import java.time.LocalDate

/**
 * Month grids for the date picker and the appointment calendar, in the Jalali or Gregorian
 * calendar. Weeks start on Saturday, as in Iran. Days are the stored Gregorian epoch days, so the
 * calendar choice only changes what is shown.
 */
data class CalendarMonth(val calendar: CalendarSystem, val year: Int, val month: Int) {
    val firstDay: EpochDay = when (calendar) {
        CalendarSystem.JALALI -> JalaliDate(year, month, 1).toEpochDay()
        CalendarSystem.GREGORIAN -> LocalDate.of(year, month, 1).toEpochDay()
    }
    val length: Int = when (calendar) {
        CalendarSystem.JALALI -> JalaliDate(year, month, 1).lengthOfMonth()
        CalendarSystem.GREGORIAN -> LocalDate.of(year, month, 1).lengthOfMonth()
    }
    /** Empty cells before day 1 (0 when the month starts on a Saturday). */
    val leadingBlanks: Int = ((LocalDate.ofEpochDay(firstDay).dayOfWeek.value + 1) % 7)

    /** Rows of 7 cells; null cells are outside the month. */
    fun weeks(): List<List<EpochDay?>> {
        val cells = List(leadingBlanks) { null } + (0 until length).map { firstDay + it }
        val padded = cells + List((7 - cells.size % 7) % 7) { null }
        return padded.chunked(7)
    }

    fun contains(day: EpochDay) = day in firstDay until firstDay + length

    fun next(): CalendarMonth = if (month == 12) copy(year = year + 1, month = 1) else copy(month = month + 1)
    fun previous(): CalendarMonth = if (month == 1) copy(year = year - 1, month = 12) else copy(month = month - 1)

    companion object {
        fun of(day: EpochDay, calendar: CalendarSystem): CalendarMonth {
            val (y, m) = DateFormatter.yearMonthOf(day, calendar)
            return CalendarMonth(calendar, y, m)
        }

        /** Day of month shown in a cell. */
        fun dayNumber(day: EpochDay, calendar: CalendarSystem): Int = when (calendar) {
            CalendarSystem.JALALI -> JalaliDate.ofEpochDay(day).day
            CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(day).dayOfMonth
        }

        /** One- or three-letter weekday headers, Saturday first. */
        fun weekdayHeaders(lang: AppLanguage): List<String> =
            if (lang == AppLanguage.PERSIAN) listOf("ش", "ی", "د", "س", "چ", "پ", "ج") else DateFormatter.weekdaysEn
    }
}
