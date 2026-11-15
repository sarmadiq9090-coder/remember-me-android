package com.fastkiwi.rememberme.ui.quick

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.TaskException
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.ui.Behaviour
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SheetShape
import com.fastkiwi.rememberme.ui.TooltipIconButton
import com.fastkiwi.rememberme.ui.categoryName
import com.fastkiwi.rememberme.ui.priorityName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Hosts Quick Add over the current screen: the sheet, its state, and the save. Call it while a [QuickAddRequest]
 * is open; [onDismiss] closes it. After «حفظ» the sheet closes and [onSaved] gets the new reminder's id (show
 * «أُضيف التذكير» with «تراجع» and scroll the row into view; [QuickAddSave.undo] removes it again). [onMore] gets
 * the fields for the editor; [onTemplate] opens «من قالب».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddHost(
    request: QuickAddRequest,
    weekStart: Int,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onMore: (TaskInput) -> Unit,
    onTemplate: () -> Unit,
) {
    val context = LocalContext.current
    // The text survives a rotation or process death while Google's speech dialog covers the app (1.0.1).
    var kept by rememberSaveable(request.id) { mutableStateOf(request.text) }
    val state = remember(request) { QuickAddState(kept, Repo.zone(), weekStart) }
    LaunchedEffect(state) { snapshotFlow { state.text }.collect { kept = it } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var saving by remember(request) { mutableStateOf(false) }
    fun close(then: () -> Unit) {
        scope.launch { runCatching { sheet.hide() } }.invokeOnCompletion { then() }
    }
    QuickAddSheet(
        state = state, sheetState = sheet,
        onSave = save@{ input ->
            if (saving) return@save
            saving = true
            scope.launch {
                val id = withContext(Dispatchers.IO) { QuickAddSave.save(context, input) }
                saving = false
                if (id != null) close { onDismiss(); onSaved(id) }
            }
        },
        onMore = { input -> close { onDismiss(); onMore(input) } },
        onTemplate = { close { onDismiss(); onTemplate() } },
        onDismiss = onDismiss,
    )
}

/** Saving and undoing a Quick Add (through [Tasks.save] and [Repo.change], off the main thread). */
object QuickAddSave {
    /** Saves [input]; returns the new reminder's id, or null when it could not be saved. */
    fun save(context: Context, input: TaskInput, now: Instant = Instant.now()): String? = try {
        var id: String? = null
        Repo.change(context) { s -> Tasks.save(s, input, now, Repo.zone()).let { (next, task) -> id = task.id; next } }
        id
    } catch (_: TaskException) { null } catch (_: java.io.IOException) { null }

    /** «تراجع» after «أُضيف التذكير»: the reminder is removed as if it had never been added. */
    fun undo(context: Context, id: String) {
        Repo.change(context) { s -> Tasks.purge(Tasks.trash(s, id, Instant.now()), id) }
    }
}

/** The one-time note before the first voice input (F-048): kept on this phone. */
internal object QuickPrefs {
    private fun p(context: Context) = context.applicationContext.getSharedPreferences("quick", Context.MODE_PRIVATE)
    fun voiceDisclosed(context: Context) = p(context).getBoolean("voiceDisclosed", false)
    fun setVoiceDisclosed(context: Context) = p(context).edit { putBoolean("voiceDisclosed", true) }
}

/**
 * The Quick Add sheet (DESIGN-SPEC §5.3): the field (focused, «اكتب التذكير وموعده», mic), the preview card
 * (always before «حفظ»), the chips «من قالب», «التصنيف», «الأهمية», and «خيارات أكثر» · «حفظ». «حفظ» stays
 * disabled while the text is being read and while a question is open.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    state: QuickAddState,
    sheetState: SheetState,
    onSave: (TaskInput) -> Unit,
    onMore: (TaskInput) -> Unit,
    onTemplate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var disclose by rememberSaveable { mutableStateOf(false) }
    var dueDialog by remember { mutableStateOf<String?>(null) }
    var dueDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val windowInfo = LocalWindowInfo.current
    // 1.0.1: the mic only where the phone has a speech dialog and a speech service; never left at a dead dialog.
    val canVoice = remember { VoiceInput.available(context) }
    var voiceNote by remember { mutableStateOf<Int?>(null) }
    var voiceTag by rememberSaveable { mutableStateOf<String?>(null) }
    var backToField by remember { mutableStateOf(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        voiceNote = when (val o = VoiceInput.result(context, voiceTag, res.resultCode, res.data)) {
            is VoiceInput.Outcome.Text -> { state.append(o.text); null }
            VoiceInput.Outcome.NoText -> R.string.qa_voice_no_text
            VoiceInput.Outcome.Failed -> R.string.qa_voice_missing
        }
        // Back in the field with the keyboard open, whatever happened (its own mic is the way out).
        backToField = true
    }
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    fun listen() {
        if (asking) return
        voiceNote = null
        if (!VoiceInput.available(context)) { voiceNote = R.string.qa_voice_missing; backToField = true; return }
        asking = true
        scope.launch {
            try {
                // 1.0.2: which languages the service has, before opening its dialog (a second and a half at most).
                val d = VoiceInput.details(context)
                val tag = VoiceInput.language(context, d)
                voiceTag = tag
                // The dialog takes over the screen; the cursor and keyboard come back with the answer.
                focusManager.clearFocus()
                voice.launch(VoiceInput.intent(context, tag, VoiceInput.offline(tag, d)))
            } catch (_: ActivityNotFoundException) {
                voiceNote = R.string.qa_voice_missing
                backToField = true
            } catch (_: SecurityException) {
                voiceNote = R.string.qa_voice_missing
                backToField = true
            } finally {
                asking = false
            }
        }
    }
    LaunchedEffect(backToField) {
        if (backToField) {
            // Back from Google's dialog: wait until this window has the focus again, or the keyboard is ignored.
            withTimeoutOrNull(1_000) { snapshotFlow { windowInfo.isWindowFocused }.first { it } }
            runCatching { focus.requestFocus() }
            keyboard?.show()
            backToField = false
        }
    }
    // A voice note goes away as soon as the person types something else.
    var noteText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.text) { if (noteText != null && state.text != noteText) voiceNote = null }
    LaunchedEffect(voiceNote) { noteText = if (voiceNote != null) state.text else null }
    // The preview follows the typing after a short pause (150 ms).
    LaunchedEffect(state.text) {
        if (state.pending) { delay(Behaviour.QuickAddDebounceMs); state.parseNow() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val stacked = LocalDensity.current.fontScale >= 1.5f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // The handle's touch target is 48 dp wide (the default is 32).
        dragHandle = { Box(Modifier.sizeIn(minWidth = Dimens.touch, minHeight = Dimens.touch), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() } },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.l).padding(bottom = Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            TextField(
                value = state.text, onValueChange = state::onTextChange,
                label = { Text(stringResource(R.string.qa_label)) },
                minLines = 1, maxLines = 3,
                trailingIcon = if (!canVoice) null else ({
                    TooltipIconButton(Ms.Mic, stringResource(R.string.qa_voice), {
                        if (QuickPrefs.voiceDisclosed(context)) listen() else disclose = true
                    })
                }),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            voiceNote?.let { note ->
                Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.text.isNotBlank()) PreviewCard(state)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                // 1.0.3: «الموعد», first in reading order.
                DueChip(state, onOpen = { L10n.latinDigitsDefault(); dueDialog = "date" })
                AssistChip(onClick = onTemplate, label = { Text(stringResource(R.string.qa_template)) }, modifier = Modifier.heightIn(min = Dimens.touch),
                    leadingIcon = { Icon(Ms.DashboardCustomize, null, Modifier.size(18.dp)) })
                CategoryChip(state.category) { state.category = it }
                PriorityChip(state.priority) { state.priority = it }
            }
            state.pastPick?.let { past ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.s))
                    Text(stringResource(R.string.qa_due_past, L10n.dueText(context, past, Instant.now(), state.zone)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.pickTomorrow() }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.qa_due_tomorrow)) }
                }
            }
            if (state.asking && state.choice == null && !state.pending) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.s))
                Text(stringResource(R.string.qa_choose_first), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ButtonPair(
                stacked = stacked,
                secondary = { m ->
                    OutlinedButton(onClick = { onMore(state.draft()) }, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.qa_more)) }
                },
                primary = { m ->
                    Button(onClick = { state.toInput()?.let(onSave) }, enabled = state.canSave, modifier = m.heightIn(min = Dimens.touch)) {
                        Icon(Ms.Check, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(Dimens.s))
                        Text(stringResource(R.string.save))
                    }
                },
            )
        }
    }
    if (dueDialog != null) {
        val base = (state.dueAt ?: state.suggestedAt()).atZone(state.zone)
        when (dueDialog) {
            "date" -> LatinDigits {
                val today = java.time.LocalDate.now(state.zone)
                val picker = androidx.compose.material3.rememberDatePickerState(
                    initialSelectedDateMillis = base.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                    selectableDates = object : androidx.compose.material3.SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long) =
                            !Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate().isBefore(today)
                        override fun isSelectableYear(year: Int) = year >= today.year
                    },
                )
                androidx.compose.material3.DatePickerDialog(
                    onDismissRequest = { dueDialog = null },
                    dismissButton = { TextButton(onClick = { dueDialog = null }) { Text(stringResource(R.string.cancel)) } },
                    confirmButton = { TextButton(onClick = {
                        dueDate = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() } ?: base.toLocalDate()
                        dueDialog = "time"
                    }) { Text(stringResource(R.string.ok)) } },
                ) { PickerLocals { androidx.compose.material3.DatePicker(state = picker) } }
            }
            "time" -> {
                val picker = androidx.compose.material3.rememberTimePickerState(base.hour, base.minute, L10n.is24(context))
                AlertDialog(
                    onDismissRequest = { dueDialog = null },
                    dismissButton = { TextButton(onClick = { dueDialog = null }) { Text(stringResource(R.string.cancel)) } },
                    confirmButton = { TextButton(onClick = {
                        val day = dueDate ?: base.toLocalDate()
                        state.pick(day.atTime(picker.hour, picker.minute).atZone(state.zone).toInstant())
                        dueDialog = null
                    }) { Text(stringResource(R.string.ok)) } },
                    text = { PickerLocals { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { androidx.compose.material3.TimePicker(state = picker) } } },
                )
            }
        }
    }
    if (disclose) AlertDialog(
        onDismissRequest = { disclose = false },
        icon = { Icon(Ms.Mic, null) },
        title = { Text(stringResource(R.string.qa_voice_title)) },
        text = { Text(stringResource(R.string.qa_voice_body)) },
        dismissButton = { TextButton(onClick = { disclose = false; backToField = true }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { disclose = false; QuickPrefs.setVoiceDisclosed(context); listen() }) { Text(stringResource(R.string.continue_)) }
        },
    )
}

/**
 * Inside a picker dialog (its own window, so its own locals): Western digits, as everywhere in the app, and text
 * no larger than 130 %, where Material 3's fixed-size date cells and time fields stop overlapping.
 */
@Composable
private fun PickerLocals(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = remember(density) { androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, 1.3f)) }
    LatinDigits { androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides capped, content = content) }
}

