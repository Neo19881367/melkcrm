package ir.melkcrm.core.offer

import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.model.Deal
import ir.melkcrm.core.model.DealStage
import ir.melkcrm.core.model.Offer
import ir.melkcrm.core.model.OfferDirection
import ir.melkcrm.core.model.OfferStatus
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.EpochMillis

class InvalidTransitionException(message: String) : IllegalStateException(message)

/** Offer state machine. Every change yields an [AuditEvent]; nothing is overwritten without a trail. */
object OfferLifecycle {
    private val transitions: Map<OfferStatus, Set<OfferStatus>> = mapOf(
        OfferStatus.DRAFT to setOf(OfferStatus.SUBMITTED, OfferStatus.WITHDRAWN),
        OfferStatus.SUBMITTED to setOf(OfferStatus.ACCEPTED, OfferStatus.REJECTED, OfferStatus.COUNTERED, OfferStatus.WITHDRAWN, OfferStatus.EXPIRED),
        OfferStatus.COUNTERED to emptySet(),
        OfferStatus.ACCEPTED to emptySet(),
        OfferStatus.REJECTED to emptySet(),
        OfferStatus.WITHDRAWN to emptySet(),
        OfferStatus.EXPIRED to emptySet(),
    )

    fun canTransition(from: OfferStatus, to: OfferStatus) = to in transitions.getValue(from)

    data class Change(val offer: Offer, val audit: AuditEvent)

    fun transition(offer: Offer, to: OfferStatus, actorId: String?, now: EpochMillis, note: String? = null, auditId: String = newId()): Change {
        if (!canTransition(offer.status, to)) throw InvalidTransitionException("Offer ${offer.id}: ${offer.status} -> $to is not allowed")
        val updated = offer.copy(status = to, responseNote = note ?: offer.responseNote, updatedAt = now)
        return Change(updated, audit(offer, actorId, now, auditId, "status" to listOf(offer.status.name, to.name), note))
    }

    data class CounterResult(val original: Offer, val counter: Offer, val audits: List<AuditEvent>)

    /** Counteroffer: closes the original as COUNTERED and opens a new SUBMITTED offer in the other direction. */
    fun counter(
        offer: Offer, counterId: String, actorId: String?, now: EpochMillis,
        price: Money? = offer.offeredPrice, deposit: Money? = offer.offeredDeposit, monthlyRent: Money? = offer.offeredMonthlyRent,
        conditions: String? = offer.conditions, expiresAt: EpochMillis? = null,
    ): CounterResult {
        val closed = transition(offer, OfferStatus.COUNTERED, actorId, now)
        val counter = offer.copy(
            id = counterId,
            direction = if (offer.direction == OfferDirection.BUYER_TO_SELLER) OfferDirection.SELLER_TO_BUYER else OfferDirection.BUYER_TO_SELLER,
            offeredPrice = price, offeredDeposit = deposit, offeredMonthlyRent = monthlyRent,
            conditions = conditions, expiresAt = expiresAt, offerDate = now,
            status = OfferStatus.SUBMITTED, parentOfferId = offer.id, responseNote = null,
            version = 0, updatedAt = now,
        )
        val created = AuditEvent(newId(), offer.agencyId, actorId, "OFFER", counterId, AuditAction.CREATE,
            mapOf("parentOfferId" to listOf(null, offer.id)), null, now)
        return CounterResult(closed.offer, counter, listOf(closed.audit, created))
    }

    /** Marks SUBMITTED offers past their expiry as EXPIRED. */
    fun expireIfDue(offer: Offer, now: EpochMillis, actorId: String? = null): Change? =
        if (offer.status == OfferStatus.SUBMITTED && offer.expiresAt != null && offer.expiresAt < now) transition(offer, OfferStatus.EXPIRED, actorId, now, "expired")
        else null

    /** The chain of offers and counteroffers ending at [latest], oldest first. */
    fun chain(latest: Offer, all: List<Offer>): List<Offer> {
        val byId = all.associateBy { it.id }
        val out = ArrayList<Offer>()
        var cur: Offer? = latest
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = cur.parentOfferId?.let { byId[it] }
        }
        return out.reversed()
    }

    private fun audit(offer: Offer, actorId: String?, now: EpochMillis, id: String, change: Pair<String, List<String?>>, note: String?) =
        AuditEvent(id, offer.agencyId, actorId, "OFFER", offer.id, AuditAction.STATUS_CHANGE, mapOf(change), note, now)

    private fun newId() = java.util.UUID.randomUUID().toString()
}

/** Deal pipeline. Stages can move forward or back (e.g. a second viewing), but terminal stages are final. */
object DealPipeline {
    fun canMove(from: DealStage, to: DealStage): Boolean = !from.isTerminal && from != to

    fun move(deal: Deal, to: DealStage, actorId: String?, now: EpochMillis, reason: String? = null): Pair<Deal, AuditEvent> {
        if (!canMove(deal.stage, to)) throw InvalidTransitionException("Deal ${deal.id}: ${deal.stage} -> $to is not allowed")
        if (to == DealStage.TRANSACTION_CANCELLED) require(!reason.isNullOrBlank()) { "A cancellation reason is required" }
        val updated = deal.copy(
            stage = to,
            closedAt = if (to.isTerminal) now else null,
            cancelReason = if (to == DealStage.TRANSACTION_CANCELLED) reason else deal.cancelReason,
            updatedAt = now,
        )
        val audit = AuditEvent(java.util.UUID.randomUUID().toString(), deal.agencyId, actorId, "DEAL", deal.id, AuditAction.STATUS_CHANGE,
            mapOf("stage" to listOf(deal.stage.name, to.name)), reason, now)
        return updated to audit
    }

    /** Stage implied by an offer event, used to advance the pipeline automatically (never backwards). */
    fun stageForOffer(status: OfferStatus, isCounter: Boolean): DealStage? = when (status) {
        OfferStatus.SUBMITTED -> if (isCounter) DealStage.COUNTEROFFER else DealStage.OFFER_SUBMITTED
        OfferStatus.ACCEPTED -> DealStage.AGREEMENT_REACHED
        OfferStatus.COUNTERED -> DealStage.NEGOTIATION
        else -> null
    }
}
