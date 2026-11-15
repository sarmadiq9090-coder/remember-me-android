package com.fastkiwi.rememberme.ui.quick

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.semantics.Role
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
 * Hosts «تذكير جديد» over the current screen (1.0.4: a full-screen window): its state and the save. Call it while a
 * [QuickAddRequest] is open; [onDismiss] closes it. After «حفظ» it closes and [onSaved] gets the new reminder's id
 * (show «أُضيف التذكير» with «تراجع» and scroll the row into view; [QuickAddSave.undo] removes it again). [onMore]
 * gets the fields for the editor; [onTemplate] opens «من قالب».
 */
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
    val scope = rememberCoroutineScope()
    var saving by remember(request) { mutableStateOf(false) }
    QuickAddScreen(
        state = state,
        onSave = save@{ input ->
            if (saving) return@save
            saving = true
            scope.launch {
                val id = withContext(Dispatchers.IO) { QuickAddSave.save(context, input) }
                saving = false
                if (id != null) { onDismiss(); onSaved(id) }
            }
        },
        onMore = { input -> onDismiss(); onMore(input) },
        onTemplate = { onDismiss(); onTemplate() },
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
 * «تذكير جديد» (DESIGN-SPEC §5.3; 1.0.4, the owner: «اجعلها تبدو احترافيه»): a full-screen Material 3 window. The top
 * bar has «×» (asks «تجاهل؟» when there is text), the title and «حفظ» (never under the keyboard; enabled when there
 * is a title and nothing is still being read or asked). Then the field (the mic inside it, the title it will save
 * under it) and 56 dp list rows: «الموعد» (the moment read from the text or picked, shown once), «التصنيف»,
 * «الأهمية», «من قالب», «خيارات أكثر».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddScreen(
    state: QuickAddState,
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
    var discard by remember { mutableStateOf(false) }
    fun requestClose() { if (state.text.isNotBlank()) discard = true else onDismiss() }
    BackHandler { requestClose() }
    val now = Instant.now()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    navigationIcon = { TooltipIconButton(Ms.Close, stringResource(R.string.qa_close), { requestClose() }) },
                    title = { Text(stringResource(R.string.new_reminder), maxLines = 1) },
                    actions = {
                        Button(onClick = { state.toInput()?.let(onSave) }, enabled = state.canSave,
                            modifier = Modifier.padding(end = Dimens.s)) { Text(stringResource(R.string.save)) }
                    },
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                    .verticalScroll(rememberScrollState()).padding(top = Dimens.s, bottom = Dimens.l),
            ) {
                TextField(
                    value = state.text, onValueChange = state::onTextChange,
                    label = { Text(stringResource(R.string.qa_label)) },
                    minLines = 1, maxLines = 4,
                    trailingIcon = if (!canVoice) null else ({
                        TooltipIconButton(Ms.Mic, stringResource(R.string.qa_voice), {
                            if (QuickPrefs.voiceDisclosed(context)) listen() else disclose = true
                        })
                    }),
                    // What will be saved as the title (never a title the person has not seen).
                    supportingText = if (state.text.isBlank() || state.title.isEmpty()) null else ({ TitleLine(state.title) }),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier.padding(horizontal = Dimens.l).fillMaxWidth().focusRequester(focus),
                )
                voiceNote?.let { note ->
                    Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Spacer(Modifier.height(Dimens.s))
                // «الموعد»: the moment read from the text, or picked; shown here only.
                val due = if (state.pending) null else state.dueAt
                val dueShown = due?.let { L10n.dueText(context, it, now, state.zone) }
                ListRow(
                    icon = Ms.Schedule, label = stringResource(R.string.qa_due),
                    value = dueShown ?: stringResource(R.string.qa_due_none),
                    valueStrong = dueShown != null,
                    supporting = if (due != null) repeatText(context, state) else null,
                    desc = stringResource(R.string.qa_due_desc, dueShown ?: stringResource(R.string.qa_due_none)),
                    onClick = { L10n.latinDigitsDefault(); dueDialog = "date" },
                    trailing = if (due == null) null else ({ TooltipIconButton(Ms.Close, stringResource(R.string.qa_due_clear), { state.clearPick() }) }),
                )
                if (state.asking && !state.pending) AskBlock(state, now)
                state.pastPick?.let { past ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = Dimens.l).semantics { liveRegion = LiveRegionMode.Polite }) {
                        Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Dimens.s))
                        Text(stringResource(R.string.qa_due_past, L10n.dueText(context, past, now, state.zone)), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { state.pickTomorrow() }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.qa_due_tomorrow)) }
                    }
                }
                RowDivider()
                CategoryRow(state.category) { state.category = it }
                PriorityRow(state.priority) { state.priority = it }
                RowDivider()
                ListRow(icon = Ms.DashboardCustomize, label = stringResource(R.string.qa_template), desc = stringResource(R.string.qa_template),
                    onClick = onTemplate, chevron = true)
                ListRow(icon = Ms.Tune, label = stringResource(R.string.qa_more), desc = stringResource(R.string.qa_more),
                    onClick = { onMore(state.draft()) }, chevron = true)
            }
        }
    }
    if (discard) com.fastkiwi.rememberme.ui.ConfirmDialog(stringResource(R.string.discard_draft_title), null, stringResource(R.string.discard),
        onConfirm = { discard = false; onDismiss() }, onDismiss = { discard = false }, dismissLabel = stringResource(R.string.keep_editing))
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

