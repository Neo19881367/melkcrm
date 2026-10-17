package ir.melkcrm.core.time

import java.time.LocalDate

/**
 * Jalali (Solar Hijri) calendar arithmetic.
 *
 * Port of the jalaali algorithm (Kazimierz M. Borkowski, as used by jalaali-js), valid for
 * Jalali years -61..3177. Leap years follow the 2820-year break table, which matches the
 * official Iranian calendar for the supported range far better than the plain 33-year rule.
 */
object JalaliCalendar {
    private val breaks = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    const val MIN_YEAR = -61
    const val MAX_YEAR = 3177

    private class CalInfo(val leap: Int, val gy: Int, val march: Int)

    private fun jalCal(jy: Int, withoutLeap: Boolean): CalInfo {
        require(jy in MIN_YEAR..MAX_YEAR) { "Jalali year out of range: $jy" }
        val gy = jy + 621
        var leapJ = -14
        var jp = breaks[0]
        var jump = 0
        for (i in 1 until breaks.size) {
            val jm = breaks[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += (jump / 33) * 8 + (jump % 33) / 4
            jp = jm
        }
        var n = jy - jp
        leapJ += (n / 33) * 8 + ((n % 33) + 3) / 4
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1
        val leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150
        val march = 20 + leapJ - leapG
        var leap = 0
        if (!withoutLeap) {
            if (jump - n < 6) n = n - jump + ((jump + 4) / 33) * 33
            leap = (((n + 1) % 33) - 1) % 4
            if (leap == -1) leap = 4
        }
        return CalInfo(leap, gy, march)
    }

    fun isLeapYear(jy: Int): Boolean = jalCal(jy, false).leap == 0

    fun monthLength(jy: Int, jm: Int): Int {
        require(jm in 1..12) { "Jalali month out of range: $jm" }
        return when {
            jm <= 6 -> 31
            jm <= 11 -> 30
            isLeapYear(jy) -> 30
            else -> 29
        }
    }

    fun isValid(jy: Int, jm: Int, jd: Int): Boolean =
        jy in MIN_YEAR..MAX_YEAR && jm in 1..12 && jd >= 1 && jd <= monthLength(jy, jm)

    /** Gregorian date of 1 Farvardin of [jy] is March [march]. */
    private fun farvardinFirst(jy: Int): LocalDate {
        val r = jalCal(jy, true)
        return LocalDate.of(r.gy, 3, r.march)
    }

    fun toGregorian(jy: Int, jm: Int, jd: Int): LocalDate {
        require(isValid(jy, jm, jd)) { "Invalid Jalali date $jy/$jm/$jd" }
        val dayOfYear = if (jm <= 7) (jm - 1) * 31 + jd - 1 else 186 + (jm - 7) * 30 + jd - 1
        return farvardinFirst(jy).plusDays(dayOfYear.toLong())
    }

    fun fromGregorian(date: LocalDate): JalaliDate {
        var jy = date.year - 621
        var start = farvardinFirst(jy)
        if (date.isBefore(start)) {
            jy -= 1
            start = farvardinFirst(jy)
        }
        val k = (date.toEpochDay() - start.toEpochDay()).toInt()
        return if (k < 186) JalaliDate(jy, 1 + k / 31, 1 + k % 31)
        else JalaliDate(jy, 7 + (k - 186) / 30, 1 + (k - 186) % 30)
    }
}

/** A Jalali calendar date. Construct only valid dates; use [JalaliDate.of] for validation. */
data class JalaliDate(val year: Int, val month: Int, val day: Int) : Comparable<JalaliDate> {
    init {
        require(JalaliCalendar.isValid(year, month, day)) { "Invalid Jalali date $year/$month/$day" }
    }

    fun toLocalDate(): LocalDate = JalaliCalendar.toGregorian(year, month, day)
    fun toEpochDay(): Long = toLocalDate().toEpochDay()
    fun plusDays(days: Long): JalaliDate = from(toLocalDate().plusDays(days))
    fun lengthOfMonth(): Int = JalaliCalendar.monthLength(year, month)
    fun isLeapYear(): Boolean = JalaliCalendar.isLeapYear(year)

    /** Same day in another month, clamped to the month length (e.g. 31 Shahrivar + 1 month = 30 Mehr). */
    fun plusMonths(months: Int): JalaliDate {
        val total = year * 12 + (month - 1) + months
        val y = Math.floorDiv(total, 12)
        val m = Math.floorMod(total, 12) + 1
        return JalaliDate(y, m, minOf(day, JalaliCalendar.monthLength(y, m)))
    }

    /** Saturday = 0 ... Friday = 6 (Iranian week). */
    fun dayOfWeekIndex(): Int = (toLocalDate().dayOfWeek.value + 1) % 7

    override fun compareTo(other: JalaliDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    override fun toString(): String = "%04d/%02d/%02d".format(year, month, day)

    companion object {
        fun from(date: LocalDate): JalaliDate = JalaliCalendar.fromGregorian(date)
        fun ofEpochDay(epochDay: Long): JalaliDate = from(LocalDate.ofEpochDay(epochDay))
        fun of(year: Int, month: Int, day: Int): JalaliDate? =
            if (JalaliCalendar.isValid(year, month, day)) JalaliDate(year, month, day) else null

        /** Parses "1403/12/30", "1403-12-30" or Persian digits. */
        fun parse(text: String): JalaliDate? {
            val parts = ir.melkcrm.core.text.Digits.toAscii(text.trim()).split('/', '-', '.')
            if (parts.size != 3) return null
            val nums = parts.map { it.trim().toIntOrNull() ?: return null }
            return of(nums[0], nums[1], nums[2])
        }
    }
}
