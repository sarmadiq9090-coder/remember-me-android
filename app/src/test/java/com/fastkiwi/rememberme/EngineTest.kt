package com.fastkiwi.rememberme

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.SystemReceiver
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.ui.L10n
import kotlinx.serialization.json.JsonObject
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
 * The alarm engine on a simulated Android 16 phone: alarms, notifications, the notification buttons,
 * re-arming after a reboot, and a time-zone change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 31, 33, 34, 36])
class EngineTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private lateinit var tzBefore: TimeZone

    @Before fun setUp() {
        Engine.drain()
        tzBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Notifier.channels(app)
    }

    @After fun tearDown() { TimeZone.setDefault(tzBefore) }

    private val am get() = app.getSystemService(AlarmManager::class.java)
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    /** The next alarm that wakes the phone (the midnight housekeeping window does not, and is ignored). */
    private fun nextWake() = shadowOf(am).scheduledAlarms.filter { it.type == AlarmManager.RTC_WAKEUP }.minByOrNull { it.triggerAtTime }
    private fun save(input: TaskInput, now: Instant = Instant.now()) =
        Repo.store(app).change { Tasks.save(it, input, now, zone).first }.tasks.last()
    private fun shown() = shadowOf(nm).allNotifications.size
    private fun dueTags() = nm.activeNotifications.mapNotNull { Notifier.dueTask(it) }

    @Test fun armsAnAlarmClockForTheNextReminder() {
        val due = Instant.now().plusSeconds(3600)
        save(TaskInput(title = "later", dueAt = due))
        Engine.sync(app)
        val next = nextWake()
        assertNotNull(next)
        assertEquals(due.toEpochMilli(), next!!.triggerAtTime)
        assertNotNull("Uses setAlarmClock (Doze-exempt)", am.nextAlarmClock)
        assertEquals(due.toEpochMilli(), Repo.prefs(app).armedAt)
        assertEquals(0, shown())
    }

    @Test fun fallsBackToAnInexactAlarmWithoutThePermission() {
        // Below Android 12 there is no exact-alarm permission to lose.
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        save(TaskInput(title = "later", dueAt = Instant.now().plusSeconds(3600)))
        Engine.sync(app)
        assertNotNull(nextWake())
        assertNull(am.nextAlarmClock)
        assertFalse(Engine.exact)
    }

    @Test fun dueRemindersArePostedWithButtonsAndStayUntilDone() {
        val now = Instant.now()
        val t = save(TaskInput(title = "دواء الضغط", dueAt = now.minusSeconds(30)), now.minusSeconds(60))
        Engine.sync(app, now)
        val n = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertEquals(Notifier.CH_NORMAL, n.channelId)
        assertTrue("Ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(3, n.actions.size)
        assertEquals("أجّل حتى…", n.actions[2].title.toString())
        assertEquals("تم", n.actions[0].title.toString())
        assertEquals("أجّل 10 دقائق", n.actions[1].title.toString())
        assertNotNull(n.deleteIntent)
        // Swiped away: it comes back on the next run, quietly.
        nm.cancel(Notifier.dueId(t.id))
        ActionReceiver.handle(app, ActionReceiver.DISMISSED, t.id, t.dueAt, now.plusSeconds(5))
        val again = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertEquals(Notifier.CH_QUIET, again.channelId)
        // Done from the notification: gone, and the task is completed.
        ActionReceiver.handle(app, ActionReceiver.DONE, t.id, t.dueAt, now.plusSeconds(10))
        assertTrue(dueTags().isEmpty())
        assertNotNull(Repo.state(app).task(t.id)!!.completedAt)
    }

    @Test fun snoozeFromTheNotificationUsesTheFirstPresetAndStaleTapsAreIgnored() {
        val now = Instant.now()
        val t = save(TaskInput(title = "r", dueAt = now.minusSeconds(30), repeat = Repeat.DAILY), now.minusSeconds(60))
        Engine.sync(app, now)
        ActionReceiver.handle(app, ActionReceiver.SNOOZE, t.id, t.dueAt, now)
        val snoozed = Repo.state(app).task(t.id)!!
        assertEquals(now.plusSeconds(600), snoozed.dueAt)
        assertTrue(dueTags().isEmpty())
        assertEquals(now.plusSeconds(600).toEpochMilli(), nextWake()!!.triggerAtTime)
        // An old notification's Done (for the occurrence before the snooze) changes nothing.
        ActionReceiver.handle(app, ActionReceiver.DONE, t.id, t.dueAt, now.plusSeconds(1))
        assertEquals(snoozed, Repo.state(app).task(t.id))
    }

    @Test fun manyDueRemindersShowAtMostEightPlusASummary() {
        val now = Instant.now()
        Repo.store(app).change { s ->
            var x = s
            for (i in 0 until 20) x = Tasks.save(x, TaskInput(title = "t$i", dueAt = now.minusSeconds(3600L - i)), now.minusSeconds(4000), zone).first
            x
        }
        Engine.sync(app, now)
        assertEquals(Notifier.MAX_SHOWN, dueTags().size)
        assertTrue(nm.activeNotifications.any { it.id == 1 && it.tag == null })
    }

    @Test fun rebootAndTimeChangesReArm() {
        val due = Instant.now().plusSeconds(7200)
        save(TaskInput(title = "after reboot", dueAt = due))
        // The boot receiver is registered for every re-arm broadcast.
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED", "android.intent.action.QUICKBOOT_POWERON")) {
            val receivers = app.packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)
            assertTrue(action, receivers.any { it.activityInfo.name == SystemReceiver::class.java.name })
        }
        // A reboot clears every alarm; BOOT_COMPLETED sets it again.
        assertNull(nextWake())
        // Delivered as the system does after a reboot (Robolectric does not route protected broadcasts sent by the app).
        SystemReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        Engine.drain()
        assertEquals(due.toEpochMilli(), nextWake()!!.triggerAtTime)
    }

    @Test fun aTimeZoneChangeKeepsOneTimeInstantsAndRebuildsRepeats() {
        val now = Instant.now()
        val once = save(TaskInput(title = "once", dueAt = now.plusSeconds(3600)), now)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"))
        Engine.sync(app, now)
        assertEquals(once.dueAt!!.toEpochMilli(), nextWake()!!.triggerAtTime)
    }

    @Test fun earlyNoticeIsShownOnceOnItsOwnChannel() {
        val now = Instant.now()
        val t = save(TaskInput(title = "meeting", dueAt = now.plusSeconds(10 * 60), remindBefore = 15), now.minusSeconds(3600))
        Engine.sync(app, now)
        val notice = nm.activeNotifications.single { it.tag == Notifier.TAG_NOTICE + t.id }
        assertEquals(Notifier.CH_NOTICE, notice.notification.channelId)
        nm.cancelAll()
        Engine.sync(app, now.plusSeconds(30))
        assertTrue(nm.activeNotifications.none { it.tag == Notifier.TAG_NOTICE + t.id })
        assertEquals(t.dueAt!!.toEpochMilli(), nextWake()!!.triggerAtTime)
    }

    @Test fun lockScreenPrivacyHidesTheTitle() {
        Repo.prefs(app).lockPrivacy = true
        val now = Instant.now()
        val t = save(TaskInput(title = "secret", dueAt = now.minusSeconds(5)), now.minusSeconds(60))
        Engine.sync(app, now)
        val n = nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("لديك تذكير", n.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        // F-022: the hidden version keeps «تم» and «أجّل», and shows no title.
        assertEquals("تم", n.publicVersion.actions[0].title.toString())
        assertTrue(listOf(android.app.Notification.EXTRA_TITLE, android.app.Notification.EXTRA_TEXT).none { n.publicVersion.extras.getCharSequence(it)?.contains("secret") == true })
    }

    @Test fun arabicTextUsesWesternDigits() {
        val ar = L10n.context(app)
        val at = Instant.parse("2026-11-14T14:35:00Z")
        val texts = listOf(
            L10n.dueText(ar, at, at.minusSeconds(86_400 * 3)), L10n.late(ar, at, at.plusSeconds(25 * 60))!!,
            L10n.duration(ar, 3), L10n.duration(ar, 11), L10n.duration(ar, 1440 * 3), L10n.date(ar, at),
            ar.getString(R.string.n_of_m, "1", "3"), L10n.plural(ar, R.plurals.reminders_waiting, 12),
        )
        for (t in texts) assertFalse("Arabic-Indic digit in \"$t\"", t.any { it in '٠'..'٩' || it in '۰'..'۹' })
        assertTrue(texts[1], texts[1].contains("25 دقيقة"))
        assertEquals("3 دقائق", texts[2])
        assertTrue(texts[0], texts[0].contains("5:35"))
        // Iraq and the Levant: «تشرين الثاني»; the choice "standard": «نوفمبر».
        Repo.store(app).change { s -> s.withSettings(JsonObject(s.settings + ("arabicMonths" to JsonPrimitive("levant")))) }
        assertTrue(L10n.date(L10n.context(app), at).contains("تشرين الثاني"))
        Repo.store(app).change { s -> s.withSettings(JsonObject(s.settings + ("arabicMonths" to JsonPrimitive("standard")))) }
        assertTrue(L10n.date(L10n.context(app), at).contains("نوفمبر"))
    }

    @Test fun noInternetPermissionAndReceiversAreLockedDown() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_RECEIVERS)
        assertFalse((info.requestedPermissions ?: emptyArray()).contains(Manifest.permission.INTERNET))
        // Exported: only the system-broadcast receiver, and AndroidX's profile installer, which needs the DUMP permission.
        val exported = info.receivers!!.filter { it.exported && it.permission != "android.permission.DUMP" }.map { it.name }
        // Plus the direct-boot receiver (F-026), which handles LOCKED_BOOT_COMPLETED only.
        assertEquals(setOf(SystemReceiver::class.java.name, com.fastkiwi.rememberme.alarm.LockedReceiver::class.java.name), exported.toSet())
    }

    @Test fun dataSurvivesAProcessRestart() {
        save(TaskInput(title = "persist", dueAt = Instant.now().plusSeconds(60)))
        Repo.resetForTests()
        assertEquals("persist", Repo.state(app).tasks.single().title)
        assertTrue(File(app.filesDir, "data/data.json").readText().contains("\"version\":1"))
        assertEquals(Codec.decode(File(app.filesDir, "data/data.json").readText()).tasks.single().title, "persist")
    }

    @Test fun aRunThatWaitedForTheLockPlansForTheTimeItRuns() {
        val due = Instant.now().plusMillis(700)
        val t = save(TaskInput(title = "race", dueAt = due), Instant.now().minusSeconds(60))
        val lock = Engine::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null)!!
        lateinit var waiting: Thread
        synchronized(lock) {
            // A data change starts a run just before the reminder is due and has to wait for the lock...
            waiting = Thread { Engine.sync(app) }.apply { start() }
            while (waiting.state != Thread.State.BLOCKED) Thread.sleep(5)
            Thread.sleep(1000)
            // ...while the alarm's run, at the due time, announces it.
            Engine.sync(app)
            assertTrue(dueTags().contains(t.id))
        }
        waiting.join(10_000)
        // The waiting run must not take it back (and so sound it a second time on the next run).
        assertTrue("still shown", dueTags().contains(t.id))
        assertEquals(Codec.iso(due), Repo.prefs(app).alertLog[t.id]?.substringBeforeLast('|'))
    }

    @Test fun aFailedWriteDuringTheTrashPurgeStillArmsTheAlarm() {
        val now = Instant.now()
        val next = now.plusSeconds(3600)
        Repo.store(app).change { s0 ->
            val s1 = Tasks.save(s0, TaskInput(title = "old trash"), now.minusSeconds(40 * 86_400L), zone).first
            val s2 = Tasks.trash(s1, s1.tasks.last().id, now.minusSeconds(40 * 86_400L))
            Tasks.save(s2, TaskInput(title = "next", dueAt = next), now, zone).first
        }
        // The disk refuses the write (full, or the file is in the way): the purge cannot be saved.
        File(app.filesDir, "data/data.json.tmp").mkdirs()
        try {
            Engine.sync(app, now)
            assertEquals(next.toEpochMilli(), nextWake()!!.triggerAtTime)
            // A notification button whose change cannot be saved does not crash the app either.
            ActionReceiver.handle(app, ActionReceiver.DONE, Repo.state(app).tasks.last().id, next, now)
        } finally { File(app.filesDir, "data/data.json.tmp").deleteRecursively() }
    }

    @Test fun anEarlyNoticeIsWithdrawnWhenTheReminderMovesLater() {
        val now = Instant.now()
        val t = save(TaskInput(title = "meeting", dueAt = now.plusSeconds(10 * 60), remindBefore = 15), now.minusSeconds(3600))
        Engine.sync(app, now)
        assertTrue(nm.activeNotifications.any { it.tag == Notifier.TAG_NOTICE + t.id })
        Repo.store(app).change { Tasks.save(it, TaskInput(id = t.id, title = "meeting", dueAt = now.plusSeconds(5 * 3600), remindBefore = 15), now, zone).first }
        Engine.sync(app, now.plusSeconds(5))
        assertTrue(nm.activeNotifications.none { it.tag == Notifier.TAG_NOTICE + t.id })
    }

    @Test fun theReminderChannelDoesNotOverrideLockScreenChoice() {
        // -1000 is NotificationManager.VISIBILITY_NO_OVERRIDE (a hidden constant).
        assertEquals(-1000, nm.getNotificationChannel(Notifier.CH_NORMAL).lockscreenVisibility)
    }
}
