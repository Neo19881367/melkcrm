package ir.melkcrm.client.sync

import ir.melkcrm.client.data.PropertyRepository
import ir.melkcrm.client.remote.RemoteApi
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.client.session.SessionManager
import ir.melkcrm.client.store.ConflictEntry
import ir.melkcrm.client.store.ImageUploadState
import ir.melkcrm.client.store.LocalRecord
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.store.OutboxEntry
import ir.melkcrm.client.store.RecordIndex
import ir.melkcrm.core.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val imagesUploaded: Int = 0,
    val changedTypes: Set<EntityType> = emptySet(),
    val error: String? = null,
)

data class SyncStatus(val running: Boolean = false, val lastSyncAt: Long? = null, val lastError: String? = null)

/** Reads and writes local photo files; implemented by the platform. */
interface ImageFiles {
    suspend fun read(path: String): ByteArray?
    fun contentType(path: String): String
}

/**
 * Offline-first synchronization:
 *  1. push queued local changes, each based on the server version it was made on;
 *  2. pull server changes since the last cursor;
 *  3. upload new photos for properties the server already knows.
 *
 * A server change never silently replaces a local change that has not been pushed, and a local
 * change never silently replaces a newer server version: both cases become a [ConflictEntry] that
 * the agent resolves by choosing a version.
 */
