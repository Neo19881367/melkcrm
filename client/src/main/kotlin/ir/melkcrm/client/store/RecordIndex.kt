package ir.melkcrm.client.store

import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.text.PersianText
import ir.melkcrm.core.text.PhoneNumbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The indexed columns of a record, derived from its JSON. */
data class IndexFields(
    val id: String,
    val agencyId: String?,
    val updatedAt: Long?,
    val deleted: Boolean,
    val customerId: String?,
    val propertyId: String?,
    val status: String?,
    val searchText: String,
)

object RecordIndex {
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    /** Record id: most entities carry `id`; property contacts and match records have composite keys. */
    fun idOf(type: EntityType, obj: JsonObject): String = when (type) {
        EntityType.PROPERTY_CONTACT -> listOf(obj.str("propertyId"), obj.str("customerId"), obj.str("role")).joinToString("|")
        EntityType.MATCH_RECORD -> requireNotNull(obj.str("key")) { "match record without key" }
        else -> requireNotNull(obj.str("id")) { "$type without id" }
    }

    fun of(type: EntityType, obj: JsonObject): IndexFields {
        val customerId = when (type) {
            EntityType.CUSTOMER -> obj.str("id")
            EntityType.PROPERTY -> obj.str("ownerCustomerId")
            EntityType.DEAL, EntityType.OFFER, EntityType.TRANSACTION -> obj.str("buyerCustomerId")
            EntityType.APPOINTMENT -> obj.strings("customerIds").firstOrNull()
            else -> obj.str("customerId")
        }
        val propertyId = if (type == EntityType.PROPERTY) obj.str("id") else obj.str("propertyId")
        val status = when (type) {
            EntityType.DEAL -> obj.str("stage")
            EntityType.MATCH_RECORD -> obj.str("reviewStatus")
            else -> obj.str("status")
        }
        return IndexFields(
            id = idOf(type, obj),
            agencyId = obj.str("agencyId"),
            updatedAt = obj.long("updatedAt") ?: obj.long("createdAt"),
            deleted = obj.bool("deleted") ?: false,
            customerId = customerId,
            propertyId = propertyId,
            status = status,
            searchText = searchText(type, obj),
        )
    }

    /** Text indexed for search. Phone numbers are added in canonical and plain-digit forms. */
    fun searchText(type: EntityType, obj: JsonObject): String {
        val parts = mutableListOf<String?>()
        when (type) {
            EntityType.CUSTOMER -> {
                parts += obj.str("fullName")
                for (k in listOf("mobile", "altPhone")) {
                    val raw = obj.str(k) ?: continue
                    val canonical = PhoneNumbers.normalize(raw)
                    parts += canonical ?: Digits.toAscii(raw)
                    canonical?.let { parts += it.removePrefix("0") }
                }
                parts += listOf(obj.str("email"), obj.str("city"), obj.str("neighborhood"), obj.str("notes"))
                parts += obj.strings("tags")
            }
            EntityType.PROPERTY -> {
                val ref = obj.str("reference")
                parts += listOf(ref, ref?.removePrefix("IR-"), obj.str("title"), obj.str("city"), obj.str("neighborhood"), obj.str("address"), obj.str("description"))
            }
            EntityType.BUYER_REQUIREMENT -> {
                parts += listOf(obj.str("title"), obj.str("notes"))
                parts += obj.strings("cities") + obj.strings("neighborhoods")
            }
            EntityType.ACTIVITY -> parts += listOf(obj.str("notes"), obj.str("nextAction"))
            EntityType.TASK, EntityType.APPOINTMENT -> parts += listOf(obj.str("title"), obj.str("notes"), obj.str("location"))
            EntityType.NOTE -> parts += obj.str("text")
            else -> Unit
        }
        return normalizeForSearch(parts.filterNotNull().joinToString(" "))
    }

    private val punctuation = Regex("[,،;:()\\[\\]\"'/\\\\.\\-]")

    /** Normalization applied to both indexed text and queries, so "IR-000123" finds "IR-000123". */
    fun normalizeForSearch(text: String): String = PersianText.normalize(text.replace(punctuation, " "))

    /** Agent responsible for the record, as the server uses it for access checks. */
    fun ownAgentField(type: EntityType): String? = when (type) {
        EntityType.CUSTOMER, EntityType.PROPERTY, EntityType.TASK, EntityType.SELLER_PROFILE -> "assignedAgentId"
        EntityType.ACTIVITY, EntityType.APPOINTMENT, EntityType.DEAL, EntityType.OFFER, EntityType.TRANSACTION, EntityType.COMMISSION -> "agentId"
        EntityType.NOTE -> "authorId"
        EntityType.BUYER_REQUIREMENT, EntityType.MATCH_RECORD, EntityType.PROPERTY_CONTACT -> null
    }

    fun agentOf(type: EntityType, obj: JsonObject): String? = ownAgentField(type)?.let { obj.str(it) }

    fun withField(obj: JsonObject, key: String, value: JsonElement): JsonObject = JsonObject(obj + (key to value))
}
