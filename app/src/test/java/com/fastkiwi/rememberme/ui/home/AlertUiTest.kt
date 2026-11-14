package com.fastkiwi.rememberme.ui.home

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.withSteps
import com.fastkiwi.rememberme.ui.AlertScreen
import com.fastkiwi.rememberme.ui.AppTheme
import org.junit.Assert.assertEquals
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
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** The reminder screen's test hooks (DESIGN-SPEC §7.4 row "Reminder screen") on the screen itself. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class AlertUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now: Instant = Instant.now()
    private var done = 0
    private var snoozedTo: Instant? = null

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun task(repeat: Repeat = Repeat.NONE, snoozes: Int = 0, priority: Priority = Priority.NORMAL) = Task(
        id = "t1", title = "دواء الضغط", body = "حبة واحدة", dueAt = now.minusSeconds(30), scheduleAt = now.minusSeconds(30),
        repeat = repeat, priority = priority, snoozeCount = snoozes, createdAt = now.minusSeconds(7200), updatedAt = now.minusSeconds(7200),
    )

    private fun show(
        t: Task = task(), presets: List<SnoozePreset> = SnoozePreset.DEFAULTS, fontScale: Float? = null,
        arrivedLateMs: Long? = null, ringing: Boolean = false, guardMs: Long = 1_000,
    ) {
        rule.setContent {
            AppTheme("light") {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale)) {
                    AlertScreen(
                        task = t, position = 1, count = 2, presets = presets, now = now,
                        onDone = { done++ }, onSkip = {}, onSnooze = { snoozedTo = it }, onEdit = {}, onLater = {},
                        arrivedLateMs = arrivedLateMs, ringing = ringing, guardMs = guardMs,
                    )
                }
            }
        }
    }

    @Test fun queueSnoozePairAndOneDoneWithDoneAtTheEnd() {
        show()
        rule.onNodeWithText("1 من 2").assertExists()
        rule.onNodeWithText("أجّل 10 دقائق").assertExists()
        rule.onAllNodesWithText("تم").assertCountEquals(1)
        val doneBox = rule.onNodeWithText("تم", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val snooze = rule.onNodeWithText("أجّل 10 دقائق", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Done $doneBox should be left of Snooze $snooze in RTL", doneBox.right <= snooze.left + 1)
        // The grid holds the other presets and «أجّل حتى…»; «حان الوقت» in the first minute.
        rule.onNodeWithText("أجّل مدة أخرى").assertExists()
        rule.onNodeWithText("أجّل حتى…").assertExists()
        rule.onNodeWithText("حان الوقت").assertExists()
        rule.onAllNodesWithText("تخطَّ هذه المرة").assertCountEquals(0)
    }

    @Test @Config(qualifiers = "en-w400dp-h860dp-xxhdpi")
    fun inEnglishDoneIsRightOfSnooze() {
        Repo.prefs(app).language = "en"
        show()
        val doneBox = rule.onNodeWithText("Done", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val snooze = rule.onNode(hasText("Snooze", substring = true) and hasText("10", substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Done $doneBox should be right of Snooze $snooze in LTR", doneBox.left >= snooze.right - 1)
    }

    @Test fun atDoubleTextDoneIsFullyVisibleAndAboveSnooze() {
        show(t = task(repeat = Repeat.DAILY, priority = Priority.HIGH), fontScale = 2f)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val doneBox = rule.onNodeWithText("تم", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val snooze = rule.onNodeWithText("أجّل 10 دقائق", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Done $doneBox inside $root", doneBox.top >= root.top && doneBox.bottom <= root.bottom && doneBox.left >= root.left && doneBox.right <= root.right)
        assertTrue("Done $doneBox above Snooze $snooze", doneBox.bottom <= snooze.top + 1)
        rule.onNodeWithText("تخطَّ هذه المرة").assertExists()
    }

    @Test fun tapsInTheFirstSecondAreIgnoredButTalkBackIsNot() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("تم").performClick()
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, done)
        // An accessibility action is never blocked.
        rule.onNodeWithText("تم").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, done)
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("تم").performClick()
        rule.mainClock.advanceTimeBy(50)
        assertEquals(2, done)
    }

    @Test fun anImpossiblePresetIsReplacedByTheNext() {
        // «today at» a time less than an hour away cannot be chosen: the next presets move up, no disabled cell.
        val soon = now.atZone(zone).toLocalTime().plusMinutes(20).withSecond(0).withNano(0)
        val presets = listOf(SnoozePreset.Today(if (soon.isBefore(LocalTime.of(1, 0))) LocalTime.of(1, 0) else soon), SnoozePreset.In(10),
            SnoozePreset.In(60), SnoozePreset.Tomorrow(LocalTime.of(9, 0)), SnoozePreset.Week, SnoozePreset.Pick)
        show(presets = presets, guardMs = 0)
        rule.onNodeWithText("أجّل 10 دقائق").assertExists()
        rule.onNodeWithText("أجّل 10 دقائق").performClick()
        assertTrue(snoozedTo!!.isAfter(now.plusSeconds(9 * 60)) && snoozedTo!!.isBefore(now.plusSeconds(11 * 60)))
        rule.onAllNodesWithText("أجّل حتى…").assertCountEquals(1)
    }

    @Test fun hintsLateArrivalRingingAndSteps() {
        val t = task(snoozes = 3).withSteps(listOf(Organize.Step("a", "واحد", true), Organize.Step("b", "اثنان", true),
            Organize.Step("c", "ثلاثة", false), Organize.Step("d", "أربعة", false), Organize.Step("e", "خمسة", false)))
        show(t = t, arrivedLateMs = 12 * 60_000L, ringing = true, guardMs = 0)
        rule.onNodeWithText("أجّلته 3 مرات. اختر وقتًا أنسب؟").assertExists()
        rule.onNodeWithText("وصل متأخرًا 12 دقيقة").assertExists()
        rule.onNodeWithText("اضغط زر الصوت لإسكات الرنين").assertExists()
        rule.onNodeWithText("2 من 5", useUnmergedTree = true).assertExists()
    }
}
