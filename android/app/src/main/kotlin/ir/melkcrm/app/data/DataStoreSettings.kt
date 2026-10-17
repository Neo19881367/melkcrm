package ir.melkcrm.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ir.melkcrm.client.settings.AppSettings
import ir.melkcrm.client.settings.SettingsSource
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.time.CalendarSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Device preferences in DataStore. The language is also mirrored into [LocaleStore], which the
 * activity reads synchronously before its first frame so the right layout direction applies at once.
 */
class DataStoreSettings(private val context: Context, private val defaults: AppSettings) : SettingsSource {
    private object K {
        val language = stringPreferencesKey("language")
        val calendar = stringPreferencesKey("calendar")
        val currency = stringPreferencesKey("currency")
        val persianDigits = booleanPreferencesKey("persianDigits")
        val tileUrl = stringPreferencesKey("tileUrl")
        val tileHeader = stringPreferencesKey("tileHeader")
        val lastServerUrl = stringPreferencesKey("lastServerUrl")
        val notifications = booleanPreferencesKey("notifications")
    }

    private fun read(p: Preferences) = AppSettings(
        language = p[K.language]?.let { AppLanguage.fromCode(it) } ?: defaults.language,
        calendar = p[K.calendar]?.let { v -> CalendarSystem.entries.firstOrNull { it.name == v } } ?: defaults.calendar,
        currency = p[K.currency]?.let { v -> CurrencyUnit.entries.firstOrNull { it.name == v } } ?: defaults.currency,
        persianDigits = p[K.persianDigits] ?: defaults.persianDigits,
        tileUrl = p[K.tileUrl] ?: defaults.tileUrl,
        tileHeader = p[K.tileHeader] ?: defaults.tileHeader,
        lastServerUrl = p[K.lastServerUrl] ?: defaults.lastServerUrl,
        notificationsEnabled = p[K.notifications] ?: defaults.notificationsEnabled,
    )

    override val settings: Flow<AppSettings> = context.settingsStore.data.map { read(it) }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        var language: AppLanguage? = null
        context.settingsStore.edit { p ->
            val next = transform(read(p))
            p[K.language] = next.language.code
            p[K.calendar] = next.calendar.name
            p[K.currency] = next.currency.name
            p[K.persianDigits] = next.persianDigits
            p[K.tileUrl] = next.tileUrl
            p[K.tileHeader] = next.tileHeader
            p[K.lastServerUrl] = next.lastServerUrl
            p[K.notifications] = next.notificationsEnabled
            language = next.language
        }
        language?.let { LocaleStore.save(context, it) }
    }
}

/** Synchronous copy of the chosen language, read in Activity.attachBaseContext. */
object LocaleStore {
    private const val FILE = "locale"
    private const val KEY = "language"

    fun load(context: Context): AppLanguage =
        AppLanguage.fromCode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, language.code).commit()
    }

    /** [context] with the app's chosen language (and its layout direction) instead of the system one. */
    fun wrap(context: Context): Context {
        val locale = java.util.Locale.forLanguageTag(load(context).code)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
