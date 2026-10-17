package ir.melkcrm.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Storage conventions (documented in docs/DATA_MODEL.md):
 *
 * - Timestamps (moments in time): `Long` epoch milliseconds, UTC.
 * - Date-only values (follow-up date, deadline, birth date, acquisition date): `Long` epoch day of the
 *   **Gregorian ISO-8601 local date** in the agency time zone (`LocalDate.toEpochDay()`), with no time
 *   of day. Jalali is never stored; it is a display conversion only.
 * - Construction year: stored as entered together with its [CalendarSystem].
 */
typealias EpochMillis = Long
typealias EpochDay = Long

enum class CalendarSystem { JALALI, GREGORIAN }

object AgencyTime {
    /** Default zone for Iranian agencies. Iran has not observed DST since 2022. */
    val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")

    fun today(zone: ZoneId = TEHRAN, nowMillis: EpochMillis = System.currentTimeMillis()): EpochDay =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()

    fun epochDayOf(millis: EpochMillis, zone: ZoneId = TEHRAN): EpochDay =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    fun startOfDayMillis(day: EpochDay, zone: ZoneId = TEHRAN): EpochMillis =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Converts a construction year to a building age in whole years relative to [today]. */
    fun buildingAge(year: Int, calendar: CalendarSystem, today: EpochDay): Int {
        val currentYear = when (calendar) {
            CalendarSystem.JALALI -> JalaliDate.ofEpochDay(today).year
            CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(today).year
        }
        return (currentYear - year).coerceAtLeast(0)
    }
}
