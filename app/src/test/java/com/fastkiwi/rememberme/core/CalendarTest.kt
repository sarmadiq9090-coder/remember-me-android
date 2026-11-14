package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Bucketing for the calendar views (F-064): week start, month grid, DST days, repeats, overdue, speed. */
class CalendarTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val baghdad = ZoneId.of("Asia/Baghdad")

    private fun task(
        id: String, due: Instant?, repeat: Repeat = Repeat.NONE, rule: RepeatRule = RepeatRule(),
        scheduleAt: Instant? = if (repeat != Repeat.NONE) due else null, snoozedAt: Instant? = null,
        extra: JsonObject = JsonObject(emptyMap()), completed: Boolean = false, deleted: Boolean = false,
    ) = Task(
        id = id, title = id, dueAt = due, scheduleAt = scheduleAt, repeat = repeat,
        rule = if (repeat == Repeat.NONE) RepeatRule() else rule.copy(from = rule.from ?: scheduleAt), snoozedAt = snoozedAt,
        completedAt = if (completed) Instant.EPOCH else null, deletedAt = if (deleted) Instant.EPOCH else null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, extra = extra,
    )

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun weekStartFollowsTheWindowsSetting() {
        val sat = d(2026, 11, 14) // a Saturday
        assertEquals(d(2026, 11, 14), Calendar.startOfWeek(sat, 6))
        assertEquals(d(2026, 11, 8), Calendar.startOfWeek(sat, 0))
        assertEquals(d(2026, 11, 9), Calendar.startOfWeek(sat, 1))
        assertEquals(d(2026, 11, 14), Calendar.startOfWeek(d(2026, 11, 20), 6))
        assertEquals(listOf(6, 0, 1, 2, 3, 4, 5), Calendar.weekdays(6))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), Calendar.weekdays(0))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 0), Calendar.weekdays(1))
        assertEquals((14..20).map { d(2026, 11, it) }, Calendar.week(sat, 6))
        assertEquals(6, Calendar.weekStartOf(JsonObject(emptyMap())))
        assertEquals(6, Calendar.weekStartOf(JsonObject(mapOf("weekStart" to JsonPrimitive(3)))))
        assertEquals(6, Calendar.weekStartOf(JsonObject(mapOf("weekStart" to JsonPrimitive("1")))))
        assertEquals(1, Calendar.weekStartOf(JsonObject(mapOf("weekStart" to JsonPrimitive(1)))))
        assertEquals(0, Calendar.weekStartOf(JsonObject(mapOf("weekStart" to JsonPrimitive(0)))))
    }

    @Test fun monthGridIsSixWeeksFromTheWeekStart() {
        val feb = YearMonth.of(2026, 2) // 1 February 2026 is a Sunday
        for ((ws, first) in listOf(6 to d(2026, 1, 31), 0 to d(2026, 2, 1), 1 to d(2026, 1, 26))) {
            val grid = Calendar.monthGrid(feb, ws)
            assertEquals(42, grid.size)
            assertEquals(first, grid.first())
            assertEquals(first.plusDays(41), grid.last())
            assertEquals(Calendar.firstDay(ws), grid.first().dayOfWeek)
            assertTrue(d(2026, 2, 28) in grid)
        }
        val now = at(berlin, 2026, 1, 1, 8)
        val b = Calendar.monthBuckets(emptyList(), feb, 6, now.atZone(berlin).toLocalDate(), now, berlin)
        assertEquals(Calendar.monthGrid(feb, 6), b.map { it.date })
    }

    @Test fun monthEndAndMonthlyRepeat() {
        val now = at(berlin, 2026, 1, 30, 8)
        val today = now.atZone(berlin).toLocalDate()
        val late = task("late", at(berlin, 2026, 1, 31, 23, 59))
        val monthly = task("rent", at(berlin, 2026, 1, 31, 9), Repeat.MONTHLY)
        val days = Calendar.monthBuckets(listOf(late, monthly), YearMonth.of(2026, 2), 6, today, now, berlin).associateBy { it.date }
        assertEquals(listOf("rent", "late"), days.getValue(d(2026, 1, 31)).items.map { it.task.id })
        assertTrue(days.getValue(d(2026, 2, 1)).items.isEmpty())
        // The 31st becomes the month's last day (the repeat engine), and it is only projected.
        val feb28 = days.getValue(d(2026, 2, 28)).items.single()
        assertEquals("rent", feb28.task.id)
        assertTrue(feb28.projected)
        assertEquals(LocalTime.of(9, 0), feb28.at!!.atZone(berlin).toLocalTime())
        assertEquals(2, days.values.sumOf { b -> b.items.count { it.task.id == "rent" } })
    }

    @Test fun berlinSpringForwardKeepsOneDailyTimePerDay() {
        val now = at(berlin, 2026, 3, 26, 12)
        val today = now.atZone(berlin).toLocalDate()
        val night = task("night", at(berlin, 2026, 3, 27, 2, 30), Repeat.DAILY)
        val late = task("late", at(berlin, 2026, 3, 27, 23, 30), Repeat.DAILY)
        val days = Calendar.buckets(listOf(night, late), d(2026, 3, 27), d(2026, 4, 2), today, now, berlin)
        for (b in days) assertEquals(b.date.toString(), listOf("night", "late"), b.items.map { it.task.id })
        // 2:30 does not exist on 29 March: it fires at 3:30 that day and is back at 2:30 the next.
        val gap = days.single { it.date == d(2026, 3, 29) }.items.first().at!!.atZone(berlin)
        assertEquals(LocalTime.of(3, 30), gap.toLocalTime())
        assertEquals(LocalTime.of(2, 30), days.single { it.date == d(2026, 3, 30) }.items.first().at!!.atZone(berlin).toLocalTime())
    }

    @Test fun berlinFallBackDayHas25Hours() {
        val now = at(berlin, 2026, 10, 24, 12)
        val today = now.atZone(berlin).toLocalDate()
        val hourly = task("h", at(berlin, 2026, 10, 25, 0), Repeat.HOURS, RepeatRule(everyHours = 1))
        val day = Calendar.day(listOf(hourly), d(2026, 10, 25), today, now, berlin)
        assertEquals(25, day.count)
        assertFalse(day.items.first().projected)
        // A one-time reminder at the second 2:30 (winter time) is still on the 25th.
        val second = java.time.ZonedDateTime.ofLocal(java.time.LocalDateTime.of(2026, 10, 25, 2, 30), berlin, java.time.ZoneOffset.ofHours(1)).toInstant()
        val b = Calendar.buckets(listOf(task("x", second)), d(2026, 10, 24), d(2026, 10, 27), today, now, berlin)
        assertEquals(listOf(0, 1, 0), b.map { it.count })
    }

    @Test fun newYorkDstDays() {
        val now = at(newYork, 2026, 3, 1, 12)
        val today = now.atZone(newYork).toLocalDate()
        val hourly = task("h", at(newYork, 2026, 3, 8, 0), Repeat.HOURS, RepeatRule(everyHours = 1))
        assertEquals(23, Calendar.day(listOf(hourly), d(2026, 3, 8), today, now, newYork).count)
        val nowFall = at(newYork, 2026, 10, 30, 12)
        val hourlyFall = task("h", at(newYork, 2026, 11, 1, 0), Repeat.HOURS, RepeatRule(everyHours = 1))
        assertEquals(25, Calendar.day(listOf(hourlyFall), d(2026, 11, 1), nowFall.atZone(newYork).toLocalDate(), nowFall, newYork).count)
        // A daily reminder just after midnight and one just before stay one per day across both changes.
        for ((start, end) in listOf(d(2026, 3, 6) to d(2026, 3, 11), d(2026, 10, 30) to d(2026, 11, 4))) {
            val n = start.minusDays(1).atTime(12, 0).atZone(newYork).toInstant()
            val early = task("early", start.atTime(0, 30).atZone(newYork).toInstant(), Repeat.DAILY)
            val late = task("late", start.atTime(23, 59).atZone(newYork).toInstant(), Repeat.DAILY)
            val days = Calendar.buckets(listOf(early, late), start, end, start.minusDays(1), n, newYork)
            for (b in days) assertEquals(b.date.toString(), listOf("early", "late"), b.items.map { it.task.id })
        }
    }

    @Test fun overdueRepeatProjectsFromNowAndAgendaFoldsOverdueIntoToday() {
        val now = at(baghdad, 2026, 11, 14, 16, 20)
        val today = d(2026, 11, 14)
        val pills = task("pills", at(baghdad, 2026, 11, 11, 14), Repeat.DAILY)
        val meeting = task("meeting", at(baghdad, 2026, 11, 14, 17, 30))
        val gift = task("gift", null)
        val old = task("old", at(baghdad, 2026, 11, 2, 9))
        val agenda = Calendar.agenda(listOf(pills, meeting, gift, old), today, now, baghdad)
        val first = agenda.first()
        assertEquals(today, first.date)
        assertEquals(listOf("old", "pills", "meeting", "gift"), first.items.map { it.task.id })
        assertTrue(first.items[0].overdue(now) && first.items[1].overdue(now))
        assertFalse(first.items[2].overdue(now))
        assertEquals(null, first.items[3].at)
        // Today's 2:00 PM has passed: the series comes back tomorrow, every day, for the agenda's 30 days.
        assertEquals(d(2026, 11, 15), agenda[1].date)
        assertTrue(agenda[1].items.single().projected)
        assertEquals(Calendar.AGENDA_DAYS.toInt(), agenda.sumOf { b -> b.items.count { it.task.id == "pills" && it.projected } } + 1)
        // In the grid the overdue reminder stays on its own day; no projected time is in the past.
        val grid = Calendar.monthBuckets(listOf(pills), YearMonth.of(2026, 11), 6, today, now, baghdad)
        assertEquals(1, grid.single { it.date == d(2026, 11, 11) }.count)
        assertTrue(grid.filter { it.date in d(2026, 11, 12)..d(2026, 11, 14) }.all { it.count == 0 })
        assertTrue(grid.flatMap { it.items }.filter { it.projected }.all { it.at!!.isAfter(now) })
    }

    @Test fun snoozedDoneTrashedUndatedAndAndroidOnlyRepeats() {
        val now = at(baghdad, 2026, 11, 14, 9, 30)
        val today = d(2026, 11, 14)
        val snoozed = task("snoozed", at(baghdad, 2026, 11, 14, 10), Repeat.DAILY, scheduleAt = at(baghdad, 2026, 11, 14, 9), snoozedAt = now.minusSeconds(60))
        val done = task("done", at(baghdad, 2026, 11, 14, 11), completed = true)
        val trashed = task("trashed", at(baghdad, 2026, 11, 14, 11), deleted = true)
        val undated = task("undated", null)
        val hijri = task("eid", at(baghdad, 2026, 11, 20, 9), Repeat.YEARLY,
            extra = JsonObject(mapOf(AlertExt.KEY to JsonObject(mapOf("hijri" to JsonObject(mapOf("month" to JsonPrimitive(10), "day" to JsonPrimitive(1))))))))
        val tasks = listOf(snoozed, done, trashed, undated, hijri)
        val week = Calendar.weekBuckets(tasks, today, 6, today, now, baghdad)
        val sat = week.first()
        assertEquals(listOf("snoozed", "undated"), sat.items.map { it.task.id })
        assertTrue(sat.items[0].snoozed)
        assertEquals(1, week[1].count)
        assertFalse(week[1].items.single().snoozed)
        assertEquals(LocalTime.of(9, 0), week[1].items.single().at!!.atZone(baghdad).toLocalTime())
        // Undated reminders appear on today only.
        assertEquals(1, week.sumOf { b -> b.items.count { it.task.id == "undated" } })
        // A Hijri-yearly reminder shows its current time and no Gregorian projection.
        val year = Calendar.buckets(tasks, today, today.plusDays(800), today, now, baghdad)
        assertEquals(1, year.sumOf { b -> b.items.count { it.task.id == "eid" } })
        // A custom projector is used when given.
        val custom = Calendar.Projector { t, c, a, z -> if (t.id == "eid") c.plusSeconds(355L * 86_400).let { if (it.isAfter(a)) it else null } else Calendar.engine.next(t, c, a, z) }
        assertEquals(3, Calendar.buckets(tasks, today, today.plusDays(800), today, now, baghdad, custom).sumOf { b -> b.items.count { it.task.id == "eid" } })
    }

    @Test fun fiveThousandTasksBucketQuickly() {
        val zone = baghdad
        val now = at(zone, 2026, 11, 14, 16, 20)
        val today = d(2026, 11, 14)
        val modes = listOf(Repeat.DAILY, Repeat.WEEKLY, Repeat.DAYS, Repeat.INTERVAL, Repeat.MONTHLY, Repeat.YEARLY)
        val tasks = (0 until 5_000).map { i ->
            val due = now.plusSeconds((i % 90 - 30) * 86_400L + (i % 24) * 3_600L)
            when {
                i % 500 == 0 -> task("t$i", due, Repeat.HOURS, RepeatRule(everyHours = 3))
                i % 5 == 0 -> task("t$i", due, modes[i / 5 % modes.size], RepeatRule(days = listOf(0, 3, 5), everyDays = 3))
                i % 50 == 1 -> task("t$i", null)
                else -> task("t$i", due)
            }
        }
        fun run() = Calendar.monthBuckets(tasks, YearMonth.of(2026, 11), 6, today, now, zone)
        fun runAgenda() = Calendar.agenda(tasks, today, now, zone)
        repeat(3) { run(); runAgenda() } // JIT warm-up
        val month = (1..5).minOf { val t = System.nanoTime(); run(); (System.nanoTime() - t) / 1_000_000 }
        val agenda = (1..5).minOf { val t = System.nanoTime(); runAgenda(); (System.nanoTime() - t) / 1_000_000 }
        assertTrue("month $month ms", month < 100)
        assertTrue("agenda $agenda ms", agenda < 100)
        assertEquals(42, run().size)
    }
}
