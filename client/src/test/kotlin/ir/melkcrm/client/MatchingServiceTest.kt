package ir.melkcrm.client

import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.matching.Outreach
import ir.melkcrm.client.sample.SampleLoader
import ir.melkcrm.core.matching.MatchClass
import ir.melkcrm.core.matching.MatchRejectionReason
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.matching.matchKey
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingServiceTest {

    private suspend fun withSample(): TestCrm {
        val t = TestCrm()
        assertIs<SampleLoader.Result.Loaded>(t.crm.samples.load())
        return t
    }

    @Test fun sampleDataProducesTheSpecificationRanking() = runTest {
        val t = withSample()
        val ranked = t.crm.matching.buyersFor("prop-0001")
        val order = ranked.map { it.customerId }
        assertEquals("cust-0002", order.first(), "buyer A ranks first")
        assertTrue("cust-0003" !in order || order.indexOf("cust-0003") > 0, "buyer B, if eligible, ranks below A")
        assertFalse("cust-0004" in order, "buyer C (east Tehran only) is excluded")
        val withExcluded = t.crm.matching.buyersFor("prop-0001", includeExcluded = true)
        val c = withExcluded.first { it.customerId == "cust-0004" }
        assertFalse(c.eligible)
        assertTrue(c.exclusions.isNotEmpty(), "excluded buyers come with a reason")
    }

    @Test fun recalculationStoresRecordsAndKeepsReviewState() = runTest {
        val t = withSample()
        val evals = t.crm.matching.recalculateAll()
        val key = matchKey("req-0001", "prop-0001")
        assertTrue(evals.getValue(key).eligible)
        val stored = t.crm.matching.records.first()
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.all { it.eligible && it.score >= 50 }, "only matches at or above 'possible' are stored")
        assertTrue(t.store.outbox().none { it.type == EntityType.MATCH_RECORD }, "sample-derived records are created locally but sample data is never uploaded")

        assertIs<SaveOutcome.Saved<*>>(t.crm.matching.review(key, MatchReviewStatus.APPROVED_FOR_CONTACT, "call tonight"))
        val before = t.crm.matching.record(key)!!

        // The asking price rises: the score changes but the agent's decision is kept and flagged as updated.
        val p = t.crm.properties.property("prop-0001")!!
        t.crm.writer.persist(EntityType.PROPERTY, p.copy(askingPrice = Money.ofToman(12_900_000_000), updatedAt = t.clock + 1))
        t.crm.matching.recalculateAll()
        val after = t.crm.matching.record(key)!!
        assertEquals(MatchReviewStatus.APPROVED_FOR_CONTACT, after.reviewStatus)
        assertTrue(after.score < before.score)
        assertTrue(after.changedSinceReview)
        assertEquals(1, after.history.size)
    }

    @Test fun reviewRulesAreEnforced() = runTest {
        val t = withSample()
        t.crm.matching.recalculateAll()
        val key = matchKey("req-0001", "prop-0001")
        assertIs<SaveOutcome.Invalid>(t.crm.matching.review(key, MatchReviewStatus.REJECTED), "rejection needs a reason")
        assertIs<SaveOutcome.Invalid>(t.crm.matching.review(key, MatchReviewStatus.SNOOZED, snoozedUntil = t.crm.writer.today()), "snooze needs a future date")
        assertIs<SaveOutcome.Saved<*>>(t.crm.matching.review(key, MatchReviewStatus.REJECTED, rejectionReason = MatchRejectionReason.PRICE_TOO_HIGH))
        assertEquals(MatchRejectionReason.PRICE_TOO_HIGH, t.crm.matching.record(key)!!.rejectionReason)
    }

    @Test fun editsTriggerPartialRecalculation() = runTest {
        val t = TestCrm()
        t.crm.customers.save(t.customer(id = "b1"))
        val req = t.crm.customers.newRequirement("b1").copy(
            id = "r1", categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"), neighborhoods = listOf("پونک"),
            minArea = 80.0, maxArea = 110.0, maxBudget = Money.ofToman(9_000_000_000),
        )
        t.crm.customers.saveRequirement(req)
        val p = t.crm.properties.newProperty().copy(
            id = "p1", title = "پونک", city = "تهران", neighborhood = "پونک", builtArea = 95.0, bedrooms = 2,
            askingPrice = Money.ofToman(8_500_000_000), status = PropertyStatus.AVAILABLE,
        )
        t.crm.properties.save(p)
        assertTrue(t.crm.matching.state.value.evaluations.isEmpty())
        t.crm.matching.propertiesFor("r1")
        val rec = t.crm.matching.record(matchKey("r1", "p1"))
        assertNotNull(rec)
        assertTrue(rec.classification != MatchClass.NOT_ELIGIBLE)
        // Over the buyer's ceiling and not negotiable: the stored match becomes ineligible.
        t.crm.properties.save(p.copy(askingPrice = Money.ofToman(11_000_000_000), negotiable = false))
        t.crm.matching.recalculateAll()
        assertFalse(t.crm.matching.record(matchKey("r1", "p1"))!!.eligible)
    }

    @Test fun engagementComesOnlyFromRecordedHistory() = runTest {
        val t = TestCrm()
        val c = t.customer(id = "c1")
        val acts = t.crm.activities
        val calls = (1..3).map { i -> acts.newActivity(ActivityType.PHONE_CALL, "c1").copy(id = "a$i", occurredAt = t.clock - i * 1000, outcome = ActivityOutcome.NO_ANSWER) }
        val e = acts.engagement(c, calls, emptyList(), emptyList(), t.clock)
        assertEquals(3, e.unansweredFollowUps)
        assertFalse(e.confirmedInterest)
        assertFalse(e.requestedViewing)
        val answered = calls + acts.newActivity(ActivityType.PHONE_CALL, "c1").copy(id = "a0", occurredAt = t.clock, outcome = ActivityOutcome.INTERESTED)
        val e2 = acts.engagement(c, answered, emptyList(), emptyList(), t.clock)
        assertEquals(0, e2.unansweredFollowUps)
        assertTrue(e2.confirmedInterest)
    }

    @Test fun outreachRespectsConsentAndNeverIncludesConfidentialData() = runTest {
        val t = withSample()
        t.crm.matching.recalculateAll()
        val key = matchKey("req-0001", "prop-0001")
        val buyer = t.crm.customers.customer("cust-0002")!!
        t.crm.writer.persist(EntityType.CUSTOMER, buyer.copy(consent = buyer.consent.copy(smsAllowed = false, callsAllowed = true)), sample = true)
        assertEquals("consent", assertIs<SaveOutcome.Forbidden>(t.crm.outreach.recordContact(key, ContactMethod.SMS, ActivityOutcome.SUCCESSFUL, null)).reason)
        assertIs<SaveOutcome.Saved<*>>(t.crm.outreach.recordContact(key, ContactMethod.PHONE_CALL, ActivityOutcome.INTERESTED, "liked it"))
        assertEquals(MatchReviewStatus.CONTACTED, t.crm.matching.record(key)!!.reviewStatus)

        val p = t.crm.properties.property("prop-0001")!!.copy(confidentialNotes = "مالک بدهکار است")
        val text = Outreach.propertySummary(p, AppLanguage.PERSIAN, CurrencyUnit.TOMAN)
        val seller = t.crm.customers.sellerForProperty(p.id)!!
        assertTrue(text.contains("IR-000123".let { ir.melkcrm.core.text.Digits.toPersian(it) }) || text.contains("IR-۰۰۰۱۲۳"))
        assertFalse(text.contains("مالک بدهکار"), "confidential notes")
        assertFalse(text.contains(p.address!!), "exact address")
        assertFalse(text.contains("۱۱,۳۰۰,۰۰۰,۰۰۰"), "seller minimum ${seller.confidential.minAcceptablePrice}")
        assertTrue(text.contains("۱۲,۰۰۰,۰۰۰,۰۰۰ تومان"), "listed price is public")
        assertNull(Regex("09\\d{9}").find(ir.melkcrm.core.text.Digits.toAscii(text)), "no phone numbers")
    }
}
