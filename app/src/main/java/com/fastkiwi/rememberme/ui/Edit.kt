package com.fastkiwi.rememberme.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.EARLY_MINUTES
import com.fastkiwi.rememberme.core.Hijri
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.localToInstant
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.core.tags
import com.fastkiwi.rememberme.data.EditorPrefs
import com.fastkiwi.rememberme.data.EditorSettings
import com.fastkiwi.rememberme.data.Filters
import com.fastkiwi.rememberme.ui.editor.AlertItem
import com.fastkiwi.rememberme.ui.editor.AlertListEditor
import com.fastkiwi.rememberme.ui.editor.AlertStyleSheet
import com.fastkiwi.rememberme.ui.editor.CollapsibleGroup
import com.fastkiwi.rememberme.ui.editor.DateTimeChips
import com.fastkiwi.rememberme.ui.editor.EditorHeader
import com.fastkiwi.rememberme.ui.editor.PrayerTimeSheet
import com.fastkiwi.rememberme.ui.editor.RadioRow
import com.fastkiwi.rememberme.ui.editor.StepsEditor
import com.fastkiwi.rememberme.ui.editor.TagsSheet
import com.fastkiwi.rememberme.ui.editor.TonePickerSheet
import com.fastkiwi.rememberme.ui.editor.fullScreenModeName
import com.fastkiwi.rememberme.ui.editor.prayerOccurrence
import com.fastkiwi.rememberme.ui.editor.prayerRelative
import com.fastkiwi.rememberme.ui.editor.toneLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The editor's starting time for a new reminder: the next full hour. */
fun defaultDue(now: Instant, zone: ZoneId): LocalDateTime =
    now.atZone(zone).toLocalDateTime().withMinute(0).withSecond(0).withNano(0).plusHours(1)

/** Per-reminder lock-screen choice («على شاشة القفل»), kept in the task's `android` object as `lock`: show | hide. */
const val LOCK_KEY = "lock"

fun AlertExt.lock(): String? = (unknown[LOCK_KEY] as? JsonPrimitive)?.content?.takeIf { it == "show" || it == "hide" }

fun AlertExt.withLock(v: String?): AlertExt = copy(unknown = if (v == null) unknown - LOCK_KEY else unknown + (LOCK_KEY to JsonPrimitive(v)))

/** True when any alert option differs from the phone's defaults (the group then opens by itself, §5.5). */
fun AlertExt.hasOptions(): Boolean = style != null || tone != null || fullScreen != null || pin != null || vibrate != null || speak != null || lock() != null

private fun encodeSteps(list: List<Organize.Step>) = JsonArray(list.map {
    JsonObject(mapOf("id" to JsonPrimitive(it.id), "text" to JsonPrimitive(it.text), "done" to JsonPrimitive(it.done)))
}).toString()

private fun decodeSteps(s: String) = Organize.parseSteps(runCatching { Codec.json.parseToJsonElement(s) }.getOrNull())

private fun encodeTags(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) }).toString()

private fun decodeTags(s: String) = Organize.parseTags(runCatching { Codec.json.parseToJsonElement(s) }.getOrNull())

private fun decodeExt(s: String) = AlertExt.parse(runCatching { Codec.json.parseToJsonElement(s) }.getOrNull())

/** Early alerts as "1440:1,60:0" (minutes:on). */
private fun encodeAlerts(list: List<Pair<Int, Boolean>>) = list.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" }

private fun decodeAlerts(s: String) = s.split(',').mapNotNull { p ->
    val parts = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
    parts[0].toIntOrNull()?.let { it to (parts[1] == "1") }
}

