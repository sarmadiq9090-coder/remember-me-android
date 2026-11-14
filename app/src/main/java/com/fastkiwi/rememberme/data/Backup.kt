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
 * valid for both apps. Nothing in the zip is ever written to disk; only data.json and backup.json are read.
 */
object Backup {
    const val MAX_BYTES = 20L * 1024 * 1024

    class ImportException(val reason: Reason) : Exception(reason.name) {
        enum class Reason { NOT_A_BACKUP, DAMAGED, TOO_LARGE, INVALID }
    }

    data class Imported(val state: AppState, val verified: Boolean, val droppedAttachments: Int)

    fun folderName(at: Instant) = "RememberMe-backup-" + Codec.iso(at).replace(Regex("[:.]"), "-")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Writes the backup zip to [out]. Returns the folder name inside it. */
    fun export(state: AppState, out: OutputStream, now: Instant): String {
        val clean = stripAttachments(state).first
        val data = Codec.encode(clean).toByteArray(Charsets.UTF_8)
        val folder = folderName(now)
        val info = JsonObject(mapOf(
            "app" to JsonPrimitive("Remember Me"),
            "kind" to JsonPrimitive("manual"),
            "createdAt" to JsonPrimitive(Codec.iso(now)),
            "algorithm" to JsonPrimitive("sha256"),
            "files" to JsonObject(mapOf("data.json" to JsonPrimitive(sha256(data)))),
            "source" to JsonPrimitive("android"),
        ))
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("$folder/backup.json"))
            zip.write(Codec.json.encodeToString(JsonObject.serializer(), info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
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
    fun read(input: InputStream, now: Instant): Imported {
        val bytes = readCapped(input)
        val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        var data: ByteArray? = null
        var info: ByteArray? = null
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
                    if (parts.last() != "data.json" && parts.last() != "backup.json") continue
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
        } else {
            data = bytes
        }
        var verified = false
        if (info != null) {
            val obj = runCatching { Codec.json.parseToJsonElement(info.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                ?: throw ImportException(ImportException.Reason.DAMAGED)
            val algo = (obj["algorithm"] as? JsonPrimitive)?.content
            val hash = ((obj["files"] as? JsonObject)?.get("data.json") as? JsonPrimitive)?.content
            if (algo != "sha256" || hash == null) throw ImportException(ImportException.Reason.DAMAGED)
            if (!hash.equals(sha256(data!!), ignoreCase = true)) throw ImportException(ImportException.Reason.DAMAGED)
            verified = true
        }
        var text = data!!.toString(Charsets.UTF_8)
        if (text.isNotEmpty() && text[0] == 0xFEFF.toChar()) text = text.substring(1)
        val state = try { Codec.decode(text, now) } catch (_: DataFormatException) { throw ImportException(ImportException.Reason.INVALID) }
        val (clean, dropped) = stripAttachments(state)
        return Imported(clean, verified, dropped)
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
