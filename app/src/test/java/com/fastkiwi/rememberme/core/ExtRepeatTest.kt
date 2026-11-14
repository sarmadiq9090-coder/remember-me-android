package com.fastkiwi.rememberme.core

import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.core.prayer.Method
import com.fastkiwi.rememberme.core.prayer.Places
import com.fastkiwi.rememberme.core.prayer.Prayer
import com.fastkiwi.rememberme.core.prayer.PrayerTimes
import com.fastkiwi.rememberme.data.EditorPrefs
import com.fastkiwi.rememberme.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId

/**
 * Completing or skipping a prayer-relative (F-050) or Hijri-yearly (F-051) reminder computes the next occurrence
 * from the prayer or the Hijri date, with the phone's setup installed as the app does (EditorPrefs.install).
 * Robolectric, because Hijri uses Android's ICU calendar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExtRepeatTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val app: android.content.Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        T.ids()
        app.getSharedPreferences("device", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun reset() { Tasks.extRepeat = { ExtRepeat() } }

    @Test fun aPrayerTaskMovesToTheNextPrayerTimeOfTheChosenCity() {
        Prefs(app).prayerCity = "Baghdad"
        Prefs(app).prayerMethod = Method.MWL.name
        EditorPrefs.install(app)
        val baghdad = Places.byEnglishName("Baghdad")!!
        val now = at(zone, 2026, 11, 14, 10)
        val first = PrayerTimes.next(Prayer.MAGHRIB, 10, now, baghdad, Method.MWL)!!
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "بعد المغرب", dueAt = first, repeat = Repeat.DAILY, ext = AlertExt(prayer = "maghrib" to 10)), now, zone)
        val (s1, _) = Tasks.complete(s, t.id, first.plusSeconds(60), zone)!!
        val next = s1.task(t.id)!!
        val expected = PrayerTimes.next(Prayer.MAGHRIB, 10, first, baghdad, Method.MWL)!!
        assertEquals(expected, next.dueAt)
        assertEquals(expected, next.scheduleAt)
        assertEquals(expected, next.rule.from)
        // It follows the sun, not the clock: the next day's time differs from a fixed daily repeat by the sunset change.
        assertTrue(next.dueAt!!.isAfter(first.plusSeconds(23 * 3600L)))
        // Rescheduling (skip) also follows the prayer.
        val skipped = Tasks.skip(s, t.id, now, zone)!!.task(t.id)!!
        assertEquals(expected, skipped.dueAt)
    }

    @Test fun withoutACityAPrayerTaskMovesLikeADailyOne() {
        EditorPrefs.install(app)
        val now = at(zone, 2026, 11, 14, 10)
        val due = at(zone, 2026, 11, 14, 17, 5)
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "r", dueAt = due, repeat = Repeat.DAILY, ext = AlertExt(prayer = "maghrib" to 10)), now, zone)
        assertEquals(due.plusSeconds(86_400), Tasks.complete(s, t.id, due.plusSeconds(60), zone)!!.first.task(t.id)!!.dueAt)
    }

    @Test fun aHijriYearlyTaskMovesToTheNextHijriDate() {
        EditorPrefs.install(app)
        val now = at(zone, 2026, 11, 14, 10)
        // 1 Ramadan, 9:00 this Hijri year.
        val first = Hijri.nextYearly(9, 1, LocalTime.of(9, 0), now, zone)
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "رمضان", dueAt = first, repeat = Repeat.YEARLY, ext = AlertExt(hijri = 9 to 1)), now, zone)
        val moved = Tasks.complete(s, t.id, first.plusSeconds(60), zone)!!.first.task(t.id)!!
        val h = Hijri.of(moved.dueAt!!.atZone(zone).toLocalDate())
        assertEquals(9, h.month); assertEquals(1, h.day)
        assertEquals(Hijri.of(first.atZone(zone).toLocalDate()).year + 1, h.year)
        assertEquals(LocalTime.of(9, 0), moved.dueAt!!.atZone(zone).toLocalTime())
        // About 354 days later, not a Gregorian year.
        val days = (moved.dueAt!!.epochSecond - first.epochSecond) / 86_400
        assertTrue("$days", days in 353..356)
    }

    @Test fun day30InA29DayMonthFallsOnDay29() {
        EditorPrefs.install(app)
        val now = at(zone, 2026, 11, 14, 10)
        // Find a Hijri year whose Shaban (8) has 29 days, starting from this one.
        var year = Hijri.of(now.atZone(zone).toLocalDate()).year + 1
        while (Hijri.toGregorian(HijriDate(year, 8, 30)) != Hijri.toGregorian(HijriDate(year, 8, 29))) year++
        val before = Hijri.toGregorian(HijriDate(year, 8, 1)).atStartOfDay(zone).toInstant()
        val at = Hijri.nextYearly(8, 30, LocalTime.of(20, 0), before, zone)
        val h = Hijri.of(at.atZone(zone).toLocalDate())
        assertEquals(8, h.month); assertEquals(29, h.day)
        // Through Tasks: a day-30 task whose next year is short lands on the 29th.
        val anchor = Hijri.toGregorian(HijriDate(year - 1, 8, 1)).atStartOfDay(zone).toInstant()
        val due = Hijri.nextYearly(8, 30, LocalTime.of(20, 0), anchor, zone)
        if (Hijri.of(due.atZone(zone).toLocalDate()).year == year - 1) {
            val (s, t) = Tasks.save(AppState(), TaskInput(title = "r", dueAt = due, repeat = Repeat.YEARLY, ext = AlertExt(hijri = 8 to 30)), anchor, zone)
            val next = Tasks.complete(s, t.id, due.plusSeconds(60), zone)!!.first.task(t.id)!!
            assertEquals(at, next.dueAt)
        }
    }
}
