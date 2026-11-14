package com.fastkiwi.rememberme.core.prayer

import com.fastkiwi.rememberme.core.Hijri
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class Prayer { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

/**
 * Calculation methods (F-050). Parameters as published by each authority; collected from
 * http://praytimes.org/calculation (MWL, ISNA, Egyptian, Umm al-Qura, Karachi, Tehran, Jafari/Leva
 * Institute Qum) and the adhan project (https://github.com/batoulapps/adhan-js, CalculationMethod.ts:
 * Dubai, Kuwait, Qatar, Umm al-Qura Fajr 18.5°).
 *
 * [fajr]/[isha] are depression angles of the sun below the horizon; [ishaMinutes] > 0 means Isha is
 * that many minutes after Maghrib instead ([ramadanIshaMinutes] in Ramadan); [maghrib] > 0 means
 * Maghrib is when the sun is that far below the horizon instead of sunset (the Shia convention).
 * [dhuhrMinutes]/[maghribMinutes] are fixed precautionary minutes the authority adds: only Dubai has
 * them (+3 to Dhuhr and Maghrib, as both aladhan.com's Dubai method and adhan's apply). Other
 * implementations (adhan) add +1 minute to Dhuhr for some methods; we do not, since the published
 * parameters do not include it.
 */
enum class Method(
    val fajr: Double,
    val isha: Double,
    val ishaMinutes: Int = 0,
    val ramadanIshaMinutes: Int = 0,
    val maghrib: Double = 0.0,
    val dhuhrMinutes: Int = 0,
    val maghribMinutes: Int = 0,
) {
    UMM_AL_QURA(18.5, 0.0, ishaMinutes = 90, ramadanIshaMinutes = 120),
    MWL(18.0, 17.0),
    EGYPTIAN(19.5, 17.5),
    KARACHI(18.0, 18.0),
    ISNA(15.0, 15.0),
    DUBAI(18.2, 18.2, dhuhrMinutes = 3, maghribMinutes = 3),
    KUWAIT(18.0, 17.5),
    QATAR(18.0, 0.0, ishaMinutes = 90, ramadanIshaMinutes = 90),
    JAFARI(16.0, 14.0, maghrib = 4.0),
    TEHRAN(17.7, 14.0, maghrib = 4.5),
}

/** Asr: STANDARD (Shafi'i, Maliki, Hanbali, Jafari) shadow = 1 × length + noon shadow; HANAFI 2 ×. */
enum class AsrRule(val shadow: Double) { STANDARD(1.0), HANAFI(2.0) }

/** A city (or typed coordinates): latitude/longitude in degrees (east and north positive), IANA zone, ISO country. */
data class Place(val nameAr: String, val nameEn: String, val lat: Double, val lon: Double, val zoneId: String, val country: String)

/**
 * Offline astronomical prayer times (F-050): no network, no location.
 *
 * The sun's declination and equation of time come from the U.S. Naval Observatory's approximate solar
 * coordinates (as used by praytimes.org; accurate to about 1′ for centuries around 2000). Each event
 * is found from the hour angle at which the sun reaches a given altitude, iterated so the sun's
 * position is taken at the event itself. Conventions:
 * - Dhuhr is the sun's transit; Sunrise/Sunset are at altitude −0.833° (refraction and the sun's
 *   radius, no elevation correction); Fajr/Isha at −angle; Asr when an object's shadow equals
 *   its noon shadow plus [AsrRule.shadow] × its length.
 * - Ramadan (for Umm al-Qura's 120-minute Isha) is the Umm al-Qura month of the local date ([Hijri]).
 * - High latitudes: Fajr is never earlier than Sunrise − night/k and Isha (when it is an angle) never
 *   later than Sunset + night/k, where night is sunset to the next sunrise and k = 7 above 48° of
 *   latitude ("one-seventh of the night") and 2 otherwise ("middle of the night"); this is adhan's
 *   recommended rule. The same bound is used when the angle is never reached (e.g. London in June).
 * - Polar dates: when the sun does not rise or does not set on the date (or the night next to it,
 *   which the bound needs), Fajr, Sunrise, Maghrib and Isha are null. Dhuhr is always given; Asr is
 *   null when the sun stays below the horizon at noon. Nothing throws.
 * - Times are rounded to the nearest minute. The day is the place's local date in [Place.zoneId].
 */
object PrayerTimes {
    private const val RAD = Math.PI / 180.0
    private const val SUNRISE_ALTITUDE = -0.833

    /** [prayer] on local [date] at [place]; null when it does not occur (polar dates). */
    fun time(prayer: Prayer, date: LocalDate, place: Place, method: Method, asr: AsrRule = AsrRule.STANDARD): Instant? {
        val zone = zoneOf(place)
        val day = Day.of(date, place, zone)
        val raw: Double? = when (prayer) {
            Prayer.DHUHR -> day.transit + method.dhuhrMinutes / 60.0
            Prayer.ASR -> day.asr(asr.shadow)
            Prayer.SUNRISE -> if (day.sunset() == null) null else day.sunrise()
            Prayer.MAGHRIB -> maghrib(day, method)?.let { it + method.maghribMinutes / 60.0 }
            Prayer.FAJR -> fajr(day, Day.of(date.minusDays(1), place, zone), method)
            Prayer.ISHA -> isha(day, Day.of(date.plusDays(1), place, zone), date, method)
        }
        return raw?.let { day.instant(it) }
    }

    /**
     * The first time [prayer] + [offsetMinutes] that is strictly after [after], searching the place's
     * days from just before [after] for a little over a year; null if the prayer never occurs.
     */
    fun next(prayer: Prayer, offsetMinutes: Int, after: Instant, place: Place, method: Method, asr: AsrRule = AsrRule.STANDARD): Instant? {
        val zone = zoneOf(place)
        val back = 2L + ceil(abs(offsetMinutes) / 1440.0).toLong()
        var date = after.atZone(zone).toLocalDate().minusDays(back)
        repeat(370 + back.toInt()) {
            val at = time(prayer, date, place, method, asr)?.plusSeconds(offsetMinutes * 60L)
            if (at != null && at.isAfter(after)) return at
            date = date.plusDays(1)
        }
        return null
    }

    private fun zoneOf(place: Place): ZoneId = ZoneId.of(place.zoneId)

    private fun portion(lat: Double): Double = if (abs(lat) > 48.0) 1.0 / 7.0 else 0.5

    private fun maghrib(day: Day, method: Method): Double? {
        val sunset = day.sunset() ?: return null
        if (day.sunrise() == null) return null
        if (method.maghrib <= 0.0) return sunset
        return day.altitudeTime(-method.maghrib, morning = false) ?: sunset
    }

    private fun fajr(day: Day, previous: Day, method: Method): Double? {
        val sunrise = day.sunrise() ?: return null
        if (day.sunset() == null) return null
        val lastSunset = previous.sunset() ?: return null
        // Previous day's sunset on this day's clock.
        val night = sunrise - (lastSunset + previous.offsetHoursTo(day))
        val safe = sunrise - portion(day.lat) * night
        val angle = day.altitudeTime(-method.fajr, morning = true)
        return if (angle == null || angle < safe) safe else angle
    }

    private fun isha(day: Day, nextDay: Day, date: LocalDate, method: Method): Double? {
        val sunset = day.sunset() ?: return null
        if (day.sunrise() == null) return null
        if (method.ishaMinutes > 0) {
            val ramadan = Hijri.of(date).month == 9
            val minutes = if (ramadan) method.ramadanIshaMinutes else method.ishaMinutes
            val maghrib = maghrib(day, method) ?: return null
            return maghrib + method.maghribMinutes / 60.0 + minutes / 60.0
        }
        val nextSunrise = nextDay.sunrise() ?: return null
        val night = nextSunrise + nextDay.offsetHoursTo(day) - sunset
        val safe = sunset + portion(day.lat) * night
        val angle = day.altitudeTime(-method.isha, morning = false)
        return if (angle == null || angle > safe) safe else angle
    }

    /** Solar position for Julian day [jd]: declination (degrees) and equation of time (hours). */
    private class Sun(val decl: Double, val eqt: Double)

    private fun sun(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * sin(g * RAD) + 0.020 * sin(2 * g * RAD), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = fix(atan2(cos(e * RAD) * sin(l * RAD), cos(l * RAD)) / RAD / 15.0, 24.0)
        var eqt = q / 15.0 - ra
        eqt -= 24.0 * Math.round(eqt / 24.0)
        return Sun(asin(sin(e * RAD) * sin(l * RAD)) / RAD, eqt)
    }

    private fun fix(a: Double, b: Double): Double {
        val r = a - b * Math.floor(a / b)
        return if (r < 0) r + b else r
    }

    /**
     * One solar day: times are hours after 00:00 UT of [utcDate], the UT date whose transit is
     * nearest to local noon of the requested local date.
     */
    private class Day(val utcDate: LocalDate, val lat: Double, val lon: Double) {
        private val jd0 = utcDate.toEpochDay() + 2440587.5
        val transit: Double = run {
            var t = 12.0 - lon / 15.0
            repeat(3) { t = 12.0 - lon / 15.0 - sun(jd0 + t / 24.0).eqt }
            t
        }
        private var sunriseCache: Any? = UNSET
        private var sunsetCache: Any? = UNSET

        fun sunrise(): Double? {
            if (sunriseCache === UNSET) sunriseCache = altitudeTime(SUNRISE_ALTITUDE, morning = true)
            return sunriseCache as Double?
        }

        fun sunset(): Double? {
            if (sunsetCache === UNSET) sunsetCache = altitudeTime(SUNRISE_ALTITUDE, morning = false)
            return sunsetCache as Double?
        }

        /** When the sun is at [altitude] degrees before (morning) or after transit; null if never. */
        fun altitudeTime(altitude: Double, morning: Boolean): Double? {
            var t = transit + if (morning) -6.0 else 6.0
            repeat(5) {
                val s = sun(jd0 + t / 24.0)
                val cosH = (sin(altitude * RAD) - sin(lat * RAD) * sin(s.decl * RAD)) / (cos(lat * RAD) * cos(s.decl * RAD))
                if (cosH.isNaN() || cosH < -1.0 || cosH > 1.0) return null
                val h = acos(cosH) / RAD / 15.0
                val noon = 12.0 - lon / 15.0 - s.eqt
                t = if (morning) noon - h else noon + h
            }
            return t
        }

        /** Asr for a shadow factor; null when the sun is not above the horizon at noon. */
        fun asr(shadow: Double): Double? {
            val decl = sun(jd0 + transit / 24.0).decl
            val zenith = abs(lat - decl)
            if (zenith >= 90.0) return null
            val altitude = atan(1.0 / (shadow + tan(zenith * RAD))) / RAD
            return altitudeTime(altitude, morning = false)
        }

        /** Hours to add to this day's times to read them on [other]'s clock. */
        fun offsetHoursTo(other: Day): Double = (utcDate.toEpochDay() - other.utcDate.toEpochDay()) * 24.0

        fun instant(hours: Double): Instant {
            val seconds = utcDate.toEpochDay() * 86_400L + Math.round(hours * 3600.0)
            return Instant.ofEpochSecond(Math.floorDiv(seconds + 30, 60L) * 60L)
        }

        companion object {
            private val UNSET = Any()

            fun of(date: LocalDate, place: Place, zone: ZoneId): Day {
                val noon = ZonedDateTime.of(date, LocalTime.NOON, zone).toEpochSecond()
                var best: Day? = null
                var bestGap = Long.MAX_VALUE
                for (shift in -1L..1L) {
                    val candidate = Day(date.plusDays(shift), place.lat, place.lon)
                    val at = candidate.utcDate.toEpochDay() * 86_400L + Math.round(candidate.transit * 3600.0)
                    val gap = abs(at - noon)
                    if (gap < bestGap) {
                        best = candidate
                        bestGap = gap
                    }
                }
                return best!!
            }
        }
    }
}
