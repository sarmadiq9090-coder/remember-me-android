package com.fastkiwi.rememberme.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SwitchRow
import com.fastkiwi.rememberme.ui.TooltipIconButton
import kotlin.math.abs

/**
 * Steps (DESIGN-SPEC §5.18, F-070, F-071): «الخطوات» with «2 من 5» and a progress bar; rows with a 48 dp
 * checkbox, the text (done = muted and struck through; the state is also the checkbox's), a drag handle
 * (TalkBack: «انقل لأعلى» / «انقل لأسفل»); «أضف خطوة» (Enter adds the next); the auto-complete switch.
 * At most 50 steps. Blank steps are dropped when saved.
 */
@Composable
fun StepsEditor(
    steps: List<Organize.Step>,
    onToggle: (index: Int, done: Boolean) -> Unit,
    onEdit: (index: Int, text: String) -> Unit,
    onAdd: (after: Int?) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    autoComplete: Boolean,
    onAutoComplete: (Boolean) -> Unit,
    focusIndex: Int? = null,
) {
    val done = steps.count { it.done }
    Column {
        if (steps.isNotEmpty()) {
            val progress = stringResource(R.string.steps_progress, done.toString(), steps.size.toString())
            Row(Modifier.fillMaxWidth().padding(start = Dimens.xxl, end = Dimens.xxl, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.steps), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(progress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { done.toFloat() / steps.size },
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xxl, vertical = Dimens.s).semantics { contentDescription = progress },
            )
        }
        val canAdd = steps.size < Organize.MAX_STEPS
        val count = steps.size + 1
        steps.forEachIndexed { i, step ->
            GroupItem(i, count) { StepRow(i, steps.size, step, onToggle, onEdit, onAdd, onMove, onRemove, focus = focusIndex == i) }
        }
        GroupItem(steps.size, count) {
            if (canAdd) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).clickable { onAdd(null) }.padding(horizontal = Dimens.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ms.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = Dimens.l))
                    Text(stringResource(R.string.step_add), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text(stringResource(R.string.steps_max), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimens.l))
            }
        }
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.s))
            GroupItem(0, 1) { SwitchRow(Ms.Checklist, stringResource(R.string.steps_autocomplete), null, autoComplete, onAutoComplete) }
        }
    }
}

/**
 * One step. Ticking is the obvious action (black-box U2-6): the whole row, checkbox and text, toggles it and
 * is at least 48 dp tall. The text is edited with the labelled pencil button «عدّل الخطوة» (a new, blank step
 * opens ready to type); «أنهِ تعديل الخطوة», Enter or leaving the field ends the edit. Reorder (handle and
 * TalkBack «انقل لأعلى» / «انقل لأسفل») and «احذف الخطوة» are unchanged.
 */
@Composable
private fun StepRow(
    i: Int, size: Int, step: Organize.Step,
    onToggle: (Int, Boolean) -> Unit, onEdit: (Int, String) -> Unit, onAdd: (Int?) -> Unit,
    onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, focus: Boolean,
) {
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val remove = stringResource(R.string.step_remove)
    val doneState = stringResource(R.string.step_done_state)
    val openState = stringResource(R.string.step_open_state)
    val label = step.text.ifBlank { stringResource(R.string.bbfix_step_new) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // A blank step (just added) opens ready to type; any other step shows its text, and a tap ticks it.
    var editing by rememberSaveable(step.id) { mutableStateOf(step.text.isBlank()) }
    var askFocus by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val text by rememberUpdatedState(step.text)
    LaunchedEffect(focus) { if (focus) editing = true }
    LaunchedEffect(editing, focus, askFocus) { if (editing && (focus || askFocus)) { runCatching { requester.requestFocus() }; askFocus = false } }
    val rowHeight = with(LocalDensity.current) { Dimens.rowCompact.toPx() }
    val index by rememberUpdatedState(i)
    val total by rememberUpdatedState(size)
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (step.done) TextDecoration.LineThrough else null,
    )
    val tick = Modifier.toggleable(value = step.done, role = Role.Checkbox) { onToggle(i, it) }
        .semantics { contentDescription = label; stateDescription = if (step.done) doneState else openState }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).padding(horizontal = Dimens.xs)
            .semantics {
                customActions = buildList {
                    if (i > 0) add(CustomAccessibilityAction(up) { onMove(i, i - 1); true })
                    if (i < size - 1) add(CustomAccessibilityAction(down) { onMove(i, i + 1); true })
                    add(CustomAccessibilityAction(remove) { onRemove(i); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!editing) {
            // The checkbox and the text are one 48 dp+ target that ticks the step.
            Row(Modifier.weight(1f).heightIn(min = Dimens.rowCompact).then(tick), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = step.done, onCheckedChange = null, modifier = Modifier.padding(Dimens.m))
                Text(step.text, style = textStyle, modifier = Modifier.weight(1f).padding(end = Dimens.s, top = Dimens.s, bottom = Dimens.s))
            }
            TooltipIconButton(Ms.Edit, stringResource(R.string.bbfix_step_edit, step.text), { editing = true; askFocus = true })
        } else {
            Box(Modifier.size(Dimens.touch).then(tick), contentAlignment = Alignment.Center) {
                Checkbox(checked = step.done, onCheckedChange = null)
            }
            BasicTextField(
                value = step.text, onValueChange = { onEdit(i, it.take(Organize.STEP_CHARS)) },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onAdd(i) }),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = Dimens.s).focusRequester(requester)
                    .onFocusChanged {
                        // Leaving the field ends the edit (a blank step stays open to type).
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus) { hadFocus = false; if (text.isNotBlank()) editing = false }
                    },
            )
            TooltipIconButton(Ms.Check, stringResource(R.string.bbfix_step_edit_done), {
                focusManager.clearFocus()
                if (text.isNotBlank()) editing = false
            })
        }
        // The handle: drag up or down by a row's height to move the step one place.
        Box(
            Modifier.size(Dimens.touch).pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; drag = 0f },
                    onDragEnd = { dragging = false; drag = 0f },
                    onDragCancel = { dragging = false; drag = 0f },
                ) { change, dy ->
                    change.consume()
                    drag += dy
                    if (abs(drag) >= rowHeight) {
                        val to = (index + if (drag > 0) 1 else -1).coerceIn(0, total - 1)
                        if (to != index) onMove(index, to)
                        drag = 0f
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Ms.DragIndicator, stringResource(R.string.drag_reorder),
                tint = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
