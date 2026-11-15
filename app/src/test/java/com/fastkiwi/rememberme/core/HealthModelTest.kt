package com.fastkiwi.rememberme.core

import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.alarm.TestAlarm.Part
import com.fastkiwi.rememberme.alarm.TestAlarm.Result
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.data.ScheduleLog
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.ScheduleStatus
import com.fastkiwi.rememberme.ui.health.ActionKind
import com.fastkiwi.rememberme.ui.health.HealthGroup
import com.fastkiwi.rememberme.ui.health.HealthModel
import com.fastkiwi.rememberme.ui.health.HealthSource
import com.fastkiwi.rememberme.ui.health.HealthStatus
import com.fastkiwi.rememberme.ui.health.MakerSteps
import com.fastkiwi.rememberme.ui.health.TestResults
import com.fastkiwi.rememberme.ui.health.TestShown
import com.fastkiwi.rememberme.ui.health.dndAllows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** «صحة التذكيرات» rules (FEATURES.md F-008, F-013, F-023) and the alert test's verdicts (F-009, F-027). No Android needed. */
class HealthModelTest {
    private val now = 1_800_000_000_000L
    private val schedule = ScheduleStatus(null, null, 3, 0)
    private fun health(n: Boolean = true, c: Boolean = true, e: Boolean = true) = Health(
        notifications = n, channel = c, exact = e, fullScreen = true, battery = true, dnd = false,
        ringer = AlertPolicy.Ringer.NORMAL, dndState = AlertPolicy.Dnd.OFF, alarmVolume = 5, alarmVolumeMax = 7, notifVolume = 4, notifVolumeMax = 7,
        lockScreenHidesAll = false, toneProblems = 0,
    )

    /** F-008 acceptance: never «كل شيء جاهز» while an essential permission is missing, in all 8 combinations. */
    @Test fun allEightCombinationsOfTheEssentials() {
        for (n in listOf(true, false)) for (c in listOf(true, false)) for (e in listOf(true, false)) {
            val h = health(n, c, e)
            val missing = HealthModel.essentialMissing(h, schedule)
            val expected = listOf(n, c, e).count { !it }
            assertEquals("n=$n c=$c e=$e", expected, missing)
            assertEquals("n=$n c=$c e=$e", n && c && e, missing == 0)
            // The summary agrees with the banner on Home.
            if (missing == 0) assertTrue(h.essentialOk) else assertFalse(h.essentialOk)
        }
    }

    @Test fun otherEssentialsAlsoBlockTheAllReadySummary() {
        assertEquals(1, HealthModel.essentialMissing(health().copy(backgroundRestricted = true), schedule))
        val unverified = ScheduleLog.Entry(now, "check", now + 60_000, "DUE", "CLOCK", 0, 1, verified = false)
        assertEquals(1, HealthModel.essentialMissing(health(), schedule.copy(last = unverified)))
        assertEquals(1, HealthModel.essentialMissing(health().copy(fgsFailures = 2), schedule))
        assertEquals(0, HealthModel.essentialMissing(health(), schedule.copy(last = unverified.copy(verified = true))))
    }

    /** F-023 acceptance: every value that cannot be read is «غير معروف», never «جاهز». */
    @Test fun unknownIsNeverReady() {
        val h = health().copy(ringer = null, dndState = null, alarmVolume = null, notifVolume = null, lockScreenHidesAll = null, toneProblems = null)
        val rows = HealthModel.items(h, schedule, now, "Google", 35).associateBy { it.key }
        for (k in listOf("ringer", "dnd", "alarm_volume", "notif_volume", "lock", "tones", "boot")) {
            assertEquals(k, HealthStatus.UNKNOWN, rows.getValue(k).status)
        }
        assertEquals(HealthStatus.UNKNOWN, HealthModel.phoneNow(health().copy(dndState = AlertPolicy.Dnd.PRIORITY_UNKNOWN)).single { it.key == "dnd" }.status)
        // What the app cannot read about the maker is unknown too.
        assertTrue(HealthModel.maker("Xiaomi", 35).all { it.status == HealthStatus.UNKNOWN })
    }

