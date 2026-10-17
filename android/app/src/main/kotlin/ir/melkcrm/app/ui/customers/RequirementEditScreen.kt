package ir.melkcrm.app.ui.customers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EditorScaffold
import ir.melkcrm.app.ui.components.EditorViewModel
import ir.melkcrm.app.ui.components.EnumChips
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.LevelInput
import ir.melkcrm.app.ui.components.MoneyInput
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.NumberInput
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SwitchRow
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.TriStateInput
import ir.melkcrm.app.ui.errorText
import ir.melkcrm.app.ui.map.LocationPickDialog
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.RequirementForm
import ir.melkcrm.core.model.Amenity
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.DocumentStatus
import ir.melkcrm.core.model.Facing
import ir.melkcrm.core.model.FinancingType
import ir.melkcrm.core.model.FlexibilityLevel
import ir.melkcrm.core.model.FurnishedStatus
import ir.melkcrm.core.model.GeoArea
import ir.melkcrm.core.model.LeadQuality
import ir.melkcrm.core.model.PropertyCategory
import ir.melkcrm.core.model.PropertyCondition
import ir.melkcrm.core.model.PurchaseReason
import ir.melkcrm.core.model.RequirementLevel
import ir.melkcrm.core.model.RequirementStatus
import ir.melkcrm.core.model.Urgency
import ir.melkcrm.core.time.AppLanguage

class RequirementEditViewModel(app: AppContainer, private val customerId: String, private val id: String?) :
    EditorViewModel<BuyerRequirement, RequirementForm>(app) {
    val isNew = id == null

    override suspend fun load(): BuyerRequirement? {
        if (crm.customers.customer(customerId) == null) return null
        return if (id == null) crm.customers.newRequirement(customerId) else crm.customers.requirement(id)?.takeIf { it.customerId == customerId }
    }

    override fun formOf(e: BuyerRequirement) = RequirementForm.from(e, settings.currency, settings.persianDigits, settings.language == AppLanguage.PERSIAN)
    override fun build(form: RequirementForm, base: BuyerRequirement): FormResult<BuyerRequirement> = form.build(base, settings.currency)
    override suspend fun save(e: BuyerRequirement, confirmed: Boolean): SaveOutcome<BuyerRequirement> = crm.customers.saveRequirement(e)

    init {
        start()
    }
}

private val radiusChoices = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0)
private val listedFeatures = setOf(Amenity.PARKING, Amenity.ELEVATOR, Amenity.STORAGE)

