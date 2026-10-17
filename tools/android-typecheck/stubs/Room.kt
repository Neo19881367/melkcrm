package androidx.room

import android.content.Context
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS) annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val ignoredColumns: Array<String> = [],
)
annotation class Index(vararg val value: String, val name: String = "", val unique: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class ColumnInfo(val name: String = "", val index: Boolean = false)
@Target(AnnotationTarget.CLASS) annotation class Fts4(
    val tokenizer: String = FtsOptions.TOKENIZER_SIMPLE,
    val tokenizerArgs: Array<String> = [],
    val contentEntity: KClass<*> = Any::class,
    val languageId: String = "",
    val notIndexed: Array<String> = [],
    val prefix: IntArray = [],
)
object FtsOptions {
    const val TOKENIZER_SIMPLE = "simple"
    const val TOKENIZER_PORTER = "porter"
    const val TOKENIZER_ICU = "icu"
    const val TOKENIZER_UNICODE61 = "unicode61"
}
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Upsert(val entity: KClass<*> = Any::class)
@Target(AnnotationTarget.FUNCTION) annotation class Insert(val entity: KClass<*> = Any::class, val onConflict: Int = 3)
@Target(AnnotationTarget.FUNCTION) annotation class Transaction
@Target(AnnotationTarget.CLASS) annotation class Database(
    val entities: Array<KClass<*>> = [],
    val views: Array<KClass<*>> = [],
    val version: Int,
    val exportSchema: Boolean = true,
)

abstract class RoomDatabase {
    open fun close() {}
    class Builder<T : RoomDatabase> internal constructor() {
        fun allowMainThreadQueries(): Builder<T> = this
        fun build(): T = TODO()
    }
}

object Room {
    @JvmStatic fun <T : RoomDatabase> databaseBuilder(context: Context, klass: Class<T>, name: String?): RoomDatabase.Builder<T> = TODO()
    @JvmStatic fun <T : RoomDatabase> inMemoryDatabaseBuilder(context: Context, klass: Class<T>): RoomDatabase.Builder<T> = TODO()
}

suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()
