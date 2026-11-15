package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.ui.quick.QuickEntry
import org.junit.After
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
import java.io.File
import java.time.ZoneId
import java.util.TimeZone

/**
 * 1.0.4, the owner on 1.0.3's sheet: «الازرار يجب ان تكون افضل ومرتبه اكثر اجعلها تبدو احترافيه». «تذكير جديد» is a
 * full-screen Material 3 window: «×», the title and «حفظ» in the top bar (never under the keyboard), the field,
 * then 56 dp list rows («الموعد», «التصنيف», «الأهمية», «من قالب», «خيارات أكثر»); the date shows once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class QuickScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val density get() = app.resources.displayMetrics.density

    @Before fun setUp() {
        com.fastkiwi.rememberme.ui.quick.VoiceInput.detailsSource = { null }
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Baghdad")))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "quick")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private fun settleUi() {
        repeat(3) {
            Thread.sleep(250)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            rule.mainClock.advanceTimeBy(300)
            rule.waitForIdle()
        }
    }

    /** The keyboard comes up: [dp] at the bottom of the window. Returns its top edge in pixels. */
    private fun keyboard(sc: ActivityScenario<MainActivity>, dp: Int): Float {
        var top = 0f
        sc.onActivity { a ->
            val px = (dp * density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets)
            top = (a.window.decorView.height - px).toFloat()
        }
        rule.waitForIdle()
        return top
    }

    @Test fun saveStaysVisibleAboveTheKeyboard() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, "اتصل بأمي بعد ساعة")).use { sc ->
            settleUi()
            val top = keyboard(sc, 320)
            val save = rule.onNodeWithText("حفظ").fetchSemanticsNode()
            assertTrue("«حفظ» ends at ${save.boundsInWindow.bottom}, the keyboard starts at $top", save.boundsInWindow.bottom <= top)
            assertTrue("«حفظ» is cut", save.boundsInWindow.height >= 40 * density)
            // The field too.
            val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode()
            assertTrue(field.boundsInWindow.bottom <= top)
        }
    }

    @Test fun aTopBarWithCloseTitleAndSaveAndTheDateOnce() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, "اتصل بأمي بعد ساعة")).use {
            settleUi()
            rule.onNodeWithContentDescription("إغلاق").assertExists()
            rule.onNode(hasText("تذكير جديد")).assertExists()
            val save = rule.onNodeWithText("حفظ").fetchSemanticsNode()
            val close = rule.onNodeWithContentDescription("إغلاق").fetchSemanticsNode()
            // Both in the top bar.
            assertTrue(save.boundsInWindow.top < 120 * density && close.boundsInWindow.top < 120 * density)
            // The moment read from the text shows once, in the «الموعد» row.
            val due = rule.onAllNodes(hasText("اليوم،", substring = true)).fetchSemanticsNodes()
            assertEquals(1, due.size)
            rule.onNode(hasContentDescription("الموعد: اليوم", substring = true)).assertExists()
        }
    }

    @Test fun theRowsAreFullWidthAnd56dp() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use {
            settleUi()
            val width = app.resources.displayMetrics.widthPixels
            for (d in listOf("الموعد: غير محدد", "التصنيف: شخصي", "الأهمية: عادي", "من قالب", "خيارات أكثر")) {
                val n = rule.onNode(hasContentDescription(d)).fetchSemanticsNode()
                val h = n.size.height / density
                assertTrue("$d is $h dp high", h >= 55.5f)
                assertTrue("$d is ${n.size.width} of $width px wide", n.size.width >= width - 2 * 16 * density - 1)
            }
        }
    }

    @Test fun categoryWorkFromItsRowIsSaved() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, "اتصل بأمي بعد ساعة")).use {
            settleUi()
            rule.onNode(hasContentDescription("التصنيف: شخصي")).performClick()
            rule.onNodeWithText("عمل").performClick()
            rule.waitForIdle()
            rule.onNode(hasContentDescription("التصنيف: عمل")).assertExists()
            rule.onNodeWithText("حفظ").performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.isNotEmpty() }
            assertEquals(Category.WORK, Repo.state(app).tasks.single().category)
        }
    }

    @Test fun closingWithTextAsksFirst() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use { sc ->
            settleUi()
            rule.onNode(hasSetTextAction()).performTextInput("اتصل بأمي")
            settleUi()
            rule.onNodeWithContentDescription("إغلاق").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("لم يُحفظ هذا التذكير بعد. تجاهله؟").assertExists()
            rule.onNodeWithText("متابعة التعديل").performClick()
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).assertExists()
            // Back asks the same.
            sc.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.onNodeWithText("تجاهل").performClick()
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).assertDoesNotExist()
            assertTrue(Repo.state(app).tasks.isEmpty())
        }
    }

    @Test fun emptyClosesWithoutAQuestion() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use {
            settleUi()
            rule.onNodeWithContentDescription("إغلاق").performClick()
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).assertDoesNotExist()
            val desc = rule.onAllNodes(hasText("لم يُحفظ هذا التذكير بعد. تجاهله؟")).fetchSemanticsNodes()
            assertEquals(0, desc.size)
        }
    }

    @Suppress("unused") private fun label(n: androidx.compose.ui.semantics.SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
}
