package com.fastkiwi.rememberme

import android.app.Application
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier

class RememberMeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        // Every start re-plans: reminders whose alarm was wiped (force stop, an OEM battery killer)
        // are announced now instead of being lost.
        Engine.syncAsync(this)
    }
}
