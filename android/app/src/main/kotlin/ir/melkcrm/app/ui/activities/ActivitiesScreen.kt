package ir.melkcrm.app.ui.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.Dropdown
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SyncButton
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.client.view.CalendarMonth
import ir.melkcrm.client.view.TimelineEntry
import ir.melkcrm.core.model.Deal
import ir.melkcrm.core.model.TaskItem
import ir.melkcrm.core.model.TaskStatus
import ir.melkcrm.core.permissions.Permission
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.time.AgencyTime
import ir.melkcrm.core.time.DateFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ActivitiesViewModel(app: AppContainer) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val timeline: StateFlow<TimelineData?> = timelineFlow(app, null, null).stateIn(viewModelScope, started, null)
    val deals: StateFlow<List<Deal>> = app.crm.deals.deals.map { l -> l.sortedWith(compareBy<Deal> { it.stage.isTerminal }.thenByDescending { it.updatedAt }) }
        .stateIn(viewModelScope, started, emptyList())
}

private val tabs = listOf(R.string.tab_timeline, R.string.tab_task_list, R.string.tab_calendar, R.string.tab_deals)

@Composable
fun ActivitiesScreen(nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel { ActivitiesViewModel(app) }
    val data by vm.timeline.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val user = app.crm.session.user
    val seesOthers = agency.policy.has(user, Permission.VIEW_TEAM_RECORDS) || agency.policy.has(user, Permission.VIEW_ALL_RECORDS)
    var agentId by rememberSaveable { mutableStateOf<String?>(null) }

    ScreenScaffold(title = stringResource(R.string.activities_title), onBack = null, onMenu = nav.openDrawer, actions = { SyncButton(nav) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.go(Routes.activityEdit(null)) }, label = { Text(stringResource(R.string.log_activity)) })
                AssistChip(onClick = { nav.go(Routes.taskEdit(null)) }, label = { Text(stringResource(R.string.task_new)) })
                AssistChip(onClick = { nav.go(Routes.appointmentEdit(null)) }, label = { Text(stringResource(R.string.appointment_new)) })
                if (seesOthers) {
                    Dropdown(
                        label = stringResource(R.string.filter_agent), options = agency.users, selected = agency.users.firstOrNull { it.id == agentId },
                        onSelect = { agentId = it?.id }, optionLabel = { it.displayName }, modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
            val d = data
            if (d == null) {
                Loading()
            } else {
                val filtered = if (agentId == null) d else d.copy(entries = d.entries.filter { e -> agentOf(e, d) == agentId })
                when (tab) {
                    0 -> TimelineTab(filtered, nav)
                    1 -> TasksTab(filtered, nav)
                    2 -> CalendarTab(filtered, nav)
                    else -> DealsTab(vm, d, agentId, nav)
                }
            }
        }
    }
}

private fun agentOf(e: TimelineEntry, d: TimelineData): String? = when (e.kind) {
    TimelineEntry.Kind.ACTIVITY -> d.activities[e.id]?.agentId
    TimelineEntry.Kind.TASK -> d.tasks[e.id]?.assignedAgentId
    TimelineEntry.Kind.APPOINTMENT -> d.appointments[e.id]?.agentId
    TimelineEntry.Kind.OFFER -> d.offers[e.id]?.agentId
}

@Composable
private fun TimelineTab(d: TimelineData, nav: Nav) {
    val entries = d.entries.filter { it.kind != TimelineEntry.Kind.TASK || d.tasks[it.id]?.status != TaskStatus.OPEN }
    if (entries.isEmpty()) {
        EmptyState(stringResource(R.string.empty_list))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.kind.name + it.id }) { e ->
            TimelineRow(e, d, nav)
            HorizontalDivider()
        }
    }
}

