package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Runs the real screens on the JVM (Robolectric, native graphics): a smoke test of each main screen,
 * adding and completing a reminder through the UI, and the screenshots in screenshots/ (sample data only).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class AppUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val outDir = File("../screenshots").apply { mkdirs() }

    @Before fun setUp() {
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    /** Lets background saves started by the screens finish before the next test resets the app. */
    @org.junit.After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    /** A believable sample week: nothing personal, nothing real. */
    private fun sample(now: Instant) {
        val today = now.atZone(zone).toLocalDate()
        fun on(days: Long, h: Int, m: Int = 0) = at(zone, today.year, today.monthValue, today.dayOfMonth + days.toInt(), h, m)
        Repo.store(app).change {
            var s: AppState = it
            fun add(i: TaskInput) { s = Tasks.save(s, i, now.minusSeconds(7200), zone).first }
            add(TaskInput(title = "دواء الضغط", body = "حبة واحدة بعد الفطور", dueAt = now.minusSeconds(25 * 60), repeat = Repeat.DAILY, priority = Priority.HIGH))
            add(TaskInput(title = "الاتصال بالكهربائي", dueAt = now.minusSeconds(3 * 3600), category = Category.WORK))
            add(TaskInput(title = "اجتماع الفريق", dueAt = now.plusSeconds(2 * 3600), category = Category.WORK, remindBefore = 15, priority = Priority.MEDIUM))
            add(TaskInput(title = "سقي النباتات", dueAt = now.plusSeconds(5 * 3600), repeat = Repeat.INTERVAL, everyDays = 3))
            add(TaskInput(title = "دفع الإيجار", dueAt = on(3, 9), repeat = Repeat.MONTHLY, priority = Priority.HIGH))
            add(TaskInput(title = "الرياضة", dueAt = on(1, 18), repeat = Repeat.DAYS, days = listOf(0, 2, 4)))
            add(TaskInput(title = "شراء هدية لأمي", dueAt = on(6, 17, 30)))
            add(TaskInput(title = "قراءة كتاب", body = "فصل واحد على الأقل"))
            s
        }
    }

    private var scenario: ActivityScenario<*>? = null

    /** Draws the activity's window onto a bitmap (captureToImage waits for a frame Robolectric never signals). */
    private fun shot(name: String) {
        rule.waitForIdle()
        @Suppress("UNCHECKED_CAST")
        (scenario as ActivityScenario<android.app.Activity>).onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun homeEditorAlertSettingsInArabic() {
        val now = Instant.now()
        sample(now)
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.onNodeWithText("ذكرني").assertExists()
            // Overdue reminders exist, so the app opens on the Overdue tab.
            rule.onNodeWithText("دواء الضغط").assertExists()
            shot("01-overdue-ar")
            rule.onNodeWithText("اليوم").performClick()
            rule.onNodeWithText("اجتماع الفريق").assertExists()
            shot("02-today-ar")
            rule.onNodeWithText("القادمة").performClick()
            rule.onNodeWithText("دفع الإيجار").assertExists()
            shot("03-upcoming-ar")
            rule.onNodeWithText("تذكير جديد", useUnmergedTree = true).performClick()
            rule.onNodeWithText("العنوان").performTextInput("موعد طبيب الأسنان")
            shot("04-new-reminder-ar")
            rule.onAllNodesWithText("حفظ")[0].performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.any { it.title == "موعد طبيب الأسنان" && it.dueAt != null } }
            rule.onNodeWithContentDescription("الإعدادات").performClick()
            shot("06-settings-ar")
            rule.onNodeWithText("السماح بالعمل في الخلفية").performClick()
            shot("07-background-ar")
        }
    }

    @Test fun alertScreenShowsTheQueueAndDoneCompletes() {
        val now = Instant.now()
        sample(now)
        val first = Tasks.due(Repo.state(app), now).first()
        val intent = Intent(app, AlertActivity::class.java).putExtra(AlertActivity.EXTRA_ID, first.id)
        ActivityScenario.launch<AlertActivity>(intent).also { scenario = it }.use {
            rule.onNodeWithText(first.title).assertExists()
            rule.onNodeWithText("1 من 2").assertExists()
            rule.onNodeWithText("10 دقائق").assertExists()
            shot("05-alert-ar")
            rule.onNodeWithText("تم").performClick()
            // The change is saved off the main thread; the screen then moves to the next reminder.
            rule.waitUntil(10_000) { Tasks.due(Repo.state(app), Instant.now()).size == 1 }
            rule.onNodeWithText("دواء الضغط").assertExists()
        }
    }

    @Test fun searchFindsArabicWithoutDiacriticsOrHamza() {
        sample(Instant.now())
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.onNodeWithContentDescription("بحث").performClick()
            rule.onNodeWithText("ابحث…").performTextInput("الايجار")
            rule.onNodeWithText("دفع الإيجار").assertExists()
        }
    }

    @Test @Config(qualifiers = "en-w400dp-h860dp-xxhdpi")
    fun homeInEnglish() {
        Repo.prefs(app).language = "en"
        val now = Instant.now()
        Repo.store(app).change {
            var s: AppState = it
            fun add(i: TaskInput) { s = Tasks.save(s, i, now.minusSeconds(7200), zone).first }
            add(TaskInput(title = "Team meeting", dueAt = now.plusSeconds(2 * 3600), category = Category.WORK, remindBefore = 15, priority = Priority.MEDIUM))
            add(TaskInput(title = "Water the plants", dueAt = now.plusSeconds(5 * 3600), repeat = Repeat.INTERVAL, everyDays = 3))
            add(TaskInput(title = "Call the electrician", dueAt = now.minusSeconds(3 * 3600)))
            s
        }
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.onNodeWithText("Remember Me").assertExists()
            rule.onNodeWithText("Today").performClick()
            rule.onNodeWithText("Team meeting").assertExists()
            shot("08-today-en")
        }
    }
}
