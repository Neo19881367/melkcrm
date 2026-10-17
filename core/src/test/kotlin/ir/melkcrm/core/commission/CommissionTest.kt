package ir.melkcrm.core.commission

import ir.melkcrm.core.Fx
import ir.melkcrm.core.model.CommissionParty
import ir.melkcrm.core.model.CommissionRecord
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.money.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommissionTest {
    @Test fun percentageOfSalePrice() {
        val rule = CommissionRule("r", Fx.AGENCY, "فروش", DealType.SALE, CommissionParty.SELLER, priceBasisPoints = 50)
        val e = CommissionCalculator.estimate(rule, price = Fx.billion(12.0))
        assertEquals(Fx.toman(60_000_000), e.total)
        assertTrue(e.isEstimate)
    }

    @Test fun rentalRuleUsesDepositAndRentSeparately() {
        val rule = CommissionRule("r", Fx.AGENCY, "اجاره", DealType.RENT, CommissionParty.LANDLORD, depositBasisPoints = 25, monthlyRentBasisPoints = 2500)
        val e = CommissionCalculator.estimate(rule, deposit = Fx.billion(1.0), monthlyRent = Fx.toman(30_000_000))
        assertEquals(Fx.toman(2_500_000 + 7_500_000), e.total)
    }

    @Test fun tiersMinMaxAndTax() {
        val rule = CommissionRule("r", Fx.AGENCY, "پلکانی", DealType.SALE, CommissionParty.BUYER,
            tiers = listOf(CommissionTier(Money.ZERO, 100), CommissionTier(Fx.billion(10.0), 50)),
            maximum = Fx.toman(200_000_000), taxBasisPoints = 1000)
        val e = CommissionCalculator.estimate(rule, price = Fx.billion(12.0))
        // 1% of 10B + 0.5% of 2B = 110M, + 10% levy
        assertEquals(Fx.toman(110_000_000), e.base)
        assertEquals(Fx.toman(121_000_000), e.total)
        val capped = CommissionCalculator.estimate(rule, price = Fx.billion(40.0))
        assertEquals(Fx.toman(200_000_000), capped.base)
    }

    @Test fun ruleValidation() {
        val bad = CommissionRule("r", Fx.AGENCY, "", DealType.SALE, CommissionParty.SELLER, depositBasisPoints = 10, minimum = Fx.toman(10), maximum = Fx.toman(5))
        assertEquals(setOf("name_required", "min_above_max", "rent_rates_on_sale_rule"), bad.validate().toSet())
    }

    @Test fun outstandingAndEstimateFlags() {
        val rec = CommissionRecord("c", Fx.AGENCY, propertyId = "p1", party = CommissionParty.SELLER, expectedAmount = Fx.toman(60_000_000), updatedAt = 0)
        assertTrue(rec.isEstimate)
        assertEquals(Money.ZERO, rec.outstanding)
        val earned = rec.copy(earnedAmount = Fx.toman(55_000_000), receivedAmount = Fx.toman(20_000_000))
        assertEquals(Fx.toman(35_000_000), earned.outstanding)
    }
}
