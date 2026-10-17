package ir.melkcrm.app.ui.customers

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import ir.melkcrm.app.ui.activities.TimelineData
import ir.melkcrm.app.ui.activities.TimelineRow
import ir.melkcrm.app.ui.activities.timelineFlow
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.ConfirmDialog
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.LabeledValue
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.report
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Deal
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Note
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.SellerProfile
import ir.melkcrm.core.permissions.Access
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomerDetailViewModel(private val app: AppContainer, val id: String) : ViewModel() {
    data class State(val loading: Boolean = true, val customer: Customer? = null, val access: Access = Access.NONE)

    private val started = SharingStarted.WhileSubscribed(5_000)
    private val crm = app.crm

    val state: StateFlow<State> = crm.customers.customers.map { l -> l.firstOrNull { it.id == id } }
        .mapLatest { c -> State(false, c, if (c == null) Access.NONE else crm.customers.access(id)) }
        .stateIn(viewModelScope, started, State())
    val requirements: StateFlow<List<BuyerRequirement>> = crm.customers.requirementsOf(id).map { it.sortedByDescending { r -> r.updatedAt } }.stateIn(viewModelScope, started, emptyList())
    val sellerProfiles: StateFlow<List<SellerProfile>> = crm.customers.sellerProfilesOf(id).stateIn(viewModelScope, started, emptyList())
    val owned: StateFlow<List<Property>> = crm.properties.ownedBy(id).stateIn(viewModelScope, started, emptyList())
    val properties: StateFlow<Map<String, Property>> = crm.properties.properties.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())
    val notes: StateFlow<List<Note>> = crm.customers.notesOf(id).stateIn(viewModelScope, started, emptyList())
    val deals: StateFlow<List<Deal>> = crm.deals.dealsOfCustomer(id).map { it.sortedByDescending { d -> d.updatedAt } }.stateIn(viewModelScope, started, emptyList())
    val timeline: StateFlow<TimelineData?> = timelineFlow(app, id, null).stateIn(viewModelScope, started, null)

    fun addNote(text: String) = viewModelScope.launch {
        if (crm.customers.addNote(text, customerId = id) != null) app.say(Msg.Res(R.string.saved))
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        if (app.report(crm.customers.delete(id), R.string.deleted_msg)) onDone()
    }
}

private val tabs = listOf(R.string.tab_overview, R.string.tab_buying, R.string.tab_selling, R.string.tab_history, R.string.tab_offers)

