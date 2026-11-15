package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.localToInstant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Quick add: dates typed in a title, «بكرة 5 العصر», "tomorrow 5pm", «كل يوم 7 الصبح». A port of the
 * Windows app's src/core/natural-dates.js (with natural-dates-ar.js and natural-dates-en.js), so both
 * apps read the same text the same way. Nothing is guessed: when a phrase allows more than one time
 * (AM or PM, this week or next, no time at all) [QuickResult.dueAt] is null and [QuickResult.options]
 * holds the choices to offer. Local and offline.
 */

/** Why a reading needs the person to choose (the Windows `ask` values). */
enum class Ask { MERIDIEM, DAY, TIME, CHOOSE, PAST }

/** One choice to offer. [labelHint] says what tells it apart: "am"/"pm", "this"/"next", "morning"/"noon"/"evening". */
data class QuickOption(val dueAt: Instant, val labelHint: String)

data class QuickResult(
    /** The text without the date phrase (the whole text when nothing was understood). */
    val title: String,
    /** Null when nothing was understood or when [ask] is set. */
    val dueAt: Instant?,
    val repeat: Repeat = Repeat.NONE,
    /** 0 = Sunday … 6 = Saturday, for [Repeat.DAYS]. */
    val days: List<Int> = emptyList(),
    val everyDays: Int = 2,
    /** For [Repeat.HOURS] (an Android addition, «كل 3 ساعات»). */
    val everyHours: Int = 1,
    val ask: Ask? = null,
    /** At most 4, in time order. */
    val options: List<QuickOption> = emptyList(),
    /** False: no phrase was found, keep the text as typed. */
    val understood: Boolean,
)

object QuickAdd {
    /** The Windows MAX_TEXT: only the first 200 characters are read. */
    const val MAX_TEXT = 200

    /**
     * The quick-add reading of [text] at [now] in [zone]. [weekStart] (0 Sunday, 1 Monday or 6 Saturday,
     * from Settings) decides whether "next Thursday" can mean this week's Thursday. Never throws: an
     * unexpected failure reads as "nothing understood", which never invents a date.
     */
    fun parse(text: String, now: Instant, zone: ZoneId, weekStart: Int = 6): QuickResult =
        try { parseStrict(text, now, zone, weekStart) } catch (e: RuntimeException) { QuickResult(title = text, dueAt = null, understood = false) }

    /** [parse] without the safety net, for the tests. */
    internal fun parseStrict(text: String, now: Instant, zone: ZoneId, weekStart: Int = 6): QuickResult {
        val none = QuickResult(title = text, dueAt = null, understood = false)
        val typed = text.take(MAX_TEXT)
        if (Js.trim(typed).isEmpty()) return none
        AndroidAdditions.parse(typed, now, zone)?.let { return it }
        val reading = pick(typed, now, zone) ?: return none
        val week = if (weekStart in listOf(0, 1, 6)) weekStart else 6
        val r = resolve(reading, now, zone, week)
        val ask = r.ask
        return QuickResult(
            title = cutTitle(typed, reading.spans),
            dueAt = if (ask == null) r.dueAt else null,
            repeat = reading.repeat?.repeat ?: Repeat.NONE,
            days = reading.repeat?.days.orEmpty(),
            everyDays = reading.repeat?.every ?: 2,
            ask = ask,
            options = r.options.mapIndexed { i, at -> QuickOption(at, hint(ask, i, at, zone)) },
            understood = true,
        )
    }

    /**
     * The Arabic reading first, as on Windows. Android addition: in a text that mixes Arabic and Latin letters
     * («دفع رسوم البنك tomorrow 10am»), the English reading wins when it covers more of the text, so an English
     * date phrase is never left half read (the Windows parity set has no mixed texts).
     */
    private fun pick(typed: String, now: Instant, zone: ZoneId): Reading? {
        val ar = ArabicDates.parse(typed)
        val mixed = typed.any { it in '\u0600'..'\u06FF' } && typed.any { it in 'a'..'z' || it in 'A'..'Z' }
        if (ar == null || !mixed) return ar ?: EnglishDates.parse(typed, now, zone)
        val en = EnglishDates.parse(typed, now, zone) ?: return ar
        fun covered(r: Reading) = r.spans.sumOf { maxOf(0, it[1] - it[0]) }
        return if (covered(en) > covered(ar)) en else ar
    }

