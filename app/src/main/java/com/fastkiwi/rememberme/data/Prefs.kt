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

    /** The data schema the phone's data is at (Migrations); null on a phone that never stored it. */
    var dataSchema: Int?
        get() = if (p.contains("dataSchema")) p.getInt("dataSchema", 1) else null
        set(v) = p.edit { if (v == null) remove("dataSchema") else putInt("dataSchema", v) }

    /** The versionCode that last opened the data (null before 0.2.0). */
    var lastVersion: Int?
        get() = if (p.contains("lastVersion")) p.getInt("lastVersion", 0) else null
        set(v) = p.edit { if (v == null) remove("lastVersion") else putInt("lastVersion", v) }

    // ---- 1.0.0 alert settings (phone only, never in data.json; FEATURES.md "Data format") ----

    /** Default tone for normal reminders and for important ones; null = the phone's own default. */
    var normalTone: com.fastkiwi.rememberme.core.ToneRef?
        get() = p.getString("normalTone", null)?.let { com.fastkiwi.rememberme.core.ToneRef.parse(runCatching { com.fastkiwi.rememberme.core.Codec.json.parseToJsonElement(it) }.getOrNull()) }
        set(v) = p.edit { if (v == null) remove("normalTone") else putString("normalTone", v.toJson().toString()) }
    var importantTone: com.fastkiwi.rememberme.core.ToneRef?
        get() = p.getString("importantTone", null)?.let { com.fastkiwi.rememberme.core.ToneRef.parse(runCatching { com.fastkiwi.rememberme.core.Codec.json.parseToJsonElement(it) }.getOrNull()) }
        set(v) = p.edit { if (v == null) remove("importantTone") else putString("importantTone", v.toJson().toString()) }

    /** «شاشة التذكير الكاملة»: always (default, 0.1 behaviour), important, off. */
    var fullScreenMode: String
        get() = p.getString("fullScreenMode", "always")?.takeIf { it in setOf("always", "important", "off") } ?: "always"
        set(v) = p.edit { putString("fullScreenMode", v) }

    /** «تثبيت التذكير على شاشة القفل»: on by default. */
    var pinDefault: Boolean
        get() = p.getBoolean("pinDefault", true)
        set(v) = p.edit { putBoolean("pinDefault", v) }

    var vibrateDefault: Boolean
        get() = p.getBoolean("vibrateDefault", true)
        set(v) = p.edit { putBoolean("vibrateDefault", v) }

    /** short, normal, long. */
    var vibrationPattern: String
        get() = p.getString("vibrationPattern", "normal")?.takeIf { it in setOf("short", "normal", "long") } ?: "normal"
        set(v) = p.edit { putString("vibrationPattern", v) }

    /** How long «منبّه مهم» rings: 30, 60 (default) or 120 seconds. */
    var ringSeconds: Int
        get() = p.getInt("ringSeconds", 60).takeIf { it in setOf(30, 60, 120) } ?: 60
        set(v) = p.edit { putInt("ringSeconds", v) }

    /** Read the title aloud by default (F-031), off. */
    var speakDefault: Boolean
        get() = p.getBoolean("speakDefault", false)
        set(v) = p.edit { putBoolean("speakDefault", v) }

    /** Hijri display (F-051) and its ±2 day adjustment. */
    var hijriDisplay: Boolean
        get() = p.getBoolean("hijriDisplay", false)
        set(v) = p.edit { putBoolean("hijriDisplay", v) }
    var hijriAdjust: Int
        get() = p.getInt("hijriAdjust", 0).coerceIn(-2, 2)
        set(v) = p.edit { putInt("hijriAdjust", v.coerceIn(-2, 2)) }

    /** Prayer times (F-050): method, Asr rule and city; empty = not set up (nothing is assumed). */
    var prayerMethod: String
        get() = p.getString("prayerMethod", "") ?: ""
        set(v) = p.edit { putString("prayerMethod", v) }
    var prayerAsr: String
        get() = p.getString("prayerAsr", "STANDARD") ?: "STANDARD"
        set(v) = p.edit { putString("prayerAsr", v) }
    var prayerCity: String
        get() = p.getString("prayerCity", "") ?: ""
        set(v) = p.edit { putString("prayerCity", v) }

    /** Morning summary (F-030): minutes after midnight, or -1 = off (default). */
    var summaryMinutes: Int
        get() = p.getInt("summaryMinutes", -1)
        set(v) = p.edit { putInt("summaryMinutes", v) }

    /** Change history (F-072), off by default. */
    var historyOn: Boolean
        get() = p.getBoolean("historyOn", false)
        set(v) = p.edit { putBoolean("historyOn", v) }

    /** Weekly automatic backup to a folder the user picked (F-087): the tree URI, or "" = off. */
    var autoBackupUri: String
        get() = p.getString("autoBackupUri", "") ?: ""
        set(v) = p.edit { putString("autoBackupUri", v) }
    var lastAutoBackup: Long
        get() = p.getLong("lastAutoBackup", 0)
        set(v) = p.edit { putLong("lastAutoBackup", v) }

    /** Quiet re-posts after a swipe, per occurrence (F-021): "taskId|dueAt" → count. */
    var repostLog: Map<String, String>
        get() = decode(p.getString("repostLog", "") ?: "")
        set(v) = p.edit { putString("repostLog", encode(v)) }

    /** The versionCode whose «ما الجديد» was shown (F-106). */
    var whatsNewSeen: Int
        get() = p.getInt("whatsNewSeen", 0)
        set(v) = p.edit { putInt("whatsNewSeen", v) }

    /** Auto-complete a reminder when all its steps are ticked (F-071), off. */
    var autoCompleteSteps: Boolean
        get() = p.getBoolean("autoCompleteSteps", false)
        set(v) = p.edit { putBoolean("autoCompleteSteps", v) }

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
    // "key TAB value" lines; a tab, a line break or "%" inside a key or a value is escaped, so an odd id
    // from an imported file (a line break) still reads back (the alert log would otherwise ring it each run).
    private fun esc(s: String) = s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")
    private fun unesc(s: String) = s.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
    private fun encode(m: Map<String, String>) = m.entries.joinToString("\n") { "${esc(it.key)}\t${esc(it.value)}" }
    private fun decode(s: String): Map<String, String> = s.lineSequence().mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else unesc(line.substring(0, i)) to unesc(line.substring(i + 1))
    }.toMap()
}
