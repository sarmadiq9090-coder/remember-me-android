package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/** 1.0.3: the «الموعد» chip in Quick Add, as a person and TalkBack meet it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class QuickDueUiTest {
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

    private fun chip() = rule.onNode(hasContentDescription("الموعد:", substring = true))

    private fun chipLabel() = chip().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString()

    @Test fun theChipSaysNotSetIs48dpAndFollowsTheText() {
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use {
            assertEquals("الموعد: غير محدد", chipLabel())
            val n = chip().fetchSemanticsNode()
            val d = app.resources.displayMetrics.density
            assertTrue("chip ${n.size.height / d} dp high", n.size.height / d >= 47.5f)
            rule.onNode(hasSetTextAction()).performTextInput("اتصل بأمي بعد ساعة")
            settleUi()
            assertTrue(chipLabel(), chipLabel().startsWith("الموعد: اليوم"))
        }
    }

    @Test fun pickingWithTheDefaultsSavesTheNextFullHour() {
        val before = Instant.now()
        ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, null)).use {
            rule.onNode(hasSetTextAction()).performTextInput("اتصل بأمي")
            settleUi()
            chip().performClick()
            rule.waitForIdle()
            // The date picker (today), then the time picker (the next full hour).
            rule.onNodeWithText("موافق").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("موافق").performClick()
            rule.waitForIdle()
            assertTrue(chipLabel(), !chipLabel().endsWith("غير محدد"))
            rule.onNodeWithText("حفظ").performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.isNotEmpty() }
            val t = Repo.state(app).tasks.single()
            assertEquals("اتصل بأمي", t.title)
            val expected = before.atZone(ZoneId.of("Asia/Baghdad")).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
            assertTrue("due ${t.dueAt} expected about $expected", Duration.between(expected, t.dueAt).abs() <= Duration.ofHours(1))
            assertEquals(0, t.dueAt!!.atZone(ZoneId.of("Asia/Baghdad")).minute)
        }
    }
}
