package ir.melkcrm.backend

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.UserRole
import ir.melkcrm.core.validation.PasswordPolicy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID

data class UserRow(
    val user: AgencyUser,
    val login: String,
    val passwordHash: String,
    val failedLogins: Int,
    val lockedUntil: Long?,
    val tokenEpoch: Int,
)

/** Authenticated caller, re-validated against the database on every request. */
data class UserPrincipal(val user: AgencyUser, val tokenEpoch: Int)

class Passwords(private val cost: Int) {
    private val dummy = BCrypt.withDefaults().hashToString(cost, "dummy-password-for-timing".toCharArray())

    fun hash(password: String): String = BCrypt.withDefaults().hashToString(cost, password.toCharArray())

    fun verify(password: String, hash: String?): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash ?: dummy).verified && hash != null

    fun validateStrength(password: String): String? = PasswordPolicy.problem(password)
}

class Tokens(private val cfg: AppConfig, private val clock: () -> Long = System::currentTimeMillis) {
    private val algorithm = Algorithm.HMAC256(cfg.jwtSecret)
    private val random = SecureRandom()
    private val javaClock = object : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
        override fun instant(): java.time.Instant = java.time.Instant.ofEpochMilli(clock())
    }
    val verifier = (JWT.require(algorithm).withIssuer(cfg.jwtIssuer).withAudience(cfg.jwtAudience) as com.auth0.jwt.JWTVerifier.BaseVerification).build(javaClock)

    fun access(user: AgencyUser, tokenEpoch: Int, now: Long): String = JWT.create()
        .withIssuer(cfg.jwtIssuer)
        .withAudience(cfg.jwtAudience)
        .withSubject(user.id)
        .withClaim("agency", user.agencyId)
        .withClaim("role", user.role.name)
        .withClaim("epoch", tokenEpoch)
        .withJWTId(UUID.randomUUID().toString())
        .withIssuedAt(Date(now))
        .withExpiresAt(Date(now + cfg.accessTokenMinutes * 60_000))
        .sign(algorithm)

    fun newRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun refreshExpiry(now: Long) = now + cfg.refreshTokenDays * 86_400_000L

    companion object {
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

fun roleOf(s: String) = UserRole.valueOf(s)
