package com.fastkiwi.rememberme.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The calendar import report (F-085) as data: every line the importer writes is one of these, with the values
 * the text needs. The screen renders them in the app language (ui/settings/IcsReportText.kt); [IcsNote.english]
 * is the plain English line, for tests and logs only.
 */

private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private fun count(n: Int) = "%,d".format(Locale.ROOT, n)

/** Minutes of an early alert, in English: "1 day", "10 minutes". */
internal fun englishMinutes(m: Int) = if (m == 1440) "1 day" else "$m minutes"

/** A span of seconds, in English: "2 day(s)", "90 seconds". */
internal fun englishSeconds(seconds: Long): String = when {
    seconds % 86_400 == 0L -> "${seconds / 86_400} day(s)"
    seconds % 3_600 == 0L -> "${seconds / 3_600} hour(s)"
    seconds % 60 == 0L -> "${seconds / 60} minutes"
    else -> "$seconds seconds"
}

/**
 * An event or task named in the report: its title (shortened to 60 characters), or, without a title, its kind
 * and its position among the file's events and tasks (1-based) and its UID when it has one.
 */
data class IcsItem(val title: String?, val todo: Boolean, val number: Int, val uid: String? = null) {
    fun english(): String = if (title != null) "\"$title\"" else "The ${if (todo) "task" else "event"} #$number" + (uid?.let { " (UID $it)" } ?: "")
}

/** A part of a repeat rule (RRULE and its companions) this app cannot keep exactly. Keys and values are the file's, upper case. */
sealed class IcsRulePart {
    abstract fun english(): String
    override fun toString() = english()

    /** A part without "=". */
    data class Garbled(val text: String) : IcsRulePart() { override fun english() = "\"$text\"" }
    data class Twice(val key: String) : IcsRulePart() { override fun english() = "$key twice" }
    /** A part this app has no equivalent for: COUNT, UNTIL, BYHOUR, BYWEEKNO, RSCALE, or any other [key]. */
    data class Unsupported(val key: String, val value: String) : IcsRulePart() { override fun english() = "$key=$value" }
    data object NoFreq : IcsRulePart() { override fun english() = "no FREQ" }
    /** INTERVAL, BYDAY (a numbered weekday such as 1MO) or BYMONTHDAY with a value that is not valid here. */
    data class BadValue(val key: String, val value: String) : IcsRulePart() { override fun english() = "$key=$value" }
    /** BYDAY, BYMONTHDAY, BYMONTH or BYSETPOS with a FREQ that cannot use it. */
    data class NotWithFreq(val key: String, val value: String, val freq: String) : IcsRulePart() { override fun english() = "$key=$value with FREQ=$freq" }
    data object HourlyAllDay : IcsRulePart() { override fun english() = "FREQ=HOURLY on an all-day date" }
    data class EveryHours(val interval: Int) : IcsRulePart() { override fun english() = "FREQ=HOURLY;INTERVAL=$interval (this app repeats every 1 to 23 hours)" }
    data class EveryDaysOnDays(val interval: Int) : IcsRulePart() { override fun english() = "FREQ=DAILY;INTERVAL=$interval with BYDAY" }
    data class EveryDays(val interval: Int) : IcsRulePart() { override fun english() = "FREQ=DAILY;INTERVAL=$interval (this app repeats every 2 to 365 days)" }
    data class EveryWeeksOnDays(val interval: Int, val days: String) : IcsRulePart() { override fun english() = "FREQ=WEEKLY;INTERVAL=$interval;BYDAY=$days" }
    data class EveryWeeks(val interval: Int) : IcsRulePart() { override fun english() = "FREQ=WEEKLY;INTERVAL=$interval" }
    /** FREQ=MONTHLY or YEARLY with an INTERVAL above 1. */
    data class EveryMonthsOrYears(val yearly: Boolean, val interval: Int) : IcsRulePart() {
        override fun english() = "FREQ=${if (yearly) "YEARLY" else "MONTHLY"};INTERVAL=$interval"
    }
    data class OtherMonth(val value: String, val startMonth: Int) : IcsRulePart() { override fun english() = "BYMONTH=$value (the start is in month $startMonth)" }
    data class SetPos(val value: String) : IcsRulePart() { override fun english() = "BYSETPOS=$value" }
    /** BYMONTHDAY (with [setPos] when given) that does not match the start's day [startDay]. Negative days count from the end. */
    data class MonthDays(val days: List<Int>, val setPos: String?, val startDay: Int) : IcsRulePart() {
        override fun english() = "BYMONTHDAY=${days.joinToString(",")}" + (setPos?.let { ";BYSETPOS=$it" } ?: "") + " (the start is on day $startDay)"
    }
    /** A FREQ this app cannot repeat by: MINUTELY, SECONDLY or an unknown one. */
    data class Freq(val freq: String) : IcsRulePart() { override fun english() = "FREQ=$freq" }
    data class SeveralRules(val count: Int) : IcsRulePart() { override fun english() = "$count RRULEs" }
    /** EXRULE, RDATE or EXDATE next to an RRULE. */
    data class Extra(val key: String) : IcsRulePart() { override fun english() = key }
}

