package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.withTags
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.Filters
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.settings.WhatsNewDialog
import com.fastkiwi.rememberme.ui.settings.makeAllNormal
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/** DESIGN-SPEC §7.4 rows Settings, Lists and Restore, through the real activity. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class SettingsUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")

    @Before fun setUp() {
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "filters")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private fun sample(now: Instant) = Repo.store(app).change {
        var s: AppState = it
        s = Tasks.save(s, TaskInput(title = "دواء الضغط", dueAt = now.plusSeconds(3600), priority = Priority.HIGH), now, zone).first
        s = Tasks.save(s, TaskInput(title = "اجتماع الفريق", dueAt = now.plusSeconds(7200)), now, zone).first
        s.copy(tasks = s.tasks.map { t -> if (t.title == "اجتماع الفريق") t.withTags(listOf("عمل", "منزل")) else t.withTags(listOf("منزل")) })
    }

    /** Every node with a click action is at least 48 × 48 dp. */
    private fun assertTouchTargets() {
        val density = app.resources.displayMetrics.density
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            assertTrue("Touch target \"$label\" is ${n.size.width / density}x${n.size.height / density} dp",
                n.size.width / density >= 47.5f && n.size.height / density >= 47.5f)
        }
    }

    @Test fun settingsShowsHealthFirstAndSearchOpensTheRowsOwnControl() {
        sample(Instant.now())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Settings) }
            rule.onNodeWithText("صحة التذكيرات").assertExists()
            rule.onNodeWithText("التنبيه").assertExists()
            rule.onNodeWithText("مدة رنين المنبّه المهم").assertExists()
            rule.onNodeWithText("حذف كل البيانات").performScrollTo().assertExists()
            assertTouchTargets()
            // No middle dot and no Arabic-Indic digits anywhere on the screen.
            for (n in rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()) {
                val t = n.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
                assertFalse(t, "·" in t || t.any { it in '٠'..'٩' })
            }
            rule.onNodeWithContentDescription("بحث").performClick()
            rule.onNodeWithText("ابحث في الإعدادات", useUnmergedTree = true).assertExists()
            rule.onNode(hasSetTextAction()).performTextInput("الاهتزاز")
            rule.onAllNodesWithText("في: التنبيه").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
            rule.onNodeWithText("في: التنبيه").performClick()
            // The row's own dialog opens: the four vibration choices.
            rule.onNodeWithText("قصير").assertExists()
            rule.onNodeWithText("مطفأ").performClick()
            rule.waitUntil(5_000) { !Repo.prefs(app).vibrateDefault }
            rule.onNodeWithText("مطفأ").assertExists()
        }
    }

    @Test fun searchWithoutResultsSaysSo() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Settings) }
            rule.onNodeWithContentDescription("بحث").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("زرافة")
            rule.onNodeWithText("لا نتائج لـ«زرافة»").assertExists()
        }
    }

    @Test fun snoozeEditorWritesSixValidPresets() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Settings) }
            rule.onNodeWithText("خيارات التأجيل").performClick()
            rule.onNodeWithText("الخيار 1").performClick()
            rule.onNodeWithText("5 دقائق").performClick()
            rule.waitUntil(5_000) { Repo.presets(Repo.state(app)).first() == com.fastkiwi.rememberme.core.SnoozePreset.In(5) }
            val raw = Repo.state(app).settings["snoozePresets"] as kotlinx.serialization.json.JsonArray
            assertEquals(6, raw.size)
        }
    }

    @Test fun listsAndTagsShowsItsSectionsAndCreatesAList() {
        sample(Instant.now())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Lists) }
            rule.onNodeWithText("القوائم والوسوم").assertExists()
            rule.onNodeWithText("التصنيفات").assertExists()
            rule.onNodeWithText("قوائمي").assertExists()
            rule.onNodeWithContentDescription("منزل، تذكيران").assertExists()
            assertTouchTargets()
            rule.onNodeWithText("قائمة جديدة", useUnmergedTree = true).assertExists()
            // (The «قائمة جديدة» dialog has a text field: under Robolectric a text field inside a dialog window never
            // becomes idle, so the list is added the way the dialog's «إنشاء» does and the screen must show it.)
            sc.onActivity { Filters(app).add(com.fastkiwi.rememberme.core.SavedFilter("l1", "المنزل", "tag", "منزل")); it.stack = listOf(Screen.Home) }
            rule.waitForIdle()
            sc.onActivity { it.push(Screen.Lists) }
            rule.onNodeWithText("الوسم: منزل").assertExists()
        }
    }

    private fun backupOf(s: AppState): ByteArray = ByteArrayOutputStream().also { Backup.export(s, it, Instant.now()) }.toByteArray()

    @Test fun restoreSummaryCancelLeavesDataJsonByteIdentical() {
        val now = Instant.now()
        sample(now)
        val other = Tasks.save(AppState(), TaskInput(title = "من النسخة", dueAt = now.plusSeconds(600)), now, zone).first
        val file = Repo.store(app).file
        val before = file.readBytes()
        val copies = Repo.store(app).safetyCopies().size
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Settings); it.flows.readRestore(backupOf(other)) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("استعادة هذه النسخة؟").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("من ذكرني لأندرويد", substring = true).assertExists()
            rule.onNodeWithText("تم التحقق من سلامة الملف").assertExists()
            rule.onNodeWithText("سيحل هذا محل تذكيراتك الحالية (2). نحفظ نسخة أمان قبل الاستبدال.").assertExists()
            rule.onNodeWithText("إلغاء").performClick()
            rule.onNodeWithText("استعادة هذه النسخة؟").assertDoesNotExist()
        }
        Engine.drain()
        assertArrayEquals(before, file.readBytes())
        assertEquals(copies, Repo.store(app).safetyCopies().size)
    }

    @Test fun restoreReplacesAfterASafetyCopyAndRefusalsHaveTheirOwnDialog() {
        val now = Instant.now()
        sample(now)
        val other = Tasks.save(AppState(), TaskInput(title = "من النسخة", dueAt = now.plusSeconds(600)), now, zone).first
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Settings); it.flows.readRestore("not a backup".toByteArray()) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("لا يمكن استعادة هذا الملف").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("حسنًا").performClick()
            sc.onActivity { it.flows.readRestore(backupOf(other)) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("استعادة هذه النسخة؟").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("استعادة").performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.map { it.title } == listOf("من النسخة") }
            assertTrue(Repo.store(app).safetyCopies().isNotEmpty())
        }
    }
}

