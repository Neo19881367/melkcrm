package ir.melkcrm.backend

import ir.melkcrm.core.api.*
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.model.UserRole
import java.util.UUID

class AuthService(private val db: Db, private val cfg: AppConfig, private val passwords: Passwords, private val tokens: Tokens, private val clock: () -> Long) {

    fun login(login: String, password: String): TokenResponse {
        // Failures are committed before the error is raised, so lockout counters persist.
        val outcome: Any = db.tx { c ->
            val now = clock()
            val row = UserRepo.byLogin(c, login)
            // Always run bcrypt so response time does not reveal whether the login exists.
            val ok = passwords.verify(password, row?.passwordHash)
            when {
                row == null || !row.user.active -> ApiException(401, "invalid_credentials")
                row.lockedUntil != null && row.lockedUntil > now -> ApiException(423, "account_locked")
                !ok -> {
                    UserRepo.recordFailure(c, row.user.id, cfg.loginAttemptsBeforeLock, now + cfg.lockMinutes * 60_000)
                    AuditRepo.log(c, row.user.agencyId, row.user.id, "USER", row.user.id, AuditAction.LOGIN, now, "failed")
                    ApiException(401, "invalid_credentials")
                }
                else -> {
                    UserRepo.recordSuccess(c, row.user.id)
                    AuditRepo.log(c, row.user.agencyId, row.user.id, "USER", row.user.id, AuditAction.LOGIN, now, "success")
                    issue(c, row.user, row.tokenEpoch, UUID.randomUUID().toString(), now)
                }
            }
        }
        return outcome as? TokenResponse ?: throw outcome as ApiException
    }

    /** Rotates the refresh token. Presenting an already-used token revokes the whole token family. */
    fun refresh(refreshToken: String): TokenResponse {
        val outcome: Any = db.tx { c ->
            val now = clock()
            val row = RefreshTokenRepo.byHash(c, Tokens.sha256(refreshToken)) ?: return@tx ApiException(401, "invalid_token")
            if (row.revoked) {
                RefreshTokenRepo.revokeFamily(c, row.familyId)
                return@tx ApiException(401, "token_reused")
            }
            if (row.expiresAt < now) return@tx ApiException(401, "token_expired")
            val user = UserRepo.byId(c, row.userId)
            if (user == null || !user.user.active) return@tx ApiException(401, "invalid_token")
            RefreshTokenRepo.revoke(c, row.id)
            issue(c, user.user, user.tokenEpoch, row.familyId, now)
        }
        return outcome as? TokenResponse ?: throw outcome as ApiException
    }

    fun logout(refreshToken: String) = db.tx { c ->
        RefreshTokenRepo.byHash(c, Tokens.sha256(refreshToken))?.let { RefreshTokenRepo.revokeFamily(c, it.familyId) }
    }

    private fun issue(c: java.sql.Connection, user: AgencyUser, epoch: Int, family: String, now: Long): TokenResponse {
        val refresh = tokens.newRefreshToken()
        RefreshTokenRepo.insert(c, user.id, Tokens.sha256(refresh), family, tokens.refreshExpiry(now), now)
        return TokenResponse(tokens.access(user, epoch, now), refresh, cfg.accessTokenMinutes * 60, user)
    }

    /** Loads the current user for a validated JWT; inactive users and stale token epochs are rejected. */
    fun principalFor(userId: String, epoch: Int): UserPrincipal? = db.tx { c ->
        val row = UserRepo.byId(c, userId) ?: return@tx null
        if (!row.user.active || row.tokenEpoch != epoch) null else UserPrincipal(row.user, row.tokenEpoch)
    }

    fun changePassword(user: AgencyUser, current: String, new: String) = db.tx { c ->
        val row = UserRepo.byId(c, user.id) ?: throw ApiException(404, "not_found")
        if (!passwords.verify(current, row.passwordHash)) throw ApiException(403, "invalid_credentials")
        passwords.validateStrength(new)?.let { throw ApiException(400, it) }
        UserRepo.setPassword(c, user.id, passwords.hash(new))
        RefreshTokenRepo.revokeAllForUser(c, user.id)
        AuditRepo.log(c, user.agencyId, user.id, "USER", user.id, AuditAction.UPDATE, clock(), "password_changed")
    }

    /** Creates the first agency and owner account from environment variables, once. */
    fun bootstrap() {
        val name = cfg.bootstrapAgencyName
        val login = cfg.bootstrapAdminLogin
        val pass = cfg.bootstrapAdminPassword
        if (name == null || login == null || pass == null) return
        db.tx { c ->
            if (AgencyRepo.count(c) > 0) return@tx
            val now = clock()
            val agencyId = UUID.randomUUID().toString()
            AgencyRepo.create(c, agencyId, name, now)
            val admin = AgencyUser(UUID.randomUUID().toString(), agencyId, "مدیر", role = UserRole.OWNER_ADMIN, createdAt = now)
            UserRepo.insert(c, admin, login, passwords.hash(pass))
            AuditRepo.log(c, agencyId, null, "USER", admin.id, AuditAction.CREATE, now, "bootstrap")
        }
    }
}
