package com.fastkiwi.rememberme.ui

import android.app.Application
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.data.EditorSettings
import com.fastkiwi.rememberme.ui.calendar.CalView
import com.fastkiwi.rememberme.ui.calendar.CalendarScreen
import com.fastkiwi.rememberme.ui.health.DndHelpScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** The QC 1.0.0 fixes G-02, G-03, G-06, G-07 and G-17 (docs/QC-1.0.0.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class QcFixUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val today = LocalDate.of(2026, 11, 14)
    private val now = at(16, 20)
    private var haptics = 0

    private fun at(h: Int, m: Int = 0): Instant = LocalDateTime.of(today, LocalTime.of(h, m)).atZone(zone).toInstant()

    private fun task(id: String, title: String, due: Instant?) =
        Task(id = id, title = title, dueAt = due, createdAt = now.minusSeconds(86_400), updatedAt = now.minusSeconds(600))

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        haptics = 0
    }

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), LocalConfirmHaptic provides { haptics++ }) {
            AppTheme("light") { content() }
        }
    }

    private fun isBidiControl(c: Char) = c in '⁦'..'⁩' || c in '‪'..'‮' || c == '‎' || c == '‏'

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun box(text: String, merged: Boolean = false): Rect =
        rule.onNodeWithText(text, useUnmergedTree = !merged, substring = merged).fetchSemanticsNode().boundsInRoot

    // ---- G-02 (F-102): a Latin title inside an Arabic sentence is isolated ----

    @Test fun g02_theDeleteForeverQuestionIsolatesALatinTitle() {
        val t = task("a", "Meeting 9", null).copy(deletedAt = now.minusSeconds(60))
        show { TrashScreen(AppState(listOf(t)), now, onRestore = {}, onPurge = {}, onEmpty = {}, onBack = {}) }
        // The row's own menu (the lower of the two «خيارات أخرى»: the top bar has one too).
        val more = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("خيارات أخرى")) and hasClickAction())
        val rowMenu = more.fetchSemanticsNodes().withIndex().maxBy { it.value.boundsInRoot.top }.index
        more[rowMenu].performClick()
        rule.onNodeWithText("حذف نهائي").performClick()
        val texts = rule.onAllNodes(hasText("Meeting 9", substring = true)).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val question = texts.first { it.length > "Meeting 9".length }
        assertTrue("«$question» isolates the title", question.any(::isBidiControl))
    }

    @Test fun g02_isolateWrapsLatinInArabicAndLeavesArabicAlone() {
        val wrapped = L10n.isolate(app, "Meeting 9")
        assertTrue(wrapped.contains("Meeting 9") && wrapped.any(::isBidiControl))
        assertEquals("دواء الضغط", L10n.isolate(app, "دواء الضغط").filterNot(::isBidiControl))
    }

    // ---- G-17: the at-time alert reads as always on, never as a grey "off" switch ----

    @Test fun g17_theAtTimeAlertSaysAlwaysAndHasNoDisabledSwitch() {
        show { EditScreen(task = null, weekStart = 6, now = now, onSave = { null }, onDelete = {}, onBack = {}, settings = EditorSettings()) }
        rule.onNodeWithText("العنوان").performTextInput("موعد")
        rule.onNodeWithText("عند الموعد").performScrollTo()
        rule.onNodeWithTag("alert-at-time-always", useUnmergedTree = true).assertExistsCompat()
        rule.onNodeWithText("دائمًا", useUnmergedTree = true).assertExistsCompat()
        val switches = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no disabled switch: ${switches.map { it.config }}", switches.none { SemanticsProperties.Disabled in it.config })
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() { fetchSemanticsNode() }

    // ---- G-06 (F-104): the confirm haptic ----

    @Test fun g06_theConstantFollowsTheApiLevel() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, ConfirmHaptic.constant(26))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, ConfirmHaptic.constant(29))
        assertEquals(HapticFeedbackConstants.CONFIRM, ConfirmHaptic.constant(30))
        assertEquals(HapticFeedbackConstants.CONFIRM, ConfirmHaptic.constant(36))
    }

    @Test fun g06_doneAndSnoozeOnTheReminderScreenConfirmByTouch() {
        var done = 0
        var snoozed = 0
        val t = task("t", "دواء الضغط", now.minusSeconds(30))
        show {
            AlertScreen(task = t, position = 1, count = 1, presets = SnoozePreset.DEFAULTS, now = now,
                onDone = { done++ }, onSkip = {}, onSnooze = { snoozed++ }, onEdit = {}, onLater = {}, guardMs = 0)
        }
        rule.onNode(hasText("تم") and hasClickAction()).performClick()
        assertEquals(1, done); assertEquals(1, haptics)
        rule.onNode(hasText("أجّل 10 دقائق") and hasClickAction()).performClick()
        assertEquals(1, snoozed); assertEquals(2, haptics)
        rule.onNode(hasText("ساعة") and hasClickAction()).performClick()
        assertEquals(2, snoozed); assertEquals(3, haptics)
    }

    @Test fun g06_saveConfirmsOnlyWhenTheReminderIsSaved() {
        var answer: String? = "اكتب عنوانًا"
        show { EditScreen(task = null, weekStart = 6, now = now, onSave = { answer }, onDelete = {}, onBack = {}, settings = EditorSettings()) }
        rule.onNode(hasText("حفظ") and hasClickAction()).performClick()
        assertEquals("a refused save gives no haptic", 0, haptics)
        answer = null
        rule.onNodeWithText("العنوان").performTextInput("موعد")
        rule.onNode(hasText("حفظ") and hasClickAction()).performClick()
        assertEquals(1, haptics)
    }

    // ---- G-03 (F-103/F-064): 200 % text ----

    private fun calendar(view: CalView, state: AppState) = show(2f) {
        var selected by androidx.compose.runtime.remember { mutableStateOf(today) }
        CalendarScreen(
            view = view, state = state, now = now, zone = zone, selected = selected,
            onSelectDay = { selected = it }, onViewChange = {}, onToday = {}, onOpen = {}, onNewOnDay = {},
            row = { Text("ROW:" + it.title) }, hijri = false, hijriAdjust = 0, onSettings = {},
        )
    }

    @Test fun g03_dayHoursKeepTheirAmPmAndTheNowLabelCoversNoTitle() {
        // A block just before now, tall at 200 %, and one just after: the worst case of the QC shot.
        calendar(CalView.DAY, AppState(listOf(
            task("pills", "دواء الضغط", at(15, 50)),
            task("meeting", "اجتماع الفريق", at(16, 30)),
        )))
        for (h in listOf("4 م", "12 ص", "11 م")) {
            val l = layoutOf(h)
            assertEquals("«$h» on one line", 1, l.lineCount)
            assertFalse("«$h» is not clipped", l.didOverflowWidth || l.didOverflowHeight)
        }
        val label = rule.onNodeWithTag("cal-now-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        for (title in listOf("دواء الضغط", "اجتماع الفريق")) {
            val block = rule.onNode(hasText(title, substring = true)).fetchSemanticsNode().boundsInRoot
            assertFalse("now label $label overlaps «$title» $block", label.overlaps(block))
            val l = layoutOf(title)
            assertFalse("«$title» whole in its block at 200 %", l.isLineEllipsized(l.lineCount - 1))
        }
    }

    @Test fun g03_weekTitlesAreNotCutToAFewLetters() {
        calendar(CalView.WEEK, AppState(listOf(
            task("meeting", "اجتماع الفريق", at(17, 30)),
            task("pills", "دواء الضغط", at(14)),
        )))
        for (title in listOf("اجتماع الفريق", "دواء الضغط")) {
            val l = layoutOf(title)
            assertEquals("«$title» may take 2 lines at 200 %", 2, l.layoutInput.maxLines)
            assertFalse("«$title» is shown whole at 200 % (${l.lineCount} lines)", l.isLineEllipsized(l.lineCount - 1))
        }
    }

    @Test fun g03_dndHelpStepNumbersSitInsideTheirDisc() {
        show(2f) { DndHelpScreen(AlertPolicy.Dnd.OFF, alarmsAllowed = true, onBack = {}) }
        val discs = rule.onAllNodesWithTag("numbered-step-disc", useUnmergedTree = true)
        val n = discs.fetchSemanticsNodes().size
        assertTrue("numbered steps shown", n > 0)
        for (i in 0 until n) {
            discs[i].performScrollTo()
            val disc = discs[i].fetchSemanticsNode().boundsInRoot
            val number = discs[i].onChildAt(0).fetchSemanticsNode().boundsInRoot
            assertEquals("disc is round: $disc", disc.width, disc.height, 1.5f)
            assertTrue("number $number inside $disc",
                number.left >= disc.left - 0.5f && number.right <= disc.right + 0.5f && number.top >= disc.top - 0.5f && number.bottom <= disc.bottom + 0.5f)
        }
    }

    // ---- G-07 (F-105): screen transitions and predictive back ----

    private var stack by mutableStateOf(listOf("home"))

    private fun host(reduced: Boolean = false) = show {
        CompositionLocalProvider(LocalReducedMotion provides reduced) {
            ScreenStackHost(stack, onBack = { stack = stack.dropLast(1) }) { s -> Text("screen-$s", Modifier.fillMaxSize()) }
        }
    }

    /** Changes the stack on the UI thread, lets the effect start the transition, then moves the clock [ms] on. */
    private fun go(to: List<String>, ms: Long) {
        rule.runOnUiThread { stack = to }
        frames(2)
        frames((ms / 16).toInt())
    }

    /** Moves the paused clock on frame by frame, letting each frame's effects run. */
    private fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }

    private fun x(s: String) = rule.onNodeWithText("screen-$s").fetchSemanticsNode().positionInRoot.x
    private fun exists(s: String) = rule.onAllNodes(hasText("screen-$s")).fetchSemanticsNodes().isNotEmpty()

    @Test fun g07_aPushSlidesInFromTheStartSideInArabicAndThePopGoesBack() {
        host()
        rule.mainClock.autoAdvance = false
        go(listOf("home", "settings"), 100)
        assertTrue("both screens during the transition", exists("home") && exists("settings"))
        // RTL: the next screen comes from the left (the end of an Arabic line) and moves right into place.
        assertTrue("settings enters from the left: ${x("settings")}", x("settings") < 0f)
        assertTrue("home leaves to the right: ${x("home")}", x("home") > 0f)
        frames(25)
        assertFalse("home gone after 300 ms", exists("home"))
        assertEquals(0f, x("settings"), 0.5f)
        go(listOf("home"), 100)
        assertTrue("a pop moves the other way: ${x("home")}", x("home") > 0f)
        frames(25)
        assertFalse(exists("settings"))
    }

    @Test @Config(qualifiers = "en-w400dp-h860dp-xxhdpi")
    fun g07_inEnglishTheNextScreenComesFromTheRight() {
        host()
        rule.mainClock.autoAdvance = false
        go(listOf("home", "settings"), 100)
        assertTrue("settings enters from the right: ${x("settings")}", x("settings") > 0f)
    }

    @Test fun g07_reducedMotionIsAShortCrossfadeWithoutMovement() {
        host(reduced = true)
        rule.mainClock.autoAdvance = false
        go(listOf("home", "settings"), 64)
        assertEquals("no slide", 0f, x("settings"), 0.5f)
        frames(13)
        assertFalse("crossfade done within 150 ms", exists("home"))
    }

    @Test fun g07_predictiveBackShowsThePreviousScreenAndCanBeCancelledOrCompleted() {
        stack = listOf("home", "settings")
        host()
        rule.waitForIdle()
        val back = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(100f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        rule.waitForIdle()
        assertTrue("the previous screen follows the gesture", exists("home") && exists("settings"))
        rule.runOnUiThread { back.dispatchOnBackCancelled() }
        rule.waitForIdle()
        assertEquals(listOf("home", "settings"), stack)
        assertFalse("cancelled: back where it was", exists("home"))
        rule.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.8f, BackEventCompat.EDGE_LEFT))
            back.onBackPressed()
        }
        rule.waitForIdle()
        assertEquals(listOf("home"), stack)
        assertFalse(exists("settings"))
        assertTrue(exists("home"))
    }

    @Test fun g07_theManifestOptsIntoPredictiveBack() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("<application[^>]*android:enableOnBackInvokedCallback=\"true\"").containsMatchIn(manifest))
    }

    // ---- Black-box re-test (BLACKBOX-REPORT.md §5): three large-text items ----

    /** The visible (clipped) bounds of the first node with [text], in the unmerged tree. */
    private fun seen(text: String, substring: Boolean = false): Rect =
        rule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    @Test fun bb_atDoubleTextTheLateLineIsReadWithTheTitle() {
        val t = Task(
            id = "t1", title = "اتصل بأمي", body = "", dueAt = now.minusSeconds(90), scheduleAt = now.minusSeconds(90),
            repeat = com.fastkiwi.rememberme.core.Repeat.DAILY, priority = com.fastkiwi.rememberme.core.Priority.HIGH, snoozeCount = 3,
            createdAt = now.minusSeconds(7200), updatedAt = now.minusSeconds(7200),
        )
        show(2f) {
            AlertScreen(task = t, position = 1, count = 2, presets = SnoozePreset.DEFAULTS, now = now,
                onDone = {}, onSkip = {}, onSnooze = {}, onEdit = {}, onLater = {}, ringing = true, guardMs = 0)
        }
        val title = seen("اتصل بأمي")
        val late = rule.onNodeWithText("متأخر", substring = true, useUnmergedTree = true)
        val lateBox = late.fetchSemanticsNode().boundsInRoot
        val lateFull = layoutOf(late.fetchSemanticsNode().config[SemanticsProperties.Text].first().text)
        val dockTop = seen("أجّلته 3 مرات. اختر وقتًا أنسب؟").top
        assertTrue("late line $lateBox under the title $title", lateBox.top >= title.bottom - 1)
        assertTrue("late line $lateBox wholly above the dock ($dockTop)", lateBox.bottom <= dockTop + 1)
        assertEquals("late line not clipped", lateFull.size.height.toFloat(), lateBox.height, 2f)
    }

    @Test fun bb_atDoubleTextTheToneNoteIsReadableAboveThePinnedButtons() {
        show(2f) {
            com.fastkiwi.rememberme.ui.editor.TonePickerSheet(current = null, style = AlertPolicy.Style.ALARM, onPick = {}, onDismiss = {})
        }
        rule.waitForIdle()
        val note = rule.onNodeWithTag("tone-note", useUnmergedTree = true).fetchSemanticsNode()
        val done = rule.onNode(hasText("تم") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val shown = note.boundsInRoot
        assertTrue("the note $shown is not squeezed (${note.size})", shown.height >= note.size.height - 2)
        assertTrue("the note $shown is above «تم» $done", shown.bottom <= done.top + 1)
    }

    @Test fun bb_atDoubleTextTheAlertTestQuestionComesIntoViewAndIsAnnounced() {
        val t0 = now.toEpochMilli() - 60_000
        val fired = com.fastkiwi.rememberme.alarm.TestAlarm.Record(startedAt = t0, plannedAt = t0 + 10_000, api = "CLOCK", firedAt = t0 + 11_000,
            posted = true, fullScreenAllowed = false, quiet = AlertPolicy.Quiet.RINGER_SILENT.name, vibrate = false, playback = null)
        val ready = Health(notifications = true, channel = true, exact = true, fullScreen = true, battery = true, dnd = false)
        val results = com.fastkiwi.rememberme.ui.health.TestResults.of(fired, ready, now.toEpochMilli())
        show(2f) {
            com.fastkiwi.rememberme.ui.health.AlarmTestScreen(fired, results, now.toEpochMilli(), onStart = { _, _ -> }, onAnswer = { _, _ -> },
                onFix = {}, onCancel = {}, onDone = {}, onBack = {})
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val q = rule.onNodeWithText("هل سمعت الصوت؟", useUnmergedTree = true).fetchSemanticsNode()
        val retest = rule.onNode(hasText("أعد الاختبار") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val shown = q.boundsInRoot
        assertTrue("the question $shown is in view (${q.size})", shown.height >= q.size.height - 2 && shown.bottom <= retest.top + 1)
        val no = rule.onAllNodes(hasText("لا") and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("its answers $no are in view too", no.height >= 47 * app.resources.displayMetrics.density && no.bottom <= retest.top + 1)
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, q.config.getOrNull(SemanticsProperties.LiveRegion))
    }
}
