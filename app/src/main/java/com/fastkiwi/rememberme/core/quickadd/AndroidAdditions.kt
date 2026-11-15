package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Tasks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Quick Add readings that exist only on Android (FEATURES.md F-042 "Additions for 1.0.0"), tried before
 * the Windows parser and kept out of the Windows parity set:
 * - «كل 3 ساعات» / "every 3 hours": an every-N-hours repeat starting N hours from now.
 * - «بعد المغرب», «عند الفجر», «بعد صلاة العشاء»: the next time of that prayer, only when prayer times are
 *   set up on this phone (otherwise the Windows reading applies, a part of the day). «بعد الظهر» alone is the
 *   everyday word for "afternoon", so it counts only as «صلاة الظهر». A text with any clock time or digit
 *   besides the hours count is left to the Windows parser.
 * - «20/11», «20/11/2027», "Sara birthday 20/11 5pm": a numeric date, day/month as written in the Arab world
 *   (month/day only when the first number cannot be a month). With a clear time it is that time; otherwise the
 *   usual question with morning, noon and evening, so a time the user has not seen is never saved.
 */
internal object AndroidAdditions {
    private const val INDIC = "٠١٢٣٤٥٦٧٨٩"
    private const val PERSIAN = "۰۱۲۳۴۵۶۷۸۹"

    private fun western(s: String) = buildString(s.length) {
        for (c in s) append(when (c) { in INDIC -> '0' + INDIC.indexOf(c); in PERSIAN -> '0' + PERSIAN.indexOf(c); else -> c })
    }

    private val EVERY_HOURS = Regex("(?:^|\\s)(?:كل|every)\\s+(\\d{1,2})\\s*(?:ساعات|ساعة|ساعه|hours?|hrs?)(?=\\s|$|[.,،!؟?])", RegexOption.IGNORE_CASE)
    private val PRAYER = Regex("(?:^|\\s)(?:(بعد|قبل|عند|وقت)\\s+)?(صلاة\\s+|صلاه\\s+)?(الفجر|الظهر|العصر|المغرب|العشاء)(?=\\s|$|[.,،!؟?])")
    private val NAMES = mapOf("الفجر" to "fajr", "الظهر" to "dhuhr", "العصر" to "asr", "المغرب" to "maghrib", "العشاء" to "isha")

    private val NUM_DATE = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?![\\d/])")
    private val CLOCK = Regex("(?<![\\d:])(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|ص|م|صباحا|صباحًا|صباحاً|مساء|مساءً|الصبح|المسا|العصر|الظهر|بالليل)?(?![\\d])", RegexOption.IGNORE_CASE)
    private val PM_WORDS = setOf("pm", "p.m.", "م", "مساء", "مساءً", "المسا", "العصر", "بالليل")
    private val AM_WORDS = setOf("am", "a.m.", "ص", "صباحا", "صباحًا", "صباحاً", "الصبح")

    private fun numericDate(t: String, text: String, now: Instant, zone: ZoneId): QuickResult? {
        val m = NUM_DATE.find(t) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val (day, month) = if (b in 1..12 && a in 1..31) a to b else if (a in 1..12 && b in 13..31) b to a else return null
        val today = now.atZone(zone).toLocalDate()
        val yearText = m.groupValues[3]
        var date = runCatching {
            val y = if (yearText.isEmpty()) today.year else yearText.toInt().let { if (it < 100) 2000 + it else it }
            LocalDate.of(y, month, day)
        }.getOrNull() ?: return null
        if (yearText.isEmpty() && date.isBefore(today)) date = date.plusYears(1)
        var rest = cut(t, m.range)
        val c = CLOCK.find(rest)?.takeIf { it.groupValues[1].toInt() in 0..23 }
        val options: List<QuickOption>
        var ask: Ask? = null
        var dueAt: Instant? = null
        if (c != null) {
            var hour = c.groupValues[1].toInt()
            val minute = c.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
            val word = c.groupValues[3].lowercase()
            rest = cut(rest, c.range)
            when {
                word in PM_WORDS && hour in 1..11 -> hour += 12
                word in AM_WORDS && hour == 12 -> hour = 0
            }
            val clear = word.isNotEmpty() || hour == 0 || hour >= 13
            if (clear) {
                dueAt = com.fastkiwi.rememberme.core.localToInstant(date.atTime(LocalTime.of(hour, minute)), zone)
                options = emptyList()
            } else {
                ask = Ask.MERIDIEM
                options = listOf(hour to "am", (hour % 12 + 12) to "pm").map { (h, hint) ->
                    QuickOption(com.fastkiwi.rememberme.core.localToInstant(date.atTime(LocalTime.of(h, minute)), zone), hint) }
            }
        } else {
            ask = Ask.TIME
            options = listOf(9 to "morning", 12 to "noon", 18 to "evening").map { (h, hint) ->
                QuickOption(com.fastkiwi.rememberme.core.localToInstant(date.atTime(LocalTime.of(h, 0)), zone), hint) }
        }
        val future = options.filter { it.dueAt.isAfter(now) }
        if (dueAt != null && !dueAt.isAfter(now)) return null
        if (dueAt == null && future.isEmpty()) return null
        return QuickResult(title = rest.ifBlank { text }, dueAt = dueAt, ask = ask, options = future, understood = true)
    }

    private fun cut(text: String, range: IntRange): String =
        (text.substring(0, range.first) + " " + text.substring(range.last + 1)).replace(Regex("\\s+"), " ").trim()

    fun parse(text: String, now: Instant, zone: ZoneId): QuickResult? {
        val t = western(text)
        EVERY_HOURS.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..23 } ?: return null
            val rest = cut(t, m.range)
            if (rest.any { it.isDigit() }) return null
            val first = now.truncatedTo(ChronoUnit.MINUTES).plus(n.toLong(), ChronoUnit.HOURS)
            return QuickResult(title = rest.ifBlank { text }, dueAt = first, repeat = Repeat.HOURS, everyHours = n, understood = true)
        }
        numericDate(t, text, now, zone)?.let { return it }
        val next = Tasks.extRepeat().prayerNext ?: return null
        val m = PRAYER.find(t) ?: return null
        val word = m.groupValues[1]
        val salah = m.groupValues[2].isNotBlank()
        val name = NAMES[m.groupValues[3]] ?: return null
        // «بعد الظهر» = "in the afternoon" in everyday Arabic: only «صلاة الظهر» means the prayer.
        if (name == "dhuhr" && !salah) return null
        if (word.isEmpty() && !salah) return null
        if (word == "قبل") return null
        val rest = cut(t, m.range)
        if (rest.any { it.isDigit() }) return null
        val at = runCatching { next(name, 0, now) }.getOrNull() ?: return null
        return QuickResult(title = rest.ifBlank { text }, dueAt = at, understood = true)
    }
}
