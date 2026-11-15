package com.fastkiwi.rememberme.qc

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.HomeScreen
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

/**
 * QC 1.0.0, F-102 «نص مختلط سليم»: "BidiFormatter / Unicode isolates around Latin runs and numbers inside Arabic
 * sentences". L10n.isolate exists but Home's undo snackbar puts the raw title into «أُنجز «%1$s»» (Home.kt:409, 502),
 * so a Latin title ending in a number ("Meeting 9") is laid out by the surrounding Arabic run. FAILS on this RC.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class QcBidiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now: Instant = Instant.now()
    private var state by mutableStateOf(AppState())
    private val ok = Health(notifications = true, channel = true, exact = true, fullScreen = true, battery = true, dnd = true)

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun isBidiControl(c: Char) = c in '⁦'..'⁩' || c in '‪'..'‮' || c == '‎' || c == '‏'

    @Test fun f102_theUndoSnackbarIsolatesALatinTitle() {
        state = Tasks.save(state, TaskInput(title = "Meeting 9", dueAt = now.plusSeconds(3600)), now.minusSeconds(7200), zone).first
        rule.setContent {
            AppTheme("light") {
                HomeScreen(
                    state = state, now = now, health = ok, backupNudge = false,
                    onNew = {}, onOpen = {}, onAlert = {},
                    onComplete = { t, showUndo ->
                        val r = Tasks.complete(state, t.id, Instant.now(), zone)
                        if (r != null) { state = r.first; showUndo { state = Tasks.undoComplete(state, r.second, Instant.now()) } }
                    },
                    onReopen = {}, onTrash = {}, onUndoTrash = {}, onSettings = {}, onHealth = {}, onBackup = {}, onDismissBackup = {},
                    onQuickAdd = {},
                    calendar = { v -> Text("calendar-" + v.name) },
                    filters = emptyList(), swipe = true,
                    change = { block, then -> state = block(state); then() },
                    onExportSelected = {},
                )
            }
        }
        rule.onAllNodes(hasContentDescription("Meeting 9", substring = true) and hasClickAction()).onFirst().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val node = rule.onAllNodes(hasText("أُنجز «", substring = true)).fetchSemanticsNodes().first()
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        assertTrue("snackbar text «$text» has no bidi isolation around the Latin title", text.any(::isBidiControl))
    }
}