/** The configuration's locale with Western digits (the date picker's state takes its locale where it is created). */
@Composable
private fun LatinDigits(content: @Composable () -> Unit) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val latn = remember(cfg) {
        android.content.res.Configuration(cfg).apply {
            setLocale(java.util.Locale.Builder().setLocale(cfg.locales[0]).setUnicodeLocaleKeyword("nu", "latn").build())
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides latn, content = content)
}

/**
 * «الموعد» (1.0.3): shows the moment that will be saved (from the text or picked), opens the date and time
 * pickers, and has its own ✕ when a moment is set. TalkBack reads «الموعد: …» or «الموعد: غير محدد».
 */
@Composable
private fun DueChip(state: QuickAddState, onOpen: () -> Unit) {
    val context = LocalContext.current
    val due = if (state.pending) null else state.dueAt
    val shown = due?.let { L10n.dueText(context, it, Instant.now(), state.zone) }
    val desc = stringResource(R.string.qa_due_desc, shown ?: stringResource(R.string.qa_due_none))
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = onOpen,
            label = { Text(shown ?: stringResource(R.string.qa_due)) },
            leadingIcon = { Icon(Ms.Schedule, null, Modifier.size(18.dp)) },
            modifier = Modifier.heightIn(min = Dimens.touch).semantics { contentDescription = desc },
        )
        if (due != null) TooltipIconButton(Ms.Close, stringResource(R.string.qa_due_clear), { state.clearPick() })
    }
}

