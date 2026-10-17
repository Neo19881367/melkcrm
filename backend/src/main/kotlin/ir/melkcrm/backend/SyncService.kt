package ir.melkcrm.backend

import ir.melkcrm.core.export.Exports
import ir.melkcrm.core.model.*
import ir.melkcrm.core.permissions.Access
import ir.melkcrm.core.permissions.PermissionPolicy
import ir.melkcrm.core.permissions.RecordScope
import ir.melkcrm.core.sync.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.validation.BuyerRequirementValidator
import ir.melkcrm.core.validation.CustomerValidator
import ir.melkcrm.core.validation.OfferValidator
import ir.melkcrm.core.validation.PropertyValidator
import ir.melkcrm.core.validation.ValidationResult
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection

/**
 * Server-side enforcement of agency isolation, role permissions, confidential-field protection,
 * validation and optimistic concurrency. The client is never trusted for any of these.
 */
class SyncService(private val db: Db, private val clock: () -> Long) {
    private val json = RecordCodec.json

    /** Types whose deletion needs the DELETE_RECORDS permission. Everything is soft-deleted. */
    private val protectedDeletes = setOf(EntityType.CUSTOMER, EntityType.PROPERTY, EntityType.DEAL, EntityType.OFFER, EntityType.TRANSACTION, EntityType.COMMISSION)
    /** Types whose field-level history is kept in the audit log. */
    private val historyTypes = setOf(EntityType.CUSTOMER, EntityType.PROPERTY, EntityType.OFFER, EntityType.TRANSACTION, EntityType.DEAL, EntityType.SELLER_PROFILE, EntityType.COMMISSION)

    fun policyFor(c: Connection, agencyId: String): PermissionPolicy =
        PermissionPolicy(AgencyRepo.get(c, agencyId)?.settings ?: AgencySettings())

    private fun scopeOf(r: SyncRecord, teamId: String?) = RecordScope(r.agencyId, r.assignedAgentId, teamId, r.type.isInventory)

    fun push(user: AgencyUser, changes: List<PushChange>): List<PushResult> = changes.map { ch ->
        try {
            db.tx { c -> pushOne(c, user, ch) }
        } catch (e: java.sql.SQLException) {
            // e.g. a concurrent insert of the same new record
            PushResult(ch.changeId, ch.record.id, PushOutcome.CONFLICT, error = "concurrent_write")
        }
    }

    private fun reject(c: Connection, user: AgencyUser, ch: PushChange, error: String, now: Long): PushResult {
        if (error == "forbidden" || error == "wrong_agency" || error.startsWith("reassign") || error.startsWith("delete")) {
            AuditRepo.log(c, user.agencyId, user.id, ch.record.type.name, ch.record.id, AuditAction.PERMISSION_DENIED, now, error)
        }
        return PushResult(ch.changeId, ch.record.id, PushOutcome.REJECTED, error = error)
    }

