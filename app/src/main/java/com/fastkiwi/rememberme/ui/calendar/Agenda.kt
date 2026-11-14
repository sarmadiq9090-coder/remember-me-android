package com.fastkiwi.rememberme.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.DayBucket
import com.fastkiwi.rememberme.core.Occurrence
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.ui.AgendaTime
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.TaskMeta

/** Continuous from today, one header per day with reminders, empty days skipped. */
@Composable
internal fun AgendaList(days: List<DayBucket>, fmt: CalFormat, onOpen: (Task) -> Unit) {
    if (days.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(Dimens.xxl), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.cal_agenda_empty), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.fabClearance)) {
        for (b in days) {
            item(key = "h-${b.date}") { DayHeader(fmt.agendaHeader(b.date), today = b.date == fmt.today) }
            itemsIndexed(b.items, key = { _, o -> "${b.date}-${o.key}" }) { i, o ->
                GroupItem(i, b.items.size) { AgendaRow(o, fmt, onOpen) }
            }
        }
    }
}

/** A date heading: titleSmall, today in primary. */
@Composable
internal fun DayHeader(text: String, today: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(start = Dimens.xxl, end = Dimens.l, top = Dimens.l, bottom = Dimens.s)
            .semantics { heading() },
    )
}

/**
 * A 56 dp agenda row: the 76 dp time column («بلا وقت» without a time), the title, and under it the
 * overdue mark («متأخر ساعتين» with a warning), the snoozed mark, then repeat, priority and category.
 */
@Composable
internal fun AgendaRow(o: Occurrence, fmt: CalFormat, onOpen: (Task) -> Unit) {
    val t = o.task
    val overdue = o.overdue(fmt.now)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact)
            .clickable(role = Role.Button) { onOpen(t) }
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(Dimens.timeColumn)) {
            if (o.at == null) Text(stringResource(R.string.no_time), style = MaterialTheme.typography.bodyMedium, color = muted)
            else Text(fmt.timeText(o.at!!), style = AgendaTime, color = if (overdue) error else MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f).padding(start = Dimens.s)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (overdue) MarkLine(Ms.Warning, fmt.late(o.at!!), error)
            else if (o.snoozed) MarkLine(Ms.Snooze, stringResource(R.string.cal_snoozed), muted)
            Box(Modifier.padding(top = 2.dp)) { TaskMeta(t) }
        }
    }
}

@Composable
internal fun MarkLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Dimens.xs))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