    @Test fun phoneNowFactsAreNeverRed() {
        val silent = health().copy(ringer = AlertPolicy.Ringer.SILENT, dndState = AlertPolicy.Dnd.NONE, alarmVolume = 0, toneProblems = 3, alarmChannelSound = false)
        val rows = HealthModel.phoneNow(silent).associateBy { it.key }
        assertTrue(rows.values.none { it.status == HealthStatus.NEEDED })
        assertEquals(HealthStatus.INFO, rows.getValue("ringer").status)
        assertEquals(HealthStatus.OFF, rows.getValue("alarm_volume").status)
        assertEquals(HealthStatus.READY, rows.getValue("notif_volume").status)
        // DND that blocks alarms points to the help screen.
        assertEquals("dnd_help", rows.getValue("dnd").action?.target)
        assertEquals(HealthStatus.OFF, rows.getValue("alarm_channel").status)
    }

    /** §5.11 item 3: every not-ready row has a source and exactly one action, whose kind follows its group. */
    @Test fun everyNotReadyRowHasASourceAndOneAction() {
        val worst = Health(
            notifications = false, channel = false, exact = false, fullScreen = false, battery = false, dnd = true,
            backgroundRestricted = true, bucket = 45, ringer = null, dndState = null, lockScreenHidesAll = true,
            alarmChannelSound = false, toneProblems = null, fgsFailures = 1, bootAt = now - 3_600_000,
        )
        val s = ScheduleStatus(ScheduleLog.Entry(now - 60_000, "open", 0, "", "NONE", 0, 0, verified = false), null, 0, 0)
        for (maker in listOf("Xiaomi", "samsung", "HUAWEI", "oppo", "vivo", "Google")) {
            val items = HealthModel.items(worst, s, now, maker, 34)
            assertTrue(items.any { it.status.notReady })
            for (it in items.filter { it.status.notReady }) {
                assertTrue("$maker ${it.key}", it.source != null && it.action != null)
                val expected = when (it.group) {
                    HealthGroup.ESSENTIAL -> ActionKind.FIX
                    HealthGroup.RECOMMENDED -> ActionKind.ALLOW
                    else -> ActionKind.OPEN_SETTINGS
                }
                assertEquals(it.key, expected, it.action!!.kind)
            }
            // A ready row has no button.
            assertTrue(items.filter { it.status == HealthStatus.READY }.all { it.action == null })
        }
        // Maker steps are labelled with the maker as their source.
        assertTrue(HealthModel.maker("vivo", 30).all { it.source == HealthSource.MAKER })
    }

    /** F-013: steps per maker and Android version; none is marked tried (none was tried on a phone yet). */
    @Test fun makerStepsPerMakerAndVersionAndNoneTried() {
        val s5 = MakerSteps.of("samsung", 33)!!.second.map { it.id }
        val s6 = MakerSteps.of("samsung", 34)!!.second.map { it.id }
        assertTrue("samsung_sleep5" in s5 && "samsung_sleep6" !in s5)
        assertTrue("samsung_sleep6" in s6 && "samsung_sleep5" !in s6)
        for (m in listOf("xiaomi", "redmi", "poco", "samsung", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "iqoo")) {
            val steps = MakerSteps.of(m, 34)
            assertTrue(m, steps != null && steps.second.isNotEmpty())
            assertTrue(m, steps!!.second.none { it.tried })
        }
        assertNull(MakerSteps.of("Google", 34))
        assertEquals(listOf("generic"), HealthModel.maker("Google", 34).map { it.key })
        assertTrue(HealthModel.maker("xiaomi", 34).all { it.tried == false })
    }

    @Test fun dndHelpSaysAllowedOnlyWhenItCanTell() {
        assertEquals(true, dndAllows(AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED, null))
        assertEquals(false, dndAllows(AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED, true))
        assertEquals(false, dndAllows(AlertPolicy.Dnd.NONE, true))
        assertNull(dndAllows(AlertPolicy.Dnd.PRIORITY_UNKNOWN, true))
        assertNull(dndAllows(null, true))
        assertNull(dndAllows(AlertPolicy.Dnd.OFF, null))
        assertEquals(true, dndAllows(AlertPolicy.Dnd.OFF, true))
    }

    // ---- Alert test (F-009, F-027) ----

    private val t0 = 1_000_000L
    private val started = TestAlarm.Record(startedAt = t0, plannedAt = t0 + 10_000, api = "CLOCK", style = AlertPolicy.Style.ALARM.name)
    private val fired = started.copy(firedAt = t0 + 11_000, posted = true, fullScreenAllowed = true, quiet = "NONE", vibrate = true)
    private fun eval(r: TestAlarm.Record, at: Long = t0 + 20_000, high: Boolean = true, sound: Boolean = true, vib: Boolean = true, dnd: Boolean = false) =
        TestAlarm.evaluate(r, at, high, sound, vib, dnd)

