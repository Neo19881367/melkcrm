package ir.melkcrm.core.sync

import ir.melkcrm.core.model.*
import ir.melkcrm.core.matching.MatchRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Converts domain entities to [SyncRecord]s and back, splitting confidential fields into their own
 * payload so the server can withhold them. Shared by the app and the backend so both agree on
 * exactly which fields are confidential.
 */
object RecordCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Fields moved to [SyncRecord.confidential]. */
    val confidentialFields: Map<EntityType, Set<String>> = mapOf(
        EntityType.PROPERTY to setOf("confidentialNotes"),
        EntityType.SELLER_PROFILE to setOf("confidential"),
    )

    /** Fields removed when a user only has MASKED access to a customer. */
    val contactFields = setOf("mobile", "altPhone", "email", "address")

    @Suppress("UNCHECKED_CAST")
    fun <T> serializer(type: EntityType): KSerializer<T> = when (type) {
        EntityType.CUSTOMER -> Customer.serializer()
        EntityType.BUYER_REQUIREMENT -> BuyerRequirement.serializer()
        EntityType.SELLER_PROFILE -> SellerProfile.serializer()
        EntityType.PROPERTY -> Property.serializer()
        EntityType.PROPERTY_CONTACT -> PropertyContact.serializer()
        EntityType.ACTIVITY -> Activity.serializer()
        EntityType.TASK -> TaskItem.serializer()
        EntityType.APPOINTMENT -> Appointment.serializer()
        EntityType.DEAL -> Deal.serializer()
        EntityType.OFFER -> Offer.serializer()
        EntityType.TRANSACTION -> Transaction.serializer()
        EntityType.COMMISSION -> CommissionRecord.serializer()
        EntityType.MATCH_RECORD -> MatchRecord.serializer()
        EntityType.NOTE -> Note.serializer()
    } as KSerializer<T>

    fun split(type: EntityType, full: JsonObject): Pair<JsonObject, JsonObject?> {
        val conf = confidentialFields[type] ?: return full to null
        val data = JsonObject(full.filterKeys { it !in conf })
        val c = JsonObject(full.filterKeys { it in conf })
        return data to c.takeIf { it.isNotEmpty() }
    }

    fun merge(data: JsonObject, confidential: JsonObject?): JsonObject =
        if (confidential == null) data else JsonObject(data + confidential)

    fun <T> encode(
        type: EntityType, value: T, id: String, agencyId: String, assignedAgentId: String?,
        version: Long, updatedAt: Long, deleted: Boolean = false,
    ): SyncRecord {
        val full = json.encodeToJsonElement(serializer<T>(type), value).jsonObject
        val (data, conf) = split(type, full)
        return SyncRecord(type, id, agencyId, assignedAgentId, version, updatedAt, deleted, data, conf)
    }

    fun <T> decode(record: SyncRecord): T =
        json.decodeFromJsonElement(serializer<T>(record.type), merge(record.data, record.confidential))

    fun mask(data: JsonObject): JsonObject = JsonObject(data.filterKeys { it !in contactFields } + ("contactMasked" to kotlinx.serialization.json.JsonPrimitive(true)))

    val empty: JsonObject = buildJsonObject { }
}
