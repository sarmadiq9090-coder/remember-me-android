package com.fastkiwi.rememberme.ui.settings

import android.content.Context
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.data.IcsAlarm
import com.fastkiwi.rememberme.data.IcsItem
import com.fastkiwi.rememberme.data.IcsNote
import com.fastkiwi.rememberme.data.IcsRulePart
import com.fastkiwi.rememberme.ui.L10n
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The calendar import report (F-085) in the app language (F-100): one line per [IcsNote]. Numbers are passed
 * as text (Western digits), lists are joined with the interface separator («، » in Arabic), dates go through
 * [L10n], and item titles, zone ids and unknown rule part names are isolated ([L10n.isolate], F-102) so a Latin
 * title inside an Arabic sentence keeps its direction.
 */
object IcsReportText {
    fun line(c: Context, n: IcsNote): String = when (n) {
        is IcsNote.About -> c.getString(R.string.ics_item_line, item(c, n.item), reason(c, n))
        is IcsNote.TooBig -> c.getString(R.string.ics_w_too_big, num(n.parts), num(n.lines))
        IcsNote.CutOff -> c.getString(R.string.ics_w_cut_off)
        is IcsNote.NotItems -> c.getString(R.string.ics_w_not_items, join(c, parts(c, n.counts)))
        is IcsNote.TooMany -> c.resources.getQuantityString(R.plurals.ics_w_too_many, n.more, num(n.max), num(n.more))
        is IcsNote.AllDay -> {
            val shown = n.items.take(5).map { item(c, it) }
            val names = join(c, shown) + if (n.items.size > 5) " " + plural(c, R.plurals.ics_and_more, n.items.size - 5) else ""
            c.resources.getQuantityString(R.plurals.ics_w_all_day, n.items.size, num(n.items.size), time(c, n.time), names)
        }
        is IcsNote.UnknownZone -> c.getString(R.string.ics_w_unknown_zone, iso(c, n.tzid), iso(c, n.phoneZone))
    }

    /** "«Title»", or «الحدث رقم 3» for an item without a title. */
    fun item(c: Context, i: IcsItem): String = when {
        i.title != null -> c.getString(R.string.ics_item_title, iso(c, i.title))
        i.todo -> c.getString(R.string.ics_item_task_n, num(i.number))
        else -> c.getString(R.string.ics_item_event_n, num(i.number))
    }

    private fun reason(c: Context, n: IcsNote.About): String = when (n) {
        is IcsNote.ChangedOccurrence -> c.getString(R.string.ics_w_changed_occurrence)
        is IcsNote.TitleShortened -> c.getString(R.string.ics_w_title_short, num(n.max))
        is IcsNote.BodyShortened -> c.getString(R.string.ics_w_body_short, num(n.max))
        is IcsNote.NoStart -> c.getString(R.string.ics_w_no_start)
        is IcsNote.ExtraDatesOnly -> c.getString(R.string.ics_w_extra_dates)
        is IcsNote.RepeatWithoutTime -> c.getString(R.string.ics_w_repeat_no_time)
        is IcsNote.MonthEnd -> c.getString(R.string.ics_w_month_end, num(n.day))
        is IcsNote.LeapDay -> c.getString(R.string.ics_w_leap_day, dayMonth(c, LocalDate.of(2028, 2, 29)), dayMonth(c, LocalDate.of(2027, 2, 28)))
        is IcsNote.ClockDiffers -> c.getString(R.string.ics_w_clock, iso(c, n.zone), iso(c, n.phoneZone))
        is IcsNote.RuleNotKept -> c.getString(R.string.ics_w_rule, rules(c, n.parts))
        is IcsNote.StartsLater -> c.getString(R.string.ics_w_starts_later, num(n.day), date(c, n.start), date(c, n.left))
        is IcsNote.AlarmsWithoutTime -> c.getString(R.string.ics_w_alarms_no_time)
        is IcsNote.AlarmNotKept -> c.getString(R.string.ics_w_alarm, alarm(c, n.alarm))
        is IcsNote.TooManyAlarms -> c.getString(R.string.ics_w_too_many_alarms, join(c, n.kept.map { lead(c, it) }), join(c, n.dropped.map { lead(c, it) }))
        is IcsNote.Incomplete -> c.getString(if (n.item.todo) R.string.ics_s_incomplete_task else R.string.ics_s_incomplete_event)
        is IcsNote.NoTitle -> c.getString(R.string.ics_s_no_title)
        is IcsNote.Cancelled -> c.getString(R.string.ics_s_cancelled)
        is IcsNote.Completed -> c.getString(R.string.ics_s_completed)
        is IcsNote.Duplicate -> c.getString(R.string.ics_s_duplicate)
        is IcsNote.BadTime -> c.getString(R.string.ics_s_bad_time)
        is IcsNote.OutOfRange -> c.getString(R.string.ics_s_out_of_range, date(c, n.date))
        is IcsNote.Passed -> if (n.parts.isEmpty()) c.getString(R.string.ics_s_passed, dateTime(c, n.at))
        else c.getString(R.string.ics_s_passed_rule, rules(c, n.parts), dateTime(c, n.at))
    }

