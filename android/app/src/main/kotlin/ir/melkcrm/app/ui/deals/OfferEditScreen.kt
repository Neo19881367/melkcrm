package ir.melkcrm.app.ui.deals

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.EditorScaffold
import ir.melkcrm.app.ui.components.EditorViewModel
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.LabeledValue
import ir.melkcrm.app.ui.components.MoneyInput
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.Input
import ir.melkcrm.client.forms.OfferForm
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Offer
import ir.melkcrm.core.model.OfferDirection
import ir.melkcrm.core.model.OfferStatus

/**
 * Records an offer on a deal, edits a draft, or counters a submitted offer. A counteroffer closes the
 * original as countered and opens a new submitted offer in the other direction; the original keeps its figures.
 */
class OfferEditViewModel(app: AppContainer, private val dealId: String, private val counterOf: String?, private val offerId: String?) :
    EditorViewModel<Offer, OfferForm>(app) {
    val isCounter = counterOf != null
    /** The offer being countered, shown for reference. */
    var original: Offer? = null
        private set
    /** Set by the "save as draft" button; the main save records the offer as submitted. */
    var asDraft = false
    private val zone get() = crm.writer.zone

    override suspend fun load(): Offer? = when {
        counterOf != null -> crm.deals.offer(counterOf)?.takeIf { it.status == OfferStatus.SUBMITTED && it.dealId == dealId }?.also { original = it }
        offerId != null -> crm.deals.offer(offerId)?.takeIf { it.status == OfferStatus.DRAFT && it.dealId == dealId }
        else -> crm.deals.deal(dealId)?.takeIf { !it.stage.isTerminal }?.let { crm.deals.newOffer(it) }
    }

    override fun formOf(e: Offer): OfferForm {
        val f = OfferForm.from(e, zone, settings.persianDigits).copy(unit = settings.currency)
        val refilled = f.copy(
            price = Input.moneyText(e.offeredPrice, settings.currency, settings.persianDigits),
            deposit = Input.moneyText(e.offeredDeposit, settings.currency, settings.persianDigits),
            monthlyRent = Input.moneyText(e.offeredMonthlyRent, settings.currency, settings.persianDigits),
        )
        // A counter starts from the countered figures, in the opposite direction, with a fresh expiry and no notes.
        return if (isCounter) {
            refilled.copy(
                direction = if (e.direction == OfferDirection.BUYER_TO_SELLER) OfferDirection.SELLER_TO_BUYER else OfferDirection.BUYER_TO_SELLER,
                expiresOn = null, notes = "",
            )
        } else refilled
    }

    override fun build(form: OfferForm, base: Offer): FormResult<Offer> = form.build(base, zone)

    override suspend fun save(e: Offer, confirmed: Boolean): SaveOutcome<Offer> =
        if (counterOf != null) crm.deals.counter(counterOf, e.offeredPrice, e.offeredDeposit, e.offeredMonthlyRent, e.conditions, e.expiresAt)
        else crm.deals.recordOffer(e, submit = !asDraft)

    init {
        start()
    }
}

@Composable
fun OfferEditScreen(dealId: String, counterOf: String?, offerId: String?, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "offer-$dealId-$counterOf-$offerId") { OfferEditViewModel(app, dealId, counterOf, offerId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done != null) nav.back() }

    EditorScaffold(
        title = stringResource(if (vm.isCounter) R.string.offer_counter_new else R.string.offer_new),
        state = state,
        onBack = { nav.back() },
        onSave = {
            vm.asDraft = false
            vm.submit()
        },
    ) { f ->
        val err = state.errors
        val dealType = state.base?.dealType ?: DealType.SALE
        Text(stringResource(R.string.offer_not_binding), style = MaterialTheme.typography.bodySmall)
        vm.original?.let { o ->
            SectionCard(stringResource(R.string.offer_countering)) {
                LabeledValue(stringResource(R.string.offer_direction), fmt.label(o.direction))
                LabeledValue(
                    stringResource(R.string.offer_figures),
                    offerFigures(o, fmt, stringResource(R.string.offer_deposit), stringResource(R.string.offer_rent)),
                )
                LabeledValue(stringResource(R.string.offer_conditions), o.conditions)
            }
        }
        SectionCard(null) {
            if (vm.isCounter) {
                LabeledValue(stringResource(R.string.offer_direction), fmt.label(f.direction))
            } else {
                EnumDropdown(stringResource(R.string.offer_direction), OfferDirection.entries, f.direction, { v -> if (v != null) vm.edit { it.copy(direction = v) } }, allowNone = false)
            }
            when (dealType) {
                DealType.SALE -> MoneyInput(f.price, { v -> vm.edit { it.copy(price = v) } }, stringResource(R.string.offer_price), error = err["offeredPrice"], unit = f.unit)
                DealType.RENT -> {
                    MoneyInput(f.deposit, { v -> vm.edit { it.copy(deposit = v) } }, stringResource(R.string.offer_deposit), error = err["offeredDeposit"], unit = f.unit)
                    MoneyInput(f.monthlyRent, { v -> vm.edit { it.copy(monthlyRent = v) } }, stringResource(R.string.offer_rent), error = err["offeredMonthlyRent"], unit = f.unit)
                }
                DealType.FULL_DEPOSIT -> MoneyInput(f.deposit, { v -> vm.edit { it.copy(deposit = v) } }, stringResource(R.string.offer_deposit), error = err["offeredDeposit"], unit = f.unit)
            }
            DateInput(f.expiresOn, { v -> vm.edit { it.copy(expiresOn = v) } }, stringResource(R.string.offer_expires), error = err["expiresAt"])
            MultilineInput(f.conditions, { v -> vm.edit { it.copy(conditions = v) } }, stringResource(R.string.offer_conditions))
            if (!vm.isCounter) MultilineInput(f.notes, { v -> vm.edit { it.copy(notes = v) } }, stringResource(R.string.notes))
        }
        Text(stringResource(if (vm.isCounter) R.string.offer_counter_note else R.string.offer_submit_note), style = MaterialTheme.typography.bodySmall)
        if (!vm.isCounter) {
            OutlinedButton(
                onClick = {
                    vm.asDraft = true
                    vm.submit()
                },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save_draft)) }
        }
    }
}
