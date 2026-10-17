package ir.melkcrm.client.data

import ir.melkcrm.core.commission.CommissionCalculator
import ir.melkcrm.core.commission.CommissionRule
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.offer.DealPipeline
import ir.melkcrm.core.offer.InvalidTransitionException
import ir.melkcrm.core.offer.OfferLifecycle
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import ir.melkcrm.core.validation.OfferValidator
import ir.melkcrm.core.validation.ValidationIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Deals, offers and counteroffers, transactions and commissions. Offers and deal stages change only
 * through the lifecycle rules in core, and every change leaves an audit entry; nothing is overwritten.
 */
class DealRepository(private val w: RecordWriter, private val activities: ActivityRepository) {

    val deals: Flow<List<Deal>> = w.observe(EntityType.DEAL)
    val offers: Flow<List<Offer>> = w.observe(EntityType.OFFER)
    val transactions: Flow<List<Transaction>> = w.observe(EntityType.TRANSACTION)
    val commissions: Flow<List<CommissionRecord>> = w.observe(EntityType.COMMISSION)

    fun offersOf(dealId: String): Flow<List<Offer>> = offers.map { l -> l.filter { it.dealId == dealId }.sortedBy { it.offerDate } }
    fun dealsOfCustomer(customerId: String): Flow<List<Deal>> = deals.map { l -> l.filter { it.buyerCustomerId == customerId || it.sellerCustomerId == customerId } }
    fun dealsOfProperty(propertyId: String): Flow<List<Deal>> = deals.map { l -> l.filter { it.propertyId == propertyId } }
    fun commissionsOf(dealId: String): Flow<List<CommissionRecord>> = commissions.map { l -> l.filter { it.dealId == dealId } }

    suspend fun deal(id: String?): Deal? = w.load(EntityType.DEAL, id)
    suspend fun offer(id: String?): Offer? = w.load(EntityType.OFFER, id)

    /** The open deal for this buyer and property, or a new one (stage: property matched). */
    suspend fun openDeal(propertyId: String, buyerCustomerId: String, requirementId: String?, dealType: DealType): Deal {
        w.loadAll<Deal>(EntityType.DEAL).firstOrNull { it.propertyId == propertyId && it.buyerCustomerId == buyerCustomerId && !it.stage.isTerminal }?.let { return it }
        val property = w.load<Property>(EntityType.PROPERTY, propertyId)
        val buyer = w.load<Customer>(EntityType.CUSTOMER, buyerCustomerId)
        val now = w.now()
        val deal = Deal(
            id = w.newId(), agencyId = w.user.agencyId, propertyId = propertyId, buyerCustomerId = buyerCustomerId,
            sellerCustomerId = property?.ownerCustomerId, requirementId = requirementId, dealType = dealType,
            agentId = buyer?.assignedAgentId ?: w.user.id, openedAt = now, updatedAt = now,
        )
        w.persist(EntityType.DEAL, deal)
        return deal
    }

    fun newOffer(deal: Deal): Offer = Offer(
        id = w.newId(), agencyId = deal.agencyId, dealId = deal.id, propertyId = deal.propertyId,
        buyerCustomerId = deal.buyerCustomerId, sellerCustomerId = deal.sellerCustomerId, dealType = deal.dealType,
        offerDate = w.now(), agentId = w.user.id, updatedAt = w.now(),
    )

