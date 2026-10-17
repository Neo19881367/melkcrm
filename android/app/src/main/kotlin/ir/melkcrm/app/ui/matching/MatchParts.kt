package ir.melkcrm.app.ui.matching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.ScoreBadge
import ir.melkcrm.app.ui.components.classColor
import ir.melkcrm.client.view.MatchFilter
import ir.melkcrm.core.matching.MatchClass
import ir.melkcrm.core.matching.MatchEvaluation
import ir.melkcrm.core.matching.MatchRecord
import ir.melkcrm.core.matching.MatchReviewStatus
import ir.melkcrm.core.matching.Reason

/** A ranked match: the score and class, who or what it is, the strongest reason and the first concern. */
@Composable
fun MatchRow(e: MatchEvaluation, record: MatchRecord?, headline: String, overline: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val fmt = LocalFormat.current
    val lines = buildList {
        if (!e.eligible) {
            e.exclusions.firstOrNull()?.let { add(stringResource(R.string.match_excluded_because) + " " + fmt.reasons.render(it)) }
        } else {
            e.reasons.firstOrNull()?.let { add("✓ " + fmt.reasons.render(it)) }
            e.concerns.firstOrNull()?.let { add("! " + fmt.reasons.render(it)) }
            if (e.missing.isNotEmpty()) add(stringResource(R.string.match_missing) + ": " + fmt.num(e.missing.size))
        }
        val status = record?.reviewStatus
        if (status != null && status != MatchReviewStatus.NEW) add(fmt.label(status) + if (record.changedSinceReview) " · " + stringResource(R.string.match_updated_badge) else "")
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { ScoreBadge(e.score, e.classification, fmt.label(e.classification), e.provisional) },
        overlineContent = if (overline != null) {
            { Text(overline) }
        } else {
            null
        },
        headlineContent = { Text(headline) },
        supportingContent = { Text(lines.joinToString("\n"), maxLines = 4) },
        trailingContent = trailing,
    )
}

/** Points per component against its weight; the points add up exactly to the total score. */
@Composable
fun ComponentBars(e: MatchEvaluation) {
    val fmt = LocalFormat.current
    Column(Modifier.fillMaxWidth()) {
        for (c in e.components) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.label(c.component), Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val frac = if (c.weight == 0) 0f else (c.points.toFloat() / c.weight).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(classColor(e.classification)))
                }
                Text(fmt.num(c.points) + "/" + fmt.num(c.weight), Modifier.width(56.dp).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ReasonList(title: String, reasons: List<Reason>, color: Color = MaterialTheme.colorScheme.onSurface, mark: String) {
    if (reasons.isEmpty()) return
    val fmt = LocalFormat.current
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    for (r in reasons) Text("$mark ${fmt.reasons.render(r)}", color = color, style = MaterialTheme.typography.bodyMedium)
}

/** Minimum class, hide rejected or snoozed, only unreviewed, and whether excluded candidates are listed with their reasons. */
@Composable
fun MatchFilterBar(filter: MatchFilter, onChange: (MatchFilter) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            stringResource(R.string.match_min_class), listOf(MatchClass.EXCELLENT, MatchClass.STRONG, MatchClass.POSSIBLE, MatchClass.WEAK),
            filter.minClass, { v -> if (v != null) onChange(filter.copy(minClass = v)) }, Modifier.width(170.dp), allowNone = false,
        )
        FilterChip(filter.onlyUnreviewed, { onChange(filter.copy(onlyUnreviewed = !filter.onlyUnreviewed)) }, { Text(stringResource(R.string.match_only_unreviewed)) }, Modifier.padding(top = 12.dp))
        FilterChip(!filter.hideRejected, { onChange(filter.copy(hideRejected = !filter.hideRejected)) }, { Text(stringResource(R.string.match_show_rejected)) }, Modifier.padding(top = 12.dp))
        FilterChip(!filter.hideSnoozed, { onChange(filter.copy(hideSnoozed = !filter.hideSnoozed)) }, { Text(stringResource(R.string.match_show_snoozed)) }, Modifier.padding(top = 12.dp))
        FilterChip(filter.showExcluded, { onChange(filter.copy(showExcluded = !filter.showExcluded)) }, { Text(stringResource(R.string.match_show_excluded)) }, Modifier.padding(top = 12.dp))
    }
}
