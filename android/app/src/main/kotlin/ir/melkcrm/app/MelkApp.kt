package ir.melkcrm.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import ir.melkcrm.app.work.Notifications

class MelkApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.start()
    }

    // WorkManager is initialized on demand with this configuration (the default initializer is
    // removed in the manifest), so workers can reach the container.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()
}

val android.content.Context.container: AppContainer get() = (applicationContext as MelkApp).container
