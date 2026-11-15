package com.fastkiwi.rememberme.ui.calendar

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Calendar
import com.fastkiwi.rememberme.core.Hijri
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.ui.ChoiceDialog
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.LocalReducedMotion
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SettingsRow
import com.fastkiwi.rememberme.ui.TooltipIconButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The calendar modes of Home (DESIGN-SPEC §5.16, F-064). Home's `HomeView { LIST, AGENDA, DAY, WEEK, MONTH }`
 * maps one to one by name: `CalView.valueOf(homeView.name)` for every value except LIST.
 */
enum class CalView { AGENDA, DAY, WEEK, MONTH }

/**
 * The calendar views of Home: header (period title, «اليوم», the view menu, settings), the view segments,
 * then agenda, day, week or month. Stateless: the caller keeps [view] and [selected] (rememberSaveable)
 * and Back from a view returns to the list (Home's job).
 *
 * [row] draws a real reminder the way Home does (Done target, swipe); it is used under the month grid.
 * Later times of repeating reminders are projected for display only and drawn as compact rows that open
 * the reminder. [viewMenu] is Home's `ViewMenuButton`, drawn here on its active `secondaryContainer` disc.
 * [showHeader] = false when the caller draws [CalendarHeader] in its own top bar.
 */
@Composable
fun CalendarScreen(
    view: CalView,
    state: AppState,
    now: Instant,
    zone: ZoneId,
    selected: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    onViewChange: (CalView) -> Unit,
    onToday: () -> Unit,
    onOpen: (Task) -> Unit,
    onNewOnDay: (LocalDate) -> Unit,
    row: @Composable (Task) -> Unit,
    hijri: Boolean,
    hijriAdjust: Int,
    modifier: Modifier = Modifier,
    viewMenu: (@Composable () -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    showHeader: Boolean = true,
) {
    val fmt = rememberCalFormat(zone, now)
    val weekStart = Repo.weekStart(state)
    Column(modifier.fillMaxSize()) {
        if (showHeader) CalendarHeader(fmt.monthTitle(YearMonth.from(selected)), onToday, viewMenu, onSettings)
        // 600–839 dp: one pane, centred (DESIGN-SPEC §3.7).
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = Dimens.maxContent).fillMaxWidth().fillMaxSize()) {
                ViewSegments(view, onViewChange)
                val reduced = LocalReducedMotion.current
                Crossfade(view, animationSpec = if (reduced) snap() else tween(200), label = "calendar-view") { v ->
                    val tasks = state.tasks
                    when (v) {
                        CalView.AGENDA -> AgendaList(remember(tasks, now, zone) { Calendar.agenda(tasks, fmt.today, now, zone) }, fmt, onOpen)
                        CalView.DAY -> DayTimeline(
                            remember(tasks, selected, now, zone) { Calendar.day(tasks, selected, fmt.today, now, zone) }, fmt, hijri, hijriAdjust,
                            onSelectDay, onOpen, onNewOnDay,
                        )
                        CalView.WEEK -> WeekRows(
                            remember(tasks, selected, weekStart, now, zone) { Calendar.weekBuckets(tasks, selected, weekStart, fmt.today, now, zone) }, selected, fmt,
                            onSelectDay = { onSelectDay(it) },
                            onOpenDay = { onSelectDay(it); onViewChange(CalView.DAY) },
                        )
                        CalView.MONTH -> MonthGrid(
                            remember(tasks, YearMonth.from(selected), weekStart, now, zone) { Calendar.monthBuckets(tasks, YearMonth.from(selected), weekStart, fmt.today, now, zone) },
                            YearMonth.from(selected), selected, weekStart, fmt, hijri, hijriAdjust, onSelectDay, onOpen, row,
                        )
                    }
                }
            }
        }
    }
}

/** The period title for the top bar («نوفمبر 2026»). */
@Composable
fun calendarTitle(selected: LocalDate, zone: ZoneId, now: Instant): String = rememberCalFormat(zone, now).monthTitle(YearMonth.from(selected))

/**
 * The top bar of the calendar views: the period, «اليوم» (jump to today; only here, never in the list),
 * the view menu shown active, settings. No window insets: it sits inside Home's Scaffold content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHeader(
    title: String,
    onToday: () -> Unit,
    viewMenu: (@Composable () -> Unit)?,
    onSettings: (() -> Unit)?,
    windowInsets: WindowInsets = WindowInsets(0),
) {
    TopAppBar(
        title = {
            // Two lines at large text, so «November 2026» is never cut (QC, English at 200 %).
            Text(title, maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading(); paneTitle = title })
        },
        actions = {
            TextButton(onClick = onToday, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.today)) }
            if (viewMenu != null) Box(
                Modifier.size(Dimens.touch).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { viewMenu() }
            if (onSettings != null) TooltipIconButton(Ms.Settings, stringResource(R.string.settings), onSettings)
        },
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
internal fun viewLabel(v: CalView): String = stringResource(
    when (v) {
        CalView.AGENDA -> R.string.cal_view_agenda
        CalView.DAY -> R.string.cal_view_day
        CalView.WEEK -> R.string.cal_view_week
        CalView.MONTH -> R.string.cal_view_month
    },
)

/** «أجندة | يوم | أسبوع | شهر»; from font scale 1.5 a row that opens a single-choice dialog (§3.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSegments(view: CalView, onSelect: (CalView) -> Unit) {
    val labels = CalView.entries.associateWith { viewLabel(it) }
    if (LocalDensity.current.fontScale >= 1.5f) {
        var open by rememberSaveable { mutableStateOf(false) }
        val title = stringResource(R.string.cal_view_label)
        SettingsRow(Ms.CalendarViewMonth, title, labels.getValue(view), onClick = { open = true })
        if (open) ChoiceDialog(
            title, CalView.entries.map { it.name to labels.getValue(it) }, view.name,
            onSelect = { onSelect(CalView.valueOf(it)) }, onDismiss = { open = false },
        )
        return
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
        CalView.entries.forEachIndexed { i, v ->
            SegmentedButton(
                selected = v == view, onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, CalView.entries.size),
                modifier = Modifier.heightIn(min = Dimens.touch),
            ) { Text(labels.getValue(v), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Previous / next around a period title; the chevrons mirror in RTL (previous at the start). */
