package ir.melkcrm.app.ui.reports

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.LabeledValue
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SyncButton
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.client.reports.ExportResult
import ir.melkcrm.core.model.CustomerRole
import ir.melkcrm.core.model.PropertyCategory
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.reports.AgentPerformance
import ir.melkcrm.core.reports.Bucket
import ir.melkcrm.core.reports.CountRow
import ir.melkcrm.core.reports.MonthlySales
import ir.melkcrm.core.reports.Reports
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.EpochDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ReportPeriod(val label: Int) {
    THIS_MONTH(R.string.period_this_month), LAST_3_MONTHS(R.string.period_last_3_months), THIS_YEAR(R.string.period_this_year), ALL(R.string.period_all)
}

/** All figures come from the records on this device, limited to the user's own records without the reports permission. */
class ReportsViewModel(private val app: AppContainer) : ViewModel() {
    private val crm = app.crm

    data class Data(
        val agencyWide: Boolean,
        val period: ReportPeriod,
        val byRole: Map<CustomerRole, List<CountRow>>,
        val newLeads: List<Triple<Int, Int, Int>>,
        val buyersByBudget: List<Bucket>,
        val sellersByType: List<CountRow>,
        val byLocation: List<Pair<String, List<CountRow>>>,
        val byPrice: List<Bucket>,
        val matching: Reports.MatchingRates,
        val viewingToOffer: Reports.Conversion,
        val offerToSale: Reports.Conversion,
        val avgDaysToSell: Double?,
        val agents: List<AgentPerformance>,
        val followUps: Reports.FollowUpCompletion,
        val commissions: Reports.CommissionSummary,
        val monthly: List<MonthlySales>,
    )

    private val period = MutableStateFlow(ReportPeriod.THIS_MONTH)
    private val _data = MutableStateFlow<Data?>(null)
    val data: StateFlow<Data?> = _data
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    init {
        viewModelScope.launch {
            crm.writer.changes.map { }.onStart { emit(Unit) }.debounce(500).collect { compute() }
        }
    }

    fun setPeriod(p: ReportPeriod) {
        period.value = p
        viewModelScope.launch { compute() }
    }

    private suspend fun compute() {
        val settings = app.currentSettings()
        val calendar = settings.calendar
        val zone = crm.writer.zone
        val today = crm.writer.today()
        val agency = crm.session.agency.value
        val user = crm.session.user
        val agencyWide = agency.policy.canViewReports(user, agencyWide = true)
        val s = crm.reports.reportSnapshot()
        val p = period.value
        val from = periodStart(p, today, calendar)?.let { AgencyTime.startOfDayMillis(it, zone) }
        val agentIds = if (agencyWide) agency.users.filter { it.active }.map { it.id }.ifEmpty { listOf(user.id) } else listOf(user.id)
        _data.value = Data(
            agencyWide = agencyWide,
            period = p,
            byRole = Reports.customersByTypeAndStatus(s),
            newLeads = Reports.newLeadsByMonth(s, calendar, zone, from = from),
            buyersByBudget = Reports.buyersByBudget(s),
            sellersByType = Reports.sellersByPropertyType(s),
            byLocation = Reports.availableByLocation(s),
            byPrice = Reports.propertiesByPrice(s),
            matching = Reports.matchingRates(s, agency.matching.thresholds),
            viewingToOffer = Reports.viewingToOffer(s),
            offerToSale = Reports.offerToSale(s),
            avgDaysToSell = Reports.averageDaysToSell(s, zone),
            agents = Reports.agentPerformance(s, agentIds, from = from),
            followUps = Reports.followUpCompletion(s, today, zone),
            commissions = Reports.commissionSummary(s),
            monthly = Reports.monthlySales(s.transactions, calendar, today, 12),
        )
    }

    fun canExport(): Boolean = crm.reports.canExport()

