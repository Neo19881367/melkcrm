package ir.melkcrm.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.errorText
import ir.melkcrm.client.forms.Input
import ir.melkcrm.client.forms.Parsed
import ir.melkcrm.core.model.RequirementLevel
import ir.melkcrm.core.money.CurrencyUnit
import ir.melkcrm.core.time.EpochDay

/** Single text field with the error (a validator code) shown under it. */
/** Masked single-line input for passwords and keys; the value is never shown or logged. */
@Composable
fun PasswordInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
    suffix: String? = null,
    enabled: Boolean = true,
) {
    val errorMsg = errorText(error)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        isError = errorMsg != null,
        supportingText = if (errorMsg != null || supporting != null) {
            { Text(errorMsg ?: supporting.orEmpty()) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        suffix = if (suffix != null) {
            { Text(suffix) }
        } else {
            null
        },
        enabled = enabled,
    )
}

@Composable
fun MultilineInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, supporting: String? = null) =
    TextInput(value, onChange, label, modifier, error, singleLine = false, minLines = 3, supporting = supporting)

@Composable
fun NumberInput(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, decimal: Boolean = false,
    suffix: String? = null, enabled: Boolean = true,
) = TextInput(value, onChange, label, modifier, error, keyboard = if (decimal) KeyboardType.Decimal else KeyboardType.Number, suffix = suffix, enabled = enabled)

/**
 * Money in the chosen unit (toman or rial, always labelled). The amount is repeated in words
 * ("۹٫۵ میلیارد تومان") under the field so a missing or extra zero is visible before saving.
 */
@Composable
fun MoneyInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, unit: CurrencyUnit? = null) {
    val fmt = LocalFormat.current
    val u = unit ?: fmt.unit
    val preview = when (val p = Input.money(value, u)) {
        is Parsed.Ok -> p.value?.let { ir.melkcrm.core.money.MoneyFormatter.compact(it, u, fmt.fa, fmt.digits) }
        is Parsed.Error -> null
    }
    TextInput(
        value = value, onChange = onChange, label = label, modifier = modifier, error = error,
        keyboard = KeyboardType.Number, suffix = ir.melkcrm.core.money.MoneyFormatter.unitLabel(u, fmt.fa), supporting = preview,
    )
}

/** Hours and minutes, typed as 14:30 (Persian digits accepted). */
@Composable
fun TimeInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null) =
    TextInput(value, onChange, label, modifier, error, keyboard = KeyboardType.Number, supporting = "HH:MM")

/** A read-only field that opens the Jalali/Gregorian date picker. */
@Composable
fun DateInput(value: EpochDay?, onChange: (EpochDay?) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, clearable: Boolean = true) {
    val fmt = LocalFormat.current
    var open by remember { mutableStateOf(false) }
    val errorMsg = errorText(error)
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value?.let { fmt.longDate(it, weekday = true) } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = errorMsg != null,
            supportingText = if (errorMsg != null) {
                { Text(errorMsg) }
            } else {
                null
            },
            trailingIcon = {
                if (value != null && clearable) {
                    IconButton(onClick = { onChange(null) }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                } else {
                    Icon(Icons.Filled.CalendarMonth, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay so a tap anywhere on the field opens the picker (the clear button stays usable).
        Box(
            Modifier.matchParentSize().padding(end = if (value != null && clearable) 56.dp else 0.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = true },
        )
    }
    if (open) {
        CalendarDatePickerDialog(
            initial = value,
            onDismiss = { open = false },
            onPick = { onChange(it); open = false },
        )
    }
}

/** Single choice from a list; [allowNone] adds a "not set" entry. */
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T?) -> Unit,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
    error: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val errorMsg = errorText(error)
    val noneText = stringResource(R.string.not_set)
    val labels = options.map { optionLabel(it) }
    val selectedText = selected?.let { s -> labels.getOrNull(options.indexOf(s)) } ?: ""
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(noneText) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            isError = errorMsg != null,
            supportingText = if (errorMsg != null) {
                { Text(errorMsg) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize()
                .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) DropdownMenuItem(text = { Text(noneText) }, onClick = { onSelect(null); expanded = false })
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(labels[i]) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

/** Enum dropdown labelled with the shared Persian/English vocabulary. */
@Composable
fun <E : Enum<E>> EnumDropdown(label: String, options: List<E>, selected: E?, onSelect: (E?) -> Unit, modifier: Modifier = Modifier, allowNone: Boolean = true, error: String? = null) {
    val fmt = LocalFormat.current
    Dropdown(label, options, selected, onSelect, { fmt.label(it) }, modifier, allowNone, error)
}

/** Several choices as chips. */
@Composable
fun <T> MultiChips(label: String?, options: List<T>, selected: Set<T>, onChange: (Set<T>) -> Unit, optionLabel: @Composable (T) -> String, error: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in options) {
                val on = o in selected
                FilterChip(selected = on, onClick = { onChange(if (on) selected - o else selected + o) }, label = { Text(optionLabel(o)) })
            }
        }
        errorText(error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun <E : Enum<E>> EnumChips(label: String?, options: List<E>, selected: Set<E>, onChange: (Set<E>) -> Unit, error: String? = null) {
    val fmt = LocalFormat.current
    MultiChips(label, options, selected, onChange, { fmt.label(it) }, error)
}

/**
 * Yes / no / not recorded. "Not recorded" is a real value: the matching engine treats it as
 * missing data, never as "no".
 */
@Composable
fun TriStateInput(label: String, value: Boolean?, onChange: (Boolean?) -> Unit, yes: String = stringResource(R.string.tri_yes), no: String = stringResource(R.string.tri_no)) {
    val options = listOf(true to yes, false to no, null to stringResource(R.string.tri_unknown))
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, text) ->
                SegmentedButton(selected = value == v, onClick = { onChange(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(text) }
            }
        }
    }
}

/** Required / preferred / not important. Only "required" can exclude a property. */
@Composable
fun LevelInput(label: String, value: RequirementLevel, onChange: (RequirementLevel) -> Unit) {
    val fmt = LocalFormat.current
    val levels = RequirementLevel.entries
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { i, l ->
                SegmentedButton(selected = value == l, onClick = { onChange(l) }, shape = SegmentedButtonDefaults.itemShape(i, levels.size)) { Text(fmt.label(l)) }
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, supporting: String? = null, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
