package ir.melkcrm.app.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.properties.priceSummary
import ir.melkcrm.client.data.PropertyRepository
import ir.melkcrm.core.model.GeoPoint
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.RequirementStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MapViewModel(private val app: AppContainer) : ViewModel() {
    data class Result(val nearby: List<PropertyRepository.Nearby> = emptyList(), val withoutCoordinates: Int = 0)

    val center = MutableStateFlow(TEHRAN)
    val radiusKm = MutableStateFlow(2.0)
    val includeClosed = MutableStateFlow(false)
    val showBuyerAreas = MutableStateFlow(false)

    private val started = SharingStarted.WhileSubscribed(5_000)

    val result: StateFlow<Result> = combine(app.crm.properties.properties, center, radiusKm, includeClosed) { _, c, r, closed -> Triple(c, r, closed) }
        .mapLatest { (c, r, closed) -> app.crm.properties.nearby(c, r, closed).let { Result(it.first, it.second) } }
        .stateIn(viewModelScope, started, Result())

    /** Every property with coordinates, for the markers. */
    val located: StateFlow<List<Property>> = combine(app.crm.properties.properties, includeClosed) { l, closed ->
        l.filter { it.location != null && (closed || !it.status.isClosed) }
    }.stateIn(viewModelScope, started, emptyList())

    /** Saved map areas of active buyer requirements. */
    val buyerAreas: StateFlow<List<MapCircle>> = app.crm.customers.requirements.map { l ->
        l.filter { it.status == RequirementStatus.ACTIVE }.flatMap { r -> r.mapAreas.mapNotNull { a -> a.center?.let { c -> MapCircle(c, a.radiusKm ?: 1.0) } } }
    }.stateIn(viewModelScope, started, emptyList())

    fun useDevice() = viewModelScope.launch {
        val loc = deviceLocation(app.context)
        if (loc == null) app.say(Msg.Res(R.string.map_location_unavailable)) else center.value = loc.point
    }
}

private val radii = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0)

@Composable
fun MapScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val context = LocalContext.current
    val vm = viewModel { MapViewModel(app) }
    val center by vm.center.collectAsStateWithLifecycle()
    val radius by vm.radiusKm.collectAsStateWithLifecycle()
    val includeClosed by vm.includeClosed.collectAsStateWithLifecycle()
    val showAreas by vm.showBuyerAreas.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val located by vm.located.collectAsStateWithLifecycle()
    val areas by vm.buyerAreas.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.useDevice() else app.say(Msg.Res(R.string.map_location_unavailable))
    }

    ScreenScaffold(
        title = stringResource(R.string.map_title),
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = {
                if (hasLocationPermission(context)) vm.useDevice()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Icon(Icons.Filled.MyLocation, stringResource(R.string.map_use_location)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(
                    label = stringResource(R.string.map_radius_km), options = radii, selected = radius,
                    onSelect = { r -> if (r != null) vm.radiusKm.value = r }, optionLabel = { fmt.num(it) + " " + stringResource(R.string.unit_km) },
                    allowNone = false, modifier = Modifier.widthIn(max = 160.dp),
                )
                FilterChip(selected = includeClosed, onClick = { vm.includeClosed.value = !includeClosed }, label = { Text(stringResource(R.string.show_closed)) }, modifier = Modifier.padding(top = 12.dp))
                FilterChip(selected = showAreas, onClick = { vm.showBuyerAreas.value = !showAreas }, label = { Text(stringResource(R.string.map_show_buyer_areas)) }, modifier = Modifier.padding(top = 12.dp))
            }
            Text(
                stringResource(R.string.map_tap_to_search) + " · " + stringResource(R.string.map_tiles_note, hostOf(fmt.settings.tileUrl)),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
            )
            val markers = located.map { p ->
                val loc = p.location!!
                MapMarker(p.id, loc.point, "${p.reference} · ${p.title}", priceSummary(p, fmt))
            }
            val circles = listOf(MapCircle(center, radius)) + if (showAreas) areas else emptyList()
            val map = @Composable { m: Modifier ->
                CrmMap(
                    center = center, zoom = 13.0, markers = markers, circles = circles, modifier = m,
                    onTap = { p: GeoPoint -> vm.center.value = p }, onMarkerClick = { id -> nav.go(Routes.property(id)) },
                )
            }
            if (wide) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                    map(Modifier.weight(0.6f).fillMaxSize())
                    NearbyList(result, nav, Modifier.weight(0.4f))
                }
            } else {
                map(Modifier.fillMaxWidth().weight(0.55f))
                NearbyList(result, nav, Modifier.weight(0.45f))
            }
        }
    }
}

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').ifBlank { url }

@Composable
private fun NearbyList(result: MapViewModel.Result, nav: Nav, modifier: Modifier) {
    val fmt = LocalFormat.current
    LazyColumn(modifier.fillMaxWidth()) {
        if (result.withoutCoordinates > 0) {
            item {
                Text(
                    stringResource(R.string.map_missing_coords, result.withoutCoordinates), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (result.nearby.isEmpty()) item { Text(stringResource(R.string.no_results), modifier = Modifier.padding(16.dp)) }
        items(result.nearby, key = { it.property.id }) { n ->
            val p = n.property
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.property(p.id)) },
                overlineContent = { Text(p.reference + " · " + fmt.label(p.status)) },
                headlineContent = { Text(p.title) },
                supportingContent = { Text(priceSummary(p, fmt) + "\n" + stringResource(R.string.map_within_km, fmt.num(Math.round(n.distanceKm * 10) / 10.0))) },
            )
            HorizontalDivider()
        }
    }
}
