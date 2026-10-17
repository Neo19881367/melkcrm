package ir.melkcrm.client

import ir.melkcrm.client.remote.InMemoryTokenStorage
import ir.melkcrm.client.remote.RemoteApi
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.client.store.InMemoryLocalStore
import ir.melkcrm.client.sync.ImageFiles
import ir.melkcrm.core.api.AgencyConfig
import ir.melkcrm.core.api.CreateUserRequest
import ir.melkcrm.core.api.ImageUploadResponse
import ir.melkcrm.core.api.TokenResponse
import ir.melkcrm.core.api.UpdateUserRequest
import ir.melkcrm.core.matching.MatchingConfig
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.sync.*
import ir.melkcrm.core.time.AppLanguage

object Fx {
    /** 2026-10-17 08:00 Tehran. */
    const val NOW = 1_792_224_000_000L
    const val AGENCY = "A"
    fun user(id: String, role: UserRole, team: String? = null) = AgencyUser(id, AGENCY, id, role = role, teamId = team, createdAt = 0)
    val admin = user("admin", UserRole.OWNER_ADMIN)
    val manager = user("manager", UserRole.MANAGER, "west")
    val agent1 = user("agent1", UserRole.AGENT, "west")
    val agent2 = user("agent2", UserRole.AGENT, "east")
}

class MemoryImages(val files: MutableMap<String, ByteArray> = mutableMapOf()) : ImageFiles {
    override suspend fun read(path: String) = files[path]
    override fun contentType(path: String) = "image/jpeg"
}

/** Scriptable stand-in for the server. By default every push is applied and pulls are empty. */
class FakeRemoteApi(var me: AgencyUser) : RemoteApi {
    val pushed = mutableListOf<PushChange>()
    var pushHandler: (PushChange) -> PushResult = { c -> PushResult(c.changeId, c.record.id, PushOutcome.APPLIED, newVersion = c.baseVersion + 1) }
    var failPush: RemoteException? = null
    val pages = ArrayDeque<PullResponse>()
    var config = AgencyConfig(AgencySettings(), MatchingConfig())
    var users = listOf(Fx.admin, Fx.manager, Fx.agent1, Fx.agent2)
    val uploaded = mutableListOf<String>()
    var exported = 0

    override suspend fun login(login: String, password: String): TokenResponse {
        if (password != "secret-pass") throw RemoteException(401, "invalid_credentials")
        return TokenResponse("access", "refresh", 900, me)
    }
    override suspend fun logout() = Unit
    override suspend fun me() = me
    override suspend fun changePassword(current: String, new: String) = Unit
    override suspend fun push(changes: List<PushChange>): List<PushResult> {
        failPush?.let { throw it }
        pushed += changes
        return changes.map(pushHandler)
    }
    override suspend fun pull(cursor: Long, limit: Int): PullResponse = pages.removeFirstOrNull() ?: PullResponse(emptyList(), cursor, false)
    override suspend fun agencyConfig() = config
    override suspend fun updateAgencyConfig(config: AgencyConfig): AgencyConfig {
        if (me.role != UserRole.OWNER_ADMIN) throw RemoteException(403, "forbidden")
        this.config = config
        return config
    }
    override suspend fun users() = users
    override suspend fun createUser(request: CreateUserRequest) = throw RemoteException(403, "forbidden")
    override suspend fun updateUser(id: String, request: UpdateUserRequest) = throw RemoteException(403, "forbidden")
    override suspend fun audit(entityId: String?, limit: Int) = emptyList<AuditEvent>()
    override suspend fun uploadImage(propertyId: String, bytes: ByteArray, contentType: String): ImageUploadResponse {
        uploaded += propertyId
        return ImageUploadResponse("img-${uploaded.size}", bytes.size.toLong())
    }
    override suspend fun downloadImage(id: String) = ByteArray(0)
    override suspend fun exportCustomersCsv(lang: AppLanguage): String { exported++; return "server-csv" }
    override suspend fun exportPropertiesCsv(lang: AppLanguage, unit: CurrencyUnit): String { exported++; return "server-csv" }
}

class TestCrm(val now: Long = Fx.NOW) {
    var clock = now
    val store = InMemoryLocalStore()
    val tokens = InMemoryTokenStorage()
    var api: FakeRemoteApi? = null
    val images = MemoryImages()
    val crm = Crm(store, tokens, { api ?: error("no api") }, images, "Me", clock = { clock })

    /** Signs in to the fake server as [user]. */
    suspend fun signIn(user: AgencyUser): FakeRemoteApi {
        val a = FakeRemoteApi(user)
        api = a
        val r = crm.session.signIn("https://crm.example.ir", user.id, "secret-pass")
        check(r is ir.melkcrm.client.session.SignInResult.Success) { r.toString() }
        return a
    }

    fun customer(id: String = crm.writer.newId(), agent: String? = crm.session.user.id, mobile: String? = "09121234567", name: String = "علی رضایی") =
        Customer(id = id, agencyId = crm.session.user.agencyId, fullName = name, mobile = mobile, roles = setOf(CustomerRole.BUYER),
            assignedAgentId = agent, city = "تهران", registeredAt = clock, updatedAt = clock)
}
