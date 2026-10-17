package ir.melkcrm.core.validation

import ir.melkcrm.core.geo.Geo
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Offer
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.PropertyCategory
import ir.melkcrm.core.model.PropertyStatus
import ir.melkcrm.core.model.SellerProfile
import ir.melkcrm.core.text.PhoneNumbers
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.JalaliDate
import java.time.LocalDate

enum class Severity { ERROR, WARNING }

/** [code] is a stable key the UI maps to a localized message; [field] names the form field. */
data class ValidationIssue(val field: String, val code: String, val severity: Severity = Severity.ERROR)

data class ValidationResult(val issues: List<ValidationIssue>) {
    val errors get() = issues.filter { it.severity == Severity.ERROR }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
    val isValid get() = errors.isEmpty()
    fun forField(field: String) = issues.filter { it.field == field }
}

private class Collector {
    val issues = mutableListOf<ValidationIssue>()
    fun error(field: String, code: String) { issues += ValidationIssue(field, code) }
    fun warn(field: String, code: String) { issues += ValidationIssue(field, code, Severity.WARNING) }
    fun <T : Comparable<T>> range(minField: String, min: T?, max: T?) {
        if (min != null && max != null && min > max) error(minField, "min_greater_than_max")
    }
    fun result() = ValidationResult(issues.toList())
}

private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
private val referenceRegex = Regex("^IR-\\d{6,}$")

object CustomerValidator {
    /** Drafts only need a name or a phone number; full records need name, a valid phone and a role. */
    fun validate(c: Customer): ValidationResult = Collector().apply {
        if (c.fullName.isBlank()) {
            if (!c.isDraft || c.mobile.isNullOrBlank()) error("fullName", "required")
        } else if (c.fullName.trim().length < 2) error("fullName", "too_short")

        if (c.mobile.isNullOrBlank() && c.altPhone.isNullOrBlank()) {
            if (!c.isDraft) error("mobile", "phone_required")
        }
        if (!c.mobile.isNullOrBlank()) {
            val n = PhoneNumbers.normalize(c.mobile)
            if (n == null) error("mobile", "invalid_phone") else if (!PhoneNumbers.isMobile(n)) warn("mobile", "not_a_mobile_number")
        }
        if (!c.altPhone.isNullOrBlank() && PhoneNumbers.normalize(c.altPhone) == null) error("altPhone", "invalid_phone")
        if (!c.email.isNullOrBlank() && !emailRegex.matches(c.email.trim())) error("email", "invalid_email")
        if (c.roles.isEmpty() && !c.isDraft) error("roles", "role_required")
        if (c.consent.marketingAllowed && c.consent.recordedAt == null) warn("consent", "consent_date_missing")
    }.result()
}

object BuyerRequirementValidator {
    fun validate(r: BuyerRequirement): ValidationResult = Collector().apply {
        range("minArea", r.minArea, r.maxArea)
        range("minBedrooms", r.minBedrooms, r.maxBedrooms)
        range("minFloor", r.minFloor, r.maxFloor)
        if ((r.minArea ?: 0.0) < 0 || (r.maxArea ?: 0.0) < 0) error("minArea", "negative")
        if ((r.minBedrooms ?: 0) < 0) error("minBedrooms", "negative")
        when (r.dealType) {
            DealType.SALE -> {
                range("minBudget", r.minBudget, r.maxBudget)
                if (r.preferredBudget != null && r.maxBudget != null && r.preferredBudget > r.maxBudget) error("preferredBudget", "preferred_above_max")
                if (r.preferredBudget != null && r.minBudget != null && r.preferredBudget < r.minBudget) error("preferredBudget", "preferred_below_min")
                if (r.maxStretch != null && r.budgetFlexible != true) warn("maxStretch", "stretch_without_flexibility")
                if (r.maxBudget == null && r.preferredBudget == null) warn("maxBudget", "budget_missing")
                if (r.maxDeposit != null || r.maxMonthlyRent != null) warn("maxMonthlyRent", "rent_fields_on_purchase")
            }
            DealType.RENT -> {
                if (r.maxDeposit == null && r.availableDeposit == null) warn("maxDeposit", "deposit_limit_missing")
                if (r.maxMonthlyRent == null) warn("maxMonthlyRent", "rent_limit_missing")
            }
            DealType.FULL_DEPOSIT -> {
                if (r.maxDeposit == null && r.availableDeposit == null) warn("maxDeposit", "deposit_limit_missing")
                if (r.maxMonthlyRent != null) warn("maxMonthlyRent", "rent_on_full_deposit")
            }
        }
        if (r.cities.isEmpty() && r.neighborhoods.isEmpty() && r.mapAreas.isEmpty() && r.provinces.isEmpty()) warn("cities", "location_missing")
        if (r.categories.isEmpty()) warn("categories", "category_missing")
        r.mapAreas.forEachIndexed { i, a ->
            val circle = a.center != null && a.radiusKm != null && a.radiusKm > 0
            if (!circle && a.polygon.size < 3) error("mapAreas[$i]", "invalid_area")
        }
        val overlap = r.neighborhoods.filter { n -> r.excludedNeighborhoods.any { ir.melkcrm.core.text.PersianText.sameName(it, n) } }
        if (overlap.isNotEmpty()) error("excludedNeighborhoods", "preferred_and_excluded")
    }.result()
}

