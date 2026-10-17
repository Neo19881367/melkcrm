package ir.melkcrm.app.ui.deals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.LabeledValue
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.MoneyInput
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.NumberInput
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.report
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.Input
import ir.melkcrm.client.forms.Parsed
import ir.melkcrm.client.forms.TransactionForm
import ir.melkcrm.core.model.CommissionRecord
import ir.melkcrm.core.model.CommissionStatus
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Deal
import ir.melkcrm.core.model.DealStage
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Offer
import ir.melkcrm.core.model.OfferStatus
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.Transaction
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.offer.DealPipeline
import ir.melkcrm.core.offer.OfferLifecycle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One deal: pipeline stage, the offer and counteroffer chains, the completed transaction and commissions. */
class DealViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    private val crm = app.crm
    private val started = SharingStarted.WhileSubscribed(5_000)

    data class State(
        val deal: Deal?,
        val property: Property?,
        val buyer: Customer?,
        val seller: Customer?,
        val offers: List<Offer>,
        val transaction: Transaction?,
        val commissions: List<CommissionRecord>,
    )

    private val related = combine(crm.properties.properties, crm.customers.customers, crm.deals.transactions) { p, c, t -> Triple(p, c, t) }

    val state: StateFlow<State?> = combine(crm.deals.deals, crm.deals.offersOf(id), crm.deals.commissionsOf(id), related) { deals, offers, commissions, rel ->
        val (properties, customers, transactions) = rel
        val d = deals.firstOrNull { it.id == id }
        State(
            deal = d,
            property = d?.let { x -> properties.firstOrNull { it.id == x.propertyId } },
            buyer = d?.let { x -> customers.firstOrNull { it.id == x.buyerCustomerId } },
            seller = d?.sellerCustomerId?.let { s -> customers.firstOrNull { it.id == s } },
            offers = offers,
            transaction = transactions.firstOrNull { it.dealId == id },
            commissions = commissions,
        )
    }.stateIn(viewModelScope, started, null)

    fun rulesCount(dealType: DealType) = crm.deals.rulesFor(dealType).size

    fun moveStage(to: DealStage, reason: String?) = viewModelScope.launch { app.report(crm.deals.moveDeal(id, to, reason)) }

    fun transition(offerId: String, to: OfferStatus, note: String?) = viewModelScope.launch {
        app.report(crm.deals.transitionOffer(offerId, to, note?.trim()?.ifBlank { null }))
    }

    /** Returns field errors to show in the form; empty when saved. */
    suspend fun complete(deal: Deal, form: TransactionForm): Map<String, String> {
        val unit = app.currentSettings().currency
        return when (val built = form.build(deal.dealType, unit)) {
            is FormResult.Errors -> built.errors
            is FormResult.Ok -> {
                val v = built.value
                val out = crm.deals.completeTransaction(deal.id, v.price, v.deposit, v.rent, v.contractDate, v.leaseMonths, v.notes)
                app.report(out, R.string.transaction_saved)
                if (out is SaveOutcome.Invalid) out.issues.associate { it.field to it.code } else emptyMap()
            }
        }
    }

    /** Parses [text] in the display unit; returns an error code or null when the call was made. */
    suspend fun commissionAmount(commissionId: String, text: String, payment: Boolean): String? {
        val unit = app.currentSettings().currency
        val amount = when (val p = Input.money(text, unit)) {
            is Parsed.Ok -> p.value ?: return "required"
            is Parsed.Error -> return p.code
        }
        val out = if (payment) crm.deals.recordCommissionPayment(commissionId, amount) else crm.deals.confirmCommission(commissionId, amount)
        app.report(out)
        return null
    }
}

@Composable
fun DealScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "deal-$id") { DealViewModel(app, id) }
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = stringResource(R.string.deal_title),
        onBack = { nav.back() },
        actions = { IconButton(onClick = { nav.go(Routes.history(id)) }) { Icon(Icons.Filled.History, stringResource(R.string.history_title)) } },
    ) { padding ->
        val s = state
        val deal = s?.deal
        when {
            s == null -> Loading()
            deal == null -> EmptyState(stringResource(R.string.record_not_found), Modifier.padding(padding))
            else -> FormColumn(padding) {
                DealHeader(s, deal, nav)
                StageSection(deal, vm)
                OffersSection(deal, s.offers, vm, nav)
                TransactionSection(deal, s.transaction, s.offers, vm)
                if (s.transaction != null || s.commissions.isNotEmpty()) CommissionsSection(s.commissions, vm)
            }
        }
    }
}

