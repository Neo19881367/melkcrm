package ir.melkcrm.app.ui.matching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.components.EmptyState
import ir.melkcrm.app.ui.components.Loading
import ir.melkcrm.app.ui.components.ScoreBadge
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.customers.requirementSummary
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.properties.priceSummary
import ir.melkcrm.core.matching.MatchComponent
import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.model.BuyerRequirement
import ir.melkcrm.core.model.Property
import ir.melkcrm.core.time.AgencyTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CompareViewModel(private val app: AppContainer, private val requirementId: String, private val propertyIds: List<String>) : ViewModel() {
    data class Entry(val property: Property, val evaluation: MatchEvaluation?)
    data class State(val requirement: BuyerRequirement?, val columns: List<Entry>)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    init {
        viewModelScope.launch {
            val crm = app.crm
            val cols = propertyIds.mapNotNull { id -> crm.properties.property(id)?.let { Entry(it, crm.matching.evaluate(requirementId, id)) } }
            _state.value = State(crm.customers.requirement(requirementId), cols)
        }
    }
}

private val COL = 150.dp
private val LABEL = 120.dp

@Composable
fun CompareScreen(requirementId: String, propertyIds: List<String>, nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val vm = viewModel(key = "compare-$requirementId-${propertyIds.joinToString(",")}") { CompareViewModel(app, requirementId, propertyIds) }
    val state by vm.state.collectAsStateWithLifecycle()
    val today = app.crm.writer.today()

    ScreenScaffold(title = stringResource(R.string.match_compare_title), onBack = { nav.back() }) { padding ->
        val s = state
        when {
            s == null -> Loading()
            s.columns.isEmpty() -> EmptyState(stringResource(R.string.record_not_found), Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
                s.requirement?.let { Text(requirementSummary(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row {
                        Text("", Modifier.width(LABEL))
                        for (c in s.columns) {
                            Column(Modifier.width(COL).padding(4.dp).clickable { nav.go(Routes.match(requirementId, c.property.id)) }) {
                                val e = c.evaluation
                                if (e != null) ScoreBadge(e.score, e.classification, fmt.label(e.classification), e.provisional)
                                Text(c.property.reference, fontWeight = FontWeight.Bold)
                                Text(c.property.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                    HorizontalDivider()
                    for ((label, value) in rows(fmt, today)) {
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(stringResource(label), Modifier.width(LABEL), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (c in s.columns) Text(value(c).ifBlank { "—" }, Modifier.width(COL).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                    for (comp in MatchComponent.entries) {
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(fmt.label(comp), Modifier.width(LABEL), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (c in s.columns) {
                                val score = c.evaluation?.components?.firstOrNull { it.component == comp }
                                Text(score?.let { fmt.num(it.points) + "/" + fmt.num(it.weight) } ?: "—", Modifier.width(COL).padding(horizontal = 4.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.match_concerns), Modifier.width(LABEL), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (c in s.columns) {
                            val e = c.evaluation
                            val lines = if (e == null) emptyList() else (e.exclusions + e.concerns).take(3).map { fmt.reasons.render(it) } + e.missing.take(2).map { "? " + fmt.reasons.render(it) }
                            Text(lines.joinToString("\n").ifBlank { "—" }, Modifier.width(COL).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun yesNo(v: Boolean?, fa: Boolean) = when (v) {
    true -> if (fa) "دارد" else "Yes"
    false -> if (fa) "ندارد" else "No"
    null -> ""
}

/** Attribute rows; values are recorded facts only, blank when not recorded. */
private fun rows(fmt: UiFormat, today: Long): List<Pair<Int, (CompareViewModel.Entry) -> String>> = listOf(
    R.string.property_price_section to { c: CompareViewModel.Entry -> priceSummary(c.property, fmt) },
    R.string.field_neighborhood to { c: CompareViewModel.Entry -> listOfNotNull(c.property.neighborhood, c.property.city).joinToString("، ") },
    R.string.property_built_area to { c: CompareViewModel.Entry -> c.property.effectiveArea?.let { fmt.area(it) }.orEmpty() },
    R.string.property_bedrooms to { c: CompareViewModel.Entry -> fmt.num(c.property.bedrooms) },
    R.string.property_floor to { c: CompareViewModel.Entry -> listOfNotNull(c.property.floor?.let { fmt.num(it) }, c.property.totalFloors?.let { fmt.num(it) }).joinToString(" / ") },
    R.string.property_building_age to { c: CompareViewModel.Entry ->
        c.property.constructionYear?.let { y -> fmt.num(AgencyTime.buildingAge(y, c.property.constructionYearCalendar, today)) }.orEmpty()
    },
    R.string.property_parking_spaces to { c: CompareViewModel.Entry -> fmt.num(c.property.parkingSpaces) },
    R.string.property_elevator to { c: CompareViewModel.Entry -> yesNo(c.property.elevator, fmt.fa) },
    R.string.property_storage to { c: CompareViewModel.Entry -> yesNo(c.property.storage, fmt.fa) },
    R.string.property_condition to { c: CompareViewModel.Entry -> fmt.label(c.property.condition) },
    R.string.property_document to { c: CompareViewModel.Entry -> fmt.label(c.property.documentStatus) },
    R.string.property_status to { c: CompareViewModel.Entry -> fmt.label(c.property.status) },
)
