package com.fastkiwi.rememberme.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.data.History
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.TooltipIconButton
import com.fastkiwi.rememberme.ui.earlyLabel
import com.fastkiwi.rememberme.ui.repeatLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
private fun icon(k: History.Kind): ImageVector = when (k) {
    History.Kind.CREATED -> Ms.Add
    History.Kind.MOVED -> Ms.Event
    History.Kind.SNOOZED -> Ms.Snooze
    History.Kind.DONE -> Ms.Check
    History.Kind.REPEAT -> Ms.Repeat
    History.Kind.ALERT_ADDED -> Ms.Notifications
}

/** What an event says (DESIGN-SPEC §5.19): «أُجّل 10 دقائق», «غُيّر الموعد: من 5:00 م إلى 9:00 ص»… */
@Composable
fun historyText(e: History.Event, now: Instant, zone: ZoneId): String {
    val context = LocalContext.current
    return when (e.kind) {
        History.Kind.CREATED -> stringResource(R.string.ev_created)
        History.Kind.DONE -> stringResource(R.string.ev_done)
        History.Kind.MOVED -> when {
            e.from != null && e.to != null -> stringResource(R.string.ev_moved, L10n.dueText(context, e.from, now, zone), L10n.dueText(context, e.to, now, zone))
            e.to != null -> stringResource(R.string.ev_moved_to, L10n.dueText(context, e.to, now, zone))
            else -> stringResource(R.string.ev_moved_to, stringResource(R.string.no_date))
        }
        History.Kind.SNOOZED -> {
            val minutes = if (e.from != null && e.to != null) ((e.to.toEpochMilli() - e.from.toEpochMilli()) / 60_000).coerceAtLeast(1) else 0
            stringResource(R.string.ev_snoozed, L10n.duration(context, minutes))
        }
        History.Kind.REPEAT -> stringResource(R.string.ev_repeat, repeatLabel(Repeat.of(e.repeat)))
        History.Kind.ALERT_ADDED -> stringResource(R.string.ev_alert_added, earlyLabel(e.minutes ?: 0))
    }
}

/**
 * «السجل» (DESIGN-SPEC §5.19, F-072): the reminder's title under the bar, day headers, newest first; each event
 * a 40 dp disc with a connector, its words and its time; the footer says the history stays on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(title: String, events: List<History.Event>, now: Instant, zone: ZoneId, onBack: () -> Unit) {
    val context = LocalContext.current
    val days = events.sortedByDescending { it.at }.groupBy { it.at.atZone(zone).toLocalDate() }
    val today = now.atZone(zone).toLocalDate()
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.history), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), onBack) },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (events.isEmpty()) item {
                Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimens.xl))
            }
            days.forEach { (day, list) ->
                item {
                    val header = when (day) {
                        today -> stringResource(R.string.today)
                        today.minusDays(1) -> stringResource(R.string.yesterday)
                        else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", L10n.current(context)).format(day)
                    }
                    Text(header, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = Dimens.xxl, end = Dimens.l, top = Dimens.l, bottom = Dimens.s).semantics { heading() })
                }
                list.forEachIndexed { i, e ->
                    item {
                        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).padding(horizontal = Dimens.l).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.Top) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(icon(e.kind), null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                                }
                                if (i < list.size - 1) Box(Modifier.width(2.dp).height(Dimens.l).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                            Spacer(Modifier.width(Dimens.l))
                            Text(historyText(e, now, zone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = Dimens.s))
                            Text(L10n.time(context, e.at, zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Dimens.s, start = Dimens.s))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(Dimens.xl), verticalAlignment = Alignment.Top) {
                    Icon(Ms.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.s))
                    Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