    fun export(context: Context, properties: Boolean) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.update { true }
            try {
                val settings = app.currentSettings()
                val result = if (properties) crm.reports.exportProperties(settings.display, settings.currency) else crm.reports.exportCustomers(settings.display)
                when (result) {
                    is ExportResult.Ok -> share(context, result)
                    ExportResult.Forbidden -> app.say(Msg.Res(R.string.export_forbidden))
                    is ExportResult.Failed -> app.say(Msg.Wrapped(R.string.export_failed, Msg.Code("srv_", result.code)))
                }
            } finally {
                _exporting.update { false }
            }
        }
    }

    /** Writes the file to the app's private cache and hands it to the share sheet; nothing is sent automatically. */
    private suspend fun share(context: Context, result: ExportResult.Ok) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            // Earlier exports are removed so personal data does not accumulate on the device.
            dir.listFiles()?.forEach { it.delete() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            File(dir, result.fileName.removeSuffix(".csv") + "-" + stamp + ".csv").apply { writeText(result.csv, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(result.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.export)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** First day of the period in the display calendar; null for all time. */
        fun periodStart(p: ReportPeriod, today: EpochDay, calendar: CalendarSystem): EpochDay? = when (p) {
            ReportPeriod.THIS_MONTH -> DateFormatter.startOfMonth(today, calendar)
            ReportPeriod.LAST_3_MONTHS -> {
                var d = DateFormatter.startOfMonth(today, calendar)
                repeat(2) { d = DateFormatter.startOfMonth(d - 1, calendar) }
                d
            }
            ReportPeriod.THIS_YEAR -> {
                var d = DateFormatter.startOfMonth(today, calendar)
                while (DateFormatter.yearMonthOf(d, calendar).second != 1) d = DateFormatter.startOfMonth(d - 1, calendar)
                d
            }
            ReportPeriod.ALL -> null
        }
    }
}

@Composable
fun ReportsScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val context = LocalContext.current
    val vm = viewModel { ReportsViewModel(app) }
    val data by vm.data.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.reports_title), onBack = null, onMenu = nav.openDrawer, actions = { SyncButton(nav) }) { padding ->
        val d = data
        if (d == null) {
            Loading()
        } else {
            FormColumn(padding) {
                if (!d.agencyWide) Banner(stringResource(R.string.report_own_scope))
                Text(stringResource(R.string.report_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for (p in ReportPeriod.entries) {
                        FilterChip(selected = d.period == p, onClick = { vm.setPeriod(p) }, label = { Text(stringResource(p.label)) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Text(stringResource(R.string.report_period_note), style = MaterialTheme.typography.bodySmall)

                ExportSection(vm.canExport(), exporting, onExport = { props -> vm.export(context, props) })

                SectionCard(stringResource(R.string.report_customers_by_type)) {
                    var any = false
                    for ((role, rows) in d.byRole) {
                        if (rows.isEmpty()) continue
                        any = true
                        Text(fmt.label(role), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                        Bars(rows.map { fmt.status(it.key) to it.count }, fmt)
                    }
                    if (!any) NoData()
                }
                SectionCard(stringResource(R.string.report_new_leads)) {
                    if (d.newLeads.isEmpty()) NoData() else Bars(d.newLeads.map { (y, m, n) -> DateFormatter.monthLabel(y, m, fmt.display) to n }, fmt)
                }
                SectionCard(stringResource(R.string.report_buyers_by_budget)) {
                    BucketBars(d.buyersByBudget, fmt, stringResource(R.string.unknown_budget))
                }
                SectionCard(stringResource(R.string.report_sellers_by_type)) {
                    if (d.sellersByType.isEmpty()) NoData() else Bars(d.sellersByType.map { r -> (PropertyCategory.entries.firstOrNull { it.name == r.key }?.let { fmt.label(it) } ?: r.key) to r.count }, fmt)
                }
                SectionCard(stringResource(R.string.report_available_by_location)) {
                    if (d.byLocation.isEmpty()) NoData()
                    for ((city, rows) in d.byLocation.take(8)) {
                        Text(if (city == "?") stringResource(R.string.not_set) else city, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                        Bars(rows.take(10).map { (if (it.key == "?") "—" else it.key) to it.count }, fmt)
                    }
                }
                SectionCard(stringResource(R.string.report_props_by_price)) {
                    BucketBars(d.byPrice, fmt, null)
                }
                SectionCard(stringResource(R.string.report_matching_rates)) {
                    Text(stringResource(R.string.report_req_matched, d.matching.requirementsMatched, d.matching.requirements))
                    Text(stringResource(R.string.report_prop_matched, d.matching.propertiesMatched, d.matching.properties))
                    Text(stringResource(R.string.report_matching_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionCard(stringResource(R.string.report_conversions)) {
                    LabeledValue(stringResource(R.string.report_viewing_to_offer), conversion(d.viewingToOffer, fmt))
                    LabeledValue(stringResource(R.string.report_offer_to_sale), conversion(d.offerToSale, fmt))
                    LabeledValue(stringResource(R.string.report_avg_days_to_sell), d.avgDaysToSell?.let { fmt.num(Math.round(it)) })
                }
                SectionCard(stringResource(R.string.report_agent_performance)) {
                    if (d.agents.isEmpty()) NoData()
                    for (a in d.agents) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(agentName(a.agentId) ?: stringResource(R.string.unknown), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.report_agent_row, a.activities, a.viewings, a.offers, a.transactions),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.report_agent_tasks, a.tasksDone, a.tasksOpen) + " · " + stringResource(R.string.commission_received) + ": " + fmt.money(a.commissionReceived),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                SectionCard(stringResource(R.string.report_followup_completion)) {
                    val f = d.followUps
                    Text(stringResource(R.string.report_followup_row, f.due, f.completedOnTime, f.completedLate, f.overdueOpen))
                }
                SectionCard(stringResource(R.string.report_commissions)) {
                    val c = d.commissions
                    LabeledValue(stringResource(R.string.commission_expected), fmt.money(c.expected))
                    LabeledValue(stringResource(R.string.commission_earned), fmt.money(c.earned))
                    LabeledValue(stringResource(R.string.commission_received), fmt.money(c.received))
                    LabeledValue(stringResource(R.string.commission_outstanding), fmt.money(c.outstanding))
                    Text(stringResource(R.string.commission_estimate_note), style = MaterialTheme.typography.bodySmall)
                }
                SectionCard(stringResource(R.string.report_completed_transactions)) {
                    val months = d.monthly.filter { it.count > 0 || it.rentalCount > 0 }
                    if (months.isEmpty()) NoData()
                    for (m in months) {
                        LabeledValue(
                            DateFormatter.monthLabel(m.year, m.month, fmt.display),
                            stringResource(R.string.report_month_sales, m.count, fmt.money(m.saleVolume), m.rentalCount),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportSection(canExport: Boolean, exporting: Boolean, onExport: (Boolean) -> Unit) {
    SectionCard(stringResource(R.string.export)) {
        if (!canExport) {
            Text(stringResource(R.string.export_forbidden), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.export_privacy_note), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onExport(false) }, enabled = !exporting, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.export_customers_csv)) }
            Button(onClick = { onExport(true) }, enabled = !exporting, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.export_properties_csv)) }
        }
    }
}

@Composable
private fun NoData() = Text(stringResource(R.string.report_no_data), style = MaterialTheme.typography.bodySmall)

@Composable
private fun conversion(c: Reports.Conversion, fmt: UiFormat): String =
    if (c.from == 0) stringResource(R.string.report_no_data) else stringResource(R.string.report_conversion, c.to, c.from, fmt.percent(c.rate))

/** A labelled horizontal bar per row, scaled to the largest count. */
@Composable
private fun Bars(rows: List<Pair<String, Int>>, fmt: UiFormat) {
    val max = rows.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1
    val color = MaterialTheme.colorScheme.primary
    for ((label, n) in rows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f), maxLines = 2)
            Box(Modifier.weight(0.45f)) {
                if (n > 0) Box(Modifier.fillMaxWidth(n.toFloat() / max).height(10.dp).background(color, RoundedCornerShape(4.dp)))
            }
            Text(fmt.num(n), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp).padding(start = 6.dp))
        }
    }
}

@Composable
private fun BucketBars(buckets: List<Bucket>, fmt: UiFormat, unknownLabel: String?) {
    if (buckets.all { it.count == 0 }) {
        NoData()
        return
    }
    val below = stringResource(R.string.report_bucket_below)
    val above = stringResource(R.string.report_bucket_above)
    Bars(
        buckets.mapNotNull { b ->
            val lo = b.fromRial
            val hi = b.toRial
            val label = when {
                b.label == "unknown" -> unknownLabel ?: return@mapNotNull null
                lo == null && hi != null -> below + " " + fmt.moneyCompact(Money(hi))
                lo != null && hi == null -> above + " " + fmt.moneyCompact(Money(lo))
                lo != null && hi != null -> fmt.moneyCompact(Money(lo)) + " – " + fmt.moneyCompact(Money(hi))
                else -> return@mapNotNull null
            }
            label to b.count
        },
        fmt,
    )
}
