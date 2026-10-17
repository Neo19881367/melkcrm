package ir.melkcrm.core.matching

import ir.melkcrm.core.geo.Geo
import ir.melkcrm.core.model.Amenity
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.FlexibilityLevel
import ir.melkcrm.core.model.LeadQuality
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.PropertyCondition
import ir.melkcrm.core.model.PropertyStatus
import ir.melkcrm.core.model.RequirementLevel
import ir.melkcrm.core.model.RequirementStatus
import ir.melkcrm.core.model.SellerProfile
import ir.melkcrm.core.model.Urgency
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.text.PersianText
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.EpochDay
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Transparent weighted buyer–property matching. Rules are documented in docs/MATCHING.md;
 * every number used here is either from [MatchingConfig] or stated in that document.
 *
 * Stage 1: hard constraints (only explicit mandatory requirements or impossible transactions exclude).
 * Stage 2: seven weighted components, each a 0..1 fraction, multiplied by its weight.
 * Stage 3: classification with configurable thresholds.
 * Stage 4: confidential negotiation-range check.
 * Stage 5: missing-data detection, confidence and provisional flag.
 */
class MatchingEngine(config: MatchingConfig = MatchingConfig()) {
    val config: MatchingConfig = config.requireValid()

    fun evaluate(buyer: BuyerCandidate, candidate: PropertyCandidate, today: EpochDay): MatchEvaluation {
        val ctx = Ctx()
        val req = buyer.requirement
        val p = candidate.property
        val seller = candidate.seller

        hardConstraints(ctx, buyer, p)
        val fractions = linkedMapOf(
            MatchComponent.BUDGET to budget(ctx, req, p, seller),
            MatchComponent.LOCATION to location(ctx, req, p),
            MatchComponent.LAYOUT to layout(ctx, req, p),
            MatchComponent.FEATURES to features(ctx, req, p, today),
            MatchComponent.TIMING to timing(ctx, req, seller, today),
            MatchComponent.FLEXIBILITY to flexibility(ctx, req, p, seller),
            MatchComponent.READINESS to readiness(ctx, buyer),
        )
        val components = toComponentScores(fractions)
        val score = components.sumOf { it.points }
        val eligible = ctx.exclusions.isEmpty()
        val missing = ctx.missing.distinct()
        val confidence = (1.0 - missing.sumOf { it.field.confidencePenalty }).coerceIn(0.1, 1.0)
        val provisional = missing.any { it.field.critical }
        val classification = if (eligible) config.thresholds.classify(score) else MatchClass.NOT_ELIGIBLE
        val negotiation = negotiation(req, p, seller)

        val action = when {
            !eligible -> RecommendedAction.NO_ACTION
            provisional -> RecommendedAction.COLLECT_MISSING_INFO
            classification == MatchClass.EXCELLENT || classification == MatchClass.STRONG -> when {
                buyer.engagement.submittedOffer -> RecommendedAction.FOLLOW_UP_OFFER
                buyer.engagement.requestedViewing || buyer.engagement.confirmedInterest -> RecommendedAction.SCHEDULE_VIEWING
                else -> RecommendedAction.CONTACT_BUYER
            }
            classification == MatchClass.POSSIBLE -> RecommendedAction.REVIEW
            else -> RecommendedAction.NO_ACTION
        }

        return MatchEvaluation(
            requirementId = req.id,
            propertyId = p.id,
            customerId = buyer.customer.id,
            eligible = eligible,
            score = score,
            classification = classification,
            components = components,
            exclusions = ctx.exclusions.toList(),
            reasons = ctx.positives.toList(),
            concerns = ctx.concerns.toList(),
            info = ctx.info.toList(),
            missing = missing,
            confidence = (confidence * 100).roundToInt() / 100.0,
            provisional = provisional,
            negotiation = negotiation,
            recommendedAction = action,
            fingerprint = fingerprint(buyer, candidate, today),
        )
    }

    /** Workflow A: ranked buyers for a property. Excluded candidates are dropped unless [includeExcluded]. */
    fun findBuyers(
        candidate: PropertyCandidate,
        buyers: List<BuyerCandidate>,
        today: EpochDay,
        includeExcluded: Boolean = false,
    ): List<MatchEvaluation> = buyers
        .map { evaluate(it, candidate, today) }
        .filter { includeExcluded || it.eligible }
        .sortedWith(ranking)

