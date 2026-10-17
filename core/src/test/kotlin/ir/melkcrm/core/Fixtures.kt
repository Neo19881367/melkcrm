package ir.melkcrm.core

import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.CalendarSystem
import java.time.LocalDate

/** Synthetic Iranian test records. */
object Fx {
    const val AGENCY = "agency-1"
    val TODAY: Long = LocalDate.of(2026, 10, 17).toEpochDay() // 1405/07/25
    const val NOW = 1_792_224_000_000L

    fun toman(v: Long) = Money.ofToman(v)
    fun billion(v: Double) = Money.ofToman((v * 1_000_000_000).toLong())

    fun customer(id: String = "c1", roles: Set<CustomerRole> = setOf(CustomerRole.BUYER), status: CustomerStatus = CustomerStatus.ACTIVE, mobile: String? = "09121110001") =
        Customer(id = id, agencyId = AGENCY, fullName = "مریم کریمی", mobile = mobile, roles = roles, status = status.name,
            city = "تهران", registeredAt = NOW, updatedAt = NOW, assignedAgentId = "agent-1")

    fun apartment(
        id: String = "p1",
        price: Money? = billion(12.0),
        neighborhood: String? = "سعادت آباد",
        city: String? = "تهران",
        area: Double? = 100.0,
        bedrooms: Int? = 2,
        status: PropertyStatus = PropertyStatus.AVAILABLE,
        elevator: Boolean? = true,
        parking: Int? = 1,
        location: Location? = Location(GeoPoint(35.785, 51.375), LocationSource.MANUAL_PIN),
    ) = Property(
        id = id, agencyId = AGENCY, reference = "IR-000123", title = "آپارتمان", category = PropertyCategory.APARTMENT,
        dealTypes = setOf(DealType.SALE), askingPrice = price, negotiable = true, province = "تهران", city = city,
        neighborhood = neighborhood, location = location, builtArea = area, bedrooms = bedrooms, floor = 3, totalFloors = 5,
        constructionYear = 1399, constructionYearCalendar = CalendarSystem.JALALI, parkingSpaces = parking, storage = true,
        elevator = elevator, condition = PropertyCondition.GOOD, documentStatus = DocumentStatus.SINGLE_SHEET_DEED,
        ownerCustomerId = "owner-1", status = status, createdAt = NOW, updatedAt = NOW,
    )

    fun rental(id: String = "r1", deposit: Money? = billion(1.0), rent: Money? = toman(30_000_000)) =
        apartment(id = id, price = null).copy(dealTypes = setOf(DealType.RENT), deposit = deposit, monthlyRent = rent)

    fun purchase(
        id: String = "q1",
        customerId: String = "c1",
        min: Money? = billion(11.0),
        max: Money? = billion(13.0),
        preferred: Money? = billion(12.0),
        neighborhoods: List<String> = listOf("سعادت آباد"),
        minArea: Double? = 90.0,
        maxArea: Double? = 120.0,
    ) = BuyerRequirement(
        id = id, agencyId = AGENCY, customerId = customerId, dealType = DealType.SALE,
        categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"), neighborhoods = neighborhoods,
        minArea = minArea, maxArea = maxArea, minBedrooms = 2, maxBedrooms = 3,
        minBudget = min, maxBudget = max, preferredBudget = preferred,
        urgency = Urgency.WITHIN_1_MONTH, createdAt = NOW, updatedAt = NOW,
    )

    fun rentRequirement(maxDeposit: Money? = billion(1.2), maxRent: Money? = toman(35_000_000)) =
        purchase().copy(dealType = DealType.RENT, minBudget = null, maxBudget = null, preferredBudget = null, maxDeposit = maxDeposit, maxMonthlyRent = maxRent)

    fun seller(propertyId: String = "p1", min: Money? = null, deadline: Long? = null) =
        SellerProfile(id = "s-$propertyId", agencyId = AGENCY, customerId = "owner-1", propertyId = propertyId,
            saleDeadline = deadline, confidential = SellerConfidential(minAcceptablePrice = min), updatedAt = NOW)
}
