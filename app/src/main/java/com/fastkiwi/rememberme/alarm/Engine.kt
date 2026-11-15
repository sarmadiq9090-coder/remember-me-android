package com.fastkiwi.rememberme.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Planner
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.WakeKind
import com.fastkiwi.rememberme.data.ScheduleLog
import com.fastkiwi.rememberme.widget.ReminderWidget
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Carries out a [com.fastkiwi.rememberme.core.Plan]: posts and clears notifications and sets the one
 * alarm for the next moment (D-004). It runs after every data change, on every app start, when the
 * alarm fires, after a reboot, an app update, a clock or time-zone change, and when the exact-alarm
 * permission changes. Each run is complete on its own, so a missed or wiped alarm only delays.
 */
object Engine {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rememberme-engine").apply { isDaemon = true } }

    /** Run in the nightly housekeeping window, after the alarms are armed (registered at app start). */
    val housekeeping = java.util.concurrent.CopyOnWriteArrayList<(Context, Instant) -> Unit>()

    /** Last time the engine ran (for tests and diagnostics). */
    @Volatile var lastRun: Instant? = null
        private set
    @Volatile var exact: Boolean = true
        private set

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** The alarm API chosen for a kind of wake-up (D-020): the strongest one only where a reminder is due. */
    enum class Api { CLOCK, EXACT, INEXACT, NONE }

    fun apiFor(kind: WakeKind?, exactAllowed: Boolean): Api = when {
        kind == null -> Api.NONE
        !exactAllowed -> Api.INEXACT
        kind == WakeKind.DUE -> Api.CLOCK
        else -> Api.EXACT
    }

    /**
     * [at]: the moment to plan for (tests); by default the time when the run gets the lock, not when it
     * asked for it. [reason] goes to the local schedule log (no reminder content).
     */
    fun sync(context: Context, at: Instant? = null, reason: String = "change") = synchronized(lock) {
        val now = at ?: Instant.now()
        val app = context.applicationContext
        // A process that started before the unlock finishes its start on the first run after it.
        com.fastkiwi.rememberme.RememberMeApp.start(app)
        val prefs = Repo.prefs(app)
        // Woken by our own alarm: how late after the armed moment (measured, for Health and the report).
        val armed = prefs.armedAt
        val lateMs = if (reason == "alarm" && armed > 0 && now.toEpochMilli() >= armed) now.toEpochMilli() - armed else -1L
        // The 30-day trash purge, done quietly on the way.
        val store = Repo.store(app)
        if (store.state.value.tasks.any { it.deletedAt != null && it.deletedAt.isBefore(now.minusMillis(Tasks.TRASH_DAYS * 86_400_000L)) }) {
            // A write that fails (disk full) must not stop the run before the alarm is set.
            runCatching { store.change { Tasks.purgeTrash(it, now) } }
        }
        val state = store.state.value
        val plan = Planner.plan(state, now, Repo.remindAgain(state), prefs.alertLog, prefs.noticeLog)

        val shown = runCatching { Notifier.shownDue(app) }.getOrDefault(emptySet())
        val dueIds = plan.due.map { it.id }.toSet()
        // Newest first among the shown ones, the rest are counted in the summary and listed in the app.
        val visible = plan.due.takeLast(Notifier.MAX_SHOWN)
        // Rung already before the unlock (F-026): shown now, quietly; the locked placeholder goes.
        val rungLocked = runCatching { DirectBoot.rungWhileLocked(app) }.getOrDefault(emptySet())
        if (rungLocked.isNotEmpty()) { DirectBoot.clearRung(app); DirectBoot.cancelLocked(app) }
        // A swiped reminder comes back quietly only while pinned, at most 5 times per occurrence (F-021).
        val reposts = prefs.repostLog
        for (t in visible) {
            val loud = t.id in plan.loud && (t.dueAt?.toEpochMilli() ?: 0) !in rungLocked
            val key = repostKey(t)
            val swiped = reposts[key]?.toIntOrNull()
            val back = swiped == null || (Notifier.decide(app, t).pinned && swiped <= com.fastkiwi.rememberme.core.AlertPolicy.MAX_REPOSTS)
            if (loud || (t.id !in shown && back)) {
                Notifier.postDue(app, t, loud, now)
                if (!loud && swiped != null) runCatching { com.fastkiwi.rememberme.data.AlertLog(app).add(com.fastkiwi.rememberme.data.AlertLog.Event.REPOSTED, "", code = "$swiped") }
            }
        }
        val dueKeys = plan.due.map(::repostKey).toSet()
        if (reposts.keys.any { it !in dueKeys }) prefs.repostLog = reposts.filterKeys { it in dueKeys }
        for (id in shown) if (id !in dueIds || visible.none { it.id == id }) Notifier.cancelDue(app, id)
        // Posted with «لديك تذكير» while locked (F-022): the details come back once the phone is unlocked.
        if (!Notifier.isLocked(app)) {
            val generic = runCatching { Notifier.shownGeneric(app) }.getOrDefault(emptySet())
            for (t in visible) if (t.id in generic && t.id !in plan.loud) Notifier.postDue(app, t, false, now)
        }
        Notifier.postSummary(app, plan.due.size)
        for (t in plan.notices) Notifier.postNotice(app, t, now)
        // A notice whose task is done, deleted or due is no longer useful. Only the ones showing are
        // cancelled (one system call each, not one per reminder).
        val notices = runCatching { Notifier.shownNotices(app) }.getOrDefault(emptySet())
        if (notices.isNotEmpty()) state.tasks.filter { t ->
            t.id in notices && (!t.active || t.id in dueIds || Tasks.noticeKey(t, now) == null)
        }.forEach { runCatching { Notifier.cancelNotice(app, it.id) } }

        // While notifications cannot be shown (switched off, permission withdrawn) nothing has been
        // announced: the loud occurrences keep their old entry, so they ring when notifications return.
        prefs.alertLog = if (Notifier.canPost(app)) plan.alertLog else {
            val old = prefs.alertLog
            plan.alertLog.toMutableMap().apply { for (id in plan.loud) { val o = old[id]; if (o != null) put(id, o) else remove(id) } }
        }
        prefs.noticeLog = plan.noticeLog
        // Two slots (D-079): A for due times, B for early notices and "sound again"; B never delays A.
        val apiA = arm(app, SLOT_A, plan.dueAt, if (plan.dueAt != null) WakeKind.DUE else null)
        val apiB = arm(app, SLOT_B, plan.otherAt, plan.otherKind)
        armHousekeeping(app, now)
        runCatching { Summary.arm(app, now) }
        val verified = isArmed(app, SLOT_A, plan.dueAt, apiA) && isArmed(app, SLOT_B, plan.otherAt, apiB)
        runCatching {
            ScheduleLog(app).add(ScheduleLog.Entry(now.toEpochMilli(), reason, plan.dueAt?.toEpochMilli() ?: 0,
                if (plan.dueAt != null) WakeKind.DUE.name else "", apiA.name, plan.due.size, plan.scheduled, verified,
                plan.otherAt?.toEpochMilli() ?: 0, apiB.name, lateMs))
        }
        // Only times and the style, for a restart before the unlock (F-026).
        runCatching {
            DirectBoot.remember(app, state.tasks.filter { it.active && it.dueAt != null && it.dueAt.isAfter(now) }
                .map { it.dueAt!!.toEpochMilli() to (com.fastkiwi.rememberme.core.AlertPolicy.style(it) == com.fastkiwi.rememberme.core.AlertPolicy.Style.ALARM) },
                prefs.language)
        }
        lastRun = now
        runCatching { ReminderWidget.refresh(app) }
        // Work that belongs to the nightly window (e.g. the weekly automatic backup, F-087). After the
        // alarms are armed, so a slow or failing hook can never delay a reminder.
        if (reason == "housekeeping") for (hook in housekeeping) runCatching { hook(app, now) }
        plan
    }

