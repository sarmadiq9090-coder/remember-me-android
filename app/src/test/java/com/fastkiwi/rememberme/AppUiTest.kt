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
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Assert.assertFalse
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
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
            // One list with sections (DESIGN-SPEC V1): Overdue first, then Today, then Upcoming.
            rule.onNodeWithText("المتأخرة").assertExists()
            rule.onNodeWithText("دواء الضغط", useUnmergedTree = true).assertExists()
            rule.onNodeWithText("اليوم").assertExists()
            shot("01-home-ar")
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("دفع الإيجار", substring = true))
            rule.onNodeWithText("القادمة").assertExists()
            shot("02-home-scrolled-ar")
            // Scrolled down, the button is an icon: it still has its name.
            rule.onNode(hasContentDescription("تذكير جديد") or hasText("تذكير جديد"), useUnmergedTree = true).performClick()
            rule.onNodeWithText("العنوان").performTextInput("موعد طبيب الأسنان")
            shot("04-new-reminder-ar")
            // One «حفظ» only, at the bottom.
            rule.onAllNodesWithText("حفظ").assertCountEquals(1)
            rule.onNodeWithText("حفظ").performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.any { it.title == "موعد طبيب الأسنان" && it.dueAt != null } }
            rule.onNodeWithContentDescription("الإعدادات").performClick()
            shot("06-settings-ar")
            rule.onNodeWithText("صحة التذكيرات").performClick()
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

    /** DESIGN-SPEC §3.3: on a repeating reminder «تم» (primary) is at the end: left of «تخطَّ هذه المرة» in Arabic. */
    @Test fun theReminderScreenPutsDoneAtTheEnd() {
        val now = Instant.now()
        sample(now)
        val repeating = Tasks.due(Repo.state(app), now).first { it.repeat != Repeat.NONE }
        val intent = Intent(app, AlertActivity::class.java).putExtra(AlertActivity.EXTRA_ID, repeating.id)
        ActivityScenario.launch<AlertActivity>(intent).also { scenario = it }.use {
            rule.onAllNodesWithText("تم").assertCountEquals(1)
            val done = rule.onNodeWithText("تم", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val skip = rule.onNodeWithText("تخطَّ هذه المرة", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Done $done should be left of Skip $skip in RTL", done.right <= skip.left + 1)
            // Every clickable thing on the screen is at least 48 × 48 dp.
            assertTouchTargets()
        }
    }

    /** Every node with a click or toggle action is at least 48 × 48 dp (WCAG 2.5.8, Android core quality). */
    private fun assertTouchTargets() {
        val density = app.resources.displayMetrics.density
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        for (n in nodes) {
            val w = n.size.width / density
            val h = n.size.height / density
            val label = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString()
                ?: n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString()
            assertTrue("Touch target \"$label\" is ${w}x$h dp", w >= 47.5f && h >= 47.5f)
        }
    }

    @Test fun homeAndEditorTouchTargetsAndRowSemantics() {
        val now = Instant.now()
        sample(now)
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            assertTouchTargets()
            // A row speaks its title, time and lateness, and offers «تم» as an action for TalkBack.
            val row = rule.onNode(hasContentDescription("دواء الضغط، ", substring = true) and hasClickAction()).fetchSemanticsNode()
            val desc = row.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
            assertTrue(desc, desc.contains("متأخر") && desc.contains("، "))
            assertFalse(desc, desc.contains("·"))
            val actions = row.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label }
            assertTrue(actions.toString(), "تم" in actions)
            rule.onNodeWithText("تذكير جديد", useUnmergedTree = true).performClick()
            rule.waitForIdle()
            assertTouchTargets()
        }
    }

    @Test fun searchFindsArabicWithoutDiacriticsOrHamza() {
        sample(Instant.now())
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.onNodeWithContentDescription("بحث").performClick()
            rule.onNodeWithText("ابحث…").performTextInput("الايجار")
            rule.onNodeWithText("دفع الإيجار", useUnmergedTree = true).assertExists()
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
            rule.onNodeWithText("Today").assertExists()
            rule.onNodeWithText("Team meeting", useUnmergedTree = true).assertExists()
            shot("08-today-en")
        }
    }

    @Test fun theEditorShowsTheReminderItIsOpenedForAndSurvivesRotation() {
        val now = Instant.now()
        var a = ""
        var b = ""
        Repo.store(app).change { s0 ->
            val (s1, ta) = Tasks.save(s0, TaskInput(title = "first reminder", dueAt = now.plusSeconds(3600)), now, zone)
            val (s2, tb) = Tasks.save(s1, TaskInput(title = "second reminder", dueAt = now.plusSeconds(7200)), now, zone)
            a = ta.id; b = tb.id
            s2
        }
        val open = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, a)
        ActivityScenario.launch<MainActivity>(open).also { scenario = it }.use { sc ->
            rule.onNodeWithText("first reminder").assertExists()
            // Another reminder opened while the editor is showing (its early notice tapped: onNewIntent).
            sc.onActivity { it.stack = listOf(Screen.Home, Screen.Edit(b)) }
            rule.onNodeWithText("second reminder").assertExists()
            rule.onNodeWithText("first reminder").assertDoesNotExist()
            // Rotating the phone keeps the editor and what was typed.
            rule.onNodeWithText("second reminder").performTextReplacement("second reminder, edited")
            sc.recreate()
            rule.onNodeWithText("second reminder, edited").assertExists()
        }
    }

    /** D-011: before a Windows-format export, every-N-hours reminders are listed, because Windows makes them one-time. */
    @Test fun exportWarnsAboutHourlyRemindersFirst() {
        val now = Instant.now()
        Repo.store(app).change {
            var s: AppState = it
            s = Tasks.save(s, TaskInput(title = "شرب الماء", dueAt = now.plusSeconds(3600), repeat = Repeat.HOURS, everyHours = 2), now, zone).first
            s = Tasks.save(s, TaskInput(title = "اجتماع", dueAt = now.plusSeconds(7200)), now, zone).first
            s
        }
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use { sc ->
            sc.onActivity { it.push(Screen.Settings) }
            // The row is below the fold of Settings: scroll to it, then tap.
            rule.onNodeWithText("حفظ نسخة").performScrollTo().performClick()
            rule.onNodeWithText("تنبيه قبل حفظ النسخة").assertExists()
            rule.onNodeWithText("• شرب الماء", substring = true).assertExists()
            rule.onNodeWithText("اجتماع", substring = true).assertDoesNotExist()
            rule.onNodeWithText("إلغاء").performClick()
            rule.onNodeWithText("تنبيه قبل حفظ النسخة").assertDoesNotExist()
        }
    }

    /** F-083: a restore is read, verified and summarised before anything is replaced; a damaged copy changes nothing. */
    @Test fun aRestoreIsCheckedAndSummarisedBeforeAnythingIsReplaced() {
        Repo.store(app).change { Tasks.save(it, TaskInput(title = "on the phone"), Instant.now(), zone).first }
        val other = Tasks.save(AppState(), TaskInput(title = "from the copy", dueAt = Instant.now().plusSeconds(3600)), Instant.now(), zone).first
        val zip = java.io.ByteArrayOutputStream().also { com.fastkiwi.rememberme.data.Backup.export(other, it, Instant.now()) }.toByteArray()
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use { sc ->
            rule.waitForIdle()
            sc.onActivity { activity ->
                val read = activity.readImport { zip.inputStream() }.getOrThrow()
                org.junit.Assert.assertTrue(read.verified)
                org.junit.Assert.assertEquals(1, read.active)
                org.junit.Assert.assertNotNull(read.createdAt)
                // Reading changed nothing.
                org.junit.Assert.assertEquals("on the phone", Repo.state(app).tasks.single().title)
                // A damaged copy is refused with a message, and nothing changes.
                val bad = zip.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                org.junit.Assert.assertTrue(activity.readImport { bad.inputStream() }.isFailure)
                org.junit.Assert.assertEquals("on the phone", Repo.state(app).tasks.single().title)
                // Applying it keeps the current data as a safety copy first.
                activity.applyImport(read)
            }
            Engine.drain()
            org.junit.Assert.assertEquals("from the copy", Repo.state(app).tasks.single().title)
            val kept = Repo.store(app).safetyCopies().first()
            org.junit.Assert.assertTrue(com.fastkiwi.rememberme.core.Codec.decode(kept.readText()).tasks.any { it.title == "on the phone" })
        }
    }
}