    /** Workflow B: ranked properties for one buyer requirement. */
    fun findProperties(
        buyer: BuyerCandidate,
        properties: List<PropertyCandidate>,
        today: EpochDay,
        includeExcluded: Boolean = false,
    ): List<MatchEvaluation> = properties
        .map { evaluate(buyer, it, today) }
        .filter { includeExcluded || it.eligible }
        .sortedWith(ranking)

    /** Workflow C: all pairs. */
    fun matchAll(buyers: List<BuyerCandidate>, properties: List<PropertyCandidate>, today: EpochDay): List<MatchEvaluation> =
        buyers.flatMap { b -> properties.map { evaluate(b, it, today) } }

    private val ranking = compareByDescending<MatchEvaluation> { it.eligible }
        .thenByDescending { it.score }
        .thenByDescending { it.confidence }
        .thenBy { it.key }

    // ---------------------------------------------------------------- Stage 1

    private fun hardConstraints(ctx: Ctx, buyer: BuyerCandidate, p: Property) {
        val req = buyer.requirement
        val statusOk = p.status in config.matchableStatuses ||
            (p.openToFurtherOffers && (p.status == PropertyStatus.RESERVED || p.status == PropertyStatus.UNDER_NEGOTIATION))
        if (p.deleted || !statusOk) ctx.exclude(ReasonCode.PROPERTY_NOT_AVAILABLE, keys = listOf(p.status.name))
        else if (p.status != PropertyStatus.AVAILABLE) ctx.info(ReasonCode.BACKUP_CANDIDATE, keys = listOf(p.status.name))

        if (req.deleted || req.status != RequirementStatus.ACTIVE) ctx.exclude(ReasonCode.REQUIREMENT_NOT_ACTIVE, keys = listOf(req.status.name))
        if (buyer.customer.deleted || buyer.customer.isInactive) ctx.exclude(ReasonCode.CUSTOMER_INACTIVE, keys = listOf(buyer.customer.status))
        if (p.ownerCustomerId != null && p.ownerCustomerId == buyer.customer.id) ctx.exclude(ReasonCode.OWN_PROPERTY)
        if (req.dealType !in p.dealTypes) ctx.exclude(ReasonCode.DEAL_TYPE_MISMATCH, keys = listOf(req.dealType.name))
        if (req.categories.isEmpty()) ctx.missing(MissingField.BUYER_CATEGORY)
        else if (p.category !in req.categories) ctx.exclude(ReasonCode.CATEGORY_MISMATCH, keys = listOf(p.category.name))
    }

    // ---------------------------------------------------------------- Budget (Stage 2)

    private fun budget(ctx: Ctx, req: BuyerRequirement, p: Property, seller: SellerProfile?): Double = when (req.dealType) {
        DealType.SALE -> saleBudget(ctx, req, p, seller)
        DealType.RENT -> {
            val dep = limitFraction(
                ctx, value = p.deposit, limit = req.maxDeposit ?: req.availableDeposit,
                minAcceptable = seller?.confidential?.minAcceptableDeposit, negotiable = p.negotiable,
                missingValue = MissingField.PROPERTY_DEPOSIT, missingLimit = MissingField.BUYER_DEPOSIT_LIMIT,
                ok = ReasonCode.DEPOSIT_WITHIN_LIMIT, above = ReasonCode.DEPOSIT_ABOVE_LIMIT, exclusion = ReasonCode.OVER_DEPOSIT_LIMIT,
            )
            val rent = limitFraction(
                ctx, value = p.monthlyRent, limit = req.maxMonthlyRent,
                minAcceptable = seller?.confidential?.minAcceptableMonthlyRent, negotiable = p.negotiable,
                missingValue = MissingField.PROPERTY_RENT, missingLimit = MissingField.BUYER_RENT_LIMIT,
                ok = ReasonCode.RENT_WITHIN_LIMIT, above = ReasonCode.RENT_ABOVE_LIMIT, exclusion = ReasonCode.OVER_RENT_LIMIT,
            )
            // Deposit and rent are scored separately and never converted into each other.
            (dep + rent) / 2
        }
        DealType.FULL_DEPOSIT -> limitFraction(
            ctx, value = p.fullDepositAmount, limit = req.maxDeposit ?: req.availableDeposit,
            minAcceptable = seller?.confidential?.minAcceptableDeposit, negotiable = p.negotiable,
            missingValue = MissingField.PROPERTY_DEPOSIT, missingLimit = MissingField.BUYER_DEPOSIT_LIMIT,
            ok = ReasonCode.DEPOSIT_WITHIN_LIMIT, above = ReasonCode.DEPOSIT_ABOVE_LIMIT, exclusion = ReasonCode.OVER_DEPOSIT_LIMIT,
        )
    }

