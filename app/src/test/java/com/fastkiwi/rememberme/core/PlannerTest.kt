package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class PlannerTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)

    @Before fun ids() = T.ids()

    @Test fun newlyDueIsLoudOnceThenQuiet() {
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "o", dueAt = now.minusSeconds(5)), now, zone)
        val p1 = Planner.plan(s, now, 0, emptyMap(), emptyMap())
        assertEquals(setOf(t.id), p1.loud)
        assertEquals(listOf(t.id), p1.due.map { it.id })
        // A reboot or the next run: the same occurrence never sounds twice.
        val p2 = Planner.plan(s, now.plusSeconds(60), 0, p1.alertLog, p1.noticeLog)
        assertTrue(p2.loud.isEmpty())
        assertEquals(1, p2.due.size)
        assertNull("Nothing ahead and no remind-again: no alarm", p2.wakeAt)
        // Snoozed and due again: a new occurrence, loud again.
        val s2 = Tasks.snooze(s, t.id, now.plusSeconds(600), now)!!
        val p3 = Planner.plan(s2, now.plusSeconds(601), 0, p2.alertLog, p2.noticeLog)
        assertEquals(setOf(t.id), p3.loud)
    }

    @Test fun remindAgainSoundsEveryIntervalAtMostSixTimes() {
        val due = now
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "o", dueAt = due), now, zone)
        var log = emptyMap<String, String>()
        var loudCount = 0
        var at = due
        var guard = 0
        while (guard++ < 50) {
            val p = Planner.plan(s, at, 5, log, emptyMap())
            if (t.id in p.loud) loudCount++
            log = p.alertLog
            at = p.wakeAt ?: break
        }
        assertEquals("First alert plus 6 repeats", 7, loudCount)
        assertEquals(due.plusSeconds(6 * 5 * 60), at)
    }

    @Test fun wakeIsTheEarliestDueOrNoticeAndHandlesAllDue() {
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "a", dueAt = now.minusSeconds(3600)), now, zone).first
        s = Tasks.save(s, TaskInput(title = "b", dueAt = now.minusSeconds(60)), now, zone).first
        s = Tasks.save(s, TaskInput(title = "c", dueAt = now.plusSeconds(7200), remindBefore = 60), now, zone).first
        s = Tasks.save(s, TaskInput(title = "d", dueAt = now.plusSeconds(1800)), now, zone).first
        val p = Planner.plan(s, now, 0, emptyMap(), emptyMap())
        assertEquals("Every due reminder is handled in one run", listOf("a", "b"), p.due.map { it.title })
        assertEquals(2, p.loud.size)
        assertEquals(now.plusSeconds(1800), p.wakeAt)
        // At c's notice moment the notice shows once.
        val atNotice = now.plusSeconds(3600)
        val p2 = Planner.plan(s, atNotice, 0, p.alertLog, p.noticeLog)
        assertEquals(listOf("c"), p2.notices.map { it.title })
        val p3 = Planner.plan(s, atNotice.plusSeconds(30), 0, p2.alertLog, p2.noticeLog)
        assertTrue(p3.notices.isEmpty())
        assertEquals(now.plusSeconds(7200), p3.wakeAt)
    }

    @Test fun completedTrashedAndUntimedTasksNeverWake() {
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "untimed"), now, zone).first
        val (s2, t) = Tasks.save(s, TaskInput(title = "x", dueAt = now.plusSeconds(60)), now, zone)
        val trashed = Tasks.trash(s2, t.id, now)
        val p = Planner.plan(trashed, now, 0, emptyMap(), emptyMap())
        assertNull(p.wakeAt)
        assertTrue(p.due.isEmpty())
    }
}
