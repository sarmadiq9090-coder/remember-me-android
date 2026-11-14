package com.fastkiwi.rememberme.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.data.AutoBackup
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.BackupPassword
import com.fastkiwi.rememberme.data.DeleteAll
import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.data.IcsImport
import com.fastkiwi.rememberme.data.IcsImportResult
import com.fastkiwi.rememberme.data.SettingsPrefs
import com.fastkiwi.rememberme.data.Tones
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * The data group's flows (DESIGN-SPEC §5.14 «بياناتك», §5.20, FEATURES F-085…F-088, F-092) and «ما الجديد» (F-106),
 * with their dialogs. Create it as a property of the activity (it registers the file pickers), call the actions from
 * Settings (and Home's backup reminder), and put [Dialogs] once in the activity's content.
 */
class SettingsFlows(private val activity: ComponentActivity) {
    /** The dialog being shown. */
    sealed class Dialog {
        data class Effects(val effects: ExportEffects) : Dialog()
        data object Password : Dialog()
        data class Restore(val ready: RestoreReader.Result.Ready, val current: Int) : Dialog()
        data class Refused(val why: RestoreReader.Refusal) : Dialog()
        data class IcsReport(val result: IcsImportResult) : Dialog()
        data object EncryptOn : Dialog()
        data object AutoBackupOptions : Dialog()
        data object DeleteOffer : Dialog()
        data object DeleteConfirm : Dialog()
        data object WhatsNew : Dialog()
        data object Busy : Dialog()
    }

    var dialog by mutableStateOf<Dialog?>(null)
        private set

    /** Bumped when a phone setting changed here (encryption, the backup folder), so Settings reads it again. */
    var version by mutableIntStateOf(0)
        private set

    /** Called after a restore (the activity refreshes its safety copies) and after «حذف كل البيانات». */
    var onRestored: () -> Unit = {}

    /** The bytes of a file waiting for its password (kept in memory only while the dialog shows). */
    private var pendingBytes: ByteArray? = null

    private val context get() = activity.applicationContext
    private fun toast(id: Int, vararg args: Any) = Toast.makeText(activity, activity.getString(id, *args), Toast.LENGTH_LONG).show()

    private val exporter = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { write(it, encrypted = false) } }
    private val sealedExporter = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { write(it, encrypted = true) } }
    private val opener = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { open(it) } }
    private val icsOpener = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { openIcs(it) } }
    private val icsExporter = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri -> uri?.let { writeIcs(it) } }
    private val folderPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { folderChosen(it) } }

    // ---------------------------------------------------------------- export (F-092, F-086)

    /** «حفظ نسخة»: encrypted when «تشفير النسخة» is on; otherwise lists what Windows changes first (F-092). */
    fun export() {
        val name = Backup.folderName(Instant.now())
        if (SettingsPrefs(context).encryptBackups) { sealedExporter.launch("$name.rmbk"); return }
        val effects = ExportEffects.of(Repo.state(context))
        if (effects.any) dialog = Dialog.Effects(effects) else exporter.launch("$name.zip")
    }

    private fun write(uri: Uri, encrypted: Boolean) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val pw = if (encrypted) BackupPassword.load(context) ?: error("no password") else null
                    val bytes = try { AutoBackup.bytes(Repo.state(context), Instant.now(), pw) } finally { pw?.fill('\u0000') }
                    activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    Repo.prefs(context).lastExport = System.currentTimeMillis()
                }.isSuccess
            }
            toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }

    // ---------------------------------------------------------------- restore (§5.20, F-086)

    fun restore() = opener.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain", "*/*"))

    private fun open(uri: Uri) {
        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { activity.contentResolver.openInputStream(uri)!!.use(RestoreReader::readCapped) }.getOrNull() }
            if (bytes == null) dialog = Dialog.Refused(RestoreReader.Refusal.FAILED) else readRestore(bytes)
        }
    }

    /** Checks a backup's bytes (also called by tests) and shows the summary, the password request or the refusal. */
    fun readRestore(bytes: ByteArray, password: CharArray? = null) {
        dialog = Dialog.Busy
        activity.lifecycleScope.launch {
            val prefs = Repo.prefs(context)
            val result = withContext(Dispatchers.IO) {
                RestoreReader.read(bytes, password, Instant.now(), { t -> Tones.resolve(context, t) == null },
                    prayerSetUp = prefs.prayerMethod.isNotEmpty() && prefs.prayerCity.isNotEmpty())
            }
            password?.fill('\u0000')
            pendingBytes = null
            dialog = when (result) {
                is RestoreReader.Result.Ready -> Dialog.Restore(result, Repo.state(context).tasks.count { it.deletedAt == null })
                RestoreReader.Result.NeedsPassword -> { pendingBytes = bytes; Dialog.Password }
                is RestoreReader.Result.Refused -> Dialog.Refused(result.why)
            }
        }
    }

    private fun applyRestore(imported: Backup.Imported) {
        dialog = null
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Repo.store(context).safetyCopy()
                    Repo.change(context) { imported.state }
                }.isSuccess
            }
            onRestored()
            if (ok) toast(R.string.import_done, imported.state.tasks.count { it.deletedAt == null }.toString()) else toast(R.string.import_failed)
        }
    }

    // ---------------------------------------------------------------- calendar files (F-085)

    fun importIcs() = icsOpener.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream", "*/*"))

    private fun openIcs(uri: Uri) {
        dialog = Dialog.Busy
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = activity.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > IcsImport.MAX_BYTES) throw IcsImport.ImportException(IcsImport.ImportException.Reason.TOO_LARGE)
                        }
                        out.toByteArray()
                    }
                    IcsImport.read(bytes.toString(Charsets.UTF_8), Repo.zone(), Instant.now())
                }
            }
            dialog = null
            result.onSuccess { dialog = Dialog.IcsReport(it) }.onFailure { e ->
                toast(if ((e as? IcsImport.ImportException)?.reason == IcsImport.ImportException.Reason.TOO_LARGE) R.string.import_too_large else R.string.set_ics_not_calendar)
            }
        }
    }

    /** Shows the report of an already-read calendar (tests). */
    fun showIcsReport(result: IcsImportResult) { dialog = Dialog.IcsReport(result) }

    private fun saveIcs(result: IcsImportResult) {
        dialog = null
        activity.lifecycleScope.launch {
            var added = 0
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Repo.change(context) { s -> IcsImport.save(s, result.inputs, Instant.now(), Repo.zone()).also { added = it.second.size }.first }
                }.isSuccess
            }
            if (ok) Toast.makeText(activity, L10n.plural(activity, R.plurals.set_ics_added, added.toLong()), Toast.LENGTH_LONG).show()
            else toast(R.string.import_failed)
        }
    }

    fun exportIcs() = icsExporter.launch(Backup.folderName(Instant.now()).replace("backup", "calendar") + ".ics")

    private fun writeIcs(uri: Uri) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(Ics.export(Repo.state(context), Repo.zone(), Instant.now()).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }

    // ---------------------------------------------------------------- weekly backup (F-087), encryption (F-086)

    /** The row: off → choose a folder; on → change the folder or stop. */
    fun autoBackup() {
        if (Repo.prefs(context).autoBackupUri.isEmpty()) runCatching { folderPicker.launch(null) } else dialog = Dialog.AutoBackupOptions
    }

    private fun folderChosen(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val granted = runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
        if (!granted) { toast(R.string.set_auto_backup_failed); return }
        val old = Repo.prefs(context).autoBackupUri
        if (old.isNotEmpty() && old != uri.toString()) AutoBackup.stop(context)
        Repo.prefs(context).autoBackupUri = uri.toString()
        version++
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { AutoBackup.run(context, Instant.now()) }
            version++
            toast(if (ok) R.string.set_auto_backup_done else R.string.set_auto_backup_failed)
        }
    }

    /** «تشفير النسخة»: on asks for the password twice (and says Windows cannot open it); off forgets the password. */
    fun setEncrypt(on: Boolean) {
        if (on) dialog = Dialog.EncryptOn else {
            BackupPassword.clear(context)
            SettingsPrefs(context).encryptBackups = false
            version++
        }
    }

    private fun savePassword(pw: CharArray) {
        dialog = Dialog.Busy
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { BackupPassword.save(context, pw) }
            pw.fill('\u0000')
            dialog = null
            if (ok) SettingsPrefs(context).encryptBackups = true else toast(R.string.set_encrypt_failed)
            version++
        }
    }

    // ---------------------------------------------------------------- delete everything (F-088)

    fun deleteAll() { dialog = Dialog.DeleteOffer }

    private fun runDeleteAll() {
        dialog = Dialog.Busy
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { DeleteAll.run(context) }
            dialog = null
            // Start again as on a first install.
            activity.startActivity(Intent(activity, activity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity.finish()
        }
    }

    // ---------------------------------------------------------------- what's new (F-106)

    /** Shows «ما الجديد في 1.0.0» when [shouldShowWhatsNew] says so; call once from the activity's onCreate. */
    fun checkWhatsNew() {
        if (runCatching { shouldShowWhatsNew(context) }.getOrDefault(false)) dialog = Dialog.WhatsNew
    }

    private fun makeAllNormalNow() {
        activity.lifecycleScope.launch(Dispatchers.IO) { runCatching { Repo.change(context) { makeAllNormal(it, Instant.now()) } } }
    }

    // ---------------------------------------------------------------- dialogs

    @Composable
    fun Dialogs() {
        when (val d = dialog) {
            null -> Unit
            Dialog.Busy -> Unit
            is Dialog.Effects -> EffectsDialog(d.effects, onSave = {
                dialog = null
                exporter.launch(Backup.folderName(Instant.now()) + ".zip")
            }, onDismiss = { dialog = null })
            Dialog.Password -> PasswordDialog(
                title = stringResource(R.string.set_password_title), body = stringResource(R.string.set_password_body), twice = false,
                onOk = { pw -> pendingBytes?.let { readRestore(it, pw) } ?: run { dialog = null } },
                onDismiss = { pendingBytes = null; dialog = null },
            )
            is Dialog.Restore -> RestoreDialog(d.ready.summary, d.current, onRestore = { applyRestore(d.ready.imported) }, onDismiss = { dialog = null })
            is Dialog.Refused -> RefusedDialog(d.why, onRetry = if (d.why == RestoreReader.Refusal.WRONG_PASSWORD) ({ restore() }) else null) { dialog = null }
            is Dialog.IcsReport -> IcsReportDialog(d.result, onImport = { saveIcs(d.result) }, onDismiss = { dialog = null })
            Dialog.EncryptOn -> PasswordDialog(
                title = stringResource(R.string.set_encrypt), body = stringResource(R.string.set_encrypt_warning), twice = true,
                onOk = ::savePassword, onDismiss = { dialog = null },
            )
            Dialog.AutoBackupOptions -> AutoBackupDialog(
                folder = AutoBackup.folderName(context), last = Repo.prefs(context).lastAutoBackup, failed = AutoBackup.failed(context),
                encrypted = SettingsPrefs(context).encryptBackups,
                onChange = { dialog = null; runCatching { folderPicker.launch(null) } },
                onStop = { AutoBackup.stop(context); version++; dialog = null },
                onDismiss = { dialog = null },
            )
            Dialog.DeleteOffer -> DeleteOfferDialog(onBackup = { dialog = null; export() }, onContinue = { dialog = Dialog.DeleteConfirm }, onDismiss = { dialog = null })
            Dialog.DeleteConfirm -> DeleteConfirmDialog(onDelete = ::runDeleteAll, onDismiss = { dialog = null })
            Dialog.WhatsNew -> WhatsNewDialog(onMakeAllNormal = ::makeAllNormalNow, onOk = { markWhatsNewSeen(context); dialog = null })
        }
    }
}

