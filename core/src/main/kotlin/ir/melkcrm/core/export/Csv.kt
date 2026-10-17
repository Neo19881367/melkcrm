package ir.melkcrm.core.export

/** Minimal RFC 4180 CSV writer. Output starts with a UTF-8 BOM so Excel shows Persian text correctly. */
object Csv {
    const val BOM = "﻿"

    fun escape(value: String?): String {
        if (value == null) return ""
        // Neutralize spreadsheet formula injection.
        val v = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toDoubleOrNull() == null) "'$value" else value
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun write(header: List<String>, rows: List<List<String?>>, withBom: Boolean = true): String = buildString {
        if (withBom) append(BOM)
        append(header.joinToString(",") { escape(it) }).append("\r\n")
        rows.forEach { row -> append(row.joinToString(",") { escape(it) }).append("\r\n") }
    }
}
