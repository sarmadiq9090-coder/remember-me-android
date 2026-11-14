package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.EARLY_MINUTES
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.localToInstant
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The editor's starting time for a new reminder: the next full hour. */
fun defaultDue(now: Instant, zone: ZoneId): LocalDateTime =
    now.atZone(zone).toLocalDateTime().withMinute(0).withSecond(0).withNano(0).plusHours(1)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditScreen(
    task: Task?,
    weekStart: Int,
    now: Instant,
    onSave: (TaskInput) -> String?,
    onDelete: (Task) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val start = task?.dueAt?.atZone(zone)?.toLocalDateTime() ?: defaultDue(now, zone)
    var title by rememberSaveable { mutableStateOf(task?.title ?: "") }
    var body by rememberSaveable { mutableStateOf(task?.body ?: "") }
    var timed by rememberSaveable { mutableStateOf(task == null || task.dueAt != null) }
    var date by rememberSaveable { mutableStateOf(start.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(start.toLocalTime().toString()) }
    var repeat by rememberSaveable { mutableStateOf(task?.repeat?.code ?: "none") }
    var days by rememberSaveable { mutableStateOf(task?.rule?.days?.joinToString(",") ?: "") }
    var everyDays by rememberSaveable { mutableIntStateOf(task?.rule?.everyDays ?: 2) }
    var everyHours by rememberSaveable { mutableIntStateOf(task?.rule?.everyHours ?: 1) }
    var early by rememberSaveable { mutableIntStateOf(task?.remindBefore ?: 0) }
    var priority by rememberSaveable { mutableStateOf(task?.priority?.code ?: "normal") }
    var category by rememberSaveable { mutableStateOf(task?.category?.code ?: "personal") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    val localDate = LocalDate.parse(date)
    val localTime = LocalTime.parse(time)
    val dueAt = if (timed) localToInstant(LocalDateTime.of(localDate, localTime), zone) else null
    val rep = Repeat.of(repeat)
    val dayList = days.split(',').mapNotNull { it.toIntOrNull() }

    fun save() {
        val input = TaskInput(
            id = task?.id, title = title, body = body, priority = Priority.of(priority), category = Category.of(category),
            dueAt = dueAt, repeat = if (timed) rep else Repeat.NONE,
            days = dayList.ifEmpty { listOf(jsDay(localDate)) }, everyDays = everyDays, everyHours = everyHours, remindBefore = if (timed) early else 0,
        )
        error = onSave(input)
    }

    val timedLabel = stringResource(R.string.remind_at_time)
    val noneLabel = stringResource(R.string.none)
    val beforeLabel = stringResource(R.string.before)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (task == null) R.string.new_reminder else R.string.edit_reminder)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (task != null) IconButton(onClick = { onDelete(task) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                TextButton(onClick = ::save, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
            },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(200); error = null }, label = { Text(stringResource(R.string.title)) },
                isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = false, maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body, onValueChange = { body = it.take(20_000) }, label = { Text(stringResource(R.string.note)) },
                minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.remind_at_time), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = timed, onCheckedChange = { timed = it }, modifier = Modifier.semantics { contentDescription = timedLabel })
            }
            if (timed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.CalendarMonth, null, modifier = Modifier.padding(end = 6.dp))
                        Text(L10n.date(context, dueAt!!, zone), maxLines = 2)
                    }
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Schedule, null, modifier = Modifier.padding(end = 6.dp))
                        Text(L10n.time(context, dueAt!!, zone))
                    }
                }
                if (task == null && !dueAt!!.isAfter(now)) Text(stringResource(R.string.time_passed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

                Choice(
                    label = stringResource(R.string.repeat),
                    options = Repeat.entries.map { it.code to repeatLabel(it) },
                    selected = repeat, onSelect = { repeat = it },
                )
                when (rep) {
                    Repeat.DAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        L10n.weekdays(weekStart).forEach { d ->
                            val on = d in dayList || (dayList.isEmpty() && d == jsDay(localDate))
                            FilterChip(selected = on, onClick = {
                                val base = dayList.ifEmpty { listOf(jsDay(localDate)) }
                                val next = if (on) base - d else base + d
                                days = next.distinct().sorted().joinToString(",")
                            }, label = { Text(L10n.weekdayName(context, d)) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Repeat.INTERVAL -> Stepper(stringResource(R.string.every_n_days), everyDays, 2, 365) { everyDays = it }
                    Repeat.HOURS -> {
                        Stepper(stringResource(R.string.every_n_hours), everyHours, 1, 23) { everyHours = it }
                        Text(stringResource(R.string.hours_windows_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Unit
                }
                Choice(
                    label = stringResource(R.string.early_notice),
                    options = (listOf(0) + EARLY_MINUTES).map { it.toString() to (if (it == 0) noneLabel else beforeLabel.replace("%1\$s", L10n.duration(context, it.toLong()))) },
                    selected = early.toString(), onSelect = { early = it.toInt() },
                )
            }
            Text(stringResource(R.string.priority), style = MaterialTheme.typography.titleMedium)
            Segments(Priority.entries.map { it.code to stringResource(when (it) { Priority.NORMAL -> R.string.priority_normal; Priority.MEDIUM -> R.string.priority_medium; Priority.HIGH -> R.string.priority_high }) }, priority) { priority = it }
            Text(stringResource(R.string.category), style = MaterialTheme.typography.titleMedium)
            Segments(Category.entries.map { it.code to stringResource(when (it) { Category.PERSONAL -> R.string.cat_personal; Category.WORK -> R.string.cat_work; Category.STUDY -> R.string.cat_study }) }, category) { category = it }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.save)) }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                pickDate = false
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(localTime.hour, localTime.minute, L10n.is24(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute).toString(); pickTime = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun repeatLabel(r: Repeat): String = stringResource(when (r) {
    Repeat.NONE -> R.string.repeat_none
    Repeat.DAILY -> R.string.repeat_daily
    Repeat.WEEKLY -> R.string.repeat_weekly
    Repeat.DAYS -> R.string.repeat_days
    Repeat.INTERVAL -> R.string.repeat_interval
    Repeat.HOURS -> R.string.repeat_hours
    Repeat.MONTHLY -> R.string.repeat_monthly
    Repeat.YEARLY -> R.string.repeat_yearly
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Choice(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "", onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(code); open = false }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (code, text) ->
            SegmentedButton(
                selected = code == selected, onClick = { onSelect(code) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size), modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(text, maxLines = 1) }
        }
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) { Icon(Icons.Filled.Remove, stringResource(R.string.decrease)) }
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) { Icon(Icons.Filled.Add, stringResource(R.string.increase)) }
    }
}
