package com.fastkiwi.rememberme.ui.quick

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.at
import com.fastkiwi.rememberme.core.quickadd.Ask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Quick Add's state (F-042): the preview is what is saved, and nothing is saved with a date not yet seen. */
class QuickAddStateTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 16, 16, 20) // Monday 4:20 PM

    private fun state(text: String = "") = QuickAddState(text, zone, 6) { now }

    private fun QuickAddState.type(text: String) { onTextChange(text); parseNow() }

    @Test fun aClearTimeIsPreviewedAndSaved() {
        val s = state()
        s.type("ذكرني أتصل بأمي بعد ساعة")
        assertEquals(now.plusSeconds(3600), s.dueAt)
        assertEquals("أتصل بأمي", s.title)
        assertNotNull(s.phrase)
        assertTrue(s.phrase!!.contains("ساعة"))
        assertTrue(s.canSave)
        val input = s.toInput()!!
        assertEquals("أتصل بأمي", input.title)
        assertEquals(now.plusSeconds(3600), input.dueAt)
        // It goes through the same save as the editor.
        val (_, task) = Tasks.save(AppState(), input, now, zone)
        assertEquals(now.plusSeconds(3600), task.dueAt)
    }

    @Test fun whileTheTextChangesNothingCanBeSaved() {
        val s = state()
        s.type("اتصل بأمي بعد ساعة")
        assertTrue(s.canSave)
        s.onTextChange("اتصل بأمي بعد ساعتين")
        assertTrue(s.pending)
        assertFalse(s.canSave)
        assertNull(s.toInput())
        s.parseNow()
        assertEquals(now.plusSeconds(7200), s.toInput()!!.dueAt)
    }

    @Test fun anAmbiguousTimeAsksAndBlocksSaveUntilAChoice() {
        val s = state()
        s.type("اتصل بأحمد الخميس 9")
        assertTrue(s.asking)
        assertEquals(Ask.MERIDIEM, s.result!!.ask)
        assertNull(s.dueAt)
        assertFalse(s.canSave)
        assertNull(s.toInput())
        assertTrue(s.options.size in 2..4)
        // A date that was not offered (never seen) is refused.
        s.choose(QuickChoice.At(now.plusSeconds(999)))
        assertNull(s.choice)
        assertFalse(s.canSave)
        val pm = s.options.last().dueAt
        s.choose(QuickChoice.At(pm))
        assertTrue(s.canSave)
        assertEquals(pm, s.toInput()!!.dueAt)
        assertEquals(at(zone, 2026, 11, 19, 21, 0), pm)
        // A new reading forgets the choice.
        s.type("اتصل بأحمد الخميس 8")
        assertNull(s.choice)
        assertFalse(s.canSave)
    }

    @Test fun noDateKeepsTheWholeTextAndDropsTheRepeat() {
        val s = state()
        s.type("اتصل بأحمد الخميس 9")
        s.choose(QuickChoice.NoDate)
        assertTrue(s.canSave)
        val input = s.toInput()!!
        assertNull(input.dueAt)
        assertEquals("اتصل بأحمد الخميس 9", input.title)
        val r = state()
        r.type("رياضة كل يوم 7 الصبح")
        assertEquals(Repeat.DAILY, r.repeat)
        r.clearDate()
        assertTrue(r.noDate)
        val i2 = r.toInput()!!
        assertNull(i2.dueAt)
        assertEquals(Repeat.NONE, i2.repeat)
        assertEquals("رياضة كل يوم 7 الصبح", i2.title)
    }

    @Test fun nothingUnderstoodSavesTheTextWithNoDate() {
        val s = state()
        s.type("buy 2 apples")
        assertTrue(s.noDate)
        assertNull(s.dueAt)
        assertTrue(s.canSave)
        assertEquals("buy 2 apples", s.toInput()!!.title)
        assertFalse(state("   ").canSave)
    }

    @Test fun aLongSharedTextIsCutAndKeptAsTheNote() {
        val long = "عنوان المقال\n" + "سطر طويل ".repeat(400)
        val s = state(long)
        assertEquals(QuickAddState.MAX_INPUT, s.text.length)
        val input = s.toInput()!!
        assertEquals("عنوان المقال", input.title)
        assertEquals(s.text.trim(), input.body)
        val one = state("x".repeat(500)).toInput()!!
        assertEquals(200, one.title.length)
        Tasks.save(AppState(), one, now, zone) // a valid title
    }

    @Test fun moreOptionsCarriesWhatIsShownAndNoUnseenDate() {
        val s = state()
        s.type("اتصل بأحمد الخميس 9")
        assertNull(s.draft().dueAt)
        s.type("رياضة كل يوم 7 الصبح")
        val d = s.draft()
        assertEquals(Repeat.DAILY, d.repeat)
        assertEquals(s.dueAt, d.dueAt)
        assertEquals("رياضة", d.title)
    }

    @Test fun dictationIsAppended() {
        val s = state()
        s.append(" اتصل بأمي ")
        assertEquals("اتصل بأمي", s.text)
        s.append("بعد ساعة")
        assertEquals("اتصل بأمي بعد ساعة", s.text)
    }
}