    /** Runs [sync] off the main thread; for receivers it keeps the broadcast alive until done. */
    fun syncAsync(context: Context, pending: BroadcastReceiver.PendingResult? = null, reason: String = "change") {
        val app = context.applicationContext
        worker.execute {
            try { sync(app, reason = reason) } finally { pending?.finish() }
        }
    }

    /**
     * Whether the system holds a slot's alarm: its PendingIntent exists, and for an alarm clock the
     * system's next alarm clock is at or before ours.
     */
    fun isArmed(context: Context, slot: Int, at: Instant?, api: Api): Boolean {
        if (at == null || api == Api.NONE) return true
        PendingIntent.getBroadcast(context, slot, intent(context, slot), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return false
        if (api != Api.CLOCK) return true
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return false
        return next.triggerTime <= at.toEpochMilli()
    }

    /** The occurrence a swipe count belongs to. */
    fun repostKey(t: com.fastkiwi.rememberme.core.Task) = t.id + "|" + (t.dueAt?.toEpochMilli() ?: 0)

    /** Waits until queued background runs have finished (tests). */
    fun drain() { worker.submit {}.get() }

    /** Slot A (due times), slot B (notices, "sound again"), and the midnight housekeeping window. */
    const val SLOT_A = 100
    const val SLOT_B = 103
    const val HOUSEKEEPING = 102

    private fun intent(context: Context, slot: Int) = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.FIRE)
        .setData(android.net.Uri.parse("rememberme://slot/$slot"))

    private fun operation(context: Context, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(context, slot, intent(context, slot), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Sets (or clears) one slot's alarm with the API for its kind of moment. Re-arming replaces it, never adds. */
    private fun arm(context: Context, slot: Int, at: Instant?, kind: WakeKind?): Api {
        val am = context.getSystemService(AlarmManager::class.java)
        val op = operation(context, slot)
        if (at == null) {
            am.cancel(op)
            op.cancel()
            if (slot == SLOT_A) Repo.prefs(context).armedAt = 0
            return Api.NONE
        }
        exact = canExact(context)
        var api = apiFor(kind, exact)
        val t = at.toEpochMilli()
        try {
            when (api) {
                // An alarm clock is exempt from Doze and app standby; Android shows it as the next alarm.
                Api.CLOCK -> {
                    val show = PendingIntent.getActivity(context, 101, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), op)
                }
                // Exact and allowed in Doze, without the alarm icon: early notices and "sound again".
                Api.EXACT -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op)
            }
        } catch (_: SecurityException) {
            exact = false
            api = Api.INEXACT
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op)
        }
        if (slot == SLOT_A) Repo.prefs(context).armedAt = t
        return api
    }

    /** The trash purge and the widget's day change run near local midnight, without waking the phone. */
    private fun armHousekeeping(context: Context, now: Instant) {
        val zone = Repo.zone()
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        runCatching {
            context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, midnight, 3_600_000L, operation(context, HOUSEKEEPING))
        }
    }
}
