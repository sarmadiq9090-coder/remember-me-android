package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.EARLY_MINUTES
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskException
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.localToInstant
import com.fastkiwi.rememberme.core.nextOccurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules
import java.util.Locale

/** What [IcsImport.read] found: the reminders to add, what was left out and why, and what changed on the way. */
data class IcsImportResult(
    val inputs: List<com.fastkiwi.rememberme.core.TaskInput>,
    /** One note per event or task that is not imported, naming it and giving the reason. */
    val skipped: List<IcsNote>,
    /** One note per thing that was imported differently from the file (a repeat dropped, an alarm not kept...). */
    val warnings: List<IcsNote>,
)

/**
 * iCalendar import (RFC 5545), a subset (F-085). Reads everything [Ics.export] writes, and the usual files of
 * Google Calendar, Outlook and Apple Calendar. Nothing is dropped silently: every event or task is either in
 * [IcsImportResult.inputs] or named in [IcsImportResult.skipped], and every difference is named in
 * [IcsImportResult.warnings].
 *
 * Read: content lines with folding (CRLF, LF or CR, then a space or tab), parameters (quoted values, RFC 6868
 * caret escapes) and TEXT escapes; VEVENT and VTODO directly inside a VCALENDAR; SUMMARY, DESCRIPTION, LOCATION
 * (added to the text), PRIORITY (1-4 high, 5 medium, 0 and 6-9 normal), CATEGORIES (PERSONAL, WORK, STUDY;
 * others are ignored), STATUS, UID.
 *
 * Time: DTSTART (for a VTODO: DUE, else DTSTART). UTC (`Z`) is UTC; a floating time (no zone) is this phone's
 * wall time; a TZID uses the file's VTIMEZONE (STANDARD/DAYLIGHT observances with a yearly RRULE or RDATEs,
 * the way every producer writes them); when that VTIMEZONE agrees with an IANA zone of the same name the IANA
 * rules are used, as they also know the past. Without a VTIMEZONE: the IANA id (also inside a longer path such
 * as /mozilla.org/.../Europe/Berlin), then the common Windows zone names (Arab, Arabic, Arabian, Egypt...),
 * else this phone's zone with a warning. Decision for all-day dates (VALUE=DATE): the reminder is set for 09:00
 * on that day in this phone's zone, as the Windows app's import does, with one warning listing them.
 *
 * Repeats: exactly the rules [Ics.export] writes map back (DAILY, WEEKLY, WEEKLY;BYDAY=..., DAILY;INTERVAL=n,
 * HOURLY;INTERVAL=n, MONTHLY/YEARLY with BYMONTHDAY=d or the month-end form BYMONTHDAY=28,...;BYSETPOS=-1),
 * plus the Windows app's bare FREQ=MONTHLY/YEARLY and a few exact equivalents (WEEKLY;INTERVAL=n as every 7n
 * days, DAILY;BYDAY=..., BYMONTHDAY=-1). A rule this app cannot express exactly (COUNT, UNTIL, EXDATE, RDATE,
 * EXRULE, BYHOUR, BYWEEKNO, BYDAY with a number, FREQ=MINUTELY/SECONDLY, several RRULEs, ...) is not guessed:
 * the event is imported once, without the repeat, and a warning names the event and the rule parts. A repeat
 * keeps its series start as dueAt (the anchor of the day of month, 29 February, the every-N phase), even when it
 * has passed: [save] moves it on to its next occurrence, as the Windows app's importTasks does. A month-end start
 * clamped to a short month (28 February with BYMONTHDAY=28,...,31;BYSETPOS=-1 or BYMONTHDAY=-1) is kept, anchored
 * on its real day. An every-N-hours series counts elapsed hours, so it gets no clock warning. A one-time
 * event whose time has passed is skipped with a reason, also as on Windows.
 *
 * Alarms: VALARM triggers before the start (relative, or absolute DATE-TIME) become early alerts when they are
 * one of 5, 10, 15, 30, 60 or 1440 minutes (EARLY_MINUTES); an alarm at the start is this app's normal reminder;
 * anything else is a warning. Up to 3 early alerts are kept (F-046): the nearest is remindBefore, and with 2 or
 * more all of them are in the task's `android.alerts` ([TaskInput.alerts]); further ones are named in a warning.
 *
 * The report is data ([IcsNote]); the settings screen renders it in the app language.
 *
 * Limits: 20 MB of text ([ImportException] TOO_LARGE), 5,000 events and tasks (the rest is named in a
 * warning), 50,000 components and 200,000 properties in all (the rest is not read, with a warning). A file
 * without a VCALENDAR throws [ImportException] NOT_ICS. Nothing else is thrown, whatever the input.
 */
object IcsImport {
    /**
     * The series anchor of an input whose start is a clamped month end ([read]'s monthAnchor), for [save]:
     * [TaskInput] has no field for it. Weak keys, so a result that is dropped takes its entries with it.
     */
    private val anchors: MutableMap<TaskInput, Instant> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_ITEMS = 5_000
    private const val MAX_COMPONENTS = 50_000
    private const val MAX_PROPERTIES = 200_000
    private const val MAX_DEPTH = 16
    private const val MAX_ZONES = 100
    private const val TZ_BUDGET = 200_000

    /** The time of day an all-day event's reminder gets. */
    val ALL_DAY_TIME: LocalTime = LocalTime.of(9, 0)

    class ImportException(val reason: Reason) : Exception(reason.name) {
        enum class Reason { NOT_ICS, TOO_LARGE }
    }

