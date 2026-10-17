package ir.melkcrm.core.reports

import ir.melkcrm.core.Fx
import ir.melkcrm.core.matching.MatchThresholds
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.CalendarSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardTest {
    private val now = AgencyTime.startOfDayMillis(Fx.TODAY) + 10 * 3_600_000L

    @Test fun figuresComeFromRecords() {
        val buyer = Fx.customer("b1").copy(registeredAt = now - 3_600_000L)
        val seller = Fx.customer("s1", roles = setOf(CustomerRole.SELLER)).copy(registeredAt = now - 30 * 86_400_000L, nextFollowUpDate = Fx.TODAY - 2)
        val inactive = Fx.customer("x", status = CustomerStatus.INACTIVE)
        val snap = CrmSnapshot(
            customers = listOf(buyer, seller, inactive),
            requirements = listOf(Fx.purchase(customerId = "b1")),
            properties = listOf(Fx.apartment().copy(ownerCustomerId = "s1"), Fx.rental(), Fx.apartment(id = "sold", status = PropertyStatus.SOLD)),
            tasks = listOf(
                TaskItem("t1", Fx.AGENCY, "پیگیری", Fx.TODAY, createdAt = 0, updatedAt = 0),
                TaskItem("t2", Fx.AGENCY, "پیگیری", Fx.TODAY - 1, createdAt = 0, updatedAt = 0),
                TaskItem("t3", Fx.AGENCY, "پیگیری", Fx.TODAY - 1, status = TaskStatus.DONE, createdAt = 0, updatedAt = 0),
            ),
            transactions = listOf(Transaction("tx", Fx.AGENCY, "d", "sold", "b1", dealType = DealType.SALE, finalPrice = Fx.billion(10.0), contractDate = Fx.TODAY - 1, updatedAt = 0)),
            commissions = listOf(
                CommissionRecord("c1", Fx.AGENCY, propertyId = "sold", party = CommissionParty.SELLER, expectedAmount = Fx.toman(50_000_000), updatedAt = 0),
                CommissionRecord("c2", Fx.AGENCY, propertyId = "sold", party = CommissionParty.BUYER, earnedAmount = Fx.toman(40_000_000), receivedAmount = Fx.toman(40_000_000), status = CommissionStatus.RECEIVED, updatedAt = 0),
            ),
            deals = listOf(Deal("d", Fx.AGENCY, "p1", "b1", dealType = DealType.SALE, stage = DealStage.NEGOTIATION, openedAt = 0, updatedAt = 0)),
        )
        val d = Dashboard.compute(snap, MatchThresholds(), now, CalendarSystem.JALALI)
        assertEquals(1, d.activeBuyers)
        assertEquals(1, d.activeSellers)
        assertEquals(3, d.totalCustomers)
        assertEquals(1, d.availableForSale)
        assertEquals(1, d.availableForRent)
        assertEquals(2, d.newCustomersThisWeek) // buyer + inactive (registered at Fx.NOW, same week)
        assertEquals(1, d.buyersWithoutMatches) // no evaluations stored yet
        assertEquals(1, d.followUpsDueToday)
        assertEquals(2, d.overdueFollowUps) // task t2 + seller follow-up date
        assertEquals(1, d.activeNegotiations)
        assertEquals(1, d.completedTransactions)
        assertEquals(Fx.toman(50_000_000), d.expectedCommission)
        assertEquals(Fx.toman(40_000_000), d.receivedCommission)
        assertEquals(6, d.monthlySales.size)
        assertEquals(1, d.monthlySales.last().count)
        assertEquals(Fx.billion(10.0), d.monthlySales.last().saleVolume)
        assertEquals(1405 to 7, d.monthlySales.last().year to d.monthlySales.last().month)
        assertEquals(Money.ZERO, d.monthlySales.first().saleVolume)
    }
}
