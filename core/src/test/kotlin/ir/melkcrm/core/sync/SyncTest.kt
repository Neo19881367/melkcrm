package ir.melkcrm.core.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    @Test fun optimisticConcurrency() {
        assertEquals(ConflictDetector.Decision.Apply, ConflictDetector.decide(serverVersion = null, baseVersion = 0))
        assertEquals(ConflictDetector.Decision.Apply, ConflictDetector.decide(serverVersion = 5, baseVersion = 5))
        // Offline edit based on version 4 while the server moved to 5: never overwrite silently.
        assertEquals(ConflictDetector.Decision.Conflict, ConflictDetector.decide(serverVersion = 5, baseVersion = 4))
        assertEquals(ConflictDetector.Decision.Conflict, ConflictDetector.decide(serverVersion = 3, baseVersion = 4))
        assertEquals(ConflictDetector.Decision.Conflict, ConflictDetector.decide(serverVersion = null, baseVersion = 2))
    }

    @Test fun diffAndRebase() {
        val local = SyncRecord(EntityType.CUSTOMER, "c1", "A", "a1", 4, 10, data = buildJsonObject { put("name", JsonPrimitive("علی")); put("city", JsonPrimitive("تهران")) })
        val server = local.copy(version = 5, data = buildJsonObject { put("name", JsonPrimitive("علی رضایی")); put("city", JsonPrimitive("تهران")) })
        val diff = ConflictDiff.diff(local.data, server.data)
        assertEquals(listOf("name"), diff.map { it.field })
        assertNull(ConflictDiff.rebase(local, server, ConflictResolution.KEEP_SERVER))
        val keepLocal = ConflictDiff.rebase(local, server, ConflictResolution.KEEP_LOCAL)!!
        assertEquals(5, keepLocal.baseVersion)
        assertEquals(local.data, keepLocal.record.data)
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(5_000, RetryPolicy.delayMillis(0))
        assertEquals(40_000, RetryPolicy.delayMillis(3))
        assertEquals(30 * 60_000L, RetryPolicy.delayMillis(30))
        assertTrue(RetryPolicy.shouldGiveUp(12))
    }
}
