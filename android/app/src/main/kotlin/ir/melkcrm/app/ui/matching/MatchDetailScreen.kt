package ir.melkcrm.app.ui.matching

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.ConfidentialCard
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.ScoreBadge
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.properties.priceSummary
import ir.melkcrm.app.ui.report
import ir.melkcrm.client.matching.Outreach
import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.matching.MatchRejectionReason
import ir.melkcrm.core.matching.MatchReview
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.matching.NegotiationStatus
import ir.melkcrm.core.matching.matchKey
import ir.melkcrm.core.model.ActivityOutcome
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.ContactMethod
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.permissions.Access
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.time.EpochDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MatchDetailViewModel(private val app: AppContainer, val requirementId: String, val propertyId: String) : ViewModel() {
    data class Snapshot(
        val evaluation: MatchEvaluation?,
        val requirement: BuyerRequirement?,
        val customer: Customer?,
        val property: Property?,
        /** Seller minimum and buyer ceiling: shown only with confidential access to the property. */
        val confidential: Boolean,
        val customerAccess: Access,
    )

    private val crm = app.crm
    val key = matchKey(requirementId, propertyId)
    private val _context = MutableStateFlow<Snapshot?>(null)
    val context: StateFlow<Snapshot?> = _context
    val record: StateFlow<MatchRecord?> = crm.matching.records.map { l -> l.firstOrNull { it.key == key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refresh()
        viewModelScope.launch {
            crm.writer.changes.filter { it.type != EntityType.MATCH_RECORD }.debounce(800).collect { refresh() }
        }
    }

    fun refresh() = viewModelScope.launch {
        val r = crm.customers.requirement(requirementId)
        val p = crm.properties.property(propertyId)
        val c = r?.let { crm.customers.customer(it.customerId) }
        _context.value = Snapshot(
            evaluation = crm.matching.evaluate(requirementId, propertyId),
            requirement = r, customer = c, property = p,
            confidential = p != null && crm.properties.canSeeConfidential(p),
            customerAccess = c?.let { crm.customers.access(it.id) } ?: Access.NONE,
        )
    }

    fun review(to: MatchReviewStatus, note: String? = null, reason: MatchRejectionReason? = null, until: EpochDay? = null) = viewModelScope.launch {
        app.report(crm.matching.review(key, to, note?.ifBlank { null }, reason, until))
    }

    fun feedback(buyer: String, viewing: String, followUp: EpochDay?) = viewModelScope.launch {
        app.report(crm.matching.saveFeedback(key, buyer, viewing, followUp))
    }

    fun recordContact(method: ContactMethod, outcome: ActivityOutcome?, notes: String) = viewModelScope.launch {
        app.report(crm.outreach.recordContact(key, method, outcome, notes.ifBlank { null }))
    }

    /** Opens (or continues) the deal for this buyer and property, where offers are recorded. */
    fun openDeal(onOpen: (String) -> Unit) = viewModelScope.launch {
        val r = crm.customers.requirement(requirementId) ?: return@launch
        onOpen(crm.deals.openDeal(propertyId, r.customerId, r.id, r.dealType).id)
    }
}

@Composable
fun MatchDetailScreen(requirementId: String, propertyId: String, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "match-$requirementId-$propertyId") { MatchDetailViewModel(app, requirementId, propertyId) }
    val ctx by vm.context.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.match_detail_title), onBack = { nav.back() }) { padding ->
        val c = ctx
        val e = c?.evaluation
        when {
            c == null -> Loading()
            e == null || c.property == null || c.requirement == null -> EmptyState(stringResource(R.string.record_not_found), Modifier.padding(padding))
            else -> FormColumn(padding) {
                Header(c, e, nav)
                Breakdown(e)
                if (c.confidential) Negotiation(e) else Banner(stringResource(R.string.confidential_hidden))
                Review(vm, record, c, nav)
                Feedback(vm, record)
                OutreachSection(vm, c)
            }
        }
    }
}