/** The preview card: what will be saved, always shown before «حفظ» (never a date the person has not seen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(state: QuickAddState) {
    val context = LocalContext.current
    val now = Instant.now()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(start = Dimens.l, end = Dimens.xs, top = Dimens.m, bottom = Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            val due = state.dueAt
            when {
                state.asking -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Dimens.m)) {
                        Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(Dimens.m))
                        Text(question(context, state), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    TitleLine(state.title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), modifier = Modifier.padding(end = Dimens.m)) {
                        state.options.forEach { o ->
                            FilterChip(
                                selected = (state.choice as? QuickChoice.At)?.dueAt == o.dueAt,
                                onClick = { state.choose(QuickChoice.At(o.dueAt)) },
                                label = { Text(L10n.dueText(context, o.dueAt, now)) },
                                leadingIcon = { Icon(Ms.Event, null, Modifier.size(18.dp)) },
                                modifier = Modifier.heightIn(min = Dimens.touch),
                            )
                        }
                        FilterChip(
                            selected = state.choice == QuickChoice.NoDate,
                            onClick = { state.choose(QuickChoice.NoDate) },
                            label = { Text(stringResource(R.string.qa_no_date)) },
                            modifier = Modifier.heightIn(min = Dimens.touch),
                        )
                    }
                }
                due != null && !state.noDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ms.Event, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Dimens.m))
                        Column(Modifier.weight(1f)) {
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(L10n.dueText(context, due, now)) }
                                state.phrase?.takeIf { !state.picked }?.let { p ->
                                    append("  ")
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(p) }
                                }
                            }, style = MaterialTheme.typography.titleMedium)
                            repeatText(context, state)?.let { r ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Ms.Repeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(Dimens.xs))
                                    Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        TooltipIconButton(Ms.Close, stringResource(R.string.qa_no_date), { state.clearDate() })
                    }
                    TitleLine(state.title)
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Dimens.m)) {
                        Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Dimens.m))
                        Text(stringResource(R.string.qa_nothing), style = MaterialTheme.typography.bodyMedium)
                    }
                    TitleLine(state.title)
                }
            }
        }
    }
}

@Composable
private fun TitleLine(title: String) {
    if (title.isEmpty()) return
    // F-102: the title keeps its own direction inside «العنوان: …».
    val shown = L10n.isolate(LocalContext.current, title)
    Text(buildAnnotatedString {
        val full = stringResource(R.string.qa_title_is, shown)
        val at = full.indexOf(shown)
        if (at < 0) append(full) else {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(full.substring(0, at)) }
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(shown) }
            append(full.substring(at + shown.length))
        }
    }, style = MaterialTheme.typography.bodyLarge, maxLines = 3, modifier = Modifier.padding(end = Dimens.m))
}

/** The question for an ambiguous reading (DESIGN-SPEC §7.3). */
private fun question(context: Context, state: QuickAddState): String {
    val r = state.result ?: return ""
    return when (r.ask) {
        com.fastkiwi.rememberme.core.quickadd.Ask.MERIDIEM -> {
            val h = state.options.firstOrNull()?.dueAt?.atZone(state.zone)?.hour?.let { if (it % 12 == 0) 12 else it % 12 }
            context.getString(R.string.qa_ask_meridiem, h?.toString().orEmpty()).replace("  ", " ")
        }
        com.fastkiwi.rememberme.core.quickadd.Ask.DAY -> context.getString(R.string.qa_ask_day)
        com.fastkiwi.rememberme.core.quickadd.Ask.TIME -> context.getString(R.string.qa_ask_time)
        com.fastkiwi.rememberme.core.quickadd.Ask.PAST -> context.getString(R.string.qa_ask_past)
        else -> context.getString(R.string.qa_ask_choose)
    }
}

