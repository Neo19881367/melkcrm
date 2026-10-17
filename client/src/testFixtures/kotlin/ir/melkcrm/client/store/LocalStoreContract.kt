package ir.melkcrm.client.store

import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.SyncRecord
import ir.melkcrm.core.sync.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour every [LocalStore] must have. The client module runs it against [InMemoryLocalStore];
 * the Android app runs the same tests against its Room implementation, so the repositories and the
 * sync engine (tested on the in-memory store) behave the same on a device.
 */
abstract class LocalStoreContract {
    /** A new, empty store. Called once per test. */
    abstract fun newStore(): LocalStore

    /** Releases resources held by the store created for the current test. */
    open fun closeStore() {}

    protected val store: LocalStore by lazy { newStore() }

    @AfterTest
    fun tearDown() = closeStore()

    protected fun record(
        id: String,
        type: EntityType = EntityType.CUSTOMER,
        searchText: String = "",
        deleted: Boolean = false,
        sample: Boolean = false,
        customerId: String? = null,
        propertyId: String? = null,
        version: Long = 1,
    ) = LocalRecord(
        type = type, id = id, agencyId = "agency-1", assignedAgentId = "agent-1", version = version,
        updatedAt = 1_000L + version, deleted = deleted, json = """{"id":"$id","v":$version}""",
        syncState = SyncState.SYNCED, sample = sample, customerId = customerId, propertyId = propertyId,
        status = "ACTIVE", searchText = searchText,
    )

    private fun outbox(id: String, queuedAt: Long, type: EntityType = EntityType.CUSTOMER, changeId: String = "c-$id") =
        OutboxEntry(type, id, changeId, baseVersion = 1, queuedAt = queuedAt)

    private fun image(id: String, propertyId: String, order: Int, state: ImageUploadState) =
        LocalImage(id, propertyId, "/files/$id.jpg", remoteId = null, sortOrder = order, createdAt = 5L, uploadState = state)

    // ------------------------------------------------------------------ records

    @Test
    fun putGetAndReplace() = runTest {
        val r = record("c1", searchText = "ali rezaei")
        store.put(r)
        assertEquals(r, store.get(EntityType.CUSTOMER, "c1"))
        assertNull(store.get(EntityType.PROPERTY, "c1"), "records are keyed by type and id")

        val newer = r.copy(version = 2, json = """{"id":"c1","v":2}""", syncState = SyncState.PENDING, assignedAgentId = null)
        store.put(newer)
        assertEquals(newer, store.get(EntityType.CUSTOMER, "c1"))
        assertEquals(1, store.all(EntityType.CUSTOMER).size)
    }

    @Test
    fun allSkipsSoftDeletedUnlessAsked() = runTest {
        store.put(record("c1"))
        store.put(record("c2", deleted = true))
        store.put(record("p1", type = EntityType.PROPERTY))
        assertEquals(setOf("c1"), store.all(EntityType.CUSTOMER).map { it.id }.toSet())
        assertEquals(setOf("c1", "c2"), store.all(EntityType.CUSTOMER, includeDeleted = true).map { it.id }.toSet())
    }

    @Test
    fun byCustomerAndByPropertySkipDeleted() = runTest {
        store.put(record("r1", EntityType.BUYER_REQUIREMENT, customerId = "c1"))
        store.put(record("r2", EntityType.BUYER_REQUIREMENT, customerId = "c1", deleted = true))
        store.put(record("r3", EntityType.BUYER_REQUIREMENT, customerId = "c2"))
        store.put(record("a1", EntityType.ACTIVITY, customerId = "c1", propertyId = "p1"))
        store.put(record("a2", EntityType.ACTIVITY, propertyId = "p2"))
        assertEquals(listOf("r1"), store.byCustomer(EntityType.BUYER_REQUIREMENT, "c1").map { it.id })
        assertEquals(listOf("a1"), store.byCustomer(EntityType.ACTIVITY, "c1").map { it.id })
        assertEquals(listOf("a1"), store.byProperty(EntityType.ACTIVITY, "p1").map { it.id })
        assertTrue(store.byProperty(EntityType.BUYER_REQUIREMENT, "p1").isEmpty())
    }

    @Test
    fun observeEmitsCurrentRecordsWithoutDeletedOnes() = runTest {
        assertTrue(store.observe(EntityType.CUSTOMER).first().isEmpty())
        store.put(record("c1"))
        store.put(record("c2", deleted = true))
        store.put(record("p1", type = EntityType.PROPERTY))
        assertEquals(listOf("c1"), store.observe(EntityType.CUSTOMER).first().map { it.id })
    }

    @Test
    fun removeDropsTheRecordAndItsQueuedChange() = runTest {
        store.put(record("c1", searchText = "ali"))
        store.put(record("c2"))
        store.putOutbox(outbox("c1", 1))
        store.putOutbox(outbox("c2", 2))
        store.remove(EntityType.CUSTOMER, "c1")
        assertNull(store.get(EntityType.CUSTOMER, "c1"))
        assertNull(store.outboxEntry(EntityType.CUSTOMER, "c1"))
        assertEquals(listOf("c2"), store.outbox().map { it.id })
        assertTrue(store.searchIds(EntityType.CUSTOMER, "ali").isEmpty())
    }

