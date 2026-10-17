package ir.melkcrm.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextTest {
    @Test fun phoneNormalization() {
        assertEquals("09121234567", PhoneNumbers.normalize("09121234567"))
        assertEquals("09121234567", PhoneNumbers.normalize("+98 912 123 4567"))
        assertEquals("09121234567", PhoneNumbers.normalize("0098-912-123-4567"))
        assertEquals("09121234567", PhoneNumbers.normalize("۰۹۱۲۱۲۳۴۵۶۷"))
        assertEquals("09121234567", PhoneNumbers.normalize("9121234567"))
        assertEquals("02122334455", PhoneNumbers.normalize("021-22334455"))
        assertNull(PhoneNumbers.normalize("12345"))
        assertNull(PhoneNumbers.normalize("0012121212121"))
        assertNull(PhoneNumbers.normalize(""))
    }

    @Test fun phoneFormattingAndMasking() {
        assertEquals("۰۹۱۲ ۱۲۳ ۴۵۶۷", PhoneNumbers.format("09121234567", persianDigits = true))
        assertEquals("0912***4567", PhoneNumbers.mask("09121234567"))
    }

    @Test fun persianNormalization() {
        assertTrue(PersianText.sameName("سعادت‌آباد", "سعادت آباد"))
        assertTrue(PersianText.sameName("علي كريمي", "علی کریمی"))
        assertEquals("tehran 12", PersianText.normalize("  Tehran   ۱۲ "))
    }
}
