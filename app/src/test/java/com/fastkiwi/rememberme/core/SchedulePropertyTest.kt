package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * D-079 / PRD P0.2 acceptance: no reminder lost, none announced twice, no alarm in the past.
 * - 36 time-zone, clock and reboot scenarios across zones with and without DST.
 * - 1,000 random sequences of actions, each re-planned twice (an idempotent rebuild must change nothing).
 */
class SchedulePropertyTest {
    @Before fun ids() = T.ids()

    /** The invariants every plan must keep at [now]. */
    private fun check(state: AppState, plan: Plan, now: Instant, label: String) {
        val due = plan.due.map { it.id }
        assertEquals("$label: a due reminder listed twice", due.size, due.toSet().size)
        for (t in state.tasks.filter { it.active && it.dueAt != null }) {
            if (!t.dueAt!!.isAfter(now)) assertTrue("$label: due ${t.title} missing from the queue", t.id in due)
            // Every future due time is covered by slot A at or before it: none is lost.
            else assertTrue("$label: ${t.title} at ${t.dueAt} not covered by slot A ${plan.dueAt}", plan.dueAt != null && !plan.dueAt.isAfter(t.dueAt))
        }
        plan.dueAt?.let { assertTrue("$label: slot A in the past", it.isAfter(now)) }
        plan.otherAt?.let { assertTrue("$label: slot B in the past", it.isAfter(now)) }
    }

    private fun scenarioState(zone: ZoneId, start: Instant): AppState {
        var s = AppState()
        fun add(i: TaskInput) { s = Tasks.save(s, i, start.minusSeconds(60), zone).first }
        add(TaskInput(title = "daily", dueAt = start.plusSeconds(3600), repeat = Repeat.DAILY, remindBefore = 15))
        add(TaskInput(title = "weekly", dueAt = start.plusSeconds(86_400 * 2), repeat = Repeat.WEEKLY))
        add(TaskInput(title = "monthly31", dueAt = at(zone, 2027, 1, 31, 9), repeat = Repeat.MONTHLY))
        add(TaskInput(title = "hours", dueAt = start.plusSeconds(1800), repeat = Repeat.HOURS, everyHours = 3))
        add(TaskInput(title = "once", dueAt = start.plusSeconds(7200), remindBefore = 60))
        add(TaskInput(title = "same-minute-a", dueAt = start.plusSeconds(5400)))
        add(TaskInput(title = "same-minute-b", dueAt = start.plusSeconds(5400)))
        return s
    }

    @Test fun thirtySixTimeZoneClockAndRebootScenarios() {
        val zones = listOf("Asia/Baghdad", "Asia/Dubai", "Europe/Berlin", "America/New_York", "Africa/Cairo", "Australia/Sydney").map(ZoneId::of)
        // Each event: how the clock and the zone move, and whether the logs survive (a reboot keeps them).
        data class Event(val name: String, val jump: Duration, val zoneShift: Boolean, val keepLogs: Boolean)
        val events = listOf(
            Event("clock +2h", Duration.ofHours(2), false, true), Event("clock -2h", Duration.ofHours(-2), false, true),
            Event("zone move", Duration.ZERO, true, true), Event("reboot after 3h", Duration.ofHours(3), false, true),
            Event("reboot after 2 days off", Duration.ofDays(2), false, true), Event("data restored on a new phone", Duration.ofHours(1), false, false),
        )
        var count = 0
        for (zone in zones) for (e in events) {
            val start = at(zone, 2027, 3, 13, 23, 0) // the night of New York's DST change, 14 March 2027
            val s0 = scenarioState(zone, start)
            val p0 = Planner.plan(s0, start, 5, emptyMap(), emptyMap())
            check(s0, p0, start, "${zone.id}/${e.name}/before")
            val now = start.plus(e.jump)
            val z2 = if (e.zoneShift) ZoneId.of("Pacific/Kiritimati") else zone
            // Repeats re-anchored in the new zone keep working; complete one to exercise nextOccurrence there.
            val s1 = Tasks.complete(s0, s0.tasks.first().id, now, z2)?.first ?: s0
            val logs = if (e.keepLogs) p0.alertLog to p0.noticeLog else emptyMap<String, String>() to emptyMap()
            val p1 = Planner.plan(s1, now, 5, logs.first, logs.second)
            check(s1, p1, now, "${zone.id}/${e.name}/after")
            // Planning again at the same moment changes nothing and sounds nothing new.
            val p2 = Planner.plan(s1, now, 5, p1.alertLog, p1.noticeLog)
            assertTrue("${zone.id}/${e.name}: a rebuild sounded again ${p2.loud}", p2.loud.isEmpty())
            assertEquals(p1.dueAt, p2.dueAt)
            assertEquals(p1.otherAt, p2.otherAt)
            count++
        }
        assertEquals(36, count)
    }

    @Test fun aThousandRandomSequencesNeverLoseOrDoubleAReminder() {
        val zone = ZoneId.of("Europe/Berlin")
        val rnd = Random(20261114)
        repeat(1000) { seq ->
            var now = at(zone, 2027, 3, 27, 22, 0) // across Europe's DST change on 28 March 2027
            var s = AppState()
            var alertLog = emptyMap<String, String>()
            var noticeLog = emptyMap<String, String>()
            val soundedAt = HashMap<String, MutableSet<String>>()
            repeat(25) { step ->
                val active = s.tasks.filter { it.active }
                when (rnd.nextInt(8)) {
                    0, 1 -> s = Tasks.save(s, TaskInput(
                        title = "t$step", dueAt = now.plusSeconds(rnd.nextLong(-600, 7200)),
                        repeat = listOf(Repeat.NONE, Repeat.DAILY, Repeat.HOURS, Repeat.WEEKLY).random(rnd), everyHours = 2,
                        remindBefore = listOf(0, 5, 15).random(rnd),
                    ), now, zone).first
                    2 -> active.randomOrNull(rnd)?.let { t -> Tasks.complete(s, t.id, now, zone)?.let { s = it.first } }
                    3 -> active.filter { it.dueAt != null }.randomOrNull(rnd)?.let { t -> Tasks.snooze(s, t.id, now.plusSeconds(600), now)?.let { s = it } }
                    4 -> active.filter { it.repeat != Repeat.NONE }.randomOrNull(rnd)?.let { t -> Tasks.skip(s, t.id, now, zone)?.let { s = it } }
                    5 -> active.randomOrNull(rnd)?.let { t -> s = Tasks.trash(s, t.id, now) }
                    else -> now = now.plusSeconds(rnd.nextLong(30, 2400))
                }
                val p = Planner.plan(s, now, listOf(0, 2, 5).random(rnd), alertLog, noticeLog)
                check(s, p, now, "seq $seq step $step")
                // A sound is for one occurrence and one "sound again" slot only once.
                for (id in p.loud) {
                    val key = p.alertLog[id]!!
                    assertTrue("seq $seq step $step: $id sounded twice for $key", soundedAt.getOrPut(id) { HashSet() }.add(key))
                }
                // The idempotent rebuild: the same moment, the logs it just wrote.
                val again = Planner.plan(s, now, 0, p.alertLog, p.noticeLog)
                assertTrue("seq $seq step $step: rebuild sounded ${again.loud}", again.loud.isEmpty())
                alertLog = p.alertLog
                noticeLog = p.noticeLog
            }
        }
    }
}
