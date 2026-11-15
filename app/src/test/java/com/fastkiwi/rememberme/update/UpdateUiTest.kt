package com.fastkiwi.rememberme.update

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.Screen
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.0.5 (D-081) on the real app (Arabic): the one-time notice with the owner's wording, the two settings (both on
 * by default) and «تحقق من التحديثات» in «حول ذكرني». Under Robolectric the app's updater always sees "offline", so
 * tapping the button never reaches GitHub.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class UpdateUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    companion object {
        private const val NOTICE = "يتصل ذكرني بالإنترنت فقط للتحقق من التحديثات وتنزيلها من GitHub. لا يُرسل أي تذكير أو بيانات عنك. يمكنك إيقافه من الإعدادات."

        @JvmStatic @BeforeClass fun showNotice() { UpdateController.showNoticeInTests = true }
        @JvmStatic @AfterClass fun hideNotice() { UpdateController.showNoticeInTests = false }
    }

    @Before fun setUp() {
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "update")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @Test fun noticeOnceThenTheSettingsAndTheCheckInAbout() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            // First start: the notice, word for word, before anything is checked.
            rule.waitUntil(5_000) { rule.onAllNodesWithText(NOTICE).fetchSemanticsNodes().isNotEmpty() }
            val prefs = UpdatePrefs(app)
            assertEquals(0L, prefs.lastUpdateCheck)
            rule.onNode(hasText("الإعدادات") and hasClickAction()).performClick()
            rule.waitForIdle()
            assertTrue(prefs.updateNoticeShown)
            sc.onActivity { a -> assertFalse(a.updates.noticePending.value); assertEquals(Screen.Settings, a.stack.last()) }
            // Settings: both switches on by default.
            rule.onNodeWithText("التحقق التلقائي من التحديثات").performScrollTo()
            rule.onNode(hasText("التحقق التلقائي من التحديثات") and hasClickAction()).assertIsOn()
            rule.onNode(hasText("التنزيل عبر Wi-Fi فقط") and hasClickAction()).performScrollTo().assertIsOn()
            assertTrue(prefs.autoUpdateCheck && prefs.updateWifiOnly)
            // The switches change the settings.
            rule.onNode(hasText("التنزيل عبر Wi-Fi فقط") and hasClickAction()).performClick()
            rule.waitForIdle()
            assertFalse(prefs.updateWifiOnly)
            rule.onNode(hasText("التحقق التلقائي من التحديثات") and hasClickAction()).performClick()
            rule.waitForIdle()
            assertFalse(prefs.autoUpdateCheck)
            // «حول ذكرني»: the statement, and the check with no connection: a clear message, no crash, nothing downloaded.
            rule.onNode(hasText("حول ذكرني") and hasClickAction()).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText(NOTICE).performScrollTo()
            rule.onNode(hasText("تحقق من التحديثات") and hasClickAction()).performScrollTo().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("لا يوجد اتصال بالإنترنت.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            sc.onActivity { a -> assertEquals(UpdateState.Failed(UpdateError.OFFLINE), a.updates.state.value) }
        }
        // Not shown again at the next start.
        ActivityScenario.launch(MainActivity::class.java).use { sc -> sc.onActivity { a -> assertFalse(a.updates.noticePending.value) } }
    }
}
