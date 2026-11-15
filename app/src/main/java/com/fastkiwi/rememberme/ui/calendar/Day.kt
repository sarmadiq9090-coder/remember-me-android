package com.fastkiwi.rememberme.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.DayBucket
import com.fastkiwi.rememberme.core.Occurrence
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import java.time.LocalDate

/**
 * One day: previous/next around the date (+ the Hijri date when on), the reminders without a time, and a
 * 24-hour timeline (60 dp per hour) with blocks and, on today, the now-line «الآن 4:20 م».
 */
@Composable
internal fun DayTimeline(
    day: DayBucket,
    fmt: CalFormat,
    hijri: Boolean,
    hijriAdjust: Int,
    onSelectDay: (LocalDate) -> Unit,
    onOpen: (Task) -> Unit,
    onNewOnDay: (LocalDate) -> Unit,
) {
    val date = day.date
    Column(Modifier.fillMaxSize()) {
        PeriodNav(
            stringResource(R.string.cal_prev_day), stringResource(R.string.cal_next_day),
            { onSelectDay(date.minusDays(1)) }, { onSelectDay(date.plusDays(1)) },
            fmt.dayMonth(date), if (hijri) fmt.hijriLine(date, hijriAdjust) else null,
        )
        val untimed = day.items.filter { it.at == null }
        val timed = day.items.filter { it.at != null }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // Open near now on today, else near the first reminder, else at 8:00.
        val startHour = when {
            date == fmt.today -> fmt.now.atZone(fmt.zone).hour - 1
            timed.isNotEmpty() -> timed.first().at!!.atZone(fmt.zone).hour - 1
            else -> 8
        }.coerceIn(0, 23)
        LaunchedEffect(date) { scroll.scrollTo(with(density) { (Dimens.hourHeight * startHour).roundToPx() }) }
        // The reminders without a time stay in view above the scrolling timeline.
        if (untimed.isNotEmpty()) UntimedStrip(untimed, onOpen)
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // G-03: the hour column is as wide as its widest label, so «12 ص» never wraps or loses «ص» at 200 %.
            val labelWidth = hourLabelWidth(fmt)
            val today = date == fmt.today
            Box(Modifier.fillMaxWidth().padding(bottom = Dimens.fabClearance)) {
                HourGrid(fmt, labelWidth) { onNewOnDay(date) }
                Blocks(timed, fmt, onOpen, labelWidth, today)
                if (today) NowLine(fmt, labelWidth)
            }
        }
    }
}

@Composable
private fun UntimedStrip(items: List<Occurrence>, onOpen: (Task) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
    ) {
        Row(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.no_time), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Dimens.l))
            Column(Modifier.weight(1f)) {
                for (o in items) Text(
                    o.task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { onOpen(o.task) }
                        .padding(vertical = Dimens.m),
                )
            }
        }
    }
}

/** The hour column: [Dimens.hourLabel], or wider when the widest hour label needs it (large text). */
@Composable
private fun hourLabelWidth(fmt: CalFormat): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val widest = (0..23).maxOf { measurer.measure(fmt.hour(it), style, softWrap = false, maxLines = 1).size.width }
    return maxOf(Dimens.hourLabel, with(density) { widest.toDp() } + Dimens.xs * 2 + 1.dp)
}

/** 24 hour rows: the label column at the start, an outlineVariant line; a tap adds a reminder on this day. */
@Composable
private fun HourGrid(fmt: CalFormat, labelWidth: Dp, onNew: () -> Unit) {
    val newLabel = stringResource(R.string.new_reminder)
    Column(Modifier.fillMaxWidth()) {
        for (h in 0..23) {
            Row(
                Modifier.fillMaxWidth().height(Dimens.hourHeight).clickable(onClickLabel = newLabel, role = Role.Button, onClick = onNew),
            ) {
                Text(
                    fmt.hour(h), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.width(labelWidth).padding(horizontal = Dimens.xs).offset(y = (-8).dp),
                )
                HorizontalDivider(Modifier.weight(1f).padding(end = Dimens.l), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * Blocks at their time (1 dp per minute); a block that would overlap the one before moves just below it. On
 * today the «الآن 4:20 م» label is laid out with them: just above the now-line when there is room, else just
 * below it, and the blocks after it move down, so the label never covers a title (G-03, large text).
 */
@Composable
private fun Blocks(items: List<Occurrence>, fmt: CalFormat, onOpen: (Task) -> Unit, labelWidth: Dp, showNow: Boolean) {
    val tops = items.map { o -> o.at!!.atZone(fmt.zone).toLocalTime().let { it.hour * 60 + it.minute }.dp }
    val nowAt = fmt.now.atZone(fmt.zone).toLocalTime().let { it.hour * 60 + it.minute }.dp
    Layout(
        content = {
            for (o in items) Block(o, fmt, onOpen)
            if (showNow) Text(
                stringResource(R.string.cal_now_at, fmt.time(fmt.now)),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().testTag("cal-now-label"),
            )
        },
        modifier = Modifier.fillMaxWidth().padding(start = labelWidth, end = Dimens.l),
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val all = measurables.map { it.measure(loose) }
        val label = if (showNow) all.last() else null
        val placeables = if (showNow) all.dropLast(1) else all
        val gap = 2.dp.roundToPx()
        val nowY = nowAt.roundToPx()
        var bottom = Int.MIN_VALUE
        var labelY: Int? = null
        fun placeLabel(p: Placeable): Int {
            val above = nowY - gap - p.height
            val y = if (above >= bottom + gap) above else maxOf(nowY + 8.dp.roundToPx(), bottom + gap)
            bottom = maxOf(bottom, y + p.height)
            return y
        }
        val ys = placeables.mapIndexed { i, p ->
            val top = tops[i].roundToPx()
            if (label != null && labelY == null && top >= nowY) labelY = placeLabel(label)
            val y = maxOf(top, bottom + gap)
            bottom = y + p.height
            y
        }
        if (label != null && labelY == null) labelY = placeLabel(label)
        val height = maxOf(constraints.minHeight, (Dimens.hourHeight * 24).roundToPx(), if (all.isEmpty()) 0 else bottom)
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p -> p.place(0, ys[i]) }
            label?.place(0, labelY ?: 0)
        }
    }
}

@Composable
private fun Block(o: Occurrence, fmt: CalFormat, onOpen: (Task) -> Unit) {
    val overdue = o.overdue(fmt.now)
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = { onOpen(o.task) }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touch),
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.m, vertical = Dimens.xs).semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            if (overdue) Icon(Ms.Warning, null, tint = error, modifier = Modifier.size(18.dp))
            else if (o.snoozed) Icon(Ms.Snooze, null, tint = muted, modifier = Modifier.size(18.dp))
            Text(fmt.timeText(o.at!!), style = MaterialTheme.typography.bodyLarge, color = if (overdue) error else MaterialTheme.colorScheme.onSurface)
            Text(o.task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (overdue) Text(stringResource(R.string.cal_late), style = MaterialTheme.typography.bodySmall, color = error)
            else if (o.snoozed) Text(stringResource(R.string.cal_snoozed), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/** The now-line in primary (its «الآن 4:20 م» label is laid out with the blocks, see [Blocks]). */
@Composable
private fun NowLine(fmt: CalFormat, labelWidth: Dp) {
    val local = fmt.now.atZone(fmt.zone).toLocalTime()
    val y: Dp = (local.hour * 60 + local.minute).dp
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().offset(y = y - 6.dp).padding(start = labelWidth - 6.dp, end = Dimens.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(primary, CircleShape))
        Spacer(Modifier.weight(1f).height(2.dp).background(primary))
    }
}
