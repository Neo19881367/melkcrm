package ir.melkcrm.backend

// Request and response classes live in core (ir.melkcrm.core.api) so the app uses the same wire format.

class ApiException(val status: Int, val code: String, val detail: String? = null) : RuntimeException(code)
