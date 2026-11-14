package com.fastkiwi.rememberme.ui.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.ui.BottomActionBar
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.ConfirmDialog
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.Page
import com.fastkiwi.rememberme.ui.SystemScreens
import com.fastkiwi.rememberme.ui.TooltipIconButton
import com.fastkiwi.rememberme.ui.groupShape
import java.time.Instant

/** Whether the important alarm may ring during Do Not Disturb, as far as the app can read it. */
fun dndAllows(dnd: AlertPolicy.Dnd?, alarmsAllowed: Boolean?): Boolean? = when (dnd) {
    AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED, AlertPolicy.Dnd.ALARMS_ONLY -> true
    AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED, AlertPolicy.Dnd.NONE -> false
    AlertPolicy.Dnd.OFF -> alarmsAllowed
    AlertPolicy.Dnd.PRIORITY_UNKNOWN, null -> null
}

/**
 * «المنبّه المهم وعدم الإزعاج» (DESIGN-SPEC §5.13, F-015, F-023): how to let important alarms ring during
 * Do Not Disturb, done by the user in the phone's settings. The app never asks for DND access.
 * Opened from Health and from the editor's alert-style sheet («السماح في «عدم الإزعاج»»).
 */
@Composable
fun DndHelpScreen(dnd: AlertPolicy.Dnd?, alarmsAllowed: Boolean?, onBack: () -> Unit, onOpen: (() -> Unit)? = null) {
    val context = LocalContext.current
    Page(stringResource(R.string.dnd_title), onBack, bottomBar = {
        BottomActionBar {
            Button(onClick = { onOpen?.invoke() ?: SystemScreens.open(context, SystemScreens.dnd()) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Ms.OpenInNew, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(Dimens.s))
                Text(stringResource(R.string.dnd_open))
            }
        }
    }) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
            Column(Modifier.padding(Dimens.xl).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.DoNotDisturbOn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(when (dnd) {
                        null -> R.string.dnd_now_unknown
                        AlertPolicy.Dnd.OFF -> R.string.dnd_now_off
                        else -> R.string.dnd_now_on
                    }), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.l))
                }
                val allowed = dndAllows(dnd, alarmsAllowed)
                val (icon, tint) = when (allowed) {
                    true -> Ms.CheckCircle to MaterialTheme.colorScheme.primary
                    false -> Ms.Error to MaterialTheme.colorScheme.error
                    null -> Ms.Help to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint)
                    Text(stringResource(when (allowed) {
                        true -> R.string.dnd_app_allowed
                        false -> R.string.dnd_app_not_allowed
                        null -> R.string.dnd_app_unknown
                    }), style = MaterialTheme.typography.bodyLarge, color = if (allowed == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = Dimens.l))
                }
            }
        }
        Text(stringResource(R.string.dnd_intro), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.m))
        NumberedStep(1, stringResource(R.string.dnd_step_1))
        NumberedStep(2, stringResource(R.string.dnd_step_2))
        NumberedStep(3, stringResource(R.string.dnd_step_3))
        Row(Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.dnd_names_vary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.m))
        }
    }
}

/**
 * «تقرير التوافق» (DESIGN-SPEC §5.21, F-028): shows the exact text that would be shared, what it leaves
 * out, then «نسخ» · «مشاركة». Nothing leaves the phone unless the user picks where it goes.
 */
@Composable
fun CompatReportScreen(text: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val big = LocalDensity.current.fontScale >= 1.5f
    val title = stringResource(R.string.compat_title)
    val copied = stringResource(R.string.report_copied)
    Page(title, onBack, bottomBar = {
        BottomActionBar {
            ButtonPair(big,
                secondary = { m ->
                    OutlinedButton(onClick = {
                        runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text)) }
                        // Android 13 and later confirm a copy themselves.
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }, modifier = m.heightIn(min = 56.dp)) {
                        Icon(Ms.ContentCopy, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.size(Dimens.s)); Text(stringResource(R.string.compat_copy))
                    }
                },
                primary = { m ->
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, modifier = m.heightIn(min = 56.dp)) {
                        Icon(Ms.Share, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.size(Dimens.s)); Text(stringResource(R.string.compat_share_btn))
                    }
                })
        }
    }) {
        Text(stringResource(R.string.compat_page_intro), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.m))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
            // The report is Latin text: it reads left to right inside the Arabic page.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(Dimens.xl))
            }
        }
        Text(stringResource(R.string.compat_excludes), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = Dimens.xl, end = Dimens.xl, top = Dimens.l).semantics { heading() })
        listOf(R.string.compat_ex_content, R.string.compat_ex_ids, R.string.compat_ex_place).forEach {
            Row(Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.m))
            }
        }
    }
}

