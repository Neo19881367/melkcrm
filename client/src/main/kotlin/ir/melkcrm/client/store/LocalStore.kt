package ir.melkcrm.client.store

import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.SyncRecord
import ir.melkcrm.core.sync.SyncState
import ir.melkcrm.core.time.EpochMillis
import kotlinx.coroutines.flow.Flow

/**
 * One locally stored record. The entity itself is kept as JSON ([json]); the other columns are
 * copies of the fields the app filters on, so the Android implementation can index them in SQLite.
 */
data class LocalRecord(
    val type: EntityType,
    val id: String,
    val agencyId: String,
    val assignedAgentId: String?,
    /** Server version this copy is based on; 0 means the server has never seen it. */
    val version: Long,
    val updatedAt: EpochMillis,
    val deleted: Boolean,
    /** Full entity JSON, including the confidential fields this user is allowed to hold. */
    val json: String,
    val syncState: SyncState,
    /** Synthetic development data; never uploaded to a server. */
    val sample: Boolean = false,
    val customerId: String? = null,
    val propertyId: String? = null,
    val status: String? = null,
    /** Normalized text used by full-text search. */
    val searchText: String = "",
)

/** A local change waiting to be pushed. One entry per record; later edits replace [changeId]. */
data class OutboxEntry(
    val type: EntityType,
    val id: String,
    val changeId: String,
    /** The server version the change was made on top of. */
    val baseVersion: Long,
    val queuedAt: EpochMillis,
    val attempts: Int = 0,
    val nextAttemptAt: EpochMillis = 0,
    val lastError: String? = null,
    /** The server refused the change (validation or permission); it is not retried until edited again. */
    val rejected: Boolean = false,
)

/** A record changed both locally and on the server. Kept until the agent chooses a version. */
data class ConflictEntry(
    val type: EntityType,
    val id: String,
    val localJson: String,
    /** The server's current copy, or null when it no longer exists or is no longer visible to this user. */
    val server: SyncRecord?,
    val detectedAt: EpochMillis,
)

enum class ImageUploadState { PENDING, UPLOADED, FAILED }

/** A property photo. Local files live in app-private storage; [remoteId] is the server image id once uploaded. */
data class LocalImage(
    val id: String,
    val propertyId: String,
    val localPath: String?,
    val remoteId: String?,
    val caption: String? = null,
    val sortOrder: Int = 0,
    val createdAt: EpochMillis,
    val uploadState: ImageUploadState,
    val lastError: String? = null,
)

/**
 * Local persistence contract. Implemented with Room on Android and in memory for tests; both are
 * checked by the same LocalStoreContract tests. All "observe" flows emit the current value first and
 * again after every change. [transaction] rolls back every change made inside it when the block throws.
 */
interface LocalStore {
    suspend fun <R> transaction(block: suspend () -> R): R

    suspend fun get(type: EntityType, id: String): LocalRecord?
    /** Records of [type]; deleted (soft-deleted) records only when [includeDeleted]. */
    suspend fun all(type: EntityType, includeDeleted: Boolean = false): List<LocalRecord>
    suspend fun byCustomer(type: EntityType, customerId: String): List<LocalRecord>
    suspend fun byProperty(type: EntityType, propertyId: String): List<LocalRecord>
    fun observe(type: EntityType): Flow<List<LocalRecord>>
    suspend fun put(record: LocalRecord)
    /**
     * Removes the local copy entirely, together with any change queued for it (used when the server says
     * the record is gone or this user may no longer see it).
     */
    suspend fun remove(type: EntityType, id: String)
    /** Ids of non-deleted records of [type] whose search text matches every word of [normalizedQuery] as a prefix. */
    suspend fun searchIds(type: EntityType, normalizedQuery: String): List<String>

    suspend fun outbox(): List<OutboxEntry>
    suspend fun outboxEntry(type: EntityType, id: String): OutboxEntry?
    suspend fun putOutbox(entry: OutboxEntry)
    suspend fun removeOutbox(type: EntityType, id: String)
    fun observeOutboxCount(): Flow<Int>

    suspend fun conflicts(): List<ConflictEntry>
    fun observeConflicts(): Flow<List<ConflictEntry>>
    suspend fun putConflict(conflict: ConflictEntry)
    suspend fun removeConflict(type: EntityType, id: String)

    suspend fun addAudit(event: AuditEvent)
    suspend fun audit(entityId: String): List<AuditEvent>

    suspend fun meta(key: String): String?
    suspend fun setMeta(key: String, value: String?)
    fun observeMeta(key: String): Flow<String?>

    suspend fun images(propertyId: String): List<LocalImage>
    fun observeImages(propertyId: String): Flow<List<LocalImage>>
    suspend fun pendingImages(): List<LocalImage>
    suspend fun putImage(image: LocalImage)
    suspend fun removeImage(id: String)

    /** Deletes sample records together with their outbox entries. */
    suspend fun deleteSampleRecords()
    /** Wipes everything (sign-out). */
    suspend fun clearAll()
}

/** Matches the Android FTS behaviour: every query word must prefix-match a word of the text. */
fun searchMatches(searchText: String, normalizedQuery: String): Boolean {
    val words = searchText.split(' ')
    return normalizedQuery.split(' ').filter { it.isNotBlank() }.all { q -> words.any { it.startsWith(q) } }
}
