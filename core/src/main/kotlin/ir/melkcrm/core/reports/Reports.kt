package ir.melkcrm.core.reports

import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.matching.MatchThresholds
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import java.time.ZoneId

/** Everything reports and the dashboard are computed from. Always real stored records. */
data class CrmSnapshot(
    val customers: List<Customer> = emptyList(),
    val requirements: List<BuyerRequirement> = emptyList(),
    val sellers: List<SellerProfile> = emptyList(),
    val properties: List<Property> = emptyList(),
    val activities: List<Activity> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val deals: List<Deal> = emptyList(),
    val offers: List<Offer> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val commissions: List<CommissionRecord> = emptyList(),
    val matches: List<MatchEvaluation> = emptyList(),
    val matchRecords: List<MatchRecord> = emptyList(),
) {
    val liveCustomers get() = customers.filter { !it.deleted && it.mergedIntoId == null }
    val liveProperties get() = properties.filter { !it.deleted }
    val activeRequirements get() = requirements.filter { !it.deleted && it.status == RequirementStatus.ACTIVE }

    /** Restricts the snapshot to one agent's records (for agent-level dashboards). */
    fun forAgent(agentId: String): CrmSnapshot {
        val customerIds = customers.filter { it.assignedAgentId == agentId }.map { it.id }.toSet()
        val propertyIds = properties.filter { it.assignedAgentId == agentId }.map { it.id }.toSet()
        return copy(
            customers = customers.filter { it.id in customerIds },
            requirements = requirements.filter { it.customerId in customerIds },
            sellers = sellers.filter { it.assignedAgentId == agentId || it.propertyId in propertyIds },
            properties = properties.filter { it.id in propertyIds },
            activities = activities.filter { it.agentId == agentId },
            tasks = tasks.filter { it.assignedAgentId == agentId },
            appointments = appointments.filter { it.agentId == agentId },
            deals = deals.filter { it.agentId == agentId },
            offers = offers.filter { it.agentId == agentId },
            transactions = transactions.filter { it.agentId == agentId },
            commissions = commissions.filter { it.agentId == agentId },
            matches = matches.filter { it.customerId in customerIds || it.propertyId in propertyIds },
            matchRecords = matchRecords.filter { it.customerId in customerIds || it.propertyId in propertyIds },
        )
    }
}

data class MonthlySales(val year: Int, val month: Int, val count: Int, val saleVolume: Money, val rentalCount: Int)

data class DashboardStats(
    val activeBuyers: Int,
    val activeSellers: Int,
    val totalCustomers: Int,
    val availableForSale: Int,
    val availableForRent: Int,
    val newCustomersThisWeek: Int,
    val buyersWithoutMatches: Int,
    val propertiesWithoutMatches: Int,
    val highPriorityMatches: Int,
    val upcomingAppointments: Int,
    val followUpsDueToday: Int,
    val overdueFollowUps: Int,
    val activeNegotiations: Int,
    val completedTransactions: Int,
    val expectedCommission: Money,
    val receivedCommission: Money,
    val monthlySales: List<MonthlySales>,
)

