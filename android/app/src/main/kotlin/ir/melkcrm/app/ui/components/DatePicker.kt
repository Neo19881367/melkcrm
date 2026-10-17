package ir.melkcrm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.client.view.CalendarMonth
import ir.melkcrm.core.text.Digits
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.EpochDay

/**
 * Date picker in the user's calendar (Jalali by default, weeks starting on Saturday). The picked
 * value is the Gregorian epoch day that is stored, so switching calendars never changes data.
 * The arrows are auto-mirrored, so in Persian (right to left) "next month" points left.
 */
@Composable
fun CalendarDatePickerDialog(initial: EpochDay?, onDismiss: () -> Unit, onPick: (EpochDay) -> Unit) {
    val fmt = LocalFormat.current
    val today = LocalApp.current.crm.writer.today()
    var month by remember { mutableStateOf(CalendarMonth.of(initial ?: today, fmt.settings.calendar)) }
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { selected?.let(onPick) }, enabled = selected != null) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { selected = today; month = CalendarMonth.of(today, fmt.settings.calendar) }) { Text(stringResource(R.string.today)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        title = { Text(selected?.let { fmt.longDate(it, weekday = true) } ?: stringResource(R.string.select_date)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.previous() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month))
                    }
                    Text(
                        DateFormatter.monthLabel(month.year, month.month, fmt.display),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = { month = month.next() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    for (h in CalendarMonth.weekdayHeaders(fmt.lang)) {
                        Text(h, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                    }
                }
                for (week in month.weeks()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (day in week) {
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val isSel = day == selected
                                    val isToday = day == today
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                            .clickable { selected = day },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            Digits.localize(CalendarMonth.dayNumber(day, month.calendar).toString(), fmt.digits),
                                            color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
