package ir.melkcrm.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.ConfirmDialog
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.LabeledValue
import ir.melkcrm.app.ui.components.PasswordInput
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.msgText
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.client.session.SignInResult
import ir.melkcrm.client.store.ConflictEntry
import ir.melkcrm.client.store.OutboxEntry
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.validation.PasswordPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------------------- account

/**
 * Sign-in against the agency's server. The password is held only in this screen's memory for the
 * request; the server returns tokens, which the app stores encrypted with the Android Keystore.
 */
class AccountViewModel(private val app: AppContainer) : ViewModel() {
    data class Form(val server: String = "", val login: String = "", val password: String = "", val busy: Boolean = false, val error: Msg? = null)

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form

    init {
        viewModelScope.launch {
            val s = app.currentSettings()
            val session = app.crm.session.session.value
            _form.update { it.copy(server = session.serverUrl ?: s.lastServerUrl) }
        }
    }

    fun edit(change: (Form) -> Form) = _form.update(change)

    fun signIn(onDone: () -> Unit) {
        val f = _form.value
        if (f.busy) return
        viewModelScope.launch {
            _form.update { it.copy(busy = true, error = null) }
            when (val r = app.crm.session.signIn(f.server, f.login, f.password)) {
                is SignInResult.Success -> {
                    app.settings.update { it.copy(lastServerUrl = f.server.trim().trimEnd('/')) }
                    _form.update { it.copy(password = "", busy = false) }
                    app.say(if (r.adoptedRecords > 0) Msg.Res(R.string.signed_in_adopted, listOf(r.adoptedRecords)) else Msg.Res(R.string.signed_in))
                    val report = app.syncNow()
                    report.error?.let { app.say(Msg.Wrapped(R.string.sync_failed, Msg.Code("srv_", it))) }
                    onDone()
                }
                is SignInResult.Failure -> _form.update {
                    it.copy(password = "", busy = false, error = Msg.Wrapped(R.string.sign_in_failed, Msg.Code("srv_", r.code)))
                }
            }
        }
    }

    fun signOut(onDone: () -> Unit) = viewModelScope.launch {
        app.signOut()
        app.say(Msg.Res(R.string.signed_out))
        onDone()
    }

    data class PasswordForm(val current: String = "", val new: String = "", val repeat: String = "", val busy: Boolean = false, val error: Msg? = null)

    private val _password = MutableStateFlow(PasswordForm())
    val password: StateFlow<PasswordForm> = _password

    fun editPassword(change: (PasswordForm) -> PasswordForm) = _password.update(change)

    /** Checks the rule locally first; the server applies the same rule and verifies the current password. */
    fun changePassword() {
        val f = _password.value
        if (f.busy) return
        val problem = if (f.new != f.repeat) Msg.Res(R.string.password_mismatch) else PasswordPolicy.problem(f.new)?.let { Msg.Code("srv_", it) }
        if (problem != null) {
            _password.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            _password.update { it.copy(busy = true, error = null) }
            try {
                app.crm.session.changePassword(f.current, f.new)
                _password.value = PasswordForm()
                app.say(Msg.Res(R.string.password_changed))
            } catch (e: RemoteException) {
                _password.update { it.copy(current = "", busy = false, error = Msg.Code("srv_", if (e.isNetwork) "network" else e.code)) }
            }
        }
    }
}

private fun isInsecureRemote(url: String): Boolean {
    val u = url.trim().lowercase()
    if (!u.startsWith("http://")) return false
    val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
    return host != "10.0.2.2" && host != "localhost" && host != "127.0.0.1"
}

