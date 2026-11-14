package com.fastkiwi.rememberme.ui.settings

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.tags
import com.fastkiwi.rememberme.core.withTags
import java.time.Instant
import java.time.ZoneId

/**
 * Lists, tags and saved filters (F-062, F-063): counts, and tag edits across every reminder. A tag edit goes through
 * [Task.withTags] (Windows' normalisation) in one state change; a list is a saved filter on a tag and removing it
 * never touches the tag.
 */
object ListsLogic {
    private fun same(a: String, b: String) = Organize.fold(a) == Organize.fold(b)

    /** Reminders not in the trash, per category. */
    fun categoryCounts(s: AppState): Map<Category, Int> =
        Category.entries.associateWith { c -> s.tasks.count { it.active && it.category == c } }

    /** Every tag in use (first spelling seen) with how many reminders outside the trash carry it, most used first. */
    fun tagCounts(s: AppState): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in s.tasks) if (t.deletedAt == null) for (tag in t.tags) {
            val k = Organize.fold(tag)
            val old = counts[k]
            counts[k] = (old?.first ?: tag) to ((old?.second ?: 0) + 1)
        }
        return counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    /** Reminders a tag edit would change (all of them, also done and in the trash, so nothing keeps the old name). */
    fun tagged(s: AppState, tag: String): Int = s.tasks.count { t -> t.tags.any { same(it, tag) } }

    /** Renames [from] to [to] on every reminder (merging with an existing tag of that name). */
    fun renameTag(s: AppState, from: String, to: String, now: Instant): AppState {
        val name = Organize.normalizeTags(listOf(to)).firstOrNull() ?: return s
        if (from == name) return s
        return s.copy(tasks = s.tasks.map { t ->
            if (t.tags.none { same(it, from) }) t else t.withTags(t.tags.map { if (same(it, from)) name else it }).copy(updatedAt = now)
        })
    }

    /** Removes [tag] from every reminder. */
    fun deleteTag(s: AppState, tag: String, now: Instant): AppState =
        s.copy(tasks = s.tasks.map { t -> if (t.tags.none { same(it, tag) }) t else t.withTags(t.tags.filterNot { same(it, tag) }).copy(updatedAt = now) })

    /** Lists after a tag rename: lists on [from] now point at [to]. */
    fun renameInLists(lists: List<SavedFilter>, from: String, to: String): List<SavedFilter> =
        lists.map { if (it.kind == "tag" && same(it.value, from)) it.copy(value = to) else it }

    /** Moves the list at [index] by [delta] places (reorder mode, «انقل لأعلى» / «انقل لأسفل»). */
    fun move(lists: List<SavedFilter>, index: Int, delta: Int): List<SavedFilter> {
        val to = index + delta
        if (index !in lists.indices || to !in lists.indices) return lists
        return lists.toMutableList().apply { add(to, removeAt(index)) }
    }

    /** Whether [t] matches a saved filter (the built-in kinds and a list's tag). */
    fun matches(f: SavedFilter, t: Task, now: Instant, zone: ZoneId, weekStart: Int): Boolean {
        if (!t.active) return false
        return when (f.kind) {
            "tag" -> t.tags.any { same(it, f.value) }
            "priority" -> t.priority == Priority.HIGH
            "overdue" -> t.dueAt != null && t.dueAt.isBefore(now)
            "untagged" -> t.tags.isEmpty()
            "week" -> t.dueAt?.let { due ->
                val today = now.atZone(zone).toLocalDate()
                val back = (jsDay(today) - weekStart + 7) % 7
                val start = today.minusDays(back.toLong())
                val day = due.atZone(zone).toLocalDate()
                !day.isBefore(start) && day.isBefore(start.plusDays(7))
            } == true
            else -> false
        }
    }

    fun count(f: SavedFilter, s: AppState, now: Instant, zone: ZoneId, weekStart: Int) = s.tasks.count { matches(f, it, now, zone, weekStart) }

    /** The four built-in saved filters (DESIGN-SPEC §5.15), ids stable so they can be pinned. */
    val BUILT_IN = listOf(
        SavedFilter("builtin-priority", "", "priority"),
        SavedFilter("builtin-overdue", "", "overdue"),
        SavedFilter("builtin-week", "", "week"),
        SavedFilter("builtin-untagged", "", "untagged"),
    )
}
