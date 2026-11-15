package com.fastkiwi.rememberme.alarm

import com.fastkiwi.rememberme.core.hidesOnLockScreen
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.quickSnooze
import java.time.Instant
import java.util.concurrent.Executors

/** The engine's own alarm. Not exported. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Direct-boot-aware only for the locked alarm (F-026); nothing else reads storage before the unlock.
        if (intent.action != DirectBoot.ACTION && !DirectBoot.unlocked(context)) return
        when (intent.action) {
            // The midnight window (no wake-up) is housekeeping: trash purge, the widget's day, the weekly backup.
            FIRE -> Engine.syncAsync(context, goAsync(), if (intent.data?.lastPathSegment == Engine.HOUSEKEEPING.toString()) "housekeeping" else "alarm")
            TestAlarm.ACTION -> TestAlarm.onFire(context)
            DirectBoot.ACTION -> DirectBoot.fire(context)
            Summary.ACTION -> { val pending = goAsync(); Thread { try { Summary.fire(context.applicationContext) } finally { pending.finish() } }.start() }
        }
    }
    companion object { const val FIRE = "com.fastkiwi.rememberme.FIRE" }
}

/**
 * Re-arms after a reboot, an app update, a clock or time-zone change, or a change of the exact-alarm
 * permission. Exported only because the system sends these; it reads nothing from the intent.
 */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) Engine.syncAsync(context, goAsync(), reasonOf(intent.action))
    }
    companion object {
        fun reasonOf(action: String?): String = when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            Intent.ACTION_TIME_CHANGED -> "time"
            Intent.ACTION_TIMEZONE_CHANGED -> "zone"
            Intent.ACTION_LOCALE_CHANGED -> "locale"
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> "permission"
            else -> "boot"
        }
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/** «تم», «أجّل» and a swiped-away reminder, from the notification. Not exported. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TEST_DONE) { TestAlarm.actionDone(context); return }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val due = intent.getStringExtra(EXTRA_DUE)?.let { Codec.inRange(runCatching { Instant.parse(it) }.getOrNull()) }
        val action = intent.action ?: return
        // «تم», «أجّل» and a swipe stop the ringing at once (F-016), before the change is saved.
        if (RingService.ringingId == Notifier.dueId(id)) RingService.stop(context)
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                handle(app, action, id, due, Instant.now(), intent.getStringExtra(EXTRA_COPY),
                    intent.getLongExtra(EXTRA_DONE_AT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
            } finally { pending.finish() }
        }
    }

    companion object {
        const val DONE = "com.fastkiwi.rememberme.DONE"
        const val SNOOZE = "com.fastkiwi.rememberme.SNOOZE"
        const val DISMISSED = "com.fastkiwi.rememberme.DISMISSED"
        const val TEST_DONE = "com.fastkiwi.rememberme.TEST_DONE"
        const val UNDO = "com.fastkiwi.rememberme.UNDO"
        const val EXTRA_COPY = "copy"
        const val EXTRA_DONE_AT = "doneAt"
        const val EXTRA_ID = "id"
        const val EXTRA_DUE = "due"
        private val worker = Executors.newSingleThreadExecutor()

        /** The occurrence the user saw ([due]) must still be current, or the tap is ignored. */
        fun handle(context: Context, action: String, id: String, due: Instant?, now: Instant, copy: String? = null, doneAt: Instant? = null) {
            val zone = Repo.zone()
            // A change that cannot be saved (disk full) changes nothing; it must not crash the app.
            try { act(context, action, id, due?.let(Codec::inRange), now, zone, copy, doneAt) }
            catch (_: java.io.IOException) { Engine.sync(context) }
            catch (_: ArithmeticException) { Engine.sync(context) }
        }

        private fun act(context: Context, action: String, id: String, due: Instant?, now: Instant, zone: java.time.ZoneId, intentCopy: String? = null, doneAt: Instant? = null) {
            val log = AlertLog(context)
            val logKey = due?.let { log.keyOf(id, Codec.iso(it)) }.orEmpty()
            when (action) {
                DONE -> {
                    runCatching { log.add(AlertLog.Event.DONE, logKey) }
                    var done: com.fastkiwi.rememberme.core.Completion? = null
                    var title = ""
                    var hidden = Repo.prefs(context).lockPrivacy
                    Repo.change(context) { s ->
                        val r = Tasks.complete(s, id, now, zone, due, checked = true)
                        done = r?.second; title = s.task(id)?.title.orEmpty()
                        s.task(id)?.let { hidden = com.fastkiwi.rememberme.core.AlertExt.of(it).hidesOnLockScreen(Repo.prefs(context).lockPrivacy) }
                        r?.first ?: s
                    }
                    // «أُنجز «…»» with «تراجع» for 10 s (F-052), hidden on the lock screen like the reminder.
                    done?.let { Notifier.postUndo(context, it, title, hidden) }
                }
                UNDO -> {
                    val copy = intentCopy ?: id
                    Repo.change(context) { s -> Tasks.undoComplete(s, com.fastkiwi.rememberme.core.Completion(id, copy, doneAt), now) }
                    Notifier.cancelUndo(context)
                }
                SNOOZE -> {
                    runCatching { log.add(AlertLog.Event.SNOOZE, logKey) }
                    Repo.change(context) { s ->
                        runCatching { Tasks.snooze(s, id, quickSnooze(Repo.presets(s), now, zone), now, due, checked = true) }.getOrNull() ?: s
                    }
                }
                // Swiped away while still due: the sound stops; it comes back quietly while pinned, at most
                // 5 times for this occurrence (F-021), and always stays in the app and the widget.
                DISMISSED -> {
                    val t = Repo.state(context).tasks.firstOrNull { it.id == id }
                    if (t != null && t.dueAt != null && (due == null || t.dueAt == due)) {
                        val prefs = Repo.prefs(context)
                        val key = Engine.repostKey(t)
                        prefs.repostLog = prefs.repostLog + (key to ((prefs.repostLog[key]?.toIntOrNull() ?: 0) + 1).toString())
                        runCatching { log.add(AlertLog.Event.DISMISSED, log.keyOf(t.id, Codec.iso(t.dueAt))) }
                    }
                    Engine.sync(context, now, "action")
                }
            }
        }
    }
}