    // ------------------------------------------------------------------ search

    @Test
    fun searchMatchesEveryWordAsAPrefix() = runTest {
        store.put(record("c1", searchText = "ali rezaei tehran 09121234567"))
        store.put(record("c2", searchText = "maryam ahmadi karaj"))
        store.put(record("c3", searchText = "ali karimi shiraz"))
        assertEquals(setOf("c1", "c3"), store.searchIds(EntityType.CUSTOMER, "ali").toSet())
        assertEquals(listOf("c1"), store.searchIds(EntityType.CUSTOMER, "ali teh").sorted())
        assertEquals(listOf("c1"), store.searchIds(EntityType.CUSTOMER, "0912").sorted())
        assertTrue(store.searchIds(EntityType.CUSTOMER, "zaei").isEmpty(), "matching is by word prefix, not substring")
        assertTrue(store.searchIds(EntityType.CUSTOMER, "ali isfahan").isEmpty(), "every word must match")
        assertTrue(store.searchIds(EntityType.PROPERTY, "ali").isEmpty())
    }

    @Test
    fun searchHandlesPersianText() = runTest {
        store.put(record("c1", searchText = "علی رضایی تهران"))
        store.put(record("c2", searchText = "مریم احمدی کرج"))
        assertEquals(listOf("c1"), store.searchIds(EntityType.CUSTOMER, "علی"))
        assertEquals(listOf("c1"), store.searchIds(EntityType.CUSTOMER, "رضا"))
        assertEquals(listOf("c2"), store.searchIds(EntityType.CUSTOMER, "مریم کر"))
    }

    @Test
    fun searchFollowsUpdatesAndSkipsDeleted() = runTest {
        store.put(record("c1", searchText = "old name"))
        store.put(record("c1", searchText = "new name", version = 2))
        assertTrue(store.searchIds(EntityType.CUSTOMER, "old").isEmpty())
        assertEquals(listOf("c1"), store.searchIds(EntityType.CUSTOMER, "new"))

        store.put(record("c1", searchText = "new name", version = 3, deleted = true))
        assertTrue(store.searchIds(EntityType.CUSTOMER, "new").isEmpty())
    }

    // ------------------------------------------------------------------ outbox

    @Test
    fun outboxKeepsOneEntryPerRecordInQueueOrder() = runTest {
        store.putOutbox(outbox("c2", queuedAt = 20))
        store.putOutbox(outbox("c1", queuedAt = 10))
        store.putOutbox(outbox("p1", queuedAt = 15, type = EntityType.PROPERTY))
        assertEquals(listOf("c1", "p1", "c2"), store.outbox().map { it.id })

        val retried = outbox("c1", queuedAt = 10, changeId = "c-second").copy(attempts = 2, nextAttemptAt = 99, lastError = "timeout", rejected = true)
        store.putOutbox(retried)
        assertEquals(retried, store.outboxEntry(EntityType.CUSTOMER, "c1"))
        assertEquals(3, store.observeOutboxCount().first())

        store.removeOutbox(EntityType.CUSTOMER, "c1")
        assertNull(store.outboxEntry(EntityType.CUSTOMER, "c1"))
        assertEquals(2, store.observeOutboxCount().first())
    }

    // ------------------------------------------------------------------ conflicts

    @Test
    fun conflictsKeepTheServerCopy() = runTest {
        val server = SyncRecord(
            type = EntityType.PROPERTY, id = "p1", agencyId = "agency-1", assignedAgentId = "agent-2", version = 7, updatedAt = 70,
            data = buildJsonObject { put("title", JsonPrimitive("آپارتمان ۱۲۰ متری")) },
            confidential = buildJsonObject { put("minimumPrice", JsonPrimitive(12_000_000_000L)) },
        )
        val first = ConflictEntry(EntityType.PROPERTY, "p1", """{"title":"local"}""", server, detectedAt = 200)
        val gone = ConflictEntry(EntityType.CUSTOMER, "c1", """{"name":"x"}""", null, detectedAt = 100)
        store.putConflict(first)
        store.putConflict(gone)
        assertEquals(listOf(gone, first), store.conflicts())
        assertEquals(listOf(gone, first), store.observeConflicts().first())

        val refreshed = first.copy(server = server.copy(version = 8))
        store.putConflict(refreshed)
        assertEquals(listOf(gone, refreshed), store.conflicts())

        store.removeConflict(EntityType.CUSTOMER, "c1")
        assertEquals(listOf(refreshed), store.conflicts())
    }

    // ------------------------------------------------------------------ audit and meta

