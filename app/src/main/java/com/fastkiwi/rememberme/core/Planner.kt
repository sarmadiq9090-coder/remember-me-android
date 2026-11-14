package com.fastkiwi.rememberme.core

import java.time.Instant

/**
 * What the alarm engine must do at a moment: a pure function of the data, so it is fully unit-tested
 * and the receiver only carries it out. Every run handles every reminder that is due, not only the
 * one that woke it, so a wiped or late alarm never loses one.
 */
data class Plan(
    /** All due reminders, oldest first (the alert queue). */
    val due: List<Task>,
    /** The ones to announce with sound now: newly due, or a "remind again" moment has come. */
    val loud: Set<String>,
    /** Early notices to show now. */
    val notices: List<Task>,
    /** The next moment the engine must run, or null when nothing is ahead. */
    val wakeAt: Instant?,
    val alertLog: Map<String, String>,
    val noticeLog: Map<String, String>,
)

object Planner {
    /** Repeated alert sounds stop after this many, as on Windows (createRepeatBeep). */
    const val MAX_AGAIN = 6
    val REMIND_AGAIN = listOf(0, 2, 5, 15)

    fun plan(state: AppState, now: Instant, remindAgainMinutes: Int, alertLog: Map<String, String>, noticeLog: Map<String, String>): Plan {
        val again = remindAgainMinutes.takeIf { it in REMIND_AGAIN && it > 0 }?.let { it * 60_000L }
        val due = Tasks.due(state, now)
        val loud = HashSet<String>()
        val newAlertLog = HashMap<String, String>()
        var wake: Instant? = null
        fun consider(at: Instant) { if (at.isAfter(now) && (wake == null || at.isBefore(wake))) wake = at }

        for (t in due) {
            val dueAt = t.dueAt!!
            val slot = if (again == null) 0L else minOf(MAX_AGAIN.toLong(), (now.toEpochMilli() - dueAt.toEpochMilli()) / again)
            val iso = Codec.iso(dueAt)
            val old = alertLog[t.id]
            val oldSlot = old?.takeIf { it.substringBeforeLast('|') == iso }?.let(::slotOf)
            // Loud when this occurrence has not sounded yet, or a later "remind again" slot has come.
            if (oldSlot == null || slot > oldSlot) loud += t.id
            newAlertLog[t.id] = iso + "|" + maxOf(slot, oldSlot ?: 0L)
            if (again != null && slot < MAX_AGAIN) consider(dueAt.plusMillis((slot + 1) * again))
        }

        val notices = Tasks.upcoming(state, now).filter { noticeLog[it.id] != Codec.iso(it.dueAt!!) }
        val newNoticeLog = HashMap<String, String>()
        for (t in state.tasks) {
            if (!t.active || t.dueAt == null) continue
            val iso = Codec.iso(t.dueAt)
            if (noticeLog[t.id] == iso || notices.any { it.id == t.id }) newNoticeLog[t.id] = iso
            if (t.dueAt.isAfter(now)) consider(t.dueAt)
            Tasks.noticeAt(t)?.let { at -> if (at.isAfter(t.updatedAt)) consider(at) }
        }
        return Plan(due, loud, notices, wake, newAlertLog, newNoticeLog)
    }

    private fun slotOf(key: String) = key.substringAfterLast('|').toLongOrNull() ?: 0L
}
