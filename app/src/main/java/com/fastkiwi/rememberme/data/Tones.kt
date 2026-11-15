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

    /** Why a picked file was not taken (shown under the row, never a toast). */
    enum class Refusal { TOO_BIG, EMPTY, NOT_AUDIO, TOO_MANY }

    @Volatile var lastRefusal: Refusal? = null
        private set

    /**
     * True when the bytes start like an audio container Android plays: MP3 (ID3 or a frame sync), AAC
     * ADTS, MP4/M4A/3GP (ftyp), Ogg (Vorbis/Opus), WAV, FLAC, AMR, MIDI, Matroska/WebM audio.
     */
    fun looksLikeAudio(b: ByteArray): Boolean {
        fun at(i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it] == s[it].code.toByte() }
        val sync = b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
        return at(0, "ID3") || sync || at(4, "ftyp") || at(0, "OggS") || (at(0, "RIFF") && at(8, "WAVE")) || at(0, "fLaC") ||
            at(0, "#!AMR") || at(0, "MThd") || (b.size >= 4 && (b[0].toInt() and 0xFF) == 0x1A && (b[1].toInt() and 0xFF) == 0x45 &&
            (b[2].toInt() and 0xFF) == 0xDF && (b[3].toInt() and 0xFF) == 0xA3)
    }

    /**
     * The second check, on the phone: Android's own extractor finds an audio track in the copy. Tests
     * replace it (Robolectric has no media codecs).
     */
    @Volatile var hasAudioTrack: (File) -> Boolean = { f ->
        runCatching {
            val x = android.media.MediaExtractor()
            try {
                x.setDataSource(f.absolutePath)
                (0 until x.trackCount).any { x.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ||
                    f.name.endsWith(".mid")
            } finally { x.release() }
        }.getOrDefault(false)
    }

    /**
     * Copies a picked audio file into the app (no persistable permission needed later). Returns the
     * reference, or null when it is too big (5 MB), empty, not audio, or the limit of 10 is reached;
     * [lastRefusal] says which.
     */
    fun importFile(c: Context, open: () -> InputStream, displayName: String): ToneRef? {
        lastRefusal = null
        val d = dir(c).apply { mkdirs() }
        val bytes = open().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE_BYTES) { lastRefusal = Refusal.TOO_BIG; return null }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        if (bytes.isEmpty()) { lastRefusal = Refusal.EMPTY; return null }
        if (!looksLikeAudio(bytes)) { lastRefusal = Refusal.NOT_AUDIO; return null }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "audio"
        val existing = d.listFiles()?.firstOrNull { it.name.substringBefore('.') == sha }
        if (existing == null) {
            if ((d.listFiles()?.size ?: 0) >= MAX_FILES) { lastRefusal = Refusal.TOO_MANY; return null }
            val f = File(d, "$sha.$ext")
            f.writeBytes(bytes)
            if (!hasAudioTrack(f)) { f.delete(); lastRefusal = Refusal.NOT_AUDIO; return null }
        }
        return ToneRef("file", sha, displayName.take(80))
    }

    /** A copy is kept this long even when unused: it may have just been picked in an editor not saved yet. */
    const val GRACE_MS = 24 * 3_600_000L

    /** Custom copies no reminder or default uses any more are removed (only after [GRACE_MS]). */
    fun cleanup(c: Context, used: Set<String>, now: Long = System.currentTimeMillis()) {
        dir(c).listFiles()?.forEach { f ->
            if (f.name.substringBefore('.') !in used && now - f.lastModified() > GRACE_MS) f.delete()
        }
    }

    /** The sha256 refs of every file tone in use: the reminders' choices and the two defaults. */
    fun used(state: com.fastkiwi.rememberme.core.AppState, defaults: List<ToneRef?>): Set<String> =
        (state.tasks.mapNotNull { com.fastkiwi.rememberme.core.AlertExt.of(it).tone } + defaults.filterNotNull())
            .filter { it.kind == "file" }.map { it.ref }.toSet()

    /** Removes copies nobody uses (run at app start and in the nightly window). */
    fun cleanupUnused(c: Context, now: Long = System.currentTimeMillis()) {
        // Never from data the app could not read (a newer format is kept untouched): its tones stay.
        if (com.fastkiwi.rememberme.Repo.store(c).newer) return
        val prefs = Prefs(c)
        cleanup(c, used(com.fastkiwi.rememberme.Repo.state(c), listOf(prefs.normalTone, prefs.importantTone)), now)
    }

    /** The copies in use, for a backup made with tones ("<sha256>.<ext>" → bytes). */
    fun forBackup(c: Context, state: com.fastkiwi.rememberme.core.AppState): Map<String, ByteArray> {
        val prefs = Prefs(c)
        val used = used(state, listOf(prefs.normalTone, prefs.importantTone))
        return dir(c).listFiles().orEmpty().filter { it.name.substringBefore('.') in used && it.length() <= MAX_FILE_BYTES }
            .associate { it.name to it.readBytes() }
    }

    /** Writes the tone copies of a restored backup (already checked against their names), at most 10. */
    fun restore(c: Context, tones: Map<String, ByteArray>) {
        val d = dir(c).apply { mkdirs() }
        for ((name, bytes) in tones) {
            if ((d.listFiles()?.size ?: 0) >= MAX_FILES) break
            val f = File(d, name)
            if (!f.exists() && looksLikeAudio(bytes)) f.writeBytes(bytes)
        }
    }
}
