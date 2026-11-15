package com.fastkiwi.rememberme.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.fastkiwi.rememberme.data.AlertLog

/**
 * Plays a reminder's tone (FEATURES.md F-016): a short foreground service whose notification is the
 * reminder's own notification, so nothing extra appears. It plays one tone (a normal reminder once, at
 * most 30 s; «منبّه مهم» looped for the ring duration), vibrates when asked, and stops on «تم», «أجّل»,
 * a swipe, the reminder screen's volume key, the duration, or Android's short-service timeout.
 * If no source can be played, the notification is posted again on the sounding fallback channel, so a
 * reminder is never silent by accident.
 */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var notificationId = 0
    private var notification: Notification? = null
    private var key = ""
    /** The start command being served: stopSelf(startId) never tears down a newer start (Android would crash it). */
    private var lastStartId = 0
    private var tts: android.speech.tts.TextToSpeech? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(startIntent: Intent?, flags: Int, startId: Int): Int {
        val intent = startIntent ?: run { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_STOP) { finish("stopped"); return START_NOT_STICKY }
        val n: Notification? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_NOTIFICATION)
        if (n == null) { stopSelf(); return START_NOT_STICKY }
        stopPlayback()
        val newId = intent.getIntExtra(EXTRA_ID, 0)
        lastStartId = startId
        // Another reminder was ringing: keep its notification (Android would remove it on a new id).
        if (notificationId != 0 && notificationId != newId) runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        notificationId = newId
        ringingId = newId
        notification = n
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val alarm = intent.getStringExtra(EXTRA_STYLE) == "alarm"
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(notificationId, n)
        } catch (_: Exception) {
            fallback("foreground")
            stopSelf()
            return START_NOT_STICKY
        }
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 30).coerceIn(1, MAX_SECONDS)
        if (pendingStop != 0 && pendingStop == newId) { pendingStop = 0; finish("stopped before start"); return START_NOT_STICKY }
        if (intent.getBooleanExtra(EXTRA_PLAY, true)) {
            val sources: Array<String> = intent.getStringArrayExtra(EXTRA_SOURCES)?.filterNotNull()?.toTypedArray() ?: emptyArray()
            if (!play(sources, alarm)) fallback("play")
        }
        if (intent.getBooleanExtra(EXTRA_VIBRATE, false)) vibrate(alarm, intent.getStringExtra(EXTRA_PATTERN) ?: "normal")
        intent.getStringExtra(EXTRA_SPEAK)?.takeIf { it.isNotBlank() }?.let { speak(it, alarm, intent.getStringExtra(EXTRA_LANG) ?: "ar") }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ AlertLog(this).add(AlertLog.Event.RING_TIMEOUT, key); finish("timeout") }, seconds * 1000L)
        return START_NOT_STICKY
    }

    /** Tries each source in order; true when one started. */
    private fun play(sources: Array<String>, alarm: Boolean): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        for ((i, src) in sources.withIndex()) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                if (src.startsWith("/")) mp.setDataSource(src) else mp.setDataSource(this, Uri.parse(src))
                mp.isLooping = alarm
                mp.prepare()
                mp.start()
                player = mp
                AlertLog(this).add(if (i == 0) AlertLog.Event.PLAY_STARTED else AlertLog.Event.FALLBACK, key, code = "source$i")
                return true
            } catch (e: Exception) {
                AlertLog(this).add(AlertLog.Event.PLAY_FAILED, key, code = e.javaClass.simpleName)
                runCatching { mp.release() }
            }
        }
        return false
    }

    private fun vibrate(alarm: Boolean, pattern: String) {
        val timings = when (pattern) {
            "short" -> longArrayOf(0, 200, 120, 200)
            "long" -> longArrayOf(0, 800, 300, 800, 300, 800)
            else -> longArrayOf(0, 400, 200, 400)
        }
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching {
            val effect = VibrationEffect.createWaveform(timings, if (alarm) 0 else -1)
            if (Build.VERSION.SDK_INT >= 33) v?.vibrate(effect, android.os.VibrationAttributes.createForUsage(
                if (alarm) android.os.VibrationAttributes.USAGE_ALARM else android.os.VibrationAttributes.USAGE_NOTIFICATION))
            else @Suppress("DEPRECATION") v?.vibrate(effect, AudioAttributes.Builder()
                .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            AlertLog(this).add(AlertLog.Event.VIBRATED, key)
        }
    }

    /** F-031: the phone's own text-to-speech says the title (or «لديك تذكير» when hidden) once, after the tone starts. */
    private fun speak(text: String, alarm: Boolean, lang: String) {
        runCatching {
            var engine: android.speech.tts.TextToSpeech? = null
            engine = android.speech.tts.TextToSpeech(this) { status ->
                val e = engine ?: return@TextToSpeech
                if (status != android.speech.tts.TextToSpeech.SUCCESS) { AlertLog(this).add(AlertLog.Event.PLAY_FAILED, key, code = "tts"); return@TextToSpeech }
                e.language = java.util.Locale.forLanguageTag(lang)
                e.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                handler.postDelayed({ runCatching { e.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "rm") } }, 1500)
            }
            tts = engine
        }
    }

    /** Nothing could play: post the same notification on the channel that has the phone's own sound. */
    private fun fallback(code: String) {
        AlertLog(this).add(AlertLog.Event.FALLBACK, key, code = code)
        val n = notification ?: return
        runCatching {
            val b = Notification.Builder.recoverBuilder(this, n).setChannelId(Notifier.CH_ALERT)
            getSystemService(NotificationManager::class.java).notify(notificationId, b.build())
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { v?.cancel() }
        tts?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        tts = null
    }

    private fun finish(@Suppress("UNUSED_PARAMETER") why: String, remove: Boolean = false) {
        stopPlayback()
        ringingId = 0
        // The notification stays (detached): only the sound stops.
        // Detached: the reminder's notification stays; removed when the reminder itself is gone (done in the app).
        stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        stopSelf(lastStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) { finish("system timeout") }

    override fun onDestroy() { stopPlayback(); ringingId = 0; if (instance === this) instance = null; super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.fastkiwi.rememberme.STOP_RING"
        const val EXTRA_ID = "id"
        const val EXTRA_NOTIFICATION = "notification"
        const val EXTRA_SOURCES = "sources"
        const val EXTRA_STYLE = "style"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_VIBRATE = "vibrate"
        const val EXTRA_PATTERN = "pattern"
        const val EXTRA_PLAY = "play"
        const val EXTRA_KEY = "key"
        const val EXTRA_SPEAK = "speak"
        const val EXTRA_LANG = "lang"
        const val MAX_SECONDS = 120

        /** The notification id that is ringing now, or 0. */
        @Volatile var ringingId = 0
        @Volatile private var instance: RingService? = null

        /** Starts ringing; false when Android refused to start the service (the caller then uses the fallback channel). */
        fun start(c: Context, id: Int, n: Notification, sources: List<String>, alarm: Boolean, seconds: Int, play: Boolean,
                  vibrate: Boolean, pattern: String, key: String, speak: String? = null, lang: String = "ar"): Boolean = try {
            ContextCompat.startForegroundService(c, Intent(c, RingService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_NOTIFICATION, n).putExtra(EXTRA_SOURCES, sources.toTypedArray())
                .putExtra(EXTRA_STYLE, if (alarm) "alarm" else "normal").putExtra(EXTRA_SECONDS, seconds).putExtra(EXTRA_PLAY, play)
                .putExtra(EXTRA_VIBRATE, vibrate).putExtra(EXTRA_PATTERN, pattern).putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_SPEAK, speak).putExtra(EXTRA_LANG, lang))
            true
        } catch (_: Exception) { false }

        /** Stops the sound (the notification stays). Never starts the service just to stop it. */
        /** A stop asked before the service exists (a tap right after the post): served at its start. */
        @Volatile private var pendingStop = 0

        fun stop(@Suppress("UNUSED_PARAMETER") c: Context, remove: Boolean = false) {
            val s = instance ?: run { pendingStop = ringingId; return }
            s.handler.post { s.finish("stopped", remove) }
        }
    }
}
