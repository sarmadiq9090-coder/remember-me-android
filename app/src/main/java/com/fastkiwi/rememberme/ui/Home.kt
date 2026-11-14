package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Task
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class Tab { OVERDUE, TODAY, UPCOMING, DONE }

/** Which section a task belongs to. Tasks without a time are listed under Upcoming, after the dated ones. */
fun tabOf(t: Task, now: Instant, zone: ZoneId): Tab? {
    if (t.deletedAt != null) return null
    if (t.completedAt != null) return Tab.DONE
    val due = t.dueAt ?: return Tab.UPCOMING
    if (!due.isAfter(now)) return Tab.OVERDUE
    return if (due.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) Tab.TODAY else Tab.UPCOMING
}

fun listFor(state: AppState, tab: Tab, now: Instant, zone: ZoneId): List<Task> {
    val items = state.tasks.filter { tabOf(it, now, zone) == tab }
    return when (tab) {
        Tab.DONE -> items.sortedByDescending { it.completedAt }.take(200)
        Tab.UPCOMING -> items.sortedWith(compareBy<Task> { it.dueAt == null }.thenBy { it.dueAt }.thenBy { it.createdAt })
        else -> items.sortedBy { it.dueAt }
    }
}

/** Search in titles and notes of every task that is not in the trash; case and Arabic letter forms folded. */
fun search(state: AppState, query: String): List<Task> {
    val q = fold(query.trim())
    if (q.isEmpty()) return emptyList()
    return state.tasks.filter { it.deletedAt == null && (fold(it.title).contains(q) || fold(it.body).contains(q)) }
        .sortedWith(compareBy<Task> { it.completedAt != null }.thenBy { it.dueAt ?: Instant.MAX })
}

/** Lower case, no diacritics or tatweel, and one form for أ/إ/آ → ا, ى → ي, ة → ه. */
fun fold(s: String): String = buildString(s.length) {
    for (ch in s.lowercase()) {
        when (ch) {
            in 'ً'..'ٟ', 'ٰ', 'ـ' -> Unit
            'أ', 'إ', 'آ', 'ٱ' -> append('ا')
            'ى' -> append('ي')
            'ة' -> append('ه')
            else -> append(ch)
        }
    }
}

