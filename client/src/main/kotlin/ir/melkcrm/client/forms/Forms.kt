package ir.melkcrm.client.forms

import ir.melkcrm.core.commission.CommissionRule
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import java.time.ZoneId

/*
 * Editable text state of each editor and its conversion to the stored entity. The screens hold one
 * of these, show the errors next to the fields named in FormResult.Errors (the same names the core
 * validators use) and pass the built entity to the repository, which validates again.
 *
 * Money fields are text in the unit the agent chose (toman by default); the entity always stores rial.
 */

data class CustomerForm(
    val fullName: String = "",
    val mobile: String = "",
    val altPhone: String = "",
    val email: String = "",
    val roles: Set<CustomerRole> = emptySet(),
    val preferredContact: ContactMethod? = null,
    val province: String = "",
    val city: String = "",
    val neighborhood: String = "",
    val address: String = "",
    val source: CustomerSource? = null,
    val assignedAgentId: String? = null,
    val status: String = CustomerStatus.NEW_LEAD.name,
    val priority: Priority = Priority.MEDIUM,
    val nextFollowUpDate: EpochDay? = null,
    val notes: String = "",
    val tags: String = "",
    val consent: ConsentPreferences = ConsentPreferences(),
    val isDraft: Boolean = false,
) {
    fun build(base: Customer, now: EpochMillis): FormResult<Customer> = FormErrors().result {
        val consentChanged = consent.copy(recordedAt = null) != base.consent.copy(recordedAt = null)
        base.copy(
            fullName = fullName.trim(),
            mobile = Input.text(mobile),
            altPhone = Input.text(altPhone),
            email = Input.text(email),
            roles = roles,
            preferredContact = preferredContact,
            province = Input.text(province),
            city = Input.text(city),
            neighborhood = Input.text(neighborhood),
            address = Input.text(address),
            source = source,
            assignedAgentId = assignedAgentId,
            status = status,
            priority = priority,
            nextFollowUpDate = nextFollowUpDate,
            notes = Input.text(notes),
            tags = Input.list(tags),
            consent = if (consentChanged) consent.copy(recordedAt = now) else consent,
            isDraft = isDraft,
        )
    }

    companion object {
        fun from(c: Customer, persian: Boolean) = CustomerForm(
            c.fullName, c.mobile.orEmpty(), c.altPhone.orEmpty(), c.email.orEmpty(), c.roles, c.preferredContact,
            c.province.orEmpty(), c.city.orEmpty(), c.neighborhood.orEmpty(), c.address.orEmpty(), c.source,
            c.assignedAgentId, c.status, c.priority, c.nextFollowUpDate, c.notes.orEmpty(), Input.listText(c.tags, persian),
            c.consent, c.isDraft,
        )
    }
}

