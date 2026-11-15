package com.fastkiwi.rememberme

import android.content.Context
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Planner
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.data.Prefs
import com.fastkiwi.rememberme.data.Store
import com.fastkiwi.rememberme.data.Migrations
import java.io.File
import java.time.ZoneId

/**
 * The one data store of the process. The activity, the notification buttons and the alarm receiver
 * all change data through [change], which writes durably and then re-plans the alarms (D-001).
 */
object Repo {
    @Volatile private var store: Store? = null
    @Volatile private var prefs: Prefs? = null

    fun store(context: Context): Store = store ?: synchronized(this) {
        store ?: Store(File(context.applicationContext.filesDir, "data")).also { st ->
            // Bring older data up to this build's schema before anything reads it (a copy is kept first).
            val prefs = prefs(context)
            // A new version of the app: keep a copy of the data as it was before this version touches it.
            val had = st.file.exists()
            val code = com.fastkiwi.rememberme.BuildConfig.VERSION_CODE
            val name = com.fastkiwi.rememberme.BuildConfig.VERSION_NAME
            val last = prefs.lastVersion ?: if (had) 1 else code
            // Keyed by versionCode and versionName (F-082): 1.0.0-rc.N and 1.0.0 share versionCode 3.
            val lastName = prefs.lastVersionName
            val changed = last != code || (lastName != null && lastName != name)
            if (had && changed && !st.newer) runCatching { st.safetyCopy("before-update-v$last-") }
            prefs.lastVersion = code
            prefs.lastVersionName = name
            val from = prefs.dataSchema ?: Migrations.CURRENT
            if (!st.newer) prefs.dataSchema = st.migrate(from, Migrations.CURRENT, Migrations.steps).coerceAtLeast(from)
            store = st
        }
    }

    fun prefs(context: Context): Prefs = prefs ?: synchronized(this) {
        prefs ?: Prefs(context.applicationContext).also { prefs = it }
    }

    fun state(context: Context): AppState = store(context).state.value

    /** Changes data, then re-arms the alarm and refreshes notifications and the widget. */
    fun change(context: Context, block: (AppState) -> AppState): AppState {
        // [editor] begin: prayer/Hijri repeats use the phone's setup (F-050, F-051); history (F-072) sees each change.
        com.fastkiwi.rememberme.data.EditorPrefs.install(context)
        var before: AppState? = null
        val next = store(context).change { s -> before = s; block(s) }
        before?.let { b -> runCatching { com.fastkiwi.rememberme.data.History.observe(context, b, next) } }
        // [editor] end
        Engine.sync(context.applicationContext)
        return next
    }

    fun zone(): ZoneId = ZoneId.systemDefault()

    // Shared settings (data.json "settings", the same as on Windows).
    fun sound(s: AppState) = Codec.settingBool(s.settings, "sound") ?: true
    fun remindAgain(s: AppState) = Codec.settingInt(s.settings, "remindAgain")?.takeIf { it in Planner.REMIND_AGAIN } ?: 0
    fun presets(s: AppState) = SnoozePreset.fromSettings(s.settings)
    /** 6 = Saturday (default), 0 = Sunday, 1 = Monday. */
    fun weekStart(s: AppState) = Codec.settingInt(s.settings, "weekStart")?.takeIf { it in listOf(0, 1, 6) } ?: 6
    fun theme(s: AppState) = Codec.settingStr(s.settings, "theme")?.takeIf { it in listOf("system", "light", "dark") } ?: "system"
    fun arabicMonths(s: AppState) = Codec.settingStr(s.settings, "arabicMonths")?.takeIf { it in listOf("auto", "levant", "standard") } ?: "auto"

    /** For tests: forget the singletons so a fresh store is opened. */
    fun resetForTests() = synchronized(this) { store = null; prefs = null }
}
