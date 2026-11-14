package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Repeat

/**
 * Arabic date and time phrases in a quick-add title: Modern Standard Arabic and everyday Iraqi, Gulf
 * and Levantine speech, with Arabic-Indic, Persian or Western digits. «بكرة 5 العصر», «باچر الصبح»,
 * «بعد ساعتين», «الخميس الجاي 8 بالليل», «كل يوم 7 الصبح», «بعد بكرة», «اليوم المغرب».
 * A rule-for-rule port of parseArabic() in the Windows app's natural-dates-ar.js; the regular
 * expressions keep the JavaScript sources (see [Js.regex]). Nothing here guesses a day or AM/PM: what
 * is unclear is left open and [resolve] asks.
 */
internal object ArabicDates {
    private const val L = "(?<![\\p{L}\\p{N}])"
    private const val R = "(?![\\p{L}\\p{N}])"

    /** Longest first, as alt() sorts (a stable sort, so equal lengths keep their order). */
    private fun alt(list: List<String>) = list.sortedByDescending { it.length }.joinToString("|")

    // Number words for counts and hours, in the normalized spelling.
    private val NUMBER_WORDS = listOf(
        1 to listOf("واحد", "واحده"), 2 to listOf("اثنين", "اتنين", "ثنين", "اثنتين", "ثنتين"), 3 to listOf("ثلاث", "ثلاثه", "تلات", "تلاته"),
        4 to listOf("اربع", "اربعه"), 5 to listOf("خمس", "خمسه"), 6 to listOf("ست", "سته"), 7 to listOf("سبع", "سبعه"),
        8 to listOf("ثمان", "ثماني", "ثمانيه", "ثمن", "ثمنيه", "تمن", "تمنيه", "تمانيه", "ثمانه"), 9 to listOf("تسع", "تسعه"), 10 to listOf("عشر", "عشره"),
        // Ordinal hours, as in «الساعة الخامسة».
        1 to listOf("الواحده"), 2 to listOf("الثانيه"), 3 to listOf("الثالثه"), 4 to listOf("الرابعه"), 5 to listOf("الخامسه"), 6 to listOf("السادسه"), 7 to listOf("السابعه"),
        8 to listOf("الثامنه"), 9 to listOf("التاسعه"), 10 to listOf("العاشره"),
        11 to listOf("الحاديه عشره", "الحاديه عشر", "احدعشر", "احد عشر", "احدعش", "حدعش", "حداش", "احداش"),
        12 to listOf("اثنا عشر", "اثني عشر", "اثنعش", "اطنعش", "ثنعش", "اتناش", "طنعش", "الثانيه عشره", "الثانيه عشر"),
    )
    private val WORD_VALUE = LinkedHashMap<String, Int>().apply { for ((value, words) in NUMBER_WORDS) for (w in words) put(w, value) }
    private val NUMBER = "\\d{1,2}|" + alt(WORD_VALUE.keys.map { it.replace(" ", "\\s+") })
    private val DIGITS = Regex("^[0-9]+$")
    private fun numberValue(text: String): Int? = if (DIGITS.matches(text)) text.toIntOrNull() ?: Int.MAX_VALUE else WORD_VALUE[Js.collapse(text)]

    // Weekdays (0 = Sunday), written with ال or after يوم / كل.
    private val WEEKDAYS = listOf(
        listOf("احد"), listOf("اثنين", "اتنين", "ثنين"), listOf("ثلاثاء", "ثلاثا", "ثلاثه", "تلاتاء", "تلاتا", "تلات", "ثلثاء"),
        listOf("اربعاء", "اربعا", "اربعه"), listOf("خميس"), listOf("جمعه"), listOf("سبت"),
    )
    private val WEEKDAY = alt(WEEKDAYS.flatten())
    private fun weekdayOf(word: String) = WEEKDAYS.indexOfFirst { word in it }
    private val NEXT = alt(listOf("الجاي", "الجايه", "الجاييه", "الياي", "اليايه", "القادم", "القادمه", "المقبل", "المقبله", "اللي جاي", "الي جاي", "اللي ياي"))
    private val THIS = alt(listOf("هذا", "هذي", "هاي", "هاد", "هذه", "هادا", "هاذا"))
    private val PAST = alt(listOf("الماضي", "الماضيه", "اللي فات", "الي فات", "الفايت", "الفات", "اللي راح"))