    private fun saleBudget(ctx: Ctx, req: BuyerRequirement, p: Property, seller: SellerProfile?): Double {
        val c = MatchComponent.BUDGET
        val price = p.askingPrice
        if (price == null) {
            ctx.missing(MissingField.PROPERTY_PRICE)
            return UNKNOWN
        }
        val max = req.maxBudget ?: req.preferredBudget
        val min = req.minBudget
        if (max == null) {
            // Never treat a missing budget as unlimited.
            ctx.missing(MissingField.BUYER_BUDGET)
            if (min != null && price < min) ctx.concern(ReasonCode.BELOW_MIN_BUDGET, c, amounts = listOf(min.rial))
            return UNKNOWN
        }
        val ceiling = req.saleCeiling() ?: max
        val pref = req.preferredBudget?.let { if (it > max) max else it }
        return when {
            price <= max -> {
                ctx.positive(ReasonCode.WITHIN_BUDGET, c, amounts = listOf(price.rial, max.rial))
                when {
                    min != null && price < min -> {
                        ctx.concern(ReasonCode.BELOW_MIN_BUDGET, c, amounts = listOf(min.rial))
                        0.75
                    }
                    pref == null -> 1.0
                    price <= pref -> {
                        ctx.positive(ReasonCode.AT_OR_BELOW_PREFERRED_BUDGET, c, amounts = listOf(pref.rial))
                        1.0
                    }
                    else -> {
                        ctx.concern(ReasonCode.ABOVE_PREFERRED_BUDGET, c, amounts = listOf(pref.rial))
                        val span = (max.rial - pref.rial).toDouble()
                        val d = if (span <= 0) 1.0 else (price.rial - pref.rial) / span
                        1.0 - 0.3 * d.coerceIn(0.0, 1.0)
                    }
                }
            }
            price <= ceiling -> {
                ctx.concern(ReasonCode.ABOVE_MAX_WITHIN_STRETCH, c, amounts = listOf(max.rial))
                val d = (price.rial - max.rial).toDouble() / (ceiling.rial - max.rial).toDouble()
                0.6 - 0.3 * d.coerceIn(0.0, 1.0)
            }
            else -> overLimit(
                ctx, value = price, ceiling = ceiling, minAcceptable = seller?.confidential?.minAcceptablePrice,
                negotiable = p.negotiable, exclusion = ReasonCode.OVER_BUDGET, component = c,
            )
        }
    }

    /** Value above the absolute ceiling: excluded unless a negotiated price within the ceiling is plausible. */
    private fun overLimit(
        ctx: Ctx, value: Money, ceiling: Money, minAcceptable: Money?, negotiable: Boolean?,
        exclusion: ReasonCode, component: MatchComponent,
    ): Double {
        if (minAcceptable != null) {
            return if (minAcceptable <= ceiling) {
                // Confidential minimum fits the buyer's ceiling; the explanation does not reveal it.
                ctx.concern(ReasonCode.ABOVE_MAX_NEGOTIATION_NEEDED, component)
                0.35
            } else {
                ctx.exclude(exclusion, amounts = listOf(value.rial, ceiling.rial))
                0.0
            }
        }
        val overPercent = (value.rial - ceiling.rial).toDouble() * 100.0 / ceiling.rial.coerceAtLeast(1)
        return if (negotiable != false && overPercent <= config.overBudgetTolerancePercent) {
            ctx.concern(ReasonCode.ABOVE_MAX_NEGOTIATION_NEEDED, component, numbers = listOf(round1(overPercent)))
            0.15
        } else {
            ctx.exclude(exclusion, amounts = listOf(value.rial, ceiling.rial))
            0.0
        }
    }

    private fun limitFraction(
        ctx: Ctx, value: Money?, limit: Money?, minAcceptable: Money?, negotiable: Boolean?,
        missingValue: MissingField, missingLimit: MissingField,
        ok: ReasonCode, above: ReasonCode, exclusion: ReasonCode,
    ): Double {
        val c = MatchComponent.BUDGET
        if (value == null) {
            ctx.missing(missingValue)
            return UNKNOWN
        }
        if (limit == null) {
            ctx.missing(missingLimit)
            return UNKNOWN
        }
        return if (value <= limit) {
            ctx.positive(ok, c, amounts = listOf(value.rial, limit.rial))
            1.0
        } else {
            val f = overLimit(ctx, value, limit, minAcceptable, negotiable, exclusion, c)
            if (f > 0) ctx.concern(above, c, amounts = listOf(limit.rial))
            f
        }
    }

