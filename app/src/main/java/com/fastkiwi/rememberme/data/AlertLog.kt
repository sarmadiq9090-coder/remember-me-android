package com.fastkiwi.rememberme.data

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * «سجل التنبيهات» (FEATURES.md F-024): what happened to each alert, for finding out why one was missed.
 * Never a title, a note or a task id: an occurrence is a short keyed hash (an install-local secret), so
 * the log cannot be linked to a reminder from outside the phone.
 */
class AlertLog(context: Context) {
    private val p = context.getSharedPreferences("alert_log", Context.MODE_PRIVATE)

    enum class Event { FIRED, POSTED, FULL_SCREEN, SCREEN_SHOWN, PLAY_STARTED, PLAY_FAILED, FALLBACK, VIBRATED, QUIET, DONE, SNOOZE, SKIP, LATER, DISMISSED, REPOSTED, RING_TIMEOUT, LATE }

    data class Entry(val at: Long, val event: Event, val key: String, val lateMs: Long, val style: String, val code: String) {
        fun encode() = listOf(at, event.name, key, lateMs, style, code).joinToString("|")
        companion object {
            fun decode(s: String): Entry? = runCatching {
                val f = s.split('|')
                Entry(f[0].toLong(), Event.valueOf(f[1]), f[2], f[3].toLong(), f[4], f[5])
            }.getOrNull()
        }
    }

    private fun salt(): ByteArray = synchronized(LOCK) {
        p.getString("salt", null)?.let { return it.chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray() }
        val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit { putString("salt", s.joinToString("") { "%02x".format(it) }) }
        s
    }

    /** The first 8 hex digits of HMAC-SHA256(install salt, "taskId|dueAt"). */
    fun keyOf(taskId: String, dueAt: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt(), "HmacSHA256")) }
        return mac.doFinal("$taskId|$dueAt".toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }

    fun add(event: Event, key: String, lateMs: Long = -1, style: String = "", code: String = "") = synchronized(LOCK) {
        val e = Entry(System.currentTimeMillis(), event, key, lateMs, style, code.replace("|", "/").take(40))
        val lines = (listOf(e.encode()) + (p.getString("entries", "") ?: "").lines().filter { it.isNotBlank() }).take(MAX)
        p.edit { putString("entries", lines.joinToString("\n")) }
    }

    fun entries(): List<Entry> = (p.getString("entries", "") ?: "").lines().mapNotNull { Entry.decode(it) }

    /** Fallbacks or failed playback in the last 7 days (shown in Health). */
    fun recentProblems(now: Long = System.currentTimeMillis()): Int =
        entries().count { it.at > now - 7 * 86_400_000L && (it.event == Event.FALLBACK || it.event == Event.PLAY_FAILED) }

    fun clear() = p.edit { remove("entries") }

    companion object {
        const val MAX = 500
        private val LOCK = Any()
    }
}
