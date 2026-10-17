package ir.melkcrm.core.model

import kotlinx.serialization.Serializable

// Enum names are persisted (Room, backend payloads, CSV). Never rename an entry; add new ones instead.

@Serializable enum class CustomerRole { BUYER, SELLER, LANDLORD, TENANT, INVESTOR }

@Serializable enum class ContactMethod { PHONE_CALL, SMS, WHATSAPP, TELEGRAM, EITAA, BALE, EMAIL, IN_PERSON }

@Serializable enum class CustomerSource { REFERRAL, WALK_IN, TELEPHONE, SOCIAL_MEDIA, ADVERTISEMENT, WEBSITE, DIVAR, OTHER }

/** Built-in customer statuses. Agencies may add custom statuses (see [CustomStatus]); records store the code string. */
@Serializable enum class CustomerStatus { NEW_LEAD, CONTACTED, QUALIFIED, ACTIVE, NEGOTIATING, CONVERTED, INACTIVE, LOST }

@Serializable enum class Priority { LOW, MEDIUM, HIGH, URGENT }

/** What a property is offered for / what a buyer requirement asks for. */
@Serializable enum class DealType {
    /** خرید و فروش */
    SALE,
    /** رهن و اجاره: deposit plus monthly rent */
    RENT,
    /** رهن کامل: full deposit, no monthly rent */
    FULL_DEPOSIT,
}

@Serializable enum class PropertyCategory { APARTMENT, VILLA, HOUSE, LAND, COMMERCIAL, OFFICE, WAREHOUSE, OTHER }

@Serializable enum class PropertyStatus {
    PROSPECT, UNDER_ASSESSMENT, PREPARING, AVAILABLE, RESERVED, UNDER_NEGOTIATION, SOLD, RENTED, WITHDRAWN, ARCHIVED;

    val isClosed: Boolean get() = this == SOLD || this == RENTED || this == WITHDRAWN || this == ARCHIVED
}

@Serializable enum class PropertyCondition { NEW, RENOVATED, GOOD, NEEDS_RENOVATION, UNDER_CONSTRUCTION, OLD_DEMOLITION }

@Serializable enum class FurnishedStatus { UNFURNISHED, SEMI_FURNISHED, FURNISHED }

/** Ownership document types common in Iran. */
@Serializable enum class DocumentStatus {
    /** سند تک‌برگ */
    SINGLE_SHEET_DEED,
    /** سند منگوله‌دار / دفترچه‌ای */
    BOOKLET_DEED,
    /** قولنامه‌ای */
    PROMISSORY_CONTRACT,
    /** وقفی */
    ENDOWMENT,
    /** تعاونی */
    COOPERATIVE,
    /** در حال صدور */
    DEED_PENDING,
    OTHER,
}

@Serializable enum class Facing { NORTH, SOUTH, EAST, WEST }

@Serializable enum class Amenity {
    PARKING, ELEVATOR, STORAGE, BALCONY, ROOF_GARDEN, POOL, SAUNA, JACUZZI, GYM, LOBBY, CONCIERGE,
    SECURITY_GUARD, CENTRAL_HEATING, PACKAGE_HEATING, FLOOR_HEATING, AIR_CONDITIONING, WATER_COOLER,
    DUCT_SPLIT, BACKUP_GENERATOR, CCTV, YARD, GAS, THREE_PHASE_POWER, WELL_WATER,
}

@Serializable enum class HeatingType { NONE, CENTRAL, PACKAGE, FLOOR, HEATER, OTHER }
@Serializable enum class CoolingType { NONE, WATER_COOLER, SPLIT, DUCT_SPLIT, CHILLER, OTHER }

/** How strictly a buyer needs a feature. Only REQUIRED can exclude a property. */
@Serializable enum class RequirementLevel { REQUIRED, PREFERRED, NOT_IMPORTANT }

@Serializable enum class Urgency(val days: Int?) {
    IMMEDIATE(14), WITHIN_1_MONTH(30), WITHIN_3_MONTHS(90), WITHIN_6_MONTHS(180), FLEXIBLE(365), UNKNOWN(null)
}

@Serializable enum class FlexibilityLevel { NONE, LOW, MEDIUM, HIGH, UNKNOWN }

@Serializable enum class LeadQuality { HOT, WARM, COLD, UNKNOWN }

@Serializable enum class FinancingType { CASH, INSTALLMENTS, BANK_LOAN, PROPERTY_SWAP, MIXED, UNKNOWN }

@Serializable enum class PurchaseReason { RESIDENCE, INVESTMENT, UPGRADE, DOWNSIZE, BUSINESS, MARRIAGE, RELOCATION, OTHER }

@Serializable enum class RequirementStatus { ACTIVE, PAUSED, FULFILLED, CANCELLED }

@Serializable enum class SellerStatus {
    PROSPECT, VALUATION_PENDING, LISTING_PREPARATION, ACTIVE_FOR_SALE, OFFERS_RECEIVED, NEGOTIATING, SOLD, WITHDRAWN
}

@Serializable enum class LocationSource { MANUAL_PIN, MANUAL_COORDINATES, GEOCODED, DEVICE_GPS, SAMPLE_APPROXIMATE, UNKNOWN }

@Serializable enum class PropertyContactRole { OWNER, CO_OWNER, LANDLORD, REPRESENTATIVE, TENANT, INTERESTED_BUYER, OTHER }

@Serializable enum class ActivityType {
    PHONE_CALL, MEETING, PROPERTY_VIEWING, MESSENGER_MANUAL, SMS, IN_APP_MESSAGE, EMAIL, OFFER, NEGOTIATION,
    FOLLOW_UP, DOCUMENT_COLLECTION, NOTE,
}

@Serializable enum class ActivityOutcome { SUCCESSFUL, NO_ANSWER, CALL_BACK, INTERESTED, NOT_INTERESTED, NEEDS_INFO, COMPLETED, CANCELLED, OTHER }

@Serializable enum class TaskStatus { OPEN, DONE, CANCELLED }

@Serializable enum class AppointmentType { MEETING, VIEWING, SIGNING, VALUATION, OTHER }

@Serializable enum class AppointmentStatus { SCHEDULED, COMPLETED, CANCELLED, NO_SHOW }

@Serializable enum class ViewingFeedback { VERY_INTERESTED, INTERESTED, NEUTRAL, NOT_INTERESTED }
