package ir.melkcrm.core.duplicates

import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.text.PersianText
import ir.melkcrm.core.text.PhoneNumbers

enum class DuplicateStrength { SAME_PHONE, SIMILAR_NAME_SAME_CITY }

data class DuplicateCandidate(val existing: Customer, val strength: DuplicateStrength, val matchedPhone: String? = null)

object DuplicateDetector {
    private fun phones(c: Customer): Set<String> = setOfNotNull(PhoneNumbers.normalize(c.mobile), PhoneNumbers.normalize(c.altPhone))

    /** Finds possible duplicates of [candidate] among [existing]; phone matches first. Merged and deleted records are ignored. */
    fun find(candidate: Customer, existing: List<Customer>): List<DuplicateCandidate> {
        val mine = phones(candidate)
        val name = PersianText.normalize(candidate.fullName).replace(" ", "")
        return existing.asSequence()
            .filter { it.id != candidate.id && !it.deleted && it.mergedIntoId == null && it.agencyId == candidate.agencyId }
            .mapNotNull { other ->
                val shared = phones(other).intersect(mine).firstOrNull()
                when {
                    shared != null -> DuplicateCandidate(other, DuplicateStrength.SAME_PHONE, shared)
                    name.length >= 4 && PersianText.normalize(other.fullName).replace(" ", "") == name &&
                        PersianText.sameName(other.city, candidate.city) -> DuplicateCandidate(other, DuplicateStrength.SIMILAR_NAME_SAME_CITY)
                    else -> null
                }
            }
            .sortedBy { it.strength.ordinal }
            .toList()
    }

    /** Groups an entire customer list into clusters sharing a phone number. */
    fun clusters(customers: List<Customer>): List<List<Customer>> {
        val byPhone = HashMap<String, MutableList<Customer>>()
        customers.filter { !it.deleted && it.mergedIntoId == null }.forEach { c ->
            phones(c).forEach { byPhone.getOrPut(it) { mutableListOf() } += c }
        }
        val seen = HashSet<String>()
        return byPhone.values.filter { it.size > 1 }.mapNotNull { group ->
            val ids = group.map { it.id }.toSet()
            if (ids.all { it in seen }) null else group.distinctBy { it.id }.also { seen += ids }
        }
    }
}

data class MergeResult(
    val merged: Customer,
    /** The secondary record, marked as merged (kept for audit, never hard-deleted). */
    val retired: Customer,
    /** Fields where both records had different non-empty values; the primary's value was kept. */
    val conflicts: List<String>,
)

/**
 * Merges [secondary] into [primary] without losing data. Related records (requirements, properties,
 * activities, offers…) must then be re-pointed from secondary.id to primary.id by the repository,
 * in the same database transaction.
 */
object CustomerMerge {
    fun merge(primary: Customer, secondary: Customer, now: Long): MergeResult {
        require(primary.id != secondary.id) { "Cannot merge a customer with itself" }
        require(primary.agencyId == secondary.agencyId) { "Cannot merge customers of different agencies" }
        val conflicts = mutableListOf<String>()
        fun <T> pick(field: String, a: T?, b: T?): T? {
            if (a != null && b != null && a != b && !(a is String && a.isBlank())) conflicts += field
            return if (a == null || (a is String && a.isBlank())) b else a
        }
        val pPhones = setOfNotNull(PhoneNumbers.normalize(primary.mobile), PhoneNumbers.normalize(primary.altPhone))
        val secondaryPhone = listOfNotNull(PhoneNumbers.normalize(secondary.mobile), PhoneNumbers.normalize(secondary.altPhone)).firstOrNull { it !in pPhones }
        val altPhone = when {
            !primary.altPhone.isNullOrBlank() -> {
                if (secondaryPhone != null) conflicts += "altPhone"
                primary.altPhone
            }
            else -> secondaryPhone
        }
        val notes = listOfNotNull(primary.notes?.takeIf { it.isNotBlank() }, secondary.notes?.takeIf { it.isNotBlank() }).distinct().joinToString("\n---\n").ifBlank { null }
        val merged = primary.copy(
            mobile = pick("mobile", primary.mobile, secondary.mobile),
            altPhone = altPhone,
            email = pick("email", primary.email, secondary.email),
            roles = primary.roles + secondary.roles,
            preferredContact = pick("preferredContact", primary.preferredContact, secondary.preferredContact),
            province = pick("province", primary.province, secondary.province),
            city = pick("city", primary.city, secondary.city),
            neighborhood = pick("neighborhood", primary.neighborhood, secondary.neighborhood),
            address = pick("address", primary.address, secondary.address),
            source = pick("source", primary.source, secondary.source),
            assignedAgentId = pick("assignedAgentId", primary.assignedAgentId, secondary.assignedAgentId),
            priority = maxOf(primary.priority, secondary.priority),
            registeredAt = minOf(primary.registeredAt, secondary.registeredAt),
            lastContactAt = listOfNotNull(primary.lastContactAt, secondary.lastContactAt).maxOrNull(),
            nextFollowUpDate = listOfNotNull(primary.nextFollowUpDate, secondary.nextFollowUpDate).minOrNull(),
            notes = notes,
            tags = (primary.tags + secondary.tags).distinct(),
            updatedAt = now,
        )
        val retired = secondary.copy(mergedIntoId = primary.id, deleted = true, updatedAt = now)
        return MergeResult(merged, retired, conflicts)
    }
}