@Composable
private fun TasksTab(d: TimelineData, nav: Nav) {
    val app = LocalApp.current
    val today = app.crm.writer.today()
    var showDone by rememberSaveable { mutableStateOf(false) }
    val tasks = d.entries.filter { it.kind == TimelineEntry.Kind.TASK }
        .mapNotNull { e -> d.tasks[e.id]?.let { e to it } }
        .filter { (_, t) -> showDone || t.status == TaskStatus.OPEN }
        .sortedWith(compareBy<Pair<TimelineEntry, TaskItem>>({ it.second.status != TaskStatus.OPEN }, { it.second.dueDate }, { it.second.dueTimeMinutes ?: 0 }))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val overdue = tasks.count { it.second.isOverdue(today) }
            val dueToday = tasks.count { it.second.isDueToday(today) }
            val fmt = LocalFormat.current
            Text(stringResource(R.string.task_overdue) + ": " + fmt.num(overdue), color = if (overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.task_due_today) + ": " + fmt.num(dueToday))
            FilterChip(showDone, { showDone = !showDone }, { Text(stringResource(R.string.task_show_done)) })
        }
        if (tasks.isEmpty()) {
            EmptyState(stringResource(R.string.empty_list))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = { it.first.id }) { (e, _) ->
                    TimelineRow(e, d, nav)
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Month view in the user's calendar (Jalali by default) with the day's appointments and tasks under it. */
@Composable
private fun CalendarTab(d: TimelineData, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val zone = app.crm.writer.zone
    val today = app.crm.writer.today()
    var selected by rememberSaveable { mutableLongStateOf(today) }
    var month by remember { mutableStateOf(CalendarMonth.of(today, fmt.settings.calendar)) }
    fun dayOf(e: TimelineEntry): Long? = when (e.kind) {
        TimelineEntry.Kind.APPOINTMENT -> d.appointments[e.id]?.let { AgencyTime.epochDayOf(it.startsAt, zone) }
        TimelineEntry.Kind.TASK -> d.tasks[e.id]?.takeIf { it.status == TaskStatus.OPEN }?.dueDate
        else -> null
    }
    val byDay = remember(d) { d.entries.mapNotNull { e -> dayOf(e)?.let { it to e } }.groupBy({ it.first }, { it.second }) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.previous() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month)) }
                Text(DateFormatter.monthLabel(month.year, month.month, fmt.display), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { month = month.next() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                for (h in CalendarMonth.weekdayHeaders(fmt.lang)) Text(h, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
            for (week in month.weeks()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    for (day in week) {
                        Box(Modifier.weight(1f).aspectRatio(1.2f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (day != null) {
                                val isSel = day == selected
                                Column(
                                    Modifier.fillMaxSize().clip(MaterialTheme.shapes.small)
                                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                        .clickable { selected = day },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        Digits.localize(CalendarMonth.dayNumber(day, month.calendar).toString(), fmt.digits),
                                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (byDay[day].orEmpty().isNotEmpty()) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(fmt.longDate(selected, weekday = true), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        }
        val dayEntries = byDay[selected].orEmpty().sortedBy { it.at }
        if (dayEntries.isEmpty()) item { EmptyState(stringResource(R.string.empty_list)) }
        items(dayEntries, key = { it.kind.name + it.id }) { e ->
            TimelineRow(e, d, nav)
            HorizontalDivider()
        }
    }
}

@Composable
private fun DealsTab(vm: ActivitiesViewModel, d: TimelineData, agentId: String?, nav: Nav) {
    val fmt = LocalFormat.current
    val deals by vm.deals.collectAsStateWithLifecycle()
    val shown = deals.filter { agentId == null || it.agentId == agentId }
    if (shown.isEmpty()) {
        EmptyState(stringResource(R.string.deals_none))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { deal ->
            ListItem(
                modifier = Modifier.clickable { nav.go(Routes.deal(deal.id)) },
                overlineContent = { Text(fmt.label(deal.dealType) + " · " + fmt.label(deal.stage)) },
                headlineContent = { Text((d.customers[deal.buyerCustomerId]?.fullName ?: "") + " ← " + (d.properties[deal.propertyId]?.reference ?: "")) },
                supportingContent = { Text(fmt.dateTime(deal.updatedAt)) },
            )
            HorizontalDivider()
        }
    }
}
