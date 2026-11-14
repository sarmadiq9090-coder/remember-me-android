package com.fastkiwi.rememberme.hunt

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.alarm.Summary
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.withExt
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Release-gate hunt (1.0.0): alert pipeline defects. Each test fails on the current code and passes once
 * the defect it is named after is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HuntAlertTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private lateinit var tzBefore: TimeZone

    @Before fun setUp() {
        Engine.drain()
        tzBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "alert_log", "schedule_log")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        app.createDeviceProtectedStorageContext().getSharedPreferences("boot_times", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Notifier.channels(app)
        nm.cancelAll()
        RingService.ringingId = 0
        drainStarted()
    }

    @After fun tearDown() { TimeZone.setDefault(tzBefore) }

    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun save(input: TaskInput, now: Instant) = Repo.store(app).change { Tasks.save(it, input, now, zone).first }.tasks.last()
    private fun hide(id: String) = Repo.store(app).change { s ->
        s.copy(tasks = s.tasks.map { if (it.id == id) it.withExt(AlertExt.of(it).copy(unknown = mapOf("lock" to JsonPrimitive("hide")))) else it })
    }
    private fun startedRing(): Intent? {
        while (true) {
            val i = shadowOf(app).nextStartedService ?: return null
            if (i.component?.className == RingService::class.java.name) return i
        }
    }
    private fun drainStarted() { while (shadowOf(app).nextStartedService != null) Unit }
    private fun title(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    private fun allText(n: Notification?): String = if (n == null) "" else listOfNotNull(
        n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_TEXT),
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).joinToString("\n")

    /** What the lock screen can show of [n] when the phone shows notification content (Android's default). */
    private fun lockScreenText(n: Notification): String = when (n.visibility) {
        Notification.VISIBILITY_SECRET -> ""
        Notification.VISIBILITY_PUBLIC -> allText(n)
        // PRIVATE: the public version when there is one; without one, the whole content shows on a lock
        // screen whose phone setting is "show all notification content".
        else -> n.publicVersion?.let(::allText) ?: allText(n)
    }

    // ---- S1 privacy: the reminder's own «أخفِ التفاصيل» is ignored outside the due notification ----

    @Test fun earlyNoticeShowsTheTitleOnTheLockScreenWhenTheReminderHidesItsDetails() {
        val now = Instant.now()
        val due = now.plusSeconds(2 * 3600)
        val t = save(TaskInput(title = "موعد سري", dueAt = due, remindBefore = 60), now.minusSeconds(60))
        hide(t.id)
        assertFalse("the phone setting shows details", Repo.prefs(app).lockPrivacy)
        Engine.sync(app, due.minusSeconds(59 * 60))
        val notice = nm.activeNotifications.single { it.tag == Notifier.TAG_NOTICE + t.id }.notification
        assertFalse("title of a hidden reminder on the lock screen: " + lockScreenText(notice), lockScreenText(notice).contains("موعد سري"))
    }

    @Test fun morningSummaryShowsTheFirstTitleOnTheLockScreenWhenThatReminderHidesItsDetails() {
        val now = Instant.parse("2026-11-15T05:00:00Z") // 08:00 in Baghdad
        val t = save(TaskInput(title = "تحليل سري", dueAt = now.plusSeconds(3600)), now.minusSeconds(60))
        hide(t.id)
        Summary.fire(app, now)
        val n = nm.activeNotifications.single { it.id == Notifier.MORNING_ID }.notification
        assertFalse("title of a hidden reminder in the summary: " + lockScreenText(n), lockScreenText(n).contains("تحليل سري"))
    }

    @Test fun undoNoteAfterDoneShowsTheTitleOnTheLockScreenWhenTheReminderHidesItsDetails() {
        val now = Instant.now()
        val t = save(TaskInput(title = "دواء سري", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        hide(t.id)
        Engine.sync(app, now)
        ActionReceiver.handle(app, ActionReceiver.DONE, t.id, t.dueAt, now.plusSeconds(2))
        val undo = nm.activeNotifications.single { it.id == Notifier.UNDO_ID }.notification
        assertFalse("title of a hidden reminder in the undo note: " + lockScreenText(undo), lockScreenText(undo).contains("دواء سري"))
    }

    // ---- S2: read aloud (F-031) «لا ينطق في الصامت أو عدم الإزعاج» ----

    @Test fun readAloudSpeaksAnImportantReminderWhileThePhoneIsSilent() {
        val now = Instant.now()
        Repo.prefs(app).speakDefault = true
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT
        save(TaskInput(title = "مهم", dueAt = now.minusSeconds(5), priority = Priority.HIGH), now.minusSeconds(60))
        Engine.sync(app, now)
        val ring = startedRing()
        assertNotNull("the alarm still rings (alarm volume)", ring)
        assertNull("spoken in silent mode", ring!!.getStringExtra(RingService.EXTRA_SPEAK))
    }

    @Test fun readAloudSpeaksAnImportantReminderWhileDoNotDisturbIsOn() {
        val now = Instant.now()
        Repo.prefs(app).speakDefault = true
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        save(TaskInput(title = "مهم", dueAt = now.minusSeconds(5), priority = Priority.HIGH), now.minusSeconds(60))
        Engine.sync(app, now)
        val ring = startedRing()
        assertNotNull("the alarm still rings (DND allows alarms)", ring)
        assertNull("spoken while Do Not Disturb is on", ring!!.getStringExtra(RingService.EXTRA_SPEAK))
    }

    // ---- S1: sounds twice ----

    @Test fun undoAfterDoneFromTheNotificationRingsTheReminderAgain() {
        val now = Instant.now()
        val t = save(TaskInput(title = "يومي", dueAt = now.minusSeconds(5), repeat = Repeat.DAILY), now.minusSeconds(60))
        Engine.sync(app, now)
        assertNotNull("first ring", startedRing())
        ActionReceiver.handle(app, ActionReceiver.DONE, t.id, t.dueAt, now.plusSeconds(2))
        drainStarted()
        val undo = nm.activeNotifications.single { it.id == Notifier.UNDO_ID }.notification
        val copy = shadowOf(undo.actions[0].actionIntent).savedIntent.getStringExtra(ActionReceiver.EXTRA_COPY)
        ActionReceiver.handle(app, ActionReceiver.UNDO, t.id, null, now.plusSeconds(4), copy)
        assertEquals(t.dueAt, Repo.state(app).task(t.id)!!.dueAt)
        assertNull("the same occurrence rang a second time after «تراجع»", startedRing())
    }

    @Test fun restoringATrashedDueReminderRingsItAgain() {
        val now = Instant.now()
        val t = save(TaskInput(title = "مرة", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Engine.sync(app, now)
        assertNotNull("first ring", startedRing())
        Repo.change(app) { Tasks.trash(it, t.id, Instant.now()) }
        drainStarted()
        // «تراجع» on the in-app snackbar after a swipe to the trash.
        Repo.change(app) { Tasks.restore(it, t.id) }
        assertNull("the same occurrence rang a second time after undoing the delete", startedRing())
    }

    // ---- S1: silent by accident ----

    @Test fun aVibrateOnlyReminderIsFullySilentWhenTheRingingServiceIsRefused() {
        val now = Instant.now()
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val t = save(TaskInput(title = "اهتزاز", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        // Android 12+ refuses a foreground service started from the background (e.g. an inexact wake-up
        // without exact-alarm access): ForegroundServiceStartNotAllowedException.
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?): ComponentName? = throw IllegalStateException("not allowed")
        }
        Notifier.postDue(refusing, Repo.state(app).task(t.id)!!, true, now)
        val n = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        // The ringer is on vibrate: the sounding fallback channel would vibrate (Android applies the ringer);
        // the quiet channel neither sounds nor vibrates.
        assertTrue("vibrate-only reminder re-posted on ${n.channelId}", n.channelId != Notifier.CH_QUIET)
    }
}

/** Android 8.0/8.1 have no «alarms» category in Do Not Disturb: priority mode always lets alarms through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27])
class HuntDndOreoTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun anImportantReminderIsSilencedByPriorityDndOnAndroid8() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        // Reminders and events allowed; on 8.x there is no PRIORITY_CATEGORY_ALARMS bit at all.
        nm.notificationPolicy = NotificationManager.Policy(
            NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS or NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS, 0, 0)
        val dnd = Notifier.dnd(app)
        assertTrue("Android 8 lets alarms through priority mode, the app read $dnd",
            dnd != com.fastkiwi.rememberme.core.AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED)
    }
}