    @Test
    fun auditIsPerEntityNewestFirst() = runTest {
        fun event(id: String, entity: String, at: Long) = AuditEvent(
            id = id, agencyId = "agency-1", actorUserId = "agent-1", entityType = "PROPERTY", entityId = entity,
            action = AuditAction.UPDATE, changes = mapOf("askingPrice" to listOf("100", "120"), "note" to listOf(null, "x")), at = at,
        )
        store.addAudit(event("e1", "p1", 10))
        store.addAudit(event("e2", "p1", 30))
        store.addAudit(event("e3", "p2", 20))
        assertEquals(listOf("e2", "e1"), store.audit("p1").map { it.id })
        assertEquals(event("e1", "p1", 10), store.audit("p1").last())
        assertTrue(store.audit("missing").isEmpty())
    }

    @Test
    fun metaSetRemoveAndObserve() = runTest {
        assertNull(store.meta("cursor"))
        assertNull(store.observeMeta("cursor").first())
        store.setMeta("cursor", "42")
        assertEquals("42", store.meta("cursor"))
        assertEquals("42", store.observeMeta("cursor").first())
        store.setMeta("cursor", "43")
        assertEquals("43", store.meta("cursor"))
        store.setMeta("cursor", null)
        assertNull(store.meta("cursor"))
        assertNull(store.observeMeta("cursor").first())
    }

    // ------------------------------------------------------------------ images

    @Test
    fun imagesAreOrderedAndPendingIncludesFailures() = runTest {
        store.putImage(image("i2", "p1", 2, ImageUploadState.UPLOADED))
        store.putImage(image("i1", "p1", 1, ImageUploadState.PENDING))
        store.putImage(image("i3", "p1", 3, ImageUploadState.FAILED))
        store.putImage(image("i4", "p2", 1, ImageUploadState.PENDING))
        assertEquals(listOf("i1", "i2", "i3"), store.images("p1").map { it.id })
        assertEquals(listOf("i1", "i2", "i3"), store.observeImages("p1").first().map { it.id })
        assertEquals(setOf("i1", "i3", "i4"), store.pendingImages().map { it.id }.toSet())

        val uploaded = image("i1", "p1", 1, ImageUploadState.UPLOADED).copy(remoteId = "srv-1", caption = "نما")
        store.putImage(uploaded)
        assertEquals(uploaded, store.images("p1").first())
        store.removeImage("i2")
        assertEquals(listOf("i1", "i3"), store.images("p1").map { it.id })
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    fun deleteSampleRecordsKeepsRealData() = runTest {
        store.put(record("real", searchText = "real customer"))
        store.put(record("s1", sample = true, searchText = "sample customer"))
        store.put(record("s2", EntityType.PROPERTY, sample = true))
        store.putOutbox(outbox("real", 1))
        store.putOutbox(outbox("s1", 2))
        store.putConflict(ConflictEntry(EntityType.CUSTOMER, "s1", "{}", null, 3))

        store.deleteSampleRecords()
        assertEquals(listOf("real"), store.all(EntityType.CUSTOMER, includeDeleted = true).map { it.id })
        assertTrue(store.all(EntityType.PROPERTY, includeDeleted = true).isEmpty())
        assertEquals(listOf("real"), store.outbox().map { it.id })
        assertTrue(store.conflicts().isEmpty())
        assertEquals(listOf("real"), store.searchIds(EntityType.CUSTOMER, "customer"))
    }

    @Test
    fun clearAllWipesEverything() = runTest {
        store.put(record("c1", searchText = "ali"))
        store.putOutbox(outbox("c1", 1))
        store.putConflict(ConflictEntry(EntityType.CUSTOMER, "c1", "{}", null, 3))
        store.addAudit(AuditEvent("e1", "agency-1", null, "CUSTOMER", "c1", AuditAction.CREATE, at = 1))
        store.setMeta("cursor", "9")
        store.putImage(image("i1", "p1", 0, ImageUploadState.PENDING))

        store.clearAll()
        assertTrue(store.all(EntityType.CUSTOMER, includeDeleted = true).isEmpty())
        assertTrue(store.outbox().isEmpty())
        assertTrue(store.conflicts().isEmpty())
        assertTrue(store.audit("c1").isEmpty())
        assertNull(store.meta("cursor"))
        assertTrue(store.images("p1").isEmpty())
        assertTrue(store.searchIds(EntityType.CUSTOMER, "ali").isEmpty())
        assertEquals(0, store.observeOutboxCount().first())
    }

    @Test
    fun transactionCommitsOrRollsBack() = runTest {
        val value = store.transaction {
            store.put(record("c1"))
            store.putOutbox(outbox("c1", 1))
            "done"
        }
        assertEquals("done", value)
        assertEquals(1, store.outbox().size)

        assertFailsWith<IllegalStateException> {
            store.transaction {
                store.put(record("c2"))
                store.putOutbox(outbox("c2", 2))
                store.setMeta("cursor", "1")
                error("write failed")
            }
        }
        assertNull(store.get(EntityType.CUSTOMER, "c2"), "a failed transaction leaves no partial record")
        assertNull(store.outboxEntry(EntityType.CUSTOMER, "c2"))
        assertNull(store.meta("cursor"))
        assertEquals(listOf("c1"), store.all(EntityType.CUSTOMER).map { it.id })
    }
}