@Composable
internal fun PeriodNav(prevLabel: String, nextLabel: String, onPrev: () -> Unit, onNext: () -> Unit, title: String?, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(horizontal = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(Ms.ChevronLeft, prevLabel, onPrev)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), textAlign = TextAlign.Center)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        TooltipIconButton(Ms.ChevronRight, nextLabel, onNext)
    }
}

@Composable
internal fun rememberCalFormat(zone: ZoneId, now: Instant): CalFormat {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(context, config, zone, now) { CalFormat(context, zone, now) }
}

/** Dates and times of the calendar views in the app language, Western digits, «إلى» for ranges. */
internal class CalFormat(private val context: Context, val zone: ZoneId, val now: Instant) {
    val locale = L10n.current(context)
    val today: LocalDate = now.atZone(zone).toLocalDate()
    val todayWord: String = context.getString(R.string.today)
    private val arabic = locale.language == "ar"
    private val is24 = L10n.is24(context)
    private fun f(p: String) = DateTimeFormatter.ofPattern(p, locale)

    /** «السبت 14 نوفمبر», with the year when it is not this year. */
    fun dayMonth(d: LocalDate): String = f(if (d.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy").format(d)
    fun monthTitle(m: YearMonth): String = f("MMMM yyyy").format(m)
    fun weekdayName(d: LocalDate): String = f("EEEE").format(d)

    /** «اليوم، السبت 14 نوفمبر», «غدًا، الأحد 15 نوفمبر», else the date. */
    fun agendaHeader(d: LocalDate): String = when (d) {
        today -> join(context.getString(R.string.today), dayMonth(d))
        today.plusDays(1) -> join(context.getString(R.string.tomorrow), dayMonth(d))
        else -> dayMonth(d)
    }

    /** «14 إلى 20 نوفمبر», «28 نوفمبر إلى 4 ديسمبر» (never a dash). */
    fun range(a: LocalDate, b: LocalDate): String {
        val year = if (b.year != today.year || a.year != b.year) " yyyy" else ""
        val first = when {
            a.year != b.year -> f("d MMMM yyyy").format(a)
            a.month != b.month -> f("d MMMM").format(a)
            else -> f("d").format(a)
        }
        return context.getString(R.string.cal_range, first, f("d MMMM$year").format(b))
    }

    fun time(at: Instant): String = L10n.time(context, at, zone)

    /** The time with «م/ص» smaller, as one text that may wrap at large font sizes. */
    fun timeText(at: Instant): AnnotatedString {
        val (digits, ampm) = L10n.timeParts(context, at, zone)
        return buildAnnotatedString {
            append(digits)
            if (ampm != null) { append(" "); withStyle(SpanStyle(fontSize = 0.75.em, fontFeatureSettings = "normal")) { append(ampm) } }
        }
    }

    /** «1 م» / "1 PM", or "13:00" on a 24-hour phone. */
    fun hour(h: Int): String = f(if (is24) "H:mm" else "h a").format(LocalTime.of(h, 0))

    /** «4 جمادى الآخرة 1448». */
    fun hijriLine(d: LocalDate, adjust: Int): String = Hijri.of(d, adjust).let {
        "${it.day} ${if (arabic) Hijri.monthNameAr(it.month) else Hijri.monthNameEn(it.month)} ${it.year}"
    }

    fun hijriDay(d: LocalDate, adjust: Int): String = Hijri.of(d, adjust).day.toString()

    /** The Hijri month(s) a range of days spans: «جمادى الأولى إلى جمادى الآخرة 1448». */
    fun hijriSpan(a: LocalDate, b: LocalDate, adjust: Int): String {
        val x = Hijri.of(a, adjust)
        val y = Hijri.of(b, adjust)
        fun name(m: Int) = if (arabic) Hijri.monthNameAr(m) else Hijri.monthNameEn(m)
        if (x.year == y.year && x.month == y.month) return "${name(x.month)} ${x.year}"
        val first = if (x.year == y.year) name(x.month) else "${name(x.month)} ${x.year}"
        return context.getString(R.string.cal_range, first, "${name(y.month)} ${y.year}")
    }

    fun count(n: Int): String = L10n.plural(context, R.plurals.cal_reminders_n, n.toLong())
    fun late(at: Instant): String = L10n.lateLine(context, at, now, zone)
    fun join(vararg parts: String?): String = L10n.join(context, parts.toList())
}
