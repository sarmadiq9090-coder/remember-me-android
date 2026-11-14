package com.fastkiwi.rememberme.data

import android.content.Context
import androidx.core.content.edit

/**
 * A short local log of the alarm engine's runs (PRD P0.2): when it ran, why, which alarm it set and
 * whether the system holds it. It never stores a reminder's title, note or id, and it never leaves the
 * phone except inside a compatibility report the user chooses to share.
 */
class ScheduleLog(context: Context) {
    private val p = context.getSharedPreferences("schedule_log", Context.MODE_PRIVATE)

    data class Entry(
        val at: Long,
        /** start, open, alarm, boot, update, time, zone, permission, action, change, check, test */
        val reason: String,
        /** Epoch ms of the alarm set, or 0 for none. */
        val wakeAt: Long,
        /** DUE, NOTICE, AGAIN or "" when nothing is ahead. */
        val kind: String,
        /** CLOCK (setAlarmClock), EXACT (setExactAndAllowWhileIdle), INEXACT (setAndAllowWhileIdle) or NONE. */
        val api: String,
        val due: Int,
        val scheduled: Int,
        /** The system was found to hold the alarms right after they were set. */
        val verified: Boolean,
        /** Slot B (early notice or "sound again"): epoch ms or 0, and its API. */
        val otherAt: Long = 0,
        val otherApi: String = "NONE",
        /** For a run woken by our alarm: how late it ran after the armed time, in ms; -1 otherwise. */
        val lateMs: Long = -1,
    ) {
        fun encode() = listOf(at, reason, wakeAt, kind, api, due, scheduled, if (verified) 1 else 0, otherAt, otherApi, lateMs).joinToString("|")
        companion object {
            fun decode(s: String): Entry? {
                val f = s.split('|')
                if (f.size !in setOf(8, 10, 11)) return null
                return runCatching {
                    Entry(f[0].toLong(), f[1], f[2].toLong(), f[3], f[4], f[5].toInt(), f[6].toInt(), f[7] == "1",
                        f.getOrNull(8)?.toLong() ?: 0, f.getOrNull(9) ?: "NONE", f.getOrNull(10)?.toLong() ?: -1)
                }.getOrNull()
            }
        }
    }

    fun add(e: Entry) = synchronized(LOCK) {
        val lines = (listOf(e.encode()) + (p.getString(KEY, "") ?: "").lines().filter { it.isNotBlank() }).take(MAX)
        p.edit { putString(KEY, lines.joinToString("\n")) }
    }

    /** Newest first. */
    fun entries(): List<Entry> = (p.getString(KEY, "") ?: "").lines().mapNotNull { Entry.decode(it) }

    fun last(): Entry? = entries().firstOrNull()

    /** The last run caused by a reboot, if any is still in the log. */
    fun lastBoot(): Entry? = entries().firstOrNull { it.reason == "boot" }

    /** Lateness of the alarm-woken runs still in the log, in ms. */
    fun lateness(): List<Long> = entries().filter { it.reason == "alarm" && it.lateMs >= 0 }.map { it.lateMs }

    fun clear() = p.edit { remove(KEY) }

    companion object {
        const val MAX = 200
        private const val KEY = "entries"
        private val LOCK = Any()
    }
}
