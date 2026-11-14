package com.fastkiwi.rememberme.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** Repeat modes. The first seven are the Windows app's; HOURS is an Android-only extension (D-004). */
enum class Repeat(val code: String) {
    NONE("none"), DAILY("daily"), WEEKLY("weekly"), DAYS("days"), INTERVAL("interval"),
    MONTHLY("monthly"), YEARLY("yearly"), HOURS("hours");

    companion object {
        fun of(code: String?): Repeat = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** The rule fields a repeat uses (the Windows data.json names). */
data class RepeatRule(
    /** Weekdays for DAYS, 0 = Sunday … 6 = Saturday (JavaScript's getDay()). */
    val days: List<Int> = emptyList(),
    /** Every N days for INTERVAL (2..365). */
    val everyDays: Int = 2,
    /** Every N hours for HOURS (1..23). */
    val everyHours: Int = 1,
    /** The series anchor: time of day for every mode, and where MONTHLY/YEARLY count from. */
    val from: Instant? = null,
)

/** JavaScript's getDay(): 0 = Sunday. */
fun jsDay(date: LocalDate): Int = date.dayOfWeek.value % 7

/**
 * When a repeating task comes back: the first occurrence after both [current] and [now], in local
 * time ([zone]). A faithful port of nextOccurrence() in the Windows app's src/core/repeat.js, so both
 * apps agree on every date. Each occurrence is rebuilt from a calendar day plus the series' time of
 * day, so the wall-clock time survives DST changes: an occurrence in a spring-forward gap fires an
 * hour later and the next one is back on time.
 */
fun nextOccurrence(current: Instant, repeat: Repeat, now: Instant, rule: RepeatRule, zone: ZoneId): Instant {
    val cur = current.atZone(zone)
    val nowZ = now.atZone(zone)
    when (repeat) {
        Repeat.MONTHLY, Repeat.YEARLY -> {
            val start = (rule.from ?: current).atZone(zone)
            val step = if (repeat == Repeat.YEARLY) 12 else 1
            val elapsed = (cur.year - start.year) * 12 + cur.monthValue - start.monthValue
            val behind = (nowZ.year - start.year) * 12 + nowZ.monthValue - start.monthValue
            var months = maxOf(Math.floorDiv(elapsed, step) * step + step, Math.floorDiv(behind - 1, step) * step)
            var next = monthsLater(start, months, zone)
            while (!next.isAfter(now) || !next.isAfter(current)) {
                months += step
                next = monthsLater(start, months, zone)
            }
            return next
        }
        Repeat.HOURS -> {
            val step = Duration.ofHours(rule.everyHours.coerceIn(1, 23).toLong())
            val gap = Duration.between(current, now)
            var n = if (gap.isNegative) 1L else gap.toMillis() / step.toMillis() + 1
            var next = current.plus(step.multipliedBy(n))
            while (!next.isAfter(now) || !next.isAfter(current)) next = current.plus(step.multipliedBy(++n))
            return next
        }
        Repeat.NONE -> return current
        else -> Unit
    }
    val time = (rule.from ?: current).atZone(zone).toLocalTime()
    fun on(from: LocalDate, days: Long): Instant = localToInstant(LocalDateTime.of(from.plusDays(days), time), zone)
    if (repeat == Repeat.DAYS) {
        val days = rule.days.ifEmpty { listOf(jsDay(cur.toLocalDate())) }
        // After a long gap, restart from yesterday instead of walking every day.
        val from = if (now.toEpochMilli() - current.toEpochMilli() > 8 * DAY_MS) nowZ.toLocalDate().minusDays(1) else cur.toLocalDate()
        var offset = 0L
        var next: Instant
        do {
            offset++
            next = on(from, offset)
        } while (jsDay(from.plusDays(offset)) !in days || !next.isAfter(now) || !next.isAfter(current))
        return next
    }
    val step = when (repeat) {
        Repeat.WEEKLY -> 7L
        Repeat.INTERVAL -> rule.everyDays.coerceIn(2, 365).toLong()
        else -> 1L
    }
    val day = cur.toLocalDate()
    var offset = step
    val behind = Math.floorDiv(now.toEpochMilli() - on(day, offset).toEpochMilli(), DAY_MS)
    if (behind > 0) offset += (behind / step) * step
    var next = on(day, offset)
    while (!next.isAfter(now) || !next.isAfter(current)) {
        offset += step
        next = on(day, offset)
    }
    return next
}

const val DAY_MS = 86_400_000L

/** A local date and time to an instant. In a DST gap the time moves forward (as JavaScript's Date does). */
fun localToInstant(local: LocalDateTime, zone: ZoneId): Instant = ZonedDateTime.of(local, zone).toInstant()

/** [start] plus [months] calendar months at the same local time; the 31st becomes the month's last day. */
private fun monthsLater(start: ZonedDateTime, months: Int, zone: ZoneId): Instant {
    val ym = YearMonth.from(start).plusMonths(months.toLong())
    val day = minOf(start.dayOfMonth, ym.lengthOfMonth())
    return localToInstant(LocalDateTime.of(ym.atDay(day), start.toLocalTime()), zone)
}

/** The same wall-clock time [days] calendar days later (JavaScript's setDate(getDate() + days)). */
fun plusLocalDays(at: Instant, days: Long, zone: ZoneId): Instant =
    localToInstant(at.atZone(zone).toLocalDateTime().plusDays(days), zone)

/** Today (in [zone]) at [time]. */
fun todayAt(now: Instant, time: LocalTime, zone: ZoneId, plusDays: Long = 0): Instant =
    localToInstant(LocalDateTime.of(now.atZone(zone).toLocalDate().plusDays(plusDays), time), zone)
