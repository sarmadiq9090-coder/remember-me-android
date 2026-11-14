package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/** Quick add: no invented dates on ordinary titles, and no crash or past time on any text. */
class QuickAddTest {
    private val baghdad = ZoneId.of("Asia/Baghdad")
    private val monday = at(baghdad, 2026, 11, 9, 10, 0)

    @Test fun noDateCorpusInventsNothing() {
        val lines = javaClass.getResource("/quickadd-nodate.txt")!!.readText().lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("corpus has ${lines.size} titles", lines.size >= 200)
        val invented = ArrayList<String>()
        for (zone in listOf(baghdad, ZoneId.of("Europe/Berlin"), ZoneId.of("America/New_York"))) {
            for (now in listOf(at(zone, 2026, 11, 9, 10, 0), at(zone, 2026, 11, 13, 23, 0), at(zone, 2026, 3, 28, 22, 30))) {
                for (text in lines) {
                    val r = QuickAdd.parseStrict(text, now, zone)
                    if (r.understood || r.dueAt != null || r.ask != null || r.title != text) invented += "«$text» at $now in ${zone.id}: $r"
                }
            }
        }
        assertEquals(invented.take(10).joinToString("\n"), 0, invented.size)
    }

    @Test fun examplesFromTheWindowsTests() {
        val r = QuickAdd.parse("اتصل بأمي بكرة ٥ العصر", monday, baghdad)
        assertEquals("اتصل بأمي", r.title)
        assertEquals(at(baghdad, 2026, 11, 10, 17, 0), r.dueAt)
        assertNull(r.ask)
        val ask = QuickAdd.parse("بكرة ٥", monday, baghdad)
        assertEquals(Ask.MERIDIEM, ask.ask)
        assertNull(ask.dueAt)
        assertEquals(listOf("am", "pm"), ask.options.map { it.labelHint })
        val gym = QuickAdd.parse("gym every monday and thursday 7pm", monday, baghdad)
        assertEquals(Repeat.DAYS, gym.repeat)
        assertEquals(listOf(1, 4), gym.days)
        val none = QuickAdd.parse("buy 2 apples", monday, baghdad)
        assertFalse(none.understood)
        assertEquals("buy 2 apples", none.title)
    }

    @Test fun outsideTheEnglishSubsetIsNoDate() {
        for (text in listOf("call mom yesterday 5pm", "meeting last friday", "workout for 30 minutes", "dentist 5/12", "party next month", "call at 5pm EST", "lunch 12-1pm", "trip in 2 weeks")) {
            val r = QuickAdd.parseStrict(text, monday, baghdad)
            assertFalse(text, r.understood)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fuzz: 20,000 random texts never throw, never answer with a time in the past, never lose the title.
    // ---------------------------------------------------------------------------------------------
    private val vocabulary = listOf(
        "بكرة", "باچر", "غداً", "بعد", "بكره", "اليوم", "الليلة", "الخميس", "الجاي", "الياي", "القادم", "الماضي", "كل", "يوم", "يومين", "أسبوع",
        "شهر", "سنة", "ساعة", "ساعتين", "دقايق", "نص", "ربع", "إلا", "ونص", "العصر", "الصبح", "المغرب", "بالليل", "نص الليل", "الفجر", "الظهر",
        "الساعة", "على", "عالساعة", "خمسة", "ثلاثة", "اثنين", "الثانية عشرة", "حدعش", "م", "ص", "هالجمعة", "يوم الأحد", "اشتري", "كيلو", "سورة",
        "tomorrow", "today", "tonight", "at", "in", "next", "this", "every", "each", "day", "days", "hours", "minutes", "morning", "noon", "evening",
        "night", "pm", "am", "a.m.", "Thursday", "monday", "fri", "sat", "Nov", "may", "weekdays", "daily", "weekly", "from now", "later", "on",
        "o'clock", "and", ",", ":", "-", "/", ".", "buy", "call", "mom", "5", "12", "7:30", "07:30", "17:00", "0", "23", "24", "99", "123",
        "٥", "١٢", "٧:٣٠", "۵", "۱۷:۴۵", "٫", "🙂", "🎉", "👨‍👩‍👧", "‏", "‫", "‎", " ", "　", "\t", "\n", "﻿", "İ", "ß",
    )

    private fun randomText(rnd: Random): String = when (rnd.nextInt(20)) {
        0 -> ""
        1 -> " ".repeat(rnd.nextInt(1, 10))
        2 -> buildString { repeat(5000) { append(vocabulary[rnd.nextInt(vocabulary.size)][0]) } }
        3 -> buildString { repeat(rnd.nextInt(1, 40)) { append(rnd.nextInt(0x20, 0x3000).toChar()) } }
        4 -> (1..rnd.nextInt(1, 300)).joinToString(" ") { vocabulary[rnd.nextInt(vocabulary.size)] }
        else -> (1..rnd.nextInt(1, 8)).joinToString(if (rnd.nextBoolean()) " " else "") { vocabulary[rnd.nextInt(vocabulary.size)] }
    }

    @Test fun fuzzNeverThrowsNorAnswersInThePast() {
        val rnd = Random(20261115)
        val zones = listOf(baghdad, ZoneId.of("Europe/Berlin"), ZoneId.of("America/New_York"), ZoneId.of("UTC"), ZoneId.of("Asia/Beirut"))
        val nows = zones.flatMap { z -> listOf(at(z, 2026, 11, 9, 10, 0), at(z, 2026, 3, 28, 23, 59), at(z, 2026, 10, 25, 1, 30), at(z, 2026, 12, 31, 23, 59)) }
        repeat(20_000) { i ->
            val text = randomText(rnd)
            val zone = zones[i % zones.size]
            val now = nows[rnd.nextInt(nows.size)].plusSeconds(rnd.nextLong(0, 59))
            val r = try {
                QuickAdd.parseStrict(text, now, zone, listOf(0, 1, 6, 3)[rnd.nextInt(4)])
            } catch (e: Exception) {
                fail("«$text» threw $e"); return
            }
            r.dueAt?.let { assertTrue("«$text»: due $it is not after $now", it.isAfter(now)) }
            assertTrue("«$text»: an option in the past", r.options.all { it.dueAt.isAfter(now) })
            assertTrue("«$text»: ${r.options.size} options", r.options.size <= 4)
            assertEquals("«$text»: options out of order", r.options.map { it.dueAt }.sorted(), r.options.map { it.dueAt })
            if (r.understood) assertEquals("«$text»: ask and dueAt", r.ask == null, r.dueAt != null)
            if (text.any { !Js.isSpace(it) }) assertTrue("«$text»: empty title", r.title.isNotEmpty())
        }
    }
}
