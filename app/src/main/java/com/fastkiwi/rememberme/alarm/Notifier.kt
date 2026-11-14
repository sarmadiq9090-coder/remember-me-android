package com.fastkiwi.rememberme.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.fastkiwi.rememberme.AlertActivity
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.snoozeTime
import com.fastkiwi.rememberme.ui.L10n
import java.time.Instant

/**
 * Notifications (D-005). Each due reminder has its own high-priority notification with «تم» and «أجّل»
 * buttons; tapping it (or the full-screen intent, where Android allows it) opens the reminder screen.
 * Channel ids are versioned: Android never lets an app change a channel's sound once created.
 */
object Notifier {
    const val CH_ALERT = "reminders_v1"
    const val CH_QUIET = "reminders_quiet_v1"
    const val CH_NOTICE = "notices_v1"
    const val TAG_DUE = "due:"
    const val TAG_NOTICE = "notice:"
    private const val GROUP = "com.fastkiwi.rememberme.DUE"
    private const val SUMMARY_ID = 1
    /** At most this many single notifications; the rest are counted in the summary and the app. */
    const val MAX_SHOWN = 8

    fun channels(context: Context) {
        val c = L10n.context(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, c.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH).apply {
            description = c.getString(R.string.channel_alert_desc)
            setSound(alarmSound, attrs)
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, c.getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_HIGH).apply {
            description = c.getString(R.string.channel_quiet_desc)
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_NOTICE, c.getString(R.string.channel_notice), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = c.getString(R.string.channel_notice_desc)
        })
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True when the reminder channel exists and the user has not silenced or blocked it. */
    fun alertChannelOk(context: Context): Boolean {
        val ch = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CH_ALERT) ?: return true
        return ch.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private fun code(id: String, salt: Int) = id.hashCode() * 31 + salt

    fun alertIntent(context: Context, id: String?): Intent = Intent(context, AlertActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        id?.let { putExtra(AlertActivity.EXTRA_ID, it) }
    }

    private fun action(context: Context, action: String, t: Task): PendingIntent {
        val i = Intent(context, ActionReceiver::class.java).apply {
            this.action = action
            data = ("rememberme://task/" + Uri.encode(t.id)).toUri()
            putExtra(ActionReceiver.EXTRA_ID, t.id)
            putExtra(ActionReceiver.EXTRA_DUE, t.dueAt?.let(Codec::iso))
        }
        return PendingIntent.getBroadcast(context, code(t.id, action.hashCode()), i, flags())
    }

    /** Posts or refreshes the notification of a due reminder. [loud]: with sound and full-screen. */
    fun postDue(context: Context, t: Task, loud: Boolean, sound: Boolean, now: Instant) {
        if (!canPost(context)) return
        val c = L10n.context(context)
        val open = PendingIntent.getActivity(context, code(t.id, 1), alertIntent(context, t.id), flags())
        val title = t.title
        val late = L10n.late(c, t.dueAt!!, now)
        // «حان الوقت، 2:00 م، كل يوم» or «متأخر 22 ساعة، …», joined with the language's separator (never «·»).
        val status = if (late != null) c.getString(R.string.late_short, L10n.duration(c, (now.toEpochMilli() - t.dueAt.toEpochMilli()) / 60_000)) else c.getString(R.string.due_now)
        val text = L10n.join(c, listOf(status, L10n.time(c, t.dueAt), L10n.repeatText(c, t)))
        val channel = if (loud && sound) CH_ALERT else CH_QUIET
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, t.body.takeIf { it.isNotBlank() }).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!loud)
            .setSilent(!loud || !sound)
            .setWhen(t.dueAt.toEpochMilli())
            .setShowWhen(true)
            .setGroup(GROUP)
            .setColor(0xFF246A4B.toInt())
            .setContentIntent(open)
            .setDeleteIntent(action(context, ActionReceiver.DISMISSED, t))
            .addAction(R.drawable.ic_check, c.getString(R.string.done), action(context, ActionReceiver.DONE, t))
            .addAction(R.drawable.ic_snooze, snoozeLabel(c, now), action(context, ActionReceiver.SNOOZE, t))
        // Lock screen: title by default; with privacy on, only «تذكير».
        if (Repo.prefs(context).lockPrivacy) {
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            b.setPublicVersion(NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(c.getString(R.string.reminder)).setContentText(c.getString(R.string.open_to_see)).build())
        } else b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (loud && canFullScreen(context)) b.setFullScreenIntent(open, true)
        try {
            NotificationManagerCompat.from(context).notify(TAG_DUE + t.id, 0, b.build())
        } catch (_: SecurityException) { /* permission withdrawn meanwhile */ }
    }

    /** «أجّل 10 دقائق»: says what the button does (the first snooze choice that applies now). */
    fun snoozeLabel(c: Context, now: Instant): String {
        val zone = Repo.zone()
        val preset = Repo.presets(Repo.state(c)).firstOrNull { snoozeTime(it, now, zone) != null }
            ?: return c.getString(R.string.snooze_for, L10n.duration(c, 10))
        return c.getString(R.string.snooze_for, com.fastkiwi.rememberme.ui.presetLabel(c, preset, now, zone))
    }

    fun postSummary(context: Context, count: Int) {
        if (!canPost(context)) return
        val nm = NotificationManagerCompat.from(context)
        if (count < 2) { nm.cancel(SUMMARY_ID); return }
        val c = L10n.context(context)
        val open = PendingIntent.getActivity(context, 7, alertIntent(context, null), flags())
        val n = NotificationCompat.Builder(c, CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(L10n.plural(c, R.plurals.reminders_waiting, count.toLong()))
            .setGroup(GROUP).setGroupSummary(true).setOngoing(true).setSilent(true)
            .setContentIntent(open).build()
        try { nm.notify(SUMMARY_ID, n) } catch (_: SecurityException) { }
    }

    fun postNotice(context: Context, t: Task, now: Instant) {
        if (!canPost(context)) return
        val c = L10n.context(context)
        val open = PendingIntent.getActivity(context, code(t.id, 2),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags())
        val n = NotificationCompat.Builder(c, CH_NOTICE)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(t.title)
            .setContentText(c.getString(R.string.coming_up, L10n.dueText(c, t.dueAt!!, now)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(if (Repo.prefs(context).lockPrivacy) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(context).notify(TAG_NOTICE + t.id, 0, n) } catch (_: SecurityException) { }
    }

    /** Ids of the reminders whose due notification is showing now. */
    fun shownDue(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .mapNotNull { it.tag?.takeIf { tag -> tag.startsWith(TAG_DUE) }?.removePrefix(TAG_DUE) }.toSet()

    fun cancelDue(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(TAG_DUE + id, 0)
    fun cancelNotice(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(TAG_NOTICE + id, 0)
}
