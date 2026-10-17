package ir.melkcrm.client.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import ir.melkcrm.core.api.AgencyConfig
import ir.melkcrm.core.api.ChangePasswordRequest
import ir.melkcrm.core.api.CreateUserRequest
import ir.melkcrm.core.api.ErrorResponse
import ir.melkcrm.core.api.ImageUploadResponse
import ir.melkcrm.core.api.LoginRequest
import ir.melkcrm.core.api.PushRequest
import ir.melkcrm.core.api.PushResponse
import ir.melkcrm.core.api.RefreshRequest
import ir.melkcrm.core.api.TokenResponse
import ir.melkcrm.core.api.UpdateUserRequest
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.sync.PullResponse
import ir.melkcrm.core.sync.PushChange
import ir.melkcrm.core.sync.PushResult
import ir.melkcrm.core.sync.RecordCodec
import ir.melkcrm.core.time.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Ktor implementation of [RemoteApi]. Works with any engine (the Android engine on Android, the test engine in
 * backend tests). The access token is refreshed once on a 401; refresh tokens rotate on every use.
 * JSON is encoded explicitly, so the client needs no content-negotiation plugin.
 */
class KtorRemoteApi(
    baseUrl: String,
    private val http: HttpClient,
    private val tokens: TokenStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) : RemoteApi {
    private val json = RecordCodec.json
    private val root = baseUrl.trimEnd('/') + "/api/v1"
    private val refreshLock = Mutex()

    private fun <T> body(serializer: KSerializer<T>, value: T) =
        TextContent(json.encodeToString(serializer, value), ContentType.Application.Json)

    private suspend fun raw(method: HttpMethod, path: String, token: String?, configure: HttpRequestBuilder.() -> Unit): HttpResponse =
        try {
            http.request(root + path) {
                this.method = method
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException(0, "network", e.message, e)
        }

    private suspend fun check(res: HttpResponse): HttpResponse {
        if (res.status.isSuccess()) return res
        val err = runCatching { json.decodeFromString(ErrorResponse.serializer(), res.bodyAsText()) }.getOrNull()
        throw RemoteException(res.status.value, err?.error ?: "http_${res.status.value}", err?.detail)
    }

    private suspend fun authed(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val current = tokens.load() ?: throw RemoteException(401, "not_signed_in")
        val token = if (current.accessExpiresAt - 30_000 <= clock()) refresh(current.accessToken) else current.accessToken
        val res = raw(method, path, token, configure)
        if (res.status != HttpStatusCode.Unauthorized) return check(res)
        return check(raw(method, path, refresh(token), configure))
    }

    /** Refreshes unless another call already did; returns the access token to use. */
    private suspend fun refresh(failedAccessToken: String): String = refreshLock.withLock {
        val stored = tokens.load() ?: throw RemoteException(401, "not_signed_in")
        if (stored.accessToken != failedAccessToken && stored.accessExpiresAt - 30_000 > clock()) return stored.accessToken
        val res = raw(HttpMethod.Post, "/auth/refresh", null) { setBody(body(RefreshRequest.serializer(), RefreshRequest(stored.refreshToken))) }
        if (res.status == HttpStatusCode.Unauthorized) {
            tokens.save(null)
            throw RemoteException(401, "session_expired")
        }
        val t = json.decodeFromString(TokenResponse.serializer(), check(res).bodyAsText())
        tokens.save(StoredTokens(t.accessToken, t.refreshToken, clock() + t.expiresInSeconds * 1000))
        t.accessToken
    }

    private suspend fun <T> decode(res: HttpResponse, serializer: KSerializer<T>): T = json.decodeFromString(serializer, res.bodyAsText())

    override suspend fun login(login: String, password: String): TokenResponse {
        val res = check(raw(HttpMethod.Post, "/auth/login", null) { setBody(body(LoginRequest.serializer(), LoginRequest(login, password))) })
        val t = decode(res, TokenResponse.serializer())
        tokens.save(StoredTokens(t.accessToken, t.refreshToken, clock() + t.expiresInSeconds * 1000))
        return t
    }

    override suspend fun logout() {
        val stored = tokens.load() ?: return
        tokens.save(null)
        runCatching { raw(HttpMethod.Post, "/auth/logout", null) { setBody(body(RefreshRequest.serializer(), RefreshRequest(stored.refreshToken))) } }
    }

    override suspend fun me(): AgencyUser = decode(authed(HttpMethod.Get, "/me"), AgencyUser.serializer())

    override suspend fun changePassword(current: String, new: String) {
        authed(HttpMethod.Post, "/me/password") { setBody(body(ChangePasswordRequest.serializer(), ChangePasswordRequest(current, new))) }
    }

    override suspend fun push(changes: List<PushChange>): List<PushResult> =
        decode(authed(HttpMethod.Post, "/sync/push") { setBody(body(PushRequest.serializer(), PushRequest(changes))) }, PushResponse.serializer()).results

    override suspend fun pull(cursor: Long, limit: Int): PullResponse =
        decode(authed(HttpMethod.Get, "/sync/pull?cursor=$cursor&limit=$limit"), PullResponse.serializer())

    override suspend fun agencyConfig(): AgencyConfig = decode(authed(HttpMethod.Get, "/agency/config"), AgencyConfig.serializer())

    override suspend fun updateAgencyConfig(config: AgencyConfig): AgencyConfig =
        decode(authed(HttpMethod.Put, "/agency/config") { setBody(body(AgencyConfig.serializer(), config)) }, AgencyConfig.serializer())

    override suspend fun users(): List<AgencyUser> = decode(authed(HttpMethod.Get, "/users"), ListSerializer(AgencyUser.serializer()))

    override suspend fun createUser(request: CreateUserRequest): AgencyUser =
        decode(authed(HttpMethod.Post, "/users") { setBody(body(CreateUserRequest.serializer(), request)) }, AgencyUser.serializer())

    override suspend fun updateUser(id: String, request: UpdateUserRequest): AgencyUser =
        decode(authed(HttpMethod.Patch, "/users/${id.encodeURLParameter()}") { setBody(body(UpdateUserRequest.serializer(), request)) }, AgencyUser.serializer())

    override suspend fun audit(entityId: String?, limit: Int): List<AuditEvent> {
        val q = buildString {
            append("?limit=").append(limit)
            if (entityId != null) append("&entityId=").append(entityId.encodeURLParameter())
        }
        return decode(authed(HttpMethod.Get, "/audit$q"), ListSerializer(AuditEvent.serializer()))
    }

    override suspend fun uploadImage(propertyId: String, bytes: ByteArray, contentType: String): ImageUploadResponse =
        decode(
            authed(HttpMethod.Post, "/properties/${propertyId.encodeURLParameter()}/images") { setBody(ByteArrayContent(bytes, ContentType.parse(contentType))) },
            ImageUploadResponse.serializer(),
        )

    override suspend fun downloadImage(id: String): ByteArray = authed(HttpMethod.Get, "/images/${id.encodeURLParameter()}").bodyAsBytes()

    override suspend fun exportCustomersCsv(lang: AppLanguage): String =
        authed(HttpMethod.Get, "/export/customers.csv?lang=${lang.code}").bodyAsText()

    override suspend fun exportPropertiesCsv(lang: AppLanguage, unit: CurrencyUnit): String =
        authed(HttpMethod.Get, "/export/properties.csv?lang=${lang.code}&unit=${unit.name.lowercase()}").bodyAsText()
}
