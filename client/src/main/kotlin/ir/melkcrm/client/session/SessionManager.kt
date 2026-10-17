package ir.melkcrm.client.session

import ir.melkcrm.client.remote.RemoteApi
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.client.remote.TokenStorage
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.store.OutboxEntry
import ir.melkcrm.client.store.RecordIndex
import ir.melkcrm.core.api.AgencyConfig
import ir.melkcrm.core.api.CreateUserRequest
import ir.melkcrm.core.api.UpdateUserRequest
import ir.melkcrm.core.matching.MatchingConfig
import ir.melkcrm.core.model.AgencySettings
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.UserRole
import ir.melkcrm.core.permissions.PermissionPolicy
import ir.melkcrm.core.sync.RecordCodec
import ir.melkcrm.core.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** The device-only identity used before signing in to an agency server. */
object LocalIdentity {
    const val AGENCY_ID = "local-agency"
    const val USER_ID = "local-user"
    fun user(displayName: String) = AgencyUser(USER_ID, AGENCY_ID, displayName, role = UserRole.OWNER_ADMIN, createdAt = 0)
}

/**
 * [authExpired]: the device still holds this agency's data but the server no longer accepts the
 * stored tokens; the user must sign in again before anything syncs.
 */
data class Session(val user: AgencyUser, val serverUrl: String? = null, val authExpired: Boolean = false) {
    val signedIn: Boolean get() = serverUrl != null
}

/** Agency-wide configuration and the user directory, cached locally. */
data class AgencyState(
    val settings: AgencySettings = AgencySettings(),
    val matching: MatchingConfig = MatchingConfig(),
    val users: List<AgencyUser> = emptyList(),
) {
    val policy: PermissionPolicy get() = PermissionPolicy(settings)
    fun user(id: String?): AgencyUser? = id?.let { i -> users.firstOrNull { it.id == i } }
    fun teamOf(agentId: String?): String? = user(agentId)?.teamId
}

sealed interface SignInResult {
    data class Success(val user: AgencyUser, val adoptedRecords: Int) : SignInResult
    data class Failure(val code: String) : SignInResult
}

/**
 * Holds who is using the app and against which server. Without a server the app works on this
 * device only, as an owner/admin of a private local agency. Signing in adopts locally created records
 * into the agency (sample data is discarded, never uploaded); signing out wipes the local database.
 */