private fun eventRes(e: AlertLog.Event) = when (e) {
    AlertLog.Event.FIRED -> R.string.alev_fired
    AlertLog.Event.POSTED -> R.string.alev_posted
    AlertLog.Event.FULL_SCREEN -> R.string.alev_full_screen
    AlertLog.Event.SCREEN_SHOWN -> R.string.alev_screen_shown
    AlertLog.Event.PLAY_STARTED -> R.string.alev_play_started
    AlertLog.Event.PLAY_FAILED -> R.string.alev_play_failed
    AlertLog.Event.FALLBACK -> R.string.alev_fallback
    AlertLog.Event.VIBRATED -> R.string.alev_vibrated
    AlertLog.Event.QUIET -> R.string.alev_quiet
    AlertLog.Event.DONE -> R.string.alev_done
    AlertLog.Event.SNOOZE -> R.string.alev_snooze
    AlertLog.Event.SKIP -> R.string.alev_skip
    AlertLog.Event.LATER -> R.string.alev_later
    AlertLog.Event.DISMISSED -> R.string.alev_dismissed
    AlertLog.Event.REPOSTED -> R.string.alev_reposted
    AlertLog.Event.RING_TIMEOUT -> R.string.alev_ring_timeout
    AlertLog.Event.LATE -> R.string.alev_late
}

/** A reason code in words, or null for codes that say nothing to a person (source indexes, exception names). */
private fun reasonRes(code: String): Int? = when (code) {
    "tone missing" -> R.string.alr_tone_missing
    "service", "foreground" -> R.string.alr_service
    "play" -> R.string.alr_play
    AlertPolicy.Quiet.RINGER_SILENT.name -> R.string.alr_silent
    AlertPolicy.Quiet.RINGER_VIBRATE.name -> R.string.alr_vibrate
    AlertPolicy.Quiet.DND.name -> R.string.alr_dnd
    AlertPolicy.Quiet.CHANNEL_MUTED.name -> R.string.alr_channel
    AlertPolicy.Quiet.SOUND_OFF.name -> R.string.alr_sound_off
    AlertPolicy.Quiet.SILENT_TONE.name -> R.string.alr_silent_tone
    else -> null
}

/** The second line of an alert-log row: test or not, style, lateness, reason, joined with «، ». */
private fun alertLogMeta(context: Context, e: AlertLog.Entry): String = L10n.join(context, listOf(
    if (e.key == com.fastkiwi.rememberme.alarm.TestAlarm.LOG_KEY) context.getString(R.string.alog_test) else null,
    when (e.style) {
        AlertPolicy.Style.ALARM.name -> context.getString(R.string.test_style_alarm)
        AlertPolicy.Style.NORMAL.name -> context.getString(R.string.test_style_normal)
        else -> null
    },
    e.lateMs.takeIf { it >= 1000 }?.let {
        context.getString(R.string.alog_late, if (it < 120_000) seconds(context, it) else L10n.duration(context, it / 60_000))
    },
    reasonRes(e.code)?.let(context::getString),
))

/** At most this many events are listed (the log keeps 500). */
const val ALERT_LOG_SHOWN = 200

/**
 * «سجل التنبيهات» (F-024): the newest events first, each with its time, the event in words, how late it
 * was and the alert style. No title, note or id: the log never holds them.
 */
@Composable
fun AlertLogScreen(entries: List<AlertLog.Entry>, onClear: () -> Unit, onBack: () -> Unit, now: Instant = Instant.now()) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    Page(stringResource(R.string.alog_title), onBack, actions = {
        if (entries.isNotEmpty()) Box {
            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.alog_clear)) }, onClick = { menu = false; confirm = true },
                    leadingIcon = { Icon(Ms.Delete, null) }, modifier = Modifier.heightIn(min = Dimens.touch))
            }
        }
    }) {
        Text(stringResource(R.string.alog_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        if (entries.isEmpty()) {
            Text(stringResource(R.string.alog_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.l))
            return@Page
        }
        val shown = entries.take(ALERT_LOG_SHOWN)
        shown.forEachIndexed { i, e ->
            val meta = alertLogMeta(context, e)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(i, shown.size),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = 2.dp)) {
                Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(stringResource(eventRes(e.event)), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f))
                        Text(L10n.dueText(context, Instant.ofEpochMilli(e.at), now), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Dimens.s))
                    }
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirm) ConfirmDialog(stringResource(R.string.alog_clear_q), stringResource(R.string.alog_clear_body), stringResource(R.string.alog_clear),
        onConfirm = onClear, onDismiss = { confirm = false }, destructive = true)
}
