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
    /** The task's updatedAt when the editor opened it (edits of an existing task). */
    val updatedAt: Instant? = null,
    /** Whether the editor's reminder fields (on/off, date, time, repeat) were changed; null from other callers. */
    val timeEdited: Boolean? = null,
    /**
     * All early alerts in minutes (EARLY_MINUTES, up to 3, F-046); null = only [remindBefore] (other callers).
     * Stored as FEATURES.md "Data format": `remindBefore` = the one nearest to the due time, and with two or
     * more, all of them in `android.alerts`.
     */
    val alerts: List<Int>? = null,
    /** The Android-only choices (tone, style, prayer, Hijri…); null = keep the task's. Its `alerts` are ignored. */
    val ext: AlertExt? = null,
    /** Tags (Windows `tags`); null = keep the task's. */
    val tags: List<String>? = null,
    /** Steps (Windows `checklist`); null = keep the task's. */
    val steps: List<Organize.Step>? = null,
    /** The phone setting «أنجز التذكير عند إكمال كل الخطوات» (F-071): ticking the last step completes the task. */
    val autoComplete: Boolean = false,
)

/**
 * What the Android-only repeats need from the phone (F-050 prayer times, F-051 Hijri adjustment). Kept as
 * a hook so the core stays pure; the app installs it (data/EditorPrefs.kt). Without it, a prayer task moves
 * like a daily one (as on Windows).
 */
