package ir.melkcrm.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.settings.AppSettings
import ir.melkcrm.core.duplicates.DuplicateCandidate
import ir.melkcrm.core.duplicates.DuplicateStrength
import ir.melkcrm.core.validation.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorState<E, F>(
    val loading: Boolean = true,
    val base: E? = null,
    val form: F? = null,
    /** Field name → error code, from the form parser or the repository's validator. */
    val errors: Map<String, String> = emptyMap(),
    val notFound: Boolean = false,
    val saving: Boolean = false,
    /** Set once saved; the screen then navigates away. */
    val done: E? = null,
    val duplicates: List<DuplicateCandidate> = emptyList(),
)

/**
 * Shared editor logic: load the record (or a new one), keep the form text, convert it with the
 * client form classes and save through the repository, which validates and checks permissions.
 * Subclasses call [start] at the end of their init block.
 */
abstract class EditorViewModel<E : Any, F : Any>(protected val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(EditorState<E, F>())
    val state: StateFlow<EditorState<E, F>> = _state.asStateFlow()
    protected val crm get() = app.crm
    protected lateinit var settings: AppSettings

    protected abstract suspend fun load(): E?
    protected abstract fun formOf(e: E): F
    protected abstract fun build(form: F, base: E): FormResult<E>
    protected abstract suspend fun save(e: E, confirmed: Boolean): SaveOutcome<E>

    protected fun start() {
        viewModelScope.launch {
            settings = app.currentSettings()
            val e = load()
            _state.value = if (e == null) EditorState(loading = false, notFound = true) else EditorState(loading = false, base = e, form = formOf(e))
        }
    }

    fun edit(change: (F) -> F) = _state.update { s -> s.form?.let { f -> s.copy(form = change(f)) } ?: s }

    fun dismissDuplicates() = _state.update { it.copy(duplicates = emptyList()) }

    fun submit(confirmed: Boolean = false) {
        val s = _state.value
        val form = s.form ?: return
        val base = s.base ?: return
        if (s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, duplicates = emptyList()) }
            when (val built = build(form, base)) {
                is FormResult.Errors -> {
                    _state.update { it.copy(saving = false, errors = built.errors) }
                    app.say(Msg.Res(R.string.validation_fix))
                }
                is FormResult.Ok -> when (val out = save(built.value, confirmed)) {
                    is SaveOutcome.Saved -> {
                        if (out.warnings.isNotEmpty()) app.say(Msg.Issues(out.warnings)) else app.say(Msg.Res(R.string.saved))
                        _state.update { it.copy(saving = false, errors = emptyMap(), done = out.value) }
                    }
                    is SaveOutcome.Invalid -> {
                        val errors = out.issues.filter { it.severity == Severity.ERROR }.associate { it.field to it.code }
                        _state.update { it.copy(saving = false, errors = errors) }
                        app.say(Msg.Res(R.string.validation_fix))
                    }
                    is SaveOutcome.PossibleDuplicates -> _state.update { it.copy(saving = false, duplicates = out.candidates) }
                    is SaveOutcome.Forbidden -> {
                        _state.update { it.copy(saving = false) }
                        app.say(Msg.Code("deny_", out.reason, R.string.no_permission))
                    }
                }
            }
        }
    }
}

/** Editor frame: loading and not-found states, a save action in the top bar and at the end of the form. */
@Composable
fun <E, F> EditorScaffold(
    title: String,
    state: EditorState<E, F>,
    onBack: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.(F) -> Unit,
) {
    ScreenScaffold(
        title = title,
        onBack = onBack,
        actions = {
            if (state.form != null) IconButton(onClick = onSave, enabled = !state.saving) { Icon(Icons.Filled.Check, stringResource(R.string.save)) }
        },
    ) { padding ->
        val form = state.form
        when {
            state.loading -> Loading()
            state.notFound || form == null -> EmptyState(stringResource(R.string.record_not_found), Modifier.padding(padding))
            else -> FormColumn(padding) {
                content(form)
                Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

/** Shown when the phone numbers or the name match existing customers; the agent decides. */
@Composable
fun DuplicatesDialog(candidates: List<DuplicateCandidate>, onOpen: (String) -> Unit, onSaveAnyway: () -> Unit, onDismiss: () -> Unit) {
    val fmt = LocalFormat.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_warning, candidates.firstOrNull()?.existing?.fullName.orEmpty())) },
        text = {
            Column {
                for (c in candidates) {
                    ListItem(
                        headlineContent = { Text(c.existing.fullName) },
                        supportingContent = {
                            Text(
                                when (c.strength) {
                                    DuplicateStrength.SAME_PHONE -> stringResource(R.string.duplicate_same_phone) + " " + fmt.phone(c.matchedPhone)
                                    DuplicateStrength.SIMILAR_NAME_SAME_CITY -> stringResource(R.string.duplicate_similar_name)
                                },
                            )
                        },
                        trailingContent = { TextButton(onClick = { onOpen(c.existing.id) }) { Text(stringResource(R.string.open)) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSaveAnyway) { Text(stringResource(R.string.save_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
