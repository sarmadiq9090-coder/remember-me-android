package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.localToInstant
import com.fastkiwi.rememberme.core.quickSnooze
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.core.withSteps
import com.fastkiwi.rememberme.ui.home.StateTag
import com.fastkiwi.rememberme.ui.home.TagKind
import com.fastkiwi.rememberme.ui.home.snoozeChoices
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Label of a snooze choice: "10 minutes", "Tomorrow, same time", "Today 6:00 PM", "Tomorrow 9:00 AM", "Next week", "Pick a time…". */
fun presetLabel(context: android.content.Context, p: SnoozePreset, now: Instant, zone: ZoneId): String = when (p) {
    is SnoozePreset.In -> if (p.minutes == 24 * 60) context.getString(R.string.home_tomorrow_same) else L10n.duration(context, p.minutes.toLong())
    is SnoozePreset.Today -> context.getString(R.string.day_at_time, context.getString(R.string.today), L10n.time(context, localToInstant(LocalDateTime.of(now.atZone(zone).toLocalDate(), p.time), zone), zone))
    is SnoozePreset.Tomorrow -> context.getString(R.string.day_at_time, context.getString(R.string.tomorrow), L10n.time(context, localToInstant(LocalDateTime.of(now.atZone(zone).toLocalDate().plusDays(1), p.time), zone), zone))
    SnoozePreset.Week -> context.getString(R.string.next_week)
    SnoozePreset.Pick -> context.getString(R.string.pick_time)
}

/**
 * Ignores pointer taps for [ms] after the screen (or the next reminder of the queue, [key]) appears
 * (DESIGN-SPEC §5.8, W7). Only pointer input is held back: accessibility actions are never blocked.
 */
@Composable
fun rememberTapGuard(key: Any?, ms: Long): Modifier {
    var armed by remember(key) { mutableStateOf(ms <= 0) }
    LaunchedEffect(key, ms) { if (ms > 0) { delay(ms); armed = true } }
    return Modifier.pointerInput(armed) {
        if (armed) return@pointerInput
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** One cell of the dock's grid: a snooze choice, or «أجّل حتى…» ([at] null). */
data class DockChoice(val label: String, val at: Instant?)

/**
 * The reminder screen's dock (DESIGN-SPEC §5.8): it never scrolls. From the top: the F-045 hint (when
 * given), «أجّل مدة أخرى» with a 3 × 2 grid of the other choices and «أجّل حتى…» ([grid] false: shown in the
 * hero instead), «تخطَّ هذه المرة» for repeats, then the pair «أجّل <first preset>» (tonal, start) and «تم»
 * (filled, end), 64 dp with a 16 dp gap. At large text the pair stacks with «تم» on top.
 */
@Composable
fun AlertDock(
    presets: List<DockChoice>,
    defaultSnooze: String,
    showSkip: Boolean,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onPreset: (Int) -> Unit,
    onPickTime: () -> Unit,
    onSkip: () -> Unit,
    guardMs: Long = 0,
    grid: Boolean = true,
    hint: String? = null,
) {
    val bigText = LocalDensity.current.fontScale >= 1.5f
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = SheetShape, modifier = rememberTapGuard(Unit, guardMs)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (grid) SnoozeGrid(presets, onPreset, onPickTime)
            if (showSkip) TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.skip_this_time), textAlign = TextAlign.Center)
            }
            val done: @Composable (Modifier) -> Unit = { m ->
                Button(onClick = onDone, modifier = m.heightIn(min = Dimens.alertButton)) {
                    Icon(Ms.Check, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(Dimens.s))
                    Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                }
            }
            val snooze: @Composable (Modifier) -> Unit = { m ->
                FilledTonalButton(onClick = onSnooze, modifier = m.heightIn(min = Dimens.alertButton)) {
                    Icon(Ms.Snooze, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(Dimens.s))
                    Text(stringResource(R.string.snooze_for, defaultSnooze), style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
                        textAlign = TextAlign.Center)
                }
            }
            if (bigText) Column(verticalArrangement = Arrangement.spacedBy(Dimens.alertGap)) {
                done(Modifier.fillMaxWidth())
                snooze(Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(Dimens.alertGap)) {
                snooze(Modifier.weight(1f))
                done(Modifier.weight(1f))
            }
        }
    }
}

