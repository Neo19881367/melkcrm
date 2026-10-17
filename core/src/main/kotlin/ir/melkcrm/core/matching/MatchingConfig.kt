package ir.melkcrm.core.matching

import ir.melkcrm.core.model.PropertyStatus
import kotlinx.serialization.Serializable

/**
 * Component weights in points; they must add up to 100. Documented in docs/MATCHING.md.
 * Agency administrators can change them; the engine validates before use.
 */
@Serializable
data class MatchWeights(
    val budget: Int = 30,
    val location: Int = 25,
    val layout: Int = 15,
    val features: Int = 10,
    val timing: Int = 10,
    val flexibility: Int = 5,
    val readiness: Int = 5,
) {
    val total: Int get() = budget + location + layout + features + timing + flexibility + readiness

    fun of(c: MatchComponent): Int = when (c) {
        MatchComponent.BUDGET -> budget
        MatchComponent.LOCATION -> location
        MatchComponent.LAYOUT -> layout
        MatchComponent.FEATURES -> features
        MatchComponent.TIMING -> timing
        MatchComponent.FLEXIBILITY -> flexibility
        MatchComponent.READINESS -> readiness
    }

    fun validate(): List<String> = buildList {
        if (total != 100) add("Weights must add up to 100 (currently $total)")
        if (listOf(budget, location, layout, features, timing, flexibility, readiness).any { it < 0 }) add("Weights cannot be negative")
        if (readiness > 15) add("Lead readiness weight is limited to 15 so unresponsive but suitable buyers are not eliminated")
    }
}

@Serializable
data class MatchThresholds(val excellent: Int = 85, val strong: Int = 70, val possible: Int = 50) {
    fun validate(): List<String> = buildList {
        if (!(possible in 1..99 && strong in 1..99 && excellent in 1..100)) add("Thresholds must be between 1 and 100")
        if (!(possible < strong && strong < excellent)) add("Thresholds must increase: possible < strong < excellent")
    }

    fun classify(score: Int): MatchClass = when {
        score >= excellent -> MatchClass.EXCELLENT
        score >= strong -> MatchClass.STRONG
        score >= possible -> MatchClass.POSSIBLE
        else -> MatchClass.WEAK
    }
}

@Serializable
data class MatchingConfig(
    val weights: MatchWeights = MatchWeights(),
    val thresholds: MatchThresholds = MatchThresholds(),
    /** A price above the buyer's absolute ceiling by at most this percentage stays a (weak) candidate if the property is negotiable. */
    val overBudgetTolerancePercent: Int = 10,
    /** Area shortfall (percent below the minimum) at which the area score reaches zero. */
    val areaShortfallZeroPercent: Int = 20,
    /** Area excess (percent above the maximum) at which the area score reaches zero. */
    val areaExcessZeroPercent: Int = 40,
    /** Properties within this distance of a saved map area keep most of the location score. */
    val nearAreaFullKm: Double = 1.0,
    /** Beyond this distance from every saved map area, proximity earns nothing. */
    val nearAreaZeroKm: Double = 8.0,
    /** Statuses considered available for matching. RESERVED/UNDER_NEGOTIATION also qualify when the property is flagged openToFurtherOffers. */
    val matchableStatuses: Set<PropertyStatus> = setOf(PropertyStatus.AVAILABLE),
    /** Bumped whenever the scoring rules change, so stored results are recalculated. */
    val rulesVersion: Int = 1,
) {
    fun validate(): List<String> = weights.validate() + thresholds.validate() + buildList {
        if (overBudgetTolerancePercent !in 0..50) add("Over-budget tolerance must be 0–50%")
        if (nearAreaZeroKm <= nearAreaFullKm) add("Map-area distance limits are inconsistent")
    }

    fun requireValid(): MatchingConfig = apply {
        val errors = validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    val fingerprint: String get() = "r$rulesVersion:${weights.hashCode()}:${thresholds.hashCode()}:$overBudgetTolerancePercent:$areaShortfallZeroPercent:$areaExcessZeroPercent:$nearAreaFullKm:$nearAreaZeroKm:${matchableStatuses.map { it.name }.sorted()}"
}
