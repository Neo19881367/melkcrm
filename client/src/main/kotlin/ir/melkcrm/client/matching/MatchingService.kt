package ir.melkcrm.client.matching

import ir.melkcrm.client.data.ActivityRepository
import ir.melkcrm.client.data.EntityChange
import ir.melkcrm.client.data.RecordWriter
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.core.matching.*
import ir.melkcrm.core.model.*
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.validation.ValidationIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MatchingState(
    /** Latest evaluations by match key: every eligible pair plus every pair with a stored record. */
    val evaluations: Map<String, MatchEvaluation> = emptyMap(),
    val lastFullRun: Long? = null,
    val running: Boolean = false,
)

/**
 * Runs the matching engine over the records on this device and keeps stored match records (the
 * agent's review state) in step with the latest scores. Results are recalculated automatically when a
 * property, seller, requirement, customer or their history changes, and in full at least daily.
 * The engine never changes a match's review status; only agents do.
 */
class MatchingService(private val w: RecordWriter, private val activities: ActivityRepository) {
    private val _state = MutableStateFlow(MatchingState())
    val state: StateFlow<MatchingState> = _state.asStateFlow()
    private val lock = Mutex()

    val records: Flow<List<MatchRecord>> = w.observe(EntityType.MATCH_RECORD)

    private fun engine() = MatchingEngine(w.agency.matching)

    private data class Inputs(
        val buyers: List<BuyerCandidate>,
        val properties: List<PropertyCandidate>,
        val stored: Map<String, MatchRecord>,
    )

    private suspend fun inputs(): Inputs {
        val customers = w.loadAll<Customer>(EntityType.CUSTOMER).filter { it.mergedIntoId == null }.associateBy { it.id }
        val acts = w.loadAll<Activity>(EntityType.ACTIVITY)
        val appts = w.loadAll<Appointment>(EntityType.APPOINTMENT)
        val offers = w.loadAll<Offer>(EntityType.OFFER)
        val now = w.now()
        val engagement = HashMap<String, EngagementSignals>()
        val buyers = w.loadAll<BuyerRequirement>(EntityType.BUYER_REQUIREMENT).mapNotNull { r ->
            val c = customers[r.customerId] ?: return@mapNotNull null
            BuyerCandidate(c, r, engagement.getOrPut(c.id) { activities.engagement(c, acts, appts, offers, now) })
        }
        val sellers = w.loadAll<SellerProfile>(EntityType.SELLER_PROFILE).groupBy { it.propertyId }
        val properties = w.loadAll<Property>(EntityType.PROPERTY).map { p ->
            val seller = sellers[p.id].orEmpty()
                .sortedWith(compareBy<SellerProfile> { it.status == SellerStatus.WITHDRAWN || it.status == SellerStatus.SOLD }.thenByDescending { it.updatedAt })
                .firstOrNull()
            PropertyCandidate(p, seller)
        }
        val stored = w.loadAll<MatchRecord>(EntityType.MATCH_RECORD).associateBy { it.key }
        return Inputs(buyers, properties, stored)
    }

    /** Cheap pre-filter: pairs the engine would exclude on deal type alone, unless a record exists. */
    private fun worthEvaluating(b: BuyerCandidate, p: PropertyCandidate, stored: Map<String, MatchRecord>): Boolean =
        b.requirement.dealType in p.property.dealTypes || stored.containsKey(matchKey(b.requirement.id, p.property.id))

