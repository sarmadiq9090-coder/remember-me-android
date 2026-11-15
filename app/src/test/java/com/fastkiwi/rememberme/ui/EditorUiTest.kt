package com.fastkiwi.rememberme.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.at
import com.fastkiwi.rememberme.core.prayer.Method
import com.fastkiwi.rememberme.data.EditorSettings
import com.fastkiwi.rememberme.data.History
import com.fastkiwi.rememberme.ui.editor.AlertStyleSheet
import com.fastkiwi.rememberme.ui.editor.HistoryScreen
import com.fastkiwi.rememberme.ui.editor.PrayerTimeSheet
import com.fastkiwi.rememberme.ui.editor.TonePickerSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** DESIGN-SPEC §7.4 row «Editor» and the editor's sheets (§5.5–5.7, §5.18, §5.19, §5.24). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class EditorUiTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)
    private val due = at(zone, 2026, 11, 16, 10, 30)
    private var saved: TaskInput? = null

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        app.getSharedPreferences("device", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("filters", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        saved = null
    }

    private fun editor(task: Task? = null, settings: EditorSettings = EditorSettings(), dnd: () -> Unit = {}) {
        rule.setContent {
            AppTheme("light") {
                EditScreen(task = task, weekStart = 6, now = now, onSave = { saved = it; null }, onDelete = {}, onBack = {},
                    settings = settings, onDndHelp = dnd, onHistory = {}, onDuplicate = {}, onSaveAsTemplate = {})
            }
        }
    }

    private fun task(input: TaskInput): Task = Tasks.save(AppState(), input, now, zone).second

    private fun dialogChoices() = rule.onAllNodes(isSelectable() and hasAnyAncestor(isDialog()))

    @Test fun oneSaveAndTheAlertOptionsFoldedByDefault() {
        editor()
        rule.onNodeWithText("العنوان").assertExists()
        rule.onAllNodesWithText("حفظ").assertCountEquals(1)
        val options = rule.onNode(hasText("خيارات التنبيه") and hasClickAction())
        options.performScrollTo()
        rule.onNode(hasText("خيارات التنبيه") and hasStateDescription("مطويّة")).assertExists()
        // The summary of the current values; the rows are hidden.
        rule.onNodeWithText("تذكير عادي، النغمة الافتراضية، الشاشة الكاملة حسب الإعداد").assertExists()
        rule.onNodeWithText("نوع التنبيه").assertDoesNotExist()
        options.performClick()
        rule.onNode(hasText("خيارات التنبيه") and hasStateDescription("ظاهرة")).assertExists()
        for (row in listOf("نوع التنبيه", "النغمة", "الاهتزاز", "الشاشة الكاملة", "تثبيت التذكير على شاشة القفل", "اقرأ العنوان بصوت", "على شاشة القفل"))
            rule.onNodeWithText(row).performScrollTo().assertExists()
        assertTouchTargets()
    }

    @Test fun addAlertIsGoneAfterThreeEarlyAlertsAndTheNearestGoesToRemindBefore() {
        editor()
        rule.onNodeWithText("العنوان").performTextInput("موعد")
        rule.onNodeWithText("عند الموعد").performScrollTo().assertExists()
        repeat(3) {
            rule.onNodeWithText("إضافة تنبيه").performScrollTo().performClick()
            dialogChoices().onFirst().performClick()
        }
        rule.onNodeWithText("إضافة تنبيه").assertDoesNotExist()
        rule.onNodeWithText("قبل يوم").performScrollTo().assertExists()
        rule.onNodeWithText("حفظ").performClick()
        val input = saved!!
        assertEquals(listOf(1440, 60, 30), input.alerts)
        assertEquals(30, input.remindBefore)
        // Removing one brings the add row back.
        rule.onNodeWithText("قبل يوم").performScrollTo().performClick()
        rule.onNodeWithText("حذف التنبيه").performClick()
        rule.onNodeWithText("إضافة تنبيه").performScrollTo().assertExists()
    }

    @Test fun anAlertTurnedOffIsNotSaved() {
        editor(task(TaskInput(title = "r", dueAt = due, alerts = listOf(1440, 60))))
        rule.onNodeWithContentDescription("قبل ساعة").performScrollTo().performClick()
        rule.onNodeWithText("حفظ").performClick()
        assertEquals(listOf(1440), saved!!.alerts)
    }

    @Test fun theOptionsOpenByThemselvesWhenSomethingDiffersFromTheDefaults() {
        editor(task(TaskInput(title = "r", dueAt = due, ext = AlertExt(tone = ToneRef.SILENT))))
        rule.onNode(hasText("خيارات التنبيه") and hasStateDescription("ظاهرة")).performScrollTo().assertExists()
        rule.onNodeWithText("صامت (اهتزاز فقط)").performScrollTo().assertExists()
    }

    @Test fun theToneSheetHasEverySourceAndAppliesOnDone() {
        editor()
        rule.onNode(hasText("خيارات التنبيه") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("النغمة").performScrollTo().performClick()
        rule.onNodeWithText("لهذا التذكير").assertExists()
        for (row in listOf("النغمة الافتراضية", "نغمات ذكرني", "من نغمات الهاتف…", "من ملفاتي…", "صامت (اهتزاز فقط)"))
            rule.onNode(hasText(row) and isSelectable()).assertExists()
        rule.onNodeWithText("في «تذكير عادي» يتبع الصوت مستوى صوت الإشعارات؛ إن كان الهاتف صامتًا سيهتز فقط.").assertExists()
        rule.onNode(hasText("صامت (اهتزاز فقط)") and isSelectable()).performClick()
        rule.onNodeWithText("تم").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("حفظ").performClick()
        assertEquals("silent", saved!!.ext?.tone?.kind)
    }

    @Test fun theToneSheetOnItsOwnForSettings() {
        var picked: ToneRef? = ToneRef.SILENT
        var closed = false
        rule.setContent {
            AppTheme("light") {
                TonePickerSheet(current = null, style = AlertPolicy.Style.ALARM, onPick = { picked = it }, onDismiss = { closed = true },
                    title = "نغمة التذكيرات المهمة", subtitle = null)
            }
        }
        rule.onNodeWithText("نغمة التذكيرات المهمة").assertExists()
        rule.onNodeWithText("في «منبّه مهم» يتبع الصوت مستوى صوت المنبّه، حتى والهاتف صامت.").assertExists()
        // Built-in tones open as a nested list with previews.
        rule.onNodeWithText("نغمات ذكرني").performClick()
        rule.onNodeWithText("نغمة ذكرني 2").performClick()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("استمع")).fetchSemanticsNodes().let { assertTrue(it.size >= 4) }
        rule.onNodeWithText("تم").performClick()
        assertEquals(ToneRef("bundled", "chime-2"), picked)
        assertTrue(closed)
    }

    @Test fun theAlertStyleSheetSetsTheStyleAndFullScreen() {
        var dnd = 0
        editor(dnd = { dnd++ })
        rule.onNode(hasText("خيارات التنبيه") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("نوع التنبيه").performScrollTo().performClick()
        rule.onNodeWithText("طريقة التنبيه").assertExists()
        rule.onNodeWithText("حسب الإعداد العام (للتنبيهات المهمة فقط)").assertExists()
        rule.onNodeWithText("السماح في «عدم الإزعاج»").performClick()
        assertEquals(1, dnd)
        rule.onNodeWithText("منبّه مهم").performClick()
        rule.onNodeWithText("إشعار فقط").performClick()
        rule.onNodeWithText("تم").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("يرنّ حتى والهاتف صامت").performScrollTo().assertExists()
        rule.onNodeWithText("حفظ").performClick()
        assertEquals("alarm", saved!!.ext?.style)
        assertEquals("off", saved!!.ext?.fullScreen)
    }

    @Test fun alertStyleSheetCardsAreRadioButtons() {
        var result: Pair<String, String?>? = null
        rule.setContent { AppTheme("light") { AlertStyleSheet("alarm", null, "important", { s, f -> result = s to f }, {}, {}) } }
        rule.onNodeWithText("حسب الإعداد العام (للتنبيهات المهمة فقط)").assertExists()
        // 1.0.5: no «اعرض الشاشة الكاملة» (the full screen is for important reminders only).
        rule.onNodeWithText("اعرض الشاشة الكاملة").assertDoesNotExist()
        rule.onNodeWithText("تذكير عادي").performClick()
        rule.onNodeWithText("إشعار فقط").performClick()
        rule.onNodeWithText("تم").performClick()
        assertEquals("normal" to "off", result)
    }

    @Test fun stepsWithProgressReorderAndTheAutoCompleteSwitch() {
        val steps = listOf(Organize.Step("a", "جواز السفر", true), Organize.Step("b", "تذكرة الطيران", false))
        editor(task(TaskInput(title = "سفر", dueAt = due, steps = steps)))
        rule.onNodeWithText("1 من 2").performScrollTo().assertExists()
        rule.onNodeWithText("أنجز التذكير عند إكمال كل الخطوات").performScrollTo().assertExists()
        // TalkBack can move a step.
        val second = rule.onNode(androidx.compose.ui.test.hasAnyDescendant(hasText("تذكرة الطيران")) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val labels = second.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertTrue(labels.toString(), "انقل لأعلى" in labels && "انقل لأسفل" !in labels)
        rule.runOnIdle { second.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "انقل لأعلى" }.action() }
        rule.onNodeWithText("أضف خطوة").performScrollTo().performClick()
        rule.onNodeWithText("1 من 3").assertExists()
        rule.onNodeWithText("حفظ").performClick()
        assertEquals(listOf("تذكرة الطيران", "جواز السفر"), saved!!.steps!!.map { it.text })
        // Ticking with the checkbox.
        rule.onNodeWithContentDescription("تذكرة الطيران").performScrollTo().performClick()
        rule.onNodeWithText("2 من 3").assertExists()
    }

    @Test fun hijriRepeatAndHijriLineWhenTheDisplayIsOn() {
        editor(settings = EditorSettings(hijriDisplay = true))
        rule.onNodeWithText("التكرار").performScrollTo().performClick()
        rule.onNodeWithText("كل سنة هجرية").performClick()
        rule.onNodeWithText("كل سنة هجرية", substring = true).assertExists()
        rule.onNodeWithText("العنوان").performTextInput("رمضان")
        rule.onNodeWithText("حفظ").performClick()
        assertNotNull(saved!!.ext?.hijri)
        assertEquals("yearly", saved!!.repeat.code)
    }

    @Test fun noDateHidesAlertsAndSavesWithoutTime() {
        editor()
        rule.onNodeWithText("العنوان").performTextInput("فكرة")
        rule.onNodeWithContentDescription("بلا موعد").performClick()
        rule.onNodeWithText("أضف موعدًا").assertExists()
        rule.onNodeWithText("التنبيهات").assertDoesNotExist()
        rule.onNodeWithText("حفظ").performClick()
        assertNull(saved!!.dueAt)
    }

    @Test fun theMenuHasHistoryOnlyWhenItIsOn() {
        editor(task(TaskInput(title = "r", dueAt = due)), settings = EditorSettings(historyOn = false))
        rule.onNodeWithContentDescription("خيارات أخرى").performClick()
        for (item in listOf("حفظ كقالب", "نسخ", "حذف")) rule.onNodeWithText(item).assertExists()
        rule.onNodeWithText("السجل").assertDoesNotExist()
    }

    @Test fun theMenuShowsHistoryWhenOn() {
        editor(task(TaskInput(title = "r", dueAt = due)), settings = EditorSettings(historyOn = true))
        rule.onNodeWithContentDescription("خيارات أخرى").performClick()
        rule.onNodeWithText("السجل").assertExists()
    }

    @Test fun historyScreenShowsDaysEventsAndTheFooter() {
        val events = listOf(
            History.Event("x", History.Kind.CREATED, now.minusSeconds(86_400 * 2)),
            History.Event("x", History.Kind.SNOOZED, now.minusSeconds(600), from = now.minusSeconds(600), to = now),
            History.Event("x", History.Kind.ALERT_ADDED, now.minusSeconds(300), minutes = 1440),
            History.Event("x", History.Kind.DONE, now),
        )
        rule.setContent { AppTheme("light") { HistoryScreen("دواء الضغط", events, now, zone) {} } }
        rule.onNodeWithText("السجل").assertExists()
        rule.onNodeWithText("دواء الضغط").assertExists()
        rule.onNodeWithText("اليوم").assertExists()
        rule.onNodeWithText("أُجّل 10 دقائق").assertExists()
        rule.onNodeWithText("أُضيف تنبيه: قبل يوم").assertExists()
        rule.onNodeWithText("أُنجز").assertExists()
        rule.onNodeWithText("أُنشئ").performScrollTo().assertExists()
        rule.onNodeWithText("السجل محفوظ على هاتفك فقط. يمكنك إيقافه أو مسحه من الإعدادات.").performScrollTo().assertExists()
    }

    @Test fun prayerSheetAsksForTheCityFirstAndAssumesNothing() {
        rule.setContent {
            AppTheme("light") {
                PrayerTimeSheet(EditorSettings(), LocalDate.of(2026, 11, 14), LocalTime.of(10, 0), null, true, now, zone, {}, {}, {}, { _, _, _ -> }, {})
            }
        }
        rule.onNodeWithText("اختر المدينة").assertExists()
        // Nothing is computed yet; after the city, the method is asked for.
        rule.onNodeWithText("بغداد").performClick()
        rule.onNodeWithText("طريقة الحساب").assertExists()
        rule.onNodeWithText("أم القرى").assertExists()
    }

    @Test fun prayerSheetPreviewsTheApproximateTime() {
        var result: Triple<String, Int, java.time.Instant>? = null
        val s = EditorSettings(prayerCity = "Baghdad", prayerMethod = Method.MWL.name)
        rule.setContent {
            AppTheme("light") {
                PrayerTimeSheet(s, LocalDate.of(2026, 11, 14), LocalTime.of(10, 0), null, true, now, zone, {}, {}, {}, { n, o, a -> result = Triple(n, o, a) }, {})
            }
        }
        rule.onNodeWithText("حسب الصلاة").assertExists()
        rule.onNodeWithText("المدينة: بغداد").assertExists()
        rule.onNodeWithText("الطريقة: رابطة العالم الإسلامي").assertExists()
        rule.onNodeWithText("المغرب").performClick()
        rule.onNodeWithText("(تقريبي)", substring = true).assertExists()
        rule.onNodeWithText("في بغداد", substring = true).assertExists()
        rule.onNodeWithText("تم").performClick()
        val r = result!!
        assertEquals("maghrib", r.first); assertEquals(15, r.second)
        assertEquals(LocalDate.of(2026, 11, 14), r.third.atZone(zone).toLocalDate())
    }

    @Test fun aPrayerTimeIsSavedAsADailyRepeatWithTheExtension() {
        editor(settings = EditorSettings(prayerCity = "Baghdad", prayerMethod = Method.MWL.name))
        rule.onNodeWithText("العنوان").performTextInput("أذكار")
        // With prayer times set up, the time chip opens the «الوقت» sheet.
        rule.onNode(hasContentDescription("الوقت", substring = true) and hasClickAction()).performClick()
        rule.onNodeWithText("حسب الصلاة").performClick()
        rule.onNodeWithText("العشاء").performClick()
        rule.onNodeWithText("تم").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("بعد العشاء بـ15 دقيقة").assertExists()
        rule.onNodeWithText("حفظ").performClick()
        val input = saved!!
        assertEquals("isha" to 15, input.ext?.prayer)
        assertEquals("daily", input.repeat.code)
        assertNotNull(input.dueAt)
    }

    private fun hasContentDescription(text: String, substring: Boolean = false) = androidx.compose.ui.test.hasContentDescription(text, substring = substring)

    /** Every clickable node is at least 48 × 48 dp. */
    private fun assertTouchTargets() {
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val w = n.size.width / density
            val h = n.size.height / density
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            assertTrue("Touch target \"$label\" is ${w}x$h dp", w >= 47.5f && h >= 47.5f)
        }
    }
}
