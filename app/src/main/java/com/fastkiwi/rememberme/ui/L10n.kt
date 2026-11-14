package com.fastkiwi.rememberme.ui

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.jsDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Language and dates. Arabic is the default whatever the phone's language (D-007). Digits are always
 * Western (0-9), as in the Windows app: the locale carries -u-nu-latn, java.time formatters use ASCII
 * digits, and numbers are passed to string resources as text, never through %d (D-009).
 */
object L10n {
    /** Iraq and the Levant use «تشرين الثاني»; elsewhere «نوفمبر» (Windows "arabicMonths"). */
    private val LEVANT = setOf("IQ", "SY", "LB", "JO", "PS")

    fun locale(language: String, arabicMonths: String = "auto", deviceRegion: String = Locale.getDefault().country): Locale {
        if (language == "en") return Locale.Builder().setLanguage("en").setRegion(deviceRegion.takeIf { it.length == 2 } ?: "US").build()
        val region = when (arabicMonths) {
            "levant" -> "IQ"
            "standard" -> "EG"
            else -> if (deviceRegion in LEVANT) deviceRegion else "EG"
        }
        return Locale.Builder().setLanguage("ar").setRegion(region).setUnicodeLocaleKeyword("nu", "latn").build()
    }

    fun current(context: Context): Locale {
        val prefs = Repo.prefs(context)
        return locale(prefs.language, Repo.arabicMonths(Repo.state(context)))
    }

    /** Applies the app language (per-app locale; Android's own Settings shows the same choice). */
    fun apply(context: Context) {
        val tags = current(context).toLanguageTag()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != tags) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }

    /** A context in the app language, for notifications and widgets built outside an activity. */
    fun context(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(current(context))
        config.setLayoutDirection(current(context))
        return context.createConfigurationContext(config)
    }

    fun is24(context: Context) = DateFormat.is24HourFormat(context)

    fun time(context: Context, at: Instant, zone: ZoneId = Repo.zone()): String {
        val f = DateTimeFormatter.ofPattern(if (is24(context)) "H:mm" else "h:mm a", current(context))
        return f.format(at.atZone(zone))
    }

    /** "Today 5:30 PM", "Tomorrow 9:00 AM", "Thursday 20 November, 8:00 PM". */
    fun dueText(context: Context, at: Instant, now: Instant = Instant.now(), zone: ZoneId = Repo.zone()): String {
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val loc = current(context)
        val t = time(context, at, zone)
        val dayText = when (day) {
            today -> context.getString(R.string.today)
            today.plusDays(1) -> context.getString(R.string.tomorrow)
            today.minusDays(1) -> context.getString(R.string.yesterday)
            else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy", loc).format(day)
        }
        return context.getString(R.string.day_at_time, dayText, t)
    }

    fun date(context: Context, at: Instant, zone: ZoneId = Repo.zone()): String =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", current(context)).format(at.atZone(zone))

    /** "5 minutes", "2 hours", "3 days" with the right Arabic plural form. */
    fun duration(context: Context, minutes: Long): String = when {
        minutes < 60 -> plural(context, R.plurals.minutes, minutes)
        minutes < 48 * 60 -> plural(context, R.plurals.hours, minutes / 60)
        else -> plural(context, R.plurals.days, minutes / (24 * 60))
    }

    fun plural(context: Context, id: Int, n: Long): String =
        context.resources.getQuantityString(id, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n.toString())

    /** How late a reminder is, or null within its first minute. */
    fun late(context: Context, dueAt: Instant, now: Instant = Instant.now()): String? {
        val minutes = (now.toEpochMilli() - dueAt.toEpochMilli()) / 60_000
        return if (minutes < 1) null else context.getString(R.string.late_by, duration(context, minutes))
    }

    /** Weekday short names in order of the user's first day of the week (0 = Sunday … 6 = Saturday). */
    fun weekdays(weekStart: Int): List<Int> = (0..6).map { (weekStart + it) % 7 }

    fun weekdayName(context: Context, jsDay: Int, short: Boolean = false): String {
        val dow = java.time.DayOfWeek.of(if (jsDay == 0) 7 else jsDay)
        return dow.getDisplayName(if (short) java.time.format.TextStyle.SHORT else java.time.format.TextStyle.FULL, current(context))
    }

    fun repeatText(context: Context, t: Task, zone: ZoneId = Repo.zone()): String? = when (t.repeat) {
        Repeat.NONE -> null
        Repeat.DAILY -> context.getString(R.string.repeat_daily)
        Repeat.WEEKLY -> context.getString(R.string.repeat_weekly_on, weekdayName(context, jsDay(t.dueAt!!.atZone(zone).toLocalDate())))
        Repeat.DAYS -> context.getString(R.string.repeat_days_on, t.rule.days.joinToString(context.getString(R.string.list_separator)) { weekdayName(context, it, true) })
        Repeat.INTERVAL -> context.getString(R.string.repeat_every, plural(context, R.plurals.days, t.rule.everyDays.toLong()))
        Repeat.HOURS -> context.getString(R.string.repeat_every, plural(context, R.plurals.hours, t.rule.everyHours.toLong()))
        Repeat.MONTHLY -> context.getString(R.string.repeat_monthly)
        Repeat.YEARLY -> context.getString(R.string.repeat_yearly)
    }
}
