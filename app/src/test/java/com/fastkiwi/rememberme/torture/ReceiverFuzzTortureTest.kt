package com.fastkiwi.rememberme.torture

import android.content.Intent
import android.net.Uri
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.AlarmReceiver
import com.fastkiwi.rememberme.alarm.DirectBoot
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.LockedReceiver
import com.fastkiwi.rememberme.alarm.SystemReceiver
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
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
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * Fuzz (torture suite 4): notification actions and receiver intents with missing, garbage or wrong-typed extras,
 * unknown actions, ids of nothing, and stale occurrences. No crash on any thread, and no change to the data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 36])
class ReceiverFuzzTortureTest {
    private val h = EngineHarness()

    @Before fun setUp() { Torture.assumeOn(); h.setUp() }
    @After fun tearDown() { if (Torture.on) h.tearDown() }

    private fun dataText() = File(h.app.filesDir, "data/data.json").takeIf { it.isFile }?.readText()

    private fun sample(now: Instant): AppState {
        var s = AppState()
        fun add(i: TaskInput, at: Instant = now.minusSeconds(7200)) { s = Tasks.save(s, i, at, h.zone).first }
        add(TaskInput(title = "due once", dueAt = now.minusSeconds(300)))
        add(TaskInput(title = "due daily", dueAt = now.minusSeconds(200), repeat = Repeat.DAILY))
        add(TaskInput(title = "later", dueAt = now.plusSeconds(7200), remindBefore = 30))
        add(TaskInput(title = "untimed"))
        add(TaskInput(title = "done", dueAt = now.minusSeconds(900)))
        s = Tasks.complete(s, s.tasks.last().id, now.minusSeconds(800), h.zone)!!.first
        add(TaskInput(title = "trashed", dueAt = now.minusSeconds(100)))
        s = Tasks.trash(s, s.tasks.last().id, now.minusSeconds(50))
        return s
    }

    private val actions = listOf(ActionReceiver.DONE, ActionReceiver.SNOOZE, ActionReceiver.UNDO, ActionReceiver.DISMISSED, ActionReceiver.TEST_DONE,
        "com.fastkiwi.rememberme.done", "", "android.intent.action.BOOT_COMPLETED", "x".repeat(5000))

    /** Garbage: every action with an id and a due time that cannot be the current occurrence. Nothing may change. */
    @Test fun garbageActionsChangeNothingAndCrashNothing() {
        val seed = Torture.seed("receiver-fuzz", 11_001)
        val rnd = Random(seed)
        val now = Torture.now()
        h.put(sample(now))
        Engine.sync(h.app)
        val before = dataText()
        // An untimed reminder has no notification, and "no due time" is its current occurrence: left out here.
        val ids = Repo.state(h.app).tasks.filter { it.dueAt != null }.map { it.id }
        val badIds = listOf("", "nope", "\u0000", "x".repeat(100_000), "../data", "id\nwith\nlines", ids.random(rnd) + " ")
        repeat(Torture.count(400, 3_000)) {
            val existing = rnd.nextBoolean()
            val id = if (existing) ids[rnd.nextInt(ids.size)] else badIds[rnd.nextInt(badIds.size)]
            val task = Repo.state(h.app).task(id)
            // A due time that is not the task's current one: none, garbage, an older or a later moment.
            val dueText = listOf(null, "garbage", "", "2026-13-01T00:00:00Z", "1970-01-01T00:00:00Z", task?.dueAt?.minusSeconds(60)?.let(Codec::iso),
                task?.dueAt?.plusMillis(1)?.let(Codec::iso), "+100000-12-31T23:59:59.999Z")[rnd.nextInt(8)]
            val action = actions[rnd.nextInt(actions.size)].let { if (it == ActionReceiver.UNDO || it == ActionReceiver.DISMISSED) ActionReceiver.DONE else it }
            if (rnd.nextBoolean()) {
                val due = dueText?.let { runCatching { Instant.parse(it) }.getOrNull() }
                try { ActionReceiver.handle(h.app, action, id, due, Torture.now(), listOf(null, "", "copy", id)[rnd.nextInt(4)]) } catch (e: Throwable) { h.crashes += e }
            } else {
                val intent = Intent(action).setData(Uri.parse("rememberme://task/" + Uri.encode(id)))
                when (rnd.nextInt(4)) {
                    0 -> { intent.putExtra(ActionReceiver.EXTRA_ID, id); dueText?.let { intent.putExtra(ActionReceiver.EXTRA_DUE, it) } }
                    1 -> { intent.putExtra(ActionReceiver.EXTRA_ID, rnd.nextInt()); intent.putExtra(ActionReceiver.EXTRA_DUE, rnd.nextLong()) }
                    2 -> { intent.putExtra(ActionReceiver.EXTRA_DUE, dueText) }
                    else -> { intent.putExtra(ActionReceiver.EXTRA_ID, id); intent.putExtra(ActionReceiver.EXTRA_DUE, arrayOf("a")); intent.putExtra(ActionReceiver.EXTRA_COPY, 5) }
                }
                try { h.deliver(ActionReceiver(), intent) } catch (e: Throwable) { h.crashes += e }
            }
        }
        // Undo of a copy that does not exist, and a swipe of nothing.
        for (id in ids + badIds) {
            ActionReceiver.handle(h.app, ActionReceiver.UNDO, id, null, Torture.now(), "no-such-copy")
            ActionReceiver.handle(h.app, ActionReceiver.DISMISSED, id, Instant.EPOCH, Torture.now())
        }
        h.drainActions(); Engine.drain()
        assertTrue("crashes: ${h.crashes.map { it.toString() + "\n" + it.stackTrace.take(4).joinToString("\n") }}", h.crashes.isEmpty())
        assertEquals("the data changed", before, dataText())
    }

