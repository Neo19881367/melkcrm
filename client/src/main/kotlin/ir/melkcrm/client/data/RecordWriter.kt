package ir.melkcrm.client.data

import ir.melkcrm.client.session.AgencyState
import ir.melkcrm.client.session.SessionManager
import ir.melkcrm.client.store.LocalRecord
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.store.OutboxEntry
import ir.melkcrm.client.store.RecordIndex
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.permissions.Access
import ir.melkcrm.core.permissions.RecordScope
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.RecordCodec
import ir.melkcrm.core.sync.SyncState
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.validation.ValidationIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import java.util.UUID

/** Result of a save. The UI shows [Invalid] issues next to their fields and [Saved.warnings] as hints. */
sealed interface SaveOutcome<out T> {
    data class Saved<T>(val value: T, val warnings: List<ValidationIssue> = emptyList()) : SaveOutcome<T>
    data class Invalid(val issues: List<ValidationIssue>) : SaveOutcome<Nothing>
    data class PossibleDuplicates(val candidates: List<ir.melkcrm.core.duplicates.DuplicateCandidate>) : SaveOutcome<Nothing>
    /** The user's role does not allow this. The server would refuse it too; the app checks first to explain why. */
    data class Forbidden(val reason: String) : SaveOutcome<Nothing>
}

/** A local write, used to trigger match recalculation and UI refreshes. */
data class EntityChange(val type: EntityType, val id: String, val customerId: String?, val propertyId: String?)

/**
 * Every local write goes through here: the record is stored, a change is queued for the server
 * (one outbox entry per record), and an audit entry with the changed fields is kept on the device.
 * The server keeps its own authoritative audit log; this one makes history visible offline.
 */
