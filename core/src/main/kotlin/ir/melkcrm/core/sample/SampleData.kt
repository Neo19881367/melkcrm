package ir.melkcrm.core.sample

import ir.melkcrm.core.commission.CommissionRule
import ir.melkcrm.core.geo.IranRegions
import ir.melkcrm.core.model.*
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.CalendarSystem
import ir.melkcrm.core.time.EpochMillis
import ir.melkcrm.core.time.JalaliDate
import kotlin.random.Random

/**
 * Deterministic, clearly synthetic development data: Iranian names, Tehran neighborhoods and
 * plausible 2026 price levels. Phone numbers use the 0912-000-xxxx block so they cannot be
 * mistaken for real customers. Coordinates are approximate and marked SAMPLE_APPROXIMATE.
 */
object SampleData {
    const val AGENCY_ID = "agency-sample-1"

    data class Dataset(
        val agency: Agency,
        val users: List<AgencyUser>,
        val customers: List<Customer>,
        val requirements: List<BuyerRequirement>,
        val properties: List<Property>,
        val sellers: List<SellerProfile>,
        val activities: List<Activity>,
        val tasks: List<TaskItem>,
        val appointments: List<Appointment>,
        val commissionRules: List<CommissionRule>,
    )

    private val firstNames = listOf("علی", "محمد", "رضا", "حسین", "مهدی", "امیر", "سارا", "مریم", "زهرا", "فاطمه", "نرگس", "نیلوفر", "الهام", "پریسا", "حمید", "سعید", "مجید", "کامران", "بهاره", "شیما", "آرش", "لیلا", "بابک", "مینا")
    private val lastNames = listOf("احمدی", "محمدی", "حسینی", "رضایی", "کریمی", "موسوی", "جعفری", "صادقی", "نوری", "رحیمی", "کاظمی", "هاشمی", "قاسمی", "اکبری", "طاهری", "شریفی", "عباسی", "فرهادی", "توکلی", "مرادی")

    /** Asking price per m² in toman, by neighborhood (synthetic). */
    private val pricePerM2 = mapOf(
        "سعادت آباد" to 120_000_000L, "شهرک غرب" to 160_000_000L, "پونک" to 95_000_000L, "جنت آباد" to 80_000_000L,
        "اکباتان" to 80_000_000L, "ستارخان" to 90_000_000L, "یوسف آباد" to 110_000_000L, "جردن" to 180_000_000L,
        "زعفرانیه" to 250_000_000L, "ولنجک" to 230_000_000L, "نیاوران" to 240_000_000L, "پاسداران" to 140_000_000L,
        "نارمک" to 75_000_000L, "تهرانپارس" to 70_000_000L, "پیروزی" to 55_000_000L, "نازی آباد" to 45_000_000L,
    )

