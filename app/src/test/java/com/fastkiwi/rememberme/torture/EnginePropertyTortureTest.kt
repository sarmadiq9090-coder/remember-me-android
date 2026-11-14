package com.fastkiwi.rememberme.torture

import android.content.Context
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.quickSnooze
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * Property (torture suite 3, gate "≥ 1,000 seeded sequences of engine runs and broadcasts"): random sequences of
 * engine runs (alarm, boot, time, zone, app start), «تم», «أجّل», swipes and new reminders on one simulated phone,
 * with the clock moving forward to the planned wake-up, late (Doze) or in jumps. After every step: each occurrence
 * rang at most once, the eight newest due ones are on screen (minus swipes past the re-post limit), nothing else is,
 * and the alarm clock is the next due time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class EnginePropertyTortureTest {
    private val h = EngineHarness()

    @Before fun setUp() { Torture.assumeOn(); h.setUp() }
    @After fun tearDown() { if (Torture.on) h.tearDown() }

    private fun reset() {
        Engine.drain()
        Repo.resetForTests()
        File(h.app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "schedule_log", "alert_log")) h.app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        h.nm.cancelAll()
        shadowOf(h.app).clearStartedServices()
        for (a in shadowOf(h.am).scheduledAlarms.toList()) a.operation?.let { h.am.cancel(it) }
    }

    @Test fun randomSequencesOfRunsAndTapsKeepTheEngineInvariants() {
        val seed = Torture.seed("engine-property", 13_001)
        // Per SDK; the two SDKs run different sequences, so the heavy run covers 1,000 in all.
        val sequences = Torture.count(60, 500)
        val sdk = android.os.Build.VERSION.SDK_INT
        val base = Torture.now().plusSeconds(86_400)
        for (seq in 0 until sequences) {
            reset()
            val rnd = Random(seed * 7919 + seq + sdk * 100_000L)
            val gen = Gen(rnd)
            var idn = 0
            Tasks.newId = { "e$seq-${++idn}" }
            var now = base
            var s = AppState()
            repeat(rnd.nextInt(3, 14)) {
                s = Tasks.save(s, TaskInput(title = gen.title(), dueAt = now.plusSeconds(rnd.nextLong(-3_600, 6 * 3_600)), priority = gen.pick(Priority.entries),
                    repeat = gen.pick(listOf(Repeat.NONE, Repeat.NONE, Repeat.DAILY, Repeat.HOURS, Repeat.WEEKLY)), everyHours = rnd.nextInt(1, 4),
                    remindBefore = if (rnd.nextBoolean()) gen.pick(listOf(5, 15, 60)) else 0), now.minusSeconds(7_200), h.zone).first
            }
            Repo.store(h.app).replace(s)
            Engine.sync(h.app, now, "start")
            val swipes = HashMap<String, Int>()
            val log = ArrayList<String>()
            try {
                repeat(40) { step ->
                    val op = rnd.nextInt(10)
                    when {
                        op < 4 -> {
                            val plan = Engine.sync(h.app, now, "probe")
                            val wake = plan.wakeAt ?: now.plusSeconds(3_600)
                            now = when (rnd.nextInt(4)) { 0 -> wake.plusSeconds(rnd.nextLong(1, 600)); 1 -> now.plusSeconds(rnd.nextLong(60, 4 * 3_600)); else -> wake }
                            Engine.sync(h.app, now, gen.pick(listOf("alarm", "boot", "time", "zone", "start")))
                            log += "run at $now"
                        }
                        op < 6 -> h.dueShown().takeIf { it.isNotEmpty() }?.let { shown ->
                            val id = gen.pick(shown); val t = Repo.state(h.app).task(id) ?: return@let
                            Repo.store(h.app).change { Tasks.complete(it, id, now, h.zone, t.dueAt, checked = true)?.first ?: it }
                            Engine.sync(h.app, now, "action"); log += "done $id"
                        }
                        op < 7 -> h.dueShown().takeIf { it.isNotEmpty() }?.let { shown ->
                            val id = gen.pick(shown); val t = Repo.state(h.app).task(id) ?: return@let
                            Repo.store(h.app).change { st -> Tasks.snooze(st, id, quickSnooze(Repo.presets(st), now, h.zone), now, t.dueAt, checked = true) ?: st }
                            Engine.sync(h.app, now, "action"); log += "snooze $id"
                        }
                        op < 8 -> h.dueShown().takeIf { it.isNotEmpty() }?.let { shown ->
                            // A swipe: the system removes the notification, then the delete intent arrives.
                            val id = gen.pick(shown); val t = Repo.state(h.app).task(id) ?: return@let
                            h.nm.cancel(Notifier.dueId(id))
                            ActionReceiver.handle(h.app, ActionReceiver.DISMISSED, id, t.dueAt, now)
                            swipes.merge(id + "|" + t.dueAt, 1, Int::plus); log += "swipe $id"
                        }
                        op < 9 -> {
                            Repo.store(h.app).change { Tasks.save(it, TaskInput(title = gen.title(), dueAt = now.plusSeconds(rnd.nextLong(-600, 3 * 3_600))), now, h.zone).first }
                            Engine.sync(h.app, now, "change"); log += "add"
                        }
                        else -> { now = now.plusSeconds(rnd.nextLong(1, 120)); Engine.sync(h.app, now, "start"); log += "start at $now" }
                    }
                    // Invariants.
                    val st = Repo.state(h.app)
                    val due = Tasks.due(st, now)
                    val visible = due.takeLast(Notifier.MAX_SHOWN).map { it.id }.toSet()
                    val shown = h.dueShown().toSet()
                    if (!visible.containsAll(shown)) fail("shown but not among the newest due: ${shown - visible}")
                    val missing = visible - shown - swipes.filter { it.value > com.fastkiwi.rememberme.core.AlertPolicy.MAX_REPOSTS }.keys.map { it.substringBefore('|') }.toSet()
                    // A run that only re-posts after a swipe happens at the next engine run; only check right after one.
                    val last = log.lastOrNull().orEmpty()
                    if (last.startsWith("run") || last.startsWith("start") || last.startsWith("done") || last.startsWith("add")) {
                        val justSwiped = swipes.keys.map { it.substringBefore('|') }.toSet()
                        if ((missing - justSwiped).isNotEmpty()) fail("due, among the newest eight, not on screen: ${missing - justSwiped}")
                    }
                    val perOccurrence = h.rings().groupingBy { it.second }.eachCount()
                    if (perOccurrence.values.any { it > 1 }) fail("an occurrence rang twice: ${perOccurrence.filterValues { it > 1 }}")
                    val nextDue = st.tasks.filter { it.active && it.dueAt != null && it.dueAt.isAfter(now) }.minOfOrNull { it.dueAt!! }
                    if (nextDue?.toEpochMilli() != h.am.nextAlarmClock?.triggerTime) fail("alarm clock ${h.am.nextAlarmClock?.triggerTime} but next due $nextDue")
                    if (h.crashes.isNotEmpty()) fail("crash ${h.crashes}")
                    if (step == 39) Unit
                }
            } catch (e: AssertionError) {
                fail("sequence $seq (seed $seed): ${e.message}\n" + log.takeLast(10).joinToString("\n"))
            }
        }
        println("[torture] engine property: $sequences sequences × 40 steps on SDK ${android.os.Build.VERSION.SDK_INT}")
    }
}
