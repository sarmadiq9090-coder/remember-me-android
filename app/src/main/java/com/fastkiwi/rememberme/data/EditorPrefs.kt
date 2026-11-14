package com.fastkiwi.rememberme.data

import android.content.Context
import com.fastkiwi.rememberme.core.ExtRepeat
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.prayer.AsrRule
import com.fastkiwi.rememberme.core.prayer.Method
import com.fastkiwi.rememberme.core.prayer.Place
import com.fastkiwi.rememberme.core.prayer.Places
import com.fastkiwi.rememberme.core.prayer.Prayer
import com.fastkiwi.rememberme.core.prayer.PrayerTimes
import java.time.Instant

/**
 * The phone settings the editor reads (all from [Prefs], the "device" file), as one snapshot, so the editor
 * and its tests do not depend on SharedPreferences while composing.
 */
data class EditorSettings(
    val hijriDisplay: Boolean = false,
    val hijriAdjust: Int = 0,
    val historyOn: Boolean = false,
    val autoCompleteSteps: Boolean = false,
    /** always | important | off */
    val fullScreenMode: String = "always",
    val pinDefault: Boolean = true,
    val vibrateDefault: Boolean = true,
    val speakDefault: Boolean = false,
    /** Hide details on the lock screen by default (Prefs.lockPrivacy). */
    val lockHideDefault: Boolean = false,
    val normalTone: ToneRef? = null,
    val importantTone: ToneRef? = null,
    /** Prayer setup (F-050): city (Places English name), method (Method name), Asr rule; "" = not chosen. */
    val prayerCity: String = "",
    val prayerMethod: String = "",
    val prayerAsr: String = "STANDARD",
) {
    val place: Place? get() = prayerCity.takeIf { it.isNotEmpty() }?.let { Places.byEnglishName(it) }
    val method: Method? get() = Method.entries.firstOrNull { it.name == prayerMethod }
    val asr: AsrRule get() = AsrRule.entries.firstOrNull { it.name == prayerAsr } ?: AsrRule.STANDARD
    /** Both chosen by the user (no default is ever assumed, F-050). */
    val prayerReady: Boolean get() = place != null && method != null
}

object EditorPrefs {
    fun read(context: Context): EditorSettings {
        val p = Prefs(context.applicationContext)
        return EditorSettings(
            hijriDisplay = p.hijriDisplay, hijriAdjust = p.hijriAdjust, historyOn = p.historyOn,
            autoCompleteSteps = p.autoCompleteSteps, fullScreenMode = p.fullScreenMode, pinDefault = p.pinDefault,
            vibrateDefault = p.vibrateDefault, speakDefault = p.speakDefault, lockHideDefault = p.lockPrivacy,
            normalTone = p.normalTone, importantTone = p.importantTone,
            prayerCity = p.prayerCity, prayerMethod = p.prayerMethod, prayerAsr = p.prayerAsr,
        )
    }

    fun setAutoComplete(context: Context, on: Boolean) { Prefs(context.applicationContext).autoCompleteSteps = on }

    fun setPrayerCity(context: Context, englishName: String) { Prefs(context.applicationContext).prayerCity = englishName }

    fun setPrayerMethod(context: Context, method: String) { Prefs(context.applicationContext).prayerMethod = method }

    /** "fajr" … "isha" → the prayer. */
    fun prayerOf(name: String): Prayer? = when (name) {
        "fajr" -> Prayer.FAJR
        "dhuhr" -> Prayer.DHUHR
        "asr" -> Prayer.ASR
        "maghrib" -> Prayer.MAGHRIB
        "isha" -> Prayer.ISHA
        else -> null
    }

    /** The first time of [name] + [offset] minutes after [after] with the phone's setup, or null when not set up. */
    fun prayerNext(s: EditorSettings, name: String, offset: Int, after: Instant): Instant? {
        val place = s.place ?: return null
        val method = s.method ?: return null
        val prayer = prayerOf(name) ?: return null
        return PrayerTimes.next(prayer, offset, after, place, method, s.asr)
    }

    /**
     * Lets the core move prayer and Hijri tasks with the phone's setup (Tasks.extRepeat). Idempotent; the
     * settings are read at each use, so a changed city applies to the next occurrence.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        Tasks.extRepeat = {
            val s = read(app)
            ExtRepeat(hijriAdjust = s.hijriAdjust, prayerNext = { name, offset, after -> prayerNext(s, name, offset, after) })
        }
    }
}