@Composable
private fun DealHeader(s: DealViewModel.State, deal: Deal, nav: Nav) {
    val fmt = LocalFormat.current
    SectionCard(null) {
        val p = s.property
        ListItem(
            modifier = Modifier.clickable { nav.go(Routes.property(deal.propertyId)) },
            overlineContent = { Text(stringResource(R.string.deal_property)) },
            headlineContent = { Text(p?.let { "${it.reference} · ${it.title}" } ?: stringResource(R.string.record_not_found)) },
            supportingContent = { Text(fmt.label(deal.dealType)) },
        )
        ListItem(
            modifier = Modifier.clickable { nav.go(Routes.customer(deal.buyerCustomerId)) },
            overlineContent = { Text(stringResource(R.string.deal_buyer)) },
            headlineContent = { Text(s.buyer?.fullName ?: stringResource(R.string.unknown)) },
        )
        val sellerId = deal.sellerCustomerId
        if (sellerId != null) {
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.customer(sellerId)) },
                overlineContent = { Text(stringResource(R.string.deal_seller)) },
                headlineContent = { Text(s.seller?.fullName ?: stringResource(R.string.unknown)) },
            )
        }
        LabeledValue(stringResource(R.string.field_agent), agentName(deal.agentId))
        LabeledValue(stringResource(R.string.deal_opened), fmt.dateTime(deal.openedAt))
        if (deal.closedAt != null) LabeledValue(stringResource(R.string.deal_closed), fmt.dateTime(deal.closedAt))
    }
}

@Composable
private fun StageSection(deal: Deal, vm: DealViewModel) {
    val fmt = LocalFormat.current
    var target by remember { mutableStateOf<DealStage?>(null) }
    SectionCard(stringResource(R.string.deal_stage)) {
        Text(fmt.label(deal.stage), style = MaterialTheme.typography.titleMedium)
        deal.cancelReason?.let { LabeledValue(stringResource(R.string.deal_cancel_reason), it) }
        if (deal.stage.isTerminal) {
            Text(stringResource(R.string.deal_closed_note), style = MaterialTheme.typography.bodySmall)
        } else {
            // Completion goes through the transaction form so the final figures are recorded.
            val options = DealStage.entries.filter { it != DealStage.TRANSACTION_COMPLETED && DealPipeline.canMove(deal.stage, it) }
            Dropdown(
                label = stringResource(R.string.deal_move_stage), options = options, selected = null,
                onSelect = { v -> target = v }, optionLabel = { fmt.label(it) },
            )
        }
    }
    val t = target
    if (t != null) MoveStageDialog(t, onDismiss = { target = null }) { reason ->
        vm.moveStage(t, reason)
        target = null
    }
}

@Composable
private fun MoveStageDialog(to: DealStage, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    val fmt = LocalFormat.current
    var reason by remember { mutableStateOf("") }
    val cancelling = to == DealStage.TRANSACTION_CANCELLED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deal_move_confirm, fmt.label(to))) },
        text = {
            Column {
                if (cancelling) Text(stringResource(R.string.deal_cancel_note), style = MaterialTheme.typography.bodySmall)
                TextInput(reason, { reason = it }, stringResource(if (cancelling) R.string.deal_cancel_reason else R.string.deal_move_note))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim().ifBlank { null }) }, enabled = !cancelling || reason.isNotBlank()) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Sale price, or deposit and rent, as recorded on the offer. */
fun offerFigures(o: Offer, fmt: UiFormat, depositLabel: String, rentLabel: String): String = when (o.dealType) {
    DealType.SALE -> fmt.money(o.offeredPrice)
    else -> listOfNotNull(
        o.offeredDeposit?.let { "$depositLabel ${fmt.money(it)}" },
        o.offeredMonthlyRent?.let { "$rentLabel ${fmt.money(it)}" },
    ).joinToString(" · ")
}

@Composable
private fun OffersSection(deal: Deal, offers: List<Offer>, vm: DealViewModel, nav: Nav) {
    // Each chain ends at an offer nobody has countered; show the newest chain first.
    val parents = offers.mapNotNull { it.parentOfferId }.toSet()
    val chains = offers.filter { it.id !in parents }.sortedByDescending { it.offerDate }.map { OfferLifecycle.chain(it, offers) }
    SectionCard(stringResource(R.string.offers_title)) {
        Text(stringResource(R.string.offer_not_binding), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chains.isEmpty()) Text(stringResource(R.string.deals_none), style = MaterialTheme.typography.bodyMedium)
        for ((i, chain) in chains.withIndex()) {
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (chain.size > 1) Text(stringResource(R.string.offer_chain), style = MaterialTheme.typography.labelMedium)
            for (o in chain) key(o.id) { OfferRow(o, deal, vm, nav) }
        }
        if (!deal.stage.isTerminal) {
            Button(onClick = { nav.go(Routes.offerEdit(deal.id)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.offer_new)) }
        }
    }
}

