package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/** F-066: bulk actions are pure, each one undoable exactly; confirmation from 10 items. */
class BulkTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)
    private val later = now.plusSeconds(60)

    @Before fun ids() = T.ids()

    private fun sample(): AppState {
        var s = AppState()
        fun add(i: TaskInput) { s = Tasks.save(s, i, now, zone).first }
        add(TaskInput(title = "one", dueAt = now.plusSeconds(3600)))
        add(TaskInput(title = "daily", dueAt = now.minusSeconds(600), repeat = Repeat.DAILY))
        add(TaskInput(title = "free"))
        add(TaskInput(title = "work", dueAt = now.plusSeconds(7200), category = Category.WORK, priority = Priority.MEDIUM))
        return s
    }

    private fun AppState.byTitle(t: String) = tasks.first { it.title == t && it.sourceId == null }

    @Test fun confirmationStartsAtTenItems() {
        assertFalse(Bulk.needsConfirm(9))
        assertTrue(Bulk.needsConfirm(10))
        assertTrue(Bulk.needsConfirm(12))
    }

    @Test fun completeAndUndoRestoreExactlyIncludingARepeat() {
        val s = sample()
        val ids = s.tasks.map { it.id }
        val r = Bulk.complete(s, ids, later, zone)
        assertEquals(4, r.count)
        assertTrue(r.state.byTitle("one").completedAt != null)
        // The repeat moved to its next occurrence and left a completed copy.
        assertTrue(r.state.byTitle("daily").dueAt!!.isAfter(now))
        assertEquals(5, r.state.tasks.size)
        val back = Bulk.undo(r.state, r.undo, later)
        assertEquals(s.tasks.map { it.copy(updatedAt = now) }, back.tasks.map { it.copy(updatedAt = now) })
        // Done ones are not completed twice.
        assertEquals(0, Bulk.complete(r.state, listOf(r.state.byTitle("one").id), later, zone).count)
    }

    @Test fun trashAndUndo() {
        val s = sample()
        val ids = s.tasks.take(2).map { it.id }
        val r = Bulk.trash(s, ids, later)
        assertEquals(2, r.count)
        assertTrue(r.state.tasks.filter { it.id in ids }.all { it.deletedAt != null })
        assertEquals(1, Bulk.repeating(s, ids))
        val back = Bulk.undo(r.state, r.undo, later)
        assertTrue(back.tasks.all { it.deletedAt == null })
        // Already in the trash: nothing to move again.
        assertEquals(0, Bulk.trash(r.state, ids, later).count)
    }

    @Test fun priorityCategoryAndListTagAreUndoable() {
        val s = sample()
        val ids = s.tasks.map { it.id }
        val p = Bulk.setPriority(s, ids, Priority.MEDIUM, later)
        assertEquals(3, p.count) // "work" was medium already
        assertTrue(p.state.tasks.all { it.priority == Priority.MEDIUM })
        assertEquals(s.tasks.map { it.priority }, Bulk.undo(p.state, p.undo, later).tasks.map { it.priority })

        val c = Bulk.setCategory(s, ids, Category.STUDY, later)
        assertEquals(4, c.count)
        assertEquals(s.tasks.map { it.category }, Bulk.undo(c.state, c.undo, later).tasks.map { it.category })

        val tagged = Bulk.addTag(s, ids, "منزل", later)
        assertEquals(4, tagged.count)
        assertTrue(tagged.state.tasks.all { it.tags == listOf("منزل") })
        // A reminder on the list already is left as it is (folded comparison).
        assertEquals(0, Bulk.addTag(tagged.state, ids, "#منزل", later).count)
        assertTrue(Bulk.undo(tagged.state, tagged.undo, later).tasks.all { it.tags.isEmpty() && "tags" !in it.extra })
    }

    @Test fun snoozeKeepsTheRepeatAndUndoes() {
        val s = sample()
        val ids = s.tasks.map { it.id }
        val until = now.plusSeconds(1800)
        val r = Bulk.snooze(s, ids, until, later)
        assertEquals(4, r.count)
        val daily = r.state.byTitle("daily")
        assertEquals(until, daily.dueAt)
        assertEquals(Repeat.DAILY, daily.repeat)
        assertEquals(s.byTitle("daily").scheduleAt, daily.scheduleAt)
        assertEquals(1, daily.snoozeCount)
        val back = Bulk.undo(r.state, r.undo, later)
        assertEquals(s.tasks.map { it.dueAt to it.snoozeCount }, back.tasks.map { it.dueAt to it.snoozeCount })
        assertNull(back.byTitle("daily").snoozedAt)
        assertThrows(TaskException::class.java) { Bulk.snooze(s, ids, now, later) }
    }
}