data class RequirementForm(
    val title: String = "",
    val dealType: DealType = DealType.SALE,
    val categories: Set<PropertyCategory> = emptySet(),
    val provinces: String = "",
    val cities: String = "",
    val neighborhoods: String = "",
    val alternativeNeighborhoods: String = "",
    val excludedCities: String = "",
    val excludedNeighborhoods: String = "",
    val mapAreas: List<GeoArea> = emptyList(),
    val locationStrict: Boolean = false,
    val minArea: String = "",
    val maxArea: String = "",
    val areaStrict: Boolean = false,
    val minBedrooms: String = "",
    val maxBedrooms: String = "",
    val bedroomsStrict: Boolean = false,
    val minFloor: String = "",
    val maxFloor: String = "",
    val maxBuildingAge: String = "",
    val buildingAgeLevel: RequirementLevel = RequirementLevel.PREFERRED,
    val parking: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val elevator: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val storage: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val newBuild: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val otherAmenities: Map<Amenity, RequirementLevel> = emptyMap(),
    val acceptableConditions: Set<PropertyCondition> = emptySet(),
    val acceptableDocuments: Set<DocumentStatus> = emptySet(),
    val documentLevel: RequirementLevel = RequirementLevel.PREFERRED,
    val preferredFacing: Set<Facing> = emptySet(),
    val furnished: FurnishedStatus? = null,
    val minBudget: String = "",
    val maxBudget: String = "",
    val preferredBudget: String = "",
    val budgetFlexible: Boolean? = null,
    val maxStretch: String = "",
    val availableDeposit: String = "",
    val maxDeposit: String = "",
    val maxMonthlyRent: String = "",
    val financing: FinancingType = FinancingType.UNKNOWN,
    val cashBuyer: Boolean? = null,
    val urgency: Urgency = Urgency.UNKNOWN,
    val expectedDate: EpochDay? = null,
    val reason: PurchaseReason? = null,
    val needsToSellFirst: Boolean? = null,
    val preferredViewingTimes: String = "",
    val negotiationFlexibility: FlexibilityLevel = FlexibilityLevel.UNKNOWN,
    val leadQuality: LeadQuality = LeadQuality.UNKNOWN,
    val status: RequirementStatus = RequirementStatus.ACTIVE,
    val notes: String = "",
) {
    fun build(base: BuyerRequirement, unit: CurrencyUnit): FormResult<BuyerRequirement> {
        val e = FormErrors()
        val minA = e.take("minArea", Input.decimal(minArea))
        val maxA = e.take("maxArea", Input.decimal(maxArea))
        val minB = e.take("minBedrooms", Input.int(minBedrooms, max = 50))
        val maxB = e.take("maxBedrooms", Input.int(maxBedrooms, max = 50))
        val minF = e.take("minFloor", Input.signedInt(minFloor))
        val maxF = e.take("maxFloor", Input.signedInt(maxFloor))
        val age = e.take("maxBuildingAgeYears", Input.int(maxBuildingAge, max = 200))
        val sale = dealType == DealType.SALE
        val minBud = e.take("minBudget", Input.money(minBudget, unit))
        val maxBud = e.take("maxBudget", Input.money(maxBudget, unit))
        val pref = e.take("preferredBudget", Input.money(preferredBudget, unit))
        val stretch = e.take("maxStretch", Input.money(maxStretch, unit))
        val avDep = e.take("availableDeposit", Input.money(availableDeposit, unit))
        val maxDep = e.take("maxDeposit", Input.money(maxDeposit, unit))
        val maxRent = e.take("maxMonthlyRent", Input.money(maxMonthlyRent, unit))
        return e.result {
            base.copy(
                title = Input.text(title), dealType = dealType, categories = categories,
                provinces = Input.list(provinces), cities = Input.list(cities), neighborhoods = Input.list(neighborhoods),
                alternativeNeighborhoods = Input.list(alternativeNeighborhoods), excludedCities = Input.list(excludedCities),
                excludedNeighborhoods = Input.list(excludedNeighborhoods), mapAreas = mapAreas, locationStrict = locationStrict,
                minArea = minA, maxArea = maxA, areaStrict = areaStrict, minBedrooms = minB, maxBedrooms = maxB,
                bedroomsStrict = bedroomsStrict, minFloor = minF, maxFloor = maxF, maxBuildingAgeYears = age,
                buildingAgeLevel = buildingAgeLevel, parking = parking, elevator = elevator, storage = storage, newBuild = newBuild,
                otherAmenities = otherAmenities.filterValues { it != RequirementLevel.NOT_IMPORTANT },
                acceptableConditions = acceptableConditions, acceptableDocuments = acceptableDocuments, documentLevel = documentLevel,
                preferredFacing = preferredFacing, furnished = furnished,
                // Budget fields of the other deal type are cleared so a stale value never affects matching.
                minBudget = minBud.takeIf { sale }, maxBudget = maxBud.takeIf { sale }, preferredBudget = pref.takeIf { sale },
                budgetFlexible = budgetFlexible, maxStretch = stretch.takeIf { sale },
                availableDeposit = avDep.takeIf { !sale }, maxDeposit = maxDep.takeIf { !sale },
                maxMonthlyRent = maxRent.takeIf { dealType == DealType.RENT },
                financing = financing, cashBuyer = cashBuyer, urgency = urgency, expectedDate = expectedDate, reason = reason,
                needsToSellFirst = needsToSellFirst, preferredViewingTimes = Input.text(preferredViewingTimes),
                negotiationFlexibility = negotiationFlexibility, leadQuality = leadQuality, status = status, notes = Input.text(notes),
            )
        }
    }

    companion object {
        fun from(r: BuyerRequirement, unit: CurrencyUnit, persianDigits: Boolean, persian: Boolean): RequirementForm {
            fun m(v: Money?) = Input.moneyText(v, unit, persianDigits)
            fun l(v: List<String>) = Input.listText(v, persian)
            fun i(v: Int?) = Input.intText(v, persianDigits)
            fun d(v: Double?) = Input.decimalText(v, persianDigits)
            return RequirementForm(
                r.title.orEmpty(), r.dealType, r.categories, l(r.provinces), l(r.cities), l(r.neighborhoods),
                l(r.alternativeNeighborhoods), l(r.excludedCities), l(r.excludedNeighborhoods), r.mapAreas, r.locationStrict,
                d(r.minArea), d(r.maxArea), r.areaStrict, i(r.minBedrooms), i(r.maxBedrooms), r.bedroomsStrict,
                i(r.minFloor), i(r.maxFloor), i(r.maxBuildingAgeYears), r.buildingAgeLevel, r.parking, r.elevator, r.storage,
                r.newBuild, r.otherAmenities, r.acceptableConditions, r.acceptableDocuments, r.documentLevel, r.preferredFacing,
                r.furnished, m(r.minBudget), m(r.maxBudget), m(r.preferredBudget), r.budgetFlexible, m(r.maxStretch),
                m(r.availableDeposit), m(r.maxDeposit), m(r.maxMonthlyRent), r.financing, r.cashBuyer, r.urgency, r.expectedDate,
                r.reason, r.needsToSellFirst, r.preferredViewingTimes.orEmpty(), r.negotiationFlexibility, r.leadQuality,
                r.status, r.notes.orEmpty(),
            )
        }
    }
}

