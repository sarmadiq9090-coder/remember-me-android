package com.fastkiwi.rememberme.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.saveable.listSaver
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
import androidx.compose.ui.platform.LocalResources
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
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Bulk
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.core.Search
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.core.tags
import com.fastkiwi.rememberme.ui.home.BulkActionBar
import com.fastkiwi.rememberme.ui.home.HomeView
import com.fastkiwi.rememberme.ui.home.QuickFilterRow
import com.fastkiwi.rememberme.ui.home.QuickFilters
import com.fastkiwi.rememberme.ui.home.SelectionTopBar
import com.fastkiwi.rememberme.ui.home.SnoozeSheet
import com.fastkiwi.rememberme.ui.home.StateTag
import com.fastkiwi.rememberme.ui.home.SwipeableReminderRow
import com.fastkiwi.rememberme.ui.home.TagKind
import com.fastkiwi.rememberme.ui.home.ReminderDetail
import com.fastkiwi.rememberme.ui.home.TwoPane
import com.fastkiwi.rememberme.ui.home.WidthClass
import com.fastkiwi.rememberme.ui.home.widthClass
import com.fastkiwi.rememberme.ui.home.ViewMenuButton
import com.fastkiwi.rememberme.ui.home.snoozeChoices
import com.fastkiwi.rememberme.ui.home.snoozeResult
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

/** Search in every task that is not in the trash (F-065: title, note, tags, category and state), letters folded. */
fun search(state: AppState, query: String, now: Instant = Instant.now(), words: Search.Words = Search.Words()): List<Task> {
    if (Search.terms(query).isEmpty()) return emptyList()
    return Search.run(state.tasks, Search.Query(text = query), now, words)
}

/** Lower case, no diacritics or tatweel, and one form for أ/إ/آ → ا, ى → ي, ة → ه (the Windows folding). */
fun fold(s: String): String = Organize.fold(s)

/** A snackbar another screen asks Home to show (a delete from the editor, with its undo). */
data class SnackRequest(val text: String, val undo: (() -> Unit)?)