object Dashboard {
    fun compute(
        s: CrmSnapshot, thresholds: MatchThresholds, now: EpochMillis, calendar: CalendarSystem,
        zone: ZoneId = AgencyTime.TEHRAN, months: Int = 6,
    ): DashboardStats {
        val today = AgencyTime.epochDayOf(now, zone)
        val customers = s.liveCustomers
        val activeReqCustomers = s.activeRequirements.map { it.customerId }.toSet()
        val activeBuyers = customers.count { CustomerRole.BUYER in it.roles && !it.isInactive && it.id in activeReqCustomers }
        val sellingProps = s.liveProperties.filter { !it.status.isClosed }.mapNotNull { it.ownerCustomerId }.toSet()
        val activeSellerIds = s.sellers.filter { !it.deleted && it.status != SellerStatus.SOLD && it.status != SellerStatus.WITHDRAWN }.map { it.customerId }.toSet() + sellingProps
        val activeSellers = customers.count { (CustomerRole.SELLER in it.roles || CustomerRole.LANDLORD in it.roles) && it.id in activeSellerIds && !it.isInactive }
        val available = s.liveProperties.filter { it.status == PropertyStatus.AVAILABLE }
        val weekStart = AgencyTime.startOfDayMillis(DateFormatter.startOfWeek(today), zone)

        val good = s.matches.filter { it.eligible && it.score >= thresholds.possible }
        val goodReq = good.map { it.requirementId }.toSet()
        val goodProp = good.map { it.propertyId }.toSet()
        val records = s.matchRecords.associateBy { it.key }
        val highPriority = good.count { e ->
            e.score >= thresholds.strong && records[e.key].let { it == null || it.reviewStatus == MatchReviewStatus.NEW || it.reviewStatus == MatchReviewStatus.APPROVED_FOR_CONTACT }
        }

        val openTasks = s.tasks.filter { !it.deleted && it.status == TaskStatus.OPEN }
        val customerFollowUps = customers.mapNotNull { it.nextFollowUpDate }
        val dueToday = openTasks.count { it.dueDate == today } + customerFollowUps.count { it == today }
        val overdue = openTasks.count { it.dueDate < today } + customerFollowUps.count { it < today }

        val negotiationStages = setOf(DealStage.OFFER_SUBMITTED, DealStage.COUNTEROFFER, DealStage.NEGOTIATION, DealStage.AGREEMENT_REACHED)
        val liveCommissions = s.commissions.filter { it.status != CommissionStatus.CANCELLED }
        val expected = liveCommissions.filter { it.status != CommissionStatus.RECEIVED }
            .fold(Money.ZERO) { acc, c -> acc + (c.earnedAmount?.let { e -> if (c.receivedAmount >= e) Money.ZERO else e - c.receivedAmount } ?: c.expectedAmount ?: Money.ZERO) }
        val received = liveCommissions.fold(Money.ZERO) { acc, c -> acc + c.receivedAmount }

        return DashboardStats(
            activeBuyers = activeBuyers,
            activeSellers = activeSellers,
            totalCustomers = customers.size,
            availableForSale = available.count { DealType.SALE in it.dealTypes },
            availableForRent = available.count { DealType.RENT in it.dealTypes || DealType.FULL_DEPOSIT in it.dealTypes },
            newCustomersThisWeek = customers.count { it.registeredAt >= weekStart },
            buyersWithoutMatches = s.activeRequirements.count { it.id !in goodReq },
            propertiesWithoutMatches = available.count { it.id !in goodProp },
            highPriorityMatches = highPriority,
            upcomingAppointments = s.appointments.count { !it.deleted && it.status == AppointmentStatus.SCHEDULED && it.startsAt >= now && it.startsAt < now + 7 * 86_400_000L },
            followUpsDueToday = dueToday,
            overdueFollowUps = overdue,
            activeNegotiations = s.deals.count { it.stage in negotiationStages },
            completedTransactions = s.transactions.size,
            expectedCommission = expected,
            receivedCommission = received,
            monthlySales = Reports.monthlySales(s.transactions, calendar, today, months),
        )
    }
}

data class CountRow(val key: String, val count: Int)
data class Bucket(val label: String, val fromRial: Long?, val toRial: Long?, val count: Int)
data class AgentPerformance(
    val agentId: String,
    val activities: Int,
    val viewings: Int,
    val offers: Int,
    val transactions: Int,
    val tasksDone: Int,
    val tasksOpen: Int,
    val commissionReceived: Money,
)

object Reports {
    private fun inRange(t: EpochMillis, from: EpochMillis?, to: EpochMillis?) = (from == null || t >= from) && (to == null || t < to)

    fun customersByTypeAndStatus(s: CrmSnapshot): Map<CustomerRole, List<CountRow>> =
        CustomerRole.entries.associateWith { role ->
            s.liveCustomers.filter { role in it.roles }.groupingBy { it.status }.eachCount().map { CountRow(it.key, it.value) }.sortedByDescending { it.count }
        }

    fun newLeadsByMonth(s: CrmSnapshot, calendar: CalendarSystem, zone: ZoneId = AgencyTime.TEHRAN, from: EpochMillis? = null, to: EpochMillis? = null): List<Triple<Int, Int, Int>> =
        s.liveCustomers.filter { inRange(it.registeredAt, from, to) }
            .groupingBy { DateFormatter.yearMonthOf(AgencyTime.epochDayOf(it.registeredAt, zone), calendar) }
            .eachCount().entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { Triple(it.key.first, it.key.second, it.value) }

