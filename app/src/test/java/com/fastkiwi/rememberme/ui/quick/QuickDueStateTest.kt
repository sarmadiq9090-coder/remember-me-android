package com.fastkiwi.rememberme.ui.quick

import com.fastkiwi.rememberme.core.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId

/**
 * 1.0.3, the owner: «اضف موعد تذكير في هذه شاشه». The «الموعد» chip in Quick Add: a date and time picked by hand,
 * kept in sync with what the text says until the person picks one, which then wins.
 */
class QuickDueStateTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 16, 16, 20) // Monday 4:20 PM

    private fun state(text: String = "") = QuickAddState(text, zone, 6) { now }
    private fun QuickAddState.type(text: String) { onTextChange(text); parseNow() }

    @Test fun theDefaultIsTheNextFullHourToday() {
        assertEquals(at(zone, 2026, 11, 16, 17, 0), state().suggestedAt())
    }

    @Test fun aPickedTimeWithAPlainTitleCanBeSaved() {
        val s = state()
        s.type("اتصل بأمي")
        val nine = at(zone, 2026, 11, 17, 9, 0)
        assertTrue(s.pick(nine))
        assertEquals(nine, s.dueAt)
        assertTrue(s.picked)
        assertTrue(s.canSave)
        val input = s.toInput()!!
        assertEquals("اتصل بأمي", input.title)
        assertEquals(nine, input.dueAt)
    }

    @Test fun theChipFollowsTheTextUntilAPickWhichThenWins() {
        val s = state()
        s.type("اتصل بأمي بعد ساعة")
        // The text's reading fills the chip.
        assertEquals(now.plus(Duration.ofHours(1)), s.dueAt)
        assertFalse(s.picked)
        val nine = at(zone, 2026, 11, 17, 9, 0)
        s.pick(nine)
        assertEquals(nine, s.dueAt)
        assertEquals("اتصل بأمي", s.title)
        // More typing with another time does not override the pick.
        s.type("اتصل بأمي بعد ساعتين")
        assertEquals(nine, s.dueAt)
        assertTrue(s.canSave)
        // A pick answers an open question too («الساعة 5»: صباحًا أم مساءً؟).
        s.type("اتصل بأحمد الساعة 5")
        assertFalse(s.asking)
        assertEquals(nine, s.dueAt)
    }

    @Test fun theChipsCrossRemovesTheDate() {
        val s = state()
        s.type("اتصل بأمي بعد ساعة")
        s.pick(at(zone, 2026, 11, 17, 9, 0))
        s.clearPick()
        assertNull(s.dueAt)
        assertFalse(s.picked)
        // Saved as a title with no date (as with the preview's ✕).
        assertTrue(s.canSave)
        assertNull(s.toInput()!!.dueAt)
    }

    @Test fun aPastTimeIsNotTakenButTomorrowIsOffered() {
        val s = state()
        s.type("اتصل بأمي")
        val earlier = at(zone, 2026, 11, 16, 9, 0)
        assertFalse(s.pick(earlier))
        assertNull(s.dueAt)
        assertEquals(earlier, s.pastPick)
        s.pickTomorrow()
        assertEquals(at(zone, 2026, 11, 17, 9, 0), s.dueAt)
        assertNull(s.pastPick)
    }
}
