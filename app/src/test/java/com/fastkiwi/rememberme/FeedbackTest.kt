package com.fastkiwi.rememberme

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.feedback.DeviceInfo
import com.fastkiwi.rememberme.feedback.Feedback
import com.fastkiwi.rememberme.feedback.FeedbackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.time.Instant
import java.time.ZoneId

/**
 * 1.0.5 (D-081, as Kiwi Scan): «أبلغنا عن مشكلة أو اقتراح». Nothing is sent by the app: the person's mail app or browser
 * opens with the text; device information only when ticked, and never a reminder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class FeedbackTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val info = DeviceInfo("1.0.5", 8, "16 (API 36)", "Google Pixel 8", "ar-IQ")

    @Before fun setUp() {
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @Test fun theLinksGoToOurMailAndOurRepositoryAndStayShort() {
        val mail = Feedback.mailto(FeedbackKind.PROBLEM, "1.0.5", "لا يرن التذكير", info)
        assertTrue(mail, mail.startsWith("mailto:support@fastkiwi.app?subject="))
        assertTrue(mail.contains(Feedback.encode("[Remember Me 1.0.5] مشكلة")))
        val gh = Feedback.githubIssue(FeedbackKind.SUGGESTION, "1.0.5", "x".repeat(20_000), info)
        assertTrue(gh, gh.startsWith("https://github.com/sarmadiq9090-coder/remember-me-android/issues/new?template=feature_request.yml"))
        assertTrue(gh.length <= Feedback.MAX_URL)
        // Only these lines, and only when ticked.
        assertEquals("App: Remember Me 1.0.5 (8)\nAndroid: 16 (API 36)\nDevice: Google Pixel 8\nLanguage: ar-IQ", Feedback.deviceLines(info))
        assertEquals("نص", Feedback.body(" نص ", null))
        // The repository has the two issue forms the links name.
        for (t in FeedbackKind.entries) assertTrue(t.template, File("../.github/ISSUE_TEMPLATE/${t.template}").exists())
    }

    @Test fun theScreenOpensTheMailAppWithTheTextAndNoReminder() {
        val now = Instant.now()
        Repo.store(app).change { Tasks.save(it, TaskInput(title = "دواء الضغط السري"), now, ZoneId.of("Asia/Baghdad")).first }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.stack = listOf(Screen.Home, Screen.Settings) }
            rule.waitForIdle()
            rule.onNode(hasText("أبلغنا عن مشكلة أو اقتراح") and hasClickAction()).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("إرسال عبر البريد").assertIsNotEnabled()
            rule.onNode(hasSetTextAction()).performTextInput("لا يرن التذكير بعد إعادة التشغيل")
            rule.onNode(hasText("أرفق معلومات الجهاز") and hasClickAction()).performClick()
            rule.onNodeWithText("سيُرفق هذا فقط:").assertExists()
            rule.onNodeWithText("إرسال عبر البريد").assertIsEnabled().performClick()
            rule.waitForIdle()
            val sent: Intent? = shadowOf(app).nextStartedActivity
            assertNotNull(sent)
            assertEquals(Intent.ACTION_SENDTO, sent!!.action)
            val body = sent.getStringExtra(Intent.EXTRA_TEXT)!!
            assertTrue(body, body.startsWith("لا يرن التذكير بعد إعادة التشغيل") && body.contains("App: Remember Me"))
            // Never a reminder.
            assertFalse(body.contains("دواء الضغط السري"))
            assertFalse(sent.dataString!!.contains(Feedback.encode("دواء")))
        }
    }
}
