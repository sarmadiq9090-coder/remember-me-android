package com.fastkiwi.rememberme.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.ui.L10n
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * «ملخص الصباح» (FEATURES.md F-030): off by default; at the chosen minute a quiet notice with the number
 * of today's reminders, never a title (so nothing shows on the lock screen). Nothing when there are none.
 * Its alarm is inexact and never touches the two reminder slots.
 */
object Summary {
    const val ACTION = "com.fastkiwi.rememberme.SUMMARY"
    private const val SLOT = 104

    /** Today's reminders still to come or waiting: active, due today (local), not in the trash. */
    fun countToday(s: AppState, now: Instant, zone: ZoneId): Int {
        val today = now.atZone(zone).toLocalDate()
        return s.tasks.count { it.active && it.dueAt != null && it.dueAt.atZone(zone).toLocalDate() == today }
    }

    /** The next moment for a summary at [minutes] after local midnight, strictly after [now]. */
    fun nextAt(minutes: Int, now: Instant, zone: ZoneId): Instant {
        val time = LocalTime.of(minutes / 60, minutes % 60)
        val today = now.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
        return if (today.isAfter(now)) today else now.atZone(zone).toLocalDate().plusDays(1).atTime(time).atZone(zone).toInstant()
    }

    private fun op(c: Context) = PendingIntent.getBroadcast(c, SLOT, Intent(c, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun arm(c: Context, now: Instant) {
        val am = c.getSystemService(AlarmManager::class.java)
        val minutes = Repo.prefs(c).summaryMinutes
        if (minutes !in 0 until 24 * 60) { am.cancel(op(c)); return }
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(minutes, now, Repo.zone()).toEpochMilli(), op(c)) }
    }

    fun fire(c: Context, now: Instant = Instant.now()) {
        val n = countToday(Repo.state(c), now, Repo.zone())
        if (n > 0 && Notifier.canPost(c)) {
            val l = L10n.context(c)
            val open = PendingIntent.getActivity(c, 105, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val zone = Repo.zone()
            val first = Repo.state(c).tasks.filter { it.active && it.dueAt != null && it.dueAt.isAfter(now) &&
                it.dueAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() }.minByOrNull { it.dueAt!! }
            val b = NotificationCompat.Builder(l, Notifier.CH_SUMMARY)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(l.getString(R.string.channel_summary))
                .setContentText(L10n.plural(l, R.plurals.reminders_today, n.toLong()))
            // The first title only when details may show on the lock screen (F-030, F-022).
            if (first != null && !Repo.prefs(c).lockPrivacy) b.setStyle(NotificationCompat.BigTextStyle().bigText(
                L10n.plural(l, R.plurals.reminders_today, n.toLong()) + "\n" + l.getString(R.string.summary_first, L10n.time(l, first.dueAt!!), first.title)))
            val notification = b
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true).setContentIntent(open).build()
            runCatching { NotificationManagerCompat.from(c).notify(Notifier.MORNING_ID, notification) }
        }
        arm(c, now.plusSeconds(60))
    }
}