/** A snackbar another screen asks Home to show (a delete from the editor, with its undo). */
data class SnackRequest(val text: String, val undo: (() -> Unit)?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppState,
    now: Instant,
    health: Health,
    backupNudge: Boolean,
    onNew: () -> Unit,
    onOpen: (Task) -> Unit,
    onAlert: (Task) -> Unit,
    onComplete: (Task, (() -> Unit) -> Unit) -> Unit,
    onReopen: (Task) -> Unit,
    onTrash: (Task) -> Unit,
    onUndoTrash: (Task) -> Unit,
    onSettings: () -> Unit,
    onHealth: () -> Unit,
    onBackup: () -> Unit,
    onDismissBackup: () -> Unit,
    snack: SnackRequest? = null,
    onSnackShown: () -> Unit = {},
    newerData: Boolean = false,
) {
    val zone = ZoneId.systemDefault()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val doneText = stringResource(R.string.marked_done)
    val trashedText = stringResource(R.string.moved_to_trash)
    val undoText = stringResource(R.string.undo)
    val listState = rememberLazyListState()

    fun showSnack(text: String, undo: (() -> Unit)?) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, if (undo != null) undoText else null, withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    LaunchedEffect(snack) { if (snack != null) { showSnack(snack.text, snack.undo); onSnackShown() } }

    // The FAB shows its label at the top and while scrolling up; an icon while scrolling down.
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var pi = 0
        var po = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (i, o) ->
            expanded = (i == 0 && o == 0) || i < pi || (i == pi && o < po)
            pi = i
            po = o
        }
    }

    Scaffold(
        topBar = {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TopAppBar(
                    navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.close_search), { searching = false; query = "" }) },
                    title = {
                        TextField(
                            value = query, onValueChange = { query = it.take(200) }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(end = Dimens.s).focusRequester(focus),
                        )
                    },
                    actions = { if (query.isNotEmpty()) TooltipIconButton(Ms.Close, stringResource(R.string.clear), { query = "" }) },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(32.dp)
                            Spacer(Modifier.size(Dimens.m))
                            Text(stringResource(R.string.app_name), modifier = Modifier.semantics { heading() })
                        }
                    },
                    actions = {
                        TooltipIconButton(Ms.Search, stringResource(R.string.search), { searching = true })
                        TooltipIconButton(Ms.Settings, stringResource(R.string.settings), onSettings)
                    },
                )
            }
        },
        floatingActionButton = {
            if (!searching) ExtendedFloatingActionButton(
                onClick = onNew, expanded = expanded,
                // Collapsed to an icon while scrolling down: then the icon carries the label for TalkBack.
                icon = { Icon(Ms.Add, if (expanded) null else stringResource(R.string.new_reminder)) },
                text = { Text(stringResource(R.string.new_reminder)) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!searching) {
                // Data from a newer version is kept untouched; say so before anything else (D-079 A18).
                if (newerData) StatusBanner(Ms.WarningAmber, stringResource(R.string.banner_newer), stringResource(R.string.fix), true, onHealth)
                else if (!health.essentialOk) StatusBanner(Ms.WarningAmber, stringResource(R.string.banner_health), stringResource(R.string.fix), true, onHealth)
                else if (backupNudge) StatusBanner(null, stringResource(R.string.banner_backup), stringResource(R.string.save_copy), false, onBackup, onDismissBackup)
            }
            val rowActions = RowActions(
                onClick = { t -> if (t.completedAt == null && t.dueAt != null && !t.dueAt.isAfter(now)) onAlert(t) else if (t.completedAt == null) onOpen(t) },
                onComplete = { t -> onComplete(t) { undo -> showSnack(doneText, undo) } },
                onReopen = onReopen,
                onTrash = { t -> onTrash(t); showSnack(trashedText) { onUndoTrash(t) } },
            )
            if (searching) {
                val results = search(state, query)
                if (results.isEmpty()) Empty(stringResource(if (query.isBlank()) R.string.search_empty_hint else R.string.no_results))
                else LazyColumn(contentPadding = PaddingValues(top = Dimens.s, bottom = Dimens.xl)) {
                    rows(results, now, null, rowActions)
                }
                return@Column
            }
            val overdue = listFor(state, Tab.OVERDUE, now, zone)
            val today = listFor(state, Tab.TODAY, now, zone)
            val upcoming = listFor(state, Tab.UPCOMING, now, zone)
            val done = listFor(state, Tab.DONE, now, zone)
            if (overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty() && done.isEmpty()) {
                EmptyAll()
                return@Column
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Dimens.fabClearance)) {
                if (overdue.isNotEmpty()) {
                    item(key = "h-overdue") { SectionHeader(stringResource(R.string.tab_overdue), overdue.size, error = true) }
                    rows(overdue, now, Tab.OVERDUE, rowActions)
                }
                item(key = "h-today") { SectionHeader(stringResource(R.string.tab_today), today.size.takeIf { it > 0 }) }
                if (today.isEmpty()) item(key = "today-empty") {
                    Text(stringResource(R.string.empty_today_inline), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.s))
                } else rows(today, now, Tab.TODAY, rowActions)
                if (upcoming.isNotEmpty()) {
                    item(key = "h-upcoming") { SectionHeader(stringResource(R.string.tab_upcoming), upcoming.size) }
                    rows(upcoming, now, Tab.UPCOMING, rowActions)
                }
                if (done.isNotEmpty()) {
                    item(key = "h-done") {
                        val state = stringResource(if (showDone) R.string.section_done_hide else R.string.section_done_show)
                        Box(Modifier.semantics { stateDescription = state }) {
                            SectionHeader(stringResource(R.string.tab_done), done.size, onClick = { showDone = !showDone }) {
                                Icon(if (showDone) Ms.ExpandLess else Ms.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (showDone) rows(done, now, Tab.DONE, rowActions)
                }
            }
        }
    }
}

data class RowActions(val onClick: (Task) -> Unit, val onComplete: (Task) -> Unit, val onReopen: (Task) -> Unit, val onTrash: (Task) -> Unit)

private fun LazyListScope.rows(items: List<Task>, now: Instant, section: Tab?, a: RowActions) {
    itemsIndexed(items, key = { _, t -> "${section?.name}-${t.id}" }) { i, t ->
        GroupItem(i, items.size) { ReminderRow(t, now, section, a) }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(Dimens.xxl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmptyAll() {
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.xxl).padding(bottom = Dimens.fabClearance),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Ms.NotificationsActive, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(Dimens.xl))
        Text(stringResource(R.string.empty_all_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s))
        Text(stringResource(R.string.empty_all_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** One reminder: the Done circle at the start, the time as the row's hero, the title under it, then repeat and priority. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderRow(t: Task, now: Instant, section: Tab?, a: RowActions) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val done = t.completedAt != null
    val overdue = !done && t.dueAt != null && !t.dueAt.isAfter(now)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = when { overdue -> MaterialTheme.colorScheme.error; done -> muted; else -> MaterialTheme.colorScheme.onSurface }
    // The label after the time: how late, or which day, or nothing for today.
    val label = L10n.rowLabel(context, t, now, section == Tab.TODAY)
    val doneLabel = stringResource(R.string.mark_done_named, t.title)
    val doneAction = stringResource(R.string.done)
    val summary = L10n.rowSummary(context, t, now, label, if (t.priority.code != "normal") priorityName(t.priority) else null)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowMin)
            .semantics(mergeDescendants = false) {
                contentDescription = summary
                role = Role.Button
                if (!done) customActions = listOf(CustomAccessibilityAction(doneAction) { a.onComplete(t); true })
            }
            .clickable(enabled = !done) { a.onClick(t) }
            .padding(start = Dimens.xs, end = Dimens.l, top = Dimens.s, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The Done target: 48 dp, a 22 dp circle drawn inside.
        Box(
            Modifier.size(Dimens.touch)
                .toggleable(value = done, role = Role.Checkbox, enabled = !done) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    a.onComplete(t)
                }
                .semantics { contentDescription = doneLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) Ms.CheckCircle else Ms.RadioButtonUnchecked, null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(26.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = Dimens.xs).clearAndSetSemantics { }) {
            FlowRow(itemVerticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    done -> Text(label!!, style = MaterialTheme.typography.bodyMedium, color = muted)
                    t.dueAt == null -> Text(stringResource(R.string.no_time), style = MaterialTheme.typography.bodyMedium, color = muted)
                    else -> {
                        TimeText(t.dueAt, RowTime, timeColor)
                        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = if (overdue) MaterialTheme.colorScheme.error else muted, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
            }
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (done) muted else MaterialTheme.colorScheme.onSurface)
            if (!done) Box(Modifier.padding(top = 2.dp)) { TaskMeta(t) }
        }
        if (done) Box {
            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (t.sourceId == null) DropdownMenuItem(text = { Text(stringResource(R.string.reopen)) }, onClick = { menu = false; a.onReopen(t) })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; a.onTrash(t) })
            }
        }
    }
}
