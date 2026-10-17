package ir.melkcrm.app.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One synchronizable record: the entity as JSON plus indexed copies of the fields the app filters on
 * (see ir.melkcrm.client.store.LocalRecord). Enum values are stored by name.
 */
@Entity(
    tableName = "records",
    primaryKeys = ["type", "id"],
    indices = [Index("type", "deleted"), Index("type", "customerId"), Index("type", "propertyId"), Index("sample")],
)
data class RecordEntity(
    val type: String,
    val id: String,
    val agencyId: String,
    val assignedAgentId: String?,
    val version: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val json: String,
    val syncState: String,
    val sample: Boolean,
    val customerId: String?,
    val propertyId: String?,
    val status: String?,
    val searchText: String,
)

/** Full-text index over [RecordEntity.searchText]; kept in step by RoomLocalStore. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["type", "recordId"])
@Entity(tableName = "record_fts")
data class RecordFtsEntity(
    val type: String,
    val recordId: String,
    val text: String,
)

@Entity(tableName = "outbox", primaryKeys = ["type", "id"])
data class OutboxEntity(
    val type: String,
    val id: String,
    val changeId: String,
    val baseVersion: Long,
    val queuedAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val rejected: Boolean,
)

@Entity(tableName = "conflicts", primaryKeys = ["type", "id"])
data class ConflictEntity(
    val type: String,
    val id: String,
    val localJson: String,
    /** The server's SyncRecord as JSON, or null when it no longer exists for this user. */
    val serverJson: String?,
    val detectedAt: Long,
)

@Entity(tableName = "audit", indices = [Index("entityId")])
data class AuditEntity(
    @PrimaryKey val id: String,
    val entityId: String,
    val at: Long,
    /** ir.melkcrm.core.model.AuditEvent as JSON. */
    val json: String,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "images", indices = [Index("propertyId")])
data class ImageEntity(
    @PrimaryKey val id: String,
    val propertyId: String,
    val localPath: String?,
    val remoteId: String?,
    val caption: String?,
    val sortOrder: Int,
    val createdAt: Long,
    val uploadState: String,
    val lastError: String?,
)

data class SearchRow(val id: String, val searchText: String)
data class KeyRow(val type: String, val id: String)
