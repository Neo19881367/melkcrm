package ir.melkcrm.core.matching

import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.SellerProfile
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.EpochMillis
import kotlinx.serialization.Serializable

@Serializable enum class MatchComponent { BUDGET, LOCATION, LAYOUT, FEATURES, TIMING, FLEXIBILITY, READINESS }

/** Recommendation labels — never a guarantee of a successful deal. */
@Serializable enum class MatchClass { EXCELLENT, STRONG, POSSIBLE, WEAK, NOT_ELIGIBLE }

@Serializable enum class ReasonKind { POSITIVE, CONCERN, EXCLUSION, INFO }

@Serializable
enum class ReasonCode(val kind: ReasonKind) {
    // Stage 1 exclusions
    PROPERTY_NOT_AVAILABLE(ReasonKind.EXCLUSION),
    REQUIREMENT_NOT_ACTIVE(ReasonKind.EXCLUSION),
    CUSTOMER_INACTIVE(ReasonKind.EXCLUSION),
    OWN_PROPERTY(ReasonKind.EXCLUSION),
    DEAL_TYPE_MISMATCH(ReasonKind.EXCLUSION),
    CATEGORY_MISMATCH(ReasonKind.EXCLUSION),
    EXCLUDED_CITY(ReasonKind.EXCLUSION),
    EXCLUDED_NEIGHBORHOOD(ReasonKind.EXCLUSION),
    OUTSIDE_REQUIRED_LOCATION(ReasonKind.EXCLUSION),
    OVER_BUDGET(ReasonKind.EXCLUSION),
    OVER_DEPOSIT_LIMIT(ReasonKind.EXCLUSION),
    OVER_RENT_LIMIT(ReasonKind.EXCLUSION),
    AREA_OUT_OF_RANGE(ReasonKind.EXCLUSION),
    BEDROOMS_OUT_OF_RANGE(ReasonKind.EXCLUSION),
    MANDATORY_FEATURE_MISSING(ReasonKind.EXCLUSION),

    // Budget
    WITHIN_BUDGET(ReasonKind.POSITIVE),
    AT_OR_BELOW_PREFERRED_BUDGET(ReasonKind.POSITIVE),
    ABOVE_PREFERRED_BUDGET(ReasonKind.CONCERN),
    BELOW_MIN_BUDGET(ReasonKind.CONCERN),
    ABOVE_MAX_WITHIN_STRETCH(ReasonKind.CONCERN),
    ABOVE_MAX_NEGOTIATION_NEEDED(ReasonKind.CONCERN),
    DEPOSIT_WITHIN_LIMIT(ReasonKind.POSITIVE),
    RENT_WITHIN_LIMIT(ReasonKind.POSITIVE),
    DEPOSIT_ABOVE_LIMIT(ReasonKind.CONCERN),
    RENT_ABOVE_LIMIT(ReasonKind.CONCERN),

    // Location
    PREFERRED_NEIGHBORHOOD(ReasonKind.POSITIVE),
    ALTERNATIVE_NEIGHBORHOOD(ReasonKind.POSITIVE),
    INSIDE_MAP_AREA(ReasonKind.POSITIVE),
    NEAR_MAP_AREA(ReasonKind.POSITIVE),
    PREFERRED_CITY(ReasonKind.POSITIVE),
    PREFERRED_PROVINCE(ReasonKind.POSITIVE),
    OUTSIDE_PREFERRED_NEIGHBORHOOD(ReasonKind.CONCERN),
    OUTSIDE_PREFERRED_LOCATION(ReasonKind.CONCERN),
    NO_LOCATION_PREFERENCE(ReasonKind.INFO),

    // Layout
    AREA_FITS(ReasonKind.POSITIVE),
    AREA_BELOW_MIN(ReasonKind.CONCERN),
    AREA_ABOVE_MAX(ReasonKind.CONCERN),
    BEDROOMS_FIT(ReasonKind.POSITIVE),
    FEWER_BEDROOMS(ReasonKind.CONCERN),
    MORE_BEDROOMS(ReasonKind.CONCERN),
    FLOOR_FITS(ReasonKind.POSITIVE),
    FLOOR_OUTSIDE_RANGE(ReasonKind.CONCERN),

    // Features
    FEATURE_SATISFIED(ReasonKind.POSITIVE),
    FEATURE_MISSING(ReasonKind.CONCERN),
    REQUIRED_FEATURE_UNKNOWN(ReasonKind.CONCERN),
    NO_FEATURE_REQUIREMENTS(ReasonKind.INFO),

    // Timing
    URGENT_BUYER(ReasonKind.POSITIVE),
    FITS_SELLER_DEADLINE(ReasonKind.POSITIVE),
    BUYER_LATER_THAN_DEADLINE(ReasonKind.CONCERN),
    NEEDS_TO_SELL_FIRST(ReasonKind.CONCERN),
    SLOW_TIMELINE(ReasonKind.CONCERN),

    // Flexibility
    BUYER_FLEXIBLE(ReasonKind.POSITIVE),
    SELLER_FLEXIBLE(ReasonKind.POSITIVE),
    PROPERTY_NEGOTIABLE(ReasonKind.POSITIVE),
    BUYER_INFLEXIBLE(ReasonKind.CONCERN),
    SELLER_INFLEXIBLE(ReasonKind.CONCERN),
    NO_FLEXIBILITY_INFO(ReasonKind.INFO),

