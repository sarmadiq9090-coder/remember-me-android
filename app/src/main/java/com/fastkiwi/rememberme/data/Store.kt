package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The data file layer, the same scheme as the Windows app's src/core/store.js: the current file is
 * copied to data.json.bak, the new content goes to data.json.tmp, is fsynced, and renamed into place.
 * A file that fails to load is kept as data.json.damaged-<time> and the .bak copy is loaded instead.
 *
 * One Store per process (see [Repo]) and every change runs under one lock, so the activity, the
 * notification buttons and the alarm receiver never write at the same time. Each change re-reads
 * nothing: the in-memory state is the truth, loaded once.
 */
class Store(private val dir: File, private val clock: () -> Instant = Instant::now) {
    val file = File(dir, "data.json")
    private val bak = File(dir, "data.json.bak")
    private val tmp = File(dir, "data.json.tmp")
    private val lock = Any()
    private val flow = MutableStateFlow(AppState())
    val state: StateFlow<AppState> get() = flow
    /** Set when the main file was unreadable and the backup copy (or nothing) was loaded. */
    @Volatile var recovered: Boolean = false
        private set
    /**
     * Set when data.json was written by a newer format (`version` > 1): it is kept untouched and nothing
     * is written over it until an app that reads it is installed (D-079 A18).
     */
    @Volatile var newer: Boolean = false
        private set

    init { synchronized(lock) { flow.value = load() } }

    private fun stamp() = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock())

    private fun read(f: File): AppState? = if (!f.isFile) null else runCatching { Codec.decode(f.readText(Charsets.UTF_8), clock()) }.getOrNull()

    /** True when the file is JSON with a numeric `version` above 1 (a newer format, not damage). */
    private fun newerFormat(f: File): Boolean = runCatching {
        val root = Codec.json.parseToJsonElement(f.readText(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        ((root["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()) > 1
    }.getOrDefault(false)

    private fun load(): AppState {
        dir.mkdirs()
        if (!file.exists()) {
            // A crash between the copy to .bak and the rename leaves only .bak (and maybe .tmp).
            return read(bak) ?: AppState()
        }
        read(file)?.let { return it }
        if (newerFormat(file)) { newer = true; return AppState() }
        // Damaged: keep it untouched for inspection, use the previous good copy.
        file.renameTo(File(dir, "data.json.damaged-${stamp()}"))
        recovered = true
        return read(bak) ?: AppState()
    }

    /** Applies [change] and writes the result durably. Returns the new state; a failed write changes nothing. */
    fun change(change: (AppState) -> AppState): AppState = synchronized(lock) {
        if (newer) throw java.io.IOException("data.json is from a newer format; not overwritten")
        val next = change(flow.value)
        if (next === flow.value) return next
        write(Codec.encode(next))
        flow.value = next
        next
    }

    /** Replaces everything (restore from a backup). */
    fun replace(state: AppState) = change { state }

    private fun write(text: String) {
        dir.mkdirs()
        if (file.exists()) {
            file.copyTo(bak, overwrite = true)
            FileOutputStream(bak, true).use { it.fd.sync() }
        }
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Some file systems refuse to rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("could not replace data.json")
        }
    }

    // ---- Safety copies: the current data, kept before an import replaces it (the newest 3). ----
    private val safetyDir = File(dir, "safety")

    /** Milliseconds too, so two copies in the same second never overwrite each other. */
    private fun safetyStamp() = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(clock())

    fun safetyCopy(prefix: String = "before-import-"): File = synchronized(lock) {
        safetyDir.mkdirs()
        val out = File(safetyDir, "$prefix${safetyStamp()}.json")
        out.writeText(Codec.encode(flow.value), Charsets.UTF_8)
        safetyDir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }.orEmpty().drop(KEEP_SAFETY).forEach { it.delete() }
        out
    }

    /** Copies kept before an import, a restore or a data migration, newest first. */
    fun safetyCopies(): List<File> = safetyDir.listFiles { f -> f.name.startsWith("before-") && f.name.endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Brings the data from schema [from] to [target] with [steps] (PRD P0.5). A copy of the data is kept
     * first; the result must read back and keep every reminder the data had (by id), or nothing is written
     * and the original stays. Returns the schema the data is at afterwards.
     */
    fun migrate(from: Int, target: Int, steps: List<Migrations.Step>): Int = synchronized(lock) {
        if (from >= target) return from
        val before = flow.value
        val path = steps.filter { it.from >= from && it.to <= target }.sortedBy { it.from }
        if (path.isEmpty() || path.first().from != from || path.last().to != target || path.zipWithNext().any { (a, b) -> a.to != b.from }) return from
        safetyCopy("before-migration-v$from-")
        val after = try {
            path.fold(before) { s, step -> step.apply(s) }
        } catch (_: Exception) { return from }
        val reread = runCatching { Codec.decode(Codec.encode(after), clock()) }.getOrNull() ?: return from
        if (!reread.tasks.map { it.id }.toSet().containsAll(before.tasks.map { it.id })) return from
        change { after }
        target
    }

    fun restoreSafety(f: File): Boolean {
        val state = read(f) ?: return false
        // What is on the phone now becomes a safety copy too, so a mistaken tap can be undone.
        safetyCopy()
        replace(state)
        return true
    }

    companion object { const val KEEP_SAFETY = 3 }
}
