package com.fastkiwi.rememberme.ui.quick

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val state = remember(request) { QuickAddState(request.text, Repo.zone(), weekStart) }
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

/** The phone's speech service, offline when it can, in the app language. Needs no permission in this app. */
fun voiceIntent(context: Context): Intent {
    val locale = L10n.current(context)
    val tag = if (locale.country.isNullOrEmpty()) locale.language else locale.language + "-" + locale.country
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.qa_label))
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
    var disclose by remember { mutableStateOf(false) }
    var voiceMissing by remember { mutableStateOf(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(state::append)
    }
    fun listen() {
        voiceMissing = false
        try { voice.launch(voiceIntent(context)) } catch (_: ActivityNotFoundException) { voiceMissing = true }
    }
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
                trailingIcon = {
                    TooltipIconButton(Ms.Mic, stringResource(R.string.qa_voice), {
                        if (QuickPrefs.voiceDisclosed(context)) listen() else disclose = true
                    })
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (voiceMissing) Text(stringResource(R.string.qa_voice_missing), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            if (state.text.isNotBlank()) PreviewCard(state)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                AssistChip(onClick = onTemplate, label = { Text(stringResource(R.string.qa_template)) }, modifier = Modifier.heightIn(min = Dimens.touch),
                    leadingIcon = { Icon(Ms.DashboardCustomize, null, Modifier.size(18.dp)) })
                CategoryChip(state.category) { state.category = it }
                PriorityChip(state.priority) { state.priority = it }
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
    if (disclose) AlertDialog(
        onDismissRequest = { disclose = false },
        icon = { Icon(Ms.Mic, null) },
        title = { Text(stringResource(R.string.qa_voice_title)) },
        text = { Text(stringResource(R.string.qa_voice_body)) },
        dismissButton = { TextButton(onClick = { disclose = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { disclose = false; QuickPrefs.setVoiceDisclosed(context); listen() }) { Text(stringResource(R.string.continue_)) }
        },
    )
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
                                state.phrase?.let { p ->
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
    Text(buildAnnotatedString {
        val full = stringResource(R.string.qa_title_is, title)
        val at = full.indexOf(title)
        if (at < 0) append(full) else {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(full.substring(0, at)) }
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(title) }
            append(full.substring(at + title.length))
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
