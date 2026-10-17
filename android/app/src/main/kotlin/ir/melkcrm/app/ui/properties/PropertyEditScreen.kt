package ir.melkcrm.app.ui.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.components.AgentPicker
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.ConfidentialCard
import ir.melkcrm.app.ui.components.CustomerPicker
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EditorScaffold
import ir.melkcrm.app.ui.components.EditorViewModel
import ir.melkcrm.app.ui.components.EnumChips
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.MoneyInput
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.NumberInput
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SwitchRow
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.TriStateInput
import ir.melkcrm.app.ui.errorText
import ir.melkcrm.app.ui.map.LocationPickDialog
import ir.melkcrm.app.ui.map.locationSourceText
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.Input
import ir.melkcrm.client.forms.Parsed
import ir.melkcrm.client.forms.PropertyForm
import ir.melkcrm.core.model.Amenity
import ir.melkcrm.core.model.CoolingType
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.DocumentStatus
import ir.melkcrm.core.model.Facing
import ir.melkcrm.core.model.FurnishedStatus
import ir.melkcrm.core.model.GeoPoint
import ir.melkcrm.core.model.HeatingType
import ir.melkcrm.core.model.Location
import ir.melkcrm.core.model.LocationSource
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.PropertyCategory
import ir.melkcrm.core.model.PropertyCondition
import ir.melkcrm.core.model.PropertyStatus
import ir.melkcrm.core.time.CalendarSystem

class PropertyEditViewModel(app: AppContainer, private val id: String?, private val ownerId: String?) : EditorViewModel<Property, PropertyForm>(app) {
    val isNew = id == null
    var canEditConfidential = false
        private set

    override suspend fun load(): Property? {
        val p = if (id == null) crm.properties.newProperty().copy(ownerCustomerId = ownerId) else crm.properties.property(id)
        if (p != null) canEditConfidential = crm.properties.canSeeConfidential(p)
        return p
    }

    override fun formOf(e: Property) = PropertyForm.from(e, settings.currency, settings.persianDigits)
    override fun build(form: PropertyForm, base: Property): FormResult<Property> = form.build(base, settings.currency)
    override suspend fun save(e: Property, confirmed: Boolean): SaveOutcome<Property> = crm.properties.save(e)

    init {
        start()
    }
}

/** Amenities with their own fields above are not repeated in the amenity chips. */
private val ownFields = setOf(Amenity.PARKING, Amenity.ELEVATOR, Amenity.STORAGE)

