package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Repeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * English date and time phrases in a quick-add title: "tomorrow 5pm", "in 2 hours", "next Thursday
 * 8pm", "every day 7am", "weekdays", "every Monday and Thursday", "at 7:30", "tonight".
 *
 * The repeats ("every …") and the AM/PM and next-week rules are a direct port of parseEnglish() and
 * readResult() in the Windows app's natural-dates-en.js. Windows reads the rest with chrono-node;
 * Android does not carry chrono-node. [Chrono] below reproduces chrono-node's answers for the phrase
 * families quick add documents (casual days, parts of the day, clock times, weekdays with this/next,
 * "next week", "in N minutes/hours/days", "N … later/from now", "Nov 20", "20 Nov"), including how it
 * merges a day with a time and which bare numbers it refuses. Anything chrono-node might read that is
 * outside that subset (yesterday, "last Friday", "for 2 hours", 5/12, ranges, time zones, "next
 * month" …) makes the whole English reading "no date": a missing date can be added by hand, an
 * invented one is the failure quick add must never have.
 */
internal object EnglishDates {
    private val DAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private const val DAY = "(?:sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)(?:day|nesday|rsday|urday|sday)?s?"
    private val TRAILING_S = Regex("s$")
    private fun dayOf(word: String): Int {
        val w = word.lowercase().replace(TRAILING_S, "").take(3)
        return DAYS.indexOfFirst { it.startsWith(w) }
    }
    private val COUNTS = linkedMapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
    private val COUNT = "\\d{1,3}|" + COUNTS.keys.joinToString("|")
    private fun count(text: String) = COUNTS[text.lowercase()] ?: text.toInt()
    // The time used when a phrase names only the part of the day. The same hours as the Arabic rules.
    private val PERIOD_AT = mapOf("morning" to 9, "noon" to 12, "afternoon" to 15, "evening" to 18, "tonight" to 21, "night" to 21, "midnight" to 0)
    private val PERIOD = Js.regex("\\b(morning|noon|afternoon|evening|tonight|night|midnight)\\b", ignoreCase = true)

    /** What a repeat pattern read: the rule, and a weekday or part of the day it names. */
    private class RepeatRead(val rule: RepeatSpec, val weekday: Int? = null, val period: String? = null)

    private fun weeklyOrInterval(days: Int) = when (days) { 1 -> RepeatSpec(Repeat.DAILY); 7 -> RepeatSpec(Repeat.WEEKLY); else -> RepeatSpec(Repeat.INTERVAL, every = days) }
    private val LIST_SPLIT = Js.regex("\\s*(?:,|and|&)\\s*", ignoreCase = true)
    private val WEEK = Regex("week", RegexOption.IGNORE_CASE)

    private val REPEATS: List<Pair<Regex, (MatchResult) -> RepeatRead?>> = listOf(
        Js.regex("\\bevery\\s+other\\s+day\\b", ignoreCase = true) to { _ -> RepeatRead(RepeatSpec(Repeat.INTERVAL, every = 2)) },
        Js.regex("\\bevery\\s+($COUNT)\\s+(days?|weeks?)\\b", ignoreCase = true) to { m ->
            val days = count(m.groupValues[1]) * (if (WEEK.containsMatchIn(m.groupValues[2])) 7 else 1)
            if (days < 1 || days > 365) null else RepeatRead(weeklyOrInterval(days))
        },
        Js.regex("\\b(?:(?:every|each)\\s+(?:week|work\\s?)days?|on\\s+weekdays|weekdays)\\b", ignoreCase = true) to { _ ->
            RepeatRead(RepeatSpec(Repeat.DAYS, days = listOf(1, 2, 3, 4, 5)))
        },
        Js.regex("\\b(?:every|each)\\s+($DAY(?:\\s*(?:,|and|&)\\s*$DAY)*)\\b", ignoreCase = true) to { m ->
            val days = m.groupValues[1].split(LIST_SPLIT).map(::dayOf).filter { it >= 0 }.distinct().sorted()
            when {
                days.isEmpty() -> null
                days.size == 1 -> RepeatRead(RepeatSpec(Repeat.WEEKLY), weekday = days[0])
                else -> RepeatRead(RepeatSpec(Repeat.DAYS, days = days))
            }
        },
        Js.regex("\\b(?:every|each)\\s+(morning|afternoon|evening|night)\\b", ignoreCase = true) to { m -> RepeatRead(RepeatSpec(Repeat.DAILY), period = m.groupValues[1].lowercase()) },
        Js.regex("\\b(?:every\\s*day|each\\s+day|daily)\\b", ignoreCase = true) to { _ -> RepeatRead(RepeatSpec(Repeat.DAILY)) },
        Js.regex("\\b(?:(?:every|each)\\s+week|weekly)\\b", ignoreCase = true) to { _ -> RepeatRead(RepeatSpec(Repeat.WEEKLY)) },
        Js.regex("\\b(?:(?:every|each)\\s+month|monthly)\\b", ignoreCase = true) to { _ -> RepeatRead(RepeatSpec(Repeat.MONTHLY)) },
        Js.regex("\\b(?:(?:every|each)\\s+year|yearly|annually)\\b", ignoreCase = true) to { _ -> RepeatRead(RepeatSpec(Repeat.YEARLY)) },
    )
    // "8 in the evening" and "10 at night" are written as "8pm" before the clock times are read.
    private val PART_OF_DAY = Js.regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s+(?:o'?clock\\s+)?(in\\s+the\\s+(morning|afternoon|evening)|at\\s+night|tonight)\\b", ignoreCase = true)
    private val MORNING = Regex("morning", RegexOption.IGNORE_CASE)
    private val NIGHT = Regex("night", RegexOption.IGNORE_CASE)
    // Short day names alone ("I sat down", "wed") are too often plain words.
    private val SHORT_DAY = Js.regex("^(?:on\\s+)?(?:sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)\\.?$", ignoreCase = true)
    private val NOW_WORDS = Js.regex("^(?:now|today's|todays)$", ignoreCase = true)
    private val LATIN = Regex("[a-zA-Z]")

    fun parse(text: String, now: Instant, zone: ZoneId): Reading? {
        if (!LATIN.containsMatchIn(text)) return null
        val spans = ArrayList<IntArray>()
        val reading = Reading()
        var period: String? = null
        val rest = StringBuilder(text)
        fun blank(s: Int, e: Int) { for (i in s until e) rest.setCharAt(i, ' ') }
        for ((pattern, read) in REPEATS) {
            val m = pattern.find(rest) ?: continue
            val repeat = read(m) ?: continue
            reading.repeat = repeat.rule
            if (repeat.weekday != null) reading.date = DateSpec.Weekday(repeat.weekday, Mod.REPEAT)
            if (repeat.rule.repeat == Repeat.DAYS) reading.date = DateSpec.Days(repeat.rule.days)
            if (repeat.period != null) period = repeat.period
            spans += intArrayOf(m.range.first, m.range.last + 1)
            blank(m.range.first, m.range.last + 1)
            break
        }
        val rewritten = ArrayList<IntArray>()
        val source = rest.toString()
        val written = PART_OF_DAY.replace(source) { m ->
            val hour = m.groupValues[1]
            val minute = m.groupValues[2]
            val part = m.groupValues[3]
            val pm = !MORNING.containsMatchIn(part) && !(NIGHT.containsMatchIn(part) && hour.toInt() < 5)
            val short = hour + (if (minute.isNotEmpty()) ":$minute" else "") + (if (pm) "pm" else "am")
            rewritten += intArrayOf(m.range.first, m.range.last + 1)
            short.padEnd(m.value.length, ' ')
        }
        val results = Chrono.parse(written, now, zone)?.filter { r ->
            val phrase = Js.trim(r.text)
            if (NOW_WORDS.matches(phrase) || SHORT_DAY.matches(phrase)) false
            else r.c.certain("day") || r.c.certain("weekday") || r.c.certain("hour") || PERIOD.containsMatchIn(phrase)
        } ?: return null
        val result = results.firstOrNull()
        if (result == null && reading.repeat == null) return null
        if (result != null) {
            val s = result.index
            val e = s + result.text.length
            // A rewritten "8 in the evening" is cut from the title whole.
            val whole = rewritten.fold(intArrayOf(s, e)) { (a, b), (x, y) -> if (x < b && y > a) intArrayOf(minOf(a, x), maxOf(b, y)) else intArrayOf(a, b) }
            spans += whole
            period = readResult(result, reading) ?: period
        }
        if (reading.time == null && period != null) reading.time = Clock(PERIOD_AT.getValue(period), 0, true)
        reading.spans = spans
        return reading
    }

    private val MIN_HOURS = Js.regex("\\b(?:min(?:ute)?s?|h(?:ou)?rs?)\\b")
    private val RELATIVE_WORD = Js.regex("\\b(?:in|within|after|from now|later)\\b")
    private val LONG_UNITS = Js.regex("\\b(?:days?|weeks?|months?|years?)\\b")
    private val NEXT_WEEK = Js.regex("\\bnext\\s+week\\b")
    private val NEXT = Js.regex("\\bnext\\b")
    private val THIS = Js.regex("\\b(?:this|coming)\\b")
    private val NEXT_UNIT = Js.regex("\\bnext\\s+(?:week|month|year)\\b")
    private val CERTAIN_WORDS = Js.regex("\\b(?:noon|midnight|tonight)\\b")
    private val ZERO_HOUR = Js.regex("\\b0\\d:\\d\\d\\b")

    /** readResult() in natural-dates-en.js. Returns the part of the day named when no hour is certain. */
    private fun readResult(result: Chrono.Result, reading: Reading): String? {
        val c = result.c
        val phrase = result.text.lowercase()
        // "in 2 hours", "30 minutes from now": an exact moment.
        if (MIN_HOURS.containsMatchIn(phrase) && RELATIVE_WORD.containsMatchIn(phrase)) {
            reading.instant = Moment(at = result.instant ?: c.localDateTime().atZone(result.zone).toInstant())
            return null
        }
        val relativeDays = RELATIVE_WORD.containsMatchIn(phrase) && LONG_UNITS.containsMatchIn(phrase)
        val date = reading.date
        if ((date is DateSpec.Weekday && date.mod == Mod.REPEAT) || date is DateSpec.Days) { /* the repeat already says which days */ }
        else if (c.certain("weekday") && !c.certain("day")) {
            val mod = when {
                NEXT_WEEK.containsMatchIn(phrase) -> Mod.NEXT_WEEK
                NEXT.containsMatchIn(phrase) -> Mod.NEXT
                THIS.containsMatchIn(phrase) -> Mod.THIS
                else -> null
            }
            reading.date = DateSpec.Weekday(c.get("weekday")!!, mod)
        } else if (c.certain("day") || relativeDays || NEXT_UNIT.containsMatchIn(phrase)) {
            reading.date = DateSpec.Ymd(LocalDate.of(c.get("year")!!, c.get("month")!!, c.get("day")!!), relativeDays)
        }
        if (c.certain("hour")) {
            val hour = c.get("hour")!!
            val meridiem = c.certain("meridiem") || hour == 0 || hour > 12 || CERTAIN_WORDS.containsMatchIn(phrase) || ZERO_HOUR.containsMatchIn(phrase)
            reading.time = Clock(hour, c.get("minute") ?: 0, meridiem)
            return null
        }
        return PERIOD.find(phrase)?.groupValues?.get(1)
    }
}

/**
 * The part of chrono-node 2.10 (MIT) that quick add relies on, rebuilt as rules: its parsers for the
 * supported phrase families, its overlap removal, its date + time merging (mergeDateTimeComponent),
 * forward dating of "Nov 20", and its filter of bare numbers. [parse] returns null ("no date") when the
 * text holds anything chrono-node could read that this subset does not reproduce.
 */
internal object Chrono {
    /** chrono-node's ParsingComponents: known (certain) and implied values. Month is 1-12, meridiem 0 AM / 1 PM. */
    class Comp(ref: LocalDateTime) {
        val known = HashMap<String, Int>()
        val implied = HashMap<String, Int>()
        init {
            imply("day", ref.dayOfMonth); imply("month", ref.monthValue); imply("year", ref.year)
            imply("hour", 12); imply("minute", 0); imply("second", 0)
        }
        fun get(k: String): Int? = known[k] ?: implied[k]
        fun certain(k: String) = k in known
        fun assign(k: String, v: Int) { known[k] = v; implied.remove(k) }
        fun imply(k: String, v: Int) { if (k !in known) implied[k] = v }
        fun isOnlyDate() = !certain("hour") && !certain("minute") && !certain("second")
        fun isOnlyTime() = !certain("weekday") && !certain("day") && !certain("month") && !certain("year")
        fun assignDate(d: LocalDate) { assign("day", d.dayOfMonth); assign("month", d.monthValue); assign("year", d.year) }
        fun implyTime(t: LocalDateTime) { imply("hour", t.hour); imply("minute", t.minute); imply("second", t.second) }
        fun localDateTime(): LocalDateTime = LocalDateTime.of(get("year")!!, get("month")!!, get("day")!!, get("hour")!!, get("minute")!!, get("second")!!)
        fun copy(ref: LocalDateTime) = Comp(ref).also { it.known.putAll(known); it.implied.clear(); it.implied.putAll(implied) }
    }

    enum class Kind { CASUAL_DATE, CASUAL_TIME, TIME, WEEKDAY, RELATIVE, MONTH_DATE, MONTH_ONLY, NOW }

    class Result(val index: Int, val text: String, val c: Comp, val kind: Kind, val zone: ZoneId, val instant: Instant? = null)

    /** Thrown inside the scan when the text holds something outside the subset. */
    private class OutOfSubset : RuntimeException(null, null, false, false)
    private fun bail(): Nothing = throw OutOfSubset()

    private const val PM = 1
    private const val AM = 0

    private fun alt(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|") { it.replace(".", "\\.") }

    private val WEEKDAY_DICT = linkedMapOf(
        "sunday" to 0, "sun" to 0, "sun." to 0, "monday" to 1, "mon" to 1, "mon." to 1, "tuesday" to 2, "tue" to 2, "tue." to 2,
        "wednesday" to 3, "wed" to 3, "wed." to 3, "thursday" to 4, "thurs" to 4, "thurs." to 4, "thur" to 4, "thur." to 4, "thu" to 4, "thu." to 4,
        "friday" to 5, "fri" to 5, "fri." to 5, "saturday" to 6, "sat" to 6, "sat." to 6,
    )
    private val FULL_MONTHS = linkedMapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7, "august" to 8,
        "september" to 9, "october" to 10, "november" to 11, "december" to 12,
    )
    private val MONTH_DICT = LinkedHashMap<String, Int>().apply {
        putAll(FULL_MONTHS)
        for ((k, v) in listOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12)) {
            put(k, v); put("$k.", v)
        }
    }
    private val MONTH = alt(MONTH_DICT.keys)
    private val WEEKDAY = alt(WEEKDAY_DICT.keys)
    private const val YEAR = "(?:[1-9][0-9]{0,3}\\s{0,2}(?:BE|AD|BC|BCE|CE)|[1-2][0-9]{3}|[5-9][0-9]|2[0-5])"
    private const val ORDINAL = "[0-9]{1,2}(?:st|nd|rd|th)?"

    private fun re(src: String) = Js.regex(src, ignoreCase = true)

    // The parsers, with chrono-node's word-boundary prefix (\W|^) as group 1.
    private val CASUAL_DATE = re("(\\W|^)(now|today|tonight|tomorrow|overmorrow|tmr|tmrw|yesterday|last\\s*night)(?=\\W|$)")
    private val CASUAL_TIME = re("(\\W|^)(?:this)?\\s{0,3}(morning|afternoon|evening|night|midnight|midday|noon)(?=\\W|$)")
    private val TIME = re(
        "(^|\\s|T|\\b)(?:(?:at|from)\\s*)??(\\d{1,4})(?:(?:\\.|:|：)(\\d{1,2})(?:(?::|：)(\\d{2})(?:\\.(\\d{1,6}))?)?)?(?:\\s*(a\\.m\\.|p\\.m\\.|am?|pm?))?" +
            "(\\s*(?:o\\W*clock|at\\s*night|in\\s*the\\s*(?:morning|afternoon)))?(?!/)(?=\\W|$)",
    )
    private val TIME_FOLLOWING = re("^\\s*(?:\\-|\\–|\\~|\\〜|to|until|through|till|\\?)\\s*\\d")
    private val WEEKDAY_RULE = re(
        "(\\W|^)(?:(?:\\,|\\(|\\（)\\s*)?(?:on\\s*?)?(?:(this|last|past|next)\\s*)?($WEEKDAY|weekend|weekday)(?:\\s*(?:\\,|\\)|\\）))?(?:\\s*(this|last|past|next)\\s*week)?(?=\\W|$)",
    )
    private const val NUM = "(?:[0-9]+(?:\\.[0-9]+)?|(?:an?|half|few|several|couple|the|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)(?![A-Za-z0-9_]))"
    private val UNIT_WORDS = listOf(
        "s", "sec", "secs", "second", "seconds", "m", "min", "mins", "minute", "minutes", "h", "hr", "hrs", "hour", "hours",
        "d", "day", "days", "w", "wk", "wks", "week", "weeks", "mo", "mos", "mon", "mons", "month", "months",
        "qtr", "qtrs", "quarter", "quarters", "y", "yr", "yrs", "year", "years",
    )
    private val UNIT = alt(UNIT_WORDS)
    private val DURATION = "$NUM\\s{0,5}(?:$UNIT)(?![A-Za-z0-9_])"
    private val WITHIN = re("(\\W|^)(within|in|for)\\s*((?:about|around|roughly|approximately|just)\\s*(?:~\\s*)?)?($NUM)\\s{0,5}($UNIT)(?=\\W|$)")
    private val LATER = re("(\\W|^)($NUM)\\s{0,5}($UNIT)\\s{0,5}(later|after|from now|henceforth|forward|out)(?=\\W|$)")
    private val RELATIVE = re("(\\W|^)(this|last|past|next|after\\s*this)\\s*((?:sec|second|min|minute|hour|day|week|month|quarter|qtr|year|yr)s?)(?=\\W|$)")
    private val MONTH_MIDDLE = re(
        "(\\W|^)(?:on\\s{0,3})?($MONTH)(?:-|/|\\s*,?\\s*)($ORDINAL)(?!\\s?(?:am|pm))\\s*(?:(?:to|-)\\s*($ORDINAL)\\s*)?(?:(?:-|/|\\s*,\\s*|\\s+)($YEAR))?(?=\\W|$)(?!:\\d)",
    )
    private val MONTH_LITTLE = re(
        "(\\W|^)(?:on\\s{0,3})?($ORDINAL)(?:\\s{0,3}(?:to|-|–|until|through|till)?\\s{0,3}($ORDINAL))?(?:-|/|\\s{0,3}(?:of)?\\s{0,3})($MONTH)(?:(?:-|/|,?\\s{0,3})($YEAR(?![^\\s]\\d)))?(?=\\W|$)",
    )
    private val MONTH_ONLY = re("(\\W|^)((?:in)\\s*)?($MONTH)\\s*(?:(?:,|-|of)?\\s*($YEAR))?(?=[^\\s\\w]|\\s+[^0-9]|\\s+$|$)")

    private val STRICT_COUNT = Js.regex("^(?:[0-9]+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)$", ignoreCase = true)
    private val STRICT_UNIT = Js.regex("^(?:min|mins|minute|minutes|hr|hrs|hour|hours|day|days)$", ignoreCase = true)
    private val WORD_COUNT = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
    private val DURATION_ANY = re("(?<![A-Za-z0-9_])$DURATION")
    private val DURATION_BEFORE = re("(?:for|within|in|next|last|past|after|this|before|about|around|roughly|approximately|just|half|\\+|-|~)\\s*$")
    private val DURATION_AFTER = re("^\\s{0,5}(?:later|after|before|from|ago|henceforth|forward|out|and|,|&)")
    private val CHAINED = re("^\\s*(?:,|and|&)?\\s*$DURATION")

    // Words and shapes chrono-node reads that this subset does not: the whole English reading is "no date".
    private val ORDINAL_WORDS = "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth|twenty|thirty"
    private val OUTSIDE = listOf(
        re("\\b(?:yesterday|overmorrow|tmrw?|ago|weekends?|weekday|midnight|midday|noonish)\\b"),
        re("\\blast\\s*night\\b"),
        re("\\b(?:today|tomorrow|tonight)(?:'s|s)\\b"),
        re("\\b(?:last|past)\\s*(?:$WEEKDAY|$MONTH|week|month|year|quarter|day|night|hour|minute|weekend|[0-9])"),
        re("\\bthis\\s*(?:week|month|year|quarter|hour|minute|second|day)s?\\b"),
        re("\\bnext\\s*(?:month|year|quarter|hour|minute|second|day|weekend|[0-9])"),
        re("\\b(?:after|before)\\s+(?:this|tomorrow|today|tonight|yesterday|next)\\b"),
        re("\\d\\s*/\\s*\\d"),
        re("\\d{4}\\s*[-./]\\s*\\d"),
        re("\\d{1,2}\\s*[-.]\\s*\\d{1,2}\\s*[-.]\\s*\\d{2,4}"),
    )
    private val ORDINAL_WORD = re("\\b(?:$ORDINAL_WORDS)\\b")
    private val MONTH_WORD = re("\\b(?:$MONTH)(?=\\W|$)")
    // chrono-node's time-zone refiners: an abbreviation or an offset right after a time.
    private val TZ_WORDS = setOf(
        "acdt", "acst", "adt", "aedt", "aest", "akdt", "akst", "art", "ast", "awst", "brt", "bst", "cat", "cdt", "cest", "cet", "clt", "cot", "cst", "ct",
        "eat", "edt", "eest", "eet", "est", "et", "get", "gmt", "gst", "hkt", "hst", "ict", "idt", "ist", "jst", "kst", "mart", "mdt", "mesz", "mez", "msk", "mst", "mt",
        "nzdt", "nzst", "pdt", "pet", "pht", "pkt", "pst", "pt", "sast", "sgt", "sst", "utc", "wat", "west", "wet", "wib", "wit", "wita", "wt", "z",
    )
    private val TZ_AFTER = re("^\\s*,?\\s*\\(?([A-Za-z]{1,5})\\)?(?=\\W|$)")
    private val OFFSET_AFTER = re("^\\s*(?:\\(?(?:GMT|UTC)\\s?)?[+-]\\s?\\d")
    private val RANGE_BETWEEN = re("^\\s*(?:to|-|–|until|through|till)\\s*$")
    private val WEEKDAY_MERGE_BETWEEN = Js.regex("^,?\\s*$")
    private val DATE_TIME_BETWEEN = Js.regex("^\\s*(T|at|after|before|on|of|,|-|\\.|∙|:)?\\s*$")
    private val UNLIKELY = Js.regex("^\\d*(\\.\\d*)?$")
    private val ONLY_DIGIT = Js.regex("^\\d$")
    private val THREE_DIGITS = Js.regex("^\\d\\d\\d+$")
    private val ENDS_AP = Js.regex("\\d[apAP]$")
    private val ENDING_NUMBERS = Js.regex("[^\\d:.](\\d[\\d.]*)$")
    private val DOT_PAIRS = Js.regex("\\d(\\.\\d{2})+$")

    private fun MatchResult.g(i: Int): String? = groups[i]?.value

    /**
     * chrono-node's scan: each pattern runs on the rest of the text after the previous result, so
     * ^ matches there too. A failed match moves on by one character (the time parser skips the whole match).
     */
    private fun scan(text: String, pattern: Regex, skipWhole: Boolean, out: MutableList<Result>, extract: (MatchResult, Int, String) -> Result?) {
        var from = 0
        while (from <= text.length) {
            val m = pattern.find(text.substring(from)) ?: return
            val abs = from + m.range.first
            val head = m.groupValues[1]
            val r = extract(m, abs + head.length, m.value.substring(head.length))
            if (r == null) {
                from = (if (skipWhole) abs + m.value.length else abs) + 1
                continue
            }
            out += r
            from = abs + r.text.length
        }
    }

    fun parse(text: String, now: Instant, zone: ZoneId): List<Result>? = try { parseOrBail(text, now, zone) } catch (e: OutOfSubset) { null }

    private fun parseOrBail(text: String, now: Instant, zone: ZoneId): List<Result> {
        if (OUTSIDE.any { it.containsMatchIn(text) }) bail()
        if (ORDINAL_WORD.containsMatchIn(text) && MONTH_WORD.containsMatchIn(text)) bail()
        val ref = now.atZone(zone).toLocalDateTime()
        val today = ref.toLocalDate()
        val found = ArrayList<Result>()
        fun result(index: Int, t: String, c: Comp, kind: Kind, instant: Instant? = null) = Result(index, t, c, kind, zone, instant)

        // Durations in a form chrono-node reads but this subset does not ("for 2 hours", "2 weeks ago").
        val strict = ArrayList<IntRange>()
        scan(text, WITHIN, false, found) { m, index, t ->
            if (m.g(2)!!.lowercase() == "for" || m.g(3) != null) bail()
            val n = m.g(4)!!
            val unit = m.g(5)!!.lowercase()
            if (!STRICT_COUNT.matches(n) || !STRICT_UNIT.matches(unit)) bail()
            if (CHAINED.containsMatchIn(text.substring(index + t.length))) bail()
            strict += index until index + t.length
            relative(n, unit, index, t, ref, today, now, zone, Kind.RELATIVE)
        }
        scan(text, LATER, false, found) { m, index, t ->
            val n = m.g(2)!!
            val unit = m.g(3)!!.lowercase()
            val word = m.g(4)!!.lowercase()
            if (word != "later" && word != "from now") bail()
            if (!STRICT_COUNT.matches(n) || !STRICT_UNIT.matches(unit)) bail()
            strict += index until index + t.length
            relative(n, unit, index, t, ref, today, now, zone, Kind.RELATIVE)
        }
        for (d in DURATION_ANY.findAll(text)) {
            if (strict.any { d.range.first in it }) continue
            if (DURATION_BEFORE.containsMatchIn(text.substring(0, d.range.first)) || DURATION_AFTER.containsMatchIn(text.substring(d.range.last + 1))) bail()
        }

        // The parsers, in chrono-node's order (results at the same index keep this order).
        val ordered = ArrayList<Result>()
        scan(text, MONTH_LITTLE, false, ordered) { m, index, t ->
            if (m.g(3) != null || m.g(5) != null) bail()
            monthDate(m.g(4)!!, m.g(2)!!, index, t, ref, zone)
        }
        scan(text, MONTH_MIDDLE, false, ordered) { m, index, t ->
            if (m.g(4) != null || m.g(5) != null) bail()
            monthDate(m.g(2)!!, m.g(3)!!, index, t, ref, zone)
        }
        scan(text, WEEKDAY_RULE, false, ordered) { m, index, t ->
            val word = m.g(3)!!.lowercase()
            if (word == "weekend" || word == "weekday") bail()
            val modifier = (m.g(2) ?: m.g(4))?.lowercase()
            if (modifier == "last" || modifier == "past") bail()
            val c = Comp(ref)
            c.assign("weekday", WEEKDAY_DICT.getValue(word))
            result(index, t, c, Kind.WEEKDAY)
        }
        scan(text, TIME, true, ordered) { m, index, t -> time(m, index, t, text, ref)?.let { result(index, t, it, Kind.TIME) } }
        // Durations were read above (chrono-node runs them between these parsers; overlaps are removed below).
        ordered += found
        scan(text, CASUAL_DATE, false, ordered) { m, index, t ->
            val c = Comp(ref)
            when (m.g(2)!!.lowercase()) {
                "now" -> { c.assignDate(today); c.assign("hour", ref.hour); c.assign("minute", ref.minute); c.assign("second", ref.second); result(index, t, c, Kind.NOW) }
                "today" -> { c.assignDate(today); c.implyTime(ref); result(index, t, c, Kind.CASUAL_DATE) }
                "tomorrow" -> { c.assignDate(today.plusDays(1)); c.implyTime(ref); result(index, t, c, Kind.CASUAL_DATE) }
                "tonight" -> { c.assignDate(today); c.imply("hour", 22); c.imply("meridiem", PM); result(index, t, c, Kind.CASUAL_DATE) }
                else -> bail()
            }
        }
        scan(text, CASUAL_TIME, false, ordered) { m, index, t ->
            val c = Comp(ref)
            when (m.g(2)!!.lowercase()) {
                "morning" -> { c.imply("meridiem", AM); c.imply("hour", 6) }
                "afternoon" -> { c.imply("meridiem", PM); c.imply("hour", 15) }
                "evening", "night" -> { c.imply("meridiem", PM); c.imply("hour", 20) }
                "noon" -> { c.imply("meridiem", AM); c.imply("hour", 12) }
                else -> bail()
            }
            result(index, t, c, Kind.CASUAL_TIME)
        }
        scan(text, MONTH_ONLY, false, ordered) { m, index, t ->
            val name = m.g(3)!!.lowercase()
            if (t.length <= 3 && name !in FULL_MONTHS) return@scan null
            if (m.g(4) != null) bail()
            val c = Comp(ref)
            c.assign("month", MONTH_DICT.getValue(name))
            c.imply("day", 1)
            result(index, t, c, Kind.MONTH_ONLY)
        }
        scan(text, RELATIVE, false, ordered) { m, index, t ->
            if (m.g(2)!!.lowercase() != "next" || m.g(3)!!.lowercase() != "week") bail()
            val c = Comp(ref)
            c.assignDate(today.plusDays(7))
            c.implyTime(ref)
            result(index, t, c, Kind.RELATIVE)
        }

        // Overlap removal: of two overlapping results the longer stays (the earlier on a tie).
        val sorted = ordered.sortedBy { it.index }
        val kept = ArrayList<Result>()
        for (r in sorted) {
            val prev = kept.lastOrNull()
            if (prev == null || r.index >= prev.index + prev.text.length) kept += r
            else if (r.text.length > prev.text.length) kept[kept.size - 1] = r
        }
        // Ranges ("Friday to Sunday") and a weekday followed by its date ("Friday, Nov 20") are merged by
        // chrono-node in ways this subset does not reproduce.
        for (i in 0 until kept.size - 1) {
            val a = kept[i]
            val b = kept[i + 1]
            val between = text.substring(a.index + a.text.length, b.index)
            if (RANGE_BETWEEN.containsMatchIn(between)) bail()
            if (a.kind == Kind.WEEKDAY && !b.c.certain("hour") && b.c.certain("day") && WEEKDAY_MERGE_BETWEEN.containsMatchIn(between)) bail()
        }
        // A time zone right after a result changes chrono-node's span.
        for (r in kept) {
            val after = text.substring(r.index + r.text.length)
            if (OFFSET_AFTER.containsMatchIn(after) && r.c.certain("hour")) bail()
            val tz = TZ_AFTER.find(after)?.groupValues?.get(1)
            if (tz != null && tz.lowercase() in TZ_WORDS && (r.c.certain("hour") || tz == tz.uppercase())) bail()
        }
        // Date + time merging (ENMergeDateTimeRefiner).
        val merged = ArrayList<Result>()
        var cur: Result? = null
        for (next in kept) {
            val c = cur
            if (c == null) { cur = next; continue }
            val between = text.substring(c.index + c.text.length, next.index)
            val should = ((c.c.isOnlyDate() && next.c.isOnlyTime()) || (next.c.isOnlyDate() && c.c.isOnlyTime())) && DATE_TIME_BETWEEN.containsMatchIn(between)
            if (!should) { merged += c; cur = next; continue }
            val (dateR, timeR) = if (c.c.isOnlyDate()) c to next else next to c
            if (dateR.kind == Kind.CASUAL_TIME && timeR.kind != Kind.TIME && timeR.kind != Kind.CASUAL_TIME) bail()
            if (dateR.kind == Kind.MONTH_ONLY || timeR.kind == Kind.MONTH_ONLY) bail()
            val kind = if (dateR.kind == Kind.CASUAL_TIME && timeR.kind == Kind.CASUAL_TIME) Kind.CASUAL_TIME else dateR.kind
            cur = Result(c.index, c.text + between + next.text, mergeDateTime(dateR.c, timeR.c, ref), kind, zone)
        }
        cur?.let { merged += it }
        // Forward dating: "Nov 20" that has passed this year is next year's.
        for (r in merged) {
            val c = r.c
            if (c.certain("day") && c.certain("month") && !c.certain("year")) {
                var i = 0
                while (i < 3 && ref.isAfter(c.localDateTime())) { c.imply("year", c.get("year")!! + 1); i++ }
            }
        }
        // chrono-node's UnlikelyFormatFilter: a result that is only a number ("12", "5.30") is not a date.
        return merged.filter { !UNLIKELY.matches(it.text.replaceFirst(" ", "")) }
    }

    /** "in 30 minutes", "2 days later": createRelativeFromReference(). */
    private fun relative(n: String, unit: String, index: Int, t: String, ref: LocalDateTime, today: LocalDate, now: Instant, zone: ZoneId, kind: Kind): Result {
        val count = WORD_COUNT[n.lowercase()] ?: n.toIntOrNull() ?: bail()
        if (count < 1 || count > 10000) bail()
        val c = Comp(ref)
        return when {
            unit.startsWith("d") -> {
                c.assignDate(today.plusDays(count.toLong()))
                c.implyTime(ref)
                Result(index, t, c, kind, zone)
            }
            else -> {
                val at = now.plusSeconds(count.toLong() * (if (unit.startsWith("h")) 3600 else 60))
                val local = at.atZone(zone).toLocalDateTime()
                c.assignDate(local.toLocalDate())
                c.assign("hour", local.hour); c.assign("minute", local.minute); c.assign("second", local.second)
                c.assign("meridiem", if (local.hour < 12) AM else PM)
                Result(index, t, c, kind, zone, at)
            }
        }
    }

    /** "Nov 20", "20th of November": the day and month, the year closest to now (moved forward later). */
    private fun monthDate(monthWord: String, dayWord: String, index: Int, t: String, ref: LocalDateTime, zone: ZoneId): Result {
        val month = MONTH_DICT.getValue(monthWord.lowercase())
        val day = dayWord.takeWhile { it.isDigit() }.toInt()
        if (day < 1 || day > 31) bail()
        // Days a month does not have (and 29 February) are filtered or moved by chrono-node.
        if (day > java.time.YearMonth.of(2025, month).lengthOfMonth()) bail()
        var year = ref.year
        val at = { y: Int -> LocalDateTime.of(y, month, day, ref.hour, ref.minute, ref.second) }
        val diff = { y: Int -> kotlin.math.abs(java.time.Duration.between(ref, at(y)).toMillis()) }
        if (diff(year + 1) < diff(year)) year += 1 else if (diff(year - 1) < diff(year)) year -= 1
        val c = Comp(ref)
        c.assign("day", day); c.assign("month", month); c.imply("year", year)
        return Result(index, t, c, Kind.MONTH_DATE, zone)
    }

    /** ENTimeExpressionParser: "5pm", "at 5", "17:30", "9:30am", "at 07:30", "5 o'clock". */
    private fun time(m: MatchResult, index: Int, t: String, text: String, ref: LocalDateTime): Comp? {
        var hour = m.g(2)!!.toInt()
        var minute = 0
        var meridiem: Int? = null
        if (m.g(7) != null && !m.g(7)!!.lowercase().contains("clock")) bail()
        if (hour > 100) {
            if (m.g(3) != null) return null
            minute = hour % 100
            hour /= 100
        }
        if (hour > 24) return null
        m.g(3)?.let { mm ->
            if (mm.length == 1 && m.g(6) == null) return null
            minute = mm.toInt()
        }
        if (minute >= 60) return null
        if (hour > 12) meridiem = PM
        m.g(6)?.let { ap ->
            if (hour > 12) return null
            when (ap[0].lowercaseChar()) {
                'a' -> { meridiem = AM; if (hour == 12) hour = 0 }
                'p' -> { meridiem = PM; if (hour != 12) hour += 12 }
            }
        }
        if (hour == 24) bail()
        if (m.g(4) != null && m.g(4)!!.toInt() >= 60) return null
        // A range ("5-7pm", "from 5 to 7") is read by chrono-node as a start and an end.
        if (TIME_FOLLOWING.containsMatchIn(text.substring(index + t.length))) bail()
        // checkAndReturnWithoutFollowingPattern(): a bare number is not a time.
        if (ONLY_DIGIT.matches(t) || THREE_DIGITS.matches(t) || ENDS_AP.containsMatchIn(t)) return null
        ENDING_NUMBERS.find(t)?.let { e ->
            val digits = e.groupValues[1]
            if (digits.contains('.') && !DOT_PAIRS.containsMatchIn(digits)) return null
            if ((digits.takeWhile { it.isDigit() }.toIntOrNull() ?: 0) > 24) return null
        }
        val c = Comp(ref)
        c.assign("hour", hour)
        c.assign("minute", minute)
        m.g(4)?.let { c.assign("second", it.toInt()) }
        if (meridiem != null) c.assign("meridiem", meridiem!!) else c.imply("meridiem", if (hour < 12) AM else PM)
        return c
    }

    /** chrono-node's mergeDateTimeComponent(). */
    private fun mergeDateTime(date: Comp, time: Comp, ref: LocalDateTime): Comp {
        val out = date.copy(ref)
        if (time.certain("hour")) {
            out.assign("hour", time.get("hour")!!)
            out.assign("minute", time.get("minute")!!)
            if (time.certain("second")) out.assign("second", time.get("second")!!) else out.imply("second", time.get("second")!!)
        } else {
            out.imply("hour", time.get("hour")!!)
            out.imply("minute", time.get("minute")!!)
            out.imply("second", time.get("second")!!)
        }
        if (time.certain("meridiem")) out.assign("meridiem", time.get("meridiem")!!)
        else if (time.get("meridiem") != null && out.get("meridiem") == null) out.imply("meridiem", time.get("meridiem")!!)
        if (out.get("meridiem") == PM && out.get("hour")!! < 12) {
            if (time.certain("hour")) out.assign("hour", out.get("hour")!! + 12) else out.imply("hour", out.get("hour")!! + 12)
        }
        return out
    }
}
