package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.DataFormatException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving data between phones and the Windows app by file (D-003).
 *
 * Export writes one .zip holding the Windows backup folder layout: RememberMe-backup-<time>/data.json
 * and backup.json with the SHA-256 of data.json's exact UTF-8 bytes. On Windows: extract the zip, then
 * Settings → Restore → choose the extracted folder.
 *
 * Import accepts that zip, a zip of a Windows backup folder, or a bare data.json. Attachments are
 * Windows-only: their files are not read and every reference to them is removed, so the data stays
 * valid for both apps. Nothing in the zip is written to disk here; only data.json, backup.json and (optional) tones/ are read,
 * and tones are handed to the caller (Tones.restore) after their checksums match.
 */
object Backup {
    const val MAX_BYTES = 20L * 1024 * 1024

    class ImportException(val reason: Reason) : Exception(reason.name) {
        enum class Reason { NOT_A_BACKUP, DAMAGED, TOO_LARGE, INVALID }
    }

    /**
     * [tones]: the app's copies of tones chosen from files, when the backup was made with them (an
     * option; manager's decision 2026-11-15), as "<sha256>.<ext>" → bytes, each checked against its name.
     */
    data class Imported(val state: AppState, val verified: Boolean, val droppedAttachments: Int, val createdAt: java.time.Instant? = null,
                        val tones: Map<String, ByteArray> = emptyMap()) {
        /** What a restore would bring, shown to the user before anything is replaced (PRD P0.5). */
        val active get() = state.tasks.count { it.active }
        val done get() = state.tasks.count { it.completedAt != null && it.deletedAt == null }
        val trashed get() = state.tasks.count { it.deletedAt != null }
        val notes get() = (state.rest["notes"] as? JsonArray)?.size ?: 0
    }

    fun folderName(at: Instant) = "RememberMe-backup-" + Codec.iso(at).replace(Regex("[:.]"), "-")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A tone copy's file name: "<sha256 hex>.<ext>". */
    private val TONE_NAME = Regex("^[0-9a-f]{64}\\.[a-z0-9]{2,5}$")

