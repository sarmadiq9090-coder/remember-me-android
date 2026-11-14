package com.fastkiwi.rememberme.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.core.Search
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import java.time.Instant
import java.time.ZoneId

/** One chip of Home's quick filters: «الكل», «مهم», a list (a saved tag filter) or a saved filter. */
data class QuickFilter(val id: String, val label: String, val kind: String)

object QuickFilters {
    const val ALL = "all"
    const val IMPORTANT = "important"

    /** F-063: chips only for a user with 2+ lists or a saved filter; a one-list user sees Home unchanged. */
    fun shown(saved: List<SavedFilter>): Boolean = saved.count { it.kind == "tag" } >= 2 || saved.any { it.kind != "tag" }

    /** «الكل», «مهم», each list, then each saved filter, in the user's order. */
    fun chips(saved: List<SavedFilter>, all: String, important: String): List<QuickFilter> =
        listOf(QuickFilter(ALL, all, ALL), QuickFilter(IMPORTANT, important, "priority")) +
            saved.filter { it.kind == "tag" }.map { QuickFilter(it.id, it.name.ifBlank { it.value }, it.kind) } +
            saved.filter { it.kind != "tag" }.map { QuickFilter(it.id, it.name, it.kind) }

    /** The search query a chip stands for, or null for «الكل» (and for a filter that no longer exists). */
    fun query(id: String, saved: List<SavedFilter>, now: Instant, zone: ZoneId, weekStart: Int): Search.Query? {
        if (id == IMPORTANT) return Search.Query(priority = Priority.HIGH)
        val f = saved.firstOrNull { it.id == id } ?: return null
        return when (f.kind) {
            "tag" -> Search.Query(tag = f.value)
            "priority" -> Search.Query(priority = Priority.HIGH)
            "overdue" -> Search.Query(states = setOf(Search.State.DUE, Search.State.OVERDUE))
            "week" -> Search.weekRange(now, zone, weekStart).let { (from, to) -> Search.Query(from = from, to = to) }
            "untagged" -> Search.Query(untagged = true)
            else -> null
        }
    }
}

/** The chips row under the top bar; it scrolls sideways and never wraps (§3.6). */
@Composable
fun QuickFilterRow(filters: List<QuickFilter>, selected: String, onSelect: (String) -> Unit, onOpenLists: () -> Unit) {
    if (filters.size < 2) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        items(filters, key = { it.id }) { f ->
            val on = f.id == selected
            FilterChip(
                selected = on,
                onClick = { onSelect(if (on) QuickFilters.ALL else f.id) },
                label = { Text(f.label, maxLines = 1) },
                leadingIcon = when {
                    on -> { { Icon(Ms.Check, null, Modifier.size(18.dp)) } }
                    f.kind == "priority" -> { { Icon(Ms.Flag, null, Modifier.size(18.dp)) } }
                    else -> null
                },
            )
        }
        item(key = "lists") {
            AssistChip(
                onClick = onOpenLists,
                label = { Text(stringResource(R.string.chip_lists), maxLines = 1) },
                leadingIcon = { Icon(Ms.ListIcon, null, Modifier.size(18.dp)) },
            )
        }
    }
}