    fun alarm(c: Context, a: IcsAlarm): String = when (a) {
        IcsAlarm.NoTrigger -> c.getString(R.string.ics_alarm_no_trigger)
        is IcsAlarm.BadTime -> c.getString(R.string.ics_alarm_bad_time)
        is IcsAlarm.FromEnd -> c.getString(R.string.ics_alarm_from_end)
        is IcsAlarm.BadTrigger -> c.getString(R.string.ics_alarm_bad_trigger)
        is IcsAlarm.AfterStart -> c.getString(R.string.ics_alarm_after, span(c, a.seconds))
        is IcsAlarm.NotOffered -> c.getString(R.string.ics_alarm_not_offered, span(c, a.seconds))
    }

    fun rule(c: Context, p: IcsRulePart): String = when (p) {
        is IcsRulePart.Garbled -> c.getString(R.string.ics_r_garbled)
        is IcsRulePart.Twice -> c.getString(R.string.ics_r_twice)
        is IcsRulePart.Unsupported -> when (p.key) {
            "COUNT" -> c.getString(R.string.ics_r_count)
            "UNTIL" -> c.getString(R.string.ics_r_until)
            "BYHOUR" -> c.getString(R.string.ics_r_byhour)
            "BYMINUTE" -> c.getString(R.string.ics_r_byminute)
            "BYSECOND" -> c.getString(R.string.ics_r_bysecond)
            "BYWEEKNO" -> c.getString(R.string.ics_r_byweekno)
            "BYYEARDAY" -> c.getString(R.string.ics_r_byyearday)
            "RSCALE", "SKIP" -> c.getString(R.string.ics_r_rscale)
            else -> c.getString(R.string.ics_r_unknown, iso(c, p.key))
        }
        IcsRulePart.NoFreq -> c.getString(R.string.ics_r_no_freq)
        is IcsRulePart.BadValue -> when (p.key) {
            "BYDAY" -> c.getString(R.string.ics_r_bad_byday)
            "BYMONTHDAY" -> c.getString(R.string.ics_r_bad_monthday)
            else -> c.getString(R.string.ics_r_bad_interval)
        }
        is IcsRulePart.NotWithFreq -> c.getString(R.string.ics_r_not_with, key(c, p.key), freq(c, p.freq))
        IcsRulePart.HourlyAllDay -> c.getString(R.string.ics_r_hourly_all_day)
        is IcsRulePart.EveryHours -> c.getString(R.string.ics_r_every_hours, plural(c, R.plurals.hours, p.interval))
        is IcsRulePart.EveryDaysOnDays -> c.getString(R.string.ics_r_every_on_days, plural(c, R.plurals.days, p.interval))
        is IcsRulePart.EveryDays -> c.getString(R.string.ics_r_every_days, plural(c, R.plurals.days, p.interval))
        is IcsRulePart.EveryWeeksOnDays -> c.getString(R.string.ics_r_every_on_days, plural(c, R.plurals.ics_weeks, p.interval))
        is IcsRulePart.EveryWeeks -> c.getString(R.string.ics_r_every, plural(c, R.plurals.ics_weeks, p.interval))
        is IcsRulePart.EveryMonthsOrYears -> c.getString(R.string.ics_r_every, plural(c, if (p.yearly) R.plurals.ics_years else R.plurals.ics_months, p.interval))
        is IcsRulePart.OtherMonth -> c.getString(R.string.ics_r_other_month)
        is IcsRulePart.SetPos -> c.getString(R.string.ics_r_setpos)
        is IcsRulePart.MonthDays -> c.getString(R.string.ics_r_month_days, join(c, p.days.map { monthDay(c, it) }), num(p.startDay))
        is IcsRulePart.Freq -> when (p.freq) {
            "MINUTELY" -> c.getString(R.string.ics_r_minutely)
            "SECONDLY" -> c.getString(R.string.ics_r_secondly)
            else -> c.getString(R.string.ics_r_freq_unknown)
        }
        is IcsRulePart.SeveralRules -> plural(c, R.plurals.ics_r_several, p.count)
        is IcsRulePart.Extra -> c.getString(when (p.key) { "EXRULE" -> R.string.ics_r_exrule; "RDATE" -> R.string.ics_r_rdate; else -> R.string.ics_r_exdate })
    }