data class ExtRepeat(
    val hijriAdjust: Int = 0,
    /** The first time of prayer [name] + [offset] minutes strictly after [after], or null when not set up. */
    val prayerNext: ((name: String, offset: Int, after: Instant) -> Instant?)? = null,
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

    /** The phone's prayer and Hijri setup (installed by the app; see [ExtRepeat]). */
    @Volatile var extRepeat: () -> ExtRepeat = { ExtRepeat() }

    /**
     * The next occurrence of a repeating task after [now] (and after its current one), with the rule to keep.
     * A prayer-relative task (F-050) follows the prayer, a Hijri-yearly one (F-051) the Hijri date (day 30 →
     * 29 in a short month); everything else the Windows rule. A prayer/Hijri task's anchor (`repeatFrom`)
     * moves to the computed occurrence, so Windows, which sees a daily/yearly repeat, continues from there.
     */
    fun next(task: Task, now: Instant, zone: ZoneId): Pair<Instant, RepeatRule> {
        val cur = task.scheduleAt ?: task.dueAt!!
        val ext = AlertExt.of(task)
        val after = if (now.isAfter(cur)) now else cur
        if (task.repeat == Repeat.DAILY && ext.prayer != null) {
            val (name, offset) = ext.prayer
            extRepeat().prayerNext?.invoke(name, offset, after)?.let { return it to task.rule.copy(from = it) }
        }
        if (task.repeat == Repeat.YEARLY && ext.hijri != null) {
            val (month, day) = ext.hijri
            val at = Hijri.nextYearly(month, day, cur.atZone(zone).toLocalTime(), after, zone, extRepeat().hijriAdjust)
            return at to task.rule.copy(from = at)
        }
        return nextOccurrence(cur, task.repeat, now, task.rule, zone) to task.rule
    }

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

    const val MAX_ALERTS = 3

    /** The early alerts to store: valid, distinct, largest first, at most 3; none without a due time. */
    fun cleanAlerts(minutes: List<Int>, dueAt: Instant?): List<Int> =
        if (dueAt == null) emptyList() else minutes.filter { it in EARLY_MINUTES }.distinct().sortedDescending().take(MAX_ALERTS)

    /**
     * Puts the input's tags, steps, alerts and Android choices on [task]. A prayer time needs a daily repeat
     * and a Hijri date a yearly one; otherwise they are dropped, so the task stays a valid Windows task.
     */
    private fun extras(task: Task, input: TaskInput, old: Task?): Task {
        var t = task
        input.tags?.let { t = t.withTags(it) }
        input.steps?.let { t = t.withSteps(it) }
        val oldExt = AlertExt.of(old ?: task)
        var ext = (input.ext ?: oldExt).copy(alerts = oldExt.alerts)
        if (input.alerts != null) {
            val list = cleanAlerts(input.alerts, t.dueAt)
            t = t.copy(remindBefore = list.minOrNull() ?: 0)
            ext = ext.copy(alerts = if (list.size >= 2) list else emptyList())
        } else if (ext.alerts.isNotEmpty() && t.remindBefore !in ext.alerts) {
            ext = ext.copy(alerts = emptyList())
        }
        if (t.dueAt == null || t.repeat != Repeat.DAILY) ext = ext.copy(prayer = null)
        if (t.dueAt == null || t.repeat != Repeat.YEARLY) ext = ext.copy(hijri = null)
        return t.withExt(ext)
    }

    fun save(state: AppState, raw: TaskInput, now: Instant, zone: ZoneId): Pair<AppState, Task> {
        val (s, t) = saveOnly(state, raw, now, zone)
        if (!raw.autoComplete || raw.id == null) return s to t
        val before = state.task(raw.id) ?: return s to t
        // F-071: this edit ticked the last open step.
        if (!stepsJustDone(before, t)) return s to t
        val done = complete(s, t.id, now, zone) ?: return s to t
        return done.first to (done.first.task(t.id) ?: t)
    }

    /** True when [after] has steps, all ticked, and [before] still had an open one. */
    fun stepsJustDone(before: Task, after: Task): Boolean {
        val steps = after.steps
        return steps.isNotEmpty() && steps.all { it.done } && before.steps.any { !it.done }
    }

    /**
     * Ticks or unticks one step (reminder screen, tablet detail pane). With [autoComplete] (F-071) ticking
     * the last open step completes the task too. Returns null when nothing changed.
     */
    fun toggleStep(state: AppState, id: String, stepId: String, done: Boolean, autoComplete: Boolean, now: Instant, zone: ZoneId): Pair<AppState, Completion?>? {
        val task = state.tasks.firstOrNull { it.id == id && it.active } ?: return null
        val steps = task.steps
        if (steps.none { it.id == stepId && it.done != done }) return null
        val next = task.withSteps(steps.map { if (it.id == stepId) it.copy(done = done) else it }).copy(updatedAt = now)
        val s = replace(state, next)
        if (autoComplete && stepsJustDone(task, next)) complete(s, id, now, zone)?.let { return it.first to it.second }
        return s to null
    }

    private fun saveOnly(state: AppState, raw: TaskInput, now: Instant, zone: ZoneId): Pair<AppState, Task> {
        val input = clean(raw)
        val old = input.id?.let { id -> state.task(id) ?: throw TaskException(TaskException.Reason.GONE) }
        if (old == null) {
            val task = extras(Task(
                id = newId(), title = input.title, body = input.body, priority = input.priority,
                category = input.category, dueAt = input.dueAt, scheduleAt = input.dueAt, repeat = input.repeat,
                rule = ruleFor(input, if (input.repeat != Repeat.NONE) input.dueAt else null, zone),
                remindBefore = early(input.remindBefore, input.dueAt), createdAt = now, updatedAt = now,
            ), input, null)
            return state.copy(tasks = state.tasks + task) to task
        }
        // An editor opened before Done or a snooze (from the notification or the alert) applies only
        // its text unless its reminder fields were edited, so its old time cannot undo that (Windows).
        val outdated = input.timeEdited != null && input.updatedAt?.toEpochMilli() != old.updatedAt.toEpochMilli()
        if (outdated && (input.timeEdited == false || old.completedAt != null)) {
            // Text, tags, steps and alert choices apply; the time, the alerts and the prayer/Hijri anchor stay.
            val keep = AlertExt.of(old)
            val ext = input.ext?.copy(alerts = keep.alerts, prayer = keep.prayer, hijri = keep.hijri)
            val task = extras(old.copy(title = input.title, body = input.body, priority = input.priority, category = input.category, updatedAt = now),
                input.copy(alerts = null, ext = ext), old)
            return replace(state, task) to task
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
        var task = extras(old.copy(
            title = input.title, body = input.body, priority = input.priority, category = input.category,
            dueAt = input.dueAt, repeat = input.repeat, rule = ruleFor(input, from, zone),
            remindBefore = early(input.remindBefore, input.dueAt), updatedAt = now,
        ), input, old)
        if (anchor) task = task.copy(scheduleAt = input.dueAt, snoozedAt = null, snoozeCount = 0)
        return replace(state, task) to task
    }

    private fun replace(state: AppState, task: Task) = state.copy(tasks = state.tasks.map { if (it.id == task.id) task else it })

    /** Compared to the millisecond, as data.json and the notification's extras store it (Windows compares the ISO text). */
    private fun stale(task: Task, expectedDueAt: Instant?, checked: Boolean) = checked && expectedDueAt?.toEpochMilli() != task.dueAt?.toEpochMilli()

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
            val (next, rule) = next(task, now, zone)
            val moved = task.copy(dueAt = next, scheduleAt = next, rule = rule, snoozedAt = null, snoozeCount = 0, updatedAt = now, extra = untick(task.extra))
            return replace(state, moved).let { it.copy(tasks = it.tasks + copy) } to Completion(id, copy.id)
        }
        return replace(state, task.copy(completedAt = now, updatedAt = now)) to Completion(id, id)
    }

    /** Puts a just-completed task back as it was. */
    fun undoComplete(state: AppState, c: Completion, now: Instant): AppState {
        val task = state.task(c.id) ?: return state
        if (c.copyId == c.id) return if (task.completedAt != null) replace(state, task.copy(completedAt = null, updatedAt = now)) else state
        val copy = state.tasks.firstOrNull { it.id == c.copyId && it.sourceId == c.id && it.completedAt != null } ?: return state
        val ext = AlertExt.of(task)
        val rule = if (ext.prayer != null || ext.hijri != null) task.rule.copy(from = copy.scheduleAt) else task.rule
        val back = task.copy(dueAt = copy.dueAt, scheduleAt = copy.scheduleAt, rule = rule, snoozedAt = copy.snoozedAt, snoozeCount = copy.snoozeCount, updatedAt = now, extra = copy.extra)
        return replace(state, back).let { s -> s.copy(tasks = s.tasks.filter { it.id != copy.id }) }
    }

    /** Moves a repeating task to its next occurrence without recording a completion. */
    fun skip(state: AppState, id: String, now: Instant, zone: ZoneId, expectedDueAt: Instant? = null, checked: Boolean = false): AppState? {
        val task = state.tasks.firstOrNull { it.id == id && it.active } ?: return null
        if (task.repeat == Repeat.NONE || task.scheduleAt == null || stale(task, expectedDueAt, checked)) return null
        val (next, rule) = next(task, now, zone)
        return replace(state, task.copy(dueAt = next, scheduleAt = next, rule = rule, snoozedAt = null, snoozeCount = 0, updatedAt = now))
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

    /** The moment of a task's first early notice (`remindBefore`), or null. */
    fun noticeAt(t: Task): Instant? = if (t.remindBefore > 0 && t.dueAt != null) t.dueAt.minusSeconds(t.remindBefore * 60L) else null

    /**
     * All early-notice lead times of a task, largest first (FEATURES.md F-046): `remindBefore`, which
     * Windows knows, plus up to 3 more kept in the Android extension.
     */
    fun noticeMinutes(t: Task): List<Int> {
        val extra = AlertExt.of(t).alerts
        // Reconciliation (FEATURES.md "Data format"): Windows changed remindBefore, so its value is used alone.
        val kept = if (extra.isNotEmpty() && t.remindBefore !in extra) emptyList() else extra
        return (listOf(t.remindBefore) + kept).filter { it > 0 }.distinct().sortedDescending()
    }

    /** The notice moments of a task, earliest first. */
    fun noticeTimes(t: Task): List<Instant> = t.dueAt?.let { d -> noticeMinutes(t).map { d.minusSeconds(it * 60L) } }.orEmpty()

    /**
     * The notice that applies at [now]: the latest moment that has come (and is after the last change),
     * as its log key — the due time alone for `remindBefore` (the 0.1 key), else "due#minutes".
     */
    fun noticeKey(t: Task, now: Instant): String? {
        val d = t.dueAt ?: return null
        if (!t.active || !d.isAfter(now)) return null
        val m = noticeMinutes(t).lastOrNull { val at = d.minusSeconds(it * 60L); !at.isAfter(now) && at.isAfter(t.updatedAt) } ?: return null
        return if (m == t.remindBefore) Codec.iso(d) else Codec.iso(d) + "#" + m
    }

    /**
     * Tasks whose early notice is due now. A notice moment at or before the task's last change is
     * skipped, so a snooze or an edit that moves the time into the lead window does not announce at once.
     */
    fun upcoming(state: AppState, now: Instant): List<Task> = state.tasks.filter { noticeKey(it, now) != null }
}
