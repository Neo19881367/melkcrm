package ir.melkcrm.core.geo

import ir.melkcrm.core.model.GeoPoint

/**
 * Reference list of Iran's 31 provinces with their capitals, plus a starter list of cities and Tehran
 * neighborhoods for pickers. Neighborhood points are APPROXIMATE centroids for map centering only;
 * they are never stored as a property's location.
 */
object IranRegions {
    data class Province(val fa: String, val en: String, val capitalFa: String, val capitalEn: String, val cities: List<String> = emptyList())
    data class Neighborhood(val fa: String, val en: String, val approxCenter: GeoPoint, val district: Int? = null)

    val provinces = listOf(
        Province("تهران", "Tehran", "تهران", "Tehran", listOf("تهران", "شهریار", "اسلامشهر", "ری", "پردیس", "دماوند", "ورامین", "قدس", "پاکدشت", "لواسان")),
        Province("البرز", "Alborz", "کرج", "Karaj", listOf("کرج", "فردیس", "نظرآباد", "هشتگرد", "محمدشهر")),
        Province("اصفهان", "Isfahan", "اصفهان", "Isfahan", listOf("اصفهان", "کاشان", "خمینی‌شهر", "نجف‌آباد", "شاهین‌شهر")),
        Province("فارس", "Fars", "شیراز", "Shiraz", listOf("شیراز", "مرودشت", "کازرون", "جهرم")),
        Province("خراسان رضوی", "Razavi Khorasan", "مشهد", "Mashhad", listOf("مشهد", "نیشابور", "سبزوار", "طرقبه")),
        Province("آذربایجان شرقی", "East Azerbaijan", "تبریز", "Tabriz", listOf("تبریز", "مراغه", "مرند")),
        Province("آذربایجان غربی", "West Azerbaijan", "ارومیه", "Urmia"),
        Province("اردبیل", "Ardabil", "اردبیل", "Ardabil"),
        Province("ایلام", "Ilam", "ایلام", "Ilam"),
        Province("بوشهر", "Bushehr", "بوشهر", "Bushehr"),
        Province("چهارمحال و بختیاری", "Chaharmahal and Bakhtiari", "شهرکرد", "Shahrekord"),
        Province("خراسان جنوبی", "South Khorasan", "بیرجند", "Birjand"),
        Province("خراسان شمالی", "North Khorasan", "بجنورد", "Bojnurd"),
        Province("خوزستان", "Khuzestan", "اهواز", "Ahvaz", listOf("اهواز", "آبادان", "دزفول")),
        Province("زنجان", "Zanjan", "زنجان", "Zanjan"),
        Province("سمنان", "Semnan", "سمنان", "Semnan"),
        Province("سیستان و بلوچستان", "Sistan and Baluchestan", "زاهدان", "Zahedan"),
        Province("قزوین", "Qazvin", "قزوین", "Qazvin"),
        Province("قم", "Qom", "قم", "Qom"),
        Province("کردستان", "Kurdistan", "سنندج", "Sanandaj"),
        Province("کرمان", "Kerman", "کرمان", "Kerman"),
        Province("کرمانشاه", "Kermanshah", "کرمانشاه", "Kermanshah"),
        Province("کهگیلویه و بویراحمد", "Kohgiluyeh and Boyer-Ahmad", "یاسوج", "Yasuj"),
        Province("گلستان", "Golestan", "گرگان", "Gorgan"),
        Province("گیلان", "Gilan", "رشت", "Rasht", listOf("رشت", "بندر انزلی", "لاهیجان")),
        Province("لرستان", "Lorestan", "خرم‌آباد", "Khorramabad"),
        Province("مازندران", "Mazandaran", "ساری", "Sari", listOf("ساری", "بابل", "آمل", "چالوس", "نوشهر", "رامسر")),
        Province("مرکزی", "Markazi", "اراک", "Arak"),
        Province("هرمزگان", "Hormozgan", "بندرعباس", "Bandar Abbas", listOf("بندرعباس", "کیش", "قشم")),
        Province("همدان", "Hamadan", "همدان", "Hamadan"),
        Province("یزد", "Yazd", "یزد", "Yazd"),
    )

    val tehranNeighborhoods = listOf(
        Neighborhood("سعادت آباد", "Saadat Abad", GeoPoint(35.7850, 51.3750), 2),
        Neighborhood("شهرک غرب", "Shahrak-e Gharb", GeoPoint(35.7580, 51.3710), 2),
        Neighborhood("پونک", "Punak", GeoPoint(35.7620, 51.3350), 5),
        Neighborhood("جنت آباد", "Janat Abad", GeoPoint(35.7550, 51.3000), 5),
        Neighborhood("اکباتان", "Ekbatan", GeoPoint(35.7050, 51.3050), 5),
        Neighborhood("ستارخان", "Sattarkhan", GeoPoint(35.7150, 51.3600), 2),
        Neighborhood("یوسف آباد", "Yousef Abad", GeoPoint(35.7300, 51.4000), 6),
        Neighborhood("جردن", "Jordan", GeoPoint(35.7700, 51.4150), 3),
        Neighborhood("زعفرانیه", "Zafaraniyeh", GeoPoint(35.8000, 51.4100), 1),
        Neighborhood("ولنجک", "Velenjak", GeoPoint(35.8050, 51.3950), 1),
        Neighborhood("نیاوران", "Niavaran", GeoPoint(35.8100, 51.4700), 1),
        Neighborhood("پاسداران", "Pasdaran", GeoPoint(35.7700, 51.4700), 3),
        Neighborhood("نارمک", "Narmak", GeoPoint(35.7400, 51.5050), 8),
        Neighborhood("تهرانپارس", "Tehranpars", GeoPoint(35.7350, 51.5400), 4),
        Neighborhood("پیروزی", "Pirouzi", GeoPoint(35.6900, 51.4700), 13),
        Neighborhood("نازی آباد", "Nazi Abad", GeoPoint(35.6400, 51.3950), 16),
    )

    /** Western / eastern Tehran groupings, as agents commonly describe search areas. */
    val tehranWest = listOf("پونک", "جنت آباد", "اکباتان", "ستارخان", "سعادت آباد", "شهرک غرب")
    val tehranEast = listOf("نارمک", "تهرانپارس", "پیروزی", "پاسداران")

    fun provinceOfCity(city: String): Province? = provinces.firstOrNull { p -> p.cities.any { ir.melkcrm.core.text.PersianText.sameName(it, city) } || ir.melkcrm.core.text.PersianText.sameName(p.capitalFa, city) }
}