    /**
     * A part of the day. [hour] turns a 1-12 hour into 0-23 for that part; [at] is the time used when
     * the phrase names only the part; [late] marks hours that belong to the next day.
     */
    private class Period(val words: List<String>, val at: Int, val hour: (Int) -> Int, val late: ((Int) -> Boolean)? = null)

    private val PERIODS = listOf(
        Period(listOf("الفجر", "فجرا", "بالفجر"), 5, { h -> if (h == 12) 0 else h }),
        Period(listOf("الصبح", "الصباح", "بالصبح", "بالصباح", "صباحا", "صباح", "صبحا", "ص", "am", "a.m."), 9, { h -> if (h == 12) 0 else h }),
        Period(listOf("الضحي", "ضحي", "بالضحي"), 10, { h -> if (h == 12) 0 else h }),
        Period(listOf("الظهر", "ظهرا", "بالظهر", "الضهر", "ضهرا", "بالضهر", "الظهريه", "الضهريه"), 12, { h -> if (h >= 11) h else h + 12 }),
        Period(listOf("بعد الظهر", "بعد الضهر"), 15, { h -> if (h == 12) 12 else h + 12 }),
        Period(listOf("العصر", "عصرا", "بالعصر", "العصريه", "عصريه"), 16, { h -> if (h == 12) 12 else h + 12 }),
        Period(listOf("المغرب", "بالمغرب", "مغربا"), 18, { h -> if (h == 12) 12 else h + 12 }),
        Period(listOf("المسا", "المساء", "بالمسا", "بالمساء", "مساء", "مساءا", "مسا", "العشيه", "عشيه", "م", "pm", "p.m."), 18, { h -> if (h == 12) 12 else h + 12 }),
        Period(listOf("العشا", "العشاء", "بالعشا", "بالعشاء"), 20, { h -> if (h in 6..11) h + 12 else if (h == 12) 0 else h }, { h -> h < 6 || h == 12 }),
        Period(listOf("الليل", "بالليل", "ليلا", "بليل"), 21, { h -> if (h in 6..11) h + 12 else if (h == 12) 0 else h }, { h -> h < 6 || h == 12 }),
        Period(listOf("نص الليل", "نصف الليل", "منتصف الليل", "بنص الليل"), 0, { h -> if (h == 12) 0 else h }, { true }),
    )
    private fun periodOf(word: String) = Js.collapse(word).let { w -> PERIODS.firstOrNull { w in it.words } }

    // One-letter and Latin marks only count right after a number.
    private val LATIN = Regex("^[a-z.]+$")
    private val PERIOD_ALONE = alt(PERIODS.flatMap { it.words }.filter { it.length > 2 && !LATIN.matches(it) }.map { it.replace(" ", "\\s+") })
    private val PERIOD_AFTER = alt(PERIODS.flatMap { it.words }.map { it.replace(" ", "\\s+").replace(".", "\\.") })

    private fun re(source: String) = Js.regex(source)