/** Saves a set of ids across rotation. */
private val IdSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * Home (DESIGN-SPEC §5.1): one list in sections, quick filters, swipe actions (§5.2), the Snooze sheet
 * (§5.9), bulk selection (§5.17) and search (F-065). The calendar views are drawn by [calendar]; Home
 * always opens in the list and Back from a view returns to it.
 *
 * [change] runs a data change off the main thread and then calls its second argument on the main thread
 * (used for snooze, bulk actions and their Undo). [onQuickAdd] is the FAB; [onNew] stays for the launcher
 * shortcut's editor path.
 */
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
    onQuickAdd: () -> Unit = onNew,
    calendar: @Composable (HomeView) -> Unit = {},
    filters: List<SavedFilter> = emptyList(),
    onOpenLists: () -> Unit = {},
    swipe: Boolean = true,
    change: (block: (AppState) -> AppState, then: () -> Unit) -> Unit = { _, _ -> },
    onExportSelected: (List<String>) -> Unit = {},
    /** The FAB and an hour row in a calendar view: a new reminder on that day. */
    onNewOnDay: (java.time.LocalDate) -> Unit = { onQuickAdd() },
    hijri: Boolean = false,
    hijriAdjust: Int = 0,
    /** Bumped by the «اليوم» launcher shortcut (F-122): open the day view on today. */
    todayRequest: Long = 0L,
    /** A reminder just added (Quick Add): scrolled into view once (DESIGN-SPEC §5.3). */
    scrollToId: String? = null,
    onScrolled: () -> Unit = {},
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val zone = ZoneId.systemDefault()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var viewName by rememberSaveable { mutableStateOf(HomeView.LIST.name) }
    val view = HomeView.valueOf(viewName)
    /** The day the calendar views show (F-064); today when Home opens. */
    var calDay by rememberSaveable { mutableStateOf(now.atZone(zone).toLocalDate().toEpochDay()) }
    val calSelected = java.time.LocalDate.ofEpochDay(calDay)
    val inCalendar = view != HomeView.LIST
    LaunchedEffect(todayRequest) {
        if (todayRequest > 0) { viewName = HomeView.DAY.name; calDay = now.atZone(zone).toLocalDate().toEpochDay() }
    }
    var quick by rememberSaveable { mutableStateOf(QuickFilters.ALL) }
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(emptySet<String>()) }
    /** Ids the Snooze sheet is open for (one row, or the selection), and whether the date-time picker is up. */
    var snoozeIds by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(emptySet<String>()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    /** The bulk dialog on screen: "trash", "done", "priority", "move", or null. */
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val selecting = selected.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoText = stringResource(R.string.undo)
    val trashedText = stringResource(R.string.moved_to_trash)
    val listState = rememberLazyListState()
    val reduced = LocalReducedMotion.current

    fun showSnack(text: String, undo: (() -> Unit)?) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, if (undo != null) undoText else null, withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    LaunchedEffect(snack) { if (snack != null) { showSnack(snack.text, snack.undo); onSnackShown() } }

    // Back leaves selection, then search, then a calendar view (Home itself is the root).
    BackHandler(enabled = selecting) { selected = emptySet() }
    BackHandler(enabled = !selecting && searching) { searching = false; query = "" }
    BackHandler(enabled = !selecting && !searching && view != HomeView.LIST) { viewName = HomeView.LIST.name }

    /** Runs a bulk action and offers its Undo; [text] says what happened. */
    fun bulk(ids: Collection<String>, action: (AppState, Instant) -> Bulk.Result, text: (Int) -> String) {
        var result: Bulk.Result? = null
        change({ s -> runCatching { action(s, Instant.now()) }.getOrNull()?.also { result = it }?.state ?: s }) {
            val r = result ?: return@change
            if (r.count > 0) showSnack(text(r.count)) { change({ s -> Bulk.undo(s, r.undo, Instant.now()) }) {} }
        }
        selected = emptySet()
    }

    fun snoozeTo(at: Instant) {
        val ids = snoozeIds
        snoozeIds = emptySet()
        picking = false
        selected = emptySet()
        // «أُجّل حتى 4:30 م» later today; «أُجّل حتى الأحد، 2:00 م» on another day.
        val sameDay = at.atZone(zone).toLocalDate() == Instant.now().atZone(zone).toLocalDate()
        val msg = res.getString(R.string.snoozed_until_msg, if (sameDay) L10n.time(context, at, zone) else snoozeResult(context, at, Instant.now(), zone))
        bulk(ids, { s, n -> Bulk.snooze(s, ids, at, n) }) { msg }
    }

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

    val words = Search.Words(
        categories = Category.entries.associateWith { res.getString(categoryRes(it)) },
        states = mapOf(
            Search.State.UPCOMING to res.getString(R.string.home_state_upcoming),
            Search.State.DUE to res.getString(R.string.home_state_due),
            Search.State.OVERDUE to res.getString(R.string.home_state_overdue),
            Search.State.SNOOZED to res.getString(R.string.state_snoozed),
            Search.State.DONE to res.getString(R.string.home_state_done),
        ),
    )
    // Quick filters (F-063): a filter that no longer exists falls back to «الكل».
    val chipsShown = QuickFilters.shown(filters)
    val quickQuery = if (chipsShown) QuickFilters.query(quick, filters, now, zone, Repo.weekStart(state)) else null
    val shownState = if (quickQuery == null) state else {
        val terms = Search.terms(quickQuery.text)
        state.copy(tasks = state.tasks.filter { Search.matches(it, quickQuery, terms, now, words) })
    }
    val overdue = listFor(shownState, Tab.OVERDUE, now, zone)
    val today = listFor(shownState, Tab.TODAY, now, zone)
    val upcoming = listFor(shownState, Tab.UPCOMING, now, zone)
    val done = listFor(shownState, Tab.DONE, now, zone)
    val results = if (searching) search(state, query, now, words) else emptyList()
    val visible = if (searching) results else overdue + today + upcoming + (if (showDone) done else emptyList())
    // Tablets (§3.7): from 840 dp a row shows its details in the pane at the end instead of opening the editor.
    val width = widthClass()
    val twoPane = width == WidthClass.EXPANDED
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    fun open(t: Task) {
        if (twoPane) detailId = t.id
        else if (t.completedAt == null && t.dueAt != null && !t.dueAt.isAfter(now)) onAlert(t) else if (t.completedAt == null) onOpen(t)
    }

    val listPane: @Composable () -> Unit = { Scaffold(
        topBar = {
            when {
                selecting -> SelectionTopBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = { selected = visible.map { it.id }.toSet() },
                    onExport = { onExportSelected(state.tasks.filter { it.id in selected }.map { it.id }); selected = emptySet() },
                )
                searching -> {
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
                }
                // The calendar views draw their own header (period, «اليوم», view menu, settings).
                inCalendar -> {}
                else -> TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(32.dp)
                            Spacer(Modifier.size(Dimens.m))
                            Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        }
                    },
                    actions = {
                        ViewMenuButton(view) { viewName = it.name }
                        TooltipIconButton(Ms.Search, stringResource(R.string.search), { searching = true })
                        TooltipIconButton(Ms.Settings, stringResource(R.string.settings), onSettings)
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) BulkActionBar(
                onDelete = { if (Bulk.needsConfirm(selected.size, Behaviour.BulkConfirmFrom)) dialog = "trash"
                    else { val ids = selected; bulk(ids, { s, n -> Bulk.trash(s, ids, n) }) { L10n.plural(context, R.plurals.bulk_trash_msg, it.toLong()) } } },
                onPriority = { dialog = "priority" },
                onMove = { dialog = "move" },
                onSnooze = { snoozeIds = selected },
                onDone = { if (Bulk.needsConfirm(selected.size, Behaviour.BulkConfirmFrom)) dialog = "done"
                    else { val ids = selected; bulk(ids, { s, n -> Bulk.complete(s, ids, n, zone) }) { L10n.plural(context, R.plurals.bulk_done_msg, it.toLong()) } } },
            )
        },
        floatingActionButton = {
            if (!searching && !selecting) ExtendedFloatingActionButton(
                onClick = { if (inCalendar) onNewOnDay(calSelected) else onQuickAdd() }, expanded = expanded,
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
            if (!searching && !selecting) {
                // Data from a newer version is kept untouched; say so before anything else (D-079 A18).
                if (newerData) StatusBanner(Ms.WarningAmber, stringResource(R.string.banner_newer), stringResource(R.string.fix), true, onHealth)
                else if (!health.essentialOk) StatusBanner(Ms.WarningAmber, stringResource(R.string.banner_health), stringResource(R.string.fix), true, onHealth)
                else if (backupNudge) StatusBanner(null, stringResource(R.string.banner_backup), stringResource(R.string.save_copy), false, onBackup, onDismissBackup)
            }
            val rowActions = RowActions(
                onClick = { t ->
                    if (selecting) selected = if (t.id in selected) selected - t.id else selected + t.id
                    else open(t)
                },
                onComplete = { t -> onComplete(t) { undo -> showSnack(res.getString(R.string.done_undo_msg, t.title), undo) } },
                onReopen = onReopen,
                onTrash = { t -> onTrash(t); showSnack(trashedText) { onUndoTrash(t) } },
                onSnooze = { t -> snoozeIds = setOf(t.id) },
                onSelect = { t -> selected = if (t.id in selected) selected - t.id else selected + t.id },
            )
            if (!searching && !selecting && inCalendar) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    com.fastkiwi.rememberme.ui.calendar.CalendarScreen(
                        view = com.fastkiwi.rememberme.ui.calendar.CalView.valueOf(view.name),
                        state = state, now = now, zone = zone, selected = calSelected,
                        onSelectDay = { calDay = it.toEpochDay() },
                        onViewChange = { viewName = it.name },
                        onToday = { calDay = now.atZone(zone).toLocalDate().toEpochDay() },
                        onOpen = { t -> if (twoPane) detailId = t.id else if (t.completedAt == null && t.dueAt != null && !t.dueAt.isAfter(now)) onAlert(t) else onOpen(t) },
                        onNewOnDay = onNewOnDay,
                        row = { t -> ReminderRow(t, now, null, rowActions) },
                        hijri = hijri, hijriAdjust = hijriAdjust,
                        viewMenu = { ViewMenuButton(view) { viewName = it.name } },
                        onSettings = onSettings,
                    )
                }
                return@Column
            }
            val look = RowLook(selected = if (selecting) selected else null, swipe = swipe && !selecting, reduced = reduced,
                current = if (twoPane && !selecting) detailId else null)
            if (searching) {
                if (results.isEmpty()) Empty(stringResource(if (query.isBlank()) R.string.search_empty_hint else R.string.no_results))
                else LazyColumn(contentPadding = PaddingValues(top = Dimens.s, bottom = Dimens.xl)) {
                    rows(results, now, null, rowActions, look.copy(showTags = true))
                }
                return@Column
            }
            if (chipsShown && !selecting) {
                QuickFilterRow(
                    QuickFilters.chips(filters, stringResource(R.string.chip_all), stringResource(R.string.priority_high)),
                    selected = if (quickQuery == null) QuickFilters.ALL else quick,
                    onSelect = { quick = it }, onOpenLists = onOpenLists,
                )
            }
            if (overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty() && done.isEmpty()) {
                if (quickQuery != null) Empty(stringResource(R.string.no_results)) else EmptyAll()
                return@Column
            }
            val filtered = quickQuery != null
            // The list position of a row, in the order the items below are emitted.
            fun indexOf(id: String): Int? {
                var i = 0
                if (overdue.isNotEmpty()) { i++; overdue.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return i + it }; i += overdue.size }
                i++
                if (today.isEmpty()) i++ else { today.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return i + it }; i += today.size }
                if (upcoming.isNotEmpty()) { i++; upcoming.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return i + it }; i += upcoming.size }
                return null
            }
            LaunchedEffect(scrollToId, state) {
                val id = scrollToId ?: return@LaunchedEffect
                indexOf(id)?.let { runCatching { listState.animateScrollToItem(it) } }
                onScrolled()
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Dimens.fabClearance)) {
                if (overdue.isNotEmpty()) {
                    item(key = "h-overdue") { SectionHeader(stringResource(R.string.tab_overdue), overdue.size, error = true) }
                    rows(overdue, now, Tab.OVERDUE, rowActions, look.copy(showTags = filtered))
                }
                item(key = "h-today") { SectionHeader(stringResource(R.string.tab_today), today.size.takeIf { it > 0 }) }
                if (today.isEmpty()) item(key = "today-empty") {
                    Text(stringResource(R.string.empty_today_inline), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.s))
                } else rows(today, now, Tab.TODAY, rowActions, look.copy(showTags = filtered))
                if (upcoming.isNotEmpty()) {
                    item(key = "h-upcoming") { SectionHeader(stringResource(R.string.tab_upcoming), upcoming.size) }
                    rows(upcoming, now, Tab.UPCOMING, rowActions, look.copy(showTags = filtered))
                }
                if (done.isNotEmpty()) {
                    item(key = "h-done") {
                        val folded = stringResource(if (showDone) R.string.section_done_hide else R.string.section_done_show)
                        Box(Modifier.semantics { stateDescription = folded }) {
                            SectionHeader(stringResource(R.string.tab_done), done.size, onClick = { showDone = !showDone }) {
                                Icon(if (showDone) Ms.ExpandLess else Ms.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (showDone) rows(done, now, Tab.DONE, rowActions, look.copy(showTags = filtered))
                }
            }
        }
    } }

    when (width) {
        WidthClass.EXPANDED -> TwoPane(list = listPane) {
            ReminderDetail(
                task = detailId?.let { id -> state.tasks.firstOrNull { it.id == id && it.deletedAt == null } },
                now = now,
                onDone = { t -> onComplete(t) { undo -> showSnack(res.getString(R.string.done_undo_msg, t.title), undo) } },
                onSnooze = { t -> snoozeIds = setOf(t.id) },
                onEdit = onOpen,
                onTrash = { t -> onTrash(t); detailId = null; showSnack(trashedText) { onUndoTrash(t) } },
                onToggleStep = { t, stepId ->
                    change({ s -> s.copy(tasks = s.tasks.map { if (it.id == t.id) toggleStep(it, stepId).copy(updatedAt = Instant.now()) else it }) }) {}
                },
            )
        }
        // 600–839 dp: one pane, centred, at most 640 dp wide.
        WidthClass.MEDIUM -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = Dimens.maxContent).fillMaxSize()) { listPane() }
        }
        WidthClass.COMPACT -> listPane()
    }

    // ---- Sheets and dialogs ----
    val presets = SnoozePreset.fromSettings(state.settings)
    if (snoozeIds.isNotEmpty() && !picking) {
        val targets = state.tasks.filter { it.id in snoozeIds && it.active }
        if (targets.isEmpty()) LaunchedEffect(Unit) { snoozeIds = emptySet() }
        else {
            val choices = snoozeChoices(context, presets, now, zone)
            SnoozeSheet(
                title = if (targets.size == 1) targets[0].title else L10n.plural(context, R.plurals.selected_n, targets.size.toLong()),
                presets = choices.map { it.label to it.result },
                onPick = { i -> snoozeTo(choices[i].at) },
                onPickTime = { picking = true },
                onDismiss = { snoozeIds = emptySet() },
                repeats = targets.any { it.repeat != com.fastkiwi.rememberme.core.Repeat.NONE },
            )
        }
    }
    if (snoozeIds.isNotEmpty() && picking) {
        SnoozePicker(now, zone, onPick = { at -> snoozeTo(at) }, onCancel = { picking = false; snoozeIds = emptySet() })
    }
    when (dialog) {
        "trash" -> {
            val ids = selected
            val repeating = Bulk.repeating(state, ids)
            val body = stringResource(R.string.bulk_trash_body) +
                (if (repeating > 0) " " + L10n.plural(context, R.plurals.bulk_repeating_note, repeating.toLong()) else "")
            ConfirmDialog(
                title = L10n.plural(context, R.plurals.bulk_trash_q, ids.size.toLong()), text = body,
                confirm = stringResource(R.string.move_to_trash), destructive = true,
                onConfirm = { bulk(ids, { s, n -> Bulk.trash(s, ids, n) }) { L10n.plural(context, R.plurals.bulk_trash_msg, it.toLong()) }; selected = emptySet() },
                onDismiss = { dialog = null },
            )
        }
        "done" -> {
            val ids = selected
            ConfirmDialog(
                title = L10n.plural(context, R.plurals.bulk_done_q, ids.size.toLong()), text = null,
                confirm = stringResource(R.string.done),
                onConfirm = { bulk(ids, { s, n -> Bulk.complete(s, ids, n, zone) }) { L10n.plural(context, R.plurals.bulk_done_msg, it.toLong()) }; selected = emptySet() },
                onDismiss = { dialog = null },
            )
        }
        "priority" -> {
            val ids = selected
            val current = state.tasks.filter { it.id in ids }.map { it.priority }.distinct().singleOrNull()?.code ?: ""
            ChoiceDialog(
                title = stringResource(R.string.bulk_priority),
                options = Priority.entries.reversed().map { it.code to res.getString(priorityRes(it)) },
                selected = current,
                onSelect = { code ->
                    val p = Priority.of(code)
                    bulk(ids, { s, n -> Bulk.setPriority(s, ids, p, n) }) { L10n.plural(context, R.plurals.bulk_changed_msg, it.toLong()) }
                    selected = emptySet()
                },
                onDismiss = { dialog = null },
            )
        }
        "move" -> {
            val ids = selected
            val lists = filters.filter { it.kind == "tag" && it.value.isNotBlank() }
            val options = Category.entries.map { "cat:" + it.code to res.getString(R.string.home_move_category, res.getString(categoryRes(it))) } +
                lists.map { "tag:" + it.value to res.getString(R.string.home_move_list, it.name.ifBlank { it.value }) }
            ChoiceDialog(
                title = stringResource(R.string.bulk_move_title), options = options, selected = "",
                onSelect = { code ->
                    val msg: (Int) -> String = { L10n.plural(context, R.plurals.bulk_changed_msg, it.toLong()) }
                    if (code.startsWith("cat:")) { val c = Category.of(code.removePrefix("cat:")); bulk(ids, { s, n -> Bulk.setCategory(s, ids, c, n) }, msg) }
                    else { val tag = code.removePrefix("tag:"); bulk(ids, { s, n -> Bulk.addTag(s, ids, tag, n) }, msg) }
                    selected = emptySet()
                },
                onDismiss = { dialog = null },
            )
        }
    }
}

