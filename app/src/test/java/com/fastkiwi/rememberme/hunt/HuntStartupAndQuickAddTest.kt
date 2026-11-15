package com.fastkiwi.rememberme.hunt

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.RememberMeApp
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.ExtRepeat
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.quickadd.QuickAdd
import com.fastkiwi.rememberme.data.SettingsPrefs
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A process started before the first unlock (LOCKED_BOOT_COMPLETED) returns early from
 * RememberMeApp.onCreate and never runs it again after the unlock: the phone settings it loads stay at
 * their defaults in that process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HuntLockedStartTest {
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
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Notifier.channels(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        RingService.ringingId = 0
    }

    @After fun tearDown() {
        TimeZone.setDefault(tzBefore)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        AlertPolicy.highIsAlarm = true
    }

    private fun boot() {
        // Delivered as the system does (Robolectric does not route protected broadcasts sent by the app).
        com.fastkiwi.rememberme.alarm.SystemReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Engine.drain()
    }

    @Test fun importantRemindersRingAsAlarmsAfterALockedRestartAlthoughTheUserChoseNormal() {
        // «التذكيرات المهمة» = «تذكير عادي» (no alarm volume, no ringing through silent mode).
        SettingsPrefs(app).importantStyle = "normal"
        // A new process, started by LOCKED_BOOT_COMPLETED before the first unlock.
        AlertPolicy.highIsAlarm = true
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        (app as RememberMeApp).onCreate()
        // The user unlocks; BOOT_COMPLETED arrives in the same process, then a high-priority reminder is due.
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        boot()
        val now = Instant.now()
        val t = Repo.store(app).change { Tasks.save(it, TaskInput(title = "مهم", dueAt = now.minusSeconds(5), priority = Priority.HIGH), now.minusSeconds(60), zone).first }.tasks.last()
        Engine.sync(app, now, "boot")
        val n = app.getSystemService(NotificationManager::class.java).activeNotifications.single { Notifier.dueTask(it) == t.id }.notification
        assertEquals("the user's «تذكير عادي» choice for important reminders", Notifier.CH_NORMAL, n.channelId)
    }

    @Test fun theWeeklyBackupAndToneCleanupAreNotHookedAfterALockedRestart() {
        Engine.housekeeping.clear()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        (app as RememberMeApp).onCreate()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        boot()
        // BOOT_COMPLETED and the nightly window now run in this process: nothing is hooked in.
        assertEquals("housekeeping hooks (weekly backup F-087, tone cleanup)", 2, Engine.housekeeping.size)
    }
}

/** Quick Add additions promised for 1.0.0 (FEATURES.md F-042, English spec "Additions for 1.0.0"). */
class HuntQuickAddTest {
    private val now = Instant.parse("2026-11-15T07:00:00Z") // 10:00 in Baghdad
    private val zone = ZoneId.of("Asia/Baghdad")

    @After fun tearDown() { Tasks.extRepeat = { ExtRepeat() } }

    @Test fun everyNHoursIsNotUnderstood() {
        for (text in listOf("اشرب ماء كل 3 ساعات", "دواء كل 8 ساعات", "take pills every 2 hours")) {
            val r = QuickAdd.parse(text, now, zone)
            assertEquals("«$text» read as ${r.repeat}, understood=${r.understood}", Repeat.HOURS, r.repeat)
        }
    }

    @Test fun afterMaghribIgnoresThePrayerTimesThatAreSetUp() {
        // Prayer times are set up on this phone: today's Maghrib is 16:58 in Baghdad.
        val maghrib = Instant.parse("2026-11-15T13:58:00Z")
        Tasks.extRepeat = { ExtRepeat(prayerNext = { name, offset, after -> if (name == "maghrib" && maghrib.plusSeconds(offset * 60L).isAfter(after)) maghrib.plusSeconds(offset * 60L) else null }) }
        val r = QuickAdd.parse("اتصل بأمي بعد المغرب", now, zone)
        assertEquals("«بعد المغرب» resolved to ${r.dueAt?.atZone(zone)}, a fixed part of the day, not the prayer", maghrib, r.dueAt)
        assertEquals("the title keeps a dangling «بعد»", "اتصل بأمي", r.title)
    }

    /** Black-box U1: an English date phrase inside an Arabic text is read in full. */
    @Test fun aMixedTextReadsTheEnglishDatePhrase() {
        val r = QuickAdd.parse("دفع رسوم البنك tomorrow 10am", now, zone)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 16, 10, 0), r.dueAt?.atZone(zone)?.toLocalDateTime())
        assertEquals("دفع رسوم البنك", r.title)
        // P3's own phrases from the black-box test keep working.
        assertTrue(QuickAdd.parse("call Sara بعد ساعة", now, zone).dueAt != null)
    }

    /** Black-box U2: the name «علي» before a day is part of the title. */
    @Test fun theNameAliBeforeADayStaysInTheTitle() {
        assertEquals("عيد ميلاد علي", QuickAdd.parse("عيد ميلاد علي الخميس 5 العصر", now, zone).title)
        assertEquals("اتصل بأمي", QuickAdd.parse("اتصل بأمي علي الساعة 5 العصر", now, zone).title)
    }
}
