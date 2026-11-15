package com.fastkiwi.rememberme.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.ui.ConfirmDialog
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SectionHeader
import com.fastkiwi.rememberme.ui.TooltipIconButton
import com.fastkiwi.rememberme.ui.categoryName
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What a saved filter of a built-in kind is called. */
@Composable
fun filterName(f: SavedFilter): String = f.name.ifEmpty {
    stringResource(when (f.kind) {
        "priority" -> R.string.set_filter_priority
        "overdue" -> R.string.set_filter_overdue
        "week" -> R.string.set_filter_week
        "untagged" -> R.string.set_filter_untagged
        else -> R.string.set_filter_list
    })
}

/**
 * «القوائم والوسوم» (DESIGN-SPEC §5.15, F-062, F-063). [lists] is the saved-filter list of data/Filters.kt (lists are
 * the `tag` kind); [onLists] saves it in its new order. Tag edits return the new state through [onChange] (one
 * Repo.change). A tap on a category, list, tag or filter asks Home to show it ([onCategory], [onFilter]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ListsScreen(
    state: AppState,
    lists: List<SavedFilter>,
    now: Instant,
    zone: ZoneId,
    weekStart: Int,
    onLists: (List<SavedFilter>) -> Unit,
    onChange: ((AppState) -> AppState) -> Unit,
    onCategory: (Category) -> Unit = {},
    onFilter: (SavedFilter) -> Unit = {},
    onDone: () -> Unit = {},
    onTrash: () -> Unit = {},
    onBack: () -> Unit,
) {
    val res = androidx.compose.ui.platform.LocalResources.current
    var reorder by rememberSaveable { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var tagMenu by remember { mutableStateOf<String?>(null) }
    var renameList by remember { mutableStateOf<SavedFilter?>(null) }
    var renameTag by remember { mutableStateOf<String?>(null) }
    var deleteTag by remember { mutableStateOf<String?>(null) }
    var newList by rememberSaveable { mutableStateOf(false) }
    var newFilter by rememberSaveable { mutableStateOf(false) }
    val tagLists = lists.filter { it.kind == "tag" }
    val savedFilters = lists.filter { it.kind != "tag" }
    val filtersShown = savedFilters + ListsLogic.BUILT_IN.filter { b -> savedFilters.none { it.kind == b.kind } }
    val tags = ListsLogic.tagCounts(state)
    val cats = ListsLogic.categoryCounts(state)

    /** Replaces the tag lists (keeping the saved filters after them) in the given order. */
    fun saveTagLists(next: List<SavedFilter>) = onLists(next + savedFilters)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_lists_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), onBack) },
                actions = {
                    Box {
                        TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { topMenu = true })
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(if (reorder) R.string.set_reorder_done else R.string.set_reorder)) },
                                onClick = { topMenu = false; reorder = !reorder })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newList = true }, icon = { Icon(Ms.Add, null) }, text = { Text(stringResource(R.string.set_new_list)) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.fabClearance),
            verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            // Categories: the shared three, fixed.
            SectionHeader(stringResource(R.string.set_categories))
            Text(stringResource(R.string.set_categories_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xxl))
            Category.entries.forEachIndexed { i, c ->
                GroupItem(i, Category.entries.size) { CountRow(Ms.Label, categoryName(c), null, cats[c] ?: 0, { onCategory(c) }) }
            }
            // My lists.
            SectionHeader(stringResource(R.string.set_my_lists), trailing = {
                TextButton(onClick = { reorder = !reorder }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                    Icon(Ms.Sort, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Dimens.xs))
                    Text(stringResource(if (reorder) R.string.set_reorder_done else R.string.set_reorder))
                }
            })
            if (tagLists.isEmpty()) Text(stringResource(R.string.set_no_lists), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.s))
            tagLists.forEachIndexed { i, f ->
                GroupItem(i, tagLists.size) {
                    val count = ListsLogic.count(f, state, now, zone, weekStart)
                    val up = stringResource(R.string.set_move_up)
                    val down = stringResource(R.string.set_move_down)
                    Row(
                        Modifier.fillMaxWidth().semantics {
                            if (reorder) customActions = listOfNotNull(
                                if (i > 0) CustomAccessibilityAction(up) { saveTagLists(ListsLogic.move(tagLists, i, -1)); true } else null,
                                if (i < tagLists.size - 1) CustomAccessibilityAction(down) { saveTagLists(ListsLogic.move(tagLists, i, 1)); true } else null,
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            CountRow(if (reorder) Ms.DragIndicator else Ms.ListIcon, filterName(f), stringResource(R.string.set_list_tag, L10n.isolate(LocalContext.current, f.value)), count,
                                if (reorder) null else ({ onFilter(f) }))
                        }
                        if (reorder) {
                            TooltipIconButton(Ms.ExpandLess, up, { saveTagLists(ListsLogic.move(tagLists, i, -1)) }, enabled = i > 0)
                            TooltipIconButton(Ms.ExpandMore, down, { saveTagLists(ListsLogic.move(tagLists, i, 1)) }, enabled = i < tagLists.size - 1)
                        } else Box {
                            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { rowMenu = f.id })
                            DropdownMenu(expanded = rowMenu == f.id, onDismissRequest = { rowMenu = null }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.set_rename)) }, onClick = { rowMenu = null; renameList = f })
                                DropdownMenuItem(text = { Text(stringResource(R.string.set_remove_list)) }, onClick = { rowMenu = null; saveTagLists(tagLists - f) })
                            }
                        }
                    }
                }
            }
            // Tags, with counts; long-press (or the TalkBack actions) renames or deletes across reminders.
            SectionHeader(stringResource(R.string.set_tags))
            if (tags.isEmpty()) Text(stringResource(R.string.set_no_tags), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.s))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                val renameLabel = stringResource(R.string.set_rename)
                val deleteLabel = stringResource(R.string.set_delete_tag)
                tags.forEach { (tag, n) ->
                    Box {
                        val open = { onFilter(SavedFilter("tag:$tag", tag, "tag", tag)) }
                        Surface(
                            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.padding(vertical = Dimens.xs),
                        ) {
                            Row(
                                Modifier.heightIn(min = Dimens.touch)
                                    .combinedClickable(role = Role.Button, onLongClick = { tagMenu = tag }, onClick = open)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = res.getQuantityString(R.plurals.set_tag_cd, n, tag, n.toString())
                                        customActions = listOf(
                                            CustomAccessibilityAction(renameLabel) { renameTag = tag; true },
                                            CustomAccessibilityAction(deleteLabel) { deleteTag = tag; true },
                                        )
                                    }
                                    .padding(horizontal = Dimens.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Ms.Tag, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(Dimens.s))
                                Text(tag, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.size(Dimens.s))
                                Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = tagMenu == tag, onDismissRequest = { tagMenu = null }) {
                            DropdownMenuItem(text = { Text(renameLabel) }, onClick = { tagMenu = null; renameTag = tag })
                            DropdownMenuItem(text = { Text(deleteLabel) }, onClick = { tagMenu = null; deleteTag = tag })
                        }
                    }
                }
            }
            // Saved filters.
            SectionHeader(stringResource(R.string.set_saved_filters))
            (filtersShown.map { it to true } + listOf(null to false)).forEachIndexed { i, (f, _) ->
                GroupItem(i, filtersShown.size + 1) {
                    if (f != null) {
                        val saved = f in savedFilters
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { CountRow(Ms.FilterAlt, filterName(f), null, ListsLogic.count(f, state, now, zone, weekStart), { onFilter(f) }) }
                            if (saved) Box {
                                TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { rowMenu = f.id })
                                DropdownMenu(expanded = rowMenu == f.id, onDismissRequest = { rowMenu = null }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.set_rename)) }, onClick = { rowMenu = null; renameList = f })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.set_remove_filter)) }, onClick = { rowMenu = null; onLists(lists - f) })
                                }
                            }
                        }
                    } else CountRow(Ms.Add, stringResource(R.string.set_new_filter), null, null, { newFilter = true }, primary = true)
                }
            }
            // Done and trash.
            SectionHeader(stringResource(R.string.set_done_and_trash))
            GroupItem(0, 2) { CountRow(Ms.CheckCircleOutline, stringResource(R.string.tab_done), null, state.tasks.count { it.completedAt != null && it.deletedAt == null }, onDone) }
            GroupItem(1, 2) { CountRow(Ms.Delete, stringResource(R.string.trash), null, state.tasks.count { it.deletedAt != null }, onTrash) }
        }
    }

    renameList?.let { f ->
        NameDialog(stringResource(R.string.set_rename), filterName(f), { name -> onLists(lists.map { if (it.id == f.id) it.copy(name = name) else it }) }, { renameList = null })
    }
    renameTag?.let { tag ->
        NameDialog(stringResource(R.string.set_rename_tag), tag, { name ->
            val to = Organize.normalizeTags(listOf(name)).firstOrNull() ?: return@NameDialog
            onChange { s -> ListsLogic.renameTag(s, tag, to, Instant.now()) }
            onLists(ListsLogic.renameInLists(lists, tag, to))
        }, { renameTag = null }, max = Organize.TAG_CHARS)
    }
    deleteTag?.let { tag ->
        val n = ListsLogic.tagged(state, tag)
        ConfirmDialog(pluralStringResource(R.plurals.set_delete_tag_q, n, n.toString()), stringResource(R.string.set_delete_tag_body), stringResource(R.string.set_delete_tag),
            { onChange { s -> ListsLogic.deleteTag(s, tag, Instant.now()) } }, { deleteTag = null }, destructive = true)
    }
    if (newList) NewListDialog(tags.map { it.first }, { name, tag ->
        onLists(lists + SavedFilter(UUID.randomUUID().toString(), name, "tag", tag))
    }, { newList = false })
    if (newFilter) {
        val kinds = ListsLogic.BUILT_IN
        com.fastkiwi.rememberme.ui.ChoiceDialog(stringResource(R.string.set_new_filter), kinds.map { it.kind to filterName(it) }, "", { kind ->
            onLists(lists + SavedFilter(UUID.randomUUID().toString(), "", kind))
        }, { newFilter = false })
    }
}

