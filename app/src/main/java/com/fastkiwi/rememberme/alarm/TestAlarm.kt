package com.fastkiwi.rememberme.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.data.AlertLog
import java.time.Instant
import java.time.ZoneId

/**
 * «اختبر التنبيه الآن» (PRD P0.4): a real alarm fires a test reminder through the same path as a real
 * one (AlarmManager → receiver → notification with actions → full-screen screen), and each part is
 * recorded so the app can say which part did not work. Sound and vibration cannot be detected by an
 * app, so the user is asked. Nothing is added to the reminders.
 */
object TestAlarm {
    const val ACTION = "com.fastkiwi.rememberme.TEST"
    const val DELAY_SECONDS = 10L
    /** A test alarm counts as on time when it fires within this much of its planned moment. */
    const val ON_TIME_MS = 60_000L
    /** The alert-log key of the test's ringing (Notifier.postTest), never a reminder's key. */
    const val LOG_KEY = "test"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("alarm_test", Context.MODE_PRIVATE)

    /** When the test alarm goes off (F-027: the night test). */
    enum class Delay { SECONDS_10, HOUR_1, TOMORROW_7 }

    /** The moment a test started at [now] with [delay] should arrive. */
    fun plannedAt(now: Instant, delay: Delay, zone: ZoneId): Instant = when (delay) {
        Delay.SECONDS_10 -> now.plusSeconds(DELAY_SECONDS)
        Delay.HOUR_1 -> now.plusSeconds(3600)
        Delay.TOMORROW_7 -> now.atZone(zone).toLocalDate().plusDays(1).atTime(7, 0).atZone(zone).toInstant()
    }

    data class Record(
        val startedAt: Long = 0, val plannedAt: Long = 0, val api: String = "",
        val firedAt: Long = 0, val posted: Boolean = false, val fullScreenAllowed: Boolean = false,
        val screenAt: Long = 0, val actionAt: Long = 0,
        /** The user's answers: true heard / felt, false not, null not answered. */
        val sound: Boolean? = null, val vibration: Boolean? = null,
        /** The chosen alert style (AlertPolicy.Style name) and delay (Delay name). */
        val style: String = AlertPolicy.Style.NORMAL.name, val delay: String = Delay.SECONDS_10.name,
        /** At the moment it fired: why no tone was played (AlertPolicy.Quiet name, "" unknown) and whether it vibrated. */
        val quiet: String = "", val vibrate: Boolean? = null,
        /** Objective sub-check of the sound: the player started (true), failed (false), not seen (null). */
        val playback: Boolean? = null,
    ) {
        val alarm get() = style == AlertPolicy.Style.ALARM.name
        /** Measured lateness of the test alarm in ms, or null before it arrived (F-027). */
        val lateMs get() = if (firedAt > 0) (firedAt - plannedAt).coerceAtLeast(0) else null
    }

    fun record(c: Context): Record {
        val p = p(c)
        fun ans(k: String) = if (p.contains(k)) p.getBoolean(k, false) else null
        val firedAt = p.getLong("firedAt", 0)
        return Record(
            p.getLong("startedAt", 0), p.getLong("plannedAt", 0), p.getString("api", "") ?: "",
            firedAt, p.getBoolean("posted", false), p.getBoolean("fsAllowed", false),
            p.getLong("screenAt", 0), p.getLong("actionAt", 0), ans("sound"), ans("vibration"),
            style = p.getString("style", null) ?: AlertPolicy.Style.NORMAL.name,
            delay = p.getString("delay", null) ?: Delay.SECONDS_10.name,
            quiet = p.getString("quiet", "") ?: "", vibrate = ans("vibrate"),
            playback = if (firedAt > 0) playback(runCatching { AlertLog(c).entries() }.getOrDefault(emptyList()), firedAt) else null,
        )
    }

    /** Reads the ringing service's log lines for the test (key [LOG_KEY]) written after it fired. */
    fun playback(entries: List<AlertLog.Entry>, firedAt: Long): Boolean? {
        val mine = entries.filter { it.key == LOG_KEY && it.at >= firedAt - 1_000 }
        return when {
            mine.any { it.event == AlertLog.Event.PLAY_STARTED || (it.event == AlertLog.Event.FALLBACK && it.code.startsWith("source")) } -> true
            mine.any { it.event == AlertLog.Event.FALLBACK } -> false
            else -> null
        }
    }

