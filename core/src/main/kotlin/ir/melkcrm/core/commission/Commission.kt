package ir.melkcrm.core.commission

import ir.melkcrm.core.model.CommissionParty
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.money.Money
import kotlinx.serialization.Serializable

/**
 * Agency-configured commission rule. Nothing here assumes an official tariff: rates differ by
 * province, union (اتحادیه املاک) circulars and agreement, so every amount computed from a rule is an
 * ESTIMATE until the agent confirms the earned amount.
 */
@Serializable
data class CommissionRule(
    val id: String,
    val agencyId: String,
    val name: String,
    val dealType: DealType,
    val party: CommissionParty,
    /** Percent of the sale price in basis points (50 = 0.5%). */
    val priceBasisPoints: Int = 0,
    /** Percent of the deposit (rahn) in basis points. */
    val depositBasisPoints: Int = 0,
    /** Percent of one month's rent in basis points (10000 = one month's rent). */
    val monthlyRentBasisPoints: Int = 0,
    /** Tiers on the sale price, applied marginally; when non-empty they replace [priceBasisPoints]. */
    val tiers: List<CommissionTier> = emptyList(),
    val fixedAmount: Money? = null,
    val minimum: Money? = null,
    val maximum: Money? = null,
    /** VAT or other levy on the commission, in basis points (configurable, default none). */
    val taxBasisPoints: Int = 0,
    val active: Boolean = true,
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("name_required")
        if (listOf(priceBasisPoints, depositBasisPoints, monthlyRentBasisPoints, taxBasisPoints).any { it < 0 }) add("negative_rate")
        if (minimum != null && maximum != null && minimum > maximum) add("min_above_max")
        if (tiers.zipWithNext().any { (a, b) -> b.from <= a.from }) add("tiers_not_increasing")
        if (dealType == DealType.SALE && (depositBasisPoints > 0 || monthlyRentBasisPoints > 0)) add("rent_rates_on_sale_rule")
    }
}

/** From [from] (rial, inclusive) upwards, until the next tier starts. */
@Serializable
data class CommissionTier(val from: Money, val basisPoints: Int)

data class CommissionEstimate(val base: Money, val tax: Money, val total: Money, val isEstimate: Boolean = true)

object CommissionCalculator {
    fun estimate(rule: CommissionRule, price: Money? = null, deposit: Money? = null, monthlyRent: Money? = null): CommissionEstimate {
        var base = Money.ZERO
        if (price != null) base += if (rule.tiers.isNotEmpty()) tiered(price, rule.tiers) else price.timesBasisPoints(rule.priceBasisPoints)
        if (deposit != null) base += deposit.timesBasisPoints(rule.depositBasisPoints)
        if (monthlyRent != null) base += monthlyRent.timesBasisPoints(rule.monthlyRentBasisPoints)
        rule.fixedAmount?.let { base += it }
        rule.minimum?.let { if (base < it) base = it }
        rule.maximum?.let { if (base > it) base = it }
        val tax = base.timesBasisPoints(rule.taxBasisPoints)
        return CommissionEstimate(base, tax, base + tax)
    }

    private fun tiered(price: Money, tiers: List<CommissionTier>): Money {
        var total = Money.ZERO
        val sorted = tiers.sortedBy { it.from }
        for ((i, t) in sorted.withIndex()) {
            if (price <= t.from) break
            val upper = sorted.getOrNull(i + 1)?.from?.let { if (it < price) it else price } ?: price
            total += (upper - t.from).timesBasisPoints(t.basisPoints)
        }
        return total
    }

    /** Agent's share of a commission amount. */
    fun agentShare(amount: Money, shareBasisPoints: Int): Money = amount.timesBasisPoints(shareBasisPoints)
}
