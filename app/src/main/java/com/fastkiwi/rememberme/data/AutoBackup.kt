package com.fastkiwi.rememberme.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The weekly automatic backup (F-087) to a folder the user picked with ACTION_OPEN_DOCUMENT_TREE (a persistable
 * read/write grant; no storage permission, no network: a cloud folder is synced by its own app). Written from the
 * engine's housekeeping run when 7 days have passed; the newest [KEEP] files of ours in that folder are kept.
 * A failure is remembered ([SettingsPrefs.autoBackupFailedAt]) so Health can show it. Encrypted when «تشفير النسخة»
 * is on, with the password kept wrapped by a non-exportable Android Keystore key ([BackupPassword]).
 */
object AutoBackup {
    const val KEEP = 5
    const val EVERY_MS = 7L * 24 * 3600 * 1000
    const val PREFIX = "RememberMe-auto-"

    /** A folder as the backup needs it: list our files, create one, delete one. */
    interface Folder {
        fun names(): List<String>
        fun write(name: String, mime: String, bytes: ByteArray)
        fun delete(name: String)
    }

    fun due(last: Long, now: Long): Boolean = last <= 0 || now - last >= EVERY_MS

    /** File name for a backup made at [now]; sorts by time. */
    fun fileName(now: Instant, encrypted: Boolean): String =
        PREFIX + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(now) + if (encrypted) ".rmbk" else ".zip"

    /** Which of our files to delete so that the newest [KEEP] stay. Files that are not ours are never touched. */
    fun toPrune(names: List<String>): List<String> =
        names.filter { it.startsWith(PREFIX) && (it.endsWith(".zip") || it.endsWith(".rmbk")) }.sortedDescending().drop(KEEP)

    /** The backup bytes: the Windows-format zip (with [tones] copies when given), sealed with [password] when given. */
    fun bytes(state: AppState, now: Instant, password: CharArray?, tones: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        Backup.export(state, out, now, tones)
        val zip = out.toByteArray()
        return if (password == null) zip else BackupCrypto.encrypt(zip, password)
    }

    /** Writes one backup into [folder] and prunes; throws on any failure. */
    fun writeTo(folder: Folder, state: AppState, now: Instant, password: CharArray?, tones: Map<String, ByteArray> = emptyMap()) {
        val data = bytes(state, now, password, tones)
        folder.write(fileName(now, password != null), if (password != null) "application/octet-stream" else "application/zip", data)
        for (old in toPrune(folder.names())) runCatching { folder.delete(old) }
    }

    /**
     * Called by the engine's housekeeping run (the lead wires it): makes the weekly backup when one is due.
     * Matches `Engine.housekeeping`'s `(Context, Instant) -> Unit`. Never throws.
     */
    fun runIfDue(context: Context, now: Instant) {
        runCatching {
            val prefs = Repo.prefs(context)
            if (prefs.autoBackupUri.isNotEmpty() && due(prefs.lastAutoBackup, now.toEpochMilli())) run(context, now)
        }
    }

    /** Makes a backup now (also right after a folder is chosen). Never throws; records success or failure. */
    fun run(context: Context, now: Instant = Instant.now()): Boolean {
        val app = context.applicationContext
        val prefs = Repo.prefs(app)
        val sp = SettingsPrefs(app)
        val uri = prefs.autoBackupUri.takeIf { it.isNotEmpty() } ?: return false
        val ok = runCatching {
            val password = if (sp.encryptBackups) BackupPassword.load(app) ?: error("no password") else null
            try {
                val state = Repo.state(app)
                writeTo(TreeFolder(app, uri.toUri()), state, now, password, if (sp.backupTones) Tones.forBackup(app, state) else emptyMap())
            } finally {
                password?.fill('\u0000')
            }
        }.isSuccess
        if (ok) { prefs.lastAutoBackup = now.toEpochMilli(); sp.autoBackupFailedAt = 0 } else sp.autoBackupFailedAt = now.toEpochMilli()
        return ok
    }

