package com.fastkiwi.rememberme.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Local wall-clock time in [zone] (month 1-12), as the Windows tests' new Date(y, m-1, d, h, mi); days may overflow. */
fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    localToInstant(LocalDateTime.of(y, m, 1, h, mi).plusDays((d - 1).toLong()), zone)

/** "M/D H:MM" as the Windows tests print it. */
fun Instant.md(zone: ZoneId): String {
    val z = atZone(zone)
    return "${z.monthValue}/${z.dayOfMonth} ${z.hour}:" + z.minute.toString().padStart(2, '0')
}

object T {
    private var counter = 0
    fun ids() { Tasks.newId = { "id-${++counter}" } }
}