/** «كل يوم», «كل الأحد، الخميس»… for the repeat the text asked for. */
private fun repeatText(context: Context, state: QuickAddState): String? {
    val due = state.dueAt ?: return null
    val r = state.result ?: return null
    if (state.repeat == com.fastkiwi.rememberme.core.Repeat.NONE) return null
    val t = com.fastkiwi.rememberme.core.Task(
        id = "", title = "", dueAt = due, scheduleAt = due, repeat = state.repeat,
        rule = com.fastkiwi.rememberme.core.RepeatRule(days = r.days, everyDays = r.everyDays.coerceIn(2, 365)),
        createdAt = due, updatedAt = due,
    )
    return L10n.repeatText(context, t, state.zone)
}

@Composable
private fun CategoryChip(current: Category, onPick: (Category) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, modifier = Modifier.heightIn(min = Dimens.touch), label = { Text(stringResource(R.string.qa_category, categoryName(current))) },
            leadingIcon = { Icon(Ms.Label, null, Modifier.size(18.dp)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Category.entries.forEach { c ->
                DropdownMenuItem(text = { Text(categoryName(c)) }, onClick = { onPick(c); open = false },
                    leadingIcon = if (c == current) ({ Icon(Ms.Check, null) }) else null)
            }
        }
    }
}

@Composable
private fun PriorityChip(current: Priority, onPick: (Priority) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = if (current == Priority.NORMAL) stringResource(R.string.priority) else stringResource(R.string.qa_priority, priorityName(current))
        AssistChip(onClick = { open = true }, modifier = Modifier.heightIn(min = Dimens.touch), label = { Text(label) },
            leadingIcon = { Icon(if (current == Priority.HIGH) Ms.Flag else Ms.OutlinedFlag, null, Modifier.size(18.dp)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(Priority.NORMAL, Priority.MEDIUM, Priority.HIGH).forEach { p ->
                DropdownMenuItem(text = { Text(priorityName(p)) }, onClick = { onPick(p); open = false },
                    leadingIcon = if (p == current) ({ Icon(Ms.Check, null) }) else null)
            }
        }
    }
}
