package com.fastkiwi.rememberme

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.data.ScheduleLog
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.CompatReport
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.HealthScreen
import com.fastkiwi.rememberme.ui.ScheduleStatus
import com.fastkiwi.rememberme.ui.health.AlarmTestScreen
import com.fastkiwi.rememberme.ui.health.AlertLogScreen
import com.fastkiwi.rememberme.ui.health.CompatReportScreen
import com.fastkiwi.rememberme.ui.health.DndHelpScreen
import com.fastkiwi.rememberme.ui.health.HealthModel
import com.fastkiwi.rememberme.ui.health.HealthStatus
import com.fastkiwi.rememberme.ui.health.START_TAG
import com.fastkiwi.rememberme.ui.health.StartChoices
import com.fastkiwi.rememberme.ui.health.TestResults
import com.fastkiwi.rememberme.ui.health.healthRowTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/** DESIGN-SPEC §7.4 test hooks for Health, the alert test, DND help, the compatibility report and the alert log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class HealthUiTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = Instant.now()

    @Before fun setUp() {
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("alarm_test", 0).edit().clear().commit()
        app.getSharedPreferences("alert_log", 0).edit().clear().commit()
    }

    private fun show(content: @Composable () -> Unit) = rule.setContent { AppTheme("light") { content() } }

    /** One essential missing, everything the app cannot read unknown, a maker with steps. */
    private val mixed = Health(
        notifications = true, channel = false, exact = true, fullScreen = false, battery = false, dnd = true,
        ringer = AlertPolicy.Ringer.SILENT, dndState = null, alarmVolume = 0, alarmVolumeMax = 7, notifVolume = null,
        lockScreenHidesAll = null, toneProblems = 2, alarmChannelSound = false, checkedAt = now.toEpochMilli(),
    )
    private val schedule = ScheduleStatus(null, null, 12, 1, listOf(4_000L))

    private fun healthScreen(h: Health, fixes: MutableList<String> = mutableListOf(), maker: String = "samsung") = show {
        HealthScreen(h, schedule, firstRun = false, now = now, onCheck = {}, onTest = {}, onReport = {}, onAlertLog = {}, onDndHelp = { fixes += "dnd_help" },
            onFix = { fixes += it }, onContinue = {}, onBack = {}, manufacturer = maker, sdk = 34)
    }

    @Test fun healthShowsTheSummaryGroupsAndTheTestButton() {
        healthScreen(mixed)
        rule.onAllNodesWithText("صحة التذكيرات").assertCountEquals(1)
        rule.onNodeWithText("إذن ضروري واحد غير مفعّل").assertExists()
        rule.onNodeWithText("كل شيء ضروري جاهز").assertDoesNotExist()
        rule.onNodeWithText("افحص الآن").assertExists()
        rule.onNodeWithText("اختبر التنبيه الآن").assertExists()
        for (g in listOf("ضروري", "يُنصح به", "الهاتف الآن", "الجدولة")) rule.onNodeWithText(g).assertExists()
        assertTrue(rule.onAllNodesWithText("الشركة المصنعة", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // The phone now: the honest silent-phone sentence; the scheduling key/values.
        rule.onNodeWithText("الهاتف صامت: التذكير العادي سيهتز فقط. المنبّه المهم يرنّ.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("مجدولة: 12", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("تحتاج معالجة: 1", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("آخر تأخير مُقاس: 4 ثوانٍ", useUnmergedTree = true).assertExists()
        // F-013: every maker step says it was not tried on a phone yet.
        assertEquals(2, rule.onAllNodesWithText("لم يُجرَّب على هاتف بعد", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("جُرِّب على هاتف", useUnmergedTree = true).assertDoesNotExist()
        // ⋮ holds the report and the alert log.
        rule.onNodeWithContentDescription("خيارات أخرى").performClick()
        rule.onNodeWithText("تقرير التوافق").assertExists()
        rule.onNodeWithText("سجل التنبيهات").assertExists()
    }

    /** §7.4: every not-ready row has «أندرويد» or «الشركة المصنعة» and exactly one button; never «جاهز» for an unknown value. */
    @Test fun everyNotReadyRowHasASourceAndExactlyOneButton() {
        val fixes = mutableListOf<String>()
        healthScreen(mixed, fixes)
        val items = HealthModel.items(mixed, schedule, now.toEpochMilli(), "samsung", 34)
        assertTrue(items.count { it.status.notReady } >= 8)
        for (item in items) {
            val inRow = hasAnyAncestor(hasTestTag(healthRowTag(item.key)))
            val buttons = rule.onAllNodes(inRow and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size
            if (item.status.notReady) {
                assertEquals(item.key, 1, buttons)
                val src = rule.onAllNodes(inRow and (hasText("أندرويد") or hasText("الشركة المصنعة")), useUnmergedTree = true).fetchSemanticsNodes()
                assertEquals(item.key, 1, src.size)
            } else if (item.status == HealthStatus.READY) assertEquals(item.key, 0, buttons)
            if (item.status == HealthStatus.UNKNOWN) {
                rule.onAllNodes(inRow and hasText("جاهز"), useUnmergedTree = true).assertCountEquals(0)
                assertEquals(item.key, 1, rule.onAllNodes(inRow and hasText("غير معروف"), useUnmergedTree = true).fetchSemanticsNodes().size)
            }
        }
        // The essential row's «إصلاح» opens its setting.
        rule.onNodeWithText("إصلاح").performScrollTo().performClick()
        assertEquals(listOf("channel"), fixes)
    }

    @Test fun allEssentialsReadySaysSoAndUnknownStillShowsUnknown() {
        healthScreen(mixed.copy(channel = true))
        rule.onNodeWithText("كل شيء ضروري جاهز").assertExists()
        rule.onNodeWithText("إذن ضروري واحد غير مفعّل").assertDoesNotExist()
        assertTrue(rule.onAllNodesWithText("غير معروف", useUnmergedTree = true).fetchSemanticsNodes().size >= 3)
    }

    @Test fun healthTouchTargetsAndArabicText() {
        healthScreen(mixed)
        assertTouchTargets()
        assertArabicText()
    }

    private fun assertTouchTargets() {
        val density = app.resources.displayMetrics.density
        for (n in rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val w = n.size.width / density
            val h = n.size.height / density
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            assertTrue("Touch target \"$label\" is ${w}x$h dp", w >= 47.5f && h >= 47.5f)
        }
    }

    /** No «·» in Arabic and no Arabic-Indic digits anywhere. */
    private fun assertArabicText() {
        val texts = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertTrue(texts.isNotEmpty())
        for (t in texts) {
            assertFalse(t, t.contains("·"))
            assertFalse(t, t.any { it in '٠'..'٩' || it in '۰'..'۹' })
        }
    }

    // ---- Alert test ----

    private val t0 = now.toEpochMilli() - 60_000
    private val fired = TestAlarm.Record(startedAt = t0, plannedAt = t0 + 10_000, api = "CLOCK", firedAt = t0 + 11_000, posted = true,
        fullScreenAllowed = false, quiet = AlertPolicy.Quiet.RINGER_SILENT.name, vibrate = false, playback = null)
    private val ready = Health(notifications = true, channel = true, exact = true, fullScreen = true, battery = true, dnd = false)

    @Test fun alertTestResultsShowTheSixPartsAndAskHonestly() {
        val answers = mutableListOf<Pair<TestAlarm.Part, Boolean>>()
        val fixes = mutableListOf<String>()
        val results = TestResults.of(fired, ready, now.toEpochMilli())
        show {
            AlarmTestScreen(fired, results, now.toEpochMilli(), onStart = { _, _ -> }, onAnswer = { p, y -> answers += p to y },
                onFix = { fixes += it }, onCancel = {}, onDone = {}, onBack = {})
        }
        rule.onNodeWithText("انتهى الاختبار", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("الأجزاء الستة").assertExists()
        for (p in listOf("المنبّه في وقته", "الإشعار", "الصوت", "الاهتزاز", "الشاشة الكاملة", "الأزرار")) rule.onNodeWithText(p, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("هل سمعت الصوت؟", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("الاختبار لا يحفظ أي تذكير.").assertExists()
        // Honest texts: the silent phone, the measured lateness in seconds, the full screen Android refused.
        rule.onNodeWithText("الهاتف صامت: التذكير العادي سيهتز فقط.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("وصل بعد 10 ثوانٍ، متأخرًا ثانية واحدة", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("أندرويد لم يسمح بعرضها فوق شاشة القفل", useUnmergedTree = true).assertExists()
        // Never a single success: the summary counts what failed and what waits.
        rule.onNodeWithText("2 نجحا، 1 لم ينجح، 3 تنتظر جوابك", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("اختبرتَ: تذكير عادي، بعد 10 ثوانٍ", useUnmergedTree = true).assertExists()
        // Answers and the fix.
        rule.onAllNodesWithText("نعم")[0].performScrollTo().performClick()
        assertEquals(TestAlarm.Part.SOUND to true, answers.single())
        rule.onNodeWithText("اسمح").performScrollTo().performClick()
        assertEquals(listOf("fullscreen"), fixes)
        rule.onNodeWithText("أعد الاختبار").assertExists()
        rule.onNodeWithText("تم").assertExists()
        assertTouchTargets()
        assertArabicText()
    }

    @Test fun aRunningTestCountsDown() {
        val rec = TestAlarm.Record(startedAt = now.toEpochMilli(), plannedAt = now.toEpochMilli() + 8_000, api = "CLOCK")
        var cancelled = false
        show {
            AlarmTestScreen(rec, TestResults.of(rec, ready, now.toEpochMilli()), now.toEpochMilli(), onStart = { _, _ -> }, onAnswer = { _, _ -> },
                onFix = {}, onCancel = { cancelled = true }, onDone = {}, onBack = {})
        }
        rule.onNodeWithText("سيصل تنبيه تجريبي بعد 8 ثوانٍ. اقفل الشاشة إن أردت اختبار الشاشة الكاملة.").assertExists()
        rule.onNodeWithText("الأجزاء الستة").assertDoesNotExist()
        rule.onNodeWithText("ألغِ الاختبار").performClick()
        assertTrue(cancelled)
    }

    @Test fun theStartSheetAsksForTheStyleAndTheDelay() {
        var started: Pair<AlertPolicy.Style, TestAlarm.Delay>? = null
        show {
            AlarmTestScreen(TestAlarm.Record(), emptyMap(), now.toEpochMilli(), onStart = { s, d -> started = s to d }, onAnswer = { _, _ -> },
                onFix = {}, onCancel = {}, onDone = {}, onBack = {})
        }
        rule.onNodeWithText("نوع التنبيه").assertExists()
        rule.onNodeWithText("متى").assertExists()
        rule.onNodeWithText("بعد 10 ثوانٍ").assertExists()
        rule.onNodeWithText("غدًا 7:00 ص").assertExists()
        rule.onNodeWithText("منبّه مهم").performClick()
        rule.onNodeWithText("بعد ساعة").performClick()
        rule.onNode(hasTestTag(START_TAG)).performClick()
        rule.waitForIdle()
        assertEquals(AlertPolicy.Style.ALARM to TestAlarm.Delay.HOUR_1, started)
    }

    @Test fun startChoicesAlone() {
        var go = false
        show { StartChoices(AlertPolicy.Style.NORMAL, {}, TestAlarm.Delay.TOMORROW_7, {}, onStart = { go = true }) }
        rule.onNodeWithText("ابدأ الاختبار").performScrollTo().performClick()
        assertTrue(go)
        assertTouchTargets()
    }

    // ---- DND help, report, alert log ----

    @Test fun dndHelpExplainsAndOpensTheSettings() {
        var opened = false
        show { DndHelpScreen(AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED, false, onBack = {}, onOpen = { opened = true }) }
        rule.onNodeWithText("المنبّه المهم وعدم الإزعاج").assertExists()
        rule.onNodeWithText("عدم الإزعاج الآن: مفعّل", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ذكرني: غير مسموح له", useUnmergedTree = true).assertExists()
        for (s in listOf("افتح إعدادات «عدم الإزعاج»", "في «التطبيقات» أو «الاستثناءات» أضف «ذكرني»", "تأكّد أن «المنبّهات» مسموحة")) rule.onNodeWithText(s).assertExists()
        rule.onNodeWithText("تختلف الأسماء حسب الشركة المصنعة للهاتف.").assertExists()
        rule.onNodeWithText("افتح إعدادات عدم الإزعاج").performClick()
        assertTrue(opened)
        assertTouchTargets()
    }

    @Test fun dndHelpSaysUnknownWhenItCannotRead() {
        show { DndHelpScreen(null, null, onBack = {}) }
        rule.onNodeWithText("عدم الإزعاج الآن: غير معروف", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ذكرني: غير معروف", useUnmergedTree = true).assertExists()
    }

    /** F-028 acceptance: the report holds no title, note, id or time zone; the screen shows it as it is. */
    @Test fun theReportHasNoPersonalDataAndIsShownExactly() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Baghdad"))
        val zone = ZoneId.of("Asia/Baghdad")
        var id = ""
        Repo.store(app).change {
            val (s, t) = Tasks.save(AppState(), TaskInput(title = "موعد سري", body = "ملاحظة خاصة جدًا", dueAt = now.plusSeconds(3600)), now, zone)
            id = t.id
            s
        }
        val h = Health.check(app)
        val rec = TestAlarm.Record(startedAt = t0, plannedAt = t0 + 10_000, api = "CLOCK", firedAt = t0 + 11_000, posted = true, fullScreenAllowed = true)
        val text = CompatReport.build(app, h, ScheduleStatus(ScheduleLog.Entry(now.toEpochMilli(), "check", 0, "", "NONE", 0, 1, true), null, 1, 0),
            rec, TestResults.of(rec, h, now.toEpochMilli()))
        for (secret in listOf("موعد سري", "ملاحظة خاصة", id, "Asia/Baghdad", "Baghdad", zone.id)) assertFalse("$secret in\n$text", text.contains(secret))
        assertTrue(text.startsWith("compat-report@1"))
        assertTrue(text.contains("Alarm test: CLOCK, normal, seconds_10, delay 1 s"))
        show { CompatReportScreen(text, onBack = {}) }
        rule.onNodeWithText("تقرير التوافق").assertExists()
        rule.onNodeWithText(text).assertExists()
        rule.onNodeWithText("ما لا يتضمنه التقرير:").assertExists()
        for (s in listOf("العناوين والملاحظات والأوقات والقوائم", "معرّفات الهاتف والحسابات", "الموقع والمنطقة الزمنية")) rule.onNodeWithText(s).assertExists()
        rule.onNodeWithText("نسخ").assertExists()
        rule.onNodeWithText("مشاركة").assertExists()
    }

    @Test fun theAlertLogListsEventsWithoutContentAndClears() {
        val at = now.toEpochMilli()
        val entries = listOf(
            AlertLog.Entry(at - 1_000, AlertLog.Event.DONE, "a1b2c3d4", -1, "", ""),
            AlertLog.Entry(at - 5_000, AlertLog.Event.POSTED, "a1b2c3d4", 4_000, "ALARM", ""),
            AlertLog.Entry(at - 9_000, AlertLog.Event.QUIET, TestAlarm.LOG_KEY, 0, "NORMAL", "RINGER_SILENT"),
        )
        var cleared = false
        show { AlertLogScreen(entries, onClear = { cleared = true }, onBack = {}, now = now) }
        rule.onNodeWithText("سجل التنبيهات").assertExists()
        rule.onNodeWithText("ضغطتَ «تم»", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("منبّه مهم، متأخر 4 ثوانٍ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("تنبيه تجريبي، تذكير عادي، الهاتف صامت", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("a1b2c3d4", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("خيارات أخرى").performClick()
        rule.onNodeWithText("مسح السجل").performClick()
        rule.onNodeWithText("مسح سجل التنبيهات؟").assertExists()
        rule.onAllNodesWithText("مسح السجل").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertTrue(cleared)
    }

    @Test fun anEmptyAlertLogSaysSo() {
        show { AlertLogScreen(emptyList(), onClear = {}, onBack = {}) }
        rule.onNodeWithText("لا أحداث بعد.").assertExists()
        rule.onNodeWithContentDescription("خيارات أخرى").assertDoesNotExist()
    }
}
