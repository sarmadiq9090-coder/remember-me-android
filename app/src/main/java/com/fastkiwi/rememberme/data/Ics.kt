package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar export (RFC 5545), so any calendar app can open the reminders (D-016).
 *
 * A timed reminder is a VEVENT: DTSTART in local time with an IANA TZID and a matching VTIMEZONE, an
 * RRULE for its repeat, and VALARMs at the time and for each early alert. A reminder without a time is
 * a VTODO. Completed and deleted reminders are left out. The repeat rules map exactly, including the
 * Windows app's month-end rule (the 31st becomes the month's last day): BYMONTHDAY=28,29,30,31;BYSETPOS=-1.
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
        if (items.any { it.dueAt != null }) vtimezone(zone, now).forEach(::line)
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
            Repeat.DAYS -> "FREQ=WEEKLY;BYDAY=" + t.rule.days.sorted().joinToString(",") { DAYS[it] }
            Repeat.INTERVAL -> "FREQ=DAILY;INTERVAL=${t.rule.everyDays}"
            Repeat.HOURS -> "FREQ=HOURLY;INTERVAL=${t.rule.everyHours}"
            Repeat.MONTHLY -> "FREQ=MONTHLY;" + monthDay(from.dayOfMonth)
            Repeat.YEARLY -> "FREQ=YEARLY;BYMONTH=${from.monthValue};" + monthDay(from.dayOfMonth)
        }
    }

    /** Day of month with the Windows clamp: days 29-31 fall back to the month's last day. */
    private fun monthDay(day: Int): String =
        if (day <= 28) "BYMONTHDAY=$day" else "BYMONTHDAY=" + (28..day).joinToString(",") + ";BYSETPOS=-1"

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
            // A repeat starts at its current regular occurrence (a snooze is not part of the series).
            val start = if (t.repeat != Repeat.NONE) t.scheduleAt ?: t.dueAt else t.dueAt
            lines += "DTSTART;TZID=${zone.id}:" + LOCAL.format(start.atZone(zone).toLocalDateTime())
            rrule(t, zone)?.let { lines += "RRULE:$it" }
            lines += alarm("PT0S", t.title)
            // Every early alert (F-046): remindBefore and the Android-only list, one VALARM each.
            for (m in com.fastkiwi.rememberme.core.Tasks.noticeMinutes(t)) lines += alarm("-PT${m}M", t.title)
        }
        lines += "END:$kind"
        return lines
    }

    private fun alarm(trigger: String, title: String) = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + text(title), "TRIGGER:$trigger", "END:VALARM")

    /**
     * VTIMEZONE for [zone] (RFC 5545 §3.6.5): a fixed zone has one STANDARD observance; a zone with
     * yearly DST rules has one STANDARD and one DAYLIGHT observance with a yearly RRULE (the usual form,
     * read the same way by every calendar); anything else lists its transitions one by one.
     */
    fun vtimezone(zone: ZoneId, now: Instant): List<String> {
        val rules = zone.rules
        val lines = arrayListOf("BEGIN:VTIMEZONE", "TZID:${zone.id}")
        fun observance(kind: String, start: LocalDateTime, from: ZoneOffset, to: ZoneOffset, rrule: String? = null) {
            lines += "BEGIN:$kind"
            lines += "DTSTART:" + LOCAL.format(start)
            lines += "TZOFFSETFROM:" + offset(from)
            lines += "TZOFFSETTO:" + offset(to)
            rrule?.let { lines += "RRULE:$it" }
            lines += "END:$kind"
        }
        val yearly = rules.transitionRules
        if (yearly.isNotEmpty()) {
            val year = now.atZone(zone).year - 1
            for (r in yearly) {
                val tr = r.createTransition(year)
                val std = rules.getStandardOffset(tr.instant.plusSeconds(1)) == tr.offsetAfter
                val dow = r.dayOfWeek
                val ind = r.dayOfMonthIndicator
                // Java's own model, "the first <day> on or after the Nth" (or "on or before" when negative),
                // written as a day range plus BYDAY, which every RFC 5545 reader expands the same way.
                val by = when {
                    dow == null -> "BYMONTHDAY=$ind"
                    ind > 0 -> "BYMONTHDAY=" + (ind..minOf(ind + 6, 31)).joinToString(",") + ";BYDAY=" + DAYS[dow.value % 7]
                    else -> "BYMONTHDAY=" + (ind - 6..ind).joinToString(",") + ";BYDAY=" + DAYS[dow.value % 7]
                }
                observance(if (std) "STANDARD" else "DAYLIGHT", tr.dateTimeBefore, tr.offsetBefore, tr.offsetAfter,
                    "FREQ=YEARLY;BYMONTH=${r.month.value};$by")
            }
            lines += "END:VTIMEZONE"
            return lines
        }
        val begin = now.minusSeconds(366L * 86_400)
        val end = now.plusSeconds(3660L * 86_400)
        val transitions = generateSequence(rules.nextTransition(begin)) { rules.nextTransition(it.instant) }
            .takeWhile { it.instant.isBefore(end) }.toList()
        val first = rules.getOffset(begin)
        val startStd = rules.getStandardOffset(begin) == first
        observance(if (startStd) "STANDARD" else "DAYLIGHT", LocalDateTime.ofInstant(begin, first).withNano(0), first, first)
        for (tr in transitions) {
            val std = rules.getStandardOffset(tr.instant) == tr.offsetAfter
            observance(if (std) "STANDARD" else "DAYLIGHT", tr.dateTimeBefore, tr.offsetBefore, tr.offsetAfter)
        }
        lines += "END:VTIMEZONE"
        return lines
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