    /**
     * Writes the backup zip to [out]. Returns the folder name inside it. [tones]: optional copies of the
     * tones chosen from files ("<sha256>.<ext>" → bytes) under `tones/`; Windows ignores them.
     */
    fun export(state: AppState, out: OutputStream, now: Instant, tones: Map<String, ByteArray> = emptyMap()): String {
        val clean = stripAttachments(state).first
        val data = Codec.encode(clean).toByteArray(Charsets.UTF_8)
        val folder = folderName(now)
        val info = JsonObject(mapOf(
            "app" to JsonPrimitive("Remember Me"),
            "kind" to JsonPrimitive("manual"),
            "createdAt" to JsonPrimitive(Codec.iso(now)),
            "algorithm" to JsonPrimitive("sha256"),
            "files" to JsonObject(mapOf("data.json" to JsonPrimitive(sha256(data))) +
                tones.filterKeys { TONE_NAME.matches(it) }.map { (n, b) -> "tones/$n" to JsonPrimitive(sha256(b)) }),
            "source" to JsonPrimitive("android"),
        ))
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("$folder/backup.json"))
            zip.write(Codec.json.encodeToString(JsonObject.serializer(), info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, bytes) in tones) if (TONE_NAME.matches(name)) {
                zip.putNextEntry(ZipEntry("$folder/tones/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
            // data.json last, as on Windows: a folder that has it is complete.
            zip.putNextEntry(ZipEntry("$folder/data.json"))
            zip.write(data)
            zip.closeEntry()
        }
        return folder
    }

    private fun readCapped(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** Reads a backup from [input] (a zip or a data.json) and checks it completely. */
    fun read(input: InputStream, now: Instant): Imported = try { readChecked(input, now) }
        catch (e: ImportException) { throw e }
        catch (_: java.util.zip.ZipException) { throw ImportException(ImportException.Reason.DAMAGED) }
        catch (_: java.io.EOFException) { throw ImportException(ImportException.Reason.DAMAGED) }
        catch (_: java.io.IOException) { throw ImportException(ImportException.Reason.DAMAGED) }
        catch (_: IllegalArgumentException) { throw ImportException(ImportException.Reason.DAMAGED) }

    private fun readChecked(input: InputStream, now: Instant): Imported {
        val bytes = readCapped(input)
        val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        var data: ByteArray? = null
        var info: ByteArray? = null
        val toneEntries = LinkedHashMap<String, ByteArray>()
        if (isZip) {
            // The data.json closest to the root wins; backup.json must sit next to it.
            val found = HashMap<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    val parts = name.split('/')
                    if (e.isDirectory || ".." in parts || name.startsWith("/") || parts.size > 3) continue
                    val tone = parts.size >= 2 && parts[parts.size - 2] == "tones" && TONE_NAME.matches(parts.last())
                    if (parts.last() != "data.json" && parts.last() != "backup.json" && !tone) continue
                    val content = readCapped(zip)
                    total += content.size
                    if (total > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
                    found[name] = content
                }
            }
            val dataName = found.keys.filter { it.endsWith("data.json") }.minByOrNull { it.count { c -> c == '/' } }
                ?: throw ImportException(ImportException.Reason.NOT_A_BACKUP)
            data = found[dataName]
            info = found[dataName.removeSuffix("data.json") + "backup.json"]
            val prefix = dataName.removeSuffix("data.json") + "tones/"
            for ((n, b) in found) if (n.startsWith(prefix)) toneEntries[n.removePrefix(prefix)] = b
        } else {
            data = bytes
        }
        var verified = false
        var createdAt: Instant? = null
        if (info != null) {
            val obj = runCatching { Codec.json.parseToJsonElement(info.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                ?: throw ImportException(ImportException.Reason.DAMAGED)
            val algo = (obj["algorithm"] as? JsonPrimitive)?.content
            val hash = ((obj["files"] as? JsonObject)?.get("data.json") as? JsonPrimitive)?.content
            if (algo != "sha256" || hash == null) throw ImportException(ImportException.Reason.DAMAGED)
            if (!hash.equals(sha256(data!!), ignoreCase = true)) throw ImportException(ImportException.Reason.DAMAGED)
            verified = true
            createdAt = (obj["createdAt"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }
        var text = data!!.toString(Charsets.UTF_8)
        if (text.isNotEmpty() && text[0] == 0xFEFF.toChar()) text = text.substring(1)
        val state = try { Codec.decode(text, now) } catch (_: DataFormatException) { throw ImportException(ImportException.Reason.INVALID) }
        val (clean, dropped) = stripAttachments(state)
        // Tone copies: only those whose content matches their name (and the checksum list when there is one).
        val infoFiles = info?.let { runCatching { (Codec.json.parseToJsonElement(it.toString(Charsets.UTF_8)) as JsonObject)["files"] as? JsonObject }.getOrNull() }
        val tones = toneEntries.filter { (name, bytes) ->
            bytes.size <= Tones.MAX_FILE_BYTES && sha256(bytes) == name.substringBefore('.') &&
                (infoFiles == null || (infoFiles["tones/$name"] as? JsonPrimitive)?.content == sha256(bytes))
        }.entries.take(Tones.MAX_FILES).associate { it.key to it.value }
        return Imported(clean, verified, dropped, createdAt, tones)
    }

    /** Removes the attachment list and every reference to it (tasks and notes). Returns how many were listed. */
    fun stripAttachments(state: AppState): Pair<AppState, Int> {
        val dropped = (state.rest["attachments"] as? JsonArray)?.size ?: 0
        val empty = JsonArray(emptyList())
        fun clear(o: JsonObject) = if (o.containsKey("attachments")) JsonObject(o + ("attachments" to empty)) else o
        val notes = (state.rest["notes"] as? JsonArray)?.map { (it as? JsonObject)?.let(::clear) ?: it } ?: emptyList<JsonElement>()
        val rest = JsonObject(state.rest + mapOf("attachments" to empty, "notes" to JsonArray(notes)))
        val tasks = state.tasks.map { t -> if ("attachments" in t.extra) t.copy(extra = clear(t.extra)) else t }
        return state.copy(tasks = tasks, rest = rest) to dropped
    }
}