    /** Workflow C over everything visible on this device. */
    suspend fun recalculateAll(): Map<String, MatchEvaluation> = lock.withLock {
        _state.update { it.copy(running = true) }
        try {
            val inp = inputs()
            val engine = engine()
            val today = w.today()
            val evals = HashMap<String, MatchEvaluation>()
            for (b in inp.buyers) for (p in inp.properties) {
                if (!worthEvaluating(b, p, inp.stored)) continue
                val e = engine.evaluate(b, p, today)
                if (e.eligible || inp.stored.containsKey(e.key)) evals[e.key] = e
            }
            store(evals.values, inp.stored)
            val now = w.now()
            w.store.setMeta(KEY_LAST_RUN, now.toString())
            _state.value = MatchingState(evals, now, running = false)
            evals
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    /** Recalculates only the pairs touching one property or one customer's requirements. */
    private suspend fun recalculatePartial(propertyIds: Set<String>, customerIds: Set<String>) = lock.withLock {
        val inp = inputs()
        val engine = engine()
        val today = w.today()
        val fresh = HashMap<String, MatchEvaluation>()
        val touched = HashSet<String>()
        for (b in inp.buyers) for (p in inp.properties) {
            if (b.customer.id !in customerIds && p.property.id !in propertyIds) continue
            val key = matchKey(b.requirement.id, p.property.id)
            touched += key
            if (!worthEvaluating(b, p, inp.stored)) continue
            val e = engine.evaluate(b, p, today)
            if (e.eligible || inp.stored.containsKey(e.key)) fresh[e.key] = e
        }
        store(fresh.values, inp.stored)
        // Drop pairs whose requirement or property disappeared.
        val liveReqs = inp.buyers.map { it.requirement.id }.toSet()
        val liveProps = inp.properties.map { it.property.id }.toSet()
        _state.update { s ->
            val kept = s.evaluations.filterKeys { it !in touched }.filterValues { it.requirementId in liveReqs && it.propertyId in liveProps }
            s.copy(evaluations = kept + fresh)
        }
    }

    /**
     * Creates match records for new eligible pairs at or above "possible" and refreshes existing ones.
     * A record is rewritten (and synced) only when its score, eligibility or flags changed.
     */
    private suspend fun store(evals: Collection<MatchEvaluation>, stored: Map<String, MatchRecord>) {
        val thresholds = w.agency.matching.thresholds
        val now = w.now()
        for (e in evals) {
            val prev = stored[e.key]
            if (prev == null && (!e.eligible || e.score < thresholds.possible)) continue
            val next = MatchReview.upsert(prev, e, w.user.agencyId, now)
            val material = prev == null || prev.score != next.score || prev.eligible != next.eligible ||
                prev.classification != next.classification || prev.provisional != next.provisional ||
                prev.changedSinceReview != next.changedSinceReview
            if (!material) continue
            val agent = w.store.get(EntityType.CUSTOMER, e.customerId)?.assignedAgentId
            w.persist(EntityType.MATCH_RECORD, next, assignedAgentId = agent, audit = false)
        }
    }

    /** Workflow A: ranked buyers for a property, freshly calculated, optionally with excluded buyers and reasons. */
    suspend fun buyersFor(propertyId: String, includeExcluded: Boolean = false): List<MatchEvaluation> {
        val inp = inputs()
        val p = inp.properties.firstOrNull { it.property.id == propertyId } ?: return emptyList()
        val result = engine().findBuyers(p, inp.buyers, w.today(), includeExcluded)
        store(result.filter { it.eligible }, inp.stored)
        return result
    }

    /** Workflow B: ranked properties for one requirement. */
    suspend fun propertiesFor(requirementId: String, includeExcluded: Boolean = false): List<MatchEvaluation> {
        val inp = inputs()
        val b = inp.buyers.firstOrNull { it.requirement.id == requirementId } ?: return emptyList()
        val result = engine().findProperties(b, inp.properties, w.today(), includeExcluded)
        store(result.filter { it.eligible }, inp.stored)
        return result
    }

    /** A single pair, for the match detail screen. */
    suspend fun evaluate(requirementId: String, propertyId: String): MatchEvaluation? {
        val inp = inputs()
        val b = inp.buyers.firstOrNull { it.requirement.id == requirementId } ?: return null
        val p = inp.properties.firstOrNull { it.property.id == propertyId } ?: return null
        return engine().evaluate(b, p, w.today())
    }

    suspend fun opportunities(): OpportunitySummary {
        if (state.value.lastFullRun == null) recalculateAll()
        val evals = state.value.evaluations.values.toList()
        val stored = w.loadAll<MatchRecord>(EntityType.MATCH_RECORD).associateBy { it.key }
        val props = w.loadAll<Property>(EntityType.PROPERTY).filter { it.status == PropertyStatus.AVAILABLE }
        val reqs = w.loadAll<BuyerRequirement>(EntityType.BUYER_REQUIREMENT).filter { it.status == RequirementStatus.ACTIVE }
        return OpportunityAnalyzer.analyze(
            evals, stored, w.agency.matching.thresholds, props.map { it.id }, reqs.map { it.id },
            props.associate { it.id to it.acquiredDate }, w.today(),
        )
    }

    /**
     * Starts automatic recalculation. Changes are batched briefly so that editing a record and its
     * related records triggers one run. Match record writes themselves are ignored.
     */
    @OptIn(FlowPreview::class)
    fun startAutoRecalculation(scope: CoroutineScope): Job = scope.launch {
        recalculateAll()
        val pending = MutableStateFlow<Set<EntityChange>>(emptySet())
        launch {
            w.changes.filter { it.type in relevant }.collect { c -> pending.update { it + c } }
        }
        pending.filter { it.isNotEmpty() }.debounce(800).collect { batch ->
            pending.update { it - batch }
            val propertyIds = batch.filter { it.type == EntityType.PROPERTY || it.type == EntityType.SELLER_PROFILE }.mapNotNull { it.propertyId }.toSet()
            val customerIds = batch.filter { it.type != EntityType.PROPERTY && it.type != EntityType.SELLER_PROFILE }.mapNotNull { it.customerId }.toSet()
            if (batch.size > 50) recalculateAll() else recalculatePartial(propertyIds, customerIds)
        }
    }

    /** Timing scores depend on the date: recalculate everything when the last full run is a day old. */
    suspend fun refreshIfStale() {
        if (Recalculation.shouldRefreshAll(state.value.lastFullRun, w.now())) recalculateAll()
    }

    // ------------------------------------------------------------------ agent review

    suspend fun record(key: String): MatchRecord? = w.load(EntityType.MATCH_RECORD, key)

    /** Creates the stored record if the agent acts on a match that was only calculated. */
    private suspend fun recordOrCreate(key: String): MatchRecord? {
        record(key)?.let { return it }
        val e = state.value.evaluations[key] ?: run {
            val (req, prop) = key.split('|').let { it[0] to it.getOrNull(1) }
            prop?.let { evaluate(req, it) }
        } ?: return null
        return MatchReview.upsert(null, e, w.user.agencyId, w.now())
    }

    suspend fun review(
        key: String, to: MatchReviewStatus, note: String? = null,
        rejectionReason: MatchRejectionReason? = null, snoozedUntil: EpochDay? = null,
    ): SaveOutcome<MatchRecord> {
        val m = recordOrCreate(key) ?: return SaveOutcome.Forbidden("not_found")
        if (!MatchReview.canTransition(m.reviewStatus, to)) return SaveOutcome.Invalid(listOf(ValidationIssue("reviewStatus", "invalid_transition")))
        if (to == MatchReviewStatus.REJECTED && rejectionReason == null) return SaveOutcome.Invalid(listOf(ValidationIssue("rejectionReason", "required")))
        if (to == MatchReviewStatus.SNOOZED && (snoozedUntil == null || snoozedUntil <= w.today())) return SaveOutcome.Invalid(listOf(ValidationIssue("snoozedUntil", "required")))
        val updated = MatchReview.transition(m, to, w.user.id, w.now(), note, rejectionReason, snoozedUntil)
        val agent = w.store.get(EntityType.CUSTOMER, m.customerId)?.assignedAgentId
        w.persist(EntityType.MATCH_RECORD, updated, assignedAgentId = agent, auditAction = AuditAction.REVIEW, note = to.name)
        return SaveOutcome.Saved(updated)
    }

    suspend fun saveFeedback(key: String, buyerFeedback: String?, viewingOutcome: String?, followUpDate: EpochDay?): SaveOutcome<MatchRecord> {
        val m = recordOrCreate(key) ?: return SaveOutcome.Forbidden("not_found")
        val updated = m.copy(
            buyerFeedback = buyerFeedback?.trim()?.ifBlank { null }, viewingOutcome = viewingOutcome?.trim()?.ifBlank { null },
            followUpDate = followUpDate, updatedAt = w.now(),
        )
        val agent = w.store.get(EntityType.CUSTOMER, m.customerId)?.assignedAgentId
        w.persist(EntityType.MATCH_RECORD, updated, assignedAgentId = agent, auditAction = AuditAction.REVIEW, note = "feedback")
        return SaveOutcome.Saved(updated)
    }

    companion object {
        const val KEY_LAST_RUN = "matching.lastFullRun"
        private val relevant = setOf(
            EntityType.PROPERTY, EntityType.SELLER_PROFILE, EntityType.BUYER_REQUIREMENT, EntityType.CUSTOMER,
            EntityType.ACTIVITY, EntityType.APPOINTMENT, EntityType.OFFER,
        )
    }
}
