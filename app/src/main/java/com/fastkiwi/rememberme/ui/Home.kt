package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class Tab { OVERDUE, TODAY, UPCOMING, DONE }

/** Which tab a task belongs to. Tasks without a time are listed under Upcoming, after the dated ones. */
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
) {
    val zone = ZoneId.systemDefault()
    val overdue = listFor(state, Tab.OVERDUE, now, zone)
    var tab by rememberSaveable { mutableStateOf(if (overdue.isNotEmpty()) Tab.OVERDUE else Tab.TODAY) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val doneText = stringResource(R.string.marked_done)
    val trashedText = stringResource(R.string.moved_to_trash)
    val undoText = stringResource(R.string.undo)

    fun snack(text: String, undo: () -> Unit) {
        scope.launch {
            val r = snackbar.showSnackbar(text, undoText, withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    Scaffold(
        topBar = {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_search))
                        }
                    },
                    title = {
                        TextField(
                            value = query, onValueChange = { query = it.take(200) }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    },
                    actions = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), modifier = Modifier.semantics { heading() }) },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!searching) ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.new_reminder)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!health.essentialOk) Banner(stringResource(R.string.banner_health), stringResource(R.string.fix), true, onHealth)
            else if (backupNudge) Banner(stringResource(R.string.banner_backup), stringResource(R.string.save_copy), false, onBackup, onDismissBackup)
            val items: List<Task>
            if (searching) {
                items = search(state, query)
            } else {
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                    Tab.entries.forEach { t ->
                        val label = stringResource(when (t) { Tab.OVERDUE -> R.string.tab_overdue; Tab.TODAY -> R.string.tab_today; Tab.UPCOMING -> R.string.tab_upcoming; Tab.DONE -> R.string.tab_done })
                        Tab(selected = tab == t, onClick = { tab = t }, modifier = Modifier.heightIn(min = 48.dp), text = {
                            if (t == Tab.OVERDUE && overdue.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(overdue.size.toString()) } }) { Text(label, modifier = Modifier.padding(end = 12.dp)) }
                            } else Text(label)
                        })
                    }
                }
                items = listFor(state, tab, now, zone)
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (searching) (if (query.isBlank()) R.string.search_empty_hint else R.string.no_results) else when (tab) {
                            Tab.OVERDUE -> R.string.empty_overdue; Tab.TODAY -> R.string.empty_today
                            Tab.UPCOMING -> R.string.empty_upcoming; Tab.DONE -> R.string.empty_done
                        }),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { t ->
                        TaskRow(t, now,
                            onClick = { if (t.completedAt == null && t.dueAt != null && !t.dueAt.isAfter(now)) onAlert(t) else if (t.completedAt == null) onOpen(t) },
                            onComplete = { onComplete(t) { undo -> snack(doneText, undo) } },
                            onReopen = { onReopen(t) },
                            onTrash = { onTrash(t); snack(trashedText) { onUndoTrash(t) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Banner(text: String, action: String, alarm: Boolean, onClick: () -> Unit, onDismiss: (() -> Unit)? = null) {
    val bg = if (alarm) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (alarm) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alarm) Icon(Icons.Filled.Warning, null, tint = fg)
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action, color = fg) }
        if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.not_now), tint = fg) }
    }
}

@Composable
fun TaskRow(t: Task, now: Instant, onClick: () -> Unit, onComplete: () -> Unit, onReopen: () -> Unit, onTrash: () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val overdue = t.completedAt == null && t.dueAt != null && !t.dueAt.isAfter(now)
    val when_ = t.dueAt?.let { L10n.dueText(context, it, now) } ?: stringResource(R.string.no_time)
    val late = if (overdue) L10n.late(context, t.dueAt!!, now) else null
    val repeat = L10n.repeatText(context, t)
    val summary = listOfNotNull(t.title, when_, late, repeat, if (t.priority.code == "high") stringResource(R.string.priority_high) else null).joinToString(", ")
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = false) { contentDescription = summary },
    ) {
        Row(Modifier.heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).fillMaxHeight().heightIn(min = 64.dp).background(priorityColor(t.priority.code, dark)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.priority.code == "high") Icon(Icons.Filled.PriorityHigh, null, tint = priorityColor("high", dark), modifier = Modifier.padding(end = 4.dp))
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(if (t.completedAt != null) L10n.dueText(context, t.completedAt, now) else when_, late).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (repeat != null && t.repeat != Repeat.NONE) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Repeat, null, modifier = Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(repeat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (t.completedAt == null) {
                IconButton(onClick = onComplete) {
                    Icon(Icons.Outlined.Circle, stringResource(R.string.mark_done_named, t.title), tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                if (t.sourceId == null) IconButton(onClick = onReopen) { Icon(Icons.Filled.Restore, stringResource(R.string.reopen_named, t.title)) }
                else Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                IconButton(onClick = onTrash) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_named, t.title)) }
            }
        }
    }
}