@Composable
fun CustomerDetailScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "customer-$id") { CustomerDetailViewModel(app, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val c = state.customer
    val canWrite = state.access == Access.WRITE

    ScreenScaffold(
        title = c?.fullName?.ifBlank { null } ?: stringResource(R.string.customers_title),
        onBack = { nav.back() },
        actions = {
            if (c != null) {
                IconButton(onClick = { nav.go(Routes.history(id)) }) { Icon(Icons.Filled.History, stringResource(R.string.tab_history)) }
                if (canWrite) {
                    IconButton(onClick = { nav.go(Routes.customerEdit(id)) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Loading()
            c == null -> EmptyState(stringResource(R.string.customer_not_found), Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
                }
                when (tab) {
                    0 -> OverviewTab(vm, c, state.access, nav)
                    1 -> BuyingTab(vm, c, canWrite, nav)
                    2 -> SellingTab(vm, c, canWrite, nav)
                    3 -> HistoryTab(vm, c, canWrite, nav)
                    else -> DealsTab(vm, nav)
                }
            }
        }
    }

    if (confirmDelete && c != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(R.string.customer_delete_confirm, c.fullName),
            confirm = stringResource(R.string.delete),
            onConfirm = { confirmDelete = false; vm.delete { nav.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun OverviewTab(vm: CustomerDetailViewModel, c: Customer, access: Access, nav: Nav) {
    val fmt = LocalFormat.current
    val app = LocalApp.current
    val context = LocalContext.current
    val notes by vm.notes.collectAsStateWithLifecycle()
    var note by rememberSaveable { mutableStateOf("") }
    FormColumn(PaddingValues(0.dp)) {
        if (c.isDraft) Banner(stringResource(R.string.draft_badge))
        SectionCard(null) {
            if (access == Access.MASKED) {
                Banner(stringResource(R.string.contact_hidden))
            } else {
                LabeledValue(stringResource(R.string.customer_mobile), fmt.phone(c.mobile))
                LabeledValue(stringResource(R.string.customer_alt_phone), fmt.phone(c.altPhone))
                LabeledValue(stringResource(R.string.customer_email), c.email)
                LabeledValue(stringResource(R.string.field_address), c.address)
                val phone = c.mobile ?: c.altPhone
                if (phone != null) {
                    // Opens the dialer with the number filled in; the agent places the call.
                    OutlinedButton(
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
                                .onFailure { app.say(Msg.Res(R.string.err_generic)) }
                        },
                        enabled = c.consent.callsAllowed,
                    ) { Text(stringResource(if (c.consent.callsAllowed) R.string.call else R.string.outreach_consent_blocked)) }
                }
            }
            LabeledValue(stringResource(R.string.customer_roles), c.roles.joinToString("، ") { fmt.label(it) })
            LabeledValue(stringResource(R.string.status), fmt.status(c.status))
            LabeledValue(stringResource(R.string.priority), fmt.label(c.priority))
            LabeledValue(stringResource(R.string.customer_assigned_agent), agentName(c.assignedAgentId) ?: stringResource(R.string.unassigned))
            LabeledValue(stringResource(R.string.customer_source), fmt.label(c.source))
            LabeledValue(stringResource(R.string.customer_preferred_contact), fmt.label(c.preferredContact))
            LabeledValue(stringResource(R.string.field_city), listOfNotNull(c.neighborhood, c.city, c.province).joinToString("، "))
            LabeledValue(stringResource(R.string.customer_registered), fmt.dateTime(c.registeredAt))
            LabeledValue(stringResource(R.string.customer_last_contact), fmt.dateTime(c.lastContactAt))
            LabeledValue(stringResource(R.string.customer_next_followup), fmt.date(c.nextFollowUpDate))
            LabeledValue(stringResource(R.string.customer_tags), c.tags.joinToString("، "))
        }
        SectionCard(stringResource(R.string.customer_consent)) {
            Text(consentSummary(c), style = MaterialTheme.typography.bodyMedium)
            c.consent.recordedAt?.let { Text(fmt.dateTime(it), style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard(null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.go(Routes.activityEdit(null, c.id)) }, label = { Text(stringResource(R.string.log_activity)) })
                AssistChip(onClick = { nav.go(Routes.taskEdit(null, c.id)) }, label = { Text(stringResource(R.string.task_new)) })
                AssistChip(onClick = { nav.go(Routes.appointmentEdit(null, c.id)) }, label = { Text(stringResource(R.string.appointment_new)) })
                AssistChip(onClick = { nav.go(Routes.requirementEdit(c.id, null)) }, label = { Text(stringResource(R.string.req_new)) })
                AssistChip(onClick = { nav.go(Routes.propertyEdit(null, c.id)) }, label = { Text(stringResource(R.string.qa_add_property)) })
            }
        }
        SectionCard(stringResource(R.string.notes)) {
            c.notes?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
            MultilineInput(note, { note = it }, stringResource(R.string.add_note))
            Button(onClick = { vm.addNote(note); note = "" }, enabled = note.isNotBlank()) { Text(stringResource(R.string.add)) }
            for (n in notes) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(n.text)
                Text(
                    listOfNotNull(fmt.dateTime(n.createdAt), agentName(n.authorId)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun consentSummary(c: Customer): String {
    fun mark(allowed: Boolean) = if (allowed) "✓" else "✗"
    return listOf(
        mark(c.consent.callsAllowed) + " " + stringResource(R.string.consent_calls),
        mark(c.consent.smsAllowed) + " " + stringResource(R.string.consent_sms),
        mark(c.consent.messengerAllowed) + " " + stringResource(R.string.consent_messenger),
        mark(c.consent.marketingAllowed) + " " + stringResource(R.string.consent_marketing),
    ).joinToString("\n")
}

/** One-line requirement summary built only from recorded values; a missing budget reads "not recorded", never "unlimited". */
@Composable
fun requirementSummary(r: BuyerRequirement): String {
    val fmt = LocalFormat.current
    val unknown = stringResource(R.string.unknown_budget)
    val budget = when (r.dealType) {
        DealType.SALE -> when {
            r.maxBudget != null -> listOfNotNull(r.minBudget?.let { fmt.moneyCompact(it) }, fmt.moneyCompact(r.maxBudget)).joinToString(" – ")
            else -> unknown
        }
        DealType.RENT -> listOfNotNull(
            r.maxDeposit?.let { stringResource(R.string.offer_deposit) + " " + fmt.moneyCompact(it) },
            r.maxMonthlyRent?.let { stringResource(R.string.offer_rent) + " " + fmt.moneyCompact(it) },
        ).joinToString(" · ").ifBlank { unknown }
        DealType.FULL_DEPOSIT -> r.maxDeposit?.let { fmt.moneyCompact(it) } ?: unknown
    }
    val place = (r.neighborhoods + r.cities).take(3).joinToString("، ")
    return listOfNotNull(
        fmt.label(r.dealType),
        r.categories.joinToString("، ") { fmt.label(it) }.ifBlank { null },
        budget,
        place.ifBlank { null },
        r.minArea?.let { fmt.area(it) + "+" },
        r.minBedrooms?.let { fmt.num(it) + "+ " + stringResource(R.string.property_bedrooms) },
    ).joinToString(" · ")
}

@Composable
private fun BuyingTab(vm: CustomerDetailViewModel, c: Customer, canWrite: Boolean, nav: Nav) {
    val fmt = LocalFormat.current
    val reqs by vm.requirements.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        if (canWrite) {
            item {
                Button(onClick = { nav.go(Routes.requirementEdit(c.id, null)) }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.req_new)) }
            }
        }
        if (reqs.isEmpty()) item { EmptyState(stringResource(R.string.req_none)) }
        items(reqs, key = { it.id }) { r ->
            ListItem(
                modifier = Modifier.clickable(enabled = canWrite) { nav.go(Routes.requirementEdit(c.id, r.id)) },
                headlineContent = { Text(r.title ?: fmt.label(r.dealType)) },
                supportingContent = { Text(requirementSummary(r)) },
                overlineContent = { Text(fmt.label(r.status)) },
                trailingContent = {
                    TextButton(onClick = { nav.go(Routes.propertiesFor(r.id)) }) { Text(stringResource(R.string.find_matching_properties)) }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SellingTab(vm: CustomerDetailViewModel, c: Customer, canWrite: Boolean, nav: Nav) {
    val fmt = LocalFormat.current
    val owned by vm.owned.collectAsStateWithLifecycle()
    val profiles by vm.sellerProfiles.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    // Properties the customer owns, plus any property they hold seller instructions for.
    val shown = (owned + profiles.mapNotNull { properties[it.propertyId] }).distinctBy { it.id }
    LazyColumn(Modifier.fillMaxSize()) {
        if (canWrite) {
            item {
                Button(onClick = { nav.go(Routes.propertyEdit(null, c.id)) }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.qa_add_property)) }
            }
        }
        if (shown.isEmpty()) item { EmptyState(stringResource(R.string.seller_none)) }
        items(shown, key = { it.id }) { p ->
            val profile = profiles.filter { it.propertyId == p.id }.maxByOrNull { it.updatedAt }
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.property(p.id)) },
                overlineContent = { Text(p.reference) },
                headlineContent = { Text(p.title) },
                supportingContent = {
                    Text(listOfNotNull(fmt.label(p.status), profile?.let { stringResource(R.string.seller_status) + ": " + fmt.label(it.status) }).joinToString(" · "))
                },
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canWrite) {
                    AssistChip(
                        onClick = { nav.go(Routes.sellerEdit(p.id, profile?.id)) },
                        label = { Text(stringResource(if (profile == null) R.string.seller_new else R.string.seller_edit)) },
                    )
                }
                AssistChip(onClick = { nav.go(Routes.buyersFor(p.id)) }, label = { Text(stringResource(R.string.find_best_buyers)) })
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun HistoryTab(vm: CustomerDetailViewModel, c: Customer, canWrite: Boolean, nav: Nav) {
    val data by vm.timeline.collectAsStateWithLifecycle()
    val d = data
    if (d == null) {
        Loading()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (canWrite) {
            item {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { nav.go(Routes.activityEdit(null, c.id)) }, label = { Text(stringResource(R.string.log_activity)) })
                    AssistChip(onClick = { nav.go(Routes.taskEdit(null, c.id)) }, label = { Text(stringResource(R.string.task_new)) })
                    AssistChip(onClick = { nav.go(Routes.appointmentEdit(null, c.id)) }, label = { Text(stringResource(R.string.appointment_new)) })
                }
            }
        }
        if (d.entries.isEmpty()) item { EmptyState(stringResource(R.string.empty_list)) }
        items(d.entries, key = { it.kind.name + it.id }) { e ->
            TimelineRow(e, d, nav, showCustomer = false)
            HorizontalDivider()
        }
    }
}

@Composable
private fun DealsTab(vm: CustomerDetailViewModel, nav: Nav) {
    val fmt = LocalFormat.current
    val deals by vm.deals.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        if (deals.isEmpty()) item { EmptyState(stringResource(R.string.deals_none)) }
        items(deals, key = { it.id }) { d ->
            val p = properties[d.propertyId]
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.deal(d.id)) },
                overlineContent = { Text(fmt.label(d.dealType)) },
                headlineContent = { Text(p?.let { "${it.reference} · ${it.title}" } ?: stringResource(R.string.property_not_found)) },
                supportingContent = { Text(stringResource(R.string.deal_stage) + ": " + fmt.label(d.stage) + " · " + fmt.dateTime(d.updatedAt)) },
            )
            HorizontalDivider()
        }
    }
}
