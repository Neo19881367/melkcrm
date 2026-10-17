package ir.melkcrm.client.remote

import ir.melkcrm.core.api.AgencyConfig
import ir.melkcrm.core.api.CreateUserRequest
import ir.melkcrm.core.api.ImageUploadResponse
import ir.melkcrm.core.api.TokenResponse
import ir.melkcrm.core.api.UpdateUserRequest
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.sync.PullResponse
import ir.melkcrm.core.sync.PushChange
import ir.melkcrm.core.sync.PushResult
import ir.melkcrm.core.time.AppLanguage

/**
 * The backend API as the app uses it. Every authorization decision is made by the server; a
 * [RemoteException] with status 403 is the normal answer to an action the user's role does not allow.
 */
interface RemoteApi {
    suspend fun login(login: String, password: String): TokenResponse
    suspend fun logout()
    suspend fun me(): AgencyUser
    suspend fun changePassword(current: String, new: String)

    suspend fun push(changes: List<PushChange>): List<PushResult>
    suspend fun pull(cursor: Long, limit: Int = 500): PullResponse

    suspend fun agencyConfig(): AgencyConfig
    suspend fun updateAgencyConfig(config: AgencyConfig): AgencyConfig
    suspend fun users(): List<AgencyUser>
    suspend fun createUser(request: CreateUserRequest): AgencyUser
    suspend fun updateUser(id: String, request: UpdateUserRequest): AgencyUser
    suspend fun audit(entityId: String?, limit: Int = 200): List<AuditEvent>

    suspend fun uploadImage(propertyId: String, bytes: ByteArray, contentType: String): ImageUploadResponse
    suspend fun downloadImage(id: String): ByteArray

    suspend fun exportCustomersCsv(lang: AppLanguage): String
    suspend fun exportPropertiesCsv(lang: AppLanguage, unit: CurrencyUnit): String
}

/** [status] 0 means the server could not be reached. [code] is the server's error code. */
class RemoteException(val status: Int, val code: String, val detail: String? = null, cause: Throwable? = null) :
    Exception(if (detail != null) "$code: $detail" else code, cause) {
    val isNetwork: Boolean get() = status == 0
    val isAuth: Boolean get() = status == 401
    val isForbidden: Boolean get() = status == 403
}

/** Tokens are stored encrypted by the platform implementation (Android Keystore). */
data class StoredTokens(val accessToken: String, val refreshToken: String, val accessExpiresAt: Long)

interface TokenStorage {
    suspend fun load(): StoredTokens?
    suspend fun save(tokens: StoredTokens?)
}

class InMemoryTokenStorage(private var tokens: StoredTokens? = null) : TokenStorage {
    override suspend fun load() = tokens
    override suspend fun save(tokens: StoredTokens?) { this.tokens = tokens }
}