object PropertyValidator {
    fun validate(p: Property, today: EpochDay): ValidationResult = Collector().apply {
        if (!referenceRegex.matches(p.reference)) error("reference", "invalid_reference")
        if (p.title.isBlank()) error("title", "required")
        if (p.dealTypes.isEmpty()) error("dealTypes", "required")
        val strict = !p.isDraft && p.status == PropertyStatus.AVAILABLE
        if (DealType.SALE in p.dealTypes && p.askingPrice == null) if (strict) error("askingPrice", "required_for_sale") else warn("askingPrice", "required_for_sale")
        if (DealType.RENT in p.dealTypes) {
            if (p.deposit == null && p.monthlyRent == null) if (strict) error("deposit", "required_for_rent") else warn("deposit", "required_for_rent")
        }
        if (DealType.FULL_DEPOSIT in p.dealTypes && p.fullDepositAmount == null) warn("fullDepositAmount", "required_for_full_deposit")
        if (p.askingPrice != null && p.askingPrice.rial == 0L) error("askingPrice", "zero")
        if (p.builtArea != null && p.builtArea <= 0) error("builtArea", "must_be_positive")
        if (p.landArea != null && p.landArea <= 0) error("landArea", "must_be_positive")
        if (p.builtArea == null && p.landArea == null && strict) error("builtArea", "area_required")
        if (p.category == PropertyCategory.APARTMENT && p.floor != null && p.totalFloors != null && p.floor > p.totalFloors) error("floor", "floor_above_total")
        if ((p.bedrooms ?: 0) < 0 || (p.bathrooms ?: 0) < 0) error("bedrooms", "negative")
        if (p.bedrooms != null && p.bedrooms > 20) warn("bedrooms", "unusual_value")
        p.constructionYear?.let { y ->
            val current = when (p.constructionYearCalendar) {
                CalendarSystem.JALALI -> JalaliDate.ofEpochDay(today).year
                CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(today).year
            }
            val min = if (p.constructionYearCalendar == CalendarSystem.JALALI) 1250 else 1870
            if (y < min || y > current + 5) error("constructionYear", "implausible_year")
        }
        p.location?.let { if (!Geo.isLikelyInIran(it.point)) warn("location", "outside_iran") }
        if (strict && p.city.isNullOrBlank()) error("city", "required")
        if (p.askingPrice != null && p.estimatedMarketValue != null) {
            val ratio = p.askingPrice.rial.toDouble() / p.estimatedMarketValue.rial.coerceAtLeast(1)
            if (ratio > 1.5 || ratio < 0.5) warn("askingPrice", "far_from_market_value")
        }
    }.result()
}

object SellerProfileValidator {
    fun validate(s: SellerProfile, property: Property?): ValidationResult = Collector().apply {
        val min = s.confidential.minAcceptablePrice
        if (min != null && property?.askingPrice != null && min > property.askingPrice) warn("minAcceptablePrice", "min_above_asking")
        if (s.exclusive == true && s.exclusiveUntil == null) warn("exclusiveUntil", "exclusive_end_missing")
    }.result()
}

object OfferValidator {
    fun validate(o: Offer): ValidationResult = Collector().apply {
        when (o.dealType) {
            DealType.SALE -> {
                if (o.offeredPrice == null) error("offeredPrice", "required")
                if (o.offeredMonthlyRent != null) error("offeredMonthlyRent", "rent_on_sale_offer")
            }
            DealType.RENT -> {
                if (o.offeredDeposit == null && o.offeredMonthlyRent == null) error("offeredDeposit", "required")
                if (o.offeredPrice != null) error("offeredPrice", "price_on_rent_offer")
            }
            DealType.FULL_DEPOSIT -> {
                if (o.offeredDeposit == null) error("offeredDeposit", "required")
                if (o.offeredPrice != null) error("offeredPrice", "price_on_rent_offer")
            }
        }
        if (o.expiresAt != null && o.expiresAt < o.offerDate) error("expiresAt", "before_offer_date")
    }.result()
}

/** Account password rule, applied by the server and checked early by the app. Returns a code or null. */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    fun problem(password: String): String? = when {
        password.length < MIN_LENGTH -> "password_too_short"
        password.all { it.isDigit() } -> "password_only_digits"
        else -> null
    }
}