class SyncEngine(
    private val store: LocalStore,
    private val session: SessionManager,
    private val properties: PropertyRepository,
    private val images: ImageFiles,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = RecordCodec.json
    private val lock = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun sync(): SyncReport = lock.withLock {
        val api = session.api() ?: return SyncReport(error = "not_signed_in")
        if (session.session.value.authExpired) return SyncReport(error = "session_expired")
        _status.update { it.copy(running = true) }
        var report = SyncReport()
        try {
            report = push(api, report)
            report = pull(api, report)
            val uploaded = uploadImages(api)
            if (uploaded > 0) report = push(api, report.copy(imagesUploaded = uploaded))
            runCatching { session.refreshAgency() }
            _status.value = SyncStatus(false, clock(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            if (e.isAuth) session.onSessionExpired()
            report = report.copy(error = if (e.isNetwork) "network" else e.code)
            _status.value = SyncStatus(false, _status.value.lastSyncAt, report.error)
        } finally {
            _status.update { it.copy(running = false) }
        }
        report
    }

    // ------------------------------------------------------------------ push

    fun toSyncRecord(rec: LocalRecord, baseVersion: Long): SyncRecord {
        val full = json.parseToJsonElement(rec.json).jsonObject
        val (data, conf) = RecordCodec.split(rec.type, full)
        return SyncRecord(rec.type, rec.id, rec.agencyId, rec.assignedAgentId, baseVersion, rec.updatedAt, rec.deleted, data, conf)
    }

    private suspend fun push(api: RemoteApi, start: SyncReport): SyncReport {
        var report = start
        val now = clock()
        val ready = store.outbox().filter { !it.rejected && it.nextAttemptAt <= now }
        for (batch in ready.chunked(200)) {
            val sent = HashMap<String, OutboxEntry>()
            val changes = batch.mapNotNull { e ->
                val rec = store.get(e.type, e.id)
                if (rec == null || rec.sample) {
                    store.removeOutbox(e.type, e.id)
                    null
                } else {
                    sent[e.changeId] = e
                    PushChange(toSyncRecord(rec, e.baseVersion), e.baseVersion, e.changeId)
                }
            }
            if (changes.isEmpty()) continue
            val results = try {
                api.push(changes)
            } catch (e: RemoteException) {
                if (e.isNetwork || e.status >= 500) backoff(sent.values, e.code)
                throw e
            }
            for (r in results) {
                val entry = sent[r.changeId] ?: continue
                report = when (r.outcome) {
                    PushOutcome.APPLIED, PushOutcome.DUPLICATE -> {
                        applied(entry, r.newVersion ?: entry.baseVersion + 1)
                        report.copy(pushed = report.pushed + 1)
                    }
                    PushOutcome.CONFLICT -> {
                        if (r.server == null && r.error == "concurrent_write") {
                            backoff(listOf(entry), "concurrent_write")
                            report
                        } else {
                            conflict(entry, r.server)
                            report.copy(conflicts = report.conflicts + 1)
                        }
                    }
                    PushOutcome.REJECTED -> {
                        store.transaction {
                            store.putOutbox(entry.copy(rejected = true, lastError = r.error, attempts = entry.attempts + 1))
                            store.get(entry.type, entry.id)?.let { store.put(it.copy(syncState = SyncState.FAILED)) }
                        }
                        report.copy(rejected = report.rejected + 1)
                    }
                }
            }
        }
        return report
    }

    private suspend fun backoff(entries: Collection<OutboxEntry>, error: String) {
        val now = clock()
        for (e in entries) {
            val current = store.outboxEntry(e.type, e.id) ?: continue
            if (current.changeId != e.changeId) continue
            store.putOutbox(current.copy(attempts = current.attempts + 1, nextAttemptAt = now + RetryPolicy.delayMillis(current.attempts), lastError = error))
        }
    }

    private suspend fun applied(entry: OutboxEntry, newVersion: Long) = store.transaction {
        val rec = store.get(entry.type, entry.id) ?: return@transaction
        val current = store.outboxEntry(entry.type, entry.id)
        val json2 = withVersion(rec.json, newVersion)
        if (current?.changeId == entry.changeId) {
            store.removeOutbox(entry.type, entry.id)
            store.put(rec.copy(version = newVersion, json = json2, syncState = SyncState.SYNCED))
        } else {
            // Edited again while the push was in flight: the queued change now builds on the new version.
            current?.let { store.putOutbox(it.copy(baseVersion = newVersion)) }
            store.put(rec.copy(version = newVersion, json = json2, syncState = SyncState.PENDING))
        }
    }

    private suspend fun conflict(entry: OutboxEntry, server: SyncRecord?) = store.transaction {
        val rec = store.get(entry.type, entry.id) ?: return@transaction
        store.putConflict(ConflictEntry(entry.type, entry.id, rec.json, server?.takeIf { !isTombstone(it) }, clock()))
        store.removeOutbox(entry.type, entry.id)
        store.put(rec.copy(syncState = SyncState.CONFLICT))
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pull(api: RemoteApi, start: SyncReport): SyncReport {
        var report = start
        var cursor = store.meta(SessionManager.KEY_CURSOR)?.toLongOrNull() ?: 0L
        val changed = HashSet<EntityType>()
        do {
            val page = api.pull(cursor, 500)
            store.transaction {
                for (r in page.records) {
                    when (applyRemote(r)) {
                        Applied.CHANGED -> { report = report.copy(pulled = report.pulled + 1); changed += r.type }
                        Applied.CONFLICT -> { report = report.copy(conflicts = report.conflicts + 1); changed += r.type }
                        Applied.UNCHANGED -> Unit
                    }
                }
                store.setMeta(SessionManager.KEY_CURSOR, page.cursor.toString())
            }
            cursor = page.cursor
        } while (page.hasMore)
        return report.copy(changedTypes = report.changedTypes + changed)
    }

    private enum class Applied { CHANGED, UNCHANGED, CONFLICT }

    private fun isTombstone(r: SyncRecord) = r.deleted && r.data.isEmpty()

    private suspend fun applyRemote(r: SyncRecord): Applied {
        val local = store.get(r.type, r.id)
        if (local != null && local.version >= r.version && !(isTombstone(r) && !local.deleted)) return Applied.UNCHANGED
        val pending = store.outboxEntry(r.type, r.id)
        if (local != null && (pending != null || local.syncState == SyncState.CONFLICT)) {
            // Unpushed local edits on top of an older version: keep both and let the agent decide.
            // A record already in conflict only gets its server copy refreshed; it is not a new conflict.
            val existing = store.conflicts().firstOrNull { it.type == r.type && it.id == r.id }
            store.putConflict(ConflictEntry(r.type, r.id, existing?.localJson ?: local.json, r.takeIf { !isTombstone(it) }, existing?.detectedAt ?: clock()))
            store.removeOutbox(r.type, r.id)
            store.put(local.copy(syncState = SyncState.CONFLICT))
            return if (existing == null) Applied.CONFLICT else Applied.UNCHANGED
        }
        if (isTombstone(r)) {
            // Deleted or no longer visible to this user: remove the local copy and anything queued for it.
            if (local == null) return Applied.UNCHANGED
            store.remove(r.type, r.id)
            return Applied.CHANGED
        }
        store.put(fromServer(r))
        return Applied.CHANGED
    }

    fun fromServer(r: SyncRecord): LocalRecord {
        val full = JsonObject(RecordCodec.merge(r.data, r.confidential).filterKeys { it != "contactMasked" })
        val obj = if ("version" in full) RecordIndex.withField(full, "version", JsonPrimitive(r.version)) else full
        val idx = RecordIndex.of(r.type, obj)
        return LocalRecord(
            type = r.type, id = r.id, agencyId = r.agencyId, assignedAgentId = r.assignedAgentId, version = r.version,
            updatedAt = r.updatedAt, deleted = r.deleted, json = obj.toString(), syncState = SyncState.SYNCED,
            customerId = idx.customerId, propertyId = idx.propertyId, status = idx.status, searchText = idx.searchText,
        )
    }

    private fun withVersion(jsonText: String, version: Long): String {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        return if ("version" in obj) RecordIndex.withField(obj, "version", JsonPrimitive(version)).toString() else jsonText
    }

    // ------------------------------------------------------------------ conflicts

    /** Keep the server's version (local edits are discarded) or re-apply the local version on top of it. */
    suspend fun resolveConflict(type: EntityType, id: String, keepLocal: Boolean) = store.transaction {
        val c = store.conflicts().firstOrNull { it.type == type && it.id == id } ?: return@transaction
        val server = c.server
        if (!keepLocal) {
            if (server == null) store.remove(type, id) else store.put(fromServer(server))
        } else {
            val base = server?.version ?: 0L
            val local = store.get(type, id)
            val restored = local?.copy(json = withVersion(c.localJson, base), version = base, syncState = SyncState.PENDING)
            if (restored != null) store.put(restored)
            store.putOutbox(OutboxEntry(type, id, UUID.randomUUID().toString(), base, clock()))
        }
        store.removeConflict(type, id)
    }

    // ------------------------------------------------------------------ photos

    private suspend fun uploadImages(api: RemoteApi): Int {
        var n = 0
        for (img in store.pendingImages()) {
            val prop = store.get(EntityType.PROPERTY, img.propertyId) ?: continue
            if (prop.version == 0L || prop.sample) continue // the server must know the property first
            val path = img.localPath ?: continue
            val bytes = images.read(path) ?: run {
                store.putImage(img.copy(uploadState = ImageUploadState.FAILED, lastError = "file_missing"))
                null
            } ?: continue
            try {
                val res = api.uploadImage(img.propertyId, bytes, images.contentType(path))
                properties.attachUploadedImage(img, res.id)
                n++
            } catch (e: RemoteException) {
                if (e.isNetwork || e.isAuth) throw e
                store.putImage(img.copy(uploadState = ImageUploadState.FAILED, lastError = e.code))
            }
        }
        return n
    }
}
