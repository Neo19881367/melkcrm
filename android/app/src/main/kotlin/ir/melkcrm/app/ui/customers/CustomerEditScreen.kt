package ir.melkcrm.app.ui.customers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.AgentPicker
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.DuplicatesDialog
import ir.melkcrm.app.ui.components.EditorScaffold
import ir.melkcrm.app.ui.components.EditorViewModel
import ir.melkcrm.app.ui.components.EnumChips
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SwitchRow
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.CustomerForm
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.core.model.ContactMethod
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.CustomerRole
import ir.melkcrm.core.model.CustomerSource
import ir.melkcrm.core.model.CustomerStatus
import ir.melkcrm.core.model.Priority
import ir.melkcrm.core.time.AppLanguage

class CustomerEditViewModel(app: AppContainer, private val id: String?) : EditorViewModel<Customer, CustomerForm>(app) {
    val isNew = id == null

    override suspend fun load(): Customer? = if (id == null) crm.customers.newCustomer() else crm.customers.customer(id)
    override fun formOf(e: Customer) = CustomerForm.from(e, settings.language == AppLanguage.PERSIAN)
    override fun build(form: CustomerForm, base: Customer): FormResult<Customer> = form.build(base, crm.writer.now())
    override suspend fun save(e: Customer, confirmed: Boolean): SaveOutcome<Customer> = crm.customers.save(e, confirmed)

    init {
        start()
    }
}

@Composable
fun CustomerEditScreen(id: String?, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "customer-edit-$id") { CustomerEditViewModel(app, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) {
        val saved = state.done ?: return@LaunchedEffect
        if (vm.isNew) nav.replace(Routes.customer(saved.id)) else nav.back()
    }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.customer_new else R.string.customer_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        SectionCard(null) {
            TextInput(f.fullName, { v -> vm.edit { it.copy(fullName = v) } }, stringResource(R.string.customer_full_name), error = err["fullName"])
            TextInput(f.mobile, { v -> vm.edit { it.copy(mobile = v) } }, stringResource(R.string.customer_mobile), error = err["mobile"], keyboard = KeyboardType.Phone)
            TextInput(f.altPhone, { v -> vm.edit { it.copy(altPhone = v) } }, stringResource(R.string.customer_alt_phone), error = err["altPhone"], keyboard = KeyboardType.Phone)
            TextInput(f.email, { v -> vm.edit { it.copy(email = v) } }, stringResource(R.string.customer_email), error = err["email"], keyboard = KeyboardType.Email)
            EnumChips(stringResource(R.string.customer_roles), CustomerRole.entries, f.roles, { v -> vm.edit { it.copy(roles = v) } }, error = err["roles"])
            EnumDropdown(stringResource(R.string.customer_preferred_contact), ContactMethod.entries, f.preferredContact, { v -> vm.edit { it.copy(preferredContact = v) } })
        }
        SectionCard(null) {
            Dropdown(
                label = stringResource(R.string.status),
                options = CustomerStatus.entries.map { it.name } + agency.settings.customStatuses.map { it.code },
                selected = f.status,
                onSelect = { v -> if (v != null) vm.edit { it.copy(status = v) } },
                optionLabel = { fmt.status(it) },
                allowNone = false,
            )
            EnumDropdown(stringResource(R.string.priority), Priority.entries, f.priority, { v -> if (v != null) vm.edit { it.copy(priority = v) } }, allowNone = false)
            EnumDropdown(stringResource(R.string.customer_source), CustomerSource.entries, f.source, { v -> vm.edit { it.copy(source = v) } })
            AgentPicker(stringResource(R.string.customer_assigned_agent), f.assignedAgentId, { v -> vm.edit { it.copy(assignedAgentId = v) } })
            DateInput(f.nextFollowUpDate, { v -> vm.edit { it.copy(nextFollowUpDate = v) } }, stringResource(R.string.customer_next_followup))
        }
        SectionCard(null) {
            TextInput(f.province, { v -> vm.edit { it.copy(province = v) } }, stringResource(R.string.field_province))
            TextInput(f.city, { v -> vm.edit { it.copy(city = v) } }, stringResource(R.string.field_city), error = err["city"])
            TextInput(f.neighborhood, { v -> vm.edit { it.copy(neighborhood = v) } }, stringResource(R.string.field_neighborhood))
            TextInput(f.address, { v -> vm.edit { it.copy(address = v) } }, stringResource(R.string.field_address), singleLine = false, minLines = 2)
        }
        SectionCard(stringResource(R.string.customer_consent)) {
            val c = f.consent
            SwitchRow(stringResource(R.string.consent_calls), c.callsAllowed, { v -> vm.edit { it.copy(consent = c.copy(callsAllowed = v)) } })
            SwitchRow(stringResource(R.string.consent_sms), c.smsAllowed, { v -> vm.edit { it.copy(consent = c.copy(smsAllowed = v)) } })
            SwitchRow(stringResource(R.string.consent_messenger), c.messengerAllowed, { v -> vm.edit { it.copy(consent = c.copy(messengerAllowed = v)) } })
            SwitchRow(stringResource(R.string.consent_marketing), c.marketingAllowed, { v -> vm.edit { it.copy(consent = c.copy(marketingAllowed = v)) } })
        }
        SectionCard(null) {
            TextInput(f.tags, { v -> vm.edit { it.copy(tags = v) } }, stringResource(R.string.customer_tags), supporting = stringResource(R.string.list_hint_comma))
            MultilineInput(f.notes, { v -> vm.edit { it.copy(notes = v) } }, stringResource(R.string.notes))
            SwitchRow(stringResource(R.string.draft_badge), f.isDraft, { v -> vm.edit { it.copy(isDraft = v) } }, supporting = stringResource(R.string.draft_hint))
        }
    }

    if (state.duplicates.isNotEmpty()) {
        DuplicatesDialog(
            candidates = state.duplicates,
            onOpen = { cid -> vm.dismissDuplicates(); nav.go(Routes.customer(cid)) },
            onSaveAnyway = { vm.submit(confirmed = true) },
            onDismiss = { vm.dismissDuplicates() },
        )
    }
}
