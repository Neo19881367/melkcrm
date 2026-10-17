package ir.melkcrm.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvTest {
    @Test fun escapingAndBom() {
        val csv = Csv.write(listOf("نام", "یادداشت"), listOf(listOf("علی", "سلام, \"دوست\"\nخط دوم"), listOf("=cmd()", null)))
        assertTrue(csv.startsWith(Csv.BOM))
        assertEquals("نام,یادداشت\r\nعلی,\"سلام, \"\"دوست\"\"\nخط دوم\"\r\n'=cmd(),\r\n", csv.removePrefix(Csv.BOM))
        assertEquals("-5", Csv.escape("-5"))
    }
}
