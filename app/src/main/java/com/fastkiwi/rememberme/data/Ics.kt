package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.localToInstant
import com.fastkiwi.rememberme.core.nextOccurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules

/**
 * iCalendar export (RFC 5545), so any calendar app can open the reminders (D-016).
 *
 * A timed reminder is a VEVENT: DTSTART in local time with an IANA TZID and a matching VTIMEZONE, an
 * RRULE for its repeat, and VALARMs at the time and for each early alert. A reminder without a time is
 * a VTODO. Completed and deleted reminders are left out. The repeat rules map exactly, including the
 * Windows app's month-end rule (the 31st becomes the month's last day): BYMONTHDAY=28,29,30,31;BYSETPOS=-1.
 *
 * A repeat whose current occurrence is not one of the rule's own (a "chosen weekdays" reminder set for another
 * weekday, which the app still rings at that time): DTSTART is the series' next occurrence (by the app's own
 * [nextOccurrence]) and the current one is an RDATE, because RFC 5545 leaves a DTSTART outside its RRULE
 * undefined and calendars disagree on it.
 */
object Ics {
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAYS = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

    fun export(state: AppState, zone: ZoneId, now: Instant): String {
        val out = StringBuilder()
        fun line(s: String) = out.append(fold(s)).append("\r\n")
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//FastKiwi//Remember Me Android//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        val items = state.tasks.filter { it.active }
        val timed = items.filter { it.dueAt != null }
        if (timed.isNotEmpty()) {
            // The VTIMEZONE covers the earliest start written (an overdue repeat can start long ago).
            val earliest = timed.minOf { start(it) }.minusSeconds(86_400)
            vtimezone(zone, now, earliest).forEach(::line)
        }
        for (t in items) component(t, zone, now).forEach(::line)
        line("END:VCALENDAR")
        return out.toString()
    }

    /** The RRULE value of a task, or null for a one-time reminder. */
    fun rrule(t: Task, zone: ZoneId): String? {
        val from = (t.rule.from ?: t.scheduleAt ?: t.dueAt ?: return null).atZone(zone)
        return when (t.repeat) {
            Repeat.NONE -> null
            Repeat.DAILY -> "FREQ=DAILY"
            Repeat.WEEKLY -> "FREQ=WEEKLY"
            Repeat.DAYS -> t.rule.days.filter { it in 0..6 }.distinct().sorted().let { days ->
                if (days.isEmpty()) "FREQ=WEEKLY" else "FREQ=WEEKLY;BYDAY=" + days.joinToString(",") { DAYS[it] }
            }
            Repeat.INTERVAL -> "FREQ=DAILY;INTERVAL=${t.rule.everyDays}"
            Repeat.HOURS -> "FREQ=HOURLY;INTERVAL=${t.rule.everyHours}"
            Repeat.MONTHLY -> "FREQ=MONTHLY;" + monthDay(from.dayOfMonth)
            Repeat.YEARLY -> "FREQ=YEARLY;BYMONTH=${from.monthValue};" + monthDay(from.dayOfMonth)
        }
    }

    /** Day of month with the Windows clamp: days 29-31 fall back to the month's last day. */
    private fun monthDay(day: Int): String =
        if (day <= 28) "BYMONTHDAY=$day" else "BYMONTHDAY=" + (28..day).joinToString(",") + ";BYSETPOS=-1"

    /** A repeat starts at its current regular occurrence (a snooze is not part of the series). */
    private fun start(t: Task): Instant = if (t.repeat != Repeat.NONE) t.scheduleAt ?: t.dueAt!! else t.dueAt!!

