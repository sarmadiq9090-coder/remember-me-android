package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.RepeatRule
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.nextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Time (torture suite 2), the pure part: every repeat mode walked across DST transitions (Berlin, New York,
 * Baghdad without DST, Tehran's historic DST, Sydney in the south, Lord Howe's 30-minute DST, Santiago's midnight
 * change), month ends and 29 February over 50 years, and catching up after long gaps. The expected occurrences
 * come from plain calendar arithmetic written here, not from the app.
 */
class TimeRepeatTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val zones = listOf("Europe/Berlin", "America/New_York", "Asia/Baghdad", "Asia/Tehran", "Australia/Sydney", "Australia/Lord_Howe", "America/Santiago").map(ZoneId::of)

    /** The calendar date of occurrence [k] (0 = the start). */
    private fun dateOf(repeat: Repeat, start: LocalDate, k: Int, rule: RepeatRule, anchorDay: Int): LocalDate = when (repeat) {
        Repeat.DAILY -> start.plusDays(k.toLong())
        Repeat.WEEKLY -> start.plusDays(7L * k)
        Repeat.INTERVAL -> start.plusDays(rule.everyDays.toLong() * k)
        Repeat.MONTHLY, Repeat.YEARLY -> {
            val ym = YearMonth.from(start).plusMonths(k.toLong() * (if (repeat == Repeat.YEARLY) 12 else 1))
            ym.atDay(minOf(anchorDay, ym.lengthOfMonth()))
        }
        Repeat.DAYS -> {
            var d = start; var n = 0
            while (n < k) { d = d.plusDays(1); if (jsDay(d) in rule.days) n++ }
            d
        }
        else -> error("no calendar date for $repeat")
    }

    /** A local date and time as JavaScript's Date builds it: a time in a gap moves forward by the gap. */
    private fun wall(date: LocalDate, time: LocalTime, zone: ZoneId): Instant = ZonedDateTime.of(LocalDateTime.of(date, time), zone).toInstant()

    /** The series as calendar arithmetic: occurrence 0 is the start. */
    private fun series(repeat: Repeat, start: Instant, rule: RepeatRule, zone: ZoneId): Sequence<Instant> {
        if (repeat == Repeat.HOURS) return generateSequence(0L) { it + 1 }.map { start.plus(Duration.ofHours(rule.everyHours.toLong() * it)) }
        val z = start.atZone(zone)
        val tod = (rule.from ?: start).atZone(zone).toLocalTime()
        val anchor = (rule.from ?: start).atZone(zone).dayOfMonth
        if (repeat == Repeat.DAYS) return generateSequence(z.toLocalDate()) { d ->
            var x = d.plusDays(1); while (jsDay(x) !in rule.days) x = x.plusDays(1); x
        }.map { wall(it, tod, zone) }
        return generateSequence(0) { it + 1 }.map { wall(dateOf(repeat, z.toLocalDate(), it, rule, anchor), tod, zone) }
    }

    private fun expected(repeat: Repeat, start: Instant, rule: RepeatRule, zone: ZoneId, n: Int): List<Instant> = series(repeat, start, rule, zone).take(n).toList()

    private fun chain(repeat: Repeat, start: Instant, rule: RepeatRule, zone: ZoneId, n: Int): List<Instant> {
        val out = arrayListOf(start)
        var cur = start
        repeat(n - 1) { cur = nextOccurrence(cur, repeat, cur, rule, zone); out += cur }
        return out
    }

    private fun describe(list: List<Instant>, zone: ZoneId) = list.joinToString { it.atZone(zone).toLocalDateTime().toString() + it.atZone(zone).offset }

    @Test fun everyRepeatModeAcrossDstTransitions() {
        val failures = ArrayList<String>()
        var cases = 0
        for (zone in zones) {
            val years = if (zone.id == "Asia/Tehran") 2015..2021 else 2025..2028
            val rules = zone.rules
            val transitions = years.flatMap { y ->
                generateSequence(rules.nextTransition(LocalDate.of(y, 1, 1).atStartOfDay(zone).toInstant())) { rules.nextTransition(it.instant) }
                    .takeWhile { it.instant.atZone(zone).year == y }.toList()
            }
            // Baghdad has had no DST since 2008: its dates are plain ones around the same days.
            val days = if (transitions.isEmpty()) listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25)) else transitions.map { it.dateTimeBefore.toLocalDate() }
            val times = (listOf(LocalTime.of(0, 30), LocalTime.of(2, 30), LocalTime.of(9, 0), LocalTime.of(23, 30)) +
                transitions.map { it.dateTimeBefore.toLocalTime() } + transitions.map { it.dateTimeBefore.toLocalTime().plusMinutes(15) }).distinct()
            for (day in days) for (time in times) for (repeat in listOf(Repeat.DAILY, Repeat.WEEKLY, Repeat.DAYS, Repeat.INTERVAL, Repeat.MONTHLY, Repeat.YEARLY, Repeat.HOURS)) {
                // Start before the change so the walk crosses it (a month before for monthly, a year for yearly).
                val back = when (repeat) { Repeat.MONTHLY -> 62L; Repeat.YEARLY -> 800L; Repeat.WEEKLY -> 21L; Repeat.INTERVAL -> 9L; Repeat.HOURS -> 1L; else -> 3L }
                val startDate = day.minusDays(back)
                val start = wall(startDate, time, zone)
                val rule = when (repeat) {
                    Repeat.DAYS -> RepeatRule(days = listOf(jsDay(startDate), (jsDay(startDate) + 3) % 7).distinct().sorted(), from = start)
                    Repeat.INTERVAL -> RepeatRule(everyDays = 3, from = start)
                    Repeat.HOURS -> RepeatRule(everyHours = 5, from = start)
                    else -> RepeatRule(from = start)
                }
                val n = if (repeat == Repeat.HOURS) 20 else 12
                cases++
                val want = expected(repeat, start, rule, zone, n)
                val got = chain(repeat, start, rule, zone, n)
                if (want != got) failures += "${zone.id} $repeat from ${start.atZone(zone).toLocalDateTime()}:\n  want ${describe(want, zone)}\n  got  ${describe(got, zone)}"
                // The wall-clock time comes back after the change (a gap shifts only that occurrence).
                if (repeat != Repeat.HOURS) {
                    val tod = start.atZone(zone).toLocalTime()
                    for (at in got) {
                        val local = at.atZone(zone).toLocalDateTime()
                        val exists = zone.rules.getValidOffsets(LocalDateTime.of(local.toLocalDate(), tod)).isNotEmpty()
                        if (exists && local.toLocalTime() != tod) failures += "${zone.id} $repeat: ${local} is not at $tod"
                    }
                }
            }
        }
        println("[torture] DST walk: $cases series")
        assertEquals(failures.take(8).joinToString("\n"), 0, failures.size)
    }

    @Test fun fiftyYearsOfMonthEndsAndLeapDays() {
        val failures = ArrayList<String>()
        for (zone in listOf(ZoneId.of("Asia/Baghdad"), ZoneId.of("America/New_York"), ZoneId.of("Australia/Sydney"))) {
            for ((repeat, start, n) in listOf(
                Triple(Repeat.MONTHLY, LocalDateTime.of(2026, 1, 31, 9, 0), 600),
                Triple(Repeat.MONTHLY, LocalDateTime.of(2026, 1, 30, 23, 59), 600),
                Triple(Repeat.MONTHLY, LocalDateTime.of(2028, 2, 29, 0, 0), 600),
                Triple(Repeat.YEARLY, LocalDateTime.of(2028, 2, 29, 8, 0), 50),
                Triple(Repeat.YEARLY, LocalDateTime.of(2027, 12, 31, 2, 30), 50),
            )) {
                val at = ZonedDateTime.of(start, zone).toInstant()
                val rule = RepeatRule(from = at)
                val want = expected(repeat, at, rule, zone, n)
                val got = chain(repeat, at, rule, zone, n)
                val diff = want.indices.firstOrNull { want[it] != got[it] }
                if (diff != null) failures += "${zone.id} $repeat from $start: occurrence $diff want ${want[diff].atZone(zone)} got ${got[diff].atZone(zone)}"
                // The leap day comes back in every leap year.
                if (start.monthValue == 2 && start.dayOfMonth == 29 && repeat == Repeat.YEARLY) {
                    val leap = got.map { it.atZone(zone).toLocalDate() }.filter { it.isLeapYear }
                    if (!leap.all { it.dayOfMonth == 29 }) failures += "${zone.id}: leap years without 29 February: $leap"
                }
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /**
     * The same 50 years through the real operations: each occurrence is snoozed or not, then done some time
     * after it came, and the data is written to JSON and read back between steps (as the store does). The series
     * must keep its day (31st, 29 February) and time whatever happens on the way.
     */
    @Test fun completingSnoozingAndSavingNeverLosesTheAnchor() {
        val seed = Torture.seed("time-anchor", 3001)
        val rnd = Random(seed)
        val failures = ArrayList<String>()
        for (zone in listOf(ZoneId.of("Asia/Baghdad"), ZoneId.of("Europe/Berlin"), ZoneId.of("Australia/Sydney"))) {
            for ((repeat, start, n) in listOf(
                Triple(Repeat.MONTHLY, LocalDateTime.of(2026, 1, 31, 9, 0), 600),
                Triple(Repeat.YEARLY, LocalDateTime.of(2028, 2, 29, 8, 0), 50),
                Triple(Repeat.DAILY, LocalDateTime.of(2026, 3, 20, 2, 30), 400),
                Triple(Repeat.WEEKLY, LocalDateTime.of(2026, 3, 1, 2, 30), 200),
            )) {
                val first = ZonedDateTime.of(start, zone).toInstant()
                var (s, t) = Tasks.save(AppState(), TaskInput(title = "x", dueAt = first, repeat = repeat), first.minusSeconds(3600), zone)
                val want = expected(repeat, first, t.rule, zone, n)
                for (k in 1 until n) {
                    val cur = s.task(t.id)!!
                    val sched = cur.scheduleAt!!
                    var now = sched.plusSeconds(rnd.nextLong(0, 3600))
                    if (rnd.nextInt(3) == 0) {
                        s = Tasks.snooze(s, t.id, now.plusSeconds(600), now, cur.dueAt, checked = true)!!
                        now = now.plusSeconds(700)
                    }
                    if (rnd.nextInt(5) == 0) s = Tasks.save(s, TaskInput(id = t.id, title = "edited $k", dueAt = s.task(t.id)!!.dueAt, repeat = repeat,
                        updatedAt = s.task(t.id)!!.updatedAt, timeEdited = false), now, zone).first
                    s = Tasks.complete(s, t.id, now, zone, s.task(t.id)!!.dueAt, checked = true)!!.first
                    // Written and read back: only what data.json holds survives.
                    s = Codec.decode(Codec.encode(s), now)
                    s = s.copy(tasks = s.tasks.filter { it.id == t.id })
                    val got = s.task(t.id)!!.scheduleAt!!
                    if (got != want[k]) { failures += "${zone.id} $repeat from $start: step $k want ${want[k].atZone(zone)} got ${got.atZone(zone)}"; break }
                }
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /** After a long gap (phone off, app not opened), the next occurrence is the first one after now, never a later one. */
    @Test fun catchingUpAfterLongGapsSkipsNothingAhead() {
        val seed = Torture.seed("time-catchup", 3002)
        val rnd = Random(seed)
        val failures = ArrayList<String>()
        repeat(Torture.count(3_000, 20_000)) {
            val zone = zones[rnd.nextInt(zones.size)]
            val repeat = listOf(Repeat.DAILY, Repeat.WEEKLY, Repeat.DAYS, Repeat.INTERVAL, Repeat.MONTHLY, Repeat.YEARLY, Repeat.HOURS)[rnd.nextInt(7)]
            val startLocal = LocalDateTime.of(rnd.nextInt(2016, 2030), rnd.nextInt(1, 13), rnd.nextInt(1, 29), rnd.nextInt(0, 24), listOf(0, 15, 30, 59)[rnd.nextInt(4)])
                .plusDays(listOf(0L, 0L, 1L, 2L, 3L)[rnd.nextInt(5)])
            val start = ZonedDateTime.of(startLocal, zone).toInstant()
            val rule = when (repeat) {
                Repeat.DAYS -> RepeatRule(days = (0..6).filter { rnd.nextBoolean() }.ifEmpty { listOf(jsDay(start.atZone(zone).toLocalDate())) }, from = start)
                Repeat.INTERVAL -> RepeatRule(everyDays = rnd.nextInt(2, 366), from = start)
                Repeat.HOURS -> RepeatRule(everyHours = rnd.nextInt(1, 24), from = start)
                else -> RepeatRule(from = start)
            }
            // DAYS needs its start on one of its days for the plain walk to be the series.
            if (repeat == Repeat.DAYS && jsDay(start.atZone(zone).toLocalDate()) !in rule.days) return@repeat
            val gap = listOf(3_600L, 86_400L, 9L * 86_400, 40L * 86_400, 400L * 86_400, 3_000L * 86_400)[rnd.nextInt(6)]
            val now = start.plusSeconds(rnd.nextLong(1, gap))
            val want = if (repeat == Repeat.HOURS) {
                val step = rule.everyHours * 3_600_000L
                start.plusMillis(((now.toEpochMilli() - start.toEpochMilli()) / step + 1) * step)
            } else series(repeat, start, rule, zone).first { it.isAfter(now) }
            val got = nextOccurrence(start, repeat, now, rule, zone)
            if (got != want) failures += "${zone.id} $repeat ${rule.days}/${rule.everyDays}/${rule.everyHours} from ${start.atZone(zone).toLocalDateTime()} now ${now.atZone(zone).toLocalDateTime()}: want ${want.atZone(zone)} got ${got.atZone(zone)}"
        }
        assertEquals(failures.take(8).joinToString("\n"), 0, failures.size)
    }
}
