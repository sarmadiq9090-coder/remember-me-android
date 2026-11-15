package com.fastkiwi.rememberme.torture

import android.content.Intent
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.AlarmReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.SystemReceiver
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Planner
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.random.Random

/**
 * Stress (torture suite 1): thousands of reminders through the real engine on simulated phones.
 * A loud announcement is counted as a RingService start (EngineHarness.rings), so a sound that repeats is seen
 * even when the notification on screen looks the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36], shadows = [CountingNotificationManager::class])
class StressTortureTest {
    private val h = EngineHarness()

    @Before fun setUp() { Torture.assumeOn(); h.setUp(); CountingNotificationManager.reset() }
    @After fun tearDown() { if (Torture.on) h.tearDown() }

    private fun expectedSlotA(s: AppState, now: Instant) = s.tasks.filter { it.active && it.dueAt != null && it.dueAt.isAfter(now) }.minOfOrNull { it.dueAt!! }

    /** 10,000 mixed reminders: one alarm per slot, the shown ones ring exactly once, the rest are counted. */
    @Test fun tenThousandRemindersThroughTheEngine() {
        Torture.assumeHeavy()
        val seed = Torture.seed("stress-10k", 1001)
        Tasks.newId = Random(seed).let { r -> { "s-" + r.nextLong().toULong().toString(36) } }
        val now = Torture.now()
        val gen = Gen(Random(seed))
        val state = gen.state(10_000, now, h.zone, spreadSeconds = 2L * 86_400)
        h.put(state)
        val due = Tasks.due(state, now)
        val t0 = System.nanoTime()
        val plan = Engine.sync(h.app, now)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Torture.report("perf", "JVM, not a phone: Engine.sync of 10,000 reminders (Robolectric SDK ${android.os.Build.VERSION.SDK_INT}): $ms ms, " +
            "${CountingNotificationManager.cancels.get()} NotificationManager.cancel and ${CountingNotificationManager.notifies.get()} notify calls")
        assertEquals(due.map { it.id }, plan.due.map { it.id })
        assertEquals("every due reminder is marked as announced", due.map { it.id }.toSet(), Repo.prefs(h.app).alertLog.keys)
        assertEquals(minOf(Notifier.MAX_SHOWN, due.size), h.dueShown().size)
        assertTrue("the summary counts the rest", due.size < 2 || h.summaryShown())
        assertEquals("the shown ones ring once each", h.dueShown().toSet(), due.takeLast(Notifier.MAX_SHOWN).map { it.id }.toSet())
        assertEquals(minOf(Notifier.MAX_SHOWN, due.size), h.rings().size)
        assertTrue("one alarm per slot: ${h.wakeAlarms().size}", h.wakeAlarms().size <= 2)
        assertEquals(expectedSlotA(state, now)?.toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
        // Again, a second later: nothing rings twice.
        Engine.sync(h.app, now.plusSeconds(1))
        assertEquals(minOf(Notifier.MAX_SHOWN, due.size), h.rings().size)
        // Three days on, everything is due: the newest eight ring, none of them twice, none lost.
        val later = now.plusSeconds(3L * 86_400)
        val plan2 = Engine.sync(h.app, later)
        assertEquals(Tasks.due(Repo.state(h.app), later).size, plan2.due.size)
        val perTask = h.rings().groupingBy { it.first }.eachCount()
        assertTrue("a reminder rang twice: ${perTask.filterValues { it > 1 }}", perTask.values.all { it == 1 })
        assertEquals(minOf(Notifier.MAX_SHOWN, plan2.due.size), h.dueShown().size)
        assertTrue(h.crashes.toString(), h.crashes.isEmpty())
    }

    /**
     * The cost of one engine run on a phone: every run cancels the early notice of every reminder that has none
     * showing (Engine.sync, "A notice whose task is done, deleted or due is no longer useful"), one binder call each.
     */
    @Test fun anEngineRunDoesNotCallTheNotificationServiceOncePerReminder() {
        val seed = Torture.seed("stress-ipc", 1002)
        val now = Torture.now()
        val gen = Gen(Random(seed))
        val state = gen.state(2_000, now, h.zone, spreadSeconds = 20L * 86_400)
        h.put(state)
        CountingNotificationManager.reset()
        Engine.sync(h.app, now)
        val calls = CountingNotificationManager.cancels.get()
        Torture.report("stress", "one engine run with 2,000 reminders made $calls NotificationManager.cancel calls (SDK ${android.os.Build.VERSION.SDK_INT})")
        assertTrue("one engine run with 2,000 reminders made $calls cancel calls (one binder call each on a phone)", calls <= 100)
    }

    /** 500 reminders in the same minute: all are queued and counted, eight ring, and as each is done the next appears. */
    @Test fun fiveHundredInTheSameMinuteAreAllAnnounced() {
        val real = Torture.now()
        // In the past by a few minutes, so the receivers' own runs (which use the real clock) see them due too.
        val minute = real.minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        var s = AppState()
        for (i in 0 until 500) s = Tasks.save(s, TaskInput(title = "r$i", dueAt = minute.plusMillis(i * 100L)), real.minusSeconds(7200), h.zone).first
        h.put(s)
        // A minute before: nothing yet, slot A at the minute.
        Engine.sync(h.app, minute.minusSeconds(30))
        assertTrue(h.dueShown().isEmpty())
        assertEquals(minute.toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
        val plan = Engine.sync(h.app)
        assertEquals(500, plan.due.size)
        assertEquals("all 500 marked loud in one run", 500, plan.loud.size)
        assertEquals(Notifier.MAX_SHOWN, h.dueShown().size)
        assertTrue(h.summaryShown())
        assertEquals(Notifier.MAX_SHOWN, h.rings().size)
        val seen = HashSet<String>(h.dueShown())
        var guard = 0
        while (Repo.state(h.app).tasks.any { it.active } && guard++ < 1000) {
            val id = h.dueShown().firstOrNull() ?: break
            val t = Repo.state(h.app).task(id)!!
            ActionReceiver.handle(h.app, ActionReceiver.DONE, id, t.dueAt, Torture.now())
            seen += h.dueShown()
        }
        assertEquals("every reminder reached the screen", 500, seen.size)
        assertTrue("nothing left", Repo.state(h.app).tasks.none { it.active })
        val perTask = h.rings().groupingBy { it.first }.eachCount()
        assertTrue("a reminder rang twice: $perTask", perTask.values.all { it == 1 })
        assertTrue(h.crashes.toString(), h.crashes.isEmpty())
    }

    /** 200 runs at once from boot, time change, time zone, the alarm and app starts end in one consistent state. */
    @Test fun aStormOfTwoHundredRunsFromSeveralThreadsEndsInTheSameState() {
        val seed = Torture.seed("stress-storm", 1003)
        val rnd = Random(seed)
        val now = Torture.now()
        var s = AppState()
        val dueIds = ArrayList<String>()
        for (i in 0 until 12) s = Tasks.save(s, TaskInput(title = "due$i", dueAt = now.minusSeconds(60L * (i + 1))), now.minusSeconds(7200), h.zone).first.also { dueIds += it.tasks.last().id }
        for (i in 0 until 18) s = Tasks.save(s, TaskInput(title = "later$i", dueAt = now.plusSeconds(3600L * (i + 1)), remindBefore = if (i % 3 == 0) 30 else 0), now.minusSeconds(7200), h.zone).first
        h.put(s)
        val actions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        val threads = (0 until 8).map { k ->
            val r = Random(seed + k)
            Thread {
                repeat(25) {
                    when (r.nextInt(5)) {
                        0 -> SystemReceiver().onReceive(h.app, Intent(actions[r.nextInt(actions.size)]))
                        1 -> AlarmReceiver().onReceive(h.app, Intent(AlarmReceiver.FIRE))
                        2 -> Engine.syncAsync(h.app, reason = "start")
                        3 -> Engine.sync(h.app, reason = "change")
                        else -> Repo.change(h.app) { it }
                    }
                }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        Engine.drain()
        assertTrue("crashes: ${h.crashes}", h.crashes.isEmpty())
        val state = Repo.state(h.app)
        val due = Tasks.due(state, Torture.now())
        assertEquals(dueIds.toSet(), due.map { it.id }.toSet())
        assertEquals(due.takeLast(Notifier.MAX_SHOWN).map { it.id }.toSet(), h.dueShown().toSet())
        assertTrue(h.summaryShown())
        val perTask = h.rings().groupingBy { it.first }.eachCount()
        assertEquals("rings per notification: $perTask", Notifier.MAX_SHOWN, h.rings().size)
        assertTrue(perTask.values.all { it == 1 })
        assertEquals(dueIds.toSet(), Repo.prefs(h.app).alertLog.keys)
        assertEquals(expectedSlotA(state, Torture.now())?.toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
        assertTrue("wake alarms: ${h.wakeAlarms().size}", h.wakeAlarms().size <= 2)
        // The same as one fresh, single run would leave.
        val reference = Planner.plan(state, Torture.now(), 0, emptyMap(), emptyMap())
        assertEquals(reference.dueAt?.toEpochMilli(), h.am.nextAlarmClock?.triggerTime)
        rnd.nextInt()
    }
}