    // «كل خميس», «كل يوم أحد وأربعاء», «كل الاثنين والخميس»
    private val EVERY_WEEKDAY = re("$L" + "كل\\s+(?:يوم\\s+)?((?:ال)?(?:$WEEKDAY)(?:\\s*(?:و|,)\\s*(?:يوم\\s+)?(?:ال)?(?:$WEEKDAY))*)$R")
    private val EVERY_N = re("$L" + "كل\\s+($NUMBER)\\s*(ايام|يوم|اسابيع|اسبوع)$R")
    private val EVERY_TWO = re("$L" + "كل\\s+(يومين|اسبوعين)$R")
    // «كل يوم», «يوميا», «كل صباح», «كل ليله»
    private val DAILY = re("$L(?:" + "كل\\s+(?:يوم|(صباح|ليله|مسا|مساء|عصر|ظهر))|يوميا)$R")
    private val WEEKLY = re("$L(?:" + "كل\\s+اسبوع|اسبوعيا)$R")
    private val MONTHLY = re("$L(?:" + "كل\\s+شهر|شهريا)$R")
    private val YEARLY = re("$L(?:" + "كل\\s+(?:سنه|عام)|سنويا)$R")
    // «بعد بكرة», «عقب باچر», «بعد غد»
    private val AFTER_TOMORROW = re("$L(?:بعد|عقب)\\s+(?:بكره|بكرا|باجر|باكر|غدا|غد|الغد)$R")
    // «بعد ساعتين», «بعد نص ساعة», «كمان 10 دقايق», «بعد 3 أيام», «بعد أسبوع»
    private val RELATIVE = re(
        "$L(?:بعد|كمان|خلال)\\s+(?:حوالي\\s+)?(?:(نص|نصف|ربع)\\s*ساعه|(ساعتين|ساعتان|ساعه|دقيقتين|دقيقتان|دقيقه|يومين|يومان|يوم|اسبوعين|اسبوع|شهرين|شهر)|($NUMBER)\\s*(ساعات|ساعه|دقايق|دقائق|دقيقه|ايام|يوم|اسابيع|اسبوع|اشهر|شهور|شهر))(?:\\s*(?:و\\s*(نص|نصف|ربع)|الا\\s*(ربع)))?$R",
    )
    private val TOMORROW = re("$L(?:و|ب|وب)?(?:بكره|بكرا|باجر|باكر|غدا|الغد|غدوه)$R")
    private val TONIGHT = re("$L(?:و|ب)?(?:الليله|هالليله)$R")
    private val TODAY = re("$L(?:و|ب)?(?:اليوم|هاليوم)$R")
    // «الخميس الجاي», «يوم الأحد», «هالجمعة», «الخميس من الأسبوع الجاي», «السبت هذا». JavaScript's
    // unbounded look-behind (?<=يوم\s+) is bounded here; the text is at most 200 characters.
    private val WEEKDAY_RULE = re(
        "$L(?:($THIS)\\s+)?(?:(?:في|ب)?يوم\\s+)?(?:(?:و|ب|ف|ل)?(ال|هال)($WEEKDAY)|(?<=يوم\\s{1,${QuickAdd.MAX_TEXT}})($WEEKDAY))(?:\\s+(?:($NEXT)|($THIS)|(?:(?:من|في|ب)\\s*)?(?:ال)?اسبوع\\s+($NEXT)|($PAST)))?$R",
    )
    private val NEXT_WEEK = re("$L(?:ال)?اسبوع\\s+(?:$NEXT)$R")
    // «5 العصر», «الساعة 8», «7:30 الصبح», «خمسة ونص المغرب», «عالساعة 4 إلا ربع», «10م»
    private val TIME = re(
        "$L((?:(?:على|علي|ع|ب)\\s*)?(?:ال)?ساعه\\s*|(?:على|علي)\\s+|عال)?($NUMBER)(?:\\s*[:.]\\s*(\\d{2}))?(?:\\s*(?:و\\s*(نص|نصف|ربع|ثلث)|(?:الا|غير)\\s*(ربع|ثلث)|و\\s*(\\d{1,2})\\s*(?:دقيقه|دقايق|دقائق)?))?(?:\\s*($PERIOD_AFTER))?(?![\\p{L}\\p{N}])",
    )
    private val PERIOD_RULE = re("$L($PERIOD_ALONE)$R")
    private val DAY_IN_LIST = re("(?<![\\p{L}])(?:و)?(?:ال)?($WEEKDAY)(?![\\p{L}])")
    private val AFTER_DAY = re("^\\s*(?:(?:على|علي|ع|في)\\s+)?$")
    private val FRACTION = mapOf("نص" to 30, "نصف" to 30, "ربع" to 15, "ثلث" to 20)
    private val ARABIC_LETTER = Regex("[؀-ۿ]")
    private val WEEKS = Regex("اسبوع|اسابيع")
    private val TWO_UNITS = Regex("^(ساعتين|ساعتان|دقيقتين|دقيقتان|يومين|يومان|اسبوعين|شهرين)$")
    private val ZERO_PADDED = Regex("^0[0-9]$")

    /** What a rule does with a match: take it as part of the date, skip it, or take it out of play only. */
    private enum class Take { KEEP, SKIP, IGNORE }

    private fun MatchResult.g(i: Int): String? = groups[i]?.value?.takeIf { it.isNotEmpty() }

