package ir.melkcrm.core.duplicates

import ir.melkcrm.core.Fx
import ir.melkcrm.core.model.CustomerRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuplicatesTest {
    @Test fun samePhoneInDifferentFormatsIsDuplicate() {
        val a = Fx.customer("a", mobile = "09121110001")
        val b = Fx.customer("b", mobile = "+98 912 111 0001")
        val found = DuplicateDetector.find(b, listOf(a))
        assertEquals(1, found.size)
        assertEquals(DuplicateStrength.SAME_PHONE, found.single().strength)
        assertEquals("09121110001", found.single().matchedPhone)
    }

    @Test fun altPhoneMatchesMobile() {
        val a = Fx.customer("a", mobile = "09120000000").copy(altPhone = "09121110001")
        val b = Fx.customer("b", mobile = "۰۹۱۲۱۱۱۰۰۰۱")
        assertEquals(1, DuplicateDetector.find(b, listOf(a)).size)
    }

    @Test fun differentAgenciesAndMergedRecordsIgnored() {
        val a = Fx.customer("a").copy(agencyId = "other")
        val merged = Fx.customer("m").copy(mergedIntoId = "x")
        assertTrue(DuplicateDetector.find(Fx.customer("b"), listOf(a, merged)).isEmpty())
    }

    @Test fun similarNameSameCityIsWeakerSignal() {
        val a = Fx.customer("a", mobile = "09120000001").copy(fullName = "علي كريمي")
        val b = Fx.customer("b", mobile = "09120000002").copy(fullName = "علی کریمی")
        assertEquals(DuplicateStrength.SIMILAR_NAME_SAME_CITY, DuplicateDetector.find(b, listOf(a)).single().strength)
    }

    @Test fun clustersGroupByPhone() {
        val list = listOf(Fx.customer("a"), Fx.customer("b", mobile = "+989121110001"), Fx.customer("c", mobile = "09129999999"))
        val clusters = DuplicateDetector.clusters(list)
        assertEquals(1, clusters.size)
        assertEquals(setOf("a", "b"), clusters.single().map { it.id }.toSet())
    }

    @Test fun mergeKeepsAllDataAndRoles() {
        val primary = Fx.customer("a", roles = setOf(CustomerRole.BUYER)).copy(notes = "خریدار", tags = listOf("vip"), email = null, registeredAt = 200)
        val secondary = Fx.customer("b", roles = setOf(CustomerRole.SELLER), mobile = "09125550000")
            .copy(notes = "فروشنده", tags = listOf("north"), email = "a@b.ir", registeredAt = 100, lastContactAt = 999)
        val r = CustomerMerge.merge(primary, secondary, now = 1000)
        assertEquals(setOf(CustomerRole.BUYER, CustomerRole.SELLER), r.merged.roles)
        assertEquals("a@b.ir", r.merged.email)
        assertEquals("09125550000", r.merged.altPhone)
        assertEquals(100, r.merged.registeredAt)
        assertEquals(999, r.merged.lastContactAt)
        assertEquals(listOf("vip", "north"), r.merged.tags)
        assertTrue(r.merged.notes!!.contains("خریدار") && r.merged.notes!!.contains("فروشنده"))
        assertEquals("a", r.retired.mergedIntoId)
        assertTrue(r.retired.deleted)
        assertTrue("mobile" in r.conflicts)
    }

    @Test fun cannotMergeAcrossAgencies() {
        assertFailsWith<IllegalArgumentException> { CustomerMerge.merge(Fx.customer("a"), Fx.customer("b").copy(agencyId = "x"), 1) }
    }
}
