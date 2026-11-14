package com.fastkiwi.rememberme

import android.app.Application
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier

class RememberMeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before the first unlock after a restart the data file cannot be read (F-026): only the
        // direct-boot receiver works then, and the normal start happens after the unlock.
        if (!com.fastkiwi.rememberme.alarm.DirectBoot.unlocked(this)) return
        Notifier.channels(this)
        // Every start re-plans: reminders whose alarm was wiped (force stop, an OEM battery killer)
        // are announced now instead of being lost.
        Engine.syncAsync(this, reason = "start")
    }
}
