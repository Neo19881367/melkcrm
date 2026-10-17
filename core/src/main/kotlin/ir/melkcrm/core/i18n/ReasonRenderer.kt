package ir.melkcrm.core.i18n

import ir.melkcrm.core.matching.MissingData
import ir.melkcrm.core.matching.Reason
import ir.melkcrm.core.matching.ReasonCode
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.PropertyCategory
import ir.melkcrm.core.model.PropertyStatus
import ir.melkcrm.core.model.RequirementStatus
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.money.MoneyFormatter
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.time.AppLanguage

/** Renders match explanations in Persian or English, with amounts in the viewer's currency unit. */
class ReasonRenderer(
    private val lang: AppLanguage,
    private val unit: CurrencyUnit = CurrencyUnit.TOMAN,
    private val persianDigits: Boolean = lang == AppLanguage.PERSIAN,
) {
    private val fa = lang == AppLanguage.PERSIAN

    private fun m(r: Reason, i: Int): String =
        r.amounts.getOrNull(i)?.let { MoneyFormatter.compact(Money(it), unit, fa, persianDigits) } ?: "?"

    private fun n(r: Reason, i: Int): String {
        val v = r.numbers.getOrNull(i) ?: return "?"
        val s = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        return Digits.localize(s, persianDigits)
    }

    private fun k(r: Reason, i: Int): String = r.keys.getOrNull(i) ?: ""

    private fun feature(r: Reason) = Labels.feature(k(r, 0), lang)

    private inline fun <reified E : Enum<E>> enumKey(r: Reason): String {
        val key = k(r, 0)
        val e = enumValues<E>().firstOrNull { it.name == key } ?: return key
        return Labels.of(e, lang)
    }

    fun render(r: Reason): String = if (fa) renderFa(r) else renderEn(r)

    fun render(m: MissingData): String {
        val base = Labels.of(m.field, lang)
        return if (m.detail != null) "$base: ${Labels.feature(m.detail, lang)}" else base
    }

    private fun renderFa(r: Reason): String = when (r.code) {
        ReasonCode.PROPERTY_NOT_AVAILABLE -> "ملک در وضعیت «${enumKey<PropertyStatus>(r)}» است"
        ReasonCode.REQUIREMENT_NOT_ACTIVE -> "درخواست خریدار فعال نیست (${enumKey<RequirementStatus>(r)})"
        ReasonCode.CUSTOMER_INACTIVE -> "مشتری غیرفعال است (${Labels.customerStatus(k(r, 0), lang)})"
        ReasonCode.OWN_PROPERTY -> "ملک متعلق به خود این مشتری است"
        ReasonCode.DEAL_TYPE_MISMATCH -> "نوع معامله (${enumKey<DealType>(r)}) برای این ملک ارائه نشده"
        ReasonCode.CATEGORY_MISMATCH -> "نوع ملک (${enumKey<PropertyCategory>(r)}) مورد نظر خریدار نیست"
        ReasonCode.EXCLUDED_CITY -> "شهر ${k(r, 0)} توسط خریدار رد شده است"
        ReasonCode.EXCLUDED_NEIGHBORHOOD -> "محله ${k(r, 0)} توسط خریدار رد شده است"
        ReasonCode.OUTSIDE_REQUIRED_LOCATION -> "خارج از منطقه الزامی خریدار"
        ReasonCode.OVER_BUDGET -> "قیمت ${m(r, 0)} بیش از سقف توان خرید (${m(r, 1)}) است"
        ReasonCode.OVER_DEPOSIT_LIMIT -> "مبلغ رهن ${m(r, 0)} بیش از سقف خریدار (${m(r, 1)}) است"
        ReasonCode.OVER_RENT_LIMIT -> "اجاره ${m(r, 0)} بیش از سقف ماهانه خریدار (${m(r, 1)}) است"
        ReasonCode.AREA_OUT_OF_RANGE -> "متراژ ${n(r, 0)} متر خارج از محدوده الزامی است"
        ReasonCode.BEDROOMS_OUT_OF_RANGE -> "تعداد اتاق (${n(r, 0)}) خارج از محدوده الزامی است"
        ReasonCode.MANDATORY_FEATURE_MISSING -> "${feature(r)} الزامی است ولی ملک ندارد"
        ReasonCode.WITHIN_BUDGET -> "در محدوده بودجه خریدار"
        ReasonCode.AT_OR_BELOW_PREFERRED_BUDGET -> "در حد یا کمتر از بودجه ترجیحی"
        ReasonCode.ABOVE_PREFERRED_BUDGET -> "قیمت بالاتر از بودجه ترجیحی (${m(r, 0)})"
        ReasonCode.BELOW_MIN_BUDGET -> "قیمت کمتر از حداقل بودجه اعلام‌شده (${m(r, 0)})"
        ReasonCode.ABOVE_MAX_WITHIN_STRETCH -> "بالاتر از سقف بودجه، در حد انعطاف اعلام‌شده خریدار"
        ReasonCode.ABOVE_MAX_NEGOTIATION_NEEDED -> "قیمت فعلی بالاتر از سقف خریدار است؛ فقط با مذاکره قابل انجام است"
        ReasonCode.DEPOSIT_WITHIN_LIMIT -> "مبلغ رهن در محدوده خریدار"
        ReasonCode.RENT_WITHIN_LIMIT -> "اجاره ماهانه در محدوده خریدار"
        ReasonCode.DEPOSIT_ABOVE_LIMIT -> "رهن بالاتر از سقف خریدار (${m(r, 0)})"
        ReasonCode.RENT_ABOVE_LIMIT -> "اجاره بالاتر از سقف خریدار (${m(r, 0)})"
        ReasonCode.PREFERRED_NEIGHBORHOOD -> "محله مورد نظر خریدار (${k(r, 0)})"
        ReasonCode.ALTERNATIVE_NEIGHBORHOOD -> "در محله جایگزین قابل قبول (${k(r, 0)})"
        ReasonCode.INSIDE_MAP_AREA -> "داخل محدوده انتخاب‌شده روی نقشه"
        ReasonCode.NEAR_MAP_AREA -> "${n(r, 0)} کیلومتر از محدوده انتخاب‌شده"
        ReasonCode.PREFERRED_CITY -> "در شهر مورد نظر (${k(r, 0)})"
        ReasonCode.PREFERRED_PROVINCE -> "در استان مورد نظر (${k(r, 0)})"
        ReasonCode.OUTSIDE_PREFERRED_NEIGHBORHOOD -> "خارج از محله‌های مورد نظر"
        ReasonCode.OUTSIDE_PREFERRED_LOCATION -> "خارج از منطقه مورد نظر خریدار"
        ReasonCode.NO_LOCATION_PREFERENCE -> "خریدار منطقه‌ای مشخص نکرده"
        ReasonCode.AREA_FITS -> "متراژ ${n(r, 0)} متر مطابق نیاز"
        ReasonCode.AREA_BELOW_MIN -> "متراژ ${n(r, 0)} متر کمتر از حداقل ${n(r, 1)} متر"
        ReasonCode.AREA_ABOVE_MAX -> "متراژ ${n(r, 0)} متر بیشتر از حداکثر ${n(r, 1)} متر"
        ReasonCode.BEDROOMS_FIT -> "${n(r, 0)} اتاق خواب مطابق نیاز"
        ReasonCode.FEWER_BEDROOMS -> "${n(r, 0)} اتاق خواب؛ کمتر از ${n(r, 1)}"
        ReasonCode.MORE_BEDROOMS -> "${n(r, 0)} اتاق خواب؛ بیشتر از ${n(r, 1)}"
        ReasonCode.FLOOR_FITS -> "طبقه ${n(r, 0)} مطابق ترجیح"
        ReasonCode.FLOOR_OUTSIDE_RANGE -> "طبقه ${n(r, 0)} خارج از محدوده ترجیحی"
        ReasonCode.FEATURE_SATISFIED -> "${feature(r)} دارد"
        ReasonCode.FEATURE_MISSING -> "${feature(r)} ندارد (ترجیحی)"
        ReasonCode.REQUIRED_FEATURE_UNKNOWN -> "وضعیت ${feature(r)} (الزامی) ثبت نشده"
        ReasonCode.NO_FEATURE_REQUIREMENTS -> "خریدار امکانات خاصی الزام نکرده"
        ReasonCode.URGENT_BUYER -> "خریدار ظرف ${n(r, 0)} روز قصد معامله دارد"
        ReasonCode.FITS_SELLER_DEADLINE -> "زمان‌بندی خریدار با مهلت فروشنده سازگار است"
        ReasonCode.BUYER_LATER_THAN_DEADLINE -> "زمان خرید دیرتر از مهلت فروشنده است"
        ReasonCode.NEEDS_TO_SELL_FIRST -> "خریدار ابتدا باید ملک خود را بفروشد"
        ReasonCode.SLOW_TIMELINE -> "زمان خرید طولانی (حدود ${n(r, 0)} روز)"
        ReasonCode.BUYER_FLEXIBLE -> "خریدار در مذاکره انعطاف دارد"
        ReasonCode.SELLER_FLEXIBLE -> "فروشنده در قیمت انعطاف دارد"
        ReasonCode.PROPERTY_NEGOTIABLE -> "قیمت قابل مذاکره است"
        ReasonCode.BUYER_INFLEXIBLE -> "خریدار انعطافی در مذاکره ندارد"
        ReasonCode.SELLER_INFLEXIBLE -> "فروشنده انعطافی در قیمت ندارد"
        ReasonCode.NO_FLEXIBILITY_INFO -> "اطلاعاتی درباره انعطاف طرفین ثبت نشده"
        ReasonCode.CONFIRMED_INTEREST -> "خریدار علاقه خود را تأیید کرده"
        ReasonCode.REQUESTED_VIEWING -> "خریدار درخواست بازدید داده"
        ReasonCode.SUBMITTED_OFFER -> "خریدار پیشنهاد قیمت داده"
        ReasonCode.HOT_LEAD -> "ارزیابی مشاور: مشتری داغ"
        ReasonCode.COLD_LEAD -> "ارزیابی مشاور: مشتری سرد"
        ReasonCode.UNRESPONSIVE -> "${n(r, 0)} پیگیری اخیر بی‌پاسخ مانده"
        ReasonCode.BACKUP_CANDIDATE -> "ملک ${enumKey<PropertyStatus>(r)} است؛ خریدار جایگزین"
    }

    private fun renderEn(r: Reason): String = when (r.code) {
        ReasonCode.PROPERTY_NOT_AVAILABLE -> "Property is ${enumKey<PropertyStatus>(r)}"
        ReasonCode.REQUIREMENT_NOT_ACTIVE -> "Buyer requirement is not active (${enumKey<RequirementStatus>(r)})"
        ReasonCode.CUSTOMER_INACTIVE -> "Customer is inactive (${Labels.customerStatus(k(r, 0), lang)})"
        ReasonCode.OWN_PROPERTY -> "The customer owns this property"
        ReasonCode.DEAL_TYPE_MISMATCH -> "Property is not offered for ${enumKey<DealType>(r)}"
        ReasonCode.CATEGORY_MISMATCH -> "Property type (${enumKey<PropertyCategory>(r)}) not wanted"
        ReasonCode.EXCLUDED_CITY -> "Buyer excluded ${k(r, 0)}"
        ReasonCode.EXCLUDED_NEIGHBORHOOD -> "Buyer excluded ${k(r, 0)}"
        ReasonCode.OUTSIDE_REQUIRED_LOCATION -> "Outside the buyer's required area"
        ReasonCode.OVER_BUDGET -> "Price ${m(r, 0)} exceeds the buyer's absolute maximum (${m(r, 1)})"
        ReasonCode.OVER_DEPOSIT_LIMIT -> "Deposit ${m(r, 0)} exceeds the buyer's limit (${m(r, 1)})"
        ReasonCode.OVER_RENT_LIMIT -> "Rent ${m(r, 0)} exceeds the buyer's monthly limit (${m(r, 1)})"
        ReasonCode.AREA_OUT_OF_RANGE -> "${n(r, 0)} m² is outside the required range"
        ReasonCode.BEDROOMS_OUT_OF_RANGE -> "${n(r, 0)} bedrooms is outside the required range"
        ReasonCode.MANDATORY_FEATURE_MISSING -> "${feature(r)} is required but missing"
        ReasonCode.WITHIN_BUDGET -> "Within stated budget"
        ReasonCode.AT_OR_BELOW_PREFERRED_BUDGET -> "At or below preferred budget"
        ReasonCode.ABOVE_PREFERRED_BUDGET -> "Asking price is above preferred budget (${m(r, 0)})"
        ReasonCode.BELOW_MIN_BUDGET -> "Price is below the buyer's minimum budget (${m(r, 0)})"
        ReasonCode.ABOVE_MAX_WITHIN_STRETCH -> "Above maximum budget, within the buyer's stated stretch"
        ReasonCode.ABOVE_MAX_NEGOTIATION_NEEDED -> "Current price is above the buyer's maximum; only feasible through negotiation"
        ReasonCode.DEPOSIT_WITHIN_LIMIT -> "Deposit within the buyer's limit"
        ReasonCode.RENT_WITHIN_LIMIT -> "Monthly rent within the buyer's limit"
        ReasonCode.DEPOSIT_ABOVE_LIMIT -> "Deposit above the buyer's limit (${m(r, 0)})"
        ReasonCode.RENT_ABOVE_LIMIT -> "Rent above the buyer's limit (${m(r, 0)})"
        ReasonCode.PREFERRED_NEIGHBORHOOD -> "Preferred neighborhood (${k(r, 0)})"
        ReasonCode.ALTERNATIVE_NEIGHBORHOOD -> "Accepted alternative neighborhood (${k(r, 0)})"
        ReasonCode.INSIDE_MAP_AREA -> "Inside the buyer's saved map area"
        ReasonCode.NEAR_MAP_AREA -> "${n(r, 0)} km from the buyer's saved map area"
        ReasonCode.PREFERRED_CITY -> "In preferred city (${k(r, 0)})"
        ReasonCode.PREFERRED_PROVINCE -> "In preferred province (${k(r, 0)})"
        ReasonCode.OUTSIDE_PREFERRED_NEIGHBORHOOD -> "Outside the preferred neighborhoods"
        ReasonCode.OUTSIDE_PREFERRED_LOCATION -> "Outside the buyer's preferred location"
        ReasonCode.NO_LOCATION_PREFERENCE -> "Buyer has no recorded location preference"
        ReasonCode.AREA_FITS -> "Meets area requirement (${n(r, 0)} m²)"
        ReasonCode.AREA_BELOW_MIN -> "${n(r, 0)} m² is below the minimum ${n(r, 1)} m²"
        ReasonCode.AREA_ABOVE_MAX -> "${n(r, 0)} m² is above the maximum ${n(r, 1)} m²"
        ReasonCode.BEDROOMS_FIT -> "${n(r, 0)} bedrooms as required"
        ReasonCode.FEWER_BEDROOMS -> "${n(r, 0)} bedrooms, fewer than ${n(r, 1)}"
        ReasonCode.MORE_BEDROOMS -> "${n(r, 0)} bedrooms, more than ${n(r, 1)}"
        ReasonCode.FLOOR_FITS -> "Floor ${n(r, 0)} as preferred"
        ReasonCode.FLOOR_OUTSIDE_RANGE -> "Floor ${n(r, 0)} outside the preferred range"
        ReasonCode.FEATURE_SATISFIED -> "${feature(r)} satisfied"
        ReasonCode.FEATURE_MISSING -> "No ${feature(r)} (preferred)"
        ReasonCode.REQUIRED_FEATURE_UNKNOWN -> "${feature(r)} (required) not recorded for the property"
        ReasonCode.NO_FEATURE_REQUIREMENTS -> "No specific feature requirements"
        ReasonCode.URGENT_BUYER -> "Buyer plans to transact within ${n(r, 0)} days"
        ReasonCode.FITS_SELLER_DEADLINE -> "Buyer's timeline fits the seller's deadline"
        ReasonCode.BUYER_LATER_THAN_DEADLINE -> "Buyer's timeline is later than the seller's deadline"
        ReasonCode.NEEDS_TO_SELL_FIRST -> "Buyer must sell another property first"
        ReasonCode.SLOW_TIMELINE -> "Long timeline (about ${n(r, 0)} days)"
        ReasonCode.BUYER_FLEXIBLE -> "Buyer is flexible in negotiation"
        ReasonCode.SELLER_FLEXIBLE -> "Seller is flexible on price"
        ReasonCode.PROPERTY_NEGOTIABLE -> "Price is negotiable"
        ReasonCode.BUYER_INFLEXIBLE -> "Buyer is not flexible"
        ReasonCode.SELLER_INFLEXIBLE -> "Seller is not flexible on price"
        ReasonCode.NO_FLEXIBILITY_INFO -> "No flexibility information recorded"
        ReasonCode.CONFIRMED_INTEREST -> "Buyer confirmed active interest"
        ReasonCode.REQUESTED_VIEWING -> "Buyer requested a viewing"
        ReasonCode.SUBMITTED_OFFER -> "Buyer has submitted an offer"
        ReasonCode.HOT_LEAD -> "Agent assessment: hot lead"
        ReasonCode.COLD_LEAD -> "Agent assessment: cold lead"
        ReasonCode.UNRESPONSIVE -> "${n(r, 0)} recent follow-ups unanswered"
        ReasonCode.BACKUP_CANDIDATE -> "Property is ${enumKey<PropertyStatus>(r)}; backup buyer"
    }
}