data class SellerForm(
    val status: SellerStatus = SellerStatus.PROSPECT,
    val saleDeadline: EpochDay? = null,
    val motivation: String = "",
    val priceFlexibility: FlexibilityLevel = FlexibilityLevel.UNKNOWN,
    val preferredContact: ContactMethod? = null,
    val negotiationConditions: String = "",
    val commissionAgreement: String = "",
    val commissionRuleId: String? = null,
    val exclusive: Boolean? = null,
    val exclusiveUntil: EpochDay? = null,
    val documents: DocumentChecklist = DocumentChecklist(),
    val followUpIntervalDays: String = "",
    val instructions: String = "",
    val minPrice: String = "",
    val minDeposit: String = "",
    val minRent: String = "",
    val confidentialNotes: String = "",
) {
    /** Confidential fields are only applied when [canEditConfidential]; otherwise the stored ones are kept. */
    fun build(base: SellerProfile, unit: CurrencyUnit, canEditConfidential: Boolean): FormResult<SellerProfile> {
        val e = FormErrors()
        val days = e.take("followUpIntervalDays", Input.int(followUpIntervalDays, min = 1, max = 365))
        val minP = if (canEditConfidential) e.take("minAcceptablePrice", Input.money(minPrice, unit)) else null
        val minD = if (canEditConfidential) e.take("minAcceptableDeposit", Input.money(minDeposit, unit)) else null
        val minR = if (canEditConfidential) e.take("minAcceptableMonthlyRent", Input.money(minRent, unit)) else null
        return e.result {
            base.copy(
                status = status, saleDeadline = saleDeadline, motivation = Input.text(motivation), priceFlexibility = priceFlexibility,
                preferredContact = preferredContact, negotiationConditions = Input.text(negotiationConditions),
                commissionAgreement = Input.text(commissionAgreement), commissionRuleId = commissionRuleId, exclusive = exclusive,
                exclusiveUntil = exclusiveUntil.takeIf { exclusive == true }, documents = documents, followUpIntervalDays = days,
                instructions = Input.text(instructions),
                confidential = if (canEditConfidential) SellerConfidential(minP, minD, minR, Input.text(confidentialNotes)) else base.confidential,
            )
        }
    }

    companion object {
        fun from(s: SellerProfile, unit: CurrencyUnit, persianDigits: Boolean) = SellerForm(
            s.status, s.saleDeadline, s.motivation.orEmpty(), s.priceFlexibility, s.preferredContact,
            s.negotiationConditions.orEmpty(), s.commissionAgreement.orEmpty(), s.commissionRuleId, s.exclusive, s.exclusiveUntil,
            s.documents, Input.intText(s.followUpIntervalDays, persianDigits), s.instructions.orEmpty(),
            Input.moneyText(s.confidential.minAcceptablePrice, unit, persianDigits),
            Input.moneyText(s.confidential.minAcceptableDeposit, unit, persianDigits),
            Input.moneyText(s.confidential.minAcceptableMonthlyRent, unit, persianDigits),
            s.confidential.notes.orEmpty(),
        )
    }
}

