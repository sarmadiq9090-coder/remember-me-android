package com.fastkiwi.rememberme.torture.blackbox

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.KeyguardManager
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.os.VibratorManager
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.Engine
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

/**
 * One activity window of the app. It is launched with ActivityScenario (which lets the Compose rule see it),
 * then driven through Robolectric's ActivityController: ActivityScenario's own waits use Robolectric's
 * frozen clock and never end once a window has been stopped and resumed.
 */
class Win(val scenario: ActivityScenario<Activity>, val info: ActivityInfo, val launcher: Boolean, val ctrl: ActivityController<*>?) {
    var state: Lifecycle.State = Lifecycle.State.RESUMED
    /** The phone's settings changed while this window was in the background: Android recreates it when it returns. */
    var stale = false
    val activity: Activity?
        get() {
            ctrl?.let { return it.get() }
            var a: Activity? = null
            runCatching { scenario.onActivity { a = it } }
            return a
        }
    val name: String get() = info.name.substringAfterLast('.').removeSuffix("Activity")
    val showWhenLocked: Boolean
        get() {
            val flag = runCatching { ActivityInfo::class.java.getField("FLAG_SHOW_WHEN_LOCKED").getInt(null) }.getOrDefault(0x800000)
            if (info.flags and flag != 0) return true
            @Suppress("DEPRECATION")
            return activity?.window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) ?: 0 != 0
        }
}

/** A task: the app's windows with one affinity, plus an Android screen shown on top of them, if any. */
class AppTask(val affinity: String) {
    val wins = mutableListOf<Win>()
    var overlay: Overlay? = null
}

/** A screen that belongs to Android, not to the app (drawn by the driver). */
class Overlay(val kind: Kind, val title: String, val lines: List<String>, val buttons: List<String>, val transparent: Boolean) {
    enum class Kind { PERMISSION, BATTERY, OPEN_FILE, SAVE_FILE, SHARE, SETTINGS, OTHER }
    var requestCode: Int = -1
    var owner: Win? = null
    var permissions: Array<String> = emptyArray()
    var suggestedName: String = "file"
    var intent: Intent? = null
}

object Home

class Driver(val rule: ComposeTestRule, val session: File) {
    companion object {
        const val START_QUALIFIERS = "ar-w412dp-h915dp-notnight-xhdpi"
        const val MAX_WAIT = 120
        /** How much phone time an ordinary action takes at most (a person's pace). */
        const val STEP_MS = 2000L
        val SETTING_VERBS = setOf("start", "font-scale", "dark", "language", "ringer", "dnd", "deny-notifications", "allow-notifications",
            "timezone", "lockscreen-notifications", "exact-alarms", "full-screen-permission", "on-error")
        const val DOCS = "com.android.externalstorage.documents"
    }

    val app: Application = ApplicationProvider.getApplicationContext()
    val out = File(session, "out")
    val report = mutableListOf<String>()
    val zoneDefault = "Asia/Baghdad"

    // ---- the phone ----
    var sysLang = "ar"
    var night = false
    var fontScale = 1f
    var locked = false
    var lockMode = "all"
    var dndMode = "off"
    var notifDenials = 0
    var stopOnError = false
    var shadeOpen = false
    var appliedLocale: Locale? = null
    val layers = mutableListOf<Any>(Home)
    var pendingAfterUnlock: PendingIntent? = null
    var unlockRequested = false

    // ---- what happened during the current step ----
    val events = mutableListOf<String>()
    val headsUps = mutableListOf<StatusBarNotification>()
    val toasts = mutableListOf<String>()
    var crash: String? = null
    private var stepNo = 0
    private var failures = 0
    private var lastPass = Instant.now()

    // ---- bookkeeping ----
    val services = mutableMapOf<String, ServiceController<out Service>>()
    private var startIds = 0
    val players = mutableListOf<Pair<MediaPlayer, ShadowMediaPlayer>>()
    val known = mutableMapOf<String, Notification>()
    val postedAt = mutableMapOf<String, Instant>()
    private val seenRequests = IdentityHashMap<Any, Boolean>()
    private var toastCount = 0
    private val bgErrors = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
    var lastSound: String? = null

    val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    val am: AlarmManager get() = app.getSystemService(AlarmManager::class.java)
    val km: KeyguardManager get() = app.getSystemService(KeyguardManager::class.java)
    val audio: AudioManager get() = app.getSystemService(AudioManager::class.java)
    val density: Float get() = app.resources.displayMetrics.density

    fun tasks(): List<AppTask> = layers.filterIsInstance<AppTask>()
    fun frontLayer(): Any = layers.last()
    fun frontTask(): AppTask? = frontLayer() as? AppTask
    fun frontWin(): Win? = frontTask()?.wins?.lastOrNull()
    /** The lock screen hides everything unless the window in front may show over it. */
    fun lockScreenShowing(): Boolean = locked && !(frontTask()?.overlay == null && frontWin()?.showWhenLocked == true)

    // =====================================================================================
    // The run
    // =====================================================================================

    fun run() {
        out.deleteRecursively(); out.mkdirs()
        startWatchdog()
        setUpPhone()
        val (lines, problems) = Script.parse(File(session, "script.txt").readText(Charsets.UTF_8))
        report += "Black-box session: ${session.absolutePath}"
        report += "Started: ${stamp()}"
        report += "Script: ${lines.size} lines"
        problems.forEach { report += "SCRIPT PROBLEM $it (line skipped)" }
        report += ""
        var i = 0
        var startAtHome = false
        // Phone settings at the top apply before the app is opened.
        while (i < lines.size && lines[i].verb in SETTING_VERBS) {
            val l = lines[i]
            val err = try { if (l.verb == "start") startAtHome = start(l) || startAtHome else perform(l); null } catch (e: ActionError) { e.message }
            report += "[before opening] line ${l.no}: ${l.raw} — " + (err?.let { "COULD NOT BE DONE: $it" } ?: "done")
            i++
        }
        if (!startAtHome) step(null, "open-app") { openApp() }
        while (i < lines.size && crash == null) {
            val l = lines[i++]
            val ok = step(l, l.slug) { perform(l) }
            if (!ok && stopOnError) { report += "Stopped at the first problem (on-error stop)."; break }
        }
        report += ""
        report += "Finished: ${stamp()} — ${stepNo} pictures, $failures line(s) could not be done" + (crash?.let { ", THE APP CRASHED" } ?: "")
        File(out, "report.txt").writeText(report.joinToString("\n") + "\n", Charsets.UTF_8)
        tearDown()
    }

    private fun writeReport() = File(out, "report.txt").writeText(report.joinToString("\n") + "\n", Charsets.UTF_8)

