package ir.melkcrm.core.model

import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import kotlinx.serialization.Serializable

/**
 * Domain entities shared by the Android app and the backend.
 * Every synchronizable record has a stable UUID `id`, an `agencyId`, a server `version`
 * (0 = never synchronized) and `updatedAt`.
 *
 * Booleans and numbers are nullable on purpose: `null` means "not recorded", which the matching
 * engine treats as missing data, never as yes/no or zero.
 */

@Serializable
data class Agency(
    val id: String,
    val name: String,
    val city: String? = null,
    val createdAt: EpochMillis,
)

@Serializable
data class ConsentPreferences(
    val callsAllowed: Boolean = true,
    val smsAllowed: Boolean = false,
    val messengerAllowed: Boolean = false,
    val marketingAllowed: Boolean = false,
    val recordedAt: EpochMillis? = null,
)

@Serializable
data class Customer(
    val id: String,
    val agencyId: String,
    val fullName: String,
    val mobile: String? = null,
    val altPhone: String? = null,
    val email: String? = null,
    val roles: Set<CustomerRole> = emptySet(),
    val preferredContact: ContactMethod? = null,
    val province: String? = null,
    val city: String? = null,
    val neighborhood: String? = null,
    val address: String? = null,
    val source: CustomerSource? = null,
    val assignedAgentId: String? = null,
    /** A [CustomerStatus] name or a custom status code. */
    val status: String = CustomerStatus.NEW_LEAD.name,
    val priority: Priority = Priority.MEDIUM,
    val registeredAt: EpochMillis,
    val lastContactAt: EpochMillis? = null,
    val nextFollowUpDate: EpochDay? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val consent: ConsentPreferences = ConsentPreferences(),
    val isDraft: Boolean = false,
    val mergedIntoId: String? = null,
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
) {
    val isInactive: Boolean get() = status == CustomerStatus.INACTIVE.name || status == CustomerStatus.LOST.name
}

@Serializable
data class CustomStatus(
    val code: String,
    val labelFa: String,
    val labelEn: String,
    val countsAsInactive: Boolean = false,
)

@Serializable
data class GeoPoint(val lat: Double, val lng: Double) {
    init {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "Invalid coordinates $lat,$lng" }
    }
}

@Serializable
data class Location(
    val point: GeoPoint,
    val source: LocationSource,
    /** Estimated accuracy radius in meters, when known. */
    val accuracyMeters: Double? = null,
)

/** A buyer's saved map area: a circle, or a polygon when the map provider supports drawing one. */
@Serializable
data class GeoArea(
    val label: String? = null,
    val center: GeoPoint? = null,
    val radiusKm: Double? = null,
    val polygon: List<GeoPoint> = emptyList(),
)