    // ---------------------------------------------------------------- Location

    private fun location(ctx: Ctx, req: BuyerRequirement, p: Property): Double {
        val c = MatchComponent.LOCATION
        if (req.excludedCities.any { PersianText.sameName(it, p.city) }) ctx.exclude(ReasonCode.EXCLUDED_CITY, keys = listOfNotNull(p.city))
        if (req.excludedNeighborhoods.any { PersianText.sameName(it, p.neighborhood) }) ctx.exclude(ReasonCode.EXCLUDED_NEIGHBORHOOD, keys = listOfNotNull(p.neighborhood))

        val hasText = req.provinces.isNotEmpty() || req.cities.isNotEmpty() || req.neighborhoods.isNotEmpty() || req.alternativeNeighborhoods.isNotEmpty()
        val hasAreas = req.mapAreas.isNotEmpty()
        if (!hasText && !hasAreas) {
            ctx.missing(MissingField.BUYER_LOCATION)
            ctx.info(ReasonCode.NO_LOCATION_PREFERENCE)
            return 0.5
        }
        if (p.city.isNullOrBlank() && p.neighborhood.isNullOrBlank() && p.location == null) {
            ctx.missing(MissingField.PROPERTY_LOCATION)
            return UNKNOWN
        }

        val nbMatch = req.neighborhoods.any { PersianText.sameName(it, p.neighborhood) }
        val altMatch = !nbMatch && req.alternativeNeighborhoods.any { PersianText.sameName(it, p.neighborhood) }
        val cityMatch = req.cities.any { PersianText.sameName(it, p.city) }
        val provinceMatch = req.provinces.any { PersianText.sameName(it, p.province) }
        val onlyCityLevel = req.neighborhoods.isEmpty() && req.alternativeNeighborhoods.isEmpty()

        var areaDistance: Double? = null
        val point = p.location?.point
        if (hasAreas && point != null) {
            areaDistance = req.mapAreas.mapNotNull { Geo.distanceToAreaKm(point, it) }.minOrNull()
        }

        var best = 0.0
        var bestReason: Reason? = null
        fun consider(f: Double, r: Reason) {
            if (f > best) {
                best = f
                bestReason = r
            }
        }
        if (nbMatch) consider(1.0, Reason(ReasonCode.PREFERRED_NEIGHBORHOOD, c, keys = listOfNotNull(p.neighborhood)))
        if (altMatch) consider(0.75, Reason(ReasonCode.ALTERNATIVE_NEIGHBORHOOD, c, keys = listOfNotNull(p.neighborhood)))
        if (areaDistance != null) {
            val full = config.nearAreaFullKm
            val zero = config.nearAreaZeroKm
            when {
                areaDistance <= 0.0 -> consider(1.0, Reason(ReasonCode.INSIDE_MAP_AREA, c))
                areaDistance <= full -> consider(0.8, Reason(ReasonCode.NEAR_MAP_AREA, c, numbers = listOf(round1(areaDistance))))
                areaDistance < zero -> consider(0.8 * (1 - (areaDistance - full) / (zero - full)), Reason(ReasonCode.NEAR_MAP_AREA, c, numbers = listOf(round1(areaDistance))))
            }
        }
        if (cityMatch) consider(if (onlyCityLevel && !hasAreas) 0.85 else 0.4, Reason(ReasonCode.PREFERRED_CITY, c, keys = listOfNotNull(p.city)))
        if (provinceMatch) {
            val provinceOnlyPreference = req.cities.isEmpty() && onlyCityLevel && !hasAreas
            consider(if (provinceOnlyPreference) 0.7 else 0.2, Reason(ReasonCode.PREFERRED_PROVINCE, c, keys = listOfNotNull(p.province)))
        }

        bestReason?.let { ctx.positives += it }
        if (cityMatch && !onlyCityLevel && !nbMatch && !altMatch && (areaDistance == null || areaDistance > 0.0)) {
            ctx.concern(ReasonCode.OUTSIDE_PREFERRED_NEIGHBORHOOD, c, keys = listOfNotNull(p.neighborhood))
        }
        if (best == 0.0 || (best <= 0.2 && !cityMatch)) {
            ctx.concern(ReasonCode.OUTSIDE_PREFERRED_LOCATION, c, keys = listOfNotNull(p.city, p.neighborhood))
        }
        if (hasAreas && point == null && !nbMatch && !altMatch) ctx.missing(MissingField.PROPERTY_COORDINATES)

        if (req.locationStrict) {
            val insideStated = nbMatch || altMatch || (areaDistance != null && areaDistance <= 0.0) ||
                (cityMatch && onlyCityLevel && !hasAreas) ||
                (provinceMatch && req.cities.isEmpty() && onlyCityLevel && !hasAreas)
            // Cannot decide without coordinates when only map areas were given; keep it provisional instead.
            val undecidable = hasAreas && point == null && !hasText
            if (!insideStated && !undecidable) ctx.exclude(ReasonCode.OUTSIDE_REQUIRED_LOCATION, keys = listOfNotNull(p.city, p.neighborhood))
        }
        if (hasAreas && point == null && !hasText) return UNKNOWN
        return best
    }

