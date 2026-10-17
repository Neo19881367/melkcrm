package ir.melkcrm.app

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import ir.melkcrm.app.data.DataStoreSettings
import ir.melkcrm.app.data.KeystoreTokenStorage
import ir.melkcrm.app.data.LocaleStore
import ir.melkcrm.app.data.PhotoFiles
import ir.melkcrm.app.data.RoomLocalStore
import ir.melkcrm.app.data.SecurePrefs
import ir.melkcrm.app.data.db.CrmDatabase
import ir.melkcrm.app.work.Reminders
import ir.melkcrm.app.work.SyncScheduler
import ir.melkcrm.client.Crm
import ir.melkcrm.client.geo.Geocoder
import ir.melkcrm.client.geo.NeshanGeocoder
import ir.melkcrm.client.remote.KtorRemoteApi
import ir.melkcrm.client.settings.AppSettings
import ir.melkcrm.client.sync.SyncReport
import ir.melkcrm.core.sync.EntityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ir.melkcrm.app.ui.Msg
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The app's object graph, created once in [MelkApp]. Everything platform-independent lives in
 * [Crm] (module :client, tested on the JVM); this class supplies the Android parts: Room storage,
 * Keystore-encrypted secrets, DataStore settings, private photo files and the HTTP client.
 *
 * No server address, credential or API key is compiled in. The agency enters its own server URL
 * and map key in Settings; development defaults come only from android/local.properties.
 */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db = CrmDatabase.open(this.context)
    val store = RoomLocalStore(db)
    val secrets = SecurePrefs(this.context)
    val tokens = KeystoreTokenStorage(secrets)
    val photos = PhotoFiles(this.context)
    val settings = DataStoreSettings(
        this.context,
        AppSettings(
            language = LocaleStore.load(this.context),
            tileUrl = BuildConfig.DEFAULT_TILE_URL.ifBlank { AppSettings.DEFAULT_TILE_URL },
            lastServerUrl = BuildConfig.DEFAULT_SERVER_URL,
        ),
    )

    // Platform HttpURLConnection engine: no OkHttp, whose current releases need a newer compileSdk.
    val http = HttpClient(Android) {
        expectSuccess = false
        engine {
            connectTimeout = 20_000
            socketTimeout = 60_000
        }
    }

    val crm = Crm(
        store = store,
        tokens = tokens,
        apiFactory = { url -> KtorRemoteApi(url, http, tokens) },
        images = photos,
        localUserName = this.context.getString(R.string.local_user_name),
    )

    /** Messages for the app-wide snackbar; they outlive the screen that produced them. */
    val messages = MutableSharedFlow<Msg>(extraBufferCapacity = 16)

    fun say(msg: Msg) {
        messages.tryEmit(msg)
    }

    /** The agency's Neshan service key, entered in Settings and kept encrypted. Read synchronously by the geocoder. */
    @Volatile var neshanKey: String? = null
        private set

    val geocoder: Geocoder = NeshanGeocoder(http, apiKey = { neshanKey })

    suspend fun setNeshanKey(key: String?) {
        val k = key?.trim()?.ifEmpty { null }
        secrets.put(KEY_NESHAN, k)
        neshanKey = k
    }

    fun start() {
        crm.start(scope)
        scope.launch {
            neshanKey = secrets.get(KEY_NESHAN)
            SyncScheduler.schedulePeriodic(context)
            Reminders.schedule(context)
        }
        // Local edits are pushed shortly after they are made when signed in.
        scope.launch {
            crm.writer.changes.collect { if (crm.session.session.value.signedIn) SyncScheduler.requestSoon(context) }
        }
        // The next reminder is re-planned whenever a task or appointment changes or arrives by sync.
        scope.launch {
            crm.writer.changes.collect { c ->
                if (c.type == EntityType.TASK || c.type == EntityType.APPOINTMENT) Reminders.planNext(context, this@AppContainer)
            }
        }
        scope.launch {
            settings.settings.drop(1).collect { Reminders.planNext(context, this@AppContainer) }
        }
    }

    suspend fun syncNow(): SyncReport = crm.syncNow().also { r ->
        if (EntityType.TASK in r.changedTypes || EntityType.APPOINTMENT in r.changedTypes) Reminders.planNext(context, this)
    }

    /** Signs out, wiping records, tokens, photos and the map key from the device. */
    suspend fun signOut() {
        crm.signOut()
        photos.wipe()
        setNeshanKey(null)
        Reminders.cancelAll(context)
    }

    suspend fun currentSettings(): AppSettings = settings.settings.first()

    private companion object {
        const val KEY_NESHAN = "neshan.key"
    }
}
