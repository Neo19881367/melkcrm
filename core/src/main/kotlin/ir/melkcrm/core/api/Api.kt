package ir.melkcrm.core.api

import ir.melkcrm.core.matching.MatchingConfig
import ir.melkcrm.core.model.AgencySettings
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.UserRole
import ir.melkcrm.core.sync.PushChange
import ir.melkcrm.core.sync.PushResult
import kotlinx.serialization.Serializable

/** Wire format of the backend REST API (/api/v1), shared by the server and the app. */

@Serializable data class LoginRequest(val login: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val user: AgencyUser)
@Serializable data class ErrorResponse(val error: String, val detail: String? = null)

@Serializable data class PushRequest(val changes: List<PushChange>)
@Serializable data class PushResponse(val results: List<PushResult>)

@Serializable data class CreateUserRequest(
    val login: String,
    val password: String,
    val displayName: String,
    val role: UserRole,
    val teamId: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

@Serializable data class UpdateUserRequest(val displayName: String, val role: UserRole, val teamId: String? = null, val active: Boolean)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class AgencyConfig(val settings: AgencySettings, val matching: MatchingConfig)
@Serializable data class ImageUploadResponse(val id: String, val sizeBytes: Long)
