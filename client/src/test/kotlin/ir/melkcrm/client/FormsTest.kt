package ir.melkcrm.client

import ir.melkcrm.client.forms.*
import ir.melkcrm.client.view.CalendarMonth
import ir.melkcrm.client.view.MatchFilter
import ir.melkcrm.client.view.PropertyFilter
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.JalaliDate
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormsTest {
    private val zone = AgencyTime.TEHRAN

    @Test fun moneyInputAcceptsPersianDigitsAndSeparatorsAndKeepsBlankAsNotRecorded() {
        assertEquals(Parsed.Ok(Money.ofToman(12_500_000_000)), Input.money("۱۲٬۵۰۰٬۰۰۰٬۰۰۰", CurrencyUnit.TOMAN))
        assertEquals(Parsed.Ok(Money.ofRial(125)), Input.money("125", CurrencyUnit.RIAL))
        assertEquals(Parsed.Ok(null), Input.money("  ", CurrencyUnit.TOMAN))
        assertEquals(Parsed.Error("invalid_amount"), Input.money("12a", CurrencyUnit.TOMAN))
        assertEquals(Parsed.Error("amount_too_large"), Input.money("9".repeat(19), CurrencyUnit.TOMAN))
        assertEquals("۱۲,۵۰۰,۰۰۰,۰۰۰".length, Input.moneyText(Money.ofToman(12_500_000_000), CurrencyUnit.TOMAN, true).length)
    }

    @Test fun numbersTimesAndListsParseBothKeyboards() {
        assertEquals(Parsed.Ok(95.5), Input.decimal("۹۵٫۵"))
        assertEquals(Parsed.Ok(95.5), Input.decimal("95,5"))
        assertEquals(Parsed.Ok(1_200_000.0), Input.decimal("1,200,000"))
        assertEquals(Parsed.Error("negative"), Input.decimal("-3"))
        assertEquals(Parsed.Ok(-1), Input.signedInt("-1"))
        assertEquals(Parsed.Ok(14 * 60 + 30), Input.time("۱۴:۳۰"))
        assertEquals(Parsed.Ok(9 * 60 + 5), Input.time("0905"))
        assertEquals(Parsed.Error("invalid_time"), Input.time("25:00"))
        assertEquals(listOf("پونک", "سعادت آباد", "شهرک غرب"), Input.list("پونک، سعادت آباد, شهرک غرب،پونک"))
        assertEquals(Parsed.Ok(250), Input.percent("2.5"))
        assertEquals("۲٫۵", Input.percentText(250, true))
    }

    @Test fun requirementFormClearsFieldsOfTheOtherDealTypeAndReportsFieldErrors() {
        val base = BuyerRequirement("r", "A", "c", dealType = DealType.SALE, createdAt = 0, updatedAt = 0)
        val form = RequirementForm(dealType = DealType.RENT, maxBudget = "10000000000", maxDeposit = "2,000,000,000", maxMonthlyRent = "30,000,000", cities = "تهران")
        val r = assertIs<FormResult.Ok<BuyerRequirement>>(form.build(base, CurrencyUnit.TOMAN)).value
        assertNull(r.maxBudget, "a sale budget left in the form never affects a rental search")
        assertEquals(Money.ofToman(2_000_000_000), r.maxDeposit)
        assertEquals(Money.ofToman(30_000_000), r.maxMonthlyRent)
        assertEquals(listOf("تهران"), r.cities)

        val bad = RequirementForm(minArea = "abc", maxBudget = "12x")
        val errors = assertIs<FormResult.Errors>(bad.build(base, CurrencyUnit.TOMAN)).errors
        assertEquals(mapOf("minArea" to "invalid_number", "maxBudget" to "invalid_amount"), errors)
    }

    @Test fun sellerFormNeverTouchesConfidentialValuesWithoutAccess() {
        val base = SellerProfile("s", "A", "c", "p", confidential = SellerConfidential(minAcceptablePrice = Money.ofToman(9_000_000_000)), updatedAt = 0)
        val form = SellerForm(minPrice = "1", motivation = "مهاجرت")
        val kept = assertIs<FormResult.Ok<SellerProfile>>(form.build(base, CurrencyUnit.TOMAN, canEditConfidential = false)).value
        assertEquals(Money.ofToman(9_000_000_000), kept.confidential.minAcceptablePrice)
        assertEquals("مهاجرت", kept.motivation)
        val changed = assertIs<FormResult.Ok<SellerProfile>>(form.build(base, CurrencyUnit.TOMAN, canEditConfidential = true)).value
        assertEquals(Money.ofToman(1), changed.confidential.minAcceptablePrice)
    }

    @Test fun propertyFormRoundTripsWithoutChangingStoredValues() {
        val p = Property(
            "p", "A", "IR-000123", "پونک ۹۵ متر", PropertyCategory.APARTMENT, setOf(DealType.SALE),
            askingPrice = Money.ofToman(9_500_000_000), builtArea = 95.5, floor = -1, bedrooms = 2, createdAt = 0, updatedAt = 0,
        )
        val form = PropertyForm.from(p, CurrencyUnit.TOMAN, persianDigits = true)
        assertEquals(p, assertIs<FormResult.Ok<Property>>(form.build(p, CurrencyUnit.TOMAN)).value)
        val rial = PropertyForm.from(p, CurrencyUnit.RIAL, persianDigits = false)
        assertEquals("95,000,000,000", rial.askingPrice)
        assertEquals(p, assertIs<FormResult.Ok<Property>>(rial.build(p, CurrencyUnit.RIAL)).value)
    }

    @Test fun appointmentTimesAreTehranLocalAndStoredAsUtc() {
        val day = JalaliDate(1405, 7, 25).toEpochDay()
        val base = Appointment("a", "A", AppointmentType.VIEWING, "", 0, updatedAt = 0)
        val a = assertIs<FormResult.Ok<Appointment>>(AppointmentForm(title = "بازدید", day = day, time = "۱۶:۰۰", durationMinutes = "45").build(base, zone)).value
        val local = java.time.Instant.ofEpochMilli(a.startsAt).atZone(zone)
        assertEquals(16, local.hour)
        assertEquals(LocalDate.ofEpochDay(day), local.toLocalDate())
        assertEquals(a.startsAt + 45 * 60_000L, a.endsAt)
        val missing = assertIs<FormResult.Errors>(AppointmentForm(title = "", day = day).build(base, zone)).errors
        assertEquals(setOf("title", "startsAt"), missing.keys)
    }

    @Test fun offerExpiresAtTheEndOfTheChosenDay() {
        val day = LocalDate.of(2026, 10, 20).toEpochDay()
        val base = Offer("o", "A", "d", "p", "b", dealType = DealType.SALE, offerDate = 0, updatedAt = 0)
        val o = assertIs<FormResult.Ok<Offer>>(OfferForm(price = "9,000,000,000", expiresOn = day, deposit = "5").build(base, zone)).value
        assertEquals(Money.ofToman(9_000_000_000), o.offeredPrice)
        assertNull(o.offeredDeposit)
        val end = java.time.Instant.ofEpochMilli(o.expiresAt!!).atZone(zone)
        assertEquals(LocalDate.of(2026, 10, 20), end.toLocalDate())
        assertEquals(23, end.hour)
    }

    @Test fun jalaliMonthGridStartsOnSaturday() {
        // 1 Farvardin 1405 is Saturday 21 March 2026.
        val m = CalendarMonth(CalendarSystem.JALALI, 1405, 1)
        assertEquals(LocalDate.of(2026, 3, 21).toEpochDay(), m.firstDay)
        assertEquals(0, m.leadingBlanks)
        assertEquals(31, m.length)
        // Esfand 1404 has 29 days (1404 is not a leap year) and the month after is Farvardin 1405.
        val esfand = m.previous()
        assertEquals(CalendarMonth(CalendarSystem.JALALI, 1404, 12), esfand)
        assertEquals(29, esfand.length)
        val weeks = esfand.weeks()
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(29, weeks.flatten().count { it != null })
        assertEquals(m, CalendarMonth.of(m.firstDay + 10, CalendarSystem.JALALI))
        val greg = CalendarMonth.of(LocalDate.of(2026, 10, 17).toEpochDay(), CalendarSystem.GREGORIAN)
        assertEquals(5, greg.leadingBlanks, "1 October 2026 is a Thursday: Sat, Sun, Mon, Tue, Wed come first")
    }

    @Test fun filtersNeverTreatMissingPricesAsMatching() {
        val a = Property("a", "A", "IR-000001", "a", PropertyCategory.APARTMENT, setOf(DealType.SALE), askingPrice = Money.ofToman(5), status = PropertyStatus.AVAILABLE, createdAt = 0, updatedAt = 0)
        val b = a.copy(id = "b", askingPrice = null)
        val c = a.copy(id = "c", status = PropertyStatus.SOLD)
        assertEquals(listOf("a"), PropertyFilter(maxPrice = Money.ofToman(10)).apply(listOf(a, b, c)).map { it.id })
        assertEquals(listOf("a", "b"), PropertyFilter().apply(listOf(a, b, c)).map { it.id })
        assertEquals(listOf("c"), PropertyFilter(status = PropertyStatus.SOLD).apply(listOf(a, b, c)).map { it.id })
        assertEquals(MatchFilter().apply(emptyList(), emptyMap(), 0), emptyList())
    }
}
