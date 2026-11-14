package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.TemplatesStore
import com.fastkiwi.rememberme.data.saveUserTemplate
import com.fastkiwi.rememberme.tile.NewReminderTile
import com.fastkiwi.rememberme.ui.quick.QuickEntry
import com.fastkiwi.rememberme.widget.ReminderWidget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Quick Add, share, templates, widget «تم», Quick Settings tile and shortcuts (DESIGN-SPEC §7.4 rows Quick Add,
 * Widget, Tile; F-042, F-047, F-049, F-121, F-122, F-123).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class QuickUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")

    @Before fun setUp() {
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "templates", "widget", "quick")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private fun quickAdd(text: String? = null) = ActivityScenario.launch<MainActivity>(QuickEntry.quickAddIntent(app, text))

    private fun field() = rule.onNode(hasSetTextAction())

    /** Types, then lets the 150 ms preview debounce run (Robolectric's main looper is paused). */
    private fun type(text: String) {
        field().performTextInput(text)
        settleUi()
    }

    private fun settleUi() {
        repeat(3) {
            Thread.sleep(250)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            rule.mainClock.advanceTimeBy(300)
            rule.waitForIdle()
        }
    }

    /** Every node with a click action is at least 48 × 48 dp. */
    private fun assertTouchTargets() {
        val density = app.resources.displayMetrics.density
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val w = n.size.width / density
            val h = n.size.height / density
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            assertTrue("Touch target \"$label\" is ${w}x$h dp", w >= 47.5f && h >= 47.5f)
        }
    }

    // ---- Quick Add (§7.4 row «Quick Add») ----

    @Test fun quickAddPreviewsThenSavesWithUndo() {
        quickAdd().use {
            rule.onNodeWithText("اكتب التذكير وموعده").assertExists()
            rule.onNodeWithContentDescription("إدخال صوتي").assertExists()
            rule.onNodeWithText("خيارات أكثر").assertExists()
            rule.onNodeWithText("حفظ").assertIsNotEnabled()
            val before = Instant.now()
            type("ذكرني أتصل بأمي بعد ساعة")
            rule.onNodeWithText("العنوان: أتصل بأمي").assertExists()
            rule.onNodeWithContentDescription("بلا موعد").assertExists()
            rule.onNodeWithText("من قالب").assertExists()
            rule.onNodeWithText("التصنيف: شخصي").assertExists()
            assertTouchTargets()
            rule.onNodeWithText("حفظ").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.isNotEmpty() }
            val t = Repo.state(app).tasks.single()
            assertEquals("أتصل بأمي", t.title)
            val due = t.dueAt!!
            assertTrue("$due", !due.isBefore(before.plusSeconds(3600).minusSeconds(120)) && !due.isAfter(Instant.now().plusSeconds(3600 + 60)))
            // The sheet closes; Home shows «أُضيف التذكير» with «تراجع».
            rule.waitUntil(10_000) { rule.onAllNodesWithText("أُضيف التذكير").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("اكتب التذكير وموعده").assertCountEqualsZero()
            rule.onNodeWithText("تراجع").performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.isEmpty() }
        }
    }

    @Test fun anAmbiguousTimeDisablesSaveUntilAChoiceAndNothingUnseenIsSaved() {
        quickAdd().use {
            type("اتصل بأحمد الخميس 9")
            rule.onNodeWithText("اختر موعدًا أو «بلا موعد»").assertExists()
            rule.onNodeWithText("حفظ").assertIsNotEnabled()
            rule.onNodeWithText("حفظ").performClick()
            rule.waitForIdle()
            Thread.sleep(200)
            assertTrue(Repo.state(app).tasks.isEmpty())
            rule.onNodeWithText("العنوان: اتصل بأحمد").assertExists()
            // Pick a dated choice (each shows its full date and time).
            val chips = rule.onAllNodes(hasText("9:00", substring = true) and hasClickAction()).fetchSemanticsNodes()
            assertTrue(chips.size in 2..4)
            rule.onAllNodes(hasText("9:00", substring = true) and hasClickAction())[0].performClick()
            rule.onNodeWithText("حفظ").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.isNotEmpty() }
            val t = Repo.state(app).tasks.single()
            val z = t.dueAt!!.atZone(zone)
            assertEquals(java.time.DayOfWeek.THURSDAY, z.dayOfWeek)
            assertTrue(z.hour == 9 || z.hour == 21)
            assertEquals("اتصل بأحمد", t.title)
        }
    }

    @Test fun noDateChoiceSavesTheWholeText() {
        quickAdd().use {
            type("اتصل بأحمد الخميس 9")
            rule.onNodeWithText("بلا موعد").performClick()
            rule.onNodeWithText("حفظ").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { Repo.state(app).tasks.isNotEmpty() }
            val t = Repo.state(app).tasks.single()
            assertNull(t.dueAt)
            assertEquals("اتصل بأحمد الخميس 9", t.title)
        }
    }

    @Test fun moreOptionsOpensTheEditorWithTheFields() {
        quickAdd("اتصل بأمي بعد ساعة").use { sc ->
            rule.onNodeWithText("العنوان: اتصل بأمي").assertExists()
            rule.onNodeWithText("خيارات أكثر").performClick()
            rule.waitForIdle()
            sc.onActivity { a -> assertEquals(Screen.Edit(null), a.stack.last()) }
            // The editor opened with the fields (it consumes the prefill once).
            rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("اتصل بأمي")).assertExists()
            rule.onNodeWithText("أضف موعدًا").assertDoesNotExist()
            assertTrue(Repo.state(app).tasks.isEmpty())
        }
    }

    @Test fun voiceShowsTheDisclosureOnceAndAsksNoPermission() {
        quickAdd().use {
            rule.onNodeWithContentDescription("إدخال صوتي").performClick()
            rule.onNodeWithText("الإدخال الصوتي").assertExists()
            rule.onNodeWithText("متابعة").performClick()
            rule.waitForIdle()
            val started = shadowOf(app).nextStartedActivity
            assertEquals(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH, started?.action)
            assertTrue(started!!.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        }
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.RECORD_AUDIO in requested)
        assertFalse(Manifest.permission.INTERNET in requested)
    }

    // ---- Share (F-049) ----

    @Test fun shareOpensThePreviewWithTextCutTo2000AndSavesNothing() {
        val long = "اتصل بأمي بعد ساعة " + "x".repeat(3000)
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, long)
        val activity = Robolectric.buildActivity(ShareActivity::class.java, share).create().get()
        assertTrue(activity.isFinishing)
        val next = shadowOf(app).nextStartedActivity
        assertEquals(QuickEntry.ACTION_QUICK_ADD, next.action)
        assertEquals(ComponentName(app, MainActivity::class.java), next.component)
        assertEquals(2000, next.getStringExtra(QuickEntry.EXTRA_TEXT)!!.length)
        assertTrue(Repo.state(app).tasks.isEmpty())
        // Not text: nothing opens.
        Robolectric.buildActivity(ShareActivity::class.java, Intent(Intent.ACTION_SEND).setType("image/png")).create()
        assertNull(shadowOf(app).nextStartedActivity)
        // The forwarded intent opens the sheet with the text; still nothing is saved.
        ActivityScenario.launch<MainActivity>(next).use {
            rule.onNodeWithText("اكتب التذكير وموعده").assertExists()
            rule.onNodeWithText("حفظ").assertExists()
            assertTrue(Repo.state(app).tasks.isEmpty())
        }
    }

    // ---- Templates (F-047) ----

    @Test fun templatesFillTheEditorAndSaveNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Templates) }
            rule.onAllNodesWithText("من قالب").assertCountEqualsAtLeast(1)
            rule.onNodeWithText("القالب يملأ الحقول فقط. لا يُحفظ شيء قبل «حفظ».").assertExists()
            rule.onNodeWithText("مناسبات").assertExists()
            assertEquals(3, rule.onAllNodesWithText("كل سنة، تنبيه قبل يوم", substring = true).fetchSemanticsNodes().size)
            rule.onNodeWithText("الروتين").performScrollTo()
            rule.onNodeWithText("كل يوم، منبّه مهم").performScrollTo().assertExists()
            rule.onNodeWithText("تنبيه قبل يوم وقبل ساعة").performScrollTo().assertExists()
            assertTouchTargets()
            rule.onNodeWithText("عيد ميلاد").performScrollTo().performClick()
            rule.waitForIdle()
            sc.onActivity { a -> assertEquals(Screen.Edit(null), a.stack.last()) }
            rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("عيد ميلاد")).assertExists()
            rule.onNodeWithText("كل سنة", substring = true).assertExists()
            assertTrue(Repo.state(app).tasks.isEmpty())
        }
    }

    @Test fun myTemplatesRenameAndDeleteByLongPress() {
        saveUserTemplate(app, TaskInput(title = "اجتماع الفريق", dueAt = Instant.now().plusSeconds(86_400), repeat = Repeat.WEEKLY, remindBefore = 15), "الاجتماع")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Templates) }
            rule.onNodeWithText("قوالبي").performScrollTo()
            rule.onNodeWithText("الاجتماع").performScrollTo().performTouchInput { longClick() }
            rule.onNodeWithText("إعادة تسمية").assertExists()
            rule.onNodeWithText("حذف").performClick()
            rule.onNodeWithText("حذف القالب «الاجتماع»؟").assertExists()
            rule.onAllNodesWithText("حذف").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            rule.waitForIdle()
            assertTrue(TemplatesStore(app).list().isEmpty())
            assertTrue(Repo.state(app).tasks.isEmpty())
        }
    }

    @Test fun templatesStoreRoundTrip() {
        val store = TemplatesStore(app)
        val t = store.save(TaskInput(title = "دواء", dueAt = com.fastkiwi.rememberme.core.at(zone, 2026, 12, 1, 20, 15), repeat = Repeat.DAILY),
            "", com.fastkiwi.rememberme.core.AlertExt(style = "alarm"), zone)
        val back = store.list().single()
        assertEquals(t, back)
        assertEquals("دواء", back.name)
        assertEquals(java.time.LocalTime.of(20, 15), back.time)
        assertEquals("alarm", back.style)
        store.rename(t.key, "   ")
        assertEquals("دواء", store.list().single().name)
        store.rename(t.key, "دواء المساء")
        assertEquals("دواء المساء", store.list().single().name)
        store.delete(t.key)
        assertTrue(store.list().isEmpty())
    }

    // ---- Widget (§7.4 row «Widget», F-121) ----

    private fun inflate(now: Instant): View = ReminderWidget.views(app, now).apply(app, FrameLayout(app))

    private fun View.all(): List<View> = if (this is ViewGroup) listOf(this) + (0 until childCount).flatMap { getChildAt(it).all() } else listOf(this)

    @Test fun widgetDoneCircleCompletesWithUndoAndIgnoresStaleTaps() {
        val now = Instant.now()
        Repo.store(app).change {
            var s: AppState = it
            s = Tasks.save(s, TaskInput(title = "دواء الضغط", dueAt = now.minusSeconds(25 * 60)), now.minusSeconds(7200), zone).first
            s = Tasks.save(s, TaskInput(title = "دفع الإيجار", dueAt = now.plusSeconds(3 * 86_400), repeat = Repeat.MONTHLY), now.minusSeconds(7200), zone).first
            s
        }
        val late = Repo.state(app).tasks.first { it.title == "دواء الضغط" }
        var views = inflate(now).all()
        val done = views.single { it.contentDescription == "تم: دواء الضغط" }
        assertEquals(View.VISIBLE, done.visibility)
        assertTrue(done.hasOnClickListeners())
        // A row three days ahead has no circle.
        assertTrue(views.none { it.contentDescription == "تم: دفع الإيجار" && it.visibility == View.VISIBLE })
        // A stale tap (the row showed another due time) changes nothing.
        assertFalse(ReminderWidget.done(app, late.id, late.dueAt!!.minusSeconds(60), now))
        assertNull(Repo.state(app).task(late.id)!!.completedAt)
        assertTrue(ReminderWidget.done(app, late.id, late.dueAt, now))
        assertNotNull(Repo.state(app).task(late.id)!!.completedAt)
        // The same row now reads «أُنجز: <title>» with «تراجع».
        views = inflate(now.plusSeconds(1)).all()
        val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), "أُنجز: دواء الضغط" in texts)
        assertTrue(texts.toString(), "تراجع" in texts)
        // A second tap on the old row is stale too.
        assertFalse(ReminderWidget.done(app, late.id, late.dueAt, now))
        ReminderWidget.undo(app, late.id, now.plusSeconds(2))
        val back = Repo.state(app).task(late.id)!!
        assertNull(back.completedAt)
        assertEquals(late.dueAt, back.dueAt)
        // After 10 s the undo row is gone.
        ReminderWidget.done(app, late.id, late.dueAt, now)
        val after = inflate(now.plusSeconds(11)).all().filterIsInstance<TextView>().map { it.text.toString() }
        assertFalse(after.toString(), "أُنجز: دواء الضغط" in after)
    }

    @Test fun widgetEmptyStateAndPlusOpensQuickAdd() {
        val views = inflate(Instant.now()).all()
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "لا تذكيرات قادمة" && it.visibility == View.VISIBLE })
        val plus = views.single { it.contentDescription == "تذكير جديد" }
        plus.performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(QuickEntry.ACTION_QUICK_ADD, started?.action)
    }

    // ---- Tile (§7.4 row «Tile», F-123) and shortcuts (F-122) ----

    @Test fun tileIsLabelledAndOpensQuickAdd() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, NewReminderTile::class.java), 0)
        assertEquals("تذكير جديد", info.loadLabel(app.packageManager).toString())
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission)
        assertTrue(info.exported)
        val intent = NewReminderTile.intent(app)
        assertEquals(QuickEntry.ACTION_QUICK_ADD, intent.action)
        assertEquals(ComponentName(app, MainActivity::class.java), intent.component)
        // The tile's intent opens the sheet.
        ActivityScenario.launch<MainActivity>(intent).use { rule.onNodeWithText("اكتب التذكير وموعده").assertExists() }
    }

    @Test fun shortcutsOpenTheRightScreens() {
        val actions = ArrayList<String>()
        val xml = app.resources.getXml(R.xml.shortcuts)
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "intent") {
                actions += xml.getAttributeValue("http://schemas.android.com/apk/res/android", "action")
            }
        }
        assertEquals(listOf(MainActivity.ACTION_NEW, QuickEntry.ACTION_QUICK_ADD, QuickEntry.ACTION_TODAY), actions)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).setAction(QuickEntry.ACTION_TODAY)).use { sc ->
            sc.onActivity { a ->
                assertEquals(listOf<Screen>(Screen.Home), a.stack)
                assertTrue(a.todayRequest > 0)
                assertNull(a.quickAdd)
            }
        }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_NEW)).use { sc ->
            sc.onActivity { a -> assertEquals(Screen.Edit(null), a.stack.last()) }
        }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
    assertTrue(fetchSemanticsNodes().isEmpty())

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsAtLeast(n: Int) =
    assertTrue(fetchSemanticsNodes().size >= n)