    /** Reads an .ics file. Throws only [ImportException]. */
    fun read(text: String, zone: ZoneId, now: Instant): IcsImportResult {
        if (text.length > MAX_BYTES || utf8Length(text) > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
        val tree = parse(if (text.startsWith("\uFEFF")) text.substring(1) else text)
        val calendars = tree.roots.filter { it.name == "VCALENDAR" }
        if (calendars.isEmpty()) throw ImportException(ImportException.Reason.NOT_ICS)
        return Reader(zone, now, calendars, tree.stopped).run()
    }

    /**
     * Adds imported inputs to [state], as the Windows app's importTasks: past one-time items are left out, and
     * a repeat whose start has passed resumes at its next occurrence (its series anchor stays the start).
     * Returns the new state and the added tasks.
     */
    fun save(state: AppState, inputs: List<TaskInput>, now: Instant, zone: ZoneId): Pair<AppState, List<Task>> {
        var s = state
        val added = ArrayList<Task>()
        for (input in inputs) {
            val due = input.dueAt
            if (due != null && input.repeat == Repeat.NONE && !due.isAfter(now)) continue
            var (next, task) = try {
                Tasks.save(s, input.copy(id = null, updatedAt = null, timeEdited = null), now, zone)
            } catch (_: TaskException) {
                continue
            }
            // A clamped month-end start keeps the real day (31, 29 February) as its series anchor.
            anchors[input]?.let { from ->
                if (task.repeat == Repeat.MONTHLY || task.repeat == Repeat.YEARLY) {
                    val anchored = task.copy(rule = task.rule.copy(from = from))
                    next = next.copy(tasks = next.tasks.map { if (it.id == anchored.id) anchored else it })
                    task = anchored
                }
            }
            val start = task.scheduleAt
            if (task.repeat != Repeat.NONE && start != null && !start.isAfter(now)) {
                val at = nextOccurrence(start, task.repeat, now, task.rule, zone)
                val moved = task.copy(dueAt = at, scheduleAt = at)
                next = next.copy(tasks = next.tasks.map { if (it.id == moved.id) moved else it })
                task = moved
            }
            s = next
            added += task
        }
        return s to added
    }

    // ------------------------------------------------------------------------------------------------
    // Content lines and components
    // ------------------------------------------------------------------------------------------------

    private class Prop(val name: String, val params: Map<String, List<String>>, val value: String) {
        fun param(key: String): String? = params[key]?.firstOrNull()
    }

    private class Comp(val name: String) {
        val props = ArrayList<Prop>()
        val children = ArrayList<Comp>()
        var closed = false
        fun first(n: String) = props.firstOrNull { it.name == n }
        fun all(n: String) = props.filter { it.name == n }
    }

    private class Tree(val roots: List<Comp>, val stopped: Boolean)

    private fun utf8Length(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** Calls [f] with each unfolded content line (RFC 5545 §3.1); stops when it returns false. */
    private inline fun forEachLine(text: String, f: (String) -> Boolean) {
        val cur = StringBuilder()
        var has = false
        var i = 0
        val n = text.length
        while (i < n) {
            var j = i
            while (j < n && text[j] != '\r' && text[j] != '\n') j++
            if (j > i && (text[i] == ' ' || text[i] == '\t') && has) {
                cur.append(text, i + 1, j)
            } else {
                if (has && cur.isNotEmpty() && !f(cur.toString())) return
                cur.setLength(0)
                cur.append(text, i, j)
                has = true
            }
            i = if (j < n && text[j] == '\r' && j + 1 < n && text[j + 1] == '\n') j + 2 else j + 1
        }
        if (has && cur.isNotEmpty()) f(cur.toString())
    }

    /** RFC 6868 parameter value escapes: ^n newline, ^' quote, ^^ caret. */
    private fun caret(s: String): String {
        if ('^' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '^' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2; continue }
                    '\'' -> { out.append('"'); i += 2; continue }
                    '^' -> { out.append('^'); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun parseLine(line: String): Prop? {
        val len = line.length
        var i = 0
        while (i < len && line[i] != ';' && line[i] != ':') i++
        if (i >= len) return null
        val name = line.substring(0, i).trim().uppercase(Locale.ROOT)
        if (name.isEmpty() || name.length > 100) return null
        val params = LinkedHashMap<String, MutableList<String>>()
        while (i < len && line[i] == ';') {
            i++
            val ps = i
            while (i < len && line[i] != '=' && line[i] != ';' && line[i] != ':') i++
            if (i >= len) return null
            if (line[i] != '=') continue
            val pname = line.substring(ps, i).trim().uppercase(Locale.ROOT)
            i++
            val values = ArrayList<String>()
            while (true) {
                if (i < len && line[i] == '"') {
                    val e = line.indexOf('"', i + 1)
                    if (e < 0) return null
                    values += caret(line.substring(i + 1, e))
                    i = e + 1
                } else {
                    val s = i
                    while (i < len && line[i] != ',' && line[i] != ';' && line[i] != ':') i++
                    values += caret(line.substring(s, i))
                }
                if (i < len && line[i] == ',') { i++; continue }
                break
            }
            if (params.size < 32) params.getOrPut(pname) { ArrayList() }.addAll(values.take(32))
        }
        if (i >= len || line[i] != ':') return null
        return Prop(name, params, line.substring(i + 1))
    }

    private fun parse(text: String): Tree {
        val roots = ArrayList<Comp>()
        val stack = ArrayList<Comp>()
        var overflow = 0 // components nested deeper than MAX_DEPTH: their lines are ignored
        var comps = 0
        var props = 0
        var stopped = false
        forEachLine(text) { line ->
            val p = parseLine(line) ?: return@forEachLine true
            when (p.name) {
                "BEGIN" -> {
                    if (++comps > MAX_COMPONENTS) { stopped = true; return@forEachLine false }
                    if (overflow > 0 || stack.size >= MAX_DEPTH) {
                        overflow++
                    } else {
                        val c = Comp(p.value.trim().uppercase(Locale.ROOT).take(64))
                        if (stack.isEmpty()) roots += c else stack.last().children += c
                        stack += c
                    }
                }
                "END" -> {
                    if (overflow > 0) {
                        overflow--
                    } else {
                        val n = p.value.trim().uppercase(Locale.ROOT).take(64)
                        val idx = stack.indexOfLast { it.name == n }
                        if (idx >= 0) {
                            stack[idx].closed = true
                            while (stack.size > idx) stack.removeAt(stack.size - 1)
                        }
                    }
                }
                else -> if (overflow == 0 && stack.isNotEmpty()) {
                    if (++props > MAX_PROPERTIES) { stopped = true; return@forEachLine false }
                    stack.last().props += p
                }
            }
            true
        }
        return Tree(roots, stopped)
    }

    /** TEXT value (RFC 5545 §3.3.11): \n and \N are line breaks, \x is x. */
    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                out.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** A TEXT list split on unescaped commas. */
    private fun splitList(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                ',' -> { out += unescape(s.substring(start, i)); start = i + 1 }
            }
            i++
        }
        out += unescape(s.substring(minOf(start, s.length)))
        return out
    }

    // ------------------------------------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------------------------------------

    private sealed class When {
        class Day(val date: LocalDate) : When()
        class Utc(val at: Instant) : When()
        class Local(val time: LocalDateTime, val tzid: String?) : When()
    }

    private val DATE_TIME = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})?(Z)?)?$")

    private fun parseWhen(p: Prop): When? {
        val v = p.value.trim().uppercase(Locale.ROOT)
        if (v.length > 16) return null
        val m = DATE_TIME.matchEntire(v) ?: return null
        val g = m.groupValues
        return try {
            val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
            if (g[4].isEmpty()) return When.Day(date)
            val t = LocalDateTime.of(date, LocalTime.of(g[4].toInt(), g[5].toInt(), minOf(59, g[6].ifEmpty { "0" }.toInt())))
            if (g[7] == "Z") When.Utc(t.toInstant(ZoneOffset.UTC)) else When.Local(t, p.param("TZID")?.trim()?.takeIf { it.isNotEmpty() })
        } catch (_: Exception) {
            null
        }
    }

    /** A local DATE-TIME (or DATE, at midnight) as used inside a VTIMEZONE. */
    private fun localValue(v: String?): LocalDateTime? {
        val w = v?.let { parseWhen(Prop("DTSTART", emptyMap(), it)) } ?: return null
        return when (w) {
            is When.Day -> w.date.atStartOfDay()
            is When.Local -> w.time
            is When.Utc -> LocalDateTime.ofInstant(w.at, ZoneOffset.UTC)
        }
    }

    private val OFFSET = Regex("^([+-])(\\d{2})(\\d{2})(\\d{2})?$")

    private fun offset(v: String?): ZoneOffset? {
        val m = v?.trim()?.let { OFFSET.matchEntire(it) } ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return try {
            ZoneOffset.ofHoursMinutesSeconds(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt(), sign * m.groupValues[4].ifEmpty { "0" }.toInt())
        } catch (_: Exception) {
            null
        }
    }

    private val DURATION = Regex("^([+-])?P(?:(\\d{1,9})W)?(?:(\\d{1,9})D)?(?:T(?:(\\d{1,9})H)?(?:(\\d{1,9})M)?(?:(\\d{1,9})S)?)?$")

    /** A DURATION value in seconds, or null. */
    private fun durationSeconds(v: String): Long? {
        val s = v.trim().uppercase(Locale.ROOT)
        if (s.length > 64 || s.endsWith("T")) return null
        val m = DURATION.matchEntire(s) ?: return null
        val g = m.groupValues
        if ((2..6).all { g[it].isEmpty() }) return null
        fun n(i: Int) = g[i].ifEmpty { "0" }.toLong()
        val total = n(2) * 604_800 + n(3) * 86_400 + n(4) * 3_600 + n(5) * 60 + n(6)
        return if (g[1] == "-") -total else total
    }

    private val DAY_CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
    private val DAY_SPEC = Regex("^([+-]?\\d{1,2})?(SU|MO|TU|WE|TH|FR|SA)$")

    private const val MAX_ALARMS = Tasks.MAX_ALERTS

    private fun clip(s: String?, max: Int = 40): String {
        val t = s.orEmpty().replace('\n', ' ').replace('\r', ' ').trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }

    /** Common Windows (Outlook, Exchange) zone names: the Arab region first, then a few frequent ones (CLDR). */
    private val WINDOWS_ZONES = mapOf(
        "arab standard time" to "Asia/Riyadh",
        "arabic standard time" to "Asia/Baghdad",
        "arabian standard time" to "Asia/Dubai",
        "egypt standard time" to "Africa/Cairo",
        "jordan standard time" to "Asia/Amman",
        "syria standard time" to "Asia/Damascus",
        "middle east standard time" to "Asia/Beirut",
        "west bank standard time" to "Asia/Hebron",
        "israel standard time" to "Asia/Jerusalem",
        "libya standard time" to "Africa/Tripoli",
        "morocco standard time" to "Africa/Casablanca",
        "w. central africa standard time" to "Africa/Lagos",
        "sudan standard time" to "Africa/Khartoum",
        "e. africa standard time" to "Africa/Nairobi",
        "iran standard time" to "Asia/Tehran",
        "turkey standard time" to "Europe/Istanbul",
        "pakistan standard time" to "Asia/Karachi",
        "india standard time" to "Asia/Kolkata",
        "gmt standard time" to "Europe/London",
        "greenwich standard time" to "Atlantic/Reykjavik",
        "w. europe standard time" to "Europe/Berlin",
        "romance standard time" to "Europe/Paris",
        "central europe standard time" to "Europe/Budapest",
        "central european standard time" to "Europe/Warsaw",
        "russian standard time" to "Europe/Moscow",
        "eastern standard time" to "America/New_York",
        "central standard time" to "America/Chicago",
        "mountain standard time" to "America/Denver",
        "pacific standard time" to "America/Los_Angeles",
        "coordinated universal time" to "UTC",
    )

    /** The IANA zone named by [id], also as the tail of a longer path (/mozilla.org/20050126_1/America/New_York). */
    private fun iana(id: String): ZoneRules? {
        val t = id.trim().removePrefix("/")
        if (t.isEmpty() || t.length > 120) return null
        fun of(s: String): ZoneRules? = try { ZoneId.of(s).rules } catch (_: Exception) { null }
        of(t)?.let { return it }
        val segs = t.split('/')
        for (k in segs.size - 1 downTo 1) of(segs.takeLast(k).joinToString("/"))?.let { return it }
        return null
    }

    // ------------------------------------------------------------------------------------------------
    // The reader
    // ------------------------------------------------------------------------------------------------

    private class RuleMap(
        val repeat: Repeat,
        val days: List<Int> = emptyList(),
        val everyDays: Int = 2,
        val everyHours: Int = 1,
        /** For MONTHLY and YEARLY: the day of month the series keeps (29-31 fall back to the month's last day). */
        val anchorDay: Int = 0,
        val note: ((IcsItem) -> IcsNote)? = null,
    )

    private class Reader(val zone: ZoneId, val now: Instant, val calendars: List<Comp>, val stopped: Boolean) {
        val inputs = ArrayList<TaskInput>()
        val skipped = ArrayList<IcsNote>()
        val warnings = ArrayList<IcsNote>()
        val allDay = ArrayList<IcsItem>()
        val uids = HashSet<String>()
        val deviceRules: ZoneRules = zone.rules
        val timezones = HashMap<String, Comp>()
        val zones = HashMap<String, ZoneRules>()
        var tzBudget = TZ_BUDGET

        fun run(): IcsImportResult {
            val items = ArrayList<Comp>()
            val others = LinkedHashMap<String, Int>()
            for (cal in calendars) for (c in cal.children) when (c.name) {
                "VTIMEZONE" -> c.first("TZID")?.value?.trim()?.let { if (it.isNotEmpty()) timezones.putIfAbsent(it, c) }
                "VEVENT", "VTODO" -> items += c
                else -> others[c.name] = (others[c.name] ?: 0) + 1
            }
            if (stopped) warnings += IcsNote.TooBig(MAX_COMPONENTS, MAX_PROPERTIES)
            else if (calendars.any { !it.closed }) warnings += IcsNote.CutOff
            if (others.isNotEmpty()) {
                val named = LinkedHashMap<String, Int>()
                for ((k, v) in others) named.merge(clip(k, 20), v, Int::plus)
                warnings += IcsNote.NotItems(named)
            }
            for ((i, c) in items.withIndex()) {
                if (i >= MAX_ITEMS) {
                    warnings += IcsNote.TooMany(MAX_ITEMS, items.size - MAX_ITEMS)
                    break
                }
                item(c, i)
            }
            if (allDay.isNotEmpty()) warnings += IcsNote.AllDay(allDay.toList(), ALL_DAY_TIME)
            return IcsImportResult(inputs, skipped, warnings)
        }

        fun wall(at: Instant): LocalDateTime = at.atZone(zone).toLocalDateTime()

        fun text(c: Comp, name: String) = c.first(name)?.value?.let(::unescape).orEmpty()

        fun item(c: Comp, index: Int) {
            val desc = text(c, "DESCRIPTION").trim()
            var title = text(c, "SUMMARY").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()
            if (title.isEmpty()) title = desc.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val uid = c.first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() }
            val label = IcsItem(title.takeIf { it.isNotEmpty() }?.let { clip(it, 60) }, c.name == "VTODO", index + 1, if (title.isEmpty()) uid?.let { clip(it) } else null)
            if (!c.closed) { skipped += IcsNote.Incomplete(label); return }
            if (title.isEmpty()) { skipped += IcsNote.NoTitle(label); return }
            val status = c.first("STATUS")?.value?.trim()?.uppercase(Locale.ROOT)
            if (status == "CANCELLED") { skipped += IcsNote.Cancelled(label); return }
            if (status == "COMPLETED" || c.first("COMPLETED") != null || c.first("PERCENT-COMPLETE")?.value?.trim() == "100") {
                skipped += IcsNote.Completed(label); return
            }
            val recurrenceId = c.first("RECURRENCE-ID")
            if (uid != null && recurrenceId == null && !uids.add(uid)) { skipped += IcsNote.Duplicate(label); return }
            if (recurrenceId != null) warnings += IcsNote.ChangedOccurrence(label)

            if (title.length > 200) {
                title = title.take(if (Character.isHighSurrogate(title[199])) 199 else 200).trim()
                warnings += IcsNote.TitleShortened(label, 200)
            }
            var body = desc
            val location = text(c, "LOCATION").trim()
            if (location.isNotEmpty()) body = if (body.isEmpty()) location else "$body\n$location"
            if (body.length > 20_000) {
                body = body.take(20_000)
                warnings += IcsNote.BodyShortened(label, 20_000)
            }
            val priority = when (c.first("PRIORITY")?.value?.trim()?.toIntOrNull()) {
                1, 2, 3, 4 -> Priority.HIGH
                5 -> Priority.MEDIUM
                else -> Priority.NORMAL
            }
            val category = c.all("CATEGORIES").asSequence().flatMap { splitList(it.value).asSequence() }
                .map { it.trim().uppercase(Locale.ROOT) }
                .firstNotNullOfOrNull { code -> Category.entries.firstOrNull { it.code.uppercase(Locale.ROOT) == code } }
                ?: Category.PERSONAL

            // Time
            val timeProp = if (c.name == "VTODO") c.first("DUE") ?: c.first("DTSTART") else c.first("DTSTART")
            var dueAt: Instant? = null
            var local: LocalDateTime? = null
            var rules: ZoneRules = deviceRules
            var zoneName = zone.id
            var isAllDay = false
            if (timeProp != null) {
                val w = parseWhen(timeProp)
                if (w == null) { skipped += IcsNote.BadTime(label, clip(timeProp.value)); return }
                when (w) {
                    is When.Day -> {
                        isAllDay = true
                        local = w.date.atTime(ALL_DAY_TIME)
                    }
                    is When.Utc -> {
                        local = LocalDateTime.ofInstant(w.at, ZoneOffset.UTC)
                        rules = ZoneOffset.UTC.rules
                        zoneName = "UTC"
                    }
                    is When.Local -> {
                        local = w.time
                        if (w.tzid != null) {
                            rules = resolve(w.tzid)
                            zoneName = w.tzid
                        }
                    }
                }
                if (local.year !in 1800..2999) { skipped += IcsNote.OutOfRange(label, local.toLocalDate()); return }
                dueAt = if (w is When.Utc) w.at else toInstant(local, rules)
                if (isAllDay) allDay += label
            } else if (c.name == "VEVENT") {
                warnings += IcsNote.NoStart(label)
            }

            // Repeat
            var repeat = Repeat.NONE
            var anchorFrom: Instant? = null
            var days = emptyList<Int>()
            var everyDays = 2
            var everyHours = 1
            val rrules = c.all("RRULE")
            var problems: List<IcsRulePart> = emptyList()
            if (rrules.isEmpty()) {
                if (dueAt != null && c.first("RDATE") != null) warnings += IcsNote.ExtraDatesOnly(label)
            } else if (dueAt == null) {
                warnings += IcsNote.RepeatWithoutTime(label)
            } else {
                val list = ArrayList<IcsRulePart>()
                if (rrules.size > 1) list += IcsRulePart.SeveralRules(rrules.size)
                // Remember Me's own export: a series whose current time is not one the rule gives (a reminder on
                // chosen weekdays that starts on another day) has DTSTART at its first rule time and the current
                // time as one RDATE before it. That one RDATE is the reminder's time, not an extra date.
                val rdates = c.all("RDATE")
                val earlyRdate: Instant? = if (rdates.size == 1 && c.first("EXRULE") == null && c.first("EXDATE") == null &&
                    !rdates[0].value.contains(',')) {
                    when (val rw = parseWhen(rdates[0])) {
                        is When.Utc -> rw.at
                        is When.Local -> toInstant(rw.time, if (rw.tzid != null) resolve(rw.tzid) else rules)
                        else -> null
                    }?.takeIf { it.isBefore(dueAt) }
                } else null
                for (k in listOf("EXRULE", "RDATE", "EXDATE")) if (c.first(k) != null && !(k == "RDATE" && earlyRdate != null)) list += IcsRulePart.Extra(k)
                val (map, why) = mapRule(rrules[0].value, local!!, isAllDay)
                list += why
                problems = list
                if (map != null && list.isEmpty()) {
                    val ruleStart = dueAt
                    if (earlyRdate != null) dueAt = earlyRdate
                    repeat = map.repeat
                    everyDays = map.everyDays
                    everyHours = map.everyHours
                    // BYDAY is in the event's own zone; the app counts weekdays in this phone's zone.
                    val shift = ChronoUnit.DAYS.between(local.toLocalDate(), ruleStart.atZone(zone).toLocalDate()).toInt()
                    days = map.days.map { Math.floorMod(it + shift, 7) }.distinct().sorted()
                    map.note?.let { warnings += it(label) }
                    // BYMONTHDAY=-1 means day 31 for a month; a yearly series' month may be shorter (February: 29).
                    val anchor = if (repeat == Repeat.YEARLY) minOf(map.anchorDay, local.month.maxLength()) else map.anchorDay
                    if ((repeat == Repeat.MONTHLY || repeat == Repeat.YEARLY) && anchor > local.dayOfMonth) {
                        anchorFrom = monthAnchor(local, rules, repeat, anchor)
                    }
                    // An every-N-hours series counts elapsed hours, whatever the zone's clock does (Ics.export writes it in UTC).
                    if (repeat != Repeat.HOURS && rules != deviceRules && clockDiffers(rules, maxOf(dueAt, now))) {
                        warnings += IcsNote.ClockDiffers(label, clip(zoneName), zone.id)
                    }
                }
            }
            if (dueAt != null && repeat == Repeat.NONE && !dueAt.isAfter(now)) {
                skipped += IcsNote.Passed(label, wall(dueAt), problems)
                return
            }
            if (problems.isNotEmpty()) warnings += IcsNote.RuleNotKept(label, problems)

            // Alarms: up to 3 early alerts, the nearest ones (F-046).
            var early = emptyList<Int>()
            val alarms = c.children.filter { it.name == "VALARM" }
            if (alarms.isNotEmpty() && dueAt == null) {
                warnings += IcsNote.AlarmsWithoutTime(label)
            } else if (alarms.isNotEmpty()) {
                val found = sortedSetOf<Int>()
                for (a in alarms) {
                    when (val r = alarmMinutes(a, dueAt!!)) {
                        is IcsAlarm -> warnings += IcsNote.AlarmNotKept(label, r)
                        is Int -> if (r > 0) found += r
                    }
                }
                early = found.take(MAX_ALARMS)
                if (found.size > MAX_ALARMS) warnings += IcsNote.TooManyAlarms(label, early, found.drop(MAX_ALARMS))
            }
            val input = TaskInput(
                title = title, body = body, priority = priority, category = category, dueAt = dueAt,
                repeat = repeat, days = days, everyDays = everyDays, everyHours = everyHours,
                remindBefore = early.firstOrNull() ?: 0, alerts = early.takeIf { it.size >= 2 },
            )
            anchorFrom?.let { anchors[input] = it }
            inputs += input
        }

        /** Minutes before the start (0 = at the start), or the [IcsAlarm] this app cannot keep. */
        fun alarmMinutes(a: Comp, dueAt: Instant): Any {
            // ACTION:NONE (RFC 9074) is an alarm that does nothing, such as Apple's default-alarm placeholder.
            if (a.first("ACTION")?.value?.trim()?.uppercase(Locale.ROOT) == "NONE") return 0
            val trig = a.first("TRIGGER") ?: return IcsAlarm.NoTrigger
            val seconds: Long
            if (trig.param("VALUE")?.uppercase(Locale.ROOT) == "DATE-TIME") {
                val at = when (val w = parseWhen(trig)) {
                    is When.Utc -> w.at
                    is When.Local -> toInstant(w.time, w.tzid?.let(::resolve) ?: deviceRules)
                    else -> null
                } ?: return IcsAlarm.BadTime(clip(trig.value))
                seconds = dueAt.epochSecond - at.epochSecond
            } else {
                if (trig.param("RELATED")?.uppercase(Locale.ROOT) == "END") return IcsAlarm.FromEnd(clip(trig.value))
                seconds = -(durationSeconds(trig.value) ?: return IcsAlarm.BadTrigger(clip(trig.value)))
            }
            if (seconds == 0L) return 0
            if (seconds < 0) return IcsAlarm.AfterStart(-seconds)
            if (seconds % 60 != 0L || seconds / 60 !in EARLY_MINUTES.map { it.toLong() }) return IcsAlarm.NotOffered(seconds)
            return (seconds / 60).toInt()
        }

        fun toInstant(t: LocalDateTime, r: ZoneRules): Instant {
            if (r === deviceRules) return localToInstant(t, zone)
            return try {
                val offsets = r.getValidOffsets(t)
                if (offsets.isNotEmpty()) t.toInstant(offsets[0]) else t.toInstant(r.getTransition(t)?.offsetBefore ?: r.getOffset(t))
            } catch (_: Exception) {
                localToInstant(t, zone)
            }
        }

        fun clockDiffers(r: ZoneRules, from: Instant): Boolean = try {
            fun diff(t: Instant) = deviceRules.getOffset(t).totalSeconds - r.getOffset(t).totalSeconds
            val first = diff(from)
            (1..26).any { diff(from.plusSeconds(it * 14L * 86_400)) != first }
        } catch (_: Exception) {
            false
        }

        /**
         * A month-end or 29 February series whose current occurrence is clamped (DTSTART 28 February with
         * BYMONTHDAY=28,29,30,31;BYSETPOS=-1, or BYMONTHDAY=-1 on a short month's last day): DTSTART is itself an
         * occurrence, so it stays the reminder's time, and the series is anchored on the latest earlier date that
         * has the real [day] (the 31st, 29 February), at the same time, as the app's own series is after the 31st
         * passed through a short month. Null when there is none in the last 12 months (8 years for a yearly one).
         */
        fun monthAnchor(local: LocalDateTime, r: ZoneRules, repeat: Repeat, day: Int): Instant? {
            val ym = YearMonth.from(local)
            for (k in 1L..(if (repeat == Repeat.MONTHLY) 12L else 8L)) {
                val m = if (repeat == Repeat.MONTHLY) ym.minusMonths(k) else ym.minusYears(k)
                if (m.lengthOfMonth() >= day) return toInstant(m.atDay(day).atTime(local.toLocalTime()), r)
            }
            return null
        }

        /** The rules for a TZID: the file's VTIMEZONE (or the IANA zone when they agree), the IANA id, a Windows name, or this phone's zone. */
        fun resolve(tzid: String): ZoneRules = zones[tzid] ?: resolveNew(tzid).also { zones[tzid] = it }

        fun resolveNew(tzid: String): ZoneRules {
            val known = iana(tzid) ?: WINDOWS_ZONES[tzid.trim().lowercase(Locale.ROOT)]?.let(::iana)
            val tz = timezones[tzid] ?: timezones[tzid.trim()]
            if (tz != null && zones.size < MAX_ZONES) {
                val built = try { vtimezone(tz) } catch (_: Exception) { null }
                if (built != null) {
                    val (custom, transitions) = built
                    val agrees = known != null && try {
                        transitions.all { known.getOffset(it.instant) == it.offsetAfter } && known.getOffset(now) == custom.getOffset(now)
                    } catch (_: Exception) {
                        false
                    }
                    return if (agrees) known else custom
                }
            }
            if (known != null) return known
            warnings += IcsNote.UnknownZone(clip(tzid), zone.id)
            return deviceRules
        }

        /** A VTIMEZONE as zone rules (transitions from 80 years ago to 50 years ahead) and its transitions, or null. */
        fun vtimezone(tz: Comp): Pair<ZoneRules, List<ZoneOffsetTransition>>? {
            val obs = tz.children.filter { it.name == "STANDARD" || it.name == "DAYLIGHT" }
            if (obs.isEmpty() || obs.size > 64 || tzBudget <= 0) return null
            val year = now.atOffset(ZoneOffset.UTC).year
            class Onset(val at: Instant, val from: ZoneOffset, val to: ZoneOffset)
            val onsets = ArrayList<Onset>()
            for (o in obs) {
                val start = localValue(o.first("DTSTART")?.value) ?: return null
                val from = offset(o.first("TZOFFSETFROM")?.value) ?: return null
                val to = offset(o.first("TZOFFSETTO")?.value) ?: return null
                val locals = arrayListOf(start)
                for (r in o.all("RDATE")) for (v in r.value.split(',')) {
                    localValue(v.substringBefore('/'))?.let { locals += it }
                    if (locals.size > 1_000) return null
                }
                val rr = o.all("RRULE")
                if (rr.size > 1) return null
                if (rr.size == 1) locals += expandYearly(rr[0].value, start, from, year - 80, year + 50) ?: return null
                tzBudget -= locals.size
                if (tzBudget < 0) return null
                for (l in locals) onsets += Onset(l.toInstant(from), from, to)
            }
            onsets.sortBy { it.at }
            val base = onsets.first().from
            var current = base
            val transitions = ArrayList<ZoneOffsetTransition>()
            for (o in onsets) {
                if (o.to == current) continue
                val last = transitions.lastOrNull()
                if (last != null && o.at.epochSecond - last.instant.epochSecond < 2 * 86_400) continue
                transitions += ZoneOffsetTransition.of(LocalDateTime.ofEpochSecond(o.at.epochSecond, 0, current), current, o.to)
                current = o.to
            }
            val rules = if (transitions.isEmpty()) ZoneRules.of(base) else ZoneRules.of(base, base, emptyList(), transitions, emptyList())
            return rules to transitions
        }

        /** The onsets of a yearly observance RRULE after its DTSTART, for the years [minYear]..[maxYear]; null when not understood. */
        fun expandYearly(value: String, start: LocalDateTime, from: ZoneOffset, minYear: Int, maxYear: Int): List<LocalDateTime>? {
            val parts = HashMap<String, String>()
            for (seg in value.split(';')) {
                if (seg.isBlank()) continue
                val eq = seg.indexOf('=')
                if (eq <= 0) return null
                parts[seg.substring(0, eq).trim().uppercase(Locale.ROOT)] = seg.substring(eq + 1).trim().uppercase(Locale.ROOT)
            }
            if (parts["FREQ"] != "YEARLY" || (parts["INTERVAL"] ?: "1") != "1") return null
            if (parts.keys.any { it !in setOf("FREQ", "INTERVAL", "BYMONTH", "BYDAY", "BYMONTHDAY", "UNTIL", "COUNT", "WKST") }) return null
            val month = parts["BYMONTH"]?.let { it.toIntOrNull()?.takeIf { m -> m in 1..12 } ?: return null } ?: start.monthValue
            val byday = parts["BYDAY"]?.let { DAY_SPEC.matchEntire(it) ?: return null }
            val ordinal = byday?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { n -> n != 0 && n in -5..5 } ?: return null }
            val dow = byday?.let { DayOfWeek.of((DAY_CODES.indexOf(it.groupValues[2]) + 6) % 7 + 1) }
            val monthDays = parts["BYMONTHDAY"]?.split(',')?.map { it.trim().toIntOrNull()?.takeIf { d -> d != 0 && d in -31..31 } ?: return null }
            if (dow != null && ordinal == null && monthDays == null) return null
            val untilRaw = parts["UNTIL"]
            val until: Any? = untilRaw?.let { u -> parseWhen(Prop("UNTIL", emptyMap(), u)) ?: return null }
            val count = parts["COUNT"]?.let { it.toIntOrNull()?.takeIf { c -> c >= 1 } ?: return null } ?: Int.MAX_VALUE
            if (start.year < 1000 || start.year > 3000) return null
            val out = ArrayList<LocalDateTime>()
            var n = 1 // DTSTART is the first onset
            for (y in start.year..maxYear) {
                if (n >= count) break
                val ym = YearMonth.of(y, month)
                val len = ym.lengthOfMonth()
                val date: LocalDate? = when {
                    dow != null && ordinal != null -> if (ordinal > 0) {
                        ym.atDay(1).with(TemporalAdjusters.nextOrSame(dow)).plusWeeks(ordinal - 1L)
                    } else {
                        ym.atEndOfMonth().with(TemporalAdjusters.previousOrSame(dow)).minusWeeks(-ordinal - 1L)
                    }.takeIf { it.monthValue == month }
                    dow != null -> monthDays!!.map { if (it > 0) it else len + 1 + it }.filter { it in 1..len }.sorted()
                        .map { ym.atDay(it) }.firstOrNull { it.dayOfWeek == dow }
                    else -> (monthDays?.first() ?: start.dayOfMonth).let { if (it > 0) it else len + 1 + it }.takeIf { it in 1..len }?.let { ym.atDay(it) }
                }
                if (date == null) continue
                val at = LocalDateTime.of(date, start.toLocalTime())
                if (!at.isAfter(start)) continue
                val within = when (until) {
                    is When.Utc -> !at.toInstant(from).isAfter(until.at)
                    is When.Local -> !at.isAfter(until.time)
                    is When.Day -> !at.toLocalDate().isAfter(until.date)
                    else -> true
                }
                if (!within) break
                n++
                if (y >= minYear) out += at
            }
            return out
        }
    }

    // ------------------------------------------------------------------------------------------------
    // RRULE → Repeat
    // ------------------------------------------------------------------------------------------------

    /**
     * Maps an RRULE to this app's repeat, judged at the event's local start [start]. Returns the mapping and
     * the parts that cannot be kept exactly (the mapping is used only when that list is empty).
     */
    private fun mapRule(value: String, start: LocalDateTime, allDay: Boolean): Pair<RuleMap?, List<IcsRulePart>> {
        val problems = ArrayList<IcsRulePart>()
        val parts = LinkedHashMap<String, String>()
        for (seg in value.split(';')) {
            if (seg.isBlank()) continue
            val eq = seg.indexOf('=')
            if (eq <= 0) { problems += IcsRulePart.Garbled(clip(seg, 20)); continue }
            val k = seg.substring(0, eq).trim().uppercase(Locale.ROOT)
            val v = seg.substring(eq + 1).trim().uppercase(Locale.ROOT)
            if (k in parts) problems += IcsRulePart.Twice(clip(k, 20)) else parts[k] = v
        }
        for ((k, v) in parts) when (k) {
            "FREQ", "INTERVAL", "WKST", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS" -> Unit
            else -> problems += IcsRulePart.Unsupported(clip(k, 20), clip(v, 20))
        }
        val freq = parts["FREQ"]
        if (freq == null) problems += IcsRulePart.NoFreq
        val interval = parts["INTERVAL"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: run { problems += IcsRulePart.BadValue("INTERVAL", clip(it, 12)); 1 } } ?: 1
        val bydayRaw = parts["BYDAY"]
        val byday: List<Int>? = bydayRaw?.let { raw ->
            val list = raw.split(',').map { it.trim() }
            if (list.all { it in DAY_CODES }) list.map { DAY_CODES.indexOf(it) }.distinct().sorted()
            else { problems += IcsRulePart.BadValue("BYDAY", clip(raw, 20)); null }
        }
        val monthDays = parts["BYMONTHDAY"]?.let { raw ->
            raw.split(',').map { it.trim().toIntOrNull()?.takeIf { d -> d != 0 && d in -31..31 } }.let { l ->
                if (l.any { it == null }) { problems += IcsRulePart.BadValue("BYMONTHDAY", clip(raw, 20)); null } else l.filterNotNull()
            }
        }
        val setpos = parts["BYSETPOS"]
        val bymonth = parts["BYMONTH"]
        fun none(vararg keys: String) { for (k in keys) parts[k]?.let { problems += IcsRulePart.NotWithFreq(k, clip(it, 20), freq.orEmpty()) } }
        val map: RuleMap? = when (freq) {
            null -> null
            "HOURLY" -> {
                none("BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS")
                if (allDay) problems += IcsRulePart.HourlyAllDay
                if (interval !in 1..23) problems += IcsRulePart.EveryHours(interval)
                RuleMap(Repeat.HOURS, everyHours = interval.coerceIn(1, 23))
            }
            "DAILY" -> {
                none("BYMONTHDAY", "BYMONTH", "BYSETPOS")
                when {
                    bydayRaw != null -> {
                        if (interval != 1) problems += IcsRulePart.EveryDaysOnDays(interval)
                        RuleMap(Repeat.DAYS, days = byday.orEmpty())
                    }
                    interval == 1 -> RuleMap(Repeat.DAILY)
                    interval <= 365 -> RuleMap(Repeat.INTERVAL, everyDays = interval)
                    else -> { problems += IcsRulePart.EveryDays(interval); null }
                }
            }
            "WEEKLY" -> {
                none("BYMONTHDAY", "BYMONTH", "BYSETPOS")
                when {
                    interval == 1 -> if (bydayRaw != null) RuleMap(Repeat.DAYS, days = byday.orEmpty()) else RuleMap(Repeat.WEEKLY)
                    bydayRaw != null && byday != listOf(jsDay(start.toLocalDate())) -> { problems += IcsRulePart.EveryWeeksOnDays(interval, clip(bydayRaw, 20)); null }
                    interval * 7 <= 365 -> RuleMap(Repeat.INTERVAL, everyDays = interval * 7)
                    else -> { problems += IcsRulePart.EveryWeeks(interval); null }
                }
            }
            "MONTHLY", "YEARLY" -> {
                val yearly = freq == "YEARLY"
                if (interval != 1) problems += IcsRulePart.EveryMonthsOrYears(yearly, interval)
                none("BYDAY")
                if (!yearly) none("BYMONTH")
                else if (bymonth != null && bymonth.toIntOrNull() != start.monthValue) problems += IcsRulePart.OtherMonth(clip(bymonth, 12), start.monthValue)
                val day = anchorDay(monthDays, setpos, start, problems)
                val note: ((IcsItem) -> IcsNote)? = when {
                    day == 0 -> null
                    !yearly && day > 28 && monthDays == null || !yearly && monthDays == listOf(day) && day > 28 -> { item -> IcsNote.MonthEnd(item, day) }
                    yearly && start.monthValue == 2 && day == 29 && (monthDays == null || monthDays == listOf(29)) -> { item -> IcsNote.LeapDay(item) }
                    else -> null
                }
                RuleMap(if (yearly) Repeat.YEARLY else Repeat.MONTHLY, anchorDay = day, note = note)
            }
            else -> { problems += IcsRulePart.Freq(clip(freq, 12)); null }
        }
        return map to problems
    }

    /** The day of month a MONTHLY/YEARLY rule keeps, judged against the start; 0 (and a problem) when it cannot be kept. */
    private fun anchorDay(monthDays: List<Int>?, setpos: String?, start: LocalDateTime, problems: MutableList<IcsRulePart>): Int {
        val day = start.dayOfMonth
        val last = YearMonth.from(start).lengthOfMonth()
        if (monthDays == null) {
            if (setpos != null) problems += IcsRulePart.SetPos(clip(setpos, 12))
            return day
        }
        // Ics.export's month end: BYMONTHDAY=28,...,D;BYSETPOS=-1 (the D-th, or the month's last day if shorter).
        if (setpos != null) {
            val d = monthDays.maxOrNull() ?: 0
            if (setpos == "-1" && d in 29..31 && monthDays.sorted() == (28..d).toList() && (day == d || (day < d && day == last && day >= 28))) return d
            problems += IcsRulePart.MonthDays(monthDays, clip(setpos, 12), day)
            return 0
        }
        if (monthDays.size == 1) {
            val d = monthDays[0]
            if (d == day) return d
            if (d == -1 && day == last) return 31
        }
        problems += IcsRulePart.MonthDays(monthDays, null, day)
        return 0
    }
}
