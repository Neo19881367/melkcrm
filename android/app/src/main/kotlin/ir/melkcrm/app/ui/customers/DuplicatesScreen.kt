package ir.melkcrm.app.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ir.melkcrm.app.ui.components.ConfirmDialog
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.report
import ir.melkcrm.core.duplicates.DuplicateDetector
import ir.melkcrm.core.model.Customer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DuplicatesViewModel(private val app: AppContainer) : ViewModel() {
    /** Groups of customers sharing a phone number, or with similar names in the same city. */
    val clusters: StateFlow<List<List<Customer>>?> = app.crm.customers.customers
        .map { l -> DuplicateDetector.clusters(l.filter { !it.deleted }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun merge(primaryId: String, secondaryId: String) = viewModelScope.launch {
        app.report(app.crm.customers.merge(primaryId, secondaryId), R.string.merge_done)
    }
}

@Composable
fun DuplicatesScreen(nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel { DuplicatesViewModel(app) }
    val clusters by vm.clusters.collectAsStateWithLifecycle()
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val canMerge = agency.policy.canMerge(app.crm.session.user)
    var pending by remember { mutableStateOf<Pair<Customer, Customer>?>(null) }

    ScreenScaffold(title = stringResource(R.string.duplicates_title), onBack = { nav.back() }) { padding ->
        val list = clusters
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(stringResource(R.string.duplicates_none), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (!canMerge) item { Text(stringResource(R.string.no_permission), color = MaterialTheme.colorScheme.error) }
                items(list, key = { g -> g.joinToString { it.id } }) { group -> ClusterCard(group, canMerge, nav) { primary, secondary -> pending = primary to secondary } }
            }
        }
    }

    pending?.let { (primary, secondary) ->
        ConfirmDialog(
            title = stringResource(R.string.duplicates_merge),
            text = stringResource(R.string.duplicates_merge_confirm, secondary.fullName, primary.fullName),
            confirm = stringResource(R.string.duplicates_merge),
            onConfirm = { pending = null; vm.merge(primary.id, secondary.id) },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ClusterCard(group: List<Customer>, canMerge: Boolean, nav: Nav, onMerge: (primary: Customer, secondary: Customer) -> Unit) {
    val fmt = LocalFormat.current
    // The record to keep; by default the one updated most recently.
    var keepId by remember(group) { mutableStateOf(group.maxByOrNull { it.updatedAt }?.id) }
    SectionCard(null) {
        Text(stringResource(R.string.duplicates_keep_hint), style = MaterialTheme.typography.bodySmall)
        for (c in group) {
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.customer(c.id)) },
                leadingContent = { RadioButton(selected = keepId == c.id, onClick = { keepId = c.id }, enabled = canMerge) },
                headlineContent = { Text(c.fullName) },
                supportingContent = {
                    Text(listOfNotNull(c.mobile?.let { fmt.phone(it) }, c.city, fmt.status(c.status), fmt.dateTime(c.updatedAt)).joinToString(" · "))
                },
                trailingContent = {
                    val keep = group.firstOrNull { it.id == keepId }
                    if (canMerge && keep != null && keep.id != c.id) {
                        TextButton(onClick = { onMerge(keep, c) }) { Text(stringResource(R.string.duplicates_merge)) }
                    }
                },
            )
        }
    }
}
