package com.fastkiwi.rememberme

import android.app.Application
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier

class RememberMeApp : Application() {
    private companion object {
        val toneCleanup: (android.content.Context, java.time.Instant) -> Unit = { c, _ -> com.fastkiwi.rememberme.data.Tones.cleanupUnused(c) }
        val autoBackup: (android.content.Context, java.time.Instant) -> Unit = { c, t -> com.fastkiwi.rememberme.data.AutoBackup.runIfDue(c, t) }
    }

    override fun onCreate() {
        super.onCreate()
        // Before the first unlock after a restart the data file cannot be read (F-026): only the
        // direct-boot receiver works then, and the normal start happens after the unlock.
        if (!com.fastkiwi.rememberme.alarm.DirectBoot.unlocked(this)) return
        Notifier.channels(this)
        // Tone copies nobody uses any more are removed (manager's decision 2026-11-15): now and nightly.
        Engine.housekeeping.addIfAbsent(toneCleanup)
        // The weekly automatic backup to the folder the user chose (F-087), in the nightly window.
        Engine.housekeeping.addIfAbsent(autoBackup)
        // «التذكيرات المهمة»: high priority rings as «منبّه مهم» unless the user chose «تذكير عادي» (F-015).
        com.fastkiwi.rememberme.core.AlertPolicy.highIsAlarm = com.fastkiwi.rememberme.data.SettingsPrefs(this).importantStyle != "normal"
        Thread { runCatching { com.fastkiwi.rememberme.data.Tones.cleanupUnused(this) } }.start()
        // Every start re-plans: reminders whose alarm was wiped (force stop, an OEM battery killer)
        // are announced now instead of being lost.
        Engine.syncAsync(this, reason = "start")
    }
}