private fun categoryRes(c: Category) = when (c) { Category.PERSONAL -> R.string.cat_personal; Category.WORK -> R.string.cat_work; Category.STUDY -> R.string.cat_study }
private fun priorityRes(p: Priority) = when (p) { Priority.NORMAL -> R.string.priority_normal; Priority.MEDIUM -> R.string.priority_medium; Priority.HIGH -> R.string.priority_high }

/**
 * What a row can do. [onSnooze] opens the Snooze sheet («أجّل», swipe end→start); [onSelect] enters or
 * toggles bulk selection («تحديد», long-press). Both may be null where a screen has no such action.
 */
data class RowActions(
    val onClick: (Task) -> Unit,
    val onComplete: (Task) -> Unit,
    val onReopen: (Task) -> Unit,
    val onTrash: (Task) -> Unit,
    val onSnooze: ((Task) -> Unit)? = null,
    val onSelect: ((Task) -> Unit)? = null,
)

/** How rows are drawn: [selected] non-null = selection mode; [swipe] = swipe actions on; [showTags] in search and filters. */
data class RowLook(
    val selected: Set<String>? = null, val swipe: Boolean = false, val showTags: Boolean = false, val reduced: Boolean = false,
    /** The reminder shown in the tablet's detail pane (highlighted). */
    val current: String? = null,
)

