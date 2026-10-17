package ir.melkcrm.backend

import ir.melkcrm.core.api.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.sync.*
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendTest {
    private var now = 1_792_224_000_000L
    private val cfg = AppConfig(
        databaseUrl = TestDatabase.freshUrl(),
        jwtSecret = "test-secret-that-is-long-enough-for-hs256-0123456789",
        bcryptCost = 4,
        imageDir = Files.createTempDirectory("img").toString(),
        loginRateLimitPerMinute = 10_000,
        databasePoolSize = 4,
    )
    private val services = buildServices(cfg, clock = { now })

    @AfterTest
    fun closeDatabase() = services.db.close()
    private val password = "correct horse battery"

    init {
        services.db.tx { c ->
            AgencyRepo.create(c, "A", "آژانس الف", now)
            AgencyRepo.create(c, "B", "آژانس ب", now)
            fun u(id: String, agency: String, role: UserRole, team: String? = null) =
                UserRepo.insert(c, AgencyUser(id, agency, id, role = role, teamId = team, createdAt = now), "$id@test", services.passwords.hash(password))
            u("admin", "A", UserRole.OWNER_ADMIN)
            u("manager", "A", UserRole.MANAGER, "west")
            u("agent1", "A", UserRole.AGENT, "west")
            u("agent2", "A", UserRole.AGENT, "east")
            u("adminB", "B", UserRole.OWNER_ADMIN)
        }
    }

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(cfg, services) }
        val client = createClient { install(ContentNegotiation) { json(RecordCodec.json) } }
        block(client)
    }

    private suspend fun HttpClient.login(user: String, pass: String = password) =
        post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("$user@test", pass)) }

    private suspend fun HttpClient.token(user: String): String = login(user).body<TokenResponse>().accessToken

    private suspend fun HttpClient.push(token: String, vararg changes: PushChange): List<PushResult> {
        val res = post("/api/v1/sync/push") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(PushRequest(changes.toList())) }
        check(res.status == HttpStatusCode.OK) { "push failed: ${res.status} ${res.bodyAsText()}" }
        return res.body<PushResponse>().results
    }

    private suspend fun HttpClient.pull(token: String, cursor: Long = 0): PullResponse =
        get("/api/v1/sync/pull?cursor=$cursor") { bearerAuth(token) }.body()

    private fun customer(id: String, agent: String?, agency: String = "A", mobile: String = "09121234567") =
        Customer(id = id, agencyId = agency, fullName = "علی رضایی", mobile = mobile, roles = setOf(CustomerRole.BUYER), assignedAgentId = agent, registeredAt = now, updatedAt = now)

    private fun customerChange(c: Customer, base: Long = 0, changeId: String = UUID.randomUUID().toString()) =
        PushChange(RecordCodec.encode(EntityType.CUSTOMER, c, c.id, c.agencyId, c.assignedAgentId, base, c.updatedAt), base, changeId)

    private fun property(id: String, agent: String?, notes: String? = "مالک عجله دارد") = Property(
        id = id, agencyId = "A", reference = "IR-000501", title = "آپارتمان", category = PropertyCategory.APARTMENT,
        dealTypes = setOf(DealType.SALE), askingPrice = Money.ofToman(12_000_000_000), city = "تهران", builtArea = 100.0,
        assignedAgentId = agent, status = PropertyStatus.AVAILABLE, confidentialNotes = notes, createdAt = now, updatedAt = now,
    )

    private fun propertyChange(p: Property, base: Long = 0) =
        PushChange(RecordCodec.encode(EntityType.PROPERTY, p, p.id, p.agencyId, p.assignedAgentId, base, p.updatedAt), base, UUID.randomUUID().toString())

    @Test fun loginLockoutAndMe() = test { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.login("agent1", "wrong").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("nobody").status)
        val t = client.token("agent1")
        val me = client.get("/api/v1/me") { bearerAuth(t) }.body<AgencyUser>()
        assertEquals("agent1", me.id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me").status)
        repeat(5) { client.login("agent2", "wrong") }
        assertEquals(HttpStatusCode.Locked, client.login("agent2").status)
        now += 16 * 60_000
        assertEquals(HttpStatusCode.OK, client.login("agent2").status)
    }

    @Test fun refreshTokensRotateAndReuseRevokesFamily() = test { client ->
        val first = client.login("agent1").body<TokenResponse>()
        val second = client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(first.refreshToken)) }.body<TokenResponse>()
        assertTrue(second.refreshToken != first.refreshToken)
        // Replaying the old token is treated as theft: the whole family is revoked.
        val replay = client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(first.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        val afterReuse = client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(second.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, afterReuse.status)
    }

    @Test fun agentsSeeOnlyTheirCustomers() = test { client ->
        val a1 = client.token("agent1")
        val a2 = client.token("agent2")
        val admin = client.token("admin")
        assertEquals(PushOutcome.APPLIED, client.push(a1, customerChange(customer("c1", "agent1"))).single().outcome)

        val mine = client.pull(a1).records.single()
        assertEquals("09121234567", mine.data["mobile"]?.jsonPrimitive?.content)
        val other = client.pull(a2).records.single()
        assertTrue(other.deleted, "other agents only get a tombstone")
        assertTrue(other.data.isEmpty())
        assertNull(other.assignedAgentId)
        assertFalse(client.pull(admin).records.single().deleted)
        val manager = client.token("manager")
        assertFalse(client.pull(manager).records.single().deleted, "manager supervises team west")
    }

    @Test fun agentCannotCreateRecordsForOthersOrReassign() = test { client ->
        val a1 = client.token("agent1")
        val r = client.push(a1, customerChange(customer("c2", "agent2"))).single()
        assertEquals(PushOutcome.REJECTED, r.outcome)
        assertEquals("forbidden", r.error)

        client.push(a1, customerChange(customer("c3", "agent1")))
        val reassign = client.push(a1, customerChange(customer("c3", "agent2"), base = 1)).single()
        assertEquals("reassign_forbidden", reassign.error)
        val manager = client.token("manager")
        assertEquals(PushOutcome.APPLIED, client.push(manager, customerChange(customer("c3", "manager"), base = 1)).single().outcome)
    }

    @Test fun confidentialFieldsAreWithheldAndProtected() = test { client ->
        val a1 = client.token("agent1")
        val a2 = client.token("agent2")
        val admin = client.token("admin")
        client.push(a1, propertyChange(property("p1", "agent1")))
        val seen = client.pull(a2).records.single()
        assertFalse(seen.deleted, "inventory is visible agency-wide")
        assertNull(seen.confidential)
        assertEquals("مالک عجله دارد", client.pull(admin).records.single().confidential!!["confidentialNotes"]!!.jsonPrimitive.content)
        // Read-only for other agents
        assertEquals("forbidden", client.push(a2, propertyChange(property("p1", "agent1", notes = null), base = 1)).single().error)

        // Unassigned pool property: agent2 may edit, but cannot see or wipe the confidential notes.
        client.push(admin, propertyChange(property("p2", null, notes = "حداقل قیمت ۱۱ میلیارد")))
        val upd = client.push(a2, propertyChange(property("p2", null, notes = null).copy(title = "ویرایش شده"), base = 1)).single()
        assertEquals(PushOutcome.APPLIED, upd.outcome)
        val stored = client.get("/api/v1/records/property/p2") { bearerAuth(admin) }.body<SyncRecord>()
        assertEquals("ویرایش شده", stored.data["title"]!!.jsonPrimitive.content)
        assertEquals("حداقل قیمت ۱۱ میلیارد", stored.confidential!!["confidentialNotes"]!!.jsonPrimitive.content)
    }

    @Test fun staleOfflineWritesConflictInsteadOfOverwriting() = test { client ->
        val a1 = client.token("agent1")
        client.push(a1, customerChange(customer("c1", "agent1")))
        assertEquals(2L, client.push(a1, customerChange(customer("c1", "agent1").copy(notes = "تازه"), base = 1)).single().newVersion)
        val stale = client.push(a1, customerChange(customer("c1", "agent1").copy(notes = "قدیمی"), base = 1)).single()
        assertEquals(PushOutcome.CONFLICT, stale.outcome)
        assertEquals(2L, stale.server!!.version)
        assertEquals("تازه", stale.server!!.data["notes"]!!.jsonPrimitive.content)
    }

    @Test fun agenciesAreIsolated() = test { client ->
        val a1 = client.token("agent1")
        client.push(a1, customerChange(customer("c1", "agent1")))
        val b = client.token("adminB")
        assertEquals("wrong_agency", client.push(b, customerChange(customer("c9", null, agency = "A"))).single().error)
        assertTrue(client.pull(b).records.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/records/customer/c1") { bearerAuth(b) }.status)
        // Assigning to a user of another agency is rejected
        assertEquals("unknown_agent", client.push(b, customerChange(customer("c10", "agent1", agency = "B"))).single().error)
    }

    @Test fun deactivatedUsersLoseAccessImmediately() = test { client ->
        val a1 = client.token("agent1")
        val admin = client.token("admin")
        val res = client.patch("/api/v1/users/agent1") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(UpdateUserRequest("agent1", UserRole.AGENT, "west", active = false)) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(a1) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("agent1").status)
        // Agents cannot manage users
        val a2 = client.token("agent2")
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/users/agent2") { bearerAuth(a2); contentType(ContentType.Application.Json); setBody(UpdateUserRequest("x", UserRole.OWNER_ADMIN, null, true)) }.status)
    }

    @Test fun retriesAreIdempotentAndPayloadsValidated() = test { client ->
        val a1 = client.token("agent1")
        val ch = customerChange(customer("c1", "agent1"), changeId = "fixed-change")
        assertEquals(PushOutcome.APPLIED, client.push(a1, ch).single().outcome)
        val again = client.push(a1, ch).single()
        assertEquals(PushOutcome.DUPLICATE, again.outcome)
        assertEquals(1L, again.newVersion)
        val bad = client.push(a1, customerChange(customer("c2", "agent1", mobile = "12345"))).single()
        assertEquals("validation:mobile:invalid_phone", bad.error)
        val mismatch = ch.copy(record = ch.record.copy(id = "other"), changeId = "x2")
        assertEquals("id_mismatch", client.push(a1, mismatch).single().error)
    }

    @Test fun deletionNeedsPermission() = test { client ->
        val a1 = client.token("agent1")
        client.push(a1, customerChange(customer("c1", "agent1")))
        val del = customerChange(customer("c1", "agent1").copy(deleted = true), base = 1).let { it.copy(record = it.record.copy(deleted = true)) }
        assertEquals("delete_forbidden", client.push(a1, del).single().error)
        val admin = client.token("admin")
        assertEquals(PushOutcome.APPLIED, client.push(admin, del.copy(changeId = "d2")).single().outcome)
        val audit = client.get("/api/v1/audit?entityId=c1") { bearerAuth(admin) }.body<List<AuditEvent>>()
        assertTrue(audit.any { it.action == AuditAction.DELETE })
        assertTrue(audit.any { it.action == AuditAction.PERMISSION_DENIED })
    }

    @Test fun managersCanRetireMergedCustomersButAgentsCannot() = test { client ->
        val manager = client.token("manager")
        client.push(manager, customerChange(customer("m1", "agent1")), customerChange(customer("m2", "agent1", mobile = "09121234567")))
        fun retired(changeId: String) = customerChange(customer("m2", "agent1").copy(mergedIntoId = "m1", deleted = true), base = 1, changeId = changeId)
            .let { it.copy(record = it.record.copy(deleted = true)) }
        assertEquals("delete_forbidden", client.push(client.token("agent1"), retired("x1")).single().error)
        assertEquals(PushOutcome.APPLIED, client.push(manager, retired("x2")).single().outcome)
        // A plain deletion (no merge target) still needs the delete permission.
        val plain = customerChange(customer("m1", "agent1").copy(deleted = true), base = 1).let { it.copy(record = it.record.copy(deleted = true)) }
        assertEquals("delete_forbidden", client.push(manager, plain).single().error)
    }

    @Test fun exportRequiresPermission() = test { client ->
        val a1 = client.token("agent1")
        client.push(a1, customerChange(customer("c1", "agent1")))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/export/customers.csv") { bearerAuth(a1) }.status)
        val csv = client.get("/api/v1/export/customers.csv?lang=fa") { bearerAuth(client.token("admin")) }.bodyAsText()
        assertTrue(csv.startsWith("﻿نام,موبایل"))
        assertTrue(csv.contains("علی رضایی"))

        val admin = client.token("admin")
        client.push(admin, propertyChange(property("p1", "agent1", notes = "SECRET-NOTE")))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/export/properties.csv") { bearerAuth(a1) }.status)
        val props = client.get("/api/v1/export/properties.csv?lang=en") { bearerAuth(admin) }.bodyAsText()
        assertTrue(props.startsWith("﻿Reference,Title"))
        assertTrue(props.contains("IR-000501"))
        assertFalse(props.contains("SECRET-NOTE"), "confidential notes must never be exported")
    }

    @Test fun matchingConfigIsValidatedAndAdminOnly() = test { client ->
        val admin = client.token("admin")
        val cfg = client.get("/api/v1/agency/config") { bearerAuth(admin) }.body<AgencyConfig>()
        assertEquals(30, cfg.matching.weights.budget)
        val bad = cfg.copy(matching = cfg.matching.copy(weights = cfg.matching.weights.copy(budget = 90)))
        assertEquals(HttpStatusCode.BadRequest, client.putConfig(admin, bad).status)
        assertEquals(HttpStatusCode.Forbidden, client.putConfig(client.token("manager"), cfg).status)
        assertEquals(HttpStatusCode.OK, client.putConfig(admin, cfg).status)
    }

    private suspend fun HttpClient.putConfig(token: String, body: AgencyConfig): io.ktor.client.statement.HttpResponse =
        put("/api/v1/agency/config") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    @Test fun imagesAreTypeCheckedAndAccessControlled() = test { client ->
        val a1 = client.token("agent1")
        client.push(a1, propertyChange(property("p1", "agent1")))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 0)
        val up = client.post("/api/v1/properties/p1/images") { bearerAuth(a1); contentType(ContentType.Image.PNG); setBody(png) }
        assertEquals(HttpStatusCode.Created, up.status)
        val id = up.body<ImageUploadResponse>().id
        val notImage = client.post("/api/v1/properties/p1/images") { bearerAuth(a1); contentType(ContentType.Image.PNG); setBody("<script>".toByteArray()) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, notImage.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/images/$id") { bearerAuth(a1) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/images/$id") { bearerAuth(client.token("adminB")) }.status)
        assertNotNull(id)
    }

}