data class PropertyForm(
    val reference: String = "",
    val title: String = "",
    val category: PropertyCategory = PropertyCategory.APARTMENT,
    val dealTypes: Set<DealType> = setOf(DealType.SALE),
    val askingPrice: String = "",
    val deposit: String = "",
    val monthlyRent: String = "",
    val fullDepositAmount: String = "",
    val estimatedMarketValue: String = "",
    val negotiable: Boolean? = null,
    val province: String = "",
    val city: String = "",
    val neighborhood: String = "",
    val address: String = "",
    val location: Location? = null,
    val landArea: String = "",
    val builtArea: String = "",
    val bedrooms: String = "",
    val bathrooms: String = "",
    val floor: String = "",
    val totalFloors: String = "",
    val unitsPerFloor: String = "",
    val constructionYear: String = "",
    val constructionYearCalendar: CalendarSystem = CalendarSystem.JALALI,
    val parkingSpaces: String = "",
    val storage: Boolean? = null,
    val elevator: Boolean? = null,
    val heating: HeatingType? = null,
    val cooling: CoolingType? = null,
    val amenities: Set<Amenity> = emptySet(),
    val facing: Facing? = null,
    val condition: PropertyCondition? = null,
    val furnished: FurnishedStatus? = null,
    val documentStatus: DocumentStatus? = null,
    val ownerCustomerId: String? = null,
    val assignedAgentId: String? = null,
    val commissionTerms: String = "",
    val description: String = "",
    val acquiredDate: EpochDay? = null,
    val status: PropertyStatus = PropertyStatus.PROSPECT,
    val openToFurtherOffers: Boolean = false,
    val confidentialNotes: String = "",
    val isDraft: Boolean = false,
) {
    fun build(base: Property, unit: CurrencyUnit): FormResult<Property> {
        val e = FormErrors()
        val sale = DealType.SALE in dealTypes
        val price = e.take("askingPrice", Input.money(askingPrice, unit))
        val dep = e.take("deposit", Input.money(deposit, unit))
        val rent = e.take("monthlyRent", Input.money(monthlyRent, unit))
        val full = e.take("fullDepositAmount", Input.money(fullDepositAmount, unit))
        val market = e.take("estimatedMarketValue", Input.money(estimatedMarketValue, unit))
        val land = e.take("landArea", Input.decimal(landArea))
        val built = e.take("builtArea", Input.decimal(builtArea))
        val beds = e.take("bedrooms", Input.int(bedrooms, max = 50))
        val baths = e.take("bathrooms", Input.int(bathrooms, max = 50))
        val fl = e.take("floor", Input.signedInt(floor))
        val total = e.take("totalFloors", Input.int(totalFloors, max = 300))
        val units = e.take("unitsPerFloor", Input.int(unitsPerFloor, max = 100))
        val year = e.take("constructionYear", Input.int(constructionYear, max = 3000))
        val parking = e.take("parkingSpaces", Input.int(parkingSpaces, max = 100))
        return e.result {
            base.copy(
                reference = reference.trim().uppercase(), title = title.trim(), category = category, dealTypes = dealTypes,
                askingPrice = price.takeIf { sale }, deposit = dep.takeIf { DealType.RENT in dealTypes },
                monthlyRent = rent.takeIf { DealType.RENT in dealTypes },
                fullDepositAmount = full.takeIf { DealType.FULL_DEPOSIT in dealTypes },
                estimatedMarketValue = market, negotiable = negotiable,
                province = Input.text(province), city = Input.text(city), neighborhood = Input.text(neighborhood),
                address = Input.text(address), location = location, landArea = land, builtArea = built, bedrooms = beds,
                bathrooms = baths, floor = fl, totalFloors = total, unitsPerFloor = units, constructionYear = year,
                constructionYearCalendar = constructionYearCalendar, parkingSpaces = parking, storage = storage,
                elevator = elevator, heating = heating, cooling = cooling, amenities = amenities, facing = facing,
                condition = condition, furnished = furnished, documentStatus = documentStatus, ownerCustomerId = ownerCustomerId,
                assignedAgentId = assignedAgentId, commissionTerms = Input.text(commissionTerms),
                description = Input.text(description), acquiredDate = acquiredDate, status = status,
                openToFurtherOffers = openToFurtherOffers, confidentialNotes = Input.text(confidentialNotes), isDraft = isDraft,
            )
        }
    }

    companion object {
        fun from(p: Property, unit: CurrencyUnit, persianDigits: Boolean): PropertyForm {
            fun m(v: Money?) = Input.moneyText(v, unit, persianDigits)
            fun i(v: Int?) = Input.intText(v, persianDigits)
            fun d(v: Double?) = Input.decimalText(v, persianDigits)
            return PropertyForm(
                p.reference, p.title, p.category, p.dealTypes, m(p.askingPrice), m(p.deposit), m(p.monthlyRent),
                m(p.fullDepositAmount), m(p.estimatedMarketValue), p.negotiable, p.province.orEmpty(), p.city.orEmpty(),
                p.neighborhood.orEmpty(), p.address.orEmpty(), p.location, d(p.landArea), d(p.builtArea), i(p.bedrooms),
                i(p.bathrooms), i(p.floor), i(p.totalFloors), i(p.unitsPerFloor), i(p.constructionYear),
                p.constructionYearCalendar, i(p.parkingSpaces), p.storage, p.elevator, p.heating, p.cooling, p.amenities,
                p.facing, p.condition, p.furnished, p.documentStatus, p.ownerCustomerId, p.assignedAgentId,
                p.commissionTerms.orEmpty(), p.description.orEmpty(), p.acquiredDate, p.status, p.openToFurtherOffers,
                p.confidentialNotes.orEmpty(), p.isDraft,
            )
        }
    }
}

