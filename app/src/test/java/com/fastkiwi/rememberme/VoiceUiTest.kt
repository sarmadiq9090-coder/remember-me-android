package com.fastkiwi.rememberme

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.ui.quick.QuickEntry
import com.fastkiwi.rememberme.ui.quick.VoiceInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.time.ZoneId
import java.util.TimeZone

/**
 * 1.0.1: voice input on a phone where it does not work. The owner's phone showed Google's own «ميزة "البحث
 * الصوتي" غير متاحة» for «العربية (العراق)» and left them there: the app asked for ar-IQ (a region that only
 * comes from the month-name setting), checked nothing before opening the dialog, and ignored a cancelled or
 * failed result. Here: no speech service at all (no mic), a service that fails, rejects the language or is
 * cancelled (an Arabic message, the field focused, the next try in another language), and one that works.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class VoiceUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val unavailable = "الإدخال الصوتي غير متاح على هذا الهاتف. استعمل ميكروفون لوحة المفاتيح."
    private val noText = "لم يصل نص من الإدخال الصوتي. جرّب مرة أخرى، أو استعمل ميكروفون لوحة المفاتيح."

    @Before fun setUp() {
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Baghdad")))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "quick")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
        // The one-time note was already read (QuickUiTest covers it).
        app.getSharedPreferences("quick", Context.MODE_PRIVATE).edit().putBoolean("voiceDisclosed", true).commit()
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private fun quickAdd() = ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null))
    private fun field() = rule.onNode(hasSetTextAction())
    private fun mic() = rule.onNodeWithContentDescription("إدخال صوتي")

    /** A phone with the Google app: the speech dialog, and (unless [service] is false) the speech service. */
    private fun installRecognizer(service: Boolean = true) {
        val pm = shadowOf(app.packageManager)
        val pkg = "com.google.android.googlequicksearchbox"
        val ai = ApplicationInfo().apply { packageName = pkg }
        pm.addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.VoiceSearchActivity"; applicationInfo = ai }
        })
        if (service) pm.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.RecognitionService"; applicationInfo = ai }
        })
    }

    /** Taps the mic and returns what was opened (the activity's own record, so a result can be sent back). */
    private fun tapMic(sc: ActivityScenario<MainActivity>): Intent {
        mic().performClick()
        rule.waitForIdle()
        var started: Intent? = null
        sc.onActivity { a -> started = shadowOf(a).nextStartedActivityForResult?.intent }
        assertNotNull("the mic opened nothing", started)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, started!!.action)
        return started!!
    }

    private fun answer(sc: ActivityScenario<MainActivity>, request: Intent, code: Int, text: String? = null) {
        val data = text?.let { Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(it)) }
        sc.onActivity { a -> shadowOf(a).receiveResult(request, code, data) }
        rule.waitForIdle()
    }

    private fun Intent.language(): String? = getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)

    @Test fun noSpeechServiceAtAllHidesTheMic() {
        quickAdd().use {
            rule.onNodeWithText("اكتب التذكير وموعده").assertExists()
            mic().assertDoesNotExist()
        }
    }

    @Test fun aDialogWithoutASpeechServiceAlsoHidesTheMic() {
        installRecognizer(service = false)
        quickAdd().use { mic().assertDoesNotExist() }
    }

    @Test fun theLanguageIsThePhonesNotTheMonthNameRegion() {
        installRecognizer()
        quickAdd().use { sc ->
            val first = tapMic(sc)
            // The app's own locale is ar-EG or ar-IQ only for the month names; the phone here is plain "ar".
            assertEquals("ar", first.language())
            assertTrue(first.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        }
    }

    @Test fun aFailingServiceLeavesTheUserInTheFieldAndTriesAnotherLanguageNextTime() {
        installRecognizer()
        quickAdd().use { sc ->
            val first = tapMic(sc)
            field().assertIsNotFocused()
            answer(sc, first, RecognizerIntent.RESULT_CLIENT_ERROR)
            rule.onNodeWithText(unavailable).assertExists()
            field().assertIsFocused()
            val second = tapMic(sc)
            assertNotEquals(first.language(), second.language())
            // The language was rejected again: one more fallback, then the service's own language.
            answer(sc, second, RecognizerIntent.RESULT_SERVER_ERROR)
            rule.onNodeWithText(unavailable).assertExists()
            val third = tapMic(sc)
            assertFalse(third.language() in listOf(first.language(), second.language()))
        }
    }

    @Test fun aCancelledDialogLeavesTheUserInTheFieldWithAMessage() {
        installRecognizer()
        quickAdd().use { sc ->
            val first = tapMic(sc)
            // The person pressed Back on Google's dead «غير متاحة» dialog: no text, no error code.
            answer(sc, first, Activity.RESULT_CANCELED)
            rule.onNodeWithText(noText).assertExists()
            field().assertIsFocused()
            assertNotEquals(first.language(), tapMic(sc).language())
        }
    }

    @Test fun textArrivesAndTheWorkingLanguageIsKept() {
        installRecognizer()
        quickAdd().use { sc ->
            val first = tapMic(sc)
            answer(sc, first, Activity.RESULT_OK, "اتصل بأمي بعد ساعة")
            field().assertTextContains("اتصل بأمي بعد ساعة")
            rule.onNodeWithText(unavailable).assertDoesNotExist()
            rule.onNodeWithText(noText).assertDoesNotExist()
            // A later cancel does not move away from the language that worked.
            val again = tapMic(sc)
            assertEquals(first.language(), again.language())
            answer(sc, again, Activity.RESULT_CANCELED)
            assertEquals(first.language(), tapMic(sc).language())
        }
    }

    @Test fun languagesNeverDependOnTheMonthNameRegion() {
        fun ar(region: String) = java.util.Locale.Builder().setLanguage("ar").setRegion(region).setUnicodeLocaleKeyword("nu", "latn").build()
        // The owner's case: the app is ar-IQ (month names), the phone is Arabic (Iraq).
        assertEquals(listOf("ar-IQ", "ar", "ar-SA", null), VoiceInput.candidates(ar("IQ"), java.util.Locale.forLanguageTag("ar-IQ")))
        // The app's region is never sent by itself: a phone in English gives plain Arabic first.
        assertEquals(listOf("ar", "ar-SA", null), VoiceInput.candidates(ar("IQ"), java.util.Locale.forLanguageTag("en-US")))
        assertEquals(listOf("ar", "ar-SA", null), VoiceInput.candidates(ar("EG"), java.util.Locale.forLanguageTag("ar")))
        assertEquals(listOf("en-GB", "en", "en-US", null), VoiceInput.candidates(java.util.Locale.forLanguageTag("en-US"), java.util.Locale.forLanguageTag("en-GB")))
        // No -u-nu-latn or other extensions reach the service.
        assertTrue(VoiceInput.candidates(ar("IQ"), ar("IQ")).filterNotNull().none { "-u-" in it })
    }

    @Test fun aboutSaysWhereTheVoiceGoes() {
        val text = app.getString(R.string.about_voice)
        assertTrue(text, "Google" in text && "الإنترنت" in text)
        assertTrue(app.getString(R.string.set_privacy_voice_body).contains("الإنترنت"))
    }
}
