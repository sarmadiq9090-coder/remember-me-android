package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.quickadd.QuickAdd
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Fuzz (torture suite 4): Quick Add on 20,000 mixed Arabic / English / emoji / RTL-mark strings: no crash (the
 * strict parser, without its safety net), never a time in the past, at most four options in order; and the
 * no-date corpus (quickadd-nodate.txt) with the decorations people really type around a title (RTL marks, emoji,
 * punctuation, no-break spaces, kashida) still holds no date.
 */
class QuickAddFuzzTortureTest {
    @Before fun on() = Torture.assumeOn()

    private fun strict(text: String, now: Instant, zone: ZoneId, week: Int) = QuickAdd.parseStrict(text, now, zone, week)

    private val words = listOf(
        "بكرة", "باچر", "غداً", "غدا", "بعد", "بكره", "اليوم", "الليلة", "الخميس", "الجمعة", "السبت", "الجاي", "الياي", "القادم", "كل", "يوم", "يومين",
        "أسبوع", "شهر", "سنة", "ساعة", "ساعتين", "دقايق", "دقيقة", "نص", "ربع", "إلا", "ونص", "العصر", "الصبح", "المغرب", "بالليل", "الفجر", "الظهر",
        "الساعة", "عالساعة", "خمسة", "ثلاث", "اثنين", "الثانية عشرة", "م", "ص", "مساءً", "صباحاً", "اتصل", "بأمي", "اشتري", "كيلو", "موعد",
        "tomorrow", "today", "tonight", "at", "in", "next", "this", "every", "day", "days", "hours", "minutes", "morning", "noon", "evening",
        "pm", "am", "Thursday", "monday", "fri", "Nov", "December", "31", "29", "Feb", "weekdays", "daily", "weekly", "on", "o'clock", "and",
        "5", "12", "7:30", "07:30", "17:00", "24:00", "23:59", "0", "00:00", "99", "٥", "١٢", "٧:٣٠", "۵", "۱۷:۴۵", "٫", "،", "؟",
        "🙂", "🎉", "👨‍👩‍👧", "🇮🇶", "‏", "‎", "؜", "‫", "‬", "⁧", "⁩", "‍", "‌", " ", "　", "\t", "\n",
        "﻿", "ـ", "ً", "ّ", "İ", "ß", "\uD83D", "\uDE00",
    )

    private fun randomText(rnd: Random): String = when (rnd.nextInt(12)) {
        0 -> buildString { repeat(rnd.nextInt(1, 60)) { append(rnd.nextInt(0, 0xFFFF).toChar()) } }
        1 -> (1..rnd.nextInt(1, 250)).joinToString(" ") { words[rnd.nextInt(words.size)] }
        2 -> words[rnd.nextInt(words.size)].repeat(rnd.nextInt(1, 80))
        else -> (1..rnd.nextInt(1, 9)).joinToString(listOf(" ", "", "  ", " ", "‏")[rnd.nextInt(5)]) { words[rnd.nextInt(words.size)] }
    }

    @Test fun twentyThousandMixedStringsNeverCrashNorAnswerInThePast() {
        val seed = Torture.seed("quickadd-fuzz", 10_001)
        val rnd = Random(seed)
        val zones = listOf("Asia/Baghdad", "Europe/Berlin", "America/New_York", "Asia/Tehran", "Australia/Sydney", "Pacific/Chatham", "America/Santiago").map(ZoneId::of)
        val bad = ArrayList<String>()
        repeat(Torture.count(20_000, 200_000)) { i ->
            val text = randomText(rnd)
            val zone = zones[rnd.nextInt(zones.size)]
            // Ordinary moments and the awkward ones: just before midnight, inside a DST night, 31 December, 29 February.
            val local = listOf(LocalDateTime.of(2026, 11, 15, 10, 0), LocalDateTime.of(2026, 11, 15, 23, 59, 59), LocalDateTime.of(2026, 3, 29, 1, 59),
                LocalDateTime.of(2026, 10, 25, 2, 30), LocalDateTime.of(2026, 12, 31, 23, 59), LocalDateTime.of(2028, 2, 29, 12, 0), LocalDateTime.of(2026, 3, 8, 2, 30))[rnd.nextInt(7)]
            val now = ZonedDateTime.of(local, zone).toInstant().plusMillis(rnd.nextLong(0, 59_000))
            val r = try { strict(text, now, zone, listOf(0, 1, 6, 3, -1)[rnd.nextInt(5)]) } catch (e: Throwable) {
                bad += "case $i «${text.take(60)}» threw ${e::class.java.simpleName}: ${e.message?.take(80)}"; return@repeat
            }
            r.dueAt?.let { if (!it.isAfter(now)) bad += "«${text.take(60)}» at $now in ${zone.id}: due $it is not after now" }
            if (r.options.any { !it.dueAt.isAfter(now) }) bad += "«${text.take(60)}»: an option in the past ${r.options}"
            if (r.options.size > 4) bad += "«${text.take(60)}»: ${r.options.size} options"
            if (r.options.map { it.dueAt } != r.options.map { it.dueAt }.sorted()) bad += "«${text.take(60)}»: options out of order"
            if (r.understood && (r.ask == null) != (r.dueAt != null)) bad += "«${text.take(60)}»: ask ${r.ask} with due ${r.dueAt}"
            if (!r.understood && r.title != text) bad += "«${text.take(60)}»: not understood but the title changed"
            if (r.understood && r.title.isBlank() && text.isNotBlank() && r.title.length > 200) bad += "«${text.take(60)}»: title"
        }
        assertEquals(bad.take(8).joinToString("\n"), 0, bad.size)
    }

