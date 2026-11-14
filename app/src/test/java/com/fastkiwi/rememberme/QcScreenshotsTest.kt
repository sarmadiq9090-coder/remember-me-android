package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
 * The QC pass (DESIGN-SPEC, STANDARDS.md): every screen in Arabic and English, light and dark, and Arabic
 * at 200 % text, written to screenshots/qc/. Sample data only. Each tour also checks that the screen's
 * primary action is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class QcScreenshotsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val outDir = File("../screenshots/qc").apply { mkdirs() }
    private var scenario: ActivityScenario<*>? = null

    @Before fun setUp() {
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Exact alarms off: the screenshots show the warning banner and the permissions screen's states.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private fun sample(en: Boolean) {
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        fun on(days: Int, h: Int, m: Int = 0) = at(zone, today.year, today.monthValue, today.dayOfMonth + days, h, m)
        fun t(ar: String, e: String) = if (en) e else ar
        Repo.store(app).change {
            var s: AppState = it
            fun add(i: TaskInput) { s = Tasks.save(s, i, now.minusSeconds(7200), zone).first }
            add(TaskInput(title = t("دواء الضغط", "Blood pressure pill"), body = t("حبة واحدة بعد الغداء مع كوب ماء.", "One after lunch with a glass of water."),
                dueAt = now.minusSeconds(2 * 3600), repeat = Repeat.DAILY, priority = Priority.HIGH))
            add(TaskInput(title = t("الاتصال بالكهربائي", "Call the electrician"), dueAt = now.minusSeconds(22 * 3600)))
            add(TaskInput(title = t("اجتماع الفريق", "Team meeting"), dueAt = now.plusSeconds(90 * 60), category = Category.WORK, remindBefore = 15))
            add(TaskInput(title = t("مراجعة درس الرياضيات", "Revise the maths lesson"), dueAt = now.plusSeconds(150 * 60), repeat = Repeat.DAYS,
                days = listOf(0, 2, 4), priority = Priority.MEDIUM, category = Category.STUDY))
            add(TaskInput(title = t("دفع الإيجار", "Pay the rent"), dueAt = on(1, 9), repeat = Repeat.MONTHLY, priority = Priority.HIGH))
            add(TaskInput(title = t("شراء هدية لأمي", "Buy a present for Mum"), dueAt = on(3, 17, 30)))
            add(TaskInput(title = t("قراءة كتاب", "Read a book")))
            val (s2, done) = Tasks.save(s, TaskInput(title = t("حجز موعد الطبيب", "Book the doctor"), dueAt = now.minusSeconds(26 * 3600)), now.minusSeconds(30 * 3600), zone)
            s = Tasks.complete(s2, done.id, now.minusSeconds(25 * 3600), zone)!!.first
            val (s3, gone) = Tasks.save(s, TaskInput(title = t("شراء بطاقة شحن", "Buy a top-up card")), now, zone)
            Tasks.trash(s3, gone.id, now.minusSeconds(3600))
        }
    }

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

    private fun go(screen: Screen) {
        @Suppress("UNCHECKED_CAST")
        (scenario as ActivityScenario<MainActivity>).onActivity { it.stack = listOf(Screen.Home) + (if (screen == Screen.Home) emptyList() else listOf(screen)) }
        rule.waitForIdle()
    }

    private fun tour(prefix: String, en: Boolean) {
        if (en) Repo.prefs(app).language = "en"
        sample(en)
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.onNodeWithText(if (en) "New reminder" else "تذكير جديد", useUnmergedTree = true).assertIsDisplayed()
            shot("$prefix-01-home")
            go(Screen.Edit(null)); shot("$prefix-02-new")
            rule.onNodeWithText(if (en) "Save" else "حفظ").assertIsDisplayed()
            val rent = Repo.state(app).tasks.first { it.repeat == Repeat.MONTHLY }
            go(Screen.Edit(rent.id)); shot("$prefix-03-edit")
            go(Screen.Settings); shot("$prefix-05-settings")
            go(Screen.Background); shot("$prefix-06-background")
            go(Screen.Trash); shot("$prefix-07-trash")
            go(Screen.About); shot("$prefix-08-about")
        }
        val due = Tasks.due(Repo.state(app), Instant.now()).first()
        val intent = Intent(app, AlertActivity::class.java).putExtra(AlertActivity.EXTRA_ID, due.id)
        ActivityScenario.launch<AlertActivity>(intent).also { scenario = it }.use {
            // The primary «تم» stays on screen, also at 200 % text.
            val done = rule.onNodeWithText(if (en) "Done" else "تم", useUnmergedTree = true)
            done.assertIsDisplayed()
            val b = done.fetchSemanticsNode().boundsInWindow
            val h = app.resources.displayMetrics.heightPixels
            assertTrue("Done is inside the window ($b, height $h)", b.bottom <= h + 1)
            shot("$prefix-04-alert")
        }
    }

    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi") fun arabicLight() = tour("ar-light", false)
    @Test @Config(qualifiers = "ar-w412dp-h915dp-night-xxhdpi") fun arabicDark() = tour("ar-dark", false)
    @Test @Config(qualifiers = "en-w412dp-h915dp-notnight-xxhdpi") fun englishLight() = tour("en-light", true)
    @Test @Config(qualifiers = "en-w412dp-h915dp-night-xxhdpi") fun englishDark() = tour("en-dark", true)

    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi")
    fun arabicLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        try { tour("ar-200", false) } finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
