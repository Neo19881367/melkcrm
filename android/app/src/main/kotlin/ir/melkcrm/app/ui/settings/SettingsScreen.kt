package ir.melkcrm.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.components.ConfirmDialog
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SwitchRow
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.work.Notifications
import ir.melkcrm.client.sample.SampleLoader
import ir.melkcrm.client.settings.AppSettings
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.time.CalendarSystem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = fmt.settings)
    val session by app.crm.session.session.collectAsStateWithLifecycle()
    var confirmSamples by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(Notifications.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> canNotify = granted }

    fun update(change: (AppSettings) -> AppSettings) {
        scope.launch { app.settings.update(change) }
    }

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = null, onMenu = nav.openDrawer) { padding ->
        FormColumn(padding) {
            SectionCard(stringResource(R.string.settings_display)) {
                Dropdown(
                    stringResource(R.string.settings_language), AppLanguage.entries, settings.language,
                    { v -> if (v != null) update { it.copy(language = v) } },
                    { stringResource(if (it == AppLanguage.PERSIAN) R.string.lang_fa else R.string.lang_en) }, allowNone = false,
                )
                Dropdown(
                    stringResource(R.string.settings_calendar), CalendarSystem.entries, settings.calendar,
                    { v -> if (v != null) update { it.copy(calendar = v) } },
                    { stringResource(if (it == CalendarSystem.JALALI) R.string.calendar_jalali else R.string.calendar_gregorian) }, allowNone = false,
                )
                Dropdown(
                    stringResource(R.string.settings_currency), CurrencyUnit.entries, settings.currency,
                    { v -> if (v != null) update { it.copy(currency = v) } },
                    { stringResource(if (it == CurrencyUnit.TOMAN) R.string.currency_toman else R.string.currency_rial) }, allowNone = false,
                )
                Text(stringResource(R.string.currency_note), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.settings_persian_digits), settings.persianDigits, { v -> update { it.copy(persianDigits = v) } })
                Text(stringResource(R.string.settings_storage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(stringResource(R.string.settings_reminders)) {
                SwitchRow(
                    stringResource(R.string.settings_reminders_enabled), settings.notificationsEnabled,
                    { v -> update { it.copy(notificationsEnabled = v) } },
                    supporting = stringResource(R.string.task_reminder_note),
                )
                if (settings.notificationsEnabled && !canNotify && Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.notifications_permission)) }
                }
            }

            SectionCard(null) {
                Link(
                    stringResource(R.string.settings_account),
                    if (session.signedIn) stringResource(R.string.settings_signed_in_as, session.user.displayName, fmt.label(session.user.role))
                    else stringResource(R.string.settings_local_mode_short),
                ) { nav.go(Routes.settings("account")) }
                Link(stringResource(R.string.settings_sync), null) { nav.go(Routes.settings("sync")) }
                Link(stringResource(R.string.settings_matching), null) { nav.go(Routes.settings("matching")) }
                Link(stringResource(R.string.commission_rules_title), null) { nav.go(Routes.settings("commission")) }
                Link(stringResource(R.string.settings_map), null) { nav.go(Routes.settings("map")) }
                Link(stringResource(R.string.settings_users), null) { nav.go(Routes.settings("users")) }
                Link(stringResource(R.string.duplicates_title), null) { nav.go(Routes.DUPLICATES) }
            }

            if (!session.signedIn) {
                SectionCard(stringResource(R.string.settings_data)) {
                    Text(stringResource(R.string.sample_data_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmSamples = true }) { Text(stringResource(R.string.load_sample_data)) }
                }
            }

            SectionCard(stringResource(R.string.settings_about)) {
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                }
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sync_offline_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmSamples) {
        ConfirmDialog(
            title = stringResource(R.string.load_sample_data),
            text = stringResource(R.string.sample_data_confirm),
            confirm = stringResource(R.string.add),
            onConfirm = {
                confirmSamples = false
                scope.launch {
                    when (val r = app.crm.samples.load()) {
                        is SampleLoader.Result.Loaded -> app.say(Msg.Res(R.string.sample_data_loaded_counts, listOf(r.customers, r.properties, r.requirements)))
                        SampleLoader.Result.NotAllowedWhileSignedIn -> app.say(Msg.Res(R.string.sample_data_signed_in))
                    }
                }
            },
            onDismiss = { confirmSamples = false },
        )
    }
}

@Composable
private fun Link(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else {
            null
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}