    private fun pushOne(c: Connection, user: AgencyUser, ch: PushChange): PushResult {
        val now = clock()
        AppliedChangeRepo.get(c, ch.changeId)?.let { stored ->
            return json.decodeFromString(PushResult.serializer(), stored).copy(outcome = PushOutcome.DUPLICATE)
        }
        val r = ch.record
        if (r.agencyId != user.agencyId) return reject(c, user, ch, "wrong_agency", now)
        val policy = policyFor(c, user.agencyId)

        val assignedTeam = r.assignedAgentId?.let { agentId ->
            val agent = UserRepo.byId(c, agentId)
            if (agent == null || agent.user.agencyId != user.agencyId) return reject(c, user, ch, "unknown_agent", now)
            agent.user.teamId
        }
        val existing = RecordRepo.getForUpdate(c, r.agencyId, r.type, r.id)

        if (ConflictDetector.decide(existing?.record?.version, ch.baseVersion) == ConflictDetector.Decision.Conflict) {
            val server = existing?.let { view(policy, user, it) }?.takeIf { !it.deleted || existing.record.deleted }
            return PushResult(ch.changeId, r.id, PushOutcome.CONFLICT, server = server)
        }

        val newScope = scopeOf(r, assignedTeam)
        if (existing == null) {
            if (!policy.canWrite(user, newScope)) return reject(c, user, ch, "forbidden", now)
        } else {
            val oldScope = scopeOf(existing.record, existing.teamId)
            if (!policy.canWrite(user, oldScope)) return reject(c, user, ch, "forbidden", now)
            if (existing.record.assignedAgentId != r.assignedAgentId) {
                val claimingUnassigned = existing.record.assignedAgentId == null && r.assignedAgentId == user.id
                if (!claimingUnassigned && !policy.canReassign(user, oldScope)) return reject(c, user, ch, "reassign_forbidden", now)
            }
            if (r.deleted && !existing.record.deleted && r.type in protectedDeletes && !policy.canDelete(user, oldScope) && !isMergeRetirement(r, user, policy)) {
                return reject(c, user, ch, "delete_forbidden", now)
            }
        }
        if (existing == null && r.deleted && r.type in protectedDeletes && !policy.canDelete(user, newScope)) return reject(c, user, ch, "delete_forbidden", now)

        // Users without confidential access can neither read nor overwrite confidential fields.
        val canConf = policy.canSeeConfidential(user, existing?.let { scopeOf(it.record, it.teamId) } ?: newScope)
        val confidential = if (canConf) r.confidential else existing?.record?.confidential
        val toStore = r.copy(confidential = confidential)

        if (!r.deleted) validate(toStore, now)?.let { return reject(c, user, ch, it, now) }

        val newVersion = if (existing == null) RecordRepo.insert(c, toStore, assignedTeam, user.id)
        else RecordRepo.update(c, toStore, assignedTeam, existing.record.version, user.id)
            ?: return PushResult(ch.changeId, r.id, PushOutcome.CONFLICT)

        val action = when {
            existing == null -> AuditAction.CREATE
            r.deleted && !existing.record.deleted -> AuditAction.DELETE
            else -> AuditAction.UPDATE
        }
        val changes = if (r.type in historyTypes) diff(existing?.record, toStore) else emptyMap()
        AuditRepo.log(c, user.agencyId, user.id, r.type.name, r.id, action, now, changes = changes)

        val result = PushResult(ch.changeId, r.id, PushOutcome.APPLIED, newVersion = newVersion)
        AppliedChangeRepo.put(c, ch.changeId, user.agencyId, user.id, json.encodeToString(PushResult.serializer(), result), now)
        return result
    }

    /** A customer retired by a merge is soft-deleted with mergedIntoId set; MERGE_CUSTOMERS covers that. */
    private fun isMergeRetirement(r: SyncRecord, user: AgencyUser, policy: PermissionPolicy): Boolean =
        r.type == EntityType.CUSTOMER && policy.canMerge(user) &&
            r.data["mergedIntoId"]?.jsonPrimitive?.contentOrNull != null

    private fun diff(old: SyncRecord?, new: SyncRecord): Map<String, List<String?>> {
        val out = linkedMapOf<String, List<String?>>()
        val keys = (old?.data?.keys.orEmpty() + new.data.keys)
        for (k in keys) {
            val a = old?.data?.get(k)?.toString()
            val b = new.data[k]?.toString()
            if (a != b && k != "updatedAt" && k != "version") out[k] = listOf(a?.take(200), b?.take(200))
        }
        if (old?.confidential != new.confidential) out["confidential"] = listOf("***", "***")
        if (old?.assignedAgentId != new.assignedAgentId) out["assignedAgentId"] = listOf(old?.assignedAgentId, new.assignedAgentId)
        return out
    }

    private fun validate(r: SyncRecord, now: Long): String? {
        val merged = RecordCodec.merge(r.data, r.confidential)
        merged["id"]?.jsonPrimitive?.contentOrNull?.let { if (it != r.id) return "id_mismatch" }
        merged["agencyId"]?.jsonPrimitive?.contentOrNull?.let { if (it != r.agencyId) return "agency_mismatch" }
        val today = AgencyTime.epochDayOf(now)
        val result: ValidationResult? = try {
            when (r.type) {
                EntityType.CUSTOMER -> CustomerValidator.validate(RecordCodec.decode(r.copy(confidential = null, data = merged)))
                EntityType.PROPERTY -> PropertyValidator.validate(RecordCodec.decode(r.copy(confidential = null, data = merged)), today)
                EntityType.BUYER_REQUIREMENT -> BuyerRequirementValidator.validate(RecordCodec.decode(r.copy(confidential = null, data = merged)))
                EntityType.OFFER -> OfferValidator.validate(RecordCodec.decode(r.copy(confidential = null, data = merged)))
                else -> {
                    RecordCodec.decode<Any>(r.copy(confidential = null, data = merged))
                    null
                }
            }
        } catch (e: Exception) {
            return "invalid_payload"
        }
        val err = result?.errors?.firstOrNull() ?: return null
        return "validation:${err.field}:${err.code}"
    }