/** An alarm (VALARM) this app cannot keep. */
sealed class IcsAlarm {
    abstract fun english(): String
    override fun toString() = english()

    data object NoTrigger : IcsAlarm() { override fun english() = "an alarm without a TRIGGER" }
    data class BadTime(val value: String) : IcsAlarm() { override fun english() = "an alarm at \"$value\"" }
    data class FromEnd(val value: String) : IcsAlarm() { override fun english() = "an alarm set from the end of the event ($value)" }
    data class BadTrigger(val value: String) : IcsAlarm() { override fun english() = "an alarm with the trigger \"$value\"" }
    data class AfterStart(val seconds: Long) : IcsAlarm() { override fun english() = "an alarm ${englishSeconds(seconds)} after the start" }
    /** Before the start, but not one of EARLY_MINUTES. */
    data class NotOffered(val seconds: Long) : IcsAlarm() {
        override fun english() = "an alarm ${englishSeconds(seconds)} before the start (this app offers 5, 10, 15, 30 or 60 minutes or 1 day)"
    }
}

/**
 * One line of the import report: something left out ([IcsImportResult.skipped]) or imported differently
 * ([IcsImportResult.warnings]). [item] is the event or task it is about, or null for the whole file.
 */
sealed class IcsNote {
    open val item: IcsItem? get() = null
    abstract fun english(): String
    override fun toString() = english()

    /** An item line: "label: reason". */
    sealed class About(override val item: IcsItem) : IcsNote() {
        protected abstract fun reason(): String
        override fun english() = item.english() + ": " + reason()
    }

    // ---------------------------------------------------------------- the whole file (warnings)

    data class TooBig(val parts: Int, val lines: Int) : IcsNote() {
        override fun english() = "The file has more than ${count(parts)} parts or ${count(lines)} lines; the rest of it was not read."
    }
    data object CutOff : IcsNote() { override fun english() = "The file ends early (END:VCALENDAR is missing); it may be cut off." }
    /** Components that are not events or tasks, by name (VJOURNAL, VFREEBUSY...), with how many. */
    data class NotItems(val counts: Map<String, Int>) : IcsNote() {
        override fun english() = "Not imported, as they are not events or tasks: " + counts.entries.joinToString(", ") { "${it.value} ${it.key}" } + "."
    }
    data class TooMany(val max: Int, val more: Int) : IcsNote() {
        override fun english() = "Only the first ${count(max)} events and tasks were read; ${count(more)} more were not imported."
    }
    /** All-day items, which get their reminder at [time] on their day. */
    data class AllDay(val items: List<IcsItem>, val time: LocalTime) : IcsNote() {
        override fun english(): String {
            val names = items.take(5).joinToString(", ") { it.english() } + if (items.size > 5) " and ${items.size - 5} more" else ""
            return "${items.size} all-day item(s) get their reminder at ${TIME.format(time)} on the day: $names."
        }
    }
    data class UnknownZone(val tzid: String, val phoneZone: String) : IcsNote() {
        override fun english() = "Unknown time zone \"$tzid\": its times are read as this phone's time zone ($phoneZone)."
    }

    // ---------------------------------------------------------------- one item (warnings)

