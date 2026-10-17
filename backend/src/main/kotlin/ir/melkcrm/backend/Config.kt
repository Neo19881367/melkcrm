package ir.melkcrm.backend

/**
 * All configuration comes from environment variables (see backend/.env.example).
 * Secrets are never compiled into the backend or the Android app.
 */
data class AppConfig(
    val port: Int = 8080,
    val databaseUrl: String,
    val databaseUser: String? = null,
    val databasePassword: String? = null,
    val databasePoolSize: Int = 10,
    val jwtSecret: String,
    val jwtIssuer: String = "melkcrm",
    val jwtAudience: String = "melkcrm-app",
    val accessTokenMinutes: Long = 15,
    val refreshTokenDays: Long = 30,
    val imageDir: String = "./data/images",
    val maxImageBytes: Long = 5L * 1024 * 1024,
    val bootstrapAgencyName: String? = null,
    val bootstrapAdminLogin: String? = null,
    val bootstrapAdminPassword: String? = null,
    val loginAttemptsBeforeLock: Int = 5,
    val lockMinutes: Long = 15,
    val bcryptCost: Int = 12,
    val loginRateLimitPerMinute: Int = 20,
) {
    init {
        require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
        bootstrapAdminPassword?.let { require(it.length >= 12) { "BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters" } }
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun req(k: String) = env[k]?.takeIf { it.isNotBlank() } ?: error("Missing required environment variable $k")
            return AppConfig(
                port = env["PORT"]?.toInt() ?: 8080,
                databaseUrl = req("DATABASE_URL"),
                databaseUser = env["DATABASE_USER"],
                databasePassword = env["DATABASE_PASSWORD"],
                databasePoolSize = env["DATABASE_POOL_SIZE"]?.toInt() ?: 10,
                jwtSecret = req("JWT_SECRET"),
                jwtIssuer = env["JWT_ISSUER"] ?: "melkcrm",
                jwtAudience = env["JWT_AUDIENCE"] ?: "melkcrm-app",
                accessTokenMinutes = env["ACCESS_TOKEN_MINUTES"]?.toLong() ?: 15,
                refreshTokenDays = env["REFRESH_TOKEN_DAYS"]?.toLong() ?: 30,
                imageDir = env["IMAGE_DIR"] ?: "./data/images",
                bootstrapAgencyName = env["BOOTSTRAP_AGENCY_NAME"],
                bootstrapAdminLogin = env["BOOTSTRAP_ADMIN_LOGIN"],
                bootstrapAdminPassword = env["BOOTSTRAP_ADMIN_PASSWORD"],
            )
        }
    }
}