@Serializable
data class BuyerRequirement(
    val id: String,
    val agencyId: String,
    val customerId: String,
    val title: String? = null,
    val dealType: DealType,
    val categories: Set<PropertyCategory> = emptySet(),
    // Location
    val provinces: List<String> = emptyList(),
    val cities: List<String> = emptyList(),
    val neighborhoods: List<String> = emptyList(),
    val alternativeNeighborhoods: List<String> = emptyList(),
    val excludedCities: List<String> = emptyList(),
    val excludedNeighborhoods: List<String> = emptyList(),
    val mapAreas: List<GeoArea> = emptyList(),
    /** When true, a property outside the preferred cities/neighborhoods/areas is excluded. */
    val locationStrict: Boolean = false,
    // Size and layout
    val minArea: Double? = null,
    val maxArea: Double? = null,
    val areaStrict: Boolean = false,
    val minBedrooms: Int? = null,
    val maxBedrooms: Int? = null,
    val bedroomsStrict: Boolean = false,
    val minFloor: Int? = null,
    val maxFloor: Int? = null,
    val maxBuildingAgeYears: Int? = null,
    val buildingAgeLevel: RequirementLevel = RequirementLevel.PREFERRED,
    // Features
    val parking: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val elevator: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val storage: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val otherAmenities: Map<Amenity, RequirementLevel> = emptyMap(),
    val newBuild: RequirementLevel = RequirementLevel.NOT_IMPORTANT,
    val acceptableConditions: Set<PropertyCondition> = emptySet(),
    val acceptableDocuments: Set<DocumentStatus> = emptySet(),
    val documentLevel: RequirementLevel = RequirementLevel.PREFERRED,
    val preferredFacing: Set<Facing> = emptySet(),
    val furnished: FurnishedStatus? = null,
    // Budget (sale)
    val minBudget: Money? = null,
    val maxBudget: Money? = null,
    val preferredBudget: Money? = null,
    val budgetFlexible: Boolean? = null,
    /** Extra amount above [maxBudget] the buyer explicitly said they could stretch to. */
    val maxStretch: Money? = null,
    // Budget (rent / full deposit)
    val availableDeposit: Money? = null,
    val maxDeposit: Money? = null,
    val maxMonthlyRent: Money? = null,
    val financing: FinancingType = FinancingType.UNKNOWN,
    val cashBuyer: Boolean? = null,
    // Timeline and motivation
    val urgency: Urgency = Urgency.UNKNOWN,
    val expectedDate: EpochDay? = null,
    val reason: PurchaseReason? = null,
    val needsToSellFirst: Boolean? = null,
    val preferredViewingTimes: String? = null,
    val negotiationFlexibility: FlexibilityLevel = FlexibilityLevel.UNKNOWN,
    val leadQuality: LeadQuality = LeadQuality.UNKNOWN,
    val status: RequirementStatus = RequirementStatus.ACTIVE,
    val notes: String? = null,
    val createdAt: EpochMillis,
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
)

@Serializable
data class DocumentChecklist(
    val deedCopy: Boolean = false,
    val idCards: Boolean = false,
    val endOfWorkCertificate: Boolean = false,
    val municipalityClearance: Boolean = false,
    val taxClearance: Boolean = false,
    val notes: String? = null,
)

/** Seller instructions for one property. Confidential fields live in [SellerConfidential]. */
@Serializable
data class SellerProfile(
    val id: String,
    val agencyId: String,
    val customerId: String,
    val propertyId: String,
    val assignedAgentId: String? = null,
    val status: SellerStatus = SellerStatus.PROSPECT,
    val saleDeadline: EpochDay? = null,
    val motivation: String? = null,
    val priceFlexibility: FlexibilityLevel = FlexibilityLevel.UNKNOWN,
    val preferredContact: ContactMethod? = null,
    val negotiationConditions: String? = null,
    val commissionAgreement: String? = null,
    val commissionRuleId: String? = null,
    val exclusive: Boolean? = null,
    val exclusiveUntil: EpochDay? = null,
    val documents: DocumentChecklist = DocumentChecklist(),
    val followUpIntervalDays: Int? = null,
    val instructions: String? = null,
    val confidential: SellerConfidential = SellerConfidential(),
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
)

/** Never shown to buyers, never included in outreach, only visible with VIEW_CONFIDENTIAL permission. */
@Serializable
data class SellerConfidential(
    val minAcceptablePrice: Money? = null,
    val minAcceptableDeposit: Money? = null,
    val minAcceptableMonthlyRent: Money? = null,
    val notes: String? = null,
)

