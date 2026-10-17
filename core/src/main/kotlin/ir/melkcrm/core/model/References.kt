package ir.melkcrm.core.model

object PropertyReference {
    private val regex = Regex("^IR-(\\d{6,})$")
    fun format(sequence: Long): String = "IR-%06d".format(sequence)
    fun parse(ref: String): Long? = regex.find(ref.trim().uppercase())?.groupValues?.get(1)?.toLongOrNull()
    fun next(existing: Collection<String>): String = format((existing.mapNotNull { parse(it) }.maxOrNull() ?: 0L) + 1)
}
