package com.fastkiwi.rememberme.ui.home

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.core.withSteps
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.HomeScreen
import org.junit.Assert.assertEquals
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

/** DESIGN-SPEC §3.7: list + detail from 840 dp, one centred pane from 600 to 839 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w900dp-h700dp-xxhdpi")
class TabletUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now: Instant = Instant.now()
    private var state by mutableStateOf(AppState())
    private val ok = Health(notifications = true, channel = true, exact = true, fullScreen = true, battery = true, dnd = true)
    private val opened = mutableListOf<String>()
    private val alerted = mutableListOf<String>()

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun add(i: TaskInput, edit: (Task) -> Task = { it }): String {
        val (s, t) = Tasks.save(state, i, now.minusSeconds(7200), zone)
        state = s.copy(tasks = s.tasks.map { if (it.id == t.id) edit(it) else it })
        return t.id
    }

    private fun show() {
        rule.setContent {
            AppTheme("light") {
                HomeScreen(
                    state = state, now = now, health = ok, backupNudge = false,
                    onNew = {}, onOpen = { opened += it.id }, onAlert = { alerted += it.id },
                    onComplete = { t, showUndo ->
                        val r = Tasks.complete(state, t.id, Instant.now(), zone)
                        if (r != null) { state = r.first; showUndo { state = Tasks.undoComplete(state, r.second, Instant.now()) } }
                    },
                    onReopen = {}, onTrash = {}, onUndoTrash = {}, onSettings = {}, onHealth = {}, onBackup = {}, onDismissBackup = {},
                    change = { block, then -> state = block(state); then() },
                )
            }
        }
    }

    private fun row(title: String) = rule.onNode(hasContentDescription("$title، ", substring = true) and hasClickAction())
    private fun dp(px: Float) = px / app.resources.displayMetrics.density

    @Test fun aRowShowsItsDetailsBesideTheList() {
        val id = add(TaskInput(title = "اجتماع الفريق", dueAt = now.plusSeconds(3600), repeat = Repeat.WEEKLY, remindBefore = 15)) {
            it.withSteps(listOf(Organize.Step("a", "جدول الأعمال", true), Organize.Step("b", "الدعوات", false)))
        }
        add(TaskInput(title = "دواء الضغط", dueAt = now.minusSeconds(7200)))
        show()
        rule.onNodeWithText("اختر تذكيرًا لعرض تفاصيله").assertExists()
        row("اجتماع الفريق").performClick()
        // Details, not the editor.
        assertTrue(opened.isEmpty())
        rule.onNodeWithText("اجتماع الفريق").assertExists()
        rule.onNodeWithText("التنبيهات").assertExists()
        rule.onNodeWithText("عند الموعد، قبل 15 دقيقة").assertExists()
        rule.onNodeWithText("1 من 2").assertExists()

        // The list pane is 440 dp at the start (the right in RTL); the detail pane fills the rest.
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val fab = rule.onNodeWithText("تذكير جديد", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("FAB $fab in the list pane", dp(fab.left) >= dp(root.right) - 440 - 1)
        val done = rule.onNodeWithText("تم", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val snooze = rule.onNodeWithText("أجّل", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("detail buttons in the detail pane", dp(done.right) <= dp(root.right) - 440)
        assertTrue("«تم» $done at the end, after «أجّل» $snooze", done.right <= snooze.left + 1)

        // Steps tick here.
        rule.onNodeWithText("الدعوات").performScrollTo().performClick()
        assertTrue("steps " + state.task(id)!!.steps, state.task(id)!!.steps.all { it.done })

        // Edit opens the editor; «تم» completes with Undo.
        rule.onNodeWithContentDescription("فتح التذكير للتعديل").performClick()
        assertEquals(listOf(id), opened)
        val before = state.task(id)!!.dueAt
        rule.onNodeWithText("تم", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue("due " + state.task(id)!!.dueAt + " before " + before, state.task(id)!!.dueAt!!.isAfter(before))
        rule.onNodeWithText("أُنجز «اجتماع الفريق»").assertExists()
    }

    @Test fun aDueRowShowsItsDetailsInsteadOfTheReminderScreen() {
        val id = add(TaskInput(title = "دواء الضغط", dueAt = now.minusSeconds(7200)))
        show()
        row("دواء الضغط").performClick()
        assertTrue(alerted.isEmpty())
        rule.onNodeWithText("دواء الضغط").assertExists()
        rule.onNodeWithText("أجّل", useUnmergedTree = true).performClick()
        rule.onNodeWithText("أجّل حتى…").assertExists()
        assertNull(state.task(id)!!.completedAt)
    }

    @Test @Config(qualifiers = "ar-w700dp-h900dp-xxhdpi")
    fun mediumWidthIsOneCentredPane() {
        val id = add(TaskInput(title = "اجتماع الفريق", dueAt = now.plusSeconds(3600)))
        show()
        rule.onNodeWithText("اختر تذكيرًا لعرض تفاصيله").assertDoesNotExist()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val r = row("اجتماع الفريق").fetchSemanticsNode().boundsInRoot
        assertTrue("row $r at most 640 dp wide", dp(r.width) <= 640f)
        assertTrue("row $r centred in $root", kotlin.math.abs(dp(r.left - root.left) - dp(root.right - r.right)) <= 2f)
        row("اجتماع الفريق").performClick()
        assertEquals(listOf(id), opened)
    }
}