    /** Records an offer (as a draft or submitted). Offers are notes of a negotiation, not contracts. */
    suspend fun recordOffer(input: Offer, submit: Boolean): SaveOutcome<Offer> {
        val o = input.copy(status = if (submit) OfferStatus.SUBMITTED else OfferStatus.DRAFT, updatedAt = w.now())
        val result = OfferValidator.validate(o)
        if (!result.isValid) return SaveOutcome.Invalid(result.issues)
        val deal = deal(o.dealId) ?: return SaveOutcome.Forbidden("deal_not_found")
        w.writeCheck(EntityType.OFFER, o.id, o.agentId, o.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        w.store.transaction {
            w.persist(EntityType.OFFER, o)
            if (submit) advanceDeal(deal, DealPipeline.stageForOffer(o.status, o.parentOfferId != null))
            if (o.direction == OfferDirection.BUYER_TO_SELLER) activities.advanceMatches(listOf(o.buyerCustomerId), o.propertyId, MatchReviewStatus.OFFER_MADE)
        }
        return SaveOutcome.Saved(o, result.warnings)
    }

    suspend fun transitionOffer(id: String, to: OfferStatus, note: String? = null): SaveOutcome<Offer> {
        val o = offer(id) ?: return SaveOutcome.Forbidden("not_found")
        val change = try {
            OfferLifecycle.transition(o, to, w.user.id, w.now(), note, w.newId())
        } catch (e: InvalidTransitionException) {
            return SaveOutcome.Invalid(listOf(ValidationIssue("status", "invalid_transition")))
        }
        w.store.transaction {
            w.persist(EntityType.OFFER, change.offer, audit = false)
            w.addAudit(change.audit)
            deal(o.dealId)?.let { advanceDeal(it, DealPipeline.stageForOffer(to, o.parentOfferId != null)) }
        }
        return SaveOutcome.Saved(change.offer)
    }

    /** Closes [id] as countered and opens the counteroffer in the other direction. */
    suspend fun counter(
        id: String, price: Money?, deposit: Money?, monthlyRent: Money?, conditions: String?, expiresAt: EpochMillis?,
    ): SaveOutcome<Offer> {
        val o = offer(id) ?: return SaveOutcome.Forbidden("not_found")
        val result = try {
            OfferLifecycle.counter(o, w.newId(), w.user.id, w.now(), price, deposit, monthlyRent, conditions, expiresAt)
        } catch (e: InvalidTransitionException) {
            return SaveOutcome.Invalid(listOf(ValidationIssue("status", "invalid_transition")))
        }
        val check = OfferValidator.validate(result.counter)
        if (!check.isValid) return SaveOutcome.Invalid(check.issues)
        w.store.transaction {
            w.persist(EntityType.OFFER, result.original, audit = false)
            w.persist(EntityType.OFFER, result.counter.copy(agentId = w.user.id), audit = false)
            result.audits.forEach { w.addAudit(it) }
            deal(o.dealId)?.let { advanceDeal(it, DealStage.COUNTEROFFER) }
        }
        return SaveOutcome.Saved(result.counter)
    }

    /** Marks submitted offers whose expiry has passed. Run on start-up and after sync. */
    suspend fun expireDueOffers(): Int {
        var n = 0
        for (o in w.loadAll<Offer>(EntityType.OFFER)) {
            val change = OfferLifecycle.expireIfDue(o, w.now(), w.user.id) ?: continue
            w.persist(EntityType.OFFER, change.offer, audit = false)
            w.addAudit(change.audit)
            n++
        }
        return n
    }

    /** Automatic pipeline moves only go forward; agents can move freely with [moveDeal]. */
    private suspend fun advanceDeal(deal: Deal, to: DealStage?) {
        if (to == null || deal.stage.isTerminal || deal.stage.ordinal >= to.ordinal) return
        val (updated, audit) = DealPipeline.move(deal, to, w.user.id, w.now())
        w.persist(EntityType.DEAL, updated, audit = false)
        w.addAudit(audit)
    }

    suspend fun moveDeal(id: String, to: DealStage, reason: String? = null): SaveOutcome<Deal> {
        val d = deal(id) ?: return SaveOutcome.Forbidden("not_found")
        if (to == DealStage.TRANSACTION_COMPLETED) return SaveOutcome.Invalid(listOf(ValidationIssue("stage", "use_complete_transaction")))
        w.writeCheck(EntityType.DEAL, d.id, d.agentId, d.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val (updated, audit) = try {
            DealPipeline.move(d, to, w.user.id, w.now(), reason)
        } catch (e: InvalidTransitionException) {
            return SaveOutcome.Invalid(listOf(ValidationIssue("stage", "invalid_transition")))
        } catch (e: IllegalArgumentException) {
            return SaveOutcome.Invalid(listOf(ValidationIssue("cancelReason", "required")))
        }
        w.persist(EntityType.DEAL, updated, audit = false)
        w.addAudit(audit)
        return SaveOutcome.Saved(updated)
    }

    /**
     * Records the completed transaction: the final price is stored separately from the asking price and
     * the offers; the property becomes sold or rented (kept, not deleted); the requirement is fulfilled;
     * commission records are created from the agency's rules as estimates until confirmed.
     */
    suspend fun completeTransaction(
        dealId: String, finalPrice: Money?, finalDeposit: Money?, finalMonthlyRent: Money?, contractDate: EpochDay,
        leaseMonths: Int?, notes: String?,
    ): SaveOutcome<Transaction> {
        val d = deal(dealId) ?: return SaveOutcome.Forbidden("not_found")
        if (d.stage.isTerminal) return SaveOutcome.Invalid(listOf(ValidationIssue("stage", "invalid_transition")))
        val issues = buildList {
            if (d.dealType == DealType.SALE && finalPrice == null) add(ValidationIssue("finalPrice", "required"))
            if (d.dealType != DealType.SALE && finalDeposit == null && finalMonthlyRent == null) add(ValidationIssue("finalDeposit", "required"))
            if (leaseMonths != null && leaseMonths <= 0) add(ValidationIssue("leaseMonths", "must_be_positive"))
        }
        if (issues.isNotEmpty()) return SaveOutcome.Invalid(issues)
        w.writeCheck(EntityType.DEAL, d.id, d.agentId, d.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val now = w.now()
        val tx = Transaction(
            id = w.newId(), agencyId = d.agencyId, dealId = d.id, propertyId = d.propertyId, buyerCustomerId = d.buyerCustomerId,
            sellerCustomerId = d.sellerCustomerId, dealType = d.dealType, finalPrice = finalPrice, finalDeposit = finalDeposit,
            finalMonthlyRent = finalMonthlyRent, contractDate = contractDate, leaseMonths = leaseMonths, agentId = d.agentId,
            notes = notes?.trim()?.ifBlank { null }, updatedAt = now,
        )
        w.store.transaction {
            w.persist(EntityType.TRANSACTION, tx)
            val (closed, audit) = DealPipeline.move(d, DealStage.TRANSACTION_COMPLETED, w.user.id, now)
            w.persist(EntityType.DEAL, closed, audit = false)
            w.addAudit(audit)
            w.load<Property>(EntityType.PROPERTY, d.propertyId)?.let { p ->
                val status = if (d.dealType == DealType.SALE) PropertyStatus.SOLD else PropertyStatus.RENTED
                w.persist(EntityType.PROPERTY, p.copy(status = status, updatedAt = now), note = "transaction ${tx.id}")
            }
            for (rec in w.store.byProperty(EntityType.SELLER_PROFILE, d.propertyId)) {
                val s = w.decode<SellerProfile>(rec)
                if (s.status != SellerStatus.SOLD) w.persist(EntityType.SELLER_PROFILE, s.copy(status = SellerStatus.SOLD, updatedAt = now))
            }
            w.load<BuyerRequirement>(EntityType.BUYER_REQUIREMENT, d.requirementId)?.let { r ->
                val agent = w.store.get(EntityType.BUYER_REQUIREMENT, r.id)?.assignedAgentId
                w.persist(EntityType.BUYER_REQUIREMENT, r.copy(status = RequirementStatus.FULFILLED, updatedAt = now), assignedAgentId = agent)
            }
            w.load<Customer>(EntityType.CUSTOMER, d.buyerCustomerId)?.let { c ->
                if (c.status != CustomerStatus.CONVERTED.name) w.persist(EntityType.CUSTOMER, c.copy(status = CustomerStatus.CONVERTED.name, updatedAt = now))
            }
            for (rule in rulesFor(d.dealType)) {
                val est = CommissionCalculator.estimate(rule, finalPrice, finalDeposit, finalMonthlyRent)
                w.persist(EntityType.COMMISSION, CommissionRecord(
                    id = w.newId(), agencyId = d.agencyId, dealId = d.id, transactionId = tx.id, propertyId = d.propertyId,
                    party = rule.party, ruleId = rule.id, agreedTerms = rule.name, expectedAmount = est.total, agentId = d.agentId,
                    transactionDate = contractDate, status = CommissionStatus.ESTIMATED, updatedAt = now,
                ))
            }
        }
        return SaveOutcome.Saved(tx)
    }

    fun rulesFor(dealType: DealType): List<CommissionRule> = w.agency.settings.commissionRules.filter { it.active && it.dealType == dealType }

    // ------------------------------------------------------------------ commissions

    suspend fun commission(id: String?): CommissionRecord? = w.load(EntityType.COMMISSION, id)

    /** The confirmed amount replaces the estimate in reports; the estimate stays on the record. */
    suspend fun confirmCommission(id: String, earned: Money): SaveOutcome<CommissionRecord> {
        val c = commission(id) ?: return SaveOutcome.Forbidden("not_found")
        w.writeCheck(EntityType.COMMISSION, c.id, c.agentId, c.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val status = when {
            c.receivedAmount >= earned && earned > Money.ZERO -> CommissionStatus.RECEIVED
            c.receivedAmount > Money.ZERO -> CommissionStatus.PARTIALLY_RECEIVED
            else -> CommissionStatus.CONFIRMED
        }
        val u = c.copy(earnedAmount = earned, status = status, updatedAt = w.now())
        w.persist(EntityType.COMMISSION, u)
        return SaveOutcome.Saved(u)
    }

    suspend fun recordCommissionPayment(id: String, amount: Money): SaveOutcome<CommissionRecord> {
        val c = commission(id) ?: return SaveOutcome.Forbidden("not_found")
        if (amount.rial == 0L) return SaveOutcome.Invalid(listOf(ValidationIssue("receivedAmount", "zero")))
        w.writeCheck(EntityType.COMMISSION, c.id, c.agentId, c.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val received = c.receivedAmount + amount
        val earned = c.earnedAmount
        val status = when {
            earned == null -> CommissionStatus.PARTIALLY_RECEIVED
            received >= earned -> CommissionStatus.RECEIVED
            else -> CommissionStatus.PARTIALLY_RECEIVED
        }
        val u = c.copy(receivedAmount = received, status = status, updatedAt = w.now())
        w.persist(EntityType.COMMISSION, u)
        return SaveOutcome.Saved(u)
    }
}
