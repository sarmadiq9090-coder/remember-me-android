package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.HijrahChronology

/**
 * The Umm al-Qura Hijri calendar (F-051): known dates, round trips, month ends, the adjustment, yearly
 * repeats. Robolectric, because the app uses Android's ICU calendar (android.icu); every day of the table
 * is compared with the JDK's independent HijrahChronology.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class HijriTest {
    @Test fun icuAgreesWithTheJdkOnEveryDayOfTheTable() {
        val first = LocalDate.from(HijrahChronology.INSTANCE.date(1300, 1, 1))
        val last = LocalDate.from(HijrahChronology.INSTANCE.date(1600, 12, 1)).plusDays(28)
        var d = first
        var mismatches = 0
        val examples = ArrayList<String>()
        while (!d.isAfter(last)) {
            val j = java.time.chrono.HijrahDate.from(d)
            val expected = HijriDate(j.get(java.time.temporal.ChronoField.YEAR), j.get(java.time.temporal.ChronoField.MONTH_OF_YEAR),
                j.get(java.time.temporal.ChronoField.DAY_OF_MONTH))
            val got = Hijri.of(d)
            if (got != expected) { mismatches++; if (examples.size < 5) examples += "$d: icu=$got jdk=$expected" }
            d = d.plusDays(1)
        }
        assertEquals(examples.joinToString("; "), 0, mismatches)
    }


    /**
     * Umm al-Qura dates from aladhan.com's public converter (calendarMethod=UAQ), e.g.
     * https://api.aladhan.com/v1/hToG/01-09-1447?calendarMethod=UAQ → 18-02-2026,
     * https://api.aladhan.com/v1/hToG/01-10-1447?calendarMethod=UAQ → 20-03-2026 (Eid al-Fitr),
     * https://api.aladhan.com/v1/hToG/10-12-1447?calendarMethod=UAQ → 27-05-2026 (Eid al-Adha),
     * https://api.aladhan.com/v1/hToG/01-01-1448?calendarMethod=UAQ → 16-06-2026,
     * https://api.aladhan.com/v1/hToG/01-09-1448?calendarMethod=UAQ → 08-02-2027;
     * and from its timings pages: 15-03-2026 = 26 Ramadan 1447, 01-10-2026 = 20 Rabi al-Thani 1448.
     */
    private val known = listOf(
        LocalDate.of(2026, 2, 18) to HijriDate(1447, 9, 1),
        LocalDate.of(2026, 3, 20) to HijriDate(1447, 10, 1),
        LocalDate.of(2026, 5, 27) to HijriDate(1447, 12, 10),
        LocalDate.of(2026, 6, 16) to HijriDate(1448, 1, 1),
        LocalDate.of(2027, 2, 8) to HijriDate(1448, 9, 1),
        LocalDate.of(2026, 3, 15) to HijriDate(1447, 9, 26),
        LocalDate.of(2026, 10, 1) to HijriDate(1448, 4, 20),
    )

    @Test fun knownDates() {
        for ((g, h) in known) {
            assertEquals("$g", h, Hijri.of(g))
            assertEquals("$h", g, Hijri.toGregorian(h))
        }
        // The day before 1 Ramadan 1447 is the last of Sha'ban (29 days that year).
        assertEquals(HijriDate(1447, 8, 29), Hijri.of(LocalDate.of(2026, 2, 17)))
        // Ramadan 1447 has 30 days (aladhan: "days":30), Shawwal 29.
        assertEquals(HijriDate(1447, 9, 30), Hijri.of(LocalDate.of(2026, 3, 19)))
    }

    @Test fun roundTripsAcrossTheTable() {
        val first = LocalDate.from(HijrahChronology.INSTANCE.date(1300, 1, 1))
        val last = LocalDate.from(HijrahChronology.INSTANCE.date(1600, 12, 1)).plusDays(28)
        assertEquals(LocalDate.of(1882, 11, 12), first)
        var d = first
        var count = 0
        var previous: HijriDate? = null
        while (!d.isAfter(last)) {
            val h = Hijri.of(d)
            assertEquals("$d", d, Hijri.toGregorian(h))
            if (previous != null) {
                val stepsOk = (h.year == previous.year && h.month == previous.month && h.day == previous.day + 1) ||
                    (h.day == 1 && previous.day in 29..30)
                assertTrue("$previous -> $h", stepsOk)
            }
            previous = h
            d = d.plusDays(1)
            count++
        }
        assertTrue(count > 106_000)
    }

    @Test fun outsideTheTableFallsBackWithoutCrashing() {
        for (g in listOf(LocalDate.of(1, 1, 1), LocalDate.of(622, 7, 16), LocalDate.of(1800, 1, 1), LocalDate.of(1882, 11, 11), LocalDate.of(2200, 6, 1), LocalDate.of(9999, 12, 31))) {
            val h = Hijri.of(g)
            assertTrue("$g -> $h", h.month in 1..12 && h.day in 1..30)
            assertEquals("$g", g, Hijri.toGregorian(h))
        }
        // The arithmetic calendar's epoch: 1 Muharram 1 AH = 16 July 622 (Julian) = 19 July 622 (proleptic Gregorian).
        assertEquals(HijriDate(1, 1, 1), Hijri.of(LocalDate.of(622, 7, 19)))
        // Year 1601 is past the table: still a date, about a lunar year after 1600.
        val y1601 = Hijri.toGregorian(HijriDate(1601, 1, 1))
        val y1600 = Hijri.toGregorian(HijriDate(1600, 1, 1))
        assertTrue((y1601.toEpochDay() - y1600.toEpochDay()) in 352L..357L)
        // Nonsense input is clamped, not thrown.
        Hijri.toGregorian(HijriDate(1447, 13, 31))
        Hijri.toGregorian(HijriDate(1447, 0, 0))
        assertEquals("ذو الحجة", Hijri.monthNameAr(99))
    }

    @Test fun day30BecomesDay29() {
        // Shawwal 1447 and Dhu al-Hijjah 1447 have 29 days.
        assertEquals(Hijri.toGregorian(HijriDate(1447, 10, 29)), Hijri.toGregorian(HijriDate(1447, 10, 30)))
        assertEquals(LocalDate.of(2026, 6, 15), Hijri.toGregorian(HijriDate(1447, 12, 30)))
        // Ramadan 1447 has 30 days: day 30 exists.
        assertEquals(LocalDate.of(2026, 3, 19), Hijri.toGregorian(HijriDate(1447, 9, 30)))
        // A yearly repeat on 30 Shawwal fires on 29 Shawwal in a 29-day year.
        val zone = ZoneId.of("Asia/Riyadh")
        val at = Hijri.nextYearly(10, 30, LocalTime.of(9, 0), Instant.parse("2026-03-01T00:00:00Z"), zone)
        assertEquals(ZonedDateTime.of(Hijri.toGregorian(HijriDate(1447, 10, 29)), LocalTime.of(9, 0), zone).toInstant(), at)
    }

    @Test fun adjustmentPlusMinusTwo() {
        val g = LocalDate.of(2026, 2, 18)
        assertEquals(HijriDate(1447, 9, 2), Hijri.of(g, 1))
        assertEquals(HijriDate(1447, 9, 3), Hijri.of(g, 2))
        assertEquals(HijriDate(1447, 8, 29), Hijri.of(g, -1))
        assertEquals(HijriDate(1447, 8, 28), Hijri.of(g, -2))
        assertEquals(LocalDate.of(2026, 2, 16), Hijri.toGregorian(HijriDate(1447, 9, 1), 2))
        assertEquals(LocalDate.of(2026, 2, 20), Hijri.toGregorian(HijriDate(1447, 9, 1), -2))
        for (adj in -2..2) {
            var d = LocalDate.of(2025, 1, 1)
            while (d.year < 2031) {
                assertEquals("$d $adj", d, Hijri.toGregorian(Hijri.of(d, adj), adj))
                d = d.plusDays(1)
            }
        }
        // The adjustment moves yearly repeats too: 1 Ramadan with −1 is a day later.
        val zone = ZoneId.of("Asia/Baghdad")
        val after = Instant.parse("2026-01-01T00:00:00Z")
        val plain = Hijri.nextYearly(9, 1, LocalTime.of(8, 0), after, zone)
        val late = Hijri.nextYearly(9, 1, LocalTime.of(8, 0), after, zone, -1)
        assertEquals(plain.plusSeconds(86_400), late)
    }

    @Test fun nextYearlyAcrossYearsAndDst() {
        val riyadh = ZoneId.of("Asia/Riyadh")
        val nine = LocalTime.of(9, 0)
        fun riyadhAt(d: LocalDate) = ZonedDateTime.of(d, nine, riyadh).toInstant()
        // Before the time on the day: today. At or after it: next Hijri year.
        assertEquals(riyadhAt(LocalDate.of(2026, 2, 18)), Hijri.nextYearly(9, 1, nine, Instant.parse("2026-02-18T05:00:00Z"), riyadh))
        assertEquals(riyadhAt(LocalDate.of(2027, 2, 8)), Hijri.nextYearly(9, 1, nine, riyadhAt(LocalDate.of(2026, 2, 18)), riyadh))
        // Across the Gregorian year boundary.
        assertEquals(riyadhAt(LocalDate.of(2027, 2, 8)), Hijri.nextYearly(9, 1, nine, Instant.parse("2026-12-31T23:00:00Z"), riyadh))
        // 1 Muharram 1448 = 16 June 2026, 1449 about 11 days earlier in 2027.
        val muharram = Hijri.nextYearly(1, 1, nine, Instant.parse("2026-07-01T00:00:00Z"), riyadh)
        assertEquals(Hijri.toGregorian(HijriDate(1449, 1, 1)), muharram.atZone(riyadh).toLocalDate())
        // Strictly after, over many years.
        var after = Instant.parse("2026-01-01T00:00:00Z")
        repeat(30) {
            val n = Hijri.nextYearly(12, 10, nine, after, riyadh)
            assertTrue(n.isAfter(after))
            assertEquals(HijriDate(Hijri.of(n.atZone(riyadh).toLocalDate()).year, 12, 10), Hijri.of(n.atZone(riyadh).toLocalDate()))
            after = n
        }
        // DST: wall-clock time kept in New York summer and winter; a time in the spring-forward gap moves an hour on.
        val ny = ZoneId.of("America/New_York")
        val gapDay = LocalDate.of(2027, 3, 14) // US DST starts
        val h = Hijri.of(gapDay)
        val gap = Hijri.nextYearly(h.month, h.day, LocalTime.of(2, 30), Instant.parse("2027-01-01T00:00:00Z"), ny)
        assertEquals(LocalTime.of(3, 30), gap.atZone(ny).toLocalTime())
        assertEquals(gapDay, gap.atZone(ny).toLocalDate())
        val summer = Hijri.nextYearly(12, 10, LocalTime.of(7, 15), Instant.parse("2026-01-01T00:00:00Z"), ny)
        assertEquals(LocalTime.of(7, 15), summer.atZone(ny).toLocalTime())
        assertEquals(LocalDate.of(2026, 5, 27), summer.atZone(ny).toLocalDate())
        val winter = Hijri.nextYearly(9, 1, LocalTime.of(7, 15), Instant.parse("2026-12-01T00:00:00Z"), ny)
        assertEquals(LocalTime.of(7, 15), winter.atZone(ny).toLocalTime())
        assertEquals(LocalDate.of(2027, 2, 8), winter.atZone(ny).toLocalDate())
    }

    @Test fun monthNames() {
        assertEquals("محرم", Hijri.monthNameAr(1))
        assertEquals("رمضان", Hijri.monthNameAr(9))
        assertEquals("Ramadan", Hijri.monthNameEn(9))
        assertEquals("Dhu al-Hijjah", Hijri.monthNameEn(12))
        assertEquals(12, (1..12).map { Hijri.monthNameAr(it) }.toSet().size)
    }
}
