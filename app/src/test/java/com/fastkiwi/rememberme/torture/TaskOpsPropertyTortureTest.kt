package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Completion
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskException
import com.fastkiwi.rememberme.core.Tasks
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Property (torture suite 3): random sequences of edit, complete, snooze, skip, undo, delete, restore, purge,
 * reopen, time passing and a write/read of data.json, checked after every step:
 * no task is lost (only a purge removes one), ids stay unique, a completed repeat leaves exactly one history copy,
 * undo restores exactly what was there, a stale action changes nothing, unknown fields survive, and the data
 * reads back as it was written.
 */
class TaskOpsPropertyTortureTest {
    @Before fun on() = Torture.assumeOn()

    private fun norm(t: Task) = t.copy(extra = JsonObject(t.extra - "attachments"))
    private fun sameIgnoringUpdated(a: Task, b: Task) = norm(a).copy(updatedAt = Instant.EPOCH) == norm(b).copy(updatedAt = Instant.EPOCH)

    private class Failure(msg: String) : AssertionError(msg)

    @Test fun randomOperationSequencesKeepTheInvariants() {
        val seed = Torture.seed("task-ops", 6001)
        val sequences = Torture.count(1_000, 10_000)
        val steps = 60
        var ops = 0
        for (seq in 0 until sequences) {
            val rnd = Random(seed * 1_000_003 + seq)
            var idn = 0
            Tasks.newId = { "id-$seq-${++idn}" }
            val gen = Gen(rnd)
            val zone = gen.pick(gen.zones)
            var now = Instant.parse("2026-11-15T06:00:00Z").plusSeconds(rnd.nextLong(0, 400L * 86_400))
            var s = AppState()
            val known = HashSet<String>()
            val log = ArrayList<String>()
            fun check(why: String) {
                val ids = s.tasks.map { it.id }
                if (ids.size != ids.toSet().size) throw Failure("duplicate ids after $why")
                val missing = known - ids.toSet()
                if (missing.isNotEmpty()) throw Failure("lost $missing after $why")
                for (t in s.tasks) {
                    if (t.active && t.repeat != Repeat.NONE && (t.dueAt == null || t.scheduleAt == null)) throw Failure("repeat without time: $t after $why")
                    // (A history copy may be reopened from the done list and edited like any reminder; its shape is checked when it is made.)
                    if ("x-unknown" !in t.extra) throw Failure("unknown field lost on ${t.id} after $why")
                }
                // data.json: written and read back, the same reminders.
                val back = Codec.decode(Codec.encode(s), now)
                if (back.tasks.map(::norm) != s.tasks.map(::norm)) {
                    val i = back.tasks.indices.first { norm(back.tasks[it]) != norm(s.tasks[it]) }
                    throw Failure("data.json round trip changed a task after $why:\n  had  ${s.tasks[i]}\n  read ${back.tasks[i]}")
                }
            }
            fun anyId(): String? = s.tasks.takeIf { it.isNotEmpty() }?.let { gen.pick(it).id }
            try {
                repeat(steps) { step ->
                    ops++
                    val before = s
                    val op = rnd.nextInt(100)
                    when {
                        op < 18 -> {
                            val (next, t) = Tasks.save(s, gen.input(now), now, zone)
                            s = next.copy(tasks = next.tasks.map { if (it.id == t.id) gen.decorate(it) else it })
                            known += t.id; log += "add ${t.id} ${t.repeat} ${t.dueAt}"
                        }
                        op < 30 -> {
                            val id = anyId() ?: return@repeat
                            val old = s.task(id)!!
                            val stale = rnd.nextInt(4) == 0
                            val edited = gen.input(now, id = id).copy(updatedAt = if (stale) old.updatedAt.minusSeconds(5) else old.updatedAt,
                                timeEdited = listOf(null, true, false)[rnd.nextInt(3)])
                            try {
                                s = Tasks.save(s, edited, now, zone).first
                                val t = s.task(id)!!
                                if (!t.extra.keys.containsAll(old.extra.keys - "attachments")) throw Failure("edit dropped ${old.extra.keys - t.extra.keys}")
                                log += "edit $id stale=$stale timeEdited=${edited.timeEdited}"
                            } catch (e: TaskException) {
                                if (s !== before) throw Failure("a refused edit changed data")
                                log += "edit $id refused ${e.reason}"
                            }
                        }
                        op < 50 -> {
                            val id = anyId() ?: return@repeat
                            val t = s.task(id)!!
                            val stale = rnd.nextInt(5) == 0
                            val expected = if (stale) t.dueAt?.minusSeconds(60) ?: now else t.dueAt
                            val copies = s.tasks.count { it.sourceId == id }
                            val r = Tasks.complete(s, id, now, zone, expected, checked = true)
                            if (r == null) {
                                log += "complete $id ignored (stale=$stale active=${t.active})"
                                if (!stale && t.active && t.dueAt != null) throw Failure("complete of the current occurrence ignored: $t")
                                return@repeat
                            }
                            if (stale && t.dueAt != null) throw Failure("a stale complete was applied: $t")
                            s = r.first
                            val c = r.second
                            log += "complete $id → ${c.copyId}"
                            if (t.repeat != Repeat.NONE && t.scheduleAt != null) {
                                if (s.tasks.count { it.sourceId == id } != copies + 1) throw Failure("repeat done without exactly one history copy")
                                val moved = s.task(id)!!
                                if (!moved.dueAt!!.isAfter(now) || !moved.dueAt.isAfter(t.scheduleAt)) throw Failure("next occurrence ${moved.dueAt} not after now $now and ${t.scheduleAt}")
                                known += c.copyId
                            } else if (s.task(id)!!.completedAt != now) throw Failure("not completed")
                            // Undo right away: exactly what was there.
                            val undo = rnd.nextInt(3) == 0
                            if (undo) {
                                val undone = Tasks.undoComplete(s, Completion(c.id, c.copyId), now.plusSeconds(3))
                                if (undone.tasks.size != before.tasks.size) throw Failure("undo left ${undone.tasks.size} tasks, had ${before.tasks.size}")
                                for ((a, b) in before.tasks.zip(undone.tasks)) if (!sameIgnoringUpdated(a, b)) throw Failure("undo did not restore:\n  had  $a\n  got  $b")
                                s = undone
                                known.remove(c.copyId).takeIf { c.copyId != c.id }
                                if (c.copyId == c.id) known += c.id
                                log += "undo $id"
                                // A second undo changes nothing.
                                if (Tasks.undoComplete(s, Completion(c.id, c.copyId), now.plusSeconds(4)).tasks.map(::norm).map { it.copy(updatedAt = Instant.EPOCH) } !=
                                    s.tasks.map(::norm).map { it.copy(updatedAt = Instant.EPOCH) }) throw Failure("a second undo changed data")
                            }
                            // The same tap again (double tap): nothing.
                            if (!undo && Tasks.complete(s, id, now, zone, expected, checked = true) != null) throw Failure("a double tap completed twice")
                        }
                        op < 62 -> {
                            val id = anyId() ?: return@repeat
                            val t = s.task(id)!!
                            val until = if (rnd.nextInt(10) == 0) now.minusSeconds(60) else now.plusSeconds(rnd.nextLong(60, 3L * 86_400))
                            val stale = rnd.nextInt(5) == 0
                            val expected = if (stale) (t.dueAt ?: now).minusSeconds(1) else t.dueAt
                            try {
                                val r = Tasks.snooze(s, id, until, now, expected, checked = true)
                                if (r == null) { log += "snooze $id ignored"; if (!stale && t.active && t.dueAt != null) throw Failure("current snooze ignored") ; return@repeat }
                                if (stale && t.dueAt != null) throw Failure("a stale snooze was applied")
                                s = r
                                val x = s.task(id)!!
                                if (x.dueAt != until || x.snoozeCount != minOf(999, t.snoozeCount + 1)) throw Failure("snooze wrong: $x")
                                if (x.scheduleAt != t.scheduleAt) throw Failure("snooze moved the series")
                                log += "snooze $id until $until"
                                if (Tasks.snooze(s, id, until.plusSeconds(600), now, expected, checked = true) != null && expected != until) throw Failure("double snooze applied twice")
                            } catch (e: TaskException) {
                                if (!until.isAfter(now) && e.reason == TaskException.Reason.SNOOZE_PAST) log += "snooze past refused" else throw e
                            }
                        }
                        op < 70 -> {
                            val id = anyId() ?: return@repeat
                            val t = s.task(id)!!
                            val r = Tasks.skip(s, id, now, zone, t.dueAt, checked = true)
                            if (r != null) {
                                s = r
                                val x = s.task(id)!!
                                if (t.repeat == Repeat.NONE) throw Failure("skip of a one-time task")
                                if (!x.dueAt!!.isAfter(now) || x.dueAt != x.scheduleAt) throw Failure("skip wrong $x")
                                log += "skip $id"
                            }
                        }
                        op < 78 -> { anyId()?.let { s = Tasks.trash(s, it, now); log += "trash $it" } }
                        op < 84 -> { anyId()?.let { s = Tasks.restore(s, it); log += "restore $it" } }
                        op < 87 -> {
                            val id = anyId() ?: return@repeat
                            val wasTrashed = s.task(id)!!.deletedAt != null
                            s = Tasks.purge(s, id)
                            if (wasTrashed) known -= id else if (s.task(id) == null) throw Failure("purge removed a task not in the trash")
                            log += "purge $id trashed=$wasTrashed"
                        }
                        op < 89 -> {
                            val all = rnd.nextInt(4) == 0
                            val limit = now.minusMillis(Tasks.TRASH_DAYS * 86_400_000L)
                            val gone = s.tasks.filter { it.deletedAt != null && (all || !it.deletedAt.isAfter(limit)) }.map { it.id }
                            s = Tasks.purgeTrash(s, now, all)
                            known -= gone.toSet()
                            if (s.tasks.any { it.id in gone }) throw Failure("purgeTrash kept $gone")
                            log += "purgeTrash all=$all ${gone.size}"
                        }
                        op < 92 -> { anyId()?.let { s = Tasks.reopen(s, it, now); log += "reopen $it" } }
                        op < 97 -> { now = now.plusSeconds(listOf(60L, 3_600L, 86_400L, 10L * 86_400, 35L * 86_400)[rnd.nextInt(5)]); log += "time → $now" }
                        else -> { s = Codec.decode(Codec.encode(s), now); log += "saved and read back" }
                    }
                    check(log.lastOrNull() ?: "step $step")
                }
            } catch (e: Throwable) {
                fail("sequence $seq (seed $seed, zone ${zone.id}): ${e.message}\nlast steps:\n" + log.takeLast(12).joinToString("\n") + "\n" + e.stackTraceToString().lines().take(6).joinToString("\n"))
            }
        }
        println("[torture] task ops: $sequences sequences, $ops operations")
        assertEquals(true, ops > 0)
    }
}