    // ---------------------------------------------------------------- Layout

    private fun layout(ctx: Ctx, req: BuyerRequirement, p: Property): Double {
        val c = MatchComponent.LAYOUT
        var sum = 0.0
        var weights = 0.0
        fun part(f: Double, w: Double) {
            sum += f * w
            weights += w
        }

        if (req.minArea != null || req.maxArea != null) {
            val a = p.effectiveArea
            if (a == null) {
                ctx.missing(MissingField.PROPERTY_AREA)
                part(UNKNOWN, 0.5)
            } else {
                val min = req.minArea
                val max = req.maxArea
                val f = when {
                    min != null && a < min -> {
                        ctx.concern(ReasonCode.AREA_BELOW_MIN, c, numbers = listOf(a, min))
                        (1 - ((min - a) / min) * 100 / config.areaShortfallZeroPercent).coerceAtLeast(0.0)
                    }
                    max != null && a > max -> {
                        ctx.concern(ReasonCode.AREA_ABOVE_MAX, c, numbers = listOf(a, max))
                        (1 - ((a - max) / max) * 100 / config.areaExcessZeroPercent).coerceAtLeast(0.0)
                    }
                    else -> {
                        ctx.positive(ReasonCode.AREA_FITS, c, numbers = listOf(a))
                        1.0
                    }
                }
                if (f < 1.0 && req.areaStrict) ctx.exclude(ReasonCode.AREA_OUT_OF_RANGE, numbers = listOf(a))
                part(f, 0.5)
            }
        }

        if (req.minBedrooms != null || req.maxBedrooms != null) {
            val b = p.bedrooms
            if (b == null) {
                ctx.missing(MissingField.PROPERTY_BEDROOMS)
                part(UNKNOWN, 0.3)
            } else {
                val min = req.minBedrooms
                val max = req.maxBedrooms
                val f = when {
                    min != null && b < min -> {
                        ctx.concern(ReasonCode.FEWER_BEDROOMS, c, numbers = listOf(b.toDouble(), min.toDouble()))
                        if (min - b == 1) 0.4 else 0.0
                    }
                    max != null && b > max -> {
                        ctx.concern(ReasonCode.MORE_BEDROOMS, c, numbers = listOf(b.toDouble(), max.toDouble()))
                        0.8
                    }
                    else -> {
                        ctx.positive(ReasonCode.BEDROOMS_FIT, c, numbers = listOf(b.toDouble()))
                        1.0
                    }
                }
                if (f < 1.0 && req.bedroomsStrict) ctx.exclude(ReasonCode.BEDROOMS_OUT_OF_RANGE, numbers = listOf(b.toDouble()))
                part(f, 0.3)
            }
        }

        if (req.minFloor != null || req.maxFloor != null) {
            val fl = p.floor
            if (fl == null) {
                ctx.missing(MissingField.PROPERTY_FLOOR)
                part(UNKNOWN, 0.2)
            } else {
                val ok = (req.minFloor == null || fl >= req.minFloor) && (req.maxFloor == null || fl <= req.maxFloor)
                if (ok) ctx.positive(ReasonCode.FLOOR_FITS, c, numbers = listOf(fl.toDouble()))
                else ctx.concern(ReasonCode.FLOOR_OUTSIDE_RANGE, c, numbers = listOf(fl.toDouble()))
                part(if (ok) 1.0 else 0.3, 0.2)
            }
        }

        if (weights == 0.0) {
            ctx.missing(MissingField.BUYER_SIZE)
            return 0.6
        }
        return sum / weights
    }

    // ---------------------------------------------------------------- Features

