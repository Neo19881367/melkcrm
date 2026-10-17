package ir.melkcrm.core.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MoneyTest {
    @Test fun tomanToRial() {
        val m = Money.ofToman(12_000_000_000)
        assertEquals(120_000_000_000, m.rial)
        assertEquals(12_000_000_000, m.toman)
        assertEquals(m, Money.of(120_000_000_000, CurrencyUnit.RIAL))
    }

    @Test fun formattingLabelsUnitAndUsesPersianDigits() {
        val m = Money.ofToman(12_000_000_000)
        assertEquals("۱۲,۰۰۰,۰۰۰,۰۰۰ تومان", MoneyFormatter.format(m, CurrencyUnit.TOMAN, persian = true))
        assertEquals("۱۲۰,۰۰۰,۰۰۰,۰۰۰ ریال", MoneyFormatter.format(m, CurrencyUnit.RIAL, persian = true))
        assertEquals("12,000,000,000 Toman", MoneyFormatter.format(m, CurrencyUnit.TOMAN, persian = false))
        assertEquals("۱۲ میلیارد تومان", MoneyFormatter.compact(m, CurrencyUnit.TOMAN, persian = true))
        assertEquals("1.5 billion Toman", MoneyFormatter.compact(Money.ofToman(1_500_000_000), CurrencyUnit.TOMAN, persian = false))
    }

    @Test fun switchingUnitDoesNotChangeValue() {
        val m = Money.ofToman(10_000_000_000)
        val asRial = MoneyFormatter.parse(MoneyFormatter.format(m, CurrencyUnit.RIAL, persian = true, withUnit = false), CurrencyUnit.RIAL)
        assertEquals(MoneyParseResult.Ok(m), asRial)
    }

    @Test fun rialAmountsNotDivisibleByTenShowDecimalToman() {
        assertEquals("1,234.5 Toman", MoneyFormatter.format(Money.ofRial(12_345), CurrencyUnit.TOMAN, persian = false))
    }

    @Test fun parseHandlesPersianDigitsAndSeparators() {
        assertEquals(MoneyParseResult.Ok(Money.ofToman(12_500_000_000)), MoneyFormatter.parse("۱۲٬۵۰۰٬۰۰۰٬۰۰۰", CurrencyUnit.TOMAN))
        assertEquals(MoneyParseResult.Empty, MoneyFormatter.parse("  ", CurrencyUnit.TOMAN))
        assertEquals(MoneyParseResult.Invalid, MoneyFormatter.parse("12.5", CurrencyUnit.TOMAN))
        assertEquals(MoneyParseResult.Invalid, MoneyFormatter.parse("-5", CurrencyUnit.TOMAN))
    }

    @Test fun largeValuesDoNotOverflow() {
        // 900 quadrillion toman fits; one more digit does not
        val big = MoneyFormatter.parse("900000000000000000", CurrencyUnit.TOMAN)
        assertIs<MoneyParseResult.Ok>(big)
        assertEquals(MoneyParseResult.TooLarge, MoneyFormatter.parse("9000000000000000000", CurrencyUnit.TOMAN))
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE - 1) + Money(10) }
        assertFailsWith<ArithmeticException> { Money.ofToman(Long.MAX_VALUE / 5) }
    }

    @Test fun basisPointsRoundHalfUp() {
        assertEquals(Money.ofToman(60_000_000), Money.ofToman(12_000_000_000).timesBasisPoints(50))
        assertEquals(Money(1), Money(15).timesBasisPoints(500)) // 0.75 -> 1
        // Huge amounts use BigInteger internally
        assertEquals(Money(Long.MAX_VALUE / 2), Money(Long.MAX_VALUE / 2).timesBasisPoints(10_000))
    }

    @Test fun negativeAmountsRejected() {
        assertFailsWith<IllegalArgumentException> { Money(-1) }
        assertFailsWith<IllegalArgumentException> { Money(5) - Money(10) }
    }
}
