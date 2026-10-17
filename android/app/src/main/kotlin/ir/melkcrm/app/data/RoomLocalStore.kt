package ir.melkcrm.app.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import ir.melkcrm.app.data.db.AuditEntity
import ir.melkcrm.app.data.db.ConflictEntity
import ir.melkcrm.app.data.db.CrmDatabase
import ir.melkcrm.app.data.db.ImageEntity
import ir.melkcrm.app.data.db.MetaEntity
import ir.melkcrm.app.data.db.OutboxEntity
import ir.melkcrm.app.data.db.RecordEntity
import ir.melkcrm.client.store.ConflictEntry
import ir.melkcrm.client.store.ImageUploadState
import ir.melkcrm.client.store.LocalImage
import ir.melkcrm.client.store.LocalRecord
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.store.OutboxEntry
import ir.melkcrm.client.store.searchMatches
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.RecordCodec
import ir.melkcrm.core.sync.SyncRecord
import ir.melkcrm.core.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [LocalStore] on Room. Behaviour matches InMemoryLocalStore, which the shared tests run against;
 * the full-text index narrows candidates and [searchMatches] applies the same word-prefix rule.
 */
class RoomLocalStore(private val db: CrmDatabase) : LocalStore {
    private val records = db.records()
    private val outboxDao = db.outbox()
    private val conflictDao = db.conflicts()
    private val auditDao = db.audit()
    private val metaDao = db.meta()
    private val imageDao = db.images()
    private val json = RecordCodec.json

    override suspend fun <R> transaction(block: suspend () -> R): R = db.withTransaction { block() }

    // ------------------------------------------------------------------ records

    override suspend fun get(type: EntityType, id: String): LocalRecord? = records.get(type.name, id)?.toModel()

    override suspend fun all(type: EntityType, includeDeleted: Boolean): List<LocalRecord> =
        records.all(type.name, includeDeleted).map { it.toModel() }

    override suspend fun byCustomer(type: EntityType, customerId: String) = records.byCustomer(type.name, customerId).map { it.toModel() }

    override suspend fun byProperty(type: EntityType, propertyId: String) = records.byProperty(type.name, propertyId).map { it.toModel() }

    override fun observe(type: EntityType): Flow<List<LocalRecord>> =
        records.observe(type.name).distinctUntilChanged().map { list -> list.map { it.toModel() } }

    override suspend fun put(record: LocalRecord) = db.withTransaction {
        records.upsert(record.toEntity())
        records.deleteFts(record.type.name, record.id)
        if (!record.deleted && record.searchText.isNotBlank()) records.insertFts(record.type.name, record.id, record.searchText)
    }

    override suspend fun remove(type: EntityType, id: String) = db.withTransaction {
        records.delete(type.name, id)
        records.deleteFts(type.name, id)
        outboxDao.delete(type.name, id)
    }