    private fun features(ctx: Ctx, req: BuyerRequirement, p: Property, today: EpochDay): Double {
        val c = MatchComponent.FEATURES
        val items = mutableListOf<Triple<String, RequirementLevel, Boolean?>>()
        items += Triple(Amenity.PARKING.name, req.parking, p.hasFeature(Amenity.PARKING))
        items += Triple(Amenity.ELEVATOR.name, req.elevator, p.hasFeature(Amenity.ELEVATOR))
        items += Triple(Amenity.STORAGE.name, req.storage, p.hasFeature(Amenity.STORAGE))
        req.otherAmenities.forEach { (amenity, level) -> items += Triple(amenity.name, level, p.hasFeature(amenity)) }

        val age = p.constructionYear?.let { AgencyTime.buildingAge(it, p.constructionYearCalendar, today) }
        if (req.newBuild != RequirementLevel.NOT_IMPORTANT) {
            val isNew = when {
                p.condition == PropertyCondition.NEW -> true
                age != null -> age <= 2
                p.condition != null -> false
                else -> null
            }
            items += Triple(FEATURE_NEW_BUILD, req.newBuild, isNew)
        }
        req.maxBuildingAgeYears?.let { maxAge -> items += Triple(FEATURE_BUILDING_AGE, req.buildingAgeLevel, age?.let { it <= maxAge }) }
        if (req.acceptableDocuments.isNotEmpty()) items += Triple(FEATURE_DOCUMENT, req.documentLevel, p.documentStatus?.let { it in req.acceptableDocuments })
        if (req.acceptableConditions.isNotEmpty()) items += Triple(FEATURE_CONDITION, RequirementLevel.PREFERRED, p.condition?.let { it in req.acceptableConditions })
        if (req.preferredFacing.isNotEmpty()) items += Triple(FEATURE_FACING, RequirementLevel.PREFERRED, p.facing?.let { it in req.preferredFacing })
        req.furnished?.let { f -> items += Triple(FEATURE_FURNISHED, RequirementLevel.PREFERRED, p.furnished?.let { it == f }) }

        var sum = 0.0
        var weights = 0.0
        for ((key, level, has) in items) {
            if (level == RequirementLevel.NOT_IMPORTANT) continue
            val w = if (level == RequirementLevel.REQUIRED) 2.0 else 1.0
            val f = when (has) {
                true -> {
                    ctx.positive(ReasonCode.FEATURE_SATISFIED, c, keys = listOf(key))
                    1.0
                }
                false -> {
                    if (level == RequirementLevel.REQUIRED) ctx.exclude(ReasonCode.MANDATORY_FEATURE_MISSING, keys = listOf(key))
                    else ctx.concern(ReasonCode.FEATURE_MISSING, c, keys = listOf(key))
                    0.0
                }
                null -> {
                    if (level == RequirementLevel.REQUIRED) {
                        ctx.concern(ReasonCode.REQUIRED_FEATURE_UNKNOWN, c, keys = listOf(key))
                        ctx.missing(MissingField.PROPERTY_REQUIRED_FEATURE, key)
                    } else {
                        ctx.missing(MissingField.PROPERTY_FEATURE, key)
                    }
                    0.5
                }
            }
            sum += f * w
            weights += w
        }
        if (weights == 0.0) {
            ctx.info(ReasonCode.NO_FEATURE_REQUIREMENTS)
            return 1.0
        }
        return sum / weights
    }

    // ---------------------------------------------------------------- Timing

    private fun timing(ctx: Ctx, req: BuyerRequirement, seller: SellerProfile?, today: EpochDay): Double {
        val c = MatchComponent.TIMING
        var f = when (req.urgency) {
            Urgency.IMMEDIATE -> 1.0
            Urgency.WITHIN_1_MONTH -> 0.9
            Urgency.WITHIN_3_MONTHS -> 0.7
            Urgency.WITHIN_6_MONTHS -> 0.5
            Urgency.FLEXIBLE -> 0.4
            Urgency.UNKNOWN -> UNKNOWN
        }
        val expected = req.expectedDate
        if (expected != null) {
            val days = expected - today
            f = when {
                days <= 30 -> 1.0
                days <= 90 -> 0.75
                days <= 180 -> 0.55
                else -> 0.4
            }
        } else if (req.urgency == Urgency.UNKNOWN) {
            ctx.missing(MissingField.BUYER_TIMELINE)
        }
        val effectiveDays = expected?.let { it - today } ?: req.urgency.days?.toLong()
        when {
            effectiveDays != null && effectiveDays <= 30 -> ctx.positive(ReasonCode.URGENT_BUYER, c, numbers = listOf(effectiveDays.coerceAtLeast(0).toDouble()))
            effectiveDays != null && effectiveDays > 120 -> ctx.concern(ReasonCode.SLOW_TIMELINE, c, numbers = listOf(effectiveDays.toDouble()))
        }

        val deadline = seller?.saleDeadline
        if (deadline != null && effectiveDays != null) {
            if (today + effectiveDays <= deadline) {
                ctx.positive(ReasonCode.FITS_SELLER_DEADLINE, c)
                f = (f + 0.1).coerceAtMost(1.0)
            } else {
                ctx.concern(ReasonCode.BUYER_LATER_THAN_DEADLINE, c)
                f *= 0.6
            }
        }
        if (req.needsToSellFirst == true) {
            ctx.concern(ReasonCode.NEEDS_TO_SELL_FIRST, c)
            f *= 0.75
        }
        return f
    }