    class ChangedOccurrence(item: IcsItem) : About(item) {
        override fun reason() = "it is one changed occurrence of a repeating event; it is imported as its own reminder, and the series keeps its usual time."
    }
    class TitleShortened(item: IcsItem, val max: Int) : About(item) { override fun reason() = "the title is shortened to ${count(max)} characters." }
    class BodyShortened(item: IcsItem, val max: Int) : About(item) { override fun reason() = "the description is shortened to ${count(max)} characters." }
    class NoStart(item: IcsItem) : About(item) { override fun reason() = "it has no start time; it is imported without a time." }
    /** RDATE without an RRULE. */
    class ExtraDatesOnly(item: IcsItem) : About(item) { override fun reason() = "its extra dates (RDATE) are not supported; only its first time is imported." }
    class RepeatWithoutTime(item: IcsItem) : About(item) { override fun reason() = "its repeat is not imported, as it has no time." }
    /** A monthly series on day 29-31: this app reminds on the last day of shorter months, the calendar skips them. */
    class MonthEnd(item: IcsItem, val day: Int) : About(item) {
        override fun reason() = "in months without day $day, this app reminds on the month's last day (the calendar skips those months)."
    }
    /** A yearly series on 29 February: this app reminds on 28 February in other years. */
    class LeapDay(item: IcsItem) : About(item) {
        override fun reason() = "in years without 29 February, this app reminds on 28 February (the calendar skips those years)."
    }
    /** A series in [zone] whose summer-time changes differ from the phone's [phoneZone]. */
    class ClockDiffers(item: IcsItem, val zone: String, val phoneZone: String) : About(item) {
        override fun reason() = "it repeats on $zone time, whose clock changes differ from this phone's ($phoneZone); " +
            "its occurrences follow this phone's clock, so some may be an hour off."
    }
    class RuleNotKept(item: IcsItem, val parts: List<IcsRulePart>) : About(item) {
        override fun reason() = "its repeat rule is not supported (${parts.joinToString(", ") { it.english() }}); it is imported as a one-time reminder."
    }
    /**
     * A month-end or 29 February series that starts on [start], the first date with its real day [day]; [left] is not
     * imported. No longer produced: a clamped start is itself an occurrence and is kept (IcsImport.monthAnchor).
     */
    class StartsLater(item: IcsItem, val day: Int, val start: LocalDate, val left: LocalDate) : About(item) {
        override fun reason() = "it keeps day $day of the month, so it starts on $start; the occurrence on $left is not imported."
    }
    class AlarmsWithoutTime(item: IcsItem) : About(item) { override fun reason() = "its alarms are not imported, as it has no time." }
    class AlarmNotKept(item: IcsItem, val alarm: IcsAlarm) : About(item) { override fun reason() = "${alarm.english()} is not imported." }
    /** More early alarms than this app keeps (3): [kept] are the nearest, in minutes, [dropped] the others. */
    class TooManyAlarms(item: IcsItem, val kept: List<Int>, val dropped: List<Int>) : About(item) {
        override fun reason() = "this app keeps up to ${kept.size} early alerts, the nearest (${kept.joinToString(", ") { englishMinutes(it) }}); " +
            dropped.joinToString(", ") { englishMinutes(it) } + " before is not imported."
    }

    // ---------------------------------------------------------------- one item left out (skipped)

    class Incomplete(item: IcsItem) : About(item) {
        override fun reason() = "the file ends inside this ${if (item.todo) "task" else "event"}, so it may be incomplete."
    }
    class NoTitle(item: IcsItem) : About(item) { override fun reason() = "it has no title." }
    class Cancelled(item: IcsItem) : About(item) { override fun reason() = "it is cancelled." }
    class Completed(item: IcsItem) : About(item) { override fun reason() = "it is already completed." }
    class Duplicate(item: IcsItem) : About(item) {
        override fun reason() = "an earlier ${if (item.todo) "task" else "event"} has the same UID (a duplicate)."
    }
    class BadTime(item: IcsItem, val value: String) : About(item) { override fun reason() = "its time could not be read ($value)." }
    class OutOfRange(item: IcsItem, val date: LocalDate) : About(item) { override fun reason() = "its date ($date) is out of range." }
    /** A one-time item whose time [at] (the phone's wall time) has passed; [parts] when it had a repeat rule this app cannot keep. */
    class Passed(item: IcsItem, val at: LocalDateTime, val parts: List<IcsRulePart> = emptyList()) : About(item) {
        override fun reason() = if (parts.isNotEmpty()) "its repeat rule is not supported (${parts.joinToString(", ") { it.english() }}) and its time (${WHEN.format(at)}) has passed."
        else "its time (${WHEN.format(at)}) has passed."
    }
}
