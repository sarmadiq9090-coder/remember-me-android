package com.fastkiwi.rememberme.ui.health

import android.content.Context
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.SystemScreens

/** System screens that only Health opens; the others stay in MainActivity.fix (the permission request needs the activity). */
object HealthFix {
    /** Opens [target] and returns true, or returns false when MainActivity.fix should handle it. */
    fun open(context: Context, target: String): Boolean {
        when (target) {
            "sound" -> SystemScreens.open(context, SystemScreens.sound())
            "alarm_channel" -> SystemScreens.open(context, SystemScreens.alarmChannel(context))
            "dnd" -> SystemScreens.open(context, SystemScreens.dnd())
            else -> return false
        }
        return true
    }
}

/** Title and steps of a maker step row (F-013). */
fun makerStepText(context: Context, id: String): Pair<String, String?> {
    val (t, d) = when (id) {
        "xiaomi_autostart" -> R.string.mk_xiaomi_autostart to R.string.mk_xiaomi_autostart_d
        "xiaomi_battery" -> R.string.mk_xiaomi_battery to R.string.mk_xiaomi_battery_d
        "xiaomi_lock" -> R.string.mk_xiaomi_lock to R.string.mk_xiaomi_lock_d
        "samsung_battery" -> R.string.mk_samsung_battery to R.string.mk_samsung_battery_d
        "samsung_sleep6" -> R.string.mk_samsung_sleep6 to R.string.mk_samsung_sleep6_d
        "samsung_sleep5" -> R.string.mk_samsung_sleep5 to R.string.mk_samsung_sleep5_d
        "huawei_launch" -> R.string.mk_huawei_launch to R.string.mk_huawei_launch_d
        "oppo_background" -> R.string.mk_oppo_background to R.string.mk_oppo_background_d
        "oppo_autostart" -> R.string.mk_oppo_autostart to R.string.mk_oppo_autostart_d
        "vivo_background" -> R.string.mk_vivo_background to R.string.mk_vivo_background_d
        "vivo_autostart" -> R.string.mk_vivo_autostart to R.string.mk_vivo_autostart_d
        else -> R.string.chk_maker_generic to R.string.steps_generic
    }
    return context.getString(t) to context.getString(d)
}
