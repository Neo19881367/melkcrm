package ir.melkcrm.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import kotlinx.coroutines.launch

/**
 * Sync state at a glance: device-only, pending changes (badge), conflicts needing a decision, or
 * synced. Tapping syncs now, or opens the account or conflict screen when that is what is needed.
 */
@Composable
fun SyncButton(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val session by app.crm.session.session.collectAsStateWithLifecycle()
    val status by app.crm.sync.status.collectAsStateWithLifecycle()
    val pendingFlow = remember { app.store.observeOutboxCount() }
    val conflictFlow = remember { app.store.observeConflicts() }
    val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
    val conflicts by conflictFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val needsAccount = !session.signedIn || session.authExpired
    IconButton(onClick = {
        when {
            needsAccount -> nav.go(Routes.settings("account"))
            conflicts.isNotEmpty() -> nav.go(Routes.settings("sync"))
            !status.running -> scope.launch {
                val r = app.syncNow()
                val err = r.error
                app.say(if (err == null) Msg.Res(R.string.sync_status_synced) else Msg.Wrapped(R.string.sync_failed, Msg.Code("srv_", err)))
            }
        }
    }) {
        BadgedBox(badge = {
            if (session.signedIn && (pending > 0 || conflicts.isNotEmpty())) Badge { Text(fmt.num(pending + conflicts.size)) }
        }) {
            Icon(
                when {
                    !session.signedIn -> Icons.Filled.CloudOff
                    session.authExpired || conflicts.isNotEmpty() || status.lastError != null -> Icons.Filled.SyncProblem
                    status.running || pending > 0 -> Icons.Filled.Sync
                    else -> Icons.Filled.CloudDone
                },
                contentDescription = stringResource(R.string.settings_sync),
            )
        }
    }
}