@Composable
fun RequirementEditScreen(customerId: String, id: String?, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "req-$customerId-$id") { RequirementEditViewModel(app, customerId, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    var pickArea by remember { mutableStateOf(false) }

    LaunchedEffect(state.done) {
        val saved = state.done ?: return@LaunchedEffect
        // A new requirement goes straight to its matching properties.
        if (vm.isNew) nav.replace(Routes.propertiesFor(saved.id)) else nav.back()
    }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.req_new else R.string.req_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        val sale = f.dealType == DealType.SALE

        SectionCard(null) {
            TextInput(f.title, { v -> vm.edit { it.copy(title = v) } }, stringResource(R.string.req_title), supporting = stringResource(R.string.optional))
            EnumDropdown(stringResource(R.string.req_deal_type), DealType.entries, f.dealType, { v -> if (v != null) vm.edit { it.copy(dealType = v) } }, allowNone = false)
            EnumChips(stringResource(R.string.req_categories), PropertyCategory.entries, f.categories, { v -> vm.edit { it.copy(categories = v) } }, error = err["categories"])
            EnumDropdown(stringResource(R.string.req_status), RequirementStatus.entries, f.status, { v -> if (v != null) vm.edit { it.copy(status = v) } }, allowNone = false)
        }

        SectionCard(stringResource(if (sale) R.string.req_budget_section else R.string.req_rent_section)) {
            if (sale) {
                MoneyInput(f.minBudget, { v -> vm.edit { it.copy(minBudget = v) } }, stringResource(R.string.req_min_budget), error = err["minBudget"])
                MoneyInput(f.maxBudget, { v -> vm.edit { it.copy(maxBudget = v) } }, stringResource(R.string.req_max_budget), error = err["maxBudget"])
                MoneyInput(f.preferredBudget, { v -> vm.edit { it.copy(preferredBudget = v) } }, stringResource(R.string.req_preferred_budget), error = err["preferredBudget"])
                TriStateInput(stringResource(R.string.req_budget_flexible), f.budgetFlexible, { v -> vm.edit { it.copy(budgetFlexible = v) } })
                if (f.budgetFlexible == true) {
                    MoneyInput(f.maxStretch, { v -> vm.edit { it.copy(maxStretch = v) } }, stringResource(R.string.req_max_stretch), error = err["maxStretch"])
                }
                TriStateInput(stringResource(R.string.req_cash_buyer), f.cashBuyer, { v -> vm.edit { it.copy(cashBuyer = v) } })
            } else {
                Text(stringResource(R.string.req_rent_note), style = MaterialTheme.typography.bodySmall)
                MoneyInput(f.availableDeposit, { v -> vm.edit { it.copy(availableDeposit = v) } }, stringResource(R.string.req_available_deposit), error = err["availableDeposit"])
                MoneyInput(f.maxDeposit, { v -> vm.edit { it.copy(maxDeposit = v) } }, stringResource(R.string.req_max_deposit), error = err["maxDeposit"])
                if (f.dealType == DealType.RENT) {
                    MoneyInput(f.maxMonthlyRent, { v -> vm.edit { it.copy(maxMonthlyRent = v) } }, stringResource(R.string.req_max_rent), error = err["maxMonthlyRent"])
                }
            }
            EnumDropdown(stringResource(R.string.req_financing), FinancingType.entries, f.financing, { v -> if (v != null) vm.edit { it.copy(financing = v) } }, allowNone = false)
            // Never treat a missing budget as unlimited: matching marks such buyers as provisional.
            Text(stringResource(R.string.req_budget_missing_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.req_location_section)) {
            TextInput(f.provinces, { v -> vm.edit { it.copy(provinces = v) } }, stringResource(R.string.req_provinces), supporting = stringResource(R.string.list_hint_comma))
            TextInput(f.cities, { v -> vm.edit { it.copy(cities = v) } }, stringResource(R.string.req_cities), error = err["cities"])
            TextInput(f.neighborhoods, { v -> vm.edit { it.copy(neighborhoods = v) } }, stringResource(R.string.req_neighborhoods))
            TextInput(f.alternativeNeighborhoods, { v -> vm.edit { it.copy(alternativeNeighborhoods = v) } }, stringResource(R.string.req_alt_neighborhoods))
            TextInput(f.excludedCities, { v -> vm.edit { it.copy(excludedCities = v) } }, stringResource(R.string.req_excluded_cities))
            TextInput(f.excludedNeighborhoods, { v -> vm.edit { it.copy(excludedNeighborhoods = v) } }, stringResource(R.string.req_excluded_neighborhoods), error = err["excludedNeighborhoods"])
            Text(stringResource(R.string.req_map_areas), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            f.mapAreas.forEachIndexed { i, area ->
                MapAreaRow(area, errorText(err["mapAreas[$i]"]), onChange = { a ->
                    vm.edit { it.copy(mapAreas = it.mapAreas.toMutableList().also { l -> l[i] = a }) }
                }, onRemove = { vm.edit { it.copy(mapAreas = it.mapAreas.filterIndexed { j, _ -> j != i }) } })
            }
            OutlinedButton(onClick = { pickArea = true }) { Text(stringResource(R.string.req_add_map_area)) }
            SwitchRow(stringResource(R.string.req_location_strict), f.locationStrict, { v -> vm.edit { it.copy(locationStrict = v) } }, supporting = stringResource(R.string.req_strict_hint))
        }

        SectionCard(stringResource(R.string.req_size_section)) {
            Row {
                NumberInput(f.minArea, { v -> vm.edit { it.copy(minArea = v) } }, stringResource(R.string.req_min_area), Modifier.weight(1f).padding(end = 4.dp), err["minArea"], decimal = true, suffix = stringResource(R.string.unit_m2))
                NumberInput(f.maxArea, { v -> vm.edit { it.copy(maxArea = v) } }, stringResource(R.string.req_max_area), Modifier.weight(1f).padding(start = 4.dp), err["maxArea"], decimal = true, suffix = stringResource(R.string.unit_m2))
            }
            SwitchRow(stringResource(R.string.req_area_strict), f.areaStrict, { v -> vm.edit { it.copy(areaStrict = v) } })
            Row {
                NumberInput(f.minBedrooms, { v -> vm.edit { it.copy(minBedrooms = v) } }, stringResource(R.string.req_min_bedrooms), Modifier.weight(1f).padding(end = 4.dp), err["minBedrooms"])
                NumberInput(f.maxBedrooms, { v -> vm.edit { it.copy(maxBedrooms = v) } }, stringResource(R.string.req_max_bedrooms), Modifier.weight(1f).padding(start = 4.dp), err["maxBedrooms"])
            }
            SwitchRow(stringResource(R.string.req_bedrooms_strict), f.bedroomsStrict, { v -> vm.edit { it.copy(bedroomsStrict = v) } })
            Row {
                NumberInput(f.minFloor, { v -> vm.edit { it.copy(minFloor = v) } }, stringResource(R.string.req_min_floor), Modifier.weight(1f).padding(end = 4.dp), err["minFloor"])
                NumberInput(f.maxFloor, { v -> vm.edit { it.copy(maxFloor = v) } }, stringResource(R.string.req_max_floor), Modifier.weight(1f).padding(start = 4.dp), err["maxFloor"])
            }
            NumberInput(f.maxBuildingAge, { v -> vm.edit { it.copy(maxBuildingAge = v) } }, stringResource(R.string.req_max_building_age), error = err["maxBuildingAgeYears"])
            LevelInput(stringResource(R.string.req_building_age_level), f.buildingAgeLevel) { v -> vm.edit { it.copy(buildingAgeLevel = v) } }
        }

        SectionCard(stringResource(R.string.req_features_section)) {
            LevelInput(stringResource(R.string.req_parking), f.parking) { v -> vm.edit { it.copy(parking = v) } }
            LevelInput(stringResource(R.string.req_elevator), f.elevator) { v -> vm.edit { it.copy(elevator = v) } }
            LevelInput(stringResource(R.string.req_storage), f.storage) { v -> vm.edit { it.copy(storage = v) } }
            LevelInput(stringResource(R.string.req_new_build), f.newBuild) { v -> vm.edit { it.copy(newBuild = v) } }
            Text(stringResource(R.string.req_other_amenities), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.req_amenity_tap_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in Amenity.entries.filter { it !in listedFeatures }) {
                    val level = f.otherAmenities[a] ?: RequirementLevel.NOT_IMPORTANT
                    // Tapping cycles: not important → preferred → required → not important.
                    val next = when (level) {
                        RequirementLevel.NOT_IMPORTANT -> RequirementLevel.PREFERRED
                        RequirementLevel.PREFERRED -> RequirementLevel.REQUIRED
                        RequirementLevel.REQUIRED -> RequirementLevel.NOT_IMPORTANT
                    }
                    FilterChip(
                        selected = level != RequirementLevel.NOT_IMPORTANT,
                        onClick = { vm.edit { it.copy(otherAmenities = it.otherAmenities + (a to next)) } },
                        label = { Text(fmt.label(a) + if (level == RequirementLevel.NOT_IMPORTANT) "" else " · " + fmt.label(level)) },
                    )
                }
            }
            EnumChips(stringResource(R.string.req_conditions), PropertyCondition.entries, f.acceptableConditions, { v -> vm.edit { it.copy(acceptableConditions = v) } })
            EnumChips(stringResource(R.string.req_documents), DocumentStatus.entries, f.acceptableDocuments, { v -> vm.edit { it.copy(acceptableDocuments = v) } })
            LevelInput(stringResource(R.string.req_document_level), f.documentLevel) { v -> vm.edit { it.copy(documentLevel = v) } }
            EnumChips(stringResource(R.string.req_facing), Facing.entries, f.preferredFacing, { v -> vm.edit { it.copy(preferredFacing = v) } })
            EnumDropdown(stringResource(R.string.req_furnished), FurnishedStatus.entries, f.furnished, { v -> vm.edit { it.copy(furnished = v) } })
        }

        SectionCard(stringResource(R.string.req_timeline_section)) {
            EnumDropdown(stringResource(R.string.req_urgency), Urgency.entries, f.urgency, { v -> if (v != null) vm.edit { it.copy(urgency = v) } }, allowNone = false)
            DateInput(f.expectedDate, { v -> vm.edit { it.copy(expectedDate = v) } }, stringResource(R.string.req_expected_date))
            EnumDropdown(stringResource(R.string.req_reason), PurchaseReason.entries, f.reason, { v -> vm.edit { it.copy(reason = v) } })
            TriStateInput(stringResource(R.string.req_needs_to_sell), f.needsToSellFirst, { v -> vm.edit { it.copy(needsToSellFirst = v) } })
            TextInput(f.preferredViewingTimes, { v -> vm.edit { it.copy(preferredViewingTimes = v) } }, stringResource(R.string.req_viewing_times))
            EnumDropdown(stringResource(R.string.req_negotiation_flex), FlexibilityLevel.entries, f.negotiationFlexibility, { v -> if (v != null) vm.edit { it.copy(negotiationFlexibility = v) } }, allowNone = false)
            EnumDropdown(stringResource(R.string.req_lead_quality), LeadQuality.entries, f.leadQuality, { v -> if (v != null) vm.edit { it.copy(leadQuality = v) } }, allowNone = false)
            MultilineInput(f.notes, { v -> vm.edit { it.copy(notes = v) } }, stringResource(R.string.notes))
        }
        if (state.errors.isNotEmpty()) Banner(stringResource(R.string.validation_fix), color = MaterialTheme.colorScheme.errorContainer)
    }

    if (pickArea) {
        LocationPickDialog(
            initial = null,
            title = stringResource(R.string.req_add_map_area),
            onDismiss = { pickArea = false },
            onPick = { loc ->
                pickArea = false
                vm.edit { it.copy(mapAreas = it.mapAreas + GeoArea(label = null, center = loc.point, radiusKm = 2.0)) }
            },
        )
    }
}

@Composable
private fun MapAreaRow(area: GeoArea, error: String?, onChange: (GeoArea) -> Unit, onRemove: () -> Unit) {
    val fmt = LocalFormat.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(area.label.orEmpty(), { v -> onChange(area.copy(label = v.ifBlank { null })) }, stringResource(R.string.req_area_label), Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
        Dropdown(
            label = stringResource(R.string.map_radius_km),
            options = (radiusChoices + listOfNotNull(area.radiusKm)).distinct().sorted(),
            selected = area.radiusKm,
            onSelect = { r -> if (r != null) onChange(area.copy(radiusKm = r)) },
            optionLabel = { fmt.num(it) + " " + stringResource(R.string.unit_km) },
            allowNone = false,
        )
        area.center?.let { c ->
            Text(fmt.num(Math.round(c.lat * 1e4) / 1e4) + ", " + fmt.num(Math.round(c.lng * 1e4) / 1e4), style = MaterialTheme.typography.bodySmall)
        }
        if (area.polygon.isNotEmpty()) Text(stringResource(R.string.req_polygon_kept), style = MaterialTheme.typography.bodySmall)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
