package ir.melkcrm.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local database. The schema is exported to app/schemas (see build.gradle.kts) so later versions
 * ship explicit migrations; there is deliberately no destructive fallback, because unsynchronized
 * offline changes must never be thrown away by an upgrade.
 */
@Database(
    entities = [RecordEntity::class, RecordFtsEntity::class, OutboxEntity::class, ConflictEntity::class, AuditEntity::class, MetaEntity::class, ImageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CrmDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun outbox(): OutboxDao
    abstract fun conflicts(): ConflictDao
    abstract fun audit(): AuditDao
    abstract fun meta(): MetaDao
    abstract fun images(): ImageDao

    companion object {
        fun open(context: Context, name: String = "melkcrm.db"): CrmDatabase =
            Room.databaseBuilder(context.applicationContext, CrmDatabase::class.java, name).build()
    }
}
