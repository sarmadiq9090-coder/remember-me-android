package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Tasks
import java.time.Instant
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
