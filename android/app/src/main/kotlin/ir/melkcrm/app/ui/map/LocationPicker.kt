package ir.melkcrm.app.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.client.geo.GeocodeResult
import ir.melkcrm.client.geo.GeocodingException
import ir.melkcrm.core.model.Location
import ir.melkcrm.core.model.LocationSource
import kotlinx.coroutines.launch

/** "Source: pin on map · ±12 m" — accuracy is shown only when it was actually measured. */
@Composable
fun locationSourceText(l: Location): String {
    val fmt = LocalFormat.current
    val accuracy = l.accuracyMeters?.let { " · ±" + fmt.num(Math.round(it)) + (if (fmt.fa) " متر" else " m") } ?: ""
    return stringResource(R.string.property_location_source, fmt.label(l.source)) + accuracy
}

/**
 * Picks a point: tap the map (stored as a manual pin), choose a search result (stored as geocoded,
 * with no accuracy claimed) or use the device position (stored with the accuracy Android reports).
 */
@Composable
fun LocationPickDialog(initial: Location?, title: String, onDismiss: () -> Unit, onPick: (Location) -> Unit) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(initial) }
    var center by remember { mutableStateOf(initial?.point ?: TEHRAN) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun useDevice() = scope.launch {
        busy = true
        val loc = deviceLocation(context)
        busy = false
        if (loc == null) {
            app.say(Msg.Res(R.string.map_location_unavailable))
        } else {
            picked = loc
            center = loc.point
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) useDevice() else app.say(Msg.Res(R.string.map_location_unavailable))
    }

    fun search() = scope.launch {
        if (query.isBlank()) return@launch
        busy = true
        results = try {
            app.geocoder.search(query, center).take(6)
        } catch (e: GeocodingException) {
            app.say(Msg.Code("geo_", e.code, R.string.map_error))
            emptyList()
        }
        busy = false
        if (results.isEmpty()) app.say(Msg.Res(R.string.no_results))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                if (app.geocoder.available) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.map_search_hint)) },
                        )
                        IconButton(onClick = { search() }, enabled = !busy) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                    }
                    for (r in results) {
                        ListItem(
                            headlineContent = { Text(r.title) },
                            supportingContent = if (r.address != null) {
                                { Text(r.address.orEmpty()) }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable {
                                picked = Location(r.point, LocationSource.GEOCODED, null)
                                center = r.point
                                results = emptyList()
                            },
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.map_geocoding_unavailable), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                CrmMap(
                    center = center,
                    zoom = if (initial != null) 16.0 else 12.0,
                    markers = listOfNotNull(picked?.let { MapMarker("picked", it.point, title) }),
                    onTap = { p -> picked = Location(p, LocationSource.MANUAL_PIN, null) },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                )
                Column(Modifier.padding(16.dp)) {
                    val p = picked
                    Text(
                        if (p == null) stringResource(R.string.map_tap_to_place)
                        else fmt.num(Math.round(p.point.lat * 1e5) / 1e5) + ", " + fmt.num(Math.round(p.point.lng * 1e5) / 1e5) + "\n" + locationSourceText(p),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (p?.source == LocationSource.GEOCODED) {
                        Text(stringResource(R.string.map_geocode_result_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (hasLocationPermission(context)) useDevice()
                            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, enabled = !busy) {
                            Icon(Icons.Filled.MyLocation, null)
                            Text(" " + stringResource(R.string.map_use_location))
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = { picked?.let(onPick) }, enabled = picked != null) { Text(stringResource(R.string.confirm)) }
                    }
                }
            }
        }
    }
}
