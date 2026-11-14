package com.fastkiwi.rememberme.torture

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.AlertActivity
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.random.Random

/**
 * UI monkey (torture suite 7): seeded random taps, long presses, swipes, text, back and rotation on the real
 * screens (Robolectric, SDK 36 only: Compose on SDK 26/27 never idles there). After every event: no crash, the
 * data file still decodes, no reminder disappears unless it was in the trash (a purge), and no event takes longer
 * than [STEP_LIMIT_MS] (an ANR-like hang). Each failure names the seed and the last events.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class UiMonkeyTortureTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")

    companion object { const val STEP_LIMIT_MS = 10_000L }

    @Before fun setUp() {
        Torture.assumeOn()
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (n in listOf("device", "schedule_log", "alert_log")) app.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @After fun settle() { if (Torture.on) { Thread.sleep(300); Engine.drain() } }

    private fun sample(now: Instant) {
        Repo.store(app).change {
            var s: AppState = it
            fun add(i: TaskInput) { s = Tasks.save(s, i, now.minusSeconds(7200), zone).first }
            add(TaskInput(title = "دواء الضغط", body = "حبة واحدة", dueAt = now.minusSeconds(25 * 60), repeat = Repeat.DAILY, priority = Priority.HIGH))
            add(TaskInput(title = "الاتصال بالكهربائي", dueAt = now.minusSeconds(3 * 3600), category = Category.WORK))
            add(TaskInput(title = "اجتماع الفريق", dueAt = now.plusSeconds(2 * 3600), remindBefore = 15, priority = Priority.MEDIUM))
            add(TaskInput(title = "سقي النباتات", dueAt = now.plusSeconds(5 * 3600), repeat = Repeat.INTERVAL, everyDays = 3))
            add(TaskInput(title = "دفع الإيجار", dueAt = now.plusSeconds(3 * 86_400), repeat = Repeat.MONTHLY))
            add(TaskInput(title = "الرياضة", dueAt = now.plusSeconds(86_400), repeat = Repeat.DAYS, days = listOf(0, 2, 4)))
            add(TaskInput(title = "قراءة كتاب", body = "فصل واحد"))
            add(TaskInput(title = "call the bank", dueAt = now.plusSeconds(40 * 3600), repeat = Repeat.WEEKLY))
            s = Tasks.complete(s, s.tasks[1].id, now.minusSeconds(60), zone)!!.first
            s = Tasks.trash(s, s.tasks[3].id, now.minusSeconds(30))
            s
        }
    }

    private val texts = listOf("بكرة 5 العصر", "tomorrow 9am", "اشتري 5 كيلو طماطم", "🙂🎉", "‏", "x".repeat(250), "", " ", "كل يوم 7 الصبح", "#منزل", "1:30", "؟؟")

    private class Hang(msg: String) : AssertionError(msg)

    private fun monkey(seed: Long, events: Int, launch: () -> ActivityScenario<out Activity>) {
        val rnd = Random(seed)
        var scenario = launch()
        val log = ArrayList<String>()
        var known = Repo.state(app).tasks.map { it.id }.toSet()
        var trashed = Repo.state(app).tasks.filter { it.deletedAt != null }.map { it.id }.toSet()
        var previous = Repo.state(app).tasks
        val file = File(app.filesDir, "data/data.json")
        var slow = 0L
        fun nodes(m: androidx.compose.ui.test.SemanticsMatcher) = try { rule.onAllNodes(m, useUnmergedTree = false).fetchSemanticsNodes().size } catch (_: Throwable) { 0 }
        fun nth(m: androidx.compose.ui.test.SemanticsMatcher, i: Int): SemanticsNodeInteraction = rule.onAllNodes(m)[i]
        fun label(n: SemanticsNodeInteraction) = runCatching {
            val c = n.fetchSemanticsNode().config
            (c.getOrNull(SemanticsProperties.Text)?.joinToString() ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?").take(40)
        }.getOrDefault("?")
        try {
            for (step in 0 until events) {
                if (scenario.state == Lifecycle.State.DESTROYED) { log += "relaunch"; scenario = launch() }
                val t0 = System.nanoTime()
                val kind = rnd.nextInt(100)
                try {
                    when {
                        kind < 55 -> { val n = nodes(hasClickAction()); if (n > 0) { val node = nth(hasClickAction(), rnd.nextInt(n)); log += "tap «${label(node)}»"; node.performClick() } }
                        kind < 62 -> { val n = nodes(hasClickAction()); if (n > 0) { val node = nth(hasClickAction(), rnd.nextInt(n)); log += "long-press «${label(node)}»"; node.performTouchInput { longClick() } } }
                        kind < 75 -> { val n = nodes(hasScrollAction()); if (n > 0) { val node = nth(hasScrollAction(), rnd.nextInt(n)); val d = rnd.nextInt(4); log += "swipe $d"
                            node.performTouchInput { when (d) { 0 -> swipeUp(); 1 -> swipeDown(); 2 -> swipeLeft(); else -> swipeRight() } } } }
                        kind < 83 -> { val n = nodes(hasSetTextAction()); if (n > 0) { val t = texts[rnd.nextInt(texts.size)]; log += "type «${t.take(20)}»"; nth(hasSetTextAction(), rnd.nextInt(n)).performTextInput(t) } }
                        kind < 93 -> { log += "back"; scenario.onActivity { a -> (a as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: a.finish() } }
                        kind < 96 -> { val q = if (rnd.nextBoolean()) "+land" else "+port"; log += "rotate $q"; RuntimeEnvironment.setQualifiers(q); scenario.recreate() }
                        else -> { log += "wait"; rule.mainClock.advanceTimeBy(rnd.nextLong(100, 5_000)) }
                    }
                    rule.waitForIdle()
                } catch (e: AssertionError) {
                    // The node went away between finding and acting on it (the screen changed): not a finding.
                    if (e is Hang) throw e
                    log += "  (skipped: ${e.message?.lineSequence()?.firstOrNull()?.take(60)})"
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("No compose hierarchies found") == true || e.message?.contains("not attached") == true) log += "  (no screen)" else throw e
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                slow = maxOf(slow, ms)
                if (ms > STEP_LIMIT_MS) throw Hang("event $step took $ms ms")
                // Data: the file decodes, and nothing vanished unless it was in the trash.
                run {
                    // The file is read only now and then, after the app's background saves: on the Windows test host an
                    // open file cannot be replaced, so reading it while the app writes would disturb the app itself.
                    if (step % 25 == 24 && file.isFile) {
                        Engine.drain(); Thread.sleep(150)
                        val text = (0 until 5).firstNotNullOfOrNull { runCatching { file.readText() }.getOrNull() ?: run { Thread.sleep(100); null } }
                        if (text != null) runCatching { Codec.decode(text) }.onFailure { throw AssertionError("data.json does not decode after event $step: $it") }
                    }
                    val now = Repo.state(app).tasks
                    // An undone «تم» removes its history copy and puts the series back on that occurrence: not a loss.
                    val undone = previous.filter { c -> c.sourceId != null && c.completedAt != null && now.any { it.id == c.sourceId && it.dueAt == c.dueAt } }.map { it.id }
                    val gone = known - now.map { it.id }.toSet() - trashed - undone.toSet()
                    if (gone.isNotEmpty()) throw AssertionError("reminders vanished without a delete: " +
                        previous.filter { it.id in gone }.joinToString { "«${it.title}» repeat=${it.repeat} due=${it.dueAt} done=${it.completedAt} source=${it.sourceId}" } +
                        "; now ${now.size} tasks, before ${previous.size}")
                    known = now.map { it.id }.toSet(); trashed = trashed + now.filter { it.deletedAt != null }.map { it.id }
                    previous = now
                }
            }
        } catch (e: Throwable) {
            // What Compose was doing: a recomposer that stays busy frame after frame is a composition loop.
            val busy = if (e::class.java.simpleName == "AppNotIdleException") runCatching {
                rule.mainClock.autoAdvance = false
                (0 until 30).map {
                    rule.mainClock.advanceTimeByFrame()
                    androidx.compose.runtime.Recomposer.runningRecomposers.value.joinToString("/") { r -> if (r.hasPendingWork) "pending" else "idle" }
                }.groupingBy { it }.eachCount().toString()
            }.getOrElse { "?" } else ""
            throw AssertionError("monkey seed $seed: ${e::class.java.simpleName}${if (busy.isNotEmpty()) " (recomposer states over 30 frames: $busy)" else ""}: ${e.message?.take(300)} | last events: " + log.takeLast(15).joinToString(" | "), e)
        } finally {
            runCatching { scenario.close() }
            RuntimeEnvironment.setQualifiers("+port")
        }
        Torture.report("ui", "UI monkey seed $seed: $events events, slowest $slow ms, ${log.count { it.startsWith("tap") }} taps, ${log.count { it == "relaunch" }} relaunches")
        assertTrue(events > 0)
    }

    @Test fun monkeyOnTheMainScreens() {
        val seed = Torture.seed("ui-monkey-main", 14_003)
        sample(Instant.now())
        monkey(seed, Torture.count(800, 4_000)) { ActivityScenario.launch(MainActivity::class.java) }
    }

    /** A second sequence on the same screens (each seed walks a different path). */
    @Test fun monkeyOnTheMainScreensAnotherSeed() {
        val seed = Torture.seed("ui-monkey-main-2", 14_004)
        sample(Instant.now())
        monkey(seed, Torture.count(800, 4_000)) { ActivityScenario.launch(MainActivity::class.java) }
    }

    @Test fun monkeyOnTheReminderScreen() {
        val seed = Torture.seed("ui-monkey-alert", 14_002)
        val now = Instant.now()
        sample(now)
        val first = Tasks.due(Repo.state(app), now).first()
        monkey(seed, Torture.count(300, 1_500)) {
            ActivityScenario.launch<AlertActivity>(Intent(app, AlertActivity::class.java).putExtra(AlertActivity.EXTRA_ID, first.id))
        }
    }
}
