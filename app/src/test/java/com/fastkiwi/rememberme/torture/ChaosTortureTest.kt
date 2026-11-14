package com.fastkiwi.rememberme.torture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.AlarmReceiver
import com.fastkiwi.rememberme.alarm.DirectBoot
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.SystemReceiver
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.ScheduleLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Instant

/**
 * Chaos (torture suite 5) on the engine: disk full, permissions taken away mid-flow, actions on reminders that
 * are gone or done, double taps, stale taps, an alarm with no data at all, and the locked (direct-boot) path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36])
class ChaosTortureTest {
    private val h = EngineHarness()

    @Before fun setUp() { Torture.assumeOn(); h.setUp() }
    @After fun tearDown() { if (Torture.on) { File(h.app.filesDir, "data").deleteRecursively(); shadowOf(h.app.getSystemService(UserManager::class.java)).setUserUnlocked(true); h.tearDown() } }

    private fun save(input: TaskInput, at: Instant = Torture.now().minusSeconds(3600)) = Repo.store(h.app).change { Tasks.save(it, input, at, h.zone).first }.tasks.last()
    private val dataFile get() = File(h.app.filesDir, "data/data.json")

    // ---- disk full -------------------------------------------------------------------------------------------

    /** Writes fail (the .tmp cannot be created, or the .bak cannot be replaced): nothing half-written, alarms still set. */
    @Test fun diskFullChangesNothingAndTheAlarmStaysArmed() {
        val now = Torture.now()
        val due = save(TaskInput(title = "due", dueAt = now.minusSeconds(60)))
        val next = save(TaskInput(title = "next", dueAt = now.plusSeconds(3600)))
        Engine.sync(h.app)
        for (obstacle in listOf("data.json.tmp", "data.json.bak")) {
            val before = dataFile.readText()
            val memory = Repo.state(h.app)
            File(h.app.filesDir, "data/$obstacle").apply { delete(); mkdirs(); File(this, "x").writeText("x") }
            try {
                ActionReceiver.handle(h.app, ActionReceiver.DONE, due.id, due.dueAt, Torture.now())
                ActionReceiver.handle(h.app, ActionReceiver.SNOOZE, due.id, due.dueAt, Torture.now())
                assertTrue("$obstacle: the app's change call throws IOException", runCatching { Repo.change(h.app) { s -> Tasks.trash(s, next.id, now) } }.exceptionOrNull() is java.io.IOException)
                assertEquals("$obstacle: file unchanged", before, dataFile.readText())
                assertEquals("$obstacle: memory unchanged", memory, Repo.state(h.app))
                Engine.sync(h.app, reason = "alarm")
                assertEquals("$obstacle: alarm still armed", next.dueAt!!.toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
                assertTrue(h.dueShown().contains(due.id))
            } finally { File(h.app.filesDir, "data/$obstacle").deleteRecursively() }
        }
        // The disk has room again: the same tap now works.
        ActionReceiver.handle(h.app, ActionReceiver.DONE, due.id, due.dueAt, Torture.now())
        assertNotNull(Repo.state(h.app).task(due.id)!!.completedAt)
        Repo.resetForTests()
        assertNotNull("written to disk", Repo.state(h.app).task(due.id)!!.completedAt)
        assertTrue(h.crashes.isEmpty())
    }

    // ---- permissions -----------------------------------------------------------------------------------------

    /**
     * Notifications are off (the user switched them off, or Android 13+ permission revoked) while a reminder comes
     * due; they are switched on again. The reminder must then ring: nothing was ever shown or heard.
     */
    @Test fun aReminderDueWhileNotificationsWereOffRingsWhenTheyComeBack() {
        val now = Torture.now()
        val t = save(TaskInput(title = "while blocked", dueAt = now.minusSeconds(30)))
        val shadowNm = shadowOf(h.nm)
        shadowNm.setNotificationsEnabled(false)
        if (android.os.Build.VERSION.SDK_INT >= 33) shadowOf(h.app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Engine.sync(h.app, reason = "alarm")
        assertTrue(h.dueShown().isEmpty())
        assertEquals(0, h.ringsOf(t.id))
        shadowNm.setNotificationsEnabled(true)
        shadowOf(h.app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Engine.sync(h.app, reason = "start")
        assertTrue("shown now", h.dueShown().contains(t.id))
        assertEquals("it never rang", 1, h.ringsOf(t.id))
    }

    /** Exact alarms revoked, then granted again (Android 12+): always some alarm at the right moment, the log says which. */
    @Test fun exactAlarmsRevokedAndGrantedMidFlowNeverLeaveNoAlarm() {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        val now = Torture.now()
        val t = save(TaskInput(title = "x", dueAt = now.plusSeconds(3600), remindBefore = 30))
        Engine.sync(h.app)
        assertEquals("CLOCK", ScheduleLog(h.app).last()!!.api)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        SystemReceiver().onReceive(h.app, Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
        Engine.drain()
        assertEquals("INEXACT", ScheduleLog(h.app).last()!!.api)
        assertTrue(h.wakeAlarms().any { it.triggerAtTime == t.dueAt!!.toEpochMilli() })
        // A change while revoked still arms.
        Repo.change(h.app) { s -> Tasks.snooze(s, t.id, now.plusSeconds(7200), now)!! }
        assertTrue(h.wakeAlarms().any { it.triggerAtTime == now.plusSeconds(7200).toEpochMilli() })
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        SystemReceiver().onReceive(h.app, Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
        Engine.drain()
        assertEquals("CLOCK", ScheduleLog(h.app).last()!!.api)
        assertEquals(now.plusSeconds(7200).toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
    }

    // ---- taps ------------------------------------------------------------------------------------------------

    @Test fun actionsOnADeletedOrDoneReminderChangeNothing() {
        val now = Torture.now()
        val gone = save(TaskInput(title = "gone", dueAt = now.minusSeconds(60)))
        val done = save(TaskInput(title = "done", dueAt = now.minusSeconds(60)))
        Engine.sync(h.app)
        Repo.change(h.app) { s -> Tasks.complete(Tasks.trash(s, gone.id, now), done.id, now, h.zone)!!.first }
        val before = dataFile.readText()
        for (id in listOf(gone.id, done.id)) for (a in listOf(ActionReceiver.DONE, ActionReceiver.SNOOZE, ActionReceiver.DISMISSED)) {
            ActionReceiver.handle(h.app, a, id, now.minusSeconds(60), Torture.now())
        }
        // Purged altogether, then tapped.
        Repo.change(h.app) { s -> Tasks.purge(s, gone.id) }
        val after = dataFile.readText()
        ActionReceiver.handle(h.app, ActionReceiver.DONE, gone.id, now.minusSeconds(60), Torture.now())
        assertEquals(after, dataFile.readText())
        assertTrue(before.contains(done.id) && after.contains(done.id))
        assertTrue(h.crashes.isEmpty())
    }

    /** «تم» and «أجّل» tapped twice (or on the notification and the reminder screen): one effect. */
    @Test fun doubleTapsHaveOneEffect() {
        val now = Torture.now()
        val daily = save(TaskInput(title = "daily", dueAt = now.minusSeconds(60), repeat = Repeat.DAILY))
        val once = save(TaskInput(title = "once", dueAt = now.minusSeconds(60)))
        Engine.sync(h.app)
        repeat(3) { ActionReceiver.handle(h.app, ActionReceiver.DONE, daily.id, daily.dueAt, Torture.now()) }
        assertEquals("one history copy", 1, Repo.state(h.app).tasks.count { it.sourceId == daily.id })
        assertEquals(daily.dueAt!!.plusSeconds(86_400), Repo.state(h.app).task(daily.id)!!.dueAt)
        repeat(3) { ActionReceiver.handle(h.app, ActionReceiver.SNOOZE, once.id, once.dueAt, Torture.now()) }
        assertEquals("snoozed once", 1, Repo.state(h.app).task(once.id)!!.snoozeCount)
        // Two threads at once (notification and reminder screen).
        val two = save(TaskInput(title = "two", dueAt = now.minusSeconds(30), repeat = Repeat.WEEKLY))
        Engine.sync(h.app)
        val threads = (0 until 4).map { Thread { ActionReceiver.handle(h.app, ActionReceiver.DONE, two.id, two.dueAt, Torture.now()) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, Repo.state(h.app).tasks.count { it.sourceId == two.id })
    }

    /** A tap on an old notification of a repeat that has since moved on is ignored. */
    @Test fun aStaleTapFromAnOldOccurrenceIsIgnored() {
        val now = Torture.now()
        val daily = save(TaskInput(title = "daily", dueAt = now.minusSeconds(2 * 86_400L + 60), repeat = Repeat.DAILY), now.minusSeconds(3 * 86_400L))
        Repo.change(h.app) { s -> Tasks.skip(s, daily.id, now.minusSeconds(86_400L + 120), h.zone)!! }
        val before = dataFile.readText()
        for (a in listOf(ActionReceiver.DONE, ActionReceiver.SNOOZE)) ActionReceiver.handle(h.app, a, daily.id, daily.dueAt, Torture.now())
        assertEquals(before, dataFile.readText())
    }

    // ---- no data ---------------------------------------------------------------------------------------------

    @Test fun anAlarmWithNoDataFileOrAnUnreadableOneDoesNotCrash() {
        File(h.app.filesDir, "data").deleteRecursively()
        Repo.resetForTests()
        h.deliver(AlarmReceiver(), Intent(AlarmReceiver.FIRE))
        Engine.drain()
        assertTrue(h.crashes.isEmpty())
        assertTrue(h.wakeAlarms().isEmpty())
        assertTrue(h.dueShown().isEmpty())
        // data.json is a folder (a broken copy tool): still no crash.
        Repo.resetForTests()
        File(h.app.filesDir, "data/data.json").mkdirs()
        h.deliver(AlarmReceiver(), Intent(AlarmReceiver.FIRE))
        Engine.drain()
        assertTrue(h.crashes.toString(), h.crashes.isEmpty())
    }

    // ---- ids the engine logs cannot hold ---------------------------------------------------------------------

    /**
     * A task id may be any non-empty string in data.json (Windows checks only that it is a string, Backup.read and
     * Codec accept it). The engine's logs (Prefs.alertLog, noticeLog, repostLog) are "id<TAB>value" lines, so an
     * id with a line break or a tab does not read back: that reminder counts as never announced and rings again on
     * every engine run (every alarm, app start, change).
     */
    @Test fun anImportedIdWithALineBreakRingsOnlyOnce() {
        val now = Torture.now()
        val due = com.fastkiwi.rememberme.core.Codec.iso(now.minusSeconds(60))
        val text = """{"version":1,"tasks":[{"id":"a\nb","title":"line break","dueAt":"$due"},{"id":"c\td","title":"tab","dueAt":"$due"}],"notes":[]}"""
        val imported = Backup.read(text.toByteArray().inputStream(), now)
        Repo.store(h.app).replace(imported.state)
        repeat(3) { Engine.sync(h.app, reason = "start") }
        assertEquals("rings: ${h.rings()}", listOf(1, 1), listOf(h.ringsOf("a\nb"), h.ringsOf("c\td")))
    }

    /**
     * A reminder dated beyond what epoch milliseconds hold (year > 292 million, e.g. "+999999999-12-31T23:59:59Z"
     * in an imported or hand-edited file: Codec and Backup.read accept it) must not stop the engine.
     */
    @Test fun aReminderDatedBeyondEpochMillisDoesNotStopTheEngine() {
        val now = Torture.now()
        val soon = com.fastkiwi.rememberme.core.Codec.iso(now.plusSeconds(3600))
        val text = """{"version":1,"tasks":[{"id":"far","title":"far","dueAt":"+999999999-12-31T23:59:59Z"},{"id":"due","title":"due","dueAt":"${com.fastkiwi.rememberme.core.Codec.iso(now.minusSeconds(60))}"}],"notes":[]}"""
        val imported = Backup.read(text.toByteArray().inputStream(), now)
        Repo.store(h.app).replace(imported.state)
        val r = runCatching { Engine.sync(h.app, reason = "start") }
        assertTrue("Engine.sync threw ${r.exceptionOrNull()}", r.isSuccess)
        assertTrue("the due reminder is shown", h.dueShown().contains("due"))
        // And with an ordinary reminder ahead, that one is armed.
        Repo.change(h.app) { s -> Tasks.save(s, TaskInput(title = "soon", dueAt = Instant.parse(soon)), now, h.zone).first }
        assertEquals(Instant.parse(soon).toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
    }

    // ---- direct boot -----------------------------------------------------------------------------------------

    private fun bootPrefsText(): String = h.app.createDeviceProtectedStorageContext().getSharedPreferences("boot_times", Context.MODE_PRIVATE).all.entries.joinToString { "${it.key}=${it.value}" }

    /** Device-protected storage holds times and a style only: never a title, a note or an id. */
    @Test fun lockedStorageHoldsNoReminderContent() {
        val now = Torture.now()
        val tasks = (0 until 30).map { save(TaskInput(title = "سري جدًا $it secret", body = "ملاحظة خاصة $it", dueAt = now.plusSeconds(600L * (it + 1)), priority = com.fastkiwi.rememberme.core.Priority.entries[it % 3])) }
        Engine.sync(h.app)
        val text = bootPrefsText()
        assertTrue(text.isNotEmpty())
        for (t in tasks) {
            assertFalse("title in locked storage", text.contains(t.title) || text.contains("سري") || text.contains("secret"))
            assertFalse("note in locked storage", text.contains("ملاحظة"))
            assertFalse("id in locked storage", text.contains(t.id))
        }
        assertTrue("only times, a/n and the language: $text", Regex("^(lang=(ar|en)(, )?|times=([0-9]+:[an],?)*(, )?)+$").matches(text))
        assertEquals(DirectBoot.KEEP, DirectBoot.times(h.app).size)
        // The locked storage folder has only this file (when Robolectric gives it its own folder).
        val dp = h.app.createDeviceProtectedStorageContext().dataDir
        if (dp != h.app.dataDir) {
            val files = dp.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertEquals("device-protected files: $files", listOf("boot_times.xml"), files)
        }
    }

    /** A restart before the unlock: the locked alarm rings once with no title; after the unlock it is shown quietly. */
    @Test fun aLockedRestartRingsOnceAndTheUnlockDoesNotRingAgain() {
        val now = Torture.now()
        val t = save(TaskInput(title = "سري secret", dueAt = now.plusSeconds(120), priority = com.fastkiwi.rememberme.core.Priority.HIGH))
        Engine.sync(h.app, now)
        // Restart: alarms gone, phone locked.
        for (a in shadowOf(h.am).scheduledAlarms.toList()) a.operation?.let { h.am.cancel(it) }
        shadowOf(h.app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertFalse(DirectBoot.unlocked(h.app))
        h.deliver(com.fastkiwi.rememberme.alarm.LockedReceiver(), Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue("the locked alarm is set", h.wakeAlarms().any { it.triggerAtTime == t.dueAt!!.toEpochMilli() } || h.am.nextAlarmClock?.triggerTime == t.dueAt!!.toEpochMilli())
        // An engine alarm arriving while locked must not read storage.
        h.deliver(AlarmReceiver(), Intent(AlarmReceiver.FIRE))
        DirectBoot.fire(h.app, t.dueAt!!.toEpochMilli() + 500)
        DirectBoot.fire(h.app, t.dueAt.toEpochMilli() + 900) // a second fire: nothing new
        val locked = h.nm.activeNotifications.filter { it.id == DirectBoot.LOCKED_ID }
        assertEquals(1, locked.size)
        val extras = locked.single().notification.extras
        val shownText = listOf("android.title", "android.text", "android.bigText").joinToString { extras.getCharSequence(it)?.toString().orEmpty() }
        assertFalse("title on the locked notification: $shownText", shownText.contains("secret") || shownText.contains("سري"))
        // Unlock: the engine shows the real reminder, quietly, and removes the placeholder.
        shadowOf(h.app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        Engine.sync(h.app, t.dueAt.plusSeconds(5), "start")
        assertTrue(h.dueShown().contains(t.id))
        assertEquals("rang a second time after the unlock", 0, h.ringsOf(t.id))
        assertTrue(h.nm.activeNotifications.none { it.id == DirectBoot.LOCKED_ID })
        Engine.sync(h.app, t.dueAt.plusSeconds(10), "start")
        assertEquals(0, h.ringsOf(t.id))
    }

    @Test fun aLockedAlarmWithNothingRememberedDoesNothing() {
        shadowOf(h.app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        DirectBoot.fire(h.app)
        DirectBoot.armNext(h.app)
        assertNull(h.nm.activeNotifications.firstOrNull { it.id == DirectBoot.LOCKED_ID })
        assertTrue(h.crashes.isEmpty())
        Notifier.MAX_SHOWN
    }
}
