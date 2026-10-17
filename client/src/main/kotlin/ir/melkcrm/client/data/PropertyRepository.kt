package ir.melkcrm.client.data

import ir.melkcrm.client.store.ImageUploadState
import ir.melkcrm.client.store.LocalImage
import ir.melkcrm.core.geo.Geo
import ir.melkcrm.core.model.*
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.text.PersianText
import ir.melkcrm.core.validation.PropertyValidator
import ir.melkcrm.core.validation.Severity
import ir.melkcrm.core.validation.ValidationIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PropertyRepository(private val w: RecordWriter) {

    val properties: Flow<List<Property>> = w.observe(EntityType.PROPERTY)
    val contacts: Flow<List<PropertyContact>> = w.observe(EntityType.PROPERTY_CONTACT)

    suspend fun property(id: String?): Property? = w.load(EntityType.PROPERTY, id)
    suspend fun all(): List<Property> = w.loadAll(EntityType.PROPERTY)
    fun ownedBy(customerId: String): Flow<List<Property>> = properties.map { l -> l.filter { it.ownerCustomerId == customerId } }
    fun contactsOf(propertyId: String): Flow<List<PropertyContact>> = contacts.map { l -> l.filter { it.propertyId == propertyId } }
    fun images(propertyId: String): Flow<List<LocalImage>> = w.store.observeImages(propertyId)

    suspend fun canSeeConfidential(p: Property): Boolean = w.canSeeConfidential(p.agencyId, p.assignedAgentId)

    /** Next free reference among all references known on this device, including archived properties. */
    suspend fun nextReference(): String =
        PropertyReference.next(w.store.all(EntityType.PROPERTY, includeDeleted = true).map { w.decode<Property>(it).reference })

    suspend fun newProperty(): Property {
        val now = w.now()
        return Property(
            id = w.newId(), agencyId = w.user.agencyId, reference = nextReference(), title = "",
            category = PropertyCategory.APARTMENT, dealTypes = setOf(DealType.SALE), assignedAgentId = w.user.id,
            acquiredDate = w.today(), createdAt = now, updatedAt = now,
        )
    }

    /**
     * Saves a property. Status changes are recorded in the history. The confidential notes cannot be
     * changed by users without confidential access; the stored value is kept.
     */
    suspend fun save(input: Property): SaveOutcome<Property> {
        val previous = property(input.id)
        val canConf = w.canSeeConfidential(input.agencyId, input.assignedAgentId)
        val p = input.copy(
            reference = input.reference.trim().uppercase(),
            title = input.title.trim(),
            confidentialNotes = if (canConf) input.confidentialNotes?.ifBlank { null } else previous?.confidentialNotes,
            updatedAt = w.now(),
        )
        val result = PropertyValidator.validate(p, w.today())
        if (!result.isValid) return SaveOutcome.Invalid(result.issues)
        w.writeCheck(EntityType.PROPERTY, p.id, p.assignedAgentId, p.agencyId, deleting = p.deleted)?.let { return SaveOutcome.Forbidden(it) }
        val warnings = result.warnings.toMutableList()
        val sameRef = w.store.all(EntityType.PROPERTY, includeDeleted = true).map { w.decode<Property>(it) }.any { it.id != p.id && it.reference == p.reference }
        if (sameRef) warnings += ValidationIssue("reference", "duplicate_reference", Severity.WARNING)
        w.persist(EntityType.PROPERTY, p)
        return SaveOutcome.Saved(p, warnings)
    }

    /** Properties are never deleted by default: closing a listing archives it and keeps its history. */
    suspend fun setStatus(id: String, status: PropertyStatus): SaveOutcome<Property> {
        val p = property(id) ?: return SaveOutcome.Forbidden("not_found")
        return save(p.copy(status = status))
    }

    suspend fun archive(id: String) = setStatus(id, PropertyStatus.ARCHIVED)

    // ------------------------------------------------------------------ contacts

    suspend fun addContact(propertyId: String, customerId: String, role: PropertyContactRole, note: String? = null): SaveOutcome<PropertyContact> {
        val p = property(propertyId) ?: return SaveOutcome.Forbidden("not_found")
        w.writeCheck(EntityType.PROPERTY, p.id, p.assignedAgentId, p.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        val pc = PropertyContact(propertyId, customerId, role, note?.ifBlank { null })
        w.persist(EntityType.PROPERTY_CONTACT, pc, assignedAgentId = p.assignedAgentId)
        return SaveOutcome.Saved(pc)
    }

    suspend fun removeContact(pc: PropertyContact): SaveOutcome<PropertyContact> {
        val p = property(pc.propertyId) ?: return SaveOutcome.Forbidden("not_found")
        w.writeCheck(EntityType.PROPERTY, p.id, p.assignedAgentId, p.agencyId)?.let { return SaveOutcome.Forbidden(it) }
        w.persist(EntityType.PROPERTY_CONTACT, pc, assignedAgentId = p.assignedAgentId, deleted = true)
        return SaveOutcome.Saved(pc)
    }

    // ------------------------------------------------------------------ photos

    /** Registers a photo already copied into app-private storage; it is uploaded on the next sync. */
    suspend fun addImage(propertyId: String, localPath: String, caption: String? = null): LocalImage {
        val existing = w.store.images(propertyId)
        val img = LocalImage(w.newId(), propertyId, localPath, null, caption, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1, w.now(), ImageUploadState.PENDING)
        w.store.putImage(img)
        return img
    }

    /** Removes the photo from the listing. A server copy stays in storage but is no longer referenced. */
    suspend fun removeImage(image: LocalImage) {
        w.store.removeImage(image.id)
        val remote = image.remoteId ?: return
        val p = property(image.propertyId) ?: return
        if (remote in p.imageIds) w.persist(EntityType.PROPERTY, p.copy(imageIds = p.imageIds - remote, updatedAt = w.now()))
    }

    /** Called by sync after an upload: the property now lists the server image id. */
    suspend fun attachUploadedImage(image: LocalImage, remoteId: String) {
        w.store.putImage(image.copy(remoteId = remoteId, uploadState = ImageUploadState.UPLOADED, lastError = null))
        val p = property(image.propertyId) ?: return
        if (remoteId !in p.imageIds) w.persist(EntityType.PROPERTY, p.copy(imageIds = p.imageIds + remoteId, updatedAt = w.now()), audit = false)
    }

    // ------------------------------------------------------------------ search and map

    suspend fun search(query: String, includeClosed: Boolean): List<Property> {
        val q = ir.melkcrm.client.store.RecordIndex.normalizeForSearch(query)
        val list = if (q.isBlank()) all() else w.store.searchIds(EntityType.PROPERTY, q).mapNotNull { property(it) }
        return list.filter { includeClosed || !it.status.isClosed }.sortedByDescending { it.updatedAt }
    }

    data class Nearby(val property: Property, val distanceKm: Double)

    /** Properties within [radiusKm] of [center], nearest first. Properties without coordinates are skipped (and counted). */
    suspend fun nearby(center: GeoPoint, radiusKm: Double, includeClosed: Boolean = false): Pair<List<Nearby>, Int> {
        val all = all().filter { includeClosed || !it.status.isClosed }
        val missing = all.count { it.location == null }
        val near = all.mapNotNull { p -> p.location?.let { Nearby(p, Geo.distanceKm(center, it.point)) } }
            .filter { it.distanceKm <= radiusKm }.sortedBy { it.distanceKm }
        return near to missing
    }
}
