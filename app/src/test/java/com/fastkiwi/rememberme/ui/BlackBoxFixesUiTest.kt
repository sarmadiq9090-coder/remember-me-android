package com.fastkiwi.rememberme.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.at
import com.fastkiwi.rememberme.data.EditorSettings
import com.fastkiwi.rememberme.ui.editor.AlertStyleSheet
import com.fastkiwi.rememberme.ui.editor.TonePickerSheet
import com.fastkiwi.rememberme.ui.health.AlarmTestScreen
import com.fastkiwi.rememberme.ui.health.HealthModel
import com.fastkiwi.rememberme.ui.health.TestResults
import com.fastkiwi.rememberme.ui.health.soundCauses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The fixes for the black-box user test (rm-blackbox-sessions/BLACKBOX-REPORT.md): U1-2 (the reminder title at
 * 200 %), U2-5 (sheet buttons pinned at 200 %), U2-6 (ticking a step), U2-1 (the green health card and the
 * volume), U2-4 (hints after «لا» in the alert test) and U2-10 (the honest «منبّه مهم» text).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class BlackBoxFixesUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now: Instant = Instant.now()
    private val density get() = app.resources.displayMetrics.density

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("filters", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(fontScale: Float = 1f, theme: String = "light", content: @Composable () -> Unit) = rule.setContent {
        AppTheme(theme) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { content() }
        }
    }

    private fun box(text: String): Rect = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    // ---- U1-2: the reminder screen's title at 200 % ----

    /** The worst case of the report: «1 من 2», an important alarm that repeats, late, snoozed 3 times, ringing. */
    private fun alertAtDoubleText(theme: String) {
        val t = Task(
            id = "t1", title = "اسقي الزرع", body = "", dueAt = now.minusSeconds(90), scheduleAt = now.minusSeconds(90),
            repeat = Repeat.DAILY, priority = Priority.HIGH, snoozeCount = 3, createdAt = now.minusSeconds(7200), updatedAt = now.minusSeconds(7200),
        )
        show(2f, theme) {
            AlertScreen(task = t, position = 1, count = 2, presets = SnoozePreset.DEFAULTS, now = now,
                onDone = {}, onSkip = {}, onSnooze = {}, onEdit = {}, onLater = {}, ringing = true, guardMs = 0)
        }
        rule.onNodeWithText("1 من 2").assertExists()
        val title = box("اسقي الزرع")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // The dock starts with the F-045 hint; the title sits wholly above it, without scrolling.
        val dockTop = box("أجّلته 3 مرات. اختر وقتًا أنسب؟").top
        val queue = box("1 من 2")
        assertTrue("title $title below the top row $queue", title.top >= queue.bottom)
        assertTrue("title $title fully above the dock ($dockTop)", title.bottom <= dockTop)
        assertTrue("title readable: ${title.height / density} dp", title.height / density >= 40f)
        // The dock rules stay: «تم» fully on screen and above «أجّل 10 دقائق».
        val done = box("تم")
        assertTrue("Done $done inside $root", done.top >= root.top && done.bottom <= root.bottom)
        assertTrue("Done above Snooze", done.bottom <= box("أجّل 10 دقائق").top + 1)
    }

    @Test fun atDoubleTextTheTitleIsReadableWithoutScrolling() = alertAtDoubleText("light")

    @Test fun atDoubleTextTheTitleIsReadableInDarkModeToo() = alertAtDoubleText("dark")

    @Test fun atNormalTextTheTitleStaysUnderTheTime() {
        val t = Task(id = "t1", title = "دواء الضغط", body = "", dueAt = now.minusSeconds(30), scheduleAt = now.minusSeconds(30),
            createdAt = now.minusSeconds(7200), updatedAt = now.minusSeconds(7200))
        show { AlertScreen(task = t, position = 1, count = 1, presets = SnoozePreset.DEFAULTS, now = now,
            onDone = {}, onSkip = {}, onSnooze = {}, onEdit = {}, onLater = {}, guardMs = 0) }
        assertTrue(box("دواء الضغط").top >= box("حان الوقت").bottom)
    }

    // ---- U2-5: «تم» / «إلغاء» pinned at the bottom of the sheets at 200 % ----

    /** The button [text] lies wholly inside the window, without scrolling, and is at least 48 dp tall. */
    private fun assertOnScreen(text: String) {
        val b = rule.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val height = app.resources.displayMetrics.heightPixels
        assertTrue("«$text» $b fully on screen (height $height)", b.top >= 0f && b.bottom <= height && b.height / density >= 47.5f)
    }

    @Test fun alertStyleSheetButtonsStayVisibleAtDoubleText() {
        var result: Pair<String, String?>? = null
        var closed = false
        show(2f) { AlertStyleSheet("normal", null, "always", { s, f -> result = s to f }, {}, { closed = true }) }
        rule.onNodeWithText("منبّه مهم").assertExists()
        assertOnScreen("تم")
        assertOnScreen("إلغاء")
        // The choices scroll above the pinned pair.
        rule.onNodeWithText("إشعار فقط").performScrollTo().performClick()
        rule.onNodeWithText("منبّه مهم").performScrollTo().performClick()
        assertOnScreen("تم")
        rule.onNodeWithText("تم").performClick()
        assertEquals("alarm" to "off", result)
        assertTrue(closed)
    }

    @Test fun toneSheetButtonsStayVisibleAtDoubleText() {
        var picked: ToneRef? = null
        show(2f) {
            TonePickerSheet(current = ToneRef("bundled", "chime-1"), style = AlertPolicy.Style.NORMAL, onPick = { picked = it }, onDismiss = {})
        }
        assertOnScreen("تم")
        assertOnScreen("إلغاء")
        rule.onNode(hasText("صامت (اهتزاز فقط)") and hasClickAction()).performScrollTo().performClick()
        assertOnScreen("تم")
        rule.onNodeWithText("تم").performClick()
        assertEquals(ToneRef.SILENT, picked)
    }

    // ---- U2-10: «منبّه مهم» says how long it rings ----

    @Test fun theImportantAlarmTextTellsTheRingLength() {
        show { AlertStyleSheet("alarm", null, "always", { _, _ -> }, {}, {}) }
        rule.onNodeWithText("يرنّ بصوت المنبّه حتى والهاتف صامت، لمدة تختارها في الإعدادات (دقيقة)، ولا يتجاوز «عدم الإزعاج» إلا إذا سمحت له.",
            useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("حتى تتعامل معه", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theImportantAlarmTextFollowsTheSetting() {
        Repo.prefs(app).ringSeconds = 30
        show { AlertStyleSheet("alarm", null, "always", { _, _ -> }, {}, {}) }
        rule.onNodeWithText("(30 ثانية)", substring = true, useUnmergedTree = true).assertExists()
    }

    // ---- U2-6: ticking a step ----

    private var saved: TaskInput? = null

    private fun editorWithSteps(fontScale: Float = 1f) {
        val zone = ZoneId.of("Asia/Baghdad")
        val now = at(zone, 2026, 11, 14, 10)
        val steps = listOf(Organize.Step("a", "البطاقة", false), Organize.Step("b", "الهوية", false))
        val task = Tasks.save(AppState(), TaskInput(title = "شنطة السفر", dueAt = at(zone, 2026, 11, 16, 17), steps = steps), now, zone).second
        show(fontScale) {
            EditScreen(task = task, weekStart = 6, now = now, onSave = { saved = it; null }, onDelete = {}, onBack = {},
                settings = EditorSettings(), onDndHelp = {}, onHistory = {}, onDuplicate = {}, onSaveAsTemplate = {})
        }
    }

    @Test fun tappingAStepsTextTicksItAndEditingIsItsOwnButton() {
        editorWithSteps()
        rule.onNodeWithText("0 من 2").performScrollTo()
        // The step's text is not a text field: a tap on it ticks the step.
        rule.onAllNodes(hasSetTextAction() and hasText("البطاقة")).assertCountEquals(0)
        rule.onNodeWithText("البطاقة", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("1 من 2").assertExists()
        // The tick target (checkbox + text) is at least 48 dp each way.
        val tick = rule.onNode(hasContentDescription("الهوية") and hasClickAction()).fetchSemanticsNode()
        assertTrue("tick target ${tick.size}", tick.size.width / density >= 48f && tick.size.height / density >= 48f)
        assertEquals("غير منجزة", tick.config.getOrNull(SemanticsProperties.StateDescription))
        // Editing is a separate, labelled button; the text then becomes editable.
        rule.onNodeWithContentDescription("عدّل الخطوة: الهوية").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("الهوية")).performTextReplacement("الهوية الوطنية")
        rule.onNodeWithContentDescription("أنهِ تعديل الخطوة").performClick()
        rule.onAllNodes(hasSetTextAction() and hasText("الهوية", substring = true)).assertCountEquals(0)
        // Reorder and delete stay TalkBack actions of the row.
        val actions = rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
            .fetchSemanticsNodes().flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label } }
        assertTrue(actions.toString(), "انقل لأسفل" in actions && "انقل لأعلى" in actions && "احذف الخطوة" in actions)
        rule.onNodeWithText("حفظ").performClick()
        val steps = saved!!.steps!!
        assertEquals(listOf("البطاقة" to true, "الهوية الوطنية" to false), steps.map { it.text to it.done })
    }

    @Test fun aNewStepOpensReadyToTypeAndTicksAtDoubleText() {
        editorWithSteps(2f)
        rule.onNodeWithText("أضف خطوة").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and isFocused()).performTextInput("النظارة")
        rule.onNodeWithText("الهوية", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("1 من 3").assertExists()
        for (n in rule.onAllNodes(hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).fetchSemanticsNodes()) {
            if (n.config.getOrNull(SemanticsProperties.Role) != androidx.compose.ui.semantics.Role.Checkbox) continue
            assertTrue("tick target ${n.size}", n.size.width / density >= 47.5f && n.size.height / density >= 47.5f)
        }
        rule.onNodeWithText("حفظ").performClick()
        assertEquals(listOf("البطاقة", "الهوية", "النظارة"), saved!!.steps!!.map { it.text })
    }

    // ---- U2-1: the summary card says when the sound will not be heard ----

    private val ready = Health(notifications = true, channel = true, exact = true, fullScreen = true, battery = true, dnd = false,
        alarmVolume = 5, alarmVolumeMax = 7, notifVolume = 5, notifVolumeMax = 7, checkedAt = now.toEpochMilli())
    private val schedule = ScheduleStatus(null, null, 3, 0)

    private fun health(h: Health) = show {
        HealthScreen(h, schedule, firstRun = false, now = now, onCheck = {}, onTest = {}, onReport = {}, onAlertLog = {}, onDndHelp = {},
            onFix = {}, onContinue = {}, onBack = {}, manufacturer = "google", sdk = 36)
    }

    @Test fun theGreenCardSaysWhenTheVolumeIsAtZero() {
        health(ready.copy(alarmVolume = 0, notifVolume = 0))
        rule.onNodeWithText("كل شيء ضروري جاهز").assertExists()
        rule.onNodeWithText("صوت الإشعارات على الصفر: لن تسمع التذكير العادي.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("صوت المنبّه على الصفر: لن تسمع المنبّه المهم.", useUnmergedTree = true).assertExists()
        assertEquals(2, rule.onAllNodes(hasTestTag(SOUND_WARNING_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        // The line sits in the card, under the title and above «افحص الآن».
        assertTrue(box("صوت الإشعارات على الصفر: لن تسمع التذكير العادي.").top >= box("كل شيء ضروري جاهز").bottom)
        assertTrue(box("صوت المنبّه على الصفر: لن تسمع المنبّه المهم.").bottom <= box("افحص الآن").top)
    }

    @Test fun theGreenCardSaysWhenTheAlarmChannelIsMuted() {
        health(ready.copy(alarmChannelSound = false))
        rule.onNodeWithText("كل شيء ضروري جاهز").assertExists()
        rule.onNodeWithText("قناة «منبّهات مهمة» مكتومة: المنبّه المهم لن يرنّ.", useUnmergedTree = true).assertExists()
    }

    @Test fun noWarningWhenTheSoundIsOn() {
        health(ready)
        rule.onNodeWithText("كل شيء ضروري جاهز").assertExists()
        rule.onAllNodes(hasTestTag(SOUND_WARNING_TAG), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(emptyList<String>(), HealthModel.soundWarnings(ready))
        assertEquals(listOf("notif_volume", "alarm_volume", "channel", "alarm_channel"),
            HealthModel.soundWarnings(ready.copy(notifVolume = 0, alarmVolume = 0, channelSound = false, alarmChannelSound = false)))
        // Unknown volumes are not reported as zero.
        assertEquals(emptyList<String>(), HealthModel.soundWarnings(ready.copy(notifVolume = null, alarmVolume = null)))
    }

    // ---- U2-4: after «لا» to «هل سمعت الصوت؟» ----

    private val t0 = now.toEpochMilli() - 60_000
    private fun heardNo(style: AlertPolicy.Style = AlertPolicy.Style.NORMAL, quiet: String = AlertPolicy.Quiet.NONE.name) = TestAlarm.Record(
        startedAt = t0, plannedAt = t0 + 10_000, api = "CLOCK", firedAt = t0 + 10_500, posted = true, fullScreenAllowed = true,
        screenAt = t0 + 10_600, sound = false, vibration = true, style = style.name, quiet = quiet, playback = true,
    )

    private fun alertTest(rec: TestAlarm.Record, h: Health, fixes: MutableList<String>) = show {
        AlarmTestScreen(rec, TestResults.of(rec, h, now.toEpochMilli()), now.toEpochMilli(), onStart = { _, _ -> }, onAnswer = { _, _ -> },
            onFix = { fixes += it }, onCancel = {}, onDone = {}, onBack = {}, health = h)
    }

    @Test fun notHeardWithTheVolumeAtZeroSaysSoAndOpensSoundSettings() {
        val fixes = mutableListOf<String>()
        alertTest(heardNo(), ready.copy(notifVolume = 0), fixes)
        rule.onNodeWithText("أجبتَ: لا", useUnmergedTree = true).performScrollTo().assertExists()
        rule.onNodeWithText("صوت الإشعارات على الصفر. ارفعه بزر الصوت أو من إعدادات الصوت.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("افتح الإعدادات").performScrollTo().performClick()
        assertEquals(listOf("sound"), fixes)
    }

    @Test fun notHeardWithTheAlarmChannelMutedOpensTheChannel() {
        val fixes = mutableListOf<String>()
        val h = ready.copy(alarmChannelSound = false)
        alertTest(heardNo(AlertPolicy.Style.ALARM), h, fixes)
        rule.onNodeWithText("قناة «منبّهات مهمة» مكتومة في إعدادات أندرويد. افتحها وشغّل الصوت.", useUnmergedTree = true).performScrollTo().assertExists()
        rule.onNodeWithText("افتح الإعدادات").performScrollTo().performClick()
        assertEquals(listOf("alarm_channel"), fixes)
    }

    @Test fun notHeardInDoNotDisturbOpensTheDndHelpWithoutRepeatingItself() {
        val fixes = mutableListOf<String>()
        alertTest(heardNo(quiet = AlertPolicy.Quiet.DND.name), ready.copy(dndState = AlertPolicy.Dnd.NONE), fixes)
        rule.onNodeWithText("عدم الإزعاج مفعّل: التذكير العادي يبقى صامتًا.", useUnmergedTree = true).performScrollTo().assertExists()
        rule.onAllNodesWithText("«عدم الإزعاج» مفعّل ويُسكت الصوت. المنبّه المهم يرنّ أثناءه إن سمحت له.", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("افتح الإعدادات").performScrollTo().performClick()
        assertEquals(listOf("dnd_help"), fixes)
    }

    @Test fun notHeardWithNoKnownCauseSaysWhatToCheck() {
        val fixes = mutableListOf<String>()
        alertTest(heardNo(), ready, fixes)
        rule.onNodeWithText("لم يجد ذكرني سببًا في الإعدادات التي يقرؤها. تحقّق من مستوى الصوت، ومن سماعة بلوتوث متصلة.", useUnmergedTree = true)
            .performScrollTo().assertExists()
        rule.onNodeWithText("افتح الإعدادات").performScrollTo().performClick()
        assertEquals(listOf("sound"), fixes)
    }

    @Test fun theCausesComeInOrder() {
        val h = ready.copy(notifVolume = 0, ringer = AlertPolicy.Ringer.SILENT, dndState = AlertPolicy.Dnd.NONE, channelSound = false)
        val normal = soundCauses(heardNo(quiet = ""), h)
        assertEquals(listOf(R.string.bbfix_hint_notif_zero, R.string.bbfix_hint_ringer, R.string.bbfix_hint_dnd, R.string.bbfix_hint_channel_normal),
            normal.map { it.hint })
        assertEquals(listOf("sound", "sound", "dnd_help", "channel"), normal.map { it.target })
        // An important alarm rings on a silent phone: no ringer hint; its own volume and channel.
        val alarm = soundCauses(heardNo(AlertPolicy.Style.ALARM, quiet = ""), h.copy(alarmVolume = 0, dndState = AlertPolicy.Dnd.OFF, alarmChannelSound = false))
        assertEquals(listOf(R.string.bbfix_hint_alarm_zero, R.string.bbfix_hint_channel_alarm), alarm.map { it.hint })
        // Without Health, only what the test recorded.
        assertEquals(listOf(R.string.bbfix_hint_ringer), soundCauses(heardNo(quiet = AlertPolicy.Quiet.RINGER_VIBRATE.name), null).map { it.hint })
        assertFalse(soundCauses(heardNo(), null).isNotEmpty())
        assertNotNull(soundCauses(heardNo(quiet = AlertPolicy.Quiet.CHANNEL_MUTED.name), null).single().quiet)
    }
}
