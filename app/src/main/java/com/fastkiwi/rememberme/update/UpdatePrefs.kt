package com.fastkiwi.rememberme.update

import android.content.Context
import androidx.core.content.edit

/**
 * 1.0.5 (D-081, as Kiwi Scan's D-080): the update settings and state, on this phone only («update» preferences).
 * «التحقق التلقائي من التحديثات» and «Wi-Fi فقط» are on by default; the one-time notice must be seen before the
 * first automatic check.
 */
open class UpdatePrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    open var autoUpdateCheck: Boolean
        get() = p.getBoolean("auto", true)
        set(v) = p.edit { putBoolean("auto", v) }

    open var updateWifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", true)
        set(v) = p.edit { putBoolean("wifiOnly", v) }

    open var updateNoticeShown: Boolean
        get() = p.getBoolean("noticeShown", false)
        set(v) = p.edit { putBoolean("noticeShown", v) }

    /** When the last automatic check ran (ms), 0 = never. */
    open var lastUpdateCheck: Long
        get() = p.getLong("lastCheck", 0L)
        set(v) = p.edit { putLong("lastCheck", v) }
}
