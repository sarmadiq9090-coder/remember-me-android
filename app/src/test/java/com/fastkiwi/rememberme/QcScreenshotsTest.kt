package com.fastkiwi.rememberme

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.at
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.withSteps
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.History
import com.fastkiwi.rememberme.ui.settings.SettingsFlows
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * The QC pass (DESIGN-SPEC, STANDARDS.md): every screen, sheet and dialog state in Arabic and English, light and
 * dark, at 200 % text (Arabic light and dark, English), and the tablet layout, written to screenshots/qc/. Sample data only. Each tour also checks that the screen's
 * primary action is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class QcScreenshotsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val outDir = File("../screenshots/qc").apply { mkdirs() }
    private var scenario: ActivityScenario<*>? = null

    @Before fun setUp() {
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Exact alarms off: the screenshots show the warning banner and the permissions screen's states.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
        History.file(app).delete()
        AlertLog(app).clear()
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private fun sample(en: Boolean) {
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        fun on(days: Int, h: Int, m: Int = 0) = at(zone, today.year, today.monthValue, today.dayOfMonth + days, h, m)
        fun t(ar: String, e: String) = if (en) e else ar
        Repo.store(app).change {
            var s: AppState = it
            fun add(i: TaskInput) { s = Tasks.save(s, i, now.minusSeconds(7200), zone).first }
            add(TaskInput(title = t("دواء الضغط", "Blood pressure pill"), body = t("حبة واحدة بعد الغداء مع كوب ماء.", "One after lunch with a glass of water."),
                dueAt = now.minusSeconds(2 * 3600), repeat = Repeat.DAILY, priority = Priority.HIGH))
            add(TaskInput(title = t("الاتصال بالكهربائي", "Call the electrician"), dueAt = now.minusSeconds(22 * 3600)))
            add(TaskInput(title = t("اجتماع الفريق", "Team meeting"), dueAt = now.plusSeconds(90 * 60), category = Category.WORK, remindBefore = 15))
            add(TaskInput(title = t("مراجعة درس الرياضيات", "Revise the maths lesson"), dueAt = now.plusSeconds(150 * 60), repeat = Repeat.DAYS,
                days = listOf(0, 2, 4), priority = Priority.MEDIUM, category = Category.STUDY))
            add(TaskInput(title = t("دفع الإيجار", "Pay the rent"), dueAt = on(1, 9), repeat = Repeat.MONTHLY, priority = Priority.HIGH))
            add(TaskInput(title = t("شراء هدية لأمي", "Buy a present for Mum"), dueAt = on(3, 17, 30)))
            add(TaskInput(title = t("قراءة كتاب", "Read a book")))
            val (s2, done) = Tasks.save(s, TaskInput(title = t("حجز موعد الطبيب", "Book the doctor"), dueAt = now.minusSeconds(26 * 3600)), now.minusSeconds(30 * 3600), zone)
            s = Tasks.complete(s2, done.id, now.minusSeconds(25 * 3600), zone)!!.first
            val (s3, gone) = Tasks.save(s, TaskInput(title = t("شراء بطاقة شحن", "Buy a top-up card")), now, zone)
            s = Tasks.trash(s3, gone.id, now.minusSeconds(3600))
            // A reminder with steps (G-05: the steps section of the editor).
            val (s4, trip) = Tasks.save(s, TaskInput(title = t("السفر إلى أربيل", "Trip to Erbil"), dueAt = on(5, 7)), now.minusSeconds(7200), zone)
            s4.copy(tasks = s4.tasks.map { if (it.id == trip.id) it.withSteps(listOf(
                Organize.Step("a", t("جواز السفر", "Passport"), true), Organize.Step("b", t("تذاكر الطيران", "Plane tickets"), false),
                Organize.Step("c", t("شاحن الهاتف", "Phone charger"), false))) else it })
        }
        // G-05: the change history and the alert log with events (no reminder content in the log).
        val s0 = Repo.state(app)
        val rentId = s0.tasks.first { it.repeat == Repeat.MONTHLY }.id
        Repo.prefs(app).historyOn = true
        History.observe(app, s0, s0.copy(tasks = s0.tasks.map {
            if (it.id == rentId) it.copy(dueAt = it.dueAt!!.plusSeconds(3600), updatedAt = now.minusSeconds(600)) else it }), now.minusSeconds(600))
        val log = AlertLog(app)
        val key = log.keyOf(rentId, "2026-11-15T09:00:00Z")
        for (e in listOf(AlertLog.Event.FIRED, AlertLog.Event.POSTED, AlertLog.Event.FULL_SCREEN, AlertLog.Event.PLAY_STARTED, AlertLog.Event.SNOOZE))
            log.add(e, key, style = "normal")
        log.add(AlertLog.Event.LATE, log.keyOf("x", "y"), lateMs = 22 * 60_000L, style = "alarm")
    }

    /** Runs the TalkBack custom action [label] of the first row whose description contains [title]. */
    private fun rowAction(title: String, label: String) {
        val node = rule.onAllNodes(hasContentDescription(title, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes().first()
        rule.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
        rule.waitForIdle()
    }

    /** A sheet (its own window) once it has opened and settled. */
    private fun sheetShot(name: String) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        shotRoots(name)
    }

    private fun click(text: String) = rule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    private fun shot(name: String) {
        rule.waitForIdle()
        @Suppress("UNCHECKED_CAST")
        (scenario as ActivityScenario<android.app.Activity>).onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * Every window on screen (a sheet or a dialog is its own window), drawn directly: name, name-2, ...
     * Does not wait for Compose to be idle (a focused field's cursor never is).
     */
    private fun shotRoots(name: String) {
        @Suppress("UNCHECKED_CAST")
        (scenario as ActivityScenario<android.app.Activity>).onActivity {
            val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            val views = (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<*>).filterIsInstance<android.view.View>()
            views.filter { it.width > 0 && it.height > 0 }.forEachIndexed { i, v ->
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bmp))
                FileOutputStream(File(outDir, if (i == 0) "$name.png" else "$name-${i + 1}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun go(screen: Screen) {
        @Suppress("UNCHECKED_CAST")
        (scenario as ActivityScenario<MainActivity>).onActivity { it.stack = listOf(Screen.Home) + (if (screen == Screen.Home) emptyList() else listOf(screen)) }
        rule.waitForIdle()
    }

    private fun tour(prefix: String, en: Boolean) {
        if (en) Repo.prefs(app).language = "en"
        sample(en)
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.onNodeWithText(if (en) "New reminder" else "تذكير جديد", useUnmergedTree = true).assertIsDisplayed()
            shot("$prefix-01-home")
            // G-05: the snooze sheet from a row (its own window).
            rowAction(if (en) "Call the electrician" else "الاتصال بالكهربائي", if (en) "Snooze" else "أجّل")
            sheetShot("$prefix-22-snooze-sheet")
            go(Screen.Edit(null)); shot("$prefix-02-new")
            rule.onNodeWithText(if (en) "Save" else "حفظ").assertIsDisplayed()
            val rent = Repo.state(app).tasks.first { it.repeat == Repeat.MONTHLY }
            go(Screen.Edit(rent.id)); shot("$prefix-03-edit")
            // G-05: the editor's options open, the tone and alert-type sheets, the steps, the prayer-time sheet.
            click(if (en) "Alert options" else "خيارات التنبيه"); shot("$prefix-24-edit-options")
            click(if (en) "Tone" else "النغمة"); sheetShot("$prefix-25-tone-sheet")
            go(Screen.Settings); go(Screen.Edit(rent.id))
            click(if (en) "Alert options" else "خيارات التنبيه")
            click(if (en) "Alert type" else "نوع التنبيه"); sheetShot("$prefix-26-alert-style-sheet")
            go(Screen.Settings)
            val trip = Repo.state(app).tasks.first { it.steps.isNotEmpty() }
            go(Screen.Edit(trip.id))
            rule.onNodeWithText(if (en) "Add a step" else "أضف خطوة").performScrollTo(); shot("$prefix-27-steps")
            Repo.prefs(app).prayerCity = "Baghdad"; Repo.prefs(app).prayerMethod = "UMM_AL_QURA"
            go(Screen.Settings); go(Screen.Edit(null))
            rule.onNode(hasContentDescription(if (en) "Time, " else "الوقت، ", substring = true) and hasClickAction()).performClick()
            rule.waitForIdle(); rule.onNodeWithText(if (en) "By prayer" else "حسب الصلاة").performClick()
            sheetShot("$prefix-28-prayer-sheet")
            Repo.prefs(app).prayerCity = ""; Repo.prefs(app).prayerMethod = ""
            go(Screen.Settings); shot("$prefix-05-settings")
            // G-05: Settings search with results (a focused field never idles: drawn directly).
            rule.onNodeWithContentDescription(if (en) "Search" else "بحث").performClick()
            rule.onNode(hasSetTextAction()).performTextInput(if (en) "vibration" else "الاهتزاز")
            rule.mainClock.advanceTimeBy(500); shotRoots("$prefix-29-settings-search")
            go(Screen.Trash); go(Screen.Settings)
            go(Screen.Background); shot("$prefix-06-background")
            go(Screen.Trash); shot("$prefix-07-trash")
            go(Screen.About); shot("$prefix-08-about")
            go(Screen.AlarmTest); shot("$prefix-09-alarm-test")
            // 1.0.0 screens (F-103: every new screen in ar/en, light/dark, 200 %).
            go(Screen.Templates); shot("$prefix-10-templates")
            go(Screen.Lists); shot("$prefix-11-lists")
            go(Screen.DndHelp); shot("$prefix-12-dnd-help")
            go(Screen.CompatReport); shot("$prefix-13-compat-report")
            go(Screen.AlertLog); shot("$prefix-14-alert-log")
            Repo.prefs(app).historyOn = true
            go(Screen.History(rent.id)); shot("$prefix-15-history")
            go(Screen.Home)
            // G-05: bulk selection (the TalkBack «تحديد» action is the long press).
            rowAction(if (en) "Blood pressure pill" else "دواء الضغط", if (en) "Select" else "تحديد")
            rowAction(if (en) "Call the electrician" else "الاتصال بالكهربائي", if (en) "Select" else "تحديد")
            shot("$prefix-21-bulk-selection")
            go(Screen.Settings); go(Screen.Home)
            @Suppress("UNCHECKED_CAST")
            (scenario as ActivityScenario<MainActivity>).onActivity { it.todayRequest = System.currentTimeMillis() }
            rule.waitForIdle(); shot("$prefix-16-day")
            // The other views from the header's view menu (the segment row becomes a dialog row at 200 %).
            for ((i, v) in (if (en) listOf("Agenda", "Week", "Month") else listOf("أجندة", "أسبوع", "شهر")).withIndex()) {
                rule.onNodeWithContentDescription(if (en) "View" else "طريقة العرض").performClick()
                rule.onAllNodesWithText(v).onLast().performClick()
                shot("$prefix-${17 + i}-" + listOf("agenda", "week", "month")[i])
            }
        }
        // Quick Add, opened as the launcher shortcut does (a sheet: its own window).
        ActivityScenario.launch<MainActivity>(com.fastkiwi.rememberme.ui.quick.QuickEntry.quickAddIntent(app,
            if (en) "remind me to call mum in an hour" else "ذكرني أتصل بأمي بعد ساعة")).also { scenario = it }.use {
            rule.mainClock.advanceTimeBy(300)
            shotRoots("$prefix-20-quickadd")
        }
        // G-05: Quick Add asking which time is meant.
        ActivityScenario.launch<MainActivity>(com.fastkiwi.rememberme.ui.quick.QuickEntry.quickAddIntent(app,
            if (en) "call Ahmed Thursday 9" else "اتصل بأحمد الخميس 9")).also { scenario = it }.use {
            rule.mainClock.advanceTimeBy(300)
            shotRoots("$prefix-30-quickadd-ask")
        }
        // G-05: the restore summary (a backup with one reminder over the sample data).
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use { sc ->
            val now = Instant.now()
            val other = Tasks.save(AppState(), TaskInput(title = if (en) "From the copy" else "من النسخة", dueAt = now.plusSeconds(600)), now, zone).first
            val bytes = ByteArrayOutputStream().also { Backup.export(other, it, now) }.toByteArray()
            sc.onActivity { it.push(Screen.Settings); it.flows.readRestore(bytes) }
            var ready = false
            rule.waitUntil(10_000) { sc.onActivity { ready = it.flows.dialog is SettingsFlows.Dialog.Restore }; ready }
            rule.waitForIdle(); shotRoots("$prefix-31-restore-summary")
        }
        // G-05: «ما الجديد» after an update from before 1.0.0 (a pre-update copy from versionCode 2).
        val safety = File(Repo.store(app).file.parentFile, "safety").apply { mkdirs() }
        val copy = File(safety, "before-update-v2-20261115-070000-000.json").apply { writeText("{}") }
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use { sc ->
            var shown = false
            rule.waitUntil(10_000) { sc.onActivity { shown = it.flows.dialog == SettingsFlows.Dialog.WhatsNew }; shown }
            rule.waitForIdle(); shotRoots("$prefix-32-whats-new")
        }
        copy.delete()
        val due = Tasks.due(Repo.state(app), Instant.now()).first()
        val intent = Intent(app, AlertActivity::class.java).putExtra(AlertActivity.EXTRA_ID, due.id)
        ActivityScenario.launch<AlertActivity>(intent).also { scenario = it }.use {
            // The primary «تم» stays on screen, also at 200 % text.
            val done = rule.onNodeWithText(if (en) "Done" else "تم", useUnmergedTree = true)
            done.assertIsDisplayed()
            val b = done.fetchSemanticsNode().boundsInWindow
            val h = app.resources.displayMetrics.heightPixels
            assertTrue("Done is inside the window ($b, height $h)", b.bottom <= h + 1)
            shot("$prefix-04-alert")
        }
    }

    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi") fun arabicLight() = tour("ar-light", false)
    @Test @Config(qualifiers = "ar-w412dp-h915dp-night-xxhdpi") fun arabicDark() = tour("ar-dark", false)
    @Test @Config(qualifiers = "en-w412dp-h915dp-notnight-xxhdpi") fun englishLight() = tour("en-light", true)
    @Test @Config(qualifiers = "en-w412dp-h915dp-night-xxhdpi") fun englishDark() = tour("en-dark", true)

    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi")
    fun arabicLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        try { tour("ar-200", false) } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    // G-05: 200 % in English and in Arabic dark too.
    @Test @Config(qualifiers = "en-w412dp-h915dp-notnight-xxhdpi")
    fun englishLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        try { tour("en-200", true) } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test @Config(qualifiers = "ar-w412dp-h915dp-night-xxhdpi")
    fun arabicDarkLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        try { tour("ar-dark-200", false) } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    /** The keyboard comes up ([dp] high): the window gets the IME inset; the picture shows a grey keyboard there. */
    private fun keyboardShot(name: String, dp: Int = 300) {
        @Suppress("UNCHECKED_CAST")
        val sc = scenario as ActivityScenario<MainActivity>
        var px = 0
        sc.onActivity { a ->
            px = (dp * a.resources.displayMetrics.density).toInt()
            ViewCompat.dispatchApplyWindowInsets(a.window.decorView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        rule.mainClock.advanceTimeBy(300)
        shotRoots(name)
        val f = File(outDir, "$name.png")
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path).copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(bmp)
        c.drawRect(0f, (bmp.height - px).toFloat(), bmp.width.toFloat(), bmp.height.toFloat(), android.graphics.Paint().apply { color = 0xFFC9CDD3.toInt() })
        val t = android.graphics.Paint().apply { color = 0xFF5F6368.toInt(); textSize = 18 * bmp.width / 412f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
        c.drawText("keyboard / لوحة المفاتيح", bmp.width / 2f, bmp.height - px / 2f, t)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 1.0.4: «تذكير جديد» alone (QC limited to the screen): empty, with text (the date read from it), with the
     * keyboard up, the date and time pickers, and a picked moment.
     */
    private fun dueSheet(prefix: String, en: Boolean) {
        if (en) Repo.prefs(app).language = "en"
        val qa = com.fastkiwi.rememberme.ui.quick.QuickEntry::quickAddIntent
        ActivityScenario.launch<MainActivity>(qa(app, null)).also { scenario = it }.use {
            rule.mainClock.advanceTimeBy(300)
            shotRoots("$prefix-40-new-empty")
            keyboardShot("$prefix-40-new-empty-keyboard")
        }
        ActivityScenario.launch<MainActivity>(qa(app, if (en) "call mum in an hour" else "اتصل بأمي بعد ساعة")).also { scenario = it }.use {
            rule.mainClock.advanceTimeBy(300)
            shotRoots("$prefix-41-new-text")
            keyboardShot("$prefix-41-new-text-keyboard")
        }
        ActivityScenario.launch<MainActivity>(qa(app, if (en) "call mum" else "اتصل بأمي")).also { scenario = it }.use {
            rule.mainClock.advanceTimeBy(300)
            rule.onNode(hasContentDescription(if (en) "Date and time:" else "الموعد:", substring = true)).performClick()
            rule.waitForIdle(); shotRoots("$prefix-42-new-date-picker")
            rule.onNodeWithText(if (en) "OK" else "موافق").performClick()
            rule.waitForIdle(); shotRoots("$prefix-43-new-time-picker")
            rule.onNodeWithText(if (en) "OK" else "موافق").performClick()
            rule.waitForIdle(); shotRoots("$prefix-44-new-picked")
        }
    }

    /** 1.0.5: Settings with the «الشاشة الكاملة» switch (for important alerts only), on and off (QC limited to it). */
    private fun fullScreenSetting(prefix: String, en: Boolean) {
        if (en) Repo.prefs(app).language = "en"
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            go(Screen.Settings)
            val row = rule.onNode(hasText(if (en) "Full screen" else "الشاشة الكاملة") and hasClickAction(), useUnmergedTree = false)
            row.performScrollTo()
            shot("$prefix-50-settings-fullscreen-on")
            row.performClick()
            rule.waitForIdle()
            shot("$prefix-51-settings-fullscreen-off")
        }
    }

    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi") fun fullScreenSettingArabicLight() = fullScreenSetting("ar-light", false)
    @Test @Config(qualifiers = "ar-w412dp-h915dp-night-xxhdpi") fun fullScreenSettingArabicDark() = fullScreenSetting("ar-dark", false)
    @Test @Config(qualifiers = "en-w412dp-h915dp-notnight-xxhdpi") fun fullScreenSettingEnglishLight() = fullScreenSetting("en-light", true)
    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi")
    fun fullScreenSettingArabicLargeText() { RuntimeEnvironment.setFontScale(2f); try { fullScreenSetting("ar-200", false) } finally { RuntimeEnvironment.setFontScale(1f) } }

    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi") fun dueSheetArabicLight() = dueSheet("ar-light", false)
    @Test @Config(qualifiers = "ar-w412dp-h915dp-night-xxhdpi") fun dueSheetArabicDark() = dueSheet("ar-dark", false)
    @Test @Config(qualifiers = "en-w412dp-h915dp-notnight-xxhdpi") fun dueSheetEnglishLight() = dueSheet("en-light", true)
    @Test @Config(qualifiers = "en-w412dp-h915dp-night-xxhdpi") fun dueSheetEnglishDark() = dueSheet("en-dark", true)
    @Test @Config(qualifiers = "ar-w412dp-h915dp-notnight-xxhdpi")
    fun dueSheetArabicLargeText() { RuntimeEnvironment.setFontScale(2f); try { dueSheet("ar-200", false) } finally { RuntimeEnvironment.setFontScale(1f) } }
    @Test @Config(qualifiers = "en-w412dp-h915dp-notnight-xxhdpi")
    fun dueSheetEnglishLargeText() { RuntimeEnvironment.setFontScale(2f); try { dueSheet("en-200", true) } finally { RuntimeEnvironment.setFontScale(1f) } }

    /** G-05: the tablet layout (840 dp and wider): the list and the chosen reminder's details side by side. */
    private fun tablet(prefix: String, en: Boolean) {
        if (en) Repo.prefs(app).language = "en"
        sample(en)
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            shot("$prefix-01-list")
            rule.onAllNodes(hasContentDescription(if (en) "Team meeting" else "اجتماع الفريق", substring = true) and hasClickAction())[0].performClick()
            shot("$prefix-02-two-pane")
        }
    }

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-notnight-hdpi") fun arabicTablet() = tablet("ar-tablet", false)
    @Test @Config(qualifiers = "en-w1280dp-h800dp-night-hdpi") fun englishTabletDark() = tablet("en-tablet-dark", true)
}
