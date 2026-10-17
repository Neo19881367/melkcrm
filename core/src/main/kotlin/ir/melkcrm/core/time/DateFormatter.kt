package ir.melkcrm.core.time

import ir.melkcrm.core.text.Digits
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AppLanguage(val code: String, val rtl: Boolean) {
    PERSIAN("fa", true),
    ENGLISH("en", false);

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: PERSIAN
    }
}

/** Display settings; the stored value never changes when these change. */
data class DisplaySettings(
    val language: AppLanguage = AppLanguage.PERSIAN,
    val calendar: CalendarSystem = if (language == AppLanguage.PERSIAN) CalendarSystem.JALALI else CalendarSystem.GREGORIAN,
    val persianDigits: Boolean = language == AppLanguage.PERSIAN,
    val zone: ZoneId = AgencyTime.TEHRAN,
)

object DateFormatter {
    val jalaliMonthsFa = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )
    val jalaliMonthsEn = listOf(
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand",
    )
    private val gregorianMonthsFa = listOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر",
    )
    private val gregorianMonthsEn = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    /** Saturday first. */
    val weekdaysFa = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")
    val weekdaysEn = listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri")

    /** Numeric date: 1403/12/30 (Jalali) or 2025-03-20 (Gregorian). */
    fun formatDate(day: EpochDay, s: DisplaySettings): String {
        val text = when (s.calendar) {
            CalendarSystem.JALALI -> JalaliDate.ofEpochDay(day).toString()
            CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(day).toString()
        }
        return Digits.localize(text, s.persianDigits)
    }

    /** Long date: ۳۰ اسفند ۱۴۰۳ / 20 Mar 2025. */
    fun formatLongDate(day: EpochDay, s: DisplaySettings, withWeekday: Boolean = false): String {
        val fa = s.language == AppLanguage.PERSIAN
        val text = when (s.calendar) {
            CalendarSystem.JALALI -> {
                val j = JalaliDate.ofEpochDay(day)
                val month = if (fa) jalaliMonthsFa[j.month - 1] else jalaliMonthsEn[j.month - 1]
                "${j.day} $month ${j.year}"
            }
            CalendarSystem.GREGORIAN -> {
                val g = LocalDate.ofEpochDay(day)
                val month = if (fa) gregorianMonthsFa[g.monthValue - 1] else gregorianMonthsEn[g.monthValue - 1]
                "${g.dayOfMonth} $month ${g.year}"
            }
        }
        val withDay = if (withWeekday) {
            val idx = (LocalDate.ofEpochDay(day).dayOfWeek.value + 1) % 7
            val wd = if (fa) weekdaysFa[idx] else weekdaysEn[idx]
            if (fa) "$wd $text" else "$wd, $text"
        } else text
        return Digits.localize(withDay, s.persianDigits)
    }

    fun formatTime(millis: EpochMillis, s: DisplaySettings): String {
        val t = Instant.ofEpochMilli(millis).atZone(s.zone)
        return Digits.localize("%02d:%02d".format(t.hour, t.minute), s.persianDigits)
    }

    fun formatDateTime(millis: EpochMillis, s: DisplaySettings): String =
        formatDate(AgencyTime.epochDayOf(millis, s.zone), s) + " " + formatTime(millis, s)

    fun monthLabel(year: Int, month: Int, s: DisplaySettings): String {
        val fa = s.language == AppLanguage.PERSIAN
        val name = when (s.calendar) {
            CalendarSystem.JALALI -> if (fa) jalaliMonthsFa[month - 1] else jalaliMonthsEn[month - 1]
            CalendarSystem.GREGORIAN -> if (fa) gregorianMonthsFa[month - 1] else gregorianMonthsEn[month - 1]
        }
        return Digits.localize("$name $year", s.persianDigits)
    }

    /** Parses user-entered date text in the active calendar into the stored epoch day. */
    fun parseDate(text: String, s: DisplaySettings): EpochDay? = when (s.calendar) {
        CalendarSystem.JALALI -> JalaliDate.parse(text)?.toEpochDay()
        CalendarSystem.GREGORIAN -> runCatching { LocalDate.parse(Digits.toAscii(text.trim())).toEpochDay() }.getOrNull()
    }

    /** (year, month) bucket of a day in the active calendar, for monthly reports. */
    fun yearMonthOf(day: EpochDay, calendar: CalendarSystem): Pair<Int, Int> = when (calendar) {
        CalendarSystem.JALALI -> JalaliDate.ofEpochDay(day).let { it.year to it.month }
        CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(day).let { it.year to it.monthValue }
    }

    /** First day of the month containing [day], in the active calendar. */
    fun startOfMonth(day: EpochDay, calendar: CalendarSystem): EpochDay = when (calendar) {
        CalendarSystem.JALALI -> JalaliDate.ofEpochDay(day).let { JalaliDate(it.year, it.month, 1).toEpochDay() }
        CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(day).withDayOfMonth(1).toEpochDay()
    }

    /** Start of the week (Saturday) containing [day]. */
    fun startOfWeek(day: EpochDay): EpochDay {
        val idx = (LocalDate.ofEpochDay(day).dayOfWeek.value + 1) % 7
        return day - idx
    }
}
