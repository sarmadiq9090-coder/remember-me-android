package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Task
import java.time.Instant

/** An icon-only button with a spoken label and the same label as a long-press tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color? = null) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        // 48 dp, so the touch target is the full size also inside a tooltip anchor.
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(Dimens.touch)) {
            if (tint != null) Icon(icon, contentDescription = label, tint = tint) else Icon(icon, contentDescription = label)
        }
    }
}

/** The app's mark (the Windows icon). */
@Composable
fun BrandMark(size: Dp = 32.dp) {
    Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(size))
}

/** A section heading: titleSmall with an optional count; the Done section's header is a toggle button. */
@Composable
fun SectionHeader(
    text: String,
    count: Int? = null,
    error: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    var m = Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(
        m.padding(start = Dimens.xxl, end = Dimens.l, top = Dimens.m, bottom = Dimens.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error) Icon(Ms.WarningAmber, null, tint = color, modifier = Modifier.padding(end = Dimens.s).size(20.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
        if (count != null) Text(
            count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.s),
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** The shape of item [index] of [count] in a grouped list: 20 dp outer corners, 4 dp inner ones. */
fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) Dimens.groupOuter else Dimens.groupInner
    val bottom = if (index == count - 1) Dimens.groupOuter else Dimens.groupInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** One item of a grouped list (2 dp apart, surfaceContainer, no dividers). */
@Composable
fun GroupItem(index: Int, count: Int, color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable () -> Unit) {
    Surface(
        color = color, shape = groupShape(index, count),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = 2.dp),
        content = content,
    )
}

/** A whole group of rows. */
@Composable
fun Group(rows: List<@Composable () -> Unit>) {
    Column { rows.forEachIndexed { i, row -> GroupItem(i, rows.size) { row() } } }
}

/** A settings-style row: leading icon, title, the current value under it, an optional trailing part. */
@Composable
fun SettingsRow(icon: ImageVector?, title: String, value: String? = null, onClick: (() -> Unit)? = null, overline: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    var m = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = Dimens.l, vertical = Dimens.m), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
        Column(Modifier.weight(1f)) {
            if (overline) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A row whose whole surface toggles a switch. */
@Composable
fun SwitchRow(icon: ImageVector?, title: String, hint: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Dimens.l, vertical = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
        Column(Modifier.weight(1f).padding(end = Dimens.m)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A single-choice dialog: a tap applies and closes; «إلغاء» dismisses. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    supporting: Map<String, String> = emptyMap(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { (code, text) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = code == selected, role = Role.RadioButton) { onSelect(code); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = code == selected, onClick = null)
                        Column(Modifier.padding(start = Dimens.l)) {
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                            supporting[code]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A confirmation dialog: dismiss first, confirm last (at the end); [destructive] colours the confirm. */
@Composable
fun ConfirmDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, dismissLabel: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel ?: stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
    )
}

/** The bottom area holding a screen's one primary action, above the gesture bar and the keyboard. */
@Composable
fun BottomActionBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Dimens.l, vertical = Dimens.m)) { content() }
    }
}

/** Secondary first (start), primary at the end, half width each; they stack at large text (primary on top). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonPair(stacked: Boolean, secondary: @Composable (Modifier) -> Unit, primary: @Composable (Modifier) -> Unit) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            primary(Modifier.fillMaxWidth())
            secondary(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            secondary(Modifier.weight(1f))
            primary(Modifier.weight(1f))
        }
    }
}

/** The warning or reminder banner under the top bar (only one at a time). */
@Composable
fun StatusBanner(icon: ImageVector?, text: String, action: String, alarm: Boolean, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    val bg = if (alarm) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (alarm) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = bg, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
        Row(Modifier.padding(start = Dimens.l, end = Dimens.xs, top = Dimens.xs, bottom = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.padding(end = Dimens.m))
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = Dimens.s))
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action, color = fg) }
            if (onDismiss != null) TooltipIconButton(Ms.Close, stringResource(R.string.not_now), onDismiss, tint = fg)
        }
    }
}

/** A time as digits plus a smaller «م/ص» run (DESIGN-SPEC §2.2). */
@Composable
fun TimeText(at: Instant, style: TextStyle, color: Color = Color.Unspecified, ampmSize: Int = 15) {
    val context = LocalContext.current
    val (digits, ampm) = L10n.timeParts(context, at)
    // Both runs sit on the same baseline, whatever their sizes.
    Row {
        Text(digits, style = style, color = color, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (ampm != null) Text(
            ampm, style = style.copy(fontSize = ampmSize.sp, fontFeatureSettings = null, lineHeight = (ampmSize * 1.4f).sp), color = color,
            modifier = Modifier.padding(start = Dimens.xs).alignByBaseline(), maxLines = 1,
        )
    }
}

/** Repeat, priority (words and an icon, never colour alone) and a non-default category, in one line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskMeta(t: Task, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, center: Boolean = false) {
    val context = LocalContext.current
    val repeat = L10n.repeatText(context, t)
    val hasCategory = t.category != Category.PERSONAL
    if (repeat == null && t.priority == Priority.NORMAL && !hasCategory) return
    FlowRow(
        horizontalArrangement = if (center) Arrangement.spacedBy(Dimens.s, Alignment.CenterHorizontally) else Arrangement.spacedBy(Dimens.s),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (repeat != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ms.Repeat, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dimens.xs))
            Text(repeat, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (t.priority != Priority.NORMAL) PriorityTag(t.priority)
        if (hasCategory) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ms.Label, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dimens.xs))
            Text(categoryName(t.category), style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
fun categoryName(c: Category) = stringResource(when (c) { Category.PERSONAL -> R.string.cat_personal; Category.WORK -> R.string.cat_work; Category.STUDY -> R.string.cat_study })

@Composable
fun priorityName(p: Priority) = stringResource(when (p) { Priority.NORMAL -> R.string.priority_normal; Priority.MEDIUM -> R.string.priority_medium; Priority.HIGH -> R.string.priority_high })

/** High: a filled flag on tertiaryContainer; medium: an outlined flag with a border. */
@Composable
fun PriorityTag(p: Priority) {
    val high = p == Priority.HIGH
    Surface(
        color = if (high) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
        contentColor = if (high) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = if (high) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = Dimens.s, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (high) Ms.Flag else Ms.OutlinedFlag, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dimens.xs))
            Text(priorityName(p), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
