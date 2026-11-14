package com.fastkiwi.rememberme.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.IntentCompat
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier

/** What the "allow background" screen checks (D-006). */
data class Health(
    val notifications: Boolean,
    val channel: Boolean,
    val exact: Boolean,
    val fullScreen: Boolean,
    val battery: Boolean,
    val dnd: Boolean,
) {
    /** Without these, reminders are not shown at all or arrive late: the red banner. */
    val essentialOk get() = notifications && channel && exact

    companion object {
        fun check(context: Context): Health {
            val pm = context.getSystemService(PowerManager::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            return Health(
                notifications = Notifier.canPost(context),
                channel = Notifier.alertChannelOk(context),
                exact = Engine.canExact(context),
                fullScreen = Notifier.canFullScreen(context),
                battery = pm.isIgnoringBatteryOptimizations(context.packageName),
                dnd = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
            )
        }
    }
}

object SystemScreens {
    private fun pkg(context: Context) = Uri.parse("package:" + context.packageName)

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(context))

    fun notifications(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun channel(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_ALERT)

    fun exact(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(context)) else appDetails(context)

    fun fullScreen(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(context)) else appDetails(context)

    @android.annotation.SuppressLint("BatteryLife")
    fun battery(context: Context): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(context))

    fun hibernation(context: Context): Intent = IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    fun dnd(): Intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)

    /** Opens [intent], or the app's details page when this phone has no such screen. */
    fun open(context: Context, intent: Intent) {
        val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Started directly (no resolveActivity: Android 11+ hides other apps' screens from queries).
        try { context.startActivity(i); return } catch (_: Exception) { }
        runCatching { context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Phone makers that stop background apps, with their steps (dontkillmyapp.com) and a best-effort screen. */
data class Maker(val name: Int, val steps: Int, val screens: List<ComponentName>)

object Makers {
    fun current(): Maker? = of(Build.MANUFACTURER)

    fun of(manufacturer: String): Maker? = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> Maker(R.string.maker_xiaomi, R.string.steps_xiaomi, listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ))
        "samsung" -> Maker(R.string.maker_samsung, R.string.steps_samsung, listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ))
        "huawei", "honor" -> Maker(R.string.maker_huawei, R.string.steps_huawei, listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ))
        "oppo", "realme", "oneplus" -> Maker(R.string.maker_oppo, R.string.steps_oppo, listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        ))
        "vivo", "iqoo" -> Maker(R.string.maker_vivo, R.string.steps_vivo, listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ))
        else -> null
    }

    /** The maker's screen if this phone has it (these names change between versions), else the app's details. */
    fun open(context: Context, maker: Maker) {
        for (c in maker.screens) {
            val i = Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i); return } catch (_: Exception) { }
        }
        SystemScreens.open(context, SystemScreens.appDetails(context))
    }
}