/** «أجّل مدة أخرى» and the grid: three per row (two at large text). */
@Composable
private fun SnoozeGrid(presets: List<DockChoice>, onPreset: (Int) -> Unit, onPickTime: () -> Unit) {
    val bigText = LocalDensity.current.fontScale >= 1.5f
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(stringResource(R.string.snooze_more), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        presets.withIndex().chunked(if (bigText) 2 else 3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                row.forEach { (i, c) ->
                    OutlinedButton(
                        onClick = { if (c.at == null) onPickTime() else onPreset(i) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.s, vertical = Dimens.xs),
                        modifier = Modifier.weight(1f).heightIn(min = Dimens.touch),
                    ) { Text(c.label, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                // Keep the cells the same width in a short last row.
                repeat((if (bigText) 2 else 3) - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The reminder screen (DESIGN-SPEC §5.8): the hero (style disc and tag, the time in displayLarge, on time
 * or late, «وصل متأخرًا …» when the alert itself came late (F-025), the title, repeat and priority, the note,
 * the steps) scrolls; the dock never does. Pointer taps are ignored for [guardMs] after a reminder appears.
 * It stays until every due reminder is dealt with. [hidden]: lock-screen privacy, the title is not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertScreen(
    task: Task,
    position: Int,
    count: Int,
    presets: List<SnoozePreset>,
    now: Instant,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: (Instant) -> Unit,
    onEdit: () -> Unit,
    onLater: () -> Unit,
    hidden: Boolean = false,
    pickFirst: Boolean = false,
    /** How late the alert was posted (from the alert log), or null when unknown. */
    arrivedLateMs: Long? = null,
    /** The phone was off at the due time (it started after it). */
    lateReasonOff: Boolean = false,
    /** The tone is playing now (a volume key silences it). */
    ringing: Boolean = false,
    onToggleStep: ((String) -> Unit)? = null,
    guardMs: Long = Behaviour.AlertTapGuardMs,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var picking by rememberSaveable(task.id) { mutableStateOf(pickFirst) }
    val due = task.dueAt!!
    val minutesLate = (now.toEpochMilli() - due.toEpochMilli()) / 60_000
    val late = minutesLate >= 1
    val title = if (hidden) stringResource(R.string.you_have_reminder) else task.title
    val alarm = AlertPolicy.style(task) == AlertPolicy.Style.ALARM
    // The first choice that applies now goes on the big button; the rest (and «أجّل حتى…») fill the grid.
    val choices = snoozeChoices(context, presets, now, zone)
    val first = choices.firstOrNull()
    val firstAt = first?.at ?: quickSnooze(presets, now, zone)
    val firstLabel = first?.label ?: L10n.duration(context, 10)
    val grid = choices.drop(1).take(5).map { DockChoice(it.label, it.at) } + DockChoice(stringResource(R.string.snooze_until), null)
    val hint = if (task.snoozeCount >= 3) stringResource(R.string.snooze_hint3) else null
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().semantics { paneTitle = title }.then(rememberTapGuard(task.id, guardMs))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // On a short window at large text the grid moves into the hero, so «تم» always fits.
            val gridInDock = !(LocalDensity.current.fontScale >= 1.5f && maxHeight < 640.dp)
            Column(Modifier.fillMaxSize()) {
                // Top row: the queue at the start; edit and «لاحقًا» at the end.
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    if (count > 1) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(R.string.n_of_m, position.toString(), count.toString()), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s))
                    }
                    Spacer(Modifier.weight(1f))
                    TooltipIconButton(Ms.Edit, stringResource(R.string.open_task), onEdit)
                    val laterDesc = stringResource(R.string.keep_waiting)
                    TextButton(onClick = onLater, modifier = Modifier.heightIn(min = Dimens.touch).semantics { contentDescription = laterDesc }) {
                        Text(stringResource(R.string.later))
                    }
                }
                // The hero scrolls if the text is large; the dock below never does. No action in the hero but the steps.
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.l),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.m),
                ) {
                    Spacer(Modifier.size(Dimens.l))
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (alarm) Ms.Alarm else Ms.NotificationsActive, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    if (alarm) StateTag(TagKind.ALARM, stringResource(R.string.home_style_alarm), emphasis = true)
                    TimeText(due, MaterialTheme.typography.displayLarge, if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, ampmSize = 28)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (late) Icon(Ms.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp).padding(end = Dimens.xs))
                        Text(
                            if (late) L10n.lateLine(context, due, now) else stringResource(R.string.due_now),
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // F-025: the alert itself arrived more than 5 minutes after its time.
                    if (arrivedLateMs != null && arrivedLateMs >= com.fastkiwi.rememberme.alarm.Notifier.LATE_NOTE_MS) {
                        val text = L10n.join(context, listOf(
                            context.getString(R.string.arrived_late, L10n.duration(context, arrivedLateMs / 60_000)),
                            if (lateReasonOff) context.getString(R.string.late_reason_off) else null,
                        ))
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    if (ringing) Text(stringResource(R.string.volume_silence_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (!hidden) {
                        TaskMeta(task, center = true)
                        if (task.body.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                            Text(task.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Dimens.l))
                        }
                        if (task.steps.isNotEmpty()) StepsCard(task, onToggleStep)
                    }
                    if (!gridInDock) Column(Modifier.fillMaxWidth()) {
                        SnoozeGrid(grid, onPreset = { i -> grid[i].at?.let(onSnooze) }, onPickTime = { picking = true })
                    }
                    Spacer(Modifier.size(Dimens.l))
                }
                AlertDock(
                    presets = grid, defaultSnooze = firstLabel, showSkip = task.repeat != Repeat.NONE,
                    onDone = onDone, onSnooze = { onSnooze(firstAt) },
                    onPreset = { i -> grid[i].at?.let(onSnooze) }, onPickTime = { picking = true }, onSkip = onSkip,
                    grid = gridInDock, hint = hint,
                )
            }
        }
    }
    if (picking) SnoozePicker(now, zone, onPick = { picking = false; onSnooze(it) }, onCancel = { picking = false })
}

