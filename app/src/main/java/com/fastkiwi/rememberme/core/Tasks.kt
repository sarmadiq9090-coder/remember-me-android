package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What the editor sends. */
data class TaskInput(
    val id: String? = null,
    val title: String,
    val body: String = "",
    val priority: Priority = Priority.NORMAL,
    val category: Category = Category.PERSONAL,
    val dueAt: Instant? = null,
    val repeat: Repeat = Repeat.NONE,
    val days: List<Int> = emptyList(),
    val everyDays: Int = 2,
    val everyHours: Int = 1,
    val remindBefore: Int = 0,
)

class TaskException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { TITLE, REPEAT_NEEDS_TIME, GONE, COMPLETED, SNOOZE_PAST }
}

/** Result of completing: what [Tasks.undoComplete] needs. */
data class Completion(val id: String, val copyId: String)

/**
 * Task operations, ported from the Windows app's src/core/tasks.js. All are pure: they take a state
 * and return the new one. `expectedDueAt` is the due time the user saw; when the task has moved on
 * (a double tap, or the notification and the app both acting), the call changes nothing.
 */
object Tasks {
    const val TRASH_DAYS = 30L
    var newId: () -> String = { UUID.randomUUID().toString() }

    private fun clean(input: TaskInput): TaskInput {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > 200) throw TaskException(TaskException.Reason.TITLE)
        if (input.repeat != Repeat.NONE && input.dueAt == null) throw TaskException(TaskException.Reason.REPEAT_NEEDS_TIME)
        return input.copy(title = title, body = input.body.take(20_000))
    }

    private fun ruleFor(input: TaskInput, from: Instant?, zone: ZoneId): RepeatRule = when (input.repeat) {
        Repeat.DAYS -> RepeatRule(
            days = input.days.filter { it in 0..6 }.distinct().sorted().ifEmpty { listOf(jsDay(input.dueAt!!.atZone(zone).toLocalDate())) },
            from = from,
        )
        Repeat.INTERVAL -> RepeatRule(everyDays = input.everyDays.coerceIn(2, 365), from = from)
        Repeat.HOURS -> RepeatRule(everyHours = input.everyHours.coerceIn(1, 23), from = from)
        Repeat.NONE -> RepeatRule()
        else -> RepeatRule(from = from)
    }

    private fun early(minutes: Int, dueAt: Instant?) = if (dueAt != null && minutes in EARLY_MINUTES) minutes else 0

    fun save(state: AppState, raw: TaskInput, now: Instant, zone: ZoneId): Pair<AppState, Task> {
        val input = clean(raw)
        val old = input.id?.let { id -> state.task(id) ?: throw TaskException(TaskException.Reason.GONE) }
        if (old == null) {
            val task = Task(
                id = newId(), title = input.title, body = input.body, priority = input.priority,
                category = input.category, dueAt = input.dueAt, scheduleAt = input.dueAt, repeat = input.repeat,
                rule = ruleFor(input, if (input.repeat != Repeat.NONE) input.dueAt else null, zone),
                remindBefore = early(input.remindBefore, input.dueAt), createdAt = now, updatedAt = now,
            )
            return state.copy(tasks = state.tasks + task) to task
        }
        if (old.completedAt != null) throw TaskException(TaskException.Reason.COMPLETED)
        val changed = input.dueAt != old.dueAt
        val repeatChanged = input.repeat != Repeat.NONE && input.repeat != old.repeat
        // A new time or repeat mode restarts the series at the new due time; a text edit keeps the anchor.
        val from = when {
            input.repeat == Repeat.NONE -> null
            changed || input.repeat != old.repeat -> input.dueAt
            else -> old.rule.from ?: old.scheduleAt ?: input.dueAt
        }
        val anchor = changed || repeatChanged
        var task = old.copy(
            title = input.title, body = input.body, priority = input.priority, category = input.category,
            dueAt = input.dueAt, repeat = input.repeat, rule = ruleFor(input, from, zone),
            remindBefore = early(input.remindBefore, input.dueAt), updatedAt = now,
        )
        if (anchor) task = task.copy(scheduleAt = input.dueAt, snoozedAt = null, snoozeCount = 0)
        return replace(state, task) to task
    }

    private fun replace(state: AppState, task: Task) = state.copy(tasks = state.tasks.map { if (it.id == task.id) task else it })

    private fun stale(task: Task, expectedDueAt: Instant?, checked: Boolean) = checked && expectedDueAt != task.dueAt

    /** Steps of a checklist (kept from Windows in extra) all unticked, for the next occurrence. */
    private fun untick(extra: JsonObject): JsonObject {
        val list = extra["checklist"] as? JsonArray ?: return extra
        val steps = list.map { el ->
            val o = el as? JsonObject ?: return@map el
            JsonObject(o + ("done" to JsonPrimitive(false)))
        }
        return JsonObject(extra + ("checklist" to JsonArray(steps)))
    }

    /**
     * Done. A repeating task moves to its next occurrence and leaves a completed copy (repeat none,
     * sourceId) in the history, as on Windows. Returns null when the call was ignored.
     */
    fun complete(state: AppState, id: String, now: Instant, zone: ZoneId, expectedDueAt: Instant? = null, checked: Boolean = false): Pair<AppState, Completion>? {
        val task = state.tasks.firstOrNull { it.id == id && it.deletedAt == null } ?: return null
        if (task.completedAt != null || stale(task, expectedDueAt, checked)) return null
        if (task.repeat != Repeat.NONE && task.scheduleAt != null) {
            val copy = task.copy(id = newId(), repeat = Repeat.NONE, rule = RepeatRule(), sourceId = task.id, completedAt = now)
            val next = nextOccurrence(task.scheduleAt, task.repeat, now, task.rule, zone)
            val moved = task.copy(dueAt = next, scheduleAt = next, snoozedAt = null, snoozeCount = 0, updatedAt = now, extra = untick(task.extra))
            return replace(state, moved).let { it.copy(tasks = it.tasks + copy) } to Completion(id, copy.id)
        }
        return replace(state, task.copy(completedAt = now, updatedAt = now)) to Completion(id, id)
    }

    /** Puts a just-completed task back as it was. */
    fun undoComplete(state: AppState, c: Completion, now: Instant): AppState {
        val task = state.task(c.id) ?: return state
        if (c.copyId == c.id) return if (task.completedAt != null) replace(state, task.copy(completedAt = null, updatedAt = now)) else state
        val copy = state.tasks.firstOrNull { it.id == c.copyId && it.sourceId == c.id && it.completedAt != null } ?: return state
        val back = task.copy(dueAt = copy.dueAt, scheduleAt = copy.scheduleAt, snoozedAt = copy.snoozedAt, snoozeCount = copy.snoozeCount, updatedAt = now, extra = copy.extra)
        return replace(state, back).let { s -> s.copy(tasks = s.tasks.filter { it.id != copy.id }) }
    }

    /** Moves a repeating task to its next occurrence without recording a completion. */
    fun skip(state: AppState, id: String, now: Instant, zone: ZoneId, expectedDueAt: Instant? = null, checked: Boolean = false): AppState? {
        val task = state.tasks.firstOrNull { it.id == id && it.active } ?: return null
        if (task.repeat == Repeat.NONE || task.scheduleAt == null || stale(task, expectedDueAt, checked)) return null
        val next = nextOccurrence(task.scheduleAt, task.repeat, now, task.rule, zone)
        return replace(state, task.copy(dueAt = next, scheduleAt = next, snoozedAt = null, snoozeCount = 0, updatedAt = now))
    }

    fun snooze(state: AppState, id: String, until: Instant, now: Instant, expectedDueAt: Instant? = null, checked: Boolean = false): AppState? {
        val task = state.tasks.firstOrNull { it.id == id && it.active } ?: return null
        if (stale(task, expectedDueAt, checked)) return null
        if (!until.isAfter(now)) throw TaskException(TaskException.Reason.SNOOZE_PAST)
        return replace(state, task.copy(dueAt = until, snoozedAt = now, snoozeCount = minOf(999, task.snoozeCount + 1), updatedAt = now))
    }

    fun reopen(state: AppState, id: String, now: Instant): AppState {
        val task = state.task(id) ?: return state
        return if (task.completedAt == null) state else replace(state, task.copy(completedAt = null, updatedAt = now))
    }

    fun trash(state: AppState, id: String, now: Instant): AppState {
        val task = state.task(id) ?: return state
        return if (task.deletedAt != null) state else replace(state, task.copy(deletedAt = now))
    }

    fun restore(state: AppState, id: String): AppState {
        val task = state.task(id) ?: return state
        return replace(state, task.copy(deletedAt = null))
    }

    fun purge(state: AppState, id: String) = state.copy(tasks = state.tasks.filter { !(it.id == id && it.deletedAt != null) })

    /** Removes tasks that have been in the trash for 30 days (or all trashed ones). Notes are Windows' own. */
    fun purgeTrash(state: AppState, now: Instant, all: Boolean = false): AppState {
        val limit = now.minusMillis(TRASH_DAYS * DAY_MS)
        return state.copy(tasks = state.tasks.filter { t -> t.deletedAt == null || (!all && t.deletedAt.isAfter(limit)) })
    }

    /** Active tasks whose time has come, oldest first: the alert queue. */
    fun due(state: AppState, now: Instant): List<Task> =
        state.tasks.filter { it.active && it.dueAt != null && !it.dueAt.isAfter(now) }.sortedBy { it.dueAt }

    /** The moment of a task's early notice, or null. */
    fun noticeAt(t: Task): Instant? = if (t.remindBefore > 0 && t.dueAt != null) t.dueAt.minusSeconds(t.remindBefore * 60L) else null

    /**
     * Tasks whose early notice is due now. A notice moment at or before the task's last change is
     * skipped, so a snooze or an edit that moves the time into the lead window does not announce at once.
     */
    fun upcoming(state: AppState, now: Instant): List<Task> = state.tasks.filter { t ->
        val at = noticeAt(t)
        t.active && at != null && t.dueAt!!.isAfter(now) && !at.isAfter(now) && at.isAfter(t.updatedAt)
    }
}
