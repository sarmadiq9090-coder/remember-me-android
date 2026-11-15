package com.fastkiwi.rememberme.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.TooltipIconButton

/** A group heading inside the editor (titleSmall in primary, DESIGN-SPEC §5.5). */
@Composable
fun EditorHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.xxl, end = Dimens.l, top = Dimens.l, bottom = Dimens.s)
            .semantics { heading() },
    )
}

/** A tonal chip that opens a picker: 48 dp, the value as its text, [label] spoken first. */
@Composable
fun PickerChip(label: String, value: String, supporting: String? = null, onClick: () -> Unit) {
    val separator = stringResource(R.string.list_separator)
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest,
        // The interface's separator («، » in Arabic, ", " in English; QC 1.0.0 Q3).
        modifier = Modifier.heightIn(min = Dimens.touch).semantics { contentDescription = listOfNotNull(label, value, supporting).joinToString(separator) },
    ) {
        Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalArrangement = Arrangement.Center) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * «الموعد» (DESIGN-SPEC §5.5, §6): `event` + [date chip] [time chip] + × «بلا موعد»; without a date, one row
 * «أضف موعدًا». [hijri] is the date chip's supporting line when the Hijri display is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DateTimeChips(
    dateLabel: String?,
    timeLabel: String?,
    hijri: String?,
    onDate: () -> Unit,
    onTime: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
) {
    if (dateLabel == null || timeLabel == null) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).clickable(role = Role.Button, onClick = onAdd).padding(horizontal = Dimens.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ms.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = Dimens.l))
            Text(stringResource(R.string.add_date), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ms.Event, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
        // Side by side; stacked at large text (§3.6). (A FlowRow here measured the chips far too narrow.)
        val chips: @Composable () -> Unit = {
            PickerChip(stringResource(R.string.date), dateLabel, hijri, onDate)
            PickerChip(stringResource(R.string.time), timeLabel, null, onTime)
        }
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s)) { chips() }
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) { chips() }
        }
        TooltipIconButton(Ms.Close, stringResource(R.string.no_date), onClear)
    }
}

/** One alert of [AlertListEditor]: [minutes] 0 = at the time; [moment] the computed time, as text. */
data class AlertItem(val minutes: Int, val on: Boolean, val title: String, val moment: String?)

/**
 * «التنبيهات» (F-046): the at-time row first (always on: it is the reminder itself), then up to [max] early
 * alerts with their moment and a switch; a tap on an early alert changes or removes it; «إضافة تنبيه» last,
 * gone at [max].
 */
@Composable
fun AlertListEditor(alerts: List<AlertItem>, onToggle: (index: Int, on: Boolean) -> Unit, onEdit: (index: Int) -> Unit, onAdd: () -> Unit, max: Int = 3) {
    val early = alerts.count { it.minutes > 0 }
    val canAdd = early < max
    val count = alerts.size + if (canAdd) 1 else 0
    Column {
        alerts.forEachIndexed { i, a ->
            GroupItem(i, count) {
                val rowMod = Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact)
                Row(
                    (if (a.minutes > 0) rowMod.clickable(role = Role.Button) { onEdit(i) } else rowMod).padding(start = Dimens.l, end = Dimens.l, top = Dimens.s, bottom = Dimens.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ms.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
                    Column(Modifier.weight(1f).padding(end = Dimens.m)) {
                        Text(a.title, style = MaterialTheme.typography.bodyLarge)
                        a.moment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (a.minutes > 0) {
                        Switch(checked = a.on, onCheckedChange = { on -> onToggle(i, on) }, modifier = Modifier.semantics { contentDescription = a.title })
                    } else {
                        // G-17: the at-time alert is the reminder itself, so it is never off. A disabled switch read as
                        // "off" (grey); a check mark and «دائمًا» say what it is, and nothing here looks tappable.
                        Row(Modifier.testTag("alert-at-time-always"), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ms.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.fs_always), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = Dimens.xs))
                        }
                    }
                }
            }
        }
        if (canAdd) GroupItem(alerts.size, count) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).clickable(role = Role.Button, onClick = onAdd).padding(horizontal = Dimens.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ms.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = Dimens.l))
                Text(stringResource(R.string.alert_add), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * A folded group (DESIGN-SPEC §6): a 72 dp header row (`Role.Button`, stateDescription «مطويّة / ظاهرة») with
 * the summary of the current values, then [rows] when [expanded].
 */
@Composable
fun CollapsibleGroup(icon: ImageVector, title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, rows: List<@Composable () -> Unit>) {
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val count = 1 + if (expanded) rows.size else 0
    Column {
        GroupItem(0, count) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onToggle)
                    .semantics(mergeDescendants = true) { role = Role.Button; stateDescription = state }
                    .padding(horizontal = Dimens.l, vertical = Dimens.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                    if (!expanded) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Ms.ExpandLess else Ms.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) rows.forEachIndexed { i, row -> GroupItem(i + 1, count) { row() } }
    }
}

/** A selectable card (alert style, DESIGN-SPEC §5.7): whole card `Role.RadioButton`; selected = 2 dp primary border + radio. */
@Composable
fun ChoiceCard(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit, extra: @Composable (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(Dimens.l),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(Dimens.m))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Dimens.s))
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.xs))
                }
            }
            extra?.let { Box(Modifier.padding(start = Dimens.xl, end = Dimens.l, bottom = Dimens.s)) { it() } }
        }
    }
}

/** A radio row (56 dp, the whole row selectable). */
@Composable
fun RadioRow(text: String, selected: Boolean, supporting: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = Dimens.l)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