/** Steps progress «2 من 5» (F-070); a tap shows the steps, and ticking works here (§5.8). */
@Composable
private fun StepsCard(task: Task, onToggle: ((String) -> Unit)?) {
    val steps = task.steps
    val doneCount = steps.count { it.done }
    var open by rememberSaveable(task.id) { mutableStateOf(false) }
    val progress = stringResource(R.string.n_of_m, doneCount.toString(), steps.size.toString())
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { open = !open }.padding(horizontal = Dimens.l, vertical = Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ms.Checklist, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.m))
                Text(stringResource(R.string.home_steps), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(progress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open) Ms.ExpandLess else Ms.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Dimens.s))
            }
            LinearProgressIndicator(
                progress = { if (steps.isEmpty()) 0f else doneCount.toFloat() / steps.size },
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = Dimens.s),
            )
            if (open) steps.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .toggleable(value = s.done, role = Role.Checkbox, enabled = onToggle != null) { onToggle?.invoke(s.id) }
                        .padding(horizontal = Dimens.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = s.done, onCheckedChange = null, modifier = Modifier.padding(Dimens.m))
                    Text(s.text, style = MaterialTheme.typography.bodyLarge,
                        color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (s.done) TextDecoration.LineThrough else null)
                }
            }
        }
    }
}

/** Toggles one step of a reminder (ids kept; Windows normalisation). */
fun toggleStep(t: Task, stepId: String): Task = t.withSteps(t.steps.map { if (it.id == stepId) it.copy(done = !it.done) else it })

/** Date, then time, for "Pick a time…" / «أجّل حتى…». A time in the past is refused with a message (F-044). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozePicker(now: Instant, zone: ZoneId, onPick: (Instant) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    if (day == null) {
        val today = now.atZone(zone).toLocalDate()
        val state = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onCancel,
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = { day = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: today.toString() }) { Text(stringResource(R.string.ok)) } },
        ) { DatePicker(state = state) }
    } else {
        val base = now.atZone(zone).toLocalTime().plusHours(1).withMinute(0)
        val state = rememberTimePickerState(base.hour, base.minute, L10n.is24(context))
        var error by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onCancel,
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                val at = localToInstant(LocalDateTime.of(LocalDate.parse(day), LocalTime.of(state.hour, state.minute)), zone)
                if (at.isAfter(Instant.now())) onPick(at) else error = true
            }) { Text(stringResource(R.string.ok)) } },
            text = {
                Column {
                    TimePicker(state = state)
                    if (error) Text(stringResource(R.string.choose_later_time), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
