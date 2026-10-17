package ir.melkcrm.core.money

import ir.melkcrm.core.text.Digits
import kotlinx.serialization.Serializable

/**
 * Monetary amounts are stored as whole **rial** in a `Long` (canonical unit, documented in
 * docs/DATA_MODEL.md). 1 toman = 10 rial. `Long.MAX_VALUE` rial ≈ 9.2 × 10¹⁷ toman, far above any
 * property price, and all arithmetic here uses exact (overflow-checked) operations.
 *
 * Changing the display unit never changes the stored value.
 */
enum class CurrencyUnit(val rialPerUnit: Long) {
    TOMAN(10),
    RIAL(1),
}

@Serializable
@JvmInline
value class Money(val rial: Long) : Comparable<Money> {
    init {
        require(rial >= 0) { "Money cannot be negative: $rial" }
    }

    /** Toman value; exact when [rial] is a multiple of 10. */
    val tomanExact: Boolean get() = rial % 10 == 0L
    val toman: Long get() = rial / 10

    operator fun plus(other: Money) = Money(Math.addExact(rial, other.rial))
    operator fun minus(other: Money) = Money(Math.subtractExact(rial, other.rial))
    override fun compareTo(other: Money): Int = rial.compareTo(other.rial)

    /** Multiplies by basis points (1/100 of a percent) with rounding half-up, without overflow. */
    fun timesBasisPoints(bp: Int): Money {
        val r = java.math.BigInteger.valueOf(rial).multiply(java.math.BigInteger.valueOf(bp.toLong()))
            .add(java.math.BigInteger.valueOf(5_000)).divide(java.math.BigInteger.valueOf(10_000))
        return Money(r.longValueExact())
    }

    fun inUnit(unit: CurrencyUnit): Long = rial / unit.rialPerUnit

    companion object {
        val ZERO = Money(0)
        fun ofToman(toman: Long): Money = Money(Math.multiplyExact(toman, 10L))
        fun ofRial(rial: Long): Money = Money(rial)
        fun of(amount: Long, unit: CurrencyUnit): Money = Money(Math.multiplyExact(amount, unit.rialPerUnit))
    }
}

sealed interface MoneyParseResult {
    data class Ok(val money: Money) : MoneyParseResult
    data object Empty : MoneyParseResult
    data object Invalid : MoneyParseResult
    data object TooLarge : MoneyParseResult
}

object MoneyFormatter {
    fun unitLabel(unit: CurrencyUnit, persian: Boolean): String = when (unit) {
        CurrencyUnit.TOMAN -> if (persian) "تومان" else "Toman"
        CurrencyUnit.RIAL -> if (persian) "ریال" else "Rial"
    }

    /** Groups digits by thousands: 12000000000 -> 12,000,000,000 (Persian digits optional). */
    fun group(value: Long, persianDigits: Boolean): String {
        val s = value.toString()
        val neg = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        val out = (if (neg) "-" else "") + sb
        return Digits.localize(out, persianDigits)
    }

    /** "۱۲,۰۰۰,۰۰۰,۰۰۰ تومان". Always labels the unit unless [withUnit] is false. */
    fun format(money: Money, unit: CurrencyUnit, persian: Boolean, persianDigits: Boolean = persian, withUnit: Boolean = true): String {
        val main = group(money.inUnit(unit), persianDigits)
        val remainder = money.rial % unit.rialPerUnit
        val text = if (remainder != 0L) main + (if (persianDigits) "٫" else ".") + Digits.localize(remainder.toString(), persianDigits) else main
        return if (withUnit) "$text ${unitLabel(unit, persian)}" else text
    }

    /** Short human form: "۱۲ میلیارد تومان", "1.5 billion Toman". */
    fun compact(money: Money, unit: CurrencyUnit, persian: Boolean, persianDigits: Boolean = persian): String {
        val v = money.inUnit(unit)
        val scales = if (persian) listOf(1_000_000_000_000L to "هزار میلیارد", 1_000_000_000L to "میلیارد", 1_000_000L to "میلیون", 1_000L to "هزار")
        else listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
        val (scale, name) = scales.firstOrNull { v >= it.first } ?: return format(money, unit, persian, persianDigits)
        val whole = v / scale
        val tenth = (v % scale) * 10 / scale
        val num = if (tenth == 0L) whole.toString() else "$whole${if (persianDigits) "٫" else "."}$tenth"
        return "${Digits.localize(num, persianDigits)} $name ${unitLabel(unit, persian)}"
    }

    /** Parses user input in [unit]; accepts Persian digits, separators and spaces. */
    fun parse(input: String?, unit: CurrencyUnit): MoneyParseResult {
        if (input.isNullOrBlank()) return MoneyParseResult.Empty
        val cleaned = Digits.toAscii(input).replace(Regex("[,٬،\\s_']"), "")
        if (cleaned.isEmpty()) return MoneyParseResult.Empty
        if (!cleaned.all { it.isDigit() }) return MoneyParseResult.Invalid
        if (cleaned.length > 19) return MoneyParseResult.TooLarge
        val amount = cleaned.toLongOrNull() ?: return MoneyParseResult.TooLarge
        return try {
            MoneyParseResult.Ok(Money.of(amount, unit))
        } catch (_: ArithmeticException) {
            MoneyParseResult.TooLarge
        }
    }
}
