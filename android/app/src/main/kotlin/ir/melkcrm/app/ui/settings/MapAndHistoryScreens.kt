package ir.melkcrm.app.ui.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.PasswordInput
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.client.settings.AppSettings
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.money.Money
import ir.melkcrm.app.ui.nav.Nav
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------- map provider

/** Returns an error resource for an invalid tile template, or null when it can be used. */
private fun tileUrlProblem(url: String): Int? {
    val u = url.trim()
    if (!(u.startsWith("https://") || u.startsWith("http://"))) return R.string.tile_url_scheme
    if (!(u.contains("{z}") && u.contains("{x}") && u.contains("{y}"))) return R.string.tile_url_placeholders
    return null
}

private fun headerProblem(h: String): Int? {
    val t = h.trim()
    if (t.isEmpty()) return null
    val i = t.indexOf(':')
    return if (i <= 0 || t.substring(i + 1).isBlank()) R.string.tile_header_format else null
}

@Composable
fun MapSettingsScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = fmt.settings)
    var url by remember(settings.tileUrl) { mutableStateOf(settings.tileUrl) }
    var header by remember(settings.tileHeader) { mutableStateOf(settings.tileHeader) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(app.neshanKey != null) }
    val urlProblem = tileUrlProblem(url)
    val headerErr = headerProblem(header)

    ScreenScaffold(title = stringResource(R.string.settings_map), onBack = { nav.back() }) { padding ->
        FormColumn(padding) {
            Text(stringResource(R.string.settings_map_note), style = MaterialTheme.typography.bodySmall)
            SectionCard(stringResource(R.string.map_tiles_section)) {
                TextInput(url, { url = it }, stringResource(R.string.settings_tile_url), keyboard = KeyboardType.Uri)
                urlProblem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextInput(header, { header = it }, stringResource(R.string.settings_tile_header))
                headerErr?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.tile_header_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    Button(
                        onClick = {
                            scope.launch {
                                app.settings.update { it.copy(tileUrl = url.trim(), tileHeader = header.trim()) }
                                app.say(Msg.Res(R.string.saved))
                            }
                        },
                        enabled = urlProblem == null && headerErr == null,
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    ) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = {
                        url = AppSettings.DEFAULT_TILE_URL
                        header = ""
                    }, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text(stringResource(R.string.tile_reset)) }
                }
            }
            SectionCard(stringResource(R.string.settings_neshan_key)) {
                Text(stringResource(if (hasKey) R.string.neshan_key_saved else R.string.neshan_key_missing), style = MaterialTheme.typography.bodyMedium)
                PasswordInput(key, { key = it }, stringResource(R.string.settings_neshan_key))
                Text(stringResource(R.string.neshan_key_note), style = MaterialTheme.typography.bodySmall)
                Row {
                    Button(
                        onClick = {
                            scope.launch {
                                app.setNeshanKey(key)
                                key = ""
                                hasKey = app.neshanKey != null
                                app.say(Msg.Res(R.string.saved))
                            }
                        },
                        enabled = key.isNotBlank(),
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    ) { Text(stringResource(R.string.save)) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                app.setNeshanKey(null)
                                hasKey = false
                            }
                        },
                        enabled = hasKey,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    ) { Text(stringResource(R.string.clear)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- history

private val moneyFields = Regex("(?i).*(price|budget|deposit|rent|amount|minimum|maximum)$")

/** Audit values are stored as text; money fields are rial integers and are shown in the chosen unit. */
private fun auditValue(field: String, value: String?, fmt: UiFormat): String {
    if (value == null) return "—"
    if (value == "***") return value
    val n = value.toLongOrNull()
    if (n != null && n >= 0 && moneyFields.matches(field)) return fmt.money(Money(n))
    return if (value.length > 120) value.take(117) + "…" else value
}

@Composable
fun HistoryScreen(entityId: String, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    var events by remember { mutableStateOf<List<AuditEvent>?>(null) }
    LaunchedEffect(entityId) { events = app.crm.history(entityId) }

    ScreenScaffold(title = stringResource(R.string.history_title), onBack = { nav.back() }) { padding ->
        val list = events
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(stringResource(R.string.history_empty), Modifier.padding(padding))
            else -> FormColumn(padding) {
                Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodySmall)
                SectionCard(null) {
                    for ((i, e) in list.withIndex()) {
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text(fmt.label(e.action), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(fmt.dateTime(e.at), agentName(e.actorUserId)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            for ((field, values) in e.changes) {
                                val old = values.getOrNull(0)
                                val new = values.getOrNull(1)
                                Text(
                                    "$field: ${auditValue(field, old, fmt)} → ${auditValue(field, new, fmt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                TextButton(onClick = { nav.back() }) { Text(stringResource(R.string.back)) }
            }
        }
    }
}