/** DESIGN-SPEC §5.25: the what's-new dialog (shown by SettingsFlows only after an update from before 1.0.0). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class WhatsNewUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun whatsNewOffersMakeAllNormalFirstAndOkLast() {
        var normal = 0
        var ok = 0
        rule.setContent { AppTheme("light") { WhatsNewDialog(onMakeAllNormal = { normal++ }, onOk = { ok++ }) } }
        rule.onNodeWithText("ما الجديد في 1.0.0").assertExists()
        rule.onNodeWithText("التذكيرات المهمة صارت ترنّ بصوت المنبّه حتى والهاتف صامت.").assertExists()
        val make = rule.onNodeWithText("اجعل كل التذكيرات عادية").fetchSemanticsNode().boundsInRoot
        val okBounds = rule.onNodeWithText("حسنًا").fetchSemanticsNode().boundsInRoot
        // RTL: the secondary (first) is at the start = right, the primary at the end = left.
        assertTrue("$make / $okBounds", okBounds.right <= make.left + 1)
        rule.onNodeWithText("اجعل كل التذكيرات عادية").performClick()
        assertEquals(1, normal)
        assertEquals(1, ok)
        val now = Instant.now()
        val s = Tasks.save(AppState(), TaskInput(title = "x", priority = Priority.HIGH, dueAt = now.plusSeconds(60)), now, ZoneId.of("UTC")).first
        assertEquals("normal", AlertExt.of(makeAllNormal(s, now).tasks[0]).style)
    }
}
