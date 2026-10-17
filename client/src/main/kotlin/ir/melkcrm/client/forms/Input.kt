package ir.melkcrm.client.forms

import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.money.MoneyFormatter
import ir.melkcrm.core.money.MoneyParseResult
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Result of parsing one text field. Error codes match the `err_*` UI strings. */
sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Error(val code: String) : Parsed<Nothing>
}

/**
 * Parsing and formatting of form text. Accepts Persian, Arabic-Indic and ASCII digits, thousands
 * separators and both decimal separators, so agents can type with either keyboard. Blank input is
 * "not recorded" (null), never zero.
 */
object Input {
    private val separators = Regex("[,٬\\s_']")

    fun money(text: String, unit: CurrencyUnit): Parsed<Money?> = when (val r = MoneyFormatter.parse(text, unit)) {
        MoneyParseResult.Empty -> Parsed.Ok(null)
        is MoneyParseResult.Ok -> Parsed.Ok(r.money)
        MoneyParseResult.TooLarge -> Parsed.Error("amount_too_large")
        else -> Parsed.Error("invalid_amount")
    }

    /** Grouped digits without the unit, for pre-filling a money field. */
    fun moneyText(m: Money?, unit: CurrencyUnit, persianDigits: Boolean): String =
        m?.let { MoneyFormatter.group(it.inUnit(unit), persianDigits) } ?: ""

    fun int(text: String, min: Int = 0, max: Int = Int.MAX_VALUE): Parsed<Int?> {
        val t = Digits.toAscii(text).replace(separators, "")
        if (t.isEmpty()) return Parsed.Ok(null)
        if (t.startsWith("-") && min >= 0) return Parsed.Error("negative")
        val v = t.toIntOrNull() ?: return Parsed.Error("invalid_number")
        if (v < min) return Parsed.Error(if (v < 0) "negative" else "invalid_number")
        if (v > max) return Parsed.Error("invalid_number")
        return Parsed.Ok(v)
    }

    /** Signed integer, for floors (basement = -1). */
    fun signedInt(text: String, min: Int = -10, max: Int = 300): Parsed<Int?> {
        val t = Digits.toAscii(text).replace(separators, "").replace('−', '-')
        if (t.isEmpty()) return Parsed.Ok(null)
        val v = t.toIntOrNull() ?: return Parsed.Error("invalid_number")
        return if (v in min..max) Parsed.Ok(v) else Parsed.Error("invalid_number")
    }

    fun decimal(text: String): Parsed<Double?> {
        val t = Digits.toAscii(text).trim().replace('٫', '.').replace("٬", "").replace(" ", "")
        if (t.isEmpty()) return Parsed.Ok(null)
        // A single comma is a decimal separator (95,5); several are thousands separators.
        val normalized = if (t.count { it == ',' } == 1 && '.' !in t) t.replace(',', '.') else t.replace(",", "")
        if (normalized.startsWith("-")) return Parsed.Error("negative")
        val v = normalized.toDoubleOrNull() ?: return Parsed.Error("invalid_number")
        if (v.isNaN() || v.isInfinite()) return Parsed.Error("invalid_number")
        return Parsed.Ok(v)
    }

    fun intText(v: Int?, persianDigits: Boolean): String = v?.let { Digits.localize(it.toString(), persianDigits) } ?: ""

    fun decimalText(v: Double?, persianDigits: Boolean): String {
        if (v == null) return ""
        val s = if (v == Math.floor(v) && v < 1e15) v.toLong().toString() else v.toString()
        return Digits.localize(s, persianDigits).let { if (persianDigits) it.replace('.', '٫') else it }
    }

    /** Comma, Persian comma, semicolon or new line separated values; blanks and duplicates removed. */
    fun list(text: String): List<String> =
        text.split(',', '،', ';', '؛', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun listText(items: List<String>, persian: Boolean): String = items.joinToString(if (persian) "، " else ", ")

    fun text(s: String): String? = s.trim().ifEmpty { null }

    /** "14:30", "۱۴:۳۰" or "1430" → minutes after midnight. */
    fun time(text: String): Parsed<Int?> {
        val t = Digits.toAscii(text).trim().replace('：', ':').replace('.', ':')
        if (t.isEmpty()) return Parsed.Ok(null)
        val (h, m) = when {
            ':' in t -> t.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: -1) to (it.getOrNull(1)?.toIntOrNull() ?: -1) }
            t.length in 3..4 && t.all { it.isDigit() } -> t.dropLast(2).toInt() to t.takeLast(2).toInt()
            t.length in 1..2 && t.all { it.isDigit() } -> t.toInt() to 0
            else -> -1 to -1
        }
        return if (h in 0..23 && m in 0..59) Parsed.Ok(h * 60 + m) else Parsed.Error("invalid_time")
    }

    fun timeText(minutes: Int?, persianDigits: Boolean): String =
        minutes?.let { Digits.localize("%02d:%02d".format(it / 60, it % 60), persianDigits) } ?: ""

    /** Percent text ("2.5") → basis points (250). */
    fun percent(text: String): Parsed<Int?> = when (val d = decimal(text)) {
        is Parsed.Error -> d
        is Parsed.Ok -> {
            val v = d.value
            if (v == null) Parsed.Ok(null) else if (v > 1000) Parsed.Error("invalid_number") else Parsed.Ok(Math.round(v * 100).toInt())
        }
    }

    fun percentText(basisPoints: Int?, persianDigits: Boolean): String =
        basisPoints?.let { decimalText(it / 100.0, persianDigits) } ?: ""

    /** A local date plus minutes after midnight in [zone] → UTC epoch milliseconds. */
    fun instant(day: EpochDay, minutes: Int, zone: ZoneId): EpochMillis =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

    fun minutesOfDay(millis: EpochMillis, zone: ZoneId): Int =
        Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }
}

/** Collects field errors while a form is converted to an entity. */
class FormErrors {
    val map = linkedMapOf<String, String>()

    fun <T> take(field: String, p: Parsed<T>): T? = when (p) {
        is Parsed.Ok -> p.value
        is Parsed.Error -> { map.putIfAbsent(field, p.code); null }
    }

    fun error(field: String, code: String) { map.putIfAbsent(field, code) }
    val isEmpty: Boolean get() = map.isEmpty()
}

sealed interface FormResult<out T> {
    data class Ok<T>(val value: T) : FormResult<T>
    /** Field name → error code, using the validators' field names. */
    data class Errors(val errors: Map<String, String>) : FormResult<Nothing>
}

internal inline fun <T> FormErrors.result(build: () -> T): FormResult<T> =
    if (isEmpty) FormResult.Ok(build()) else FormResult.Errors(map.toMap())
