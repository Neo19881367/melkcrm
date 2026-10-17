package ir.melkcrm.client.store

import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.sync.EntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** [LocalStore] kept in memory. Used by tests and previews; Android uses the Room implementation. */
class InMemoryLocalStore : LocalStore {
    private data class Key(val type: EntityType, val id: String)

    private val records = MutableStateFlow<Map<Key, LocalRecord>>(emptyMap())
    private val outbox = MutableStateFlow<Map<Key, OutboxEntry>>(emptyMap())
    private val conflicts = MutableStateFlow<Map<Key, ConflictEntry>>(emptyMap())
    private val audit = MutableStateFlow<List<AuditEvent>>(emptyList())
    private val meta = MutableStateFlow<Map<String, String>>(emptyMap())
    private val images = MutableStateFlow<Map<String, LocalImage>>(emptyMap())

    private var depth = 0

    /** Restores every table when [block] throws, like a database transaction. Not meant for concurrent writers. */
    override suspend fun <R> transaction(block: suspend () -> R): R {
        val snapshot = listOf(records.value, outbox.value, conflicts.value, audit.value, meta.value, images.value)
        depth++
        try {
            return block()
        } catch (e: Throwable) {
            if (depth == 1) restore(snapshot)
            throw e
        } finally {
            depth--
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restore(s: List<Any>) {
        records.value = s[0] as Map<Key, LocalRecord>
        outbox.value = s[1] as Map<Key, OutboxEntry>
        conflicts.value = s[2] as Map<Key, ConflictEntry>
        audit.value = s[3] as List<AuditEvent>
        meta.value = s[4] as Map<String, String>
        images.value = s[5] as Map<String, LocalImage>
    }

    override suspend fun get(type: EntityType, id: String) = records.value[Key(type, id)]

    override suspend fun all(type: EntityType, includeDeleted: Boolean) =
        records.value.values.filter { it.type == type && (includeDeleted || !it.deleted) }

    override suspend fun byCustomer(type: EntityType, customerId: String) =
        records.value.values.filter { it.type == type && it.customerId == customerId && !it.deleted }

    override suspend fun byProperty(type: EntityType, propertyId: String) =
        records.value.values.filter { it.type == type && it.propertyId == propertyId && !it.deleted }

    override fun observe(type: EntityType): Flow<List<LocalRecord>> =
        records.map { m -> m.values.filter { it.type == type && !it.deleted } }.distinctUntilChanged()

    override suspend fun put(record: LocalRecord) = records.update { it + (Key(record.type, record.id) to record) }

    override suspend fun remove(type: EntityType, id: String) {
        records.update { it - Key(type, id) }
        outbox.update { it - Key(type, id) }
    }

    override suspend fun searchIds(type: EntityType, normalizedQuery: String): List<String> =
        all(type).filter { searchMatches(it.searchText, normalizedQuery) }.map { it.id }

    override suspend fun outbox() = outbox.value.values.sortedBy { it.queuedAt }
    override suspend fun outboxEntry(type: EntityType, id: String) = outbox.value[Key(type, id)]
    override suspend fun putOutbox(entry: OutboxEntry) = outbox.update { it + (Key(entry.type, entry.id) to entry) }
    override suspend fun removeOutbox(type: EntityType, id: String) = outbox.update { it - Key(type, id) }
    override fun observeOutboxCount(): Flow<Int> = outbox.map { it.size }.distinctUntilChanged()

    override suspend fun conflicts() = conflicts.value.values.sortedBy { it.detectedAt }
    override fun observeConflicts(): Flow<List<ConflictEntry>> = conflicts.map { it.values.sortedBy { c -> c.detectedAt } }
    override suspend fun putConflict(conflict: ConflictEntry) = conflicts.update { it + (Key(conflict.type, conflict.id) to conflict) }
    override suspend fun removeConflict(type: EntityType, id: String) = conflicts.update { it - Key(type, id) }

    override suspend fun addAudit(event: AuditEvent) = audit.update { it + event }
    override suspend fun audit(entityId: String) = audit.value.filter { it.entityId == entityId }.sortedByDescending { it.at }

    override suspend fun meta(key: String) = meta.value[key]
    override suspend fun setMeta(key: String, value: String?) = meta.update { if (value == null) it - key else it + (key to value) }
    override fun observeMeta(key: String): Flow<String?> = meta.map { it[key] }.distinctUntilChanged()

    override suspend fun images(propertyId: String) = images.value.values.filter { it.propertyId == propertyId }.sortedBy { it.sortOrder }
    override fun observeImages(propertyId: String): Flow<List<LocalImage>> =
        images.map { m -> m.values.filter { it.propertyId == propertyId }.sortedBy { it.sortOrder } }
    override suspend fun pendingImages() = images.value.values.filter { it.uploadState != ImageUploadState.UPLOADED }
    override suspend fun putImage(image: LocalImage) = images.update { it + (image.id to image) }
    override suspend fun removeImage(id: String) = images.update { it - id }

    override suspend fun deleteSampleRecords() {
        val sampleKeys = records.value.filterValues { it.sample }.keys
        records.update { it - sampleKeys }
        outbox.update { it - sampleKeys }
        conflicts.update { it - sampleKeys }
    }

    override suspend fun clearAll() {
        records.value = emptyMap()
        outbox.value = emptyMap()
        conflicts.value = emptyMap()
        audit.value = emptyList()
        meta.value = emptyMap()
        images.value = emptyMap()
    }
}
