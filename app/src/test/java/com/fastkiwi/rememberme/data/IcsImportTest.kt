package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.T
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.at
import com.fastkiwi.rememberme.core.localToInstant
import com.fastkiwi.rememberme.core.nextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** The iCalendar import (F-085): our own export comes back exactly; other producers' files come in with a report. */
class IcsImportTest {
    @Before fun ids() = T.ids()

    private val baghdad = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T07:00:00Z")

    private fun ics(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//EN") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

    private fun event(title: String, vararg lines: String) = arrayOf("BEGIN:VEVENT", "UID:${title.hashCode()}-${lines.contentHashCode()}@test", "SUMMARY:$title", *lines, "END:VEVENT")

    private fun occurrences(t: Task, zone: ZoneId, n: Int = 15): List<Instant> {
        val out = ArrayList<Instant>()
        var cur = t.scheduleAt ?: t.dueAt!!
        out += cur
        repeat(n) { cur = nextOccurrence(cur, t.repeat, cur, t.rule, zone); out += cur }
        return out
    }

    /** The report's lines in plain English (IcsNote.english), as the assertions read them. */
    private val IcsImportResult.w: List<String> get() = warnings.map { it.english() }
    private val IcsImportResult.s: List<String> get() = skipped.map { it.english() }

    private fun imported(text: String, zone: ZoneId = baghdad, at: Instant = now): Pair<IcsImportResult, List<Task>> {
        val r = IcsImport.read(text, zone, at)
        return r to IcsImport.save(AppState(), r.inputs, at, zone).second
    }

    // ---------- our own export ----------

    @Test fun everyExportedRepeatModeComesBackExactly() {
        val cases = listOf(
            Triple("Asia/Baghdad", TaskInput(title = "once", body = "line 1\nline 2; a, b \\ c", priority = Priority.HIGH, remindBefore = 15), intArrayOf(2026, 12, 1, 9, 0)),
            Triple("Asia/Baghdad", TaskInput(title = "daily", repeat = Repeat.DAILY, category = Category.WORK, remindBefore = 5), intArrayOf(2026, 11, 16, 9, 0)),
            Triple("Europe/Berlin", TaskInput(title = "weekly", repeat = Repeat.WEEKLY, priority = Priority.MEDIUM, remindBefore = 1440), intArrayOf(2027, 3, 1, 2, 30)),
            Triple("Asia/Riyadh", TaskInput(title = "sun-thu", repeat = Repeat.DAYS, days = listOf(0, 1, 2, 3, 4), category = Category.STUDY), intArrayOf(2026, 11, 22, 9, 0)),
            Triple("Asia/Riyadh", TaskInput(title = "one day", repeat = Repeat.DAYS, days = listOf(5)), intArrayOf(2026, 11, 20, 13, 0)),
            Triple("America/New_York", TaskInput(title = "every 3 days", repeat = Repeat.INTERVAL, everyDays = 3, remindBefore = 30), intArrayOf(2027, 3, 12, 2, 30)),
            Triple("Asia/Baghdad", TaskInput(title = "every 5 hours", repeat = Repeat.HOURS, everyHours = 5, remindBefore = 10), intArrayOf(2026, 11, 15, 22, 0)),
            Triple("Europe/Berlin", TaskInput(title = "every 23 hours", repeat = Repeat.HOURS, everyHours = 23), intArrayOf(2027, 3, 27, 8, 0)),
            Triple("Asia/Baghdad", TaskInput(title = "monthly 15", repeat = Repeat.MONTHLY, remindBefore = 60), intArrayOf(2026, 12, 15, 9, 0)),
            Triple("Asia/Baghdad", TaskInput(title = "monthly 31", repeat = Repeat.MONTHLY), intArrayOf(2027, 1, 31, 9, 0)),
            Triple("Europe/Berlin", TaskInput(title = "monthly 30", repeat = Repeat.MONTHLY), intArrayOf(2026, 11, 30, 20, 0)),
            Triple("Europe/Berlin", TaskInput(title = "yearly", repeat = Repeat.YEARLY), intArrayOf(2027, 6, 10, 8, 0)),
            Triple("Europe/Berlin", TaskInput(title = "yearly 29 Feb", repeat = Repeat.YEARLY), intArrayOf(2028, 2, 29, 8, 0)),
            Triple("America/New_York", TaskInput(title = "yearly 31 Oct", repeat = Repeat.YEARLY), intArrayOf(2027, 10, 31, 1, 30)),
        )
        for ((zoneId, input, d) in cases) {
            val zone = ZoneId.of(zoneId)
            val due = at(zone, d[0], d[1], d[2], d[3], d[4])
            val created = due.minusSeconds(7_200)
            val (s, t) = Tasks.save(AppState(), input.copy(dueAt = due), created, zone)
            val text = Ics.export(s, zone, created)
            val r = IcsImport.read(text, zone, created)
            val what = "${input.title} in $zoneId"
            assertEquals(what, emptyList<String>(), r.s)
            assertEquals(what, emptyList<String>(), r.w)
            val back = IcsImport.save(AppState(), r.inputs, created, zone).second.single()
            assertEquals(what, t.title, back.title)
            assertEquals(what, t.body, back.body)
            assertEquals(what, t.priority, back.priority)
            assertEquals(what, t.category, back.category)
            assertEquals(what, t.remindBefore, back.remindBefore)
            assertEquals(what, t.repeat, back.repeat)
            assertEquals(what, t.rule, back.rule)
            assertEquals(what, t.dueAt, back.dueAt)
            assertEquals(what, t.scheduleAt, back.scheduleAt)
            if (t.repeat != Repeat.NONE) assertEquals(what, occurrences(t, zone), occurrences(back, zone))
        }
    }

    @Test fun anUntimedReminderComesBackUntimed() {
        val (s, _) = Tasks.save(AppState(), TaskInput(title = "بلا وقت", body = "ملاحظة", category = Category.WORK), now, baghdad)
        val (r, tasks) = imported(Ics.export(s, baghdad, now))
        assertEquals(emptyList<String>(), r.s + r.w)
        val t = tasks.single()
        assertEquals("بلا وقت", t.title)
        assertEquals("ملاحظة", t.body)
        assertEquals(Category.WORK, t.category)
        assertNull(t.dueAt)
    }

    /** A month-end series moved on to a short month keeps its real day (31st, 29 February) after the round trip. */
    @Test fun aClampedMonthEndOrLeapDaySeriesKeepsItsDay() {
        val cases = listOf(
            Triple(Repeat.MONTHLY, at(baghdad, 2027, 1, 31, 9), at(baghdad, 2027, 1, 31, 10)),
            Triple(Repeat.MONTHLY, at(baghdad, 2027, 3, 31, 9), at(baghdad, 2027, 3, 31, 12)),
            Triple(Repeat.YEARLY, at(baghdad, 2028, 2, 29, 9), at(baghdad, 2028, 2, 29, 10)),
        )
        for ((mode, due, done) in cases) {
            val (s0, t0) = Tasks.save(AppState(), TaskInput(title = "end $mode", dueAt = due, repeat = mode), due.minusSeconds(86_400), baghdad)
            val (s, _) = Tasks.complete(s0, t0.id, done, baghdad)!!
            val t = s.task(t0.id)!!
            assertTrue(t.rule.from != t.scheduleAt) // the clamped case
            val (r, tasks) = imported(Ics.export(s, baghdad, done), at = done)
            assertEquals(emptyList<String>(), r.s + r.w)
            val back = tasks.single()
            assertEquals(t.dueAt, back.dueAt)
            assertEquals(t.rule.from, back.rule.from)
            assertEquals(occurrences(t, baghdad), occurrences(back, baghdad))
        }
    }

    /**
     * A clamped month-end start that has not come yet (DTSTART 28 February with the month-end rule, or BYMONTHDAY=-1,
     * or a yearly 29 February in a common year) is itself an occurrence: it stays the reminder's time, and the series
     * keeps its real day after it. Nothing is left out, so there is no warning.
     */
    @Test fun aFutureClampedStartKeepsItsTimeAndItsRealDay() {
        val at = Instant.parse("2027-01-15T07:00:00Z")
        val cases = listOf(
            "RRULE:FREQ=MONTHLY;BYMONTHDAY=28,29,30,31;BYSETPOS=-1" to listOf("2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31"),
            "RRULE:FREQ=MONTHLY;BYMONTHDAY=-1" to listOf("2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31"),
            "RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=28,29;BYSETPOS=-1" to listOf("2027-02-28", "2028-02-29", "2029-02-28", "2030-02-28"),
        )
        for ((rule, want) in cases) {
            val (r, tasks) = imported(ics(*event("Rent", "DTSTART;TZID=Asia/Baghdad:20270228T090000", rule)), at = at)
            assertEquals(rule, emptyList<String>(), r.s + r.w)
            val t = tasks.single()
            assertEquals(rule, at(baghdad, 2027, 2, 28, 9), t.dueAt)
            assertEquals(rule, want, occurrences(t, baghdad, 3).map { it.atZone(baghdad).toLocalDate().toString() })
            assertTrue(rule, occurrences(t, baghdad, 3).all { it.atZone(baghdad).toLocalTime() == java.time.LocalTime.of(9, 0) })
        }
    }

    /** Our own export of a clamped month-end series exported before its anchor day has come back the same (torture). */
    @Test fun anEarlyExportOfAClampedSeriesComesBackTheSame() {
        val due = at(baghdad, 2027, 1, 31, 9)
        val (s0, t0) = Tasks.save(AppState(), TaskInput(title = "rent", dueAt = due, repeat = Repeat.MONTHLY), due.minusSeconds(40L * 86_400), baghdad)
        val (s, _) = Tasks.complete(s0, t0.id, due.plusSeconds(60), baghdad)!! // done early: now Feb 28
        val t = s.task(t0.id)!!
        val exportedAt = Instant.parse("2027-01-15T07:00:00Z") // before 31 January
        val (r, tasks) = imported(Ics.export(s, baghdad, exportedAt), at = exportedAt)
        assertEquals(emptyList<String>(), r.s + r.w)
        val back = tasks.single()
        assertEquals(t.scheduleAt, back.dueAt)
        assertEquals(occurrences(t, baghdad), occurrences(back, baghdad))
    }

    /**
     * An every-N-hours repeat is exported with a UTC DTSTART (elapsed hours, exact for every reader) and comes back
     * with the same occurrences across a DST change, without a clock warning.
     */
    @Test fun anHourlySeriesIsExportedInUtcAndComesBackTheSame() {
        val berlin = ZoneId.of("Europe/Berlin")
        val due = at(berlin, 2029, 3, 24, 23, 0)
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "pill", dueAt = due, repeat = Repeat.HOURS, everyHours = 22), due.minusSeconds(3600), berlin)
        val text = Ics.export(s, berlin, due.minusSeconds(3600))
        assertTrue(text, text.contains("DTSTART:20290324T220000Z\r\nRRULE:FREQ=HOURLY;INTERVAL=22\r\n"))
        for (phone in listOf(berlin, baghdad)) {
            val (r, tasks) = imported(text, zone = phone, at = due.minusSeconds(3600))
            assertEquals(emptyList<String>(), r.s + r.w)
            val back = tasks.single()
            assertEquals(Repeat.HOURS, back.repeat)
            assertEquals(occurrences(t, berlin, 30), occurrences(back, phone, 30))
        }
    }

    @Test fun aPastRepeatResumesAtItsNextOccurrenceAndAPastOneTimeEventIsSkipped() {
        val text = ics(
            *event("Water plants", "DTSTART;TZID=Asia/Baghdad:20200105T080000", "RRULE:FREQ=DAILY;INTERVAL=3"),
            *event("Old meeting", "DTSTART:20250101T100000Z"),
            *event("Right now", "DTSTART:20261115T070000Z"),
        )
        val (r, tasks) = imported(text)
        val t = tasks.single()
        assertEquals(Repeat.INTERVAL, t.repeat)
        assertEquals(at(baghdad, 2020, 1, 5, 8), t.rule.from)
        assertTrue(t.dueAt!!.isAfter(now))
        assertEquals(t.dueAt, nextOccurrence(t.rule.from!!, Repeat.INTERVAL, now, t.rule, baghdad))
        assertEquals(2, r.s.size)
        assertTrue(r.s[0].startsWith("\"Old meeting\"") && r.s[0].contains("passed"))
        assertTrue(r.s[1].startsWith("\"Right now\""))
    }

    // ---------- other producers ----------

    private val newYorkZone = arrayOf(
        "BEGIN:VTIMEZONE", "TZID:%s", "X-LIC-LOCATION:America/New_York",
        "BEGIN:DAYLIGHT", "TZOFFSETFROM:-0500", "TZOFFSETTO:-0400", "TZNAME:EDT", "DTSTART:19700308T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU", "END:DAYLIGHT",
        "BEGIN:STANDARD", "TZOFFSETFROM:-0400", "TZOFFSETTO:-0500", "TZNAME:EST", "DTSTART:19701101T020000", "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU", "END:STANDARD",
        "END:VTIMEZONE",
    )

    private fun nyZone(tzid: String) = newYorkZone.map { it.replace("%s", tzid) }.toTypedArray()

    @Test fun googleCalendarStyle() {
        val text = ics(
            "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:Work", "X-WR-TIMEZONE:America/New_York",
            *nyZone("America/New_York"),
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261207T090000", "DTEND;TZID=America/New_York:20261207T093000",
            "RRULE:FREQ=WEEKLY;WKST=SU;BYDAY=MO,WE", "DTSTAMP:20261115T120000Z", "UID:7kukuqrfedlm2f9t0vr42q2a3q@google.com",
            "CREATED:20261101T100000Z", "DESCRIPTION:Daily sync\\, with notes\\nSecond line", "LAST-MODIFIED:20261101T100000Z",
            "LOCATION:Room 4", "SEQUENCE:0", "STATUS:CONFIRMED", "SUMMARY:Team standup", "TRANSP:OPAQUE",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:This is an event reminder", "TRIGGER:-P0DT0H10M0S", "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:19900512", "DTEND;VALUE=DATE:19900513", "RRULE:FREQ=YEARLY", "DTSTAMP:20261115T120000Z",
            "UID:birthday-sara@google.com", "SUMMARY:Sara's birthday", "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261210T140000", "RRULE:FREQ=WEEKLY;BYDAY=TH",
            "EXDATE;TZID=America/New_York:20261224T140000", "UID:omar@google.com", "SUMMARY:1:1 with Omar",
            "END:VEVENT",
            "BEGIN:VEVENT", "DTSTART:20250101T100000Z", "UID:old@google.com", "SUMMARY:Old meeting", "END:VEVENT",
        )
        val (r, tasks) = imported(text)
        assertEquals(3, tasks.size)
        val standup = tasks[0]
        assertEquals("Team standup", standup.title)
        assertEquals("Daily sync, with notes\nSecond line\nRoom 4", standup.body)
        assertEquals(Instant.parse("2026-12-07T14:00:00Z"), standup.dueAt)
        assertEquals(Repeat.DAYS, standup.repeat)
        assertEquals(listOf(1, 3), standup.rule.days)
        assertEquals(10, standup.remindBefore)
        val birthday = tasks[1]
        assertEquals(Repeat.YEARLY, birthday.repeat)
        assertEquals(LocalDateTime.of(2027, 5, 12, 9, 0), birthday.dueAt!!.atZone(baghdad).toLocalDateTime())
        val omar = tasks[2]
        assertEquals(Repeat.NONE, omar.repeat)
        assertEquals(Instant.parse("2026-12-10T19:00:00Z"), omar.dueAt)
        assertTrue(r.s.single().startsWith("\"Old meeting\""))
        assertTrue(r.w.any { it.startsWith("\"1:1 with Omar\"") && it.contains("EXDATE") && it.contains("one-time") })
        assertTrue(r.w.any { it.startsWith("\"Team standup\"") && it.contains("America/New_York") && it.contains("hour") })
        assertTrue(r.w.any { it.contains("all-day") && it.contains("\"Sara's birthday\"") && it.contains("09:00") })
    }

    @Test fun aVTimezoneWithAnUnknownNameIsUsed() {
        val text = ics(
            *nyZone("Eastern Time (US & Canada)"),
            *event("Winter", "DTSTART;TZID=\"Eastern Time (US & Canada)\":20261207T090000"),
            *event("Summer", "DTSTART;TZID=Eastern Time (US & Canada):20270705T090000"),
            *event("Spring gap", "DTSTART;TZID=Eastern Time (US & Canada):20270314T023000"),
            "BEGIN:VTIMEZONE", "TZID:Custom RDATE",
            "BEGIN:STANDARD", "DTSTART:20261025T030000", "RDATE:20271031T030000", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:20270328T020000", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200", "END:DAYLIGHT",
            "END:VTIMEZONE",
            *event("RDATE winter", "DTSTART;TZID=Custom RDATE:20270110T120000"),
            *event("RDATE summer", "DTSTART;TZID=Custom RDATE:20270710T120000"),
            *event("RDATE late", "DTSTART;TZID=Custom RDATE:20271210T120000"),
        )
        val (r, tasks) = imported(text)
        assertEquals(emptyList<String>(), r.s + r.w)
        assertEquals(
            listOf("2026-12-07T14:00:00Z", "2027-07-05T13:00:00Z", "2027-03-14T07:30:00Z", "2027-01-10T11:00:00Z", "2027-07-10T10:00:00Z", "2027-12-10T11:00:00Z").map(Instant::parse),
            tasks.map { it.dueAt },
        )
    }

    @Test fun outlookStyleWithWindowsZoneNames() {
        val text = ics(
            "METHOD:PUBLISH", "X-MS-OLK-FORCEINSPECTOROPEN:TRUE",
            "BEGIN:VTIMEZONE", "TZID:Arab Standard Time",
            "BEGIN:STANDARD", "DTSTART:16010101T000000", "TZOFFSETFROM:+0300", "TZOFFSETTO:+0300", "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VTIMEZONE", "TZID:W. Europe Standard Time",
            "BEGIN:STANDARD", "DTSTART:16011028T030000", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:16010325T020000", "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200", "END:DAYLIGHT",
            "END:VTIMEZONE",
            "BEGIN:VEVENT", "CLASS:PUBLIC", "CREATED:20261101T080000Z", "DESCRIPTION:Send the report\\n",
            "DTEND;TZID=\"Arab Standard Time\":20261201T100000", "DTSTAMP:20261101T080000Z",
            "DTSTART;TZID=\"Arab Standard Time\":20261201T090000", "LAST-MODIFIED:20261101T080000Z", "PRIORITY:5",
            "RRULE:FREQ=MONTHLY;BYMONTHDAY=1", "SEQUENCE:0", "SUMMARY;LANGUAGE=ar:التقرير الشهري", "TRANSP:OPAQUE",
            "UID:040000008200E00074C5B7101A82E00800000000A0B1C2D3E4F5", "X-MICROSOFT-CDO-BUSYSTATUS:BUSY", "X-MICROSOFT-CDO-IMPORTANCE:1",
            "BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT", "DTSTART;TZID=W. Europe Standard Time:20270615T183000", "SUMMARY:Berlin call", "CATEGORIES:Work",
            "PRIORITY:1", "RRULE:FREQ=WEEKLY;COUNT=10;BYDAY=TU", "UID:berlin@outlook", "END:VEVENT",
            "BEGIN:VEVENT", "DTSTART;TZID=W. Europe Standard Time:20270101T120000", "SUMMARY:Winter lunch", "UID:lunch@outlook", "END:VEVENT",
        )
        val (r, tasks) = imported(text)
        val report = tasks[0]
        assertEquals("التقرير الشهري", report.title)
        assertEquals("Send the report", report.body)
        assertEquals(Instant.parse("2026-12-01T06:00:00Z"), report.dueAt)
        assertEquals(Repeat.MONTHLY, report.repeat)
        assertEquals(Priority.MEDIUM, report.priority)
        assertEquals(15, report.remindBefore)
        val berlin = tasks[1]
        assertEquals(Instant.parse("2027-06-15T16:30:00Z"), berlin.dueAt)
        assertEquals(Repeat.NONE, berlin.repeat)
        assertEquals(Category.WORK, berlin.category)
        assertEquals(Priority.HIGH, berlin.priority)
        assertEquals(Instant.parse("2027-01-01T11:00:00Z"), tasks[2].dueAt)
        assertEquals(emptyList<String>(), r.s)
        assertEquals(1, r.w.size)
        assertTrue(r.w[0], r.w[0].startsWith("\"Berlin call\"") && r.w[0].contains("COUNT=10"))
    }

    @Test fun windowsZoneNamesWithoutAVTimezone() {
        val text = ics(
            *event("Riyadh", "DTSTART;TZID=Arab Standard Time:20261201T090000"),
            *event("Baghdad", "DTSTART;TZID=Arabic Standard Time:20261201T090000"),
            *event("Dubai", "DTSTART;TZID=\"Arabian Standard Time\":20261201T090000"),
            *event("Cairo", "DTSTART;TZID=Egypt Standard Time:20261201T090000"),
            *event("Fiji", "DTSTART;TZID=Fiji Standard Time:20261201T090000"),
            *event("Mozilla", "DTSTART;TZID=/mozilla.org/20050126_1/America/New_York:20261201T090000"),
        )
        val (r, tasks) = imported(text)
        assertEquals(
            listOf("2026-12-01T06:00:00Z", "2026-12-01T06:00:00Z", "2026-12-01T05:00:00Z", "2026-12-01T07:00:00Z", "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z").map(Instant::parse),
            tasks.map { it.dueAt },
        )
        assertTrue(r.w.toString(), r.w.single().contains("Fiji Standard Time") && r.w.single().contains("Asia/Baghdad"))
    }

    @Test fun appleStyleWithFloatingTimes() {
        val text = ics(
            "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT", "CREATED:20261101T101010Z", "UID:6F9A1B2C-3D4E-5F60-7182-93A4B5C6D7E8", "DTEND:20261201T093000",
            "TRANSP:OPAQUE", "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC", "SUMMARY:Dentist", "DTSTAMP:20261101T101010Z",
            "DTSTART:20261201T083000", "SEQUENCE:0",
            "BEGIN:VALARM", "X-WR-ALARMUID:A1", "UID:A1", "TRIGGER:-PT30M", "ATTACH;VALUE=URI:Chord", "ACTION:AUDIO", "END:VALARM",
            "BEGIN:VALARM", "TRIGGER;VALUE=DATE-TIME:19760401T005545Z", "ACTION:NONE", "X-APPLE-DEFAULT-ALARM:TRUE", "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:rent-apple", "DTSTART:20261203T190000", "RRULE:FREQ=MONTHLY;INTERVAL=1", "SUMMARY:Pay rent", "END:VEVENT",
            "BEGIN:VTODO", "UID:gifts", "DUE;VALUE=DATE:20261220", "SUMMARY:Buy gifts", "STATUS:NEEDS-ACTION", "END:VTODO",
            "BEGIN:VTODO", "UID:done", "SUMMARY:Done thing", "STATUS:COMPLETED", "COMPLETED:20261101T000000Z", "END:VTODO",
            "BEGIN:VTODO", "UID:untimed", "SUMMARY:Someday", "END:VTODO",
        )
        val (r, tasks) = imported(text)
        assertEquals(listOf("Dentist", "Pay rent", "Buy gifts", "Someday"), tasks.map { it.title })
        assertEquals(localToInstant(LocalDateTime.of(2026, 12, 1, 8, 30), baghdad), tasks[0].dueAt)
        assertEquals(30, tasks[0].remindBefore)
        assertEquals(Repeat.MONTHLY, tasks[1].repeat)
        assertEquals(at(baghdad, 2026, 12, 3, 19), tasks[1].dueAt)
        assertEquals(at(baghdad, 2026, 12, 20, 9), tasks[2].dueAt)
        assertNull(tasks[3].dueAt)
        assertTrue(r.s.single().startsWith("\"Done thing\""))
        assertEquals(1, r.w.size)
        assertTrue(r.w[0].contains("\"Buy gifts\"") && r.w[0].contains("09:00"))
        // A floating time follows the phone: the same file in Berlin is 08:30 Berlin time.
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(localToInstant(LocalDateTime.of(2026, 12, 1, 8, 30), berlin), IcsImport.read(text, berlin, now).inputs[0].dueAt)
    }

    @Test fun allDayBirthdaysRepeatYearlyAt9() {
        val text = ics(
            *event("Mum", "DTSTART;VALUE=DATE:19650312", "RRULE:FREQ=YEARLY"),
            *event("Leap", "DTSTART;VALUE=DATE:19960229", "RRULE:FREQ=YEARLY"),
            *event("Apple style", "DTSTART;VALUE=DATE:19801201", "RRULE:FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=1"),
            *event("Day off", "DTSTART;VALUE=DATE:20261224"),
        )
        val (r, tasks) = imported(text)
        assertEquals(emptyList<String>(), r.s)
        assertEquals(listOf(Repeat.YEARLY, Repeat.YEARLY, Repeat.YEARLY, Repeat.NONE), tasks.map { it.repeat })
        assertEquals(
            listOf(LocalDateTime.of(2027, 3, 12, 9, 0), LocalDateTime.of(2027, 2, 28, 9, 0), LocalDateTime.of(2026, 12, 1, 9, 0), LocalDateTime.of(2026, 12, 24, 9, 0)),
            tasks.map { it.dueAt!!.atZone(baghdad).toLocalDateTime() },
        )
        // The leap-day birthday is back on the 29th in 2028.
        val leap = tasks[1]
        assertEquals(LocalDateTime.of(2028, 2, 29, 9, 0), nextOccurrence(leap.dueAt!!, Repeat.YEARLY, leap.dueAt!!, leap.rule, baghdad).atZone(baghdad).toLocalDateTime())
        assertTrue(r.w.any { it.startsWith("\"Leap\"") && it.contains("28 February") })
        assertTrue(r.w.any { it.startsWith("4 all-day") && it.contains("\"Mum\"") })
    }

    @Test fun theWindowsAppsExport() {
        val text = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Remember Me//Tasks//AR", "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT", "UID:abc@remember-me", "DTSTAMP:20261115T070000Z", "DTSTART:20261120T060000Z", "DURATION:PT15M",
            "SUMMARY:دفع الإيجار", "CATEGORIES:bills", "RRULE:FREQ=MONTHLY",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:دفع الإيجار", "TRIGGER:-PT60M", "END:VALARM", "END:VEVENT",
            "BEGIN:VEVENT", "UID:def@remember-me", "DTSTAMP:20261115T070000Z", "DTSTART:20261116T050000Z", "DURATION:PT15M",
            "SUMMARY:Gym", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val (r, tasks) = imported(text)
        assertEquals(emptyList<String>(), r.s + r.w)
        assertEquals(Repeat.MONTHLY, tasks[0].repeat)
        assertEquals(Instant.parse("2026-11-20T06:00:00Z"), tasks[0].dueAt)
        assertEquals(60, tasks[0].remindBefore)
        assertEquals(listOf(1, 3, 5), tasks[1].rule.days)
        // In a zone with summer time, a series defined in UTC drifts by an hour: said, not hidden.
        val berlin = IcsImport.read(text, ZoneId.of("Europe/Berlin"), now)
        assertEquals(2, berlin.w.size)
        assertTrue(berlin.w.all { it.contains("UTC") })
    }

    @Test fun exactEquivalentsFromOtherProducersMap() {
        val start = "DTSTART;TZID=Asia/Baghdad:20270104T090000" // a Monday
        val cases = listOf(
            "FREQ=WEEKLY;INTERVAL=2" to Triple(Repeat.INTERVAL, 14, emptyList<Int>()),
            "FREQ=WEEKLY;INTERVAL=3;BYDAY=MO" to Triple(Repeat.INTERVAL, 21, emptyList()),
            "FREQ=DAILY;BYDAY=MO,TU,WE" to Triple(Repeat.DAYS, 2, listOf(1, 2, 3)),
            "freq=daily;interval=3" to Triple(Repeat.INTERVAL, 3, emptyList()),
            "FREQ=DAILY;INTERVAL=1" to Triple(Repeat.DAILY, 2, emptyList()),
            "FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=4" to Triple(Repeat.YEARLY, 2, emptyList()),
            "FREQ=MONTHLY;BYMONTHDAY=4" to Triple(Repeat.MONTHLY, 2, emptyList()),
            "FREQ=WEEKLY;BYDAY=SU,MO;WKST=MO" to Triple(Repeat.DAYS, 2, listOf(0, 1)),
        )
        for ((rule, expected) in cases) {
            val (r, tasks) = imported(ics(*event("x", start, "RRULE:$rule")))
            assertEquals(rule, emptyList<String>(), r.s + r.w)
            val t = tasks.single()
            assertEquals(rule, expected.first, t.repeat)
            if (t.repeat == Repeat.INTERVAL) assertEquals(rule, expected.second, t.rule.everyDays)
            if (t.repeat == Repeat.DAYS) assertEquals(rule, expected.third, t.rule.days)
        }
        // The last day of every month.
        val (r, tasks) = imported(ics(*event("end", "DTSTART;TZID=Asia/Baghdad:20270131T090000", "RRULE:FREQ=MONTHLY;BYMONTHDAY=-1")))
        assertEquals(emptyList<String>(), r.w)
        val t = tasks.single()
        assertEquals(listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"), occurrences(t, baghdad, 3).map { it.atZone(baghdad).toLocalDate().toString() })
        // A plain monthly rule on the 30th: the calendar skips February, this app does not; said.
        val (r2, _) = imported(ics(*event("30th", "DTSTART;TZID=Asia/Baghdad:20270130T090000", "RRULE:FREQ=MONTHLY")))
        assertTrue(r2.w.single().let { it.startsWith("\"30th\"") && it.contains("last day") })
    }

    @Test fun weekdaysFollowThePhonesCalendarDay() {
        // 20:00 Monday in New York is 04:00 Tuesday in Baghdad.
        val (_, tasks) = imported(ics(*event("Late call", "DTSTART;TZID=America/New_York:20261207T200000", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE")))
        assertEquals(listOf(2, 4), tasks.single().rule.days)
    }

    @Test fun unsupportedRulesAreImportedOnceWithAWarningNamingTheEvent() {
        val start = "DTSTART;TZID=Asia/Baghdad:20270104T090000"
        val rules = listOf(
            "FREQ=DAILY;COUNT=5" to "COUNT=5",
            "FREQ=WEEKLY;UNTIL=20271231T000000Z" to "UNTIL=",
            "FREQ=DAILY;BYHOUR=9,17" to "BYHOUR=9,17",
            "FREQ=MINUTELY;INTERVAL=30" to "FREQ=MINUTELY",
            "FREQ=SECONDLY" to "FREQ=SECONDLY",
            "FREQ=YEARLY;BYWEEKNO=1" to "BYWEEKNO=1",
            "FREQ=YEARLY;BYYEARDAY=100" to "BYYEARDAY=100",
            "FREQ=MONTHLY;BYDAY=1MO" to "BYDAY=1MO",
            "FREQ=MONTHLY;INTERVAL=2" to "INTERVAL=2",
            "FREQ=YEARLY;INTERVAL=4" to "INTERVAL=4",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH" to "FREQ=WEEKLY;INTERVAL=2",
            "FREQ=WEEKLY;INTERVAL=60" to "FREQ=WEEKLY;INTERVAL=60",
            "FREQ=DAILY;INTERVAL=400" to "INTERVAL=400",
            "FREQ=HOURLY;INTERVAL=36" to "FREQ=HOURLY;INTERVAL=36",
            "FREQ=MONTHLY;BYMONTHDAY=15" to "BYMONTHDAY=15",
            "FREQ=MONTHLY;BYMONTHDAY=1,15" to "BYMONTHDAY=1,15",
            "FREQ=YEARLY;BYMONTH=3" to "BYMONTH=3",
            "FREQ=MONTHLY;BYMONTHDAY=28,29,30,31;BYSETPOS=-1" to "BYSETPOS=-1",
            "FREQ=MONTHLY;BYSETPOS=2" to "BYSETPOS=2",
            "FREQ=DAILY;BYMONTH=1" to "BYMONTH=1",
            "FREQ=WEEKLY;RSCALE=HIJRI" to "RSCALE=HIJRI",
            "INTERVAL=2" to "no FREQ",
            "FREQ=FORTNIGHTLY" to "FREQ=FORTNIGHTLY",
            "garbage" to "\"garbage\"",
            "FREQ=DAILY;FREQ=WEEKLY" to "FREQ twice",
            "FREQ=DAILY;INTERVAL=0" to "INTERVAL=0",
        )
        val extras = listOf(
            arrayOf("RRULE:FREQ=DAILY", "EXDATE;TZID=Asia/Baghdad:20270106T090000") to "EXDATE",
            arrayOf("RRULE:FREQ=DAILY", "RDATE;TZID=Asia/Baghdad:20270110T120000") to "RDATE",
            arrayOf("RRULE:FREQ=DAILY", "EXRULE:FREQ=WEEKLY") to "EXRULE",
            arrayOf("RRULE:FREQ=DAILY", "RRULE:FREQ=WEEKLY") to "2 RRULEs",
        )
        val all = rules.map { arrayOf("RRULE:${it.first}") to it.second } + extras
        val lines = all.withIndex().flatMap { (i, c) -> event("Rule $i", start, *c.first).toList() }
        val (r, tasks) = imported(ics(*lines.toTypedArray()))
        assertEquals(emptyList<String>(), r.s)
        assertEquals(all.size, tasks.size)
        assertTrue(tasks.all { it.repeat == Repeat.NONE && it.dueAt == at(baghdad, 2027, 1, 4, 9) })
        assertEquals(all.size, r.w.size)
        for ((i, c) in all.withIndex()) {
            val w = r.w[i]
            assertTrue("$i: $w", w.startsWith("\"Rule $i\":") && w.contains(c.second) && w.contains("one-time"))
        }
        // A past start with an unsupported rule is skipped, saying both.
        val (r2, t2) = imported(ics(*event("Past course", "DTSTART;TZID=Asia/Baghdad:20260104T090000", "RRULE:FREQ=WEEKLY;COUNT=40")))
        assertTrue(t2.isEmpty())
        assertTrue(r2.s.single().let { it.startsWith("\"Past course\"") && it.contains("COUNT=40") && it.contains("passed") })
        // RDATE alone (no RRULE) keeps the first time and says so.
        val (r3, t3) = imported(ics(*event("Extra dates", start, "RDATE:20270201T090000Z")))
        assertEquals(1, t3.size)
        assertTrue(r3.w.single().contains("RDATE"))
    }

    @Test fun alarms() {
        val due = "DTSTART:20270104T090000Z"
        fun alarm(vararg l: String) = arrayOf("BEGIN:VALARM", "ACTION:DISPLAY", *l, "END:VALARM")
        val text = ics(
            *event("Many", due, *alarm("TRIGGER:-PT1440M"), *alarm("TRIGGER:-PT10M"), *alarm("TRIGGER;RELATED=START:-PT5M"),
                *alarm("TRIGGER:-PT20M"), *alarm("TRIGGER:PT5M"), *alarm("TRIGGER;RELATED=END:-PT5M"), *alarm("TRIGGER:PT0S"),
                *alarm("TRIGGER:-PT90S"), *alarm("TRIGGER:soon"), *alarm("DESCRIPTION:no trigger")),
            *event("Absolute", due, *alarm("TRIGGER;VALUE=DATE-TIME:20270104T083000Z")),
            *event("A day", due, *alarm("TRIGGER:-P1D")),
            *event("Hour", due, *alarm("TRIGGER:-PT1H")),
            *event("Untimed", *alarm("TRIGGER:-PT10M")),
        )
        val (r, tasks) = imported(text)
        assertEquals(listOf(5, 30, 1440, 60), tasks.take(4).map { it.remindBefore })
        assertEquals(0, tasks[4].remindBefore)
        val many = r.w.filter { it.startsWith("\"Many\"") }
        assertTrue(many.toString(), many.any { it.contains("20 minutes before") })
        assertTrue(many.any { it.contains("after the start") })
        assertTrue(many.any { it.contains("end of the event") })
        assertTrue(many.any { it.contains("90 seconds") })
        assertTrue(many.any { it.contains("\"soon\"") })
        assertTrue(many.any { it.contains("without a TRIGGER") })
        assertEquals(6, many.size) // the six alarms this app cannot keep; 5 minutes, 10 minutes and 1 day are all kept
        assertEquals(listOf(1440, 10, 5), AlertExt.of(tasks[0]).alerts)
        assertTrue(r.w.any { it.startsWith("\"Untimed\"") && it.contains("alarms") })
        // The notes are data: the screen renders them from their values.
        val kinds = r.warnings.filter { it.item?.title == "Many" }.map { (it as IcsNote.AlarmNotKept).alarm }
        assertEquals(listOf(IcsAlarm.NotOffered(1_200), IcsAlarm.AfterStart(300), IcsAlarm.FromEnd("-PT5M"), IcsAlarm.NotOffered(90), IcsAlarm.BadTrigger("soon"), IcsAlarm.NoTrigger), kinds)
    }

    private fun alarm(trigger: String) = arrayOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:$trigger", "END:VALARM")

    /** F-046: up to 3 early alarms become the reminder's early alerts (the nearest in remindBefore, all in android.alerts). */
    @Test fun twoAndThreeAlarmsBecomeEarlyAlerts() {
        val due = "DTSTART:20270104T090000Z"
        val text = ics(
            *event("Two", due, *alarm("-PT10M"), *alarm("-PT1H")),
            *event("Three", due, *alarm("-P1D"), *alarm("-PT15M"), *alarm("-PT1H"), *alarm("-PT1H")),
            *event("One", due, *alarm("-PT30M")),
            *event("Series", "DTSTART;TZID=Asia/Baghdad:20200105T080000", "RRULE:FREQ=DAILY", *alarm("-PT5M"), *alarm("-P1D")),
        )
        val (r, tasks) = imported(text)
        assertEquals(emptyList<String>(), r.s + r.w)
        assertEquals(listOf(10, 15, 30, 5), tasks.map { it.remindBefore })
        assertEquals(listOf(listOf(60, 10), listOf(1440, 60, 15), emptyList(), listOf(1440, 5)), tasks.map { AlertExt.of(it).alerts })
        assertEquals(listOf(10, 60), r.inputs[0].alerts)
        assertNull(r.inputs[2].alerts)
        // The series moved on to its next occurrence keeps its alerts.
        assertTrue(tasks[3].dueAt!!.isAfter(now))
    }

    @Test fun aFourthEarlyAlarmIsNamedInAWarning() {
        val text = ics(*event("Four", "DTSTART:20270104T090000Z", *alarm("-P1D"), *alarm("-PT5M"), *alarm("-PT1H"), *alarm("-PT15M"), *alarm("-PT30M")))
        val (r, tasks) = imported(text)
        val t = tasks.single()
        assertEquals(5, t.remindBefore)
        assertEquals(listOf(30, 15, 5), AlertExt.of(t).alerts)
        val w = r.warnings.single() as IcsNote.TooManyAlarms
        assertEquals("Four", w.item.title)
        assertEquals(listOf(5, 15, 30), w.kept)
        assertEquals(listOf(60, 1440), w.dropped)
        assertTrue(w.english(), w.english().startsWith("\"Four\":") && w.english().contains("1 day"))
    }

    @Test fun textEscapingFoldingPriorityAndCategories() {
        val long = "SUMMARY:" + "ذكرني ".repeat(30) + "\\, نهاية"
        val folded = Ics.fold(long)
        assertTrue(folded.contains("\r\n "))
        val text = ics(
            "BEGIN:VEVENT", "UID:esc", "SUMMARY:a\\, b\\; c\\\\ d\\nnext", "DESCRIPTION:line1\\Nline2\\,x", "DTSTART:20270104T090000Z",
            "CATEGORIES:Other,study", "END:VEVENT",
            "BEGIN:VEVENT", "UID:fold", folded.replace("\r\n ", "\n\t"), "DTSTART:20270104T090000Z", "CATEGORIES:x", "CATEGORIES:WORK", "END:VEVENT",
            *(0..9).flatMap { p -> event("p$p", "DTSTART:20270104T090000Z", "PRIORITY:$p").toList() }.toTypedArray(),
            *event("px", "DTSTART:20270104T090000Z", "PRIORITY:high"),
            "BEGIN:VEVENT", "UID:nosummary", "DESCRIPTION:\\n  First line\\nsecond", "DTSTART:20270104T090000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:notitle", "DTSTART:20270104T090000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:esc", "SUMMARY:duplicate", "DTSTART:20270104T090000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:cancel", "SUMMARY:Cancelled one", "STATUS:CANCELLED", "DTSTART:20270104T090000Z", "END:VEVENT",
            *event("T".repeat(250), "DTSTART:20270104T090000Z"),
        )
        val (r, tasks) = imported(text)
        assertEquals("a, b; c\\ d next", tasks[0].title)
        assertEquals("line1\nline2,x", tasks[0].body)
        assertEquals(Category.STUDY, tasks[0].category)
        assertEquals("ذكرني ".repeat(30) + ", نهاية", tasks[1].title)
        assertEquals(Category.WORK, tasks[1].category)
        val pr = tasks.subList(2, 13).map { it.priority }
        assertEquals(listOf(Priority.NORMAL) + List(4) { Priority.HIGH } + Priority.MEDIUM + List(4) { Priority.NORMAL } + Priority.NORMAL, pr)
        assertEquals("First line", tasks[13].title)
        assertEquals(200, tasks[14].title.length)
        assertEquals(15, tasks.size)
        assertEquals(3, r.s.size)
        assertTrue(r.s[0].contains("no title"))
        assertTrue(r.s[1].startsWith("\"duplicate\"") && r.s[1].contains("UID"))
        assertTrue(r.s[2].startsWith("\"Cancelled one\""))
        assertTrue(r.w.single().contains("200 characters"))
    }

    @Test fun lineEndingsAndOddButReadableFiles() {
        val base = ics(*event("Plain", "DTSTART:20270104T090000Z"))
        for (text in listOf(base, base.replace("\r\n", "\n"), base.replace("\r\n", "\r"), "﻿" + base, base.lowercase().replace("summary:plain", "SUMMARY:Plain"))) {
            val r = IcsImport.read(text, baghdad, now)
            assertEquals(1, r.inputs.size)
            assertEquals("Plain", r.inputs[0].title)
        }
        // Two calendars in one file, and an event with a changed occurrence of a series.
        val two = ics(*event("A", "DTSTART:20270104T090000Z")) + ics(
            "BEGIN:VEVENT", "UID:series", "SUMMARY:Moved", "RECURRENCE-ID:20270105T090000Z", "DTSTART:20270106T090000Z", "END:VEVENT",
            "BEGIN:VJOURNAL", "SUMMARY:diary", "END:VJOURNAL",
        )
        val r = IcsImport.read(two, baghdad, now)
        assertEquals(listOf("A", "Moved"), r.inputs.map { it.title })
        assertTrue(r.w.any { it.startsWith("\"Moved\"") && it.contains("changed occurrence") })
        assertTrue(r.w.any { it.contains("1 VJOURNAL") })
    }

    @Test fun cutOffDamagedAndHugeFiles() {
        val full = ics(*event("First", "DTSTART:20270104T090000Z"), *event("Second", "DTSTART:20270105T090000Z", "RRULE:FREQ=DAILY"))
        val cut = full.substring(0, full.indexOf("RRULE"))
        val r = IcsImport.read(cut, baghdad, now)
        assertEquals(listOf("First"), r.inputs.map { it.title })
        assertTrue(r.s.single().let { it.startsWith("\"Second\"") && it.contains("incomplete") })
        assertTrue(r.w.single().contains("ends early"))

        for (bad in listOf("", "hello", "BEGIN:VEVENT\r\nSUMMARY:x\r\nEND:VEVENT", "{\"version\":1}", "PK\u0003\u0004")) {
            val e = assertThrows(IcsImport.ImportException::class.java) { IcsImport.read(bad, baghdad, now) }
            assertEquals(IcsImport.ImportException.Reason.NOT_ICS, e.reason)
        }
        val big = "X".repeat((IcsImport.MAX_BYTES + 1).toInt())
        assertEquals(IcsImport.ImportException.Reason.TOO_LARGE, assertThrows(IcsImport.ImportException::class.java) { IcsImport.read(big, baghdad, now) }.reason)
        // 7 MB of Arabic text is more than 20 MB of UTF-8.
        val arabic = "ذ".repeat(11 * 1024 * 1024)
        assertEquals(IcsImport.ImportException.Reason.TOO_LARGE, assertThrows(IcsImport.ImportException::class.java) { IcsImport.read(arabic, baghdad, now) }.reason)

        val many = ics(*(0..5_001).flatMap { event("E$it", "DTSTART:20270104T090000Z").toList() }.toTypedArray())
        val rm = IcsImport.read(many, baghdad, now)
        assertEquals(IcsImport.MAX_ITEMS, rm.inputs.size)
        assertTrue(rm.w.single().contains("5,000") && rm.w.single().contains("2 more"))
    }

    @Test fun timeZoneEdgeCases() {
        val text = ics(
            *event("Gap", "DTSTART;TZID=Europe/Berlin:20270328T023000"),
            *event("Unknown", "DTSTART;TZID=Mars/Olympus_Mons:20270104T090000"),
            *event("Leap second", "DTSTART:20261231T235960Z"),
            *event("Bad date", "DTSTART:20270230T090000"),
            *event("Far", "DTSTART:30010101T090000Z"),
            *event("No start"),
        )
        val (r, tasks) = imported(text)
        assertEquals(localToInstant(LocalDateTime.of(2027, 3, 28, 2, 30), ZoneId.of("Europe/Berlin")), tasks[0].dueAt)
        assertEquals(Instant.parse("2027-03-28T01:30:00Z"), tasks[0].dueAt)
        assertEquals(at(baghdad, 2027, 1, 4, 9), tasks[1].dueAt)
        assertEquals(Instant.parse("2026-12-31T23:59:59Z"), tasks[2].dueAt)
        assertNull(tasks[3].dueAt)
        assertEquals(listOf("Gap", "Unknown", "Leap second", "No start"), tasks.map { it.title })
        assertTrue(r.s[0].startsWith("\"Bad date\"") && r.s[0].contains("could not be read"))
        assertTrue(r.s[1].startsWith("\"Far\"") && r.s[1].contains("out of range"))
        assertTrue(r.w.any { it.contains("Mars/Olympus_Mons") })
        assertTrue(r.w.any { it.startsWith("\"No start\"") })
    }

    // ---------- hostile input ----------

    @Test fun fuzzNeverThrowsAnythingElseAndStaysFast() {
        val rnd = Random(85)
        val zone = baghdad
        val (s, _) = Tasks.save(AppState(), TaskInput(title = "m", dueAt = at(zone, 2027, 1, 31, 9), repeat = Repeat.MONTHLY, remindBefore = 60), now, zone)
        val (s2, _) = Tasks.save(s, TaskInput(title = "d", dueAt = at(zone, 2027, 1, 3, 9), repeat = Repeat.DAYS, days = listOf(1, 3)), now, zone)
        val corpus = listOf(
            Ics.export(s2, ZoneId.of("Europe/Berlin"), now),
            ics(*nyZone("Eastern"), *event("g", "DTSTART;TZID=Eastern:20261207T090000", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE", "BEGIN:VALARM", "TRIGGER:-PT10M", "END:VALARM")),
            ics(*event("b", "DTSTART;VALUE=DATE:19900512", "RRULE:FREQ=YEARLY;BYMONTH=5;BYMONTHDAY=12")),
            ics(*event("r", "DTSTART:20270228T090000", "RRULE:FREQ=MONTHLY;BYMONTHDAY=28,29,30,31;BYSETPOS=-1")),
        )
        val tokens = listOf(
            "BEGIN:VEVENT", "END:VEVENT", "BEGIN:VCALENDAR", "END:VCALENDAR", "BEGIN:VTIMEZONE", "BEGIN:STANDARD", "BEGIN:DAYLIGHT", "BEGIN:VALARM",
            "\r\n", "\r\n ", "\n\t", ":", ";", ",", "\"", "\\", "=", "^", "TZID=", ";TZID=Eastern:", "RRULE:FREQ=", "FREQ=YEARLY;BYDAY=-1SU;BYMONTH=",
            "BYDAY=99SU", "BYMONTHDAY=-31,0,32", "BYSETPOS=-1", "INTERVAL=99999999999999999999", "COUNT=0", "UNTIL=1",
            "TRIGGER:-P99999999W", "TRIGGER;VALUE=DATE-TIME:99991231T235959Z", "DTSTART:99991231T235959Z", "DTSTART:00010101T000000",
            "DTSTART:16010101T000000", "TZOFFSETFROM:+9999", "TZOFFSETTO:-1800", "TZOFFSETTO:+0000", "RDATE:20270101T000000,20270102T000000",
            "DUE;VALUE=DATE:20270101", "PRIORITY:-1", "CATEGORIES:\\,,,", "SUMMARY:", "\u0000", "\uD800", "ذكرني", "﻿",
        )
        fun mutate(src: String): String {
            val sb = StringBuilder(src)
            repeat(1 + rnd.nextInt(8)) {
                if (sb.isEmpty()) sb.append(tokens.random(rnd))
                val p = rnd.nextInt(sb.length + 1)
                when (rnd.nextInt(6)) {
                    0 -> sb.insert(p, tokens.random(rnd))
                    1 -> sb.delete(p, minOf(sb.length, p + rnd.nextInt(40)))
                    2 -> if (p < sb.length) sb.setCharAt(p, (rnd.nextInt(0x700)).toChar())
                    3 -> { val q = minOf(sb.length, p + rnd.nextInt(200)); val piece = sb.substring(p, q); sb.insert(q, piece.repeat(1 + rnd.nextInt(5))) }
                    4 -> sb.setLength(p)
                    else -> sb.insert(p, "\r\n" + tokens.random(rnd) + tokens.random(rnd) + "\r\n")
                }
            }
            return sb.toString()
        }
        fun garbage(): String = String(CharArray(rnd.nextInt(3_000)) { if (rnd.nextInt(4) == 0) tokens.random(rnd)[0] else rnd.nextInt(0x10000).toChar() })
        fun huge(i: Int): String = when (i % 6) {
            0 -> ics(*event("big", "DTSTART:20270104T090000Z", "DESCRIPTION:" + "x".repeat(3 * 1024 * 1024)))
            1 -> "BEGIN:VCALENDAR\r\n" + "BEGIN:VEVENT\r\n".repeat(70_000)
            2 -> "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" + "X-A:b\r\n".repeat(300_000)
            3 -> ics(*(0 until 6_000).flatMap { event("e$it", "DTSTART;TZID=T$it:20270104T090000", "RRULE:FREQ=DAILY", "BEGIN:VALARM", "TRIGGER:-PT5M", "END:VALARM").toList() }.toTypedArray(),
                *(0 until 300).flatMap { z -> listOf("BEGIN:VTIMEZONE", "TZID:T$z") + (0 until 40).flatMap { k -> listOf("BEGIN:STANDARD", "DTSTART:1601010${k % 9 + 1}T000000", "TZOFFSETFROM:+0${k % 9}00", "TZOFFSETTO:+0${(k + 1) % 9}00", "RRULE:FREQ=YEARLY;BYMONTH=${k % 12 + 1};BYDAY=-1SU", "END:STANDARD") } + "END:VTIMEZONE" }.toTypedArray())
            4 -> "BEGIN:VCALENDAR\r\n" + "\r\n".repeat(5_000_000) + "END:VCALENDAR"
            else -> "BEGIN:VCALENDAR\r\n" + corpus[1].repeat(1 + 19 * 1024 * 1024 / corpus[1].length)
        }
        var worst = 0L
        var accepted = 0
        for (i in 0 until 5_000) {
            val input = when {
                i % 500 == 499 -> huge(i / 500)
                i % 5 == 0 -> garbage()
                i % 5 == 1 -> corpus.random(rnd).let { it.substring(0, rnd.nextInt(it.length + 1)) }
                else -> mutate(corpus.random(rnd))
            }
            val t0 = System.nanoTime()
            try {
                val r = IcsImport.read(input, zone, now)
                IcsImport.save(AppState(), r.inputs, now, zone)
                for (note in r.skipped + r.warnings) assertTrue(note.english().isNotEmpty())
                assertTrue(r.inputs.all { i -> i.alerts?.let { a -> a.size in 2..3 && a.min() == i.remindBefore } ?: true })
                accepted++
            } catch (_: IcsImport.ImportException) {
                // documented
            } catch (e: Throwable) {
                fail("input $i threw $e")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            worst = maxOf(worst, ms)
            assertTrue("input $i took $ms ms", ms < 2_000)
        }
        assertTrue("accepted $accepted", accepted > 1_000)
        println("ICS fuzz: 5000 inputs, $accepted read, slowest $worst ms")
        assertFalse(worst >= 2_000)
    }
}
