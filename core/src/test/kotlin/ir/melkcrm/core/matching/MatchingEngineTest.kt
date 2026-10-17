package ir.melkcrm.core.matching

import ir.melkcrm.core.Fx
import ir.melkcrm.core.i18n.ReasonRenderer
import ir.melkcrm.core.model.*
import ir.melkcrm.core.time.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchingEngineTest {
    private val engine = MatchingEngine()

    private fun eval(
        req: BuyerRequirement = Fx.purchase(),
        prop: Property = Fx.apartment(),
        seller: SellerProfile? = null,
        customer: Customer = Fx.customer(),
        engagement: EngagementSignals = EngagementSignals(),
    ) = engine.evaluate(BuyerCandidate(customer, req, engagement), PropertyCandidate(prop, seller), Fx.TODAY)

    private fun MatchEvaluation.codes() = (exclusions + reasons + concerns + info).map { it.code }.toSet()

    @Test fun idealMatchIsStrongOrBetterWithConsistentBreakdown() {
        val e = eval(
            req = Fx.purchase().copy(parking = RequirementLevel.REQUIRED, elevator = RequirementLevel.REQUIRED, negotiationFlexibility = FlexibilityLevel.HIGH),
            engagement = EngagementSignals(confirmedInterest = true),
        )
        assertTrue(e.eligible)
        assertTrue(e.score >= 85, "score ${e.score}")
        assertEquals(MatchClass.EXCELLENT, e.classification)
        assertEquals(e.score, e.components.sumOf { it.points }, "breakdown must add up to the displayed score")
        assertEquals(100, e.components.sumOf { it.weight })
        assertTrue(ReasonCode.WITHIN_BUDGET in e.codes())
        assertTrue(ReasonCode.PREFERRED_NEIGHBORHOOD in e.codes())
        assertTrue(ReasonCode.AREA_FITS in e.codes())
        assertFalse(e.provisional)
    }

    @Test fun breakdownAlwaysSumsToScoreAcrossManyInputs() {
        val prices = listOf(8.0, 10.0, 11.0, 12.0, 12.9, 13.0, 13.5, 14.0)
        val areas = listOf(70.0, 85.0, 100.0, 130.0, 170.0)
        for (p in prices) for (a in areas) {
            val e = eval(prop = Fx.apartment(price = Fx.billion(p), area = a))
            assertEquals(e.score, e.components.sumOf { it.points }, "price $p area $a")
            assertTrue(e.score in 0..100)
            e.components.forEach { assertTrue(it.points <= it.weight) }
        }
    }

    @Test fun budgetBelowAskingPriceIsExcludedWhenFarAbove() {
        val e = eval(req = Fx.purchase(min = Fx.billion(8.0), max = Fx.billion(9.0), preferred = Fx.billion(8.5)))
        assertFalse(e.eligible)
        assertTrue(ReasonCode.OVER_BUDGET in e.exclusions.map { it.code })
        assertEquals(MatchClass.NOT_ELIGIBLE, e.classification)
    }

    @Test fun slightlyAboveBudgetOnNegotiablePropertyStaysAsConcern() {
        // 12B asking vs 11.5B max (4.3% over) and property negotiable
        val e = eval(req = Fx.purchase(min = Fx.billion(10.0), max = Fx.billion(11.5), preferred = Fx.billion(11.0)))
        assertTrue(e.eligible)
        assertTrue(ReasonCode.ABOVE_MAX_NEGOTIATION_NEEDED in e.codes())
        assertTrue(e.component(MatchComponent.BUDGET).fraction < 0.2)
    }

    @Test fun nonNegotiablePropertyAboveCeilingIsExcluded() {
        val e = eval(req = Fx.purchase(min = Fx.billion(10.0), max = Fx.billion(11.5), preferred = Fx.billion(11.0)), prop = Fx.apartment().copy(negotiable = false))
        assertFalse(e.eligible)
    }

    @Test fun budgetAboveAskingPriceIsFullBudgetScore() {
        val e = eval(req = Fx.purchase(min = Fx.billion(12.0), max = Fx.billion(16.0), preferred = Fx.billion(14.0)))
        assertEquals(1.0, e.component(MatchComponent.BUDGET).fraction)
        assertTrue(ReasonCode.AT_OR_BELOW_PREFERRED_BUDGET in e.codes())
    }

    @Test fun priceBelowBuyerMinimumIsAConcernNotAnExclusion() {
        val e = eval(req = Fx.purchase(min = Fx.billion(13.0), max = Fx.billion(15.0), preferred = null))
        assertTrue(e.eligible)
        assertTrue(ReasonCode.BELOW_MIN_BUDGET in e.codes())
    }

    @Test fun stretchIsOnlyUsedWhenExplicit() {
        val base = Fx.purchase(min = Fx.billion(10.0), max = Fx.billion(11.0), preferred = null)
        val withStretch = eval(req = base.copy(budgetFlexible = true, maxStretch = Fx.billion(1.5)), prop = Fx.apartment().copy(negotiable = false))
        assertTrue(withStretch.eligible)
        assertTrue(ReasonCode.ABOVE_MAX_WITHIN_STRETCH in withStretch.codes())
        // flexible but no stretch amount: the flag alone does not raise the ceiling
        val flagOnly = eval(req = base.copy(budgetFlexible = true), prop = Fx.apartment().copy(negotiable = false))
        assertFalse(flagOnly.eligible)
    }

    @Test fun missingBudgetIsNeverTreatedAsUnlimited() {
        val e = eval(req = Fx.purchase(min = null, max = null, preferred = null))
        assertTrue(e.eligible)
        assertEquals(MatchingEngine.UNKNOWN, e.component(MatchComponent.BUDGET).fraction)
        assertTrue(e.provisional)
        assertTrue(MissingField.BUYER_BUDGET in e.missing.map { it.field })
        assertEquals(RecommendedAction.COLLECT_MISSING_INFO, e.recommendedAction)
        assertTrue(e.confidence < 0.8)
        val full = eval()
        assertTrue(e.score < full.score)
    }

    @Test fun missingAskingPriceIsProvisional() {
        val e = eval(prop = Fx.apartment(price = null))
        assertTrue(e.provisional)
        assertTrue(MissingField.PROPERTY_PRICE in e.missing.map { it.field })
    }

    @Test fun propertyOutsidePreferredCityLosesLocationScore() {
        val e = eval(prop = Fx.apartment(city = "کرج", neighborhood = "گوهردشت", location = null))
        assertTrue(e.eligible, "not strict, so not excluded")
        assertEquals(0.0, e.component(MatchComponent.LOCATION).fraction)
        assertTrue(ReasonCode.OUTSIDE_PREFERRED_LOCATION in e.codes())
    }

    @Test fun strictLocationExcludesOutsideProperty() {
        val e = eval(req = Fx.purchase().copy(locationStrict = true), prop = Fx.apartment(neighborhood = "نارمک"))
        assertFalse(e.eligible)
        assertTrue(ReasonCode.OUTSIDE_REQUIRED_LOCATION in e.exclusions.map { it.code })
    }

    @Test fun excludedNeighborhoodIsHardConstraint() {
        val e = eval(req = Fx.purchase(neighborhoods = emptyList()).copy(excludedNeighborhoods = listOf("سعادت‌آباد")))
        assertFalse(e.eligible)
        assertTrue(ReasonCode.EXCLUDED_NEIGHBORHOOD in e.exclusions.map { it.code })
    }

    @Test fun alternativeNeighborhoodScoresBelowPreferred() {
        val alt = eval(req = Fx.purchase(neighborhoods = listOf("پونک")).copy(alternativeNeighborhoods = listOf("سعادت آباد")))
        assertEquals(0.75, alt.component(MatchComponent.LOCATION).fraction)
    }

    @Test fun mapAreaDistanceWorksAndMissingCoordinatesDoNotCrash() {
        val area = GeoArea("نزدیک", GeoPoint(35.785, 51.375), radiusKm = 1.0)
        val req = Fx.purchase(neighborhoods = emptyList()).copy(cities = emptyList(), mapAreas = listOf(area))
        val inside = eval(req = req)
        assertEquals(1.0, inside.component(MatchComponent.LOCATION).fraction)
        assertTrue(ReasonCode.INSIDE_MAP_AREA in inside.codes())

        val near = eval(req = req, prop = Fx.apartment(location = Location(GeoPoint(35.785, 51.42), LocationSource.MANUAL_PIN))) // ~4 km east
        val f = near.component(MatchComponent.LOCATION).fraction
        assertTrue(f > 0.0 && f < 0.8, "fraction $f")

        val noCoords = eval(req = req, prop = Fx.apartment(location = null))
        assertTrue(noCoords.eligible)
        assertTrue(MissingField.PROPERTY_COORDINATES in noCoords.missing.map { it.field })
        assertEquals(MatchingEngine.UNKNOWN, noCoords.component(MatchComponent.LOCATION).fraction)
    }

    @Test fun mandatoryElevatorNotMetExcludes() {
        val e = eval(req = Fx.purchase().copy(elevator = RequirementLevel.REQUIRED), prop = Fx.apartment(elevator = false))
        assertFalse(e.eligible)
        val ex = e.exclusions.single()
        assertEquals(ReasonCode.MANDATORY_FEATURE_MISSING, ex.code)
        assertEquals(listOf("ELEVATOR"), ex.keys)
    }

    @Test fun preferredElevatorNotMetIsOnlyAConcern() {
        val e = eval(req = Fx.purchase().copy(elevator = RequirementLevel.PREFERRED), prop = Fx.apartment(elevator = false))
        assertTrue(e.eligible)
        assertTrue(ReasonCode.FEATURE_MISSING in e.codes())
    }

    @Test fun requiredFeatureUnknownIsProvisionalNotExcluded() {
        val e = eval(req = Fx.purchase().copy(elevator = RequirementLevel.REQUIRED), prop = Fx.apartment(elevator = null))
        assertTrue(e.eligible)
        assertTrue(e.provisional)
        assertTrue(e.missing.any { it.field == MissingField.PROPERTY_REQUIRED_FEATURE && it.detail == "ELEVATOR" })
    }

    @Test fun inactiveBuyerIsExcluded() {
        val e = eval(customer = Fx.customer(status = CustomerStatus.INACTIVE))
        assertFalse(e.eligible)
        assertTrue(ReasonCode.CUSTOMER_INACTIVE in e.exclusions.map { it.code })
        val paused = eval(req = Fx.purchase().copy(status = RequirementStatus.PAUSED))
        assertFalse(paused.eligible)
    }

    @Test fun soldPropertyIsExcluded() {
        val e = eval(prop = Fx.apartment(status = PropertyStatus.SOLD))
        assertFalse(e.eligible)
        assertTrue(ReasonCode.PROPERTY_NOT_AVAILABLE in e.exclusions.map { it.code })
    }

    @Test fun reservedPropertyOnlyWhenExplicitlyOpen() {
        assertFalse(eval(prop = Fx.apartment(status = PropertyStatus.RESERVED)).eligible)
        val open = eval(prop = Fx.apartment(status = PropertyStatus.RESERVED).copy(openToFurtherOffers = true))
        assertTrue(open.eligible)
        assertTrue(ReasonCode.BACKUP_CANDIDATE in open.codes())
    }

    @Test fun dealTypeAndCategoryMismatchExclude() {
        assertFalse(eval(prop = Fx.rental()).eligible)
        assertFalse(eval(prop = Fx.apartment().copy(category = PropertyCategory.OFFICE)).eligible)
    }

    @Test fun buyerIsNotMatchedToOwnProperty() {
        val e = eval(customer = Fx.customer(id = "owner-1"), req = Fx.purchase(customerId = "owner-1"))
        assertTrue(ReasonCode.OWN_PROPERTY in e.exclusions.map { it.code })
    }

    @Test fun rentalComparesDepositAndRentSeparately() {
        val ok = eval(req = Fx.rentRequirement(), prop = Fx.rental())
        assertTrue(ok.eligible)
        assertTrue(ReasonCode.DEPOSIT_WITHIN_LIMIT in ok.codes())
        assertTrue(ReasonCode.RENT_WITHIN_LIMIT in ok.codes())
        assertEquals(1.0, ok.component(MatchComponent.BUDGET).fraction)

        // A big deposit allowance does not compensate for rent far above the limit.
        val rentTooHigh = eval(req = Fx.rentRequirement(maxDeposit = Fx.billion(5.0), maxRent = Fx.toman(20_000_000)), prop = Fx.rental())
        assertFalse(rentTooHigh.eligible)
        assertTrue(ReasonCode.OVER_RENT_LIMIT in rentTooHigh.exclusions.map { it.code })

        val missingRent = eval(req = Fx.rentRequirement(maxRent = null), prop = Fx.rental())
        assertTrue(MissingField.BUYER_RENT_LIMIT in missingRent.missing.map { it.field })
        assertTrue(missingRent.provisional)
        assertEquals(2, missingRent.negotiation.size)
    }

    @Test fun fullDepositUsesFullDepositAmountOnly() {
        val prop = Fx.apartment(price = null).copy(dealTypes = setOf(DealType.FULL_DEPOSIT), fullDepositAmount = Fx.billion(3.0))
        val req = Fx.rentRequirement(maxDeposit = Fx.billion(3.2), maxRent = null).copy(dealType = DealType.FULL_DEPOSIT)
        val e = eval(req = req, prop = prop)
        assertTrue(e.eligible)
        assertEquals(1.0, e.component(MatchComponent.BUDGET).fraction)
    }

    @Test fun negotiationRangeUsesConfidentialMinimumWithoutLeakingIt() {
        val min = Fx.billion(11.3)
        val req = Fx.purchase(min = Fx.billion(10.0), max = Fx.billion(11.5), preferred = Fx.billion(11.0))
        val e = eval(req = req, prop = Fx.apartment().copy(negotiable = false), seller = Fx.seller(min = min))
        // Asking 12B is above the 11.5B ceiling, but the seller's minimum fits: eligible via negotiation.
        assertTrue(e.eligible)
        val n = e.negotiation.single()
        assertEquals(NegotiationStatus.FEASIBLE, n.status)
        assertEquals(min.rial, n.rangeLow)
        assertEquals(Fx.billion(11.5).rial, n.rangeHigh)
        // No explanation line carries the confidential minimum.
        val allAmounts = (e.reasons + e.concerns + e.exclusions + e.info).flatMap { it.amounts }
        assertFalse(min.rial in allAmounts)
        val texts = (e.reasons + e.concerns).map { ReasonRenderer(AppLanguage.ENGLISH).render(it) }
        assertTrue(texts.none { it.contains("11.3") })
    }

    @Test fun negotiationNotFeasibleWhenMinimumAboveCeiling() {
        val req = Fx.purchase(min = Fx.billion(10.0), max = Fx.billion(11.5), preferred = Fx.billion(11.0))
        val e = eval(req = req, seller = Fx.seller(min = Fx.billion(11.9)))
        assertFalse(e.eligible, "seller minimum above the buyer's ceiling makes the deal impossible")
        assertEquals(NegotiationStatus.NOT_FEASIBLE, e.negotiation.single().status)
        assertEquals(Fx.billion(0.4).rial, e.negotiation.single().gap)
    }

    @Test fun negotiationUnknownWithoutConfidentialData() {
        assertEquals(NegotiationStatus.UNKNOWN, eval().negotiation.single().status)
    }

    @Test fun areaAndBedroomStrictness() {
        val small = Fx.apartment(area = 80.0)
        val soft = eval(prop = small)
        assertTrue(soft.eligible)
        assertTrue(ReasonCode.AREA_BELOW_MIN in soft.codes())
        assertFalse(eval(req = Fx.purchase().copy(areaStrict = true), prop = small).eligible)
        assertFalse(eval(req = Fx.purchase().copy(bedroomsStrict = true), prop = Fx.apartment(bedrooms = 1)).eligible)
    }

    @Test fun timingPrefersBuyersWithinSellerDeadline() {
        val fast = eval(seller = Fx.seller(deadline = Fx.TODAY + 60))
        val slow = eval(req = Fx.purchase().copy(urgency = Urgency.WITHIN_6_MONTHS), seller = Fx.seller(deadline = Fx.TODAY + 60))
        assertTrue(ReasonCode.FITS_SELLER_DEADLINE in fast.codes())
        assertTrue(ReasonCode.BUYER_LATER_THAN_DEADLINE in slow.codes())
        assertTrue(fast.component(MatchComponent.TIMING).fraction > slow.component(MatchComponent.TIMING).fraction)
    }

    @Test fun readinessIsLimitedSoUnresponsiveBuyersStayListed() {
        val responsive = eval(engagement = EngagementSignals(confirmedInterest = true, requestedViewing = true))
        val silent = eval(engagement = EngagementSignals(unansweredFollowUps = 5))
        assertTrue(silent.eligible)
        assertTrue(responsive.score - silent.score <= MatchWeights().readiness)
        assertTrue(ReasonCode.UNRESPONSIVE in silent.codes())
        assertEquals(RecommendedAction.SCHEDULE_VIEWING, responsive.recommendedAction)
    }

    @Test fun flexibilityIsNeverInferred() {
        val e = eval(prop = Fx.apartment().copy(negotiable = null))
        assertEquals(0.5, e.component(MatchComponent.FLEXIBILITY).fraction)
        assertTrue(ReasonCode.NO_FLEXIBILITY_INFO in e.codes())
    }

    @Test fun configurableWeightsAndThresholds() {
        val budgetHeavy = MatchingEngine(MatchingConfig(weights = MatchWeights(budget = 60, location = 10, layout = 10, features = 5, timing = 5, flexibility = 5, readiness = 5)))
        val e = budgetHeavy.evaluate(BuyerCandidate(Fx.customer(), Fx.purchase()), PropertyCandidate(Fx.apartment()), Fx.TODAY)
        assertEquals(60, e.component(MatchComponent.BUDGET).weight)
        assertEquals(e.score, e.components.sumOf { it.points })
        assertFailsWith<IllegalArgumentException> { MatchingEngine(MatchingConfig(weights = MatchWeights(budget = 50))) }
        assertFailsWith<IllegalArgumentException> { MatchingEngine(MatchingConfig(thresholds = MatchThresholds(excellent = 60, strong = 70))) }
        val lenient = MatchThresholds(excellent = 70, strong = 50, possible = 30)
        assertEquals(MatchClass.EXCELLENT, lenient.classify(72))
    }

    @Test fun rankingOrdersByScore() {
        val buyers = listOf(
            BuyerCandidate(Fx.customer("a"), Fx.purchase(id = "qa", customerId = "a")),
            BuyerCandidate(Fx.customer("b"), Fx.purchase(id = "qb", customerId = "b", neighborhoods = listOf("پونک"))),
            BuyerCandidate(Fx.customer("c"), Fx.purchase(id = "qc", customerId = "c", min = Fx.billion(5.0), max = Fx.billion(6.0), preferred = null)),
        )
        val ranked = engine.findBuyers(PropertyCandidate(Fx.apartment()), buyers, Fx.TODAY)
        assertEquals(listOf("a", "b"), ranked.map { it.customerId })
        val withExcluded = engine.findBuyers(PropertyCandidate(Fx.apartment()), buyers, Fx.TODAY, includeExcluded = true)
        assertEquals("c", withExcluded.last().customerId)
        assertFalse(withExcluded.last().eligible)
    }

    @Test fun fingerprintChangesWhenInputsChange() {
        val a = eval()
        val b = eval(prop = Fx.apartment().copy(updatedAt = Fx.NOW + 1, version = 2))
        val c = eval()
        assertEquals(a.fingerprint, c.fingerprint)
        assertTrue(a.fingerprint != b.fingerprint)
    }

    @Test fun everyReasonRendersInBothLanguages() {
        val e = eval(req = Fx.purchase().copy(elevator = RequirementLevel.REQUIRED), prop = Fx.apartment(elevator = false))
        val fa = ReasonRenderer(AppLanguage.PERSIAN)
        val en = ReasonRenderer(AppLanguage.ENGLISH)
        for (r in e.exclusions + e.reasons + e.concerns + e.info) {
            assertTrue(fa.render(r).isNotBlank())
            assertTrue(en.render(r).isNotBlank())
        }
        assertEquals("آسانسور الزامی است ولی ملک ندارد", fa.render(e.exclusions.single()))
        // Every code has a rendering (when-expressions are exhaustive, this guards against "?" placeholders)
        for (code in ReasonCode.entries) {
            val r = Reason(code, amounts = listOf(10L, 20L), numbers = listOf(1.0, 2.0), keys = listOf("AVAILABLE"))
            assertNotNull(fa.render(r)); assertNotNull(en.render(r))
        }
    }
}
