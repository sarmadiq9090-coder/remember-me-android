package com.fastkiwi.rememberme.data

import android.content.Context
import androidx.core.content.edit
import com.fastkiwi.rememberme.core.prayer.Place
import com.fastkiwi.rememberme.core.prayer.Places
import java.time.ZoneId
import java.util.Locale

/**
 * Phone-only settings added by the Settings screen 1.0.0, in the same "device" file as [Prefs] (never in
 * data.json; FEATURES.md "Data format"). The keys [Prefs] already has are read and written through it.
 */
class SettingsPrefs(context: Context) {
    private val p = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /**
     * «التذكيرات المهمة»: how a high-priority reminder without its own choice alerts, "alarm" (default,
     * F-015) or "normal".
     */
    var importantStyle: String
        get() = p.getString("importantStyle", "alarm")?.takeIf { it == "alarm" || it == "normal" } ?: "alarm"
        set(v) = p.edit { putString("importantStyle", if (v == "normal") "normal" else "alarm") }

    /** «السحب على التذكيرات» (DESIGN-SPEC §5.2): Home's own setting ([HomePrefs.swipe]), on by default. */
    var swipeOn: Boolean
        get() = p.getBoolean(HomePrefs.KEY_SWIPE, true)
        set(v) = p.edit { putBoolean(HomePrefs.KEY_SWIPE, v) }

    /** «تضمين النغمات المختارة من ملفاتي»: backups carry the tone copies in use (off; Windows ignores them). */
    var backupTones: Boolean
        get() = p.getBoolean("backupTones", false)
        set(v) = p.edit { putBoolean("backupTones", v) }

    /** «تشفير النسخة» (F-086): backups are sealed with the saved password ([BackupPassword]). */
    var encryptBackups: Boolean
        get() = p.getBoolean("encryptBackups", false)
        set(v) = p.edit { putBoolean("encryptBackups", v) }

    /** Epoch ms of the last failed weekly backup (F-087), 0 = none since the last success. Health reads it. */
    var autoBackupFailedAt: Long
        get() = p.getLong("autoBackupFailedAt", 0)
        set(v) = p.edit { putLong("autoBackupFailedAt", v) }

    companion object {
        /** prayerCity for typed coordinates: "geo:<lat>,<lon>,<IANA zone>". Otherwise a city's English name. */
        const val GEO = "geo:"

        fun geo(lat: Double, lon: Double, zone: ZoneId): String =
            GEO + String.format(Locale.ROOT, "%.4f,%.4f,%s", lat, lon, zone.id)

        /** The place [Prefs.prayerCity] names, or null when none is chosen or it cannot be read. */
        fun place(city: String): Place? {
            if (city.isEmpty()) return null
            if (!city.startsWith(GEO)) return Places.byEnglishName(city)
            val parts = city.removePrefix(GEO).split(',')
            if (parts.size != 3) return null
            val lat = parts[0].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
            val lon = parts[1].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
            val zone = runCatching { ZoneId.of(parts[2]) }.getOrNull() ?: return null
            val label = String.format(Locale.ROOT, "%.2f, %.2f", lat, lon)
            return Place(label, label, lat, lon, zone.id, "")
        }
    }
}
