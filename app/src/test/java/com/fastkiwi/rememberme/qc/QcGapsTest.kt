package com.fastkiwi.rememberme.qc

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
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
 * QC 1.0.0 (docs/QC-1.0.0.md): tests written by the independent QC auditor against the build's own contract
 * (docs/FEATURES.md). A test that fails here is a gap in the build, not in the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QcGapsTest {
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

    /**
     * Gate 1 / "Permissions for 1.0.0": "The manifest test pins this list" — exactly 0.1.1's set plus
     * FOREGROUND_SERVICE, so any other permission (internet, location, storage, media, microphone, DND access)
     * fails. The suite only checked single permissions one at a time; this pins the whole set. (Passes on this RC.)
     */
    @Config(sdk = [26, 33, 36])
    @Test fun f_gate1_thePermissionSetIsExactly011PlusForegroundService() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
            // AndroidX core's own signature permission for non-exported dynamic receivers (also in 0.1.1).
            .filterNot { it == "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" }.toSet()
        val expected = setOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.VIBRATE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.FOREGROUND_SERVICE",
        ).filterNot { it == "android.permission.SCHEDULE_EXACT_ALARM" && android.os.Build.VERSION.SDK_INT > 32 }.toSet()  // maxSdkVersion 32
        assertEquals(expected, requested)
    }

    /**
     * F-043: «10 ضغطات سريعة = تنبيه واحد». Ten taps on the same notification's «أجّل» snooze once: the first tap
     * moves the occurrence, the other nine are for an occurrence that has moved on. (Passes on this RC.)
     */
    @Test fun f043_tenFastSnoozeTapsOnOneNotificationSnoozeOnce() {
        val now = Instant.now()
        val t = Repo.store(app).change {
            Tasks.save(it, TaskInput(title = "r", dueAt = now.minusSeconds(30), repeat = Repeat.DAILY), now.minusSeconds(60), zone).first
        }.tasks.last()
        Engine.sync(app, now)
        repeat(10) { i -> ActionReceiver.handle(app, ActionReceiver.SNOOZE, t.id, t.dueAt, now.plusMillis(i * 50L)) }
        val after = Repo.state(app).task(t.id)!!
        assertEquals(1, after.snoozeCount)
        assertEquals(now.plusSeconds(600), after.dueAt)
        assertEquals("the repeat rule is unchanged", t.rule, after.rule)
    }

    /**
     * F-009: «ملف البيانات مطابق بايتاً بايتاً قبل الاختبار وبعده». The suite only checked that no reminder was added
     * to an empty data set; this runs the whole alert test (arm, fire, «تم») over real data and compares the bytes.
     * (Passes on this RC.)
     */
    @Test fun f009_theAlertTestLeavesDataJsonByteIdentical() {
        val now = Instant.now()
        Repo.store(app).change {
            var s = Tasks.save(it, TaskInput(title = "دواء الضغط", dueAt = now.plusSeconds(3600), repeat = Repeat.DAILY), now.minusSeconds(60), zone).first
            s = Tasks.save(s, TaskInput(title = "Meeting", dueAt = now.minusSeconds(600)), now.minusSeconds(900), zone).first
            s
        }
        Engine.sync(app, now)
        val file = File(app.filesDir, "data/data.json")
        val before = file.readBytes()
        com.fastkiwi.rememberme.alarm.TestAlarm.start(app, now)
        com.fastkiwi.rememberme.alarm.AlarmReceiver().onReceive(app, android.content.Intent(com.fastkiwi.rememberme.alarm.TestAlarm.ACTION))
        ActionReceiver().onReceive(app, android.content.Intent(ActionReceiver.TEST_DONE))
        assertTrue("data.json changed during the alert test", before.contentEquals(file.readBytes()))
    }

    /**
     * F-082 (English spec, "Context and verification notes"): release candidates are `1.0.0-rc.N` with the same
     * versionCode 3, "the pre-update snapshot (F-082) must therefore be keyed by versionCode **and** versionName".
     * Repo.store keys it by versionCode only (Repo.kt:29-32) and never records the versionName, so installing
     * rc.2 over rc.1, or 1.0.0 over rc.N, keeps no copy. FAILS on this RC.
     */
    @Test fun f082_anUpdateWithTheSameVersionCodeButANewVersionNameKeepsACopyFirst() {
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "دواء الضغط", dueAt = Instant.now().plusSeconds(3600)), Instant.now(), zone).first
        File(app.filesDir, "data").mkdirs()
        File(app.filesDir, "data/data.json").writeText(Codec.encode(s))
        // What an earlier release candidate of the same versionCode left: its versionCode, under another name.
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit()
            .putInt("lastVersion", BuildConfig.VERSION_CODE)
            .putString("lastVersionName", BuildConfig.VERSION_NAME + "-rc.1")
            .commit()
        Repo.resetForTests()
        val copies = Repo.store(app).safetyCopies().filter { it.name.startsWith("before-update-") }
        assertTrue("no pre-update copy for ${BuildConfig.VERSION_NAME}-rc.1 -> ${BuildConfig.VERSION_NAME} (same versionCode)", copies.isNotEmpty())
    }

    /**
     * F-030 / Arabic plurals: the restore toast «استُعيدت البيانات: %1$s تذكير.» (strings.xml import_done, used at
     * MainActivity.kt:242 and SettingsFlows.kt:178) is a plain string with a count, so 3 reads «3 تذكير» and 11 reads
     * «11 تذكير». FAILS on this RC.
     */
    @Test fun texts_theRestoreCountUsesACorrectArabicPlural() {
        val ar = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale("ar")) })
        val en = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
        val three = ar.getString(R.string.import_done, "3")
        val eleven = ar.getString(R.string.import_done, "11")
        val one = en.getString(R.string.import_done, "1")
        assertTrue("«$three» / «$eleven» / «$one»", three.contains("3 تذكيرات") && eleven.contains("11 تذكيرًا") && !one.contains("1 reminders"))
    }
}