// -------------------------------------------------------------------- dialog bodies

@Composable
private fun Line(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(end = Dimens.m).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** F-092: what Windows changes, with a count per effect; the every-few-hours titles are listed (D-011). */
@Composable
private fun EffectsDialog(effects: ExportEffects, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_hours_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(stringResource(R.string.set_export_intro), style = MaterialTheme.typography.bodyMedium)
                if (effects.hourly.isNotEmpty()) {
                    Line(Ms.Repeat, pluralStringResource(R.plurals.set_fx_hourly, effects.hourly.size, effects.hourly.size))
                    Text(effects.hourly.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Dimens.xxl))
                }
                if (effects.prayer > 0) Line(Ms.Mosque, pluralStringResource(R.plurals.set_fx_prayer, effects.prayer, effects.prayer))
                if (effects.hijri > 0) Line(Ms.CalendarMonth, pluralStringResource(R.plurals.set_fx_hijri, effects.hijri, effects.hijri))
                if (effects.extraAlerts > 0) Line(Ms.Notifications, pluralStringResource(R.plurals.set_fx_alerts, effects.extraAlerts, effects.extraAlerts))
                if (effects.keptUnused > 0) Line(Ms.Info, pluralStringResource(R.plurals.set_fx_kept, effects.keptUnused, effects.keptUnused))
                Text(stringResource(R.string.set_export_phone_same), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.export_anyway)) } },
    )
}