@Composable
fun AccountScreen(nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel { AccountViewModel(app) }
    val form by vm.form.collectAsStateWithLifecycle()
    val session by app.crm.session.session.collectAsStateWithLifecycle()
    val pendingFlow = remember { app.store.observeOutboxCount() }
    val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
    var confirmSignOut by remember { mutableStateOf(false) }
    val fmt = LocalFormat.current

    ScreenScaffold(title = stringResource(R.string.settings_account), onBack = { nav.back() }) { padding ->
        FormColumn(padding) {
            if (session.signedIn) {
                SectionCard(null) {
                    Text(stringResource(R.string.settings_signed_in_as, session.user.displayName, fmt.label(session.user.role)), style = MaterialTheme.typography.titleMedium)
                    LabeledValue(stringResource(R.string.settings_server_url), session.serverUrl)
                    if (session.authExpired) Banner(stringResource(R.string.session_expired_banner), color = MaterialTheme.colorScheme.errorContainer)
                }
            } else {
                Banner(stringResource(R.string.settings_local_mode))
            }

            // Shown to sign in, or to sign in again after the server ended the session.
            if (!session.signedIn || session.authExpired) {
                SectionCard(stringResource(R.string.settings_sign_in)) {
                    TextInput(
                        form.server, { v -> vm.edit { it.copy(server = v) } }, stringResource(R.string.settings_server_url),
                        keyboard = KeyboardType.Uri, supporting = stringResource(R.string.settings_server_hint), enabled = !session.signedIn,
                    )
                    if (isInsecureRemote(form.server)) Banner(stringResource(R.string.server_http_warning), color = MaterialTheme.colorScheme.errorContainer)
                    TextInput(form.login, { v -> vm.edit { it.copy(login = v) } }, stringResource(R.string.settings_login))
                    PasswordInput(form.password, { v -> vm.edit { it.copy(password = v) } }, stringResource(R.string.settings_password))
                    form.error?.let { Text(msgText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (!session.signedIn) Text(stringResource(R.string.sign_in_adopt_note), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { vm.signIn { nav.back() } },
                        enabled = !form.busy && form.server.isNotBlank() && form.login.isNotBlank() && form.password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_sign_in)) }
                }
            }

            if (session.signedIn && !session.authExpired) {
                val pw by vm.password.collectAsStateWithLifecycle()
                SectionCard(stringResource(R.string.password_change)) {
                    PasswordInput(pw.current, { v -> vm.editPassword { it.copy(current = v) } }, stringResource(R.string.password_current))
                    PasswordInput(
                        pw.new, { v -> vm.editPassword { it.copy(new = v) } }, stringResource(R.string.password_new),
                        supporting = stringResource(R.string.password_rule, PasswordPolicy.MIN_LENGTH),
                    )
                    PasswordInput(pw.repeat, { v -> vm.editPassword { it.copy(repeat = v) } }, stringResource(R.string.password_repeat))
                    pw.error?.let { Text(msgText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.password_change_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { vm.changePassword() },
                        enabled = !pw.busy && pw.current.isNotEmpty() && pw.new.isNotEmpty() && pw.repeat.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.password_change)) }
                }
            }

            if (session.signedIn) {
                SectionCard(stringResource(R.string.settings_sign_out)) {
                    Text(stringResource(R.string.sign_out_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_sign_out)) }
                }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sign_out),
            text = if (pending > 0) stringResource(R.string.sign_out_pending_warning, pending) else stringResource(R.string.sign_out_note),
            confirm = stringResource(R.string.settings_sign_out),
            onConfirm = {
                confirmSignOut = false
                vm.signOut { nav.top(Routes.DASHBOARD) }
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

// ---------------------------------------------------------------------------------------- sync

class SyncViewModel(private val app: AppContainer) : ViewModel() {
    val status = app.crm.sync.status
    val conflicts = app.store.observeConflicts()
    val pending = app.store.observeOutboxCount()
    private val _rejected = MutableStateFlow<List<OutboxEntry>>(emptyList())
    val rejected: StateFlow<List<OutboxEntry>> = _rejected

    init {
        viewModelScope.launch { pending.collect { _rejected.value = app.store.outbox().filter { it.rejected } } }
    }

    fun syncNow() = viewModelScope.launch {
        val r = app.syncNow()
        val err = r.error
        app.say(if (err == null) Msg.Res(R.string.sync_status_synced) else Msg.Wrapped(R.string.sync_failed, Msg.Code("srv_", err)))
    }

    fun resolve(c: ConflictEntry, keepLocal: Boolean) = viewModelScope.launch {
        app.crm.sync.resolveConflict(c.type, c.id, keepLocal)
        app.say(Msg.Res(R.string.conflict_resolved))
        // Keeping the local copy queues it again on top of the server's version.
        if (keepLocal && app.crm.session.session.value.signedIn) app.syncNow()
    }
}

/** Fields whose values differ between the local copy and the server's copy, with both values. */
private fun conflictDiff(c: ConflictEntry): List<Triple<String, String, String>> {
    val local = runCatching { Json.parseToJsonElement(c.localJson).jsonObject }.getOrNull() ?: return emptyList()
    val server = c.server ?: return emptyList()
    val remote = JsonObject(server.data + (server.confidential ?: JsonObject(emptyMap())))
    val ignore = setOf("version", "updatedAt", "createdAt")
    return (local.keys + remote.keys).filter { it !in ignore }.distinct().mapNotNull { k ->
        val a = local[k]
        val b = remote[k]
        if (a == b) null else Triple(k, short(a), short(b))
    }
}

private fun short(e: kotlinx.serialization.json.JsonElement?): String {
    val s = when (e) {
        null -> "—"
        is JsonPrimitive -> e.content
        else -> e.toString()
    }
    return if (s.length > 80) s.take(77) + "…" else s
}

private fun conflictTitle(c: ConflictEntry): String {
    val local = runCatching { Json.parseToJsonElement(c.localJson).jsonObject }.getOrNull()
    for (k in listOf("fullName", "reference", "title", "subject")) {
        (local?.get(k) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return c.id.take(8)
}

/** Detail route for a record type, where the agent can review or edit it. */
private fun routeOf(type: EntityType, id: String): String? = when (type) {
    EntityType.CUSTOMER -> Routes.customer(id)
    EntityType.PROPERTY -> Routes.property(id)
    EntityType.DEAL -> Routes.deal(id)
    EntityType.TASK -> Routes.taskEdit(id)
    EntityType.APPOINTMENT -> Routes.appointmentEdit(id)
    EntityType.ACTIVITY -> Routes.activityEdit(id)
    else -> null
}

@Composable
fun SyncScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel { SyncViewModel(app) }
    val status by vm.status.collectAsStateWithLifecycle()
    val conflicts by vm.conflicts.collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by vm.pending.collectAsStateWithLifecycle(initialValue = 0)
    val rejected by vm.rejected.collectAsStateWithLifecycle()
    val session by app.crm.session.session.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.settings_sync), onBack = { nav.back() }) { padding ->
        FormColumn(padding) {
            SectionCard(null) {
                if (!session.signedIn) {
                    Text(stringResource(R.string.settings_local_mode), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { nav.go(Routes.settings("account")) }) { Text(stringResource(R.string.settings_sign_in)) }
                } else {
                    Text(
                        if (pending == 0) stringResource(R.string.sync_status_synced) else stringResource(R.string.sync_status_pending, pending),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        status.lastSyncAt?.let { stringResource(R.string.sync_last, fmt.dateTime(it)) } ?: stringResource(R.string.sync_never),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    status.lastError?.let { Text(msgText(Msg.Wrapped(R.string.sync_failed, Msg.Code("srv_", it))), color = MaterialTheme.colorScheme.error) }
                    if (session.authExpired) {
                        Banner(stringResource(R.string.session_expired_banner), color = MaterialTheme.colorScheme.errorContainer)
                        OutlinedButton(onClick = { nav.go(Routes.settings("account")) }) { Text(stringResource(R.string.settings_sign_in)) }
                    }
                    Button(onClick = { vm.syncNow() }, enabled = !status.running && !session.authExpired, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (status.running) R.string.syncing else R.string.sync_now))
                    }
                }
                Text(stringResource(R.string.sync_offline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (conflicts.isNotEmpty()) {
                SectionCard(stringResource(R.string.sync_conflicts)) {
                    Text(stringResource(R.string.conflict_explain), style = MaterialTheme.typography.bodySmall)
                    for ((i, c) in conflicts.withIndex()) {
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ConflictItem(c, fmt, vm, nav)
                    }
                }
            }

            if (rejected.isNotEmpty()) {
                SectionCard(stringResource(R.string.sync_rejected)) {
                    Text(stringResource(R.string.sync_rejected_note), style = MaterialTheme.typography.bodySmall)
                    for (r in rejected) {
                        val route = routeOf(r.type, r.id)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(fmt.label(r.type) + " · " + r.id.take(8), style = MaterialTheme.typography.bodyMedium)
                                Text(msgText(Msg.Code("srv_", r.lastError ?: "rejected")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (route != null) TextButton(onClick = { nav.go(route) }) { Text(stringResource(R.string.open)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictItem(c: ConflictEntry, fmt: UiFormat, vm: SyncViewModel, nav: Nav) {
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    val diff = remember(c) { conflictDiff(c) }
    Column(Modifier.fillMaxWidth()) {
        Text(fmt.label(c.type) + " · " + conflictTitle(c), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.conflict_detected, fmt.dateTime(c.detectedAt)), style = MaterialTheme.typography.bodySmall)
        if (c.server == null) Text(stringResource(R.string.conflict_server_missing), style = MaterialTheme.typography.bodySmall)
        for ((field, mine, theirs) in diff.take(12)) {
            Text(field, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.conflict_mine, mine), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.conflict_theirs, theirs), style = MaterialTheme.typography.bodySmall)
        }
        if (diff.size > 12) Text("…", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = false }) { Text(stringResource(R.string.conflict_keep_server)) }
            OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.conflict_keep_local)) }
        }
        routeOf(c.type, c.id)?.let { r -> TextButton(onClick = { nav.go(r) }) { Text(stringResource(R.string.open)) } }
    }
    val keepLocal = confirm
    if (keepLocal != null) {
        ConfirmDialog(
            title = stringResource(if (keepLocal) R.string.conflict_keep_local else R.string.conflict_keep_server),
            text = stringResource(if (keepLocal) R.string.conflict_keep_local_note else R.string.conflict_keep_server_note),
            confirm = stringResource(R.string.confirm),
            onConfirm = {
                confirm = null
                vm.resolve(c, keepLocal)
            },
            onDismiss = { confirm = null },
        )
    }
}