    @Test fun theNoDateCorpusWithEverydayDecorationsStillHoldsNoDate() {
        val corpus = javaClass.getResource("/quickadd-nodate.txt")!!.readText().lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(corpus.size >= 200)
        val decorate: List<Pair<String, (String) -> String>> = listOf(
            "RLM around" to { s -> "‏$s‏" },
            "LRM around" to { s -> "‎$s‎" },
            "ALM after" to { s -> "$s؜" },
            "emoji after" to { s -> "$s 🙂" },
            "emoji before" to { s -> "🛒 $s" },
            "flag after" to { s -> "$s 🇮🇶" },
            "exclamation" to { s -> "$s!" },
            "Arabic question" to { s -> "$s؟" },
            "full stop" to { s -> "$s." },
            "Arabic comma inside" to { s -> s.replaceFirst(" ", "، ") },
            "no-break spaces" to { s -> s.replace(' ', ' ') },
            "double spaces" to { s -> s.replace(" ", "  ") },
            "kashida" to { s -> s.replace("ل", "لـ") },
            "BOM before" to { s -> "﻿$s" },
            "trailing newline" to { s -> "$s\n" },
            "RTL embedding" to { s -> "‫$s‬" },
            "isolate" to { s -> "⁧$s⁩" },
        )
        val zone = ZoneId.of("Asia/Baghdad")
        val nows = listOf(LocalDateTime.of(2026, 11, 9, 10, 0), LocalDateTime.of(2026, 11, 13, 23, 0)).map { ZonedDateTime.of(it, zone).toInstant() }
        val invented = ArrayList<String>()
        val inventedTexts = LinkedHashSet<String>()
        val byKind = HashMap<String, Int>()
        for (line in corpus) for ((kind, f) in decorate) for (now in nows) {
            val text = f(line)
            val r = strict(text, now, zone, 6)
            // Accepted (lead, 2026-11-15): «الخميس، الماضي» / «السبت، اللي فات» are read the same way by the
            // Windows parser (checked with parseWhen); Quick Add then asks for a time and never saves a date
            // the user has not seen. Kept for parity; noted for both apps' backlog.
            if (r.dueAt == null && r.ask != null && SHARED_WITH_WINDOWS.any { text.contains(it) }) continue
            if (r.understood || r.dueAt != null || r.ask != null) { invented += "$kind «$text» → due=${r.dueAt} ask=${r.ask} title=«${r.title}»"; inventedTexts += text; byKind.merge(kind, 1, Int::plus) }
        }
        // Which of them the Windows app reads the same way (a shared rule) and which only Android invents.
        val texts = inventedTexts.toList()
        val windows = windowsReadings(texts, nows.first(), zone)
        val androidOnly = texts.filterIndexed { i, _ -> windows[i] == null }
        Torture.report("quickadd", "decorated no-date corpus: ${invented.size} readings with a date $byKind; Android only: ${androidOnly.size} ${androidOnly.take(12).map { "«$it»" }}; " +
            "shared with Windows: ${texts.size - androidOnly.size} ${texts.filterIndexed { i, _ -> windows[i] != null }.take(6).map { "«$it»" }}")
        assertEquals("Android reads a date where the Windows app reads none: " + androidOnly.take(8).joinToString { "«$it» (${it.map { c -> "%04X".format(c.code) }.joinToString(" ")})" }, 0, androidOnly.size)
        assertEquals(invented.take(8).joinToString("\n"), 0, invented.size)
    }

    /** The Windows parseWhen() reading of each text (null = no date), via torture/tools/quickadd-windows.cjs. */
    private fun windowsReadings(texts: List<String>, now: Instant, zone: ZoneId): List<JsonElement?> {
        if (texts.isEmpty()) return emptyList()
        val dir = java.io.File(Torture.root, "app/build/torture").apply { mkdirs() }
        val input = java.io.File(dir, "quickadd-cases.json")
        val output = java.io.File(dir, "quickadd-answers.json")
        input.writeText(JsonArray(texts.map { t ->
            JsonObject(mapOf("text" to JsonPrimitive(t), "now" to JsonPrimitive(now.toString()), "zone" to JsonPrimitive(zone.id)))
        }).toString())
        Torture.node("quickadd-windows.cjs", Torture.windowsDir().path, input.path, output.path)
        val answers = Codec.json.parseToJsonElement(output.readText()) as JsonArray
        return answers.map { a -> (a as JsonObject)["reading"]?.takeIf { it !is JsonNull } }
    }

    private companion object {
        val SHARED_WITH_WINDOWS = listOf("الماضي", "اللي فات")
    }
}
