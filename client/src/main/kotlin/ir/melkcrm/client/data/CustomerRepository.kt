package ir.melkcrm.client.data

import ir.melkcrm.core.duplicates.CustomerMerge
import ir.melkcrm.core.duplicates.DuplicateCandidate
import ir.melkcrm.core.duplicates.DuplicateDetector
import ir.melkcrm.core.duplicates.MergeResult
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.model.*
import ir.melkcrm.core.permissions.Access
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.text.PersianText
import ir.melkcrm.core.text.PhoneNumbers
import ir.melkcrm.core.validation.BuyerRequirementValidator
import ir.melkcrm.core.validation.CustomerValidator
import ir.melkcrm.core.validation.SellerProfileValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CustomerRepository(private val w: RecordWriter) {

    val customers: Flow<List<Customer>> = w.observe<Customer>(EntityType.CUSTOMER).map { list -> list.filter { it.mergedIntoId == null } }
    val requirements: Flow<List<BuyerRequirement>> = w.observe(EntityType.BUYER_REQUIREMENT)
    val sellers: Flow<List<SellerProfile>> = w.observe(EntityType.SELLER_PROFILE)
    val notes: Flow<List<Note>> = w.observe(EntityType.NOTE)

    suspend fun customer(id: String?): Customer? = w.load(EntityType.CUSTOMER, id)
    suspend fun allCustomers(): List<Customer> = w.loadAll<Customer>(EntityType.CUSTOMER).filter { it.mergedIntoId == null }
    suspend fun requirement(id: String?): BuyerRequirement? = w.load(EntityType.BUYER_REQUIREMENT, id)
    suspend fun seller(id: String?): SellerProfile? = w.load(EntityType.SELLER_PROFILE, id)

    fun requirementsOf(customerId: String): Flow<List<BuyerRequirement>> = requirements.map { l -> l.filter { it.customerId == customerId } }
    fun sellerProfilesOf(customerId: String): Flow<List<SellerProfile>> = sellers.map { l -> l.filter { it.customerId == customerId } }
    fun notesOf(customerId: String): Flow<List<Note>> = notes.map { l -> l.filter { it.customerId == customerId }.sortedByDescending { it.createdAt } }

    /** Access the current user has to a customer: MASKED means contact details were withheld by the server. */
    suspend fun access(customerId: String): Access = w.store.get(EntityType.CUSTOMER, customerId)?.let { w.access(it) } ?: Access.NONE

    fun newCustomer(roles: Set<CustomerRole> = emptySet()): Customer {
        val now = w.now()
        return Customer(id = w.newId(), agencyId = w.user.agencyId, fullName = "", roles = roles, assignedAgentId = w.user.id, registeredAt = now, updatedAt = now)
    }

    suspend fun findDuplicates(c: Customer): List<DuplicateCandidate> = DuplicateDetector.find(c, allCustomers())

    /**
     * Saves a customer. Phone numbers are stored in canonical form. A new customer (or a changed phone
     * number) that looks like an existing one is not saved until the agent confirms ([confirmDuplicate]).
     */
    suspend fun save(input: Customer, confirmDuplicate: Boolean = false): SaveOutcome<Customer> {
        val c = input.copy(
            fullName = input.fullName.trim(),
            mobile = input.mobile?.let { PhoneNumbers.normalize(it) ?: it.trim() }?.ifBlank { null },
            altPhone = input.altPhone?.let { PhoneNumbers.normalize(it) ?: it.trim() }?.ifBlank { null },
            email = input.email?.trim()?.ifBlank { null },
            updatedAt = w.now(),
        )
        val result = CustomerValidator.validate(c)
        if (!result.isValid) return SaveOutcome.Invalid(result.issues)
        w.writeCheck(EntityType.CUSTOMER, c.id, c.assignedAgentId, c.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val previous = customer(c.id)
        if (!confirmDuplicate && (previous == null || previous.mobile != c.mobile || previous.altPhone != c.altPhone || previous.fullName != c.fullName)) {
            val dups = findDuplicates(c)
            if (dups.isNotEmpty()) return SaveOutcome.PossibleDuplicates(dups)
        }
        w.persist(EntityType.CUSTOMER, c)
        if (previous != null && previous.assignedAgentId != c.assignedAgentId) cascadeAgent(c)
        return SaveOutcome.Saved(c, result.warnings)
    }

    /** Requirements and stored matches follow their customer's agent. */
    private suspend fun cascadeAgent(c: Customer) {
        for (r in w.store.byCustomer(EntityType.BUYER_REQUIREMENT, c.id)) w.persist(EntityType.BUYER_REQUIREMENT, w.decode<BuyerRequirement>(r), assignedAgentId = c.assignedAgentId, audit = false)
        for (m in w.store.byCustomer(EntityType.MATCH_RECORD, c.id)) w.persist(EntityType.MATCH_RECORD, w.decode<MatchRecord>(m), assignedAgentId = c.assignedAgentId, audit = false)
    }

    suspend fun delete(id: String): SaveOutcome<Customer> {
        val c = customer(id) ?: return SaveOutcome.Forbidden("not_found")
        w.writeCheck(EntityType.CUSTOMER, id, c.assignedAgentId, c.agencyId, deleting = true)?.let { return SaveOutcome.Forbidden(it) }
        val d = c.copy(deleted = true, updatedAt = w.now())
        w.persist(EntityType.CUSTOMER, d)
        return SaveOutcome.Saved(d)
    }

    /**
     * Merges [secondaryId] into [primaryId] and moves every related record to the primary customer,
     * in one local transaction. The secondary record is kept (marked merged) for the audit trail.
     */
    suspend fun merge(primaryId: String, secondaryId: String): SaveOutcome<MergeResult> {
        if (!w.agency.policy.canMerge(w.user)) return SaveOutcome.Forbidden("merge_forbidden")
        val primary = customer(primaryId) ?: return SaveOutcome.Forbidden("not_found")
        val secondary = customer(secondaryId) ?: return SaveOutcome.Forbidden("not_found")
        val result = CustomerMerge.merge(primary, secondary, w.now())
        w.store.transaction {
            w.persist(EntityType.CUSTOMER, result.merged, auditAction = AuditAction.MERGE, note = "merged ${secondary.id}")
            w.persist(EntityType.CUSTOMER, result.retired, auditAction = AuditAction.MERGE, note = "merged into ${primary.id}")
            repoint(secondaryId, primaryId)
        }
        return SaveOutcome.Saved(result)
    }

    private suspend fun repoint(from: String, to: String) {
        val now = w.now()
        val agent = customer(to)?.assignedAgentId
        suspend fun <T> each(type: EntityType, block: (T) -> T?) {
            for (rec in w.store.all(type)) {
                val updated = block(w.decode(rec)) ?: continue
                // Requirements and matches inherit the agent of the (remaining) customer.
                val inherited = if (type == EntityType.BUYER_REQUIREMENT || type == EntityType.MATCH_RECORD) agent else null
                w.persist(type, updated, assignedAgentId = inherited, note = "merge")
            }
        }
        each<BuyerRequirement>(EntityType.BUYER_REQUIREMENT) { if (it.customerId == from) it.copy(customerId = to, updatedAt = now) else null }
        each<SellerProfile>(EntityType.SELLER_PROFILE) { if (it.customerId == from) it.copy(customerId = to, updatedAt = now) else null }
        each<Property>(EntityType.PROPERTY) { if (it.ownerCustomerId == from) it.copy(ownerCustomerId = to, updatedAt = now) else null }
        each<Activity>(EntityType.ACTIVITY) { if (it.customerId == from) it.copy(customerId = to, updatedAt = now) else null }
        each<TaskItem>(EntityType.TASK) { if (it.customerId == from) it.copy(customerId = to, updatedAt = now) else null }
        each<Appointment>(EntityType.APPOINTMENT) { a -> if (from in a.customerIds) a.copy(customerIds = a.customerIds.map { if (it == from) to else it }.distinct(), updatedAt = now) else null }
        each<Deal>(EntityType.DEAL) { d ->
            if (d.buyerCustomerId == from || d.sellerCustomerId == from)
                d.copy(buyerCustomerId = if (d.buyerCustomerId == from) to else d.buyerCustomerId, sellerCustomerId = if (d.sellerCustomerId == from) to else d.sellerCustomerId, updatedAt = now)
            else null
        }
        each<Offer>(EntityType.OFFER) { o ->
            if (o.buyerCustomerId == from || o.sellerCustomerId == from)
                o.copy(buyerCustomerId = if (o.buyerCustomerId == from) to else o.buyerCustomerId, sellerCustomerId = if (o.sellerCustomerId == from) to else o.sellerCustomerId, updatedAt = now)
            else null
        }
        each<Transaction>(EntityType.TRANSACTION) { t ->
            if (t.buyerCustomerId == from || t.sellerCustomerId == from)
                t.copy(buyerCustomerId = if (t.buyerCustomerId == from) to else t.buyerCustomerId, sellerCustomerId = if (t.sellerCustomerId == from) to else t.sellerCustomerId, updatedAt = now)
            else null
        }
        each<Note>(EntityType.NOTE) { if (it.customerId == from) it.copy(customerId = to) else null }
        each<MatchRecord>(EntityType.MATCH_RECORD) { if (it.customerId == from) it.copy(customerId = to, updatedAt = now) else null }
        // Property contacts are keyed by customer: retire the old link and create the new one.
        for (rec in w.store.all(EntityType.PROPERTY_CONTACT)) {
            val pc = w.decode<PropertyContact>(rec)
            if (pc.customerId != from) continue
            w.persist(EntityType.PROPERTY_CONTACT, pc, assignedAgentId = rec.assignedAgentId, deleted = true, note = "merge")
            w.persist(EntityType.PROPERTY_CONTACT, pc.copy(customerId = to), assignedAgentId = rec.assignedAgentId, note = "merge")
        }
    }

    // ------------------------------------------------------------------ buyer requirements

    fun newRequirement(customerId: String, dealType: DealType = DealType.SALE): BuyerRequirement {
        val now = w.now()
        return BuyerRequirement(id = w.newId(), agencyId = w.user.agencyId, customerId = customerId, dealType = dealType, createdAt = now, updatedAt = now)
    }

    /** Saves a requirement and gives the customer the buyer or tenant role if they did not have it. */
    suspend fun saveRequirement(input: BuyerRequirement): SaveOutcome<BuyerRequirement> {
        val r = input.copy(
            cities = input.cities.clean(), neighborhoods = input.neighborhoods.clean(), alternativeNeighborhoods = input.alternativeNeighborhoods.clean(),
            excludedCities = input.excludedCities.clean(), excludedNeighborhoods = input.excludedNeighborhoods.clean(), provinces = input.provinces.clean(),
            updatedAt = w.now(),
        )
        val result = BuyerRequirementValidator.validate(r)
        if (!result.isValid) return SaveOutcome.Invalid(result.issues)
        val customer = customer(r.customerId) ?: return SaveOutcome.Forbidden("customer_not_found")
        w.writeCheck(EntityType.CUSTOMER, customer.id, customer.assignedAgentId, customer.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val role = if (r.dealType == DealType.SALE) CustomerRole.BUYER else CustomerRole.TENANT
        if (role !in customer.roles) w.persist(EntityType.CUSTOMER, customer.copy(roles = customer.roles + role, updatedAt = w.now()), note = "role added by requirement")
        w.persist(EntityType.BUYER_REQUIREMENT, r, assignedAgentId = customer.assignedAgentId)
        return SaveOutcome.Saved(r, result.warnings)
    }

    // ------------------------------------------------------------------ sellers

    fun newSeller(customerId: String, propertyId: String): SellerProfile =
        SellerProfile(id = w.newId(), agencyId = w.user.agencyId, customerId = customerId, propertyId = propertyId, assignedAgentId = w.user.id, updatedAt = w.now())

    suspend fun sellerForProperty(propertyId: String): SellerProfile? =
        w.store.byProperty(EntityType.SELLER_PROFILE, propertyId).map { w.decode<SellerProfile>(it) }
            .sortedWith(compareBy<SellerProfile> { it.status == SellerStatus.WITHDRAWN || it.status == SellerStatus.SOLD }.thenByDescending { it.updatedAt })
            .firstOrNull()

    /**
     * Saves seller instructions. Users without confidential access cannot change the confidential part:
     * the previously stored value is kept (the server enforces the same rule).
     */
    suspend fun saveSeller(input: SellerProfile): SaveOutcome<SellerProfile> {
        val property = w.load<Property>(EntityType.PROPERTY, input.propertyId) ?: return SaveOutcome.Forbidden("property_not_found")
        val customer = customer(input.customerId) ?: return SaveOutcome.Forbidden("customer_not_found")
        val previous = seller(input.id)
        val agent = input.assignedAgentId ?: property.assignedAgentId
        val canConf = w.canSeeConfidential(input.agencyId, agent)
        val s = input.copy(
            assignedAgentId = agent,
            confidential = if (canConf) input.confidential else previous?.confidential ?: SellerConfidential(),
            updatedAt = w.now(),
        )
        w.writeCheck(EntityType.SELLER_PROFILE, s.id, agent, s.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val result = SellerProfileValidator.validate(s, property)
        w.store.transaction {
            val role = if (DealType.SALE in property.dealTypes) CustomerRole.SELLER else CustomerRole.LANDLORD
            if (role !in customer.roles) w.persist(EntityType.CUSTOMER, customer.copy(roles = customer.roles + role, updatedAt = w.now()), note = "role added by seller profile")
            if (property.ownerCustomerId == null) w.persist(EntityType.PROPERTY, property.copy(ownerCustomerId = customer.id, updatedAt = w.now()))
            w.persist(EntityType.SELLER_PROFILE, s)
        }
        return SaveOutcome.Saved(s, result.warnings)
    }

    // ------------------------------------------------------------------ notes

    suspend fun addNote(text: String, customerId: String? = null, propertyId: String? = null): Note? {
        if (text.isBlank()) return null
        val n = Note(w.newId(), w.user.agencyId, customerId, propertyId, text.trim(), w.user.id, w.now())
        w.persist(EntityType.NOTE, n)
        return n
    }

    // ------------------------------------------------------------------ search

    /**
     * Full-text customer search over name, phone numbers, notes and tags, plus customers linked to a
     * property whose reference or title matches, and buyers whose requirement notes match.
     */
    suspend fun search(query: String): List<Customer> {
        val q = ir.melkcrm.client.store.RecordIndex.normalizeForSearch(query)
        if (q.isBlank()) return allCustomers().sortedByDescending { it.updatedAt }
        val ids = LinkedHashSet<String>()
        val digits = Digits.toAscii(query).filter { it.isDigit() || it == '+' }
        val phoneQuery = if (digits.length >= 4 && digits.length >= query.count { !it.isWhitespace() } - 3) {
            PhoneNumbers.normalize(digits) ?: digits.removePrefix("+98").removePrefix("0098").let { if (it.startsWith("9")) "0$it" else it }
        } else null
        ids += w.store.searchIds(EntityType.CUSTOMER, q)
        if (phoneQuery != null) ids += w.store.searchIds(EntityType.CUSTOMER, phoneQuery)
        for (pid in w.store.searchIds(EntityType.PROPERTY, q)) {
            w.store.get(EntityType.PROPERTY, pid)?.customerId?.let { ids += it }
            w.store.byProperty(EntityType.PROPERTY_CONTACT, pid).mapNotNull { it.customerId }.forEach { ids += it }
        }
        for (type in listOf(EntityType.BUYER_REQUIREMENT, EntityType.NOTE, EntityType.ACTIVITY)) {
            for (id in w.store.searchIds(type, q)) w.store.get(type, id)?.customerId?.let { ids += it }
        }
        return ids.mapNotNull { customer(it) }.filter { it.mergedIntoId == null }
    }

    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { PersianText.normalize(it).replace(" ", "") }
}
