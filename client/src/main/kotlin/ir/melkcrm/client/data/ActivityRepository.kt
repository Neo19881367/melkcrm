package ir.melkcrm.client.data

import ir.melkcrm.core.matching.EngagementSignals
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.matching.MatchReview
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.model.*
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis
import ir.melkcrm.core.validation.Severity
import ir.melkcrm.core.validation.ValidationIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ActivityRepository(private val w: RecordWriter) {

    val activities: Flow<List<Activity>> = w.observe<Activity>(EntityType.ACTIVITY).map { l -> l.sortedByDescending { it.occurredAt } }
    val tasks: Flow<List<TaskItem>> = w.observe(EntityType.TASK)
    val appointments: Flow<List<Appointment>> = w.observe<Appointment>(EntityType.APPOINTMENT).map { l -> l.sortedBy { it.startsAt } }

    fun activitiesOf(customerId: String): Flow<List<Activity>> = activities.map { l -> l.filter { it.customerId == customerId } }
    fun activitiesForProperty(propertyId: String): Flow<List<Activity>> = activities.map { l -> l.filter { it.propertyId == propertyId } }
    fun tasksOf(customerId: String): Flow<List<TaskItem>> = tasks.map { l -> l.filter { it.customerId == customerId } }
    fun appointmentsOf(customerId: String): Flow<List<Appointment>> = appointments.map { l -> l.filter { customerId in it.customerIds } }

    suspend fun activity(id: String?): Activity? = w.load(EntityType.ACTIVITY, id)
    suspend fun task(id: String?): TaskItem? = w.load(EntityType.TASK, id)
    suspend fun appointment(id: String?): Appointment? = w.load(EntityType.APPOINTMENT, id)

    fun newActivity(type: ActivityType = ActivityType.PHONE_CALL, customerId: String? = null, propertyId: String? = null): Activity {
        val now = w.now()
        return Activity(w.newId(), w.user.agencyId, type, occurredAt = now, agentId = w.user.id, customerId = customerId, propertyId = propertyId, updatedAt = now)
    }

    /**
     * Logs a contact or event. A successful contact updates the customer's last-contact time (and moves a
     * new lead to "contacted"); a next action with a date becomes a task with a reminder.
     */
    suspend fun log(input: Activity): SaveOutcome<Activity> {
        val a = input.copy(notes = input.notes?.trim()?.ifBlank { null }, nextAction = input.nextAction?.trim()?.ifBlank { null }, updatedAt = w.now())
        if (a.type == ActivityType.MESSENGER_MANUAL && a.channel == null) return SaveOutcome.Invalid(listOf(ValidationIssue("channel", "required")))
        w.store.transaction {
            w.persist(EntityType.ACTIVITY, a)
            val customer = a.customerId?.let { w.load<Customer>(EntityType.CUSTOMER, it) }
            if (customer != null && a.type in contactTypes && a.outcome != ActivityOutcome.NO_ANSWER) {
                val last = maxOf(customer.lastContactAt ?: 0, a.occurredAt)
                val status = if (customer.status == CustomerStatus.NEW_LEAD.name) CustomerStatus.CONTACTED.name else customer.status
                if (last != customer.lastContactAt || status != customer.status) {
                    w.persist(EntityType.CUSTOMER, customer.copy(lastContactAt = last, status = status, updatedAt = w.now()), note = "activity")
                }
            }
            val next = a.nextAction
            val nextDate = a.nextActionDate
            if (next != null && nextDate != null) {
                saveTask(newTask(next, nextDate).copy(customerId = a.customerId, propertyId = a.propertyId, reminderAt = AgencyTime.startOfDayMillis(nextDate, w.zone) + 9 * 3_600_000L))
            }
        }
        return SaveOutcome.Saved(a)
    }

    // ------------------------------------------------------------------ tasks

    fun newTask(title: String = "", due: EpochDay = w.today()): TaskItem {
        val now = w.now()
        return TaskItem(w.newId(), w.user.agencyId, title, due, assignedAgentId = w.user.id, createdAt = now, updatedAt = now)
    }

    suspend fun saveTask(input: TaskItem): SaveOutcome<TaskItem> {
        val t = input.copy(title = input.title.trim(), updatedAt = w.now())
        if (t.title.isBlank()) return SaveOutcome.Invalid(listOf(ValidationIssue("title", "required")))
        val dueTime = t.dueTimeMinutes
        if (dueTime != null && dueTime !in 0 until 24 * 60) return SaveOutcome.Invalid(listOf(ValidationIssue("dueTimeMinutes", "invalid_number")))
        w.writeCheck(EntityType.TASK, t.id, t.assignedAgentId, t.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        w.persist(EntityType.TASK, t)
        return SaveOutcome.Saved(t)
    }

    suspend fun completeTask(id: String, done: Boolean = true): SaveOutcome<TaskItem> {
        val t = task(id) ?: return SaveOutcome.Forbidden("not_found")
        return saveTask(t.copy(status = if (done) TaskStatus.DONE else TaskStatus.OPEN, completedAt = if (done) w.now() else null))
    }

    // ------------------------------------------------------------------ appointments

    fun newAppointment(type: AppointmentType = AppointmentType.VIEWING, startsAt: EpochMillis = w.now() + 86_400_000L): Appointment =
        Appointment(w.newId(), w.user.agencyId, type, "", startsAt, agentId = w.user.id, updatedAt = w.now())

    suspend fun saveAppointment(input: Appointment): SaveOutcome<Appointment> {
        val a = input.copy(title = input.title.trim(), updatedAt = w.now())
        val ends = a.endsAt
        val reminder = a.reminderMinutesBefore
        val issues = buildList {
            if (a.title.isBlank()) add(ValidationIssue("title", "required"))
            if (ends != null && ends < a.startsAt) add(ValidationIssue("endsAt", "min_greater_than_max"))
            if (reminder != null && reminder < 0) add(ValidationIssue("reminderMinutesBefore", "negative"))
        }
        if (issues.isNotEmpty()) return SaveOutcome.Invalid(issues)
        w.writeCheck(EntityType.APPOINTMENT, a.id, a.agentId, a.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        w.persist(EntityType.APPOINTMENT, a)
        val propertyId = a.propertyId
        if (a.type == AppointmentType.VIEWING && propertyId != null && a.status == AppointmentStatus.SCHEDULED) {
            advanceMatches(a.customerIds, propertyId, MatchReviewStatus.VIEWING_SCHEDULED)
        }
        val warnings = if (a.startsAt < w.now() && a.status == AppointmentStatus.SCHEDULED) listOf(ValidationIssue("startsAt", "in_the_past", Severity.WARNING)) else emptyList()
        return SaveOutcome.Saved(a, warnings)
    }

    /** Records the outcome. A completed viewing or meeting is also logged as an activity for each customer. */
    suspend fun completeAppointment(id: String, status: AppointmentStatus, feedback: ViewingFeedback?, notes: String?): SaveOutcome<Appointment> {
        val a = appointment(id) ?: return SaveOutcome.Forbidden("not_found")
        val updated = a.copy(status = status, feedback = feedback, outcomeNotes = notes?.trim()?.ifBlank { null })
        val saved = saveAppointment(updated)
        if (saved !is SaveOutcome.Saved || status != AppointmentStatus.COMPLETED) return saved
        val type = if (a.type == AppointmentType.VIEWING) ActivityType.PROPERTY_VIEWING else ActivityType.MEETING
        val outcome = when (feedback) {
            ViewingFeedback.VERY_INTERESTED, ViewingFeedback.INTERESTED -> ActivityOutcome.INTERESTED
            ViewingFeedback.NOT_INTERESTED -> ActivityOutcome.NOT_INTERESTED
            else -> ActivityOutcome.COMPLETED
        }
        for (cid in a.customerIds) {
            log(newActivity(type, cid, a.propertyId).copy(occurredAt = a.startsAt, outcome = outcome, viewingFeedback = feedback, notes = updated.outcomeNotes))
        }
        val propertyId = a.propertyId
        if (a.type == AppointmentType.VIEWING && propertyId != null) advanceMatches(a.customerIds, propertyId, MatchReviewStatus.VIEWED)
        return saved
    }

    /** Moves stored matches for these buyers and this property forward (never backwards). */
    suspend fun advanceMatches(customerIds: List<String>, propertyId: String, to: MatchReviewStatus) {
        val progress = listOf(
            MatchReviewStatus.NEW, MatchReviewStatus.REVIEWED, MatchReviewStatus.APPROVED_FOR_CONTACT, MatchReviewStatus.CONTACTED,
            MatchReviewStatus.VIEWING_SCHEDULED, MatchReviewStatus.VIEWED, MatchReviewStatus.OFFER_MADE,
        )
        for (rec in w.store.byProperty(EntityType.MATCH_RECORD, propertyId)) {
            val m = w.decode<MatchRecord>(rec)
            if (m.customerId !in customerIds) continue
            val ahead = m.reviewStatus == MatchReviewStatus.CLOSED ||
                (m.reviewStatus in progress && progress.indexOf(m.reviewStatus) >= progress.indexOf(to))
            if (ahead || !MatchReview.canTransition(m.reviewStatus, to)) continue
            w.persist(EntityType.MATCH_RECORD, MatchReview.transition(m, to, w.user.id, w.now()), assignedAgentId = rec.assignedAgentId, audit = false)
        }
    }

    // ------------------------------------------------------------------ engagement

    /**
     * Readiness signals from recorded CRM history only: an interested outcome or viewing feedback,
     * a viewing appointment, a buyer offer, and consecutive unanswered follow-ups. Nothing is inferred.
     */
    fun engagement(
        customer: Customer, activities: List<Activity>, appointments: List<Appointment>, offers: List<Offer>, now: EpochMillis,
        windowDays: Int = 90,
    ): EngagementSignals {
        val since = now - windowDays * 86_400_000L
        val mine = activities.filter { it.customerId == customer.id && it.occurredAt >= since }.sortedByDescending { it.occurredAt }
        val interested = mine.any { it.outcome == ActivityOutcome.INTERESTED || it.viewingFeedback == ViewingFeedback.INTERESTED || it.viewingFeedback == ViewingFeedback.VERY_INTERESTED }
        val viewing = appointments.any { customer.id in it.customerIds && it.type == AppointmentType.VIEWING && it.startsAt >= since && it.status != AppointmentStatus.CANCELLED }
        val offer = offers.any { it.buyerCustomerId == customer.id && it.direction == OfferDirection.BUYER_TO_SELLER && it.offerDate >= since && it.status != OfferStatus.DRAFT }
        val unanswered = mine.filter { it.type in contactTypes }.takeWhile { it.outcome == ActivityOutcome.NO_ANSWER }.size
        return EngagementSignals(interested, viewing, offer, unanswered, customer.lastContactAt)
    }

    companion object {
        val contactTypes = setOf(
            ActivityType.PHONE_CALL, ActivityType.MEETING, ActivityType.PROPERTY_VIEWING, ActivityType.MESSENGER_MANUAL,
            ActivityType.SMS, ActivityType.EMAIL, ActivityType.FOLLOW_UP, ActivityType.NEGOTIATION, ActivityType.IN_APP_MESSAGE,
        )
    }
}
