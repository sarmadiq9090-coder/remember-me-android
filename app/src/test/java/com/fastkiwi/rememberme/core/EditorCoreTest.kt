package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/** The editor's data rules (FEATURES.md "Data format", F-046, F-050, F-062, F-070, F-071). */
class EditorCoreTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)
    private val due = at(zone, 2026, 11, 16, 10, 30)

    @Before fun ids() = T.ids()
    @After fun hook() { Tasks.extRepeat = { ExtRepeat() } }

    private fun save(input: TaskInput, s: AppState = AppState()) = Tasks.save(s, input, now, zone)

    @Test fun severalAlertsKeepTheNearestInRemindBeforeAndAllInTheExtension() {
        val (s, t) = save(TaskInput(title = "طبيب", dueAt = due, alerts = listOf(60, 1440, 15, 5)))
        // At most 3, largest first; Windows sees the nearest one.
        assertEquals(15, t.remindBefore)
        assertEquals(listOf(1440, 60, 15), AlertExt.of(t).alerts)
        assertEquals(listOf(1440, 60, 15), Tasks.noticeMinutes(t))
        val json = Codec.encodeTask(t)
        assertEquals(15, json["remindBefore"]!!.jsonPrimitive.int)
        assertEquals(listOf(1440, 60, 15), json["android"]!!.jsonObject["alerts"]!!.jsonArray.map { it.jsonPrimitive.int })
        // One alert only: remindBefore alone, no extension member.
        val one = save(TaskInput(id = t.id, title = "طبيب", dueAt = due, alerts = listOf(60)), s).second
        assertEquals(60, one.remindBefore)
        assertTrue(AlertExt.of(one).alerts.isEmpty())
        // None: no alert at all.
        val none = save(TaskInput(id = t.id, title = "طبيب", dueAt = due, alerts = emptyList()), s).second
        assertEquals(0, none.remindBefore)
        assertTrue(Tasks.noticeMinutes(none).isEmpty())
    }

    @Test fun windowsChangingRemindBeforeDropsTheExtraAlerts() {
        val t = save(TaskInput(title = "r", dueAt = due, alerts = listOf(1440, 60))).second
        // Windows set another early notice: alerts no longer contains it, so remindBefore is used alone.
        val edited = t.copy(remindBefore = 30)
        assertEquals(listOf(30), Tasks.noticeMinutes(edited))
        // Windows removed it.
        assertEquals(emptyList<Int>(), Tasks.noticeMinutes(t.copy(remindBefore = 0)))
        // A later Android save without alerts (another caller) also drops the stale list.
        val s = AppState(tasks = listOf(edited))
        val again = save(TaskInput(id = t.id, title = "r2", dueAt = due, remindBefore = 30), s).second
        assertTrue(AlertExt.of(again).alerts.isEmpty())
        assertEquals(30, again.remindBefore)
    }

    @Test fun noAlertsWithoutADueTime() {
        val t = save(TaskInput(title = "r", alerts = listOf(60, 1440))).second
        assertEquals(0, t.remindBefore)
        assertTrue(AlertExt.of(t).alerts.isEmpty())
    }

    @Test fun tagsStepsAndChoicesAreSavedTheWindowsWay() {
        val ext = AlertExt(style = "alarm", tone = ToneRef("bundled", "chime-2"), fullScreen = "off", pin = "off", vibrate = false, speak = true)
        val steps = listOf(Organize.Step("a", " جواز السفر ", true), Organize.Step("", "تذكرة", false), Organize.Step("x", "  ", false))
        val t = save(TaskInput(title = "سفر", dueAt = due, tags = listOf("#سفر", "سفر", "عمل"), steps = steps, ext = ext)).second
        assertEquals(listOf("سفر", "عمل"), t.tags)
        assertEquals(listOf("جواز السفر", "تذكرة"), t.steps.map { it.text })
        assertTrue(t.steps[1].id.isNotEmpty())
        val back = AlertExt.of(t)
        assertEquals("alarm", back.style); assertEquals("chime-2", back.tone?.ref); assertEquals("off", back.fullScreen)
        assertEquals("off", back.pin); assertEquals(false, back.vibrate); assertEquals(true, back.speak)
        // Fields left null keep what the task had.
        val s = AppState(tasks = listOf(t))
        val kept = save(TaskInput(id = t.id, title = "سفر 2", dueAt = due), s).second
        assertEquals(t.tags, kept.tags); assertEquals(t.steps, kept.steps); assertEquals("alarm", AlertExt.of(kept).style)
        // Empty lists remove the Windows keys.
        val cleared = save(TaskInput(id = t.id, title = "سفر", dueAt = due, tags = emptyList(), steps = emptyList(), ext = AlertExt()), s).second
        assertFalse("tags" in cleared.extra); assertFalse("checklist" in cleared.extra); assertFalse(AlertExt.KEY in cleared.extra)
    }

    @Test fun prayerAndHijriNeedTheirRepeatOrAreDropped() {
        val t = save(TaskInput(title = "r", dueAt = due, repeat = Repeat.WEEKLY, ext = AlertExt(prayer = "asr" to 15, hijri = 9 to 1))).second
        assertNull(AlertExt.of(t).prayer); assertNull(AlertExt.of(t).hijri)
        val p = save(TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY, ext = AlertExt(prayer = "asr" to 15))).second
        assertEquals("asr" to 15, AlertExt.of(p).prayer)
        // Windows sees a valid daily task.
        assertEquals("daily", Codec.encodeTask(p)["repeat"]!!.jsonPrimitive.content)
    }

    @Test fun anOutdatedEditorKeepsTheTimeAndAlertsButAppliesTagsAndChoices() {
        val (s0, t) = save(TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY, alerts = listOf(1440, 60)))
        // Done from the notification meanwhile: the task moved on.
        val (s1, _) = Tasks.complete(s0, t.id, now.plusSeconds(60), zone)!!
        val moved = s1.task(t.id)!!
        val input = TaskInput(id = t.id, title = "r edited", dueAt = due, repeat = Repeat.DAILY, alerts = listOf(5),
            updatedAt = t.updatedAt, timeEdited = false, tags = listOf("بيت"), ext = AlertExt(style = "alarm"))
        val out = Tasks.save(s1, input, now.plusSeconds(120), zone).second
        assertEquals(moved.dueAt, out.dueAt)
        assertEquals(listOf(1440, 60), Tasks.noticeMinutes(out))
        assertEquals("r edited", out.title); assertEquals(listOf("بيت"), out.tags); assertEquals("alarm", AlertExt.of(out).style)
    }

    private fun withSteps(done: List<Boolean>): Pair<AppState, Task> {
        val steps = done.mapIndexed { i, d -> Organize.Step("s$i", "step $i", d) }
        return save(TaskInput(title = "r", dueAt = due, steps = steps))
    }

    @Test fun tickingTheLastStepCompletesOnlyWithTheSwitchOn() {
        val (s, t) = withSteps(listOf(true, false))
        // Off: nothing is completed (F-071).
        val off = Tasks.toggleStep(s, t.id, "s1", true, autoComplete = false, now, zone)!!
        assertNull(off.second)
        assertNull(off.first.task(t.id)!!.completedAt)
        assertTrue(off.first.task(t.id)!!.steps.all { it.done })
        // On: the reminder is done.
        val on = Tasks.toggleStep(s, t.id, "s1", true, autoComplete = true, now, zone)!!
        assertNotNull(on.second)
        assertNotNull(on.first.task(t.id)!!.completedAt)
        // Unticking never completes; an unchanged step changes nothing.
        assertNull(Tasks.toggleStep(s, t.id, "s0", true, autoComplete = true, now, zone))
        assertNull(Tasks.toggleStep(s, t.id, "s0", false, autoComplete = true, now, zone)!!.second)
    }

    @Test fun savingTheEditorWithAllStepsTickedCompletesWithTheSwitchOn() {
        val (s, t) = withSteps(listOf(true, false))
        val all = t.steps.map { it.copy(done = true) }
        val off = Tasks.save(s, TaskInput(id = t.id, title = "r", dueAt = due, steps = all), now, zone).second
        assertNull(off.completedAt)
        val on = Tasks.save(s, TaskInput(id = t.id, title = "r", dueAt = due, steps = all, autoComplete = true), now, zone).second
        assertNotNull(on.completedAt)
        // A task whose steps were all ticked already is not completed by a text edit.
        val (s2, t2) = withSteps(listOf(true, true))
        assertNull(Tasks.save(s2, TaskInput(id = t2.id, title = "x", dueAt = due, steps = t2.steps, autoComplete = true), now, zone).second.completedAt)
    }

    @Test fun aRepeatsStepsAreUntickedForTheNextOccurrenceAndTheCopyKeepsThem() {
        val steps = listOf(Organize.Step("a", "one", true), Organize.Step("b", "two", false))
        val (s, t) = save(TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY, steps = steps))
        val (s1, c) = Tasks.toggleStep(s, t.id, "b", true, autoComplete = true, now, zone)!!
        assertNotNull(c)
        val next = s1.task(t.id)!!
        assertTrue(next.steps.none { it.done })
        assertTrue(next.dueAt!!.isAfter(due))
        val copy = s1.task(c!!.copyId)!!
        assertTrue(copy.steps.all { it.done })
    }

    @Test fun aPrayerTaskMovesWithThePrayerHook() {
        val asked = ArrayList<Triple<String, Int, java.time.Instant>>()
        val fixed = at(zone, 2026, 11, 17, 15, 5)
        Tasks.extRepeat = { ExtRepeat(prayerNext = { n, o, a -> asked += Triple(n, o, a); fixed }) }
        val (s, t) = save(TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY, ext = AlertExt(prayer = "asr" to 15)))
        val (s1, _) = Tasks.complete(s, t.id, now, zone)!!
        val moved = s1.task(t.id)!!
        assertEquals(fixed, moved.dueAt); assertEquals(fixed, moved.scheduleAt); assertEquals(fixed, moved.rule.from)
        // Done before the due time: the next one is after the current occurrence, not after now.
        assertEquals(Triple("asr", 15, due), asked.single())
        // Skip uses it too.
        val skipped = Tasks.skip(s, t.id, now, zone)!!.task(t.id)!!
        assertEquals(fixed, skipped.dueAt)
        // Without a setup (null), the Windows daily rule applies.
        Tasks.extRepeat = { ExtRepeat() }
        val daily = Tasks.complete(s, t.id, now, zone)!!.first.task(t.id)!!
        assertEquals(due.plusSeconds(86_400), daily.dueAt)
    }

    @Test fun undoOfAPrayerCompletionRestoresTheAnchor() {
        val fixed = at(zone, 2026, 11, 17, 15, 5)
        Tasks.extRepeat = { ExtRepeat(prayerNext = { _, _, _ -> fixed }) }
        val (s, t) = save(TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY, ext = AlertExt(prayer = "maghrib" to -10)))
        val (s1, c) = Tasks.complete(s, t.id, now, zone)!!
        val back = Tasks.undoComplete(s1, c, now).task(t.id)!!
        assertEquals(due, back.dueAt); assertEquals(due, back.rule.from)
    }

    @Test fun unknownAndroidMembersSurviveAnEdit() {
        val t = Task(id = "k", title = "r", dueAt = due, createdAt = now, updatedAt = now,
            extra = JsonObject(mapOf(AlertExt.KEY to JsonObject(mapOf("v" to JsonPrimitive(1), "lock" to JsonPrimitive("hide"), "future" to JsonArray(emptyList()))))))
        val s = AppState(tasks = listOf(t))
        val out = save(TaskInput(id = "k", title = "r2", dueAt = due, ext = AlertExt.of(t).copy(style = "normal")), s).second
        val o = out.extra[AlertExt.KEY]!!.jsonObject
        assertEquals("hide", o["lock"]!!.jsonPrimitive.content); assertTrue("future" in o); assertEquals("normal", o["style"]!!.jsonPrimitive.content)
    }
}
