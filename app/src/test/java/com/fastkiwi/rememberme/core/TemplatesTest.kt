package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

/** Templates (F-047): the built-ins as data, and a template only fills the editor. */
class TemplatesTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 16, 16, 20)

    @Test fun builtInsMatchTheSpecGroups() {
        val by = Templates.BUILT_IN.groupBy { it.group }.mapValues { (_, v) -> v.map { it.key } }
        assertEquals(listOf("birthday", "anniversary", "yearly"), by[TemplateGroup.EVENTS])
        assertEquals(listOf("bill", "medical", "document"), by[TemplateGroup.BILLS])
        assertEquals(listOf("medicine", "maintenance", "weekly"), by[TemplateGroup.ROUTINE])
        assertNull(by[TemplateGroup.MINE])
    }

    @Test fun everyBuiltInFillsAValidReminderInTheFuture() {
        for (t in Templates.BUILT_IN) {
            val fill = Templates.fill(t, "اسم", now, zone)
            val due = fill.input.dueAt!!
            assertTrue(t.key, due.isAfter(now))
            assertEquals(t.key, LocalTime.of(9, 0), due.atZone(zone).toLocalTime())
            assertTrue(t.key, (listOf(fill.input.remindBefore) + fill.ext.alerts).filter { it > 0 }.all { it in EARLY_MINUTES })
            val (_, task) = Tasks.save(AppState(), fill.input, now, zone)
            assertEquals(t.repeat, task.repeat)
            assertEquals("اسم", task.title)
        }
    }

    @Test fun theNearestAlertIsTheOneWindowsReads() {
        val medical = Templates.fill(Templates.BUILT_IN.first { it.key == "medical" }, "موعد طبي", now, zone)
        assertEquals(60, medical.input.remindBefore)
        assertEquals(listOf(1440), medical.ext.alerts)
        val birthday = Templates.fill(Templates.BUILT_IN.first { it.key == "birthday" }, "عيد ميلاد", now, zone)
        assertEquals(Repeat.YEARLY, birthday.input.repeat)
        assertEquals(1440, birthday.input.remindBefore)
        assertTrue(birthday.ext.alerts.isEmpty())
        val medicine = Templates.fill(Templates.BUILT_IN.first { it.key == "medicine" }, "دواء يومي", now, zone)
        assertEquals("alarm", medicine.ext.style)
        assertEquals(Repeat.DAILY, medicine.input.repeat)
        val maintenance = Templates.fill(Templates.BUILT_IN.first { it.key == "maintenance" }, "صيانة", now, zone)
        assertEquals(Repeat.INTERVAL, maintenance.input.repeat)
        assertEquals(180, maintenance.input.everyDays)
    }

    @Test fun nineOClockTodayOrTomorrow() {
        assertEquals(at(zone, 2026, 11, 17, 9, 0), Templates.nextAt(LocalTime.of(9, 0), now, zone))
        assertEquals(at(zone, 2026, 11, 16, 18, 0), Templates.nextAt(LocalTime.of(18, 0), now, zone))
    }

    @Test fun aUserTemplateKeepsTheTimeOfDayNotTheDate() {
        val input = TaskInput(title = "اجتماع الفريق", body = "غرفة 2", dueAt = at(zone, 2026, 12, 3, 18, 30), repeat = Repeat.WEEKLY,
            remindBefore = 15, priority = Priority.HIGH, category = Category.WORK)
        val t = Templates.fromInput("k", "  اجتماع  ", input, AlertExt(alerts = listOf(60), style = "alarm"), zone)
        assertEquals("اجتماع", t.name)
        assertEquals(LocalTime.of(18, 30), t.time)
        assertEquals(listOf(60, 15), t.alerts)
        val fill = Templates.fill(t, "ignored", now, zone)
        assertEquals("اجتماع الفريق", fill.input.title)
        assertEquals(at(zone, 2026, 11, 16, 18, 30), fill.input.dueAt)
        assertEquals(15, fill.input.remindBefore)
        assertEquals(listOf(60), fill.ext.alerts)
        assertEquals("alarm", fill.ext.style)
        assertEquals(Repeat.WEEKLY, fill.input.repeat)
        assertEquals(Priority.HIGH, fill.input.priority)
        assertEquals(Category.WORK, fill.input.category)
        // Without a date the template is a note: no repeat, no alerts.
        val note = Templates.fromInput("n", "", TaskInput(title = "قراءة", repeat = Repeat.NONE), null, zone)
        assertEquals("قراءة", note.name)
        assertNull(Templates.fill(note, "", now, zone).input.dueAt)
    }
}