    // Readiness
    CONFIRMED_INTEREST(ReasonKind.POSITIVE),
    REQUESTED_VIEWING(ReasonKind.POSITIVE),
    SUBMITTED_OFFER(ReasonKind.POSITIVE),
    HOT_LEAD(ReasonKind.POSITIVE),
    COLD_LEAD(ReasonKind.CONCERN),
    UNRESPONSIVE(ReasonKind.CONCERN),

    // Other
    BACKUP_CANDIDATE(ReasonKind.INFO),
}

/**
 * One explanation line. [amounts] are rial values (rendered in the viewer's unit), [numbers] are
 * plain numbers, [keys] are enum names or place names. Confidential figures are never put here.
 */
@Serializable
data class Reason(
    val code: ReasonCode,
    val component: MatchComponent? = null,
    val amounts: List<Long> = emptyList(),
    val numbers: List<Double> = emptyList(),
    val keys: List<String> = emptyList(),
)

@Serializable
enum class MissingField(val critical: Boolean, val confidencePenalty: Double) {
    BUYER_BUDGET(true, 0.30),
    BUYER_DEPOSIT_LIMIT(true, 0.15),
    BUYER_RENT_LIMIT(true, 0.15),
    BUYER_LOCATION(true, 0.15),
    BUYER_CATEGORY(false, 0.05),
    BUYER_SIZE(false, 0.05),
    BUYER_TIMELINE(false, 0.05),
    PROPERTY_PRICE(true, 0.30),
    PROPERTY_DEPOSIT(true, 0.15),
    PROPERTY_RENT(true, 0.15),
    PROPERTY_LOCATION(true, 0.15),
    PROPERTY_COORDINATES(false, 0.05),
    PROPERTY_AREA(true, 0.10),
    PROPERTY_BEDROOMS(false, 0.05),
    PROPERTY_FLOOR(false, 0.0),
    /** A feature the buyer marked REQUIRED is not recorded on the property. */
    PROPERTY_REQUIRED_FEATURE(true, 0.05),
    PROPERTY_FEATURE(false, 0.02),
}

@Serializable
data class MissingData(val field: MissingField, val detail: String? = null)

@Serializable
data class ComponentScore(
    val component: MatchComponent,
    val weight: Int,
    /** 0.0–1.0 fulfilment of this component. */
    val fraction: Double,
    /** Integer points shown to the user; points of all components add up exactly to the total score. */
    val points: Int,
)

@Serializable enum class NegotiationStatus { FEASIBLE, NOT_FEASIBLE, UNKNOWN }

@Serializable enum class NegotiationSide { PRICE, DEPOSIT, MONTHLY_RENT }

/**
 * CONFIDENTIAL. Uses the seller's undisclosed minimum and the buyer's ceiling. The UI shows figures
 * only to users with VIEW_CONFIDENTIAL permission and never includes them in outreach to either party.
 * Neither the asking price, the minimum nor the budget is a binding offer.
 */
@Serializable
data class NegotiationAssessment(
    val side: NegotiationSide,
    val status: NegotiationStatus,
    /** Overlap of acceptable values (rial) when FEASIBLE. */
    val rangeLow: Long? = null,
    val rangeHigh: Long? = null,
    /** How far apart the parties are (rial) when NOT_FEASIBLE. */
    val gap: Long? = null,
)

@Serializable enum class RecommendedAction { CONTACT_BUYER, SCHEDULE_VIEWING, FOLLOW_UP_OFFER, COLLECT_MISSING_INFO, REVIEW, NO_ACTION }

@Serializable enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

@Serializable
data class MatchEvaluation(
    val requirementId: String,
    val propertyId: String,
    val customerId: String,
    val eligible: Boolean,
    val score: Int,
    val classification: MatchClass,
    val components: List<ComponentScore>,
    val exclusions: List<Reason>,
    val reasons: List<Reason>,
    val concerns: List<Reason>,
    val info: List<Reason>,
    val missing: List<MissingData>,
    val confidence: Double,
    val provisional: Boolean,
    val negotiation: List<NegotiationAssessment>,
    val recommendedAction: RecommendedAction,
    /** Changes whenever any input that affects the result changes; used to detect stale stored results. */
    val fingerprint: String,
) {
    val key: String get() = matchKey(requirementId, propertyId)
    val confidenceLevel: ConfidenceLevel get() = when {
        confidence >= 0.8 -> ConfidenceLevel.HIGH
        confidence >= 0.5 -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }
    fun component(c: MatchComponent): ComponentScore = components.first { it.component == c }
}

fun matchKey(requirementId: String, propertyId: String) = "$requirementId|$propertyId"

/** Explicit CRM signals used for lead readiness. Computed from activities, appointments and offers. */
@Serializable
data class EngagementSignals(
    val confirmedInterest: Boolean = false,
    val requestedViewing: Boolean = false,
    val submittedOffer: Boolean = false,
    /** Consecutive recent follow-ups with no answer. */
    val unansweredFollowUps: Int = 0,
    val lastContactAt: EpochMillis? = null,
) {
    val fingerprint: String get() = "$confirmedInterest$requestedViewing$submittedOffer$unansweredFollowUps"
}

data class BuyerCandidate(
    val customer: Customer,
    val requirement: BuyerRequirement,
    val engagement: EngagementSignals = EngagementSignals(),
)

data class PropertyCandidate(
    val property: Property,
    val seller: SellerProfile? = null,
)

/** The buyer's hard ceiling: max budget plus an explicitly stated stretch. */
internal fun BuyerRequirement.saleCeiling(): Money? {
    val max = maxBudget ?: return null
    val stretch = maxStretch
    return if (budgetFlexible == true && stretch != null) max + stretch else max
}