/** A row with a title, an optional supporting line and a count at the end. */
@Composable
private fun CountRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, count: Int?, onClick: (() -> Unit)?, primary: Boolean = false) {
    var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    if (onClick != null) m = m.combinedClickable(role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        val tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, tint = tint, modifier = Modifier.padding(end = Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit, max: Int = 40) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(max) }, singleLine = true, label = { Text(stringResource(R.string.set_name)) }, modifier = Modifier.fillMaxWidth()) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()); onDismiss() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

/** «قائمة جديدة»: a name and the tag it shows (an existing tag, or a new one typed). «إلغاء» · «إنشاء». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewListDialog(tags: List<String>, onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    val cleanTag = Organize.normalizeTags(listOf(tag)).firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_new_list)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.set_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tag, { tag = it.take(Organize.TAG_CHARS) }, singleLine = true, label = { Text(stringResource(R.string.set_list_tag_field)) },
                    leadingIcon = { Icon(Ms.Tag, null) }, modifier = Modifier.fillMaxWidth())
                if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    tags.forEach { t ->
                        FilterChip(selected = cleanTag != null && Organize.fold(t) == Organize.fold(cleanTag), onClick = { tag = t; if (name.isBlank()) name = t },
                            label = { Text(t) }, modifier = Modifier.heightIn(min = Dimens.touch))
                    }
                }
                Text(stringResource(R.string.set_new_list_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = cleanTag != null, onClick = { onCreate(name.trim().ifEmpty { cleanTag!! }, cleanTag!!); onDismiss() },
                modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_create)) }
        },
    )
}

