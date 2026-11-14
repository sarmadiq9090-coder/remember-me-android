package com.fastkiwi.rememberme.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/** A Hijri (Umm al-Qura) date: month 1 = Muharram … 12 = Dhu al-Hijjah. */
data class HijriDate(val year: Int, val month: Int, val day: Int)

/**
 * Hijri dates for display and for yearly Hijri repeats (F-051).
 *
 * The calendar is the JDK's `HijrahChronology` ("Hijrah-umalqura"), i.e. the Umm al-Qura table used
 * in Saudi Arabia; Android has it from API 26 (our minSdk). The table covers 1 Muharram 1300 AH
 * (1882-11-12) to the end of 1600 AH (2174-11-25). Outside that range nothing throws: both directions
 * fall back to the arithmetic (tabular, "civil") Islamic calendar, which is within a day or two of
 * Umm al-Qura; near the two edges of the table the fallback may disagree with the table by a day.
 *
 * [adjustDays] (the user's −2…+2 correction for the local announcement) shifts the Hijri day: with
 * +1 the Hijri date shown for a Gregorian day is the one the table gives for the next day.
 */
object Hijri {
    private val chrono: HijrahChronology = HijrahChronology.INSTANCE
    private val yearRange = chrono.range(ChronoField.YEAR)

    /** The Umm al-Qura date of [date], shifted by [adjustDays]. */
    fun of(date: LocalDate, adjustDays: Int = 0): HijriDate {
        val d = date.plusDays(adjustDays.toLong())
        return try {
            val h = HijrahDate.from(d)
            HijriDate(h.get(ChronoField.YEAR), h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.DAY_OF_MONTH))
        } catch (_: DateTimeException) {
            Tabular.fromEpochDay(d.toEpochDay())
        }
    }

    /**
     * The Gregorian day of [h] (inverse of [of] with the same [adjustDays]). Month is clamped to 1..12
     * and day to 1..30; a day 30 in a 29-day month becomes day 29.
     */
    fun toGregorian(h: HijriDate, adjustDays: Int = 0): LocalDate {
        val month = h.month.coerceIn(1, 12)
        val day = h.day.coerceIn(1, 30)
        val base = if (yearRange.isValidValue(h.year.toLong())) {
            try {
                val first = chrono.date(h.year, month, 1)
                LocalDate.from(first.plus((minOf(day, first.lengthOfMonth()) - 1).toLong(), ChronoUnit.DAYS))
            } catch (_: DateTimeException) {
                LocalDate.ofEpochDay(Tabular.toEpochDay(h.year, month, day))
            }
        } else {
            LocalDate.ofEpochDay(Tabular.toEpochDay(h.year, month, day))
        }
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

    /** The arithmetic Islamic calendar (30-year cycle, leap years 2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29). */
    private object Tabular {
        /** Julian Day Number of 1 Muharram 1 AH (16 July 622, Julian calendar). */
        private const val EPOCH_JDN = 1948440L
        private const val UNIX_EPOCH_JDN = 2440588L

        fun toEpochDay(year: Int, month: Int, day: Int): Long {
            val y = year.toLong()
            val monthDays = Math.ceil(29.5 * (month - 1)).toLong()
            val jdn = day + monthDays + (y - 1) * 354 + Math.floorDiv(3 + 11 * y, 30L) + EPOCH_JDN - 1
            return jdn - UNIX_EPOCH_JDN
        }

        fun fromEpochDay(epochDay: Long): HijriDate {
            val jdn = epochDay + UNIX_EPOCH_JDN
            val year = Math.floorDiv(30 * (jdn - EPOCH_JDN) + 10646, 10631L).toInt()
            var month = 12
            while (month > 1 && toEpochDay(year, month, 1) > epochDay) month--
            val day = (epochDay - toEpochDay(year, month, 1) + 1).toInt()
            return HijriDate(year, month, day)
        }
    }
}