/**
 * The reminder editor (DESIGN-SPEC §5.5, PRD §6.2 order): title; «الموعد» (date and time chips, repeat);
 * «التنبيهات» (at the time + up to 3 early alerts); «التنظيم» (category, tags); «الأهمية»; «الملاحظة والخطوات»;
 * «خيارات التنبيه» (folded unless something differs from the defaults). Close at the top start; ⋮ when editing
 * («السجل» with history on, «حفظ كقالب», «نسخ», «حذف»); one «حفظ» at the bottom, above the keyboard.
 *
 * [initial] prefills a new reminder (a template, Quick Add's «خيارات أكثر», «نسخ»). [settings] = the phone's
 * settings (read from Prefs when null). A menu item whose callback is null is not shown.
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
    initial: TaskInput? = null,
    settings: EditorSettings? = null,
    onSaveAsTemplate: ((TaskInput) -> Unit)? = null,
    onDuplicate: ((TaskInput) -> Unit)? = null,
    onHistory: ((Task) -> Unit)? = null,
    onDndHelp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var cfg by remember { mutableStateOf(settings ?: EditorPrefs.read(context)) }
    LaunchedEffect(Unit) { EditorPrefs.install(context) }
    val src = if (task == null) initial else null
    val firstDue = task?.dueAt ?: src?.dueAt
    val start = firstDue?.atZone(zone)?.toLocalDateTime() ?: defaultDue(now, zone)
    val firstExt = task?.let { AlertExt.of(it) } ?: src?.ext ?: AlertExt()
    var title by rememberSaveable { mutableStateOf(task?.title ?: src?.title ?: "") }
    var body by rememberSaveable { mutableStateOf(task?.body ?: src?.body ?: "") }
    var timed by rememberSaveable { mutableStateOf(if (task != null || src != null) firstDue != null else true) }
    var date by rememberSaveable { mutableStateOf(start.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(start.toLocalTime().toString()) }
    var repeat by rememberSaveable { mutableStateOf(task?.repeat?.code ?: src?.repeat?.code ?: "none") }
    var hijriYearly by rememberSaveable { mutableStateOf(firstExt.hijri != null && (task?.repeat ?: src?.repeat) == Repeat.YEARLY) }
    var days by rememberSaveable { mutableStateOf(task?.rule?.days?.joinToString(",") ?: src?.days?.joinToString(",") ?: "") }
    var everyDays by rememberSaveable { mutableIntStateOf(task?.rule?.everyDays ?: src?.everyDays ?: 2) }
    var everyHours by rememberSaveable { mutableIntStateOf(task?.rule?.everyHours ?: src?.everyHours ?: 1) }
    var alertsText by rememberSaveable {
        val list = when {
            task != null -> Tasks.noticeMinutes(task)
            src != null -> src.alerts ?: listOf(src.remindBefore)
            else -> emptyList()
        }
        mutableStateOf(encodeAlerts(Tasks.cleanAlerts(list, firstDue ?: Instant.EPOCH).map { it to true }))
    }
    var priority by rememberSaveable { mutableStateOf(task?.priority?.code ?: src?.priority?.code ?: "normal") }
    var category by rememberSaveable { mutableStateOf(task?.category?.code ?: src?.category?.code ?: "personal") }
    var tagsText by rememberSaveable { mutableStateOf(encodeTags(task?.tags ?: src?.tags ?: emptyList())) }
    var stepsText by rememberSaveable { mutableStateOf(encodeSteps(task?.steps ?: src?.steps ?: emptyList())) }
    var extText by rememberSaveable { mutableStateOf(firstExt.copy(alerts = emptyList(), hijri = null).toJson().toString()) }
    var optionsOpen by rememberSaveable { mutableStateOf(firstExt.hasOptions()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var editAlert by rememberSaveable { mutableIntStateOf(-1) }
    var focusStep by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }

    val ext = decodeExt(extText)
    fun setExt(e: AlertExt) { extText = e.copy(alerts = emptyList(), hijri = null).toJson().toString() }
    val early = decodeAlerts(alertsText)
    val tags = decodeTags(tagsText)
    val steps = decodeSteps(stepsText)

    // What the editor opened with: a Done or snooze made meanwhile must not be undone by its old time.
    fun reminderKey() = listOf(timed, date, time, repeat, days, everyDays, everyHours, hijriYearly, ext.prayer).joinToString("|")
    fun allKey() = listOf(reminderKey(), title, body, alertsText, priority, category, tagsText, stepsText, extText).joinToString("|")
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
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    val prayer = ext.prayer?.takeIf { timed && rep == Repeat.DAILY }
    val hijri = Hijri.of(localDate, cfg.hijriAdjust)
    fun hijriMonth(m: Int) = if (arabic) Hijri.monthNameAr(m) else Hijri.monthNameEn(m)
    val byPriority = if (Priority.of(priority) == Priority.HIGH) "alarm" else "normal"
    val effStyle = ext.style ?: byPriority

    fun input(): TaskInput {
        val alerts = early.filter { it.second }.map { it.first }
        val finalExt = ext.copy(
            prayer = prayer,
            hijri = if (timed && hijriYearly && rep == Repeat.YEARLY) hijri.month to hijri.day else null,
        )
        return TaskInput(
            id = task?.id, title = title, body = body, priority = Priority.of(priority), category = Category.of(category),
            dueAt = dueAt, repeat = if (timed) rep else Repeat.NONE,
            days = dayList.ifEmpty { listOf(jsDay(localDate)) }, everyDays = everyDays, everyHours = everyHours,
            remindBefore = if (timed) alerts.minOrNull() ?: 0 else 0,
            updatedAt = task?.let { Instant.ofEpochMilli(openedAt) }, timeEdited = task?.let { reminderKey() != openedKey },
            alerts = if (timed) alerts else emptyList(), ext = finalExt, tags = tags,
            steps = steps.filter { it.text.isNotBlank() }, autoComplete = cfg.autoCompleteSteps,
        )
    }
    fun save() { error = onSave(input()) }
    fun close() { if (changed) dialog = "discard" else onBack() }
    BackHandler(enabled = changed) { dialog = "discard" }
    // A new day: a prayer time is computed again for it.
    fun setDate(d: LocalDate) {
        val at = prayer?.let { prayerOccurrence(cfg, it.first, it.second, d, now, zone) }
        if (at != null) {
            val z = at.atZone(zone)
            date = z.toLocalDate().toString(); time = z.toLocalTime().withSecond(0).withNano(0).toString()
        } else date = d.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (task == null) R.string.new_reminder else R.string.edit_reminder), maxLines = 1) },
                navigationIcon = { TooltipIconButton(Ms.Close, stringResource(R.string.back), ::close) },
                actions = {
                    if (task != null) Box {
                        TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (cfg.historyOn && onHistory != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.history)) }, leadingIcon = { Icon(Ms.History, null) },
                                onClick = { menu = false; onHistory(task) })
                            if (onSaveAsTemplate != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as_template)) }, leadingIcon = { Icon(Ms.DashboardCustomize, null) },
                                onClick = { menu = false; onSaveAsTemplate(input().copy(id = null, updatedAt = null, timeEdited = null)) })
                            if (onDuplicate != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.duplicate)) }, leadingIcon = { Icon(Ms.ContentCopy, null) },
                                onClick = { menu = false; onDuplicate(input().copy(id = null, updatedAt = null, timeEdited = null)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Ms.Delete, null) },
                                onClick = { menu = false; onDelete(task) })
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
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Dimens.l)) {
            // 1. Title
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(200); error = null }, label = { Text(stringResource(R.string.title)) },
                isError = error != null, supportingText = error?.let { { Text(it) } }, maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l),
            )

            // 2. When and 3. repeat
            EditorHeader(stringResource(R.string.schedule_section))
            val hijriText = if (timed && cfg.hijriDisplay) stringResource(R.string.hijri_date_fmt, hijri.day.toString(), hijriMonth(hijri.month), hijri.year.toString()) else null
            val timeText = if (!timed) null else prayer?.let { prayerRelative(it.first, it.second) } ?: L10n.time(context, dueAt!!, zone)
            val dateText = if (timed) shortDate(localDate, now, zone) else null
            val whenRows = mutableListOf<@Composable () -> Unit>()
            whenRows += {
                DateTimeChips(
                    dateLabel = dateText, timeLabel = timeText, hijri = hijriText,
                    onDate = { dialog = "date" },
                    onTime = { if (cfg.prayerReady || prayer != null) sheet = "prayer" else dialog = "time" },
                    onClear = { timed = false },
                    onAdd = { timed = true },
                )
            }
            if (timed) {
                val repeatText = if (hijriYearly && rep == Repeat.YEARLY) stringResource(R.string.repeat_hijri_on, "${hijri.day} ${hijriMonth(hijri.month)}") else repeatLabel(rep)
                whenRows += { SettingsRow(Ms.Repeat, stringResource(R.string.repeat), repeatText, { dialog = "repeat" }, overline = true) }
                when (rep) {
                    Repeat.DAYS -> whenRows += {
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
                            Text(
                                L10n.daysSummary(context, weekStart, dayList.ifEmpty { listOf(jsDay(localDate)) }),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Dimens.s),
                            )
                        }
                    }
                    Repeat.INTERVAL -> whenRows += { Stepper(stringResource(R.string.every_n_days), everyDays, 2, 365) { everyDays = it } }
                    Repeat.HOURS -> whenRows += {
                        Column {
                            Stepper(stringResource(R.string.every_n_hours), everyHours, 1, 23) { everyHours = it }
                            Text(stringResource(R.string.hours_note_short), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.m))
                        }
                    }
                    else -> Unit
                }
            }
            Group(whenRows)
            if (timed && task == null && !dueAt!!.isAfter(now)) Row(Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(Dimens.s))
                Text(stringResource(R.string.time_passed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            // 4. Alerts (only with a time)
            if (timed) {
                EditorHeader(stringResource(R.string.alerts_section))
                val items = listOf(AlertItem(0, true, stringResource(R.string.alert_at_time), null)) + early.map { (m, on) ->
                    AlertItem(m, on, earlyLabel(m), alertMoment(dueAt!!.minusSeconds(m * 60L), dueAt, now, zone))
                }
                AlertListEditor(
                    items,
                    onToggle = { i, on -> alertsText = encodeAlerts(early.mapIndexed { j, a -> if (j == i - 1) a.first to on else a }) },
                    onEdit = { i -> editAlert = i - 1; dialog = "alert" },
                    onAdd = { editAlert = -1; dialog = "alert" },
                    max = Tasks.MAX_ALERTS,
                )
            }

            // 5. Organise
            EditorHeader(stringResource(R.string.organise_section))
            val tagsValue = if (tags.isEmpty()) stringResource(R.string.tags_none) else tags.joinToString(stringResource(R.string.list_separator))
            Group(listOf(
                { SettingsRow(Ms.Label, stringResource(R.string.category), categoryName(Category.of(category)), { dialog = "category" }, overline = true) },
                { SettingsRow(Ms.Tag, stringResource(R.string.tags), tagsValue, { sheet = "tags" }, overline = true) },
            ))

            // 6. Priority
            EditorHeader(stringResource(R.string.priority))
            val priorities = Priority.entries.map { it.code to priorityName(it) }
            if (bigText) {
                Group(listOf({ SettingsRow(Ms.Flag, stringResource(R.string.priority), priorities.first { it.first == priority }.second, { dialog = "priority" }, overline = true) }))
            } else {
                Box(Modifier.padding(horizontal = Dimens.l)) {
                    Segments(priorities, priority, icons = mapOf("medium" to Ms.OutlinedFlag, "high" to Ms.Flag)) { priority = it }
                }
            }

            // 7. Note and steps
            EditorHeader(stringResource(R.string.note_steps_section))
            OutlinedTextField(
                value = body, onValueChange = { body = it.take(20_000) }, label = { Text(stringResource(R.string.note)) },
                minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l),
            )
            Spacer(Modifier.height(Dimens.s))
            StepsEditor(
                steps,
                onToggle = { i, d -> stepsText = encodeSteps(steps.mapIndexed { j, s -> if (j == i) s.copy(done = d) else s }) },
                onEdit = { i, t -> stepsText = encodeSteps(steps.mapIndexed { j, s -> if (j == i) s.copy(text = t) else s }) },
                onAdd = { after ->
                    if (steps.size < Organize.MAX_STEPS) {
                        val at = after?.plus(1) ?: steps.size
                        stepsText = encodeSteps(steps.toMutableList().apply { add(at, Organize.Step(UUID.randomUUID().toString(), "", false)) })
                        focusStep = at
                    }
                },
                onMove = { from, to ->
                    val list = steps.toMutableList()
                    if (from in list.indices && to in list.indices) { list.add(to, list.removeAt(from)); stepsText = encodeSteps(list) }
                },
                onRemove = { i -> stepsText = encodeSteps(steps.filterIndexed { j, _ -> j != i }) },
                autoComplete = cfg.autoCompleteSteps,
                onAutoComplete = { on -> EditorPrefs.setAutoComplete(context, on); cfg = cfg.copy(autoCompleteSteps = on) },
                focusIndex = focusStep,
            )

            // 8. Alert options, folded
            Spacer(Modifier.height(Dimens.l))
            val styleName = stringResource(if (effStyle == "alarm") R.string.style_alarm else R.string.style_normal)
            val fsValue = stringResource(when (ext.fullScreen) { "on" -> R.string.fs_show; "off" -> R.string.fs_notify_only; else -> R.string.fs_follow })
            val fsSummary = if (ext.fullScreen == null) stringResource(R.string.fs_summary_follow) else fsValue
            val toneName = toneLabel(ext.tone)
            val lock = ext.lock()
            val lockValue = stringResource(when (lock) { "show" -> R.string.lock_show; "hide" -> R.string.lock_hide; else -> R.string.fs_follow })
            val lockHint = if (lock == "hide" || (lock == null && cfg.lockHideDefault)) stringResource(R.string.lock_hide_hint) else null
            val vibrateOn = ext.vibrate ?: cfg.vibrateDefault
            val pinOn = ext.pin?.let { it == "on" } ?: cfg.pinDefault
            val speakOn = ext.speak ?: cfg.speakDefault
            val styleHint = if (effStyle == "alarm") stringResource(R.string.style_alarm_hint) else null
            val fsHint = if (ext.fullScreen == null) fullScreenModeName(cfg.fullScreenMode) else null
            CollapsibleGroup(
                Ms.Settings, stringResource(R.string.alert_options), L10n.join(context, listOf(styleName, toneName, fsSummary)), optionsOpen, { optionsOpen = !optionsOpen },
                listOf(
                    { OptionRow(if (effStyle == "alarm") Ms.Alarm else Ms.Notifications, stringResource(R.string.alert_style), styleName, styleHint) { sheet = "style" } },
                    { OptionRow(Ms.MusicNote, stringResource(R.string.tone), toneName, null) { sheet = "tone" } },
                    { SwitchRow(Ms.Vibration, stringResource(R.string.vibration), null, vibrateOn) { setExt(ext.copy(vibrate = it.takeIf { v -> v != cfg.vibrateDefault })) } },
                    { OptionRow(Ms.Fullscreen, stringResource(R.string.full_screen), fsValue, fsHint) { sheet = "style" } },
                    { SwitchRow(Ms.PushPin, stringResource(R.string.pin), stringResource(R.string.pin_hint), pinOn) {
                        setExt(ext.copy(pin = if (it == cfg.pinDefault) null else if (it) "on" else "off")) } },
                    { SwitchRow(Ms.RecordVoiceOver, stringResource(R.string.speak), stringResource(R.string.speak_hint), speakOn) {
                        setExt(ext.copy(speak = it.takeIf { v -> v != cfg.speakDefault })) } },
                    { OptionRow(Ms.VisibilityOff, stringResource(R.string.lock_screen), lockValue, lockHint) { dialog = "lock" } },
                ),
            )
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
                    state.selectedDateMillis?.let { setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    dialog = null
                }) { Text(stringResource(R.string.ok)) } },
            ) { DatePicker(state = state) }
        }
        "time" -> {
            val state = rememberTimePickerState(localTime.hour, localTime.minute, L10n.is24(context))
            AlertDialog(
                onDismissRequest = { dialog = null },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
                confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute).toString(); setExt(ext.copy(prayer = null)); dialog = null }) { Text(stringResource(R.string.ok)) } },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TimePicker(state = state)
                        // The way into prayer-relative times (F-050); the sheet asks for the city and the method first.
                        TextButton(onClick = { dialog = null; sheet = "prayer-first" }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                            Icon(Ms.Mosque, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(Dimens.s))
                            Text(stringResource(R.string.time_mode_prayer))
                        }
                    }
                },
            )
        }
        "repeat" -> {
            val options = Repeat.entries.map { it.code to repeatLabel(it) } +
                (if (cfg.hijriDisplay || hijriYearly) listOf("hijri" to stringResource(R.string.repeat_hijri)) else emptyList())
            ChoiceDialog(
                stringResource(R.string.repeat), options, if (hijriYearly && rep == Repeat.YEARLY) "hijri" else repeat,
                { code ->
                    if (code == "hijri") { repeat = Repeat.YEARLY.code; hijriYearly = true } else { repeat = code; hijriYearly = false }
                    if (code != Repeat.DAILY.code && ext.prayer != null) setExt(ext.copy(prayer = null))
                },
                { dialog = null },
                supporting = mapOf(Repeat.HOURS.code to stringResource(R.string.hours_note_short)),
            )
        }
        "alert" -> {
            val current = early.getOrNull(editAlert)?.first
            val taken = early.map { it.first }.toSet() - setOfNotNull(current)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(if (current == null) R.string.alert_add else R.string.alert_change)) },
                text = {
                    Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                        EARLY_MINUTES.sortedDescending().filter { it !in taken }.forEach { m ->
                            RadioRow(earlyLabel(m), m == current) {
                                val list = if (current == null) early + (m to true) else early.map { if (it.first == current) m to it.second else it }
                                alertsText = encodeAlerts(list.sortedByDescending { it.first })
                                dialog = null
                            }
                        }
                    }
                },
                dismissButton = if (current != null) ({
                    TextButton(onClick = { alertsText = encodeAlerts(early.filter { it.first != current }); dialog = null }) {
                        Text(stringResource(R.string.alert_remove), color = MaterialTheme.colorScheme.error)
                    }
                }) else null,
                confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        "priority" -> ChoiceDialog(stringResource(R.string.priority), Priority.entries.map { it.code to priorityName(it) }, priority, { priority = it }, { dialog = null })
        "category" -> ChoiceDialog(stringResource(R.string.category), Category.entries.map { it.code to categoryName(it) }, category, { category = it }, { dialog = null })
        "lock" -> ChoiceDialog(
            stringResource(R.string.lock_screen),
            listOf("" to stringResource(R.string.fs_follow), "show" to stringResource(R.string.lock_show), "hide" to stringResource(R.string.lock_hide)),
            ext.lock() ?: "", { setExt(ext.withLock(it.ifEmpty { null })) }, { dialog = null },
        )
        "discard" -> ConfirmDialog(stringResource(R.string.discard_title), null, stringResource(R.string.discard), onBack, { dialog = null },
            dismissLabel = stringResource(R.string.keep_editing))
    }

    when (sheet) {
        "tone" -> {
            val style = if (effStyle == "alarm") AlertPolicy.Style.ALARM else AlertPolicy.Style.NORMAL
            TonePickerSheet(
                current = ext.tone, style = style,
                onPick = { setExt(ext.copy(tone = it)) }, onDismiss = { sheet = null },
                defaultTone = if (style == AlertPolicy.Style.ALARM) cfg.importantTone else cfg.normalTone,
            )
        }
        "style" -> AlertStyleSheet(
            style = effStyle, fullScreen = ext.fullScreen, globalMode = cfg.fullScreenMode,
            onDone = { s, fs -> setExt(ext.copy(style = s.takeIf { it != byPriority }, fullScreen = fs)) },
            onDndHelp = { onDndHelp?.invoke() },
            onDismiss = { sheet = null },
        )
        "tags" -> {
            val lists = remember { runCatching { Filters(context).all().filter { it.kind == "tag" }.map { it.value } }.getOrDefault(emptyList()) }
            TagsSheet(tags, lists, inUse = emptyList(), onDone = { tagsText = encodeTags(it) }, onDismiss = { sheet = null })
        }
        "prayer", "prayer-first" -> PrayerTimeSheet(
            settings = cfg, date = localDate, clock = localTime, prayer = prayer, startWithPrayer = sheet == "prayer-first" || prayer != null,
            now = now, zone = zone,
            onCity = { EditorPrefs.setPrayerCity(context, it); cfg = cfg.copy(prayerCity = it) },
            onMethod = { EditorPrefs.setPrayerMethod(context, it); cfg = cfg.copy(prayerMethod = it) },
            onClock = { time = it.toString(); setExt(ext.copy(prayer = null)) },
            onPrayer = { n, off, at ->
                val z = at.atZone(zone)
                timed = true; date = z.toLocalDate().toString(); time = z.toLocalTime().withSecond(0).withNano(0).toString()
                repeat = Repeat.DAILY.code; hijriYearly = false
                setExt(ext.copy(prayer = n to off))
            },
            onDismiss = { sheet = null },
        )
    }
}

/** A row with a value and an optional third line (the alert options, §5.5 `edit-advanced`). */
@Composable
private fun OptionRow(icon: ImageVector, title: String, value: String, hint: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Dimens.l, vertical = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** «الاثنين 16 نوفمبر» (the year only when it is not this year). */
@Composable
fun shortDate(d: LocalDate, now: Instant, zone: ZoneId): String {
    val context = LocalContext.current
    val sameYear = d.year == now.atZone(zone).year
    return DateTimeFormatter.ofPattern(if (sameYear) "EEEE d MMMM" else "EEEE d MMMM yyyy", L10n.current(context)).format(d)
}

/** An early alert's moment: the time alone on the due day, else «الأحد 15 نوفمبر، 10:30 ص»; «مضى وقته» when past. */
@Composable
private fun alertMoment(at: Instant, due: Instant, now: Instant, zone: ZoneId): String {
    val context = LocalContext.current
    val sameDay = at.atZone(zone).toLocalDate() == due.atZone(zone).toLocalDate()
    val text = if (sameDay) L10n.time(context, at, zone) else L10n.join(context, listOf(shortDate(at.atZone(zone).toLocalDate(), now, zone), L10n.time(context, at, zone)))
    return if (at.isAfter(now)) text else L10n.join(context, listOf(text, stringResource(R.string.alert_passed)))
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
fun Segments(options: List<Pair<String, String>>, selected: String, icons: Map<String, ImageVector> = emptyMap(), onSelect: (String) -> Unit) {
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
