package com.fastkiwi.rememberme.core

import java.time.Instant
import java.time.ZoneId

/**
 * The one search matcher (FEATURES.md F-065) and the states of F-061, computed from the data with no new
 * field. Text is folded the Windows way ([Organize.fold]): hamza forms, alef maqsura, taa marbuta,
 * diacritics and tatweel never stop a match, and the stored text is never changed.
 */
object Search {
    /** F-061. Cancelled = in the trash (30 days) or a skipped repeat; there is no other state. */
    enum class State { UPCOMING, DUE, OVERDUE, SNOOZED, DONE, TRASHED }

    /** The first minute after the due time is "due now"; after that the reminder is overdue (never done by itself). */
    const val DUE_NOW_MS = 60_000L

    fun stateOf(t: Task, now: Instant): State = when {
        t.deletedAt != null -> State.TRASHED
        t.completedAt != null -> State.DONE
        t.dueAt == null -> State.UPCOMING
        !t.dueAt.isAfter(now) -> if (now.toEpochMilli() - t.dueAt.toEpochMilli() < DUE_NOW_MS) State.DUE else State.OVERDUE
        t.snoozedAt != null -> State.SNOOZED
        else -> State.UPCOMING
    }

    /**
     * What to look for. [text] is split on spaces; every word must appear in the title, the note, a tag, the
     * category's name or the state's name. The other fields narrow the result; null means "any".
     */
    data class Query(
        val text: String = "",
        val states: Set<State>? = null,
        val category: Category? = null,
        val priority: Priority? = null,
        /** A tag, compared folded (a list is a saved tag filter). */
        val tag: String? = null,
        /** Only tasks without any tag. */
        val untagged: Boolean = false,
        /** Due time in [from, to); a task without a time never matches a date range. */
        val from: Instant? = null,
        val to: Instant? = null,
        val includeDone: Boolean = true,
        val includeTrash: Boolean = false,
    )

    /** Names the interface shows for categories and states, so «عمل» or «مؤجّل» find their tasks. */
    data class Words(val categories: Map<Category, String> = emptyMap(), val states: Map<State, String> = emptyMap()) {
        internal val foldedCategories = categories.mapValues { Organize.fold(it.value) }
        internal val foldedStates = states.mapValues { Organize.fold(it.value) }
    }

    fun terms(text: String): List<String> = Organize.fold(text).split(' ', '\t', '\n', ' ').filter { it.isNotEmpty() }

    /** Whether [t] matches [q]; [terms] are the folded words of the query text (see [terms]). */
    fun matches(t: Task, q: Query, terms: List<String>, now: Instant, words: Words = Words()): Boolean {
        val state = stateOf(t, now)
        if (state == State.TRASHED && !q.includeTrash) return false
        if (state == State.DONE && !q.includeDone) return false
        if (q.states != null && state !in q.states) return false
        if (q.category != null && t.category != q.category) return false
        if (q.priority != null && t.priority != q.priority) return false
        if (q.from != null || q.to != null) {
            val d = t.dueAt ?: return false
            if (q.from != null && d.isBefore(q.from)) return false
            if (q.to != null && !d.isBefore(q.to)) return false
        }
        val needTags = q.tag != null || q.untagged || terms.isNotEmpty()
        val tags = if (needTags) t.tags else emptyList()
        if (q.untagged && tags.isNotEmpty()) return false
        if (q.tag != null) {
            val want = Organize.fold(q.tag.trim().removePrefix("#"))
            if (tags.none { Organize.fold(it) == want }) return false
        }
        if (terms.isEmpty()) return true
        val title = Organize.fold(t.title)
        var body: String? = null
        var folded: List<String>? = null
        for (term in terms) {
            if (title.contains(term)) continue
            val b = body ?: Organize.fold(t.body).also { body = it }
            if (b.contains(term)) continue
            val ft = folded ?: tags.map(Organize::fold).also { folded = it }
            if (ft.any { it.contains(term) }) continue
            if (words.foldedCategories[t.category]?.contains(term) == true) continue
            if (words.foldedStates[state]?.contains(term) == true) continue
            return false
        }
        return true
    }

    /** The matching tasks: open ones first, then by due time (no time last), then newest first. */
    fun run(tasks: List<Task>, q: Query, now: Instant, words: Words = Words()): List<Task> {
        val terms = terms(q.text)
        return tasks.filter { matches(it, q, terms, now, words) }
            .sortedWith(compareBy<Task> { it.completedAt != null || it.deletedAt != null }.thenBy { it.dueAt ?: Instant.MAX }.thenByDescending { it.createdAt })
    }

    /** The start of the week that holds [now] ([weekStart]: 0 = Sunday … 6 = Saturday, as settings.weekStart). */
    fun weekRange(now: Instant, zone: ZoneId, weekStart: Int): Pair<Instant, Instant> {
        val today = now.atZone(zone).toLocalDate()
        val back = ((jsDay(today) - weekStart) % 7 + 7) % 7
        val start = today.minusDays(back.toLong())
        return start.atStartOfDay(zone).toInstant() to start.plusDays(7).atStartOfDay(zone).toInstant()
    }
}
