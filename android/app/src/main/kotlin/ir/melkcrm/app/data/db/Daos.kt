package ir.melkcrm.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE type = :type AND id = :id")
    suspend fun get(type: String, id: String): RecordEntity?

    @Query("SELECT * FROM records WHERE type = :type AND (:includeDeleted OR deleted = 0)")
    suspend fun all(type: String, includeDeleted: Boolean): List<RecordEntity>

    @Query("SELECT * FROM records WHERE type = :type AND customerId = :customerId AND deleted = 0")
    suspend fun byCustomer(type: String, customerId: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE type = :type AND propertyId = :propertyId AND deleted = 0")
    suspend fun byProperty(type: String, propertyId: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE type = :type AND deleted = 0")
    fun observe(type: String): Flow<List<RecordEntity>>

    @Upsert
    suspend fun upsert(record: RecordEntity)

    @Query("DELETE FROM records WHERE type = :type AND id = :id")
    suspend fun delete(type: String, id: String)

    @Query("SELECT id, searchText FROM records WHERE type = :type AND deleted = 0")
    suspend fun searchable(type: String): List<SearchRow>

    @Query("SELECT type, id FROM records WHERE sample = 1")
    suspend fun sampleKeys(): List<KeyRow>

    @Query("DELETE FROM records WHERE sample = 1")
    suspend fun deleteSamples()

    @Query("DELETE FROM records")
    suspend fun clear()

    // ---- full-text index

    @Query("DELETE FROM record_fts WHERE type = :type AND recordId = :id")
    suspend fun deleteFts(type: String, id: String)

    @Query("INSERT INTO record_fts (type, recordId, text) VALUES (:type, :id, :text)")
    suspend fun insertFts(type: String, id: String, text: String)

    @Query("SELECT recordId FROM record_fts WHERE record_fts MATCH :match AND type = :type")
    suspend fun matchFts(type: String, match: String): List<String>

    @Query("DELETE FROM record_fts")
    suspend fun clearFts()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY queuedAt")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE type = :type AND id = :id")
    suspend fun get(type: String, id: String): OutboxEntity?

    @Upsert
    suspend fun upsert(entry: OutboxEntity)

    @Query("DELETE FROM outbox WHERE type = :type AND id = :id")
    suspend fun delete(type: String, id: String)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY detectedAt")
    suspend fun all(): List<ConflictEntity>

    @Query("SELECT * FROM conflicts ORDER BY detectedAt")
    fun observe(): Flow<List<ConflictEntity>>

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Query("DELETE FROM conflicts WHERE type = :type AND id = :id")
    suspend fun delete(type: String, id: String)

    @Query("DELETE FROM conflicts")
    suspend fun clear()
}

@Dao
interface AuditDao {
    @Upsert
    suspend fun insert(event: AuditEntity)

    @Query("SELECT * FROM audit WHERE entityId = :entityId ORDER BY at DESC")
    suspend fun forEntity(entityId: String): List<AuditEntity>

    @Query("DELETE FROM audit")
    suspend fun clear()
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entry: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM meta")
    suspend fun clear()
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM images WHERE propertyId = :propertyId ORDER BY sortOrder")
    suspend fun forProperty(propertyId: String): List<ImageEntity>

    @Query("SELECT * FROM images WHERE propertyId = :propertyId ORDER BY sortOrder")
    fun observe(propertyId: String): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE uploadState != 'UPLOADED'")
    suspend fun pending(): List<ImageEntity>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun get(id: String): ImageEntity?

    @Upsert
    suspend fun upsert(image: ImageEntity)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM images")
    suspend fun all(): List<ImageEntity>

    @Query("DELETE FROM images")
    suspend fun clear()
}
