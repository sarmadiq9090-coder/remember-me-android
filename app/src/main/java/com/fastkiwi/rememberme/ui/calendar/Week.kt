package com.fastkiwi.rememberme.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.DayBucket
import com.fastkiwi.rememberme.core.Occurrence
import com.fastkiwi.rememberme.ui.DayNumber
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.Ms
import java.time.LocalDate

/** Lines shown per day before «+2 أخرى». */
internal const val WEEK_LINES = 3

/**
 * A week: previous/next around «14 إلى 20 نوفمبر», then 7 day rows (day name + number at the start,
 * today on a primaryContainer disc with «اليوم»), up to 3 lines then «+N أخرى», or «لا شيء». A tap on a
 * day opens it in the day view.
 */
@Composable
internal fun WeekRows(
    days: List<DayBucket>,
    selected: LocalDate,
    fmt: CalFormat,
    onSelectDay: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PeriodNav(
            stringResource(R.string.cal_prev_week), stringResource(R.string.cal_next_week),
            { onSelectDay(selected.minusDays(7)) }, { onSelectDay(selected.plusDays(7)) },
            fmt.range(days.first().date, days.last().date),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Dimens.s, bottom = Dimens.fabClearance)) {
            days.forEachIndexed { i, b -> GroupItem(i, days.size) { WeekDayRow(b, fmt) { onOpenDay(b.date) } } }
        }
    }
}

@Composable
private fun WeekDayRow(b: DayBucket, fmt: CalFormat, onClick: () -> Unit) {
    val today = b.date == fmt.today
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = Dimens.l, vertical = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 64.dp).padding(end = Dimens.l), horizontalAlignment = Alignment.CenterHorizontally) {
            if (today) {
                Text(stringResource(R.string.today), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary)
                Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(b.date.dayOfMonth.toString(), style = DayNumber.copy(fontSize = DayNumber.fontSize * 1.4f, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                Text(fmt.weekdayName(b.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(b.date.dayOfMonth.toString(), style = DayNumber.copy(fontSize = DayNumber.fontSize * 1.4f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            if (b.items.isEmpty()) Text(stringResource(R.string.cal_nothing), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (o in b.items.take(WEEK_LINES)) WeekLine(o, fmt)
            val more = b.items.size - WEEK_LINES
            if (more > 0) Text(stringResource(R.string.cal_more_n, more.toString()), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** «5:30 م اجتماع الفريق»; overdue with a warning icon and «متأخر». */
@Composable
private fun WeekLine(o: Occurrence, fmt: CalFormat) {
    val overdue = o.overdue(fmt.now)
    val error = MaterialTheme.colorScheme.error
    // G-03: at large text the title gets its own line (up to 2), instead of a few letters beside the time.
    val big = LocalDensity.current.fontScale >= 1.5f
    val title: @Composable (Modifier) -> Unit = { m ->
        Text(o.task.title, style = MaterialTheme.typography.bodyMedium, maxLines = if (big) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = m)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            if (overdue) Icon(Ms.Warning, null, tint = error, modifier = Modifier.size(16.dp))
            val at = o.at
            if (at == null) Text(stringResource(R.string.no_time), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = if (big) Modifier else Modifier.width(Dimens.timeColumn))
            else Text(fmt.timeText(at), style = MaterialTheme.typography.bodyMedium, color = if (overdue) error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 64.dp))
            if (!big) title(Modifier.weight(1f, fill = false))
            if (overdue) Text(stringResource(R.string.cal_late), style = MaterialTheme.typography.bodySmall, color = error)
        }
        if (big) title(Modifier)
    }
}
