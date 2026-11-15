package com.fastkiwi.rememberme.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.fastkiwi.rememberme.AlertActivity
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.hidesOnLockScreen
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.snoozeTime
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.data.Tones
import com.fastkiwi.rememberme.ui.L10n
import java.time.Instant

/**
 * Notifications (D-005, FEATURES.md F-015…F-022, F-032). A due reminder is posted on a silent channel
 * for its style and the app plays its tone through [RingService] (F-016); if that cannot start, the
 * notification goes on the sounding fallback channel. Channel ids are fixed from 1.0.0; there is never a
 * channel per tone.
 */
object Notifier {
    /** Fallback: the phone's default sound (existing id, kept). */
    const val CH_ALERT = "reminders_v1"
    const val CH_QUIET = "reminders_quiet_v1"
    const val CH_NOTICE = "notices_v1"
    const val CH_NORMAL = "due_normal_v1"
    const val CH_ALARM = "due_alarm_v1"
    const val CH_SUMMARY = "summary_v1"
    /** Fallback for a vibrate-only alert when the ringing service cannot start: vibrates, no sound. */
    const val CH_VIBRATE = "reminders_vibrate_v1"
    const val TAG_NOTICE = "notice:"
    const val EXTRA_TASK = "rm.task"
    private const val GROUP = "com.fastkiwi.rememberme.DUE"
    private const val SUMMARY_ID = 1
    const val TEST_ID = 2
    const val MORNING_ID = 3
    const val UNDO_ID = 5
    /** At most this many single notifications; the rest are counted in the summary and the app. */
    const val MAX_SHOWN = 8
    /** An occurrence posted later than this says so (F-025). */
    const val LATE_NOTE_MS = 5 * 60_000L

    /** The notification id of a reminder's due notification: stable, so the ringing service can use it. */
    fun dueId(taskId: String): Int = 1000 + (taskId.hashCode() and 0x3FFFFFFF)