    /** True when the last weekly backup failed (Health shows it with a fix). */
    fun failed(context: Context): Boolean = SettingsPrefs(context).autoBackupFailedAt > 0 && Repo.prefs(context).autoBackupUri.isNotEmpty()

    /** Stops the weekly backup and gives the folder grant back. Files already written stay. */
    fun stop(context: Context) {
        val prefs = Repo.prefs(context)
        val uri = prefs.autoBackupUri
        if (uri.isNotEmpty()) runCatching {
            context.contentResolver.releasePersistableUriPermission(uri.toUri(),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.autoBackupUri = ""
        prefs.lastAutoBackup = 0
        SettingsPrefs(context).autoBackupFailedAt = 0
    }

    /** The folder's name for the settings row, or null. */
    fun folderName(context: Context): String? {
        val uri = Repo.prefs(context).autoBackupUri.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val tree = uri.toUri()
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfterLast(':').substringAfterLast('/') }.getOrNull()
    }

    /** A document tree through DocumentsContract (no androidx.documentfile dependency). */
    class TreeFolder(private val context: Context, private val tree: Uri) : Folder {
        private val resolver get() = context.contentResolver
        private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

        private fun children(): List<Pair<String, String>> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val out = ArrayList<Pair<String, String>>()
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) out += c.getString(0) to (c.getString(1) ?: "")
            }
            return out
        }

        override fun names(): List<String> = children().map { it.second }

        override fun write(name: String, mime: String, bytes: ByteArray) {
            val doc = DocumentsContract.createDocument(resolver, root, mime, name) ?: error("create failed")
            try {
                resolver.openOutputStream(doc, "wt")!!.use { it.write(bytes) }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
        }

        override fun delete(name: String) {
            val id = children().firstOrNull { it.second == name }?.first ?: return
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        }
    }
}

/**
 * The backup password (F-086), kept only so the weekly backup can be encrypted without asking: wrapped with
 * AES-GCM by a key that lives in the Android Keystore and cannot be exported. Never in data.json or a backup.
 * Where the Keystore is unavailable, nothing is stored and [save] returns false.
 */
object BackupPassword {
    private const val ALIAS = "rememberme_backup_password"
    private const val PREF = "backupPasswordWrapped"

    /** How the password is wrapped; tests replace it (Robolectric has no Android Keystore). */
    interface Wrapper {
        fun wrap(plain: ByteArray): ByteArray
        fun unwrap(sealed: ByteArray): ByteArray
        fun delete()
    }

    @Volatile var wrapper: Wrapper = KeystoreWrapper

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun has(c: Context): Boolean = prefs(c).contains(PREF)

    fun save(c: Context, password: CharArray): Boolean = runCatching {
        val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(password)).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        try {
            val sealed = wrapper.wrap(bytes)
            prefs(c).edit(commit = true) { putString(PREF, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
        } finally {
            bytes.fill(0)
        }
    }.isSuccess

    fun load(c: Context): CharArray? = runCatching {
        val sealed = Base64.decode(prefs(c).getString(PREF, null) ?: return null, Base64.NO_WRAP)
        val bytes = wrapper.unwrap(sealed)
        try {
            val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            CharArray(chars.remaining()).also { chars.get(it) }
        } finally {
            bytes.fill(0)
        }
    }.getOrNull()

    fun clear(c: Context) {
        prefs(c).edit(commit = true) { remove(PREF) }
        runCatching { wrapper.delete() }
    }

    private object KeystoreWrapper : Wrapper {
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            return gen.generateKey()
        }

        override fun wrap(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
        }

        override fun unwrap(sealed: ByteArray): ByteArray {
            val n = sealed[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, n))
            return cipher.doFinal(sealed, 1 + n, sealed.size - 1 - n)
        }

        override fun delete() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }
    }
}
