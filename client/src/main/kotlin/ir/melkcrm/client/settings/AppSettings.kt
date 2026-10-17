package ir.melkcrm.client.settings

import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.DisplaySettings
import kotlinx.coroutines.flow.Flow

/**
 * Per-device preferences. Display choices never change stored values: amounts stay in rial and
 * dates stay Gregorian epoch days whatever the user picks here.
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.PERSIAN,
    val calendar: CalendarSystem = CalendarSystem.JALALI,
    val currency: CurrencyUnit = CurrencyUnit.TOMAN,
    val persianDigits: Boolean = true,
    /** XYZ tile template with {z}/{x}/{y}. */
    val tileUrl: String = DEFAULT_TILE_URL,
    /** Optional "Header-Name: value" sent with tile requests (commercial tile providers). */
    val tileHeader: String = "",
    val lastServerUrl: String = "",
    val notificationsEnabled: Boolean = true,
) {
    val display: DisplaySettings get() = DisplaySettings(language, calendar, persianDigits)

    companion object {
        const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    }
}

interface SettingsSource {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Secrets typed in by the agency (map provider key). Stored encrypted by the platform. */
interface SecretStore {
    suspend fun get(name: String): String?
    suspend fun put(name: String, value: String?)
}
