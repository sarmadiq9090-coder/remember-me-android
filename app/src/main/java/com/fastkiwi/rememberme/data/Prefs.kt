package com.fastkiwi.rememberme.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Choices about this phone, kept apart from data.json (which moves between devices): the interface
 * language, lock-screen privacy, onboarding, the backup nudge, and what the alarm engine has already
 * announced (so a restart never sounds a reminder twice).
 */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** "ar" (default) or "en". */
    var language: String
        get() = p.getString("language", "ar") ?: "ar"
        set(v) = p.edit { putString("language", if (v == "en") "en" else "ar") }

    /** Hide the title on the lock screen (show «تذكير» only). Off by default. */
    var lockPrivacy: Boolean
        get() = p.getBoolean("lockPrivacy", false)
        set(v) = p.edit { putBoolean("lockPrivacy", v) }

    /** The Android 13+ notification prompt was shown (it may be shown only so often). */
    var notifAsked: Boolean
        get() = p.getBoolean("notifAsked", false)
        set(v) = p.edit { putBoolean("notifAsked", v) }

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit { putBoolean("onboarded", v) }

    /** Epoch ms of the last export (0 = never), for the monthly «احفظ نسخة» reminder. */
    var lastExport: Long
        get() = p.getLong("lastExport", 0)
        set(v) = p.edit { putLong("lastExport", v) }

    var backupNudgeDismissed: Long
        get() = p.getLong("backupNudgeDismissed", 0)
        set(v) = p.edit { putLong("backupNudgeDismissed", v) }

    var firstRun: Long
        get() = p.getLong("firstRun", 0).takeIf { it > 0 } ?: System.currentTimeMillis().also { now -> p.edit { putLong("firstRun", now) } }
        set(v) = p.edit { putLong("firstRun", v) }

    /** Task id → "dueAt|soundSlot" of the last loud alert. */
    var alertLog: Map<String, String>
        get() = decode(p.getString("alertLog", "") ?: "")
        set(v) = p.edit { putString("alertLog", encode(v)) }

    /** Task id → dueAt whose early notice was shown. */
    var noticeLog: Map<String, String>
        get() = decode(p.getString("noticeLog", "") ?: "")
        set(v) = p.edit { putString("noticeLog", encode(v)) }

    /** Epoch ms of the alarm the engine last set (0 = none), to spot alarms the system wiped. */
    var armedAt: Long
        get() = p.getLong("armedAt", 0)
        set(v) = p.edit { putLong("armedAt", v) }

    // Ids and due times never contain tabs or newlines.
    private fun encode(m: Map<String, String>) = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    private fun decode(s: String): Map<String, String> = s.lineSequence().mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
    }.toMap()
}
