package ir.melkcrm.app.ui.properties

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.activities.TimelineData
import ir.melkcrm.app.ui.activities.TimelineRow
import ir.melkcrm.app.ui.activities.timelineFlow
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.ConfidentialCard
import ir.melkcrm.app.ui.components.CustomerPickDialog
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.LabeledValue
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.agentName
import ir.melkcrm.app.ui.map.locationSourceText
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.report
import ir.melkcrm.client.store.ImageUploadState
import ir.melkcrm.client.store.LocalImage
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.Deal
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.PropertyContact
import ir.melkcrm.core.model.PropertyContactRole
import ir.melkcrm.core.model.PropertyStatus
import ir.melkcrm.core.model.SellerProfile
import ir.melkcrm.core.money.Money
import ir.melkcrm.core.permissions.Access
import ir.melkcrm.core.sync.EntityType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class PropertyDetailViewModel(private val app: AppContainer, val id: String) : ViewModel() {
    data class State(val loading: Boolean = true, val property: Property? = null, val access: Access = Access.NONE, val confidential: Boolean = false)

    private val crm = app.crm
    private val started = SharingStarted.WhileSubscribed(5_000)

    val state: StateFlow<State> = crm.properties.properties.map { l -> l.firstOrNull { it.id == id } }
        .mapLatest { p ->
            if (p == null) State(loading = false)
            else State(false, p, app.store.get(EntityType.PROPERTY, id)?.let { crm.writer.access(it) } ?: Access.NONE, crm.properties.canSeeConfidential(p))
        }
        .stateIn(viewModelScope, started, State())
    val images: StateFlow<List<LocalImage>> = crm.properties.images(id).stateIn(viewModelScope, started, emptyList())
    val sellers: StateFlow<List<SellerProfile>> = crm.customers.sellers.map { l -> l.filter { it.propertyId == id }.sortedByDescending { it.updatedAt } }.stateIn(viewModelScope, started, emptyList())
    val contacts: StateFlow<List<PropertyContact>> = crm.properties.contactsOf(id).stateIn(viewModelScope, started, emptyList())
    val customers: StateFlow<Map<String, Customer>> = crm.customers.customers.map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, started, emptyMap())
    val deals: StateFlow<List<Deal>> = crm.deals.dealsOfProperty(id).map { it.sortedByDescending { d -> d.updatedAt } }.stateIn(viewModelScope, started, emptyList())
    val timeline: StateFlow<TimelineData?> = timelineFlow(app, null, id).stateIn(viewModelScope, started, null)

    fun setStatus(s: PropertyStatus) = viewModelScope.launch { app.report(crm.properties.setStatus(id, s)) }

    fun addPhoto(uri: android.net.Uri) = viewModelScope.launch {
        val path = app.photos.import(uri)
        if (path == null) app.say(Msg.Res(R.string.photo_import_failed)) else crm.properties.addImage(id, path)
    }

    fun removePhoto(img: LocalImage) = viewModelScope.launch {
        crm.properties.removeImage(img)
        app.photos.delete(img.localPath)
    }

    fun addContact(customerId: String, role: PropertyContactRole) = viewModelScope.launch { app.report(crm.properties.addContact(id, customerId, role)) }
    fun removeContact(pc: PropertyContact) = viewModelScope.launch { app.report(crm.properties.removeContact(pc)) }
}

private val tabs = listOf(R.string.tab_overview, R.string.property_photos, R.string.tab_selling, R.string.tab_history, R.string.tab_offers)