@Composable
private fun OfferRow(o: Offer, deal: Deal, vm: DealViewModel, nav: Nav) {
    val fmt = LocalFormat.current
    var respond by remember { mutableStateOf<OfferStatus?>(null) }
    val dep = stringResource(R.string.offer_deposit)
    val rent = stringResource(R.string.offer_rent)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            fmt.label(o.direction) + " · " + fmt.label(o.status),
            style = MaterialTheme.typography.labelLarge,
            color = if (o.status.isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(offerFigures(o, fmt, dep, rent).ifBlank { stringResource(R.string.not_set) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(fmt.dateTime(o.offerDate), o.expiresAt?.let { stringResource(R.string.offer_expires) + ": " + fmt.dateTime(it) }, agentName(o.agentId)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        o.conditions?.let { Text(stringResource(R.string.offer_conditions) + ": " + it, style = MaterialTheme.typography.bodySmall) }
        o.responseNote?.let { Text(stringResource(R.string.offer_response_note) + ": " + it, style = MaterialTheme.typography.bodySmall) }
        o.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!deal.stage.isTerminal && o.status.isOpen) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (o.status == OfferStatus.DRAFT) {
                    AssistChip(onClick = { nav.go(Routes.offerEdit(deal.id, offerId = o.id)) }, label = { Text(stringResource(R.string.edit)) })
                    AssistChip(onClick = { vm.transition(o.id, OfferStatus.SUBMITTED, null) }, label = { Text(stringResource(R.string.offer_submit)) })
                }
                if (OfferLifecycle.canTransition(o.status, OfferStatus.ACCEPTED)) {
                    AssistChip(onClick = { respond = OfferStatus.ACCEPTED }, label = { Text(stringResource(R.string.offer_accept)) })
                }
                if (OfferLifecycle.canTransition(o.status, OfferStatus.REJECTED)) {
                    AssistChip(onClick = { respond = OfferStatus.REJECTED }, label = { Text(stringResource(R.string.offer_reject)) })
                }
                if (OfferLifecycle.canTransition(o.status, OfferStatus.COUNTERED)) {
                    AssistChip(onClick = { nav.go(Routes.offerEdit(deal.id, counterOf = o.id)) }, label = { Text(stringResource(R.string.offer_counter_new)) })
                }
                if (OfferLifecycle.canTransition(o.status, OfferStatus.WITHDRAWN)) {
                    AssistChip(onClick = { respond = OfferStatus.WITHDRAWN }, label = { Text(stringResource(R.string.offer_withdraw)) })
                }
            }
        }
    }
    val r = respond
    if (r != null) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { respond = null },
            title = { Text(stringResource(R.string.offer_mark_as, fmt.label(r))) },
            text = {
                Column {
                    if (r == OfferStatus.ACCEPTED) Text(stringResource(R.string.offer_accept_note), style = MaterialTheme.typography.bodySmall)
                    TextInput(note, { note = it }, stringResource(R.string.offer_response_note))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.transition(o.id, r, note)
                    respond = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { respond = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TransactionSection(deal: Deal, tx: Transaction?, offers: List<Offer>, vm: DealViewModel) {
    val fmt = LocalFormat.current
    val app = LocalApp.current
    if (tx != null) {
        SectionCard(stringResource(R.string.transaction_title)) {
            if (deal.dealType == DealType.SALE) LabeledValue(stringResource(R.string.transaction_final_price), fmt.money(tx.finalPrice))
            else {
                LabeledValue(stringResource(R.string.transaction_final_deposit), fmt.money(tx.finalDeposit))
                if (deal.dealType == DealType.RENT) LabeledValue(stringResource(R.string.transaction_final_rent), fmt.money(tx.finalMonthlyRent))
                LabeledValue(stringResource(R.string.transaction_lease_months), tx.leaseMonths?.let { fmt.num(it) })
            }
            LabeledValue(stringResource(R.string.transaction_contract_date), fmt.date(tx.contractDate))
            LabeledValue(stringResource(R.string.notes), tx.notes)
        }
        return
    }
    if (deal.stage.isTerminal) return
    var open by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.transaction_title)) {
        if (!open) {
            Text(stringResource(R.string.transaction_explain), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.transaction_complete)) }
        } else {
            // Figures start from the accepted offer, when there is one; the agent confirms the actual contract values.
            val accepted = offers.filter { it.status == OfferStatus.ACCEPTED }.maxByOrNull { it.updatedAt }
            val unit = fmt.unit
            var form by remember {
                mutableStateOf(
                    TransactionForm(
                        finalPrice = Input.moneyText(accepted?.offeredPrice, unit, fmt.digits),
                        finalDeposit = Input.moneyText(accepted?.offeredDeposit, unit, fmt.digits),
                        finalMonthlyRent = Input.moneyText(accepted?.offeredMonthlyRent, unit, fmt.digits),
                        contractDate = app.crm.writer.today(),
                    ),
                )
            }
            var errors by remember { mutableStateOf(emptyMap<String, String>()) }
            var saving by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            if (accepted != null) Text(stringResource(R.string.transaction_prefilled_note), style = MaterialTheme.typography.bodySmall)
            if (deal.dealType == DealType.SALE) {
                MoneyInput(form.finalPrice, { v -> form = form.copy(finalPrice = v) }, stringResource(R.string.transaction_final_price), error = errors["finalPrice"])
            } else {
                MoneyInput(form.finalDeposit, { v -> form = form.copy(finalDeposit = v) }, stringResource(R.string.transaction_final_deposit), error = errors["finalDeposit"])
                if (deal.dealType == DealType.RENT) {
                    MoneyInput(form.finalMonthlyRent, { v -> form = form.copy(finalMonthlyRent = v) }, stringResource(R.string.transaction_final_rent), error = errors["finalMonthlyRent"])
                }
                NumberInput(form.leaseMonths, { v -> form = form.copy(leaseMonths = v) }, stringResource(R.string.transaction_lease_months), error = errors["leaseMonths"])
            }
            DateInput(form.contractDate, { v -> if (v != null) form = form.copy(contractDate = v) }, stringResource(R.string.transaction_contract_date), clearable = false)
            MultilineInput(form.notes, { v -> form = form.copy(notes = v) }, stringResource(R.string.notes))
            val rules = vm.rulesCount(deal.dealType)
            Text(
                if (rules == 0) stringResource(R.string.transaction_no_rules) else stringResource(R.string.transaction_rules_apply, rules),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.transaction_effects_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        errors = vm.complete(deal, form)
                        saving = false
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun CommissionsSection(commissions: List<CommissionRecord>, vm: DealViewModel) {
    val fmt = LocalFormat.current
    SectionCard(stringResource(R.string.commission_title)) {
        Text(stringResource(R.string.commission_estimate_note), style = MaterialTheme.typography.bodySmall)
        if (commissions.isEmpty()) Text(stringResource(R.string.transaction_no_rules), style = MaterialTheme.typography.bodyMedium)
        for (c in commissions) key(c.id) {
            var dialog by remember { mutableStateOf<Boolean?>(null) } // true = payment, false = confirm amount
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    stringResource(R.string.commission_party) + ": " + fmt.label(c.party) + " · " + fmt.label(c.status),
                    style = MaterialTheme.typography.labelLarge,
                )
                c.agreedTerms?.let { Text(stringResource(R.string.commission_rule) + ": " + it, style = MaterialTheme.typography.bodySmall) }
                if (c.isEstimate) Banner(stringResource(R.string.commission_estimate_badge) + " · " + stringResource(R.string.commission_expected) + ": " + fmt.money(c.expectedAmount).ifBlank { "—" })
                else LabeledValue(stringResource(R.string.commission_expected), fmt.money(c.expectedAmount))
                LabeledValue(stringResource(R.string.commission_earned), fmt.money(c.earnedAmount))
                LabeledValue(stringResource(R.string.commission_received), fmt.money(c.receivedAmount))
                if (!c.isEstimate) LabeledValue(stringResource(R.string.commission_outstanding), fmt.money(c.outstanding))
                if (c.status != CommissionStatus.CANCELLED) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { dialog = false }, label = { Text(stringResource(R.string.commission_confirm)) })
                        AssistChip(onClick = { dialog = true }, label = { Text(stringResource(R.string.commission_record_payment)) })
                    }
                }
            }
            val payment = dialog
            if (payment != null) {
                CommissionAmountDialog(
                    title = stringResource(if (payment) R.string.commission_record_payment else R.string.commission_confirm),
                    initial = if (payment) "" else Input.moneyText(c.earnedAmount ?: c.expectedAmount, fmt.unit, fmt.digits),
                    onDismiss = { dialog = null },
                    onSave = { text -> vm.commissionAmount(c.id, text, payment).also { if (it == null) dialog = null } },
                )
            }
        }
    }
}

@Composable
private fun CommissionAmountDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: suspend (String) -> String?) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { MoneyInput(text, { text = it }, stringResource(R.string.amount), error = error) },
        confirmButton = { TextButton(onClick = { scope.launch { error = onSave(text) } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
