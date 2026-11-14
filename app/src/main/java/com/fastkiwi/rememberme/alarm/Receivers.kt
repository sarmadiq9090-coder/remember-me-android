package com.fastkiwi.rememberme.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.quickSnooze
import java.time.Instant
import java.util.concurrent.Executors

/** The engine's own alarm. Not exported. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FIRE) Engine.syncAsync(context, goAsync())
    }
    companion object { const val FIRE = "com.fastkiwi.rememberme.FIRE" }
}

/**
 * Re-arms after a reboot, an app update, a clock or time-zone change, or a change of the exact-alarm
 * permission. Exported only because the system sends these; it reads nothing from the intent.
 */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) Engine.syncAsync(context, goAsync())
    }
    companion object {
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
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val due = intent.getStringExtra(EXTRA_DUE)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val action = intent.action ?: return
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                handle(app, action, id, due, Instant.now())
            } finally { pending.finish() }
        }
    }

    companion object {
        const val DONE = "com.fastkiwi.rememberme.DONE"
        const val SNOOZE = "com.fastkiwi.rememberme.SNOOZE"
        const val DISMISSED = "com.fastkiwi.rememberme.DISMISSED"
        const val EXTRA_ID = "id"
        const val EXTRA_DUE = "due"
        private val worker = Executors.newSingleThreadExecutor()

        /** The occurrence the user saw ([due]) must still be current, or the tap is ignored. */
        fun handle(context: Context, action: String, id: String, due: Instant?, now: Instant) {
            val zone = Repo.zone()
            // A change that cannot be saved (disk full) changes nothing; it must not crash the app.
            try { act(context, action, id, due, now, zone) } catch (_: java.io.IOException) { Engine.sync(context) }
        }

        private fun act(context: Context, action: String, id: String, due: Instant?, now: Instant, zone: java.time.ZoneId) {
            when (action) {
                DONE -> Repo.change(context) { s -> Tasks.complete(s, id, now, zone, due, checked = true)?.first ?: s }
                SNOOZE -> Repo.change(context) { s ->
                    runCatching { Tasks.snooze(s, id, quickSnooze(Repo.presets(s), now, zone), now, due, checked = true) }.getOrNull() ?: s
                }
                // Swiped away while still due: it comes back, quietly, until Done or Snooze (D-005).
                DISMISSED -> Engine.sync(context, now)
            }
        }
    }
}
