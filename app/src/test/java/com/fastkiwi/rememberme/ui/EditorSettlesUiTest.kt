package com.fastkiwi.rememberme.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fastkiwi.rememberme.core.at
import com.fastkiwi.rememberme.data.EditorSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.TimeZone

/**
 * The editor's composition settles after a priority change (UI monkey seed 14003: «متوسط» never became idle,
 * the recomposer pending frame after frame). Before the fix each of these clicks ended in AppNotIdleException.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class EditorSettlesUiTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)

    @Before fun setUp() { TimeZone.setDefault(TimeZone.getTimeZone(zone)) }

    private fun editor() = rule.setContent {
        AppTheme("light") {
            EditScreen(task = null, weekStart = 6, now = now, onSave = { null }, onDelete = {}, onBack = {}, settings = EditorSettings())
        }
    }

    private fun selected(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, on)

    @Test fun everyPriorityChangeSettles() {
        editor()
        rule.onNodeWithText("متوسط").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("متوسط").assert(selected(true))
        rule.onNodeWithText("مهم").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("عادي").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("عادي").assert(selected(true))
        rule.onNodeWithText("مهم").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("مهم").assert(selected(true))
    }
}