    private fun stamp() = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Instant.now().atZone(ZoneId.systemDefault()))

    private fun setUpPhone() {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneDefault))
        runCatching { Engine.drain() }
        Repo.resetForTests()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> bgErrors += t.name to e }
        FullScreenShadow.allowed = true
        ShadowAlarmManager.setAutoSchedule(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(nm).setNotificationsEnabled(false)
        shadowOf(km).setIsKeyguardSecure(true)
        shadowOf(km).setIsDeviceSecure(true)
        shadowOf(km).setKeyguardLocked(false)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(4000, 0) }
        ShadowMediaPlayer.setCreateListener { p, s -> players += p to s }
        appliedLocale = Locale.forLanguageTag(sysLang)
        lastPass = Instant.now()
    }

    private fun tearDown() {
        for (t in tasks()) t.wins.toList().forEach { w -> runCatching { destroyWin(w) } }
        services.values.forEach { runCatching { it.destroy() } }
        runCatching { Engine.drain() }
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    /** Returns false when the line could not be done. */
    @Volatile private var stepDeadline = Long.MAX_VALUE
    @Volatile private var stepLabel = ""

    /** If one line takes far too long, the driver stops the run with a clear message instead of hanging. */
    private fun startWatchdog() {
        Thread({
            while (true) {
                Thread.sleep(5000)
                if (Instant.now().toEpochMilli() > stepDeadline) {
                    report += "THE DRIVER GOT STUCK on: $stepLabel — the run was stopped (this is a driver problem, not necessarily the app's)."
                    runCatching { writeReport() }
                    Runtime.getRuntime().halt(3)
                }
            }
        }, "blackbox-watchdog").apply { isDaemon = true }.start()
    }

    private fun step(line: Line?, slug: String, block: () -> Unit): Boolean {
        val label = line?.raw ?: "open-app (the app is opened from its icon)"
        stepLabel = label
        val waitS = if (line?.verb == "wait") (line.args.firstOrNull()?.toIntOrNull() ?: 0).coerceIn(0, MAX_WAIT) else 0
        stepDeadline = Instant.now().toEpochMilli() + (waitS + 240) * 1000L
        events.clear(); headsUps.clear(); toasts.clear()
        if (line?.verb != "notifications") shadeOpen = false
        passTime(STEP_MS)
        var error: String? = null
        try {
            block()
            settle()
        } catch (e: ActionError) {
            error = e.message
            runCatching { settle() }.onFailure { crashed(it) }
        } catch (e: Throwable) {
            crashed(e)
        }
        checkBackgroundErrors()
        val n = "%02d".format(Locale.ROOT, stepNo++)
        val name = "$n-$slug"
        val result = when {
            crash != null -> "THE APP CRASHED: $crash"
            error != null -> "COULD NOT BE DONE: $error"
            else -> "done"
        }
        if (error != null) failures++
        report += "$n ${label} — $result"
        try { capture(name, label, result) } catch (e: Throwable) { report += "   (the driver could not take the picture: ${e.javaClass.simpleName}: ${e.message})" }
        writeReport()
        stepDeadline = Long.MAX_VALUE
        return error == null
    }

    private fun crashed(e: Throwable) {
        if (e is ActionError) return
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val where = e.stackTrace.firstOrNull { it.className.startsWith(app.packageName) && !it.className.contains(".torture.") }
        crash = "${e.javaClass.simpleName}: ${e.message?.take(300)}" + (where?.let { " (in ${it.className.substringAfterLast('.')}.${it.methodName}, line ${it.lineNumber})" } ?: "")
        File(out, "crash-details.txt").appendText(sw.toString() + "\n", Charsets.UTF_8)
        events += "Android would now say that the app has stopped (crash)."
    }

    private fun checkBackgroundErrors() {
        val errs = synchronized(bgErrors) { bgErrors.toList().also { bgErrors.clear() } }
        if (errs.isNotEmpty() && crash == null) crashed(errs.first().second)
    }

    // =====================================================================================
    // Time, idling, and what Android does by itself
    // =====================================================================================

    /** Lets phone time pass: the screen's clock and Android's timers, at most [capMs] (real time is the limit). */
    fun passTime(capMs: Long) {
        val now = Instant.now()
        val ms = Duration.between(lastPass, now).toMillis().coerceIn(0, capMs)
        lastPass = now
        if (ms <= 0) return
        rule.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun idleCompose() {
        try { rule.waitForIdle() } catch (e: IllegalStateException) {
            if (e.message?.contains("No compose hierarchies", true) != true && e.message?.contains("no compose", true) != true) throw e
        }
    }

    /** Lets the app and Android finish what the last action started. */
    fun settle() {
        Thread.sleep(120)
        var quiet = 0
        for (round in 0 until 25) {
            shadowOf(Looper.getMainLooper()).idle()
            idleCompose()
            Engine.drain()
            val before = runCatching { Repo.store(app).state.value }.getOrNull()
            var changed = false
            changed = fireDueAlarms() or changed
            changed = processServices() or changed
            changed = processPermissionRequests() or changed
            changed = processStartedActivities() or changed
            changed = processFinished() or changed
            changed = processNotifications() or changed
            changed = processLocale() or changed
            processToasts()
            syncLifecycle()
            shadowOf(Looper.getMainLooper()).idle()
            checkBackgroundErrors()
            if (crash != null) return
            Thread.sleep(50)
            if (runCatching { Repo.store(app).state.value }.getOrNull() !== before) changed = true
            if (changed) quiet = 0 else quiet++
            if (quiet >= 3) break
        }
        idleCompose()
    }

    /** Delivers every alarm the app set that has come due (wall-clock time for RTC alarms). */
    private fun fireDueAlarms(): Boolean {
        val sam = shadowOf(am)
        var fired = false
        for (a in sam.scheduledAlarms.sortedBy { it.triggerAtTime }) {
            val due = when (a.type) {
                AlarmManager.RTC, AlarmManager.RTC_WAKEUP -> a.triggerAtTime <= Instant.now().toEpochMilli()
                else -> a.triggerAtTime <= SystemClock.elapsedRealtime()
            }
            if (due && sam.scheduledAlarms.contains(a)) {
                val op = a.operation
                debug("alarm due: type=${a.type} op=${op?.let { shadowOf(it).savedIntent }} receivers=${op?.let { shadowOf(app).getReceiversForIntent(shadowOf(it).savedIntent).size }}")
                if (op != null && shadowOf(op).isBroadcast) { am.cancel(op); deliverBroadcast(Intent(shadowOf(op).savedIntent)) } else sam.fireAlarm(a)
                fired = true
            }
        }
        if (fired) shadowOf(Looper.getMainLooper()).idle()
        return fired
    }

    fun debug(s: String) { runCatching { File(out, "driver-debug.txt").appendText(s + "\n", Charsets.UTF_8) } }

    /** Delivers a broadcast to the app's receivers as Android would (also receivers declared without an intent filter). */
    fun deliverBroadcast(i: Intent) {
        if (shadowOf(app).hasReceiverForIntent(i)) { app.sendBroadcast(i); shadowOf(Looper.getMainLooper()).idle(); return }
        val targets = i.component?.let { listOf(it.className) } ?: app.packageManager.queryBroadcastReceivers(i, 0)
            .filter { it.activityInfo.packageName == app.packageName }.map { it.activityInfo.name }
        debug("manual broadcast ${i.action} -> $targets")
        for (cls in targets) {
            val r = Class.forName(cls).getDeclaredConstructor().newInstance() as android.content.BroadcastReceiver
            pendingResultFor()?.let { pr -> runCatching { android.content.BroadcastReceiver::class.java.getMethod("setPendingResult", android.content.BroadcastReceiver.PendingResult::class.java).invoke(r, pr) } }
            r.onReceive(app, i)
        }
    }

    private fun pendingResultFor(): android.content.BroadcastReceiver.PendingResult? = runCatching {
        val c = android.content.BroadcastReceiver.PendingResult::class.java.declaredConstructors.maxByOrNull { it.parameterCount }!!
        c.isAccessible = true
        val args = c.parameterTypes.mapIndexed { idx, t ->
            when (t) {
                Int::class.javaPrimitiveType -> if (idx == 3) 2 /* TYPE_UNREGISTERED: finish() needs no system call */ else 0
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        c.newInstance(*args.toTypedArray()) as android.content.BroadcastReceiver.PendingResult
    }.getOrNull()

    /** What happens when a person taps something that holds a PendingIntent. */
    fun sendPending(pi: PendingIntent) {
        val s = shadowOf(pi)
        if (s.isBroadcast) deliverBroadcast(Intent(s.savedIntent)) else pi.send()
    }

    private fun processServices(): Boolean {
        var changed = false
        val sa = shadowOf(app)
        while (true) {
            val i = sa.nextStartedService ?: break
            changed = true
            val cn = i.component ?: app.packageManager.resolveService(i, 0)?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
            if (cn == null || cn.packageName != app.packageName) continue
            val before = players.size
            val ctrl = services[cn.className] ?: run {
                @Suppress("UNCHECKED_CAST")
                val cls = Class.forName(cn.className) as Class<Service>
                Robolectric.buildService(cls, i).create().also { services[cn.className] = it }
            }
            ctrl.withIntent(i).startCommand(0, ++startIds)
            shadowOf(Looper.getMainLooper()).idle()
            describeSoundStart(i, players.drop(before))
        }
        while (true) {
            val i = sa.nextStoppedService ?: break
            i.component?.className?.let { services.remove(it) }?.let { runCatching { it.destroy() }; changed = true }
        }
        for ((k, c) in services.toList()) {
            val s = c.get()
            if (shadowOf(s).isStoppedBySelf) {
                runCatching { c.destroy() }
                services.remove(k)
                changed = true
                if (lastSound != null) { events += "The app's ringing stopped."; lastSound = null }
            }
        }
        return changed
    }

    private fun describeSoundStart(i: Intent, newPlayers: List<Pair<MediaPlayer, ShadowMediaPlayer>>) {
        if (!i.hasExtra("play") && !i.hasExtra("vibrate") && newPlayers.isEmpty()) { events += "The app started a background service."; return }
        val play = i.getBooleanExtra("play", newPlayers.isNotEmpty())
        val vibrate = i.getBooleanExtra("vibrate", false)
        val alarm = i.getStringExtra("style") == "alarm"
        val seconds = i.getIntExtra("seconds", 0)
        val speak = i.getStringExtra("speak")
        val pattern = i.getStringExtra("pattern")
        val started = newPlayers.firstOrNull { it.second.state == ShadowMediaPlayer.State.STARTED }
        val parts = mutableListOf<String>()
        if (play) {
            val src = started?.second?.sourceUri?.toString() ?: started?.second?.dataSource?.toString() ?: i.getStringArrayExtra("sources")?.firstOrNull()
            val usage = started?.second?.audioAttributes?.usage
            val loop = started?.first?.isLooping == true
            parts += if (started != null) "SOUND WOULD PLAY: yes — ${toneName(src)}, " + (if (loop) "repeating" else "played once") +
                (if (seconds > 0) ", stops by itself after at most $seconds s" else "") + androidSays(usage)
            else "SOUND WOULD PLAY: the app asked for a tone (${toneName(i.getStringArrayExtra("sources")?.firstOrNull())}) but could not start it"
        } else parts += "SOUND WOULD PLAY: no (the app chose not to play a tone)"
        parts += if (vibrate) "VIBRATION: yes" + (pattern?.let { " (pattern «$it»)" } ?: "") + (if (alarm) ", repeating" else "") else "VIBRATION: no"
        if (!speak.isNullOrBlank()) parts += "READ ALOUD (text-to-speech): «$speak»"
        lastSound = parts.joinToString("; ")
        events += "The app started ringing (" + (if (alarm) "important-alarm style" else "normal style") + "): " + lastSound
    }

    fun toneName(src: String?): String = when {
        src == null -> "unknown tone"
        src.contains("alarm_alert") -> "the phone's default alarm sound"
        src.contains("notification_sound") -> "the phone's default notification sound"
        src.contains("ringtone") -> "the phone's default ringtone"
        src.startsWith("android.resource://") -> "a sound built into the app («${src.substringAfterLast('/').let { last ->
            last.toIntOrNull()?.let { id -> runCatching { app.resources.getResourceEntryName(id) }.getOrNull() } ?: last }}»)"
        src.startsWith("/") || src.startsWith("file:") -> "a sound file («${src.substringAfterLast('/')}»)"
        src.startsWith("content://") -> "a sound chosen from the phone («${Uri.decode(src.substringAfterLast('/'))}»)"
        else -> src
    }

    private fun androidSays(usage: Int?): String {
        val ringerSilent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        return when (usage) {
            AudioAttributes.USAGE_ALARM -> when (dndMode) {
                "total" -> " (alarm sound — but Do Not Disturb «total silence» would block it)"
                else -> " (alarm sound: plays at alarm volume, also when the ringer is silent or on vibrate)"
            }
            AudioAttributes.USAGE_NOTIFICATION_EVENT, AudioAttributes.USAGE_NOTIFICATION -> when {
                dndMode != "off" -> " (notification sound — Do Not Disturb is on, so Android would most likely mute it)"
                ringerSilent -> " (notification sound — the ringer is ${ringerName()}, so Android would mute it)"
                else -> " (notification sound, at notification volume)"
            }
            else -> ""
        }
    }

    fun ringerName() = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }

    /** Sound playing right now, in words, or null. */
    fun soundNow(): String? {
        val playing = players.filter { runCatching { it.second.state == ShadowMediaPlayer.State.STARTED }.getOrDefault(false) }
        val vib = runCatching { shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator).isVibrating }.getOrDefault(false)
        if (playing.isEmpty() && !vib) return null
        val p = playing.firstOrNull()
        return listOfNotNull(
            p?.let { "a tone is playing: ${toneName(it.second.sourceUri?.toString())}" + (if (it.first.isLooping) ", repeating" else "") },
            if (vib) "the phone is vibrating" else null,
        ).joinToString("; ")
    }

    private fun processPermissionRequests(): Boolean {
        var changed = false
        for (t in tasks()) for (w in t.wins) {
            val a = w.activity ?: continue
            val req = shadowOf(a).lastRequestedPermission ?: continue
            if (seenRequests.containsKey(req)) continue
            seenRequests[req] = true
            changed = true
            val perms = req.requestedPermissions
            val granted = perms.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            when {
                granted -> deliverPermission(w, req.requestCode, perms, true)
                Manifest.permission.POST_NOTIFICATIONS in perms && notifDenials >= 2 -> {
                    events += "Android did not show its question again (notifications were refused twice before); the app got «refused»."
                    deliverPermission(w, req.requestCode, perms, false)
                }
                else -> askPermission(t, w, req.requestCode, perms)
            }
        }
        return changed
    }

    private fun askPermission(t: AppTask, w: Win?, code: Int, perms: Array<String>) {
        if (t.overlay?.kind == Overlay.Kind.PERMISSION) return
        val label = appLabel()
        val ar = sysLang == "ar"
        val what = if (Manifest.permission.POST_NOTIFICATIONS in perms) (if (ar) "بإرسال إشعارات إليك" else "to send you notifications")
            else perms.joinToString { it.substringAfterLast('.') }
        val o = Overlay(Overlay.Kind.PERMISSION,
            if (ar) "هل تريد السماح لتطبيق «$label» $what؟" else "Allow $label $what?", emptyList(),
            if (ar) listOf("السماح", "عدم السماح") else listOf("Allow", "Don't allow"), transparent = true)
        o.requestCode = code; o.owner = w; o.permissions = perms
        showOverlay(t, o)
        events += "Android asks: ${o.title}"
    }

    private fun permissionIntent(i: Intent, code: Int): Boolean {
        if (i.action != "android.content.pm.action.REQUEST_PERMISSIONS") return false
        val t = frontTask() ?: tasks().lastOrNull() ?: return true
        val perms = i.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES") ?: return true
        // The request object recorded for the window is the same question: do not ask twice.
        frontWin()?.activity?.let { a -> shadowOf(a).lastRequestedPermission?.let { seenRequests[it] = true } }
        when {
            perms.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } -> deliverPermission(frontWin(), code, perms, true)
            Manifest.permission.POST_NOTIFICATIONS in perms && notifDenials >= 2 -> {
                events += "Android did not show its question again (notifications were refused twice before); the app got «refused»."
                deliverPermission(frontWin(), code, perms, false)
            }
            else -> askPermission(t, frontWin(), code, perms)
        }
        return true
    }

    fun appLabel(): String = app.packageManager.getApplicationLabel(app.applicationInfo).toString()

    private fun deliverPermission(w: Win?, code: Int, perms: Array<String>, granted: Boolean) {
        if (granted) setNotificationsAllowed(true)
        val a = (w ?: frontWin())?.activity ?: return
        @Suppress("DEPRECATION")
        a.onRequestPermissionsResult(code, perms, IntArray(perms.size) { if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED })
    }

    fun setNotificationsAllowed(on: Boolean) {
        if (on) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(nm).setNotificationsEnabled(on)
        if (!on) { nm.cancelAll(); known.clear() }
    }

    private fun processStartedActivities(): Boolean {
        val sa = shadowOf(app)
        val forResult = mutableListOf<ShadowActivity.IntentForResult>()
        while (true) forResult += (sa.nextStartedActivityForResult ?: break)
        val plain = mutableListOf<Intent>()
        while (true) plain += (sa.nextStartedActivity ?: break)
        for (r in forResult) plain.removeAll { it === r.intent || it.toUri(0) == r.intent.toUri(0) }
        for (r in forResult) startForResult(r)
        for (i in plain) startActivity(i)
        return forResult.isNotEmpty() || plain.isNotEmpty()
    }

    private fun startForResult(r: ShadowActivity.IntentForResult) {
        val i = r.intent
        if (permissionIntent(i, r.requestCode)) return
        val owner = frontWin()
        val task = frontTask() ?: tasks().lastOrNull() ?: return
        val ar = sysLang == "ar"
        val o = when (i.action) {
            Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT -> {
                val files = fixtureFiles().map { it.name }
                Overlay(Overlay.Kind.OPEN_FILE, if (ar) "اختر ملفًا" else "Choose a file",
                    listOf(if (ar) "الملفات المتاحة:" else "Files you can pick:") + files.map { "• $it" } +
                        listOf("(pick-file <name>)", "type: " + (i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.joinToString() ?: i.type ?: "*/*")),
                    emptyList(), transparent = false)
            }
            Intent.ACTION_CREATE_DOCUMENT -> {
                val name = i.getStringExtra(Intent.EXTRA_TITLE) ?: "file"
                Overlay(Overlay.Kind.SAVE_FILE, if (ar) "حفظ في «التنزيلات»" else "Save to Downloads",
                    listOf((if (ar) "اسم الملف: " else "File name: ") + name, "type: ${i.type}"),
                    listOf(if (ar) "حفظ" else "Save"), transparent = false).also { it.suggestedName = name }
            }
            else -> {
                if (isOurs(i)) { startActivity(i); return }
                otherScreen(i)
            }
        }
        o.requestCode = r.requestCode; o.owner = owner; o.intent = i
        showOverlay(task, o)
        events += "Android opened its own screen: ${o.title}"
    }

    private fun isOurs(i: Intent): Boolean = resolve(i)?.packageName == app.packageName

    private fun resolve(i: Intent): ComponentName? = i.component
        ?: app.packageManager.resolveActivity(i, 0)?.activityInfo?.let { ComponentName(it.packageName, it.name) }

    fun startActivity(i: Intent) {
        if (permissionIntent(i, -1)) return
        val cn = resolve(i)
        if (cn != null && cn.packageName == app.packageName) { startAppActivity(i, cn); return }
        val task = frontTask() ?: tasks().lastOrNull()
        if (task == null) { events += "The app tried to open another screen (${i.action}) but nothing was in front."; return }
        val ar = sysLang == "ar"
        val o = when (i.action) {
            Intent.ACTION_CHOOSER -> {
                val inner = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                val text = inner?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Overlay(Overlay.Kind.SHARE, (if (ar) "مشاركة: " else "Share: ") + (i.getStringExtra(Intent.EXTRA_TITLE) ?: ""),
                    listOf(if (ar) "النص الذي سيُشارك:" else "Text to be shared:") + text.lines(), emptyList(), transparent = false).also {
                    File(out, "files").mkdirs(); File(out, "files/shared-text-${stepNo}.txt").writeText(text, Charsets.UTF_8)
                }
            }
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> Overlay(Overlay.Kind.BATTERY,
                if (ar) "هل تريد السماح لتطبيق «${appLabel()}» بالعمل دائمًا في الخلفية؟" else "Let ${appLabel()} always run in background?",
                listOf(if (ar) "قد يؤدي ذلك إلى استهلاك أكبر للبطارية." else "This may reduce battery life."),
                if (ar) listOf("السماح", "رفض") else listOf("Allow", "Deny"), transparent = true)
            else -> otherScreen(i)
        }
        o.intent = i
        o.owner = frontWin()
        showOverlay(task, o)
        events += "The app opened an Android screen: ${o.title}"
    }

    private fun otherScreen(i: Intent): Overlay {
        val ar = sysLang == "ar"
        val label = appLabel()
        val (title, line) = when (i.action) {
            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS -> "Android Settings › Apps › $label › Notifications" to "Use allow-notifications / deny-notifications here, then back."
            android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS -> {
                val ch = i.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID)?.let { nm.getNotificationChannel(it)?.name }
                "Android Settings › $label › Notifications › «$ch»" to "Use: channel \"$ch\" on|silent|off, then back."
            }
            "android.settings.REQUEST_SCHEDULE_EXACT_ALARM" -> "Android Settings › Alarms & reminders › $label" to "Use exact-alarms on|off, then back."
            "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT" -> "Android Settings › Full-screen notifications › $label" to "The driver always allows full-screen notifications. Use back."
            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> "Android Settings › Battery optimisation" to "Use back."
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> "Android Settings › Apps › App info › $label" to "Use back."
            android.provider.Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "android.settings.ZEN_MODE_SETTINGS", android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS ->
                "Android Settings › Do Not Disturb" to "Use dnd on|off|total|alarms, then back."
            Intent.ACTION_VIEW -> "Another app would open: ${i.dataString}" to "This phone has no other apps. Use back."
            else -> "Another screen (${i.action ?: i.component?.className})" to "The driver cannot show screens of other apps. Use back."
        }
        return Overlay(Overlay.Kind.SETTINGS, title, listOf(line, if (ar) "(شاشة من أندرويد، ليست من التطبيق)" else "(an Android screen, not part of the app)"), emptyList(), transparent = false)
    }

    fun showOverlay(task: AppTask, o: Overlay) {
        task.overlay = o
        bringToFront(task)
    }

    fun bringToFront(layer: Any) {
        layers.remove(layer); layers += layer
    }

    /** Launches one of the app's windows the way Android would for this intent. */
    private fun startAppActivity(intent: Intent, cn: ComponentName) {
        val info = app.packageManager.getActivityInfo(cn, 0)
        val affinity = info.taskAffinity ?: app.packageName
        val task = tasks().firstOrNull { it.affinity == affinity } ?: AppTask(affinity).also { layers += it }
        val flags = intent.flags
        val existing = task.wins.lastOrNull { it.info.name == info.name }
        val top = task.wins.lastOrNull()
        val singleTask = info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK || info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
        val clearTop = flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0
        val singleTop = info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP || flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0
        val launcherIntent = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        bringToFront(task)
        when {
            existing != null && launcherIntent -> Unit
            existing != null && (singleTask || clearTop) -> {
                while (task.wins.last() !== existing) closeWin(task, task.wins.last())
                newIntent(existing, intent)
            }
            existing != null && existing === top && singleTop -> newIntent(existing, intent)
            else -> launchWin(task, Intent(intent).setComponent(cn), info)
        }
        syncLifecycle()
    }

    private fun newIntent(w: Win, intent: Intent) {
        val c = w.ctrl
        if (c != null) { c.newIntent(intent); return }
        w.scenario.onActivity { a -> InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(a, intent) }
    }

    private fun launchWin(task: AppTask, intent: Intent, info: ActivityInfo) {
        // Android pauses what was in front before the new window starts.
        for (t in tasks()) for (w in t.wins) if (w.state == Lifecycle.State.RESUMED) moveTo(w, Lifecycle.State.STARTED)
        val launcher = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.className == info.name
        @Suppress("UNCHECKED_CAST")
        val sc = ActivityScenario.launch<Activity>(intent)
        var a: Activity? = null
        sc.onActivity { a = it }
        val ctrl = a?.let { act -> runCatching {
            val f = ShadowActivity::class.java.getDeclaredField("controller"); f.isAccessible = true
            f.get(shadowOf(act)) as ActivityController<*>?
        }.getOrNull() }
        if (ctrl == null) debug("no controller for ${info.name}: using ActivityScenario")
        task.wins += Win(sc, info, launcher, ctrl)
        // Our own launch is not something the app asked for: forget it.
        val sa = shadowOf(app)
        val keep = mutableListOf<Intent>()
        while (true) { val i = sa.nextStartedActivity ?: break; if (i.component?.className != info.name || i.toUri(0) != intent.toUri(0)) keep += i }
        // Put back what the app itself started meanwhile (processed next round).
        keep.forEach { app.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun closeWin(task: AppTask, w: Win) {
        runCatching { destroyWin(w) }.onFailure { debug("close failed: $it") }
        task.wins.remove(w)
        if (task.wins.isEmpty()) { layers.remove(task) }
    }

    private fun processFinished(): Boolean {
        var changed = false
        for (t in tasks().toList()) for (w in t.wins.toList()) {
            val a = w.activity
            val gone = a == null || a.isFinishing || a.isDestroyed || (w.ctrl == null && runCatching { w.scenario.state == Lifecycle.State.DESTROYED }.getOrDefault(true))
            if (gone) {
                closeWin(t, w); changed = true
                if (t.wins.isEmpty() && t.overlay != null) t.overlay = null
            }
        }
        return changed
    }

    private fun rank(s: Lifecycle.State) = when (s) { Lifecycle.State.RESUMED -> 3; Lifecycle.State.STARTED -> 2; else -> 1 }

    private fun moveTo(w: Win, s: Lifecycle.State) {
        if (w.state == s) return
        val c = w.ctrl
        try {
            if (c == null) w.scenario.moveToState(s)
            else {
                val from = rank(w.state); val to = rank(s)
                if (to < from) {
                    if (from == 3) { c.pause(); c.topActivityResumed(false) }
                    if (to == 1) c.stop()
                } else {
                    if (from == 1) c.restart()
                    if (to == 3) { c.resume(); c.postResume(); c.topActivityResumed(true) }
                }
            }
        } catch (e: Exception) { debug("moving a window to $s failed: $e") }
        w.state = s
        if (s == Lifecycle.State.RESUMED && w.stale) { w.stale = false; recreateWin(w) }
    }

    /** Android hands the new configuration to the window; one that does not handle it itself is recreated. */
    private fun recreateWin(w: Win) {
        val c = w.ctrl
        runCatching { if (c != null) c.configurationChange(android.content.res.Configuration(app.resources.configuration)) else w.scenario.recreate() }
            .onFailure { debug("configuration change failed: $it") }
    }

    private fun destroyWin(w: Win) {
        val c = w.ctrl ?: run { w.scenario.close(); return }
        val a = c.get()
        if (a.isDestroyed) return
        if (w.state == Lifecycle.State.RESUMED) c.pause()
        if (w.state != Lifecycle.State.CREATED) c.stop()
        c.destroy()
    }

    /** The window in front is resumed; one under an Android dialog is paused; everything else is stopped. */
    fun syncLifecycle() {
        val front = frontTask()
        for (t in tasks()) for ((i, w) in t.wins.withIndex()) {
            val isTop = t === front && i == t.wins.lastIndex
            val desired = when {
                !isTop -> Lifecycle.State.CREATED
                locked && !w.showWhenLocked -> Lifecycle.State.CREATED
                t.overlay?.transparent == true -> Lifecycle.State.STARTED
                t.overlay != null -> Lifecycle.State.CREATED
                else -> Lifecycle.State.RESUMED
            }
            moveTo(w, desired)
        }
    }

    private fun processNotifications(): Boolean {
        var changed = false
        val shadow = shadowOf(nm)
        val active = nm.activeNotifications.filter { it.packageName == app.packageName || it.packageName == null }
        val keys = mutableSetOf<String>()
        for (sbn in active) {
            val key = "${sbn.tag}|${sbn.id}"
            keys += key
            val n = (if (sbn.tag == null) shadow.getNotification(sbn.id) else shadow.getNotification(sbn.tag, sbn.id)) ?: sbn.notification
            val prev = known[key]
            if (prev === n) continue
            changed = true
            known[key] = n
            postedAt[key] = Instant.now()
            val alerting = !silentByGroup(n) && !(prev != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            if (!alerting) continue
            val ch = n.channelId?.let { nm.getNotificationChannel(it) }
            val importance = ch?.importance ?: NotificationManager.IMPORTANCE_DEFAULT
            if (importance < NotificationManager.IMPORTANCE_DEFAULT) continue
            if (ch?.sound != null) events += "Android plays the phone's notification sound for «${title(n)}» (category «${ch.name}»)" +
                (if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) " — but the ringer is ${ringerName()}, so it would be muted" else "")
            if (n.fullScreenIntent != null && locked) {
                events += "Android opened the full-screen alert by itself for «${title(n)}» (the phone was locked)."
                runCatching { n.fullScreenIntent.send() }
            } else if (importance >= NotificationManager.IMPORTANCE_HIGH) {
                if (!locked) { headsUps.removeAll { "${it.tag}|${it.id}" == key }; headsUps += sbn
                    events += "A banner (heads-up) appeared at the top: «${title(n)}»" + (if (n.fullScreenIntent != null) " — it asks for a full-screen alert, but Android shows only a banner while the phone is in use" else "") }
                else events += "On the lock screen: «${title(n)}» appeared (the screen lights up)."
            }
        }
        for (k in known.keys.toList()) if (k !in keys) { known.remove(k); postedAt.remove(k); changed = true }
        // Notifications with a time-out disappear by themselves.
        for (sbn in active) {
            val key = "${sbn.tag}|${sbn.id}"
            val t = sbn.notification.timeoutAfter
            val at = postedAt[key] ?: continue
            if (t > 0 && Duration.between(at, Instant.now()).toMillis() >= t) {
                if (sbn.tag == null) nm.cancel(sbn.id) else nm.cancel(sbn.tag, sbn.id)
                known.remove(key); postedAt.remove(key); changed = true
            }
        }
        return changed
    }

    /** Android's rule: a child of a group that alerts only through its summary (or the reverse) makes no alert. */
    fun silentByGroup(n: Notification): Boolean {
        if (n.group == null) return false
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        return (n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && !summary) || (n.groupAlertBehavior == Notification.GROUP_ALERT_CHILDREN && summary)
    }

    fun title(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    /** Android applies a per-app language the app sets for itself by recreating its windows. */
    private fun processLocale(): Boolean {
        val wanted = effectiveLocale()
        val cur = appliedLocale
        if (cur != null && cur.language == wanted.language && cur.country == wanted.country) return false
        debug("app language: $cur -> $wanted")
        applyConfig(wanted)
        recreateAll()
        return true
    }

    fun effectiveLocale(): Locale {
        val l = runCatching { app.getSystemService(LocaleManager::class.java).applicationLocales }.getOrNull()
        return if (l != null && !l.isEmpty) l[0] else Locale.forLanguageTag(sysLang)
    }

    fun applyConfig(locale: Locale = effectiveLocale()) {
        val lang = locale.language + (if (locale.country.isNotEmpty()) "-r" + locale.country else "")
        RuntimeEnvironment.setQualifiers("$lang-w412dp-h915dp-${if (night) "night" else "notnight"}-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        // Android keeps the full per-app locale (with -u-nu-latn) in the configuration; resource qualifiers cannot say it.
        val res = app.resources
        val cfg = android.content.res.Configuration(res.configuration).apply { setLocales(android.os.LocaleList(locale)) }
        @Suppress("DEPRECATION") res.updateConfiguration(cfg, res.displayMetrics)
        @Suppress("DEPRECATION") android.content.res.Resources.getSystem().updateConfiguration(cfg, android.content.res.Resources.getSystem().displayMetrics)
        Locale.setDefault(locale)
        appliedLocale = locale
    }

    fun recreateAll() {
        for (t in tasks()) for (w in t.wins) {
            if (w.state == Lifecycle.State.RESUMED) recreateWin(w) else w.stale = true
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun processToasts() {
        val shown = shadowOf(app).shownToasts.size
        if (shown > toastCount) {
            ShadowToast.getTextOfLatestToast()?.let { toasts += it; events += "A short message (toast) appeared at the bottom: «$it»" }
            toastCount = shown
        }
    }

    // =====================================================================================
    // Actions
    // =====================================================================================

    private fun start(l: Line): Boolean {
        var home = false
        for (a in l.args) when (a.lowercase()) {
            "onboarded" -> {
                Repo.prefs(app).onboarded = true
                Repo.prefs(app).notifAsked = true
                setNotificationsAllowed(true)
            }
            "at-home" -> home = true
            "fresh" -> Unit
            else -> throw ActionError("start knows: fresh, onboarded, at-home (not «$a»)")
        }
        return home
    }

    fun perform(l: Line) {
        when (l.verb) {
            "tap" -> tap(l.arg(0), byDesc = false, long = false)
            "tap-desc" -> tap(l.arg(0), byDesc = true, long = false)
            "long-press" -> tap(l.arg(0), byDesc = false, long = true)
            "type" -> type(l.arg(0))
            "clear" -> clear()
            "scroll" -> scroll(l.arg(0))
            "scroll-to" -> scrollTo(l.arg(0))
            "swipe-left" -> swipeRow(l.arg(0), left = true)
            "swipe-right" -> swipeRow(l.arg(0), left = false)
            "tap-outside" -> tapOutside()
            "tap-at" -> tapAt(l.floatArg(0), l.floatArg(1))
            "drag" -> drag(l.floatArg(0), l.floatArg(1), l.floatArg(2), l.floatArg(3))
            "back" -> back()
            "home" -> { requireUnlocked(); bringToFront(Home); syncLifecycle() }
            "open-app" -> { requireUnlocked(); openApp() }
            "wait" -> waitSeconds(l.intArg(0))
            "lock" -> lock()
            "unlock" -> unlock()
            "notifications" -> { if (lockScreenShowing()) throw ActionError("the phone is locked: the notifications are on the lock screen (see the picture)"); shadeOpen = true }
            "notification-tap" -> notificationTap(l.arg(0))
            "notification-action" -> notificationAction(l.arg(0), l.arg(1))
            "notification-swipe" -> notificationSwipe(l.arg(0))
            "font-scale" -> { fontScale = l.floatArg(0).also { if (it < 0.5f || it > 3f) throw ActionError("text size must be between 0.5 and 3.0") }; applyConfig(); recreateAll() }
            "dark" -> { night = onOff(l.arg(0)); applyConfig(); recreateAll() }
            "language" -> language(l.arg(0))
            "ringer" -> audio.ringerMode = when (l.arg(0)) {
                "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; "normal" -> AudioManager.RINGER_MODE_NORMAL
                else -> throw ActionError("ringer is silent, vibrate or normal")
            }
            "dnd" -> dnd(l.arg(0))
            "deny-notifications" -> { setNotificationsAllowed(false); events += "Notifications for the app were turned off in Android's settings (its notifications disappear)." }
            "allow-notifications" -> setNotificationsAllowed(true)
            "exact-alarms" -> {
                ShadowAlarmManager.setCanScheduleExactAlarms(onOff(l.arg(0)))
                deliverBroadcast(Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED").setPackage(app.packageName))
            }
            "channel" -> channel(l.arg(0), l.arg(1))
            "full-screen-permission" -> FullScreenShadow.allowed = onOff(l.arg(0))
            "timezone" -> {
                val z = runCatching { ZoneId.of(l.arg(0)) }.getOrElse { throw ActionError("unknown time zone «${l.arg(0)}» (example: Europe/London)") }
                TimeZone.setDefault(TimeZone.getTimeZone(z))
                deliverBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).setPackage(app.packageName))
            }
            "lockscreen-notifications" -> {
                lockMode = when (l.arg(0)) { "all", "hide-sensitive", "none" -> l.arg(0); else -> throw ActionError("use all, hide-sensitive or none") }
                // Android keeps this choice in its secure settings; apps read it there.
                val cr = app.contentResolver
                android.provider.Settings.Secure.putInt(cr, "lock_screen_show_notifications", if (lockMode == "none") 0 else 1)
                android.provider.Settings.Secure.putInt(cr, "lock_screen_allow_private_notifications", if (lockMode == "all") 1 else 0)
            }
            "on-error" -> stopOnError = when (l.arg(0)) { "stop" -> true; "continue" -> false; else -> throw ActionError("use on-error stop or on-error continue") }
            "reboot" -> reboot()
            "pick-file" -> pickFile(l.arg(0))
            "save-file" -> saveFile(l.args.getOrNull(0))
            "share-text" -> shareText(l.arg(0))
            "screenshot" -> Unit
            "start" -> throw ActionError("«start …» works only at the top of the script, before other lines")
            else -> throw ActionError("unknown action «${l.verb}» (see README)")
        }
    }

    private fun onOff(s: String) = when (s.lowercase()) { "on", "yes", "true" -> true; "off", "no", "false" -> false; else -> throw ActionError("use on or off") }

    private fun requireUnlocked() { if (lockScreenShowing()) throw ActionError("the phone is locked; use unlock first") }

    fun openApp() {
        requireUnlocked()
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: throw ActionError("the app has no icon on the home screen")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(i)
    }

    private fun language(s: String) {
        if (s != "ar" && s != "en") throw ActionError("language is ar or en")
        sysLang = s
        applyConfig(); recreateAll()
        deliverBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED).setPackage(app.packageName))
    }

    private fun dnd(s: String) {
        val filter = when (s) {
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "on" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> throw ActionError("dnd is on, off, alarms or total")
        }
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        if (s == "on") nm.setNotificationPolicy(NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA, 0, 0))
        nm.setInterruptionFilter(filter)
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        dndMode = s
    }

    private fun channel(name: String, how: String) {
        val ch = nm.notificationChannels.firstOrNull { it.name.toString() == name } ?: throw ActionError(
            "no notification category «$name»; the app has: " + nm.notificationChannels.joinToString { "«${it.name}»" })
        ch.importance = when (how) {
            "off" -> NotificationManager.IMPORTANCE_NONE
            "silent" -> NotificationManager.IMPORTANCE_LOW
            "on" -> NotificationManager.IMPORTANCE_HIGH
            else -> throw ActionError("channel \"<name>\" on|silent|off")
        }
    }

    private fun lock() {
        locked = true
        shadowOf(km).setKeyguardLocked(true)
        shadowOf(km).setIsDeviceLocked(true)
        syncLifecycle()
    }

    private fun unlock() {
        if (!locked) throw ActionError("the phone is not locked")
        locked = false
        unlockRequested = false
        shadowOf(km).setIsDeviceLocked(false)
        shadowOf(km).setKeyguardLocked(false)
        val um = app.getSystemService(UserManager::class.java)
        if (!um.isUserUnlocked) {
            shadowOf(um).setUserUnlocked(true)
            deliverBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
            events += "First unlock after the restart: Android tells the app that the phone has started."
        }
        pendingAfterUnlock?.let { sendPending(it) }
        pendingAfterUnlock = null
        syncLifecycle()
    }

    private fun waitSeconds(s0: Int) {
        if (s0 < 0) throw ActionError("wait needs a positive number of seconds")
        val s = s0.coerceAtMost(MAX_WAIT)
        val end = Instant.now().plusSeconds(s.toLong())
        while (true) {
            val left = Duration.between(Instant.now(), end).toMillis()
            if (left <= 0) break
            Thread.sleep(minOf(left, 1000))
            passTime(Long.MAX_VALUE)
            settle()
            if (crash != null) return
        }
        if (s0 > MAX_WAIT) throw ActionError("waits are limited to $MAX_WAIT seconds per line; waited $MAX_WAIT (use several wait lines)")
    }

    private fun reboot() {
        for (t in tasks().toList()) for (w in t.wins.toList()) closeWin(t, w)
        layers.clear(); layers += Home
        services.values.forEach { runCatching { it.destroy() } }; services.clear()
        players.clear(); lastSound = null
        val sam = shadowOf(am)
        for (a in sam.scheduledAlarms.toList()) runCatching { val op = a.operation; val li = a.onAlarmListener; if (op != null) am.cancel(op) else if (li != null) am.cancel(li) }
        nm.cancelAll(); known.clear(); postedAt.clear()
        runCatching { Engine.drain() }
        Repo.resetForTests()
        // The phone starts locked, before the first unlock (the app may only use its locked-boot part).
        locked = true
        shadowOf(km).setKeyguardLocked(true); shadowOf(km).setIsDeviceLocked(true)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        app.onCreate()
        deliverBroadcast(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(app.packageName))
        events += "The phone restarted and shows the lock screen (before the first unlock). Use unlock to continue."
    }

    // ---- files and sharing ----

    fun fixtureFiles(): List<File> {
        val dirs = listOf(File(session, "files"), File("../torture/blackbox/fixtures"), File("torture/blackbox/fixtures"))
        return dirs.filter { it.isDirectory }.flatMap { d -> d.listFiles()!!.filter { it.isFile }.sortedBy { it.name } }.distinctBy { it.name }
    }

    private fun overlayOf(kind: Overlay.Kind): Pair<AppTask, Overlay> {
        val t = frontTask(); val o = t?.overlay
        if (t == null || o == null || o.kind != kind) throw ActionError(
            if (kind == Overlay.Kind.OPEN_FILE) "no file picker is open (the app has not asked to choose a file)" else "no «save file» screen is open")
        return t to o
    }

    private fun pickFile(name: String) {
        val (t, o) = overlayOf(Overlay.Kind.OPEN_FILE)
        val f = fixtureFiles().firstOrNull { it.name == name } ?: throw ActionError("no file «$name»; available: " + fixtureFiles().joinToString { it.name })
        val uri = FakeDocs.add(f)
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { FileInputStream(f) }
        closeOverlay(t, o, Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun saveFile(name: String?) {
        val (t, o) = overlayOf(Overlay.Kind.SAVE_FILE)
        val n = name ?: o.suggestedName
        val f = File(out, "files/$n").also { it.parentFile!!.mkdirs() }
        val uri = FakeDocs.add(f)
        shadowOf(app.contentResolver).registerOutputStreamSupplier(uri) { FileOutputStream(f) }
        closeOverlay(t, o, Activity.RESULT_OK, Intent().setData(uri))
        events += "Saved as out/files/$n"
    }

    private fun shareText(text: String) {
        requireUnlocked()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val targets = app.packageManager.queryIntentActivities(send, 0).filter { it.activityInfo.packageName == app.packageName }
        if (targets.isEmpty()) throw ActionError("${appLabel()} is not offered in Android's share sheet (it does not accept shared text)")
        val ai = targets.first().activityInfo
        startActivity(send.setComponent(ComponentName(ai.packageName, ai.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun closeOverlay(t: AppTask, o: Overlay, result: Int, data: Intent?) {
        t.overlay = null
        syncLifecycle()
        val w = o.owner
        when (o.kind) {
            Overlay.Kind.PERMISSION -> deliverPermission(w, o.requestCode, o.permissions, result == Activity.RESULT_OK)
            Overlay.Kind.BATTERY -> if (result == Activity.RESULT_OK) shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
            else -> Unit
        }
        if (o.requestCode >= 0 && o.kind != Overlay.Kind.PERMISSION) {
            val a = (w ?: t.wins.lastOrNull())?.activity
            if (a != null) shadowOf(a).callOnActivityResult(o.requestCode, result, data)
        }
    }

    private fun overlayButton(t: AppTask, o: Overlay, label: String): Boolean {
        val i = o.buttons.indexOfFirst { it == label }.takeIf { it >= 0 } ?: o.buttons.indexOfFirst { it.contains(label, true) }
        if (i < 0) return false
        when (o.kind) {
            Overlay.Kind.PERMISSION -> {
                if (i == 1) notifDenials++
                closeOverlay(t, o, if (i == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
            }
            Overlay.Kind.BATTERY -> closeOverlay(t, o, if (i == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
            Overlay.Kind.SAVE_FILE -> saveFile(null)
            else -> closeOverlay(t, o, Activity.RESULT_CANCELED, null)
        }
        return true
    }

    // ---- touching the screen ----

    class Found(val node: SemanticsNode, val win: RootWin, val how: String)

    private fun inShade(): Boolean = shadeOpen

    /** Finds an element a person can see with this text (or description), top window first. */
    fun find(text: String, byDesc: Boolean): Found {
        val wins = visibleWindows()
        if (wins.isEmpty()) throw ActionError(when {
            lockScreenShowing() -> "the phone is locked; only the lock screen is visible"
            frontLayer() === Home -> "the home screen is showing, not the app (use open-app)"
            else -> "nothing of the app is on screen"
        })
        val norm = { s: String -> s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim() }
        val t = norm(text)
        val passes: List<Pair<String, (SemanticsNode) -> Boolean>> = (if (!byDesc) listOf(
            "text" to { n: SemanticsNode -> textsOf(n).any { norm(it) == t } },
        ) else emptyList()) + listOf(
            "description" to { n: SemanticsNode -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { norm(it) == t } == true },
        ) + (if (!byDesc) listOf("text containing it" to { n: SemanticsNode -> textsOf(n).any { norm(it).contains(t, true) } }) else emptyList()) +
            listOf("description containing it" to { n: SemanticsNode -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { norm(it).contains(t, true) } == true })
        var offscreen: Found? = null
        for ((how, match) in passes) {
            for ((wi, w) in wins.withIndex().reversed()) {
                val owner = w.semantics ?: continue
                val hits = allNodes(owner.unmergedRootSemanticsNode).filter { !hidden(it) && match(it) }
                val visible = hits.filter { onScreen(it, w) }
                if (visible.isNotEmpty()) {
                    val covering = wins.drop(wi + 1).firstOrNull { it.modal }
                    if (covering != null) throw ActionError("«$text» is behind ${covering.kindName} that is open on top; a tap there would only close it (use back, tap-outside, or a button in it)")
                    return Found(visible.first(), w, how + if (visible.size > 1) " (${visible.size} matches; the first one was used)" else "")
                }
                if (offscreen == null && hits.isNotEmpty()) offscreen = Found(hits.first(), w, how)
            }
        }
        if (offscreen != null) throw ActionError("«$text» exists further down/up but is not visible on screen; scroll first (scroll-to \"$text\")")
        throw ActionError("no element with " + (if (byDesc) "description" else "text") + " «$text» on screen")
    }

    fun textsOf(n: SemanticsNode): List<String> =
        (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)

    fun allNodes(root: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(root)
        return out
    }

    @Suppress("DEPRECATION")
    fun hidden(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.contains(SemanticsProperties.InvisibleToUser)) return true
            if (runCatching { p.config.contains(SemanticsProperties.HideFromAccessibility) }.getOrDefault(false)) return true
            p = p.parent
        }
        return false
    }

    fun onScreen(n: SemanticsNode, w: RootWin): Boolean {
        val b = n.boundsInWindow
        if (b.width <= 0f || b.height <= 0f) return false
        val cx = b.center.x + w.x; val cy = b.center.y + w.y
        val dm = app.resources.displayMetrics
        return cx >= 0 && cy >= 0 && cx < dm.widthPixels && cy < dm.heightPixels && cx >= w.x && cy >= w.y && cx < w.x + w.view.width && cy < w.y + w.view.height
    }

    private fun byId(id: Int) = SemanticsMatcher("node $id") { it.id == id }

    private fun tap(text: String, byDesc: Boolean, long: Boolean) {
        val t = frontTask()
        if (shadeOpen) { shadeOpen = false; throw ActionError("the notification shade is open; use notification-tap / notification-action, or back to close it") }
        if (!lockScreenShowing() && t?.overlay != null) {
            if (overlayButton(t, t.overlay!!, text)) return
            throw ActionError("an Android screen is in front («${t.overlay!!.title}») and it has no «$text»" +
                (t.overlay!!.buttons.takeIf { it.isNotEmpty() }?.let { "; its buttons: " + it.joinToString { b -> "«$b»" } } ?: ""))
        }
        val f = find(text, byDesc)
        val node = rule.onNode(byId(f.node.id), useUnmergedTree = true)
        if (long) node.performTouchInput { longClick() } else node.performClick()
        if (!f.how.startsWith("text") || f.how.contains("containing") || f.how.contains("matches")) events += "Matched «$text» by ${f.how}."
    }

    private fun focusedField(): SemanticsNode? {
        for (w in visibleWindows().reversed()) {
            val o = w.semantics ?: continue
            allNodes(o.unmergedRootSemanticsNode).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.contains(SemanticsActions.SetText) }?.let { return it }
        }
        return null
    }

    private fun type(text: String) {
        val f = focusedField() ?: throw ActionError("no text field has the cursor; tap the field first")
        rule.onNode(byId(f.id), useUnmergedTree = true).performTextInput(text)
    }

    private fun clear() {
        val f = focusedField() ?: throw ActionError("no text field has the cursor; tap the field first")
        rule.onNode(byId(f.id), useUnmergedTree = true).performTextClearance()
    }

    private fun scrollable(): Pair<SemanticsNode, RootWin> {
        val w = visibleWindows().lastOrNull() ?: throw ActionError("nothing of the app is on screen")
        val o = w.semantics ?: throw ActionError("this screen cannot be scrolled")
        val list = allNodes(o.unmergedRootSemanticsNode).filter { it.config.contains(SemanticsActions.ScrollBy) && !hidden(it) }
        val n = list.maxByOrNull { it.boundsInWindow.width * it.boundsInWindow.height } ?: throw ActionError("nothing on this screen scrolls")
        return n to w
    }

    private fun scroll(dir: String) {
        val (n, _) = scrollable()
        val i = rule.onNode(byId(n.id), useUnmergedTree = true)
        when (dir) {
            "down" -> i.performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.25f, durationMillis = 400) }
            "up" -> i.performTouchInput { swipeDown(startY = bottom * 0.25f, endY = bottom * 0.8f, durationMillis = 400) }
            else -> throw ActionError("scroll down or scroll up")
        }
    }

    private fun scrollTo(text: String) {
        repeat(25) { attempt ->
            try { find(text, false); return } catch (e: ActionError) {
                if (e.message?.contains("behind") == true) throw e
            }
            // Present but off screen: bring it into view the way a person would, by scrolling.
            val w = visibleWindows().lastOrNull() ?: throw ActionError("nothing of the app is on screen")
            val owner = w.semantics
            val hit = owner?.let { o -> allNodes(o.unmergedRootSemanticsNode).firstOrNull { n -> !hidden(n) && (textsOf(n).any { it.contains(text) } || n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true) } }
            if (hit != null) {
                runCatching { rule.onNode(byId(hit.id), useUnmergedTree = true).performScrollTo() }
                settle()
                runCatching { find(text, false) }.onSuccess { return }
            }
            val (n, _) = runCatching { scrollable() }.getOrElse { throw ActionError("«$text» is not on screen and nothing scrolls") }
            if (attempt == 0 && n.config.contains(SemanticsActions.ScrollToIndex)) {
                runCatching { rule.onNode(byId(n.id), useUnmergedTree = true).performScrollToNode(hasText(text, substring = true) or hasContentDescription(text, substring = true)) }
            } else {
                rule.onNode(byId(n.id), useUnmergedTree = true).performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.3f, durationMillis = 400) }
            }
            settle()
        }
        throw ActionError("could not find «$text» even after scrolling to the end")
    }

    private fun swipeRow(text: String, left: Boolean) {
        val f = find(text, false)
        var n: SemanticsNode = f.node
        // The row: the nearest parent that can be tapped or that reads as one item.
        var p: SemanticsNode? = n
        while (p != null && !p.isRoot) {
            if (p.config.contains(SemanticsActions.OnClick) || p.config.isMergingSemanticsOfDescendants) { n = p; break }
            p = p.parent
        }
        val i = rule.onNode(byId(n.id), useUnmergedTree = true)
        if (left) i.performTouchInput { swipeLeft() } else i.performTouchInput { swipeRight() }
    }

    private fun motion(w: RootWin, points: List<Pair<Float, Float>>, stepMs: Long = 16) {
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float, t: Long) = MotionEvent.obtain(t0, t, action, x - w.x, y - w.y, 0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val (x0, y0) = points.first()
        w.view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x0, y0, t0))
        var t = t0
        for ((x, y) in points.drop(1)) { t += stepMs; w.view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, t)) }
        val (x1, y1) = points.last()
        w.view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x1, y1, t + stepMs))
    }

    private fun tapAt(xDp: Float, yDp: Float) {
        if (frontTask()?.overlay != null) throw ActionError("an Android screen is in front; tap its buttons by text")
        val x = xDp * density; val y = yDp * density
        val w = windowAt(x, y) ?: throw ActionError("nothing of the app is at $xDp, $yDp dp")
        motion(w, listOf(x to y))
    }

    private fun drag(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (frontTask()?.overlay != null) throw ActionError("an Android screen is in front")
        val w = windowAt(x1 * density, y1 * density) ?: throw ActionError("nothing of the app is at $x1, $y1 dp")
        val pts = (0..12).map { k -> val f = k / 12f; (x1 + (x2 - x1) * f) * density to (y1 + (y2 - y1) * f) * density }
        motion(w, pts, 25)
    }

    private fun windowAt(x: Float, y: Float): RootWin? {
        val wins = visibleWindows()
        for (w in wins.reversed()) {
            val inside = x >= w.x && y >= w.y && x < w.x + w.view.width && y < w.y + w.view.height
            if (inside || w.modal) return w
        }
        return null
    }

    private fun tapOutside() {
        val t = frontTask()
        if (t?.overlay != null) {
            val o = t.overlay!!
            if (!o.transparent) throw ActionError("the Android screen in front fills the whole screen; use back")
            if (o.kind == Overlay.Kind.PERMISSION) closeOverlay(t, o, Activity.RESULT_CANCELED, null) else closeOverlay(t, o, Activity.RESULT_CANCELED, null)
            return
        }
        val wins = visibleWindows()
        val top = wins.lastOrNull() ?: throw ActionError("nothing of the app is on screen")
        if (wins.size < 2) throw ActionError("no dialog, sheet or menu is open, so there is no «outside»")
        val dm = app.resources.displayMetrics
        // A point outside the top window's content: the top edge of the screen, or just above a sheet.
        val y = if (top.y > 24 * density) top.y / 2f else 16 * density
        motion(top, listOf(dm.widthPixels / 2f to y))
    }

    private fun back() {
        if (shadeOpen) { shadeOpen = false; return }
        if (lockScreenShowing()) throw ActionError("the phone is locked; back does nothing on the lock screen")
        val t = frontTask() ?: throw ActionError("the home screen is showing; back does nothing")
        t.overlay?.let { o -> closeOverlay(t, o, Activity.RESULT_CANCELED, null); return }
        val wins = visibleWindows()
        val top = wins.lastOrNull()
        if (top != null && !top.isActivity) {
            val now = SystemClock.uptimeMillis()
            top.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            top.view.dispatchKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            return
        }
        val w = t.wins.lastOrNull() ?: return
        val a = w.activity as? androidx.activity.ComponentActivity ?: run { w.activity?.onBackPressed(); return }
        if (a.onBackPressedDispatcher.hasEnabledCallbacks()) { a.onBackPressedDispatcher.onBackPressed(); return }
        // Nothing in the app handles back: Android 12+ moves the app's first window to the background, otherwise closes the window.
        if (w === t.wins.first() && w.launcher) { bringToFront(Home); events += "The app went to the background (home screen)." }
        else a.finish()
        syncLifecycle()
    }

    // ---- notifications ----

    fun appNotifications(): List<StatusBarNotification> =
        nm.activeNotifications.filter { it.packageName == app.packageName || it.packageName == null }.sortedByDescending { it.notification.`when` }

    /** What the person sees of a notification right now (the public version on a locked screen when hidden). */
    fun visibleVersion(sbn: StatusBarNotification): Notification {
        val n = sbn.notification
        if (!lockScreenShowing()) return n
        return when {
            lockMode == "hide-sensitive" && n.visibility == Notification.VISIBILITY_PRIVATE -> n.publicVersion ?: n
            else -> n
        }
    }

    private fun findNotification(text: String): StatusBarNotification {
        val list = appNotifications()
        if (list.isEmpty()) throw ActionError("there are no notifications from the app")
        if (lockScreenShowing() && lockMode == "none") throw ActionError("the lock screen shows no notifications (lockscreen-notifications none); unlock first")
        fun t(sbn: StatusBarNotification) = title(visibleVersion(sbn))
        return list.firstOrNull { t(it) == text } ?: list.firstOrNull { t(it).contains(text, true) }
            ?: list.firstOrNull { title(it.notification).contains(text, true) && !lockScreenShowing() }
            ?: throw ActionError("no notification titled «$text»; showing: " + list.joinToString { "«${t(it)}»" })
    }

    private fun sendFromNotification(pi: PendingIntent, what: String) {
        val activity = shadowOf(pi).isActivity
        if (activity && lockScreenShowing()) {
            val target = shadowOf(pi).savedIntent
            val cn = resolve(target)
            val overLock = cn != null && cn.packageName == app.packageName && runCatching {
                val info = app.packageManager.getActivityInfo(cn, 0)
                val flag = runCatching { ActivityInfo::class.java.getField("FLAG_SHOW_WHEN_LOCKED").getInt(null) }.getOrDefault(0x800000)
                info.flags and flag != 0
            }.getOrDefault(false)
            if (!overLock) {
                pendingAfterUnlock = pi; unlockRequested = true
                events += "Android asks to unlock the phone first ($what opens after unlock)."
                return
            }
        }
        sendPending(pi)
        shadeOpen = false
    }

    private fun notificationTap(text: String) {
        val sbn = findNotification(text)
        val n = sbn.notification
        val pi = n.contentIntent ?: throw ActionError("tapping «$text» does nothing (the notification opens nothing)")
        if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) { if (sbn.tag == null) nm.cancel(sbn.id) else nm.cancel(sbn.tag, sbn.id) }
        sendFromNotification(pi, "the notification")
    }

    private fun notificationAction(text: String, label: String) {
        val sbn = findNotification(text)
        val shown = visibleVersion(sbn)
        val actions = shown.actions ?: emptyArray()
        val a = actions.firstOrNull { it.title.toString() == label } ?: actions.firstOrNull { it.title.toString().contains(label, true) }
            ?: throw ActionError("the notification «$text» has no button «$label»; its buttons: " + actions.joinToString { "«${it.title}»" }.ifEmpty { "none" })
        val pi = a.actionIntent ?: throw ActionError("the button «$label» does nothing")
        sendFromNotification(pi, "the button")
    }

    private fun notificationSwipe(text: String) {
        val sbn = findNotification(text)
        val n = sbn.notification
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0
        if (ongoing && lockScreenShowing()) throw ActionError("«$text» is a pinned notification: Android does not let you swipe it away on the lock screen")
        if (sbn.tag == null) nm.cancel(sbn.id) else nm.cancel(sbn.tag, sbn.id)
        known.remove("${sbn.tag}|${sbn.id}")
        n.deleteIntent?.let { sendPending(it) }
    }
}
