package ir.melkcrm.core.validation

import ir.melkcrm.core.Fx
import ir.melkcrm.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationTest {
    private fun codes(r: ValidationResult) = r.errors.map { "${it.field}:${it.code}" }.toSet()

    @Test fun validCustomer() = assertTrue(CustomerValidator.validate(Fx.customer()).isValid)

    @Test fun customerRequiresNamePhoneAndRole() {
        val r = CustomerValidator.validate(Fx.customer(mobile = null).copy(fullName = " ", roles = emptySet()))
        assertEquals(setOf("fullName:required", "mobile:phone_required", "roles:role_required"), codes(r))
    }

    @Test fun customerPhoneAndEmailFormats() {
        val r = CustomerValidator.validate(Fx.customer(mobile = "12345").copy(email = "not-an-email", altPhone = "abc"))
        assertEquals(setOf("mobile:invalid_phone", "email:invalid_email", "altPhone:invalid_phone"), codes(r))
        val landline = CustomerValidator.validate(Fx.customer(mobile = "02122334455"))
        assertTrue(landline.isValid)
        assertEquals("not_a_mobile_number", landline.warnings.single().code)
    }

    @Test fun draftCustomerNeedsOnlyAPhoneOrName() {
        val draft = Fx.customer().copy(fullName = "", roles = emptySet(), isDraft = true)
        assertTrue(CustomerValidator.validate(draft).isValid)
    }

    @Test fun requirementRanges() {
        val r = BuyerRequirementValidator.validate(Fx.purchase(min = Fx.billion(14.0), max = Fx.billion(12.0), minArea = 150.0, maxArea = 100.0))
        assertTrue("minBudget:min_greater_than_max" in codes(r))
        assertTrue("minArea:min_greater_than_max" in codes(r))
        assertTrue("preferredBudget:preferred_below_min" in codes(r))
    }

    @Test fun requirementWarnsOnMissingBudgetAndRentFields() {
        val r = BuyerRequirementValidator.validate(Fx.purchase(min = null, max = null, preferred = null))
        assertTrue(r.isValid)
        assertTrue(r.warnings.any { it.code == "budget_missing" })
        val rent = BuyerRequirementValidator.validate(Fx.rentRequirement(maxDeposit = null, maxRent = null))
        assertEquals(setOf("deposit_limit_missing", "rent_limit_missing"), rent.warnings.map { it.code }.toSet())
    }

    @Test fun preferredAndExcludedNeighborhoodConflict() {
        val r = BuyerRequirementValidator.validate(Fx.purchase().copy(excludedNeighborhoods = listOf("سعادت‌آباد")))
        assertTrue("excludedNeighborhoods:preferred_and_excluded" in codes(r))
    }

    @Test fun validProperty() = assertTrue(PropertyValidator.validate(Fx.apartment(), Fx.TODAY).isValid)

    @Test fun propertyRules() {
        val p = Fx.apartment(price = null).copy(reference = "123", floor = 9, totalFloors = 5, constructionYear = 1500,
            location = Location(GeoPoint(48.85, 2.35), LocationSource.MANUAL_PIN))
        val r = PropertyValidator.validate(p, Fx.TODAY)
        assertEquals(setOf("reference:invalid_reference", "askingPrice:required_for_sale", "floor:floor_above_total", "constructionYear:implausible_year"), codes(r))
        assertTrue(r.warnings.any { it.code == "outside_iran" })
    }

    @Test fun draftPropertyOnlyWarnsAboutPrice() {
        val r = PropertyValidator.validate(Fx.apartment(price = null).copy(isDraft = true), Fx.TODAY)
        assertTrue(r.isValid)
        assertTrue(r.warnings.any { it.code == "required_for_sale" })
    }

    @Test fun rentalPropertyNeedsDepositOrRent() {
        assertFalse(PropertyValidator.validate(Fx.rental(deposit = null, rent = null), Fx.TODAY).isValid)
        assertTrue(PropertyValidator.validate(Fx.rental(), Fx.TODAY).isValid)
    }

    @Test fun offerFieldsDependOnDealType() {
        val sale = Offer("o", Fx.AGENCY, "d", "p1", "c1", dealType = DealType.SALE, offeredPrice = null, offeredMonthlyRent = Fx.toman(10), offerDate = 10, updatedAt = 10)
        assertEquals(setOf("offeredPrice:required", "offeredMonthlyRent:rent_on_sale_offer"), codes(OfferValidator.validate(sale)))
        val rent = sale.copy(dealType = DealType.RENT, offeredPrice = Fx.toman(5), offeredMonthlyRent = Fx.toman(10))
        assertEquals(setOf("offeredPrice:price_on_rent_offer"), codes(OfferValidator.validate(rent)))
    }
}