data class ActivityForm(
    val type: ActivityType = ActivityType.PHONE_CALL,
    val day: EpochDay,
    val time: String = "",
    val customerId: String? = null,
    val propertyId: String? = null,
    val outcome: ActivityOutcome? = null,
    val channel: ContactMethod? = null,
    val notes: String = "",
    val nextAction: String = "",
    val nextActionDate: EpochDay? = null,
    val viewingFeedback: ViewingFeedback? = null,
) {
    fun build(base: Activity, zone: ZoneId): FormResult<Activity> {
        val e = FormErrors()
        val minutes = e.take("occurredAt", Input.time(time))
        if (type == ActivityType.MESSENGER_MANUAL && channel == null) e.error("channel", "required")
        if (nextAction.isNotBlank() && nextActionDate == null) e.error("nextActionDate", "required")
        return e.result {
            base.copy(
                type = type, occurredAt = Input.instant(day, minutes ?: Input.minutesOfDay(base.occurredAt, zone), zone),
                customerId = customerId, propertyId = propertyId, outcome = outcome,
                channel = channel,
                notes = Input.text(notes), nextAction = Input.text(nextAction), nextActionDate = nextActionDate.takeIf { nextAction.isNotBlank() },
                viewingFeedback = viewingFeedback.takeIf { type == ActivityType.PROPERTY_VIEWING },
            )
        }
    }

    companion object {
        fun from(a: Activity, zone: ZoneId, persianDigits: Boolean) = ActivityForm(
            a.type, ir.melkcrm.core.time.AgencyTime.epochDayOf(a.occurredAt, zone),
            Input.timeText(Input.minutesOfDay(a.occurredAt, zone), persianDigits), a.customerId, a.propertyId, a.outcome,
            a.channel, a.notes.orEmpty(), a.nextAction.orEmpty(), a.nextActionDate, a.viewingFeedback,
        )
    }
}

