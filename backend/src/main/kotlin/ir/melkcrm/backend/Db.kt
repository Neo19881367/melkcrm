package ir.melkcrm.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

class Db(val dataSource: DataSource) : AutoCloseable {
    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try {
            val r = block(c)
            c.commit()
            r
        } catch (t: Throwable) {
            c.rollback()
            throw t
        }
    }

    companion object {
        fun connect(cfg: AppConfig): Db {
            val hc = HikariConfig().apply {
                jdbcUrl = cfg.databaseUrl
                cfg.databaseUser?.let { username = it }
                cfg.databasePassword?.let { password = it }
                maximumPoolSize = cfg.databasePoolSize
                isAutoCommit = true
            }
            val ds = HikariDataSource(hc)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Db(ds)
        }
    }
}

fun Connection.prepare(sql: String, vararg args: Any?): PreparedStatement {
    val ps = prepareStatement(sql)
    args.forEachIndexed { i, a ->
        val idx = i + 1
        when (a) {
            null -> ps.setNull(idx, Types.NULL)
            is String -> ps.setString(idx, a)
            is Long -> ps.setLong(idx, a)
            is Int -> ps.setInt(idx, a)
            is Boolean -> ps.setBoolean(idx, a)
            else -> ps.setString(idx, a.toString())
        }
    }
    return ps
}

fun Connection.update(sql: String, vararg args: Any?): Int = prepare(sql, *args).use { it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *args).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }
