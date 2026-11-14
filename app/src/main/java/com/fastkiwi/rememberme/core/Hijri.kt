package com.fastkiwi.rememberme.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** A Hijri (Umm al-Qura) date: month 1 = Muharram … 12 = Dhu al-Hijjah. */
data class HijriDate(val year: Int, val month: Int, val day: Int)

/**
 * Hijri dates for display and for yearly Hijri repeats (F-051).
 *
 * The calendar is Android's ICU `IslamicCalendar` with the Umm al-Qura calculation (FEATURES.md F-051;
 * part of Android since API 24, so it is on every supported phone). The Umm al-Qura table covers
 * 1300–1600 AH; outside it ICU uses the arithmetic ("civil") calendar, within a day or two. The tests
 * compare every day of the table with the JDK's independent `HijrahChronology`.
 *
 * [adjustDays] (the user's −2…+2 correction for the local announcement) shifts the Hijri day: with
 * +1 the Hijri date shown for a Gregorian day is the one the table gives for the next day.
 */
object Hijri {
    private val utc = android.icu.util.TimeZone.getTimeZone("UTC")

    private fun calendar(): android.icu.util.IslamicCalendar =
        android.icu.util.IslamicCalendar(utc, android.icu.util.ULocale.ROOT).apply {
            calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        }

    /** The Umm al-Qura date of [date], shifted by [adjustDays]. */
    fun of(date: LocalDate, adjustDays: Int = 0): HijriDate {
        val d = date.plusDays(adjustDays.toLong())
        val c = calendar()
        c.timeInMillis = d.toEpochDay() * 86_400_000L
        return HijriDate(c.get(android.icu.util.Calendar.EXTENDED_YEAR), c.get(android.icu.util.Calendar.MONTH) + 1,
            c.get(android.icu.util.Calendar.DAY_OF_MONTH))
    }

    /** Days in Hijri [year]/[month] (29 or 30). */
    fun lengthOfMonth(year: Int, month: Int): Int {
        val c = calendar()
        c.clear()
        c.set(android.icu.util.Calendar.EXTENDED_YEAR, year)
        c.set(android.icu.util.Calendar.MONTH, month.coerceIn(1, 12) - 1)
        c.set(android.icu.util.Calendar.DAY_OF_MONTH, 1)
        return c.getActualMaximum(android.icu.util.Calendar.DAY_OF_MONTH)
    }

    /**
     * The Gregorian day of [h] (inverse of [of] with the same [adjustDays]). Month is clamped to 1..12
     * and day to 1..30; a day 30 in a 29-day month becomes day 29.
     */
    fun toGregorian(h: HijriDate, adjustDays: Int = 0): LocalDate {
        val month = h.month.coerceIn(1, 12)
        val day = minOf(h.day.coerceIn(1, 30), lengthOfMonth(h.year, month))
        val c = calendar()
        c.clear()
        c.set(android.icu.util.Calendar.EXTENDED_YEAR, h.year)
        c.set(android.icu.util.Calendar.MONTH, month - 1)
        c.set(android.icu.util.Calendar.DAY_OF_MONTH, day)
        val base = LocalDate.ofEpochDay(Math.floorDiv(c.timeInMillis, 86_400_000L))
        return base.minusDays(adjustDays.toLong())
    }

    /**
     * The first time after [after] that falls on Hijri [month]/[day] (adjusted by [adjustDays]) at
     * [time] in [zone]. Day 30 in a 29-day month fires on day 29. A [time] in a DST gap moves forward
     * (as [localToInstant] does), so the wall-clock time is kept on every other occurrence.
     */
    fun nextYearly(month: Int, day: Int, time: LocalTime, after: Instant, zone: ZoneId, adjustDays: Int = 0): Instant {
        val startYear = of(after.atZone(zone).toLocalDate(), adjustDays).year
        var year = startYear - 1
        while (true) {
            val date = toGregorian(HijriDate(year, month, day), adjustDays)
            val at = localToInstant(LocalDateTime.of(date, time), zone)
            if (at.isAfter(after)) return at
            year++
        }
    }

    private val namesAr = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )
    private val namesEn = listOf(
        "Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Shaban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah",
    )

    /** Arabic month name; [month] is clamped to 1..12. */
    fun monthNameAr(month: Int): String = namesAr[month.coerceIn(1, 12) - 1]

    /** English month name; [month] is clamped to 1..12. */
    fun monthNameEn(month: Int): String = namesEn[month.coerceIn(1, 12) - 1]
}