private fun LazyListScope.rows(items: List<Task>, now: Instant, section: Tab?, a: RowActions, look: RowLook) {
    itemsIndexed(items, key = { _, t -> "${section?.name}-${t.id}" }) { i, t ->
        val sel = look.selected?.let { t.id in it }
        Box(if (look.reduced) Modifier else Modifier.animateItem()) {
            val on = sel == true || (look.selected == null && t.id == look.current)
            GroupItem(i, items.size, color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                SwipeableReminderRow(
                    task = t, onDone = a.onComplete, onSnoozeRequest = { a.onSnooze?.invoke(it) },
                    enabled = look.swipe && t.completedAt == null && a.onSnooze != null,
                ) { ReminderRow(t, now, section, a, selected = sel, showTags = look.showTags) }
            }
        }
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

/** A reminder is snoozed while its snoozed time is still ahead (F-061). */
private fun snoozed(t: Task, now: Instant) = t.active && t.snoozedAt != null && t.dueAt != null && t.dueAt.isAfter(now)

/** The alerts of a reminder: the one at its time plus its early notices (F-046). */
private fun alertCount(t: Task) = if (t.dueAt == null) 0 else 1 + Tasks.noticeMinutes(t).size

/**
 * One reminder (DESIGN-SPEC §5.1): the Done circle (or, in selection mode, the selection ring) at the
 * start; line 1 the time with its label and «مؤجّل»; line 2 the title; line 3 the metadata in the spec's
 * order. TalkBack reads one merged description and offers «تم»، «أجّل»، «تحديد» as actions.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReminderRow(t: Task, now: Instant, section: Tab?, a: RowActions, selected: Boolean? = null, showTags: Boolean = false) {
    val context = LocalContext.current
    val res = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val done = t.completedAt != null
    val overdue = !done && t.dueAt != null && !t.dueAt.isAfter(now)
    val isSnoozed = snoozed(t, now)
    val alarm = !done && AlertPolicy.style(t) == AlertPolicy.Style.ALARM
    val steps = t.steps
    val stepsText = if (steps.isEmpty()) null else stringResource(R.string.n_of_m, steps.count { it.done }.toString(), steps.size.toString())
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = when { overdue -> MaterialTheme.colorScheme.error; done -> muted; else -> MaterialTheme.colorScheme.onSurface }
    // The label after the time: how late, or which day, or nothing for today.
    val label = L10n.rowLabel(context, t, now, section == Tab.TODAY)
    val doneLabel = stringResource(R.string.mark_done_named, t.title)
    val selectLabel = stringResource(R.string.home_select_named, t.title)
    val doneAction = stringResource(R.string.done)
    val snoozeAction = stringResource(R.string.snooze_title)
    val selectAction = stringResource(R.string.select)
    val snoozedWord = stringResource(R.string.state_snoozed)
    val alarmWord = stringResource(R.string.home_style_alarm)
    val summary = L10n.join(context, listOf(
        t.title,
        if (done) label else t.dueAt?.let { L10n.dueText(context, it, now) } ?: res.getString(R.string.no_time),
        if (overdue) label else if (isSnoozed) snoozedWord else null,
        L10n.repeatText(context, t),
        if (t.priority != Priority.NORMAL) res.getString(priorityRes(t.priority)) else null,
        if (alarm) alarmWord else null,
        stepsText,
    ))
    var menu by remember { mutableStateOf(false) }
    val selecting = selected != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowMin)
            .semantics(mergeDescendants = false) {
                contentDescription = summary
                role = Role.Button
                customActions = buildList {
                    if (!done && !selecting) add(CustomAccessibilityAction(doneAction) { a.onComplete(t); true })
                    if (!done && !selecting && a.onSnooze != null) add(CustomAccessibilityAction(snoozeAction) { a.onSnooze.invoke(t); true })
                    if (a.onSelect != null) add(CustomAccessibilityAction(selectAction) { a.onSelect.invoke(t); true })
                }
            }
            .combinedClickable(
                enabled = !done || selecting,
                onClick = { a.onClick(t) },
                onLongClick = a.onSelect?.let { sel -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); sel(t) } },
            )
            .padding(start = Dimens.xs, end = Dimens.l, top = Dimens.s, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            // The selection ring in place of the Done circle.
            Box(
                Modifier.size(Dimens.touch)
                    .toggleable(value = selected == true, role = Role.Checkbox) { a.onSelect?.invoke(t) ?: a.onClick(t) }
                    .semantics { contentDescription = selectLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (selected == true) Ms.CheckCircle else Ms.RadioButtonUnchecked, null,
                    tint = if (selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(26.dp))
            }
        } else {
            // The Done target: 48 dp, a 26 dp circle drawn inside.
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
        }
        Column(Modifier.weight(1f).padding(start = Dimens.xs).clearAndSetSemantics { }) {
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    done -> Text(label!!, style = MaterialTheme.typography.bodyMedium, color = muted)
                    t.dueAt == null -> Text(stringResource(R.string.no_time), style = MaterialTheme.typography.bodyMedium, color = muted)
                    else -> {
                        TimeText(t.dueAt, RowTime, timeColor)
                        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = if (overdue) MaterialTheme.colorScheme.error else muted)
                        if (isSnoozed) StateTag(TagKind.SNOOZED, snoozedWord)
                    }
                }
            }
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (done) muted else MaterialTheme.colorScheme.onSurface)
            if (!done) Box(Modifier.padding(top = 2.dp)) { RowMeta(t, alarm, stepsText, showTags) }
        }
        if (done && !selecting) Box {
            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (t.sourceId == null) DropdownMenuItem(text = { Text(stringResource(R.string.reopen)) }, onClick = { menu = false; a.onReopen(t) })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; a.onTrash(t) })
            }
        }
    }
}

/**
 * Line 3 of a row, only what is present, in this order (§5.1): repeat; priority; «منبّه»; category (not
 * «شخصي»); tags (search and filters only); steps «2 من 5»; «اهتزاز فقط»; several alerts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowMeta(t: Task, alarm: Boolean, stepsText: String?, showTags: Boolean) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val repeat = L10n.repeatText(context, t)
    val ext = AlertExt.of(t)
    val silent = ext.tone?.kind == "silent"
    val alerts = alertCount(t)
    val tags = if (showTags) t.tags else emptyList()
    val hasCategory = t.category != Category.PERSONAL
    if (repeat == null && t.priority == Priority.NORMAL && !alarm && !hasCategory && tags.isEmpty() && stepsText == null && !silent && alerts < 2) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable fun fact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dimens.xs))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (repeat != null) fact(Ms.Repeat, repeat)
        when (t.priority) {
            Priority.HIGH -> StateTag(TagKind.PRIORITY_HIGH, stringResource(R.string.priority_high))
            Priority.MEDIUM -> StateTag(TagKind.PRIORITY_MED, stringResource(R.string.priority_medium))
            Priority.NORMAL -> Unit
        }
        if (alarm) StateTag(TagKind.ALARM, stringResource(R.string.style_tag_short), description = stringResource(R.string.home_style_alarm))
        if (hasCategory) fact(Ms.Label, stringResource(categoryRes(t.category)))
        tags.forEach { fact(Ms.Tag, it) }
        if (stepsText != null) fact(Ms.Checklist, stepsText)
        if (silent) fact(Ms.Vibration, stringResource(R.string.vibrate_only))
        if (alerts >= 2) fact(Ms.Notifications, L10n.plural(context, R.plurals.alerts_n, alerts.toLong()))
    }
}
