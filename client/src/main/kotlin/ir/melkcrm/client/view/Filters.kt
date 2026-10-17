package ir.melkcrm.client.view

import ir.melkcrm.client.store.RecordIndex
import ir.melkcrm.client.store.searchMatches
import ir.melkcrm.core.matching.MatchClass
import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.EpochDay

/** List filters of the customer screen. Text search runs in the repository; these narrow the result. */
data class CustomerFilter(
    val role: CustomerRole? = null,
    val status: String? = null,
    val priority: Priority? = null,
    val agentId: String? = null,
    val followUpDue: Boolean = false,
    val includeInactive: Boolean = true,
) {
    val active: Boolean get() = this != CustomerFilter()

    fun apply(list: List<Customer>, today: EpochDay): List<Customer> = list.filter { c ->
        (role == null || role in c.roles) &&
            (status == null || c.status == status) &&
            (priority == null || c.priority == priority) &&
            (agentId == null || c.assignedAgentId == agentId) &&
            (!followUpDue || (c.nextFollowUpDate?.let { it <= today } == true)) &&
            (includeInactive || !c.isInactive)
    }

    companion object {
        /** Default order: follow-ups due first, then priority, then most recently updated. */
        fun sort(list: List<Customer>, today: EpochDay): List<Customer> = list.sortedWith(
            compareBy<Customer> { c -> c.nextFollowUpDate?.let { if (it <= today) 0 else 1 } ?: 1 }
                .thenByDescending { it.priority.ordinal }
                .thenByDescending { it.updatedAt },
        )
    }
}

data class PropertyFilter(
    val dealType: DealType? = null,
    val category: PropertyCategory? = null,
    val status: PropertyStatus? = null,
    val includeClosed: Boolean = false,
    val minPrice: Money? = null,
    val maxPrice: Money? = null,
    val agentId: String? = null,
    val withoutCoordinates: Boolean = false,
) {
    val active: Boolean get() = this != PropertyFilter()

    /** Price filters use the asking price for sale, the deposit (or full deposit) for rentals. */
    fun apply(list: List<Property>): List<Property> = list.filter { p ->
        val price = when (dealType) {
            DealType.RENT -> p.deposit
            DealType.FULL_DEPOSIT -> p.fullDepositAmount
            else -> p.askingPrice
        }
        (dealType == null || dealType in p.dealTypes) &&
            (category == null || p.category == category) &&
            (status == null || p.status == status) &&
            (includeClosed || status != null || !p.status.isClosed) &&
            (minPrice == null || (price != null && price >= minPrice)) &&
            (maxPrice == null || (price != null && price <= maxPrice)) &&
            (agentId == null || p.assignedAgentId == agentId) &&
            (!withoutCoordinates || p.location == null)
    }
}

/** Filters for match result lists (Workflows A and B). */
data class MatchFilter(
    val minClass: MatchClass = MatchClass.POSSIBLE,
    val hideRejected: Boolean = true,
    val hideSnoozed: Boolean = true,
    val onlyUnreviewed: Boolean = false,
    val showExcluded: Boolean = false,
) {
    fun apply(list: List<MatchEvaluation>, records: Map<String, MatchRecord>, today: EpochDay): List<MatchEvaluation> = list.filter { e ->
        val rec = records[e.key]
        val classOk = if (!e.eligible) showExcluded else e.classification.ordinal <= minClass.ordinal
        classOk &&
            (!hideRejected || rec?.reviewStatus != MatchReviewStatus.REJECTED) &&
            (!hideSnoozed || rec == null || !rec.isSnoozed(today)) &&
            (!onlyUnreviewed || rec == null || rec.reviewStatus == MatchReviewStatus.NEW || rec.changedSinceReview)
    }.sortedWith(compareByDescending<MatchEvaluation> { it.eligible }.thenByDescending { it.score }.thenByDescending { it.confidence })
}

/** One row of the activity timeline: activities, tasks, appointments and offers in one list. */
data class TimelineEntry(val at: Long, val kind: Kind, val id: String, val customerId: String?, val propertyId: String?) {
    enum class Kind { ACTIVITY, TASK, APPOINTMENT, OFFER }
}

object Timeline {
    fun build(
        activities: List<Activity>, tasks: List<TaskItem>, appointments: List<Appointment>, offers: List<Offer>,
        customerId: String? = null, propertyId: String? = null, zoneStartOfDay: (EpochDay) -> Long,
    ): List<TimelineEntry> {
        fun keep(c: String?, cs: List<String> = listOfNotNull(c), p: String?): Boolean =
            (customerId == null || customerId in cs) && (propertyId == null || p == propertyId)
        val out = ArrayList<TimelineEntry>()
        activities.filter { !it.deleted && keep(it.customerId, p = it.propertyId) }
            .forEach { out += TimelineEntry(it.occurredAt, TimelineEntry.Kind.ACTIVITY, it.id, it.customerId, it.propertyId) }
        tasks.filter { !it.deleted && keep(it.customerId, p = it.propertyId) }
            .forEach { out += TimelineEntry(it.completedAt ?: (zoneStartOfDay(it.dueDate) + (it.dueTimeMinutes ?: 0) * 60_000L), TimelineEntry.Kind.TASK, it.id, it.customerId, it.propertyId) }
        appointments.filter { !it.deleted && keep(null, it.customerIds, it.propertyId) }
            .forEach { out += TimelineEntry(it.startsAt, TimelineEntry.Kind.APPOINTMENT, it.id, it.customerIds.firstOrNull(), it.propertyId) }
        offers.filter { keep(it.buyerCustomerId, listOfNotNull(it.buyerCustomerId, it.sellerCustomerId), it.propertyId) }
            .forEach { out += TimelineEntry(it.offerDate, TimelineEntry.Kind.OFFER, it.id, it.buyerCustomerId, it.propertyId) }
        return out.sortedByDescending { it.at }
    }
}

/** Picker search over already-loaded lists (customers, properties) without a database round trip. */
object QuickSearch {
    fun customers(list: List<Customer>, query: String): List<Customer> {
        val q = RecordIndex.normalizeForSearch(query)
        if (q.isBlank()) return list
        return list.filter { c ->
            searchMatches(RecordIndex.normalizeForSearch(listOfNotNull(c.fullName, c.mobile, c.mobile?.removePrefix("0"), c.altPhone, c.city, c.neighborhood).joinToString(" ")), q)
        }
    }

    fun properties(list: List<Property>, query: String): List<Property> {
        val q = RecordIndex.normalizeForSearch(query)
        if (q.isBlank()) return list
        return list.filter { p ->
            searchMatches(RecordIndex.normalizeForSearch(listOfNotNull(p.reference, p.reference.removePrefix("IR-"), p.title, p.city, p.neighborhood).joinToString(" ")), q)
        }
    }
}
