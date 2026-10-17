package ir.melkcrm.client

import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.core.commission.CommissionRule
import ir.melkcrm.core.duplicates.DuplicateStrength
import ir.melkcrm.core.matching.MatchingConfig
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.sync.EntityType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {

    @Test fun customerSaveValidatesNormalizesQueuesAndAudits() = runTest {
        val t = TestCrm()
        val repo = t.crm.customers
        assertIs<SaveOutcome.Invalid>(repo.save(t.customer(mobile = "12345")))
        val c = t.customer(id = "c1", mobile = "+98 912 123 4567")
        val saved = assertIs<SaveOutcome.Saved<Customer>>(repo.save(c))
        assertEquals("09121234567", saved.value.mobile)

        val first = t.store.outboxEntry(EntityType.CUSTOMER, "c1")
        assertNotNull(first)
        assertEquals(0, first.baseVersion)
        assertEquals(AuditAction.CREATE, t.store.audit("c1").single().action)

        t.clock += 1000
        repo.save(saved.value.copy(city = "کرج", status = CustomerStatus.QUALIFIED.name))
        val second = t.store.outboxEntry(EntityType.CUSTOMER, "c1")!!
        assertEquals(1, t.store.outbox().size, "one outbox entry per record")
        assertNotEquals(first.changeId, second.changeId, "a new edit gets a new idempotency key")
        val update = t.store.audit("c1").first()
        assertEquals(AuditAction.STATUS_CHANGE, update.action)
        assertEquals(listOf("تهران", "کرج"), update.changes["city"])
    }

    @Test fun duplicatesNeedConfirmation() = runTest {
        val t = TestCrm()
        val repo = t.crm.customers
        repo.save(t.customer(id = "c1", mobile = "09121234567"))
        val dup = repo.save(t.customer(id = "c2", mobile = "0912 123 4567", name = "رضا احمدی"))
        val d = assertIs<SaveOutcome.PossibleDuplicates>(dup)
        assertEquals(DuplicateStrength.SAME_PHONE, d.candidates.single().strength)
        assertIs<SaveOutcome.Saved<Customer>>(repo.save(t.customer(id = "c2", mobile = "09121234567", name = "رضا احمدی"), confirmDuplicate = true))
    }

    @Test fun mergeMovesRelatedRecordsAndKeepsTheRetiredCustomer() = runTest {
        val t = TestCrm()
        val repo = t.crm.customers
        repo.save(t.customer(id = "c1"))
        repo.save(t.customer(id = "c2", mobile = "09351112222", name = "علی رضایی"), confirmDuplicate = true)
        repo.saveRequirement(repo.newRequirement("c2").copy(maxBudget = Money.ofToman(5_000_000_000), cities = listOf("تهران")))
        val p = t.crm.properties.newProperty().copy(title = "آپارتمان", ownerCustomerId = "c2", city = "تهران")
        t.crm.properties.save(p)
        t.crm.properties.addContact(p.id, "c2", PropertyContactRole.OWNER)
        t.crm.activities.log(t.crm.activities.newActivity(ActivityType.PHONE_CALL, "c2"))

        val result = assertIs<SaveOutcome.Saved<*>>(repo.merge("c1", "c2"))
        assertNotNull(result)
        assertNull(repo.customer("c2"), "retired customer is hidden")
        val retired = t.crm.writer.decode<Customer>(t.store.get(EntityType.CUSTOMER, "c2")!!)
        assertEquals("c1", retired.mergedIntoId)
        assertEquals("09351112222", repo.customer("c1")!!.altPhone, "second phone number kept")
        assertEquals("c1", repo.requirements.first().single().customerId)
        assertEquals("c1", t.crm.properties.property(p.id)!!.ownerCustomerId)
        assertEquals("c1", t.crm.activities.activities.first().single().customerId)
        assertEquals(listOf("c1"), t.crm.properties.contacts.first().map { it.customerId })
        assertTrue(t.store.audit("c1").any { it.action == AuditAction.MERGE })
    }

    @Test fun agentsCannotMergeOrEditOthersCustomers() = runTest {
        val t = TestCrm()
        t.signIn(Fx.agent1)
        val repo = t.crm.customers
        assertIs<SaveOutcome.Saved<Customer>>(repo.save(t.customer(id = "mine", agent = "agent1")))
        assertEquals("forbidden", assertIs<SaveOutcome.Forbidden>(repo.save(t.customer(id = "theirs", agent = "agent2", mobile = "09125556666"))).reason)
        assertEquals("merge_forbidden", assertIs<SaveOutcome.Forbidden>(repo.merge("mine", "x")).reason)
        val mine = repo.customer("mine")!!
        assertEquals("reassign_forbidden", assertIs<SaveOutcome.Forbidden>(repo.save(mine.copy(assignedAgentId = "agent2"))).reason)
        assertEquals("delete_forbidden", assertIs<SaveOutcome.Forbidden>(repo.delete("mine")).reason)
    }

    @Test fun requirementAddsBuyerOrTenantRole() = runTest {
        val t = TestCrm()
        val repo = t.crm.customers
        repo.save(t.customer(id = "c1").copy(roles = setOf(CustomerRole.SELLER)))
        assertIs<SaveOutcome.Saved<BuyerRequirement>>(repo.saveRequirement(repo.newRequirement("c1", DealType.RENT).copy(maxDeposit = Money.ofToman(1_000_000_000))))
        assertEquals(setOf(CustomerRole.SELLER, CustomerRole.TENANT), repo.customer("c1")!!.roles)
        val bad = repo.newRequirement("c1").copy(minArea = 120.0, maxArea = 80.0)
        assertEquals(listOf("min_greater_than_max"), assertIs<SaveOutcome.Invalid>(repo.saveRequirement(bad)).issues.filter { it.severity == ir.melkcrm.core.validation.Severity.ERROR }.map { it.code })
    }

    @Test fun sellerConfidentialFieldsCannotBeChangedWithoutAccess() = runTest {
        val t = TestCrm()
        t.signIn(Fx.agent1)
        val props = t.crm.properties
        val p = props.newProperty().copy(title = "ویلا", assignedAgentId = "agent2", city = "تهران")
        // agent1 cannot create a property for agent2 …
        assertIs<SaveOutcome.Forbidden>(props.save(p))
        // … but a property arriving from the server can be seen; its seller minimum stays as stored.
        t.crm.writer.persist(EntityType.PROPERTY, p, enqueue = false)
        val seller = t.crm.customers.newSeller("owner", p.id).copy(assignedAgentId = "agent2")
        t.crm.customers.save(t.customer(id = "owner"))
        val attempt = t.crm.customers.saveSeller(seller.copy(confidential = SellerConfidential(minAcceptablePrice = Money.ofToman(1))))
        assertIs<SaveOutcome.Forbidden>(attempt)
        assertFalse(props.canSeeConfidential(p))
    }

    @Test fun propertyReferencesStatusHistoryAndArchiving() = runTest {
        val t = TestCrm()
        val props = t.crm.properties
        val a = props.newProperty().copy(title = "الف", city = "تهران")
        assertEquals("IR-000001", a.reference)
        props.save(a)
        val b = props.newProperty()
        assertEquals("IR-000002", b.reference)
        val dup = assertIs<SaveOutcome.Saved<Property>>(props.save(b.copy(title = "ب", reference = "IR-000001")))
        assertTrue(dup.warnings.any { it.code == "duplicate_reference" })

        val invalid = props.save(a.copy(status = PropertyStatus.AVAILABLE, dealTypes = setOf(DealType.SALE)))
        assertTrue(assertIs<SaveOutcome.Invalid>(invalid).issues.any { it.field == "askingPrice" })

        props.archive(a.id)
        assertEquals(PropertyStatus.ARCHIVED, props.property(a.id)!!.status, "archived, not deleted")
        val history = t.store.audit(a.id)
        assertTrue(history.any { it.action == AuditAction.STATUS_CHANGE && it.changes["status"] == listOf("PROSPECT", "ARCHIVED") })
        assertTrue(props.search("", includeClosed = false).none { it.id == a.id })
        assertTrue(props.search("", includeClosed = true).any { it.id == a.id })
    }

    @Test fun searchFindsCustomersByPhoneNameAndPropertyReference() = runTest {
        val t = TestCrm()
        t.crm.customers.save(t.customer(id = "c1", name = "مهدی یوسفی", mobile = "09121112233"))
        t.crm.customers.save(t.customer(id = "c2", name = "سارا كريمي", mobile = "09354445566"))
        val p = t.crm.properties.newProperty().copy(title = "مغازه", ownerCustomerId = "c1")
        t.crm.properties.save(p)
        assertEquals(listOf("c1"), t.crm.customers.search("۰۹۱۲۱۱۱").map { it.id })
        assertEquals(listOf("c2"), t.crm.customers.search("+98 935 444").map { it.id })
        assertEquals(listOf("c2"), t.crm.customers.search("سارا کریمی").map { it.id }, "Arabic and Persian letter forms match")
        assertEquals(listOf("c1"), t.crm.customers.search(p.reference).map { it.id })
    }

    @Test fun activityUpdatesLastContactAndCreatesFollowUpTask() = runTest {
        val t = TestCrm()
        t.crm.customers.save(t.customer(id = "c1"))
        val acts = t.crm.activities
        acts.log(acts.newActivity(ActivityType.PHONE_CALL, "c1").copy(outcome = ActivityOutcome.NO_ANSWER))
        assertEquals(CustomerStatus.NEW_LEAD.name, t.crm.customers.customer("c1")!!.status, "an unanswered call is not a contact")
        t.clock += 60_000
        acts.log(acts.newActivity(ActivityType.PHONE_CALL, "c1").copy(outcome = ActivityOutcome.INTERESTED, nextAction = "ارسال فایل", nextActionDate = t.crm.writer.today() + 2))
        val c = t.crm.customers.customer("c1")!!
        assertEquals(t.clock, c.lastContactAt)
        assertEquals(CustomerStatus.CONTACTED.name, c.status)
        val task = acts.tasks.first().single()
        assertEquals("ارسال فایل", task.title)
        assertNotNull(task.reminderAt)
        val messenger = acts.log(acts.newActivity(ActivityType.MESSENGER_MANUAL, "c1"))
        assertEquals("channel", assertIs<SaveOutcome.Invalid>(messenger).issues.single().field)
    }

    @Test fun offerCounterofferAcceptanceAndTransaction() = runTest {
        val t = TestCrm()
        val rule = CommissionRule("r1", t.crm.session.user.agencyId, "Sale 0.5%", DealType.SALE, CommissionParty.BUYER, priceBasisPoints = 50)
        t.crm.session.updateAgencyConfig(AgencySettings(commissionRules = listOf(rule)), MatchingConfig())
        t.crm.customers.save(t.customer(id = "buyer"))
        t.crm.customers.save(t.customer(id = "owner", mobile = "09350000000", name = "مالک"))
        val req = t.crm.customers.newRequirement("buyer").copy(maxBudget = Money.ofToman(12_000_000_000), cities = listOf("تهران"))
        t.crm.customers.saveRequirement(req)
        val p = t.crm.properties.newProperty().copy(title = "آپارتمان", ownerCustomerId = "owner", askingPrice = Money.ofToman(12_500_000_000))
        t.crm.properties.save(p)
        t.crm.customers.saveSeller(t.crm.customers.newSeller("owner", p.id).copy(confidential = SellerConfidential(minAcceptablePrice = Money.ofToman(11_800_000_000))))

        val deals = t.crm.deals
        val deal = deals.openDeal(p.id, "buyer", req.id, DealType.SALE)
        assertEquals(deal.id, deals.openDeal(p.id, "buyer", req.id, DealType.SALE).id, "one open deal per buyer and property")
        val offer = assertIs<SaveOutcome.Saved<Offer>>(deals.recordOffer(deals.newOffer(deal).copy(offeredPrice = Money.ofToman(11_500_000_000)), submit = true)).value
        assertEquals(DealStage.OFFER_SUBMITTED, deals.deal(deal.id)!!.stage)

        val counter = assertIs<SaveOutcome.Saved<Offer>>(deals.counter(offer.id, Money.ofToman(12_000_000_000), null, null, null, null)).value
        assertEquals(OfferStatus.COUNTERED, deals.offer(offer.id)!!.status)
        assertEquals(OfferDirection.SELLER_TO_BUYER, counter.direction)
        assertEquals(offer.id, counter.parentOfferId)
        assertIs<SaveOutcome.Invalid>(deals.transitionOffer(offer.id, OfferStatus.ACCEPTED), "a countered offer cannot be accepted")
        deals.transitionOffer(counter.id, OfferStatus.ACCEPTED, "agreed")
        assertEquals(DealStage.AGREEMENT_REACHED, deals.deal(deal.id)!!.stage)
        assertTrue(t.store.audit(offer.id).any { it.changes["status"] == listOf("SUBMITTED", "COUNTERED") })

        val tx = assertIs<SaveOutcome.Saved<Transaction>>(deals.completeTransaction(deal.id, Money.ofToman(11_900_000_000), null, null, t.crm.writer.today(), null, null)).value
        assertEquals(Money.ofToman(11_900_000_000), tx.finalPrice)
        assertEquals(Money.ofToman(12_500_000_000), t.crm.properties.property(p.id)!!.askingPrice, "asking price is kept separately")
        assertEquals(PropertyStatus.SOLD, t.crm.properties.property(p.id)!!.status)
        assertEquals(RequirementStatus.FULFILLED, t.crm.customers.requirement(req.id)!!.status)
        assertEquals(SellerStatus.SOLD, t.crm.customers.sellerForProperty(p.id)!!.status)
        assertEquals(CustomerStatus.CONVERTED.name, t.crm.customers.customer("buyer")!!.status)

        val commission = deals.commissions.first().single()
        assertEquals(CommissionStatus.ESTIMATED, commission.status)
        assertTrue(commission.isEstimate)
        assertEquals(Money.ofToman(59_500_000), commission.expectedAmount)
        deals.confirmCommission(commission.id, Money.ofToman(60_000_000))
        deals.recordCommissionPayment(commission.id, Money.ofToman(20_000_000))
        assertEquals(CommissionStatus.PARTIALLY_RECEIVED, deals.commission(commission.id)!!.status)
        deals.recordCommissionPayment(commission.id, Money.ofToman(40_000_000))
        val paid = deals.commission(commission.id)!!
        assertEquals(CommissionStatus.RECEIVED, paid.status)
        assertEquals(Money.ZERO, paid.outstanding)
    }

    @Test fun offersExpire() = runTest {
        val t = TestCrm()
        t.crm.customers.save(t.customer(id = "b"))
        val p = t.crm.properties.newProperty().copy(title = "x")
        t.crm.properties.save(p)
        val deal = t.crm.deals.openDeal(p.id, "b", null, DealType.SALE)
        t.crm.deals.recordOffer(t.crm.deals.newOffer(deal).copy(offeredPrice = Money.ofToman(1_000), expiresAt = t.clock + 1000), submit = true)
        t.clock += 2000
        assertEquals(1, t.crm.deals.expireDueOffers())
        assertEquals(OfferStatus.EXPIRED, t.crm.deals.offers.first().single().status)
    }
}
