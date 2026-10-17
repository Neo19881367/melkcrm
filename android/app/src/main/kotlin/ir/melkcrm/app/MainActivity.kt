package ir.melkcrm.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import ir.melkcrm.app.data.LocaleStore
import ir.melkcrm.app.ui.AppRoot
import ir.melkcrm.app.work.Notifications

/**
 * Single activity. The app language (Persian or English) is applied in [attachBaseContext], which
 * also sets the layout direction, so Persian screens are right-to-left from the first frame.
 * Changing the language in Settings recreates the activity.
 */
class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute.value = intent?.getStringExtra(Notifications.EXTRA_ROUTE)
        setContent {
            AppRoot(
                app = container,
                pendingRoute = pendingRoute.value,
                onRouteConsumed = { pendingRoute.value = null },
                onLanguageChanged = { recreate() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }
}
