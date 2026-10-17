package ir.melkcrm.app.ui.matching

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SyncButton
import ir.melkcrm.app.ui.customers.requirementSummary
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.properties.priceSummary
import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.matching.OpportunitySummary
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Property
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Workflow C: agency-wide opportunities from the latest matching run on this device. */
class MatchingHomeViewModel(private val app: AppContainer) : ViewModel() {
    private val crm = app.crm
    private val started = SharingStarted.WhileSubscribed(5_000)
    private val _summary = MutableStateFlow<OpportunitySummary?>(null)
    val summary: StateFlow<OpportunitySummary?> = _summary
    val matching = crm.matching.state
    val customers: StateFlow<Map<String, Customer>> = crm.customers.customers.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())
    val requirements: StateFlow<Map<String, BuyerRequirement>> = crm.customers.requirements.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())
    val properties: StateFlow<Map<String, Property>> = crm.properties.properties.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())

    init {
        viewModelScope.launch {
            crm.matching.state.map { it.lastFullRun to it.evaluations }.distinctUntilChanged().debounce(300).collectLatest {
                if (!crm.matching.state.value.running) _summary.value = crm.matching.opportunities()
            }
        }
    }

    fun recalculate() = viewModelScope.launch {
        crm.matching.recalculateAll()
        _summary.value = crm.matching.opportunities()
    }
}

private const val SHOWN = 5

@Composable
fun MatchingHomeScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel { MatchingHomeViewModel(app) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val state by vm.matching.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val requirements by vm.requirements.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }

    ScreenScaffold(
        title = stringResource(R.string.matching_title),
        onBack = null,
        onMenu = nav.openDrawer,
        actions = {
            IconButton(onClick = { vm.recalculate() }, enabled = !state.running) { Icon(Icons.Filled.Refresh, stringResource(R.string.matching_refresh)) }
            SyncButton(nav)
        },
    ) { padding ->
        val s = summary
        if (s == null) {
            Loading()
            return@ScreenScaffold
        }
        FormColumn(padding) {
            Text(
                if (state.running) stringResource(R.string.matching_running)
                else stringResource(R.string.matching_last_run, state.lastFullRun?.let { fmt.dateTime(it) } ?: stringResource(R.string.sync_never)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.match_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            EvalSection(R.string.matching_high_priority, s.highPriority, expanded, customers, properties, nav)
            EvalSection(R.string.matching_new_matches, s.newMatches, expanded, customers, properties, nav)
            Section(R.string.matching_needs_followup, s.needsFollowUp.size, expanded) { n ->
                for (m in s.needsFollowUp.take(n)) FollowUpRow(m, customers, properties, nav)
            }
            Section(R.string.matching_hot_properties, s.hotProperties.size, expanded) { n ->
                for ((pid, count) in s.hotProperties.entries.sortedByDescending { it.value }.take(n)) {
                    val p = properties[pid] ?: continue
                    ListItem(
                        modifier = Modifier.clickable { nav.go(Routes.buyersFor(pid)) },
                        headlineContent = { Text("${p.reference} · ${p.title}") },
                        supportingContent = { Text(stringResource(R.string.matching_strong_buyers, count)) },
                    )
                }
            }
            Section(R.string.matching_props_without_buyers, s.propertiesWithoutBuyers.size, expanded) { n ->
                for (pid in s.propertiesWithoutBuyers.take(n)) {
                    val p = properties[pid] ?: continue
                    ListItem(
                        modifier = Modifier.clickable { nav.go(Routes.buyersFor(pid)) },
                        headlineContent = { Text("${p.reference} · ${p.title}") },
                        supportingContent = { Text(priceSummary(p, fmt)) },
                    )
                }
            }
            Section(R.string.matching_reqs_without_props, s.requirementsWithoutProperties.size, expanded) { n ->
                for (rid in s.requirementsWithoutProperties.take(n)) RequirementRow(rid, requirements, customers, nav)
            }
            Section(R.string.matching_hard_to_satisfy, s.hardToSatisfy.size, expanded) { n ->
                for (rid in s.hardToSatisfy.take(n)) RequirementRow(rid, requirements, customers, nav)
            }
            EvalSection(R.string.matching_recent_properties, s.recentPropertyMatches, expanded, customers, properties, nav)
        }
    }
}

@Composable
private fun EvalSection(
    title: Int, list: List<MatchEvaluation>, expanded: MutableMap<Int, Boolean>, customers: Map<String, Customer>, properties: Map<String, Property>, nav: Nav,
) {
    val fmt = LocalFormat.current
    Section(title, list.size, expanded) { n ->
        for (e in list.take(n)) {
            val p = properties[e.propertyId]
            MatchRow(
                e, null,
                headline = (customers[e.customerId]?.fullName ?: "") + " ← " + (p?.reference ?: ""),
                overline = p?.let { priceSummary(it, fmt) },
                onClick = { nav.go(Routes.match(e.requirementId, e.propertyId)) },
            )
        }
    }
}

@Composable
private fun Section(title: Int, count: Int, expanded: MutableMap<Int, Boolean>, content: @Composable (Int) -> Unit) {
    val fmt = LocalFormat.current
    val open = expanded[title] == true
    SectionCard(stringResource(title) + " (" + fmt.num(count) + ")") {
        if (count == 0) {
            Text(stringResource(R.string.empty_list), style = MaterialTheme.typography.bodySmall)
        } else {
            content(if (open) count else SHOWN)
            if (count > SHOWN) {
                TextButton(onClick = { expanded[title] = !open }) { Text(stringResource(if (open) R.string.show_less else R.string.show_all)) }
            }
        }
    }
}

@Composable
private fun RequirementRow(rid: String, requirements: Map<String, BuyerRequirement>, customers: Map<String, Customer>, nav: Nav) {
    val r = requirements[rid] ?: return
    ListItem(
        modifier = Modifier.clickable { nav.go(Routes.propertiesFor(rid)) },
        headlineContent = { Text(customers[r.customerId]?.fullName ?: stringResource(R.string.unknown)) },
        supportingContent = { Text(requirementSummary(r)) },
    )
}

@Composable
private fun FollowUpRow(m: MatchRecord, customers: Map<String, Customer>, properties: Map<String, Property>, nav: Nav) {
    val fmt = LocalFormat.current
    ListItem(
        modifier = Modifier.clickable { nav.go(Routes.match(m.requirementId, m.propertyId)) },
        headlineContent = { Text((customers[m.customerId]?.fullName ?: "") + " ← " + (properties[m.propertyId]?.reference ?: "")) },
        supportingContent = {
            Text(listOfNotNull(fmt.label(m.reviewStatus), m.followUpDate?.let { stringResource(R.string.review_followup) + ": " + fmt.date(it) }).joinToString(" · "))
        },
    )
}
