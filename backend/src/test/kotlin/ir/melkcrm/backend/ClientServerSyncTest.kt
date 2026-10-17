package ir.melkcrm.backend

import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import ir.melkcrm.client.Crm
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.remote.InMemoryTokenStorage
import ir.melkcrm.client.remote.KtorRemoteApi
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.core.api.CreateUserRequest
import ir.melkcrm.core.api.UpdateUserRequest
import ir.melkcrm.client.reports.ExportResult
import ir.melkcrm.client.session.SignInResult
import ir.melkcrm.client.store.InMemoryLocalStore
import ir.melkcrm.client.sync.ImageFiles
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.SyncState
import ir.melkcrm.core.time.DisplaySettings
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end: the app's sync engine and HTTP client (module :client) against the real server,
 * with several offline devices. Proves that permissions, confidential-field protection and
 * conflict detection hold across the whole path, not just in each half.
 */
class ClientServerSyncTest {
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
            fun u(id: String, role: UserRole, team: String? = null) =
                UserRepo.insert(c, AgencyUser(id, "A", id, role = role, teamId = team, createdAt = now), "$id@test", services.passwords.hash(password))
            u("admin", UserRole.OWNER_ADMIN)
            u("agent1", UserRole.AGENT, "west")
            u("agent2", UserRole.AGENT, "east")
        }
    }

    private class Files0 : ImageFiles {
        val files = HashMap<String, ByteArray>()
        override suspend fun read(path: String) = files[path]
        override fun contentType(path: String) = "image/jpeg"
    }

    private inner class Device(http: HttpClient) {
        val images = Files0()
        val store = InMemoryLocalStore()
        private val tokens = InMemoryTokenStorage()
        val crm = Crm(store, tokens, { url -> KtorRemoteApi(url, http, tokens) { now } }, images, "local", clock = { now })

        suspend fun signIn(user: String): Device {
            val r = crm.session.signIn("http://localhost", "$user@test", password)
            check(r is SignInResult.Success) { "sign-in failed: $r" }
            return this
        }

        suspend fun sync() = crm.sync.sync().also { check(it.error == null) { "sync error ${it.error}" } }
    }

    private fun e2e(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(cfg, services) }
        block()
    }

    private fun customer(d: Device, id: String, mobile: String) = Customer(
        id = id, agencyId = "A", fullName = "مشتری $id", mobile = mobile, roles = setOf(CustomerRole.BUYER),
        assignedAgentId = d.crm.session.user.id, city = "تهران", registeredAt = now, updatedAt = now,
    )

    @Test fun recordsFlowBetweenDevicesWithinPermissions() = e2e {
        val agent1 = Device(client).signIn("agent1")
        val admin = Device(client).signIn("admin")
        val agent2 = Device(client).signIn("agent2")

        assertIs<SaveOutcome.Saved<*>>(agent1.crm.customers.save(customer(agent1, "c1", "09121110000")))
        assertIs<SaveOutcome.Saved<*>>(agent1.crm.customers.save(customer(agent1, "owner", "09122220000").copy(roles = setOf(CustomerRole.SELLER))))
        agent1.crm.customers.saveRequirement(agent1.crm.customers.newRequirement("c1").copy(
            id = "r1", categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"), neighborhoods = listOf("پونک"),
            minArea = 80.0, maxArea = 110.0, maxBudget = Money.ofToman(10_000_000_000),
        ))
        val p = agent1.crm.properties.newProperty().copy(
            id = "p1", title = "پونک ۹۵ متر", city = "تهران", neighborhood = "پونک", builtArea = 95.0, bedrooms = 2,
            askingPrice = Money.ofToman(9_500_000_000), status = PropertyStatus.AVAILABLE, ownerCustomerId = "owner",
            confidentialNotes = "مالک مهاجرت می‌کند",
        )
        assertIs<SaveOutcome.Saved<*>>(agent1.crm.properties.save(p))
        agent1.crm.customers.saveSeller(agent1.crm.customers.newSeller("owner", "p1").copy(
            id = "s1", status = SellerStatus.ACTIVE_FOR_SALE, confidential = SellerConfidential(minAcceptablePrice = Money.ofToman(9_000_000_000)),
        ))
        val pushed = agent1.sync()
        assertEquals(0, pushed.rejected)
        assertTrue(agent1.store.outbox().isEmpty())
        assertEquals(SyncState.SYNCED, agent1.store.get(EntityType.PROPERTY, "p1")!!.syncState)

        // The owner sees everything, including the seller's confidential minimum.
        admin.sync()
        assertEquals(Money.ofToman(9_000_000_000), admin.crm.customers.seller("s1")!!.confidential.minAcceptablePrice)
        assertEquals("مالک مهاجرت می‌کند", admin.crm.properties.property("p1")!!.confidentialNotes)
        val buyers = admin.crm.matching.buyersFor("p1")
        assertEquals(listOf("c1"), buyers.map { it.customerId })
        assertTrue(buyers.single().negotiation.any { it.rangeLow != null }, "confidential negotiation range available to the owner")

        // Another agent sees the listing (agency inventory) but not the customers, the seller file or confidential notes.
        agent2.sync()
        val seen = agent2.crm.properties.property("p1")
        assertNotNull(seen)
        assertNull(seen.confidentialNotes)
        assertNull(agent2.crm.customers.customer("c1"))
        assertNull(agent2.crm.customers.seller("s1"))
        assertTrue(agent2.crm.matching.buyersFor("p1").isEmpty())
    }

    @Test fun concurrentOfflineEditsBecomeAConflictNotAnOverwrite() = e2e {
        val agent1 = Device(client).signIn("agent1")
        val admin = Device(client).signIn("admin")
        agent1.crm.customers.save(customer(agent1, "c1", "09121110000"))
        agent1.sync()
        admin.sync()

        agent1.crm.customers.save(agent1.crm.customers.customer("c1")!!.copy(city = "کرج"))
        admin.crm.customers.save(admin.crm.customers.customer("c1")!!.copy(notes = "خریدار جدی"))
        agent1.sync()
        val report = admin.sync()
        assertEquals(1, report.conflicts)
        val conflict = admin.store.conflicts().single()
        assertEquals(2, conflict.server!!.version)
        assertEquals("خریدار جدی", admin.crm.customers.customer("c1")!!.notes, "local edit kept until resolved")

        admin.crm.sync.resolveConflict(EntityType.CUSTOMER, "c1", keepLocal = true)
        admin.sync()
        agent1.sync()
        val final = agent1.crm.customers.customer("c1")!!
        assertEquals("خریدار جدی", final.notes)
        assertEquals(3, agent1.store.get(EntityType.CUSTOMER, "c1")!!.version)
    }

    @Test fun theServerRejectsWritesTheRoleDoesNotAllowEvenFromAModifiedApp() = e2e {
        val agent1 = Device(client).signIn("agent1")
        // Bypass the app's own check, as a tampered client would.
        agent1.crm.writer.persist(EntityType.CUSTOMER, customer(agent1, "x1", "09123334444").copy(assignedAgentId = "agent2"))
        val r = agent1.crm.sync.sync()
        assertEquals(1, r.rejected)
        assertEquals(SyncState.FAILED, agent1.store.get(EntityType.CUSTOMER, "x1")!!.syncState)
        assertEquals("forbidden", agent1.store.outbox().single().lastError)
        val audit = services.db.tx { c -> AuditRepo.list(c, "A", "x1", 10) }
        assertTrue(audit.any { it.action == AuditAction.PERMISSION_DENIED })
    }

    @Test fun confidentialNotesSurviveEditsByUsersWhoCannotSeeThem() = e2e {
        val admin = Device(client).signIn("admin")
        val agent2 = Device(client).signIn("agent2")
        val p = admin.crm.properties.newProperty().copy(id = "p9", title = "زمین", assignedAgentId = null, confidentialNotes = "سند در رهن بانک")
        assertIs<SaveOutcome.Saved<*>>(admin.crm.properties.save(p))
        admin.sync()
        agent2.sync()
        val seen = agent2.crm.properties.property("p9")!!
        assertNull(seen.confidentialNotes)
        // Unassigned listings can be edited by any agent; the confidential part stays untouched.
        assertIs<SaveOutcome.Saved<*>>(agent2.crm.properties.save(seen.copy(title = "زمین ۵۰۰ متری")))
        agent2.sync()
        admin.sync()
        val after = admin.crm.properties.property("p9")!!
        assertEquals("زمین ۵۰۰ متری", after.title)
        assertEquals("سند در رهن بانک", after.confidentialNotes)
    }

    @Test fun photosAndExportsGoThroughTheServer() = e2e {
        val agent1 = Device(client).signIn("agent1")
        val admin = Device(client).signIn("admin")
        val p = agent1.crm.properties.newProperty().copy(id = "p1", title = "آپارتمان")
        agent1.crm.properties.save(p)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        agent1.images.files["/photos/1.jpg"] = jpeg
        agent1.crm.properties.addImage("p1", "/photos/1.jpg")
        assertEquals(1, agent1.sync().imagesUploaded)
        admin.sync()
        val id = admin.crm.properties.property("p1")!!.imageIds.single()
        assertContentEquals(jpeg, admin.crm.session.api()!!.downloadImage(id))

        assertEquals(ExportResult.Forbidden, agent1.crm.reports.exportCustomers(DisplaySettings()))
        val csv = assertIs<ExportResult.Ok>(admin.crm.reports.exportProperties(DisplaySettings(), ir.melkcrm.core.money.CurrencyUnit.TOMAN))
        assertTrue(csv.csv.contains(p.reference))
    }

    @Test fun pulledListingsAreMatchedAndSigningOutForgetsEverything() = e2e {
        val agent1 = Device(client).signIn("agent1")
        val admin = Device(client).signIn("admin")
        agent1.crm.customers.save(customer(agent1, "c1", "09121110000"))
        agent1.crm.customers.saveRequirement(agent1.crm.customers.newRequirement("c1").copy(
            id = "r1", categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"), maxBudget = Money.ofToman(10_000_000_000),
        ))
        agent1.crm.syncNow()
        admin.crm.properties.save(admin.crm.properties.newProperty().copy(
            id = "p1", title = "آپارتمان", city = "تهران", builtArea = 90.0, askingPrice = Money.ofToman(9_000_000_000), status = PropertyStatus.AVAILABLE,
        ))
        admin.crm.syncNow()

        assertTrue(agent1.crm.matching.state.value.evaluations.isEmpty())
        val report = agent1.crm.syncNow()
        assertTrue(report.pulled >= 1)
        assertTrue("r1|p1" in agent1.crm.matching.state.value.evaluations, "a listing that arrives by sync is matched without a manual refresh")

        agent1.crm.signOut()
        assertTrue(agent1.crm.matching.state.value.evaluations.isEmpty())
        assertTrue(agent1.store.all(EntityType.CUSTOMER).isEmpty())
        assertTrue(agent1.crm.session.session.value.signedIn.not())
    }

    @Test fun adminsManageAccountsFromTheAppAndPasswordChangesKeepTheDeviceSignedIn() = e2e {
        val admin = Device(client).signIn("admin")
        val agent1 = Device(client).signIn("agent1")

        // Only users who manage users may create accounts; the server decides.
        val refused = runCatching {
            agent1.crm.session.createUser(CreateUserRequest("agent3@test", "a-long-password", "مشاور سوم", UserRole.AGENT))
        }.exceptionOrNull()
        assertIs<RemoteException>(refused)
        assertEquals("forbidden", refused.code)
        val weak = runCatching {
            admin.crm.session.createUser(CreateUserRequest("agent3@test", "123456789012", "مشاور سوم", UserRole.AGENT))
        }.exceptionOrNull()
        assertEquals("password_only_digits", (weak as RemoteException).code)

        val created = admin.crm.session.createUser(CreateUserRequest("agent3@test", "a-long-password", "مشاور سوم", UserRole.AGENT, teamId = "west"))
        assertTrue(admin.crm.session.agency.value.users.any { it.id == created.id }, "the agent list refreshes after creating an account")
        val agent3 = Device(client)
        assertIs<SignInResult.Success>(agent3.crm.session.signIn("http://localhost", "agent3@test", "a-long-password"))

        // Changing the password signs the account out on the server; this device signs back in by itself.
        agent3.crm.session.changePassword("a-long-password", "another-long-password")
        assertTrue(!agent3.crm.session.session.value.authExpired)
        agent3.sync()
        assertIs<SignInResult.Failure>(Device(client).crm.session.signIn("http://localhost", "agent3@test", "a-long-password"))

        // Deactivating an account stops its devices from syncing.
        admin.crm.session.updateUser(created.id, UpdateUserRequest(created.displayName, UserRole.AGENT, "west", active = false))
        assertEquals(false, admin.crm.session.agency.value.user(created.id)?.active)
        assertTrue(agent3.crm.sync.sync().error != null)
    }
}
