package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
 * The reminder screen: the oldest due reminder first, with Done, Skip (for repeats) and the six snooze
 * choices. It stays until every due reminder is dealt with.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var picking by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
            if (count > 1) Text(stringResource(R.string.n_of_m, position.toString(), count.toString()), style = MaterialTheme.typography.labelLarge)
            Text(
                task.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            val late = L10n.late(context, task.dueAt!!, now)
            Text(
                listOfNotNull(L10n.dueText(context, task.dueAt, now), late).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                color = if (late != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            L10n.repeatText(context, task)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (task.body.isNotBlank()) Text(task.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.done), style = MaterialTheme.typography.titleLarge)
            }
            if (task.repeat != Repeat.NONE) OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.skip_this_time))
            }
            Text(stringResource(R.string.remind_me_again), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                presets.forEach { p ->
                    val at = snoozeTime(p, now, zone)
                    val enabled = p is SnoozePreset.Pick || at != null
                    OutlinedButton(
                        onClick = { if (p is SnoozePreset.Pick) picking = true else at?.let(onSnooze) },
                        enabled = enabled,
                        colors = ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { Text(presetLabel(context, p, now, zone)) }
                }
            }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_task)) }
            TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.keep_waiting)) }
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
            confirmButton = { TextButton(onClick = { day = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: today.toString() }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    } else {
        val base = now.atZone(zone).toLocalTime().plusHours(1).withMinute(0)
        val state = rememberTimePickerState(base.hour, base.minute, L10n.is24(context))
        var error by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onCancel,
            confirmButton = { TextButton(onClick = {
                val at = localToInstant(LocalDateTime.of(LocalDate.parse(day), LocalTime.of(state.hour, state.minute)), zone)
                if (at.isAfter(Instant.now())) onPick(at) else error = true
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
            text = {
                Column {
                    TimePicker(state = state)
                    if (error) Text(stringResource(R.string.choose_later_time), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
