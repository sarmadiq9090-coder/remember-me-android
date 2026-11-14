package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.EARLY_MINUTES
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.data.IcsImport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Fuzz (torture suite 4): .ics import of mutated real-world-like files (this app's export, Google, Outlook with
 * Windows zone names, Apple floating times, all-day birthdays). Only [IcsImport.ImportException] may be thrown;
 * what is read is always a valid reminder that saves and round-trips; nothing is dropped silently.
 */
class IcsFuzzTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T06:00:00Z")

    private val google = """
        BEGIN:VCALENDAR
        PRODID:-//Google Inc//Google Calendar 70.9054//EN
        VERSION:2.0
        CALSCALE:GREGORIAN
        METHOD:PUBLISH
        X-WR-CALNAME:Work
        X-WR-TIMEZONE:Europe/Berlin
        BEGIN:VTIMEZONE
        TZID:Europe/Berlin
        X-LIC-LOCATION:Europe/Berlin
        BEGIN:DAYLIGHT
        TZOFFSETFROM:+0100
        TZOFFSETTO:+0200
        TZNAME:CEST
        DTSTART:19700329T020000
        RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
        END:DAYLIGHT
        BEGIN:STANDARD
        TZOFFSETFROM:+0200
        TZOFFSETTO:+0100
        TZNAME:CET
        DTSTART:19701025T030000
        RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
        END:STANDARD
        END:VTIMEZONE
        BEGIN:VEVENT
        DTSTART;TZID=Europe/Berlin:20261201T093000
        DTEND;TZID=Europe/Berlin:20261201T100000
        RRULE:FREQ=WEEKLY;BYDAY=TU,TH
        DTSTAMP:20261114T120000Z
        UID:abc123@google.com
        SUMMARY:Stand-up\, team
        DESCRIPTION:Line one\nLine two
        LOCATION:Room 4
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT10M
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20270315
        DTEND;VALUE=DATE:20270316
        RRULE:FREQ=YEARLY
        UID:bday@google.com
        SUMMARY:عيد ميلاد سارة 🎂
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20261220T080000Z
        RRULE:FREQ=MONTHLY;COUNT=5
        UID:count@google.com
        SUMMARY:Pay rent
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private val outlook = """
        BEGIN:VCALENDAR
        PRODID:-//Microsoft Corporation//Outlook 16.0 MIMEDIR//EN
        VERSION:2.0
        METHOD:PUBLISH
        BEGIN:VTIMEZONE
        TZID:Arabic Standard Time
        BEGIN:STANDARD
        DTSTART:16010101T000000
        TZOFFSETFROM:+0300
        TZOFFSETTO:+0300
        END:STANDARD
        END:VTIMEZONE
        BEGIN:VEVENT
        CLASS:PUBLIC
        DTSTART;TZID="Arabic Standard Time":20261203T170000
        DTEND;TZID="Arabic Standard Time":20261203T180000
        RRULE:FREQ=DAILY;INTERVAL=3
        PRIORITY:1
        SUMMARY;LANGUAGE=ar:موعد الطبيب
        UID:040000008200E00074C5B7101A82E00800000000
        X-MICROSOFT-CDO-BUSYSTATUS:BUSY
        BEGIN:VALARM
        TRIGGER:-PT1440M
        ACTION:DISPLAY
        DESCRIPTION:Reminder
        END:VALARM
        END:VEVENT
        BEGIN:VTODO
        UID:todo-1
        SUMMARY:اشتري خبز
        DUE;TZID=Arab Standard Time:20261205T090000
        STATUS:NEEDS-ACTION
        END:VTODO
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private val apple = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Apple Inc.//macOS 15.0//EN
        CALSCALE:GREGORIAN
        BEGIN:VEVENT
        CREATED:20261101T100000Z
        UID:1F2E-APPLE
        DTSTART:20261210T083000
        DTEND:20261210T093000
        RRULE:FREQ=MONTHLY;BYMONTHDAY=-1
        SUMMARY:Month end report
        X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
        BEGIN:VALARM
        X-WR-ALARMUID:9
        TRIGGER;VALUE=DATE-TIME:20261210T080000Z
        ACTION:AUDIO
        END:VALARM
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\n") + "\n"

    private fun ownExport(seed: Long): String {
        val gen = Gen(Random(seed))
        return Ics.export(gen.state(30, now, zone, spreadSeconds = 60L * 86_400), zone, now)
    }

    /** Checks one file; returns a problem or null. */
    private fun check(text: String): String? {
        val r = try { IcsImport.read(text, zone, now) } catch (_: IcsImport.ImportException) { return null } catch (e: Throwable) {
            return "read threw ${e::class.java.name}: ${e.message?.take(100)}"
        }
        if (r.inputs.size > IcsImport.MAX_ITEMS) return "${r.inputs.size} items"
        for (i in r.inputs) {
            if (i.title.isBlank() || i.title.trim().length > 200) return "title «${i.title.take(40)}» (${i.title.length})"
            if (i.repeat != Repeat.NONE && i.dueAt == null) return "repeat without time"
            if (i.remindBefore != 0 && i.remindBefore !in EARLY_MINUTES) return "remindBefore ${i.remindBefore}"
            if (i.days.any { it !in 0..6 }) return "days ${i.days}"
        }
        val saved = try { IcsImport.save(AppState(), r.inputs, now, zone).first } catch (e: Throwable) { return "save threw ${e::class.java.name}: ${e.message}" }
        val back = try { Codec.decode(Codec.encode(saved), now) } catch (e: Throwable) { return "saved data does not read back: ${e.message}" }
        if (back.tasks.size != saved.tasks.size) return "round trip lost tasks"
        if (saved.tasks.any { it.repeat != Repeat.NONE && !it.dueAt!!.isAfter(now) }) return "a repeat imported in the past"
        if (saved.tasks.any { it.repeat == Repeat.NONE && it.dueAt != null && !it.dueAt.isAfter(now) }) return "a past one-time event imported"
        return null
    }

    private fun mutate(text: String, rnd: Random): String {
        val lines = text.split(Regex("\r\n|\n|\r")).toMutableList()
        repeat(rnd.nextInt(1, 6)) {
            if (lines.isEmpty()) return@repeat
            val k = rnd.nextInt(lines.size)
            when (rnd.nextInt(14)) {
                0 -> lines.removeAt(k)
                1 -> lines.add(k, lines[rnd.nextInt(lines.size)])
                2 -> { val j = rnd.nextInt(lines.size); val t = lines[k]; lines[k] = lines[j]; lines[j] = t }
                3 -> lines.add(k, buildString { repeat(rnd.nextInt(1, 80)) { append(rnd.nextInt(0x20, 0x2000).toChar()) } })
                4 -> lines[k] = lines[k].let { l -> if (l.isEmpty()) l else l.substring(0, rnd.nextInt(l.length)) }
                5 -> lines[k] = lines[k].replace(':', ';')
                6 -> lines.add(k, "RRULE:" + listOf("FREQ=MONTHLY;BYMONTHDAY=31", "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29", "FREQ=HOURLY;INTERVAL=0", "FREQ=DAILY;INTERVAL=-3",
                    "FREQ=WEEKLY;BYDAY=XX,1MO", "FREQ=SECONDLY", "FREQ=MONTHLY;BYSETPOS=-1;BYMONTHDAY=28,29,30,31", "FREQ=DAILY;INTERVAL=99999999999", "FREQ=", "UNTIL=20200101T000000Z")[rnd.nextInt(10)])
                7 -> lines.add(k, listOf("DTSTART:00000000T000000", "DTSTART:99991231T235959Z", "DTSTART;TZID=Nowhere/Zone:20261201T090000", "DTSTART;VALUE=DATE:20260230",
                    "DTSTART:20261201T250000", "DTSTART;TZID=:x", "DUE:2026", "DTSTART;TZID=\"Arab Standard Time\":20270101T000000")[rnd.nextInt(8)])
                8 -> lines.add(k, listOf("BEGIN:VEVENT", "END:VEVENT", "BEGIN:VALARM", "END:VCALENDAR", "BEGIN:VCALENDAR", "BEGIN:VTIMEZONE", "END:STANDARD")[rnd.nextInt(7)])
                9 -> lines.add(k, " " + "x".repeat(rnd.nextInt(1, 2000)))
                10 -> lines.add(k, "TRIGGER:" + listOf("-PT5M", "PT0S", "-P1D", "-PT99999999H", "P", "-PT-5M", "20261201T000000Z")[rnd.nextInt(7)])
                11 -> lines.add(k, "SUMMARY:" + listOf("", "\\", "\\n\\n", "‏", "x".repeat(500), "🙂".repeat(300), "a\\,b\\;c")[rnd.nextInt(7)])
                12 -> lines[k] = lines[k].lowercase()
                else -> lines.add(k, "X-${rnd.nextInt()};A=\"b:c\";D=^n^^:" + "v".repeat(rnd.nextInt(0, 100)))
            }
        }
        return lines.joinToString(listOf("\r\n", "\n", "\r")[rnd.nextInt(3)])
    }

    @Test fun mutatedRealWorldFilesNeverCrashAndNeverGiveABadReminder() {
        val seed = Torture.seed("ics-fuzz", 9001)
        val rnd = Random(seed)
        val bases = listOf(google, outlook, apple, ownExport(seed), ownExport(seed + 1))
        val bad = ArrayList<String>()
        repeat(Torture.count(5_000, 40_000)) { i ->
            val text = mutate(bases[rnd.nextInt(bases.size)], rnd)
            val t0 = System.nanoTime()
            check(text)?.let { bad += "case $i: $it" }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > 5_000) bad += "case $i took $ms ms"
        }
        assertEquals(bad.take(8).joinToString("\n"), 0, bad.size)
    }

    @Test fun hostileFilesAreRefusedOrReadWithinLimits() {
        val head = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
        val cases = linkedMapOf(
            "empty" to "",
            "no calendar" to "BEGIN:VEVENT\r\nEND:VEVENT\r\n",
            "nested 10,000 deep" to head + "BEGIN:X\r\n".repeat(10_000) + "END:X\r\n".repeat(10_000) + "END:VCALENDAR\r\n",
            "unclosed 10,000" to head + "BEGIN:VEVENT\r\n".repeat(10_000),
            "60,000 events" to head + (0 until 60_000).joinToString("") { "BEGIN:VEVENT\r\nUID:$it\r\nSUMMARY:e$it\r\nDTSTART:20271201T090000Z\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n",
            "one 5 MB line" to head + "SUMMARY:" + "a".repeat(5_000_000) + "\r\nEND:VCALENDAR\r\n",
            "folded 100,000 times" to head + "BEGIN:VEVENT\r\nSUMMARY:x" + "\r\n y".repeat(100_000) + "\r\nDTSTART:20271201T090000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            "300 time zones" to head + (0 until 300).joinToString("") { "BEGIN:VTIMEZONE\r\nTZID:Z$it\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0${it % 10}00\r\nTZOFFSETTO:+0${it % 10}00\r\nRRULE:FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Z$it:20271201T090000\r\nSUMMARY:z$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n",
            "yearly rule from year 1" to head + "BEGIN:VTIMEZONE\r\nTZID:Old\r\nBEGIN:DAYLIGHT\r\nDTSTART:00010101T000000\r\nTZOFFSETFROM:+0000\r\nTZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Old:99991201T090000\r\nSUMMARY:old\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            "bom and lf" to "﻿" + google.replace("\r\n", "\n"),
        )
        val bad = ArrayList<String>()
        for ((name, text) in cases) {
            val t0 = System.nanoTime()
            val p = check(text)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("[torture] ics shape $name: ${p ?: "ok"} ($ms ms)")
            if (p != null) bad += "$name: $p"
            if (ms > 20_000) bad += "$name took $ms ms"
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    /** Unmutated files: every event and to-do is imported or named in the skipped list. */
    @Test fun nothingIsDroppedSilently() {
        for (text in listOf(google, outlook, apple, ownExport(11), ownExport(12))) {
            val r = IcsImport.read(text, zone, now)
            val items = Regex("^BEGIN:(VEVENT|VTODO)\\s*$", RegexOption.MULTILINE).findAll(text).count()
            assertEquals("imported ${r.inputs.size} + skipped ${r.skipped.size} of $items\n${r.skipped}", items, r.inputs.size + r.skipped.size)
        }
        Tasks.newId
    }
}