    /**
     * Whether [at] is an occurrence the exported RRULE gives from it: the series' time of day (rule.from), and
     * for chosen weekdays one of those days, for a monthly or yearly repeat the anchor's (clamped) day and month.
     */
    private fun regular(t: Task, at: Instant, zone: ZoneId): Boolean {
        val date = at.atZone(zone).toLocalDate()
        val anchor = (t.rule.from ?: at).atZone(zone)
        val onTime = localToInstant(LocalDateTime.of(date, anchor.toLocalTime()), zone) == at
        val day = minOf(anchor.dayOfMonth, date.lengthOfMonth())
        return when (t.repeat) {
            Repeat.NONE, Repeat.HOURS -> true
            Repeat.DAYS -> onTime && t.rule.days.filter { it in 0..6 }.let { it.isEmpty() || jsDay(date) in it }
            Repeat.MONTHLY -> onTime && date.dayOfMonth == day
            Repeat.YEARLY -> onTime && date.month == anchor.month && date.dayOfMonth == day
            else -> onTime
        }
    }

    /**
     * The local DTSTART of [at]: the series' own wall time where it gives [at] (a time in a DST gap stays the
     * series' time, so the RRULE keeps it on the other days), else the local time of [at].
     */
    private fun wall(t: Task, at: Instant, zone: ZoneId): LocalDateTime {
        val local = at.atZone(zone).toLocalDateTime()
        if (t.repeat == Repeat.NONE || t.repeat == Repeat.HOURS) return local
        val series = LocalDateTime.of(local.toLocalDate(), (t.rule.from ?: at).atZone(zone).toLocalTime())
        return if (localToInstant(series, zone) == at) series else local
    }

    private fun component(t: Task, zone: ZoneId, now: Instant): List<String> {
        val lines = ArrayList<String>()
        val kind = if (t.dueAt == null) "VTODO" else "VEVENT"
        lines += "BEGIN:$kind"
        lines += "UID:${t.id}@rememberme.fastkiwi.app"
        lines += "DTSTAMP:" + UTC.format(now)
        lines += "CREATED:" + UTC.format(t.createdAt)
        lines += "LAST-MODIFIED:" + UTC.format(t.updatedAt)
        lines += "SUMMARY:" + text(t.title)
        if (t.body.isNotBlank()) lines += "DESCRIPTION:" + text(t.body)
        lines += "CATEGORIES:" + t.category.code.uppercase()
        when (t.priority) {
            Priority.HIGH -> lines += "PRIORITY:1"
            Priority.MEDIUM -> lines += "PRIORITY:5"
            Priority.NORMAL -> Unit
        }
        if (t.dueAt != null) {
            val start = start(t)
            val rule = rrule(t, zone)
            // Local time with the TZID; a local time that names another instant (the second pass of a fall-back
            // hour) is written in UTC instead.
            fun time(name: String, at: Instant, local: LocalDateTime) =
                if (localToInstant(local, zone) == at) "$name;TZID=${zone.id}:" + LOCAL.format(local) else "$name:" + UTC.format(at)
            if (rule == null || regular(t, start, zone)) {
                lines += time("DTSTART", start, wall(t, start, zone))
                rule?.let { lines += "RRULE:$it" }
            } else {
                // The series from its next own occurrence, and the current one as an extra date.
                val next = nextOccurrence(start, t.repeat, start, t.rule, zone)
                lines += time("DTSTART", next, wall(t, next, zone))
                lines += "RRULE:$rule"
                lines += time("RDATE", start, start.atZone(zone).toLocalDateTime())
            }
            lines += alarm("PT0S", t.title)
            // Every early alert (F-046): remindBefore and the Android-only list, one VALARM each.
            for (m in com.fastkiwi.rememberme.core.Tasks.noticeMinutes(t)) lines += alarm("-PT${m}M", t.title)
        }
        lines += "END:$kind"
        return lines
    }

