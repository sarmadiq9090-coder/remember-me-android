package com.fastkiwi.rememberme.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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

/**
 * The reminder editor (DESIGN-SPEC §3.2): close at the top start, ⋮ «حذف» when editing, the fields in
 * two groups («الموعد», «التفاصيل»), and one «حفظ» at the bottom, above the keyboard.
 */
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
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // What the editor opened with: a Done or snooze made meanwhile must not be undone by its old time.
    fun reminderKey() = listOf(timed, date, time, repeat, days, everyDays, everyHours).joinToString("|")
    fun allKey() = listOf(reminderKey(), title, body, early, priority, category).joinToString("|")
    val openedKey = rememberSaveable { reminderKey() }
    val openedAll = rememberSaveable { allKey() }
    val openedAt = rememberSaveable { task?.updatedAt?.toEpochMilli() ?: 0L }

    val localDate = LocalDate.parse(date)
    val localTime = LocalTime.parse(time)
    val dueAt = if (timed) localToInstant(LocalDateTime.of(localDate, localTime), zone) else null
    val rep = Repeat.of(repeat)
    val dayList = days.split(',').mapNotNull { it.toIntOrNull() }
    val changed = allKey() != openedAll
    val bigText = LocalDensity.current.fontScale >= 1.5f

    fun save() {
        val input = TaskInput(
            id = task?.id, title = title, body = body, priority = Priority.of(priority), category = Category.of(category),
            dueAt = dueAt, repeat = if (timed) rep else Repeat.NONE,
            days = dayList.ifEmpty { listOf(jsDay(localDate)) }, everyDays = everyDays, everyHours = everyHours, remindBefore = if (timed) early else 0,
            updatedAt = task?.let { Instant.ofEpochMilli(openedAt) }, timeEdited = task?.let { reminderKey() != openedKey },
        )
        error = onSave(input)
    }
    fun close() { if (changed) dialog = "discard" else onBack() }
    BackHandler(enabled = changed) { dialog = "discard" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (task == null) R.string.new_reminder else R.string.edit_reminder)) },
                navigationIcon = { TooltipIconButton(Ms.Close, stringResource(R.string.back), ::close) },
                actions = {
                    if (task != null) Box {
                        TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete(task) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Box(Modifier.imePadding()) {
                BottomActionBar {
                    Button(onClick = ::save, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Ms.Check, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(Dimens.s))
                        Text(stringResource(R.string.save))
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(200); error = null }, label = { Text(stringResource(R.string.title)) },
                isError = error != null, supportingText = error?.let { { Text(it) } }, maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l),
            )
            OutlinedTextField(
                value = body, onValueChange = { body = it.take(20_000) }, label = { Text(stringResource(R.string.note)) },
                minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l),
            )
            SectionHeader(stringResource(R.string.schedule_section))
            val rows = mutableListOf<@Composable () -> Unit>()
            rows += { SwitchRow(Ms.Alarm, stringResource(R.string.remind_at_time), null, timed) { timed = it } }
            if (timed) {
                rows += { SettingsRow(Ms.Event, stringResource(R.string.date), L10n.date(context, dueAt!!, zone), { dialog = "date" }, overline = true) }
                rows += { SettingsRow(Ms.Schedule, stringResource(R.string.time), L10n.time(context, dueAt!!, zone), { dialog = "time" }, overline = true) }
                rows += { SettingsRow(Ms.Repeat, stringResource(R.string.repeat), repeatLabel(rep), { dialog = "repeat" }, overline = true) }
                when (rep) {
                    Repeat.DAYS -> rows += {
                        Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m)) {
                            FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                L10n.weekdays(weekStart).forEach { d ->
                                    val on = d in dayList || (dayList.isEmpty() && d == jsDay(localDate))
                                    WeekdayToggle(L10n.weekdayLetter(context, d), L10n.weekdayName(context, d), on) {
                                        val base = dayList.ifEmpty { listOf(jsDay(localDate)) }
                                        val next = if (on) base - d else base + d
                                        if (next.isNotEmpty()) days = next.distinct().sorted().joinToString(",")
                                    }
                                }
                            }
                            val chosen = dayList.ifEmpty { listOf(jsDay(localDate)) }
                            Text(
                                L10n.daysSummary(context, weekStart, chosen),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Dimens.s),
                            )
                        }
                    }
                    Repeat.INTERVAL -> rows += { Stepper(stringResource(R.string.every_n_days), everyDays, 2, 365) { everyDays = it } }
                    Repeat.HOURS -> rows += {
                        Column {
                            Stepper(stringResource(R.string.every_n_hours), everyHours, 1, 23) { everyHours = it }
                            Text(stringResource(R.string.hours_note_short), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.m))
                        }
                    }
                    else -> Unit
                }
                rows += { SettingsRow(Ms.Notifications, stringResource(R.string.early_notice), earlyLabel(early), { dialog = "early" }, overline = true) }
            }
            Group(rows)
            if (timed && task == null && !dueAt!!.isAfter(now)) Row(Modifier.padding(horizontal = Dimens.xxl), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(Dimens.s))
                Text(stringResource(R.string.time_passed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            SectionHeader(stringResource(R.string.details_section))
            val priorities = Priority.entries.map { it.code to priorityName(it) }
            val categories = Category.entries.map { it.code to categoryName(it) }
            if (bigText) {
                Group(listOf(
                    { SettingsRow(Ms.Flag, stringResource(R.string.priority), priorities.first { it.first == priority }.second, { dialog = "priority" }, overline = true) },
                    { SettingsRow(Ms.Label, stringResource(R.string.category), categories.first { it.first == category }.second, { dialog = "category" }, overline = true) },
                ))
            } else {
                Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    Text(stringResource(R.string.priority), style = MaterialTheme.typography.titleSmall)
                    Segments(priorities, priority, icons = mapOf("medium" to Ms.OutlinedFlag, "high" to Ms.Flag)) { priority = it }
                    Spacer(Modifier.height(Dimens.s))
                    Text(stringResource(R.string.category), style = MaterialTheme.typography.titleSmall)
                    Segments(categories, category) { category = it }
                }
            }
            Spacer(Modifier.height(Dimens.l))
        }
    }

    when (dialog) {
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { dialog = null },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
                confirmButton = { TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    dialog = null
                }) { Text(stringResource(R.string.ok)) } },
            ) { DatePicker(state = state) }
        }
        "time" -> {
            val state = rememberTimePickerState(localTime.hour, localTime.minute, L10n.is24(context))
            AlertDialog(
                onDismissRequest = { dialog = null },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
                confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute).toString(); dialog = null }) { Text(stringResource(R.string.ok)) } },
                text = { TimePicker(state = state) },
            )
        }
        "repeat" -> ChoiceDialog(
            stringResource(R.string.repeat), Repeat.entries.map { it.code to repeatLabel(it) }, repeat, { repeat = it }, { dialog = null },
            supporting = mapOf(Repeat.HOURS.code to stringResource(R.string.hours_note_short)),
        )
        "early" -> ChoiceDialog(stringResource(R.string.early_notice), (listOf(0) + EARLY_MINUTES).map { it.toString() to earlyLabel(it) }, early.toString(), { early = it.toInt() }, { dialog = null })
        "priority" -> ChoiceDialog(stringResource(R.string.priority), Priority.entries.map { it.code to priorityName(it) }, priority, { priority = it }, { dialog = null })
        "category" -> ChoiceDialog(stringResource(R.string.category), Category.entries.map { it.code to categoryName(it) }, category, { category = it }, { dialog = null })
        "discard" -> ConfirmDialog(stringResource(R.string.discard_title), null, stringResource(R.string.discard), onBack, { dialog = null },
            dismissLabel = stringResource(R.string.keep_editing))
    }
}

