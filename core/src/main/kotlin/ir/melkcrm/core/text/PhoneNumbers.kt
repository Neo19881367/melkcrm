package ir.melkcrm.core.text

/** Iranian phone number normalization. Canonical mobile form: 09XXXXXXXXX (11 digits). */
object PhoneNumbers {
    private val nonDigits = Regex("[^0-9+]")

    /**
     * Returns the canonical form of a phone number, or null when it cannot be a valid Iranian number.
     * Mobile numbers become 09XXXXXXXXX; landlines become 0 + area code + number (e.g. 02122334455).
     */
    fun normalize(input: String?): String? {
        if (input.isNullOrBlank()) return null
        var s = Digits.toAscii(input).replace(nonDigits, "")
        when {
            s.startsWith("+98") -> s = "0" + s.removePrefix("+98")
            s.startsWith("0098") -> s = "0" + s.removePrefix("0098")
            s.startsWith("98") && s.length == 12 -> s = "0" + s.removePrefix("98")
            s.startsWith("9") && s.length == 10 -> s = "0$s"
        }
        if (s.contains('+')) return null
        return if (isValidCanonical(s)) s else null
    }

    fun isMobile(canonical: String): Boolean = canonical.length == 11 && canonical.startsWith("09")

    private fun isValidCanonical(s: String): Boolean =
        s.length == 11 && s.startsWith("0") && s[1] != '0' && s.all { it.isDigit() }

    /** 0912 345 6789 */
    fun format(canonical: String, persianDigits: Boolean): String {
        val f = if (canonical.length == 11) {
            if (isMobile(canonical)) "${canonical.substring(0, 4)} ${canonical.substring(4, 7)} ${canonical.substring(7)}"
            else "${canonical.substring(0, 3)} ${canonical.substring(3)}"
        } else canonical
        return Digits.localize(f, persianDigits)
    }

    /** Masks the middle digits, for agents without contact permission: 0912***6789 */
    fun mask(canonical: String): String =
        if (canonical.length >= 8) canonical.take(4) + "*".repeat(canonical.length - 8) + canonical.takeLast(4) else "****"
}