    private fun alarm(trigger: String, title: String) = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + text(title), "TRIGGER:$trigger", "END:VALARM")

    /**
     * VTIMEZONE for [zone] (RFC 5545 §3.6.5), exact for every instant from [from] (default: a year before [now])
     * on, read by the letter of the RFC (each onset is its DTSTART/RRULE local time in TZOFFSETFROM).
     * - A zone with yearly rules: one observance per rule with a yearly RRULE (two when the rule's 7-day window
     *   crosses a month end, as Africa/Cairo's "Friday on or after 26 October"), from the first of Java's
     *   transitions that the rules give (often years back, the usual form). The RRULE is written for the local
     *   date before the change, so a rule defined in UTC or standard time whose local day is the day before
     *   (America/Nuuk, Pacific/Easter), or a change at 24:00, gets the right weekday and days.
     * - Before that, when the rules start after [from] (Asia/Gaza's predicted transitions until 2087), and for a
     *   zone without yearly rules (Africa/Casablanca): one observance for the offset in force at [from] and one
     *   per explicit transition after it, all of them, so the file never runs out after a fixed number of years.
     * Each RRULE is checked against Java's own rule for the years 1900 to 2500; a rule that no RRULE form matches
     * (none in the current data) is written transition by transition for the next 60 years instead.
     */
    fun vtimezone(zone: ZoneId, now: Instant, from: Instant? = null): List<String> = vtimezone(zone.id, zone.rules, now, from)

    internal fun vtimezone(id: String, rules: ZoneRules, now: Instant, from: Instant? = null): List<String> {
        val lines = arrayListOf("BEGIN:VTIMEZONE", "TZID:$id")
        fun observance(std: Boolean, start: LocalDateTime, from: ZoneOffset, to: ZoneOffset, rrule: String? = null) {
            val kind = if (std) "STANDARD" else "DAYLIGHT"
            lines += "BEGIN:$kind"
            lines += "DTSTART:" + LOCAL.format(start)
            lines += "TZOFFSETFROM:" + offset(from)
            lines += "TZOFFSETTO:" + offset(to)
            rrule?.let { lines += "RRULE:$it" }
            lines += "END:$kind"
        }
        val default = now.minusSeconds(366L * 86_400)
        val begin = Instant.ofEpochSecond((if (from != null && from.isBefore(default)) from else default).epochSecond)
        // The offset at the start, then the explicit transitions after it, before [until] (null: all).
        fun explicit(until: Instant?) {
            val first = rules.getOffset(begin)
            observance(rules.getStandardOffset(begin) == first, LocalDateTime.ofInstant(begin, first), first, first)
            var tr = rules.nextTransition(begin)
            while (tr != null && (until == null || tr.instant.isBefore(until))) {
                observance(rules.getStandardOffset(tr.instant) == tr.offsetAfter, tr.dateTimeBefore, tr.offsetBefore, tr.offsetAfter)
                tr = rules.nextTransition(tr.instant)
            }
        }
        // Java applies yearly rules only after explicit transitions.
        val yearly = if (rules.transitions.isEmpty()) emptyList() else rules.transitionRules.map { it to yearlyForm(it) }
        when {
            yearly.isEmpty() -> explicit(null)
            yearly.any { it.second == null } -> explicit(LocalDate.of(now.atOffset(ZoneOffset.UTC).year + 60, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
            else -> {
                val era = ruleEra(rules)
                class Rule(val std: Boolean, val start: LocalDateTime, val at: Instant, val from: ZoneOffset, val to: ZoneOffset, val rrule: String)
                val ruled = ArrayList<Rule>()
                for ((rule, form) in yearly) {
                    form!!
                    for (part in form.parts) {
                        // This part's first onset in the rules' era (one part of a window across a month end is not used every year).
                        val tr = (era.atOffset(ZoneOffset.UTC).year - 1..era.atOffset(ZoneOffset.UTC).year + 400).asSequence()
                            .map { rule.createTransition(it) }
                            .firstOrNull { !it.instant.isBefore(era) && it.dateTimeBefore.toLocalDate() in part.dates(it.dateTimeBefore.year, form.weekday) } ?: continue
                        ruled += Rule(rule.standardOffset == rule.offsetAfter, tr.dateTimeBefore, tr.instant, rule.offsetBefore, rule.offsetAfter, part.rrule(form.weekday))
                    }
                }
                // The rules start as early as Java's data allows (the usual form, and what a reader that takes the
                // observance in force today as the zone's yearly rule expects); only a zone whose rules start after
                // [begin] (predicted transitions listed one by one) also needs its offsets before them.
                if (ruled.isEmpty() || ruled.minOf { it.at }.isAfter(begin)) explicit(era)
                for (r in ruled) observance(r.std, r.start, r.from, r.to, r.rrule)
            }
        }
        lines += "END:VTIMEZONE"
        return lines
    }

    /**
     * The first instant from which [rules]' yearly rules give every transition: the latest explicit transitions
     * that are the rules' own (with no rule transition missing among them) are left to the rules.
     */
    private fun ruleEra(rules: ZoneRules): Instant {
        val explicit = rules.transitions
        val yearly = rules.transitionRules
        fun same(a: ZoneOffsetTransition, b: ZoneOffsetTransition) =
            a.instant == b.instant && a.offsetBefore == b.offsetBefore && a.offsetAfter == b.offsetAfter
        fun ruleTransitions(fromYear: Int, toYear: Int) = (fromYear..toYear).flatMap { y -> yearly.map { it.createTransition(y) } }.sortedBy { it.instant }
        val last = explicit.last()
        var k = explicit.size
        while (k > 0) {
            val e = explicit[k - 1]
            val y = e.dateTimeBefore.year
            if (ruleTransitions(y - 1, y + 1).none { same(it, e) }) break
            k--
        }
        while (k < explicit.size) {
            val from = explicit[k]
            val want = explicit.subList(k, explicit.size)
            val got = ruleTransitions(from.dateTimeBefore.year - 1, last.dateTimeBefore.year + 1)
                .filter { !it.instant.isBefore(from.instant) && !it.instant.isAfter(last.instant) }
            if (got.size == want.size && got.indices.all { same(got[it], want[it]) }) break
            k++
        }
        return if (k < explicit.size) explicit[k].instant else last.instant.plusSeconds(1)
    }

    /** One yearly RRULE of a VTIMEZONE rule: BYMONTH plus BYMONTHDAY, or BYYEARDAY when [month] is null. */
    internal class Part(val month: Int?, val days: List<Int>) {
        /** The dates this part gives in [year] (BYDAY [weekday] limits them, RFC 5545 §3.3.10). */
        fun dates(year: Int, weekday: DayOfWeek?): Set<LocalDate> {
            val out = HashSet<LocalDate>()
            val len = if (month != null) YearMonth.of(year, month).lengthOfMonth() else Year.of(year).length()
            for (d in days) {
                val n = if (d > 0) d else len + 1 + d
                if (n !in 1..len) continue
                val date = if (month != null) LocalDate.of(year, month, n) else LocalDate.ofYearDay(year, n)
                if (weekday == null || date.dayOfWeek == weekday) out += date
            }
            return out
        }

        fun rrule(weekday: DayOfWeek?): String =
            "FREQ=YEARLY;" + (if (month != null) "BYMONTH=$month;BYMONTHDAY=" else "BYYEARDAY=") + days.joinToString(",") +
                (weekday?.let { ";BYDAY=" + DAYS[it.value % 7] } ?: "")
    }

    /** The RRULE form of a yearly rule: its parts and the weekday of the local date before the change. */
    internal class YearlyForm(val weekday: DayOfWeek?, val parts: List<Part>)

    private const val CHECK_FROM = 1900
    private const val CHECK_TO = 2500

    /**
     * The RRULE parts that give exactly Java's onsets of [rule] (local date-times before the change) for every
     * year 1900..2500, or null. Java's model is "the first <weekday> on or after day N" (or "on or before",
     * counted from the month end) at a time in wall, standard or UTC time: the onset's local date is that day
     * moved by a fixed number of days, so it is a fixed weekday inside a fixed 7-day window, written as a day
     * list plus BYDAY. Forms tried in order: one month's days (counted from its start, then from its end), the
     * days of two months, then days of the year (for a window across the end of February or of the year).
     */
    internal fun yearlyForm(rule: ZoneOffsetTransitionRule): YearlyForm? = try {
        val ind = rule.dayOfMonthIndicator
        val dow = rule.dayOfWeek
        fun anchor(y: Int): LocalDate =
            if (ind < 0) LocalDate.of(y, rule.month, rule.month.length(Year.isLeap(y.toLong())) + 1 + ind) else LocalDate.of(y, rule.month, ind)
        // Java's date before the time of day is applied (ZoneOffsetTransitionRule.createTransition).
        fun raw(y: Int): LocalDate = when {
            dow == null -> anchor(y)
            ind < 0 -> anchor(y).with(TemporalAdjusters.previousOrSame(dow))
            else -> anchor(y).with(TemporalAdjusters.nextOrSame(dow))
        }
        val base = rule.createTransition(2001).dateTimeBefore
        val shift = ChronoUnit.DAYS.between(raw(2001), base.toLocalDate())
        val weekday = dow?.plus(shift)
        fun window(y: Int): List<LocalDate> = when {
            dow == null -> listOf(anchor(y))
            ind < 0 -> (6 downTo 0).map { anchor(y).minusDays(it.toLong()) }
            else -> (0..6).map { anchor(y).plusDays(it.toLong()) }
        }.map { it.plusDays(shift) }
        val truth = ArrayList<LocalDate>()
        var consistent = true
        for (y in CHECK_FROM..CHECK_TO) {
            val at = rule.createTransition(y).dateTimeBefore
            if (at.toLocalTime() != base.toLocalTime() || ChronoUnit.DAYS.between(raw(y), at.toLocalDate()) != shift) consistent = false
            truth += at.toLocalDate()
        }
        val truthSet = truth.toHashSet()
        fun product(groups: List<List<Part>>): List<List<Part>> =
            groups.fold(listOf(emptyList())) { acc, options -> acc.flatMap { prefix -> options.map { prefix + it } } }
        fun candidates(dates: List<LocalDate>): List<List<Part>> {
            val byMonth = dates.groupBy { YearMonth.from(it) }.values.map { ds ->
                val len = ds[0].lengthOfMonth()
                listOf(Part(ds[0].monthValue, ds.map { it.dayOfMonth }), Part(ds[0].monthValue, ds.map { it.dayOfMonth - len - 1 }))
            }
            val byYear = dates.groupBy { it.year }.values.map { ds ->
                val len = ds[0].lengthOfYear()
                listOf(Part(null, ds.map { it.dayOfYear }), Part(null, ds.map { it.dayOfYear - len - 1 }))
            }
            return product(byMonth) + product(byYear)
        }
        val tried = (candidates(window(2001)) + candidates(window(2000))).distinctBy { parts -> parts.map { it.month to it.days } }
        tried.takeIf { consistent }?.firstOrNull { parts ->
            val got = HashSet<LocalDate>()
            for (y in CHECK_FROM - 1..CHECK_TO + 1) for (p in parts) got += p.dates(y, weekday)
            got.filterTo(HashSet()) { !it.isBefore(truth.first()) && !it.isAfter(truth.last()) } == truthSet
        }?.let { YearlyForm(weekday, it) }
    } catch (_: RuntimeException) {
        null
    }

    private fun offset(o: ZoneOffset): String {
        val s = o.totalSeconds
        val sign = if (s < 0) "-" else "+"
        val a = kotlin.math.abs(s)
        val hhmm = "%02d%02d".format(java.util.Locale.ROOT, a / 3600, (a % 3600) / 60)
        return if (a % 60 == 0) sign + hhmm else sign + hhmm + "%02d".format(java.util.Locale.ROOT, a % 60)
    }

    /** TEXT value escaping (RFC 5545 §3.3.11). */
    fun text(s: String): String = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** Folds a content line at 75 octets without splitting a UTF-8 character (RFC 5545 §3.1). */
    fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var count = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (count + n > limit) {
                out.append("\r\n ")
                count = 0
                limit = 74 // the leading space counts
            }
            out.appendCodePoint(cp)
            count += n
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
