package ir.melkcrm.core.matching

import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis

/** Workflow C: agency-wide opportunity analysis over all evaluations. */
data class OpportunitySummary(
    /** Eligible matches at or above "possible" not yet stored (never seen before). */
    val newMatches: List<MatchEvaluation>,
    val propertiesWithoutBuyers: List<String>,
    val requirementsWithoutProperties: List<String>,
    /** propertyId -> number of strong/excellent buyers, for properties with at least [hotPropertyMin]. */
    val hotProperties: Map<String, Int>,
    /** Requirements that pass the hard constraints of less than [hardRatio] of the available properties. */
    val hardToSatisfy: List<String>,
    /** Good matches with properties acquired in the last [recentDays] days. */
    val recentPropertyMatches: List<MatchEvaluation>,
    /** Stored matches approved but not contacted, or with a follow-up date due. */
    val needsFollowUp: List<MatchRecord>,
    val highPriority: List<MatchEvaluation>,
)

object OpportunityAnalyzer {
    fun analyze(
        evaluations: List<MatchEvaluation>,
        stored: Map<String, MatchRecord>,
        thresholds: MatchThresholds,
        propertyIds: Collection<String>,
        requirementIds: Collection<String>,
        propertyAcquired: Map<String, EpochDay?>,
        today: EpochDay,
        recentDays: Int = 14,
        hotPropertyMin: Int = 3,
        hardRatio: Double = 0.05,
    ): OpportunitySummary {
        val good = evaluations.filter { it.eligible && it.score >= thresholds.possible }
        val strong = good.filter { it.score >= thresholds.strong }
        val goodByProperty = good.groupBy { it.propertyId }
        val goodByRequirement = good.groupBy { it.requirementId }
        val eligibleByRequirement = evaluations.filter { it.eligible }.groupBy { it.requirementId }
        val propertyCount = propertyIds.size.coerceAtLeast(1)

        val active = { e: MatchEvaluation ->
            val rec = stored[e.key]
            rec == null || (rec.reviewStatus.isActive && !rec.isSnoozed(today))
        }
        return OpportunitySummary(
            newMatches = good.filter { stored[it.key] == null }.sortedByDescending { it.score },
            propertiesWithoutBuyers = propertyIds.filter { goodByProperty[it].isNullOrEmpty() },
            requirementsWithoutProperties = requirementIds.filter { goodByRequirement[it].isNullOrEmpty() },
            hotProperties = strong.groupBy { it.propertyId }.mapValues { it.value.size }.filterValues { it >= hotPropertyMin },
            hardToSatisfy = requirementIds.filter { (eligibleByRequirement[it]?.size ?: 0).toDouble() / propertyCount < hardRatio },
            recentPropertyMatches = good.filter { e -> propertyAcquired[e.propertyId]?.let { today - it <= recentDays } == true },
            needsFollowUp = stored.values.filter {
                (it.reviewStatus == MatchReviewStatus.APPROVED_FOR_CONTACT && !it.contacted) ||
                    (it.followUpDate != null && it.followUpDate <= today && it.reviewStatus.isActive)
            },
            highPriority = strong.filter(active).sortedByDescending { it.score },
        )
    }
}

/** Decides when stored results are stale and must be recalculated. */
object Recalculation {
    fun isStale(record: MatchRecord?, currentFingerprint: String): Boolean =
        record == null || record.fingerprint != currentFingerprint

    fun shouldRefreshAll(lastFullRun: EpochMillis?, now: EpochMillis, maxAgeMillis: Long = 24 * 3_600_000L): Boolean =
        lastFullRun == null || now - lastFullRun >= maxAgeMillis
}
