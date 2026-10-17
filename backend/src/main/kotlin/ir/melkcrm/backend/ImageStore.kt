package ir.melkcrm.backend

import ir.melkcrm.core.api.*
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.permissions.RecordScope
import ir.melkcrm.core.sync.EntityType
import java.io.File
import java.util.UUID

/**
 * Property photos stored outside the web root under random names, served only through an
 * authenticated endpoint that re-checks access to the property. Only JPEG, PNG and WebP are accepted,
 * verified by magic bytes rather than the client's content type.
 */
class ImageStore(private val cfg: AppConfig, private val db: Db, private val clock: () -> Long) {
    private val root = File(cfg.imageDir).apply { mkdirs() }

    private fun sniff(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size > 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun scopeFor(c: java.sql.Connection, user: AgencyUser, propertyId: String): Pair<RecordScope, Boolean> {
        val stored = RecordRepo.get(c, user.agencyId, EntityType.PROPERTY, propertyId) ?: throw ApiException(404, "not_found")
        val scope = RecordScope(user.agencyId, stored.record.assignedAgentId, stored.teamId, isInventory = true)
        return scope to stored.record.deleted
    }

    fun upload(user: AgencyUser, propertyId: String, bytes: ByteArray, declaredType: String?): ImageUploadResponse {
        if (bytes.size > cfg.maxImageBytes) throw ApiException(413, "image_too_large")
        val type = sniff(bytes) ?: throw ApiException(415, "unsupported_image")
        if (declaredType != null && !declaredType.startsWith("image/")) throw ApiException(415, "unsupported_image")
        return db.tx { c ->
            val (scope, _) = scopeFor(c, user, propertyId)
            if (!SyncService(db, clock).policyFor(c, user.agencyId).canWrite(user, scope)) throw ApiException(403, "forbidden")
            val id = UUID.randomUUID().toString()
            val key = "${user.agencyId}/${UUID.randomUUID()}"
            val f = File(root, key)
            f.parentFile.mkdirs()
            f.writeBytes(bytes)
            c.update("INSERT INTO images(id, agency_id, property_id, content_type, size_bytes, storage_key, uploaded_by, created_at) VALUES (?,?,?,?,?,?,?,?)",
                id, user.agencyId, propertyId, type, bytes.size.toLong(), key, user.id, clock())
            AuditRepo.log(c, user.agencyId, user.id, "PROPERTY_IMAGE", id, AuditAction.CREATE, clock(), propertyId)
            ImageUploadResponse(id, bytes.size.toLong())
        }
    }

    fun download(user: AgencyUser, id: String): Pair<String, ByteArray> = db.tx { c ->
        val row = c.queryOne("SELECT * FROM images WHERE id = ? AND agency_id = ?", id, user.agencyId) {
            Triple(it.getString("property_id"), it.getString("content_type"), it.getString("storage_key"))
        } ?: throw ApiException(404, "not_found")
        val (scope, _) = scopeFor(c, user, row.first)
        if (!SyncService(db, clock).policyFor(c, user.agencyId).canRead(user, scope)) throw ApiException(404, "not_found")
        row.second to File(root, row.third).readBytes()
    }
}
