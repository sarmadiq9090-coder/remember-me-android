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
    /** What the next wake is for: a due time, an early notice or a "sound again" moment (D-020). */
    val wakeKind: WakeKind? = null,
    /** Active reminders with a time still ahead (shown in the health centre). */
    val scheduled: Int = 0,
    /** Slot A: the next due time (setAlarmClock). Never depends on slot B (D-079). */
    val dueAt: Instant? = null,
    /** Slot B: the next early notice or "sound again" moment (setExactAndAllowWhileIdle). */
    val otherAt: Instant? = null,
    /** What slot B is for: NOTICE or AGAIN. */
    val otherKind: WakeKind? = null,
)

/** Why the engine must run next; the alarm API is chosen from it (D-020). */
enum class WakeKind { DUE, NOTICE, AGAIN }

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
        var kind: WakeKind? = null
        var slotA: Instant? = null
        var slotB: Instant? = null
        var slotBKind: WakeKind? = null
        // The earliest moment wins; at the same moment a due time outranks a notice or a re-alert.
        fun consider(at: Instant, k: WakeKind) {
            if (!at.isAfter(now)) return
            if (k == WakeKind.DUE) { if (slotA == null || at.isBefore(slotA)) slotA = at }
            else if (slotB == null || at.isBefore(slotB) || (at == slotB && k.ordinal < slotBKind!!.ordinal)) { slotB = at; slotBKind = k }
            val w = wake
            if (w == null || at.isBefore(w) || (at == w && k.ordinal < kind!!.ordinal)) { wake = at; kind = k }
        }

        for (t in due) {
            val dueAt = t.dueAt!!
            val slot = if (again == null) 0L else minOf(MAX_AGAIN.toLong(), (now.toEpochMilli() - dueAt.toEpochMilli()) / again)
            val iso = Codec.iso(dueAt)
            val old = alertLog[t.id]
            val oldSlot = old?.takeIf { it.substringBeforeLast('|') == iso }?.let(::slotOf)
            // Loud when this occurrence has not sounded yet, or a later "remind again" slot has come.
            if (oldSlot == null || slot > oldSlot) loud += t.id
            newAlertLog[t.id] = iso + "|" + maxOf(slot, oldSlot ?: 0L)
            if (again != null && slot < MAX_AGAIN) consider(dueAt.plusMillis((slot + 1) * again), WakeKind.AGAIN)
        }

        // Several notices per reminder (F-046): each has its own key, so each is shown once.
        // An announced occurrence stays remembered for 2 days while its task exists, even when it is no
        // longer due (done, trashed, moved on), so «تراجع» or a restore never sounds it a second time.
        val keepAfter = now.minusSeconds(2 * 86_400)
        for ((id, entry) in alertLog) {
            if (id in newAlertLog || state.tasks.none { it.id == id }) continue
            val at = runCatching { Instant.parse(entry.substringBeforeLast('|')) }.getOrNull() ?: continue
            if (at.isAfter(keepAfter)) newAlertLog[id] = entry
        }
        val notices = Tasks.upcoming(state, now).filter { noticeLog[it.id] != Tasks.noticeKey(it, now) }
        val newNoticeLog = HashMap<String, String>()
        var scheduled = 0
        for (t in state.tasks) {
            if (!t.active || t.dueAt == null) continue
            val key = Tasks.noticeKey(t, now)
            val logged = noticeLog[t.id]
            if (key != null) newNoticeLog[t.id] = key
            // Kept while it still belongs to this occurrence (a later notice of it replaces it).
            else if (logged != null && logged.substringBefore('#') == Codec.iso(t.dueAt)) newNoticeLog[t.id] = logged
            if (t.dueAt.isAfter(now)) { consider(t.dueAt, WakeKind.DUE); scheduled++ }
            for (at in Tasks.noticeTimes(t)) if (at.isAfter(t.updatedAt)) consider(at, WakeKind.NOTICE)
        }
        return Plan(due, loud, notices, wake, newAlertLog, newNoticeLog, kind, scheduled, slotA, slotB, slotBKind)
    }

    private fun slotOf(key: String) = key.substringAfterLast('|').toLongOrNull() ?: 0L
}