    fun channels(context: Context) {
        val c = L10n.context(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val sound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        fun ch(id: String, name: Int, desc: Int, importance: Int, block: NotificationChannel.() -> Unit) =
            nm.createNotificationChannel(NotificationChannel(id, c.getString(name), importance).apply { description = c.getString(desc); block() })
        // The app plays the tone itself (F-016): these two are silent and do not vibrate on their own.
        ch(CH_NORMAL, R.string.channel_normal, R.string.channel_normal_desc, NotificationManager.IMPORTANCE_HIGH) { setSound(null, null); enableVibration(false) }
        ch(CH_ALARM, R.string.channel_alarm, R.string.channel_alarm_desc, NotificationManager.IMPORTANCE_HIGH) { setSound(null, null); enableVibration(false) }
        ch(CH_ALERT, R.string.channel_alert, R.string.channel_alert_desc, NotificationManager.IMPORTANCE_HIGH) { setSound(sound, attrs); enableVibration(true) }
        ch(CH_QUIET, R.string.channel_quiet, R.string.channel_quiet_desc, NotificationManager.IMPORTANCE_HIGH) { setSound(null, null); enableVibration(false) }
        ch(CH_VIBRATE, R.string.channel_vibrate, R.string.channel_vibrate_desc, NotificationManager.IMPORTANCE_HIGH) { setSound(null, null); enableVibration(true) }
        ch(CH_NOTICE, R.string.channel_notice, R.string.channel_notice_desc, NotificationManager.IMPORTANCE_DEFAULT) {}
        ch(CH_SUMMARY, R.string.channel_summary, R.string.channel_summary_desc, NotificationManager.IMPORTANCE_LOW) { setSound(null, null) }
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channel(context: Context, id: String) = context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)

    /** True when the due channels exist and the user has not lowered them below a heads-up alert. */
    fun alertChannelOk(context: Context): Boolean = listOf(CH_NORMAL, CH_ALARM).all { (channel(context, it)?.importance ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH }

    /** The user has not silenced the channel of [style] (the app then plays no tone, F-015). */
    fun styleChannelAudible(context: Context, style: AlertPolicy.Style): Boolean =
        (channel(context, if (style == AlertPolicy.Style.ALARM) CH_ALARM else CH_NORMAL)?.importance ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_DEFAULT

    fun alertChannelSound(context: Context): Boolean = styleChannelAudible(context, AlertPolicy.Style.NORMAL)

    fun alertChannelVibrates(context: Context): Boolean = Repo.prefs(context).vibrateDefault

    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** The phone's ringer and Do Not Disturb, as far as the app can read them without special access. */
    fun ringer(context: Context): AlertPolicy.Ringer = when (context.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> AlertPolicy.Ringer.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> AlertPolicy.Ringer.VIBRATE
        else -> AlertPolicy.Ringer.NORMAL
    }

    fun dnd(context: Context): AlertPolicy.Dnd {
        val nm = context.getSystemService(NotificationManager::class.java)
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> AlertPolicy.Dnd.OFF
            NotificationManager.INTERRUPTION_FILTER_NONE -> AlertPolicy.Dnd.NONE
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> AlertPolicy.Dnd.ALARMS_ONLY
            else -> {
                // Reading the policy needs no special access on most versions; when it fails we say "unknown".
                // Android 8.x has no alarms category: priority mode always lets alarms through there.
                if (Build.VERSION.SDK_INT < 28) return AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED
                val policy = runCatching { nm.notificationPolicy }.getOrNull() ?: return AlertPolicy.Dnd.PRIORITY_UNKNOWN
                if (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0) AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED
                else AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED
            }
        }
    }

    private fun fullScreenMode(context: Context) = when (Repo.prefs(context).fullScreenMode) {
        "important" -> AlertPolicy.FullScreenMode.IMPORTANT
        "off" -> AlertPolicy.FullScreenMode.OFF
        else -> AlertPolicy.FullScreenMode.ALWAYS
    }

    /** The decision for a reminder now (also used by Health and the alert test). */
    fun decide(context: Context, t: Task, ext: AlertExt = AlertExt.of(t)): AlertPolicy.Decision {
        val prefs = Repo.prefs(context)
        val style = AlertPolicy.style(t, ext)
        return AlertPolicy.decide(t, ringer(context), dnd(context), Repo.sound(Repo.state(context)), styleChannelAudible(context, style),
            prefs.vibrateDefault, fullScreenMode(context), prefs.pinDefault, ext)
    }

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

    /** The tone sources for a reminder of [style], in fallback order (F-017). */
    fun sources(context: Context, t: Task?, style: AlertPolicy.Style, ext: AlertExt? = t?.let(AlertExt::of)): Tones.Chain {
        val prefs = Repo.prefs(context)
        val def = if (style == AlertPolicy.Style.ALARM) prefs.importantTone else prefs.normalTone
        return Tones.chain(context, ext?.tone, def, style)
    }

    /**
     * Posts or refreshes the notification of a due reminder. [loud]: newly due or a "sound again" moment:
     * it rings (tone and/or vibration as the policy says) and may open the full-screen reminder.
     */
    fun postDue(context: Context, t: Task, loud: Boolean, now: Instant) {
        if (!canPost(context)) return
        val c = L10n.context(context)
        val ext = AlertExt.of(t)
        val d = decide(context, t, ext)
        val alarm = d.style == AlertPolicy.Style.ALARM
        val id = dueId(t.id)
        val log = AlertLog(context)
        val key = log.keyOf(t.id, Codec.iso(t.dueAt!!))
        val open = PendingIntent.getActivity(context, code(t.id, 1), alertIntent(context, t.id), flags())
        val lateMs = now.toEpochMilli() - t.dueAt.toEpochMilli()
        val minutes = lateMs / 60_000
        val status = when {
            lateMs >= LATE_NOTE_MS -> c.getString(R.string.arrived_late, L10n.duration(c, minutes))
            minutes >= 1 -> c.getString(R.string.late_short, L10n.duration(c, minutes))
            else -> c.getString(R.string.due_now)
        }
        // «حان الوقت، 2:00 م، كل يوم», joined with the language's separator (never «·»).
        val text = if (alarm && lateMs < LATE_NOTE_MS) c.getString(R.string.alarm_rings_until, L10n.time(c, t.dueAt))
            else L10n.join(c, listOf(status, L10n.time(c, t.dueAt), L10n.repeatText(c, t)))
        val channel = if (loud) (if (alarm) CH_ALARM else CH_NORMAL) else CH_QUIET
        val done = action(context, ActionReceiver.DONE, t)
        val snooze = action(context, ActionReceiver.SNOOZE, t)
        val until = PendingIntent.getActivity(context, code(t.id, 4),
            alertIntent(context, t.id).putExtra(AlertActivity.EXTRA_PICK, true), flags())
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, t.body.takeIf { it.isNotBlank() }).joinToString("\n")))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(d.pinned)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!loud)
            // Never setSilent on a loud post: Android then suppresses the heads-up and the full screen.
            // The due channels have no sound of their own; the app plays the tone (F-016).
            .setSilent(!loud)
            .setWhen(t.dueAt.toEpochMilli())
            .setShowWhen(true)
            .setGroup(GROUP)
            .setColor(0xFF246A4B.toInt())
            .setContentIntent(open)
            .setDeleteIntent(action(context, ActionReceiver.DISMISSED, t))
            .addAction(R.drawable.ic_check, c.getString(R.string.done), done)
            .addAction(R.drawable.ic_snooze, snoozeLabel(c, now), snooze)
            .addAction(R.drawable.ic_snooze, c.getString(R.string.snooze_until), until)
            .addExtras(android.os.Bundle().apply { putString(EXTRA_TASK, t.id) })
        // Lock screen (F-022): details by default; hidden = «لديك تذكير» with the same buttons. The reminder's
        // own choice wins over the phone setting.
        val hideDetails = ext.hidesOnLockScreen(Repo.prefs(context).lockPrivacy)
        if (hideDetails) {
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            b.setPublicVersion(NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(c.getString(R.string.you_have_reminder)).setContentText(c.getString(R.string.open_to_see))
                .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .addAction(R.drawable.ic_check, c.getString(R.string.done), done)
                .addAction(R.drawable.ic_snooze, snoozeLabel(c, now), snooze)
                .build())
        } else b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        val full = loud && d.fullScreen && canFullScreen(context)
        if (full) { b.setFullScreenIntent(open, true); log.add(AlertLog.Event.FULL_SCREEN, key, style = d.style.name) }
        val n = b.build()
        if (loud && lateMs >= LATE_NOTE_MS) log.add(AlertLog.Event.LATE, key, lateMs, d.style.name)
        // Posted first, so it is on screen even if the ringing service is slow or refused; the service
        // then takes over the same notification id.
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { return }
        if (!loud) return
        log.add(AlertLog.Event.POSTED, key, lateMs, d.style.name)
        if (!d.playTone && !d.vibrate) { log.add(AlertLog.Event.QUIET, key, lateMs, d.style.name, d.quiet.name); return }
        val prefs = Repo.prefs(context)
        val seconds = if (alarm) prefs.ringSeconds else AlertPolicy.NORMAL_MAX_SECONDS
        val chain = sources(context, t, d.style, ext)
        if (chain.fellBack) log.add(AlertLog.Event.FALLBACK, key, code = "tone missing")
        if (d.quiet != AlertPolicy.Quiet.NONE) log.add(AlertLog.Event.QUIET, key, lateMs, d.style.name, d.quiet.name)
        // F-031: read aloud only where a tone may play; locked with «أخفِ التفاصيل» it says «لديك تذكير» only.
        val locked = runCatching { context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(true)
        // F-031: never in silent or vibrate mode, never while Do Not Disturb is on (even when alarms ring).
        val speakAllowed = d.playTone && ringer(context) == AlertPolicy.Ringer.NORMAL && dnd(context) == AlertPolicy.Dnd.OFF
        val speak = if (speakAllowed && (ext.speak ?: prefs.speakDefault))
            (if (locked && hideDetails) c.getString(R.string.you_have_reminder) else t.title) else null
        if (RingService.start(context, id, n, chain.sources.map { it.uri }, alarm, seconds, d.playTone, d.vibrate, prefs.vibrationPattern, key,
                speak, prefs.language)) {
            RingService.ringingId = id
            return
        }
        // The service could not start: the same notification again on a channel that sounds by itself.
        log.add(AlertLog.Event.FALLBACK, key, code = "service")
        val fb = NotificationCompat.Builder(c, n).setChannelId(when { d.playTone -> CH_ALERT; d.vibrate -> CH_VIBRATE; else -> CH_QUIET })
            .setSilent(!d.playTone && !d.vibrate).build()
        try { NotificationManagerCompat.from(context).notify(id, fb) } catch (_: SecurityException) { }
    }

    /** «أجّل 10 دقائق»: says what the button does (the first snooze choice that applies now). */
    fun snoozeLabel(c: Context, now: Instant): String {
        val zone = Repo.zone()
        val preset = Repo.presets(Repo.state(c)).firstOrNull { snoozeTime(it, now, zone) != null }
            ?: return c.getString(R.string.snooze_for, L10n.duration(c, 10))
        return c.getString(R.string.snooze_for, com.fastkiwi.rememberme.ui.presetLabel(c, preset, now, zone))
    }

    /**
     * The «اختبر التنبيه الآن» reminder (F-009): the real path for [style]: its silent channel, the tone a
     * real reminder of that style would use, «تم» and «أجّل», and the full-screen screen.
     */
    fun postTest(context: Context, now: Instant, style: AlertPolicy.Style = AlertPolicy.Style.NORMAL) {
        if (!canPost(context)) return
        val c = L10n.context(context)
        val alarm = style == AlertPolicy.Style.ALARM
        val screen = PendingIntent.getActivity(context, 301, alertIntent(context, null).putExtra(AlertActivity.EXTRA_TEST, true), flags())
        val done = PendingIntent.getBroadcast(context, 302, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.TEST_DONE), flags())
        val b = NotificationCompat.Builder(c, if (alarm) CH_ALARM else CH_NORMAL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(c.getString(R.string.test_title))
            .setContentText(c.getString(R.string.test_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.getString(R.string.test_text)))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(now.toEpochMilli()).setShowWhen(true)
            .setColor(0xFF246A4B.toInt())
            .setAutoCancel(true)
            .setContentIntent(screen)
            .addAction(R.drawable.ic_check, c.getString(R.string.done), done)
            .addAction(R.drawable.ic_snooze, snoozeLabel(c, now),
                PendingIntent.getBroadcast(context, 303, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.TEST_DONE), flags()))
        if (canFullScreen(context) && Repo.prefs(context).fullScreenMode != "off") b.setFullScreenIntent(screen, true)
        val n = b.build()
        try { NotificationManagerCompat.from(context).notify(TEST_ID, n) } catch (_: SecurityException) { return }
        val prefs = Repo.prefs(context)
        val d = AlertPolicy.decide(Task(id = "test", title = "", priority = if (alarm) com.fastkiwi.rememberme.core.Priority.HIGH else com.fastkiwi.rememberme.core.Priority.NORMAL,
            createdAt = now, updatedAt = now), ringer(context), dnd(context), Repo.sound(Repo.state(context)), styleChannelAudible(context, style),
            prefs.vibrateDefault, fullScreenMode(context), prefs.pinDefault)
        val started = (d.playTone || d.vibrate) && RingService.start(context, TEST_ID, n, sources(context, null, style).sources.map { it.uri }, alarm,
            if (alarm) minOf(prefs.ringSeconds, 30) else 10, d.playTone, d.vibrate, prefs.vibrationPattern, "test")
        if (started) RingService.ringingId = TEST_ID
    }

    fun cancelTest(context: Context) {
        if (RingService.ringingId == TEST_ID) RingService.stop(context)
        NotificationManagerCompat.from(context).cancel(TEST_ID)
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
        val b = NotificationCompat.Builder(c, CH_NOTICE)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(t.title)
            .setContentText(c.getString(R.string.coming_up, L10n.dueText(c, t.dueAt!!, now)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(open)
        // Private either way, so the phone's own "hide sensitive content" can hide more (F-022).
        b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (AlertExt.of(t).hidesOnLockScreen(Repo.prefs(context).lockPrivacy))
            b.setPublicVersion(NotificationCompat.Builder(c, CH_NOTICE).setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(c.getString(R.string.you_have_reminder)).build())
        try { NotificationManagerCompat.from(context).notify(TAG_NOTICE + t.id, 0, b.build()) } catch (_: SecurityException) { }
    }

    /** The task id of a due notification, or null for any other. */
    fun dueTask(sbn: StatusBarNotification): String? = sbn.notification.extras?.getString(EXTRA_TASK)?.takeIf { sbn.tag == null }

    /** Ids of the reminders whose due notification is showing now. */
    fun shownDue(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNull(::dueTask).toSet()

    fun cancelDue(context: Context, id: String) {
        val nid = dueId(id)
        if (RingService.ringingId == nid) RingService.stop(context, remove = true)
        NotificationManagerCompat.from(context).cancel(nid)
    }

    /** After «تم» from the notification: a quiet note with «تراجع» that goes away after 10 s (F-052). */
    fun postUndo(context: Context, c: com.fastkiwi.rememberme.core.Completion, title: String, hidden: Boolean = Repo.prefs(context).lockPrivacy) {
        if (!canPost(context)) return
        val l = L10n.context(context)
        val undo = PendingIntent.getBroadcast(context, 500, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.UNDO)
            .putExtra(ActionReceiver.EXTRA_ID, c.id).putExtra(ActionReceiver.EXTRA_COPY, c.copyId)
            .putExtra(ActionReceiver.EXTRA_DONE_AT, c.doneAt?.toEpochMilli() ?: 0L), flags())
        val b = NotificationCompat.Builder(l, CH_QUIET)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(l.getString(R.string.done_undo_title, L10n.isolate(l, title)))
            .setSilent(true).setAutoCancel(true).setTimeoutAfter(10_000)
            .addAction(R.drawable.ic_check, l.getString(R.string.undo), undo)
        b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (hidden) b.setPublicVersion(NotificationCompat.Builder(l, CH_QUIET).setSmallIcon(R.drawable.ic_check)
            .setContentTitle(l.getString(R.string.done)).addAction(R.drawable.ic_check, l.getString(R.string.undo), undo).build())
        try { NotificationManagerCompat.from(context).notify(UNDO_ID, b.build()) } catch (_: SecurityException) { }
    }

    fun cancelUndo(context: Context) = NotificationManagerCompat.from(context).cancel(UNDO_ID)

    /** Ids of the reminders whose early notice is showing now. */
    fun shownNotices(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNull { it.tag?.takeIf { t -> t.startsWith(TAG_NOTICE) }?.removePrefix(TAG_NOTICE) }.toSet()

    fun cancelNotice(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(TAG_NOTICE + id, 0)
}
