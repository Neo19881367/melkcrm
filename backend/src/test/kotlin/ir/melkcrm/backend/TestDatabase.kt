package ir.melkcrm.backend

import java.sql.DriverManager
import java.util.UUID

/**
 * A fresh, empty database for one test. H2 in PostgreSQL mode by default, so the tests need nothing
 * installed. Set MELK_TEST_POSTGRES_URL to the JDBC URL of a PostgreSQL server whose user may create
 * databases (for example jdbc:postgresql://localhost:5432/postgres?user=postgres&password=...) to run
 * the same tests against PostgreSQL; each test gets its own database, dropped when the JVM exits.
 */
object TestDatabase {
    private val postgres: String? = System.getenv("MELK_TEST_POSTGRES_URL")?.takeIf { it.isNotBlank() }
    private val created = mutableListOf<String>()

    init {
        if (postgres != null) {
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching {
                    DriverManager.getConnection(postgres).use { c ->
                        synchronized(created) { created.toList() }.forEach { name ->
                            c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
                        }
                    }
                }
            })
        }
    }

    fun freshUrl(): String {
        val admin = postgres ?: return "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val name = "melk_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(admin).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        synchronized(created) { created += name }
        // Same server and credentials, different database: replace the path of the JDBC URL.
        val m = Regex("^(jdbc:postgresql://[^/?]+)(/[^?]*)?(\\?.*)?$").matchEntire(admin)
            ?: error("MELK_TEST_POSTGRES_URL must look like jdbc:postgresql://host:port/db?user=...")
        return m.groupValues[1] + "/" + name + m.groupValues[3]
    }
}
