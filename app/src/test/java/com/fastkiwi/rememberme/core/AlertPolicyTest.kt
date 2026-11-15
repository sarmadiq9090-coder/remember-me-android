package com.fastkiwi.rememberme.core

import com.fastkiwi.rememberme.core.AlertPolicy.Dnd
import com.fastkiwi.rememberme.core.AlertPolicy.FullScreenMode
import com.fastkiwi.rememberme.core.AlertPolicy.Quiet
import com.fastkiwi.rememberme.core.AlertPolicy.Ringer
import com.fastkiwi.rememberme.core.AlertPolicy.Style
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** FEATURES.md F-015, F-019, F-020, F-021: every combination of the alert decision. */
class AlertPolicyTest {
    private val at = Instant.parse("2026-11-15T08:00:00Z")
    private fun task(priority: Priority = Priority.NORMAL, ext: AlertExt? = null) =
        Task(id = "t", title = "x", priority = priority, createdAt = at, updatedAt = at).let { if (ext != null) it.withExt(ext) else it }

    private fun decide(t: Task, ringer: Ringer = Ringer.NORMAL, dnd: Dnd = Dnd.OFF, soundOn: Boolean = true, audible: Boolean = true,
                       vibrate: Boolean = true, fs: FullScreenMode = FullScreenMode.IMPORTANT, pin: Boolean = true) =
        AlertPolicy.decide(t, ringer, dnd, soundOn, audible, vibrate, fs, pin)

    @Test fun styleFollowsPriorityUnlessChosen() {
        assertEquals(Style.ALARM, AlertPolicy.style(task(Priority.HIGH)))
        assertEquals(Style.NORMAL, AlertPolicy.style(task(Priority.NORMAL)))
        assertEquals(Style.NORMAL, AlertPolicy.style(task(Priority.MEDIUM)))
        assertEquals(Style.NORMAL, AlertPolicy.style(task(Priority.HIGH, AlertExt(style = "normal"))))
        assertEquals(Style.ALARM, AlertPolicy.style(task(Priority.MEDIUM, AlertExt(style = "alarm"))))
    }

    @Test fun aNormalReminderFollowsTheRinger() {
        val t = task()
        decide(t).let { assertTrue(it.playTone); assertTrue(it.vibrate); assertEquals(Quiet.NONE, it.quiet) }
        // Vibrate mode: vibrates only.
        decide(t, Ringer.VIBRATE).let { assertFalse(it.playTone); assertTrue(it.vibrate); assertEquals(Quiet.RINGER_VIBRATE, it.quiet) }
        // Silent: no tone and no vibration.
        decide(t, Ringer.SILENT).let { assertFalse(it.playTone); assertFalse(it.vibrate); assertEquals(Quiet.RINGER_SILENT, it.quiet) }
    }

    @Test fun anImportantAlarmRingsInSilentAndVibrateModes() {
        val t = task(Priority.HIGH)
        for (r in Ringer.entries) decide(t, r).let { assertTrue("$r", it.playTone); assertTrue("$r", it.vibrate) }
    }

    @Test fun doNotDisturbIsNeverBypassed() {
        val normal = task()
        val alarm = task(Priority.HIGH)
        // Total silence: nothing plays, nothing vibrates, whatever the style.
        for (t in listOf(normal, alarm)) decide(t, dnd = Dnd.NONE).let { assertFalse(it.playTone); assertFalse(it.vibrate); assertEquals(Quiet.DND, it.quiet) }
        // Alarms allowed (or alarms only): the alarm rings, the normal reminder does not.
        for (d in listOf(Dnd.ALARMS_ONLY, Dnd.PRIORITY_ALARMS_ALLOWED, Dnd.PRIORITY_UNKNOWN)) {
            assertTrue("$d", decide(alarm, dnd = d).playTone)
            assertFalse("$d", decide(normal, dnd = d).playTone)
        }
        // Priority mode that blocks alarms: nothing plays.
        assertFalse(decide(alarm, dnd = Dnd.PRIORITY_ALARMS_BLOCKED).playTone)
        assertFalse(decide(normal, dnd = Dnd.PRIORITY_ALARMS_BLOCKED).playTone)
    }

