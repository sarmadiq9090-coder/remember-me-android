package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.random.Random

/**
 * Time (torture suite 2), on the engine: the clock set forwards and backwards by hours and days, a time-zone
 * change while a reminder is due, and the DST nights. Requirement: no repeated sound for one occurrence, no lost
 * reminder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36])
class TimeEngineTortureTest {
    private val h = EngineHarness()

    @Before fun setUp() { Torture.assumeOn(); h.setUp() }
    @After fun tearDown() { if (Torture.on) h.tearDown() }

    private fun save(input: TaskInput, at: Instant) = Repo.store(h.app).change { Tasks.save(it, input, at, h.zone).first }.tasks.last()

    /** Random clock jumps of hours and days, forwards and back: every reminder that came due rang, none twice. */
    @Test fun randomClockJumpsNeverLoseOrRepeatAReminder() {
        val seed = Torture.seed("time-jumps", 4001)
        val rnd = Random(seed)
        val base = Instant.parse("2026-11-15T06:00:00Z")
        var s = AppState()
        for (i in 0 until 30) s = Tasks.save(s, TaskInput(title = "r$i", dueAt = base.plusSeconds(rnd.nextLong(0, 6L * 86_400)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),
            base.minusSeconds(3600), h.zone).first
        h.put(s)
        var clock = base
        var latest = base
        // Due while more than eight were waiting: by design those are counted in the summary, not rung.
        val capped = HashSet<String>()
        repeat(Torture.count(150, 600)) {
            val jump = listOf(-86_400L, -7_200L, -3_600L, 600L, 3_600L, 7_200L, 43_200L, 86_400L)[rnd.nextInt(8)]
            clock = clock.plusSeconds(jump + rnd.nextLong(0, 300))
            if (clock.isBefore(base)) clock = base
            if (clock.isAfter(latest)) latest = clock
            val plan = Engine.sync(h.app, clock, "time")
            plan.due.dropLast(com.fastkiwi.rememberme.alarm.Notifier.MAX_SHOWN).forEach { capped += it.id }
            // The user deals with some of what is on screen.
            for (id in h.dueShown()) if (rnd.nextInt(3) == 0) {
                val t = Repo.state(h.app).task(id) ?: continue
                Repo.store(h.app).change { Tasks.complete(it, id, clock, h.zone, t.dueAt, checked = true)?.first ?: it }
                Engine.sync(h.app, clock, "change")
            }
        }
        Engine.sync(h.app, latest.plusSeconds(1), "time")
        val came = s.tasks.filter { !it.dueAt!!.isAfter(latest) }
        val repeated = came.filter { h.ringsOf(it.id) > 1 }
        val never = came.filter { h.ringsOf(it.id) == 0 && it.id !in capped }
        Torture.report("time", "clock jumps (SDK ${android.os.Build.VERSION.SDK_INT}, seed $seed): ${came.size} came due, ${repeated.size} rang more than once, ${never.size} never rang, ${capped.size} capped")
        assertTrue("never rang: ${never.map { it.title }}", never.isEmpty())
        assertTrue("rang again after the clock went back and forward: ${repeated.map { it.title + "×" + h.ringsOf(it.id) }}", repeated.isEmpty())
    }

    /** The plain case of the same thing: the clock goes back an hour after a reminder rang, then the time comes again. */
    @Test fun theClockSetBackAndForwardDoesNotRingTheSameOccurrenceTwice() {
        val now = Torture.now()
        val t = save(TaskInput(title = "x", dueAt = now.plusSeconds(1800)), now)
        Engine.sync(h.app, now.plusSeconds(1801), "alarm")
        assertEquals(1, h.ringsOf(t.id))
        Engine.sync(h.app, now, "time")      // set back an hour
        Engine.sync(h.app, now.plusSeconds(1802), "alarm")  // the time comes again
        assertEquals("the same occurrence rang twice", 1, h.ringsOf(t.id))
    }

    /** A time-zone change while a reminder is due: still shown, no new sound, the next alarm unchanged. */
    @Test fun aTimeZoneChangeWhileDueKeepsItShownWithoutASecondSound() {
        val now = Torture.now()
        val due = save(TaskInput(title = "due", dueAt = now.minusSeconds(120)), now.minusSeconds(3600))
        val daily = save(TaskInput(title = "daily", dueAt = now.minusSeconds(60), repeat = Repeat.DAILY), now.minusSeconds(3600))
        val later = save(TaskInput(title = "later", dueAt = now.plusSeconds(7200)), now.minusSeconds(3600))
        Engine.sync(h.app, now)
        assertEquals(1, h.ringsOf(due.id)); assertEquals(1, h.ringsOf(daily.id))
        for (tz in listOf("Asia/Tokyo", "America/Los_Angeles", "Pacific/Kiritimati", "Asia/Kathmandu")) {
            TimeZone.setDefault(TimeZone.getTimeZone(tz))
            Engine.sync(h.app, now.plusSeconds(5), "zone")
            assertTrue(h.dueShown().containsAll(listOf(due.id, daily.id)))
            assertEquals(later.dueAt!!.toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
        }
        assertEquals(1, h.ringsOf(due.id)); assertEquals(1, h.ringsOf(daily.id))
        // Done on the daily one in the new zone: the next occurrence is at the series' wall time, in the future.
        ActionReceiver.handle(h.app, ActionReceiver.DONE, daily.id, daily.dueAt, Torture.now())
        val next = Repo.state(h.app).task(daily.id)!!.dueAt!!
        assertTrue(next.isAfter(Torture.now()))
    }

    /** DST nights in Berlin and New York: a daily reminder at 02:30 rings once per day, also on the night without 02:30. */
    @Test fun dailyAtTwoThirtyAcrossDstNightsRingsOncePerDay() {
        for (zone in listOf(ZoneId.of("Europe/Berlin"), ZoneId.of("America/New_York"), ZoneId.of("Australia/Sydney"))) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val tr = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
            val first = ZonedDateTime.of(LocalDateTime.of(tr.dateTimeBefore.toLocalDate().minusDays(2), java.time.LocalTime.of(2, 30)), zone).toInstant()
            val t = Repo.store(h.app).change { Tasks.save(it, TaskInput(title = "dst ${zone.id}", dueAt = first, repeat = Repeat.DAILY), first.minusSeconds(3600), zone).first }.tasks.last()
            // Five days: each alarm fires on time, the reminder is done a minute later.
            repeat(5) {
                val cur = Repo.state(h.app).task(t.id)!!
                Engine.sync(h.app, cur.dueAt!!.plusMillis(200), "alarm")
                Engine.sync(h.app, cur.dueAt.plusSeconds(30), "start")
                val doneAt = cur.dueAt.plusSeconds(60)
                Repo.store(h.app).change { Tasks.complete(it, t.id, doneAt, zone, cur.dueAt, checked = true)!!.first }
                Engine.sync(h.app, doneAt, "change")
            }
            assertEquals("${zone.id}: one sound per day", 5, h.ringsOf(t.id))
            Repo.store(h.app).change { s -> s.copy(tasks = s.tasks.filter { it.id != t.id }) }
            h.app.getSharedPreferences("device", 0).edit().clear().commit()
            org.robolectric.Shadows.shadowOf(h.app).clearStartedServices()
        }
    }

    /** Undo after «تم» from the notification brings the reminder back; it must not ring a second time for it. */
    @Test fun undoAfterDoneDoesNotRingTheSameOccurrenceAgain() {
        val now = Torture.now()
        val t = save(TaskInput(title = "daily", dueAt = now.minusSeconds(60), repeat = Repeat.DAILY), now.minusSeconds(3600))
        Engine.sync(h.app)
        assertEquals(1, h.ringsOf(t.id))
        ActionReceiver.handle(h.app, ActionReceiver.DONE, t.id, t.dueAt, Torture.now())
        val copy = Repo.state(h.app).tasks.single { it.sourceId == t.id }
        ActionReceiver.handle(h.app, ActionReceiver.UNDO, t.id, null, Torture.now(), copy.id)
        assertEquals(t.dueAt, Repo.state(h.app).task(t.id)!!.dueAt)
        assertTrue("shown again", h.dueShown().contains(t.id))
        assertEquals("rang again after undo", 1, h.ringsOf(t.id))
    }
}
