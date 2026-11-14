package com.fastkiwi.rememberme.ui.settings

import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.BackupCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * What a Windows-format export changes on Windows (F-092): counts per effect over the active reminders.
 * Nothing is dropped silently: each count is shown before the file is written.
 */
data class ExportEffects(
    /** Every few hours → one-time on Windows. */
    val hourly: List<String>,
    /** Prayer-relative → a fixed daily time. */
    val prayer: Int,
    /** Hijri yearly → yearly on the Gregorian date. */
    val hijri: Int,
    /** Several early alerts → only the nearest one. */
    val extraAlerts: Int,
    /** Tone, alert type, full screen, pin, vibration, read aloud: kept in the file, unused on Windows. */
    val keptUnused: Int,
) {
    val any get() = hourly.isNotEmpty() || prayer > 0 || hijri > 0 || extraAlerts > 0 || keptUnused > 0

    companion object {
        fun of(state: AppState): ExportEffects {
            val active = state.tasks.filter { it.active }
            val ext = active.associateWith { AlertExt.of(it) }
            return ExportEffects(
                hourly = active.filter { it.repeat == Repeat.HOURS }.map { it.title },
                prayer = ext.count { it.value.prayer != null },
                hijri = ext.count { it.value.hijri != null },
                extraAlerts = ext.count { (t, e) -> e.alerts.any { it != t.remindBefore } },
                keptUnused = ext.values.count { e ->
                    e.tone != null || e.style != null || e.fullScreen != null || e.pin != null || e.vibrate != null || e.speak != null
                },
            )
        }
    }
}

/** Reading a backup file for «استعادة من ملف»: the checked content and its summary, a password request, or a refusal. */
object RestoreReader {
    enum class Refusal { NOT_A_BACKUP, DAMAGED, TOO_LARGE, INVALID, NEWER, WRONG_PASSWORD, FAILED }
    enum class Source { WINDOWS, ANDROID, UNKNOWN }

    sealed class Result {
        data class Ready(val imported: Backup.Imported, val summary: Summary) : Result()
        /** An encrypted file (F-086): ask for the password, then call [read] again with it. */
        data object NeedsPassword : Result()
        data class Refused(val why: Refusal) : Result()
    }

    data class Summary(
        val source: Source,
        val createdAt: Instant?,
        val verified: Boolean,
        val encrypted: Boolean,
        val active: Int,
        val repeating: Int,
        val done: Int,
        val trash: Int,
        val notes: Int,
        val droppedAttachments: Int,
        /** Reminders whose own tone is not on this phone (they will use the default tone). */
        val missingTones: Int,
        /** Prayer-relative reminders while this phone has no prayer method or city chosen. */
        val prayerWithoutSettings: Int,
    )

    /** A little more than [Backup.MAX_BYTES], for the encryption header. */
    const val MAX_BYTES = Backup.MAX_BYTES + 4096

    /** Reads at most [MAX_BYTES] + 1, so a larger file is refused without reading it all. */
    fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            out.write(buf, 0, n)
            if (total > MAX_BYTES) break
        }
        return out.toByteArray()
    }

    /**
     * Checks [bytes] completely without changing anything. [toneMissing] says whether a tone reference cannot be
     * played on this phone; [prayerSetUp] whether the phone has a prayer method and city.
     */
    fun read(
        bytes: ByteArray,
        password: CharArray?,
        now: Instant,
        toneMissing: (ToneRef) -> Boolean,
        prayerSetUp: Boolean,
    ): Result {
        if (bytes.size > MAX_BYTES) return Result.Refused(Refusal.TOO_LARGE)
        val encrypted = BackupCrypto.isEncrypted(bytes)
        val plain = if (encrypted) {
            if (password == null || password.isEmpty()) return Result.NeedsPassword
            try {
                BackupCrypto.decrypt(bytes, password)
            } catch (_: BackupCrypto.WrongPassword) {
                return Result.Refused(Refusal.WRONG_PASSWORD)
            } catch (_: BackupCrypto.Damaged) {
                return Result.Refused(Refusal.DAMAGED)
            } catch (_: Exception) {
                return Result.Refused(Refusal.FAILED)
            }
        } else bytes
        val imported = try {
            Backup.read(ByteArrayInputStream(plain), now)
        } catch (e: Backup.ImportException) {
            return Result.Refused(when (e.reason) {
                Backup.ImportException.Reason.NOT_A_BACKUP -> Refusal.NOT_A_BACKUP
                Backup.ImportException.Reason.DAMAGED -> Refusal.DAMAGED
                Backup.ImportException.Reason.TOO_LARGE -> Refusal.TOO_LARGE
                Backup.ImportException.Reason.INVALID -> if (newerFormat(plain)) Refusal.NEWER else Refusal.INVALID
            })
        } catch (_: Exception) {
            return Result.Refused(Refusal.FAILED)
        }
        val s = imported.state
        val active = s.tasks.filter { it.active }
        val ext = s.tasks.filter { it.deletedAt == null }.map { AlertExt.of(it) }
        return Result.Ready(imported, Summary(
            source = sourceOf(plain), createdAt = imported.createdAt, verified = imported.verified, encrypted = encrypted,
            active = imported.active, repeating = active.count { it.repeat != Repeat.NONE }, done = imported.done,
            trash = imported.trashed, notes = imported.notes, droppedAttachments = imported.droppedAttachments,
            missingTones = ext.count { e -> e.tone?.let { it.kind != "default" && it.kind != "silent" && toneMissing(it) } == true },
            prayerWithoutSettings = if (prayerSetUp) 0 else active.count { AlertExt.of(it).prayer != null },
        ))
    }

    /** The data.json text of a zip or bare file (the shallowest data.json), or null. */
    private fun dataJson(bytes: ByteArray): Pair<String?, String?> {
        if (!(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) return bytes.toString(Charsets.UTF_8) to null
        val found = HashMap<String, String>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    val parts = name.split('/')
                    if (e.isDirectory || ".." in parts || parts.size > 3) continue
                    if (parts.last() != "data.json" && parts.last() != "backup.json") continue
                    found[name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        val name = found.keys.filter { it.endsWith("data.json") }.minByOrNull { it.count { c -> c == '/' } } ?: return null to null
        return found[name] to found[name.removeSuffix("data.json") + "backup.json"]
    }

    /** True when the data says `version` above 1 (written by a newer app). */
    fun newerFormat(bytes: ByteArray): Boolean = runCatching {
        val text = dataJson(bytes).first?.removePrefix("﻿") ?: return false
        val root = Codec.json.parseToJsonElement(text) as JsonObject
        ((root["version"] as JsonPrimitive).intOrNull ?: 0) > 1
    }.getOrDefault(false)

    /** Which app wrote the backup: backup.json's "source" ("android"), Windows otherwise; a bare data.json is unknown. */
    fun sourceOf(bytes: ByteArray): Source {
        val (data, info) = dataJson(bytes)
        if (data == null || info == null) return Source.UNKNOWN
        val src = runCatching { ((Codec.json.parseToJsonElement(info) as JsonObject)["source"] as? JsonPrimitive)?.content }.getOrNull()
        return if (src == "android") Source.ANDROID else Source.WINDOWS
    }
}
