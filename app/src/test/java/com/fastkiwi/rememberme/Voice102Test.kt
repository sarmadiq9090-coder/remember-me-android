package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.ui.quick.QuickEntry
import org.junit.After
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
import java.time.ZoneId
import java.util.TimeZone

/**
 * 1.0.2, from the owner's phone again: Google's «ميزة "البحث الصوتي" غير متاحة» for «العربية (العراق)». The phone
 * itself is ar-IQ, so 1.0.1's chain (the phone's language first) asked for ar-IQ again, offline. Without a list
 * of the languages the service supports, start with ar-SA and do not ask for offline. And Quick Add's text is
 * not lost when the person opens «خيارات أكثر» and goes back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-rIQ-w400dp-h860dp-xxhdpi")
class Voice102Test {
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

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    @Test fun anIraqiPhoneWithoutALanguageListStartsWithSaudiArabicOnline() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use { sc ->
            rule.onNodeWithContentDescription("إدخال صوتي").performClick()
            rule.waitForIdle()
            var started: Intent? = null
            sc.onActivity { a -> started = shadowOf(a).nextStartedActivityForResult?.intent }
            assertNotNull("the mic opened nothing", started)
            assertEquals("ar-SA", started!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertFalse("offline asked without knowing an offline pack exists", started!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        }
    }

    private fun firstLaunch(sc: ActivityScenario<MainActivity>): Intent {
        rule.onNodeWithContentDescription("إدخال صوتي").performClick()
        rule.waitForIdle()
        var started: Intent? = null
        sc.onActivity { a -> started = shadowOf(a).nextStartedActivityForResult?.intent }
        return started!!
    }

    @Test fun anIraqiPhoneWithAListWithoutIraqGetsTheFirstListedArabicAndOfflineOnlyIfOnThePhone() {
        com.fastkiwi.rememberme.ui.quick.VoiceInput.detailsSource = {
            com.fastkiwi.rememberme.ui.quick.VoiceInput.Details(listOf("ar-EG", "ar-SA", "en-US"), offline = setOf("ar-SA"))
        }
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use { sc ->
            val i = firstLaunch(sc)
            assertEquals("ar-SA", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertTrue(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        }
    }

    @Test fun anIraqiPhoneWhoseServiceListsIraqGetsIt() {
        com.fastkiwi.rememberme.ui.quick.VoiceInput.detailsSource = {
            com.fastkiwi.rememberme.ui.quick.VoiceInput.Details(listOf("ar-SA", "ar-IQ"))
        }
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use { sc ->
            val i = firstLaunch(sc)
            assertEquals("ar-IQ", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertFalse(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        }
    }

    @Test fun backFromMoreOptionsDoesNotLoseTheQuickAddText() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, "اتصل بأمي بعد ساعة")).use { sc ->
            rule.onNodeWithText("خيارات أكثر").performClick()
            rule.waitForIdle()
            sc.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            // The editor asks before the typed reminder is thrown away.
            rule.onNodeWithText("لم يُحفظ هذا التذكير بعد. تجاهله؟").assertExists()
            rule.onNodeWithText("متابعة التعديل").performClick()
            rule.waitForIdle()
            sc.onActivity { a -> assertTrue(a.stack.last() is Screen.Edit) }
            // Discarding on purpose still works.
            sc.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.onNodeWithText("تجاهل").performClick()
            rule.waitForIdle()
            sc.onActivity { a -> assertTrue(a.stack.last() == Screen.Home) }
            assertTrue(Repo.state(app).tasks.isEmpty())
        }
    }
}
