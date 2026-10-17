package ir.melkcrm.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.settings.AppSettings
import ir.melkcrm.core.i18n.Labels
import ir.melkcrm.core.i18n.ReasonRenderer
import ir.melkcrm.core.model.CustomStatus
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.money.MoneyFormatter
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.text.PhoneNumbers
import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.validation.Severity
import ir.melkcrm.core.validation.ValidationIssue

/**
 * Display formatting for the current settings. Stored values never change with these choices:
 * money stays rial and dates stay Gregorian epoch days; only what is shown follows the user's
 * language, calendar, currency unit and digit preference.
 */
class UiFormat(val settings: AppSettings, val customStatuses: List<CustomStatus> = emptyList()) {
    val display = settings.display
    val lang: AppLanguage get() = settings.language
    val fa: Boolean get() = lang == AppLanguage.PERSIAN
    val unit get() = settings.currency
    val digits: Boolean get() = settings.persianDigits
    val reasons by lazy { ReasonRenderer(lang, unit, digits) }

    fun money(m: Money?): String = m?.let { MoneyFormatter.format(it, unit, fa, digits) } ?: ""
    fun moneyCompact(m: Money?): String = m?.let { MoneyFormatter.compact(it, unit, fa, digits) } ?: ""
    fun unitLabel(): String = MoneyFormatter.unitLabel(unit, fa)
    fun date(d: EpochDay?): String = d?.let { DateFormatter.formatDate(it, display) } ?: ""
    fun longDate(d: EpochDay?, weekday: Boolean = false): String = d?.let { DateFormatter.formatLongDate(it, display, weekday) } ?: ""
    fun dateTime(ms: Long?): String = ms?.let { DateFormatter.formatDateTime(it, display) } ?: ""
    fun time(ms: Long?): String = ms?.let { DateFormatter.formatTime(it, display) } ?: ""
    fun label(e: Enum<*>?): String = e?.let { Labels.of(it, lang) } ?: ""
    fun status(code: String): String = Labels.customerStatus(code, lang, customStatuses)
    fun num(n: Number?): String {
        if (n == null) return ""
        val s = if (n is Double && n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
        return Digits.localize(s, digits).let { if (digits) it.replace('.', '٫') else it }
    }
    fun area(a: Double?): String = a?.let { num(it) + (if (fa) " متر" else " m²") } ?: ""
    fun phone(p: String?): String = p?.let { PhoneNumbers.normalize(it)?.let { c -> PhoneNumbers.format(c, digits) } ?: Digits.localize(it, digits) } ?: ""
    fun percent(fraction: Double): String = num(Math.round(fraction * 100).toInt()) + (if (fa) "٪" else "%")
}

val LocalFormat = compositionLocalOf { UiFormat(AppSettings()) }
val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** A message for the global snackbar. */
sealed interface Msg {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : Msg
    /** A code with a resource prefix, e.g. ("deny_", "forbidden"). */
    data class Code(val prefix: String, val code: String, @StringRes val fallback: Int = R.string.err_generic) : Msg
    data class Text(val text: String) : Msg
    /** Saved, with warnings worth reading (validator codes). */
    data class Issues(val issues: List<ValidationIssue>, @StringRes val lead: Int = R.string.saved) : Msg
    /** A formatted string whose single argument is another message, e.g. "Sync failed: <reason>". */
    data class Wrapped(@StringRes val id: Int, val inner: Msg) : Msg
}

fun Context.stringByName(name: String): String? {
    val id = resources.getIdentifier(name, "string", packageName)
    return if (id != 0) getString(id) else null
}

fun Context.text(msg: Msg): String = when (msg) {
    is Msg.Res -> if (msg.args.isEmpty()) getString(msg.id) else getString(msg.id, *msg.args.toTypedArray())
    // Server and sync codes are often validation or permission codes; fall back to those texts before the generic one.
    is Msg.Code -> stringByName(msg.prefix + msg.code) ?: stringByName("deny_" + msg.code) ?: stringByName("err_" + msg.code) ?: getString(msg.fallback)
    is Msg.Text -> msg.text
    is Msg.Issues -> (listOf(getString(msg.lead)) + msg.issues.map { issueText(it) }.distinct()).joinToString(" · ")
    is Msg.Wrapped -> getString(msg.id, text(msg.inner))
}

/** Shows the outcome of a repository call in the snackbar; true when it was saved. */
fun AppContainer.report(outcome: SaveOutcome<*>, @StringRes ok: Int = R.string.saved): Boolean = when (outcome) {
    is SaveOutcome.Saved -> {
        say(if (outcome.warnings.isEmpty()) Msg.Res(ok) else Msg.Issues(outcome.warnings, ok))
        true
    }
    is SaveOutcome.Invalid -> {
        say(Msg.Issues(outcome.issues, R.string.validation_fix))
        false
    }
    is SaveOutcome.PossibleDuplicates -> {
        say(Msg.Res(R.string.duplicates_title))
        false
    }
    is SaveOutcome.Forbidden -> {
        say(Msg.Code("deny_", outcome.reason, R.string.no_permission))
        false
    }
}

/** Text for a validation issue: warn_<code>, falling back to err_<code>, then a generic message. */
fun Context.issueText(issue: ValidationIssue): String =
    if (issue.severity == Severity.WARNING) {
        stringByName("warn_" + issue.code) ?: stringByName("err_" + issue.code) ?: getString(R.string.warn_generic)
    } else {
        stringByName("err_" + issue.code) ?: getString(R.string.err_generic)
    }

fun Context.errorText(code: String?): String? = code?.let { stringByName("err_$it") ?: getString(R.string.err_generic) }

@Composable
fun errorText(code: String?): String? = LocalContext.current.errorText(code)

@Composable
fun msgText(msg: Msg): String = LocalContext.current.text(msg)
