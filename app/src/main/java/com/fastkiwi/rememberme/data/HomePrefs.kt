package com.fastkiwi.rememberme.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Home's choices about this phone, in the same "device" preferences file as [Prefs] (never in data.json).
 * Settings › العرض shows the switch; Home reads it.
 */
class HomePrefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** «السحب على التذكيرات» (DESIGN-SPEC §5.2): swipe to finish or snooze a row. On by default. */
    var swipe: Boolean
        get() = p.getBoolean(KEY_SWIPE, true)
        set(v) = p.edit { putBoolean(KEY_SWIPE, v) }

    companion object {
        const val KEY_SWIPE = "swipeActions"
    }
}