    private fun hint(ask: Ask?, index: Int, at: Instant, zone: ZoneId): String {
        val hour = at.atZone(zone).hour
        return when (ask) {
            null -> ""
            Ask.MERIDIEM -> if (hour < 12) "am" else "pm"
            Ask.DAY, Ask.PAST -> if (index == 0 && ask == Ask.DAY) "this" else "next"
            Ask.TIME, Ask.CHOOSE -> if (hour < 12) "morning" else if (hour == 12) "noon" else "evening"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A reading: what the Arabic or English reader found, before it is turned into times.
// ---------------------------------------------------------------------------------------------

/** A clock time; [meridiem] false means a 1-12 hour that could be AM or PM. */
internal data class Clock(val hour: Int, val minute: Int, val meridiem: Boolean)

/** Which day a phrase names (the Windows `date` shapes). */
internal sealed interface DateSpec {
    /** Some weekdays (a "days" repeat): the first of them still ahead. */
    data class Days(val days: List<Int>) : DateSpec
    /** [days] from today; [keepTime] keeps now's clock time («بعد 3 أيام»). */
    data class Offset(val days: Int, val keepTime: Boolean = false) : DateSpec
    /** A calendar date. */
    data class Ymd(val date: LocalDate, val keepTime: Boolean) : DateSpec
    /** [months] calendar months from today, at now's clock time. */
    data class Months(val months: Int) : DateSpec
    /** A weekday (0 = Sunday) with its modifier. */
    data class Weekday(val day: Int, val mod: Mod?) : DateSpec
}

/** "repeat": a weekly repeat; "this"; "next" (this week's or the next); "nextWeek" (in next week). */
internal enum class Mod { REPEAT, THIS, NEXT, NEXT_WEEK }

internal data class RepeatSpec(val repeat: Repeat, val days: List<Int> = emptyList(), val every: Int? = null)

/** An exact moment: [minutes] from now, or [at]. */
internal data class Moment(val minutes: Int? = null, val at: Instant? = null)

internal class Reading {
    var date: DateSpec? = null
    var time: Clock? = null
    var instant: Moment? = null
    var repeat: RepeatSpec? = null
    /** A night hour after a named day: that day's early morning or the night after it. */
    var late = false
    /** Typed [start, end) of each phrase to cut from the title. */
    var spans: List<IntArray> = emptyList()
}

internal class Resolved(val dueAt: Instant?, val options: List<Instant>, val ask: Ask?)

// The choices offered when a phrase names a day but no time: morning, noon and evening.
private val DEFAULT_TIMES = listOf(9 to 0, 12 to 0, 18 to 0)
private const val MAX_OPTIONS = 4

/** JavaScript's new Date(y, m, d + days, hour, minute): wall-clock arithmetic, then local → instant. */
private fun at(today: LocalDate, days: Int, clock: Pair<Int, Int>, zone: ZoneId): Instant =
    localToInstant(today.plusDays(days.toLong()).atStartOfDay().plusHours(clock.first.toLong()).plusMinutes(clock.second.toLong()), zone)

/** Turns a reading into a time, or into the choices to ask about (resolve() in natural-dates.js). */
internal fun resolve(reading: Reading, now: Instant, zone: ZoneId, weekStart: Int): Resolved {
    reading.instant?.let { m ->
        val due = (m.at ?: now.plusSeconds(m.minutes!!.toLong() * 60)).truncatedTo(ChronoUnit.MINUTES)
        return Resolved(due, listOf(due), null)
    }
    val local = now.atZone(zone)
    val today = local.toLocalDate()
    val weekday = jsDay(today)
    val date = reading.date
    val time = reading.time
    val times: List<Pair<Int, Int>>? = when {
        time != null -> when {
            time.meridiem -> listOf(time.hour to time.minute)
            time.hour == 12 -> listOf(12 to time.minute, 0 to time.minute)
            else -> listOf(time.hour to time.minute, time.hour + 12 to time.minute)
        }
        (date is DateSpec.Offset && date.keepTime) || (date is DateSpec.Ymd && date.keepTime) || date is DateSpec.Months ->
            listOf(local.hour to local.minute)
        else -> null
    }
    // days: the candidate days, counted from today. first: take the first of them still ahead
    // (a repeat, or a time with no day: today, or tomorrow once it has passed).
    var days: List<Int>
    var first = false
    var step = 1
    when (date) {
        null -> { days = listOf(0, 1); first = true }
        is DateSpec.Days -> { days = (0 until 8).filter { (weekday + it) % 7 in date.days }; first = true }
        is DateSpec.Offset -> days = listOf(date.days)
        is DateSpec.Ymd -> days = listOf(ChronoUnit.DAYS.between(today, date.date).toInt())
        is DateSpec.Months -> days = listOf(ChronoUnit.DAYS.between(today, today.plusMonths(date.months.toLong())).toInt())
        is DateSpec.Weekday -> {
            step = 7
            val ahead = (date.day - weekday + 7) % 7
            val next = if (ahead == 0) 7 else ahead
            val place = (weekday - weekStart + 7) % 7
            days = when (date.mod) {
                Mod.REPEAT -> { first = true; listOf(ahead, ahead + 7) }
                Mod.THIS -> listOf(ahead)
                Mod.NEXT -> if (place + next < 7) listOf(next, next + 7) else listOf(next)
                Mod.NEXT_WEEK -> listOf(7 - place + (date.day - weekStart + 7) % 7)
                null -> if (ahead == 0) listOf(0, 7) else listOf(ahead)
            }
        }
    }
    // A night hour after a named day («الخميس 2 بالليل», «بكرة نص الليل») can mean that day's early
    // morning or the night after it, so both are offered.
    if (reading.late && !first) days = days.flatMap { listOf(it, it + 1) }.distinct()
    val build = { list: List<Int>, clock: List<Pair<Int, Int>> ->
        if (first) clock.mapNotNull { c -> list.map { d -> at(today, d, c, zone) }.firstOrNull { it.isAfter(now) } }
        else list.flatMap { d -> clock.map { c -> at(today, d, c, zone) } }.filter { it.isAfter(now) }
    }
    // No time and an open day: morning or evening of each day, all with their dates.
    val clock = times ?: if (days.size > 1 && !first) listOf(DEFAULT_TIMES[0], DEFAULT_TIMES[2]) else DEFAULT_TIMES
    var options = build(days, clock)
    var ask: Ask? = null
    if (times == null) ask = if (days.size > 1 && !first) Ask.CHOOSE else Ask.TIME
    if (options.isEmpty()) {
        // The day and time have passed («اليوم 5 العصر» in the evening): offer the next one instead.
        options = build(days.map { it + step }, clock)
        ask = Ask.PAST
    }
    options = options.take(MAX_OPTIONS * 2).distinct().sorted().take(MAX_OPTIONS)
    if (ask == null && options.size > 1) {
        ask = if (time != null && !time.meridiem) (if (days.size > 1 && !first) Ask.CHOOSE else Ask.MERIDIEM) else Ask.DAY
    }
    return Resolved(if (ask == null && options.size == 1) options[0] else null, options, ask)
}

// Words left hanging once the phrase is cut out: "Call mom at", «موعد الطبيب في».
private val CONNECTORS = Js.regex("^(?:at|on|by|in|for|from|due|this|next|the|في|على|علي|ع|عند|الساعه|الساعة|يوم|من|ب|و|-|–|,|،|:)$", unicodeCase = true)
private val EDGES = Js.regex("^[\\s,،:;–-]+|[\\s,،:;–-]+$")

/** The title without the date phrases (cutTitle() in natural-dates.js); a title that is only a date stays whole. */
internal fun cutTitle(text: String, spans: List<IntArray>): String {
    val sorted = spans.sortedBy { it[0] }
    val pieces = ArrayList<String>()
    var from = 0
    for (span in sorted) {
        val s = span[0].coerceIn(0, text.length)
        val e = span[1].coerceIn(0, text.length)
        pieces += text.substring(from, maxOf(from, s))
        from = maxOf(from, e)
    }
    pieces += text.substring(from)
    val words = pieces.map { Js.words(it).toMutableList() }
    // Android addition: «علي» is also the name Ali. It is cut as «على» only before a time («علي الساعة 5»,
    // «علي 5»), never before a day («عيد ميلاد علي الخميس» keeps the name). Not in the Windows parity set.
    fun spanStartsWithTime(i: Int): Boolean {
        val span = sorted.getOrNull(i) ?: return true
        val head = text.substring(span[0].coerceIn(0, text.length), span[1].coerceIn(0, text.length)).trimStart()
        return head.firstOrNull()?.let { it.isDigit() } == true || head.startsWith("الساع") || head.startsWith("ساع")
    }
    words.forEachIndexed { i, list ->
        if (i < words.size - 1) while (list.isNotEmpty() && CONNECTORS.matches(list.last())) {
            if (list.last() == "علي" && !spanStartsWithTime(i)) break
            list.removeAt(list.size - 1)
        }
        if (i > 0) while (list.isNotEmpty() && CONNECTORS.matches(list.first())) list.removeAt(0)
    }
    val title = EDGES.replace(words.flatten().joinToString(" "), "")
    return title.ifEmpty { Js.trim(text) }
}