    // ---------------------------------------------------------------- pieces

    private fun rules(c: Context, parts: List<IcsRulePart>) = join(c, parts.map { rule(c, it) })

    private fun key(c: Context, k: String) = c.getString(when (k) {
        "BYDAY" -> R.string.ics_key_byday
        "BYMONTHDAY" -> R.string.ics_key_bymonthday
        "BYMONTH" -> R.string.ics_key_bymonth
        else -> R.string.ics_key_bysetpos
    })

    private fun freq(c: Context, f: String) = c.getString(when (f) {
        "HOURLY" -> R.string.ics_freq_hourly
        "DAILY" -> R.string.ics_freq_daily
        "WEEKLY" -> R.string.ics_freq_weekly
        "MONTHLY" -> R.string.ics_freq_monthly
        else -> R.string.ics_freq_yearly
    })

    private fun parts(c: Context, counts: Map<String, Int>): List<String> {
        var journal = 0
        var freeBusy = 0
        var other = 0
        for ((name, n) in counts) when (name) {
            "VJOURNAL" -> journal += n
            "VFREEBUSY" -> freeBusy += n
            else -> other += n
        }
        return listOfNotNull(
            journal.takeIf { it > 0 }?.let { plural(c, R.plurals.ics_part_journal, it) },
            freeBusy.takeIf { it > 0 }?.let { plural(c, R.plurals.ics_part_freebusy, it) },
            other.takeIf { it > 0 }?.let { plural(c, R.plurals.ics_part_other, it) },
        )
    }

    /** A day of month in a rule: 15, «الأخير» for -1, «2 من الآخر» for -2. */
    private fun monthDay(c: Context, d: Int): String = when {
        d == -1 -> c.getString(R.string.ics_r_last_day)
        d < 0 -> c.getString(R.string.ics_r_from_end, num(-d))
        else -> num(d)
    }

    /** An early alert: «يوم», «ساعة», «10 دقائق». */
    private fun lead(c: Context, minutes: Int): String = span(c, minutes * 60L)

    /** A span in the largest whole unit: days, hours, minutes, else seconds. */
    private fun span(c: Context, seconds: Long): String = when {
        seconds % 86_400 == 0L -> L10n.plural(c, R.plurals.days, seconds / 86_400)
        seconds % 3_600 == 0L -> L10n.plural(c, R.plurals.hours, seconds / 3_600)
        seconds % 60 == 0L -> L10n.plural(c, R.plurals.minutes, seconds / 60)
        else -> L10n.plural(c, R.plurals.ics_seconds, seconds)
    }

    private fun plural(c: Context, id: Int, n: Int) = L10n.plural(c, id, n.toLong())

    /** Western digits with the thousands comma: 5,000. */
    private fun num(n: Int) = "%,d".format(Locale.ROOT, n)

    private fun join(c: Context, parts: List<String>) = L10n.join(c, parts)

    private fun iso(c: Context, s: String) = L10n.isolate(c, s)

    private fun date(c: Context, d: LocalDate) = L10n.date(c, d.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun time(c: Context, t: LocalTime) = L10n.time(c, LocalDate.of(2000, 1, 1).atTime(t).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun dateTime(c: Context, at: LocalDateTime) =
        c.getString(R.string.day_at_time, date(c, at.toLocalDate()), time(c, at.toLocalTime()))

    private fun dayMonth(c: Context, d: LocalDate) = DateTimeFormatter.ofPattern("d MMMM", L10n.current(c)).format(d)
}
