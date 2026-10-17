package ir.melkcrm.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.client.view.QuickSearch
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Property

/** A field showing the chosen item; tapping it opens a searchable list. */
@Composable
private fun PickerField(label: String, valueText: String?, onOpen: () -> Unit, onClear: (() -> Unit)?, error: String?) {
    val errorMsg = ir.melkcrm.app.ui.errorText(error)
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = valueText.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.not_set)) },
            isError = errorMsg != null,
            supportingText = if (errorMsg != null) {
                { Text(errorMsg) }
            } else {
                null
            },
            trailingIcon = {
                if (valueText != null && onClear != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                } else {
                    Icon(Icons.Filled.Search, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize().padding(end = if (valueText != null && onClear != null) 56.dp else 0.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen() },
        )
    }
}

@Composable
private fun <T> SearchDialog(
    title: String,
    items: List<T>,
    filter: (List<T>, String) -> List<T>,
    key: (T) -> String,
    headline: @Composable (T) -> String,
    supporting: @Composable (T) -> String?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) { filter(items, query).take(200) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    EmptyState(stringResource(R.string.empty_list))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = key) { item ->
                            val sub = supporting(item)
                            ListItem(
                                headlineContent = { Text(headline(item)) },
                                supportingContent = if (sub != null) {
                                    { Text(sub) }
                                } else {
                                    null
                                },
                                modifier = Modifier.clickable { onPick(item) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A search dialog over the customers this user can see. */
@Composable
fun CustomerPickDialog(title: String, onPick: (Customer) -> Unit, onDismiss: () -> Unit, filter: (Customer) -> Boolean = { true }) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val customers by app.crm.customers.customers.collectAsStateWithLifecycle(initialValue = emptyList())
    val list = remember(customers) { customers.filter { !it.deleted && filter(it) }.sortedBy { it.fullName } }
    SearchDialog(
        title = title, items = list, filter = { l, q -> QuickSearch.customers(l, q) }, key = { it.id },
        headline = { it.fullName },
        supporting = { c -> listOfNotNull(c.mobile?.let { fmt.phone(it) }, c.city).joinToString(" · ").ifBlank { null } },
        onPick = onPick, onDismiss = onDismiss,
    )
}

/** Picks a customer the user can see. Contact details are shown only when visible to the user. */
@Composable
fun CustomerPicker(label: String, selectedId: String?, onSelect: (String?) -> Unit, error: String? = null, filter: (Customer) -> Boolean = { true }) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val customers by app.crm.customers.customers.collectAsStateWithLifecycle(initialValue = emptyList())
    val list = remember(customers) { customers.filter { !it.deleted && filter(it) }.sortedBy { it.fullName } }
    var open by remember { mutableStateOf(false) }
    val selected = customers.firstOrNull { it.id == selectedId }
    PickerField(label, selected?.fullName ?: selectedId?.let { stringResource(R.string.unknown) }, { open = true }, { onSelect(null) }, error)
    if (open) {
        SearchDialog(
            title = label, items = list, filter = { l, q -> QuickSearch.customers(l, q) }, key = { it.id },
            headline = { it.fullName },
            supporting = { c -> listOfNotNull(c.mobile?.let { fmt.phone(it) }, c.city, c.roles.joinToString("، ") { r -> fmt.label(r) }.ifBlank { null }).joinToString(" · ").ifBlank { null } },
            onPick = { onSelect(it.id); open = false }, onDismiss = { open = false },
        )
    }
}

@Composable
fun PropertyPicker(label: String, selectedId: String?, onSelect: (String?) -> Unit, error: String? = null, includeClosed: Boolean = false) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val properties by app.crm.properties.properties.collectAsStateWithLifecycle(initialValue = emptyList())
    val list = remember(properties, includeClosed) { properties.filter { includeClosed || !it.status.isClosed }.sortedByDescending { it.updatedAt } }
    var open by remember { mutableStateOf(false) }
    val selected = properties.firstOrNull { it.id == selectedId }
    PickerField(label, selected?.let { "${it.reference} · ${it.title}" } ?: selectedId?.let { stringResource(R.string.unknown) }, { open = true }, { onSelect(null) }, error)
    if (open) {
        SearchDialog(
            title = label, items = list, filter = { l, q -> QuickSearch.properties(l, q) }, key = { it.id },
            headline = { p: Property -> "${p.reference} · ${p.title}" },
            supporting = { p: Property -> listOfNotNull(p.neighborhood, p.city, fmt.label(p.status)).joinToString(" · ") },
            onPick = { onSelect(it.id); open = false }, onDismiss = { open = false },
        )
    }
}

/** Assigned agent. The server refuses reassignment the user's role does not allow. */
@Composable
fun AgentPicker(label: String, selectedId: String?, onSelect: (String?) -> Unit, allowNone: Boolean = true) {
    val app = LocalApp.current
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val users = agency.users.filter { it.active || it.id == selectedId }
    Dropdown(
        label = label, options = users, selected = users.firstOrNull { it.id == selectedId }, onSelect = { u: AgencyUser? -> onSelect(u?.id) },
        optionLabel = { it.displayName }, allowNone = allowNone,
    )
}

@Composable
fun agentName(id: String?): String? {
    val app = LocalApp.current
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    return id?.let { i -> agency.user(i)?.displayName ?: i }
}
