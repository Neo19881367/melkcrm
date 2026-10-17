package ir.melkcrm.app.ui.matching

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.customers.requirementSummary
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.properties.priceSummary
import ir.melkcrm.client.view.MatchFilter
import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Property
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Ranked results for one property (Workflow A) or one requirement (Workflow B). Results are
 * recalculated when the screen opens and whenever records change; excluded candidates are kept with
 * their reasons so the filter can show why someone is not on the list.
 */
class RankedViewModel(private val app: AppContainer, private val propertyId: String?, private val requirementId: String?) : ViewModel() {
    private val crm = app.crm
    private val started = SharingStarted.WhileSubscribed(5_000)
    private val evaluations = MutableStateFlow<List<MatchEvaluation>?>(null)
    val filter = MutableStateFlow(MatchFilter())
    val running = MutableStateFlow(false)

    val records: StateFlow<Map<String, MatchRecord>> = crm.matching.records.map { l -> l.associateBy { it.key } }.stateIn(viewModelScope, started, emptyMap())
    val customers: StateFlow<Map<String, Customer>> = crm.customers.customers.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())
    val requirements: StateFlow<Map<String, BuyerRequirement>> = crm.customers.requirements.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())
    val properties: StateFlow<Map<String, Property>> = crm.properties.properties.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())

    val shown: StateFlow<List<MatchEvaluation>?> = combine(evaluations, records, filter) { e, r, f -> e?.let { f.apply(it, r, crm.writer.today()) } }
        .stateIn(viewModelScope, started, null)

    init {
        refresh()
        viewModelScope.launch { crm.writer.changes.debounce(1_200).collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        running.value = true
        evaluations.value = when {
            propertyId != null -> crm.matching.buyersFor(propertyId, includeExcluded = true)
            requirementId != null -> crm.matching.propertiesFor(requirementId, includeExcluded = true)
            else -> emptyList()
        }
        running.value = false
    }
}

@Composable
fun BuyersForPropertyScreen(propertyId: String, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "buyers-$propertyId") { RankedViewModel(app, propertyId, null) }
    val shown by vm.shown.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val requirements by vm.requirements.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val p = properties[propertyId]

    ScreenScaffold(
        title = stringResource(R.string.best_buyers_title),
        onBack = { nav.back() },
        actions = { IconButton(onClick = { vm.refresh() }, enabled = !running) { Icon(Icons.Filled.Refresh, stringResource(R.string.matching_refresh)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (p != null) {
                Text("${p.reference} · ${p.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                Text(priceSummary(p, fmt) + " · " + fmt.label(p.status), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            MatchFilterBar(filter) { vm.filter.value = it }
            Text(stringResource(R.string.match_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            val list = shown
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(stringResource(R.string.match_none))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.key }) { e ->
                        val r = requirements[e.requirementId]
                        MatchRow(
                            e, records[e.key],
                            headline = customers[e.customerId]?.fullName ?: stringResource(R.string.unknown),
                            overline = r?.let { requirementSummary(it) },
                            onClick = { nav.go(Routes.match(e.requirementId, e.propertyId)) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private const val MAX_COMPARE = 4

@Composable
fun PropertiesForRequirementScreen(requirementId: String, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "props-$requirementId") { RankedViewModel(app, null, requirementId) }
    val shown by vm.shown.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val requirements by vm.requirements.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(listOf<String>()) }
    val r = requirements[requirementId]

    ScreenScaffold(
        title = stringResource(R.string.matching_properties_title),
        onBack = { nav.back() },
        actions = { IconButton(onClick = { vm.refresh() }, enabled = !running) { Icon(Icons.Filled.Refresh, stringResource(R.string.matching_refresh)) } },
        floatingActionButton = {
            if (selected.size >= 2) {
                ExtendedFloatingActionButton(
                    onClick = { nav.go(Routes.compare(requirementId, selected)) },
                    icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null) },
                    text = { Text(stringResource(R.string.match_compare) + " (" + fmt.num(selected.size) + ")") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (r != null) {
                Text(customers[r.customerId]?.fullName ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                Text(requirementSummary(r), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                if (r.maxBudget == null && r.maxDeposit == null && r.maxMonthlyRent == null) {
                    Banner(stringResource(R.string.match_budget_missing_banner), Modifier.padding(horizontal = 16.dp))
                }
            }
            MatchFilterBar(filter) { vm.filter.value = it }
            Text(stringResource(R.string.match_compare_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            val list = shown
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(stringResource(R.string.match_none))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.key }) { e ->
                        val p = properties[e.propertyId]
                        val on = e.propertyId in selected
                        MatchRow(
                            e, records[e.key],
                            headline = p?.let { "${it.reference} · ${it.title}" } ?: stringResource(R.string.property_not_found),
                            overline = p?.let { listOfNotNull(priceSummary(it, fmt), listOfNotNull(it.neighborhood, it.city).joinToString("، ").ifBlank { null }).joinToString(" · ") },
                            onClick = { nav.go(Routes.match(e.requirementId, e.propertyId)) },
                            trailing = {
                                Checkbox(checked = on, onCheckedChange = { v ->
                                    selected = when {
                                        !v -> selected - e.propertyId
                                        selected.size >= MAX_COMPARE -> { app.say(Msg.Res(R.string.match_compare_limit)); selected }
                                        else -> selected + e.propertyId
                                    }
                                })
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
