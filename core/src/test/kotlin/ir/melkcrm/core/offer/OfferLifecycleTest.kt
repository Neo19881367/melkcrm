package ir.melkcrm.core.offer

import ir.melkcrm.core.Fx
import ir.melkcrm.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfferLifecycleTest {
    private val offer = Offer("o1", Fx.AGENCY, "d1", "p1", "c1", "owner-1", DealType.SALE, offeredPrice = Fx.billion(11.0),
        offerDate = 1000, status = OfferStatus.DRAFT, agentId = "agent-1", updatedAt = 1000)

    @Test fun submitAcceptProducesAuditTrail() {
        val submitted = OfferLifecycle.transition(offer, OfferStatus.SUBMITTED, "agent-1", 2000)
        assertEquals(OfferStatus.SUBMITTED, submitted.offer.status)
        assertEquals(listOf("DRAFT", "SUBMITTED"), submitted.audit.changes["status"])
        val accepted = OfferLifecycle.transition(submitted.offer, OfferStatus.ACCEPTED, "agent-1", 3000, "پذیرفته شد")
        assertEquals(OfferStatus.ACCEPTED, accepted.offer.status)
        assertEquals(AuditAction.STATUS_CHANGE, accepted.audit.action)
    }

    @Test fun illegalTransitionsAreRejected() {
        assertFailsWith<InvalidTransitionException> { OfferLifecycle.transition(offer, OfferStatus.ACCEPTED, null, 1) }
        val rejected = OfferLifecycle.transition(OfferLifecycle.transition(offer, OfferStatus.SUBMITTED, null, 1).offer, OfferStatus.REJECTED, null, 2).offer
        assertFailsWith<InvalidTransitionException> { OfferLifecycle.transition(rejected, OfferStatus.SUBMITTED, null, 3) }
    }

    @Test fun counterofferChain() {
        val submitted = OfferLifecycle.transition(offer, OfferStatus.SUBMITTED, null, 2000).offer
        val c = OfferLifecycle.counter(submitted, "o2", "agent-1", 3000, price = Fx.billion(11.8))
        assertEquals(OfferStatus.COUNTERED, c.original.status)
        assertEquals(OfferStatus.SUBMITTED, c.counter.status)
        assertEquals(OfferDirection.SELLER_TO_BUYER, c.counter.direction)
        assertEquals("o1", c.counter.parentOfferId)
        assertEquals(2, c.audits.size)
        val c2 = OfferLifecycle.counter(c.counter, "o3", "agent-1", 4000, price = Fx.billion(11.5))
        val chain = OfferLifecycle.chain(c2.counter, listOf(c.original, c2.original, c2.counter))
        assertEquals(listOf("o1", "o2", "o3"), chain.map { it.id })
    }

    @Test fun expiry() {
        val s = OfferLifecycle.transition(offer.copy(expiresAt = 5000), OfferStatus.SUBMITTED, null, 2000).offer
        assertNull(OfferLifecycle.expireIfDue(s, 4000))
        assertEquals(OfferStatus.EXPIRED, OfferLifecycle.expireIfDue(s, 6000)!!.offer.status)
    }

    @Test fun dealPipelineTerminalStagesAreFinal() {
        val deal = Deal("d1", Fx.AGENCY, "p1", "c1", dealType = DealType.SALE, openedAt = 0, updatedAt = 0)
        val (done, audit) = DealPipeline.move(deal, DealStage.TRANSACTION_COMPLETED, "agent-1", 10)
        assertEquals(10, done.closedAt)
        assertEquals(listOf("PROPERTY_MATCHED", "TRANSACTION_COMPLETED"), audit.changes["stage"])
        assertFailsWith<InvalidTransitionException> { DealPipeline.move(done, DealStage.NEGOTIATION, null, 11) }
        assertFailsWith<IllegalArgumentException> { DealPipeline.move(deal, DealStage.TRANSACTION_CANCELLED, null, 11) }
        assertTrue(DealPipeline.canMove(DealStage.VIEWING_COMPLETED, DealStage.VIEWING_SCHEDULED))
    }
}
