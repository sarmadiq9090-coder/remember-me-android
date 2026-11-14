package com.fastkiwi.rememberme.ui.home

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.withTags
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.time.ZoneId
import java.util.TimeZone

/** Home's test hooks (DESIGN-SPEC §7.4 rows Home, Home row, Undo, Bulk) on the screen itself, with the data held in the test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class HomeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now: Instant = Instant.now()
    private var state by mutableStateOf(AppState())
    private val ok = Health(notifications = true, channel = true, exact = true, fullScreen = true, battery = true, dnd = true)
    private var quickAdds = 0
    private var exported: List<String>? = null

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun add(i: TaskInput): String {
        val (s, t) = Tasks.save(state, i, now.minusSeconds(7200), zone)
        state = s
        return t.id
    }

    private fun show(filters: List<SavedFilter> = emptyList(), swipe: Boolean = true) {
        rule.setContent {
            AppTheme("light") {
                HomeScreen(
                    state = state, now = now, health = ok, backupNudge = false,
                    onNew = {}, onOpen = {}, onAlert = {},
                    onComplete = { t, showUndo ->
                        val r = Tasks.complete(state, t.id, Instant.now(), zone)
                        if (r != null) { state = r.first; showUndo { state = Tasks.undoComplete(state, r.second, Instant.now()) } }
                    },
                    onReopen = {}, onTrash = {}, onUndoTrash = {}, onSettings = {}, onHealth = {}, onBackup = {}, onDismissBackup = {},
                    onQuickAdd = { quickAdds++ },
                    calendar = { v -> Text("calendar-" + v.name) },
                    filters = filters, swipe = swipe,
                    change = { block, then -> state = block(state); then() },
                    onExportSelected = { exported = it },
                )
            }
        }
    }

    private fun row(title: String) = rule.onNode(hasContentDescription("$title، ", substring = true) and hasClickAction())

    @Test fun homeHasTheBrandOneTodayAndNoNavigationLabels() {
        add(TaskInput(title = "اجتماع", dueAt = now.plusSeconds(60)))
        add(TaskInput(title = "سفر", dueAt = now.plusSeconds(3 * 86_400)))
        show()
        rule.onNodeWithText("ذكرني").assertExists()
        rule.onAllNodesWithText("اليوم").assertCountEquals(1)
        rule.onNodeWithText("القادمة").assertExists()
        rule.onNodeWithText("تذكير جديد", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("طريقة العرض").assertExists()
        rule.onNodeWithContentDescription("بحث").assertExists()
        rule.onNodeWithContentDescription("الإعدادات").assertExists()
        for (nav in listOf("التذكيرات", "التقويم", "القوائم")) rule.onAllNodesWithText(nav).assertCountEquals(0)
        // The FAB opens Quick Add.
        rule.onNodeWithText("تذكير جديد", useUnmergedTree = true).performClick()
        assertEquals(1, quickAdds)
    }

    @Test fun aOneListUserSeesNoChips() {
        add(TaskInput(title = "اجتماع", dueAt = now.plusSeconds(60)))
        show(filters = listOf(SavedFilter("l1", "منزل", "tag", "منزل")))
        rule.onAllNodesWithText("الكل").assertCountEquals(0)
    }

    @Test fun twoListsShowChipsAndAListFilters() {
        val home = add(TaskInput(title = "تسوق", dueAt = now.plusSeconds(60)))
        add(TaskInput(title = "تقرير", dueAt = now.plusSeconds(120)))
        state = state.copy(tasks = state.tasks.map { if (it.id == home) it.withTags(listOf("منزل")) else it })
        show(filters = listOf(SavedFilter("l1", "منزل", "tag", "منزل"), SavedFilter("l2", "عمل", "tag", "عمل")))
        rule.onNodeWithText("الكل").assertExists()
        rule.onNodeWithText("مهم").assertExists()
        rule.onNodeWithText("القوائم…").assertExists()
        rule.onNodeWithText("منزل").performClick()
        row("تسوق").assertExists()
        row("تقرير").assertDoesNotExist()
        rule.onNodeWithText("الكل").performClick()
        row("تقرير").assertExists()
    }

    @Test fun aSavedFilterAloneShowsChips() {
        add(TaskInput(title = "اجتماع", dueAt = now.plusSeconds(60)))
        show(filters = listOf(SavedFilter("f1", "متأخر", "overdue")))
        rule.onNodeWithText("الكل").assertExists()
    }

    @Test fun aRowSpeaksItsStateAndOffersItsActions() {
        add(TaskInput(title = "دواء الضغط", dueAt = now.minusSeconds(2 * 3600), repeat = Repeat.DAILY, priority = Priority.HIGH))
        show()
        val node = row("دواء الضغط").fetchSemanticsNode()
        val desc = node.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(desc, desc.contains("دواء الضغط") && desc.contains("متأخر") && desc.contains("منبّه مهم") && desc.contains(":"))
        assertFalse(desc, desc.contains("·"))
        val actions = node.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("تم", "أجّل", "تحديد"), actions)
    }

    @Test fun swipeStartToEndCompletesWithAnExactUndo() {
        val id = add(TaskInput(title = "سقي النباتات", dueAt = now.plusSeconds(3600), repeat = Repeat.DAILY))
        val before = state.task(id)!!
        show()
        // In RTL the finger moves right → left for start → end.
        row("سقي النباتات").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("one completion", 2, state.tasks.size)
        assertTrue(state.task(id)!!.dueAt!!.isAfter(before.dueAt))
        rule.onNodeWithText("أُنجز «سقي النباتات»").assertExists()
        rule.onNodeWithText("تراجع").performClick()
        rule.waitForIdle()
        val back = state.task(id)!!
        assertEquals(before.dueAt, back.dueAt)
        assertEquals(1, state.tasks.size)
    }

    @Test fun swipeEndToStartOpensTheSnoozeSheet() {
        val id = add(TaskInput(title = "الرياضة", dueAt = now.plusSeconds(3600), repeat = Repeat.DAILY))
        show()
        row("الرياضة").performTouchInput { swipeRight() }
        rule.waitForIdle()
        // Nothing changed yet; the sheet shows each preset with its time.
        assertEquals(0, state.task(id)!!.snoozeCount)
        rule.onNodeWithText("التكرار لا يتغيّر").assertExists()
        rule.onNodeWithText("أجّل حتى…").assertExists()
        rule.onNodeWithText("ساعة").assertExists()
        rule.onNodeWithText("10 دقائق").performClick()
        rule.waitForIdle()
        val t = state.task(id)!!
        assertEquals(1, t.snoozeCount)
        assertEquals(Repeat.DAILY, t.repeat)
        rule.onNodeWithText("أُجّل حتى", substring = true).assertExists()
        rule.onNodeWithText("تراجع").performClick()
        rule.waitForIdle()
        assertEquals(0, state.task(id)!!.snoozeCount)
    }

    @Test fun swipingIsOffWhenTheSettingIsOff() {
        val id = add(TaskInput(title = "سقي النباتات", dueAt = now.plusSeconds(3600)))
        show(swipe = false)
        row("سقي النباتات").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertNull(state.task(id)!!.completedAt)
    }

    @Test fun longPressSelectsAndTheBarCompletesWithUndo() {
        val ids = listOf("أ", "ب", "ج", "د").map { add(TaskInput(title = "مهمة $it", dueAt = now.plusSeconds(3600))) }
        show()
        row("مهمة أ").performTouchInput { longClick() }
        rule.onNodeWithText("1 محدد").assertExists()
        row("مهمة ب").performClick()
        row("مهمة ج").performClick()
        rule.onNodeWithText("3 محددة").assertExists()
        rule.onNodeWithContentDescription("إلغاء التحديد").assertExists()
        rule.onNodeWithContentDescription("تحديد الكل").assertExists()
        // FAB and swipe are gone in selection mode.
        rule.onAllNodesWithText("تذكير جديد", useUnmergedTree = true).assertCountEquals(0)
        // Five labelled items in this order, «تم» last (leftmost in RTL).
        val labels = listOf("حذف", "الأهمية", "نقل", "أجّل", "تم")
        val xs = labels.map { rule.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x }
        assertEquals(xs.sortedDescending(), xs)
        rule.onNodeWithText("تم", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(3, state.tasks.count { it.completedAt != null })
        rule.onNodeWithText("أُنجز 3 تذكيرات").assertExists()
        rule.onNodeWithText("تراجع").performClick()
        rule.waitForIdle()
        assertTrue(state.tasks.all { it.completedAt == null })
        assertEquals(ids.size, state.tasks.size)
    }

    @Test fun talkBackSelectActionEntersSelection() {
        add(TaskInput(title = "مهمة", dueAt = now.plusSeconds(3600)))
        show()
        val select = row("مهمة").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "تحديد" }
        rule.runOnIdle { select.action() }
        rule.onNodeWithText("1 محدد").assertExists()
    }

    @Test fun deletingFewerThanTenAppliesAtOnceWithUndo() {
        repeat(3) { add(TaskInput(title = "مهمة $it", dueAt = now.plusSeconds(3600L + it))) }
        show()
        row("مهمة 0").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("تحديد الكل").performClick()
        rule.onNodeWithText("حذف", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("نقل 3 تذكيرات إلى السلة؟").assertCountEquals(0)
        assertTrue(state.tasks.all { it.deletedAt != null })
        rule.onNodeWithText("نُقلت 3 تذكيرات إلى السلة").assertExists()
        rule.onNodeWithText("تراجع").performClick()
        rule.waitForIdle()
        assertTrue(state.tasks.all { it.deletedAt == null })
    }

    @Test fun deletingTwelveAsksFirst() {
        repeat(12) { add(TaskInput(title = "مهمة $it", dueAt = now.plusSeconds(3600L + it), repeat = if (it < 4) Repeat.DAILY else Repeat.NONE)) }
        show()
        row("مهمة 0").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("تحديد الكل").performClick()
        rule.onNodeWithText("12 محددة").assertExists()
        rule.onNodeWithText("حذف", useUnmergedTree = true).performClick()
        rule.onNodeWithText("نقل 12 تذكيرًا إلى السلة؟").assertExists()
        rule.onNodeWithText("4 منها متكررة", substring = true).assertExists()
        assertTrue(state.tasks.all { it.deletedAt == null })
        rule.onNodeWithText("نقل إلى السلة").performClick()
        rule.waitForIdle()
        assertTrue(state.tasks.all { it.deletedAt != null })
    }

    @Test fun exportSelectedIsInTheOverflowMenu() {
        val a = add(TaskInput(title = "مهمة أ", dueAt = now.plusSeconds(3600)))
        add(TaskInput(title = "مهمة ب", dueAt = now.plusSeconds(7200)))
        show()
        row("مهمة أ").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("خيارات أخرى").performClick()
        rule.onNodeWithText("تصدير المحدد").performClick()
        assertEquals(listOf(a), exported)
    }

    @Test fun theViewMenuOpensACalendarViewAndBackReturnsToTheList() {
        add(TaskInput(title = "اجتماع", dueAt = now.plusSeconds(60)))
        show()
        rule.onNodeWithContentDescription("طريقة العرض").performClick()
        for (v in listOf("القائمة", "أجندة", "يوم", "أسبوع", "شهر")) rule.onNodeWithText(v).assertExists()
        rule.onNodeWithText("شهر").performClick()
        rule.onNodeWithText("calendar-MONTH").assertExists()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("calendar-MONTH").assertDoesNotExist()
        rule.onAllNodesWithText("اليوم").assertCountEquals(1)
    }

    @Test fun searchFindsTagsCategoryAndState() {
        val home = add(TaskInput(title = "تسوق", dueAt = now.plusSeconds(60)))
        add(TaskInput(title = "تقرير", dueAt = now.plusSeconds(120), category = com.fastkiwi.rememberme.core.Category.WORK))
        state = state.copy(tasks = state.tasks.map { if (it.id == home) it.withTags(listOf("منزل")) else it })
        show()
        rule.onNodeWithContentDescription("بحث").performClick()
        rule.onNodeWithText("ابحث…").performTextInput("منزل")
        row("تسوق").assertExists()
        row("تقرير").assertDoesNotExist()
        rule.onNodeWithText("ابحث…").assertDoesNotExist()
        assertNotNull(state.task(home))
    }
}
