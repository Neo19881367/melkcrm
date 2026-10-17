package ir.melkcrm.app.ui.activities

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.AgentPicker
import ir.melkcrm.app.ui.components.CustomerPickDialog
import ir.melkcrm.app.ui.components.CustomerPicker
import ir.melkcrm.app.ui.components.DateInput
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EditorScaffold
import ir.melkcrm.app.ui.components.EditorViewModel
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.MultilineInput
import ir.melkcrm.app.ui.components.NumberInput
import ir.melkcrm.app.ui.components.PropertyPicker
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SwitchRow
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.components.TimeInput
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.report
import ir.melkcrm.app.work.Notifications
import ir.melkcrm.client.data.SaveOutcome
import ir.melkcrm.client.forms.ActivityForm
import ir.melkcrm.client.forms.AppointmentForm
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.TaskForm
import ir.melkcrm.core.model.Activity
import ir.melkcrm.core.model.ActivityOutcome
import ir.melkcrm.core.model.ActivityType
import ir.melkcrm.core.model.Appointment
import ir.melkcrm.core.model.AppointmentStatus
import ir.melkcrm.core.model.AppointmentType
import ir.melkcrm.core.model.ContactMethod
import ir.melkcrm.core.model.TaskItem
import ir.melkcrm.core.model.TaskStatus
import ir.melkcrm.core.model.ViewingFeedback
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------- activity

class ActivityEditViewModel(app: AppContainer, private val id: String?, private val customerId: String?, private val propertyId: String?) :
    EditorViewModel<Activity, ActivityForm>(app) {
    val isNew = id == null
    private val zone get() = crm.writer.zone

    override suspend fun load(): Activity? = if (id == null) crm.activities.newActivity(customerId = customerId, propertyId = propertyId) else crm.activities.activity(id)
    override fun formOf(e: Activity) = ActivityForm.from(e, zone, settings.persianDigits)
    override fun build(form: ActivityForm, base: Activity): FormResult<Activity> = form.build(base, zone)
    override suspend fun save(e: Activity, confirmed: Boolean): SaveOutcome<Activity> = crm.activities.log(e)

    init {
        start()
    }
}

