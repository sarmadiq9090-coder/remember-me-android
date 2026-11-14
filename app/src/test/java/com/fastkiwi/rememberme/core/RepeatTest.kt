package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The repeat rules, checked against the Windows app's own cases (tests/scheduling.test.js), so both
 * apps put every occurrence on the same day and time; plus DST fall-back, a time-zone move and the
 * Android-only hourly repeat.
 */
class RepeatTest {
    @Before fun ids() = T.ids()

    private fun save(s: AppState, input: TaskInput, zone: ZoneId) = Tasks.save(s, input, Instant.parse("2020-01-01T00:00:00Z"), zone)
    private fun complete(s: AppState, id: String, now: Instant, zone: ZoneId): AppState = Tasks.complete(s, id, now, zone)!!.first

    @Test fun dailyAndWeeklyKeepLocalHourAcrossDst() {
        val cases = listOf(
            Triple("America/New_York", 2026, 3 to 7), Triple("America/New_York", 2026, 10 to 31),
            Triple("Europe/Berlin", 2026, 3 to 28), Triple("Europe/Berlin", 2026, 10 to 24),
        )
        for ((name, y, md) in cases) {
            val zone = ZoneId.of(name)
            val (m, d) = md
            var (s, daily) = save(AppState(), TaskInput(title = "daily", dueAt = at(zone, y, m, d, 9), repeat = Repeat.DAILY), zone)
            s = complete(s, daily.id, at(zone, y, m, d, 9, 5), zone)
            val next = s.task(daily.id)!!.dueAt!!.atZone(zone)
            assertEquals("$name daily", listOf(at(zone, y, m, d + 1).atZone(zone).dayOfMonth, 9, 0), listOf(next.dayOfMonth, next.hour, next.minute))
            val (s2, weekly) = save(s, TaskInput(title = "weekly", dueAt = at(zone, y, m, d - 3, 9), repeat = Repeat.WEEKLY), zone)
            val w = complete(s2, weekly.id, at(zone, y, m, d + 2, 9, 5), zone).task(weekly.id)!!.dueAt!!.atZone(zone)
            assertEquals("$name weekly",
                listOf(at(zone, y, m, d + 4).atZone(zone).dayOfMonth, at(zone, y, m, d - 3).atZone(zone).dayOfWeek, 9),
                listOf(w.dayOfMonth, w.dayOfWeek, w.hour))
        }
    }

    @Test fun dailyMissedForDaysAlertsOnceAndResumes() {
        val zone = ZoneId.of("Europe/Berlin")
        val (s, t) = save(AppState(), TaskInput(title = "دواء", dueAt = at(zone, 2026, 10, 20, 8, 30), repeat = Repeat.DAILY), zone)
        val back = at(zone, 2026, 10, 30, 12)
        assertEquals("Ten missed days produce one alert, not ten", 1, Tasks.due(s, back).size)
        val s2 = complete(s, t.id, back, zone)
        val next = s2.task(t.id)!!.dueAt!!.atZone(zone)
        assertEquals(listOf(31, 8, 30), listOf(next.dayOfMonth, next.hour, next.minute))
        assertEquals(0, Tasks.due(s2, back).size)
    }

    @Test fun monthlyAndYearlyClampWithoutDrift() {
        val zone = ZoneId.of("Asia/Baghdad")
        var (s, monthly) = save(AppState(), TaskInput(title = "إيجار", dueAt = at(zone, 2027, 1, 31, 9), repeat = Repeat.MONTHLY), zone)
        val seen = mutableListOf<String>()
        repeat(4) {
            s = complete(s, monthly.id, s.task(monthly.id)!!.dueAt!!, zone)
            val d = s.task(monthly.id)!!.dueAt!!.atZone(zone)
            seen += "${d.monthValue}/${d.dayOfMonth} ${d.hour}"
        }
        assertEquals(listOf("2/28 9", "3/31 9", "4/30 9", "5/31 9"), seen)
        var (s2, yearly) = save(AppState(), TaskInput(title = "ميلاد", dueAt = at(zone, 2028, 2, 29, 8), repeat = Repeat.YEARLY), zone)
        val years = mutableListOf<String>()
        repeat(4) {
            s2 = complete(s2, yearly.id, s2.task(yearly.id)!!.dueAt!!, zone)
            val d = s2.task(yearly.id)!!.dueAt!!.atZone(zone)
            years += "${d.year}-${d.monthValue}-${d.dayOfMonth}"
        }
        assertEquals(listOf("2029-2-28", "2030-2-28", "2031-2-28", "2032-2-29"), years)
        // Fourteen months with the app closed: one alert, then the next 31st-or-last-day after today.
        val (s3, late) = save(AppState(), TaskInput(title = "late", dueAt = at(zone, 2026, 1, 31, 9), repeat = Repeat.MONTHLY), zone)
        val back = at(zone, 2027, 3, 15, 12)
        assertEquals(1, Tasks.due(s3, back).count { it.id == late.id })
        assertEquals(LocalDate.of(2027, 3, 31), complete(s3, late.id, back, zone).task(late.id)!!.dueAt!!.atZone(zone).toLocalDate())
    }

