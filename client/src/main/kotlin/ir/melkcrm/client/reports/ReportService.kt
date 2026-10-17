package ir.melkcrm.client.reports

import ir.melkcrm.client.data.RecordWriter
import ir.melkcrm.client.matching.MatchingService
import ir.melkcrm.core.export.Exports
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.permissions.Access
import ir.melkcrm.core.reports.CrmSnapshot
import ir.melkcrm.core.reports.Dashboard
import ir.melkcrm.core.reports.DashboardStats
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.DisplaySettings

sealed interface ExportResult {
    data class Ok(val fileName: String, val csv: String) : ExportResult
    data object Forbidden : ExportResult
    data class Failed(val code: String) : ExportResult
}

/** Builds dashboards, reports and exports from the records stored on this device, never from estimates or samples of thin air. */
class ReportService(private val w: RecordWriter, private val matching: MatchingService) {

    suspend fun snapshot(): CrmSnapshot = CrmSnapshot(
        customers = w.loadAll(EntityType.CUSTOMER),
        requirements = w.loadAll(EntityType.BUYER_REQUIREMENT),
        sellers = w.loadAll(EntityType.SELLER_PROFILE),
        properties = w.loadAll(EntityType.PROPERTY),
        activities = w.loadAll(EntityType.ACTIVITY),
        tasks = w.loadAll(EntityType.TASK),
        appointments = w.loadAll(EntityType.APPOINTMENT),
        deals = w.loadAll(EntityType.DEAL),
        offers = w.loadAll(EntityType.OFFER),
        transactions = w.loadAll(EntityType.TRANSACTION),
        commissions = w.loadAll(EntityType.COMMISSION),
        matches = matching.state.value.evaluations.values.toList(),
        matchRecords = w.loadAll<MatchRecord>(EntityType.MATCH_RECORD),
    )

    /** Agency-wide figures need the reports permission; otherwise the snapshot is limited to the user's own records. */
    suspend fun reportSnapshot(): CrmSnapshot {
        val s = snapshot()
        return if (w.agency.policy.canViewReports(w.user, agencyWide = true)) s else s.forAgent(w.user.id)
    }

    suspend fun dashboard(calendar: CalendarSystem): DashboardStats =
        Dashboard.compute(snapshot(), w.agency.matching.thresholds, w.now(), calendar, w.zone)

    fun canExport(): Boolean = w.agency.policy.canExport(w.user)

    /**
     * CSV export. Signed in, the server produces the file so it can enforce the export permission and
     * audit it; on a device-only installation it is generated locally and recorded in the local audit log.
     */
    suspend fun exportCustomers(display: DisplaySettings): ExportResult {
        if (!canExport()) return ExportResult.Forbidden
        val api = w.session.api()
        val csv = if (api != null) {
            try { api.exportCustomersCsv(display.language) } catch (e: ir.melkcrm.client.remote.RemoteException) {
                return if (e.isForbidden) ExportResult.Forbidden else ExportResult.Failed(e.code)
            }
        } else {
            val rows = w.store.all(EntityType.CUSTOMER).mapNotNull { rec ->
                val access = w.access(rec)
                if (access == Access.NONE) null else Exports.CustomerRow(w.decode(rec), access >= Access.READ)
            }
            audit("CUSTOMER", rows.size)
            Exports.customers(rows, agentNames(), display.language, w.agency.settings.customStatuses, display)
        }
        return ExportResult.Ok("customers.csv", csv)
    }

    suspend fun exportProperties(display: DisplaySettings, unit: CurrencyUnit): ExportResult {
        if (!canExport()) return ExportResult.Forbidden
        val api = w.session.api()
        val csv = if (api != null) {
            try { api.exportPropertiesCsv(display.language, unit) } catch (e: ir.melkcrm.client.remote.RemoteException) {
                return if (e.isForbidden) ExportResult.Forbidden else ExportResult.Failed(e.code)
            }
        } else {
            val props = w.loadAll<Property>(EntityType.PROPERTY)
            audit("PROPERTY", props.size)
            Exports.properties(props, agentNames(), display.language, unit, display)
        }
        return ExportResult.Ok("properties.csv", csv)
    }

    private fun agentNames() = w.agency.users.associate { it.id to it.displayName }

    private suspend fun audit(entity: String, rows: Int) =
        w.addAudit(AuditEvent(w.newId(), w.user.agencyId, w.user.id, entity, "*", AuditAction.EXPORT, emptyMap(), "csv rows=$rows", w.now()))
}
