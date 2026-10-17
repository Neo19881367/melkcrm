package ir.melkcrm.core.sync

import ir.melkcrm.core.time.EpochMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Entity types that synchronize through the generic record API. */
@Serializable
enum class EntityType(val isInventory: Boolean = false) {
    CUSTOMER, BUYER_REQUIREMENT, SELLER_PROFILE, PROPERTY(isInventory = true), PROPERTY_CONTACT,
    ACTIVITY, TASK, APPOINTMENT, DEAL, OFFER, TRANSACTION, COMMISSION, MATCH_RECORD, NOTE,
}

/**
 * A synchronized record. [data] holds shareable fields; [confidential] holds fields only users with
 * confidential access may receive (seller minimum price, confidential notes). The server strips
 * [confidential] for everyone else.
 */
@Serializable
data class SyncRecord(
    val type: EntityType,
    val id: String,
    val agencyId: String,
    val assignedAgentId: String? = null,
    /** Server version; 0 for a record the server has never seen. */
    val version: Long,
    val updatedAt: EpochMillis,
    val deleted: Boolean = false,
    val data: JsonObject,
    val confidential: JsonObject? = null,
)

@Serializable
data class PushChange(
    val record: SyncRecord,
    /** The server version this change was made on top of (0 = new record). */
    val baseVersion: Long,
    /** Client-side idempotency key; retrying the same change is harmless. */
    val changeId: String,
)

@Serializable enum class PushOutcome { APPLIED, CONFLICT, REJECTED, DUPLICATE }

@Serializable
data class PushResult(
    val changeId: String,
    val id: String,
    val outcome: PushOutcome,
    val newVersion: Long? = null,
    /** Current server state when CONFLICT, so the client can show both versions. */
    val server: SyncRecord? = null,
    val error: String? = null,
)

@Serializable
data class PullResponse(val records: List<SyncRecord>, val cursor: Long, val hasMore: Boolean)

/** Optimistic concurrency: a write is accepted only if it was based on the current server version. */
object ConflictDetector {
    sealed interface Decision {
        data object Apply : Decision
        data object Conflict : Decision
    }

    fun decide(serverVersion: Long?, baseVersion: Long): Decision = when {
        serverVersion == null -> if (baseVersion == 0L) Decision.Apply else Decision.Conflict // deleted on server or never existed
        serverVersion == baseVersion -> Decision.Apply
        else -> Decision.Conflict // server has newer data: never overwrite silently
    }
}

@Serializable enum class ConflictResolution { KEEP_SERVER, KEEP_LOCAL, MERGED }

/** Field-level view of a conflict for the resolution screen. */
object ConflictDiff {
    data class FieldDiff(val field: String, val local: String?, val server: String?)

    fun diff(local: JsonObject, server: JsonObject): List<FieldDiff> =
        (local.keys + server.keys).sorted().mapNotNull { k ->
            val l = local[k]?.toString()
            val s = server[k]?.toString()
            if (l == s) null else FieldDiff(k, l, s)
        }

    /** Re-bases the local change on the server version so it can be pushed again (explicit user choice). */
    fun rebase(local: SyncRecord, server: SyncRecord, resolution: ConflictResolution, merged: JsonObject? = null): PushChange? = when (resolution) {
        ConflictResolution.KEEP_SERVER -> null
        ConflictResolution.KEEP_LOCAL -> PushChange(local.copy(version = server.version), server.version, java.util.UUID.randomUUID().toString())
        ConflictResolution.MERGED -> PushChange(local.copy(version = server.version, data = requireNotNull(merged)), server.version, java.util.UUID.randomUUID().toString())
    }
}

@Serializable enum class SyncState { SYNCED, PENDING, SYNCING, CONFLICT, FAILED, LOCAL_ONLY }

/** Exponential backoff for retrying failed pushes. */
object RetryPolicy {
    fun delayMillis(attempt: Int, baseMillis: Long = 5_000, maxMillis: Long = 30 * 60_000L): Long {
        val exp = baseMillis * (1L shl attempt.coerceIn(0, 20))
        return exp.coerceAtMost(maxMillis)
    }

    fun shouldGiveUp(attempt: Int, maxAttempts: Int = 12) = attempt >= maxAttempts
}
