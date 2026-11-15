package com.fastkiwi.rememberme

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.alarm.SystemReceiver
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The update from 0.1.0 (versionCode 1), 0.1.1 (versionCode 2), 1.0.0 (3) and 1.0.1 (4) to this version: the data, the phone's settings and
 * the "already announced" log carry over, a safety copy is kept first, the old-style notifications are replaced
 * by one per reminder, and nothing rings a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36])
class UpgradeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private lateinit var tzBefore: TimeZone
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        Engine.drain()
        tzBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "alert_log", "schedule_log")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        nm.cancelAll()
        RingService.ringingId = 0
    }

    @After fun tearDown() { TimeZone.setDefault(tzBefore) }

    /** What 0.1.x left on the phone: data.json v1, its phone settings and logs, and its notification for a due reminder. */
    private fun leaveOldVersion(versionCode: Int?): Pair<String, String> {
        val now = Instant.now()
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "دواء الضغط", dueAt = now.minusSeconds(600), repeat = Repeat.DAILY), now.minusSeconds(7200), zone).first
        s = Tasks.save(s, TaskInput(title = "اجتماع", dueAt = now.plusSeconds(3600), remindBefore = 15), now.minusSeconds(7200), zone).first
        val due = s.tasks[0]
        File(app.filesDir, "data").mkdirs()
        File(app.filesDir, "data/data.json").writeText(Codec.encode(s))
        val p = app.getSharedPreferences("device", Context.MODE_PRIVATE).edit()
        p.putString("language", "ar").putBoolean("lockPrivacy", true).putBoolean("onboarded", true)
        // 0.1.x already rang the due one: its alert log ("id TAB due|slot").
        p.putString("alertLog", due.id + "\t" + Codec.iso(due.dueAt!!) + "|0")
        if (versionCode != null) p.putInt("lastVersion", versionCode)
        p.commit()
        // 0.1.x's notification for it: tag "due:<id>", id 0, on the old channel.
        val old = android.app.NotificationChannel("reminders_v1", "Reminders", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(old)
        nm.notify("due:" + due.id, 0, Notification.Builder(app, "reminders_v1").setSmallIcon(R.drawable.ic_stat_bell).setContentTitle(due.title).build())
        return due.id to s.tasks[1].id
    }

    private fun update() {
        Repo.resetForTests()
        // As after the update: MY_PACKAGE_REPLACED re-plans everything.
        SystemReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        Engine.drain()
    }

    private fun check(versionCode: Int?) {
        val (dueId, laterId) = leaveOldVersion(versionCode)
        update()
        // The data is all there.
        val state = Repo.state(app)
        assertEquals(setOf(dueId, laterId), state.tasks.map { it.id }.toSet())
        // A copy of the data as it was, before 1.0.0 touched it.
        assertEquals(1, Repo.store(app).safetyCopies().count { it.name.startsWith("before-update-v${versionCode ?: 1}-") })
        // The phone's settings carry over.
        assertTrue(Repo.prefs(app).lockPrivacy)
        // One notification for the due reminder, the new kind; the old one is gone.
        assertTrue(nm.activeNotifications.none { it.tag?.startsWith("due:") == true })
        assertEquals(listOf(dueId), nm.activeNotifications.mapNotNull { Notifier.dueTask(it) })
        // It had rung already in 0.1.x: no second ring after the update.
        val ring = shadowOf(app).nextStartedService
        assertNull("rang again after the update: $ring", ring?.takeIf { it.component?.className == RingService::class.java.name })
        // The later reminder has its alarm.
        val am = app.getSystemService(AlarmManager::class.java)
        assertTrue(shadowOf(am).scheduledAlarms.any { it.type == AlarmManager.RTC_WAKEUP })
        assertEquals(BuildConfig.VERSION_CODE, Repo.prefs(app).lastVersion)
    }

    @Test fun from101() = check(4)

    @Test fun from100() = check(3)

    @Test fun from011() = check(2)

    @Test fun from010() = check(1)

    /** 0.1.0 before it recorded lastVersion at all. */
    @Test fun from010WithoutAVersionRecord() = check(null)
}