    /** Budget buckets in rial; defaults are toman billions: <5, 5–10, 10–20, 20–50, ≥50. */
    fun buyersByBudget(
        s: CrmSnapshot,
        edgesToman: List<Long> = listOf(5_000_000_000, 10_000_000_000, 20_000_000_000, 50_000_000_000),
    ): List<Bucket> {
        val reqs = s.activeRequirements.filter { it.dealType == DealType.SALE }
        val edges = edgesToman.map { it * 10 }
        val buckets = mutableListOf<Bucket>()
        var prev: Long? = null
        for (e in edges + listOf<Long?>(null)) {
            val lo = prev
            val count = reqs.count { r ->
                val b = (r.maxBudget ?: r.preferredBudget)?.rial ?: return@count false
                (lo == null || b >= lo) && (e == null || b < e)
            }
            buckets += Bucket(label = "${prev ?: 0}-${e ?: "∞"}", fromRial = prev, toRial = e, count = count)
            prev = e
        }
        buckets += Bucket("unknown", null, null, reqs.count { it.maxBudget == null && it.preferredBudget == null })
        return buckets
    }

    fun sellersByPropertyType(s: CrmSnapshot): List<CountRow> {
        val props = s.liveProperties.associateBy { it.id }
        return s.sellers.filter { !it.deleted }.mapNotNull { props[it.propertyId]?.category }
            .groupingBy { it.name }.eachCount().map { CountRow(it.key, it.value) }.sortedByDescending { it.count }
    }

    fun availableByLocation(s: CrmSnapshot): List<Pair<String, List<CountRow>>> =
        s.liveProperties.filter { it.status == PropertyStatus.AVAILABLE }
            .groupBy { it.city ?: "?" }
            .map { (city, list) -> city to list.groupingBy { it.neighborhood ?: "?" }.eachCount().map { CountRow(it.key, it.value) }.sortedByDescending { it.count } }
            .sortedByDescending { it.second.sumOf { r -> r.count } }

    fun propertiesByPrice(s: CrmSnapshot, edgesToman: List<Long> = listOf(5_000_000_000, 10_000_000_000, 20_000_000_000, 50_000_000_000)): List<Bucket> {
        val props = s.liveProperties.filter { it.status == PropertyStatus.AVAILABLE && it.askingPrice != null }
        val edges = edgesToman.map { it * 10 }
        var prev: Long? = null
        return (edges + listOf<Long?>(null)).map { e ->
            val p0 = prev
            val c = props.count { val v = it.askingPrice!!.rial; (p0 == null || v >= p0) && (e == null || v < e) }
            prev = e
            Bucket("${p0 ?: 0}-${e ?: "∞"}", p0, e, c)
        }
    }

    data class MatchingRates(val requirements: Int, val requirementsMatched: Int, val properties: Int, val propertiesMatched: Int) {
        val requirementRate get() = if (requirements == 0) 0.0 else requirementsMatched.toDouble() / requirements
        val propertyRate get() = if (properties == 0) 0.0 else propertiesMatched.toDouble() / properties
    }

    fun matchingRates(s: CrmSnapshot, thresholds: MatchThresholds): MatchingRates {
        val good = s.matches.filter { it.eligible && it.score >= thresholds.possible }
        val reqs = s.activeRequirements.map { it.id }.toSet()
        val props = s.liveProperties.filter { it.status == PropertyStatus.AVAILABLE }.map { it.id }.toSet()
        return MatchingRates(reqs.size, good.map { it.requirementId }.filter { it in reqs }.toSet().size,
            props.size, good.map { it.propertyId }.filter { it in props }.toSet().size)
    }

    data class Conversion(val from: Int, val to: Int) {
        val rate get() = if (from == 0) 0.0 else to.toDouble() / from
    }

    /** Distinct buyer+property pairs that had a viewing vs. those that later got an offer. */
    fun viewingToOffer(s: CrmSnapshot): Conversion {
        val viewed = (s.activities.filter { it.type == ActivityType.PROPERTY_VIEWING && !it.deleted }.mapNotNull { a -> a.customerId?.let { c -> a.propertyId?.let { c to it } } } +
            s.appointments.filter { it.type == AppointmentType.VIEWING && it.status == AppointmentStatus.COMPLETED }.flatMap { a -> a.propertyId?.let { p -> a.customerIds.map { it to p } } ?: emptyList() }).toSet()
        val offered = s.offers.map { it.buyerCustomerId to it.propertyId }.toSet()
        return Conversion(viewed.size, viewed.count { it in offered })
    }