    /** Every part in every state it can take. */
    @Test fun evaluateEveryPartState() {
        // ALARM: waiting, on time, late, lost.
        assertEquals(Result.WAITING, eval(started, t0 + 5_000)[Part.ALARM])
        assertEquals(Result.OK, eval(fired)[Part.ALARM])
        assertEquals(Result.FAILED, eval(started.copy(firedAt = t0 + 10_000 + TestAlarm.ON_TIME_MS + 1, posted = true), t0 + 200_000)[Part.ALARM])
        assertEquals(Result.FAILED, eval(started, t0 + 10_000 + 2 * TestAlarm.ON_TIME_MS + 1)[Part.ALARM])
        // NOTIFICATION: waiting, shown, not shown, channel lowered, lost.
        assertEquals(Result.WAITING, eval(started, t0 + 5_000)[Part.NOTIFICATION])
        assertEquals(Result.OK, eval(fired)[Part.NOTIFICATION])
        assertEquals(Result.FAILED, eval(fired.copy(posted = false))[Part.NOTIFICATION])
        assertEquals(Result.FAILED, eval(fired, high = false)[Part.NOTIFICATION])
        assertEquals(Result.FAILED, eval(started, t0 + 500_000)[Part.NOTIFICATION])
        // SOUND: waiting, ask, heard, not heard, muted channel, DND, lost.
        assertEquals(Result.WAITING, eval(started, t0 + 5_000)[Part.SOUND])
        assertEquals(Result.ASK, eval(fired)[Part.SOUND])
        assertEquals(Result.OK, eval(fired.copy(sound = true))[Part.SOUND])
        assertEquals(Result.FAILED, eval(fired.copy(sound = false))[Part.SOUND])
        assertEquals(Result.FAILED, eval(fired.copy(sound = true), sound = false)[Part.SOUND])
        assertEquals(Result.FAILED, eval(fired.copy(sound = true), dnd = true)[Part.SOUND])
        assertEquals(Result.FAILED, eval(started, t0 + 500_000)[Part.SOUND])
        // VIBRATION: waiting, ask, felt, not felt, off, lost.
        assertEquals(Result.WAITING, eval(started, t0 + 5_000)[Part.VIBRATION])
        assertEquals(Result.ASK, eval(fired)[Part.VIBRATION])
        assertEquals(Result.OK, eval(fired.copy(vibration = true))[Part.VIBRATION])
        assertEquals(Result.FAILED, eval(fired.copy(vibration = false))[Part.VIBRATION])
        assertEquals(Result.FAILED, eval(fired.copy(vibration = true), vib = false)[Part.VIBRATION])
        assertEquals(Result.FAILED, eval(started, t0 + 500_000)[Part.VIBRATION])
        // SCREEN: waiting, shown, not allowed, not seen (unknown), lost.
        assertEquals(Result.WAITING, eval(started, t0 + 5_000)[Part.SCREEN])
        assertEquals(Result.OK, eval(fired.copy(screenAt = t0 + 12_000))[Part.SCREEN])
        assertEquals(Result.NOT_ALLOWED, eval(fired.copy(fullScreenAllowed = false))[Part.SCREEN])
        assertEquals(Result.ASK, eval(fired)[Part.SCREEN])
        assertEquals(Result.FAILED, eval(started, t0 + 500_000)[Part.SCREEN])
        // BUTTONS: waiting, pressed, not yet, lost.
        assertEquals(Result.WAITING, eval(started, t0 + 5_000)[Part.BUTTONS])
        assertEquals(Result.OK, eval(fired.copy(actionAt = t0 + 15_000))[Part.BUTTONS])
        assertEquals(Result.ASK, eval(fired)[Part.BUTTONS])
        assertEquals(Result.FAILED, eval(started, t0 + 500_000)[Part.BUTTONS])
        // No test: nothing.
        assertTrue(eval(TestAlarm.Record()).isEmpty())
    }

    /** 1.0.5: a normal reminder never opens the full screen, so its test has no «الشاشة الكاملة» part. */
    @Test fun aNormalTestHasNoFullScreenPart() {
        val normal = eval(fired.copy(style = AlertPolicy.Style.NORMAL.name))
        assertFalse(Part.SCREEN in normal.keys)
        assertTrue(Part.BUTTONS in normal.keys)
    }

