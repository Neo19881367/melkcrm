package ir.melkcrm.app.ui.properties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SyncButton
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.view.PropertyFilter
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.PropertyCategory
import ir.melkcrm.core.model.PropertyStatus
import ir.melkcrm.core.permissions.Permission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

class PropertyListViewModel(private val app: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(PropertyFilter())

    val results: StateFlow<List<Property>?> =
        combine(app.crm.properties.properties, query.debounce(250).onStart { emit(query.value) }, filter) { _, q, f -> q to f }
            .mapLatest { (q, f) -> f.apply(app.crm.properties.search(q, includeClosed = true)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** The listed figures for the deal types a property is offered for, e.g. "۹٫۵ میلیارد تومان" or "deposit + rent". */
fun priceSummary(p: Property, fmt: UiFormat): String = buildList {
    if (DealType.SALE in p.dealTypes) add(p.askingPrice?.let { fmt.moneyCompact(it) } ?: "—")
    if (DealType.RENT in p.dealTypes) add(listOf(p.deposit, p.monthlyRent).joinToString(" + ") { m -> m?.let { fmt.moneyCompact(it) } ?: "—" })
    if (DealType.FULL_DEPOSIT in p.dealTypes) add(p.fullDepositAmount?.let { fmt.moneyCompact(it) } ?: "—")
}.joinToString(" | ")

@Composable
fun PropertyListScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel { PropertyListViewModel(app) }
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val user = app.crm.session.user
    val seesOthers = agency.settings.agentsSeeAgencyInventory || agency.policy.has(user, Permission.VIEW_TEAM_RECORDS)

    ScreenScaffold(
        title = stringResource(R.string.properties_title),
        onBack = null,
        onMenu = nav.openDrawer,
        actions = {
            IconButton(onClick = { nav.go(Routes.MAP) }) { Icon(Icons.Filled.Map, stringResource(R.string.map_title)) }
            SyncButton(nav)
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.go(Routes.propertyEdit(null)) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.property_new)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_properties_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumDropdown(stringResource(R.string.req_deal_type), DealType.entries, filter.dealType, { v -> vm.filter.value = filter.copy(dealType = v) }, Modifier.widthIn(max = 170.dp))
                EnumDropdown(stringResource(R.string.property_category), PropertyCategory.entries, filter.category, { v -> vm.filter.value = filter.copy(category = v) }, Modifier.widthIn(max = 170.dp))
                EnumDropdown(stringResource(R.string.property_status), PropertyStatus.entries, filter.status, { v -> vm.filter.value = filter.copy(status = v) }, Modifier.widthIn(max = 170.dp))
                if (seesOthers) {
                    Dropdown(
                        label = stringResource(R.string.agent), options = agency.users, selected = agency.users.firstOrNull { it.id == filter.agentId },
                        onSelect = { u -> vm.filter.value = filter.copy(agentId = u?.id) }, optionLabel = { it.displayName },
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                }
                FilterChip(
                    selected = filter.includeClosed, onClick = { vm.filter.value = filter.copy(includeClosed = !filter.includeClosed) },
                    label = { Text(stringResource(R.string.show_closed)) }, modifier = Modifier.padding(top = 12.dp),
                )
                FilterChip(
                    selected = filter.withoutCoordinates, onClick = { vm.filter.value = filter.copy(withoutCoordinates = !filter.withoutCoordinates) },
                    label = { Text(stringResource(R.string.property_no_coordinates)) }, modifier = Modifier.padding(top = 12.dp),
                )
            }
            val list = results
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(stringResource(if (query.isBlank() && !filter.active) R.string.properties_empty else R.string.no_results))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { p ->
                        PropertyRow(p) { nav.go(Routes.property(p.id)) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun PropertyRow(p: Property, onClick: () -> Unit) {
    val fmt = LocalFormat.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text(p.reference + (if (p.isDraft) " · " + stringResource(R.string.draft_badge) else "")) },
        headlineContent = { Text(p.title.ifBlank { fmt.label(p.category) }) },
        supportingContent = {
            Text(
                listOfNotNull(
                    fmt.label(p.category),
                    listOfNotNull(p.neighborhood, p.city).joinToString("، ").ifBlank { null },
                    p.effectiveArea?.let { fmt.area(it) },
                    p.bedrooms?.let { fmt.num(it) + " " + stringResource(R.string.property_bedrooms) },
                ).joinToString(" · ") + "\n" + priceSummary(p, fmt),
            )
        },
        trailingContent = {
            Column {
                Text(fmt.label(p.status), style = MaterialTheme.typography.labelMedium, color = if (p.status.isClosed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                agentName(p.assignedAgentId)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
    )
}