    fun offerToSale(s: CrmSnapshot): Conversion {
        val dealsWithOffers = s.offers.map { it.dealId }.toSet()
        val completed = s.transactions.map { it.dealId }.toSet()
        return Conversion(dealsWithOffers.size, dealsWithOffers.count { it in completed })
    }

    /** Average days from acquisition (or creation) to contract, for completed sales. */
    fun averageDaysToSell(s: CrmSnapshot, zone: ZoneId = AgencyTime.TEHRAN): Double? {
        val props = s.properties.associateBy { it.id }
        val days = s.transactions.filter { it.dealType == DealType.SALE }.mapNotNull { t ->
            val p = props[t.propertyId] ?: return@mapNotNull null
            val start = p.acquiredDate ?: AgencyTime.epochDayOf(p.createdAt, zone)
            (t.contractDate - start).takeIf { it >= 0 }
        }
        return if (days.isEmpty()) null else days.average()
    }

    fun agentPerformance(s: CrmSnapshot, agentIds: Collection<String>, from: EpochMillis? = null, to: EpochMillis? = null): List<AgentPerformance> =
        agentIds.map { id ->
            val acts = s.activities.filter { it.agentId == id && !it.deleted && inRange(it.occurredAt, from, to) }
            AgentPerformance(
                agentId = id,
                activities = acts.size,
                viewings = acts.count { it.type == ActivityType.PROPERTY_VIEWING },
                offers = s.offers.count { it.agentId == id && inRange(it.offerDate, from, to) },
                transactions = s.transactions.count { it.agentId == id },
                tasksDone = s.tasks.count { it.assignedAgentId == id && it.status == TaskStatus.DONE && (it.completedAt == null || inRange(it.completedAt, from, to)) },
                tasksOpen = s.tasks.count { it.assignedAgentId == id && it.status == TaskStatus.OPEN },
                commissionReceived = s.commissions.filter { it.agentId == id }.fold(Money.ZERO) { a, c -> a + c.receivedAmount },
            )
        }.sortedByDescending { it.transactions * 1000 + it.offers * 10 + it.activities }

    data class FollowUpCompletion(val due: Int, val completedOnTime: Int, val completedLate: Int, val overdueOpen: Int)

    fun followUpCompletion(s: CrmSnapshot, today: EpochDay, zone: ZoneId = AgencyTime.TEHRAN): FollowUpCompletion {
        val due = s.tasks.filter { !it.deleted && it.status != TaskStatus.CANCELLED && it.dueDate <= today }
        val done = due.filter { it.status == TaskStatus.DONE }
        val onTime = done.count { t -> t.completedAt?.let { AgencyTime.epochDayOf(it, zone) <= t.dueDate } ?: true }
        return FollowUpCompletion(due.size, onTime, done.size - onTime, due.count { it.status == TaskStatus.OPEN && it.dueDate < today })
    }

    data class CommissionSummary(val expected: Money, val earned: Money, val received: Money, val outstanding: Money)

    fun commissionSummary(s: CrmSnapshot): CommissionSummary {
        val live = s.commissions.filter { it.status != CommissionStatus.CANCELLED }
        return CommissionSummary(
            expected = live.filter { it.isEstimate }.fold(Money.ZERO) { a, c -> a + (c.expectedAmount ?: Money.ZERO) },
            earned = live.fold(Money.ZERO) { a, c -> a + (c.earnedAmount ?: Money.ZERO) },
            received = live.fold(Money.ZERO) { a, c -> a + c.receivedAmount },
            outstanding = live.fold(Money.ZERO) { a, c -> a + c.outstanding },
        )
    }

    fun monthlySales(transactions: List<Transaction>, calendar: CalendarSystem, today: EpochDay, months: Int): List<MonthlySales> {
        val (cy, cm) = DateFormatter.yearMonthOf(today, calendar)
        val keys = (months - 1 downTo 0).map { back ->
            val total = cy * 12 + (cm - 1) - back
            Math.floorDiv(total, 12) to Math.floorMod(total, 12) + 1
        }
        val grouped = transactions.groupBy { DateFormatter.yearMonthOf(it.contractDate, calendar) }
        return keys.map { k ->
            val list = grouped[k].orEmpty()
            val sales = list.filter { it.dealType == DealType.SALE }
            MonthlySales(k.first, k.second, sales.size, sales.fold(Money.ZERO) { a, t -> a + (t.finalPrice ?: Money.ZERO) }, list.size - sales.size)
        }
    }
}
