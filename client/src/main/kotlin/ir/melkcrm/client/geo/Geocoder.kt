package ir.melkcrm.client.geo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import ir.melkcrm.core.model.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * An address-search result. Results are approximate: the app stores a location from a result as
 * [ir.melkcrm.core.model.LocationSource.GEOCODED] with no accuracy claim, and asks the agent to
 * confirm the pin before saving.
 */
data class GeocodeResult(
    val title: String,
    val address: String?,
    val point: GeoPoint,
    val city: String? = null,
    val neighborhood: String? = null,
)

/** Map-provider independent address search. */
interface Geocoder {
    /** False when the provider is not configured (for example no API key); the UI then offers manual pin placement only. */
    val available: Boolean
    suspend fun search(term: String, near: GeoPoint): List<GeocodeResult>
    suspend fun reverse(point: GeoPoint): GeocodeResult?
}

class GeocodingException(val code: String) : Exception(code)

object NoGeocoder : Geocoder {
    override val available = false
    override suspend fun search(term: String, near: GeoPoint) = emptyList<GeocodeResult>()
    override suspend fun reverse(point: GeoPoint): GeocodeResult? = null
}

/**
 * Neshan (نشان) search and reverse geocoding, an Iranian provider that works for users inside Iran.
 * Uses the agency's own *service* key, entered in Settings and sent in the `Api-Key` header; no key is
 * shipped with the app.
 *
 * The request paths ([searchPath], [reversePath]) and response field names follow Neshan's public
 * documentation as understood at the time of writing; they could not be tested from the build
 * environment and must be verified against https://platform.neshan.org before production use.
 * They are constructor parameters so they can be corrected without code changes elsewhere.
 */
class NeshanGeocoder(
    private val http: HttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://api.neshan.org",
    private val searchPath: String = "/v1/search",
    private val reversePath: String = "/v5/reverse",
) : Geocoder {
    private val json = Json { ignoreUnknownKeys = true }

    override val available: Boolean get() = !apiKey().isNullOrBlank()

    private suspend fun getJson(url: String): JsonObject {
        val key = apiKey()?.trim()
        if (key.isNullOrEmpty()) throw GeocodingException("no_api_key")
        val res = try {
            http.get(url) { header("Api-Key", key) }
        } catch (e: Exception) {
            throw GeocodingException("network")
        }
        if (!res.status.isSuccess()) throw GeocodingException("http_${res.status.value}")
        return json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    override suspend fun search(term: String, near: GeoPoint): List<GeocodeResult> {
        if (term.isBlank()) return emptyList()
        val url = "$baseUrl$searchPath?term=${term.trim().encodeURLParameter()}&lat=${near.lat}&lng=${near.lng}"
        val items = getJson(url)["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val loc = o["location"] as? JsonObject ?: return@mapNotNull null
            val x = (loc["x"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val y = (loc["y"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val point = runCatching { GeoPoint(y, x) }.getOrNull() ?: return@mapNotNull null
            GeocodeResult(
                title = o.str("title") ?: o.str("address") ?: return@mapNotNull null,
                address = o.str("address"),
                point = point,
                city = o.str("region")?.split(',', '،')?.lastOrNull()?.trim(),
                neighborhood = o.str("neighbourhood"),
            )
        }
    }

    override suspend fun reverse(point: GeoPoint): GeocodeResult? {
        val o = getJson("$baseUrl$reversePath?lat=${point.lat}&lng=${point.lng}")
        if (o.str("status")?.uppercase()?.let { it != "OK" } == true) return null
        val address = o.str("formatted_address") ?: return null
        return GeocodeResult(address, address, point, o.str("city"), o.str("neighbourhood"))
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
