package ir.melkcrm.core.export

import ir.melkcrm.core.i18n.Labels
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.CustomStatus
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.MoneyFormatter
import ir.melkcrm.core.text.PhoneNumbers
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.AppLanguage
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.DisplaySettings

/**
 * CSV exports shared by the backend (signed-in agencies) and the app (device-only mode), so both
 * produce identical columns. Callers decide which rows the user may export and whether contact
 * details are visible; confidential fields (seller minimum, confidential notes) are never exported.
 */
object Exports {
    data class CustomerRow(val customer: Customer, val contactVisible: Boolean)

    fun customers(
        rows: List<CustomerRow>, agentNames: Map<String, String>, lang: AppLanguage,
        customStatuses: List<CustomStatus> = emptyList(), display: DisplaySettings = DisplaySettings(lang),
    ): String {
        val fa = lang == AppLanguage.PERSIAN
        val header = if (fa) listOf("نام", "موبایل", "نقش", "وضعیت", "اولویت", "شهر", "محله", "منبع", "تاریخ ثبت", "آخرین تماس", "مشاور")
        else listOf("Name", "Mobile", "Roles", "Status", "Priority", "City", "Neighborhood", "Source", "Registered", "Last contact", "Agent")
        val body = rows.filter { !it.customer.deleted && it.customer.mergedIntoId == null }.map { (c, contact) ->
            listOf(
                c.fullName,
                c.mobile?.let { m -> PhoneNumbers.normalize(m)?.let { if (contact) it else PhoneNumbers.mask(it) } },
                c.roles.joinToString(" / ") { Labels.of(it, lang) },
                Labels.customerStatus(c.status, lang, customStatuses),
                Labels.of(c.priority, lang),
                c.city, c.neighborhood,
                c.source?.let { Labels.of(it, lang) },
                DateFormatter.formatDate(AgencyTime.epochDayOf(c.registeredAt, display.zone), display),
                c.lastContactAt?.let { DateFormatter.formatDate(AgencyTime.epochDayOf(it, display.zone), display) },
                c.assignedAgentId?.let { agentNames[it] },
            )
        }
        return Csv.write(header, body)
    }

    /** Amounts are written as plain numbers in [unit]; the unit is stated in the column header. */
    fun properties(
        properties: List<Property>, agentNames: Map<String, String>, lang: AppLanguage,
        unit: CurrencyUnit = CurrencyUnit.TOMAN, display: DisplaySettings = DisplaySettings(lang),
    ): String {
        val fa = lang == AppLanguage.PERSIAN
        val u = MoneyFormatter.unitLabel(unit, fa)
        val header = if (fa) listOf("کد", "عنوان", "نوع", "معامله", "وضعیت", "قیمت ($u)", "رهن ($u)", "اجاره ($u)", "متراژ", "خواب", "شهر", "محله", "مشاور", "آخرین تغییر")
        else listOf("Reference", "Title", "Type", "Deal", "Status", "Asking price ($u)", "Deposit ($u)", "Monthly rent ($u)", "Area m²", "Bedrooms", "City", "Neighborhood", "Agent", "Last updated")
        val body = properties.filter { !it.deleted }.sortedBy { it.reference }.map { p ->
            listOf(
                p.reference, p.title, Labels.of(p.category, lang),
                p.dealTypes.joinToString(" / ") { Labels.of(it, lang) },
                Labels.of(p.status, lang),
                p.askingPrice?.inUnit(unit)?.toString(),
                (p.deposit ?: p.fullDepositAmount)?.inUnit(unit)?.toString(),
                p.monthlyRent?.inUnit(unit)?.toString(),
                p.effectiveArea?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() },
                p.bedrooms?.toString(),
                p.city, p.neighborhood,
                p.assignedAgentId?.let { agentNames[it] },
                DateFormatter.formatDate(AgencyTime.epochDayOf(p.updatedAt, display.zone), display),
            )
        }
        return Csv.write(header, body)
    }
}
