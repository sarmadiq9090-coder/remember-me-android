package com.fastkiwi.rememberme.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Calendar
import com.fastkiwi.rememberme.core.DayBucket
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.ui.DayNumber
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.HijriNumber
import com.fastkiwi.rememberme.ui.L10n
import java.time.LocalDate
import java.time.YearMonth

/**
 * A month: weekday letters from the week start, a 6 × 7 grid (Gregorian number, the Hijri number when on,
 * a count pill with the number, never a dot; past days without a count; other months at 60 %; today a
 * filled primary circle; the selected day a 2 dp primary outline), then the selected day and its rows.
 */
@Composable
internal fun MonthGrid(
    days: List<DayBucket>,
    month: YearMonth,
    selected: LocalDate,
    weekStart: Int,
    fmt: CalFormat,
    hijri: Boolean,
    hijriAdjust: Int,
    onSelectDay: (LocalDate) -> Unit,
    onOpen: (Task) -> Unit,
    row: @Composable (Task) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.fabClearance)) {
        PeriodNav(
            stringResource(R.string.cal_prev_month), stringResource(R.string.cal_next_month),
            { onSelectDay(selected.minusMonths(1)) }, { onSelectDay(selected.plusMonths(1)) },
            null, if (hijri) fmt.hijriSpan(month.atDay(1), month.atEndOfMonth(), hijriAdjust) else null,
        )
        Column(Modifier.padding(horizontal = Dimens.s)) {
            // Narrow weekday letters (س ح ن ث ر خ ج), the first at the start; the cells carry the full names.
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                for (d in Calendar.weekdays(weekStart)) Text(
                    L10n.weekdayLetter(context, d), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = Dimens.s),
                )
            }
            for (week in days.chunked(7)) Row(Modifier.fillMaxWidth()) {
                for (b in week) DayCell(b, month, selected, fmt, hijri, hijriAdjust, Modifier.weight(1f)) { onSelectDay(b.date) }
            }
        }
        val day = days.firstOrNull { it.date == selected }
        val items = day?.items.orEmpty()
        DayHeader(if (items.isEmpty()) fmt.dayMonth(selected) else fmt.join(fmt.dayMonth(selected), fmt.count(items.size)), today = false)
        if (items.isEmpty()) Text(
            stringResource(R.string.cal_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.s),
        )
        items.forEachIndexed { i, o ->
            // The real occurrence is Home's row (Done, swipe); a projected later time opens the reminder.
            GroupItem(i, items.size) { if (o.projected) AgendaRow(o, fmt, onOpen) else row(o.task) }
        }
    }
}

/** «السبت 14 نوفمبر، 5 تذكيرات، اليوم». */
internal fun cellDescription(fmt: CalFormat, date: LocalDate, count: Int, today: Boolean): String =
    fmt.join(fmt.dayMonth(date), if (count > 0) fmt.count(count) else null, if (today) fmt.todayWord else null)

@Composable
private fun DayCell(b: DayBucket, month: YearMonth, selected: LocalDate, fmt: CalFormat, hijri: Boolean, hijriAdjust: Int, modifier: Modifier, onClick: () -> Unit) {
    val date = b.date
    val today = date == fmt.today
    val isSelected = date == selected
    // Past days show no count: their times have gone (an overdue reminder still shows under the grid).
    val count = if (date.isBefore(fmt.today)) 0 else b.count
    val cd = cellDescription(fmt, date, count, today)
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    val large = LocalDensity.current.fontScale >= 1.5f
    Box(
        modifier.heightIn(min = 56.dp).padding(2.dp)
            .then(if (isSelected) Modifier.border(2.dp, primary, shape) else Modifier)
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = cd; this.selected = isSelected; role = Role.Button }
            .alpha(if (YearMonth.from(date) == month) 1f else 0.6f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = Dimens.xs)) {
            Box(
                Modifier.sizeIn(minWidth = 34.dp, minHeight = 34.dp).then(if (today) Modifier.background(primary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    style = if (today) DayNumber.copy(fontWeight = FontWeight.Bold) else DayNumber,
                    color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (hijri) Text(fmt.hijriDay(date, hijriAdjust), style = HijriNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // With large text the corner has no room: the pill goes under the numbers instead of over them.
            if (count > 0 && large) CountPill(count)
        }
        if (count > 0 && !large) CountPill(count, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun CountPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = 18.dp, minHeight = 18.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
