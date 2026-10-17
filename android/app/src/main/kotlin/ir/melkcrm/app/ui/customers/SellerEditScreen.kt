package ir.melkcrm.app.ui.customers

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.CheckRow
import ir.melkcrm.app.ui.components.ConfidentialCard
import ir.melkcrm.app.ui.components.CustomerPicker
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EditorScaffold
import ir.melkcrm.app.ui.components.EditorViewModel
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.MoneyInput
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.NumberInput
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.TriStateInput
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.SellerForm
import ir.melkcrm.core.model.ContactMethod
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.FlexibilityLevel
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.SellerProfile
import ir.melkcrm.core.model.SellerStatus
import kotlinx.coroutines.flow.MutableStateFlow

class SellerEditViewModel(app: AppContainer, private val propertyId: String, private val id: String?) : EditorViewModel<SellerProfile, SellerForm>(app) {
    val isNew = id == null
    var property: Property? = null
        private set

    /** Whether this user may see and change the seller's minimum price; decided by the same rule the server applies. */
    var canEditConfidential = false
        private set

    /** The seller. A new profile starts with the property's owner when one is recorded. */
    val sellerId = MutableStateFlow<String?>(null)

    override suspend fun load(): SellerProfile? {
        val p = crm.properties.property(propertyId) ?: return null
        property = p
        canEditConfidential = crm.properties.canSeeConfidential(p)
        val s = if (id == null) crm.customers.newSeller(p.ownerCustomerId.orEmpty(), p.id) else crm.customers.seller(id)?.takeIf { it.propertyId == p.id }
        sellerId.value = s?.customerId?.ifBlank { null }
        return s
    }

    override fun formOf(e: SellerProfile) = SellerForm.from(e, settings.currency, settings.persianDigits)

    override fun build(form: SellerForm, base: SellerProfile): FormResult<SellerProfile> {
        val seller = sellerId.value ?: return FormResult.Errors(mapOf("customerId" to "required"))
        return form.build(base.copy(customerId = seller), settings.currency, canEditConfidential)
    }

    override suspend fun save(e: SellerProfile, confirmed: Boolean): SaveOutcome<SellerProfile> = crm.customers.saveSeller(e)

    init {
        start()
    }
}

