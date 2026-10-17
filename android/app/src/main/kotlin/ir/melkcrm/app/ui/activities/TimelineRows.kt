package ir.melkcrm.app.ui.activities

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.report
import ir.melkcrm.client.view.Timeline
import ir.melkcrm.client.view.TimelineEntry
import ir.melkcrm.core.model.Activity
import ir.melkcrm.core.model.Appointment
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.Offer
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.model.TaskItem
import ir.melkcrm.core.model.TaskStatus
import ir.melkcrm.core.time.AgencyTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The records behind timeline entries, looked up by id when rendering. */
data class TimelineData(
    val entries: List<TimelineEntry>,
    val activities: Map<String, Activity>,
    val tasks: Map<String, TaskItem>,
    val appointments: Map<String, Appointment>,
    val offers: Map<String, Offer>,
    val customers: Map<String, Customer>,
    val properties: Map<String, Property>,
)

private data class Lists(val a: List<Activity>, val t: List<TaskItem>, val ap: List<Appointment>, val o: List<Offer>)

/** Activities, tasks, appointments and offers in one list, newest first, optionally for one customer or property. */
fun timelineFlow(app: AppContainer, customerId: String?, propertyId: String?): Flow<TimelineData> {
    val crm = app.crm
    val lists = combine(crm.activities.activities, crm.activities.tasks, crm.activities.appointments, crm.deals.offers) { a, t, ap, o -> Lists(a, t, ap, o) }
    return combine(lists, crm.customers.customers, crm.properties.properties) { l, c, p ->
        TimelineData(
            Timeline.build(l.a, l.t, l.ap, l.o, customerId, propertyId) { AgencyTime.startOfDayMillis(it, crm.writer.zone) },
            l.a.associateBy { it.id }, l.t.associateBy { it.id }, l.ap.associateBy { it.id }, l.o.associateBy { it.id },
            c.associateBy { it.id }, p.associateBy { it.id },
        )
    }
}

private fun offerFigures(o: Offer, fmt: UiFormat): String = when (o.dealType) {
    DealType.SALE -> fmt.money(o.offeredPrice)
    else -> listOfNotNull(o.offeredDeposit?.let { fmt.money(it) }, o.offeredMonthlyRent?.let { fmt.money(it) }).joinToString(" + ")
}

/** One timeline entry; tapping opens the record. Tasks can be ticked off in place. */
@Composable
fun TimelineRow(entry: TimelineEntry, data: TimelineData, nav: Nav, showCustomer: Boolean = true, showProperty: Boolean = true) {
    val fmt = LocalFormat.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val today = app.crm.writer.today()
    val who = listOfNotNull(
        entry.customerId?.takeIf { showCustomer }?.let { data.customers[it]?.fullName },
        entry.propertyId?.takeIf { showProperty }?.let { data.properties[it]?.reference },
    ).joinToString(" · ")
    when (entry.kind) {
        TimelineEntry.Kind.ACTIVITY -> {
            val a = data.activities[entry.id] ?: return
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.activityEdit(a.id)) },
                leadingContent = { Icon(Icons.Filled.Call, null) },
                headlineContent = { Text(listOfNotNull(fmt.label(a.type), a.outcome?.let { fmt.label(it) }, a.channel?.let { fmt.label(it) }).joinToString(" · ")) },
                supportingContent = {
                    Text(listOfNotNull(fmt.dateTime(a.occurredAt), who.ifBlank { null }, a.notes?.lines()?.firstOrNull()).joinToString("\n"), maxLines = 3)
                },
            )
        }
        TimelineEntry.Kind.TASK -> {
            val t = data.tasks[entry.id] ?: return
            val overdue = t.isOverdue(today)
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.taskEdit(t.id)) },
                leadingContent = { Icon(Icons.Filled.TaskAlt, null) },
                headlineContent = { Text(t.title) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            stringResource(R.string.task_due) + ": " + fmt.date(t.dueDate) + (t.dueTimeMinutes?.let { " " + ir.melkcrm.client.forms.Input.timeText(it, fmt.digits) } ?: ""),
                            if (overdue) stringResource(R.string.task_overdue) else null,
                            who.ifBlank { null },
                        ).joinToString("\n"),
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Checkbox(
                        checked = t.status == TaskStatus.DONE,
                        onCheckedChange = { done -> scope.launch { app.report(app.crm.activities.completeTask(t.id, done)) } },
                        enabled = t.status != TaskStatus.CANCELLED,
                    )
                },
            )
        }
        TimelineEntry.Kind.APPOINTMENT -> {
            val a = data.appointments[entry.id] ?: return
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.appointmentEdit(a.id)) },
                leadingContent = { Icon(Icons.Filled.Event, null) },
                headlineContent = { Text(fmt.label(a.type) + " · " + a.title) },
                supportingContent = { Text(listOfNotNull(fmt.dateTime(a.startsAt), fmt.label(a.status), who.ifBlank { null }).joinToString("\n")) },
            )
        }
        TimelineEntry.Kind.OFFER -> {
            val o = data.offers[entry.id] ?: return
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.deal(o.dealId)) },
                leadingContent = { Icon(Icons.Filled.LocalOffer, null) },
                headlineContent = { Text(fmt.label(o.direction) + " · " + offerFigures(o, fmt)) },
                supportingContent = { Text(listOfNotNull(fmt.dateTime(o.offerDate), fmt.label(o.status), who.ifBlank { null }).joinToString("\n")) },
            )
        }
    }
}
