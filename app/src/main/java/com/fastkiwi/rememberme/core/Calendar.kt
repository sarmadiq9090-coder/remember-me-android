package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * One reminder on one day of a calendar view (F-064). [at] is null for a reminder without a date. A
 * [projected] occurrence is a later time of a repeating reminder, computed with the repeat engine
 * ([nextOccurrence]) for display only and never stored; only the task's own current time is real.
 */
data class Occurrence(val task: Task, val at: Instant?, val projected: Boolean = false) {
    /** The task's current time has passed and it is not done. Projected times are always ahead. */
    fun overdue(now: Instant) = !projected && at != null && !at.isAfter(now)
    /** The current occurrence was snoozed. */
    val snoozed: Boolean get() = !projected && task.snoozedAt != null
    /** Unique within a view: a task appears once per time. */
    val key: String get() = task.id + "@" + (at?.toEpochMilli()?.toString() ?: "-")
}

/** The occurrences of one local day, timed ones by time, then the ones without a time. */
data class DayBucket(val date: LocalDate, val items: List<Occurrence>) {
    val count: Int get() = items.size
}

/**
 * Pure bucketing of reminders by local day, week and month for the calendar views. No Android and no
 * Hijri here (the Hijri numerals are a display matter of the UI). Week start is the Windows setting
 * `settings.weekStart` as JavaScript's getDay() numbers it: 6 Saturday (default), 0 Sunday, 1 Monday.
 */
object Calendar {
    /** How far ahead the agenda projects repeating reminders. One-time reminders are shown at any distance. */
    const val AGENDA_DAYS = 30L
    /** At most this many projected times per reminder and view (an hourly repeat over a 6-week grid is 1,008). */
    const val MAX_PER_TASK = 1_100

    /** `settings.weekStart` of data.json, read as Windows does (anything else is Saturday). */
    fun weekStartOf(settings: JsonObject): Int = Codec.settingInt(settings, "weekStart")?.takeIf { it in listOf(0, 1, 6) } ?: 6

    fun firstDay(weekStart: Int): DayOfWeek = when (weekStart) { 0 -> DayOfWeek.SUNDAY; 1 -> DayOfWeek.MONDAY; else -> DayOfWeek.SATURDAY }

    /** Weekdays (0 = Sunday … 6 = Saturday) in the order of the week. */
    fun weekdays(weekStart: Int): List<Int> = (0..6).map { (jsDay(firstDay(weekStart)) + it) % 7 }

    private fun jsDay(d: DayOfWeek) = d.value % 7