@Composable
fun SellerEditScreen(propertyId: String, id: String?, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "seller-$propertyId-$id") { SellerEditViewModel(app, propertyId, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sellerId by vm.sellerId.collectAsStateWithLifecycle()
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) {
        if (state.done != null) nav.back()
    }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.seller_new else R.string.seller_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        val p = vm.property
        if (p != null) {
            Text("${p.reference} · ${p.title}", style = MaterialTheme.typography.titleMedium)
            if (!vm.isNew) OutlinedButton(onClick = { nav.go(Routes.buyersFor(p.id)) }) { Text(stringResource(R.string.find_best_buyers)) }
        }
        SectionCard(null) {
            CustomerPicker(stringResource(R.string.seller_customer), sellerId, { vm.sellerId.value = it }, error = err["customerId"])
            EnumDropdown(stringResource(R.string.seller_status), SellerStatus.entries, f.status, { v -> if (v != null) vm.edit { it.copy(status = v) } }, allowNone = false)
            DateInput(f.saleDeadline, { v -> vm.edit { it.copy(saleDeadline = v) } }, stringResource(R.string.seller_deadline))
            TextInput(f.motivation, { v -> vm.edit { it.copy(motivation = v) } }, stringResource(R.string.seller_motivation))
            EnumDropdown(stringResource(R.string.seller_price_flex), FlexibilityLevel.entries, f.priceFlexibility, { v -> if (v != null) vm.edit { it.copy(priceFlexibility = v) } }, allowNone = false)
            EnumDropdown(stringResource(R.string.customer_preferred_contact), ContactMethod.entries, f.preferredContact, { v -> vm.edit { it.copy(preferredContact = v) } })
            MultilineInput(f.negotiationConditions, { v -> vm.edit { it.copy(negotiationConditions = v) } }, stringResource(R.string.seller_negotiation_conditions))
            MultilineInput(f.instructions, { v -> vm.edit { it.copy(instructions = v) } }, stringResource(R.string.seller_instructions))
            NumberInput(f.followUpIntervalDays, { v -> vm.edit { it.copy(followUpIntervalDays = v) } }, stringResource(R.string.seller_followup_days), error = err["followUpIntervalDays"])
        }
        SectionCard(stringResource(R.string.commission_title)) {
            val rules = agency.settings.commissionRules.filter { it.active || it.id == f.commissionRuleId }
            Dropdown(
                label = stringResource(R.string.commission_rule), options = rules.map { it.id }, selected = f.commissionRuleId,
                onSelect = { v -> vm.edit { it.copy(commissionRuleId = v) } }, optionLabel = { rid -> rules.firstOrNull { it.id == rid }?.name ?: rid },
            )
            TextInput(f.commissionAgreement, { v -> vm.edit { it.copy(commissionAgreement = v) } }, stringResource(R.string.seller_commission_agreement), singleLine = false, minLines = 2)
            TriStateInput(stringResource(R.string.seller_exclusive), f.exclusive, { v -> vm.edit { it.copy(exclusive = v) } })
            if (f.exclusive == true) {
                DateInput(f.exclusiveUntil, { v -> vm.edit { it.copy(exclusiveUntil = v) } }, stringResource(R.string.seller_exclusive_until), error = err["exclusiveUntil"])
            }
        }
        SectionCard(stringResource(R.string.seller_documents)) {
            val d = f.documents
            CheckRow(stringResource(R.string.doc_deed), d.deedCopy) { v -> vm.edit { it.copy(documents = d.copy(deedCopy = v)) } }
            CheckRow(stringResource(R.string.doc_ids), d.idCards) { v -> vm.edit { it.copy(documents = d.copy(idCards = v)) } }
            CheckRow(stringResource(R.string.doc_end_of_work), d.endOfWorkCertificate) { v -> vm.edit { it.copy(documents = d.copy(endOfWorkCertificate = v)) } }
            CheckRow(stringResource(R.string.doc_municipality), d.municipalityClearance) { v -> vm.edit { it.copy(documents = d.copy(municipalityClearance = v)) } }
            CheckRow(stringResource(R.string.doc_tax), d.taxClearance) { v -> vm.edit { it.copy(documents = d.copy(taxClearance = v)) } }
            TextInput(d.notes.orEmpty(), { v -> vm.edit { it.copy(documents = d.copy(notes = v.ifBlank { null })) } }, stringResource(R.string.notes))
        }
        if (vm.canEditConfidential) {
            ConfidentialCard(stringResource(R.string.seller_confidential_section)) {
                val types = p?.dealTypes.orEmpty()
                if (DealType.SALE in types || types.isEmpty()) {
                    MoneyInput(f.minPrice, { v -> vm.edit { it.copy(minPrice = v) } }, stringResource(R.string.seller_min_price), error = err["minAcceptablePrice"])
                }
                if (DealType.RENT in types || DealType.FULL_DEPOSIT in types) {
                    MoneyInput(f.minDeposit, { v -> vm.edit { it.copy(minDeposit = v) } }, stringResource(R.string.seller_min_deposit), error = err["minAcceptableDeposit"])
                }
                if (DealType.RENT in types) {
                    MoneyInput(f.minRent, { v -> vm.edit { it.copy(minRent = v) } }, stringResource(R.string.seller_min_rent), error = err["minAcceptableMonthlyRent"])
                }
                MultilineInput(f.confidentialNotes, { v -> vm.edit { it.copy(confidentialNotes = v) } }, stringResource(R.string.seller_confidential_notes))
            }
        } else {
            Banner(stringResource(R.string.confidential_hidden))
        }
    }
}