/** A password (once to open; twice to set, with the two compared). */
@Composable
private fun PasswordDialog(title: String, body: String, twice: Boolean, onOk: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    val mismatch = twice && b.isNotEmpty() && a != b
    val ok = a.isNotEmpty() && (!twice || a == b)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(a, { a = it }, singleLine = true, label = { Text(stringResource(R.string.set_password)) },
                    visualTransformation = PasswordVisualTransformation(), textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth())
                if (twice) OutlinedTextField(b, { b = it }, singleLine = true, label = { Text(stringResource(R.string.set_password_again)) },
                    visualTransformation = PasswordVisualTransformation(), isError = mismatch,
                    supportingText = if (mismatch) ({ Text(stringResource(R.string.set_password_mismatch)) }) else null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr), modifier = Modifier.fillMaxWidth())
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = ok, onClick = { val pw = a.toCharArray(); a = ""; b = ""; onOk(pw) }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.set_ok))
            }
        },
    )
}

/** One count of the restore summary's 2 × 2 grid. */
@Composable
fun CountTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(Dimens.m)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** §5.20: source and date, the check, the counts, notes, warnings, the safety-copy sentence; «إلغاء» · «استعادة». */
@Composable
private fun RestoreDialog(s: RestoreReader.Summary, current: Int, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_restore_q)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                val source = stringResource(when (s.source) {
                    RestoreReader.Source.WINDOWS -> R.string.set_from_windows
                    RestoreReader.Source.ANDROID -> R.string.set_from_android
                    RestoreReader.Source.UNKNOWN -> R.string.set_from_file
                })
                Line(Ms.Notes, s.createdAt?.let { source + "\n" + L10n.dueText(context, it) } ?: source)
                if (s.verified) Line(Ms.CheckCircle, stringResource(R.string.set_verified), MaterialTheme.colorScheme.primary)
                else Line(Ms.Info, stringResource(R.string.import_unverified))
                if (s.encrypted) Line(Ms.Lock, stringResource(R.string.set_was_encrypted))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    CountTile(stringResource(R.string.set_c_active), s.active, Modifier.weight(1f))
                    CountTile(stringResource(R.string.set_c_repeating), s.repeating, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    CountTile(stringResource(R.string.set_c_done), s.done, Modifier.weight(1f))
                    CountTile(stringResource(R.string.set_c_trash), s.trash, Modifier.weight(1f))
                }
                if (s.notes > 0) Line(Ms.Notes, stringResource(R.string.set_notes_kept))
                val warn = MaterialTheme.colorScheme.error
                if (s.droppedAttachments > 0) Line(Ms.Warning, pluralStringResource(R.plurals.set_attachments_dropped, s.droppedAttachments, s.droppedAttachments), warn)
                if (s.missingTones > 0) Line(Ms.Warning, pluralStringResource(R.plurals.set_tones_missing, s.missingTones, s.missingTones), warn)
                if (s.prayerWithoutSettings > 0) Line(Ms.Warning, pluralStringResource(R.plurals.set_prayer_unset, s.prayerWithoutSettings, s.prayerWithoutSettings), warn)
                Line(Ms.Info, stringResource(R.string.set_restore_replace, current))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { Button(onClick = onRestore, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.restore)) } },
    )
}