/** A thin divider between groups of rows, inset from the start like Material 3 lists. */
@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = Dimens.l + 24.dp + Dimens.l, top = Dimens.xs, bottom = Dimens.xs),
        color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One Material 3 list row (56 dp at least, the full width): icon · label · value, and a chevron or a trailing
 * button. TalkBack reads [desc] («الموعد: غير محدد», «التصنيف: شخصي»…).
 */
@Composable
private fun ListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    desc: String,
    onClick: () -> Unit,
    value: String? = null,
    valueStrong: Boolean = false,
    supporting: String? = null,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) { contentDescription = desc }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = Dimens.l, end = if (trailing == null) Dimens.l else Dimens.xs, top = Dimens.s, bottom = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Dimens.l))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            value?.let {
                Spacer(Modifier.width(Dimens.m))
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (valueStrong) FontWeight.Medium else FontWeight.Normal),
                    color = if (valueStrong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (chevron) {
                Spacer(Modifier.width(Dimens.s))
                Icon(Ms.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        trailing?.let { Box(Modifier.padding(end = Dimens.xs)) { it() } }
    }
}

/** The question for an ambiguous time («5 صباحًا أم مساءً؟») with its choices, under «الموعد». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskBlock(state: QuickAddState, now: Instant) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.s))
            Text(question(context, state), style = MaterialTheme.typography.titleSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
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
        if (state.choice == null) Text(stringResource(R.string.qa_choose_first), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun CategoryRow(current: Category, onPick: (Category) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = categoryName(current)
    Box {
        ListRow(icon = Ms.Label, label = stringResource(R.string.category), value = name,
            desc = stringResource(R.string.qa_category, name), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Category.entries.forEach { c ->
                DropdownMenuItem(text = { Text(categoryName(c)) }, onClick = { onPick(c); open = false },
                    leadingIcon = if (c == current) ({ Icon(Ms.Check, null) }) else null)
            }
        }
    }
}

@Composable
private fun PriorityRow(current: Priority, onPick: (Priority) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = priorityName(current)
    Box {
        ListRow(icon = if (current == Priority.HIGH) Ms.Flag else Ms.OutlinedFlag, label = stringResource(R.string.priority), value = name,
            desc = stringResource(R.string.qa_priority, name), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(Priority.NORMAL, Priority.MEDIUM, Priority.HIGH).forEach { p ->
                DropdownMenuItem(text = { Text(priorityName(p)) }, onClick = { onPick(p); open = false },
                    leadingIcon = if (p == current) ({ Icon(Ms.Check, null) }) else null)
            }
        }
    }
}
