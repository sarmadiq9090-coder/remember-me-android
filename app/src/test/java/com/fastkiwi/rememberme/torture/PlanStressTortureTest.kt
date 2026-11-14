package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Planner
import com.fastkiwi.rememberme.core.Plan
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.WakeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Stress on the pure planner (torture suite 1): 10,000 reminders walked forward in time, minute by minute around
 * busy moments and in big jumps, with the logs carried from run to run as the engine does. Every occurrence is
 * loud exactly once (remind-again off) or once per remind-again slot, every notice is shown once, and the slots
 * are always the earliest moments ahead.
 */
class PlanStressTortureTest {
    @Before fun on() = Torture.assumeOn()

    private fun checkPlan(s: AppState, now: Instant, p: Plan) {
        val due = Tasks.due(s, now)
        assertEquals(due.map { it.id }, p.due.map { it.id })
        assertTrue(p.loud.all { id -> due.any { it.id == id } })
        assertEquals(due.map { it.id }.toSet(), p.alertLog.keys)
        val futureDue = s.tasks.filter { it.active && it.dueAt != null && it.dueAt.isAfter(now) }.minOfOrNull { it.dueAt!! }
        assertEquals("slot A is the next due time", futureDue, p.dueAt)
        val nextNotice = s.tasks.filter { it.active && it.dueAt != null }
            .flatMap { t -> Tasks.noticeTimes(t).filter { it.isAfter(t.updatedAt) && it.isAfter(now) } }.minOrNull()
        if (p.otherKind == WakeKind.NOTICE || p.otherAt == null) {
            assertTrue("slot B ${p.otherAt} later than the next notice $nextNotice", nextNotice == null || (p.otherAt != null && !p.otherAt!!.isAfter(nextNotice)))
        }
        val earliest = listOfNotNull(p.dueAt, p.otherAt).minOrNull()
        assertEquals(earliest, p.wakeAt)
    }

    private fun walk(n: Int, again: Int, seed: Long) {
        val rnd = Random(seed)
        val gen = Gen(rnd)
        val zone = ZoneId.of("Asia/Baghdad")
        val start = Instant.parse("2026-11-15T06:00:00Z")
        val s = gen.state(n, start, zone, spreadSeconds = 2L * 86_400)
        var alertLog = emptyMap<String, String>()
        var noticeLog = emptyMap<String, String>()
        val loudCount = HashMap<String, Int>()
        val noticeCount = HashMap<String, Int>()
        var now = start.minusSeconds(2L * 86_400 + 3600)
        val end = start.plusSeconds(3L * 86_400)
        var runs = 0
        while (now.isBefore(end)) {
            val p = Planner.plan(s, now, again, alertLog, noticeLog)
            checkPlan(s, now, p)
            for (id in p.loud) loudCount.merge(id + "|" + Codec.iso(s.task(id)!!.dueAt!!), 1, Int::plus)
            for (t in p.notices) noticeCount.merge(t.id + "|" + Tasks.noticeKey(t, now), 1, Int::plus)
            // The same moment again (a second receiver): nothing new.
            val q = Planner.plan(s, now, again, p.alertLog, p.noticeLog)
            assertTrue("a second run at the same moment is loud for ${q.loud}", q.loud.isEmpty())
            assertTrue(q.notices.isEmpty())
            alertLog = p.alertLog; noticeLog = p.noticeLog
            runs++
            // Wake when the plan says, sometimes late (Doze), sometimes a random app open in between.
            val wake = p.wakeAt ?: break
            now = when (rnd.nextInt(10)) {
                0 -> now.plusSeconds(rnd.nextLong(1, 7200))
                1 -> wake.plusSeconds(rnd.nextLong(0, 900))
                else -> wake
            }
        }
        val maxLoud = if (again == 0) 1 else Planner.MAX_AGAIN + 1
        val over = loudCount.filterValues { it > maxLoud }
        assertTrue("occurrences loud more than $maxLoud times: ${over.entries.take(5)}", over.isEmpty())
        assertTrue("notices shown twice: ${noticeCount.filterValues { it > 1 }.entries.take(5)}", noticeCount.values.all { it == 1 })
        // Nothing that came due in the walk was never loud.
        val dueEver = s.tasks.filter { it.active && it.dueAt != null && it.dueAt.isBefore(now) }
        val never = dueEver.filter { t -> loudCount.keys.none { it.startsWith(t.id + "|") } }
        assertTrue("due but never announced: ${never.take(5).map { it.id + " " + it.dueAt }}", never.isEmpty())
        println("[torture] planner walk n=$n again=$again: $runs runs, ${loudCount.size} occurrences, ${noticeCount.size} notices")
    }

    @Test fun tenThousandRemindersWalkedThroughThreeDays() {
        val seed = Torture.seed("plan-walk", 2001)
        walk(Torture.count(2_000, 10_000), 0, seed)
    }

    @Test fun withRemindAgainEveryOccurrenceSoundsAtMostSevenTimes() {
        val seed = Torture.seed("plan-walk-again", 2002)
        walk(Torture.count(1_000, 5_000), 2, seed)
    }
}
