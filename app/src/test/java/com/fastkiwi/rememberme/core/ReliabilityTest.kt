package com.fastkiwi.rememberme.core

import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.TestAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/** 0.2.0 reliability rules that need no Android: the wake kind, the alarm API per kind, the alarm test's verdicts. */
class ReliabilityTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)

    @Before fun ids() = T.ids()

    @Test fun wakeKindFollowsTheEarliestMomentAndDueWinsATie() {
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "a", dueAt = now.plusSeconds(3600), remindBefore = 60), now.minusSeconds(60), zone).first
        // The notice (now) is passed already; next is the due time in an hour.
        assertEquals(WakeKind.DUE, Planner.plan(s, now, 0, emptyMap(), emptyMap()).wakeKind)
        var s2 = AppState()
        s2 = Tasks.save(s2, TaskInput(title = "b", dueAt = now.plusSeconds(7200), remindBefore = 60), now.minusSeconds(60), zone).first
        assertEquals(WakeKind.NOTICE, Planner.plan(s2, now, 0, emptyMap(), emptyMap()).wakeKind)
        // A notice and another reminder's due time at the same moment: the due time decides.
        s2 = Tasks.save(s2, TaskInput(title = "c", dueAt = now.plusSeconds(3600)), now.minusSeconds(60), zone).first
        val p = Planner.plan(s2, now, 0, emptyMap(), emptyMap())
        assertEquals(now.plusSeconds(3600), p.wakeAt)
        assertEquals(WakeKind.DUE, p.wakeKind)
        assertEquals(2, p.scheduled)
        // Only a "sound again" moment ahead.
        val (s3, _) = Tasks.save(AppState(), TaskInput(title = "d", dueAt = now.minusSeconds(60)), now.minusSeconds(120), zone)
        val p3 = Planner.plan(s3, now, 5, emptyMap(), emptyMap())
        assertEquals(WakeKind.AGAIN, p3.wakeKind)
        assertNull(Planner.plan(AppState(), now, 0, emptyMap(), emptyMap()).wakeKind)
    }

    @Test fun theStrongestAlarmOnlyWhereAReminderIsDue() {
        assertEquals(Engine.Api.CLOCK, Engine.apiFor(WakeKind.DUE, true))
        assertEquals(Engine.Api.EXACT, Engine.apiFor(WakeKind.NOTICE, true))
        assertEquals(Engine.Api.EXACT, Engine.apiFor(WakeKind.AGAIN, true))
        for (k in WakeKind.entries) assertEquals(Engine.Api.INEXACT, Engine.apiFor(k, false))
        assertEquals(Engine.Api.NONE, Engine.apiFor(null, true))
    }

    @Test fun theAlarmTestNeverReportsSuccessForAPartItCouldNotSee() {
        val t0 = 1_000_000L
        val started = TestAlarm.Record(startedAt = t0, plannedAt = t0 + 10_000, api = "EXACT")
        // Before the alarm: waiting, nothing reported as working.
        val waiting = TestAlarm.evaluate(started, t0 + 5_000, channelHigh = true, channelSound = true, channelVibrates = true, dnd = false)
        assertTrue(waiting.values.all { it == TestAlarm.Result.WAITING })
        // Never arrived, two minutes after: the alarm part failed.
        val lost = TestAlarm.evaluate(started, t0 + 200_000, true, true, true, false)
        assertEquals(TestAlarm.Result.FAILED, lost[TestAlarm.Part.ALARM])
        assertEquals(TestAlarm.Result.FAILED, lost[TestAlarm.Part.NOTIFICATION])
        // Arrived on time and shown; sound and vibration are asked, not assumed.
        val fired = started.copy(firedAt = t0 + 12_000, posted = true, fullScreenAllowed = true)
        val r = TestAlarm.evaluate(fired, t0 + 20_000, true, true, true, false)
        assertEquals(TestAlarm.Result.OK, r[TestAlarm.Part.ALARM])
        assertEquals(TestAlarm.Result.OK, r[TestAlarm.Part.NOTIFICATION])
        assertEquals(TestAlarm.Result.ASK, r[TestAlarm.Part.SOUND])
        assertEquals(TestAlarm.Result.ASK, r[TestAlarm.Part.VIBRATION])
        assertEquals(TestAlarm.Result.ASK, r[TestAlarm.Part.SCREEN])
        assertEquals(TestAlarm.Result.ASK, r[TestAlarm.Part.BUTTONS])
        // A muted channel or Do Not Disturb fails the sound whatever the user answers.
        assertEquals(TestAlarm.Result.FAILED, TestAlarm.evaluate(fired.copy(sound = true), t0 + 20_000, true, false, true, false)[TestAlarm.Part.SOUND])
        assertEquals(TestAlarm.Result.FAILED, TestAlarm.evaluate(fired.copy(sound = true), t0 + 20_000, true, true, true, true)[TestAlarm.Part.SOUND])
        // Late by more than a minute: arrived, but the alarm part failed.
        assertEquals(TestAlarm.Result.FAILED, TestAlarm.evaluate(started.copy(firedAt = t0 + 90_000, posted = true), t0 + 95_000, true, true, true, false)[TestAlarm.Part.ALARM])
        // Full screen not allowed by Android: said so, not counted as a failure of the app.
        assertEquals(TestAlarm.Result.NOT_ALLOWED, TestAlarm.evaluate(fired.copy(fullScreenAllowed = false), t0 + 20_000, true, true, true, false)[TestAlarm.Part.SCREEN])
        // All confirmed.
        val all = TestAlarm.evaluate(fired.copy(screenAt = t0 + 13_000, actionAt = t0 + 15_000, sound = true, vibration = true), t0 + 20_000, true, true, true, false)
        assertTrue(all.toString(), all.values.all { it == TestAlarm.Result.OK })
        // No test yet: nothing to show.
        assertTrue(TestAlarm.evaluate(TestAlarm.Record(), t0, true, true, true, false).isEmpty())
    }
}