/** Reminder choices offered for tasks: none, at a time on the due day. */
data class TaskForm(
    val title: String = "",
    val dueDate: EpochDay,
    val dueTime: String = "",
    val remind: Boolean = false,
    val assignedAgentId: String? = null,
    val customerId: String? = null,
    val propertyId: String? = null,
    val notes: String = "",
) {
    fun build(base: TaskItem, zone: ZoneId): FormResult<TaskItem> {
        val e = FormErrors()
        if (title.isBlank()) e.error("title", "required")
        val minutes = e.take("dueTimeMinutes", Input.time(dueTime))
        return e.result {
            base.copy(
                title = title.trim(), dueDate = dueDate, dueTimeMinutes = minutes, assignedAgentId = assignedAgentId,
                customerId = customerId, propertyId = propertyId, notes = Input.text(notes),
                // Without a time the reminder fires at 09:00 on the due day.
                reminderAt = if (remind) Input.instant(dueDate, minutes ?: 9 * 60, zone) else null,
            )
        }
    }

    companion object {
        fun from(t: TaskItem, persianDigits: Boolean) = TaskForm(
            t.title, t.dueDate, Input.timeText(t.dueTimeMinutes, persianDigits), t.reminderAt != null, t.assignedAgentId,
            t.customerId, t.propertyId, t.notes.orEmpty(),
        )
    }
}

data class AppointmentForm(
    val type: AppointmentType = AppointmentType.VIEWING,
    val title: String = "",
    val day: EpochDay,
    val time: String = "",
    val durationMinutes: String = "60",
    val customerIds: List<String> = emptyList(),
    val propertyId: String? = null,
    val location: String = "",
    val reminderMinutesBefore: Int? = 60,
    val agentId: String? = null,
) {
    fun build(base: Appointment, zone: ZoneId): FormResult<Appointment> {
        val e = FormErrors()
        if (title.isBlank()) e.error("title", "required")
        val minutes = e.take("startsAt", Input.time(time))
        if (minutes == null && "startsAt" !in e.map) e.error("startsAt", "required")
        val duration = e.take("endsAt", Input.int(durationMinutes, min = 0, max = 24 * 60))
        return e.result {
            val start = Input.instant(day, minutes!!, zone)
            base.copy(
                type = type, title = title.trim(), startsAt = start, endsAt = duration?.takeIf { it > 0 }?.let { start + it * 60_000L },
                customerIds = customerIds, propertyId = propertyId, location = Input.text(location),
                reminderMinutesBefore = reminderMinutesBefore, agentId = agentId,
            )
        }
    }

    companion object {
        fun from(a: Appointment, zone: ZoneId, persianDigits: Boolean) = AppointmentForm(
            a.type, a.title, ir.melkcrm.core.time.AgencyTime.epochDayOf(a.startsAt, zone),
            Input.timeText(Input.minutesOfDay(a.startsAt, zone), persianDigits),
            Input.intText(a.endsAt?.let { ((it - a.startsAt) / 60_000L).toInt() }, persianDigits),
            a.customerIds, a.propertyId, a.location.orEmpty(), a.reminderMinutesBefore, a.agentId,
        )
    }
}

data class OfferForm(
    val direction: OfferDirection = OfferDirection.BUYER_TO_SELLER,
    val unit: CurrencyUnit = CurrencyUnit.TOMAN,
    val price: String = "",
    val deposit: String = "",
    val monthlyRent: String = "",
    val expiresOn: EpochDay? = null,
    val conditions: String = "",
    val notes: String = "",
) {
    /** The offer expires at the end of [expiresOn] in the agency's time zone. */
    fun build(base: Offer, zone: ZoneId): FormResult<Offer> {
        val e = FormErrors()
        val p = e.take("offeredPrice", Input.money(price, unit))
        val d = e.take("offeredDeposit", Input.money(deposit, unit))
        val r = e.take("offeredMonthlyRent", Input.money(monthlyRent, unit))
        return e.result {
            base.copy(
                direction = direction, enteredUnit = unit,
                offeredPrice = p.takeIf { base.dealType == DealType.SALE },
                offeredDeposit = d.takeIf { base.dealType != DealType.SALE },
                offeredMonthlyRent = r.takeIf { base.dealType == DealType.RENT },
                expiresAt = expiresOn?.let { Input.instant(it + 1, 0, zone) - 1 },
                conditions = Input.text(conditions), notes = Input.text(notes),
            )
        }
    }

    companion object {
        fun from(o: Offer, zone: ZoneId, persianDigits: Boolean) = OfferForm(
            o.direction, o.enteredUnit, Input.moneyText(o.offeredPrice, o.enteredUnit, persianDigits),
            Input.moneyText(o.offeredDeposit, o.enteredUnit, persianDigits), Input.moneyText(o.offeredMonthlyRent, o.enteredUnit, persianDigits),
            o.expiresAt?.let { ir.melkcrm.core.time.AgencyTime.epochDayOf(it, zone) }, o.conditions.orEmpty(), o.notes.orEmpty(),
        )
    }
}