class RecordWriter(
    val store: LocalStore,
    val session: SessionManager,
    val clock: () -> Long = System::currentTimeMillis,
    val newId: () -> String = { UUID.randomUUID().toString() },
    val zone: ZoneId = AgencyTime.TEHRAN,
) {
    val json = RecordCodec.json
    private val _changes = MutableSharedFlow<EntityChange>(extraBufferCapacity = 512)
    val changes: SharedFlow<EntityChange> = _changes.asSharedFlow()

    val user: AgencyUser get() = session.user
    val agency: AgencyState get() = session.agency.value
    fun now(): Long = clock()
    fun today(): EpochDay = AgencyTime.today(zone, clock())

    // ------------------------------------------------------------------ reading

    fun <T> decode(rec: LocalRecord): T = json.decodeFromString(RecordCodec.serializer<T>(rec.type), rec.json)

    fun <T> observe(type: EntityType): Flow<List<T>> = store.observe(type).map { list -> list.map { decode<T>(it) } }

    suspend fun <T> load(type: EntityType, id: String?): T? = id?.let { store.get(type, it) }?.takeIf { !it.deleted }?.let { decode<T>(it) }

    suspend fun <T> loadAll(type: EntityType): List<T> = store.all(type).map { decode<T>(it) }

    // ------------------------------------------------------------------ access (mirrors the server; the server decides)

    fun scope(type: EntityType, agencyId: String, assignedAgentId: String?) =
        RecordScope(agencyId, assignedAgentId, agency.teamOf(assignedAgentId), type.isInventory)

    fun access(rec: LocalRecord): Access = agency.policy.access(user, scope(rec.type, rec.agencyId, rec.assignedAgentId))

    fun canSeeConfidential(agencyId: String, assignedAgentId: String?): Boolean =
        agency.policy.canSeeConfidential(user, scope(EntityType.PROPERTY, agencyId, assignedAgentId))

    /** Null when allowed, otherwise the reason. */
    suspend fun writeCheck(type: EntityType, id: String, newAssignedAgentId: String?, agencyId: String, deleting: Boolean = false): String? {
        val existing = store.get(type, id)
        val policy = agency.policy
        if (agencyId != user.agencyId) return "wrong_agency"
        if (existing == null) {
            return if (policy.canWrite(user, scope(type, agencyId, newAssignedAgentId))) null else "forbidden"
        }
        val oldScope = scope(type, existing.agencyId, existing.assignedAgentId)
        if (!policy.canWrite(user, oldScope)) return "forbidden"
        if (existing.assignedAgentId != newAssignedAgentId) {
            val claimingUnassigned = existing.assignedAgentId == null && newAssignedAgentId == user.id
            if (!claimingUnassigned && !policy.canReassign(user, oldScope)) return "reassign_forbidden"
        }
        if (deleting && !existing.deleted && !policy.canDelete(user, oldScope)) return "delete_forbidden"
        return null
    }

    // ------------------------------------------------------------------ writing

    /**
     * Stores [value]. [assignedAgentId] overrides the agent derived from the entity (used for records
     * that inherit their agent from a customer or property). [deleted] overrides the entity flag for
     * types without one. Sample records are never queued for upload.
     */
    suspend fun <T> persist(
        type: EntityType,
        value: T,
        assignedAgentId: String? = null,
        deleted: Boolean? = null,
        audit: Boolean = true,
        auditAction: AuditAction? = null,
        note: String? = null,
        sample: Boolean? = null,
        enqueue: Boolean = true,
    ): LocalRecord {
        val obj = json.encodeToJsonElement(RecordCodec.serializer<T>(type), value).jsonObject
        val idx = RecordIndex.of(type, obj)
        val previous = store.get(type, idx.id)
        val agencyId = idx.agencyId ?: previous?.agencyId ?: user.agencyId
        val isSample = sample ?: previous?.sample ?: referencesSample(idx.customerId, idx.propertyId)
        val rec = LocalRecord(
            type = type, id = idx.id, agencyId = agencyId,
            assignedAgentId = assignedAgentId ?: RecordIndex.agentOf(type, obj),
            version = previous?.version ?: 0,
            updatedAt = idx.updatedAt ?: clock(),
            deleted = deleted ?: idx.deleted,
            json = obj.toString(),
            syncState = if (isSample) SyncState.LOCAL_ONLY else SyncState.PENDING,
            sample = isSample,
            customerId = idx.customerId, propertyId = idx.propertyId, status = idx.status, searchText = idx.searchText,
        )
        store.transaction {
            store.put(rec)
            if (enqueue && !isSample) enqueue(type, idx.id, previous)
            if (audit) audit(rec, previous, obj, auditAction, note)
        }
        _changes.tryEmit(EntityChange(type, idx.id, idx.customerId, idx.propertyId))
        return rec
    }

    /** Records about sample customers or properties are sample data too, so they are never uploaded. */
    private suspend fun referencesSample(customerId: String?, propertyId: String?): Boolean =
        (customerId != null && store.get(EntityType.CUSTOMER, customerId)?.sample == true) ||
            (propertyId != null && store.get(EntityType.PROPERTY, propertyId)?.sample == true)

    private suspend fun enqueue(type: EntityType, id: String, previous: LocalRecord?) {
        val existing = store.outboxEntry(type, id)
        val entry = existing?.copy(changeId = newId(), rejected = false, lastError = null, attempts = 0, nextAttemptAt = 0)
            ?: OutboxEntry(type, id, newId(), previous?.version ?: 0, clock())
        store.putOutbox(entry)
    }

    suspend fun addAudit(event: AuditEvent) = store.addAudit(event)

    private suspend fun audit(rec: LocalRecord, previous: LocalRecord?, obj: JsonObject, action: AuditAction?, note: String?) {
        val old = previous?.let { json.parseToJsonElement(it.json).jsonObject }
        val changes = diff(rec.type, old, obj)
        if (previous != null && changes.isEmpty() && action == null && previous.deleted == rec.deleted) return
        val act = action ?: when {
            previous == null -> AuditAction.CREATE
            rec.deleted && !previous.deleted -> AuditAction.DELETE
            "status" in changes || "stage" in changes -> AuditAction.STATUS_CHANGE
            else -> AuditAction.UPDATE
        }
        store.addAudit(AuditEvent(newId(), rec.agencyId, user.id, rec.type.name, rec.id, act, if (previous == null) emptyMap() else changes, note, clock()))
    }

    private val ignoredInDiff = setOf("updatedAt", "version", "computedAt", "fingerprint", "history")

    /** Field-level changes; confidential values are never written into the history, only that they changed. */
    fun diff(type: EntityType, old: JsonObject?, new: JsonObject): Map<String, List<String?>> {
        val confidential = RecordCodec.confidentialFields[type].orEmpty()
        val out = linkedMapOf<String, List<String?>>()
        for (k in (old?.keys.orEmpty() + new.keys)) {
            if (k in ignoredInDiff) continue
            val a = old?.get(k)?.let { display(it) }
            val b = new[k]?.let { display(it) }
            if (a == b) continue
            out[k] = if (k in confidential) listOf("***", "***") else listOf(a?.take(200), b?.take(200))
        }
        return out
    }

    private fun display(e: kotlinx.serialization.json.JsonElement): String? =
        (e as? JsonPrimitive)?.let { if (it.isString) it.content else it.contentOrNull } ?: e.toString()

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