class SessionManager(
    private val store: LocalStore,
    private val tokens: TokenStorage,
    private val apiFactory: (serverUrl: String) -> RemoteApi,
    private val localUserName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = RecordCodec.json
    private val _session = MutableStateFlow(Session(LocalIdentity.user(localUserName)))
    val session: StateFlow<Session> = _session.asStateFlow()
    private val _agency = MutableStateFlow(AgencyState(users = listOf(LocalIdentity.user(localUserName))))
    val agency: StateFlow<AgencyState> = _agency.asStateFlow()
    private var api: RemoteApi? = null

    val user: AgencyUser get() = _session.value.user

    fun api(): RemoteApi? = api

    suspend fun restore() {
        val server = store.meta(KEY_SERVER)
        val user = store.meta(KEY_USER)?.let { runCatching { json.decodeFromString(AgencyUser.serializer(), it) }.getOrNull() }
        if (server != null && user != null) {
            api = apiFactory(server)
            _session.value = Session(user, server, authExpired = tokens.load() == null)
        } else {
            api = null
            _session.value = Session(LocalIdentity.user(localUserName))
        }
        val config = store.meta(KEY_CONFIG)?.let { runCatching { json.decodeFromString(AgencyConfig.serializer(), it) }.getOrNull() }
        val users = store.meta(KEY_USERS)?.let { runCatching { json.decodeFromString(ListSerializer(AgencyUser.serializer()), it) }.getOrNull() }
        _agency.value = AgencyState(
            settings = config?.settings ?: AgencySettings(),
            matching = config?.matching ?: MatchingConfig(),
            users = (users ?: emptyList()).ifEmpty { listOf(_session.value.user) },
        )
    }

    suspend fun signIn(serverUrl: String, login: String, password: String): SignInResult {
        val url = serverUrl.trim().trimEnd('/')
        if (!url.startsWith("https://") && !url.startsWith("http://")) return SignInResult.Failure("invalid_url")
        val remote = apiFactory(url)
        val response = try {
            remote.login(login.trim(), password)
        } catch (e: RemoteException) {
            return SignInResult.Failure(if (e.isNetwork) "network" else e.code)
        }
        val user = response.user
        val previous = _session.value
        val sameAccount = previous.signedIn && previous.serverUrl == url && previous.user.id == user.id
        val adopted = store.transaction {
            // Never mix two accounts' data on one device.
            if (previous.signedIn && !sameAccount) store.clearAll()
            store.deleteSampleRecords()
            val n = if (!previous.signedIn) adoptLocalRecords(user) else 0
            store.setMeta(KEY_SERVER, url)
            store.setMeta(KEY_LOGIN, login.trim())
            store.setMeta(KEY_USER, json.encodeToString(AgencyUser.serializer(), user))
            if (!sameAccount) store.setMeta(KEY_CURSOR, null)
            n
        }
        api = remote
        _session.value = Session(user, url)
        if (!sameAccount) _agency.value = AgencyState(users = listOf(user))
        runCatching { refreshAgency() }
        return SignInResult.Success(user, adopted)
    }

    /** Re-labels records created in device-only mode so they belong to the signed-in agency and user. */
    private suspend fun adoptLocalRecords(user: AgencyUser): Int {
        var count = 0
        for (type in ir.melkcrm.core.sync.EntityType.entries) {
            for (rec in store.all(type, includeDeleted = true)) {
                if (rec.agencyId != LocalIdentity.AGENCY_ID) continue
                var obj = json.parseToJsonElement(rec.json).jsonObject
                if ("agencyId" in obj) obj = RecordIndex.withField(obj, "agencyId", JsonPrimitive(user.agencyId))
                for (field in listOf("assignedAgentId", "agentId", "authorId")) {
                    val v = (obj[field] as? JsonPrimitive)?.content
                    if (v == LocalIdentity.USER_ID) obj = RecordIndex.withField(obj, field, JsonPrimitive(user.id))
                }
                val assigned = if (rec.assignedAgentId == LocalIdentity.USER_ID) user.id else rec.assignedAgentId
                store.put(rec.copy(agencyId = user.agencyId, assignedAgentId = assigned, json = obj.toString(), version = 0, syncState = SyncState.PENDING))
                store.putOutbox(OutboxEntry(rec.type, rec.id, UUID.randomUUID().toString(), 0, clock()))
                count++
            }
        }
        return count
    }

    /** Fetches agency configuration and users from the server and caches them. */
    suspend fun refreshAgency() {
        val remote = api ?: return
        val config = remote.agencyConfig()
        val users = runCatching { remote.users() }.getOrElse { listOf(user) }
        store.setMeta(KEY_CONFIG, json.encodeToString(AgencyConfig.serializer(), config))
        store.setMeta(KEY_USERS, json.encodeToString(ListSerializer(AgencyUser.serializer()), users))
        // Pick up role or team changes made by an administrator.
        users.firstOrNull { it.id == user.id }?.let { me ->
            if (me != user) {
                store.setMeta(KEY_USER, json.encodeToString(AgencyUser.serializer(), me))
                _session.value = _session.value.copy(user = me)
            }
        }
        _agency.value = AgencyState(config.settings, config.matching, users)
    }

    /**
     * Saves agency configuration. Signed in, the server validates the change and rejects it unless the
     * user is an administrator; in device-only mode it is stored locally.
     */
    suspend fun updateAgencyConfig(settings: AgencySettings, matching: MatchingConfig) {
        val errors = matching.validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val saved = api?.updateAgencyConfig(AgencyConfig(settings, matching)) ?: AgencyConfig(settings, matching)
        store.setMeta(KEY_CONFIG, json.encodeToString(AgencyConfig.serializer(), saved))
        _agency.value = _agency.value.copy(settings = saved.settings, matching = saved.matching)
    }

    /**
     * Changes the signed-in user's password. The server then signs the account out everywhere; this
     * device signs straight back in with the new password so work continues without interruption.
     */
    suspend fun changePassword(current: String, new: String) {
        val remote = api ?: throw RemoteException(401, "not_signed_in")
        remote.changePassword(current, new)
        val login = store.meta(KEY_LOGIN)
        if (login == null || runCatching { remote.login(login, new) }.isFailure) onSessionExpired()
    }

    /** Creates a server account; the server allows it only for users who manage users. */
    suspend fun createUser(request: CreateUserRequest): AgencyUser {
        val remote = api ?: throw RemoteException(401, "not_signed_in")
        return remote.createUser(request).also { runCatching { refreshAgency() } }
    }

    /** Changes another account's name, role, team or active flag (server-checked). Deactivating signs that user out. */
    suspend fun updateUser(id: String, request: UpdateUserRequest): AgencyUser {
        val remote = api ?: throw RemoteException(401, "not_signed_in")
        return remote.updateUser(id, request).also { runCatching { refreshAgency() } }
    }

    /** Device-only mode: the agent list used for assignment (sample agents, the local user). */
    suspend fun setLocalUsers(users: List<AgencyUser>) {
        check(api == null) { "Users are managed on the server" }
        val all = (listOf(user) + users).distinctBy { it.id }
        store.setMeta(KEY_USERS, json.encodeToString(ListSerializer(AgencyUser.serializer()), all))
        _agency.value = _agency.value.copy(users = all)
    }

    suspend fun signOut() {
        runCatching { api?.logout() }
        tokens.save(null)
        store.clearAll()
        api = null
        _session.value = Session(LocalIdentity.user(localUserName))
        _agency.value = AgencyState(users = listOf(_session.value.user))
    }

    /** Called when the server says the session is no longer valid (password changed, user deactivated). */
    suspend fun onSessionExpired() {
        tokens.save(null)
        if (_session.value.signedIn) _session.value = _session.value.copy(authExpired = true)
    }

    companion object {
        const val KEY_SERVER = "session.server"
        const val KEY_USER = "session.user"
        const val KEY_LOGIN = "session.login"
        const val KEY_CONFIG = "agency.config"
        const val KEY_USERS = "agency.users"
        const val KEY_CURSOR = "sync.cursor"
    }
}
