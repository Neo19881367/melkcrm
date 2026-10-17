package ir.melkcrm.core.model

import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import kotlinx.serialization.Serializable

@Serializable enum class DealStage {
    LEAD_RECEIVED, CUSTOMER_QUALIFIED, PROPERTY_MATCHED, BUYER_CONTACTED, VIEWING_SCHEDULED, VIEWING_COMPLETED,
    OFFER_SUBMITTED, COUNTEROFFER, NEGOTIATION, AGREEMENT_REACHED, TRANSACTION_COMPLETED, TRANSACTION_CANCELLED;

    val isTerminal: Boolean get() = this == TRANSACTION_COMPLETED || this == TRANSACTION_CANCELLED
}

/** A buyer + property negotiation pipeline. Offers belong to a deal. */
@Serializable
data class Deal(
    val id: String,
    val agencyId: String,
    val propertyId: String,
    val buyerCustomerId: String,
    val sellerCustomerId: String? = null,
    val requirementId: String? = null,
    val dealType: DealType,
    val stage: DealStage = DealStage.PROPERTY_MATCHED,
    val agentId: String? = null,
    val openedAt: EpochMillis,
    val closedAt: EpochMillis? = null,
    val cancelReason: String? = null,
    val version: Long = 0,
    val updatedAt: EpochMillis,
)

@Serializable enum class OfferStatus {
    DRAFT, SUBMITTED, COUNTERED, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED;

    val isOpen: Boolean get() = this == DRAFT || this == SUBMITTED
}

@Serializable enum class OfferDirection { BUYER_TO_SELLER, SELLER_TO_BUYER }

@Serializable
data class Offer(
    val id: String,
    val agencyId: String,
    val dealId: String,
    val propertyId: String,
    val buyerCustomerId: String,
    val sellerCustomerId: String? = null,
    val dealType: DealType,
    val direction: OfferDirection = OfferDirection.BUYER_TO_SELLER,
    /** Sale price; null for rentals. */
    val offeredPrice: Money? = null,
    val offeredDeposit: Money? = null,
    val offeredMonthlyRent: Money? = null,
    /** Unit the agent entered the figures in; the stored value is always rial. */
    val enteredUnit: CurrencyUnit = CurrencyUnit.TOMAN,
    val offerDate: EpochMillis,
    val expiresAt: EpochMillis? = null,
    val conditions: String? = null,
    val status: OfferStatus = OfferStatus.DRAFT,
    /** The offer this one counters. */
    val parentOfferId: String? = null,
    val responseNote: String? = null,
    val agentId: String? = null,
    val notes: String? = null,
    val version: Long = 0,
    val updatedAt: EpochMillis,
)

@Serializable
data class Transaction(
    val id: String,
    val agencyId: String,
    val dealId: String,
    val propertyId: String,
    val buyerCustomerId: String,
    val sellerCustomerId: String? = null,
    val dealType: DealType,
    val finalPrice: Money? = null,
    val finalDeposit: Money? = null,
    val finalMonthlyRent: Money? = null,
    val contractDate: EpochDay,
    val leaseMonths: Int? = null,
    val agentId: String? = null,
    val notes: String? = null,
    val version: Long = 0,
    val updatedAt: EpochMillis,
)

@Serializable enum class CommissionStatus { ESTIMATED, CONFIRMED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED }

@Serializable enum class CommissionParty { BUYER, SELLER, LANDLORD, TENANT }

@Serializable
data class CommissionRecord(
    val id: String,
    val agencyId: String,
    val dealId: String? = null,
    val transactionId: String? = null,
    val propertyId: String,
    val party: CommissionParty,
    val ruleId: String? = null,
    val agreedTerms: String? = null,
    /** Calculated estimate from the rule; labelled as an estimate everywhere. */
    val expectedAmount: Money? = null,
    /** Confirmed amount once the transaction is completed. */
    val earnedAmount: Money? = null,
    val receivedAmount: Money = Money.ZERO,
    val agentId: String? = null,
    val agentShareBasisPoints: Int? = null,
    val transactionDate: EpochDay? = null,
    val status: CommissionStatus = CommissionStatus.ESTIMATED,
    val version: Long = 0,
    val updatedAt: EpochMillis,
) {
    val outstanding: Money get() {
        val base = earnedAmount ?: return Money.ZERO
        return if (receivedAmount >= base) Money.ZERO else base - receivedAmount
    }
    val isEstimate: Boolean get() = earnedAmount == null
}

@Serializable enum class AuditAction { CREATE, UPDATE, DELETE, STATUS_CHANGE, MERGE, EXPORT, LOGIN, PERMISSION_DENIED, REVIEW }

@Serializable
data class AuditEvent(
    val id: String,
    val agencyId: String,
    val actorUserId: String?,
    val entityType: String,
    val entityId: String,
    val action: AuditAction,
    /** Field name -> [old, new] for updates; status transitions use "status". */
    val changes: Map<String, List<String?>> = emptyMap(),
    val note: String? = null,
    val at: EpochMillis,
)