/** A refusal is its own dialog with one «حسنًا» (wrong password also offers to try again). */
@Composable
private fun RefusedDialog(why: RestoreReader.Refusal, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    val (title, body) = when (why) {
        RestoreReader.Refusal.NOT_A_BACKUP -> R.string.set_refused_title to R.string.import_not_backup
        RestoreReader.Refusal.DAMAGED -> R.string.set_refused_title to R.string.set_refused_damaged
        RestoreReader.Refusal.TOO_LARGE -> R.string.set_refused_title to R.string.import_too_large
        RestoreReader.Refusal.INVALID -> R.string.set_refused_title to R.string.import_invalid
        RestoreReader.Refusal.NEWER -> R.string.set_refused_title to R.string.set_refused_newer
        RestoreReader.Refusal.WRONG_PASSWORD -> R.string.set_wrong_password_title to R.string.set_wrong_password
        RestoreReader.Refusal.FAILED -> R.string.set_refused_title to R.string.import_failed
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Ms.Error, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        dismissButton = if (onRetry != null) ({
            TextButton(onClick = { onDismiss(); onRetry() }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_try_again)) }
        }) else null,
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_ok)) } },
    )
}

/** F-085: what will be added, what was left out and what changed (the importer's own lines); nothing is dropped silently. */
@Composable
private fun IcsReportDialog(r: IcsImportResult, onImport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_ics_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Line(Ms.Event, if (r.inputs.isEmpty()) stringResource(R.string.set_ics_nothing) else pluralStringResource(R.plurals.set_ics_will_add, r.inputs.size, r.inputs.size))
                if (r.skipped.isNotEmpty()) {
                    Line(Ms.Warning, pluralStringResource(R.plurals.set_ics_skipped, r.skipped.size, r.skipped.size), MaterialTheme.colorScheme.error)
                    r.skipped.forEach { Text("• ⁨$it⁩", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = Dimens.xxl)) }
                }
                if (r.warnings.isNotEmpty()) {
                    Line(Ms.Info, pluralStringResource(R.plurals.set_ics_changed, r.warnings.size, r.warnings.size))
                    r.warnings.forEach { Text("• ⁨$it⁩", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = Dimens.xxl)) }
                }
            }
        },
        dismissButton = if (r.inputs.isEmpty()) null else ({
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) }
        }),
        confirmButton = {
            if (r.inputs.isEmpty()) TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_ok)) }
            else Button(onClick = onImport, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_import)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoBackupDialog(folder: String?, last: Long, failed: Boolean, encrypted: Boolean, onChange: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_auto_backup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Line(Ms.Folder, stringResource(R.string.set_auto_backup_folder, folder ?: "…"))
                Line(Ms.History, if (last > 0) stringResource(R.string.set_auto_backup_last, L10n.dueText(context, Instant.ofEpochMilli(last))) else stringResource(R.string.set_auto_backup_none))
                if (failed) Line(Ms.Warning, stringResource(R.string.set_auto_backup_failed), MaterialTheme.colorScheme.error)
                Line(if (encrypted) Ms.Lock else Ms.Info, stringResource(if (encrypted) R.string.set_auto_backup_encrypted else R.string.set_auto_backup_plain))
                Text(stringResource(R.string.set_auto_backup_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_auto_backup_stop), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_close)) }
            }
        },
        confirmButton = { TextButton(onClick = onChange, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_auto_backup_change)) } },
    )
}

/** F-088 step 1: what goes, and a backup offered first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeleteOfferDialog(onBackup: () -> Unit, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Ms.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.set_delete_all_q)) },
        text = { Text(stringResource(R.string.set_delete_all_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = onBackup, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_backup_first)) }
            }
        },
        confirmButton = { TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.continue_), color = MaterialTheme.colorScheme.error) } },
    )
}

/** F-088 step 2: the typed confirmation «احذف»; only then is the destructive button enabled. */
@Composable
private fun DeleteConfirmDialog(onDelete: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val word = stringResource(R.string.set_delete_word)
    val ok = typed.trim() == word
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_delete_all)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(stringResource(R.string.set_delete_type, word), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.set_delete_type_label, word)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            Button(enabled = ok, onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_delete_all)) }
        },
    )
}