    /** Never a single "success" while any part failed, waits for an answer or is unknown. */
    @Test fun neverASingleSuccessWithAnUnknownOrFailedPart() {
        val asked = eval(fired)
        assertFalse(TestResults.allPassed(asked))
        assertEquals(TestShown.UNKNOWN, TestResults.shown(Part.SCREEN, asked.getValue(Part.SCREEN)))
        val counts = TestResults.counts(asked)
        assertEquals(2, counts[TestShown.PASS])
        assertEquals(3, counts[TestShown.ASK])
        assertEquals(1, counts[TestShown.UNKNOWN])
        val one = eval(fired.copy(screenAt = t0 + 12_000, actionAt = t0 + 15_000, sound = true, vibration = false))
        assertFalse(TestResults.allPassed(one))
        assertEquals(1, TestResults.counts(one)[TestShown.FAIL])
        val all = eval(fired.copy(screenAt = t0 + 12_000, actionAt = t0 + 15_000, sound = true, vibration = true))
        assertTrue(TestResults.allPassed(all))
        assertTrue(TestResults.running(started, eval(started, t0 + 5_000)))
        assertFalse(TestResults.running(fired, asked))
    }

    /** F-027 acceptance: the measured lateness, in ms (shown in seconds). */
    @Test fun lateIsMeasured() {
        assertEquals(1_000L, fired.lateMs)
        assertNull(started.lateMs)
        assertEquals(0L, started.copy(firedAt = t0 + 9_000).lateMs)
    }

    @Test fun theNightTestArrivesTomorrowAtSeven() {
        val zone = ZoneId.of("Asia/Baghdad")
        val at = Instant.parse("2026-11-15T19:30:00Z") // 22:30 in Baghdad
        assertEquals(at.plusSeconds(10), TestAlarm.plannedAt(at, TestAlarm.Delay.SECONDS_10, zone))
        assertEquals(at.plusSeconds(3600), TestAlarm.plannedAt(at, TestAlarm.Delay.HOUR_1, zone))
        assertEquals(Instant.parse("2026-11-16T04:00:00Z"), TestAlarm.plannedAt(at, TestAlarm.Delay.TOMORROW_7, zone))
    }

    @Test fun playbackIsReadFromTheTestsOwnLogLines() {
        val fire = 5_000L
        fun e(at: Long, ev: AlertLog.Event, key: String = TestAlarm.LOG_KEY, code: String = "") = AlertLog.Entry(at, ev, key, -1, "", code)
        assertNull(TestAlarm.playback(emptyList(), fire))
        assertEquals(true, TestAlarm.playback(listOf(e(fire + 100, AlertLog.Event.PLAY_STARTED, code = "source0")), fire))
        assertEquals(true, TestAlarm.playback(listOf(e(fire + 100, AlertLog.Event.PLAY_FAILED), e(fire + 200, AlertLog.Event.FALLBACK, code = "source1")), fire))
        assertEquals(false, TestAlarm.playback(listOf(e(fire + 100, AlertLog.Event.FALLBACK, code = "foreground")), fire))
        // Another reminder's lines, or an older test's, do not count.
        assertNull(TestAlarm.playback(listOf(e(fire + 100, AlertLog.Event.PLAY_STARTED, key = "a1b2c3d4")), fire))
        assertNull(TestAlarm.playback(listOf(e(fire - 60_000, AlertLog.Event.PLAY_STARTED)), fire))
    }

    @Test fun dndBlocksTheTestByStyle() {
        val h = health().copy(dndState = AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED)
        assertTrue(TestResults.dndBlocks(started.copy(style = AlertPolicy.Style.NORMAL.name), h))
        assertFalse(TestResults.dndBlocks(started.copy(style = AlertPolicy.Style.ALARM.name), h))
        assertTrue(TestResults.dndBlocks(started.copy(style = AlertPolicy.Style.ALARM.name), h.copy(dndState = AlertPolicy.Dnd.NONE)))
        // What was recorded when it fired wins over the phone's state now.
        assertFalse(TestResults.dndBlocks(started.copy(style = AlertPolicy.Style.NORMAL.name, quiet = "NONE"), h))
        assertTrue(TestResults.dndBlocks(started.copy(style = AlertPolicy.Style.NORMAL.name, quiet = "DND"), health()))
        assertNotEquals(TestResults.of(fired.copy(sound = true), health(), t0 + 20_000)[Part.SOUND], Result.FAILED)
    }
}
