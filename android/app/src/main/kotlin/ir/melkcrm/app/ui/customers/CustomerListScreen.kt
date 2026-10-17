package ir.melkcrm.app.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PeopleOutline
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
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SyncButton
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.view.CustomerFilter
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.CustomerRole
import ir.melkcrm.core.model.CustomerStatus
import ir.melkcrm.core.model.Priority
import ir.melkcrm.core.permissions.Permission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

class CustomerListViewModel(private val app: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(CustomerFilter())

    /** Search covers names, phone numbers in any format, notes, requirements, activities and owned properties. */
    val results: StateFlow<List<Customer>?> =
        combine(app.crm.customers.customers, query.debounce(250).onStart { emit(query.value) }, filter) { all, q, f -> Triple(all, q, f) }
            .mapLatest { (all, q, f) ->
                val today = app.crm.writer.today()
                val base = if (q.isBlank()) all else app.crm.customers.search(q)
                CustomerFilter.sort(f.apply(base, today), today)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun CustomerListScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel { CustomerListViewModel(app) }
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val user = app.crm.session.user
    val canMerge = agency.policy.canMerge(user)
    val seesOthers = agency.policy.has(user, Permission.VIEW_TEAM_RECORDS) || agency.policy.has(user, Permission.VIEW_ALL_RECORDS)
    val today = app.crm.writer.today()

    ScreenScaffold(
        title = stringResource(R.string.customers_title),
        onBack = null,
        onMenu = nav.openDrawer,
        actions = {
            if (canMerge) IconButton(onClick = { nav.go(Routes.DUPLICATES) }) { Icon(Icons.Filled.PeopleOutline, stringResource(R.string.duplicates_title)) }
            SyncButton(nav)
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.go(Routes.customerEdit(null)) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.customer_new)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_customers_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumDropdown(
                    stringResource(R.string.customer_roles), CustomerRole.entries, filter.role,
                    { r -> vm.filter.value = filter.copy(role = r) }, Modifier.widthIn(max = 180.dp),
                )
                Dropdown(
                    label = stringResource(R.string.status),
                    options = CustomerStatus.entries.map { it.name } + agency.settings.customStatuses.map { it.code },
                    selected = filter.status,
                    onSelect = { s -> vm.filter.value = filter.copy(status = s) },
                    optionLabel = { fmt.status(it) },
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                EnumDropdown(
                    stringResource(R.string.priority), Priority.entries, filter.priority,
                    { p -> vm.filter.value = filter.copy(priority = p) }, Modifier.widthIn(max = 160.dp),
                )
                if (seesOthers) {
                    Dropdown(
                        label = stringResource(R.string.agent), options = agency.users, selected = agency.users.firstOrNull { it.id == filter.agentId },
                        onSelect = { u -> vm.filter.value = filter.copy(agentId = u?.id) }, optionLabel = { it.displayName },
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
                FilterChip(
                    selected = filter.followUpDue, onClick = { vm.filter.value = filter.copy(followUpDue = !filter.followUpDue) },
                    label = { Text(stringResource(R.string.dash_followups_today)) }, modifier = Modifier.padding(top = 12.dp),
                )
            }
            val list = results
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(stringResource(if (query.isBlank() && !filter.active) R.string.customers_empty else R.string.no_results))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { c ->
                        CustomerRow(c, today) { nav.go(Routes.customer(c.id)) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerRow(c: Customer, today: Long, onClick: () -> Unit) {
    val fmt = LocalFormat.current
    val follow = c.nextFollowUpDate
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row {
                Text(c.fullName.ifBlank { stringResource(R.string.unnamed) })
                if (c.isDraft) Text("  " + stringResource(R.string.draft_badge), color = MaterialTheme.colorScheme.tertiary)
            }
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        c.roles.joinToString("، ") { fmt.label(it) }.ifBlank { null },
                        c.mobile?.let { fmt.phone(it) },
                        listOfNotNull(c.neighborhood, c.city).joinToString("، ").ifBlank { null },
                    ).joinToString(" · "),
                )
                if (follow != null) {
                    Text(
                        stringResource(R.string.customer_next_followup) + ": " + fmt.date(follow),
                        color = if (follow <= today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            Column {
                Text(fmt.status(c.status), style = MaterialTheme.typography.labelMedium)
                Text(fmt.label(c.priority), style = MaterialTheme.typography.labelSmall, color = if (c.priority >= Priority.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                agentName(c.assignedAgentId)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
    )
}
