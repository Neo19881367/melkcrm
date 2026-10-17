package ir.melkcrm.client.sample

import ir.melkcrm.client.data.RecordWriter
import ir.melkcrm.core.model.*
import ir.melkcrm.core.sample.SampleData
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.RecordCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Loads the synthetic Iranian dataset for development and demos. Only allowed on a device-only
 * installation; sample records are flagged, never queued for upload, and removed when signing in.
 */
class SampleLoader(private val w: RecordWriter) {
    sealed interface Result {
        data class Loaded(val customers: Int, val properties: Int, val requirements: Int) : Result
        data object NotAllowedWhileSignedIn : Result
    }

    suspend fun load(): Result {
        if (w.session.session.value.signedIn) return Result.NotAllowedWhileSignedIn
        val d = SampleData.generate(nowMillis = w.now())
        val agency = w.user.agencyId
        suspend fun <T> put(type: EntityType, items: List<T>, agentOf: (T) -> String? = { null }) {
            for (item in items) {
                val obj = RecordCodec.json.encodeToJsonElement(RecordCodec.serializer<T>(type), item).jsonObject
                val relabeled = if ("agencyId" in obj) JsonObject(obj + ("agencyId" to JsonPrimitive(agency))) else obj
                val value: T = RecordCodec.json.decodeFromJsonElement(RecordCodec.serializer<T>(type), relabeled)
                w.persist(type, value, assignedAgentId = agentOf(item), audit = false, sample = true, enqueue = false)
            }
        }
        val customerAgent = d.customers.associate { it.id to it.assignedAgentId }
        w.store.transaction {
            put(EntityType.CUSTOMER, d.customers)
            put(EntityType.PROPERTY, d.properties)
            put(EntityType.BUYER_REQUIREMENT, d.requirements) { customerAgent[it.customerId] }
            put(EntityType.SELLER_PROFILE, d.sellers)
            put(EntityType.ACTIVITY, d.activities)
            put(EntityType.TASK, d.tasks)
            put(EntityType.APPOINTMENT, d.appointments)
        }
        w.session.setLocalUsers(d.users.map { it.copy(agencyId = agency) })
        if (w.agency.settings.commissionRules.isEmpty()) {
            w.session.updateAgencyConfig(w.agency.settings.copy(commissionRules = d.commissionRules.map { it.copy(agencyId = agency) }), w.agency.matching)
        }
        return Result.Loaded(d.customers.size, d.properties.size, d.requirements.size)
    }
}
