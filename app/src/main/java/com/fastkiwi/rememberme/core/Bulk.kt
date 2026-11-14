package com.fastkiwi.rememberme.core

import java.time.Instant
import java.time.ZoneId

/**
 * Actions on several reminders at once (FEATURES.md F-066, DESIGN-SPEC §5.17). Pure, like [Tasks]: each
 * takes a state and returns the new one plus what its undo needs. Every action here is undoable; the
 * screen asks first from [CONFIRM_FROM] items.
 */
object Bulk {
    /** From this many items the screen confirms before acting (DESIGN-SPEC Behaviour.BulkConfirmFrom). */
    const val CONFIRM_FROM = 10

    fun needsConfirm(count: Int, from: Int = CONFIRM_FROM) = count >= from

    /** The result of an action: the new state, how many reminders changed, and how to put them back. */
    data class Result(val state: AppState, val count: Int, val undo: Undo)

    sealed class Undo {
        /** Done: [Tasks.undoComplete] for each (a repeat returns to the completed occurrence). */
        data class Completed(val completions: List<Completion>) : Undo()
        /** Trash: back out of the trash. */
        data class Trashed(val ids: List<String>) : Undo()
        /** Anything else: the reminders as they were before. */
        data class Before(val tasks: List<Task>) : Undo()
    }

    private fun live(state: AppState, ids: Collection<String>): List<Task> {
        val set = ids.toHashSet()
        return state.tasks.filter { it.id in set && it.deletedAt == null }
    }

    private fun replace(state: AppState, changed: Map<String, Task>): AppState =
        if (changed.isEmpty()) state else state.copy(tasks = state.tasks.map { changed[it.id] ?: it })

    /** Marks every open reminder of [ids] done. */
    fun complete(state: AppState, ids: Collection<String>, now: Instant, zone: ZoneId): Result {
        var s = state
        val done = ArrayList<Completion>()
        for (t in live(state, ids)) {
            if (t.completedAt != null) continue
            val r = Tasks.complete(s, t.id, now, zone) ?: continue
            s = r.first
            done += r.second
        }
        return Result(s, done.size, Undo.Completed(done))
    }

    /** Moves every reminder of [ids] that is not there yet to the trash. */
    fun trash(state: AppState, ids: Collection<String>, now: Instant): Result {
        val moved = live(state, ids).map { it.id }
        var s = state
        for (id in moved) s = Tasks.trash(s, id, now)
        return Result(s, moved.size, Undo.Trashed(moved))
    }

    /** Sets the priority of every reminder of [ids] whose priority differs. */
    fun setPriority(state: AppState, ids: Collection<String>, p: Priority, now: Instant): Result =
        edit(state, ids, now) { if (it.priority == p) null else it.copy(priority = p) }

    /** Moves reminders to one of the three shared categories. */
    fun setCategory(state: AppState, ids: Collection<String>, c: Category, now: Instant): Result =
        edit(state, ids, now) { if (it.category == c) null else it.copy(category = c) }

    /** Puts reminders on a list: adds the list's tag (Windows normalisation; a reminder keeps its other tags). */
    fun addTag(state: AppState, ids: Collection<String>, tag: String, now: Instant): Result {
        val want = Organize.fold(tag.trim().removePrefix("#"))
        return edit(state, ids, now) { t ->
            if (t.tags.any { Organize.fold(it) == want }) null else t.withTags(t.tags + tag).takeIf { it.tags.size > t.tags.size }
        }
    }

    /** Snoozes every open reminder of [ids] to [until]; the repeat rule never changes (F-043). */
    fun snooze(state: AppState, ids: Collection<String>, until: Instant, now: Instant): Result {
        if (!until.isAfter(now)) throw TaskException(TaskException.Reason.SNOOZE_PAST)
        val before = live(state, ids).filter { it.completedAt == null }
        var s = state
        for (t in before) s = Tasks.snooze(s, t.id, until, now) ?: s
        return Result(s, before.size, Undo.Before(before))
    }

    private fun edit(state: AppState, ids: Collection<String>, now: Instant, change: (Task) -> Task?): Result {
        val before = ArrayList<Task>()
        val changed = HashMap<String, Task>()
        for (t in live(state, ids)) {
            val next = change(t) ?: continue
            before += t
            changed[t.id] = next.copy(updatedAt = now)
        }
        return Result(replace(state, changed), changed.size, Undo.Before(before))
    }

    /** Puts things back as they were before the action. */
    fun undo(state: AppState, undo: Undo, now: Instant): AppState = when (undo) {
        is Undo.Completed -> undo.completions.fold(state) { s, c -> Tasks.undoComplete(s, c, now) }
        is Undo.Trashed -> undo.ids.fold(state) { s, id -> Tasks.restore(s, id) }
        is Undo.Before -> {
            val ids = state.tasks.mapTo(HashSet()) { it.id }
            replace(state, undo.tasks.filter { it.id in ids }.associate { it.id to it.copy(updatedAt = now) })
        }
    }

    /** How many of [ids] repeat (their series stop when trashed; the confirmation says so). */
    fun repeating(state: AppState, ids: Collection<String>): Int = live(state, ids).count { it.repeat != Repeat.NONE && it.completedAt == null }
}
