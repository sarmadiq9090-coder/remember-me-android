package com.fastkiwi.rememberme.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.TimeText
import com.fastkiwi.rememberme.ui.TooltipIconButton
import com.fastkiwi.rememberme.ui.categoryName
import java.time.Instant
import java.time.format.DateTimeFormatter

/** How wide the window is, in the three classes of DESIGN-SPEC §3.7. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** < 600 dp phone; 600–839 dp one centred pane; ≥ 840 dp list + detail. No adaptive library: the configuration's width. */
@Composable
fun widthClass(): WidthClass {
    // The window's own width (the configuration's screen width is not the window on a split screen).
    val w = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp().value }
    return when {
        w >= Dimens.twoPane.value -> WidthClass.EXPANDED
        w >= Dimens.compactWidth.value -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
}

/**
 * The tablet layout (§3.7, `home-tablet-*.png`): the list pane, 440 dp, at the start; the detail pane fills
 * the rest on `surfaceContainerLow` with 28 dp corners and a 16 dp margin. Only used from 840 dp.
 */
@Composable
fun TwoPane(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(Dimens.listPane).fillMaxHeight()) { list() }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(Dimens.l),
        ) { detail() }
    }
}

/**
 * The detail pane of a reminder: edit and ⋮ at the top end; the time, its day, the title; date, repeat,
 * alerts and category; the note; the steps (tickable); «أجّل» (secondary) and «تم» (primary) at the bottom
 * end for an open reminder. [task] null: nothing chosen yet.
 */
@Composable
fun ReminderDetail(
    task: Task?,
    now: Instant,
    onDone: (Task) -> Unit,
    onSnooze: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    onTrash: (Task) -> Unit,
    onToggleStep: (Task, String) -> Unit,
) {
    if (task == null) {
        Box(Modifier.fillMaxSize().padding(Dimens.xxl), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.home_detail_empty), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    val context = LocalContext.current
    val done = task.completedAt != null
    val overdue = !done && task.dueAt != null && !task.dueAt.isAfter(now)
    var menu by remember(task.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().semantics { paneTitle = task.title }) {
        Row(Modifier.fillMaxWidth().padding(Dimens.s), horizontalArrangement = Arrangement.End) {
            if (!done) TooltipIconButton(Ms.Edit, stringResource(R.string.open_task), { onEdit(task) })
            Box {
                TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Ms.Delete, null) },
                        onClick = { menu = false; onTrash(task) })
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            val due = task.dueAt
            if (due != null) {
                TimeText(due, MaterialTheme.typography.displayMedium, if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, ampmSize = 24)
                Text(dayLine(context, due, now), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (overdue) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.xs))
                    Text(L10n.lateLine(context, due, now), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            } else Text(stringResource(R.string.no_time), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(task.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() },
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            val facts = buildList<Triple<ImageVector, String, String>> {
                if (due != null) add(Triple(Ms.CalendarMonth, stringResource(R.string.date), L10n.date(context, due)))
                add(Triple(Ms.Repeat, stringResource(R.string.repeat), L10n.repeatText(context, task) ?: stringResource(R.string.home_no_repeat)))
                if (due != null) add(Triple(Ms.Notifications, stringResource(R.string.home_alerts), alertsText(context, task)))
                add(Triple(Ms.Label, stringResource(R.string.category), categoryName(task.category)))
            }
            facts.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    pair.forEach { (icon, label, value) -> FactCard(icon, label, value, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (task.body.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Text(task.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Dimens.l))
            }
            if (task.steps.isNotEmpty()) StepsList(task, enabled = !done) { onToggleStep(task, it) }
            Spacer(Modifier.size(Dimens.l))
        }
        // Secondary then primary, at the bottom end of the pane.
        if (!done && task.deletedAt == null) Row(
            Modifier.fillMaxWidth().padding(Dimens.xl), horizontalArrangement = Arrangement.spacedBy(Dimens.m, Alignment.End),
        ) {
            OutlinedButton(onClick = { onSnooze(task) }, modifier = Modifier.heightIn(min = Dimens.touch).width(160.dp)) {
                Text(stringResource(R.string.snooze_title))
            }
            Button(onClick = { onDone(task) }, modifier = Modifier.heightIn(min = Dimens.touch).width(160.dp)) {
                Icon(Ms.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.s))
                Text(stringResource(R.string.done))
            }
        }
    }
}

/** «اليوم، السبت 14 نوفمبر»: a near day by name, then the date; other days by their date alone. */
private fun dayLine(context: android.content.Context, at: Instant, now: Instant): String {
    val zone = com.fastkiwi.rememberme.Repo.zone()
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.atZone(zone).toLocalDate())
    val rel = L10n.dayText(context, at, now, zone)
    if (days !in -1L..1L) return rel
    return L10n.join(context, listOf(rel, DateTimeFormatter.ofPattern("EEEE d MMMM", L10n.current(context)).format(at.atZone(zone))))
}

/** «عند الموعد، قبل 15 دقيقة»: the alert at the time, then each early notice. */
private fun alertsText(context: android.content.Context, t: Task): String = L10n.join(context,
    listOf(context.getString(R.string.home_at_time)) + Tasks.noticeMinutes(t).sorted().map {
        context.getString(R.string.before, L10n.duration(context, it.toLong()))
    })

@Composable
private fun FactCard(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Row(Modifier.padding(Dimens.l).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.m))
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Steps with «2 من 5» and a progress bar; each step a 48 dp checkbox row (DESIGN-SPEC §5.18, tickable here). */
@Composable
private fun StepsList(task: Task, enabled: Boolean, onToggle: (String) -> Unit) {
    val steps = task.steps
    val doneCount = steps.count { it.done }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Dimens.s)) {
            Row(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.Checklist, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Dimens.m))
                Text(stringResource(R.string.home_steps), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.n_of_m, doneCount.toString(), steps.size.toString()), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { doneCount.toFloat() / steps.size },
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
            steps.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .toggleable(value = s.done, role = Role.Checkbox, enabled = enabled) { onToggle(s.id) }
                        .padding(horizontal = Dimens.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = s.done, onCheckedChange = null, modifier = Modifier.padding(Dimens.m))
                    Text(s.text, style = MaterialTheme.typography.bodyLarge,
                        color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (s.done) TextDecoration.LineThrough else null)
                }
            }
        }
    }
}