    /** What [user] may see of a stored record: null means not even its existence. */
    private fun view(policy: PermissionPolicy, user: AgencyUser, s: StoredRecord): SyncRecord {
        val scope = scopeOf(s.record, s.teamId)
        val access = policy.access(user, scope)
        val tombstone = SyncRecord(s.record.type, s.record.id, s.record.agencyId, null, s.record.version, s.record.updatedAt, true, RecordCodec.empty)
        return when (access) {
            Access.NONE -> tombstone
            Access.MASKED -> if (s.record.type == EntityType.CUSTOMER) s.record.copy(data = RecordCodec.mask(s.record.data), confidential = null) else tombstone
            Access.READ, Access.WRITE -> if (policy.canSeeConfidential(user, scope)) s.record else s.record.copy(confidential = null)
        }
    }

    fun pull(user: AgencyUser, cursor: Long, limit: Int): PullResponse = db.tx { c ->
        val policy = policyFor(c, user.agencyId)
        val page = RecordRepo.changesSince(c, user.agencyId, cursor, limit + 1)
        val items = page.take(limit)
        PullResponse(items.map { view(policy, user, it) }, items.lastOrNull()?.serverSeq ?: cursor, page.size > limit)
    }

    fun get(user: AgencyUser, type: EntityType, id: String): SyncRecord? = db.tx { c ->
        val s = RecordRepo.get(c, user.agencyId, type, id) ?: return@tx null
        view(policyFor(c, user.agencyId), user, s).takeIf { !it.deleted || s.record.deleted }
    }

    fun exportCustomersCsv(user: AgencyUser, lang: AppLanguage): String = db.tx { c ->
        val policy = policyFor(c, user.agencyId)
        if (!policy.canExport(user)) throw ApiException(403, "export_forbidden")
        val agency = AgencyRepo.get(c, user.agencyId)
        val users = UserRepo.list(c, user.agencyId).associate { it.user.id to it.user.displayName }
        val rows = RecordRepo.allOfType(c, user.agencyId, EntityType.CUSTOMER).mapNotNull { s ->
            val access = policy.access(user, scopeOf(s.record, s.teamId))
            if (access == Access.NONE) return@mapNotNull null
            Exports.CustomerRow(RecordCodec.decode(s.record.copy(confidential = null)), contactVisible = access >= Access.READ)
        }
        AuditRepo.log(c, user.agencyId, user.id, "CUSTOMER", "*", AuditAction.EXPORT, clock(), "csv rows=${rows.size}")
        Exports.customers(rows, users, lang, agency?.settings?.customStatuses.orEmpty())
    }

    /** Properties the user can read; confidential notes are never part of the export. */
    fun exportPropertiesCsv(user: AgencyUser, lang: AppLanguage, unit: CurrencyUnit): String = db.tx { c ->
        val policy = policyFor(c, user.agencyId)
        if (!policy.canExport(user)) throw ApiException(403, "export_forbidden")
        val users = UserRepo.list(c, user.agencyId).associate { it.user.id to it.user.displayName }
        val props = RecordRepo.allOfType(c, user.agencyId, EntityType.PROPERTY)
            .filter { policy.canRead(user, scopeOf(it.record, it.teamId)) }
            .map { RecordCodec.decode<Property>(it.record.copy(confidential = null)) }
        AuditRepo.log(c, user.agencyId, user.id, "PROPERTY", "*", AuditAction.EXPORT, clock(), "csv rows=${props.size}")
        Exports.properties(props, users, lang, unit)
    }
}
