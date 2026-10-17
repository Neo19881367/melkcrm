package ir.melkcrm.client

import ir.melkcrm.client.data.ActivityRepository
import ir.melkcrm.client.data.CustomerRepository
import ir.melkcrm.client.data.DealRepository
import ir.melkcrm.client.data.PropertyRepository
import ir.melkcrm.client.data.RecordWriter
import ir.melkcrm.client.matching.MatchingService
import ir.melkcrm.client.matching.OutreachService
import ir.melkcrm.client.remote.RemoteApi
import ir.melkcrm.client.remote.TokenStorage
import ir.melkcrm.client.reports.ReportService
import ir.melkcrm.client.sample.SampleLoader
import ir.melkcrm.client.session.SessionManager
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.sync.ImageFiles
import ir.melkcrm.client.sync.SyncEngine
import ir.melkcrm.client.sync.SyncReport
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.time.AgencyTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The app's object graph without Android: one instance per process. The Android AppContainer
 * creates it with the Room store, encrypted token storage and a Ktor-backed [RemoteApi].
 */
class Crm(
    val store: LocalStore,
    tokens: TokenStorage,
    apiFactory: (serverUrl: String) -> RemoteApi,
    images: ImageFiles,
    localUserName: String,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = AgencyTime.TEHRAN,
) {
    val session = SessionManager(store, tokens, apiFactory, localUserName, clock)
    val writer = RecordWriter(store, session, clock, zone = zone)
    val customers = CustomerRepository(writer)
    val properties = PropertyRepository(writer)
    val activities = ActivityRepository(writer)
    val deals = DealRepository(writer, activities)
    val matching = MatchingService(writer, activities)
    val outreach = OutreachService(writer, activities, matching)
    val reports = ReportService(writer, matching)
    val sync = SyncEngine(store, session, properties, images, clock)
    val samples = SampleLoader(writer)

    /**
     * Starts the app's background behaviour: restores the session, expires overdue offers and keeps
     * match results current as records change. Call once per process.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        session.restore()
        runCatching { deals.expireDueOffers() }
        matching.startAutoRecalculation(this)
    }

    /**
     * Synchronizes with the server. Records pulled from the server do not pass through the local
     * writer, so match results are recalculated here when anything arrived.
     */
    suspend fun syncNow(): SyncReport {
        val report = sync.sync()
        if (report.pulled > 0 || report.conflicts > 0) matching.recalculateAll()
        runCatching { deals.expireDueOffers() }
        return report
    }

    /** Signs out and wipes the device copy, including match results held in memory. */
    suspend fun signOut() {
        session.signOut()
        matching.recalculateAll()
    }

    /** History of a record: the server's audit log when signed in and permitted, otherwise the local one. */
    suspend fun history(entityId: String): List<AuditEvent> {
        val local = store.audit(entityId)
        val remote = session.api()?.let { api -> runCatching { api.audit(entityId) }.getOrNull() } ?: return local
        return (remote + local.filter { l -> remote.none { it.at == l.at && it.action == l.action } }).sortedByDescending { it.at }
    }
}
