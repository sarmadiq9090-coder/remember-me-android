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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.ui.quick.QuickEntry
import com.fastkiwi.rememberme.ui.quick.VoiceInput
import org.junit.After
import org.junit.Assert.assertEquals
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

/** Independent bug hunt on the 1.0.1 voice input path. Each test here failed against 94c42dc. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class VoiceHuntTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

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
        app.getSharedPreferences("quick", Context.MODE_PRIVATE).edit().putBoolean("voiceDisclosed", true).commit()
    }

    @After fun settle() {
        Repo.prefs(app).language = "ar"
        Thread.sleep(300)
        Engine.drain()
    }

    private fun installRecognizer() {
        val pm = shadowOf(app.packageManager)
        val pkg = "com.google.android.googlequicksearchbox"
        val ai = ApplicationInfo().apply { packageName = pkg }
        pm.addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.VoiceSearchActivity"; applicationInfo = ai }
        })
        pm.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.RecognitionService"; applicationInfo = ai }
        })
    }

    /**
     * H-1. "After a language has worked once, a cancel is just a cancel" checks voiceGood != null, not that the
     * language now being tried is the one that worked. Arabic worked once; the person switches the app to
     * English; English comes up as Google's dead «not available» dialog; Back. The English language is never
     * marked failed, so every later tap opens the same dead dialog, forever, and the plain «does not work here»
     * message never comes. (Same with a phone-locale change: ar-IQ worked, phone becomes ar-SA.)
     */
    @Test fun aCancelInANewLanguageMovesOnEvenIfAnotherLanguageWorkedBefore() {
        Repo.prefs(app).language = "ar"
        val ar = VoiceInput.language(app)
        val ok = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("اتصل بأمي"))
        assertTrue(VoiceInput.result(app, ar, Activity.RESULT_OK, ok) is VoiceInput.Outcome.Text)

        Repo.prefs(app).language = "en"
        val tried = mutableListOf<String?>()
        val outcomes = mutableListOf<VoiceInput.Outcome>()
        repeat(4) {
            val tag = VoiceInput.language(app)
            tried += tag
            outcomes += VoiceInput.result(app, tag, Activity.RESULT_CANCELED, null)
        }
        assertTrue("English dead dialog retried with the same language every time: $tried", tried.distinct().size > 1)
        assertTrue("never told it does not work here: $outcomes", VoiceInput.Outcome.Failed in outcomes)
    }

    /**
     * H-2. The sheet lives in MainActivity.quickAdd (plain mutableStateOf, not saved) and its text in
     * remember(request). Google's dialog covers the app; a rotation (or process death) while it is open recreates
     * MainActivity from the saved stack, which skips handle(intent): the sheet is gone, the words the person
     * dictated are dropped by the registry, and what they had typed before tapping the mic is lost too.
     */
    @Test fun aRotationWhileTheDialogIsOpenKeepsTheSheetAndTheWords() {
        installRecognizer()
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use { sc ->
            rule.onNode(hasSetTextAction()).performTextInput("موعد الطبيب")
            rule.waitForIdle()
            rule.onNodeWithContentDescription("إدخال صوتي").performClick()
            rule.waitForIdle()
            var rc = -1
            sc.onActivity { a -> rc = shadowOf(a).nextStartedActivityForResult?.requestCode ?: -1 }
            assertTrue("the mic opened nothing", rc >= 0)

            sc.recreate() // the phone turned while Google's dialog was on screen
            rule.waitForIdle()

            val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("بعد ساعة"))
            sc.onActivity { a -> a.activityResultRegistry.dispatchResult(rc, Activity.RESULT_OK, data) }
            rule.waitForIdle()
            val field = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            assertEquals("the Quick Add sheet is gone after the rotation", 1, field.size)
            rule.onNode(hasSetTextAction()).assertTextContains("موعد الطبيب بعد ساعة")
        }
    }
}
