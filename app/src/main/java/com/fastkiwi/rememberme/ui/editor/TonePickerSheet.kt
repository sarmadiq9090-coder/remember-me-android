package com.fastkiwi.rememberme.ui.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.data.Tones
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SheetShape
import com.fastkiwi.rememberme.ui.TooltipIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The label of a tone choice (null or "default" = the default). */
@Composable
fun toneLabel(t: ToneRef?): String = when (t?.kind) {
    null, "default" -> stringResource(R.string.tone_default)
    "silent" -> stringResource(R.string.tone_silent)
    "bundled" -> stringResource(R.string.tone_bundled_n, ((Tones.BUNDLED.indexOfFirst { it.first == t.ref }).coerceAtLeast(0) + 1).toString())
    else -> t.title.ifEmpty { t.ref.substringAfterLast('/') }
}

/** Plays one tone at a time with the alert style's audio usage, at the current volume (DESIGN-SPEC §5.6). */
class TonePreview(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(source: Tones.Source, style: AlertPolicy.Style, onEnd: () -> Unit): Boolean {
        stop()
        return runCatching {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (style == AlertPolicy.Style.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            if (source.uri.startsWith("/")) p.setDataSource(source.uri) else p.setDataSource(context, source.uri.toUri())
            p.setOnCompletionListener { stop(); onEnd() }
            p.prepare()
            p.start()
            player = p
        }.isSuccess
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }
}

/**
 * «النغمة» (DESIGN-SPEC §5.6, F-014, F-017): the default, the 3 built-in tones (a nested list), the phone's
 * tones (Android's picker), «من ملفاتي…» (copied into the app by [Tones.importFile]) and «صامت (اهتزاز فقط)».
 * Each row but silent has a preview; the preview stops when the sheet closes. Nothing applies before «تم».
 * Used per reminder (the editor) and for the two defaults (Settings): pass [title], [subtitle] and
 * [defaultTone] (what «النغمة الافتراضية» plays; null = the phone's own). [onPick] gets null for the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonePickerSheet(
    current: ToneRef?,
    style: AlertPolicy.Style,
    onPick: (ToneRef?) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.tone),
    subtitle: String? = stringResource(R.string.tone_for_this),
    defaultTone: ToneRef? = null,
    defaultLabel: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { TonePreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    var selected by remember { mutableStateOf(current?.takeIf { it.kind != "default" }) }
    var picked by remember { mutableStateOf(current?.takeIf { it.kind == "system" }) }
    var file by remember { mutableStateOf(current?.takeIf { it.kind == "file" }) }
    var playing by remember { mutableStateOf<String?>(null) }
    var builtinOpen by remember { mutableStateOf(current?.kind == "bundled") }
    var fileError by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    val errPlay = stringResource(R.string.tone_err_play)
    val errFile = stringResource(R.string.tone_err_file)
    val errLong = stringResource(R.string.tone_err_long)
    val errBig = stringResource(R.string.tone_err_big)
    val errEmpty = stringResource(R.string.tone_err_empty)
    val errNotAudio = stringResource(R.string.tone_err_not_audio)
    val errMany = stringResource(R.string.tone_err_many)

    fun play(key: String, ref: ToneRef?) {
        if (playing == key) { preview.stop(); playing = null; return }
        val source = if (ref == null) Tones.chain(context, null, defaultTone, style).sources.firstOrNull() else Tones.resolve(context, ref)
        val ok = source != null && preview.play(source, style) { playing = null }
        playing = if (ok) key else null
        if (!ok) when (ref?.kind) { "file" -> fileError = errPlay; "system" -> phoneError = errPlay }
    }

    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION")
        val uri: Uri = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return@rememberLauncherForActivityResult
        val name = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull().orEmpty()
        val ref = ToneRef("system", uri.toString(), name)
        phoneError = null
        picked = ref
        selected = ref
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { importTone(context, uri) }
            when (result) {
                is ImportResult.Ok -> { fileError = null; file = result.ref; selected = result.ref }
                ImportResult.TooLong -> fileError = errLong
                ImportResult.CannotPlay -> fileError = errPlay
                is ImportResult.Rejected -> fileError = when (result.why) {
                    Tones.Refusal.TOO_BIG -> errBig
                    Tones.Refusal.EMPTY -> errEmpty
                    Tones.Refusal.NOT_AUDIO -> errNotAudio
                    Tones.Refusal.TOO_MANY -> errMany
                    null -> errFile
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { preview.stop(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Dimens.l)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Dimens.xl).semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs)) }
            Spacer(Modifier.size(Dimens.s))
            Column(Modifier.selectableGroup()) {
                ToneRow(Ms.MusicNote, stringResource(R.string.tone_default), defaultLabel ?: defaultTone?.let { toneLabel(it) } ?: stringResource(R.string.tone_phone_default),
                    selected == null, { selected = null }, playing == "default", { play("default", null) })
                ToneRow(Ms.LibraryMusic, stringResource(R.string.tone_builtin), stringResource(R.string.tone_builtin_hint),
                    selected?.kind == "bundled", { builtinOpen = !builtinOpen; if (selected?.kind != "bundled") selected = ToneRef("bundled", Tones.BUNDLED.first().first) },
                    false, null)
                if (builtinOpen) Tones.BUNDLED.forEachIndexed { i, (id, _) ->
                    val ref = ToneRef("bundled", id)
                    ToneRow(null, stringResource(R.string.tone_bundled_n, (i + 1).toString()), null, selected == ref, { selected = ref },
                        playing == id, { play(id, ref) }, indent = true)
                }
                ToneRow(Ms.Notifications, stringResource(R.string.tone_phone), picked?.title?.ifEmpty { null } ?: stringResource(R.string.tone_phone_hint),
                    selected?.kind == "system", {
                        val type = if (style == AlertPolicy.Style.ALARM) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
                        picked?.let { intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it.ref.toUri()) }
                        if (runCatching { phonePicker.launch(intent) }.isFailure) phoneError = errPlay
                    }, playing == "system", picked?.let { p -> { play("system", p) } }, error = phoneError)
                ToneRow(Ms.Folder, stringResource(R.string.tone_files),
                    file?.let { stringResource(R.string.list_separator).let { sep -> it.title + sep + stringResource(R.string.tone_copied) } },
                    selected?.kind == "file", { runCatching { filePicker.launch(arrayOf("audio/*")) } },
                    playing == "file", file?.let { f -> { play("file", f) } }, error = fileError)
                ToneRow(Ms.Vibration, stringResource(R.string.tone_silent), null, selected?.kind == "silent", { preview.stop(); playing = null; selected = ToneRef.SILENT }, false, null)
            }
            Row(Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.m), verticalAlignment = Alignment.Top) {
                Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.s))
                Text(stringResource(if (style == AlertPolicy.Style.ALARM) R.string.tone_note_alarm else R.string.tone_note),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val stacked = LocalDensity.current.fontScale >= 1.5f
            Column(Modifier.padding(horizontal = Dimens.l)) {
                ButtonPair(
                    stacked,
                    secondary = { m -> OutlinedButton(onClick = { preview.stop(); onDismiss() }, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
                    primary = { m -> Button(onClick = { preview.stop(); onPick(selected); onDismiss() }, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.done)) } },
                )
            }
        }
    }
}

@Composable
private fun ToneRow(
    icon: ImageVector?, title: String, supporting: String?, selected: Boolean, onSelect: () -> Unit,
    playing: Boolean, onPlay: (() -> Unit)?, indent: Boolean = false, error: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).padding(horizontal = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = Dimens.rowCompact).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(start = if (indent) Dimens.xl else Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(Dimens.m))
            Column(Modifier.weight(1f).padding(vertical = Dimens.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon?.let { Icon(it, null, modifier = Modifier.size(20.dp).padding(end = Dimens.xs)) }
                    if (icon != null) Spacer(Modifier.width(Dimens.xs))
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                }
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (onPlay != null) TooltipIconButton(if (playing) Ms.Stop else Ms.PlayArrow, stringResource(if (playing) R.string.tone_stop else R.string.tone_play), onPlay)
    }
}

private sealed class ImportResult {
    data class Ok(val ref: ToneRef) : ImportResult()
    data object TooLong : ImportResult()
    data object CannotPlay : ImportResult()
    data class Rejected(val why: Tones.Refusal?) : ImportResult()
}

/** Checks that the picked file plays and lasts at most 5 minutes, then keeps a copy (F-014). */
private fun importTone(context: Context, uri: Uri): ImportResult {
    val ms = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(context, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { runCatching { r.release() } }
    }.getOrNull() ?: return ImportResult.CannotPlay
    if (ms > 5 * 60_000L) return ImportResult.TooLong
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    val ref = runCatching { Tones.importFile(context, { context.contentResolver.openInputStream(uri)!! }, name) }.getOrNull()
    return if (ref != null) ImportResult.Ok(ref) else ImportResult.Rejected(Tones.lastRefusal)
}
