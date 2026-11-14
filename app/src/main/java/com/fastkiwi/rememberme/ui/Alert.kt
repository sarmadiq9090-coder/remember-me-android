package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.localToInstant
import com.fastkiwi.rememberme.core.snoozeTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Label of a snooze choice: "10 minutes", "Today 6:00 PM", "Tomorrow 9:00 AM", "Next week", "Pick a time…". */
fun presetLabel(context: android.content.Context, p: SnoozePreset, now: Instant, zone: ZoneId): String = when (p) {
    is SnoozePreset.In -> L10n.duration(context, p.minutes.toLong())
    is SnoozePreset.Today -> context.getString(R.string.day_at_time, context.getString(R.string.today), L10n.time(context, localToInstant(LocalDateTime.of(now.atZone(zone).toLocalDate(), p.time), zone), zone))
    is SnoozePreset.Tomorrow -> context.getString(R.string.day_at_time, context.getString(R.string.tomorrow), L10n.time(context, localToInstant(LocalDateTime.of(now.atZone(zone).toLocalDate().plusDays(1), p.time), zone), zone))
    SnoozePreset.Week -> context.getString(R.string.next_week)
    SnoozePreset.Pick -> context.getString(R.string.pick_time)
}

/**
 * The reminder screen (DESIGN-SPEC §3.3): the due time is the hero, then the title; the dock at the
 * bottom never scrolls: the six snooze choices (2 × 3) and «تخطَّ هذه المرة» · «تم» (primary at the end).
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
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var picking by rememberSaveable { mutableStateOf(false) }
    val bigText = LocalDensity.current.fontScale >= 1.5f
    val due = task.dueAt!!
    val minutesLate = (now.toEpochMilli() - due.toEpochMilli()) / 60_000
    val late = minutesLate >= 1
    val title = if (hidden) stringResource(R.string.reminder) else task.title
    val snoozeGrid: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    presets.chunked(if (bigText) 1 else 2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                            pair.forEach { p ->
                                val at = snoozeTime(p, now, zone)
                                OutlinedButton(
                                    onClick = { if (p is SnoozePreset.Pick) picking = true else at?.let(onSnooze) },
                                    enabled = p is SnoozePreset.Pick || at != null,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.weight(1f).heightIn(min = Dimens.touch),
                                ) { Text(presetLabel(context, p, now, zone), textAlign = TextAlign.Center) }
                            }
                        }
                    }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().semantics { paneTitle = title }) {
        Column(Modifier.fillMaxSize()) {
            // Top row: the queue at the start; edit and «لاحقًا» at the end.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                if (count > 1) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(R.string.n_of_m, position.toString(), count.toString()), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s))
                }
                Spacer(Modifier.weight(1f))
                TooltipIconButton(Icons.Rounded.Edit, stringResource(R.string.open_task), onEdit)
                val laterDesc = stringResource(R.string.keep_waiting)
                TextButton(onClick = onLater, modifier = Modifier.heightIn(min = Dimens.touch).semantics { contentDescription = laterDesc }) {
                    Text(stringResource(R.string.later))
                }
            }
            // The hero scrolls if the text is large; the dock below never does.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.l),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                Spacer(Modifier.size(Dimens.l))
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                TimeText(due, MaterialTheme.typography.displayLarge, if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, ampmSize = 28)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (late) Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp).padding(end = Dimens.xs))
                    Text(
                        if (late) L10n.lateLine(context, due, now)
                        else stringResource(R.string.due_now),
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                if (!hidden) {
                    TaskMeta(task, center = true)
                    if (task.body.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Text(task.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Dimens.l))
                    }
                }
                // At large text the dock keeps only «تم» (and «تخطَّ»), so the time and title stay visible;
                // the snooze choices scroll with the reminder instead.
                if (bigText) {
                    Text(stringResource(R.string.remind_me_again), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    snoozeGrid()
                }
                Spacer(Modifier.size(Dimens.l))
            }
            // The dock.
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    if (!bigText) {
                        Text(stringResource(R.string.remind_me_again), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        snoozeGrid()
                    }
                    val done: @Composable (Modifier) -> Unit = { m ->
                        Button(onClick = onDone, modifier = m.heightIn(min = 64.dp)) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.size(Dimens.s))
                            Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    if (task.repeat == Repeat.NONE) done(Modifier.fillMaxWidth())
                    else ButtonPair(
                        stacked = bigText,
                        secondary = { m -> OutlinedButton(onClick = onSkip, modifier = m.heightIn(min = 64.dp)) { Text(stringResource(R.string.skip_this_time), textAlign = TextAlign.Center) } },
                        primary = done,
                    )
                }
            }
        }
    }
    if (picking) SnoozePicker(now, zone, onPick = { picking = false; onSnooze(it) }, onCancel = { picking = false })
}

/** Date, then time, for "Pick a time…". */
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