@Serializable
data class Property(
    val id: String,
    val agencyId: String,
    /** Internal reference, e.g. IR-000123. */
    val reference: String,
    val title: String,
    val category: PropertyCategory,
    val dealTypes: Set<DealType>,
    val askingPrice: Money? = null,
    val deposit: Money? = null,
    val monthlyRent: Money? = null,
    val fullDepositAmount: Money? = null,
    val estimatedMarketValue: Money? = null,
    val negotiable: Boolean? = null,
    val province: String? = null,
    val city: String? = null,
    val neighborhood: String? = null,
    val address: String? = null,
    val location: Location? = null,
    val landArea: Double? = null,
    val builtArea: Double? = null,
    val bedrooms: Int? = null,
    val bathrooms: Int? = null,
    val floor: Int? = null,
    val totalFloors: Int? = null,
    val unitsPerFloor: Int? = null,
    val constructionYear: Int? = null,
    val constructionYearCalendar: CalendarSystem = CalendarSystem.JALALI,
    val parkingSpaces: Int? = null,
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
    val commissionTerms: String? = null,
    val description: String? = null,
    val acquiredDate: EpochDay? = null,
    val status: PropertyStatus = PropertyStatus.PROSPECT,
    /** Explicitly allows matching while RESERVED / UNDER_NEGOTIATION (backup buyers). */
    val openToFurtherOffers: Boolean = false,
    val confidentialNotes: String? = null,
    /** Server ids of uploaded photos (see PropertyImage for local files not yet uploaded). */
    val imageIds: List<String> = emptyList(),
    val isDraft: Boolean = false,
    val createdAt: EpochMillis,
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
) {
    /** The usable area for comparison: built-up area, or land area for land. */
    val effectiveArea: Double? get() = if (category == PropertyCategory.LAND) landArea ?: builtArea else builtArea ?: landArea

    fun hasFeature(amenity: Amenity): Boolean? = when (amenity) {
        Amenity.PARKING -> parkingSpaces?.let { it > 0 }
        Amenity.ELEVATOR -> elevator
        Amenity.STORAGE -> storage
        else -> if (amenity in amenities) true else null
    }
}

@Serializable
data class PropertyImage(
    val id: String,
    val propertyId: String,
    /** Local content URI on device, or a server storage key after upload. */
    val uri: String,
    val remoteKey: String? = null,
    val caption: String? = null,
    val sortOrder: Int = 0,
    val createdAt: EpochMillis,
)

@Serializable
data class PropertyContact(
    val propertyId: String,
    val customerId: String,
    val role: PropertyContactRole,
    val note: String? = null,
)

@Serializable
data class Activity(
    val id: String,
    val agencyId: String,
    val type: ActivityType,
    val occurredAt: EpochMillis,
    val agentId: String? = null,
    val customerId: String? = null,
    val propertyId: String? = null,
    val requirementId: String? = null,
    val outcome: ActivityOutcome? = null,
    /** For messenger activities: which channel (recorded manually). */
    val channel: ContactMethod? = null,
    val notes: String? = null,
    val nextAction: String? = null,
    val nextActionDate: EpochDay? = null,
    val viewingFeedback: ViewingFeedback? = null,
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
)

@Serializable
data class TaskItem(
    val id: String,
    val agencyId: String,
    val title: String,
    val dueDate: EpochDay,
    val dueTimeMinutes: Int? = null,
    val assignedAgentId: String? = null,
    val customerId: String? = null,
    val propertyId: String? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val reminderAt: EpochMillis? = null,
    val completedAt: EpochMillis? = null,
    val notes: String? = null,
    val createdAt: EpochMillis,
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
) {
    fun isOverdue(today: EpochDay): Boolean = status == TaskStatus.OPEN && dueDate < today
    fun isDueToday(today: EpochDay): Boolean = status == TaskStatus.OPEN && dueDate == today
}

@Serializable
data class Appointment(
    val id: String,
    val agencyId: String,
    val type: AppointmentType,
    val title: String,
    val startsAt: EpochMillis,
    val endsAt: EpochMillis? = null,
    val agentId: String? = null,
    val customerIds: List<String> = emptyList(),
    val propertyId: String? = null,
    val location: String? = null,
    val reminderMinutesBefore: Int? = 60,
    val status: AppointmentStatus = AppointmentStatus.SCHEDULED,
    val feedback: ViewingFeedback? = null,
    val outcomeNotes: String? = null,
    val version: Long = 0,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
)

@Serializable
data class Note(
    val id: String,
    val agencyId: String,
    val customerId: String? = null,
    val propertyId: String? = null,
    val text: String,
    val authorId: String? = null,
    val createdAt: EpochMillis,
)
