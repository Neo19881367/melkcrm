package ir.melkcrm.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.CustomerPickDialog
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SyncButton
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.core.reports.DashboardStats
import ir.melkcrm.core.time.DateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Every figure is computed from the records on this device; nothing is estimated or invented. */
class DashboardViewModel(private val app: AppContainer) : ViewModel() {
    private val _stats = MutableStateFlow<DashboardStats?>(null)
    val stats: StateFlow<DashboardStats?> = _stats

    init {
        viewModelScope.launch {
            merge(flowOf(Unit), app.crm.writer.changes.map { }, app.crm.matching.state.map { }, app.settings.settings.map { })
                .debounce(300)
                .collectLatest { _stats.value = app.crm.reports.dashboard(app.currentSettings().calendar) }
        }
    }
}

@Composable
fun DashboardScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel { DashboardViewModel(app) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val session by app.crm.session.session.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(
        title = stringResource(R.string.nav_dashboard),
        onBack = null,
        onMenu = nav.openDrawer,
        actions = { SyncButton(nav) },
    ) { padding ->
        val s = stats
        if (s == null) {
            Loading()
            return@ScreenScaffold
        }
        FormColumn(padding) {
            if (!session.signedIn) Banner(stringResource(R.string.settings_local_mode))
            if (session.authExpired) Banner(stringResource(R.string.session_expired_banner), color = MaterialTheme.colorScheme.errorContainer)

            SectionCard(stringResource(R.string.dash_quick_actions)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { nav.go(Routes.customerEdit(null)) }, label = { Text(stringResource(R.string.qa_add_customer)) })
                    AssistChip(onClick = { pickFor = "requirement" }, label = { Text(stringResource(R.string.qa_add_requirement)) })
                    AssistChip(onClick = { nav.go(Routes.propertyEdit(null)) }, label = { Text(stringResource(R.string.qa_add_property)) })
                    AssistChip(onClick = { nav.go(Routes.PROPERTIES) }, label = { Text(stringResource(R.string.qa_find_buyers)) })
                    AssistChip(onClick = { pickFor = "properties" }, label = { Text(stringResource(R.string.qa_find_properties)) })
                    AssistChip(onClick = { nav.go(Routes.taskEdit(null)) }, label = { Text(stringResource(R.string.qa_schedule_followup)) })
                    AssistChip(onClick = { nav.go(Routes.appointmentEdit(null)) }, label = { Text(stringResource(R.string.appointment_new)) })
                }
                val what = pickFor
                if (what != null) {
                    CustomerPickDialog(
                        title = stringResource(R.string.pick_customer),
                        onPick = { c ->
                            pickFor = null
                            nav.go(if (what == "requirement") Routes.requirementEdit(c.id, null) else Routes.customer(c.id))
                        },
                        onDismiss = { pickFor = null },
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(R.string.dash_active_buyers, fmt.num(s.activeBuyers)) { nav.top(Routes.CUSTOMERS) }
                Stat(R.string.dash_active_sellers, fmt.num(s.activeSellers)) { nav.top(Routes.CUSTOMERS) }
                Stat(R.string.dash_total_customers, fmt.num(s.totalCustomers)) { nav.top(Routes.CUSTOMERS) }
                Stat(R.string.dash_new_this_week, fmt.num(s.newCustomersThisWeek)) { nav.top(Routes.CUSTOMERS) }
                Stat(R.string.dash_for_sale, fmt.num(s.availableForSale)) { nav.top(Routes.PROPERTIES) }
                Stat(R.string.dash_for_rent, fmt.num(s.availableForRent)) { nav.top(Routes.PROPERTIES) }
                Stat(R.string.dash_high_priority_matches, fmt.num(s.highPriorityMatches)) { nav.top(Routes.MATCHING) }
                Stat(R.string.dash_buyers_no_match, fmt.num(s.buyersWithoutMatches)) { nav.top(Routes.MATCHING) }
                Stat(R.string.dash_props_no_match, fmt.num(s.propertiesWithoutMatches)) { nav.top(Routes.MATCHING) }
                Stat(R.string.dash_upcoming_appointments, fmt.num(s.upcomingAppointments)) { nav.top(Routes.ACTIVITIES) }
                Stat(R.string.dash_followups_today, fmt.num(s.followUpsDueToday)) { nav.top(Routes.ACTIVITIES) }
                Stat(R.string.dash_overdue, fmt.num(s.overdueFollowUps), alert = s.overdueFollowUps > 0) { nav.top(Routes.ACTIVITIES) }
                Stat(R.string.dash_active_negotiations, fmt.num(s.activeNegotiations)) { nav.top(Routes.ACTIVITIES) }
                Stat(R.string.dash_completed_transactions, fmt.num(s.completedTransactions)) { nav.top(Routes.REPORTS) }
                Stat(R.string.dash_expected_commission, fmt.moneyCompact(s.expectedCommission), note = stringResource(R.string.commission_estimate_badge)) { nav.top(Routes.REPORTS) }
                Stat(R.string.dash_received_commission, fmt.moneyCompact(s.receivedCommission)) { nav.top(Routes.REPORTS) }
            }

            SectionCard(stringResource(R.string.dash_monthly_sales)) {
                val max = (s.monthlySales.maxOfOrNull { it.count + it.rentalCount } ?: 0).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    for (m in s.monthlySales) {
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(fmt.num(m.count + m.rentalCount), style = MaterialTheme.typography.labelSmall)
                            Box(
                                Modifier.width(18.dp).fillMaxHeight(((m.count + m.rentalCount).toFloat() / max).coerceIn(0.02f, 0.75f))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Text(
                                DateFormatter.monthLabel(m.year, m.month, fmt.display).substringBefore(' '),
                                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1,
                            )
                        }
                    }
                }
                Text(stringResource(R.string.dash_sales_count), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.dash_figures_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: Int, value: String, alert: Boolean = false, note: String? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(164.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
