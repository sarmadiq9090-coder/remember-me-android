package com.fastkiwi.rememberme

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.DirectBoot
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.alarm.Summary
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.Planner
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.withExt
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

/** The 1.0.0 alert pipeline: ringing service, channels, pin, notices, undo, summary, before-unlock (F-014…F-032, F-046, F-052). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36])
class AlertPipelineTest {
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
    }

    @After fun tearDown() { TimeZone.setDefault(tzBefore) }

    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val am get() = app.getSystemService(AlarmManager::class.java)
    private fun save(input: TaskInput, now: Instant = Instant.now()) =
        Repo.store(app).change { Tasks.save(it, input, now, zone).first }.tasks.last()
    private fun startedRing() = shadowOf(app).nextStartedService?.takeIf { it.component?.className == RingService::class.java.name }

    @Test fun aDueReminderIsPostedThenTheServiceRingsTheSameNotification() {
        val now = Instant.now()
        val t = save(TaskInput(title = "دواء", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Engine.sync(app, now)
        val sbn = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }
        assertEquals(Notifier.dueId(t.id), sbn.id)
        assertEquals(Notifier.CH_NORMAL, sbn.notification.channelId)
        val ring = startedRing()
        assertNotNull("ringing service started", ring)
        assertEquals(Notifier.dueId(t.id), ring!!.getIntExtra(RingService.EXTRA_ID, 0))
        assertEquals("normal", ring.getStringExtra(RingService.EXTRA_STYLE))
        // Sources: the chain ends with a playable fallback; never empty.
        assertTrue(ring.getStringArrayExtra(RingService.EXTRA_SOURCES)!!.isNotEmpty())
        assertTrue(ring.getIntExtra(RingService.EXTRA_SECONDS, 0) <= 30)
    }

    @Test fun anImportantReminderUsesTheAlarmChannelCategoryAndRingDuration() {
        val now = Instant.now()
        Repo.prefs(app).ringSeconds = 120
        val t = save(TaskInput(title = "مهم", dueAt = now.minusSeconds(5), priority = Priority.HIGH), now.minusSeconds(60))
        Engine.sync(app, now)
        val n = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertEquals(Notifier.CH_ALARM, n.channelId)
        assertEquals(android.app.Notification.CATEGORY_ALARM, n.category)
        val ring = startedRing()!!
        assertEquals("alarm", ring.getStringExtra(RingService.EXTRA_STYLE))
        assertEquals(120, ring.getIntExtra(RingService.EXTRA_SECONDS, 0))
    }

    @Test fun aSilentToneVibratesOnlyAndPlaysNothing() {
        val now = Instant.now()
        val t = save(TaskInput(title = "silent", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Repo.store(app).change { s -> s.copy(tasks = s.tasks.map { if (it.id == t.id) it.withExt(AlertExt(tone = ToneRef.SILENT)) else it }) }
        Engine.sync(app, now)
        val ring = startedRing()!!
        assertFalse(ring.getBooleanExtra(RingService.EXTRA_PLAY, true))
        assertTrue(ring.getBooleanExtra(RingService.EXTRA_VIBRATE, false))
    }

    @Test fun fullScreenOffStillShowsTheNotificationWithItsButtons() {
        val now = Instant.now()
        Repo.prefs(app).fullScreenMode = "off"
        val t = save(TaskInput(title = "no full screen", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Engine.sync(app, now)
        val n = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertNull(n.fullScreenIntent)
        assertEquals(listOf("تم", "أجّل 10 دقائق", "أجّل حتى…"), n.actions.map { it.title.toString() })
    }

    @Test fun aSwipedPinnedReminderComesBackAtMostFiveTimesAndAnUnpinnedOneStaysAway() {
        val now = Instant.now()
        val t = save(TaskInput(title = "pinned", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Engine.sync(app, now)
        repeat(7) { i ->
            nm.cancel(Notifier.dueId(t.id))
            ActionReceiver.handle(app, ActionReceiver.DISMISSED, t.id, t.dueAt, now.plusSeconds(10L + i))
            val back = nm.activeNotifications.any { Notifier.dueTask(it) == t.id }
            assertEquals("swipe ${i + 1}", i < com.fastkiwi.rememberme.core.AlertPolicy.MAX_REPOSTS, back)
        }
        // Not pinned: the first swipe hides it from the notifications (it stays in the app).
        Repo.prefs(app).pinDefault = false
        val u = save(TaskInput(title = "free", dueAt = now.minusSeconds(4)), now.minusSeconds(60))
        Engine.sync(app, now.plusSeconds(30))
        nm.cancel(Notifier.dueId(u.id))
        ActionReceiver.handle(app, ActionReceiver.DISMISSED, u.id, u.dueAt, now.plusSeconds(31))
        assertTrue(nm.activeNotifications.none { Notifier.dueTask(it) == u.id })
        assertTrue(Tasks.due(Repo.state(app), now.plusSeconds(32)).any { it.id == u.id })
    }

    @Test fun doneFromTheNotificationOffersUndoThatRestoresTheOccurrence() {
        val now = Instant.now()
        val t = save(TaskInput(title = "يومي", dueAt = now.minusSeconds(5), repeat = Repeat.DAILY), now.minusSeconds(60))
        Engine.sync(app, now)
        ActionReceiver.handle(app, ActionReceiver.DONE, t.id, t.dueAt, now.plusSeconds(2))
        val undo = nm.activeNotifications.single { it.id == Notifier.UNDO_ID }.notification
        assertEquals("أُنجز «يومي»", undo.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        // F-102: a title in the other direction is wrapped in directional controls; the same direction is left alone.
        val ar = com.fastkiwi.rememberme.ui.L10n.context(app)
        assertEquals("يومي", com.fastkiwi.rememberme.ui.L10n.isolate(ar, "يومي"))
        val wrapped = com.fastkiwi.rememberme.ui.L10n.isolate(ar, "Meeting 5")
        assertTrue(wrapped.contains("Meeting 5") && wrapped != "Meeting 5" && wrapped.first().code in 0x200E..0x2069)
        assertEquals("تراجع", undo.actions[0].title.toString())
        assertTrue(Repo.state(app).task(t.id)!!.dueAt!!.isAfter(now))
        val copy = shadowOf(undo.actions[0].actionIntent).savedIntent.getStringExtra(ActionReceiver.EXTRA_COPY)
        ActionReceiver.handle(app, ActionReceiver.UNDO, t.id, null, now.plusSeconds(4), copy)
        assertEquals(t.dueAt, Repo.state(app).task(t.id)!!.dueAt)
        assertTrue("history copy removed", Repo.state(app).tasks.none { it.sourceId == t.id })
        assertTrue(nm.activeNotifications.none { it.id == Notifier.UNDO_ID })
    }

    /** Black-box U1: «تراجع» on the note after «تم» of a one-time reminder really reopens it. */
    @Test fun undoFromTheNotificationReopensAOneTimeReminder() {
        val now = Instant.now()
        val t = save(TaskInput(title = "call ماما", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Engine.sync(app, now)
        ActionReceiver.handle(app, ActionReceiver.DONE, t.id, t.dueAt, now.plusNanos(123_456_789))
        assertNotNull(Repo.state(app).task(t.id)!!.completedAt)
        val undo = nm.activeNotifications.single { it.id == Notifier.UNDO_ID }.notification
        val i = shadowOf(undo.actions[0].actionIntent).savedIntent
        ActionReceiver.handle(app, ActionReceiver.UNDO, t.id, null, now.plusSeconds(3), i.getStringExtra(ActionReceiver.EXTRA_COPY),
            i.getLongExtra(ActionReceiver.EXTRA_DONE_AT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
        assertNull("reopened", Repo.state(app).task(t.id)!!.completedAt)
    }

    @Test fun severalEarlyNoticesEachShowOnce() {
        val now = Instant.now()
        val due = now.plusSeconds(2 * 86_400)
        val t = save(TaskInput(title = "موعد طبي", dueAt = due, remindBefore = 60), now.minusSeconds(60))
        Repo.store(app).change { s -> s.copy(tasks = s.tasks.map { if (it.id == t.id) it.withExt(AlertExt(alerts = listOf(1440, 60))) else it }) }
        val task = Repo.state(app).task(t.id)!!
        assertEquals(listOf(1440, 60), Tasks.noticeMinutes(task))
        var noticeLog = emptyMap<String, String>()
        var shown = 0
        for (at in listOf(due.minusSeconds(1440 * 60), due.minusSeconds(1440 * 60 - 5), due.minusSeconds(3600), due.minusSeconds(3590))) {
            val plan = Planner.plan(Repo.state(app), at, 0, emptyMap(), noticeLog)
            shown += plan.notices.size
            noticeLog = plan.noticeLog
        }
        assertEquals("one per lead time", 2, shown)
        // The next wake before the day-before notice is that notice.
        val plan = Planner.plan(Repo.state(app), now, 0, emptyMap(), emptyMap())
        assertEquals(due.minusSeconds(1440 * 60), plan.otherAt)
    }

    @Test fun theMorningSummaryCountsTodayAndShowsNoTitleWhenDetailsAreHidden() {
        val now = Instant.parse("2026-11-15T05:00:00Z") // 08:00 in Baghdad
        Repo.store(app).change { s0 ->
            var s = s0
            for ((title, h) in listOf("a" to 9, "b" to 13, "c" to 23)) s = Tasks.save(s, TaskInput(title = title, dueAt = now.plusSeconds((h - 8) * 3600L)), now.minusSeconds(60), zone).first
            s = Tasks.save(s, TaskInput(title = "tomorrow", dueAt = now.plusSeconds(30 * 3600L)), now.minusSeconds(60), zone).first
            s
        }
        assertEquals(3, Summary.countToday(Repo.state(app), now, zone))
        assertEquals(Instant.parse("2026-11-16T05:00:00Z"), Summary.nextAt(8 * 60, now, zone))
        Repo.prefs(app).lockPrivacy = true
        Summary.fire(app, now)
        val n = nm.activeNotifications.single { it.id == Notifier.MORNING_ID }.notification
        assertEquals("لديك 3 تذكيرات اليوم", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertNull(n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))
    }

    @Test fun beforeTheUnlockOnlyTimesAreKeptAndAnUnlockDoesNotRingTwice() {
        val now = Instant.now()
        val t = save(TaskInput(title = "سري جدًا", body = "ملاحظة", dueAt = now.plusSeconds(600)), now.minusSeconds(60))
        Engine.sync(app, now)
        val raw = app.createDeviceProtectedStorageContext().getSharedPreferences("boot_times", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("سري") || raw.contains("ملاحظة") || raw.contains(t.id))
        assertEquals(listOf(t.dueAt!!.toEpochMilli() to false), DirectBoot.times(app))
        // Locked after a restart: the time comes, the phone rings with «لديك تذكير».
        shadowOf(app.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(false)
        DirectBoot.fire(app, t.dueAt.toEpochMilli() + 500)
        val locked = nm.activeNotifications.single { it.id == DirectBoot.LOCKED_ID }.notification
        assertEquals("لديك تذكير", locked.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals(Notifier.CH_ALERT, locked.channelId)
        // Unlocked: the real notification appears, quietly (it already rang), and the placeholder goes.
        shadowOf(app.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
        val plan = Engine.sync(app, Instant.ofEpochMilli(t.dueAt.toEpochMilli() + 60_000))
        assertTrue(nm.activeNotifications.any { Notifier.dueTask(it) == t.id })
        assertTrue(nm.activeNotifications.none { it.id == DirectBoot.LOCKED_ID })
        assertTrue(plan.due.any { it.id == t.id })
        assertNull("no ring for the occurrence that rang while locked", startedRing())
    }

    @Test fun aReminderCanHideItsDetailsOnTheLockScreenWhileThePhoneShowsThem() {
        val now = Instant.now()
        val t = save(TaskInput(title = "سري", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Repo.store(app).change { s -> s.copy(tasks = s.tasks.map { if (it.id == t.id) it.withExt(AlertExt(unknown = mapOf("lock" to kotlinx.serialization.json.JsonPrimitive("hide")))) else it }) }
        Engine.sync(app, now)
        val n = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("لديك تذكير", n.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
    }

    @Test fun theChannelsAreTheFixedSetAndTheDueOnesHaveNoSoundOfTheirOwn() {
        val ids = nm.notificationChannels.map { it.id }.toSet()
        assertEquals(setOf("due_normal_v1", "due_alarm_v1", "reminders_v1", "reminders_quiet_v1", "reminders_vibrate_v1", "notices_v1", "summary_v1"), ids)
        assertNull(nm.getNotificationChannel(Notifier.CH_NORMAL).sound)
        assertNull(nm.getNotificationChannel(Notifier.CH_ALARM).sound)
        assertNotNull(nm.getNotificationChannel(Notifier.CH_ALERT).sound)
    }

    @Test fun theAppNeverAsksForDndAccess() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions.orEmpty().toSet()
        assertFalse(perms.contains("android.permission.ACCESS_NOTIFICATION_POLICY"))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE))
    }
}