    fun generate(nowMillis: EpochMillis = System.currentTimeMillis(), seed: Int = 1403): Dataset {
        val rnd = Random(seed)
        val today = AgencyTime.today(nowMillis = nowMillis)
        val day = 86_400_000L
        val currentJalaliYear = JalaliDate.ofEpochDay(today).year
        fun id(prefix: String, i: Int) = "$prefix-%04d".format(i)
        fun toman(v: Long) = Money.ofToman(v)
        fun roundToman(v: Long, step: Long = 100_000_000L) = (v / step) * step

        val agency = Agency(AGENCY_ID, "مشاور املاک نمونه", "تهران", nowMillis - 400 * day)
        val users = listOf(
            AgencyUser("user-admin", AGENCY_ID, "مدیر آژانس", "09120000001", null, UserRole.OWNER_ADMIN, null, true, agency.createdAt),
            AgencyUser("user-manager", AGENCY_ID, "سرپرست تیم غرب", "09120000002", null, UserRole.MANAGER, "team-west", true, agency.createdAt),
            AgencyUser("user-agent-1", AGENCY_ID, "مشاور یک", "09120000003", null, UserRole.AGENT, "team-west", true, agency.createdAt),
            AgencyUser("user-agent-2", AGENCY_ID, "مشاور دو", "09120000004", null, UserRole.AGENT, "team-east", true, agency.createdAt),
        )
        val agentIds = listOf("user-agent-1", "user-agent-2", "user-manager")
        val hoods = IranRegions.tehranNeighborhoods

        val customers = mutableListOf<Customer>()
        val requirements = mutableListOf<BuyerRequirement>()
        val properties = mutableListOf<Property>()
        val sellers = mutableListOf<SellerProfile>()

        fun customer(i: Int, roles: Set<CustomerRole>, status: CustomerStatus = CustomerStatus.ACTIVE, hood: String? = null): Customer {
            val reg = nowMillis - rnd.nextLong(1, 200) * day
            return Customer(
                id = id("cust", i), agencyId = AGENCY_ID,
                fullName = firstNames[rnd.nextInt(firstNames.size)] + " " + lastNames[rnd.nextInt(lastNames.size)],
                mobile = "0912000%04d".format(1000 + i), roles = roles,
                preferredContact = listOf(ContactMethod.PHONE_CALL, ContactMethod.WHATSAPP, ContactMethod.TELEGRAM, ContactMethod.SMS)[rnd.nextInt(4)],
                province = "تهران", city = "تهران", neighborhood = hood ?: hoods[rnd.nextInt(hoods.size)].fa,
                source = CustomerSource.entries[rnd.nextInt(CustomerSource.entries.size)],
                assignedAgentId = agentIds[i % agentIds.size], status = status.name,
                priority = Priority.entries[rnd.nextInt(Priority.entries.size)], registeredAt = reg,
                lastContactAt = reg + rnd.nextLong(0, 10) * day,
                nextFollowUpDate = if (i % 3 == 0) today + rnd.nextLong(-3, 7) else null,
                consent = ConsentPreferences(callsAllowed = true, smsAllowed = i % 2 == 0, messengerAllowed = i % 3 != 0, recordedAt = reg),
                updatedAt = reg,
            )
        }

        // The three illustrative buyers from the specification, plus their target property.
        val saadat = hoods.first { it.en == "Saadat Abad" }
        val ownerA = customer(1, setOf(CustomerRole.SELLER), hood = saadat.fa)
        customers += ownerA
        val pSaadat = Property(
            id = id("prop", 1), agencyId = AGENCY_ID, reference = PropertyReference.format(123),
            title = "آپارتمان ۱۰۰ متری سعادت آباد", category = PropertyCategory.APARTMENT, dealTypes = setOf(DealType.SALE),
            askingPrice = toman(12_000_000_000), estimatedMarketValue = toman(11_500_000_000), negotiable = true,
            province = "تهران", city = "تهران", neighborhood = saadat.fa, address = "سعادت آباد، خیابان نمونه",
            location = Location(saadat.approxCenter, LocationSource.SAMPLE_APPROXIMATE, 800.0),
            builtArea = 100.0, bedrooms = 2, bathrooms = 1, floor = 3, totalFloors = 5, constructionYear = currentJalaliYear - 6,
            constructionYearCalendar = CalendarSystem.JALALI, parkingSpaces = 1, storage = true, elevator = true,
            heating = HeatingType.PACKAGE, cooling = CoolingType.SPLIT, condition = PropertyCondition.GOOD,
            documentStatus = DocumentStatus.SINGLE_SHEET_DEED, facing = Facing.NORTH,
            ownerCustomerId = ownerA.id, assignedAgentId = "user-agent-1", acquiredDate = today - 5,
            status = PropertyStatus.AVAILABLE, createdAt = nowMillis - 5 * day, updatedAt = nowMillis - 5 * day,
        )
        properties += pSaadat
        sellers += SellerProfile(
            id = id("seller", 1), agencyId = AGENCY_ID, customerId = ownerA.id, propertyId = pSaadat.id, assignedAgentId = "user-agent-1",
            status = SellerStatus.ACTIVE_FOR_SALE, saleDeadline = today + 60, motivation = "خرید واحد بزرگ‌تر",
            priceFlexibility = FlexibilityLevel.MEDIUM, exclusive = false,
            confidential = SellerConfidential(minAcceptablePrice = toman(11_300_000_000), notes = "تا ۱۱.۳ میلیارد قابل مذاکره"),
            updatedAt = nowMillis - 5 * day,
        )
        val buyerA = customer(2, setOf(CustomerRole.BUYER), hood = saadat.fa)
        val buyerB = customer(3, setOf(CustomerRole.BUYER))
        val buyerC = customer(4, setOf(CustomerRole.BUYER, CustomerRole.INVESTOR))
        customers += listOf(buyerA, buyerB, buyerC)
        requirements += BuyerRequirement(
            id = id("req", 1), agencyId = AGENCY_ID, customerId = buyerA.id, title = "آپارتمان سعادت آباد",
            dealType = DealType.SALE, categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"),
            neighborhoods = listOf("سعادت آباد"), alternativeNeighborhoods = listOf("شهرک غرب"),
            minArea = 90.0, maxArea = 120.0, minBedrooms = 2, maxBedrooms = 3, parking = RequirementLevel.REQUIRED, elevator = RequirementLevel.REQUIRED,
            minBudget = toman(11_000_000_000), maxBudget = toman(13_000_000_000), preferredBudget = toman(11_500_000_000),
            urgency = Urgency.WITHIN_1_MONTH, negotiationFlexibility = FlexibilityLevel.MEDIUM, leadQuality = LeadQuality.HOT,
            preferredFacing = setOf(Facing.NORTH), notes = "واحد شمالی ترجیح دارد",
            createdAt = buyerA.registeredAt, updatedAt = buyerA.registeredAt,
        )
        requirements += BuyerRequirement(
            id = id("req", 2), agencyId = AGENCY_ID, customerId = buyerB.id, title = "غرب تهران",
            dealType = DealType.SALE, categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"),
            neighborhoods = IranRegions.tehranWest - "سعادت آباد", minArea = 80.0, maxArea = 110.0, minBedrooms = 2,
            minBudget = toman(9_000_000_000), maxBudget = toman(10_000_000_000), budgetFlexible = true, maxStretch = toman(1_000_000_000),
            urgency = Urgency.WITHIN_3_MONTHS, leadQuality = LeadQuality.WARM,
            createdAt = buyerB.registeredAt, updatedAt = buyerB.registeredAt,
        )
        requirements += BuyerRequirement(
            id = id("req", 3), agencyId = AGENCY_ID, customerId = buyerC.id, title = "فقط شرق تهران",
            dealType = DealType.SALE, categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"),
            neighborhoods = IranRegions.tehranEast, locationStrict = true, minArea = 120.0, maxArea = 150.0,
            minBudget = toman(12_000_000_000), maxBudget = toman(14_000_000_000),
            urgency = Urgency.WITHIN_6_MONTHS, createdAt = buyerC.registeredAt, updatedAt = buyerC.registeredAt,
        )

        // Generated inventory.
        var ref = 124L
        for (i in 2..34) {
            val hood = hoods[rnd.nextInt(hoods.size)]
            val owner = customer(100 + i, if (i % 5 == 0) setOf(CustomerRole.LANDLORD) else setOf(CustomerRole.SELLER), hood = hood.fa)
            customers += owner
            val rental = i % 5 == 0
            val category = if (i % 11 == 0) PropertyCategory.OFFICE else if (i % 13 == 0) PropertyCategory.HOUSE else PropertyCategory.APARTMENT
            val area = (60 + rnd.nextInt(0, 140)).toDouble()
            val perM2 = pricePerM2[hood.fa] ?: 80_000_000L
            val price = roundToman((area * perM2 * (0.9 + rnd.nextDouble() * 0.25)).toLong())
            val totalFloors = 4 + rnd.nextInt(0, 9)
            val status = when {
                i % 17 == 0 -> PropertyStatus.SOLD
                i % 19 == 0 -> PropertyStatus.RESERVED
                i % 23 == 0 -> PropertyStatus.PREPARING
                else -> PropertyStatus.AVAILABLE
            }
            val hasCoords = i % 7 != 0 // some properties deliberately have no coordinates
            val p = Property(
                id = id("prop", i), agencyId = AGENCY_ID, reference = PropertyReference.format(ref++),
                title = "${if (rental) "اجاره" else "فروش"} ${area.toInt()} متر ${hood.fa}", category = category,
                dealTypes = if (rental) setOf(DealType.RENT) else setOf(DealType.SALE),
                askingPrice = if (rental) null else toman(price),
                deposit = if (rental) toman(roundToman(price / 10)) else null,
                monthlyRent = if (rental) toman(roundToman(price / 400, 1_000_000)) else null,
                estimatedMarketValue = if (rental) null else toman(roundToman((price * 0.95).toLong())),
                negotiable = if (i % 4 == 0) null else rnd.nextBoolean(),
                province = "تهران", city = "تهران", neighborhood = hood.fa,
                location = if (hasCoords) Location(ir.melkcrm.core.model.GeoPoint(hood.approxCenter.lat + rnd.nextDouble(-0.006, 0.006), hood.approxCenter.lng + rnd.nextDouble(-0.006, 0.006)), LocationSource.SAMPLE_APPROXIMATE, 700.0) else null,
                builtArea = area, bedrooms = if (category == PropertyCategory.OFFICE) null else (1 + (area / 55).toInt()).coerceAtMost(4),
                bathrooms = 1 + (area / 120).toInt(), floor = rnd.nextInt(0, totalFloors + 1), totalFloors = totalFloors,
                constructionYear = currentJalaliYear - rnd.nextInt(0, 25), constructionYearCalendar = CalendarSystem.JALALI,
                parkingSpaces = if (i % 6 == 0) 0 else 1, storage = if (i % 9 == 0) null else rnd.nextBoolean(),
                elevator = if (totalFloors <= 4 && i % 3 == 0) false else if (i % 8 == 0) null else true,
                condition = PropertyCondition.entries[rnd.nextInt(4)], documentStatus = if (i % 10 == 0) DocumentStatus.PROMISSORY_CONTRACT else DocumentStatus.SINGLE_SHEET_DEED,
                facing = if (rnd.nextBoolean()) Facing.NORTH else Facing.SOUTH,
                ownerCustomerId = owner.id, assignedAgentId = agentIds[i % agentIds.size],
                acquiredDate = today - rnd.nextLong(1, 120), status = status,
                createdAt = nowMillis - rnd.nextLong(1, 120) * day, updatedAt = nowMillis - rnd.nextLong(0, 5) * day,
            )
            properties += p
            sellers += SellerProfile(
                id = id("seller", i), agencyId = AGENCY_ID, customerId = owner.id, propertyId = p.id, assignedAgentId = p.assignedAgentId,
                status = if (status == PropertyStatus.SOLD) SellerStatus.SOLD else SellerStatus.ACTIVE_FOR_SALE,
                saleDeadline = if (i % 3 == 0) today + rnd.nextLong(20, 150) else null,
                priceFlexibility = FlexibilityLevel.entries[rnd.nextInt(FlexibilityLevel.entries.size)],
                confidential = SellerConfidential(minAcceptablePrice = if (!rental && i % 2 == 0) toman(roundToman((price * 0.93).toLong())) else null),
                updatedAt = p.updatedAt,
            )
        }

        // Generated buyers and tenants.
        for (i in 4..30) {
            val c = customer(200 + i, if (i % 6 == 0) setOf(CustomerRole.TENANT) else setOf(CustomerRole.BUYER),
                status = if (i % 15 == 0) CustomerStatus.INACTIVE else CustomerStatus.ACTIVE)
            customers += c
            val rental = i % 6 == 0
            val hood = hoods[rnd.nextInt(hoods.size)]
            val area = (70 + rnd.nextInt(0, 80)).toDouble()
            val budget = roundToman((area * (pricePerM2[hood.fa] ?: 80_000_000L) * (0.95 + rnd.nextDouble() * 0.2)).toLong())
            requirements += BuyerRequirement(
                id = id("req", i), agencyId = AGENCY_ID, customerId = c.id,
                dealType = if (rental) DealType.RENT else DealType.SALE,
                categories = setOf(PropertyCategory.APARTMENT), cities = listOf("تهران"),
                neighborhoods = listOf(hood.fa), alternativeNeighborhoods = hoods.shuffled(rnd).take(2).map { it.fa },
                mapAreas = if (i % 4 == 0) listOf(GeoArea("اطراف ${hood.fa}", hood.approxCenter, 2.0)) else emptyList(),
                minArea = area - 15, maxArea = area + 25, minBedrooms = if (area > 90) 2 else 1,
                parking = if (i % 3 == 0) RequirementLevel.REQUIRED else RequirementLevel.PREFERRED,
                elevator = if (i % 5 == 0) RequirementLevel.REQUIRED else RequirementLevel.PREFERRED,
                storage = RequirementLevel.PREFERRED, maxBuildingAgeYears = if (i % 4 == 1) 10 else null,
                minBudget = if (rental || i % 9 == 0) null else toman(roundToman((budget * 0.8).toLong())),
                maxBudget = if (rental || i % 9 == 0) null else toman(budget),
                preferredBudget = if (rental || i % 9 == 0) null else toman(roundToman((budget * 0.92).toLong())),
                budgetFlexible = if (i % 2 == 0) true else null, maxStretch = if (i % 2 == 0 && !rental) toman(roundToman(budget / 10)) else null,
                maxDeposit = if (rental) toman(roundToman(budget / 10)) else null,
                maxMonthlyRent = if (rental) toman(roundToman(budget / 380, 1_000_000)) else null,
                urgency = Urgency.entries[rnd.nextInt(Urgency.entries.size)],
                needsToSellFirst = if (i % 7 == 0) true else null,
                negotiationFlexibility = FlexibilityLevel.entries[rnd.nextInt(FlexibilityLevel.entries.size)],
                leadQuality = LeadQuality.entries[rnd.nextInt(LeadQuality.entries.size)],
                status = if (i % 13 == 0) RequirementStatus.PAUSED else RequirementStatus.ACTIVE,
                createdAt = c.registeredAt, updatedAt = c.registeredAt,
            )
        }

        // A deliberate duplicate (same phone, different spelling) for the duplicate-detection screen.
        val dupSource = customers.first { it.id == buyerB.id }
        customers += dupSource.copy(id = id("cust", 999), fullName = dupSource.fullName.replace("ی", "ي"), mobile = "+98" + dupSource.mobile!!.drop(1), registeredAt = nowMillis - day, updatedAt = nowMillis - day)

        val activities = customers.take(25).mapIndexed { i, c ->
            Activity(
                id = id("act", i), agencyId = AGENCY_ID,
                type = listOf(ActivityType.PHONE_CALL, ActivityType.MEETING, ActivityType.PROPERTY_VIEWING, ActivityType.MESSENGER_MANUAL)[i % 4],
                occurredAt = nowMillis - (i + 1) * day / 2, agentId = c.assignedAgentId, customerId = c.id,
                propertyId = if (i % 4 == 2) properties[i % properties.size].id else null,
                outcome = listOf(ActivityOutcome.INTERESTED, ActivityOutcome.NO_ANSWER, ActivityOutcome.SUCCESSFUL, ActivityOutcome.CALL_BACK)[i % 4],
                channel = if (i % 4 == 3) ContactMethod.WHATSAPP else null,
                notes = "ثبت نمونه", updatedAt = nowMillis - (i + 1) * day / 2,
            )
        }
        val tasks = (0 until 12).map { i ->
            TaskItem(
                id = id("task", i), agencyId = AGENCY_ID, title = "پیگیری مشتری ${customers[i].fullName}",
                dueDate = today + (i - 4), assignedAgentId = agentIds[i % agentIds.size], customerId = customers[i].id,
                status = if (i < 2) TaskStatus.DONE else TaskStatus.OPEN, completedAt = if (i < 2) nowMillis - day else null,
                createdAt = nowMillis - 10 * day, updatedAt = nowMillis - day,
            )
        }
        val appointments = (0 until 6).map { i ->
            Appointment(
                id = id("appt", i), agencyId = AGENCY_ID, type = if (i % 2 == 0) AppointmentType.VIEWING else AppointmentType.MEETING,
                title = if (i % 2 == 0) "بازدید ${properties[i].title}" else "جلسه با مشتری", startsAt = AgencyTime.startOfDayMillis(today + i) + 11 * 3_600_000L,
                endsAt = AgencyTime.startOfDayMillis(today + i) + 12 * 3_600_000L, agentId = agentIds[i % agentIds.size],
                customerIds = listOf(customers[2 + i].id), propertyId = if (i % 2 == 0) properties[i].id else null, updatedAt = nowMillis,
            )
        }
        val rules = listOf(
            CommissionRule("rule-sale", AGENCY_ID, "فروش (نمونه، قابل تنظیم)", DealType.SALE, CommissionParty.SELLER, priceBasisPoints = 50),
            CommissionRule("rule-sale-buyer", AGENCY_ID, "خرید (نمونه، قابل تنظیم)", DealType.SALE, CommissionParty.BUYER, priceBasisPoints = 50),
            CommissionRule("rule-rent", AGENCY_ID, "اجاره (نمونه، قابل تنظیم)", DealType.RENT, CommissionParty.LANDLORD, depositBasisPoints = 25, monthlyRentBasisPoints = 2500),
        )
        return Dataset(agency, users, customers, requirements, properties, sellers, activities, tasks, appointments, rules)
    }
}
