package ir.melkcrm.core.i18n

import ir.melkcrm.core.matching.ConfidenceLevel
import ir.melkcrm.core.matching.MatchClass
import ir.melkcrm.core.matching.MatchRejectionReason
import ir.melkcrm.core.matching.NegotiationSide
import ir.melkcrm.core.matching.MatchComponent
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.matching.MatchingEngine
import ir.melkcrm.core.matching.MissingField
import ir.melkcrm.core.matching.RecommendedAction
import ir.melkcrm.core.model.*
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.AppLanguage

/**
 * Persian and English labels for domain enums. UI chrome strings live in Android resources;
 * domain vocabulary lives here so the app, backend exports and tests use identical terms.
 */
object Labels {
    private val fa = mutableMapOf<String, String>()
    private val en = mutableMapOf<String, String>()

    private fun enumKey(e: Enum<*>): String {
        val cls = e.javaClass
        val enumClass = if (cls.isEnum) cls else cls.superclass
        return enumClass.simpleName + "." + e.name
    }

    private fun put(e: Enum<*>, faText: String, enText: String) {
        val k = enumKey(e)
        fa[k] = faText
        en[k] = enText
    }

    private fun putKey(key: String, faText: String, enText: String) {
        fa[key] = faText
        en[key] = enText
    }

    fun of(e: Enum<*>, lang: AppLanguage): String {
        val k = enumKey(e)
        return (if (lang == AppLanguage.PERSIAN) fa[k] else en[k]) ?: e.name.lowercase().replace('_', ' ')
    }

    /** Label for a feature key used in match reasons: an [Amenity] name or a MatchingEngine.FEATURE_* key. */
    fun feature(key: String, lang: AppLanguage): String {
        val amenity = Amenity.entries.firstOrNull { it.name == key }
        if (amenity != null) return of(amenity, lang)
        return (if (lang == AppLanguage.PERSIAN) fa["feature.$key"] else en["feature.$key"]) ?: key
    }

    fun customerStatus(code: String, lang: AppLanguage, custom: List<CustomStatus> = emptyList()): String {
        CustomerStatus.entries.firstOrNull { it.name == code }?.let { return of(it, lang) }
        val c = custom.firstOrNull { it.code == code } ?: return code
        return if (lang == AppLanguage.PERSIAN) c.labelFa else c.labelEn
    }

