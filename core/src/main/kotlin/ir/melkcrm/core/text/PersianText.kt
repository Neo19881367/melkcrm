package ir.melkcrm.core.text

/** Digit and text helpers for mixed Persian/English input. */
object Digits {
    private const val PERSIAN = "۰۱۲۳۴۵۶۷۸۹"
    private const val ARABIC = "٠١٢٣٤٥٦٧٨٩"

    /** Converts ASCII digits to Persian digits; other characters are kept. */
    fun toPersian(input: String): String = buildString(input.length) {
        for (c in input) append(if (c in '0'..'9') PERSIAN[c - '0'] else c)
    }

    /** Converts Persian and Arabic-Indic digits to ASCII digits. */
    fun toAscii(input: String): String = buildString(input.length) {
        for (c in input) {
            val p = PERSIAN.indexOf(c)
            val a = ARABIC.indexOf(c)
            append(
                when {
                    p >= 0 -> '0' + p
                    a >= 0 -> '0' + a
                    else -> c
                },
            )
        }
    }

    fun localize(input: String, persian: Boolean): String = if (persian) toPersian(input) else input
}

object PersianText {
    private const val ZWNJ = '‌'

    /**
     * Normalizes text for search and comparison: Arabic yeh/kaf to Persian forms,
     * digits to ASCII, removes diacritics and tatweel, collapses whitespace and ZWNJ,
     * lowercases Latin characters.
     */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (raw in Digits.toAscii(input)) {
            val c = when (raw) {
                'ي', 'ى' -> 'ی'
                'ك' -> 'ک'
                'ة' -> 'ه'
                'أ', 'إ', 'ٱ' -> 'ا'
                'ؤ' -> 'و'
                ZWNJ, ' ', '\t', '\n' -> ' '
                else -> raw
            }
            if (c == 'ـ' || c in 'ً'..'ٟ' || c == 'ٰ') continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** Equality on normalized text, ignoring spaces (e.g. "سعادت آباد" == "سعادت‌آباد"). */
    fun sameName(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return normalize(a).replace(" ", "") == normalize(b).replace(" ", "")
    }
}
