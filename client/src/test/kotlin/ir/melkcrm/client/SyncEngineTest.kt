package ir.melkcrm.client

import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.client.store.ImageUploadState
import ir.melkcrm.core.model.*
import ir.melkcrm.core.sync.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private fun serverCopy(t: TestCrm, c: Customer, version: Long): SyncRecord =
        RecordCodec.encode(EntityType.CUSTOMER, c, c.id, c.agencyId, c.assignedAgentId, version, c.updatedAt)

    @Test fun appliedChangesBecomeSynced() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "c1"))
        val report = t.crm.sync.sync()
        assertEquals(1, report.pushed)
        assertNull(report.error)
        val rec = t.store.get(EntityType.CUSTOMER, "c1")!!
        assertEquals(SyncState.SYNCED, rec.syncState)
        assertEquals(1, rec.version)
        assertTrue(rec.json.contains("\"version\":1"))
        assertTrue(t.store.outbox().isEmpty())
        assertEquals(0, api.pushed.single().baseVersion)

        t.crm.customers.save(t.crm.customers.customer("c1")!!.copy(city = "کرج"))
        t.crm.sync.sync()
        assertEquals(1, api.pushed.last().baseVersion, "the next edit builds on the server version")
    }

    @Test fun confidentialFieldsTravelSeparately() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "owner"))
        val p = t.crm.properties.newProperty().copy(id = "p1", title = "x", confidentialNotes = "secret")
        t.crm.properties.save(p)
        t.crm.customers.saveSeller(t.crm.customers.newSeller("owner", "p1").copy(id = "s1", confidential = SellerConfidential(minAcceptablePrice = ir.melkcrm.core.money.Money.ofToman(5))))
        t.crm.sync.sync()
        val prop = api.pushed.first { it.record.type == EntityType.PROPERTY }.record
        assertFalse("confidentialNotes" in prop.data)
        assertEquals("secret", prop.confidential!!["confidentialNotes"].toString().trim('"'))
        val seller = api.pushed.first { it.record.type == EntityType.SELLER_PROFILE }.record
        assertFalse("confidential" in seller.data)
        assertNotNull(seller.confidential)
    }

    @Test fun serverConflictIsKeptForTheAgentToResolve() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "c1"))
        t.crm.sync.sync()
        t.crm.customers.save(t.crm.customers.customer("c1")!!.copy(city = "local"))
        val serverVersion = t.customer(id = "c1").copy(city = "server", agencyId = "A")
        api.pushHandler = { c -> PushResult(c.changeId, c.record.id, PushOutcome.CONFLICT, server = serverCopy(t, serverVersion, 5)) }
        val report = t.crm.sync.sync()
        assertEquals(1, report.conflicts)
        val conflict = t.store.conflicts().single()
        assertEquals(5, conflict.server!!.version)
        assertEquals(SyncState.CONFLICT, t.store.get(EntityType.CUSTOMER, "c1")!!.syncState)
        assertTrue(t.store.outbox().isEmpty(), "nothing is retried until the agent chooses")
        assertEquals("local", t.crm.customers.customer("c1")!!.city, "the local edit is not lost")

        t.crm.sync.resolveConflict(EntityType.CUSTOMER, "c1", keepLocal = true)
        val entry = t.store.outbox().single()
        assertEquals(5, entry.baseVersion, "re-applied on top of the server version")
        assertEquals("local", t.crm.customers.customer("c1")!!.city)
        assertTrue(t.store.conflicts().isEmpty())
    }

    @Test fun keepingTheServerVersionDiscardsTheLocalEdit() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "c1"))
        val serverVersion = t.customer(id = "c1").copy(city = "server", agencyId = "A")
        api.pushHandler = { c -> PushResult(c.changeId, c.record.id, PushOutcome.CONFLICT, server = serverCopy(t, serverVersion, 3)) }
        t.crm.sync.sync()
        t.crm.sync.resolveConflict(EntityType.CUSTOMER, "c1", keepLocal = false)
        assertEquals("server", t.crm.customers.customer("c1")!!.city)
        assertEquals(SyncState.SYNCED, t.store.get(EntityType.CUSTOMER, "c1")!!.syncState)
        assertTrue(t.store.outbox().isEmpty())
    }

    @Test fun pulledChangesNeverOverwriteUnpushedLocalEdits() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "c1"))
        t.crm.sync.sync()
        // Offline edit that cannot be pushed right now.
        t.crm.customers.save(t.crm.customers.customer("c1")!!.copy(city = "offline edit"))
        api.failPush = RemoteException(0, "network")
        t.crm.sync.sync()
        api.failPush = null
        assertTrue(t.store.outbox().single().nextAttemptAt > t.clock, "network failures back off")

        // Meanwhile someone else changed it on the server.
        api.pages += PullResponse(listOf(serverCopy(t, t.customer(id = "c1").copy(city = "newer on server", agencyId = "A"), 2)), 10, false)
        api.pushHandler = { error("should not push while backing off") }
        val report = t.crm.sync.sync()
        assertEquals(1, report.conflicts)
        assertEquals("offline edit", t.crm.customers.customer("c1")!!.city)
        assertEquals("newer on server", RecordCodec.decode<Customer>(t.store.conflicts().single().server!!).city)
        assertEquals("10", t.store.meta("sync.cursor"))
    }

    @Test fun pullAppliesNewRecordsAndTombstonesRemoveAccess() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        val c = t.customer(id = "c9", agent = "agent1").copy(agencyId = "A")
        api.pages += PullResponse(listOf(serverCopy(t, c, 4)), 1, true)
        api.pages += PullResponse(listOf(serverCopy(t, c.copy(id = "c10"), 1)), 2, false)
        t.crm.sync.sync()
        assertEquals(4, t.store.get(EntityType.CUSTOMER, "c9")!!.version)
        assertNotNull(t.crm.customers.customer("c10"))
        // Reassigned to another agent: the server now sends a tombstone and the local copy goes.
        api.pages += PullResponse(listOf(SyncRecord(EntityType.CUSTOMER, "c9", "A", null, 5, t.clock, true, JsonObject(emptyMap()))), 3, false)
        t.crm.sync.sync()
        assertNull(t.store.get(EntityType.CUSTOMER, "c9"))
    }

    @Test fun maskedCustomersArriveWithoutContactDetails() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        val full = serverCopy(t, t.customer(id = "c5", agent = "agent2").copy(agencyId = "A"), 2)
        api.pages += PullResponse(listOf(full.copy(data = RecordCodec.mask(full.data))), 1, false)
        t.crm.sync.sync()
        val c = t.crm.customers.customer("c5")!!
        assertNull(c.mobile)
        assertFalse(t.store.get(EntityType.CUSTOMER, "c5")!!.json.contains("contactMasked"))
    }

    @Test fun rejectedChangesAreNotRetriedUntilEdited() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "c1"))
        api.pushHandler = { c -> PushResult(c.changeId, c.record.id, PushOutcome.REJECTED, error = "forbidden") }
        assertEquals(1, t.crm.sync.sync().rejected)
        assertEquals(SyncState.FAILED, t.store.get(EntityType.CUSTOMER, "c1")!!.syncState)
        val before = api.pushed.size
        t.crm.sync.sync()
        assertEquals(before, api.pushed.size)
        t.crm.customers.save(t.crm.customers.customer("c1")!!.copy(city = "x"))
        api.pushHandler = { c -> PushResult(c.changeId, c.record.id, PushOutcome.APPLIED, newVersion = 1) }
        t.crm.sync.sync()
        assertEquals(SyncState.SYNCED, t.store.get(EntityType.CUSTOMER, "c1")!!.syncState)
    }

    @Test fun photosUploadAfterThePropertyExistsOnTheServer() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        val p = t.crm.properties.newProperty().copy(id = "p1", title = "x")
        t.crm.properties.save(p)
        t.images.files["/files/a.jpg"] = byteArrayOf(1, 2, 3)
        t.crm.properties.addImage("p1", "/files/a.jpg")
        val report = t.crm.sync.sync()
        assertEquals(1, report.imagesUploaded)
        assertEquals(listOf("img-1"), t.crm.properties.property("p1")!!.imageIds)
        assertEquals(ImageUploadState.UPLOADED, t.store.images("p1").single().uploadState)
        assertTrue(t.store.outbox().isEmpty(), "the property with its photo id was pushed in the same sync")
        assertEquals(listOf("img-1"), api.pushed.last { it.record.type == EntityType.PROPERTY }.record.data["imageIds"]!!.toString().let { listOf(it.trim('[', ']', '"')) })
    }

    @Test fun signingInAdoptsLocalRecordsAndDropsSampleData() = runTest {
        val t = TestCrm()
        t.crm.samples.load()
        t.crm.customers.save(t.customer(id = "mine", agent = "local-user", mobile = "09390001122"), confirmDuplicate = true)
        val api = t.signIn(Fx.agent1)
        assertNull(t.store.get(EntityType.CUSTOMER, "cust-0001"), "sample data is never uploaded")
        val mine = t.store.get(EntityType.CUSTOMER, "mine")!!
        assertEquals("A", mine.agencyId)
        assertEquals("agent1", mine.assignedAgentId)
        assertEquals("agent1", t.crm.customers.customer("mine")!!.assignedAgentId)
        t.crm.sync.sync()
        assertEquals(listOf("mine"), api.pushed.map { it.record.id })
        assertEquals("A", api.pushed.single().record.data["agencyId"]!!.toString().trim('"'))
    }

    @Test fun sampleDataCannotBeLoadedWhileSignedIn() = runTest {
        val t = TestCrm()
        t.signIn(Fx.admin)
        assertEquals(ir.melkcrm.client.sample.SampleLoader.Result.NotAllowedWhileSignedIn, t.crm.samples.load())
    }

    @Test fun anAuthFailureMarksTheSessionExpiredButKeepsData() = runTest {
        val t = TestCrm()
        val api = t.signIn(Fx.agent1)
        t.crm.customers.save(t.customer(id = "c1"))
        api.failPush = RemoteException(401, "session_expired")
        val r = t.crm.sync.sync()
        assertEquals("session_expired", r.error)
        assertTrue(t.crm.session.session.value.authExpired)
        assertNotNull(t.crm.customers.customer("c1"))
        assertEquals(1, t.store.outbox().size)
    }
}