    fun startOfWeek(date: LocalDate, weekStart: Int): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay(weekStart)))

    /** The seven days of the week holding [date]. */
    fun week(date: LocalDate, weekStart: Int): List<LocalDate> = startOfWeek(date, weekStart).let { s -> (0L..6L).map(s::plusDays) }

    /** The 6 × 7 days of a month grid: from the week start on or before the 1st, 42 days. */
    fun monthGrid(month: YearMonth, weekStart: Int): List<LocalDate> = startOfWeek(month.atDay(1), weekStart).let { s -> (0L..41L).map(s::plusDays) }

    /**
     * The next time of [t] after both [current] and [after], or null when it is not projected here.
     * Prayer-relative and Hijri-yearly reminders (Android extension) move by their own rules, so they show
     * only their current time until the UI passes a [Projector] that knows those rules.
     */
    fun interface Projector { fun next(t: Task, current: Instant, after: Instant, zone: ZoneId): Instant? }

    /** The Windows repeat engine ([nextOccurrence]); null for prayer-relative and Hijri-yearly reminders. */
    val engine = Projector { t, current, after, zone ->
        if (t.repeat == Repeat.NONE || !plainRepeat(t)) null else nextOccurrence(current, t.repeat, after, t.rule, zone)
    }

    private fun plainRepeat(t: Task): Boolean {
        if (AlertExt.KEY !in t.extra) return true
        val ext = AlertExt.of(t)
        return ext.prayer == null && ext.hijri == null
    }

    private fun projectable(t: Task): Boolean = t.repeat != Repeat.NONE && t.scheduleAt != null

    /**
     * Every occurrence of the active tasks in the local days [from] (inclusive) to [until] (exclusive):
     * each task's current time when it falls in the range, and the later times of repeating tasks from
     * after [now]. Tasks without a date are not included (see [undated]).
     */
    fun occurrences(tasks: List<Task>, from: LocalDate, until: LocalDate, now: Instant, zone: ZoneId, projector: Projector = engine): List<Occurrence> {
        val start = from.atStartOfDay(zone).toInstant()
        val end = until.atStartOfDay(zone).toInstant()
        val out = ArrayList<Occurrence>()
        for (t in tasks) {
            if (!t.active) continue
            val due = t.dueAt ?: continue
            if (!due.isBefore(start) && due.isBefore(end)) out += Occurrence(t, due)
            project(t, start, end, now, zone, projector, out)
        }
        return out
    }

    private fun project(t: Task, start: Instant, end: Instant, now: Instant, zone: ZoneId, projector: Projector, out: MutableList<Occurrence>) {
        if (!projectable(t)) return
        // A repeat comes back after its current time and after now (as completing it would), then onwards.
        val floor = maxOf(start.minusMillis(1), now)
        var cur = t.scheduleAt!!
        var next = projector.next(t, cur, floor, zone) ?: return
        var n = 0
        while (next.isBefore(end) && n < MAX_PER_TASK) {
            if (next != t.dueAt && !next.isBefore(start)) { out += Occurrence(t, next, projected = true); n++ }
            cur = next
            next = projector.next(t, cur, cur, zone) ?: return
            if (!next.isAfter(cur)) return
        }
    }

    /** Active tasks without a date, oldest first. The views show them on today. */
    fun undated(tasks: List<Task>): List<Task> = tasks.filter { it.active && it.dueAt == null }.sortedBy { it.createdAt }

    private val order = compareBy<Occurrence>({ it.at == null }, { it.at }, { it.task.createdAt }, { it.task.id })

    /**
     * One bucket per day from [from] to [until] (exclusive), empty days included. The undated tasks are
     * added to [today] when it is in the range.
     */
    fun buckets(tasks: List<Task>, from: LocalDate, until: LocalDate, today: LocalDate, now: Instant, zone: ZoneId, projector: Projector = engine): List<DayBucket> {
        val map = HashMap<LocalDate, MutableList<Occurrence>>()
        for (o in occurrences(tasks, from, until, now, zone, projector)) map.getOrPut(o.at!!.atZone(zone).toLocalDate()) { ArrayList() } += o
        if (!today.isBefore(from) && today.isBefore(until)) {
            val list = map.getOrPut(today) { ArrayList() }
            for (t in undated(tasks)) list += Occurrence(t, null)
        }
        val days = ArrayList<DayBucket>()
        var d = from
        while (d.isBefore(until)) {
            days += DayBucket(d, map[d]?.sortedWith(order).orEmpty())
            d = d.plusDays(1)
        }
        return days
    }

    fun day(tasks: List<Task>, date: LocalDate, today: LocalDate, now: Instant, zone: ZoneId, projector: Projector = engine): DayBucket =
        buckets(tasks, date, date.plusDays(1), today, now, zone, projector).first()

    fun weekBuckets(tasks: List<Task>, date: LocalDate, weekStart: Int, today: LocalDate, now: Instant, zone: ZoneId, projector: Projector = engine): List<DayBucket> {
        val s = startOfWeek(date, weekStart)
        return buckets(tasks, s, s.plusDays(7), today, now, zone, projector)
    }

    fun monthBuckets(tasks: List<Task>, month: YearMonth, weekStart: Int, today: LocalDate, now: Instant, zone: ZoneId, projector: Projector = engine): List<DayBucket> {
        val s = startOfWeek(month.atDay(1), weekStart)
        return buckets(tasks, s, s.plusDays(42), today, now, zone, projector)
    }

    /**
     * The agenda: continuous from [today], days without reminders skipped. Overdue reminders of earlier
     * days are listed first under today (they are still waiting), then today's times, then the undated
     * ones. One-time reminders appear at any distance; repeats are projected [days] ahead.
     */
    fun agenda(tasks: List<Task>, today: LocalDate, now: Instant, zone: ZoneId, days: Long = AGENDA_DAYS, projector: Projector = engine): List<DayBucket> {
        val start = today.atStartOfDay(zone).toInstant()
        val horizon = today.plusDays(days).atStartOfDay(zone).toInstant()
        val map = HashMap<LocalDate, MutableList<Occurrence>>()
        val out = ArrayList<Occurrence>()
        for (t in tasks) {
            if (!t.active) continue
            val due = t.dueAt ?: continue
            out += Occurrence(t, due)
            project(t, start, horizon, now, zone, projector, out)
        }
        for (o in out) {
            val d = o.at!!.atZone(zone).toLocalDate().let { if (it.isBefore(today)) today else it }
            map.getOrPut(d) { ArrayList() } += o
        }
        val undated = undated(tasks)
        if (undated.isNotEmpty()) map.getOrPut(today) { ArrayList() }.addAll(undated.map { Occurrence(it, null) })
        return map.entries.sortedBy { it.key }.map { (d, list) -> DayBucket(d, list.sortedWith(order)) }
    }
}
