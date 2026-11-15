package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.withExt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 1.0.5, the owner: «الشاشه الكاملة للتنبيهات المهمة فقط». The full-screen alert over the lock screen only for an
 * important reminder («منبّه مهم», or importance «مهم»); a normal one is a heads-up notification with its sound.
 * The setting can turn it off for the important ones too; nothing turns it on for every reminder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36], shadows = [com.fastkiwi.rememberme.torture.blackbox.FullScreenShadow::class])
class FullScreenImportantTest {
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
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        com.fastkiwi.rememberme.torture.blackbox.FullScreenShadow.allowed = true
        Notifier.channels(app)
        nm.cancelAll()
        RingService.ringingId = 0
    }

    @After fun tearDown() { TimeZone.setDefault(tzBefore) }

    private fun due(input: TaskInput, ext: AlertExt? = null): android.app.Notification {
        val now = Instant.now()
        var t = Repo.store(app).change { Tasks.save(it, input.copy(dueAt = now.minusSeconds(5)), now.minusSeconds(60), zone).first }.tasks.last()
        if (ext != null) Repo.store(app).change { s -> s.copy(tasks = s.tasks.map { if (it.id == t.id) it.withExt(ext).also { x -> t = x } else it }) }
        Engine.sync(app, now)
        return nm.activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
    }

    @Test fun aNormalReminderIsAHeadsUpWithoutFullScreen() {
        val n = due(TaskInput(title = "اشرب الماء"))
        assertNull("a normal reminder opened the full screen", n.fullScreenIntent)
        assertEquals(Notifier.CH_NORMAL, n.channelId)
        // Heads-up: its channel pops up on screen, with its sound.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Notifier.CH_NORMAL).importance)
    }

    @Test fun aNormalReminderSetToShowFullScreenStillDoesNot() {
        val n = due(TaskInput(title = "اشرب الماء"), AlertExt(fullScreen = "on"))
        assertNull(n.fullScreenIntent)
    }

    @Test fun anImportantReminderOpensTheFullScreen() {
        assertNotNull(due(TaskInput(title = "دواء الضغط", priority = Priority.HIGH)).fullScreenIntent)
    }

    @Test fun anImportantAlarmOnANormalPriorityOpensTheFullScreen() {
        assertNotNull(due(TaskInput(title = "موعد الطبيب"), AlertExt(style = "alarm")).fullScreenIntent)
    }

    @Test fun theSwitchTurnsItOffForTheImportantOnesToo() {
        Repo.prefs(app).fullScreenMode = "off"
        assertNull(due(TaskInput(title = "دواء الضغط", priority = Priority.HIGH)).fullScreenIntent)
        assertNull(due(TaskInput(title = "موعد الطبيب"), AlertExt(style = "alarm", fullScreen = "on")).fullScreenIntent)
    }

    @Test fun thereIsNoEveryReminderMode() {
        // 1.0.4 stored «دائمًا» ("always"): it now reads as «للتنبيهات المهمة فقط».
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().putString("fullScreenMode", "always").commit()
        Repo.resetForTests()
        assertEquals("important", Repo.prefs(app).fullScreenMode)
        assertNull(due(TaskInput(title = "اشرب الماء")).fullScreenIntent)
        assertNotNull(due(TaskInput(title = "دواء الضغط", priority = Priority.HIGH)).fullScreenIntent)
    }
}
