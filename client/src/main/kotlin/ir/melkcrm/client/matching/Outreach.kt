package ir.melkcrm.client.matching

import ir.melkcrm.client.data.ActivityRepository
import ir.melkcrm.client.data.RecordWriter
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.core.i18n.Labels
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.MoneyFormatter
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.time.AppLanguage

/**
 * Controlled outreach. The app never sends anything by itself: it prepares a text with public listing
 * facts only, the agent sends it through a channel of their choice, then records the contact here.
 */
object Outreach {
    /** Whether the customer allowed this channel. In-person contact and e-mail are not consent-gated. */
    fun allowed(c: Customer, method: ContactMethod): Boolean = when (method) {
        ContactMethod.PHONE_CALL -> c.consent.callsAllowed
        ContactMethod.SMS -> c.consent.smsAllowed
        ContactMethod.WHATSAPP, ContactMethod.TELEGRAM, ContactMethod.EITAA, ContactMethod.BALE -> c.consent.messengerAllowed
        ContactMethod.EMAIL, ContactMethod.IN_PERSON -> true
    }

    fun activityTypeFor(method: ContactMethod): ActivityType = when (method) {
        ContactMethod.PHONE_CALL -> ActivityType.PHONE_CALL
        ContactMethod.SMS -> ActivityType.SMS
        ContactMethod.EMAIL -> ActivityType.EMAIL
        ContactMethod.IN_PERSON -> ActivityType.MEETING
        ContactMethod.WHATSAPP, ContactMethod.TELEGRAM, ContactMethod.EITAA, ContactMethod.BALE -> ActivityType.MESSENGER_MANUAL
    }

    /**
     * A shareable description of a listing for a buyer. Contains only what the agency would publish:
     * reference, type, deal, location by neighborhood, area, rooms, listed price and features. Never the
     * seller's minimum, confidential notes, the exact address, the owner, or the buyer's own budget.
     */
    fun propertySummary(p: Property, lang: AppLanguage, unit: CurrencyUnit, persianDigits: Boolean = lang == AppLanguage.PERSIAN): String {
        val fa = lang == AppLanguage.PERSIAN
        fun n(v: Number) = Digits.localize(if (v is Double && v == Math.floor(v)) v.toLong().toString() else v.toString(), persianDigits)
        fun money(m: ir.melkcrm.core.money.Money) = MoneyFormatter.format(m, unit, fa, persianDigits)
        val lines = mutableListOf<String>()
        lines += "${p.reference} · ${p.title}".let { Digits.localize(it, persianDigits) }
        val deal = p.dealTypes.joinToString(" / ") { Labels.of(it, lang) }
        val place = listOfNotNull(p.neighborhood, p.city).joinToString(if (fa) "، " else ", ")
        lines += if (fa) "${Labels.of(p.category, lang)} – $deal${if (place.isNotEmpty()) " – $place" else ""}"
        else "${Labels.of(p.category, lang)} – $deal${if (place.isNotEmpty()) " – $place" else ""}"
        val size = buildList {
            p.effectiveArea?.let { add(if (fa) "${n(it)} متر" else "${n(it)} m²") }
            p.bedrooms?.let { add(if (fa) "${n(it)} خواب" else "$it bedrooms") }
            p.floor?.let { add(if (fa) "طبقه ${n(it)}" else "floor $it") }
        }
        if (size.isNotEmpty()) lines += size.joinToString(" · ")
        if (DealType.SALE in p.dealTypes) p.askingPrice?.let { lines += (if (fa) "قیمت: " else "Price: ") + money(it) }
        if (DealType.RENT in p.dealTypes) {
            p.deposit?.let { lines += (if (fa) "رهن: " else "Deposit: ") + money(it) }
            p.monthlyRent?.let { lines += (if (fa) "اجاره ماهانه: " else "Monthly rent: ") + money(it) }
        }
        if (DealType.FULL_DEPOSIT in p.dealTypes) p.fullDepositAmount?.let { lines += (if (fa) "رهن کامل: " else "Full deposit: ") + money(it) }
        val features = buildList {
            if ((p.parkingSpaces ?: 0) > 0) add(Labels.of(Amenity.PARKING, lang))
            if (p.elevator == true) add(Labels.of(Amenity.ELEVATOR, lang))
            if (p.storage == true) add(Labels.of(Amenity.STORAGE, lang))
            p.amenities.filter { it != Amenity.PARKING && it != Amenity.ELEVATOR && it != Amenity.STORAGE }.take(5).forEach { add(Labels.of(it, lang)) }
        }
        if (features.isNotEmpty()) lines += features.joinToString(if (fa) "، " else ", ")
        return lines.joinToString("\n")
    }
}

class OutreachService(private val w: RecordWriter, private val activities: ActivityRepository, private val matching: MatchingService) {
    /**
     * Records a contact the agent made about a match. Refused when the customer has not allowed the
     * channel. The match moves to "contacted" unless it is already further along.
     */
    suspend fun recordContact(key: String, method: ContactMethod, outcome: ActivityOutcome?, notes: String?): SaveOutcome<Activity> {
        val (reqId, propId) = key.split('|').let { it[0] to it.getOrElse(1) { "" } }
        val req = w.load<BuyerRequirement>(EntityType.BUYER_REQUIREMENT, reqId) ?: return SaveOutcome.Forbidden("not_found")
        val customer = w.load<Customer>(EntityType.CUSTOMER, req.customerId) ?: return SaveOutcome.Forbidden("not_found")
        if (!Outreach.allowed(customer, method)) return SaveOutcome.Forbidden("consent")
        val type = Outreach.activityTypeFor(method)
        val activity = activities.newActivity(type, customer.id, propId).copy(
            requirementId = req.id, outcome = outcome, channel = method.takeIf { type == ActivityType.MESSENGER_MANUAL }, notes = notes,
        )
        val saved = activities.log(activity)
        if (saved is SaveOutcome.Saved && outcome != ActivityOutcome.NO_ANSWER) {
            activities.advanceMatches(listOf(customer.id), propId, MatchReviewStatus.CONTACTED)
            if (matching.record(key) == null) matching.review(key, MatchReviewStatus.CONTACTED)
        }
        return saved
    }
}
