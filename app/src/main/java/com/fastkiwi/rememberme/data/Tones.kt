package com.fastkiwi.rememberme.data

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.ToneRef
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where a tone choice leads (FEATURES.md F-014, F-017). A choice is a stable reference; this turns it
 * into something playable, and when that fails walks the fallback chain so a reminder is never silent
 * by accident: the reminder's tone → the default for its style → the bundled chime → the phone's default.
 */
object Tones {
    val BUNDLED = listOf("chime-1" to R.raw.chime_1, "chime-2" to R.raw.chime_2, "chime-3" to R.raw.chime_3)
    const val MAX_FILE_BYTES = 5L * 1024 * 1024
    const val MAX_FILES = 10

    /** A playable source: either a URI string or a file path. */
    data class Source(val uri: String, val label: String)

    fun dir(c: Context) = File(c.filesDir, "tones")

    fun bundledUri(c: Context, id: String): String? =
        BUNDLED.firstOrNull { it.first == id }?.let { "android.resource://${c.packageName}/${it.second}" }

    /** The phone's own default for a style, or null when the phone has none ("None"). */
    fun phoneDefault(c: Context, style: AlertPolicy.Style): String? = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(c, if (style == AlertPolicy.Style.ALARM) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)?.toString()
    }.getOrNull()

    /**
     * One reference to a source, or null when it is missing on this phone: a deleted copy, a system tone
     * whose title no longer matches (another phone), an unknown bundled id. "default" and "silent" are
     * not sources (they are resolved by the caller).
     */
    fun resolve(c: Context, t: ToneRef): Source? = when (t.kind) {
        "bundled" -> bundledUri(c, t.ref)?.let { Source(it, t.ref) }
        "file" -> dir(c).listFiles()?.firstOrNull { it.name.substringBefore('.') == t.ref }?.let { Source(it.absolutePath, t.title) }
        "system" -> t.ref.takeIf { it.isNotEmpty() }?.let { ref ->
            val title = runCatching { RingtoneManager.getRingtone(c, Uri.parse(ref))?.getTitle(c) }.getOrNull()
            if (title != null && (t.title.isEmpty() || title == t.title)) Source(ref, title) else null
        }
        else -> null
    }

    /**
     * The chain for a reminder: its tone, the default for its style, bundled chime-1, the phone default.
     * Missing entries are skipped; [fellBack] is true when the reminder's own choice was missing.
     */
    data class Chain(val sources: List<Source>, val fellBack: Boolean)

    fun chain(c: Context, own: ToneRef?, styleDefault: ToneRef?, style: AlertPolicy.Style): Chain {
        val list = ArrayList<Source>()
        var fellBack = false
        if (own != null && own.kind != "default" && own.kind != "silent") {
            val s = resolve(c, own)
            if (s != null) list += s else fellBack = true
        }
        val def = styleDefault?.let { resolve(c, it) } ?: phoneDefault(c, style)?.let { Source(it, "") }
        def?.let { list += it }
        bundledUri(c, "chime-1")?.let { list += Source(it, "chime-1") }
        phoneDefault(c, AlertPolicy.Style.NORMAL)?.let { list += Source(it, "") }
        return Chain(list.distinctBy { it.uri }, fellBack)
    }

    /**
     * Copies a picked audio file into the app (no persistable permission needed later). Returns the
     * reference, or null when it is too big, empty, or the limit of custom tones is reached.
     */
    fun importFile(c: Context, open: () -> InputStream, displayName: String): ToneRef? {
        val d = dir(c).apply { mkdirs() }
        val bytes = open().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        if (bytes.isEmpty()) return null
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "audio"
        val existing = d.listFiles()?.firstOrNull { it.name.substringBefore('.') == sha }
        if (existing == null) {
            if ((d.listFiles()?.size ?: 0) >= MAX_FILES) return null
            File(d, "$sha.$ext").writeBytes(bytes)
        }
        return ToneRef("file", sha, displayName.take(80))
    }

    /** Custom copies no reminder or default uses any more are removed. */
    fun cleanup(c: Context, used: Set<String>) {
        dir(c).listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in used) f.delete() }
    }
}
