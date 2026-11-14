package com.fastkiwi.rememberme.ui.home

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.snoozeTime
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SheetShape
import com.fastkiwi.rememberme.ui.presetLabel
import java.time.Instant
import java.time.ZoneId

/** A snooze choice that applies now: its label, the moment it snoozes to, and that moment as text. */
data class SnoozeChoice(val label: String, val at: Instant, val result: String)

/**
 * The presets that give a time now, in the user's order (F-043): «اختر وقتًا…» and a preset that is not
 * possible now (today's time less than an hour away) are left out; «أجّل حتى…» stands for picking.
 */
fun snoozeChoices(context: Context, presets: List<SnoozePreset>, now: Instant, zone: ZoneId): List<SnoozeChoice> =
    presets.mapNotNull { p ->
        if (p is SnoozePreset.Pick) return@mapNotNull null
        val at = snoozeTime(p, now, zone) ?: return@mapNotNull null
        SnoozeChoice(presetLabel(context, p, now, zone), at, snoozeResult(context, at, now, zone))
    }.distinctBy { it.at }

/** «حتى 4:25 م» later today; «الأحد، 2:00 م» within the week; the date beyond it. */
fun snoozeResult(context: Context, at: Instant, now: Instant, zone: ZoneId): String {
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val time = L10n.time(context, at, zone)
    return when {
        day == today -> context.getString(R.string.until_time, time)
        day.isAfter(today) && day.isBefore(today.plusDays(7)) -> context.getString(R.string.day_at_time, L10n.weekdayName(context, jsDay(day)), time)
        else -> L10n.dueText(context, at, now, zone)
    }
}

/**
 * The Snooze sheet (DESIGN-SPEC §5.9): «أجّل» and what is snoozed; a row per preset with its resulting
 * time at the end (a tap applies at once); «أجّل حتى…»; «التكرار لا يتغيّر» when a repeat is involved;
 * «إلغاء» at the end. No filled button: choices apply on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(
    title: String,
    presets: List<Pair<String, String>>,
    onPick: (Int) -> Unit,
    onPickTime: () -> Unit,
    onDismiss: () -> Unit,
    repeats: Boolean = false,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Dimens.s)) {
            Text(stringResource(R.string.snooze_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.xl).semantics { heading() })
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.padding(horizontal = Dimens.xl).padding(bottom = Dimens.s))
            presets.forEachIndexed { i, (label, result) ->
                SheetRow(Ms.Snooze, label, result) { onPick(i) }
            }
            HorizontalDivider(Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs))
            SheetRow(Ms.Event, stringResource(R.string.snooze_until), null, onPickTime)
            if (repeats) Row(Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.Repeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.m))
                Text(stringResource(R.string.repeat_unchanged), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, result: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.xl, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.l))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (result != null) Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.m))
    }
}