    // ---------------------------------------------------------------- Flexibility

    private fun flexibility(ctx: Ctx, req: BuyerRequirement, p: Property, seller: SellerProfile?): Double {
        val c = MatchComponent.FLEXIBILITY
        val values = mutableListOf<Double>()
        flexValue(req.negotiationFlexibility)?.let {
            values += it
            if (it >= 0.7) ctx.positive(ReasonCode.BUYER_FLEXIBLE, c) else if (it <= 0.2) ctx.concern(ReasonCode.BUYER_INFLEXIBLE, c)
        }
        if (req.dealType == DealType.SALE && req.budgetFlexible == true) values += 0.8
        flexValue(seller?.priceFlexibility ?: FlexibilityLevel.UNKNOWN)?.let {
            values += it
            if (it >= 0.7) ctx.positive(ReasonCode.SELLER_FLEXIBLE, c) else if (it <= 0.2) ctx.concern(ReasonCode.SELLER_INFLEXIBLE, c)
        }
        when (p.negotiable) {
            true -> {
                values += 0.8
                ctx.positive(ReasonCode.PROPERTY_NEGOTIABLE, c)
            }
            false -> values += 0.3
            null -> Unit
        }
        if (values.isEmpty()) {
            // No evidence either way: neutral, never inferred.
            ctx.info(ReasonCode.NO_FLEXIBILITY_INFO)
            return 0.5
        }
        return values.average()
    }

    private fun flexValue(level: FlexibilityLevel): Double? = when (level) {
        FlexibilityLevel.NONE -> 0.2
        FlexibilityLevel.LOW -> 0.45
        FlexibilityLevel.MEDIUM -> 0.7
        FlexibilityLevel.HIGH -> 1.0
        FlexibilityLevel.UNKNOWN -> null
    }

    // ---------------------------------------------------------------- Readiness

    private fun readiness(ctx: Ctx, buyer: BuyerCandidate): Double {
        val c = MatchComponent.READINESS
        val e = buyer.engagement
        var f = 0.5
        if (e.confirmedInterest) { f += 0.15; ctx.positive(ReasonCode.CONFIRMED_INTEREST, c) }
        if (e.requestedViewing) { f += 0.15; ctx.positive(ReasonCode.REQUESTED_VIEWING, c) }
        if (e.submittedOffer) { f += 0.2; ctx.positive(ReasonCode.SUBMITTED_OFFER, c) }
        when (buyer.requirement.leadQuality) {
            LeadQuality.HOT -> { f += 0.1; ctx.positive(ReasonCode.HOT_LEAD, c) }
            LeadQuality.COLD -> { f -= 0.1; ctx.concern(ReasonCode.COLD_LEAD, c) }
            else -> Unit
        }
        if (e.unansweredFollowUps >= 2) {
            f -= if (e.unansweredFollowUps >= 4) 0.25 else 0.15
            ctx.concern(ReasonCode.UNRESPONSIVE, c, numbers = listOf(e.unansweredFollowUps.toDouble()))
        }
        // Floor keeps an unresponsive but otherwise suitable buyer in the list.
        return f.coerceIn(0.1, 1.0)
    }

    // ---------------------------------------------------------------- Stage 4

    private fun negotiation(req: BuyerRequirement, p: Property, seller: SellerProfile?): List<NegotiationAssessment> {
        val conf = seller?.confidential
        return when (req.dealType) {
            DealType.SALE -> listOf(range(NegotiationSide.PRICE, conf?.minAcceptablePrice, req.saleCeiling(), p.askingPrice))
            DealType.RENT -> listOf(
                range(NegotiationSide.DEPOSIT, conf?.minAcceptableDeposit, req.maxDeposit ?: req.availableDeposit, p.deposit),
                range(NegotiationSide.MONTHLY_RENT, conf?.minAcceptableMonthlyRent, req.maxMonthlyRent, p.monthlyRent),
            )
            DealType.FULL_DEPOSIT -> listOf(range(NegotiationSide.DEPOSIT, conf?.minAcceptableDeposit, req.maxDeposit ?: req.availableDeposit, p.fullDepositAmount))
        }
    }