    @Test fun chosenWeekdaysAndEveryNDays() {
        val zone = ZoneId.of("Asia/Baghdad")
        // Sunday–Thursday working week: Thursday's reminder comes back on Sunday.
        val (s, work) = save(AppState(), TaskInput(title = "دوام", dueAt = at(zone, 2026, 10, 22, 9), repeat = Repeat.DAYS, days = listOf(4, 0, 1, 2, 3, 3, 9)), zone)
        assertEquals("Days are de-duplicated, validated and sorted", listOf(0, 1, 2, 3, 4), work.rule.days)
        val w = complete(s, work.id, at(zone, 2026, 10, 22, 9, 5), zone).task(work.id)!!.dueAt!!.atZone(zone)
        assertEquals(listOf(0, 25, 9), listOf(jsDay(w.toLocalDate()), w.dayOfMonth, w.hour))
        val (s2, gym) = save(AppState(), TaskInput(title = "رياضة", dueAt = at(zone, 2026, 1, 5, 18), repeat = Repeat.DAYS, days = listOf(1, 3)), zone)
        val g = complete(s2, gym.id, at(zone, 2026, 10, 24, 12), zone).task(gym.id)!!.dueAt!!.atZone(zone)
        assertEquals(listOf(1, 26, 18), listOf(jsDay(g.toLocalDate()), g.dayOfMonth, g.hour))
        val (s3, water) = save(AppState(), TaskInput(title = "سقي النبات", dueAt = at(zone, 2026, 10, 1, 7), repeat = Repeat.INTERVAL, everyDays = 3), zone)
        assertEquals("Oct 1 + 3n: the first after the 24th is the 25th", 25,
            complete(s3, water.id, at(zone, 2026, 10, 24, 8), zone).task(water.id)!!.dueAt!!.atZone(zone).dayOfMonth)
        assertEquals(365, save(AppState(), TaskInput(title = "x", dueAt = water.dueAt, repeat = Repeat.INTERVAL, everyDays = 900), zone).second.rule.everyDays)
    }

    private fun run(zone: ZoneId, start: Instant, input: TaskInput, count: Int): List<String> {
        var (s, t) = save(AppState(), input.copy(dueAt = start), zone)
        val seen = mutableListOf<String>()
        repeat(count) {
            s = complete(s, t.id, s.task(t.id)!!.dueAt!!.plusSeconds(60), zone)
            seen += s.task(t.id)!!.dueAt!!.md(zone)
        }
        return seen
    }

    @Test fun repeatsReturnToUsualTimeAfterDstGap() {
        val cairo = ZoneId.of("Africa/Cairo")
        // Egypt moves from 00:00 to 01:00 on Friday, April 30 2027: 00:30 does not exist that night.
        assertEquals(listOf("4/29 0:30", "4/30 1:30", "5/1 0:30", "5/2 0:30"), run(cairo, at(cairo, 2027, 4, 28, 0, 30), TaskInput(title = "d", repeat = Repeat.DAILY), 4))
        assertEquals(listOf("4/29 0:30", "4/30 1:30", "5/1 0:30", "5/2 0:30"), run(cairo, at(cairo, 2027, 4, 28, 0, 30), TaskInput(title = "d", repeat = Repeat.DAYS, days = (0..6).toList()), 4))
        val ny = ZoneId.of("America/New_York")
        // New York moves from 02:00 to 03:00 on Sunday, March 14 2027.
        assertEquals(listOf("3/14 3:30", "3/15 2:30", "3/16 2:30"), run(ny, at(ny, 2027, 3, 13, 2, 30), TaskInput(title = "d", repeat = Repeat.DAILY), 3))
        assertEquals(listOf("3/14 3:30", "3/21 2:30"), run(ny, at(ny, 2027, 3, 7, 2, 30), TaskInput(title = "w", repeat = Repeat.WEEKLY), 2))
        assertEquals(listOf("3/14 3:30", "3/16 2:30", "3/18 2:30"), run(ny, at(ny, 2027, 3, 12, 2, 30), TaskInput(title = "i", repeat = Repeat.INTERVAL, everyDays = 2), 3))
    }