data class TransactionForm(
    val finalPrice: String = "",
    val finalDeposit: String = "",
    val finalMonthlyRent: String = "",
    val contractDate: EpochDay,
    val leaseMonths: String = "",
    val notes: String = "",
) {
    data class Values(val price: Money?, val deposit: Money?, val rent: Money?, val contractDate: EpochDay, val leaseMonths: Int?, val notes: String?)

    fun build(dealType: DealType, unit: CurrencyUnit): FormResult<Values> {
        val e = FormErrors()
        val p = e.take("finalPrice", Input.money(finalPrice, unit))
        val d = e.take("finalDeposit", Input.money(finalDeposit, unit))
        val r = e.take("finalMonthlyRent", Input.money(finalMonthlyRent, unit))
        val months = e.take("leaseMonths", Input.int(leaseMonths, min = 1, max = 600))
        return e.result {
            Values(
                p.takeIf { dealType == DealType.SALE }, d.takeIf { dealType != DealType.SALE }, r.takeIf { dealType == DealType.RENT },
                contractDate, months.takeIf { dealType != DealType.SALE }, Input.text(notes),
            )
        }
    }
}

data class CommissionRuleForm(
    val name: String = "",
    val dealType: DealType = DealType.SALE,
    val party: CommissionParty = CommissionParty.BUYER,
    val pricePercent: String = "",
    val depositPercent: String = "",
    val rentPercent: String = "",
    val fixed: String = "",
    val minimum: String = "",
    val maximum: String = "",
    val taxPercent: String = "",
    val active: Boolean = true,
) {
    fun build(base: CommissionRule, unit: CurrencyUnit): FormResult<CommissionRule> {
        val e = FormErrors()
        if (name.isBlank()) e.error("name", "required")
        val price = e.take("priceBasisPoints", Input.percent(pricePercent))
        val dep = e.take("depositBasisPoints", Input.percent(depositPercent))
        // Rent commission is entered as a percent of one month's rent (100 = one month).
        val rent = e.take("monthlyRentBasisPoints", Input.percent(rentPercent))
        val fx = e.take("fixedAmount", Input.money(fixed, unit))
        val min = e.take("minimum", Input.money(minimum, unit))
        val max = e.take("maximum", Input.money(maximum, unit))
        val tax = e.take("taxBasisPoints", Input.percent(taxPercent))
        if (min != null && max != null && min > max) e.error("minimum", "min_greater_than_max")
        return e.result {
            base.copy(
                name = name.trim(), dealType = dealType, party = party,
                priceBasisPoints = if (dealType == DealType.SALE) price ?: 0 else 0,
                depositBasisPoints = if (dealType == DealType.SALE) 0 else dep ?: 0,
                monthlyRentBasisPoints = if (dealType == DealType.RENT) rent ?: 0 else 0,
                fixedAmount = fx, minimum = min, maximum = max, taxBasisPoints = tax ?: 0, active = active,
            )
        }
    }

    companion object {
        fun from(r: CommissionRule, unit: CurrencyUnit, persianDigits: Boolean) = CommissionRuleForm(
            r.name, r.dealType, r.party, Input.percentText(r.priceBasisPoints.takeIf { it > 0 }, persianDigits),
            Input.percentText(r.depositBasisPoints.takeIf { it > 0 }, persianDigits),
            Input.percentText(r.monthlyRentBasisPoints.takeIf { it > 0 }, persianDigits),
            Input.moneyText(r.fixedAmount, unit, persianDigits), Input.moneyText(r.minimum, unit, persianDigits),
            Input.moneyText(r.maximum, unit, persianDigits), Input.percentText(r.taxBasisPoints.takeIf { it > 0 }, persianDigits), r.active,
        )
    }
}
