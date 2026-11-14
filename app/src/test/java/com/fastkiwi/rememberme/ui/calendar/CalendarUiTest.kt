package com.fastkiwi.rememberme.ui.calendar

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Hijri
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.RepeatRule
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** DESIGN-SPEC §7.4 row Calendar: segments, «اليوم» only in calendar views, the month cell description, 200 %. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class CalendarUiTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val today = LocalDate.of(2026, 11, 14) // a Saturday
    private val now = at(16, 20)

    private val views = mutableListOf<CalView>()
    private var todayClicks = 0
    private val opened = mutableListOf<String>()

    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Instant = LocalDateTime.of(day, java.time.LocalTime.of(h, m)).atZone(zone).toInstant()

    private fun task(id: String, title: String, due: Instant?, repeat: Repeat = Repeat.NONE, snoozed: Boolean = false, priority: Priority = Priority.NORMAL) = Task(
        id = id, title = title, dueAt = due, scheduleAt = if (repeat != Repeat.NONE) due else null, repeat = repeat,
        rule = if (repeat != Repeat.NONE) RepeatRule(from = due) else RepeatRule(), priority = priority,
        snoozedAt = if (snoozed) now.minusSeconds(600) else null, createdAt = now.minusSeconds(86_400), updatedAt = now.minusSeconds(600),
    )

    /** The mockup's Saturday: 2:00 PM overdue (daily), 5:30 PM, 7:00 PM snoozed, 9:00 PM, and one without a date. */
    private val state = AppState(
        listOf(
            task("pills", "دواء الضغط", at(14), Repeat.DAILY, priority = Priority.HIGH),
            task("meeting", "اجتماع الفريق", at(17, 30)),
            task("bread", "شراء الخبز والحليب", at(19), snoozed = true),
            task("math", "مراجعة درس الرياضيات", at(21)),
            task("gift", "شراء هدية لسارة", null),
        ),
    )

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(initial: CalView, hijri: Boolean = false, fontScale: Float? = null) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                AppTheme("light") {
                    var view by remember { mutableStateOf(initial) }
                    var selected by remember { mutableStateOf(today) }
                    CalendarScreen(
                        view = view, state = state, now = now, zone = zone, selected = selected,
                        onSelectDay = { selected = it }, onViewChange = { views += it; view = it },
                        onToday = { todayClicks++; selected = today }, onOpen = { opened += it.id }, onNewOnDay = {},
                        row = { Text("ROW:" + it.title) }, hijri = hijri, hijriAdjust = 0, onSettings = {},
                    )
                }
            }
        }
    }

    /** The go-to-today button: a clickable whose only text is «اليوم» (today's week row also says «اليوم»). */
    private fun todayButton() = rule.onAllNodes(
        SemanticsMatcher("only text اليوم") { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == listOf("اليوم") } and hasClickAction(),
    )

    /** Every text and description on screen: no «·» in Arabic and only Western digits. */
    private fun assertCleanText() {
        val nodes = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        for (n in nodes) {
            val texts = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            for (t in texts) {
                assertFalse("middle dot in «$t»", t.contains('·'))
                assertFalse("Arabic-Indic digit in «$t»", t.any { it in '٠'..'٩' || it in '۰'..'۹' })
                assertFalse("dash range in «$t»", Regex("\\d\\s*[-–—]\\s*\\d").containsMatchIn(t))
            }
        }
    }

    /** Every clickable is at least 48 × 48 dp. */
    private fun assertTouchTargets() {
        val min = (48 * app.resources.displayMetrics.density).toInt() - 1
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            assertTrue("small target ${n.config} ${n.size}", n.size.width >= min && n.size.height >= min)
        }
    }

    @Test fun segmentsSwitchViewsAndTodayButtonIsInTheHeader() {
        show(CalView.MONTH)
        for (label in listOf("أجندة", "يوم", "أسبوع", "شهر")) rule.onNodeWithText(label).assertExists()
        rule.onNodeWithText("نوفمبر 2026").assertExists()
        todayButton().assertCountEquals(1)
        rule.onNodeWithText("يوم").performClick()
        assertEquals(listOf(CalView.DAY), views)
        rule.onNodeWithContentDescription("اليوم السابق").assertExists()
        rule.onNodeWithContentDescription("اليوم التالي").performClick()
        rule.onNodeWithText("الأحد 15 نوفمبر").assertExists()
        todayButton().assertCountEquals(1)
        todayButton()[0].performClick()
        assertEquals(1, todayClicks)
        rule.onNodeWithText("السبت 14 نوفمبر").assertExists()
        rule.onNodeWithText("أسبوع").performClick()
        // The week row of today says «اليوم» too, but only the header's is a button.
        todayButton().assertCountEquals(1)
    }

    @Test fun monthCellsCarryDateCountAndToday() {
        show(CalView.MONTH)
        rule.onNodeWithContentDescription("السبت 14 نوفمبر، 5 تذكيرات، اليوم").assertExists().assertIsSelected()
        // Past days show no count; tomorrow has the daily reminder once (projected, never stored).
        rule.onNodeWithContentDescription("الجمعة 13 نوفمبر").assertExists()
        rule.onNodeWithContentDescription("الأحد 15 نوفمبر، تذكير واحد").assertExists()
        // Weekday letters start from Saturday (the default week start).
        rule.onNodeWithText("السبت 14 نوفمبر، 5 تذكيرات").performScrollTo()
        rule.onAllNodesWithText("ROW:", substring = true).assertCountEquals(5)
        rule.onNodeWithContentDescription("الأحد 15 نوفمبر، تذكير واحد").performClick()
        rule.onNodeWithContentDescription("الأحد 15 نوفمبر، تذكير واحد").assertIsSelected()
        rule.onNodeWithText("الأحد 15 نوفمبر، تذكير واحد").performScrollTo()
        // A projected time is a compact row that opens the reminder, not Home's row.
        rule.onAllNodesWithText("ROW:", substring = true).assertCountEquals(0)
        rule.onNodeWithText("دواء الضغط").performClick()
        assertEquals(listOf("pills"), opened)
        assertTouchTargets()
        assertCleanText()
    }

    @Test fun monthWithHijriNumbersAndNavigation() {
        show(CalView.MONTH, hijri = true)
        val h = Hijri.of(today)
        rule.onAllNodesWithText(h.day.toString(), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onNodeWithContentDescription("الشهر التالي").performClick()
        rule.onNodeWithText("ديسمبر 2026").assertExists()
        rule.onNodeWithContentDescription("الشهر السابق").performClick()
        rule.onNodeWithText("نوفمبر 2026").assertExists()
        assertCleanText()
    }

    @Test fun weekShowsRangeLinesAndMore() {
        show(CalView.WEEK)
        rule.onNodeWithText("14 إلى 20 نوفمبر").assertExists()
        rule.onNodeWithText("+2 أخرى").assertExists()
        // The daily reminder appears on each of the 7 days (6 of them projected).
        rule.onAllNodesWithText("دواء الضغط").assertCountEquals(7)
        rule.onNodeWithContentDescription("الأسبوع التالي").performClick()
        rule.onNodeWithText("21 إلى 27 نوفمبر").assertExists()
        assertTouchTargets()
        assertCleanText()
    }

    @Test fun dayShowsUntimedStripMarksAndNowLine() {
        show(CalView.DAY, hijri = true)
        rule.onNodeWithText("السبت 14 نوفمبر").assertExists()
        val h = Hijri.of(today)
        rule.onNodeWithText("${h.day} ${Hijri.monthNameAr(h.month)} ${h.year}").assertExists()
        rule.onNodeWithText("بلا وقت").assertExists()
        rule.onNodeWithText("شراء هدية لسارة").assertExists()
        rule.onNodeWithText("متأخر").assertExists()
        rule.onNodeWithText("مؤجّل").assertExists()
        rule.onNodeWithText("الآن", substring = true).assertExists()
        assertTouchTargets()
        assertCleanText()
    }

    @Test fun agendaHeadersFromToday() {
        show(CalView.AGENDA)
        rule.onNodeWithText("اليوم، السبت 14 نوفمبر").assertExists()
        rule.onNodeWithText("متأخر ساعتين").assertExists()
        rule.onNodeWithText("بلا وقت").assertExists()
        rule.onNodeWithText("غدًا، الأحد 15 نوفمبر").performScrollTo()
        assertTouchTargets()
        assertCleanText()
    }

    @Test fun largeTextTurnsSegmentsIntoADialogRow() {
        show(CalView.MONTH, fontScale = 2f)
        // No segment row that could clip: one row with the current view opens a single-choice dialog.
        rule.onNodeWithText("أجندة").assertDoesNotExist()
        rule.onNodeWithText("طريقة العرض").assertExists()
        rule.onNodeWithText("شهر").assertExists().performClick()
        for (label in listOf("أجندة", "يوم", "أسبوع", "شهر")) rule.onAllNodesWithText(label).fetchSemanticsNodes().let { assertTrue(label, it.isNotEmpty()) }
        rule.onNodeWithText("أسبوع").performClick()
        assertEquals(listOf(CalView.WEEK), views)
        rule.onNodeWithText("14 إلى 20 نوفمبر").assertExists()
        todayButton().assertCountEquals(1)
        rule.onNodeWithText("نوفمبر 2026").assertExists()
        assertTouchTargets()
    }

    @Test fun largeTextMonthCellStillDescribed() {
        show(CalView.MONTH, fontScale = 2f)
        rule.onNodeWithContentDescription("السبت 14 نوفمبر، 5 تذكيرات، اليوم").assertExists()
        assertTouchTargets()
    }
}