    init {
        put(CustomerRole.BUYER, "خریدار", "Buyer")
        put(CustomerRole.SELLER, "فروشنده", "Seller")
        put(CustomerRole.LANDLORD, "موجر", "Landlord")
        put(CustomerRole.TENANT, "مستأجر", "Tenant")
        put(CustomerRole.INVESTOR, "سرمایه‌گذار", "Investor")

        put(ContactMethod.PHONE_CALL, "تماس تلفنی", "Phone call")
        put(ContactMethod.SMS, "پیامک", "SMS")
        put(ContactMethod.WHATSAPP, "واتساپ", "WhatsApp")
        put(ContactMethod.TELEGRAM, "تلگرام", "Telegram")
        put(ContactMethod.EITAA, "ایتا", "Eitaa")
        put(ContactMethod.BALE, "بله", "Bale")
        put(ContactMethod.EMAIL, "ایمیل", "Email")
        put(ContactMethod.IN_PERSON, "حضوری", "In person")

        put(CustomerSource.REFERRAL, "معرفی", "Referral")
        put(CustomerSource.WALK_IN, "مراجعه حضوری", "Walk-in")
        put(CustomerSource.TELEPHONE, "تماس تلفنی", "Telephone")
        put(CustomerSource.SOCIAL_MEDIA, "شبکه‌های اجتماعی", "Social media")
        put(CustomerSource.ADVERTISEMENT, "تبلیغات", "Advertisement")
        put(CustomerSource.WEBSITE, "وب‌سایت", "Website")
        put(CustomerSource.DIVAR, "دیوار", "Divar")
        put(CustomerSource.OTHER, "سایر", "Other")

        put(CustomerStatus.NEW_LEAD, "سرنخ جدید", "New lead")
        put(CustomerStatus.CONTACTED, "تماس گرفته شده", "Contacted")
        put(CustomerStatus.QUALIFIED, "واجد شرایط", "Qualified")
        put(CustomerStatus.ACTIVE, "فعال", "Active")
        put(CustomerStatus.NEGOTIATING, "در حال مذاکره", "Negotiating")
        put(CustomerStatus.CONVERTED, "به معامله رسیده", "Converted")
        put(CustomerStatus.INACTIVE, "غیرفعال", "Inactive")
        put(CustomerStatus.LOST, "از دست رفته", "Lost")

        put(Priority.LOW, "کم", "Low")
        put(Priority.MEDIUM, "متوسط", "Medium")
        put(Priority.HIGH, "زیاد", "High")
        put(Priority.URGENT, "فوری", "Urgent")

        put(DealType.SALE, "خرید و فروش", "Sale")
        put(DealType.RENT, "رهن و اجاره", "Rent (deposit + monthly)")
        put(DealType.FULL_DEPOSIT, "رهن کامل", "Full deposit (rahn)")

        put(PropertyCategory.APARTMENT, "آپارتمان", "Apartment")
        put(PropertyCategory.VILLA, "ویلا", "Villa")
        put(PropertyCategory.HOUSE, "خانه ویلایی", "House")
        put(PropertyCategory.LAND, "زمین", "Land")
        put(PropertyCategory.COMMERCIAL, "تجاری / مغازه", "Commercial unit")
        put(PropertyCategory.OFFICE, "اداری", "Office")
        put(PropertyCategory.WAREHOUSE, "انبار / سوله", "Warehouse")
        put(PropertyCategory.OTHER, "سایر", "Other")

        put(PropertyStatus.PROSPECT, "در حال جذب", "Prospect")
        put(PropertyStatus.UNDER_ASSESSMENT, "در حال ارزیابی", "Under assessment")
        put(PropertyStatus.PREPARING, "در حال آماده‌سازی", "Preparing")
        put(PropertyStatus.AVAILABLE, "موجود", "Available")
        put(PropertyStatus.RESERVED, "رزرو شده", "Reserved")
        put(PropertyStatus.UNDER_NEGOTIATION, "در حال مذاکره", "Under negotiation")
        put(PropertyStatus.SOLD, "فروخته شده", "Sold")
        put(PropertyStatus.RENTED, "اجاره داده شده", "Rented")
        put(PropertyStatus.WITHDRAWN, "منصرف شده", "Withdrawn")
        put(PropertyStatus.ARCHIVED, "بایگانی", "Archived")

        put(PropertyCondition.NEW, "نوساز", "New")
        put(PropertyCondition.RENOVATED, "بازسازی شده", "Renovated")
        put(PropertyCondition.GOOD, "سالم", "Good")
        put(PropertyCondition.NEEDS_RENOVATION, "نیاز به بازسازی", "Needs renovation")
        put(PropertyCondition.UNDER_CONSTRUCTION, "در حال ساخت", "Under construction")
        put(PropertyCondition.OLD_DEMOLITION, "کلنگی", "Teardown")

        put(FurnishedStatus.UNFURNISHED, "بدون مبلمان", "Unfurnished")
        put(FurnishedStatus.SEMI_FURNISHED, "نیمه مبله", "Semi-furnished")
        put(FurnishedStatus.FURNISHED, "مبله", "Furnished")

        put(DocumentStatus.SINGLE_SHEET_DEED, "سند تک‌برگ", "Single-sheet deed")
        put(DocumentStatus.BOOKLET_DEED, "سند دفترچه‌ای", "Booklet deed")
        put(DocumentStatus.PROMISSORY_CONTRACT, "قولنامه‌ای", "Promissory contract")
        put(DocumentStatus.ENDOWMENT, "وقفی", "Endowment (vaqfi)")
        put(DocumentStatus.COOPERATIVE, "تعاونی", "Cooperative")
        put(DocumentStatus.DEED_PENDING, "در حال صدور سند", "Deed pending")
        put(DocumentStatus.OTHER, "سایر", "Other")

        put(Facing.NORTH, "شمالی", "North-facing")
        put(Facing.SOUTH, "جنوبی", "South-facing")
        put(Facing.EAST, "شرقی", "East-facing")
        put(Facing.WEST, "غربی", "West-facing")

        put(Amenity.PARKING, "پارکینگ", "Parking")
        put(Amenity.ELEVATOR, "آسانسور", "Elevator")
        put(Amenity.STORAGE, "انباری", "Storage room")
        put(Amenity.BALCONY, "بالکن", "Balcony")
        put(Amenity.ROOF_GARDEN, "روف گاردن", "Roof garden")
        put(Amenity.POOL, "استخر", "Pool")
        put(Amenity.SAUNA, "سونا", "Sauna")
        put(Amenity.JACUZZI, "جکوزی", "Jacuzzi")
        put(Amenity.GYM, "سالن ورزش", "Gym")
        put(Amenity.LOBBY, "لابی", "Lobby")
        put(Amenity.CONCIERGE, "سرایدار", "Caretaker")
        put(Amenity.SECURITY_GUARD, "نگهبانی", "Security guard")
        put(Amenity.CENTRAL_HEATING, "موتورخانه مرکزی", "Central heating")
        put(Amenity.PACKAGE_HEATING, "پکیج", "Combi boiler")
        put(Amenity.FLOOR_HEATING, "گرمایش از کف", "Floor heating")
        put(Amenity.AIR_CONDITIONING, "اسپلیت", "Air conditioning")
        put(Amenity.WATER_COOLER, "کولر آبی", "Evaporative cooler")
        put(Amenity.DUCT_SPLIT, "داکت اسپلیت", "Duct split")
        put(Amenity.BACKUP_GENERATOR, "برق اضطراری", "Backup generator")
        put(Amenity.CCTV, "دوربین مداربسته", "CCTV")
        put(Amenity.YARD, "حیاط", "Yard")
        put(Amenity.GAS, "گاز", "Gas")
        put(Amenity.THREE_PHASE_POWER, "برق سه‌فاز", "Three-phase power")
        put(Amenity.WELL_WATER, "چاه آب", "Water well")

        putKey("feature.${MatchingEngine.FEATURE_NEW_BUILD}", "نوساز", "New build")
        putKey("feature.${MatchingEngine.FEATURE_BUILDING_AGE}", "سن بنا", "Building age")
        putKey("feature.${MatchingEngine.FEATURE_DOCUMENT}", "نوع سند", "Ownership document")
        putKey("feature.${MatchingEngine.FEATURE_CONDITION}", "وضعیت ملک", "Condition")
        putKey("feature.${MatchingEngine.FEATURE_FACING}", "جهت ساختمان", "Facing")
        putKey("feature.${MatchingEngine.FEATURE_FURNISHED}", "مبلمان", "Furnishing")

        put(RequirementLevel.REQUIRED, "الزامی", "Required")
        put(RequirementLevel.PREFERRED, "ترجیحی", "Preferred")
        put(RequirementLevel.NOT_IMPORTANT, "مهم نیست", "Not important")

        put(Urgency.IMMEDIATE, "فوری", "Immediate")
        put(Urgency.WITHIN_1_MONTH, "تا یک ماه", "Within 1 month")
        put(Urgency.WITHIN_3_MONTHS, "تا سه ماه", "Within 3 months")
        put(Urgency.WITHIN_6_MONTHS, "تا شش ماه", "Within 6 months")
        put(Urgency.FLEXIBLE, "عجله ندارد", "Flexible")
        put(Urgency.UNKNOWN, "نامشخص", "Unknown")

        put(FlexibilityLevel.NONE, "بدون انعطاف", "None")
        put(FlexibilityLevel.LOW, "کم", "Low")
        put(FlexibilityLevel.MEDIUM, "متوسط", "Medium")
        put(FlexibilityLevel.HIGH, "زیاد", "High")
        put(FlexibilityLevel.UNKNOWN, "نامشخص", "Unknown")

        put(LeadQuality.HOT, "داغ", "Hot")
        put(LeadQuality.WARM, "گرم", "Warm")
        put(LeadQuality.COLD, "سرد", "Cold")
        put(LeadQuality.UNKNOWN, "نامشخص", "Unknown")

        put(FinancingType.CASH, "نقدی", "Cash")
        put(FinancingType.INSTALLMENTS, "اقساطی", "Installments")
        put(FinancingType.BANK_LOAN, "وام بانکی", "Bank loan")
        put(FinancingType.PROPERTY_SWAP, "معاوضه", "Property swap")
        put(FinancingType.MIXED, "ترکیبی", "Mixed")
        put(FinancingType.UNKNOWN, "نامشخص", "Unknown")

        put(PurchaseReason.RESIDENCE, "سکونت", "Residence")
        put(PurchaseReason.INVESTMENT, "سرمایه‌گذاری", "Investment")
        put(PurchaseReason.UPGRADE, "تبدیل به احسن", "Upgrade")
        put(PurchaseReason.DOWNSIZE, "کوچک‌تر کردن", "Downsize")
        put(PurchaseReason.BUSINESS, "کسب و کار", "Business")
        put(PurchaseReason.MARRIAGE, "ازدواج", "Marriage")
        put(PurchaseReason.RELOCATION, "جابجایی", "Relocation")
        put(PurchaseReason.OTHER, "سایر", "Other")

        put(RequirementStatus.ACTIVE, "فعال", "Active")
        put(RequirementStatus.PAUSED, "متوقف", "Paused")
        put(RequirementStatus.FULFILLED, "انجام شده", "Fulfilled")
        put(RequirementStatus.CANCELLED, "لغو شده", "Cancelled")

        put(SellerStatus.PROSPECT, "در حال جذب", "Prospect")
        put(SellerStatus.VALUATION_PENDING, "در انتظار قیمت‌گذاری", "Valuation pending")
        put(SellerStatus.LISTING_PREPARATION, "آماده‌سازی فایل", "Listing preparation")
        put(SellerStatus.ACTIVE_FOR_SALE, "فعال برای فروش", "Active for sale")
        put(SellerStatus.OFFERS_RECEIVED, "پیشنهاد دریافت شده", "Offers received")
        put(SellerStatus.NEGOTIATING, "در حال مذاکره", "Negotiating")
        put(SellerStatus.SOLD, "فروخته شده", "Sold")
        put(SellerStatus.WITHDRAWN, "منصرف شده", "Withdrawn")

        put(ActivityType.PHONE_CALL, "تماس تلفنی", "Phone call")
        put(ActivityType.MEETING, "جلسه", "Meeting")
        put(ActivityType.PROPERTY_VIEWING, "بازدید ملک", "Property viewing")
        put(ActivityType.MESSENGER_MANUAL, "پیام‌رسان (ثبت دستی)", "Messenger (recorded manually)")
        put(ActivityType.SMS, "پیامک", "SMS")
        put(ActivityType.IN_APP_MESSAGE, "پیام داخلی", "In-app message")
        put(ActivityType.EMAIL, "ایمیل", "Email")
        put(ActivityType.OFFER, "پیشنهاد قیمت", "Offer")
        put(ActivityType.NEGOTIATION, "مذاکره", "Negotiation")
        put(ActivityType.FOLLOW_UP, "پیگیری", "Follow-up")
        put(ActivityType.DOCUMENT_COLLECTION, "جمع‌آوری مدارک", "Document collection")
        put(ActivityType.NOTE, "یادداشت", "Note")

        put(ActivityOutcome.SUCCESSFUL, "موفق", "Successful")
        put(ActivityOutcome.NO_ANSWER, "پاسخ نداد", "No answer")
        put(ActivityOutcome.CALL_BACK, "تماس مجدد", "Call back")
        put(ActivityOutcome.INTERESTED, "علاقه‌مند", "Interested")
        put(ActivityOutcome.NOT_INTERESTED, "علاقه‌مند نیست", "Not interested")
        put(ActivityOutcome.NEEDS_INFO, "نیاز به اطلاعات", "Needs information")
        put(ActivityOutcome.COMPLETED, "انجام شد", "Completed")
        put(ActivityOutcome.CANCELLED, "لغو شد", "Cancelled")
        put(ActivityOutcome.OTHER, "سایر", "Other")

        put(AppointmentType.MEETING, "جلسه", "Meeting")
        put(AppointmentType.VIEWING, "بازدید", "Viewing")
        put(AppointmentType.SIGNING, "قرارداد", "Contract signing")
        put(AppointmentType.VALUATION, "کارشناسی", "Valuation")
        put(AppointmentType.OTHER, "سایر", "Other")

        put(AppointmentStatus.SCHEDULED, "برنامه‌ریزی شده", "Scheduled")
        put(AppointmentStatus.COMPLETED, "انجام شده", "Completed")
        put(AppointmentStatus.CANCELLED, "لغو شده", "Cancelled")
        put(AppointmentStatus.NO_SHOW, "حاضر نشد", "No-show")

        put(ViewingFeedback.VERY_INTERESTED, "خیلی علاقه‌مند", "Very interested")
        put(ViewingFeedback.INTERESTED, "علاقه‌مند", "Interested")
        put(ViewingFeedback.NEUTRAL, "بی‌نظر", "Neutral")
        put(ViewingFeedback.NOT_INTERESTED, "علاقه‌مند نیست", "Not interested")

        put(DealStage.LEAD_RECEIVED, "دریافت سرنخ", "Lead received")
        put(DealStage.CUSTOMER_QUALIFIED, "تأیید مشتری", "Customer qualified")
        put(DealStage.PROPERTY_MATCHED, "تطبیق ملک", "Property matched")
        put(DealStage.BUYER_CONTACTED, "تماس با خریدار", "Buyer contacted")
        put(DealStage.VIEWING_SCHEDULED, "بازدید برنامه‌ریزی شد", "Viewing scheduled")
        put(DealStage.VIEWING_COMPLETED, "بازدید انجام شد", "Viewing completed")
        put(DealStage.OFFER_SUBMITTED, "ثبت پیشنهاد", "Offer submitted")
        put(DealStage.COUNTEROFFER, "پیشنهاد متقابل", "Counteroffer")
        put(DealStage.NEGOTIATION, "مذاکره", "Negotiation")
        put(DealStage.AGREEMENT_REACHED, "توافق", "Agreement reached")
        put(DealStage.TRANSACTION_COMPLETED, "معامله انجام شد", "Transaction completed")
        put(DealStage.TRANSACTION_CANCELLED, "معامله لغو شد", "Transaction cancelled")

        put(OfferStatus.DRAFT, "پیش‌نویس", "Draft")
        put(OfferStatus.SUBMITTED, "ارسال شده", "Submitted")
        put(OfferStatus.COUNTERED, "پیشنهاد متقابل داده شد", "Countered")
        put(OfferStatus.ACCEPTED, "پذیرفته شد", "Accepted")
        put(OfferStatus.REJECTED, "رد شد", "Rejected")
        put(OfferStatus.WITHDRAWN, "پس گرفته شد", "Withdrawn")
        put(OfferStatus.EXPIRED, "منقضی شد", "Expired")

        put(CommissionStatus.ESTIMATED, "برآوردی", "Estimated")
        put(CommissionStatus.CONFIRMED, "قطعی", "Confirmed")
        put(CommissionStatus.PARTIALLY_RECEIVED, "بخشی دریافت شد", "Partially received")
        put(CommissionStatus.RECEIVED, "دریافت شد", "Received")
        put(CommissionStatus.CANCELLED, "لغو شد", "Cancelled")

        put(UserRole.OWNER_ADMIN, "مدیر آژانس", "Agency owner / admin")
        put(UserRole.MANAGER, "سرپرست", "Manager")
        put(UserRole.AGENT, "مشاور", "Agent")

        put(EntityType.CUSTOMER, "مشتری", "Customer")
        put(EntityType.BUYER_REQUIREMENT, "درخواست خریدار", "Buyer requirement")
        put(EntityType.SELLER_PROFILE, "پرونده فروشنده", "Seller profile")
        put(EntityType.PROPERTY, "ملک", "Property")
        put(EntityType.PROPERTY_CONTACT, "طرف تماس ملک", "Property contact")
        put(EntityType.ACTIVITY, "فعالیت", "Activity")
        put(EntityType.TASK, "کار", "Task")
        put(EntityType.APPOINTMENT, "قرار ملاقات", "Appointment")
        put(EntityType.DEAL, "معامله", "Deal")
        put(EntityType.OFFER, "پیشنهاد", "Offer")
        put(EntityType.TRANSACTION, "قرارداد", "Transaction")
        put(EntityType.COMMISSION, "کمیسیون", "Commission")
        put(EntityType.MATCH_RECORD, "بررسی تطبیق", "Match review")
        put(EntityType.NOTE, "یادداشت", "Note")

        put(AuditAction.CREATE, "ایجاد", "Created")
        put(AuditAction.UPDATE, "ویرایش", "Updated")
        put(AuditAction.DELETE, "حذف", "Deleted")
        put(AuditAction.STATUS_CHANGE, "تغییر وضعیت", "Status changed")
        put(AuditAction.MERGE, "ادغام", "Merged")
        put(AuditAction.EXPORT, "خروجی", "Exported")
        put(AuditAction.LOGIN, "ورود", "Signed in")
        put(AuditAction.PERMISSION_DENIED, "دسترسی رد شد", "Access denied")
        put(AuditAction.REVIEW, "بررسی", "Reviewed")

        put(TaskStatus.OPEN, "باز", "Open")
        put(TaskStatus.DONE, "انجام شد", "Done")
        put(TaskStatus.CANCELLED, "لغو شد", "Cancelled")

        put(MatchClass.EXCELLENT, "تطابق عالی", "Excellent match")
        put(MatchClass.STRONG, "تطابق قوی", "Strong match")
        put(MatchClass.POSSIBLE, "تطابق احتمالی", "Possible match")
        put(MatchClass.WEAK, "تطابق ضعیف", "Weak match")
        put(MatchClass.NOT_ELIGIBLE, "نامناسب", "Not eligible")

        put(MatchComponent.BUDGET, "بودجه", "Budget")
        put(MatchComponent.LOCATION, "موقعیت", "Location")
        put(MatchComponent.LAYOUT, "متراژ و چیدمان", "Area & layout")
        put(MatchComponent.FEATURES, "امکانات", "Amenities & features")
        put(MatchComponent.TIMING, "زمان‌بندی", "Timing")
        put(MatchComponent.FLEXIBILITY, "انعطاف", "Flexibility")
        put(MatchComponent.READINESS, "آمادگی مشتری", "Lead readiness")

        put(MatchReviewStatus.NEW, "جدید", "New")
        put(MatchReviewStatus.REVIEWED, "بررسی شد", "Reviewed")
        put(MatchReviewStatus.APPROVED_FOR_CONTACT, "تأیید برای تماس", "Approved for contact")
        put(MatchReviewStatus.CONTACTED, "تماس گرفته شد", "Contacted")
        put(MatchReviewStatus.VIEWING_SCHEDULED, "بازدید برنامه‌ریزی شد", "Viewing scheduled")
        put(MatchReviewStatus.VIEWED, "بازدید شد", "Viewed")
        put(MatchReviewStatus.OFFER_MADE, "پیشنهاد داده شد", "Offer made")
        put(MatchReviewStatus.REJECTED, "رد شد", "Rejected")
        put(MatchReviewStatus.SNOOZED, "به تعویق افتاد", "Snoozed")
        put(MatchReviewStatus.CLOSED, "بسته شد", "Closed")

        put(RecommendedAction.CONTACT_BUYER, "با خریدار تماس بگیرید", "Contact the buyer")
        put(RecommendedAction.SCHEDULE_VIEWING, "بازدید هماهنگ کنید", "Schedule a viewing")
        put(RecommendedAction.FOLLOW_UP_OFFER, "پیشنهاد را پیگیری کنید", "Follow up the offer")
        put(RecommendedAction.COLLECT_MISSING_INFO, "اطلاعات ناقص را تکمیل کنید", "Collect missing information")
        put(RecommendedAction.REVIEW, "بررسی توسط مشاور", "Review manually")
        put(RecommendedAction.NO_ACTION, "اقدامی لازم نیست", "No action needed")

        put(MissingField.BUYER_BUDGET, "بودجه خریدار", "Buyer budget")
        put(MissingField.BUYER_DEPOSIT_LIMIT, "سقف رهن خریدار", "Buyer deposit limit")
        put(MissingField.BUYER_RENT_LIMIT, "سقف اجاره ماهانه", "Buyer monthly rent limit")
        put(MissingField.BUYER_LOCATION, "منطقه مورد نظر خریدار", "Buyer preferred location")
        put(MissingField.BUYER_CATEGORY, "نوع ملک مورد نظر", "Required property type")
        put(MissingField.BUYER_SIZE, "متراژ و تعداد اتاق مورد نظر", "Desired area and bedrooms")
        put(MissingField.BUYER_TIMELINE, "زمان خرید", "Purchase timeline")
        put(MissingField.PROPERTY_PRICE, "قیمت پیشنهادی فروش", "Asking price")
        put(MissingField.PROPERTY_DEPOSIT, "مبلغ رهن", "Deposit amount")
        put(MissingField.PROPERTY_RENT, "اجاره ماهانه", "Monthly rent")
        put(MissingField.PROPERTY_LOCATION, "شهر و محله ملک", "Property city and neighborhood")
        put(MissingField.PROPERTY_COORDINATES, "مختصات ملک روی نقشه", "Property map coordinates")
        put(MissingField.PROPERTY_AREA, "متراژ ملک", "Property area")
        put(MissingField.PROPERTY_BEDROOMS, "تعداد اتاق خواب", "Number of bedrooms")
        put(MissingField.PROPERTY_FLOOR, "طبقه", "Floor")
        put(MissingField.PROPERTY_REQUIRED_FEATURE, "امکانات الزامی", "Required feature")
        put(MissingField.PROPERTY_FEATURE, "امکانات", "Feature")
        put(HeatingType.NONE, "ندارد", "None")
        put(HeatingType.CENTRAL, "موتورخانه مرکزی", "Central boiler")
        put(HeatingType.PACKAGE, "پکیج", "Combi boiler (package)")
        put(HeatingType.FLOOR, "گرمایش از کف", "Underfloor heating")
        put(HeatingType.HEATER, "بخاری", "Heater")
        put(HeatingType.OTHER, "سایر", "Other")

        put(CoolingType.NONE, "ندارد", "None")
        put(CoolingType.WATER_COOLER, "کولر آبی", "Evaporative cooler")
        put(CoolingType.SPLIT, "اسپلیت", "Split AC")
        put(CoolingType.DUCT_SPLIT, "داکت اسپلیت", "Ducted split")
        put(CoolingType.CHILLER, "چیلر", "Chiller")
        put(CoolingType.OTHER, "سایر", "Other")

        put(CommissionParty.BUYER, "خریدار", "Buyer")
        put(CommissionParty.SELLER, "فروشنده", "Seller")
        put(CommissionParty.LANDLORD, "موجر", "Landlord")
        put(CommissionParty.TENANT, "مستأجر", "Tenant")

        put(OfferDirection.BUYER_TO_SELLER, "از خریدار به فروشنده", "Buyer to seller")
        put(OfferDirection.SELLER_TO_BUYER, "از فروشنده به خریدار", "Seller to buyer")

        put(LocationSource.MANUAL_PIN, "انتخاب دستی روی نقشه", "Pin placed manually")
        put(LocationSource.MANUAL_COORDINATES, "مختصات واردشده", "Coordinates entered")
        put(LocationSource.GEOCODED, "جستجوی نشانی (تقریبی)", "Address search (approximate)")
        put(LocationSource.DEVICE_GPS, "موقعیت دستگاه", "Device location")
        put(LocationSource.SAMPLE_APPROXIMATE, "تقریبی (داده نمونه)", "Approximate (sample data)")
        put(LocationSource.UNKNOWN, "نامشخص", "Unknown")

        put(PropertyContactRole.OWNER, "مالک", "Owner")
        put(PropertyContactRole.CO_OWNER, "شریک مالک", "Co-owner")
        put(PropertyContactRole.LANDLORD, "موجر", "Landlord")
        put(PropertyContactRole.REPRESENTATIVE, "وکیل / نماینده", "Representative")
        put(PropertyContactRole.TENANT, "مستأجر فعلی", "Current tenant")
        put(PropertyContactRole.INTERESTED_BUYER, "خریدار علاقه‌مند", "Interested buyer")
        put(PropertyContactRole.OTHER, "سایر", "Other")

        put(MatchRejectionReason.BUYER_NOT_INTERESTED, "خریدار علاقه ندارد", "Buyer not interested")
        put(MatchRejectionReason.PRICE_TOO_HIGH, "قیمت بالاست", "Price too high")
        put(MatchRejectionReason.LOCATION_UNSUITABLE, "موقعیت نامناسب", "Location unsuitable")
        put(MatchRejectionReason.LAYOUT_UNSUITABLE, "متراژ یا چیدمان نامناسب", "Layout unsuitable")
        put(MatchRejectionReason.CONDITION_UNSUITABLE, "وضعیت ملک نامناسب", "Condition unsuitable")
        put(MatchRejectionReason.SELLER_DECLINED, "فروشنده نپذیرفت", "Seller declined")
        put(MatchRejectionReason.ALREADY_SHOWN, "قبلاً معرفی شده", "Already shown")
        put(MatchRejectionReason.DUPLICATE, "تکراری", "Duplicate")
        put(MatchRejectionReason.OTHER, "سایر", "Other")

        put(ConfidenceLevel.HIGH, "زیاد", "High")
        put(ConfidenceLevel.MEDIUM, "متوسط", "Medium")
        put(ConfidenceLevel.LOW, "کم", "Low")

        put(NegotiationSide.PRICE, "قیمت", "Price")
        put(NegotiationSide.DEPOSIT, "رهن", "Deposit")
        put(NegotiationSide.MONTHLY_RENT, "اجاره ماهانه", "Monthly rent")
    }
}