@Composable
fun PropertyEditScreen(id: String?, ownerId: String?, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "property-edit-$id-$ownerId") { PropertyEditViewModel(app, id, ownerId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }

    LaunchedEffect(state.done) {
        val saved = state.done ?: return@LaunchedEffect
        if (vm.isNew) nav.replace(Routes.property(saved.id)) else nav.back()
    }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.property_new else R.string.property_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        val sale = DealType.SALE in f.dealTypes
        val rent = DealType.RENT in f.dealTypes
        val full = DealType.FULL_DEPOSIT in f.dealTypes

        SectionCard(null) {
            TextInput(f.reference, { v -> vm.edit { it.copy(reference = v) } }, stringResource(R.string.property_reference), error = err["reference"])
            TextInput(f.title, { v -> vm.edit { it.copy(title = v) } }, stringResource(R.string.property_title_field), error = err["title"])
            EnumDropdown(stringResource(R.string.property_category), PropertyCategory.entries, f.category, { v -> if (v != null) vm.edit { it.copy(category = v) } }, allowNone = false)
            EnumChips(stringResource(R.string.property_deal_types), DealType.entries, f.dealTypes, { v -> vm.edit { it.copy(dealTypes = v) } }, error = err["dealTypes"])
            EnumDropdown(stringResource(R.string.property_status), PropertyStatus.entries, f.status, { v -> if (v != null) vm.edit { it.copy(status = v) } }, allowNone = false)
            if (f.status == PropertyStatus.RESERVED || f.status == PropertyStatus.UNDER_NEGOTIATION) {
                SwitchRow(stringResource(R.string.property_open_to_offers), f.openToFurtherOffers, { v -> vm.edit { it.copy(openToFurtherOffers = v) } })
            }
            CustomerPicker(stringResource(R.string.property_owner), f.ownerCustomerId, { v -> vm.edit { it.copy(ownerCustomerId = v) } })
            AgentPicker(stringResource(R.string.customer_assigned_agent), f.assignedAgentId, { v -> vm.edit { it.copy(assignedAgentId = v) } })
            DateInput(f.acquiredDate, { v -> vm.edit { it.copy(acquiredDate = v) } }, stringResource(R.string.property_acquired))
        }

        SectionCard(stringResource(R.string.property_price_section)) {
            if (sale) MoneyInput(f.askingPrice, { v -> vm.edit { it.copy(askingPrice = v) } }, stringResource(R.string.property_asking_price), error = err["askingPrice"])
            if (rent) {
                MoneyInput(f.deposit, { v -> vm.edit { it.copy(deposit = v) } }, stringResource(R.string.property_deposit), error = err["deposit"])
                MoneyInput(f.monthlyRent, { v -> vm.edit { it.copy(monthlyRent = v) } }, stringResource(R.string.property_monthly_rent), error = err["monthlyRent"])
            }
            if (full) MoneyInput(f.fullDepositAmount, { v -> vm.edit { it.copy(fullDepositAmount = v) } }, stringResource(R.string.property_full_deposit), error = err["fullDepositAmount"])
            MoneyInput(f.estimatedMarketValue, { v -> vm.edit { it.copy(estimatedMarketValue = v) } }, stringResource(R.string.property_market_value), error = err["estimatedMarketValue"])
            TriStateInput(stringResource(R.string.property_negotiable), f.negotiable, { v -> vm.edit { it.copy(negotiable = v) } })
            TextInput(f.commissionTerms, { v -> vm.edit { it.copy(commissionTerms = v) } }, stringResource(R.string.property_commission_terms))
        }

        SectionCard(stringResource(R.string.property_location)) {
            TextInput(f.province, { v -> vm.edit { it.copy(province = v) } }, stringResource(R.string.field_province))
            TextInput(f.city, { v -> vm.edit { it.copy(city = v) } }, stringResource(R.string.field_city), error = err["city"])
            TextInput(f.neighborhood, { v -> vm.edit { it.copy(neighborhood = v) } }, stringResource(R.string.field_neighborhood))
            TextInput(f.address, { v -> vm.edit { it.copy(address = v) } }, stringResource(R.string.field_address), singleLine = false, minLines = 2)
            val loc = f.location
            if (loc == null) {
                Text(stringResource(R.string.property_no_coordinates), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(fmt.num(Math.round(loc.point.lat * 1e5) / 1e5) + ", " + fmt.num(Math.round(loc.point.lng * 1e5) / 1e5), style = MaterialTheme.typography.bodyMedium)
                Text(locationSourceText(loc), style = MaterialTheme.typography.bodySmall)
            }
            errorText(err["location"])?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.property_pick_on_map)) }
                if (loc != null) TextButton(onClick = { vm.edit { it.copy(location = null) } }) { Text(stringResource(R.string.clear)) }
            }
            // Coordinates typed in by the agent, e.g. copied from a listing; stored as manual coordinates.
            Row {
                NumberInput(lat, { lat = it }, stringResource(R.string.property_lat), Modifier.weight(1f).padding(end = 4.dp), decimal = true)
                NumberInput(lng, { lng = it }, stringResource(R.string.property_lng), Modifier.weight(1f).padding(start = 4.dp), decimal = true)
            }
            TextButton(
                onClick = {
                    val a = (Input.decimal(lat) as? Parsed.Ok)?.value
                    val b = (Input.decimal(lng) as? Parsed.Ok)?.value
                    val point = if (a != null && b != null) runCatching { GeoPoint(a, b) }.getOrNull() else null
                    if (point == null) {
                        app.say(Msg.Res(R.string.err_invalid_coordinates))
                    } else {
                        vm.edit { it.copy(location = Location(point, LocationSource.MANUAL_COORDINATES, null)) }
                        lat = ""
                        lng = ""
                    }
                },
                enabled = lat.isNotBlank() && lng.isNotBlank(),
            ) { Text(stringResource(R.string.apply)) }
        }

        SectionCard(stringResource(R.string.req_size_section)) {
            Row {
                NumberInput(f.builtArea, { v -> vm.edit { it.copy(builtArea = v) } }, stringResource(R.string.property_built_area), Modifier.weight(1f).padding(end = 4.dp), err["builtArea"], decimal = true, suffix = stringResource(R.string.unit_m2))
                NumberInput(f.landArea, { v -> vm.edit { it.copy(landArea = v) } }, stringResource(R.string.property_land_area), Modifier.weight(1f).padding(start = 4.dp), err["landArea"], decimal = true, suffix = stringResource(R.string.unit_m2))
            }
            Row {
                NumberInput(f.bedrooms, { v -> vm.edit { it.copy(bedrooms = v) } }, stringResource(R.string.property_bedrooms), Modifier.weight(1f).padding(end = 4.dp), err["bedrooms"])
                NumberInput(f.bathrooms, { v -> vm.edit { it.copy(bathrooms = v) } }, stringResource(R.string.property_bathrooms), Modifier.weight(1f).padding(start = 4.dp), err["bathrooms"])
            }
            Row {
                NumberInput(f.floor, { v -> vm.edit { it.copy(floor = v) } }, stringResource(R.string.property_floor), Modifier.weight(1f).padding(end = 4.dp), err["floor"])
                NumberInput(f.totalFloors, { v -> vm.edit { it.copy(totalFloors = v) } }, stringResource(R.string.property_total_floors), Modifier.weight(1f).padding(start = 4.dp), err["totalFloors"])
            }
            NumberInput(f.unitsPerFloor, { v -> vm.edit { it.copy(unitsPerFloor = v) } }, stringResource(R.string.property_units_per_floor), error = err["unitsPerFloor"])
            Row {
                NumberInput(f.constructionYear, { v -> vm.edit { it.copy(constructionYear = v) } }, stringResource(R.string.property_construction_year), Modifier.weight(1f).padding(end = 4.dp), err["constructionYear"])
                Dropdown(
                    stringResource(R.string.property_year_calendar), CalendarSystem.entries, f.constructionYearCalendar,
                    { v -> if (v != null) vm.edit { it.copy(constructionYearCalendar = v) } },
                    { c -> stringResource(if (c == CalendarSystem.JALALI) R.string.calendar_jalali else R.string.calendar_gregorian) },
                    Modifier.weight(1f).padding(start = 4.dp), allowNone = false,
                )
            }
        }

        SectionCard(stringResource(R.string.req_features_section)) {
            NumberInput(f.parkingSpaces, { v -> vm.edit { it.copy(parkingSpaces = v) } }, stringResource(R.string.property_parking_spaces), error = err["parkingSpaces"])
            TriStateInput(stringResource(R.string.property_elevator), f.elevator, { v -> vm.edit { it.copy(elevator = v) } })
            TriStateInput(stringResource(R.string.property_storage), f.storage, { v -> vm.edit { it.copy(storage = v) } })
            EnumDropdown(stringResource(R.string.property_heating), HeatingType.entries, f.heating, { v -> vm.edit { it.copy(heating = v) } })
            EnumDropdown(stringResource(R.string.property_cooling), CoolingType.entries, f.cooling, { v -> vm.edit { it.copy(cooling = v) } })
            EnumChips(stringResource(R.string.property_amenities), Amenity.entries.filter { it !in ownFields }, f.amenities, { v -> vm.edit { it.copy(amenities = v) } })
            Text(stringResource(R.string.property_amenities_note), style = MaterialTheme.typography.bodySmall)
            EnumDropdown(stringResource(R.string.property_facing), Facing.entries, f.facing, { v -> vm.edit { it.copy(facing = v) } })
            EnumDropdown(stringResource(R.string.property_condition), PropertyCondition.entries, f.condition, { v -> vm.edit { it.copy(condition = v) } })
            EnumDropdown(stringResource(R.string.property_furnished), FurnishedStatus.entries, f.furnished, { v -> vm.edit { it.copy(furnished = v) } })
            EnumDropdown(stringResource(R.string.property_document), DocumentStatus.entries, f.documentStatus, { v -> vm.edit { it.copy(documentStatus = v) } })
        }

        SectionCard(null) {
            MultilineInput(f.description, { v -> vm.edit { it.copy(description = v) } }, stringResource(R.string.property_description))
            SwitchRow(stringResource(R.string.draft_badge), f.isDraft, { v -> vm.edit { it.copy(isDraft = v) } }, supporting = stringResource(R.string.draft_hint))
            if (vm.isNew) Text(stringResource(R.string.property_photos_after_save), style = MaterialTheme.typography.bodySmall)
        }

        if (vm.canEditConfidential) {
            ConfidentialCard(stringResource(R.string.property_confidential_notes)) {
                MultilineInput(f.confidentialNotes, { v -> vm.edit { it.copy(confidentialNotes = v) } }, stringResource(R.string.property_confidential_notes))
            }
        } else {
            Banner(stringResource(R.string.confidential_hidden))
        }
    }

    if (picking) {
        val current = state.form?.location
        LocationPickDialog(
            initial = current,
            title = stringResource(R.string.property_pick_on_map),
            onDismiss = { picking = false },
            onPick = { loc -> picking = false; vm.edit { it.copy(location = loc) } },
        )
    }
}
