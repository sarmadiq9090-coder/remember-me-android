package com.fastkiwi.rememberme

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.fastkiwi.rememberme.alarm.DirectBoot
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class RememberMeApp : Application() {
    /** The normal start ran in this process (per Application object, so each test's app starts afresh). */
    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Before the first unlock after a restart the data file cannot be read (F-026): only the
        // direct-boot receiver works then. The normal start is finished in this same process once the user
        // unlocks (ACTION_USER_UNLOCKED), or by the first engine run after it, whichever comes first.
        if (!DirectBoot.unlocked(this)) {
            started.set(false)
            runCatching {
                registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) { start(this@RememberMeApp) }
                }, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            }
            return
        }
        start(this)
    }

    companion object {
        private val toneCleanup: (Context, Instant) -> Unit = { c, _ -> com.fastkiwi.rememberme.data.Tones.cleanupUnused(c) }
        private val autoBackup: (Context, Instant) -> Unit = { c, t -> com.fastkiwi.rememberme.data.AutoBackup.runIfDue(c, t) }

        /**
         * The normal start, once per process and only after the unlock: channels; the nightly hooks (tone
         * copies nobody uses are removed, the weekly automatic backup F-087); «التذكيرات المهمة» (F-015); and a
         * first engine run, so reminders whose alarm was wiped (force stop, an OEM battery killer) are
         * announced now instead of being lost.
         */
        fun start(app: Context) {
            val c = app.applicationContext
            if (!DirectBoot.unlocked(c)) return
            if ((c as? RememberMeApp)?.started?.compareAndSet(false, true) == false) return
            Notifier.channels(c)
            Engine.housekeeping.addIfAbsent(toneCleanup)
            Engine.housekeeping.addIfAbsent(autoBackup)
            com.fastkiwi.rememberme.core.AlertPolicy.highIsAlarm = com.fastkiwi.rememberme.data.SettingsPrefs(c).importantStyle != "normal"
            Thread { runCatching { com.fastkiwi.rememberme.data.Tones.cleanupUnused(c) } }.start()
            Engine.syncAsync(c, reason = "start")
        }
    }
}