    fun parse(text: String): Reading? {
        if (!ARABIC_LETTER.containsMatchIn(text)) return null
        val norm = ArabicText.normalize(text)
        val n = norm.text
        val used = ArrayList<IntArray>()   // normalized [start, end) of every phrase taken, also ignored ones
        val spans = ArrayList<IntArray>()  // the phrases that are part of the date, cut from the title
        val reading = Reading()
        var period: Period? = null
        fun free(s: Int, e: Int) = used.none { (a, b) -> s < b && e > a }
        // Runs one rule over the parts of the text no other rule took.
        fun each(rule: Regex, take: (MatchResult, Int, Int) -> Take) {
            for (m in rule.findAll(n)) {
                val s = m.range.first
                val e = m.range.last + 1
                if (!free(s, e)) continue
                val kept = take(m, s, e)
                if (kept == Take.SKIP) continue
                used += intArrayOf(s, e)
                if (kept != Take.IGNORE) spans += intArrayOf(s, e)
            }
        }
        // Counted repeats first: «كل ثلاثة أيام» is every three days, not every Tuesday («الثلاثة»).
        each(EVERY_N) { m, _, _ ->
            val count = (numberValue(m.g(1)!!) ?: 0).toLong() * (if (WEEKS.containsMatchIn(m.g(2)!!)) 7 else 1)
            if (reading.repeat != null || count == 0L || count > 365) return@each Take.SKIP
            reading.repeat = when (count) { 1L -> RepeatSpec(Repeat.DAILY); 7L -> RepeatSpec(Repeat.WEEKLY); else -> RepeatSpec(Repeat.INTERVAL, every = count.toInt()) }
            Take.KEEP
        }
        each(EVERY_TWO) { m, _, _ ->
            if (reading.repeat != null) return@each Take.SKIP
            reading.repeat = RepeatSpec(Repeat.INTERVAL, every = if (m.g(1) == "يومين") 2 else 14)
            Take.KEEP
        }
        each(EVERY_WEEKDAY) { m, _, _ ->
            if (reading.repeat != null) return@each Take.SKIP
            val days = DAY_IN_LIST.findAll(m.g(1)!!).map { weekdayOf(it.groupValues[1]) }.filter { it >= 0 }.distinct().sorted().toList()
            if (days.isEmpty()) return@each Take.SKIP
            reading.repeat = if (days.size == 1) RepeatSpec(Repeat.WEEKLY) else RepeatSpec(Repeat.DAYS, days = days)
            reading.date = if (days.size == 1) DateSpec.Weekday(days[0], Mod.REPEAT) else DateSpec.Days(days)
            Take.KEEP
        }
        each(DAILY) { m, _, _ ->
            if (reading.repeat != null) return@each Take.SKIP
            reading.repeat = RepeatSpec(Repeat.DAILY)
            m.g(1)?.let { word ->
                period = when (word) { "صباح" -> PERIODS[1]; "ليله" -> PERIODS[9]; "مسا", "مساء" -> PERIODS[7]; "عصر" -> PERIODS[5]; "ظهر" -> PERIODS[3]; else -> null }
            }
            Take.KEEP
        }
        for ((rule, repeat) in listOf(WEEKLY to Repeat.WEEKLY, MONTHLY to Repeat.MONTHLY, YEARLY to Repeat.YEARLY)) {
            each(rule) { _, _, _ -> if (reading.repeat != null) Take.SKIP else { reading.repeat = RepeatSpec(repeat); Take.KEEP } }
        }

        fun setDate(date: DateSpec): Take = if (reading.date != null) Take.SKIP else { reading.date = date; Take.KEEP }
        each(AFTER_TOMORROW) { _, _, _ -> setDate(DateSpec.Offset(2)) }
        each(RELATIVE) { m, _, _ ->
            if (reading.instant != null || reading.date != null) return@each Take.SKIP
            val unit = m.g(2) ?: m.g(4) ?: ""
            val count = when {
                m.g(1) != null -> 1
                m.g(3) != null -> numberValue(m.g(3)!!) ?: 0
                TWO_UNITS.matches(unit) -> 2
                else -> 1
            }
            if (count == 0) return@each Take.SKIP
            val extra = m.g(5)?.let { FRACTION[it]!! } ?: if (m.g(6) != null) -15 else 0
            when {
                unit.contains("ساع") || m.g(1) != null -> reading.instant = Moment(minutes = if (m.g(1) != null) FRACTION[m.g(1)!!]!! else count * 60 + extra)
                unit.contains("دقي") || unit.contains("دقا") -> reading.instant = Moment(minutes = count)
                unit.contains("شهر") || unit.contains("اشهر") || unit.contains("شهور") -> reading.date = DateSpec.Months(count)
                else -> reading.date = DateSpec.Offset(count * (if (WEEKS.containsMatchIn(unit)) 7 else 1), keepTime = true)
            }
            Take.KEEP
        }
        each(TOMORROW) { _, _, _ -> setDate(DateSpec.Offset(1)) }
        each(TONIGHT) { _, _, _ -> if (setDate(DateSpec.Offset(0)) == Take.SKIP) Take.SKIP else { if (period == null) period = PERIODS[9]; Take.KEEP } }
        each(TODAY) { _, _, _ -> setDate(DateSpec.Offset(0)) }
        each(WEEKDAY_RULE) { m, _, _ ->
            // A past day («الخميس الماضي») is not a reminder time: leave it in the title.
            if (m.g(8) != null) return@each Take.IGNORE
            val day = weekdayOf(m.g(3) ?: m.g(4) ?: "")
            if (day < 0) return@each Take.SKIP
            val date = reading.date
            if (date is DateSpec.Weekday && date.mod == Mod.REPEAT) return@each if (date.day == day) Take.KEEP else Take.SKIP
            if (date != null) return@each Take.SKIP
            reading.date = DateSpec.Weekday(
                day,
                when {
                    m.g(7) != null -> Mod.NEXT_WEEK
                    m.g(5) != null -> Mod.NEXT
                    m.g(1) != null || m.g(6) != null || m.g(2) == "هال" -> Mod.THIS
                    else -> null
                },
            )
            Take.KEEP
        }
        each(NEXT_WEEK) { _, _, _ -> setDate(DateSpec.Offset(7)) }

        // A bare number is a time only when it is clearly one: after «الساعة» or «على», with minutes or a
        // part of the day, or right after a day word («بكرة 5»). «اشتري 5 كيلو» keeps its number.
        fun afterDay(s: Int) = spans.any { (_, e) -> e <= s && AFTER_DAY.containsMatchIn(n.substring(e, s)) }
        each(TIME) { m, s, _ ->
            if (reading.time != null || reading.instant != null) return@each Take.SKIP
            var hour = numberValue(Js.collapse(m.g(2)!!)) ?: return@each Take.SKIP
            var minute = m.g(3)?.toInt() ?: 0
            val fraction = m.g(4)?.let { FRACTION[it]!! } ?: m.g(5)?.let { -FRACTION[it]!! } ?: m.g(6)?.toInt() ?: 0
            // «كل ليلة 10», «الليلة 10»: the part of the day named before the number applies to it.
            val named = m.g(7)?.let { periodOf(it) }
            val p = named ?: period
            val clear = m.g(1) != null || m.g(3) != null || m.g(4) != null || m.g(5) != null || m.g(6) != null || named != null
            if (!clear && !afterDay(s)) return@each Take.SKIP
            // Words are 1-12; a typed 0-23 hour is a 24-hour time.
            if (hour > 23 || minute > 59 || fraction > 59) return@each Take.SKIP
            minute += fraction
            if (minute < 0) { minute += 60; hour = (hour + 23) % 24 }
            if (p != null && hour in 1..12) {
                reading.late = p.late?.invoke(hour) == true
                hour = p.hour(hour)
            } else if (p != null && hour == 0) reading.late = p.late?.invoke(12) == true
            val certain = p != null || hour == 0 || hour > 12 || (m.g(3) != null && ZERO_PADDED.matches(m.g(2)!!))
            reading.time = Clock(hour, minute, certain)
            if (p != null) period = null
            Take.KEEP
        }
        each(PERIOD_RULE) { m, _, _ ->
            if (reading.time != null || reading.instant != null) return@each Take.SKIP
            period = periodOf(m.g(1)!!) ?: return@each Take.SKIP
            Take.KEEP
        }
        period?.let { p ->
            if (reading.time == null) {
                reading.time = Clock(p.at, 0, true)
                reading.late = p.late?.invoke(p.at) == true
            }
        }
        if (spans.isEmpty() || (reading.date == null && reading.time == null && reading.instant == null && reading.repeat == null)) return null
        // A cut phrase takes its trailing marks with it («مساءً»).
        fun end(from: Int): Int { var i = from; while (i < text.length && ArabicText.isMark(text[i])) i++; return i }
        val map = norm.map
        fun typed(i: Int) = map.getOrElse(i) { text.length }
        reading.spans = spans.map { (s, e) -> intArrayOf(typed(s), end(typed(e - 1) + 1)) }
        return reading
    }
}
