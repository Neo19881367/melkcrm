package ir.melkcrm.core.sample

import ir.melkcrm.core.Fx
import ir.melkcrm.core.duplicates.DuplicateDetector
import ir.melkcrm.core.matching.BuyerCandidate
import ir.melkcrm.core.matching.MatchingEngine
import ir.melkcrm.core.matching.PropertyCandidate
import ir.melkcrm.core.model.CustomerRole
import ir.melkcrm.core.validation.CustomerValidator
import ir.melkcrm.core.validation.PropertyValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleDataTest {
    private val data = SampleData.generate(nowMillis = Fx.NOW)

    @Test fun deterministic() {
        assertEquals(data, SampleData.generate(nowMillis = Fx.NOW))
    }

    @Test fun recordsAreValid() {
        data.customers.filter { !it.deleted }.forEach { assertTrue(CustomerValidator.validate(it).isValid, "${it.id}: ${CustomerValidator.validate(it).errors}") }
        data.properties.forEach { assertTrue(PropertyValidator.validate(it, Fx.TODAY).isValid, "${it.id}: ${PropertyValidator.validate(it, Fx.TODAY).errors}") }
        assertEquals(data.properties.size, data.properties.map { it.reference }.toSet().size)
    }

    @Test fun specificationExampleRanksBuyersAboveBC() {
        val engine = MatchingEngine()
        val prop = data.properties.first { it.id == "prop-0001" }
        val seller = data.sellers.first { it.propertyId == prop.id }
        val byId = data.customers.associateBy { it.id }
        val buyers = data.requirements.filter { it.id in setOf("req-0001", "req-0002", "req-0003") }
            .map { BuyerCandidate(byId.getValue(it.customerId), it) }
        val results = engine.findBuyers(PropertyCandidate(prop, seller), buyers, Fx.TODAY, includeExcluded = true)
        val a = results.first { it.requirementId == "req-0001" }
        val b = results.first { it.requirementId == "req-0002" }
        val c = results.first { it.requirementId == "req-0003" }
        assertTrue(a.eligible && a.score >= 70, "A ${a.score}")
        assertTrue(b.score < a.score, "B ${b.score}")
        assertTrue(!c.eligible || c.score < b.score, "C ${c.score}")
        assertEquals("req-0001", results.first().requirementId)
    }

    @Test fun containsDeliberateDuplicateAndEdgeCases() {
        assertTrue(DuplicateDetector.clusters(data.customers).isNotEmpty())
        assertTrue(data.properties.any { it.location == null })
        assertTrue(data.requirements.any { it.maxBudget == null && it.dealType == ir.melkcrm.core.model.DealType.SALE })
        assertTrue(data.customers.any { CustomerRole.TENANT in it.roles })
    }

    @Test fun fullMatchRunCompletes() {
        val engine = MatchingEngine()
        val byId = data.customers.associateBy { it.id }
        val sellers = data.sellers.associateBy { it.propertyId }
        val all = engine.matchAll(data.requirements.map { BuyerCandidate(byId.getValue(it.customerId), it) }, data.properties.map { PropertyCandidate(it, sellers[it.id]) }, Fx.TODAY)
        assertEquals(data.requirements.size * data.properties.size, all.size)
        all.forEach { assertEquals(it.score, it.components.sumOf { c -> c.points }) }
        assertTrue(all.count { it.eligible } > 10)
    }
}
