package ir.melkcrm.core.matching

import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import kotlinx.serialization.Serializable

/** Agent workflow on a recommendation. The engine never moves a match past NEW by itself. */
@Serializable
enum class MatchReviewStatus {
    NEW, REVIEWED, APPROVED_FOR_CONTACT, CONTACTED, VIEWING_SCHEDULED, VIEWED, OFFER_MADE, REJECTED, SNOOZED, CLOSED;

    val isActive: Boolean get() = this != REJECTED && this != CLOSED
}

@Serializable enum class MatchRejectionReason {
    BUYER_NOT_INTERESTED, PRICE_TOO_HIGH, LOCATION_UNSUITABLE, LAYOUT_UNSUITABLE, CONDITION_UNSUITABLE,
    SELLER_DECLINED, ALREADY_SHOWN, DUPLICATE, OTHER,
}

@Serializable
data class MatchReviewEvent(
    val at: EpochMillis,
    val userId: String?,
    val from: MatchReviewStatus,
    val to: MatchReviewStatus,
    val note: String? = null,
)

/** Stored match: a snapshot of the latest evaluation plus the agent's review state. */
@Serializable
data class MatchRecord(
    val key: String,
    val agencyId: String,
    val requirementId: String,
    val propertyId: String,
    val customerId: String,
    val score: Int,
    val classification: MatchClass,
    val eligible: Boolean,
    val provisional: Boolean,
    val fingerprint: String,
    val firstSeenAt: EpochMillis,
    val computedAt: EpochMillis,
    val reviewStatus: MatchReviewStatus = MatchReviewStatus.NEW,
    val rejectionReason: MatchRejectionReason? = null,
    val snoozedUntil: EpochDay? = null,
    val contacted: Boolean = false,
    val buyerFeedback: String? = null,
    val viewingOutcome: String? = null,
    val followUpDate: EpochDay? = null,
    /** Inputs changed after the agent reviewed it; shown as "updated" in the review list. */
    val changedSinceReview: Boolean = false,
    val history: List<MatchReviewEvent> = emptyList(),
    val version: Long = 0,
    val updatedAt: EpochMillis,
) {
    fun isSnoozed(today: EpochDay): Boolean = reviewStatus == MatchReviewStatus.SNOOZED && (snoozedUntil == null || snoozedUntil > today)
}

object MatchReview {
    private val allowed: Map<MatchReviewStatus, Set<MatchReviewStatus>> = MatchReviewStatus.entries.associateWith { from ->
        when (from) {
            MatchReviewStatus.CLOSED -> setOf(MatchReviewStatus.NEW)
            else -> MatchReviewStatus.entries.toSet() - from
        }
    }

    fun canTransition(from: MatchReviewStatus, to: MatchReviewStatus): Boolean = to in allowed.getValue(from)

    /** Creates or refreshes the stored record from a new evaluation without losing review state. */
    fun upsert(previous: MatchRecord?, eval: MatchEvaluation, agencyId: String, now: EpochMillis): MatchRecord {
        if (previous == null) {
            return MatchRecord(
                key = eval.key, agencyId = agencyId, requirementId = eval.requirementId, propertyId = eval.propertyId,
                customerId = eval.customerId, score = eval.score, classification = eval.classification, eligible = eval.eligible,
                provisional = eval.provisional, fingerprint = eval.fingerprint, firstSeenAt = now, computedAt = now, updatedAt = now,
            )
        }
        val inputsChanged = previous.fingerprint.substringBeforeLast('|') != eval.fingerprint.substringBeforeLast('|')
        val reviewed = previous.reviewStatus != MatchReviewStatus.NEW
        return previous.copy(
            score = eval.score,
            classification = eval.classification,
            eligible = eval.eligible,
            provisional = eval.provisional,
            fingerprint = eval.fingerprint,
            computedAt = now,
            changedSinceReview = previous.changedSinceReview || (reviewed && inputsChanged && previous.score != eval.score),
            updatedAt = if (previous.score != eval.score || previous.eligible != eval.eligible) now else previous.updatedAt,
        )
    }

    fun transition(
        record: MatchRecord,
        to: MatchReviewStatus,
        userId: String?,
        now: EpochMillis,
        note: String? = null,
        rejectionReason: MatchRejectionReason? = null,
        snoozedUntil: EpochDay? = null,
    ): MatchRecord {
        require(canTransition(record.reviewStatus, to)) { "Cannot move match from ${record.reviewStatus} to $to" }
        if (to == MatchReviewStatus.REJECTED) require(rejectionReason != null) { "A rejection reason is required" }
        if (to == MatchReviewStatus.SNOOZED) require(snoozedUntil != null) { "A snooze date is required" }
        return record.copy(
            reviewStatus = to,
            rejectionReason = if (to == MatchReviewStatus.REJECTED) rejectionReason else record.rejectionReason,
            snoozedUntil = if (to == MatchReviewStatus.SNOOZED) snoozedUntil else null,
            contacted = record.contacted || to == MatchReviewStatus.CONTACTED || to == MatchReviewStatus.VIEWING_SCHEDULED ||
                to == MatchReviewStatus.VIEWED || to == MatchReviewStatus.OFFER_MADE,
            changedSinceReview = false,
            history = record.history + MatchReviewEvent(now, userId, record.reviewStatus, to, note),
            updatedAt = now,
        )
    }
}