@Composable
private fun Header(c: MatchDetailViewModel.Snapshot, e: MatchEvaluation, nav: Nav) {
    val fmt = LocalFormat.current
    val p = c.property ?: return
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreBadge(e.score, e.classification, fmt.label(e.classification), e.provisional)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    c.customer?.fullName ?: stringResource(R.string.unknown), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { c.customer?.let { nav.go(Routes.customer(it.id)) } },
                )
                Text(
                    "${p.reference} · ${p.title}", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { nav.go(Routes.property(p.id)) },
                )
                Text(priceSummary(p, fmt), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.match_confidence, fmt.label(e.confidenceLevel)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (e.provisional) Text(stringResource(R.string.match_provisional_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        Text(stringResource(R.string.recommended_action) + ": " + fmt.label(e.recommendedAction), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.match_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Breakdown(e: MatchEvaluation) {
    val fmt = LocalFormat.current
    SectionCard(stringResource(R.string.match_breakdown)) {
        if (!e.eligible) {
            ReasonList(stringResource(R.string.match_excluded_because), e.exclusions, MaterialTheme.colorScheme.error, "✗")
        } else {
            ComponentBars(e)
        }
        ReasonList(stringResource(R.string.match_reasons), e.reasons, mark = "✓")
        ReasonList(stringResource(R.string.match_concerns), e.concerns, MaterialTheme.colorScheme.tertiary, "!")
        ReasonList(stringResource(R.string.match_info), e.info, MaterialTheme.colorScheme.onSurfaceVariant, "·")
        if (e.missing.isNotEmpty()) {
            Text(stringResource(R.string.match_missing), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for (m in e.missing) Text("? " + fmt.reasons.render(m), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Confidential: overlap of the seller's undisclosed minimum and the buyer's ceiling. Never shared with either party. */
@Composable
private fun Negotiation(e: MatchEvaluation) {
    val fmt = LocalFormat.current
    if (e.negotiation.isEmpty()) return
    ConfidentialCard(stringResource(R.string.match_negotiation)) {
        for (n in e.negotiation) {
            Text(fmt.label(n.side), style = MaterialTheme.typography.titleSmall)
            Text(
                when (n.status) {
                    NegotiationStatus.FEASIBLE -> stringResource(R.string.negotiation_feasible)
                    NegotiationStatus.NOT_FEASIBLE -> stringResource(R.string.negotiation_not_feasible)
                    NegotiationStatus.UNKNOWN -> stringResource(R.string.negotiation_unknown)
                },
            )
            val low = n.rangeLow
            val high = n.rangeHigh
            if (low != null && high != null) Text(stringResource(R.string.negotiation_range, fmt.money(Money(low)), fmt.money(Money(high))))
            n.gap?.let { Text(stringResource(R.string.negotiation_gap, fmt.money(Money(it)))) }
        }
        Text(stringResource(R.string.negotiation_disclaimer), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Review(vm: MatchDetailViewModel, record: MatchRecord?, c: MatchDetailViewModel.Snapshot, nav: Nav) {
    val fmt = LocalFormat.current
    val status = record?.reviewStatus ?: MatchReviewStatus.NEW
    var rejecting by remember { mutableStateOf(false) }
    var snoozing by remember { mutableStateOf(false) }
    fun can(to: MatchReviewStatus) = MatchReview.canTransition(status, to)
    SectionCard(stringResource(R.string.match_review_actions)) {
        Text(stringResource(R.string.status) + ": " + fmt.label(status), style = MaterialTheme.typography.bodyMedium)
        record?.rejectionReason?.takeIf { status == MatchReviewStatus.REJECTED }?.let { Text(fmt.label(it), style = MaterialTheme.typography.bodySmall) }
        record?.snoozedUntil?.takeIf { status == MatchReviewStatus.SNOOZED }?.let { Text(stringResource(R.string.review_snooze_until) + ": " + fmt.date(it), style = MaterialTheme.typography.bodySmall) }
        if (record?.changedSinceReview == true) Text(stringResource(R.string.match_updated_badge), color = MaterialTheme.colorScheme.tertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == MatchReviewStatus.CLOSED) {
                AssistChip(onClick = { vm.review(MatchReviewStatus.NEW) }, label = { Text(stringResource(R.string.review_reopen)) })
            }
            if (can(MatchReviewStatus.REVIEWED) && status == MatchReviewStatus.NEW) AssistChip(onClick = { vm.review(MatchReviewStatus.REVIEWED) }, label = { Text(stringResource(R.string.review_mark_reviewed)) })
            if (can(MatchReviewStatus.APPROVED_FOR_CONTACT)) AssistChip(onClick = { vm.review(MatchReviewStatus.APPROVED_FOR_CONTACT) }, label = { Text(stringResource(R.string.review_approve)) })
            if (can(MatchReviewStatus.VIEWING_SCHEDULED)) {
                // Scheduling the viewing appointment moves the match forward when it is saved.
                AssistChip(onClick = { nav.go(Routes.appointmentEdit(null, c.customer?.id, vm.propertyId)) }, label = { Text(stringResource(R.string.review_viewing_scheduled)) })
            }
            if (can(MatchReviewStatus.VIEWED)) AssistChip(onClick = { vm.review(MatchReviewStatus.VIEWED) }, label = { Text(stringResource(R.string.review_viewed)) })
            AssistChip(onClick = { vm.openDeal { id -> nav.go(Routes.deal(id)) } }, label = { Text(stringResource(R.string.review_offer)) })
            if (can(MatchReviewStatus.SNOOZED)) AssistChip(onClick = { snoozing = true }, label = { Text(stringResource(R.string.review_snooze)) })
            if (can(MatchReviewStatus.REJECTED)) AssistChip(onClick = { rejecting = true }, label = { Text(stringResource(R.string.review_reject)) })
            if (can(MatchReviewStatus.CLOSED)) AssistChip(onClick = { vm.review(MatchReviewStatus.CLOSED) }, label = { Text(stringResource(R.string.review_close)) })
        }
        val history = record?.history.orEmpty()
        if (history.isNotEmpty()) {
            Text(stringResource(R.string.review_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for (h in history.asReversed()) {
                Text(
                    listOfNotNull(fmt.dateTime(h.at), fmt.label(h.from) + " → " + fmt.label(h.to), agentName(h.userId), h.note).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (rejecting) RejectDialog(onDismiss = { rejecting = false }) { reason, note -> rejecting = false; vm.review(MatchReviewStatus.REJECTED, note, reason) }
    if (snoozing) SnoozeDialog(onDismiss = { snoozing = false }) { until, note -> snoozing = false; vm.review(MatchReviewStatus.SNOOZED, note, until = until) }
}

@Composable
private fun RejectDialog(onDismiss: () -> Unit, onConfirm: (MatchRejectionReason, String) -> Unit) {
    val fmt = LocalFormat.current
    var reason by remember { mutableStateOf<MatchRejectionReason?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_rejection_reason)) },
        text = {
            Column {
                for (r in MatchRejectionReason.entries) {
                    Row(Modifier.fillMaxWidth().clickable { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(fmt.label(r))
                    }
                }
                TextInput(note, { note = it }, stringResource(R.string.notes))
            }
        },
        confirmButton = { TextButton(onClick = { reason?.let { onConfirm(it, note) } }, enabled = reason != null) { Text(stringResource(R.string.review_reject)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SnoozeDialog(onDismiss: () -> Unit, onConfirm: (EpochDay, String) -> Unit) {
    val app = LocalApp.current
    var until by remember { mutableStateOf<EpochDay?>(app.crm.writer.today() + 7) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_snooze)) },
        text = {
            Column {
                DateInput(until, { until = it }, stringResource(R.string.review_snooze_until))
                TextInput(note, { note = it }, stringResource(R.string.notes))
            }
        },
        confirmButton = {
            val u = until
            TextButton(onClick = { if (u != null) onConfirm(u, note) }, enabled = u != null && u > app.crm.writer.today()) { Text(stringResource(R.string.review_snooze)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Feedback(vm: MatchDetailViewModel, record: MatchRecord?) {
    var buyer by remember(record?.buyerFeedback) { mutableStateOf(record?.buyerFeedback.orEmpty()) }
    var viewing by remember(record?.viewingOutcome) { mutableStateOf(record?.viewingOutcome.orEmpty()) }
    var follow by remember(record?.followUpDate) { mutableStateOf(record?.followUpDate) }
    SectionCard(stringResource(R.string.review_feedback)) {
        MultilineInput(buyer, { buyer = it }, stringResource(R.string.review_feedback))
        TextInput(viewing, { viewing = it }, stringResource(R.string.review_viewing_outcome))
        DateInput(follow, { follow = it }, stringResource(R.string.review_followup))
        OutlinedButton(onClick = { vm.feedback(buyer, viewing, follow) }) { Text(stringResource(R.string.save)) }
    }
}

/**
 * Controlled outreach: the app prepares a text with public listing facts only (no seller minimum,
 * no exact address, no other party's figures). The agent sends it themselves and then logs the contact.
 * Channels the customer has not consented to are not offered.
 */
@Composable
private fun OutreachSection(vm: MatchDetailViewModel, c: MatchDetailViewModel.Snapshot) {
    val fmt = LocalFormat.current
    val context = LocalContext.current
    val app = LocalApp.current
    val customer = c.customer ?: return
    val p = c.property ?: return
    val allowed = ContactMethod.entries.filter { Outreach.allowed(customer, it) }
    var method by remember { mutableStateOf(allowed.firstOrNull()) }
    var outcome by remember { mutableStateOf<ActivityOutcome?>(null) }
    var notes by remember { mutableStateOf("") }
    val summary = remember(p, fmt) { Outreach.propertySummary(p, fmt.lang, fmt.unit, fmt.digits) }
    SectionCard(stringResource(R.string.outreach_title)) {
        Text(stringResource(R.string.outreach_note), style = MaterialTheme.typography.bodySmall)
        if (c.customerAccess < Access.READ) {
            Banner(stringResource(R.string.contact_hidden))
            return@SectionCard
        }
        if (allowed.isEmpty()) {
            Banner(stringResource(R.string.outreach_consent_blocked), color = MaterialTheme.colorScheme.errorContainer)
            return@SectionCard
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(summary, modifier = Modifier.padding(12.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copy(context, summary); app.say(Msg.Res(R.string.copied)) }) { Text(stringResource(R.string.copy)) }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }) { Text(stringResource(R.string.share)) }
        }
        Text(stringResource(R.string.outreach_log_contact), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Dropdown(stringResource(R.string.activity_channel), allowed, method, { method = it }, { fmt.label(it) }, allowNone = false)
        EnumDropdown(stringResource(R.string.activity_outcome), ActivityOutcome.entries, outcome, { outcome = it })
        TextInput(notes, { notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
        Button(onClick = {
            method?.let { m ->
                vm.recordContact(m, outcome, notes)
                notes = ""
                outcome = null
            }
        }, enabled = method != null) { Text(stringResource(R.string.outreach_log_contact)) }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("listing", text))
}