    @Test fun fallBackKeepsWallClock() {
        // Berlin falls back on 25 October 2026, 03:00 → 02:00: a daily 02:30 fires once, at the first 02:30.
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(listOf("10/25 2:30", "10/26 2:30"), run(zone, at(zone, 2026, 10, 24, 2, 30), TaskInput(title = "d", repeat = Repeat.DAILY), 2))
    }

    @Test fun timeZoneMoveFollowsTheWindowsRuleAndOneTimeIsAnInstant() {
        // As on Windows (D-012): the series' time of day comes from repeatFrom, an instant, read in the
        // phone's current zone. A daily 09:00 made in Baghdad (UTC+3) comes back at 10:00 in Dubai (UTC+4),
        // which is still 09:00 Baghdad time. Both apps compute the same moment from the same file.
        val baghdad = ZoneId.of("Asia/Baghdad")
        val dubai = ZoneId.of("Asia/Dubai")
        val (s, t) = save(AppState(), TaskInput(title = "d", dueAt = at(baghdad, 2026, 11, 14, 9), repeat = Repeat.DAILY), baghdad)
        val next = complete(s, t.id, at(dubai, 2026, 11, 14, 12), dubai).task(t.id)!!.dueAt!!
        assertEquals(listOf(15, 10, 0), next.atZone(dubai).let { listOf(it.dayOfMonth, it.hour, it.minute) })
        assertEquals(9, next.atZone(baghdad).hour)
        // A one-time reminder is a fixed instant: due at the same moment whatever the zone.
        val (s2, once) = save(AppState(), TaskInput(title = "o", dueAt = at(baghdad, 2026, 11, 14, 9)), baghdad)
        assertEquals(1, Tasks.due(s2, at(baghdad, 2026, 11, 14, 9)).count { it.id == once.id })
        assertEquals(0, Tasks.due(s2, at(baghdad, 2026, 11, 14, 8, 59)).count { it.id == once.id })
    }

    @Test fun everyNHours() {
        val zone = ZoneId.of("Asia/Baghdad")
        val start = at(zone, 2026, 11, 14, 8)
        val rule = RepeatRule(everyHours = 3)
        assertEquals(at(zone, 2026, 11, 14, 11), nextOccurrence(start, Repeat.HOURS, start.plusSeconds(60), rule, zone))
        // Phone off for a day: the next slot after now, on the same 3-hour grid.
        assertEquals(at(zone, 2026, 11, 15, 11), nextOccurrence(start, Repeat.HOURS, at(zone, 2026, 11, 15, 9, 30), rule, zone))
        // Hours are elapsed time, also across a DST change.
        val ny = ZoneId.of("America/New_York")
        val s = at(ny, 2027, 3, 14, 0)
        assertEquals(Duration.ofHours(4), Duration.between(s, nextOccurrence(s, Repeat.HOURS, s, RepeatRule(everyHours = 4), ny)))
        // Clamped to 1..23.
        assertEquals(start.plusSeconds(3600), nextOccurrence(start, Repeat.HOURS, start, RepeatRule(everyHours = 0), zone))
    }

    @Test fun nextIsNeverInThePast() {
        val zone = ZoneId.of("Asia/Baghdad")
        val now = at(zone, 2026, 11, 14, 12)
        for (r in Repeat.entries.filter { it != Repeat.NONE }) {
            for (daysAgo in listOf(0L, 1L, 40L, 400L, 3000L)) {
                val start = now.minus(Duration.ofDays(daysAgo)).minusSeconds(17)
                val next = nextOccurrence(start, r, now, RepeatRule(days = listOf(2, 5), everyDays = 9, everyHours = 5, from = start), zone)
                assertTrue("$r from $daysAgo days ago gave $next", next.isAfter(now) && next.isAfter(start))
            }
        }
    }
}
