package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.withExt

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.T
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.at
import com.fastkiwi.rememberme.core.nextOccurrence
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.component.VToDo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules

/**
 * The iCalendar export, checked with an independent RFC 5545 implementation (ical4j, test only): it
 * parses our file and expands each RRULE, and the occurrences must equal the app's own nextOccurrence.
 */
class IcsTest {
    @Before fun ids() = T.ids()

    private fun parse(text: String): Calendar = CalendarBuilder().build(StringReader(text))

    @Test fun structureEscapingAndFolding() {
        val zone = ZoneId.of("Asia/Baghdad")
        val now = at(zone, 2026, 11, 14, 10)
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "دفع الإيجار; بسرعة, الآن", body = "سطر\nثاني", dueAt = at(zone, 2026, 11, 30, 9), repeat = Repeat.MONTHLY, priority = Priority.HIGH, remindBefore = 60), now, zone).first
        s = Tasks.save(s, TaskInput(title = "بلا وقت " + "طويل جدًا ".repeat(12)), now, zone).first
        val (s2, done) = Tasks.save(s, TaskInput(title = "done", dueAt = now), now, zone)
        s = Tasks.complete(s2, done.id, now, zone)!!.first
        val text = Ics.export(s, zone, now)
        for (line in text.split("\r\n")) assertTrue("line over 75 octets: $line", line.toByteArray(Charsets.UTF_8).size <= 75)
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertTrue(text.contains("SUMMARY:دفع الإيجار\\; بسرعة\\, الآن"))
        assertTrue(text.contains("DESCRIPTION:سطر\\nثاني"))
        val cal = parse(text)
        assertEquals(1, cal.getComponents<VEvent>("VEVENT").size)
        assertEquals(1, cal.getComponents<VToDo>("VTODO").size)
        val ev = cal.getComponents<VEvent>("VEVENT")[0]
        assertEquals("دفع الإيجار; بسرعة, الآن", ev.summary.get().value)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=28,29,30;BYSETPOS=-1", ev.getProperty<net.fortuna.ical4j.model.property.RRule<*>>("RRULE").get().value)
        assertEquals(2, ev.alarms.size)
    }

    /** Expands the exported RRULE with ical4j and compares with nextOccurrence, for each repeat mode. */
    @Test fun rrulesExpandToTheSameOccurrencesAsTheApp() {
        val cases = listOf(
            "Asia/Baghdad" to TaskInput(title = "d", dueAt = null, repeat = Repeat.DAILY),
            "Europe/Berlin" to TaskInput(title = "w", repeat = Repeat.WEEKLY),
            "Asia/Riyadh" to TaskInput(title = "sun-thu", repeat = Repeat.DAYS, days = listOf(0, 1, 2, 3, 4)),
            "America/New_York" to TaskInput(title = "i", repeat = Repeat.INTERVAL, everyDays = 3),
            "Asia/Baghdad" to TaskInput(title = "h", repeat = Repeat.HOURS, everyHours = 5),
            "Asia/Baghdad" to TaskInput(title = "m31", repeat = Repeat.MONTHLY),
            "Europe/Berlin" to TaskInput(title = "y29", repeat = Repeat.YEARLY),
        )
        val starts = listOf(
            intArrayOf(2026, 11, 14, 9, 0), intArrayOf(2027, 3, 1, 2, 30), intArrayOf(2026, 10, 22, 9, 0), intArrayOf(2027, 3, 12, 2, 30),
            intArrayOf(2026, 11, 14, 8, 0), intArrayOf(2027, 1, 31, 9, 0), intArrayOf(2028, 2, 29, 8, 0),
        )
        for ((i, pair) in cases.withIndex()) {
            val zone = ZoneId.of(pair.first)
            val (y, m, d, h, mi) = starts[i].toList()
            val due = at(zone, y, m, d, h, mi)
            val (s, t) = Tasks.save(AppState(), pair.second.copy(dueAt = due), due.minusSeconds(60), zone)
            val ev = parse(Ics.export(s, zone, due)).getComponents<VEvent>("VEVENT").single()
            // The app's own sequence of occurrences.
            val expected = ArrayList<Instant>()
            var cur = due
            expected += cur
            repeat(14) { cur = nextOccurrence(cur, t.repeat, cur, t.rule, zone); expected += cur }
            // ical4j's expansion of the same rule.
            val end = ZonedDateTime.ofInstant(cur.plusSeconds(1), zone)
            val period = Period<ZonedDateTime>(ZonedDateTime.ofInstant(due.minusSeconds(1), zone), end)
            val got = ev.calculateRecurrenceSet<Temporal>(period).map { p -> instant(p.start) }.sorted()
            assertEquals("${pair.second.title} in ${pair.first}", expected, got)
        }
    }

    private fun instant(t: Any): Instant = when (t) {
        is ZonedDateTime -> t.toInstant()
        is java.time.OffsetDateTime -> t.toInstant()
        is Instant -> t
        else -> error("unexpected temporal ${t::class}")
    }

    @Test fun timeZonesHaveTheirTransitions() {
        val now = Instant.parse("2026-11-14T07:00:00Z")
        val berlin = Ics.vtimezone(ZoneId.of("Europe/Berlin"), now)
        assertTrue(berlin.contains("BEGIN:DAYLIGHT"))
        assertTrue(berlin.contains("TZOFFSETTO:+0200"))
        // EU rule: last Sunday of March and of October.
        assertTrue(berlin.toString(), berlin.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=25,26,27,28,29,30,31;BYDAY=SU"))
        assertTrue(berlin.contains("RRULE:FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=25,26,27,28,29,30,31;BYDAY=SU"))
        // US rule: second Sunday of March, first Sunday of November.
        val ny = Ics.vtimezone(ZoneId.of("America/New_York"), now)
        assertTrue(ny.toString(), ny.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=8,9,10,11,12,13,14;BYDAY=SU"))
        assertTrue(ny.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=1,2,3,4,5,6,7;BYDAY=SU"))
        val baghdad = Ics.vtimezone(ZoneId.of("Asia/Baghdad"), now)
        assertEquals(listOf("BEGIN:VTIMEZONE", "TZID:Asia/Baghdad", "BEGIN:STANDARD", "DTSTART:20251113T100000", "TZOFFSETFROM:+0300", "TZOFFSETTO:+0300", "END:STANDARD", "END:VTIMEZONE"), baghdad)
    }

    /**
     * Onsets of a VTIMEZONE read by the letter of RFC 5545 §3.6.5: DTSTART local time in TZOFFSETFROM, its RRULE
     * expanded by ical4j's Recur; the offset at an instant is the TZOFFSETTO of the latest onset at or before it.
     */
    private fun onsets(lines: List<String>, until: Instant): List<Pair<Instant, ZoneOffset>> {
        val f = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        fun off(v: String) = ZoneOffset.of(v.substring(0, 3) + ":" + v.substring(3, 5) + (if (v.length > 5) ":" + v.substring(5) else ""))
        val out = ArrayList<Pair<Instant, ZoneOffset>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i] != "BEGIN:STANDARD" && lines[i] != "BEGIN:DAYLIGHT") { i++; continue }
            val e = (i until lines.size).first { lines[it] == lines[i].replace("BEGIN", "END") }
            val block = lines.subList(i, e + 1)
            fun v(k: String) = block.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')
            val start = LocalDateTime.parse(v("DTSTART")!!, f)
            val starts = v("RRULE")?.let { Recur<LocalDateTime>(it).getDates(start, start, LocalDateTime.ofInstant(until, ZoneOffset.UTC).plusDays(2)) } ?: listOf(start)
            for (st in starts) out += st.atOffset(off(v("TZOFFSETFROM")!!)).toInstant() to off(v("TZOFFSETTO")!!)
            i = e + 1
        }
        return out.sortedBy { it.first }
    }

    /** Java's offsets and the VTIMEZONE's (RFC reading) agree weekly, and around every transition, for [years]. */
    private fun assertExact(id: String, rules: ZoneRules, now: Instant, years: Long, lines: List<String> = Ics.vtimezone(id, rules, now)) {
        val from = now.minusSeconds(360L * 86_400)
        val end = now.plusSeconds(years * 366 * 86_400)
        val samples = ArrayList<Instant>()
        var t = from
        while (t.isBefore(end)) { samples += t; t = t.plusSeconds(7L * 86_400 + 3_600) }
        generateSequence(rules.nextTransition(from)) { rules.nextTransition(it.instant) }.takeWhile { it.instant.isBefore(end) }
            .forEach { samples += it.instant.minusSeconds(3_600); samples += it.instant; samples += it.instant.plusSeconds(3_600) }
        val on = onsets(lines, end)
        for (at in samples) assertEquals("$id at $at", rules.getOffset(at), on.lastOrNull { !it.first.isAfter(at) }?.second)
        // ical4j reads it too.
        val text = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"
        assertEquals(1, parse(text).getComponents<VTimeZone>("VTIMEZONE").size)
    }

    /**
     * Release torture findings (S2, S3): a "weekday on or after day N" window past the month end (Cairo's Friday
     * on or after 26 October), rules in UTC whose local day is the day before (Nuuk, Scoresbysund, Easter
     * Island), predicted transitions listed one by one (Gaza, Hebron, to 2087), a zone without yearly rules
     * (Casablanca), 30-minute DST (Lord Howe), changes at local midnight (Santiago).
     */
    @Test fun vtimezoneIsExactForTheTrickyZones() {
        val now = Instant.parse("2026-11-15T06:00:00Z")
        for (id in listOf("Africa/Cairo", "America/Nuuk", "America/Scoresbysund", "Pacific/Easter", "Asia/Gaza", "Asia/Hebron",
            "Africa/Casablanca", "Australia/Lord_Howe", "America/Santiago", "Europe/Dublin", "Europe/Berlin", "America/New_York", "Asia/Tehran")) {
            assertExact(id, ZoneId.of(id).rules, now, 70)
        }
        // Cairo's October rule gives Friday 1 November in the years where 26 to 31 October has no Friday.
        val cairo = Ics.vtimezone(ZoneId.of("Africa/Cairo"), now)
        assertTrue(cairo.toString(), cairo.contains("RRULE:FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=26,27,28,29,30,31;BYDAY=FR"))
        assertTrue(cairo.toString(), cairo.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=1;BYDAY=FR"))
        // Nuuk's March change is at 01:00 UTC on a Sunday: 23:00 on the Saturday before, local time.
        val nuuk = Ics.vtimezone(ZoneId.of("America/Nuuk"), now)
        assertTrue(nuuk.toString(), nuuk.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=24,25,26,27,28,29,30;BYDAY=SA"))
    }

    /** Every zone of java.time: a yearly RRULE form for each of its rules, and a VTIMEZONE exact for 30 years. */
    @Test fun everyZoneHasAnExactVTimezone() {
        val now = Instant.parse("2026-11-15T06:00:00Z")
        for (id in ZoneId.getAvailableZoneIds().filter { !it.startsWith("ical4j") }.sorted()) {
            val rules = ZoneId.of(id).rules
            for (r in rules.transitionRules) assertNotNull("$id $r", Ics.yearlyForm(r))
            assertExact(id, rules, now, 30)
        }
    }

    /** A rule whose 7-day window crosses the end of February is written with BYYEARDAY (leap years move it). */
    @Test fun aWindowAcrossTheEndOfFebruaryUsesYearDays() {
        val std = ZoneOffset.ofHours(-3)
        val dst = ZoneOffset.ofHours(-2)
        val rules = ZoneRules.of(ZoneOffset.ofHours(-4), ZoneOffset.ofHours(-4), emptyList(),
            listOf(ZoneOffsetTransition.of(LocalDateTime.of(1999, 1, 3, 2, 0), ZoneOffset.ofHours(-4), std)),
            listOf(
                // Sunday on or after 25 February, 02:00 wall time: 25 February to 2 or 3 March.
                ZoneOffsetTransitionRule.of(Month.FEBRUARY, 25, DayOfWeek.SUNDAY, LocalTime.of(2, 0), false, ZoneOffsetTransitionRule.TimeDefinition.WALL, std, std, dst),
                // Sunday on or after 25 October, 01:00 UTC: 23:00 on the Saturday before, local time.
                ZoneOffsetTransitionRule.of(Month.OCTOBER, 25, DayOfWeek.SUNDAY, LocalTime.of(1, 0), false, ZoneOffsetTransitionRule.TimeDefinition.UTC, std, dst, std),
            ))
        for (r in rules.transitionRules) assertNotNull("$r", Ics.yearlyForm(r))
        val now = Instant.parse("2026-11-15T06:00:00Z")
        val lines = Ics.vtimezone("Test/Zone", rules, now)
        assertTrue(lines.toString(), lines.contains("RRULE:FREQ=YEARLY;BYYEARDAY=56,57,58,59,60,61,62;BYDAY=SU"))
        assertTrue(lines.toString(), lines.contains("RRULE:FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=24,25,26,27,28,29,30;BYDAY=SA"))
        assertExact("Test/Zone", rules, now, 60, lines)
    }

    /**
     * A "chosen weekdays" reminder set for another weekday rings first on that day (the app's own series): DTSTART
     * is the series' next occurrence and the first time an RDATE, so every RFC 5545 reader gets the same dates.
     */
    @Test fun aWeekdaysReminderStartingOnAnotherDayKeepsItsFirstTime() {
        val zone = ZoneId.of("Asia/Baghdad")
        val due = at(zone, 2026, 11, 16, 9) // a Monday; the rule is Sunday and Wednesday
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "x", dueAt = due, repeat = Repeat.DAYS, days = listOf(0, 3)), due.minusSeconds(3600), zone)
        val text = Ics.export(s, zone, due.minusSeconds(3600))
        assertTrue(text, text.contains("DTSTART;TZID=Asia/Baghdad:20261118T090000\r\n"))
        assertTrue(text, text.contains("RDATE;TZID=Asia/Baghdad:20261116T090000\r\n"))
        val ev = parse(text).getComponents<VEvent>("VEVENT").single()
        val expected = ArrayList<Instant>()
        var cur = due
        expected += cur
        repeat(10) { cur = nextOccurrence(cur, t.repeat, cur, t.rule, zone); expected += cur }
        val got = ev.calculateRecurrenceSet<Temporal>(Period<ZonedDateTime>(ZonedDateTime.ofInstant(due.minusSeconds(1), zone), ZonedDateTime.ofInstant(cur.plusSeconds(1), zone)))
            .map { p -> instant(p.start) }.sorted()
        assertEquals(expected, got)
    }

    /** A daily 02:30 reminder whose current occurrence is on the spring-forward night (03:30) keeps 02:30 as its time. */
    @Test fun aSeriesInADstGapKeepsItsTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val first = at(zone, 2027, 3, 27, 2, 30)
        val (s0, t0) = Tasks.save(AppState(), TaskInput(title = "x", dueAt = first, repeat = Repeat.DAILY), first.minusSeconds(3600), zone)
        val (s, _) = Tasks.complete(s0, t0.id, first.plusSeconds(60), zone)!!
        val t = s.task(t0.id)!!
        assertEquals(at(zone, 2027, 3, 28, 3, 30), t.scheduleAt)
        val text = Ics.export(s, zone, first.plusSeconds(120))
        assertTrue(text, text.contains("DTSTART;TZID=Europe/Berlin:20270328T023000\r\n"))
        val ev = parse(text).getComponents<VEvent>("VEVENT").single()
        val expected = ArrayList<Instant>()
        var cur = t.scheduleAt!!
        expected += cur
        repeat(5) { cur = nextOccurrence(cur, t.repeat, cur, t.rule, zone); expected += cur }
        val got = ev.calculateRecurrenceSet<Temporal>(Period<ZonedDateTime>(ZonedDateTime.ofInstant(expected[0].minusSeconds(1), zone), ZonedDateTime.ofInstant(cur.plusSeconds(1), zone)))
            .map { p -> instant(p.start) }.sorted()
        assertEquals(expected, got)
    }

    /** F-046 + F-085: every early alert is exported as its own VALARM and comes back as the same alerts. */
    @Test fun earlyAlertsRoundTripThroughACalendarFile() {
        val zone = java.time.ZoneId.of("Asia/Baghdad")
        val now = java.time.Instant.parse("2026-11-15T07:00:00Z")
        var s = com.fastkiwi.rememberme.core.AppState()
        s = com.fastkiwi.rememberme.core.Tasks.save(s, com.fastkiwi.rememberme.core.TaskInput(title = "موعد طبي",
            dueAt = now.plusSeconds(3 * 86_400), remindBefore = 60), now, zone).first
        s = s.copy(tasks = s.tasks.map { it.withExt(AlertExt(alerts = listOf(1440, 60, 15))) })
        val text = Ics.export(s, zone, now)
        org.junit.Assert.assertEquals(3, Regex("BEGIN:VALARM").findAll(text).count() - 1) // plus the one at the time
        val back = IcsImport.read(text, zone, now)
        val saved = IcsImport.save(com.fastkiwi.rememberme.core.AppState(), back.inputs, now, zone).first.tasks.single()
        org.junit.Assert.assertEquals(listOf(1440, 60, 15), com.fastkiwi.rememberme.core.Tasks.noticeMinutes(saved))
    }
}