    private fun range(side: NegotiationSide, sellerMin: Money?, buyerMax: Money?, asking: Money?): NegotiationAssessment {
        if (sellerMin == null || buyerMax == null) return NegotiationAssessment(side, NegotiationStatus.UNKNOWN)
        return if (sellerMin <= buyerMax) {
            val high = if (asking != null && asking < buyerMax) maxOf(asking, sellerMin) else buyerMax
            NegotiationAssessment(side, NegotiationStatus.FEASIBLE, rangeLow = sellerMin.rial, rangeHigh = high.rial)
        } else {
            NegotiationAssessment(side, NegotiationStatus.NOT_FEASIBLE, gap = (sellerMin - buyerMax).rial)
        }
    }

    // ---------------------------------------------------------------- Scores

    /** Integer points per component via largest-remainder rounding, so the breakdown always sums to the score. */
    private fun toComponentScores(fractions: Map<MatchComponent, Double>): List<ComponentScore> {
        val exact = fractions.map { (c, f) -> c to config.weights.of(c) * f.coerceIn(0.0, 1.0) }
        val total = (exact.sumOf { it.second } + 1e-9).roundToInt()
        val floors = exact.map { it.first to floor(it.second + 1e-9).toInt() }.toMutableList()
        var remaining = total - floors.sumOf { it.second }
        val order = exact.indices.sortedByDescending { exact[it].second - floor(exact[it].second + 1e-9) }
        for (i in order) {
            if (remaining <= 0) break
            floors[i] = floors[i].first to floors[i].second + 1
            remaining--
        }
        return exact.indices.map { i ->
            val c = exact[i].first
            ComponentScore(c, config.weights.of(c), round2(fractions.getValue(c).coerceIn(0.0, 1.0)), floors[i].second)
        }
    }

    private fun fingerprint(b: BuyerCandidate, pc: PropertyCandidate, today: EpochDay): String =
        listOf(
            b.requirement.id, b.requirement.version, b.requirement.updatedAt,
            b.customer.id, b.customer.status, b.customer.updatedAt, b.engagement.fingerprint,
            pc.property.id, pc.property.version, pc.property.updatedAt,
            pc.seller?.id, pc.seller?.updatedAt,
            config.fingerprint,
            // Timing depends on the date; results are refreshed at most daily.
            today,
        ).joinToString("|")

    private class Ctx {
        val exclusions = mutableListOf<Reason>()
        val positives = mutableListOf<Reason>()
        val concerns = mutableListOf<Reason>()
        val info = mutableListOf<Reason>()
        val missing = mutableListOf<MissingData>()

        fun exclude(code: ReasonCode, amounts: List<Long> = emptyList(), numbers: List<Double> = emptyList(), keys: List<String> = emptyList()) {
            exclusions += Reason(code, null, amounts, numbers, keys)
        }
        fun positive(code: ReasonCode, c: MatchComponent, amounts: List<Long> = emptyList(), numbers: List<Double> = emptyList(), keys: List<String> = emptyList()) {
            positives += Reason(code, c, amounts, numbers, keys)
        }
        fun concern(code: ReasonCode, c: MatchComponent, amounts: List<Long> = emptyList(), numbers: List<Double> = emptyList(), keys: List<String> = emptyList()) {
            concerns += Reason(code, c, amounts, numbers, keys)
        }
        fun info(code: ReasonCode, keys: List<String> = emptyList()) {
            info += Reason(code, keys = keys)
        }
        fun missing(field: MissingField, detail: String? = null) {
            missing += MissingData(field, detail)
        }
    }

    companion object {
        /** Partial credit for an unknown value: never full credit, never zero. */
        const val UNKNOWN = 0.3

        const val FEATURE_NEW_BUILD = "NEW_BUILD"
        const val FEATURE_BUILDING_AGE = "BUILDING_AGE"
        const val FEATURE_DOCUMENT = "DOCUMENT"
        const val FEATURE_CONDITION = "CONDITION"
        const val FEATURE_FACING = "FACING"
        const val FEATURE_FURNISHED = "FURNISHED"

        private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
        private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
    }
}