@Composable
fun ActivityEditScreen(id: String?, customerId: String?, propertyId: String?, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "activity-$id-$customerId-$propertyId") { ActivityEditViewModel(app, id, customerId, propertyId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done != null) nav.back() }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.activity_new else R.string.activity_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        SectionCard(null) {
            EnumDropdown(stringResource(R.string.activity_type), ActivityType.entries, f.type, { v -> if (v != null) vm.edit { it.copy(type = v) } }, allowNone = false)
            if (f.type == ActivityType.MESSENGER_MANUAL) {
                // Messenger conversations are logged by hand; the app does not read any messenger.
                Text(stringResource(R.string.messenger_manual_note), style = MaterialTheme.typography.bodySmall)
                EnumDropdown(
                    stringResource(R.string.activity_channel),
                    listOf(ContactMethod.WHATSAPP, ContactMethod.TELEGRAM, ContactMethod.EITAA, ContactMethod.BALE),
                    f.channel, { v -> vm.edit { it.copy(channel = v) } }, error = err["channel"],
                )
            }
            Row {
                DateInput(f.day, { v -> if (v != null) vm.edit { it.copy(day = v) } }, stringResource(R.string.activity_time), Modifier.weight(1f).padding(end = 4.dp), clearable = false)
                TimeInput(f.time, { v -> vm.edit { it.copy(time = v) } }, stringResource(R.string.appointment_time), Modifier.weight(1f).padding(start = 4.dp), error = err["occurredAt"])
            }
            CustomerPicker(stringResource(R.string.activity_customer), f.customerId, { v -> vm.edit { it.copy(customerId = v) } })
            PropertyPicker(stringResource(R.string.activity_property), f.propertyId, { v -> vm.edit { it.copy(propertyId = v) } }, includeClosed = true)
            EnumDropdown(stringResource(R.string.activity_outcome), ActivityOutcome.entries, f.outcome, { v -> vm.edit { it.copy(outcome = v) } })
            if (f.type == ActivityType.PROPERTY_VIEWING) {
                EnumDropdown(stringResource(R.string.activity_feedback), ViewingFeedback.entries, f.viewingFeedback, { v -> vm.edit { it.copy(viewingFeedback = v) } })
            }
            MultilineInput(f.notes, { v -> vm.edit { it.copy(notes = v) } }, stringResource(R.string.notes))
        }
        SectionCard(stringResource(R.string.activity_next_action)) {
            TextInput(f.nextAction, { v -> vm.edit { it.copy(nextAction = v) } }, stringResource(R.string.activity_next_action))
            DateInput(f.nextActionDate, { v -> vm.edit { it.copy(nextActionDate = v) } }, stringResource(R.string.activity_next_date), error = err["nextActionDate"])
            Text(stringResource(R.string.activity_next_action_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------- task

class TaskEditViewModel(app: AppContainer, private val id: String?, private val customerId: String?, private val propertyId: String?) :
    EditorViewModel<TaskItem, TaskForm>(app) {
    val isNew = id == null

    override suspend fun load(): TaskItem? =
        if (id == null) crm.activities.newTask().copy(customerId = customerId, propertyId = propertyId) else crm.activities.task(id)
    override fun formOf(e: TaskItem) = TaskForm.from(e, settings.persianDigits)
    override fun build(form: TaskForm, base: TaskItem): FormResult<TaskItem> = form.build(base, crm.writer.zone)
    override suspend fun save(e: TaskItem, confirmed: Boolean): SaveOutcome<TaskItem> = crm.activities.saveTask(e)

    init {
        start()
    }
}

/** Asks for the notification permission (Android 13+) the first time a reminder is switched on. */
@Composable
private fun rememberNotificationRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun TaskEditScreen(id: String?, customerId: String?, propertyId: String?, nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel(key = "task-$id-$customerId-$propertyId") { TaskEditViewModel(app, id, customerId, propertyId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val askNotifications = rememberNotificationRequest()
    LaunchedEffect(state.done) { if (state.done != null) nav.back() }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.task_new else R.string.task_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        val base = state.base
        SectionCard(null) {
            TextInput(f.title, { v -> vm.edit { it.copy(title = v) } }, stringResource(R.string.task_title), error = err["title"])
            Row {
                DateInput(f.dueDate, { v -> if (v != null) vm.edit { it.copy(dueDate = v) } }, stringResource(R.string.task_due), Modifier.weight(1f).padding(end = 4.dp), clearable = false)
                TimeInput(f.dueTime, { v -> vm.edit { it.copy(dueTime = v) } }, stringResource(R.string.task_due_time), Modifier.weight(1f).padding(start = 4.dp), error = err["dueTimeMinutes"])
            }
            SwitchRow(stringResource(R.string.task_reminder), f.remind, { v ->
                if (v) askNotifications()
                vm.edit { it.copy(remind = v) }
            }, supporting = stringResource(R.string.task_reminder_note))
            AgentPicker(stringResource(R.string.customer_assigned_agent), f.assignedAgentId, { v -> vm.edit { it.copy(assignedAgentId = v) } })
            CustomerPicker(stringResource(R.string.activity_customer), f.customerId, { v -> vm.edit { it.copy(customerId = v) } })
            PropertyPicker(stringResource(R.string.activity_property), f.propertyId, { v -> vm.edit { it.copy(propertyId = v) } }, includeClosed = true)
            MultilineInput(f.notes, { v -> vm.edit { it.copy(notes = v) } }, stringResource(R.string.notes))
        }
        if (base != null && !vm.isNew) {
            val done = base.status == TaskStatus.DONE
            OutlinedButton(onClick = {
                scope.launch { if (app.report(app.crm.activities.completeTask(base.id, !done))) nav.back() }
            }) { Text(stringResource(if (done) R.string.task_reopen else R.string.task_mark_done)) }
        }
    }
}

// ---------------------------------------------------------------------------------------- appointment

class AppointmentEditViewModel(app: AppContainer, private val id: String?, private val customerId: String?, private val propertyId: String?) :
    EditorViewModel<Appointment, AppointmentForm>(app) {
    val isNew = id == null
    private val zone get() = crm.writer.zone

    override suspend fun load(): Appointment? = if (id == null) {
        crm.activities.newAppointment().copy(customerIds = listOfNotNull(customerId), propertyId = propertyId)
    } else {
        crm.activities.appointment(id)
    }

    override fun formOf(e: Appointment) = AppointmentForm.from(e, zone, settings.persianDigits).let { f ->
        // A new appointment starts with an empty time so the agent enters it; tomorrow is the default day.
        if (isNew) f.copy(time = "") else f
    }

    override fun build(form: AppointmentForm, base: Appointment): FormResult<Appointment> = form.build(base, zone)
    override suspend fun save(e: Appointment, confirmed: Boolean): SaveOutcome<Appointment> = crm.activities.saveAppointment(e)

    init {
        start()
    }
}

private val reminderChoices = listOf<Int?>(null, 15, 30, 60, 120, 24 * 60)

@Composable
fun AppointmentEditScreen(id: String?, customerId: String?, propertyId: String?, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "appointment-$id-$customerId-$propertyId") { AppointmentEditViewModel(app, id, customerId, propertyId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val customers by app.crm.customers.customers.collectAsStateWithLifecycle(initialValue = emptyList())
    val askNotifications = rememberNotificationRequest()
    var addingCustomer by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done != null) nav.back() }

    EditorScaffold(
        title = stringResource(if (vm.isNew) R.string.appointment_new else R.string.appointment_edit),
        state = state, onBack = { nav.back() }, onSave = { vm.submit() },
    ) { f ->
        val err = state.errors
        SectionCard(null) {
            EnumDropdown(stringResource(R.string.appointment_type), AppointmentType.entries, f.type, { v -> if (v != null) vm.edit { it.copy(type = v) } }, allowNone = false)
            TextInput(f.title, { v -> vm.edit { it.copy(title = v) } }, stringResource(R.string.appointment_title), error = err["title"])
            Row {
                DateInput(f.day, { v -> if (v != null) vm.edit { it.copy(day = v) } }, stringResource(R.string.appointment_start), Modifier.weight(1f).padding(end = 4.dp), clearable = false)
                TimeInput(f.time, { v -> vm.edit { it.copy(time = v) } }, stringResource(R.string.appointment_time), Modifier.weight(1f).padding(start = 4.dp), error = err["startsAt"])
            }
            NumberInput(f.durationMinutes, { v -> vm.edit { it.copy(durationMinutes = v) } }, stringResource(R.string.appointment_duration), error = err["endsAt"])
            TextInput(f.location, { v -> vm.edit { it.copy(location = v) } }, stringResource(R.string.appointment_location))
            Dropdown(
                label = stringResource(R.string.appointment_reminder), options = reminderChoices, selected = f.reminderMinutesBefore,
                onSelect = { v -> if (v != null) askNotifications(); vm.edit { it.copy(reminderMinutesBefore = v) } },
                optionLabel = { m -> if (m == null) stringResource(R.string.none) else stringResource(R.string.minutes_before, fmt.num(m)) },
                allowNone = false,
            )
            AgentPicker(stringResource(R.string.agent), f.agentId, { v -> vm.edit { it.copy(agentId = v) } })
            PropertyPicker(stringResource(R.string.activity_property), f.propertyId, { v -> vm.edit { it.copy(propertyId = v) } })
        }
        SectionCard(stringResource(R.string.appointment_customers)) {
            for (cid in f.customerIds) {
                ListItem(
                    headlineContent = { Text(customers.firstOrNull { it.id == cid }?.fullName ?: stringResource(R.string.unknown)) },
                    trailingContent = {
                        IconButton(onClick = { vm.edit { it.copy(customerIds = it.customerIds - cid) } }) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) }
                    },
                )
            }
            OutlinedButton(onClick = { addingCustomer = true }) { Text(stringResource(R.string.add)) }
        }
        val base = state.base
        if (base != null && !vm.isNew && base.status == AppointmentStatus.SCHEDULED) AppointmentOutcome(base, nav)
        else if (base != null && !vm.isNew) {
            SectionCard(stringResource(R.string.appointment_outcome)) {
                Text(fmt.label(base.status) + (base.feedback?.let { " · " + fmt.label(it) } ?: ""))
                base.outcomeNotes?.let { Text(it) }
            }
        }
    }
    if (addingCustomer) {
        CustomerPickDialog(
            stringResource(R.string.pick_customer),
            onPick = { c -> addingCustomer = false; vm.edit { it.copy(customerIds = (it.customerIds + c.id).distinct()) } },
            onDismiss = { addingCustomer = false },
        )
    }
}

/** Records how a scheduled appointment went. A completed viewing is also logged as an activity for each customer. */
@Composable
private fun AppointmentOutcome(a: Appointment, nav: Nav) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(AppointmentStatus.COMPLETED) }
    var feedback by remember { mutableStateOf<ViewingFeedback?>(null) }
    var notes by remember { mutableStateOf("") }
    SectionCard(stringResource(R.string.appointment_complete)) {
        EnumDropdown(stringResource(R.string.status), listOf(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW), status, { v -> if (v != null) status = v }, allowNone = false)
        if (a.type == AppointmentType.VIEWING && status == AppointmentStatus.COMPLETED) {
            EnumDropdown(stringResource(R.string.appointment_feedback), ViewingFeedback.entries, feedback, { feedback = it })
        }
        MultilineInput(notes, { notes = it }, stringResource(R.string.appointment_outcome))
        Button(onClick = {
            scope.launch { if (app.report(app.crm.activities.completeAppointment(a.id, status, feedback, notes))) nav.back() }
        }) { Text(stringResource(R.string.save)) }
    }
}
