package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.T
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

/** F-072: the change history is off by default, writes nothing while off, keeps no text, and is bounded. */
class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)
    private val due = at(zone, 2026, 11, 16, 10, 30)

    @Before fun ids() = T.ids()

    private fun history(on: Boolean): Pair<History, File> {
        val f = File(tmp.root, "history.json")
        return History(f) { on } to f
    }

    @Test fun nothingIsWrittenWhileOff() {
        val (h, f) = history(on = false)
        val (s, _) = Tasks.save(AppState(), TaskInput(title = "سر", dueAt = due), now, zone)
        h.record(History.diff(AppState(), s, now))
        assertFalse(f.exists())
        assertTrue(h.events("id-1").isEmpty())
    }

    @Test fun createEditSnoozeDoneAreRecordedWithoutTitlesOrNotes() {
        val (h, f) = history(on = true)
        val s0 = AppState()
        val (s1, t) = Tasks.save(s0, TaskInput(title = "موعد سري", body = "ملاحظة خاصة", dueAt = due), now, zone)
        h.record(History.diff(s0, s1, now))
        val later = due.plusSeconds(3600)
        val (s2, _) = Tasks.save(s1, TaskInput(id = t.id, title = "موعد سري", body = "ملاحظة خاصة", dueAt = later, repeat = Repeat.MONTHLY, alerts = listOf(1440)), now, zone)
        h.record(History.diff(s1, s2, now))
        val s3 = Tasks.snooze(s2, t.id, later.plusSeconds(600), later)!!
        h.record(History.diff(s2, s3, later))
        val (s4, _) = Tasks.complete(s3, t.id, later.plusSeconds(700), zone)!!
        h.record(History.diff(s3, s4, later.plusSeconds(700)))
        val kinds = h.events(t.id).map { it.kind }
        assertEquals(listOf(History.Kind.CREATED, History.Kind.MOVED, History.Kind.REPEAT, History.Kind.ALERT_ADDED, History.Kind.SNOOZED, History.Kind.DONE), kinds)
        val moved = h.events(t.id)[1]
        assertEquals(due, moved.from); assertEquals(later, moved.to)
        assertEquals(1440, h.events(t.id)[3].minutes)
        assertEquals("monthly", h.events(t.id)[2].repeat)
        // The completed copy of the repeat is not "created"; the moved series is not "moved".
        assertEquals(6, h.all().size)
        val text = f.readText()
        assertFalse(text.contains("موعد") || text.contains("ملاحظة"))
    }

    @Test fun aRestoreOfManyRemindersIsNotRecordedAsCreated() {
        var s = AppState()
        repeat(5) { s = Tasks.save(s, TaskInput(title = "r$it", dueAt = due), now, zone).first }
        assertTrue(History.diff(AppState(), s, now).none { it.kind == History.Kind.CREATED })
    }

    @Test fun boundedPerReminderAndInAll() {
        val (h, _) = history(on = true)
        h.record((1..70).map { History.Event("a", History.Kind.SNOOZED, now.plusSeconds(it.toLong())) })
        assertEquals(History.PER_TASK, h.events("a").size)
        assertEquals(now.plusSeconds(70), h.events("a").last().at)
        h.record((1..History.TOTAL + 10).map { History.Event("t$it", History.Kind.DONE, now) })
        assertEquals(History.TOTAL, h.all().size)
    }

    @Test fun clearRemovesEverything() {
        val (h, f) = history(on = true)
        h.record(listOf(History.Event("a", History.Kind.DONE, now)))
        assertTrue(f.exists())
        h.clear()
        assertFalse(f.exists())
        assertTrue(h.all().isEmpty())
    }
}
