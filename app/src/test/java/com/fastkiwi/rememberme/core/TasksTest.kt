package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class TasksTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)

    @Before fun ids() = T.ids()

    private fun new(s: AppState = AppState(), input: TaskInput) = Tasks.save(s, input, now, zone)

    @Test fun titleIsRequiredAndTrimmed() {
        assertThrows(TaskException::class.java) { new(input = TaskInput(title = "   ")) }
        assertThrows(TaskException::class.java) { new(input = TaskInput(title = "x".repeat(201))) }
        assertEquals("اشتري خبز", new(input = TaskInput(title = "  اشتري خبز ")).second.title)
        assertThrows(TaskException::class.java) { new(input = TaskInput(title = "r", repeat = Repeat.DAILY)) }
    }

    @Test fun newRepeatAnchorsAtItsDueTime() {
        val due = at(zone, 2026, 11, 15, 9)
        val t = new(input = TaskInput(title = "r", dueAt = due, repeat = Repeat.MONTHLY, remindBefore = 15)).second
        assertEquals(due, t.scheduleAt)
        assertEquals(due, t.rule.from)
        assertEquals(15, t.remindBefore)
        // An early notice needs a time and one of the allowed values.
        assertEquals(0, new(input = TaskInput(title = "x", remindBefore = 15)).second.remindBefore)
        assertEquals(0, new(input = TaskInput(title = "x", dueAt = due, remindBefore = 7)).second.remindBefore)
    }

    @Test fun completingARepeatMovesItAndKeepsAHistoryCopy() {
        val due = at(zone, 2026, 11, 14, 9)
        val (s, t) = new(input = TaskInput(title = "دواء", dueAt = due, repeat = Repeat.DAILY))
        val (s2, c) = Tasks.complete(s, t.id, now, zone)!!
        val moved = s2.task(t.id)!!
        assertEquals(at(zone, 2026, 11, 15, 9), moved.dueAt)
        assertNull(moved.completedAt)
        val copy = s2.task(c.copyId)!!
        assertEquals(t.id, copy.sourceId)
        assertEquals(Repeat.NONE, copy.repeat)
        assertEquals(now, copy.completedAt)
        assertEquals(due, copy.dueAt)
        // Undo brings back the occurrence and removes the copy.
        val s3 = Tasks.undoComplete(s2, c, now)
        assertEquals(due, s3.task(t.id)!!.dueAt)
        assertNull(s3.task(c.copyId))
    }

    /** UI monkey seed 14003: «تم» twice, and the undo of the first one saved after the second. */
    @Test fun anUndoSavedAfterALaterDoneOfTheSameRepeatChangesNothing() {
        val d0 = at(zone, 2026, 11, 14, 9)
        val (s, t) = new(input = TaskInput(title = "دواء الضغط", dueAt = d0, repeat = Repeat.DAILY))
        val (s1, c1) = Tasks.complete(s, t.id, now, zone, d0, checked = true)!!
        val d1 = s1.task(t.id)!!.dueAt
        val (s2, c2) = Tasks.complete(s1, t.id, now.plusSeconds(2), zone, d1, checked = true)!!
        // The older undo: the series stays on its next occurrence and both copies stay.
        assertSame(s2, Tasks.undoComplete(s2, c1, now.plusSeconds(3)))
        // The newest one still undoes exactly its own completion, and then the older one is the newest again.
        val s3 = Tasks.undoComplete(s2, c2, now.plusSeconds(3))
        assertEquals(d1, s3.task(t.id)!!.dueAt)
        assertNull(s3.task(c2.copyId))
        assertEquals(d0, s3.task(c1.copyId)!!.dueAt)
        val s4 = Tasks.undoComplete(s3, c1, now.plusSeconds(4))
        assertEquals(d0, s4.task(t.id)!!.dueAt)
        assertEquals(listOf(t.id), s4.tasks.map { it.id })
    }

    @Test fun completingAOneTimeTaskAndUndo() {
        val (s, t) = new(input = TaskInput(title = "o", dueAt = at(zone, 2026, 11, 14, 9)))
        val (s2, c) = Tasks.complete(s, t.id, now, zone)!!
        assertEquals(now, s2.task(t.id)!!.completedAt)
        assertNull(Tasks.complete(s2, t.id, now, zone))
        assertNull(Tasks.undoComplete(s2, c, now).task(t.id)!!.completedAt)
        assertNull(Tasks.reopen(s2, t.id, now).task(t.id)!!.completedAt)
    }

    @Test fun staleTapsChangeNothing() {
        val due = at(zone, 2026, 11, 14, 9)
        val (s, t) = new(input = TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY))
        val (s2, _) = Tasks.complete(s, t.id, now, zone, expectedDueAt = due, checked = true)!!
        // The same notification tapped twice: the second Done is for an occurrence that has moved on.
        assertNull(Tasks.complete(s2, t.id, now, zone, expectedDueAt = due, checked = true))
        assertNull(Tasks.snooze(s2, t.id, now.plusSeconds(600), now, expectedDueAt = due, checked = true))
        assertNull(Tasks.skip(s2, t.id, now, zone, expectedDueAt = due, checked = true))
    }

    @Test fun snoozeCountsAndRejectsThePast() {
        val (s, t) = new(input = TaskInput(title = "o", dueAt = at(zone, 2026, 11, 14, 9)))
        val s2 = Tasks.snooze(s, t.id, now.plusSeconds(600), now)!!
        assertEquals(now.plusSeconds(600), s2.task(t.id)!!.dueAt)
        assertEquals(1, s2.task(t.id)!!.snoozeCount)
        assertEquals(now, s2.task(t.id)!!.snoozedAt)
        assertThrows(TaskException::class.java) { Tasks.snooze(s2, t.id, now, now) }
        // A snoozed repeat keeps its series: completing it moves to the next regular occurrence.
        val (s3, r) = new(input = TaskInput(title = "r", dueAt = at(zone, 2026, 11, 14, 9), repeat = Repeat.DAILY))
        val s4 = Tasks.snooze(s3, r.id, at(zone, 2026, 11, 14, 11), now)!!
        val s5 = Tasks.complete(s4, r.id, at(zone, 2026, 11, 14, 11, 1), zone)!!.first
        assertEquals(at(zone, 2026, 11, 15, 9), s5.task(r.id)!!.dueAt)
        assertEquals(0, s5.task(r.id)!!.snoozeCount)
    }

    @Test fun skipMovesARepeatWithoutHistory() {
        val (s, t) = new(input = TaskInput(title = "r", dueAt = at(zone, 2026, 11, 14, 9), repeat = Repeat.WEEKLY))
        val s2 = Tasks.skip(s, t.id, now, zone)!!
        assertEquals(at(zone, 2026, 11, 21, 9), s2.task(t.id)!!.dueAt)
        assertEquals(1, s2.tasks.size)
        val (s3, once) = new(s2, TaskInput(title = "o", dueAt = now))
        assertNull(Tasks.skip(s3, once.id, now, zone))
    }

    @Test fun editingKeepsOrRestartsTheSeries() {
        val due = at(zone, 2026, 11, 14, 9)
        val (s, t) = new(input = TaskInput(title = "r", dueAt = due, repeat = Repeat.MONTHLY))
        val snoozed = Tasks.snooze(s, t.id, at(zone, 2026, 11, 14, 12), now)!!
        // A text edit keeps the snoozed time and the anchor.
        val (s2, edited) = Tasks.save(snoozed, TaskInput(id = t.id, title = "rent", dueAt = at(zone, 2026, 11, 14, 12), repeat = Repeat.MONTHLY), now, zone)
        assertEquals(due, edited.rule.from)
        assertEquals("rent", edited.title)
        // The editor sent the snoozed time back unchanged, so the series is not restarted.
        assertEquals(due, edited.scheduleAt)
        // A new time restarts the series there.
        val newDue = at(zone, 2026, 11, 20, 8)
        val moved = Tasks.save(s2, TaskInput(id = t.id, title = "rent", dueAt = newDue, repeat = Repeat.MONTHLY), now, zone).second
        assertEquals(newDue, moved.scheduleAt)
        assertEquals(newDue, moved.rule.from)
        assertEquals(0, moved.snoozeCount)
        // A completed task must be reopened first; a missing one is gone.
        val (s3, o) = new(s2, TaskInput(title = "o", dueAt = due))
        val done = Tasks.complete(s3, o.id, now, zone)!!.first
        assertThrows(TaskException::class.java) { Tasks.save(done, TaskInput(id = o.id, title = "o2"), now, zone) }
        assertThrows(TaskException::class.java) { Tasks.save(done, TaskInput(id = "nope", title = "x"), now, zone) }
    }

    @Test fun checklistFromWindowsIsKeptAndUntickedForTheNextOccurrence() {
        val steps = JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("a"), "text" to JsonPrimitive("step"), "done" to JsonPrimitive(true)))))
        val (s, t) = new(input = TaskInput(title = "r", dueAt = at(zone, 2026, 11, 14, 9), repeat = Repeat.DAILY))
        val withSteps = s.copy(tasks = s.tasks.map { it.copy(extra = JsonObject(mapOf("checklist" to steps, "tags" to JsonArray(listOf(JsonPrimitive("home")))))) })
        val (s2, c) = Tasks.complete(withSteps, t.id, now, zone)!!
        val next = s2.task(t.id)!!
        assertFalse(next.extra["checklist"]!!.let { (it as JsonArray)[0].jsonObject["done"]!!.jsonPrimitive.boolean })
        assertTrue(s2.task(c.copyId)!!.extra["checklist"]!!.let { (it as JsonArray)[0].jsonObject["done"]!!.jsonPrimitive.boolean })
        assertEquals("home", (next.extra["tags"] as JsonArray)[0].jsonPrimitive.content)
        // Undo brings the ticks back.
        assertTrue(Tasks.undoComplete(s2, c, now).task(t.id)!!.extra["checklist"]!!.let { (it as JsonArray)[0].jsonObject["done"]!!.jsonPrimitive.boolean })
    }

    @Test fun trashRestorePurge() {
        val (s, t) = new(input = TaskInput(title = "o", dueAt = now))
        val trashed = Tasks.trash(s, t.id, now)
        assertNotNull(trashed.task(t.id)!!.deletedAt)
        assertTrue(Tasks.due(trashed, now).isEmpty())
        assertSame(trashed, Tasks.trash(trashed, t.id, now.plusSeconds(5)))
        assertNull(Tasks.restore(trashed, t.id).task(t.id)!!.deletedAt)
        assertEquals(1, Tasks.purgeTrash(trashed, now.plusSeconds(29 * 86_400)).tasks.size)
        assertEquals(0, Tasks.purgeTrash(trashed, now.plusSeconds(31 * 86_400)).tasks.size)
        assertEquals(0, Tasks.purgeTrash(trashed, now, all = true).tasks.size)
        assertEquals(0, Tasks.purge(trashed, t.id).tasks.size)
        assertEquals(1, Tasks.purge(s, t.id).tasks.size)
    }

    @Test fun dueQueueIsOldestFirstAndEarlyNotices() {
        var s = AppState()
        for (i in 0 until 20) s = new(s, TaskInput(title = "t$i", dueAt = now.minusSeconds(60L * (20 - i)))).first
        assertEquals((0 until 20).map { "t$it" }, Tasks.due(s, now).map { it.title })
        val (s2, e) = new(AppState(), TaskInput(title = "e", dueAt = now.plusSeconds(30 * 60), remindBefore = 15))
        assertTrue(Tasks.upcoming(s2, now).isEmpty())
        assertEquals(listOf(e.id), Tasks.upcoming(s2, now.plusSeconds(16 * 60)).map { it.id })
        assertTrue(Tasks.upcoming(s2, now.plusSeconds(31 * 60)).isEmpty())
        // Moved into the lead window by an edit: no notice for that time.
        val edited = Tasks.save(s2, TaskInput(id = e.id, title = "e", dueAt = now.plusSeconds(10 * 60), remindBefore = 15), now, zone).first
        assertTrue(Tasks.upcoming(edited, now.plusSeconds(60)).isEmpty())
    }

    @Test fun snoozePresets() {
        val p = SnoozePreset.DEFAULTS
        assertEquals(now.plusSeconds(600), snoozeTime(p[0], now, zone))
        assertEquals(at(zone, 2026, 11, 14, 18), snoozeTime(p[2], now, zone))
        // "Today 18:00" closes an hour before.
        assertNull(snoozeTime(p[2], at(zone, 2026, 11, 14, 17, 1), zone))
        assertEquals(at(zone, 2026, 11, 15, 9), snoozeTime(p[3], now, zone))
        assertEquals(at(zone, 2026, 11, 21, 10), snoozeTime(p[4], now, zone))
        assertNull(snoozeTime(p[5], now, zone))
        assertEquals(now.plusSeconds(600), quickSnooze(p, now, zone))
        // Whole days keep the wall clock across DST (New York, 7 → 8 March 2027 has none; 13 → 14 has one).
        val ny = ZoneId.of("America/New_York")
        val sat = at(ny, 2027, 3, 13, 9)
        assertEquals(at(ny, 2027, 3, 14, 9), snoozeTime(SnoozePreset.In(1440), sat, ny))
        assertEquals(sat.plusSeconds(3600), snoozeTime(SnoozePreset.In(60), sat, ny))
    }

    @Test fun snoozePresetsFromWindowsSettings() {
        fun obj(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) -> k to if (v is Int) JsonPrimitive(v) else JsonPrimitive(v as String) })
        val list = JsonArray(listOf(obj("kind" to "in", "minutes" to 5), obj("kind" to "today", "time" to "00:30"), obj("kind" to "tomorrow", "time" to "07:15"),
            obj("kind" to "bad"), obj("kind" to "in", "minutes" to 999_999), obj("kind" to "pick")))
        val p = SnoozePreset.normalize(list)
        assertEquals(SnoozePreset.In(5), p[0])
        assertEquals("A today time before 01:00 falls back to the slot default", SnoozePreset.DEFAULTS[1], p[1])
        assertEquals(SnoozePreset.Tomorrow(LocalTime.of(7, 15)), p[2])
        assertEquals(SnoozePreset.DEFAULTS[3], p[3])
        assertEquals(SnoozePreset.DEFAULTS[4], p[4])
        assertEquals(SnoozePreset.Pick, p[5])
        assertEquals(SnoozePreset.DEFAULTS, SnoozePreset.normalize(JsonArray(listOf(obj("kind" to "week")))))
        for (preset in SnoozePreset.DEFAULTS) assertEquals(preset, SnoozePreset.parse(preset.toJson()))
    }

    @Test fun aSnoozeTimeFinerThanAMillisecondStillMatchesTheNotificationsButtons() {
        val due = at(zone, 2026, 11, 14, 9)
        val (s0, t) = new(input = TaskInput(title = "r", dueAt = due))
        // Instant.now() can carry microseconds; data.json and the notification's extras keep milliseconds.
        val until = now.plusSeconds(600).plusNanos(123_456)
        val s1 = Tasks.snooze(s0, t.id, until, now, due, checked = true)!!
        val seen = Instant.parse(Codec.iso(until))
        val later = until.plusSeconds(5)
        assertNotNull("Done from the notification", Tasks.complete(s1, t.id, later, zone, seen, checked = true))
        assertNotNull("Snooze from the notification", Tasks.snooze(s1, t.id, later.plusSeconds(600), later, seen, checked = true))
        assertNull("An older occurrence is still ignored", Tasks.complete(s1, t.id, later, zone, due, checked = true))
    }

    @Test fun anEditorOpenedBeforeDoneOrSnoozeDoesNotPutTheOldTimeBack() {
        val due = at(zone, 2026, 11, 14, 9)
        val (s0, t) = new(input = TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY))
        val later = now.plusSeconds(60)
        // The editor opened at t.updatedAt; meanwhile Done from the notification moved the series on.
        val s1 = Tasks.complete(s0, t.id, later, zone, due, checked = true)!!.first
        val moved = s1.task(t.id)!!
        val edit = TaskInput(id = t.id, title = "r fixed", dueAt = due, repeat = Repeat.DAILY, updatedAt = t.updatedAt, timeEdited = false)
        val saved = Tasks.save(s1, edit, later.plusSeconds(60), zone).second
        assertEquals("r fixed", saved.title)
        assertEquals(moved.dueAt, saved.dueAt)
        assertEquals(moved.scheduleAt, saved.scheduleAt)
        assertEquals(moved.rule, saved.rule)
        // The user did change the time in that editor: their new time wins.
        val retimed = Tasks.save(s1, edit.copy(dueAt = due.plusSeconds(7200), timeEdited = true), later.plusSeconds(60), zone).second
        assertEquals(due.plusSeconds(7200), retimed.dueAt)
        // Not outdated: the editor's fields apply as before.
        val fresh = Tasks.save(s1, edit.copy(dueAt = moved.dueAt, updatedAt = moved.updatedAt), later.plusSeconds(60), zone).second
        assertEquals("r fixed", fresh.title)
        // A one-time reminder completed meanwhile keeps its completion; only the text is applied.
        val (o0, once) = new(input = TaskInput(title = "once", dueAt = due))
        val o1 = Tasks.complete(o0, once.id, later, zone, due, checked = true)!!.first
        val text = Tasks.save(o1, TaskInput(id = once.id, title = "once fixed", dueAt = due, updatedAt = once.updatedAt, timeEdited = true), later.plusSeconds(60), zone).second
        assertEquals("once fixed", text.title)
        assertNotNull(text.completedAt)
    }

    /** An undo offered for a one-time task's «تم» never clears a later «تم» after a reopen. */
    @Test fun anOldUndoOfAOneTimeTaskLeavesALaterCompletionAlone() {
        val zone = java.time.ZoneId.of("Asia/Baghdad")
        val t0 = Instant.parse("2026-11-15T09:00:00Z")
        val (s0, task) = Tasks.save(AppState(), TaskInput(title = "x", dueAt = t0.plusSeconds(60)), t0, zone)
        val (s1, first) = Tasks.complete(s0, task.id, t0.plusSeconds(120), zone)!!
        val s2 = Tasks.reopen(s1, task.id, t0.plusSeconds(130))
        val (s3, _) = Tasks.complete(s2, task.id, t0.plusSeconds(140), zone)!!
        val s4 = Tasks.undoComplete(s3, first, t0.plusSeconds(150))
        org.junit.Assert.assertEquals(t0.plusSeconds(140), s4.task(task.id)!!.completedAt)
    }
}
