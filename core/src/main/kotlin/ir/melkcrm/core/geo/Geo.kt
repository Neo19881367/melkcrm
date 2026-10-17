package ir.melkcrm.core.geo

import ir.melkcrm.core.model.GeoArea
import ir.melkcrm.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088

    /** Great-circle distance in kilometers. */
    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(h)))
    }

    /** Ray-casting point-in-polygon on lat/lng (adequate for city-scale areas). */
    fun inPolygon(p: GeoPoint, polygon: List<GeoPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance from a point to a segment, using a local equirectangular projection (km). */
    private fun distanceToSegmentKm(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111.320 * cos(Math.toRadians(p.lat))
        val ky = 110.574
        val ax = (a.lng - p.lng) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lng - p.lng) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /**
     * Distance in km from [p] to the area: 0 when inside, otherwise distance to the boundary.
     * Returns null when the area has no usable geometry.
     */
    fun distanceToAreaKm(p: GeoPoint, area: GeoArea): Double? {
        if (area.polygon.size >= 3) {
            if (inPolygon(p, area.polygon)) return 0.0
            var best = Double.MAX_VALUE
            for (i in area.polygon.indices) {
                val a = area.polygon[i]
                val b = area.polygon[(i + 1) % area.polygon.size]
                best = min(best, distanceToSegmentKm(p, a, b))
            }
            return best
        }
        val c = area.center ?: return null
        val r = area.radiusKm ?: return null
        return (distanceKm(p, c) - r).coerceAtLeast(0.0)
    }

    /** Rough bounding box of Iran, used only to warn about likely-wrong coordinates. */
    fun isLikelyInIran(p: GeoPoint): Boolean = p.lat in 24.8..39.9 && p.lng in 44.0..63.4
}
