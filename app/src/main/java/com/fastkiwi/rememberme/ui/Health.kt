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
import androidx.core.net.toUri
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.data.AlertLog

/** What «صحة التذكيرات» checks (D-006, FEATURES.md F-008, F-023). */
data class Health(
    val notifications: Boolean,
    val channel: Boolean,
    val exact: Boolean,
    val fullScreen: Boolean,
    val battery: Boolean,
    val dnd: Boolean,
    val channelSound: Boolean = true,
    val channelVibrates: Boolean = true,
    /** The user restricted the app's background activity (Android 9+): alarms may not run. */
    val backgroundRestricted: Boolean = false,
    /** Android's app standby bucket (Android 9+): 5 active … 45 restricted; 0 unknown. */
    val bucket: Int = 0,
    /** Which exact-alarm permission applies: USE_EXACT_ALARM (13+), SCHEDULE_EXACT_ALARM (12), none (older). */
    val exactPermission: String = "",
    /** When the phone last started (epoch ms). */
    val bootAt: Long = 0,
    /** F-023: ringer mode and Do Not Disturb as the app can read them; null = «غير معروف» (never green). */
    val ringer: AlertPolicy.Ringer? = AlertPolicy.Ringer.NORMAL,
    val dndState: AlertPolicy.Dnd? = AlertPolicy.Dnd.OFF,
    /** Whether the phone's Do Not Disturb policy lets alarms through (also while DND is off); null = unknown. */
    val dndAlarmsAllowed: Boolean? = null,
    /** Stream volumes (current and maximum); null = could not be read. */
    val alarmVolume: Int? = null,
    val alarmVolumeMax: Int? = null,
    val notifVolume: Int? = null,
    val notifVolumeMax: Int? = null,
    /** The «منبّهات مهمة» channel is not muted. */
    val alarmChannelSound: Boolean = true,
    /** Fallbacks or failed tones in the last 7 days (F-017); null = the log could not be read. */
    val toneProblems: Int? = 0,
    /** The ringing service could not start in the last 7 days (the fallback channel was used). */
    val fgsFailures: Int = 0,
    /** Android hides all notification content on the lock screen (null: cannot be read). */
    val lockScreenHidesAll: Boolean? = null,
    /** When this check ran (epoch ms), for «آخر فحص». */
    val checkedAt: Long = 0,
) {
    /** Without these, reminders are not shown at all or arrive late: the red banner. */
    val essentialOk get() = notifications && channel && exact && !backgroundRestricted

    /** In the "restricted" standby bucket alarms are cut to about one a day (alarm clocks excepted). */
    val bucketRestricted get() = bucket >= 45

    /** The notification volume is at zero (the reminder sound cannot be heard). Unknown is not zero here; Health shows it as unknown. */
    val volumeZero get() = notifVolume == 0
    val alarmVolumeZero get() = alarmVolume == 0

    companion object {
        fun check(context: Context): Health {
            val pm = context.getSystemService(PowerManager::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            val audio = runCatching { context.getSystemService(android.media.AudioManager::class.java) }.getOrNull()
            fun vol(stream: Int) = runCatching { audio!!.getStreamVolume(stream) }.getOrNull()
            fun max(stream: Int) = runCatching { audio!!.getStreamMaxVolume(stream) }.getOrNull()
            val week = System.currentTimeMillis() - 7 * 86_400_000L
            val log = runCatching { com.fastkiwi.rememberme.data.AlertLog(context).entries() }.getOrNull()
            return Health(
                notifications = Notifier.canPost(context),
                channel = Notifier.alertChannelOk(context),
                exact = Engine.canExact(context),
                fullScreen = Notifier.canFullScreen(context),
                battery = pm.isIgnoringBatteryOptimizations(context.packageName),
                dnd = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
                channelSound = Notifier.alertChannelSound(context),
                channelVibrates = Notifier.alertChannelVibrates(context),
                backgroundRestricted = Build.VERSION.SDK_INT >= 28 &&
                    context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted,
                bucket = if (Build.VERSION.SDK_INT >= 28)
                    runCatching { context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket }.getOrDefault(0) else 0,
                exactPermission = when {
                    Build.VERSION.SDK_INT >= 33 -> "USE_EXACT_ALARM"
                    Build.VERSION.SDK_INT >= 31 -> "SCHEDULE_EXACT_ALARM"
                    else -> "none needed"
                },
                bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime(),
                ringer = runCatching { Notifier.ringer(context) }.getOrNull(),
                // Android's "unknown" filter is not "off": shown as «غير معروف».
                dndState = runCatching {
                    if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) null else Notifier.dnd(context)
                }.getOrNull(),
                // Alarms became a DND category of their own in Android 9; before that the app cannot tell.
                dndAlarmsAllowed = if (Build.VERSION.SDK_INT >= 28) runCatching {
                    nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
                }.getOrNull() else null,
                alarmVolume = vol(android.media.AudioManager.STREAM_ALARM),
                alarmVolumeMax = max(android.media.AudioManager.STREAM_ALARM),
                notifVolume = vol(android.media.AudioManager.STREAM_NOTIFICATION),
                notifVolumeMax = max(android.media.AudioManager.STREAM_NOTIFICATION),
                alarmChannelSound = Notifier.styleChannelAudible(context, AlertPolicy.Style.ALARM),
                toneProblems = log?.count { it.at > week && (it.event == AlertLog.Event.FALLBACK || it.event == AlertLog.Event.PLAY_FAILED) },
                fgsFailures = log?.count { it.at > week && it.event == AlertLog.Event.FALLBACK && it.code in FGS_CODES } ?: 0,
                lockScreenHidesAll = lockScreenHidesAll(context),
                checkedAt = System.currentTimeMillis(),
            )
        }

        /** Alert-log codes written when the ringing service could not start (RingService, Notifier). */
        val FGS_CODES = setOf("foreground", "service")
    }
}

/**
 * Whether the phone shows no notification content on the lock screen (F-022). The secure setting is
 * readable by apps on most versions; anything else is «غير معروف».
 */
fun lockScreenHidesAll(context: Context): Boolean? = runCatching {
    val r = context.contentResolver
    // "Hide sensitive content" does not hide ours when «أظهر التفاصيل» is chosen (they are public).
    when (Settings.Secure.getInt(r, "lock_screen_show_notifications", -1)) { 0 -> true; 1 -> false; else -> null }
}.getOrNull()

/** The phone hides sensitive notification content on the lock screen (null: cannot be read). */
fun phoneHidesSensitiveContent(context: Context): Boolean? = runCatching {
    val r = context.contentResolver
    if (Settings.Secure.getInt(r, "lock_screen_show_notifications", 1) == 0) return@runCatching true
    when (Settings.Secure.getInt(r, "lock_screen_allow_private_notifications", -1)) { 0 -> true; 1 -> false; else -> null }
}.getOrNull()

object SystemScreens {
    private fun pkg(context: Context): Uri = ("package:" + context.packageName).toUri()

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(context))

    fun notifications(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun channel(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_NORMAL)

    fun alarmChannel(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_ALARM)

    fun sound(): Intent = Intent(Settings.ACTION_SOUND_SETTINGS)

    fun exact(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(context)) else appDetails(context)

    fun fullScreen(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(context)) else appDetails(context)

    @android.annotation.SuppressLint("BatteryLife")
    fun battery(context: Context): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(context))

    fun hibernation(context: Context): Intent = IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    fun dnd(): Intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)

    /** The list of apps and their battery optimisation (no special permission needed). */
    fun batteryList(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

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
