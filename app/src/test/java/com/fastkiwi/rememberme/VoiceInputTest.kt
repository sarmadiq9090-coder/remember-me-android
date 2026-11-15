package com.fastkiwi.rememberme

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
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.ui.quick.VoiceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 1.0.1 voice input without the UI, on the oldest and newest Android the app supports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "ar")
class VoiceInputTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pkg = "com.google.android.googlequicksearchbox"

    @Before fun setUp() {
        com.fastkiwi.rememberme.ui.quick.VoiceInput.detailsSource = { null }
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "quick")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun install(dialog: Boolean, service: Boolean) {
        val pm = shadowOf(app.packageManager)
        val ai = ApplicationInfo().apply { packageName = pkg }
        if (dialog) pm.addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.VoiceSearchActivity"; applicationInfo = ai }
        })
        if (service) pm.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.RecognitionService"; applicationInfo = ai }
        })
    }

    private fun texts(vararg t: String) = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*t))

    @Test fun availableOnlyWithBothTheDialogAndTheService() {
        assertFalse(VoiceInput.available(app))
        install(dialog = true, service = false)
        assertFalse(VoiceInput.available(app))
        install(dialog = true, service = true)
        assertTrue(VoiceInput.available(app))
    }

    @Test fun theIntentAsksForOfflineOnlyWhenToldAndOmitsTheLanguageWhenNone() {
        val i = VoiceInput.intent(app, null)
        assertFalse(i.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        assertFalse(i.hasExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(VoiceInput.intent(app, "ar-EG", offline = true).getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals("ar", VoiceInput.intent(app, "ar").getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    @Test fun resultsMoveThroughTheLanguagesAndKeepTheOneThatWorks() {
        val d = VoiceInput.Details(listOf("ar-SA", "ar-EG"))
        assertEquals("ar-SA", VoiceInput.language(app, d))
        assertEquals(VoiceInput.Outcome.Failed, VoiceInput.result(app, "ar-SA", RecognizerIntent.RESULT_NETWORK_ERROR, null))
        assertEquals("ar-EG", VoiceInput.language(app, d))
        // Blank text is no text.
        assertEquals(VoiceInput.Outcome.NoText, VoiceInput.result(app, "ar-EG", Activity.RESULT_OK, texts("  ")))
        assertNull(VoiceInput.language(app, d))
        assertEquals(VoiceInput.Outcome.Text("ذكرني"), VoiceInput.result(app, null, Activity.RESULT_OK, texts(" ذكرني ")))
        // The service's own language worked: it is kept, a cancel does not move it.
        assertNull(VoiceInput.language(app, d))
        assertEquals(VoiceInput.Outcome.NoText, VoiceInput.result(app, null, Activity.RESULT_CANCELED, null))
        assertNull(VoiceInput.language(app))
        // Nothing heard is not a failure of the language.
        assertEquals(VoiceInput.Outcome.NoText, VoiceInput.result(app, null, RecognizerIntent.RESULT_NO_MATCH, null))
        assertNull(VoiceInput.language(app))
    }
}
