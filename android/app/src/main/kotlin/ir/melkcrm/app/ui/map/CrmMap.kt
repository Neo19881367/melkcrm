package ir.melkcrm.app.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.core.model.GeoPoint
import ir.melkcrm.core.model.Location
import ir.melkcrm.core.model.LocationSource
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.util.GeoPoint as OsmPoint

/*
 * The map layer. Screens describe what to show with [MapMarker] and [MapCircle] and receive taps as
 * core GeoPoints; only this file knows the map SDK. It currently uses osmdroid with a configurable
 * raster tile server (Settings → Map), which works with tile servers reachable from Iran, including
 * an agency's own. Replacing the SDK (for example with the Neshan Android SDK) means reimplementing
 * [CrmMap] here; nothing else changes.
 */

val TEHRAN = GeoPoint(35.6892, 51.3890)

data class MapMarker(val id: String, val point: GeoPoint, val title: String, val snippet: String? = null)

data class MapCircle(val center: GeoPoint, val radiusKm: Double)

private fun GeoPoint.osm() = OsmPoint(lat, lng)
private fun OsmPoint.core(): GeoPoint? = runCatching { GeoPoint(latitude, longitude) }.getOrNull()

/** Raster tiles from a URL template with {z}, {x}, {y} and optionally {s} (a, b, c). */
private class TemplateTileSource(private val template: String) :
    OnlineTileSourceBase("crm-" + template.hashCode(), 3, 19, 256, ".png", arrayOf(template)) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        template.replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
            .replace("{s}", listOf("a", "b", "c")[(MapTileIndex.getX(pMapTileIndex) + MapTileIndex.getY(pMapTileIndex)) % 3])
}

/** Applies the tile settings: the user agent tile servers require and an optional "Name: value" header. */
private fun configure(context: Context, header: String) {
    val conf = Configuration.getInstance()
    conf.userAgentValue = context.packageName
    conf.osmdroidBasePath = context.cacheDir.resolve("osmdroid")
    conf.osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
    val props = conf.additionalHttpRequestProperties
    props.clear()
    val name = header.substringBefore(':').trim()
    val value = header.substringAfter(':', "").trim()
    if (name.isNotEmpty() && value.isNotEmpty()) props[name] = value
}

private class MapHolder {
    var appliedCenter: GeoPoint? = null
    var appliedTiles: String? = null
    var events: MapEventsOverlay? = null
}

/**
 * A map showing [markers] and [circles]. The view is centred on [center] when it is created and
 * whenever the caller passes a different centre (after a search, for example).
 */
@Composable
fun CrmMap(
    center: GeoPoint,
    zoom: Double,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    circles: List<MapCircle> = emptyList(),
    onTap: ((GeoPoint) -> Unit)? = null,
    onMarkerClick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = LocalFormat.current.settings
    val holder = remember { MapHolder() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        configure(context, settings.tileHeader)
        MapView(context).apply {
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            setMinZoomLevel(4.0)
            setMaxZoomLevel(19.0)
        }
    }
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        val tiles = settings.tileUrl + "|" + settings.tileHeader
        if (holder.appliedTiles != tiles) {
            configure(mv.context, settings.tileHeader)
            mv.setTileSource(TemplateTileSource(settings.tileUrl))
            holder.appliedTiles = tiles
        }
        if (holder.appliedCenter != center) {
            mv.controller.setZoom(zoom)
            mv.controller.setCenter(center.osm())
            holder.appliedCenter = center
        }
        mv.overlays.clear()
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsmPoint?): Boolean {
                val point = p?.core() ?: return false
                onTap?.invoke(point) ?: return false
                return true
            }

            override fun longPressHelper(p: OsmPoint?): Boolean = false
        })
        holder.events = events
        mv.overlays.add(events)
        for (c in circles) {
            mv.overlays.add(Polygon(mv).apply {
                setPoints(Polygon.pointsAsCircle(c.center.osm(), c.radiusKm * 1000.0))
                fillPaint.color = Color.argb(40, 11, 92, 85)
                outlinePaint.color = Color.argb(200, 11, 92, 85)
                outlinePaint.strokeWidth = 3f
                setOnClickListener { _, _, _ -> false }
            })
        }
        for (m in markers) {
            mv.overlays.add(Marker(mv).apply {
                position = m.point.osm()
                title = m.title
                snippet = m.snippet
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { marker, _ ->
                    if (onMarkerClick != null) onMarkerClick(m.id) else marker.showInfoWindow()
                    true
                }
            })
        }
        mv.invalidate()
    })
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * The device's position with the accuracy Android reports, stored as DEVICE_GPS. Null when location is
 * off or unavailable; nothing is guessed.
 */
@SuppressLint("MissingPermission")
suspend fun deviceLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return null
    val fix: android.location.Location? = if (Build.VERSION.SDK_INT >= 30) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                lm.getCurrentLocation(providers.first(), null, ContextCompat.getMainExecutor(context)) { loc -> if (cont.isActive) cont.resume(loc) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    } else {
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
    val f = fix ?: return null
    val point = runCatching { GeoPoint(f.latitude, f.longitude) }.getOrNull() ?: return null
    return Location(point, LocationSource.DEVICE_GPS, if (f.hasAccuracy()) f.accuracy.toDouble() else null)
}