    private fun op(c: Context) = PendingIntent.getBroadcast(
        c, 300, Intent(c, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Starts a test: clears the last result and sets a real alarm for [delay] from now, as an alarm clock
     * (the API of a due reminder) when exact alarms are allowed. [style] is the alert style to test.
     */
    fun start(
        c: Context, now: Instant = Instant.now(), style: AlertPolicy.Style = AlertPolicy.Style.NORMAL,
        delay: Delay = Delay.SECONDS_10, zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val at = plannedAt(now, delay, zone).toEpochMilli()
        val am = c.getSystemService(AlarmManager::class.java)
        val exact = Engine.canExact(c)
        // The same API as a due reminder (slot A): an alarm clock when exact alarms are allowed.
        var api = if (exact) "CLOCK" else "INEXACT"
        Notifier.cancelTest(c)
        try {
            if (exact) {
                val show = PendingIntent.getActivity(c, 304, Intent(c, com.fastkiwi.rememberme.MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op(c))
            } else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op(c))
        } catch (_: SecurityException) {
            api = "INEXACT"
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op(c))
        }
        p(c).edit {
            clear()
            putLong("startedAt", now.toEpochMilli()); putLong("plannedAt", at); putString("api", api)
            putString("style", style.name); putString("delay", delay.name)
        }
    }

    /** The test alarm arrived: post the test reminder of the chosen style and note what Android allowed. */
    fun onFire(c: Context, now: Instant = Instant.now()) {
        val style = runCatching { AlertPolicy.Style.valueOf(p(c).getString("style", null) ?: "") }.getOrDefault(AlertPolicy.Style.NORMAL)
        // What a real reminder of this style would do now (the same decision Notifier.postTest makes).
        val d = runCatching {
            val prefs = Repo.prefs(c)
            AlertPolicy.decide(
                Task(id = "test", title = "", priority = if (style == AlertPolicy.Style.ALARM) Priority.HIGH else Priority.NORMAL, createdAt = now, updatedAt = now),
                Notifier.ringer(c), Notifier.dnd(c), Repo.sound(Repo.state(c)), Notifier.styleChannelAudible(c, style),
                prefs.vibrateDefault, AlertPolicy.FullScreenMode.ALWAYS, prefs.pinDefault,
            )
        }.getOrNull()
        p(c).edit {
            putLong("firedAt", now.toEpochMilli()); putBoolean("fsAllowed", Notifier.canFullScreen(c))
            if (d != null) { putString("quiet", d.quiet.name); putBoolean("vibrate", d.vibrate) }
        }
        Notifier.postTest(c, now, style)
        val shown = runCatching {
            c.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == Notifier.TEST_ID && it.tag == null }
        }.getOrDefault(false)
        p(c).edit { putBoolean("posted", shown) }
    }

    fun screenShown(c: Context, now: Instant = Instant.now()) = p(c).edit { putLong("screenAt", now.toEpochMilli()) }

    fun actionDone(c: Context, now: Instant = Instant.now()) {
        p(c).edit { putLong("actionAt", now.toEpochMilli()) }
        Notifier.cancelTest(c)
    }

    fun answer(c: Context, sound: Boolean? = null, vibration: Boolean? = null) = p(c).edit {
        sound?.let { putBoolean("sound", it) }
        vibration?.let { putBoolean("vibration", it) }
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(op(c))
        Notifier.cancelTest(c)
        p(c).edit { clear() }
    }

    /** One part of the test and what it showed. */
    enum class Part { ALARM, NOTIFICATION, SOUND, VIBRATION, SCREEN, BUTTONS }
    enum class Result { OK, FAILED, NOT_ALLOWED, WAITING, ASK }

    /**
     * Reads a record as results per part. Pure, so it is unit-tested. [now] decides when a missing
     * step counts as failed rather than still waiting.
     */
    fun evaluate(r: Record, now: Long, channelHigh: Boolean, channelSound: Boolean, channelVibrates: Boolean, dnd: Boolean): Map<Part, Result> {
        if (r.startedAt == 0L) return emptyMap()
        val late = now > r.plannedAt + 2 * ON_TIME_MS
        val fired = r.firedAt > 0
        val alarm = when {
            fired && r.firedAt - r.plannedAt <= ON_TIME_MS -> Result.OK
            fired -> Result.FAILED // arrived, but late
            late -> Result.FAILED
            else -> Result.WAITING
        }
        val notification = when {
            !fired -> if (late) Result.FAILED else Result.WAITING
            r.posted && channelHigh -> Result.OK
            else -> Result.FAILED
        }
        val sound = when {
            !channelSound || dnd -> Result.FAILED
            !fired -> if (late) Result.FAILED else Result.WAITING
            r.sound == null -> Result.ASK
            r.sound -> Result.OK
            else -> Result.FAILED
        }
        val vibration = when {
            !channelVibrates -> Result.FAILED
            !fired -> if (late) Result.FAILED else Result.WAITING
            r.vibration == null -> Result.ASK
            r.vibration -> Result.OK
            else -> Result.FAILED
        }
        val screen = when {
            !fired -> if (late) Result.FAILED else Result.WAITING
            !r.fullScreenAllowed -> Result.NOT_ALLOWED
            r.screenAt > 0 -> Result.OK
            // With the screen on and unlocked, Android shows a heads-up notification instead: not a fault.
            else -> Result.ASK
        }
        val buttons = when {
            r.actionAt > 0 -> Result.OK
            !fired -> if (late) Result.FAILED else Result.WAITING
            else -> Result.ASK
        }
        return linkedMapOf(Part.ALARM to alarm, Part.NOTIFICATION to notification, Part.SOUND to sound,
            Part.VIBRATION to vibration, Part.SCREEN to screen, Part.BUTTONS to buttons)
    }
}
