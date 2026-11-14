package com.fastkiwi.rememberme

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.AlarmReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.SystemReceiver
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.Migrations
import com.fastkiwi.rememberme.data.ScheduleLog
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * 0.2.0 — «الثقة والاعتمادية»: the data is the source of truth and every rebuild is idempotent; the
 * alarm API follows the kind of moment; the schedule log holds no reminder content; clock jumps,
 * time-zone moves and reboots never lose or double a reminder; the alarm test; migrations; restores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 31, 33, 34, 36])
class EngineReliabilityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private lateinit var tzBefore: TimeZone

    @Before fun setUp() {
        Engine.drain()
        tzBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "schedule_log", "alarm_test")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Notifier.channels(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After fun tearDown() { TimeZone.setDefault(tzBefore) }

    private val am get() = app.getSystemService(AlarmManager::class.java)
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun save(input: TaskInput, now: Instant = Instant.now()) = Repo.store(app).change { Tasks.save(it, input, now, zone).first }.tasks.last()
    private fun dueTags() = nm.activeNotifications.mapNotNull { Notifier.dueTask(it) }
    /** Alarms that wake the phone (the midnight housekeeping window does not). */
    private fun wakeAlarms() = shadowOf(am).scheduledAlarms.filter { it.type == AlarmManager.RTC_WAKEUP }
    private fun loudPosts() = shadowOf(nm).allNotifications.count { it.channelId == Notifier.CH_NORMAL || it.channelId == Notifier.CH_ALARM }

    @Test fun aDueTimeUsesAnAlarmClockAndANoticeAnExactAlarmWithoutTheIcon() {
        val now = Instant.now()
        save(TaskInput(title = "x", dueAt = now.plusSeconds(3 * 3600), remindBefore = 60), now.minusSeconds(60))
        Engine.sync(app, now)
        // Two slots (D-079): the due time is an alarm clock (slot A), the notice in 2 h an exact alarm
        // allowed in Doze, without the alarm-clock icon (slot B). B never replaces A.
        assertEquals(now.plusSeconds(3 * 3600).toEpochMilli(), am.nextAlarmClock!!.triggerTime)
        assertEquals(now.plusSeconds(2 * 3600).toEpochMilli(), wakeAlarms().minOf { it.triggerAtTime })
        val e = ScheduleLog(app).last()!!
        assertEquals("CLOCK", e.api)
        assertEquals("EXACT", e.otherApi)
        assertTrue(e.verified)
        // After the notice, only the due time is left: still the same alarm clock.
        Engine.sync(app, now.plusSeconds(2 * 3600 + 1))
        assertEquals(now.plusSeconds(3 * 3600).toEpochMilli(), am.nextAlarmClock!!.triggerTime)
        assertEquals("CLOCK", ScheduleLog(app).last()!!.api)
        assertTrue(ScheduleLog(app).last()!!.verified)
    }

    @Test fun withoutExactAlarmsItFallsBackAndTheLogSaysSo() {
        // Below Android 12 exact alarms are always allowed.
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        save(TaskInput(title = "x", dueAt = Instant.now().plusSeconds(3600)))
        Engine.sync(app)
        assertEquals("INEXACT", ScheduleLog(app).last()!!.api)
        assertNull(am.nextAlarmClock)
        assertNotNull(shadowOf(am).peekNextScheduledAlarm())
    }

    @Test fun theScheduleLogHoldsNoReminderContent() {
        val t = save(TaskInput(title = "موعد سري جدًا", body = "ملاحظة خاصة", dueAt = Instant.now().plusSeconds(3600)))
        Engine.sync(app, reason = "check")
        val raw = app.getSharedPreferences("schedule_log", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("سري") || raw.contains("خاصة") || raw.contains(t.id))
        val e = ScheduleLog(app).last()!!
        assertEquals("check", e.reason)
        assertEquals(1, e.scheduled)
        assertEquals(t.dueAt!!.toEpochMilli(), e.wakeAt)
    }

    @Test fun rebuildingTenTimesGivesOneAlarmOneNotificationAndOneSound() {
        val now = Instant.now()
        save(TaskInput(title = "due", dueAt = now.minusSeconds(30)), now.minusSeconds(60))
        save(TaskInput(title = "later", dueAt = now.plusSeconds(3600)), now.minusSeconds(60))
        repeat(10) { Engine.sync(app, now.plusMillis(it * 100L)) }
        assertEquals(1, dueTags().size)
        assertEquals("The reminder sounded once", 1, loudPosts())
        assertEquals(1, wakeAlarms().size)
        // Several receivers at once (reboot + time change + an alarm) still give one of each.
        val threads = (1..4).map { Thread { Engine.sync(app, now.plusSeconds(1)) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, dueTags().size)
        assertEquals(1, loudPosts())
        assertEquals(1, wakeAlarms().size)
    }

    @Test fun twoRemindersInTheSameMinuteAreBothAnnounced() {
        val now = Instant.now()
        save(TaskInput(title = "a", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        save(TaskInput(title = "b", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        save(TaskInput(title = "c", dueAt = now.minusSeconds(4)), now.minusSeconds(60))
        Engine.sync(app, now)
        assertEquals(3, dueTags().size)
        assertEquals(3, loudPosts())
    }

    @Test fun theClockSetForwardAnnouncesOnceAndSetBackNeverRepeats() {
        val now = Instant.now()
        val t = save(TaskInput(title = "x", dueAt = now.plusSeconds(3600)), now)
        Engine.sync(app, now)
        // The user sets the clock two hours forward: TIME_SET re-plans at the new time.
        Engine.sync(app, now.plusSeconds(7200), "time")
        assertEquals(listOf(t.id), dueTags())
        assertEquals(1, loudPosts())
        // And back again: the reminder is not due any more; nothing sounds twice.
        Engine.sync(app, now, "time")
        assertTrue(dueTags().isEmpty())
        Engine.sync(app, now.plusSeconds(7200), "time")
        assertEquals("The same occurrence never sounds twice", 1, loudPosts())
    }

    @Test fun aRebootClearsAlarmsAndBootSetsTheSameOneAgain() {
        val due = Instant.now().plusSeconds(7200)
        save(TaskInput(title = "x", dueAt = due))
        Engine.sync(app)
        val before = shadowOf(am).peekNextScheduledAlarm()!!.triggerAtTime
        // A reboot wipes every alarm.
        for (a in shadowOf(am).scheduledAlarms.toList()) a.operation?.let { am.cancel(it) }
        assertNull(shadowOf(am).peekNextScheduledAlarm())
        SystemReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        Engine.drain()
        assertEquals(before, shadowOf(am).peekNextScheduledAlarm()!!.triggerAtTime)
        assertEquals("boot", ScheduleLog(app).lastBoot()!!.reason)
        assertEquals(SystemReceiver.reasonOf(Intent.ACTION_TIMEZONE_CHANGED), "zone")
    }

    @Test fun aWipedAlarmIsSeenAsNotVerifiedUntilTheNextRun() {
        val due = Instant.now().plusSeconds(7200)
        save(TaskInput(title = "x", dueAt = due))
        Engine.sync(app)
        assertTrue(Engine.isArmed(app, Engine.SLOT_A, due, Engine.Api.CLOCK))
        for (a in shadowOf(am).scheduledAlarms.toList()) a.operation?.let { am.cancel(it); it.cancel() }
        assertFalse(Engine.isArmed(app, Engine.SLOT_A, due, Engine.Api.CLOCK))
        Engine.sync(app, reason = "check")
        assertTrue(ScheduleLog(app).last()!!.verified)
    }

    @Test fun theAlarmTestGoesThroughTheRealPathAndAddsNoReminder() {
        val now = Instant.now()
        TestAlarm.start(app, now)
        val alarm = wakeAlarms().single()
        assertEquals(now.plusSeconds(TestAlarm.DELAY_SECONDS).toEpochMilli(), alarm.triggerAtTime)
        // The alarm fires: the receiver posts the test reminder on the real reminder channel.
        AlarmReceiver().onReceive(app, Intent(TestAlarm.ACTION))
        val n = nm.activeNotifications.single { it.id == Notifier.TEST_ID }.notification
        assertEquals(Notifier.CH_NORMAL, n.channelId)
        assertEquals("تم", n.actions[0].title.toString())
        val rec = TestAlarm.record(app)
        assertTrue(rec.firedAt > 0 && rec.posted)
        // «تم» from the notification is recorded and removes the test reminder.
        ActionReceiver().onReceive(app, Intent(ActionReceiver.TEST_DONE))
        assertTrue(TestAlarm.record(app).actionAt > 0)
        assertTrue(nm.activeNotifications.none { it.id == Notifier.TEST_ID })
        assertTrue("No reminder was added", Repo.state(app).tasks.isEmpty())
    }

    @Test fun aMigrationKeepsACopyAndRefusesToLoseAReminder() {
        val store = Repo.store(app)
        save(TaskInput(title = "keep me", dueAt = Instant.now().plusSeconds(3600)))
        save(TaskInput(title = "and me"))
        // A good step: applied, copy kept.
        val good = Migrations.Step(1, 2) { s -> s.copy(tasks = s.tasks.map { it.copy(body = it.body + "!") }) }
        assertEquals(2, store.migrate(1, 2, listOf(good)))
        assertTrue(store.state.value.tasks.all { it.body.endsWith("!") })
        assertEquals(1, store.safetyCopies().count { it.name.startsWith("before-migration-v1-") })
        // A step that drops a reminder: refused, nothing written.
        val lossy = Migrations.Step(2, 3) { s -> s.copy(tasks = s.tasks.drop(1)) }
        assertEquals(2, store.migrate(2, 3, listOf(lossy)))
        assertEquals(2, store.state.value.tasks.size)
        // A step that crashes: refused.
        val broken = Migrations.Step(2, 3) { _ -> error("bug") }
        assertEquals(2, store.migrate(2, 3, listOf(broken)))
        // A gap in the steps: refused.
        assertEquals(2, store.migrate(2, 4, listOf(Migrations.Step(3, 4) { it })))
        // Data from disk reads the same after all this.
        Repo.resetForTests()
        assertEquals(2, Repo.state(app).tasks.size)
    }

    @Test fun aBrokenNoticeSlotNeverDelaysADueTime() {
        val now = Instant.now()
        val t = save(TaskInput(title = "x", dueAt = now.plusSeconds(3 * 3600), remindBefore = 60), now.minusSeconds(60))
        Engine.sync(app, now)
        // Both slots are set: the due time as an alarm clock, the notice as an exact alarm.
        assertEquals(t.dueAt!!.toEpochMilli(), am.nextAlarmClock!!.triggerTime)
        assertEquals(2, shadowOf(am).scheduledAlarms.count { it.triggerAtTime == t.dueAt.toEpochMilli() || it.triggerAtTime == now.plusSeconds(2 * 3600).toEpochMilli() })
        // Slot B is wiped (an OEM killer, a lost allow-while-idle): slot A still holds the due time.
        val b = shadowOf(am).scheduledAlarms.first { it.triggerAtTime == now.plusSeconds(2 * 3600).toEpochMilli() }
        am.cancel(b.operation!!)
        assertEquals(t.dueAt.toEpochMilli(), am.nextAlarmClock!!.triggerTime)
        // When slot A fires, the reminder is announced.
        Engine.sync(app, t.dueAt.plusSeconds(1), "alarm")
        assertEquals(listOf(t.id), dueTags())
    }

    @Test fun dataFromANewerFormatIsKeptUntouchedAndNeverOverwritten() {
        val dir = File(app.filesDir, "data").apply { mkdirs() }
        val newer = """{"version":2,"tasks":[{"id":"a","title":"from the future"}],"notes":[],"something":"new"}"""
        File(dir, "data.json").writeText(newer)
        Repo.resetForTests()
        val store = Repo.store(app)
        assertTrue(store.newer)
        assertTrue(Repo.state(app).tasks.isEmpty())
        // No write may replace it, and it is not renamed as damaged.
        assertTrue(runCatching { store.change { Tasks.save(it, TaskInput(title = "x"), Instant.now(), zone).first } }.isFailure)
        Engine.sync(app)
        assertEquals(newer, File(dir, "data.json").readText())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("data.json.damaged") })
    }

    @Test fun aNewVersionKeepsACopyOfTheDataBeforeTouchingIt() {
        save(TaskInput(title = "before the update"))
        Repo.prefs(app).lastVersion = 1
        Repo.resetForTests()
        val store = Repo.store(app)
        val copy = store.safetyCopies().single { it.name.startsWith("before-update-v1-") }
        assertTrue(Codec.decode(copy.readText()).tasks.any { it.title == "before the update" })
        assertEquals(BuildConfig.VERSION_CODE, Repo.prefs(app).lastVersion)
        // Opening again with the same version adds no copy.
        Repo.resetForTests()
        assertEquals(1, Repo.store(app).safetyCopies().count { it.name.startsWith("before-update-") })
    }
}