    override suspend fun searchIds(type: EntityType, normalizedQuery: String): List<String> {
        val words = normalizedQuery.split(' ').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "\"$it*\"" }
        val candidates = try {
            records.matchFts(type.name, match).toSet()
        } catch (e: SQLiteException) {
            null
        }
        // Same rule as the in-memory store, so search behaves identically on every platform.
        return records.searchable(type.name)
            .filter { (candidates == null || it.id in candidates) && searchMatches(it.searchText, normalizedQuery) }
            .map { it.id }
    }

    // ------------------------------------------------------------------ outbox

    override suspend fun outbox(): List<OutboxEntry> = outboxDao.all().map { it.toModel() }
    override suspend fun outboxEntry(type: EntityType, id: String): OutboxEntry? = outboxDao.get(type.name, id)?.toModel()
    override suspend fun putOutbox(entry: OutboxEntry) = outboxDao.upsert(entry.toEntity())
    override suspend fun removeOutbox(type: EntityType, id: String) = outboxDao.delete(type.name, id)
    override fun observeOutboxCount(): Flow<Int> = outboxDao.observeCount().distinctUntilChanged()

    // ------------------------------------------------------------------ conflicts

    override suspend fun conflicts(): List<ConflictEntry> = conflictDao.all().mapNotNull { it.toModel() }
    override fun observeConflicts(): Flow<List<ConflictEntry>> = conflictDao.observe().map { l -> l.mapNotNull { it.toModel() } }
    override suspend fun putConflict(conflict: ConflictEntry) = conflictDao.upsert(
        ConflictEntity(
            conflict.type.name, conflict.id, conflict.localJson,
            conflict.server?.let { json.encodeToString(SyncRecord.serializer(), it) }, conflict.detectedAt,
        ),
    )
    override suspend fun removeConflict(type: EntityType, id: String) = conflictDao.delete(type.name, id)

    // ------------------------------------------------------------------ audit and meta

    override suspend fun addAudit(event: AuditEvent) =
        auditDao.insert(AuditEntity(event.id, event.entityId, event.at, json.encodeToString(AuditEvent.serializer(), event)))

    override suspend fun audit(entityId: String): List<AuditEvent> =
        auditDao.forEntity(entityId).mapNotNull { runCatching { json.decodeFromString(AuditEvent.serializer(), it.json) }.getOrNull() }

    override suspend fun meta(key: String): String? = metaDao.get(key)
    override suspend fun setMeta(key: String, value: String?) = if (value == null) metaDao.delete(key) else metaDao.put(MetaEntity(key, value))
    override fun observeMeta(key: String): Flow<String?> = metaDao.observe(key).distinctUntilChanged()

    // ------------------------------------------------------------------ images

    override suspend fun images(propertyId: String): List<LocalImage> = imageDao.forProperty(propertyId).map { it.toModel() }
    override fun observeImages(propertyId: String): Flow<List<LocalImage>> = imageDao.observe(propertyId).map { l -> l.map { it.toModel() } }
    override suspend fun pendingImages(): List<LocalImage> = imageDao.pending().map { it.toModel() }
    override suspend fun putImage(image: LocalImage) = imageDao.upsert(image.toEntity())
    override suspend fun removeImage(id: String) = imageDao.delete(id)

    /** All image rows, so files can be deleted from disk when the data is wiped. */
    suspend fun allImages(): List<LocalImage> = imageDao.all().map { it.toModel() }

    // ------------------------------------------------------------------ housekeeping

    override suspend fun deleteSampleRecords() = db.withTransaction {
        for (k in records.sampleKeys()) {
            records.deleteFts(k.type, k.id)
            outboxDao.delete(k.type, k.id)
            conflictDao.delete(k.type, k.id)
        }
        records.deleteSamples()
    }

    override suspend fun clearAll() = db.withTransaction {
        records.clear()
        records.clearFts()
        outboxDao.clear()
        conflictDao.clear()
        auditDao.clear()
        metaDao.clear()
        imageDao.clear()
    }

    // ------------------------------------------------------------------ mapping

    private fun RecordEntity.toModel() = LocalRecord(
        type = EntityType.valueOf(type), id = id, agencyId = agencyId, assignedAgentId = assignedAgentId, version = version,
        updatedAt = updatedAt, deleted = deleted, json = json, syncState = SyncState.valueOf(syncState), sample = sample,
        customerId = customerId, propertyId = propertyId, status = status, searchText = searchText,
    )

    private fun LocalRecord.toEntity() = RecordEntity(
        type.name, id, agencyId, assignedAgentId, version, updatedAt, deleted, json, syncState.name, sample,
        customerId, propertyId, status, searchText,
    )

    private fun OutboxEntity.toModel() = OutboxEntry(EntityType.valueOf(type), id, changeId, baseVersion, queuedAt, attempts, nextAttemptAt, lastError, rejected)

    private fun OutboxEntry.toEntity() = OutboxEntity(type.name, id, changeId, baseVersion, queuedAt, attempts, nextAttemptAt, lastError, rejected)

    private fun ConflictEntity.toModel(): ConflictEntry? {
        val t = runCatching { EntityType.valueOf(type) }.getOrNull() ?: return null
        val server = serverJson?.let { runCatching { json.decodeFromString(SyncRecord.serializer(), it) }.getOrNull() }
        return ConflictEntry(t, id, localJson, server, detectedAt)
    }

    private fun ImageEntity.toModel() = LocalImage(
        id, propertyId, localPath, remoteId, caption, sortOrder, createdAt,
        runCatching { ImageUploadState.valueOf(uploadState) }.getOrDefault(ImageUploadState.PENDING), lastError,
    )

    private fun LocalImage.toEntity() = ImageEntity(id, propertyId, localPath, remoteId, caption, sortOrder, createdAt, uploadState.name, lastError)
}
