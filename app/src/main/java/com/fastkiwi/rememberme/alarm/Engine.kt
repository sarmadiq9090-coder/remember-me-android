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

    /** Last time the engine ran (for tests and diagnostics). */
    @Volatile var lastRun: Instant? = null
        private set
    @Volatile var exact: Boolean = true
        private set

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun sync(context: Context, now: Instant = Instant.now()) = synchronized(lock) {
        val app = context.applicationContext
        val prefs = Repo.prefs(app)
        // The 30-day trash purge, done quietly on the way.
        val store = Repo.store(app)
        if (store.state.value.tasks.any { it.deletedAt != null && it.deletedAt.isBefore(now.minusMillis(Tasks.TRASH_DAYS * 86_400_000L)) }) {
            store.change { Tasks.purgeTrash(it, now) }
        }
        val state = store.state.value
        val plan = Planner.plan(state, now, Repo.remindAgain(state), prefs.alertLog, prefs.noticeLog)

        val shown = runCatching { Notifier.shownDue(app) }.getOrDefault(emptySet())
        val dueIds = plan.due.map { it.id }.toSet()
        // Newest first among the shown ones, the rest are counted in the summary and listed in the app.
        val visible = plan.due.takeLast(Notifier.MAX_SHOWN)
        val sound = Repo.sound(state)
        for (t in visible) {
            val loud = t.id in plan.loud
            if (loud || t.id !in shown) Notifier.postDue(app, t, loud, sound, now)
        }
        for (id in shown) if (id !in dueIds || visible.none { it.id == id }) Notifier.cancelDue(app, id)
        Notifier.postSummary(app, plan.due.size)
        for (t in plan.notices) Notifier.postNotice(app, t, now)
        // A notice whose task is done, deleted or due is no longer useful.
        state.tasks.filter { !it.active || it.id in dueIds }.forEach { runCatching { Notifier.cancelNotice(app, it.id) } }

        prefs.alertLog = plan.alertLog
        prefs.noticeLog = plan.noticeLog
        arm(app, plan.wakeAt)
        lastRun = now
        runCatching { ReminderWidget.refresh(app) }
    }

    /** Runs [sync] off the main thread; for receivers it keeps the broadcast alive until done. */
    fun syncAsync(context: Context, pending: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext
        worker.execute {
            try { sync(app) } finally { pending?.finish() }
        }
    }

    /** Waits until queued background runs have finished (tests). */
    fun drain() { worker.submit {}.get() }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 100, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(context: Context, at: Instant?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val op = operation(context)
        if (at == null) {
            am.cancel(op)
            Repo.prefs(context).armedAt = 0
            return
        }
        exact = canExact(context)
        try {
            if (exact) {
                // An alarm clock is exempt from Doze and app standby; the user sees it as the next alarm.
                val show = PendingIntent.getActivity(context, 101, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), op)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), op)
            }
        } catch (_: SecurityException) {
            exact = false
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), op)
        }
        Repo.prefs(context).armedAt = at.toEpochMilli()
    }
}
