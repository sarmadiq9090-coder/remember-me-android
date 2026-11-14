package com.fastkiwi.rememberme.core.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Prayer times (F-050) against (1) an independent reference written here, (2) published timetable
 * entries, and (3) the edge cases: next() ordering, DST zones and a polar date.
 */
// Robolectric: Umm al-Qura's Ramadan rule reads the Hijri month from Android's ICU calendar.
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class PrayerTimesTest {

    private fun city(name: String): Place = Places.byEnglishName(name) ?: error("no city $name")

    // ---------------------------------------------------------------------------------------------
    // (1) The independent reference: NOAA's solar position equations (Jean Meeus, "Astronomical
    // Algorithms", as in the NOAA Solar Calculator spreadsheet, https://gml.noaa.gov/grad/solcalc/
    // calcdetails.html) and a bisection search on the sun's true altitude. It shares no code with
    // PrayerTimes (which uses the USNO low-precision series and closed-form hour angles); it only
    // shares the documented conventions (−0.833° horizon, Asr shadow rule, the night-fraction
    // high-latitude bound, null on polar dates, Ramadan by the Umm al-Qura month).
    // ---------------------------------------------------------------------------------------------

    private class RefParams(val fajr: Double, val isha: Double?, val ishaMin: Int, val ramadanIshaMin: Int, val maghribAngle: Double?, val plus: Int)

    private val refParams = mapOf(
        Method.UMM_AL_QURA to RefParams(18.5, null, 90, 120, null, 0),
        Method.MWL to RefParams(18.0, 17.0, 0, 0, null, 0),
        Method.EGYPTIAN to RefParams(19.5, 17.5, 0, 0, null, 0),
        Method.KARACHI to RefParams(18.0, 18.0, 0, 0, null, 0),
        Method.ISNA to RefParams(15.0, 15.0, 0, 0, null, 0),
        Method.DUBAI to RefParams(18.2, 18.2, 0, 0, null, 3),
        Method.KUWAIT to RefParams(18.0, 17.5, 0, 0, null, 0),
        Method.QATAR to RefParams(18.0, null, 90, 90, null, 0),
        Method.JAFARI to RefParams(16.0, 14.0, 0, 0, 4.0, 0),
        Method.TEHRAN to RefParams(17.7, 14.0, 0, 0, 4.5, 0),
    )

    private object Noaa {
        private fun r(d: Double) = Math.toRadians(d)
        private fun d(x: Double) = Math.toDegrees(x)

        /** Declination (degrees) and equation of time (minutes) at Unix time [t] seconds. */
        fun sun(t: Double): Pair<Double, Double> {
            val jd = t / 86400.0 + 2440587.5
            val c = (jd - 2451545.0) / 36525.0
            val l0 = ((280.46646 + c * (36000.76983 + c * 0.0003032)) % 360.0 + 360.0) % 360.0
            val m = 357.52911 + c * (35999.05029 - 0.0001537 * c)
            val e = 0.016708634 - c * (0.000042037 + 0.0000001267 * c)
            val center = sin(r(m)) * (1.914602 - c * (0.004817 + 0.000014 * c)) +
                sin(r(2 * m)) * (0.019993 - 0.000101 * c) + sin(r(3 * m)) * 0.000289
            val trueLong = l0 + center
            val omega = 125.04 - 1934.136 * c
            val lambda = trueLong - 0.00569 - 0.00478 * sin(r(omega))
            val eps0 = 23.0 + (26.0 + (21.448 - c * (46.815 + c * (0.00059 - c * 0.001813))) / 60.0) / 60.0
            val eps = eps0 + 0.00256 * cos(r(omega))
            val decl = d(asin(sin(r(eps)) * sin(r(lambda))))
            val y = tan(r(eps / 2)) * tan(r(eps / 2))
            val eqt = 4.0 * d(
                y * sin(2 * r(l0)) - 2 * e * sin(r(m)) + 4 * e * y * sin(r(m)) * cos(2 * r(l0)) -
                    0.5 * y * y * sin(4 * r(l0)) - 1.25 * e * e * sin(2 * r(m)),
            )
            return decl to eqt
        }

        /** Hour angle (degrees, −180..180) of the sun at [t]. */
        fun hourAngle(t: Double, lon: Double): Double {
            val (_, eqt) = sun(t)
            val utMin = (((t % 86400.0) + 86400.0) % 86400.0) / 60.0
            var ha = (utMin + eqt + 4.0 * lon) / 4.0 - 180.0
            while (ha < -180.0) ha += 360.0
            while (ha >= 180.0) ha -= 360.0
            return ha
        }

        fun altitude(t: Double, lat: Double, lon: Double): Double {
            val (decl, _) = sun(t)
            val ha = hourAngle(t, lon)
            return d(asin(sin(r(lat)) * sin(r(decl)) + cos(r(lat)) * cos(r(decl)) * cos(r(ha))))
        }

        /** The transit nearest to [near] (Unix seconds). */
        fun transit(near: Double, lon: Double): Double {
            var t = near
            repeat(6) { t -= hourAngle(t, lon) / 360.0 * 86164.0 }
            return t
        }

        /** Bisection: when the altitude crosses [target] in the 12 hours before (morning) or after [transit]. */
        fun cross(transit: Double, target: Double, lat: Double, lon: Double, morning: Boolean): Double? {
            var lo = if (morning) transit - 43200.0 else transit
            var hi = if (morning) transit else transit + 43200.0
            fun f(t: Double) = (altitude(t, lat, lon) - target) * if (morning) 1.0 else -1.0
            if (f(lo) >= 0 || f(hi) <= 0) return null
            repeat(60) {
                val mid = (lo + hi) / 2
                if (f(mid) < 0) lo = mid else hi = mid
            }
            return (lo + hi) / 2
        }
    }

    /** Reference times (Unix seconds, unrounded) for one local date. */
    private fun reference(prayer: Prayer, date: LocalDate, p: Place, method: Method, asr: AsrRule): Double? {
        val zone = ZoneId.of(p.zoneId)
        val params = refParams.getValue(method)
        fun transitOf(day: LocalDate) = Noaa.transit(ZonedDateTime.of(day, LocalTime.NOON, zone).toEpochSecond().toDouble(), p.lon)
        fun rise(day: LocalDate) = Noaa.cross(transitOf(day), -0.833, p.lat, p.lon, true)
        fun set(day: LocalDate) = Noaa.cross(transitOf(day), -0.833, p.lat, p.lon, false)
        val transit = transitOf(date)
        val sunrise = rise(date)
        val sunset = set(date)
        val polar = sunrise == null || sunset == null
        val k = if (abs(p.lat) > 48.0) 1.0 / 7.0 else 0.5
        fun maghribRaw(): Double? {
            if (polar) return null
            val a = params.maghribAngle ?: return sunset
            return Noaa.cross(transit, -a, p.lat, p.lon, false) ?: sunset
        }
        return when (prayer) {
            Prayer.DHUHR -> transit + params.plus * 60.0
            Prayer.ASR -> {
                val decl = Noaa.sun(transit).first
                val z = abs(p.lat - decl)
                if (z >= 90.0) null else {
                    val alt = Math.toDegrees(atan(1.0 / ((if (asr == AsrRule.HANAFI) 2.0 else 1.0) + tan(Math.toRadians(z)))))
                    Noaa.cross(transit, alt, p.lat, p.lon, false)
                }
            }
            Prayer.SUNRISE -> if (polar) null else sunrise
            Prayer.MAGHRIB -> maghribRaw()?.plus(params.plus * 60.0)
            Prayer.FAJR -> {
                if (polar) return null
                val before = set(date.minusDays(1)) ?: return null
                val safe = sunrise!! - k * (sunrise - before)
                val angle = Noaa.cross(transit, -params.fajr, p.lat, p.lon, true)
                if (angle == null || angle < safe) safe else angle
            }
            Prayer.ISHA -> {
                if (polar) return null
                if (params.isha == null) {
                    val ramadan = HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR) == 9
                    maghribRaw()!! + 60.0 * (if (ramadan) params.ramadanIshaMin else params.ishaMin)
                } else {
                    val after = rise(date.plusDays(1)) ?: return null
                    val safe = sunset!! + k * (after - sunset)
                    val angle = Noaa.cross(transit, -params.isha, p.lat, p.lon, false)
                    if (angle == null || angle > safe) safe else angle
                }
            }
        }
    }

    private val referenceCities = listOf(
        "Makkah", "Madinah", "Riyadh", "Baghdad", "Basra", "Erbil", "Najaf", "Kuwait City", "Doha", "Dubai",
        "Muscat", "Sanaa", "Amman", "Damascus", "Beirut", "Jerusalem", "Cairo", "Khartoum", "Tripoli", "Tunis",
        "Algiers", "Casablanca", "Tehran", "Istanbul", "Karachi", "Jakarta", "Kuala Lumpur", "London", "Paris",
        "Stockholm", "Moscow", "New York", "Toronto", "Sydney", "Auckland", "Cape Town",
    )

    /** 2026–2030, eight dates a year: solstices, equinoxes, DST change days and Ramadan days. */
    private val referenceDates: List<LocalDate> = (2026..2030).flatMap { y ->
        listOf(
            LocalDate.of(y, 1, 15), LocalDate.of(y, 3, 1), LocalDate.of(y, 3, 29), LocalDate.of(y, 5, 10),
            LocalDate.of(y, 6, 21), LocalDate.of(y, 9, 22), LocalDate.of(y, 11, 1), LocalDate.of(y, 12, 21),
        )
    }

    @Test fun matchesIndependentReferenceWithinOneMinute() {
        var cases = 0
        var comparisons = 0
        val maxByMethod = mutableMapOf<Method, Double>()
        val failures = mutableListOf<String>()
        for (name in referenceCities) {
            val p = city(name)
            for (date in referenceDates) for (method in Method.entries) {
                cases++
                for (prayer in Prayer.entries) {
                    val rules = if (prayer == Prayer.ASR) AsrRule.entries else listOf(AsrRule.STANDARD)
                    for (asr in rules) {
                        val mine = PrayerTimes.time(prayer, date, p, method, asr)
                        val ref = reference(prayer, date, p, method, asr)
                        if (mine == null || ref == null) {
                            if ((mine == null) != (ref == null)) failures += "$name $date $method $prayer: mine=$mine ref=$ref"
                            continue
                        }
                        comparisons++
                        val dev = abs(mine.epochSecond - ref)
                        maxByMethod[method] = maxOf(maxByMethod[method] ?: 0.0, dev)
                        if (dev > 60.0) failures += "$name $date $method $prayer $asr: mine=$mine ref=${Instant.ofEpochSecond(ref.toLong())} (${dev.toInt()} s)"
                    }
                }
            }
        }
        println("Prayer reference: $cases cases (city × date × method), $comparisons times compared")
        for ((m, dev) in maxByMethod) println("  max deviation $m: ${"%.1f".format(dev)} s")
        assertTrue("at least 2,000 cases", cases >= 2000)
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // (2) Published timetables. Each day is read from aladhan.com's public timings API (a separate
    // implementation), local times, rounded to the minute; checked within 2 minutes.
    // ---------------------------------------------------------------------------------------------

    private class Published(
        val url: String, val city: String, val date: LocalDate, val method: Method, val asr: AsrRule,
        val fajr: String, val sunrise: String, val dhuhr: String, val asrTime: String, val maghrib: String, val isha: String,
    )

    private val published = listOf(
        // https://api.aladhan.com/v1/timings/15-03-2026?latitude=21.42&longitude=39.83&method=4&timezonestring=Asia/Riyadh (26 Ramadan 1447: Isha 120 min)
        Published("aladhan Makkah", "Makkah", LocalDate.of(2026, 3, 15), Method.UMM_AL_QURA, AsrRule.STANDARD, "05:14", "06:30", "12:30", "15:53", "18:30", "20:30"),
        // https://api.aladhan.com/v1/timings/01-10-2026?latitude=24.71&longitude=46.68&method=4&timezonestring=Asia/Riyadh
        Published("aladhan Riyadh", "Riyadh", LocalDate.of(2026, 10, 1), Method.UMM_AL_QURA, AsrRule.STANDARD, "04:27", "05:45", "11:43", "15:08", "17:40", "19:10"),
        // https://api.aladhan.com/v1/timings/21-06-2026?latitude=30.04&longitude=31.24&method=5&timezonestring=Africa/Cairo
        Published("aladhan Cairo", "Cairo", LocalDate.of(2026, 6, 21), Method.EGYPTIAN, AsrRule.STANDARD, "04:08", "05:54", "12:57", "16:32", "19:59", "21:33"),
        // https://api.aladhan.com/v1/timings/10-01-2026?latitude=33.31&longitude=44.36&method=3&timezonestring=Asia/Baghdad
        Published("aladhan Baghdad MWL", "Baghdad", LocalDate.of(2026, 1, 10), Method.MWL, AsrRule.STANDARD, "05:39", "07:07", "12:10", "14:54", "17:13", "18:36"),
        // https://api.aladhan.com/v1/timings/01-07-2026?latitude=33.31&longitude=44.36&method=0&timezonestring=Asia/Baghdad
        Published("aladhan Baghdad Jafari", "Baghdad", LocalDate.of(2026, 7, 1), Method.JAFARI, AsrRule.STANDARD, "03:28", "04:57", "12:06", "15:50", "19:34", "20:32"),
        // https://api.aladhan.com/v1/timings/01-09-2026?latitude=35.69&longitude=51.39&method=7&timezonestring=Asia/Tehran
        Published("aladhan Tehran", "Tehran", LocalDate.of(2026, 9, 1), Method.TEHRAN, AsrRule.STANDARD, "04:09", "05:36", "12:04", "15:43", "18:51", "19:40"),
        // https://api.aladhan.com/v1/timings/01-04-2026?latitude=24.86&longitude=67.01&method=1&school=1&timezonestring=Asia/Karachi
        Published("aladhan Karachi Hanafi", "Karachi", LocalDate.of(2026, 4, 1), Method.KARACHI, AsrRule.HANAFI, "05:07", "06:24", "12:36", "17:03", "18:48", "20:05"),
        // https://api.aladhan.com/v1/timings/04-07-2026?latitude=40.71&longitude=-74.01&method=2&timezonestring=America/New_York
        Published("aladhan New York", "New York", LocalDate.of(2026, 7, 4), Method.ISNA, AsrRule.STANDARD, "03:52", "05:30", "13:01", "17:00", "20:31", "22:09"),
        // https://api.aladhan.com/v1/timings/01-12-2026?latitude=25.2&longitude=55.27&method=16&timezonestring=Asia/Dubai
        Published("aladhan Dubai", "Dubai", LocalDate.of(2026, 12, 1), Method.DUBAI, AsrRule.STANDARD, "05:25", "06:47", "12:11", "15:09", "17:31", "18:50"),
        // https://api.aladhan.com/v1/timings/10-05-2026?latitude=29.38&longitude=47.98&method=9&timezonestring=Asia/Kuwait
        Published("aladhan Kuwait", "Kuwait City", LocalDate.of(2026, 5, 10), Method.KUWAIT, AsrRule.STANDARD, "03:31", "04:59", "11:45", "15:20", "18:30", "19:56"),
        // https://api.aladhan.com/v1/timings/15-08-2026?latitude=25.29&longitude=51.53&method=10&timezonestring=Asia/Qatar
        Published("aladhan Doha", "Doha", LocalDate.of(2026, 8, 15), Method.QATAR, AsrRule.STANDARD, "03:46", "05:07", "11:38", "15:08", "18:09", "19:39"),
    )

    @Test fun matchesPublishedTimetablesWithinTwoMinutes() {
        val failures = mutableListOf<String>()
        var entries = 0
        for (pub in published) {
            val p = city(pub.city)
            val zone = ZoneId.of(p.zoneId)
            val expected = listOf(pub.fajr, pub.sunrise, pub.dhuhr, pub.asrTime, pub.maghrib, pub.isha)
            for ((i, prayer) in Prayer.entries.withIndex()) {
                entries++
                val mine = PrayerTimes.time(prayer, pub.date, p, pub.method, pub.asr)!!
                val want = ZonedDateTime.of(pub.date, LocalTime.parse(expected[i]), zone).toInstant()
                val dev = abs(Duration.between(want, mine).seconds)
                if (dev > 120) failures += "${pub.url} $prayer: mine=${mine.atZone(zone).toLocalTime()} published=${expected[i]}"
            }
        }
        assertTrue(entries >= 10)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // (3) next(), DST and polar dates.
    // ---------------------------------------------------------------------------------------------

    @Test fun nextIsStrictlyAfter() {
        val p = city("Baghdad")
        for (prayer in Prayer.entries) for (offset in listOf(-30, 0, 10, 90)) {
            var after = Instant.parse("2026-11-15T00:00:00Z")
            repeat(5) {
                val next = PrayerTimes.next(prayer, offset, after, p, Method.MWL)!!
                assertTrue("$prayer $offset $next after $after", next.isAfter(after))
                assertTrue(Duration.between(after, next) <= Duration.ofHours(25))
                // Exactly at a time: the next one is the following day's.
                val again = PrayerTimes.next(prayer, offset, next, p, Method.MWL)!!
                assertTrue(again.isAfter(next))
                assertTrue(Duration.between(next, again) in Duration.ofHours(23)..Duration.ofHours(25))
                after = next
            }
        }
        // It matches time() on the right day: 10 minutes after Maghrib.
        val after = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, ZoneId.of("Asia/Baghdad")).toInstant()
        val m = PrayerTimes.time(Prayer.MAGHRIB, LocalDate.of(2026, 11, 15), p, Method.MWL)!!
        assertEquals(m.plusSeconds(600), PrayerTimes.next(Prayer.MAGHRIB, 10, after, p, Method.MWL))
        // Just after that, the next is tomorrow's.
        val tomorrow = PrayerTimes.time(Prayer.MAGHRIB, LocalDate.of(2026, 11, 16), p, Method.MWL)!!
        assertEquals(tomorrow.plusSeconds(600), PrayerTimes.next(Prayer.MAGHRIB, 10, m.plusSeconds(600), p, Method.MWL))
    }

    @Test fun dstZonesKeepTheLocalDay() {
        val cases = listOf(
            Triple("London", LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29)),
            Triple("London", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25)),
            Triple("New York", LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8)),
            Triple("New York", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)),
        )
        for ((name, before, change) in cases) {
            val p = city(name)
            val zone = ZoneId.of(p.zoneId)
            for (prayer in Prayer.entries) {
                val a = PrayerTimes.time(prayer, before, p, Method.MWL)!!
                val b = PrayerTimes.time(prayer, change, p, Method.MWL)!!
                assertEquals("$name $prayer on its local date", before, a.atZone(zone).toLocalDate())
                assertEquals("$name $prayer on its local date", change, b.atZone(zone).toLocalDate())
                // The clock moves by an hour, so the local time jumps by about an hour; the instant by about a day.
                val gap = Duration.between(a, b).toMinutes()
                assertTrue("$name $prayer $gap", gap in (24 * 60 - 10)..(24 * 60 + 10))
                val localShift = b.atZone(zone).toLocalTime().toSecondOfDay() - a.atZone(zone).toLocalTime().toSecondOfDay()
                assertTrue("$name $prayer shift $localShift", abs(abs(localShift) - 3600) <= 600)
                // next() from the evening before lands on the change day.
                val n = PrayerTimes.next(prayer, 0, a, p, Method.MWL)
                assertEquals(b, n)
            }
        }
    }

    @Test fun polarDateReturnsNullWithoutCrashing() {
        val tromso = city("Tromso")
        val june = LocalDate.of(2026, 6, 21)
        for (method in Method.entries) {
            assertNull(PrayerTimes.time(Prayer.FAJR, june, tromso, method))
            assertNull(PrayerTimes.time(Prayer.ISHA, june, tromso, method))
            assertNull(PrayerTimes.time(Prayer.SUNRISE, june, tromso, method))
            assertNull(PrayerTimes.time(Prayer.MAGHRIB, june, tromso, method))
            assertNotNull(PrayerTimes.time(Prayer.DHUHR, june, tromso, method))
        }
        // Polar night: no sunrise; Asr is null too.
        val december = LocalDate.of(2026, 12, 21)
        assertNull(PrayerTimes.time(Prayer.SUNRISE, december, tromso, Method.MWL))
        assertNull(PrayerTimes.time(Prayer.ASR, december, tromso, Method.MWL))
        // next() skips the polar weeks and still finds the first Isha after them.
        val n = PrayerTimes.next(Prayer.ISHA, 0, Instant.parse("2026-06-01T00:00:00Z"), tromso, Method.MWL)!!
        assertTrue(n.isAfter(Instant.parse("2026-07-01T00:00:00Z")))
        // Every day of a year, every method: never throws.
        var day = LocalDate.of(2026, 1, 1)
        while (day.year == 2026) {
            for (m in Method.entries) for (pr in Prayer.entries) PrayerTimes.time(pr, day, tromso, m, AsrRule.HANAFI)
            day = day.plusDays(1)
        }
    }

    @Test fun highLatitudeRuleGivesTimesInLondonSummer() {
        val london = city("London")
        val date = LocalDate.of(2026, 6, 21)
        val fajr = PrayerTimes.time(Prayer.FAJR, date, london, Method.MWL)!!
        val isha = PrayerTimes.time(Prayer.ISHA, date, london, Method.MWL)!!
        val sunrise = PrayerTimes.time(Prayer.SUNRISE, date, london, Method.MWL)!!
        val maghrib = PrayerTimes.time(Prayer.MAGHRIB, date, london, Method.MWL)!!
        assertTrue(fajr.isBefore(sunrise))
        assertTrue(isha.isAfter(maghrib))
        // One-seventh of a ~8 h 20 min night: about 71 minutes.
        assertTrue(Duration.between(maghrib, isha).toMinutes() in 60L..80L)
    }

    @Test fun placesAreComplete() {
        val all = Places.all
        assertTrue("${all.size} cities", all.size >= 150)
        assertEquals(all.size, all.map { it.nameEn }.toSet().size)
        for (p in all) {
            ZoneId.of(p.zoneId)
            assertTrue(p.nameAr.isNotBlank() && p.nameEn.isNotBlank())
            assertTrue(p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
            assertEquals(2, p.country.length)
            // A sane local noon: transit within 3 hours of 12:00 local.
            val dhuhr = PrayerTimes.time(Prayer.DHUHR, LocalDate.of(2026, 3, 20), p, Method.MWL)!!
            val local = dhuhr.atZone(ZoneId.of(p.zoneId)).toLocalTime()
            assertTrue("${p.nameEn} $local", local.isAfter(LocalTime.of(9, 0)) && local.isBefore(LocalTime.of(15, 0)))
        }
        // Every Iraqi governorate centre.
        val iraq = listOf(
            "Baghdad", "Basra", "Mosul", "Erbil", "Sulaymaniyah", "Duhok", "Kirkuk", "Ramadi", "Baqubah", "Tikrit",
            "Hillah", "Karbala", "Najaf", "Diwaniyah", "Kut", "Amarah", "Nasiriyah", "Samawah", "Halabja",
        )
        for (c in iraq) assertEquals("IQ", city(c).country)
    }
}