@Composable
fun PropertyDetailScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "property-$id") { PropertyDetailViewModel(app, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val p = state.property
    val canWrite = state.access == Access.WRITE

    ScreenScaffold(
        title = p?.reference ?: stringResource(R.string.properties_title),
        onBack = { nav.back() },
        actions = {
            if (p != null) {
                IconButton(onClick = { nav.go(Routes.history(id)) }) { Icon(Icons.Filled.History, stringResource(R.string.tab_history)) }
                if (canWrite) IconButton(onClick = { nav.go(Routes.propertyEdit(id)) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
            }
        },
    ) { padding ->
        when {
            state.loading -> Loading()
            p == null -> EmptyState(stringResource(R.string.property_not_found), Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
                }
                when (tab) {
                    0 -> Overview(vm, p, canWrite, state.confidential, nav)
                    1 -> Photos(vm, p, canWrite)
                    2 -> SellerTab(vm, p, canWrite, state.confidential, nav)
                    3 -> PropertyTimeline(vm, p, nav)
                    else -> PropertyDeals(vm, nav)
                }
            }
        }
    }
}

@Composable
private fun Overview(vm: PropertyDetailViewModel, p: Property, canWrite: Boolean, confidential: Boolean, nav: Nav) {
    val fmt = LocalFormat.current
    val app = LocalApp.current
    val today = app.crm.writer.today()
    FormColumn(PaddingValues(0.dp)) {
        Text(p.title, style = MaterialTheme.typography.titleLarge)
        if (p.isDraft) Banner(stringResource(R.string.draft_badge))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { nav.go(Routes.buyersFor(p.id)) }, label = { Text(stringResource(R.string.find_best_buyers)) })
            AssistChip(onClick = { nav.go(Routes.appointmentEdit(null, p.ownerCustomerId, p.id)) }, label = { Text(stringResource(R.string.appointment_new)) })
            AssistChip(onClick = { nav.go(Routes.activityEdit(null, p.ownerCustomerId, p.id)) }, label = { Text(stringResource(R.string.log_activity)) })
        }
        SectionCard(null) {
            if (canWrite) {
                EnumDropdown(stringResource(R.string.property_status), PropertyStatus.entries, p.status, { s -> if (s != null && s != p.status) vm.setStatus(s) }, allowNone = false)
                Text(stringResource(R.string.property_never_deleted_note), style = MaterialTheme.typography.bodySmall)
            } else {
                LabeledValue(stringResource(R.string.property_status), fmt.label(p.status))
            }
            LabeledValue(stringResource(R.string.property_category), fmt.label(p.category))
            LabeledValue(stringResource(R.string.property_deal_types), p.dealTypes.joinToString("، ") { fmt.label(it) })
            LabeledValue(stringResource(R.string.customer_assigned_agent), agentName(p.assignedAgentId) ?: stringResource(R.string.unassigned))
            LabeledValue(stringResource(R.string.property_acquired), fmt.date(p.acquiredDate))
            p.acquiredDate?.let { d -> if (d <= today) Text(stringResource(R.string.listing_age_days, (today - d).toInt()), style = MaterialTheme.typography.bodySmall) }
            LabeledValue(stringResource(R.string.property_last_updated), fmt.dateTime(p.updatedAt))
        }
        SectionCard(stringResource(R.string.property_price_section)) {
            if (DealType.SALE in p.dealTypes) {
                LabeledValue(stringResource(R.string.property_asking_price), fmt.money(p.askingPrice))
                val price = p.askingPrice
                val area = p.effectiveArea
                if (price != null && area != null && area > 0) {
                    Text(stringResource(R.string.price_per_m2, fmt.money(Money((price.rial / area).toLong()))), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (DealType.RENT in p.dealTypes) {
                LabeledValue(stringResource(R.string.property_deposit), fmt.money(p.deposit))
                LabeledValue(stringResource(R.string.property_monthly_rent), fmt.money(p.monthlyRent))
            }
            if (DealType.FULL_DEPOSIT in p.dealTypes) LabeledValue(stringResource(R.string.property_full_deposit), fmt.money(p.fullDepositAmount))
            LabeledValue(stringResource(R.string.property_market_value), fmt.money(p.estimatedMarketValue))
            LabeledValue(stringResource(R.string.property_negotiable), p.negotiable?.let { stringResource(if (it) R.string.yes else R.string.no) })
            LabeledValue(stringResource(R.string.property_commission_terms), p.commissionTerms)
        }
        SectionCard(stringResource(R.string.property_location)) {
            LabeledValue(stringResource(R.string.field_city), listOfNotNull(p.neighborhood, p.city, p.province).joinToString("، "))
            LabeledValue(stringResource(R.string.field_address), p.address)
            val loc = p.location
            if (loc == null) {
                Text(stringResource(R.string.property_no_coordinates), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(fmt.num(Math.round(loc.point.lat * 1e5) / 1e5) + ", " + fmt.num(Math.round(loc.point.lng * 1e5) / 1e5), style = MaterialTheme.typography.bodyMedium)
                Text(locationSourceText(loc), style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionCard(stringResource(R.string.req_size_section)) {
            LabeledValue(stringResource(R.string.property_built_area), p.builtArea?.let { fmt.area(it) })
            LabeledValue(stringResource(R.string.property_land_area), p.landArea?.let { fmt.area(it) })
            LabeledValue(stringResource(R.string.property_bedrooms), fmt.num(p.bedrooms))
            LabeledValue(stringResource(R.string.property_bathrooms), fmt.num(p.bathrooms))
            LabeledValue(stringResource(R.string.property_floor), listOfNotNull(p.floor?.let { fmt.num(it) }, p.totalFloors?.let { fmt.num(it) }).joinToString(" / "))
            LabeledValue(stringResource(R.string.property_construction_year), p.constructionYear?.let { fmt.num(it) + " " + stringResource(if (p.constructionYearCalendar == ir.melkcrm.core.time.CalendarSystem.JALALI) R.string.calendar_jalali else R.string.calendar_gregorian) })
            LabeledValue(stringResource(R.string.property_parking_spaces), fmt.num(p.parkingSpaces))
            LabeledValue(stringResource(R.string.property_elevator), p.elevator?.let { stringResource(if (it) R.string.yes else R.string.no) })
            LabeledValue(stringResource(R.string.property_storage), p.storage?.let { stringResource(if (it) R.string.yes else R.string.no) })
            LabeledValue(stringResource(R.string.property_heating), fmt.label(p.heating))
            LabeledValue(stringResource(R.string.property_cooling), fmt.label(p.cooling))
            LabeledValue(stringResource(R.string.property_amenities), p.amenities.joinToString("، ") { fmt.label(it) })
            LabeledValue(stringResource(R.string.property_facing), fmt.label(p.facing))
            LabeledValue(stringResource(R.string.property_condition), fmt.label(p.condition))
            LabeledValue(stringResource(R.string.property_furnished), fmt.label(p.furnished))
            LabeledValue(stringResource(R.string.property_document), fmt.label(p.documentStatus))
        }
        p.description?.let { SectionCard(stringResource(R.string.property_description)) { Text(it) } }
        if (confidential) {
            val notes = p.confidentialNotes
            if (notes != null) ConfidentialCard(stringResource(R.string.property_confidential_notes)) { Text(notes) }
        }
    }
}

@Composable
private fun Photos(vm: PropertyDetailViewModel, p: Property, canWrite: Boolean) {
    val app = LocalApp.current
    val images by vm.images.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.addPhoto(uri) }
    var confirm by remember { mutableStateOf<LocalImage?>(null) }
    // Photos uploaded from other devices are listed on the property but have no local file yet.
    val remoteOnly = p.imageIds - images.mapNotNull { it.remoteId }.toSet()
    Column(Modifier.fillMaxSize()) {
        if (canWrite) {
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.padding(16.dp),
            ) { Text(stringResource(R.string.property_add_photo)) }
            Text(stringResource(R.string.photo_privacy_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (images.isEmpty() && remoteOnly.isEmpty()) {
            EmptyState(stringResource(R.string.photos_none))
        } else LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(images, key = { it.id }) { img ->
                Box(Modifier.padding(4.dp).aspectRatio(1f)) {
                    val path = img.localPath
                    val remoteFile by produceState<File?>(null, img.remoteId, path) {
                        val rid = img.remoteId
                        value = if (path == null && rid != null) app.photos.remote(app.crm.session.api(), rid) else null
                    }
                    AsyncImage(model = path?.let { File(it) } ?: remoteFile, contentDescription = img.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (img.uploadState != ImageUploadState.UPLOADED) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.TopStart)) {
                            Text(
                                stringResource(if (img.uploadState == ImageUploadState.FAILED) R.string.photo_upload_failed else R.string.photo_upload_pending),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                    if (canWrite) {
                        IconButton(onClick = { confirm = img }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
            items(remoteOnly, key = { it }) { rid ->
                val file by produceState<File?>(null, rid) { value = app.photos.remote(app.crm.session.api(), rid) }
                Box(Modifier.padding(4.dp).aspectRatio(1f)) {
                    AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
    confirm?.let { img ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.photo_remove)) },
            text = { Text(stringResource(R.string.photo_remove_note)) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.removePhoto(img) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SellerTab(vm: PropertyDetailViewModel, p: Property, canWrite: Boolean, confidential: Boolean, nav: Nav) {
    val fmt = LocalFormat.current
    val sellers by vm.sellers.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var roleFor by remember { mutableStateOf<Customer?>(null) }
    val s = sellers.firstOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        p.ownerCustomerId?.let { oid ->
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.customer(oid)) },
                overlineContent = { Text(stringResource(R.string.property_owner)) },
                headlineContent = { Text(customers[oid]?.fullName ?: stringResource(R.string.unknown)) },
            )
        }
        SectionCard(stringResource(R.string.seller_section)) {
            if (s == null) {
                Text(stringResource(R.string.seller_none))
            } else {
                LabeledValue(stringResource(R.string.seller_customer), customers[s.customerId]?.fullName)
                LabeledValue(stringResource(R.string.seller_status), fmt.label(s.status))
                LabeledValue(stringResource(R.string.seller_deadline), fmt.date(s.saleDeadline))
                LabeledValue(stringResource(R.string.seller_price_flex), fmt.label(s.priceFlexibility))
                LabeledValue(stringResource(R.string.seller_exclusive), s.exclusive?.let { stringResource(if (it) R.string.yes else R.string.no) })
                LabeledValue(stringResource(R.string.seller_exclusive_until), fmt.date(s.exclusiveUntil))
                LabeledValue(stringResource(R.string.seller_motivation), s.motivation)
                LabeledValue(stringResource(R.string.seller_negotiation_conditions), s.negotiationConditions)
                LabeledValue(stringResource(R.string.seller_instructions), s.instructions)
                LabeledValue(stringResource(R.string.seller_commission_agreement), s.commissionAgreement)
            }
            if (canWrite) {
                TextButton(onClick = { nav.go(Routes.sellerEdit(p.id, s?.id)) }) { Text(stringResource(if (s == null) R.string.seller_new else R.string.seller_edit)) }
            }
        }
        if (s != null) {
            if (confidential) {
                ConfidentialCard(stringResource(R.string.seller_confidential_section)) {
                    LabeledValue(stringResource(R.string.seller_min_price), fmt.money(s.confidential.minAcceptablePrice))
                    LabeledValue(stringResource(R.string.seller_min_deposit), fmt.money(s.confidential.minAcceptableDeposit))
                    LabeledValue(stringResource(R.string.seller_min_rent), fmt.money(s.confidential.minAcceptableMonthlyRent))
                    LabeledValue(stringResource(R.string.seller_confidential_notes), s.confidential.notes)
                }
            } else {
                Banner(stringResource(R.string.confidential_hidden))
            }
        }
        SectionCard(stringResource(R.string.property_contacts)) {
            if (contacts.isEmpty()) Text(stringResource(R.string.empty_list))
            for (pc in contacts) {
                ListItem(
                    modifier = Modifier.clickable { nav.go(Routes.customer(pc.customerId)) },
                    headlineContent = { Text(customers[pc.customerId]?.fullName ?: stringResource(R.string.unknown)) },
                    supportingContent = { Text(fmt.label(pc.role) + (pc.note?.let { " · $it" } ?: "")) },
                    trailingContent = {
                        if (canWrite) IconButton(onClick = { vm.removeContact(pc) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    },
                )
            }
            if (canWrite) TextButton(onClick = { adding = true }) { Text(stringResource(R.string.contact_add)) }
        }
    }
    if (adding) {
        CustomerPickDialog(stringResource(R.string.pick_customer), onPick = { c -> adding = false; roleFor = c }, onDismiss = { adding = false })
    }
    roleFor?.let { c -> RoleDialog(onPick = { r -> roleFor = null; vm.addContact(c.id, r) }, onDismiss = { roleFor = null }) }
}

@Composable
private fun RoleDialog(onPick: (PropertyContactRole) -> Unit, onDismiss: () -> Unit) {
    val fmt = LocalFormat.current
    var role by remember { mutableStateOf(PropertyContactRole.OWNER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_role)) },
        text = {
            Column {
                for (r in PropertyContactRole.entries) {
                    Row(Modifier.fillMaxWidth().clickable { role = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == r, onClick = { role = r })
                        Text(fmt.label(r))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(role) }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PropertyTimeline(vm: PropertyDetailViewModel, p: Property, nav: Nav) {
    val data by vm.timeline.collectAsStateWithLifecycle()
    val d = data
    if (d == null) {
        Loading()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.go(Routes.activityEdit(null, null, p.id)) }, label = { Text(stringResource(R.string.log_activity)) })
                AssistChip(onClick = { nav.go(Routes.taskEdit(null, null, p.id)) }, label = { Text(stringResource(R.string.task_new)) })
                AssistChip(onClick = { nav.go(Routes.appointmentEdit(null, null, p.id)) }, label = { Text(stringResource(R.string.appointment_new)) })
            }
        }
        if (d.entries.isEmpty()) item { EmptyState(stringResource(R.string.empty_list)) }
        items(d.entries, key = { it.kind.name + it.id }) { e ->
            TimelineRow(e, d, nav, showProperty = false)
            HorizontalDivider()
        }
    }
}

@Composable
private fun PropertyDeals(vm: PropertyDetailViewModel, nav: Nav) {
    val fmt = LocalFormat.current
    val deals by vm.deals.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        if (deals.isEmpty()) item { EmptyState(stringResource(R.string.deals_none)) }
        items(deals, key = { it.id }) { d ->
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.deal(d.id)) },
                overlineContent = { Text(fmt.label(d.dealType)) },
                headlineContent = { Text(customers[d.buyerCustomerId]?.fullName ?: stringResource(R.string.unknown)) },
                supportingContent = { Text(stringResource(R.string.deal_stage) + ": " + fmt.label(d.stage) + " · " + fmt.dateTime(d.updatedAt)) },
            )
            HorizontalDivider()
        }
    }
}