    /**
     * A due time beyond what epoch milliseconds can hold (year > 292 million) in the action's extra: Tasks' stale
     * check calls toEpochMilli() and throws ArithmeticException, which ActionReceiver.handle does not catch
     * (only IOException), so the receiver's thread dies. Not reachable from outside (the receiver is not exported).
     */
    @Test fun aDueExtraBeyondEpochMillisDoesNotCrashTheReceiver() {
        val now = Torture.now()
        h.put(sample(now))
        val id = Repo.state(h.app).tasks.first { it.dueAt != null }.id
        val before = dataText()
        for (a in listOf(ActionReceiver.DONE, ActionReceiver.SNOOZE, ActionReceiver.DISMISSED)) {
            try { ActionReceiver.handle(h.app, a, id, Instant.parse("+999999999-12-31T23:59:59.999Z"), Torture.now()) } catch (e: Throwable) { h.crashes += e }
        }
        assertTrue("crashes: ${h.crashes.map { it.toString() }}", h.crashes.isEmpty())
        assertEquals(before, dataText())
    }

    /** The system receivers with random actions, extras and data: no crash; only their own actions run the engine. */
    @Test fun systemAndAlarmReceiversTakeAnyIntent() {
        val seed = Torture.seed("system-fuzz", 11_002)
        val rnd = Random(seed)
        val now = Torture.now()
        h.put(sample(now))
        val before = dataText()
        val pool = SystemReceiver.ACTIONS.toList() + listOf(AlarmReceiver.FIRE, DirectBoot.ACTION, "com.fastkiwi.rememberme.TEST_ALARM", "com.fastkiwi.rememberme.SUMMARY",
            Intent.ACTION_LOCKED_BOOT_COMPLETED, "nonsense", "", Intent.ACTION_USER_UNLOCKED)
        repeat(Torture.count(300, 2_000)) {
            val i = Intent(pool[rnd.nextInt(pool.size)])
            if (rnd.nextBoolean()) i.putExtra("id", rnd.nextInt()).putExtra("due", "x").setData(Uri.parse("rememberme://slot/" + rnd.nextInt()))
            val r = listOf(SystemReceiver(), AlarmReceiver(), LockedReceiver())[rnd.nextInt(3)]
            try { h.deliver(r, i) } catch (e: Throwable) { h.crashes += e }
        }
        Engine.drain()
        Thread.sleep(200)
        assertTrue("crashes: ${h.crashes}", h.crashes.isEmpty())
        // The engine may purge nothing here (no 30-day-old trash), so the data is as it was.
        assertEquals(before, dataText())
        assertTrue("one alarm per slot", h.wakeAlarms().size <= 3)
    }
}