@Composable
fun earlyLabel(minutes: Int): String {
    val context = LocalContext.current
    return if (minutes == 0) stringResource(R.string.none) else stringResource(R.string.before, L10n.duration(context, minutes.toLong()))
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

/** A round weekday toggle: 44 dp drawn in a 48 dp target; the letter shown, the full name spoken. */
@Composable
fun WeekdayToggle(letter: String, name: String, on: Boolean, onToggle: () -> Unit) {
    val selected = stringResource(R.string.weekday_selected)
    val notSelected = stringResource(R.string.weekday_not_selected)
    Box(
        Modifier.size(Dimens.touch)
            .toggleable(value = on, role = Role.Checkbox) { onToggle() }
            .semantics { contentDescription = name; stateDescription = if (on) selected else notSelected },
        contentAlignment = Alignment.Center,
    ) {
        val shape = CircleShape
        Box(
            Modifier.size(42.dp).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest, shape)
                .let { if (on) it else it.border(1.dp, MaterialTheme.colorScheme.outline, shape) },
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, icons: Map<String, androidx.compose.ui.graphics.vector.ImageVector> = emptyMap(), onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (code, text) ->
            SegmentedButton(
                selected = code == selected, onClick = { onSelect(code) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size), modifier = Modifier.heightIn(min = 48.dp),
                icon = { SegmentedButtonDefaults.Icon(code == selected) { icons[code]?.let { Icon(it, null, Modifier.size(18.dp)) } } },
            ) { Text(text, maxLines = 1) }
        }
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TooltipIconButton(Ms.Remove, stringResource(R.string.decrease), { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min)
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Dimens.s))
        TooltipIconButton(Ms.Add, stringResource(R.string.increase), { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max)
    }
}
