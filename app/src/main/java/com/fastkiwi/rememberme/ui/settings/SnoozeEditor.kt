package com.fastkiwi.rememberme.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.ui.ChoiceDialog
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Group
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.Page
import com.fastkiwi.rememberme.ui.SettingsRow
import kotlinx.serialization.json.JsonArray
import java.time.LocalTime

/**
 * «خيارات التأجيل» (F-043): the six `settings.snoozePresets` slots, in Windows kinds only (`in`, `today`, `tomorrow`,
 * `week`, `pick`). Every write is the full list of six valid presets; nothing else is ever written.
 */
object SnoozeOptions {
    /** The "in N minutes" choices offered (1440 = «غدًا في الوقت نفسه», whole days keep the clock time). */
    val MINUTES = listOf(5, 10, 15, 30, 60, 120, 180, 1440)

    /** True when [list] is six presets that Windows' checkSnoozePreset accepts unchanged. */
    fun valid(list: List<SnoozePreset>): Boolean =
        list.size == SnoozePreset.DEFAULTS.size && list.all { SnoozePreset.parse(it.toJson()) == it }

    /** [list] with slot [index] replaced, or null when the result would not be valid (then nothing is written). */
    fun replace(list: List<SnoozePreset>, index: Int, preset: SnoozePreset): List<SnoozePreset>? {
        if (index !in list.indices) return null
        val next = list.toMutableList().apply { set(index, preset) }
        return next.takeIf(::valid)
    }

    fun toJson(list: List<SnoozePreset>): JsonArray {
        require(valid(list)) { "invalid snooze presets" }
        return JsonArray(list.map { it.toJson() })
    }

    /** A short label for the settings row and the slot rows. */
    fun label(context: Context, p: SnoozePreset): String = when (p) {
        is SnoozePreset.In -> if (p.minutes == 1440) context.getString(R.string.set_tomorrow_same) else L10n.duration(context, p.minutes.toLong())
        is SnoozePreset.Today -> context.getString(R.string.day_at_time, context.getString(R.string.today), clock(context, p.time))
        is SnoozePreset.Tomorrow -> context.getString(R.string.day_at_time, context.getString(R.string.tomorrow), clock(context, p.time))
        SnoozePreset.Week -> context.getString(R.string.next_week)
        SnoozePreset.Pick -> context.getString(R.string.pick_time)
    }

    fun clock(context: Context, t: LocalTime): String =
        L10n.time(context, java.time.LocalDate.of(2026, 1, 1).atTime(t).atZone(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC)

    /** «5، 10، 15، 30 دقيقة، ساعة، غدًا»-style summary: the six labels joined with the list separator. */
    fun summary(context: Context, list: List<SnoozePreset>): String =
        list.joinToString(context.getString(R.string.list_separator)) { label(context, it) }
}

/** The editor page: six slot rows; a tap opens a single-choice dialog of Windows-kind choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeEditorScreen(presets: List<SnoozePreset>, onChange: (List<SnoozePreset>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var slot by rememberSaveable { mutableStateOf(-1) }
    var timeFor by rememberSaveable { mutableStateOf<String?>(null) }
    Page(stringResource(R.string.set_snooze_presets), onBack) {
        Text(stringResource(R.string.set_snooze_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        Group(presets.mapIndexed { i, p ->
            { SettingsRow(if (i == 0) Ms.Snooze else Ms.Schedule, stringResource(R.string.set_snooze_slot, (i + 1).toString()), SnoozeOptions.label(context, p), { slot = i }) }
        })
        Spacer(Modifier.size(Dimens.s))
        Group(listOf({ SettingsRow(Ms.RestartAlt, stringResource(R.string.set_snooze_reset), null, { onChange(SnoozePreset.DEFAULTS) }) }))
    }
    if (slot >= 0 && timeFor == null) {
        val options = SnoozeOptions.MINUTES.map { "in:$it" to SnoozeOptions.label(context, SnoozePreset.In(it)) } + listOf(
            "today" to stringResource(R.string.set_snooze_today_at),
            "tomorrow" to stringResource(R.string.set_snooze_tomorrow_at),
            "week" to stringResource(R.string.next_week),
            "pick" to stringResource(R.string.pick_time),
        )
        val current = when (val p = presets[slot]) {
            is SnoozePreset.In -> "in:${p.minutes}"; is SnoozePreset.Today -> "today"; is SnoozePreset.Tomorrow -> "tomorrow"
            SnoozePreset.Week -> "week"; SnoozePreset.Pick -> "pick"
        }
        ChoiceDialog(stringResource(R.string.set_snooze_slot, (slot + 1).toString()), options, current, { code ->
            when {
                code.startsWith("in:") -> SnoozeOptions.replace(presets, slot, SnoozePreset.In(code.removePrefix("in:").toInt()))?.let(onChange)
                code == "week" -> SnoozeOptions.replace(presets, slot, SnoozePreset.Week)?.let(onChange)
                code == "pick" -> SnoozeOptions.replace(presets, slot, SnoozePreset.Pick)?.let(onChange)
                else -> timeFor = code
            }
        }, { if (timeFor == null) slot = -1 })
    }
    val kind = timeFor
    if (slot >= 0 && kind != null) {
        val old = when (val p = presets[slot]) { is SnoozePreset.Today -> p.time; is SnoozePreset.Tomorrow -> p.time; else -> LocalTime.of(if (kind == "today") 18 else 9, 0) }
        val state = rememberTimePickerState(old.hour, old.minute, L10n.is24(context))
        AlertDialog(
            onDismissRequest = { timeFor = null; slot = -1 },
            title = { Text(stringResource(if (kind == "today") R.string.set_snooze_today_at else R.string.set_snooze_tomorrow_at)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    TimePicker(state)
                    if (kind == "today") Text(stringResource(R.string.set_snooze_today_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            dismissButton = { TextButton(onClick = { timeFor = null; slot = -1 }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                val t = LocalTime.of(state.hour, state.minute)
                val preset = if (kind == "today") SnoozePreset.Today(t) else SnoozePreset.Tomorrow(t)
                val next = SnoozeOptions.replace(presets, slot, preset)
                TextButton(enabled = next != null, onClick = { next?.let(onChange); timeFor = null; slot = -1 }) { Text(stringResource(R.string.set_ok)) }
            },
        )
    }
}