    @Test fun silentToneAndSoundOffAndMutedChannelSaySo() {
        decide(task(ext = AlertExt(tone = ToneRef("silent", "", "")))).let { assertFalse(it.playTone); assertTrue(it.vibrate); assertEquals(Quiet.SILENT_TONE, it.quiet) }
        assertEquals(Quiet.SOUND_OFF, decide(task(), soundOn = false).quiet)
        assertEquals(Quiet.CHANNEL_MUTED, decide(task(Priority.HIGH), audible = false).quiet)
    }

    @Test fun vibrationCanBeTurnedOffPerReminderOrByDefault() {
        assertFalse(decide(task(), vibrate = false).vibrate)
        assertTrue(decide(task(ext = AlertExt(vibrate = true)), vibrate = false).vibrate)
        assertFalse(decide(task(Priority.HIGH, AlertExt(vibrate = false))).vibrate)
    }

    /** 1.0.5: «الشاشه الكاملة للتنبيهات المهمة فقط»; the switch turns it off for them too; nothing turns it on for a normal one. */
    @Test fun fullScreenOnlyForImportantReminders() {
        assertFalse(decide(task()).fullScreen)
        assertFalse(decide(task(Priority.MEDIUM)).fullScreen)
        assertTrue(decide(task(Priority.HIGH)).fullScreen)
        assertTrue(decide(task(ext = AlertExt(style = "alarm"))).fullScreen)
        // High importance kept as a normal alert (its own choice) is still important.
        assertTrue(decide(task(Priority.HIGH, AlertExt(style = "normal"))).fullScreen)
        assertFalse(decide(task(Priority.HIGH), fs = FullScreenMode.OFF).fullScreen)
        assertFalse(decide(task(ext = AlertExt(style = "alarm", fullScreen = "on")), fs = FullScreenMode.OFF).fullScreen)
        // A normal reminder's own "on" (1.0.4) no longer opens it.
        assertFalse(decide(task(ext = AlertExt(fullScreen = "on"))).fullScreen)
        assertFalse(decide(task(Priority.HIGH, AlertExt(fullScreen = "off"))).fullScreen)
    }

    @Test fun pinFollowsTheSettingUnlessOverridden() {
        assertTrue(decide(task()).pinned)
        assertFalse(decide(task(), pin = false).pinned)
        assertTrue(decide(task(ext = AlertExt(pin = "on")), pin = false).pinned)
        assertFalse(decide(task(ext = AlertExt(pin = "off"))).pinned)
    }

    @Test fun theExtensionRoundTripsAndKeepsUnknownMembers() {
        val ext = AlertExt(tone = ToneRef("bundled", "chime-2", "Chime"), style = "alarm", fullScreen = "off", pin = "on", vibrate = false,
            alerts = listOf(1440, 60), speak = true, prayer = "fajr" to -10, hijri = 9 to 1,
            unknown = mapOf("future" to JsonPrimitive("kept")))
        val t = task(ext = ext)
        assertEquals(ext, AlertExt.of(t))
        // The task's other unknown fields are untouched.
        val other = t.copy(extra = JsonObject(t.extra + ("windowsField" to JsonPrimitive(3))))
        assertEquals(ext, AlertExt.of(other.withExt(ext)))
        assertEquals(JsonPrimitive(3), other.withExt(AlertExt()).extra["windowsField"])
        // Empty: the key is removed, so a file without Android choices stays byte-for-byte Windows-shaped.
        assertNull(other.withExt(AlertExt()).extra[AlertExt.KEY])
    }

    @Test fun aDamagedExtensionMeansDefaults() {
        val t = task().copy(extra = JsonObject(mapOf(AlertExt.KEY to JsonPrimitive("garbage"))))
        assertEquals(AlertExt(), AlertExt.of(t).copy(unknown = emptyMap()))
        assertEquals(Style.NORMAL, AlertPolicy.style(t))
    }
}
