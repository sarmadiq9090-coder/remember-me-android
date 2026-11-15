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
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VToDo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal

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
