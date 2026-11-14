package com.fastkiwi.rememberme.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.R

/**
 * Reminders before the first unlock after a restart (FEATURES.md F-026). Android keeps the app's normal
 * storage closed until the phone is unlocked once, so the data file cannot be read. The engine therefore
 * keeps, in device-protected storage, only the next due times and whether each is an «منبّه مهم» — never
 * a title, a note or an id. After a locked restart this arms the next time; when it comes, the phone
 * rings with the phone's own sound and says «لديك تذكير، افتح القفل لرؤيته». After the unlock the
 * normal engine takes over and does not ring the same occurrence again.
 */
object DirectBoot {
    private const val PREFS = "boot_times"
    private const val SLOT = 110
    const val LOCKED_ID = 4
    const val ACTION = "com.fastkiwi.rememberme.LOCKED_FIRE"
    /** How many upcoming times are kept (a night of restarts needs few). */
    const val KEEP = 20

    private fun dp(c: Context): Context = c.createDeviceProtectedStorageContext()
    private fun p(c: Context) = dp(c).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun unlocked(c: Context): Boolean = c.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /** Called by the engine on every run (unlocked): the next due times, "epochMs:a|n" only. */
    fun remember(c: Context, times: List<Pair<Long, Boolean>>, language: String) {
        val v = times.sortedBy { it.first }.take(KEEP).joinToString(",") { "${it.first}:${if (it.second) "a" else "n"}" }
        val p = p(c)
        if (p.getString("times", "") != v || p.getString("lang", "") != language) p.edit { putString("times", v); putString("lang", language) }
    }

    /** The interface language, readable while locked. */
    private fun localized(c: Context): Context {
        val cfg = android.content.res.Configuration(c.resources.configuration)
        cfg.setLocale(java.util.Locale.forLanguageTag(if (p(c).getString("lang", "ar") == "en") "en" else "ar"))
        return dp(c).createConfigurationContext(cfg)
    }

    fun times(c: Context): List<Pair<Long, Boolean>> = (p(c).getString("times", "") ?: "").split(',').mapNotNull { s ->
        val parts = s.split(':')
        parts.getOrNull(0)?.toLongOrNull()?.let { it to (parts.getOrNull(1) == "a") }
    }

    /** Occurrences already rung while locked (the engine posts them quietly after the unlock). */
    fun rungWhileLocked(c: Context): Set<Long> = (p(c).getString("rung", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()

    fun clearRung(c: Context) { if (p(c).contains("rung")) p(c).edit { remove("rung") } }

    private fun op(c: Context) = PendingIntent.getBroadcast(dp(c), SLOT,
        Intent(c, AlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** While locked: arms the next remembered time after [now]. */
    fun armNext(c: Context, now: Long = System.currentTimeMillis()) {
        val am = c.getSystemService(AlarmManager::class.java)
        val rung = rungWhileLocked(c)
        // A time missed while the phone was off (up to 6 h ago) rings now, once.
        val next = times(c).firstOrNull { it.first > now - 6 * 3_600_000L && it.first !in rung } ?: run { am.cancel(op(c)); return }
        val at = maxOf(next.first, now + 1000)
        runCatching {
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), op(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op(c))
        }
    }

    /** The locked alarm fired: ring for every remembered time that has come, then arm the next. */
    fun fire(c: Context, now: Long = System.currentTimeMillis()) {
        if (unlocked(c)) { Engine.syncAsync(c, reason = "alarm"); return }
        val rung = rungWhileLocked(c)
        val come = times(c).filter { it.first <= now + 1000 && it.first > now - 6 * 3_600_000L && it.first !in rung }
        if (come.isNotEmpty()) {
            p(c).edit { putString("rung", (rung + come.map { it.first }).joinToString(",")) }
            post(c, come.any { it.second })
        }
        armNext(c, now)
    }

    /** «لديك تذكير، افتح القفل لرؤيته»: the fallback channel, so the phone's own sound plays (no file is readable yet). */
    private fun post(c: Context, alarm: Boolean) {
        val l = localized(c)
        val open = PendingIntent.getActivity(dp(c), 111, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(l, Notifier.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(l.getString(R.string.you_have_reminder))
            .setContentText(l.getString(R.string.open_to_see))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(dp(c)).notify(LOCKED_ID, n) }
    }

    fun cancelLocked(c: Context) = runCatching { c.getSystemService(NotificationManager::class.java).cancel(LOCKED_ID) }
}

/** Direct-boot-aware: the locked restart. Its alarm goes to the non-exported [AlarmReceiver]. */
class LockedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED && !DirectBoot.unlocked(context)) DirectBoot.armNext(context)
    }
}
