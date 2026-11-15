package com.fastkiwi.rememberme.ui.health

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.alarm.TestAlarm.Part
import com.fastkiwi.rememberme.alarm.TestAlarm.Result
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.ui.BottomActionBar
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.Page
import com.fastkiwi.rememberme.ui.SheetShape
import com.fastkiwi.rememberme.ui.groupShape

/** How a part's result is shown: never "passed" for something the app could not see (F-009). */
enum class TestShown { PASS, FAIL, ASK, UNKNOWN, WAITING }

object TestResults {
    /** The shown state of [part] with result [r]. A full screen that did not appear is unknown, not passed. */
    fun shown(part: Part, r: Result): TestShown = when (r) {
        Result.OK -> TestShown.PASS
        Result.FAILED, Result.NOT_ALLOWED -> TestShown.FAIL
        Result.WAITING -> TestShown.WAITING
        Result.ASK -> if (part == Part.SCREEN) TestShown.UNKNOWN else TestShown.ASK
    }

    fun counts(results: Map<Part, Result>): Map<TestShown, Int> = results.entries.groupingBy { shown(it.key, it.value) }.eachCount()

    /** True only when every part passed (1.0.5: a normal reminder's test has no full-screen part). */
    fun allPassed(results: Map<Part, Result>) = results.keys.containsAll(Part.entries - Part.SCREEN) && results.all { shown(it.key, it.value) == TestShown.PASS }

    /** The test is waiting for its alarm (nothing to show yet). */
    fun running(rec: TestAlarm.Record, results: Map<Part, Result>) = rec.startedAt > 0 && results.isNotEmpty() && results.values.all { it == Result.WAITING }

    /** Whether Do Not Disturb keeps this test's tone from playing: as recorded when it fired, or as the phone is now. */
    fun dndBlocks(rec: TestAlarm.Record, h: Health): Boolean =
        if (rec.quiet.isNotEmpty()) rec.quiet == AlertPolicy.Quiet.DND.name
        else when (h.dndState) {
            AlertPolicy.Dnd.NONE, AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED -> true
            AlertPolicy.Dnd.ALARMS_ONLY, AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED, AlertPolicy.Dnd.PRIORITY_UNKNOWN -> !rec.alarm
            AlertPolicy.Dnd.OFF, null -> false
        }

    /** The six results for [rec], with the channel of the tested style. */
    fun of(rec: TestAlarm.Record, h: Health, nowMs: Long): Map<Part, Result> = TestAlarm.evaluate(
        rec, nowMs, channelHigh = h.channel, channelSound = if (rec.alarm) h.alarmChannelSound else h.channelSound,
        channelVibrates = h.channelVibrates, dnd = dndBlocks(rec, h),
    )
}

private fun styleName(c: Context, style: String) =
    c.getString(if (style == AlertPolicy.Style.ALARM.name) R.string.test_style_alarm else R.string.test_style_normal)

private fun delayName(c: Context, delay: String) = c.getString(when (delay) {
    TestAlarm.Delay.HOUR_1.name -> R.string.test_in_1h
    TestAlarm.Delay.TOMORROW_7.name -> R.string.test_tomorrow_7
    else -> R.string.test_in_10s
})

/** «10 ثوانٍ», «59 دقيقة», «14 ساعة». */
private fun remaining(c: Context, ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s < 120) L10n.plural(c, R.plurals.health_seconds, s.coerceAtLeast(0)) else L10n.duration(c, (s + 59) / 60)
}

/** The honest line for why no tone was played (F-023). */
private fun quietLine(c: Context, quiet: String, alarm: Boolean): String? = when (quiet) {
    AlertPolicy.Quiet.RINGER_SILENT.name -> c.getString(R.string.q_silent_normal)
    AlertPolicy.Quiet.RINGER_VIBRATE.name -> c.getString(R.string.q_vibrate_normal)
    AlertPolicy.Quiet.DND.name -> c.getString(if (alarm) R.string.q_dnd_alarm else R.string.q_dnd_normal)
    AlertPolicy.Quiet.CHANNEL_MUTED.name -> c.getString(R.string.q_channel_muted)
    AlertPolicy.Quiet.SOUND_OFF.name -> c.getString(R.string.q_sound_off)
    else -> null
}

/** One reason the test's sound may not have been heard: its hint and the settings screen that fixes it. */
data class SoundCause(val hint: Int, val target: String, val quiet: AlertPolicy.Quiet? = null)

/**
 * After «لا» to «هل سمعت الصوت؟» (black-box U2-4): what to check, most likely first, from what the test
 * recorded when it fired ([TestAlarm.Record.quiet]) and what Health reads now ([h], null when unknown):
 * the volume of the tested style at zero, the phone on silent or vibrate (a normal reminder), Do Not Disturb,
 * the «تذكيرات عادية» / «منبّهات مهمة» channel muted. Empty when none applies.
 */
fun soundCauses(rec: TestAlarm.Record, h: Health?): List<SoundCause> = buildList {
    val alarm = rec.alarm
    val q = rec.quiet
    if (h != null && (if (alarm) h.alarmVolume == 0 else h.notifVolume == 0))
        add(SoundCause(if (alarm) R.string.bbfix_hint_alarm_zero else R.string.bbfix_hint_notif_zero, "sound"))
    if (!alarm && (q == AlertPolicy.Quiet.RINGER_SILENT.name || q == AlertPolicy.Quiet.RINGER_VIBRATE.name ||
            (q.isEmpty() && (h?.ringer == AlertPolicy.Ringer.SILENT || h?.ringer == AlertPolicy.Ringer.VIBRATE))))
        add(SoundCause(R.string.bbfix_hint_ringer, "sound", if (q.startsWith("RINGER")) AlertPolicy.Quiet.valueOf(q) else null))
    if (q == AlertPolicy.Quiet.DND.name || (h != null && TestResults.dndBlocks(rec, h)))
        add(SoundCause(R.string.bbfix_hint_dnd, "dnd_help", if (q == AlertPolicy.Quiet.DND.name) AlertPolicy.Quiet.DND else null))
    if (q == AlertPolicy.Quiet.CHANNEL_MUTED.name || (h != null && !(if (alarm) h.alarmChannelSound else h.channelSound)))
        add(SoundCause(if (alarm) R.string.bbfix_hint_channel_alarm else R.string.bbfix_hint_channel_normal,
            if (alarm) "alarm_channel" else "channel", if (q == AlertPolicy.Quiet.CHANNEL_MUTED.name) AlertPolicy.Quiet.CHANNEL_MUTED else null))
}

/** What one part shows: its text lines, an optional question and an optional fix. */
private data class PartView(val title: String, val lines: List<String>, val question: String?, val fix: HealthAction?)

private fun partView(c: Context, part: Part, r: Result, rec: TestAlarm.Record, h: Health?): PartView {
    val shown = TestResults.shown(part, r)
    val fired = rec.firedAt > 0
    val notArrived = c.getString(R.string.t_not_arrived)
    fun answered(a: Boolean?) = a?.let { c.getString(R.string.you_answered, c.getString(if (it) R.string.answer_yes else R.string.answer_no)) }
    return when (part) {
        Part.ALARM -> {
            val late = rec.lateMs
            val text = when {
                late == null -> c.getString(R.string.t_alarm_lost)
                shown == TestShown.FAIL -> c.getString(R.string.t_alarm_late, seconds(c, late))
                else -> {
                    // «وصل بعد 10 ثوانٍ»: the wait that was asked for; the lateness is measured on its own (F-027).
                    val after = rec.plannedAt - rec.startedAt
                    val afterText = if (after < 120_000) seconds(c, after) else L10n.duration(c, after / 60_000)
                    if (late < 1000) c.getString(R.string.t_alarm_exact, afterText) else c.getString(R.string.t_alarm_ok, afterText, seconds(c, late))
                }
            }
            PartView(c.getString(R.string.part_on_time), listOf(text), null,
                if (shown == TestShown.FAIL) HealthAction(ActionKind.ALLOW, "battery") else null)
        }
        Part.NOTIFICATION -> PartView(c.getString(R.string.part_notif), listOf(when {
            shown == TestShown.PASS -> c.getString(R.string.t_notif_ok)
            fired -> c.getString(R.string.t_notif_fail)
            else -> notArrived
        }), null, if (shown == TestShown.FAIL && fired) HealthAction(ActionKind.ALLOW, "notifications") else null)
        Part.SOUND -> {
            val causes = soundCauses(rec, h)
            val lines = buildList {
                if (!fired) { add(notArrived); return@buildList }
                val quiet = quietLine(c, rec.quiet, rec.alarm)
                // The objective sub-check: did the player start? Not asked when the policy kept it quiet.
                if (quiet == null) add(c.getString(R.string.key_value, c.getString(R.string.part_playback), c.getString(when (rec.playback) {
                    true -> R.string.res_pass
                    false -> R.string.res_fail
                    null -> R.string.st_unknown
                })))
                answered(rec.sound)?.let(::add)
                quiet?.let(::add)
                // Not heard: what to check (the cause the quiet line above already says is not repeated).
                if (shown == TestShown.FAIL && fired) {
                    val hints = causes.filter { it.quiet == null || it.quiet.name != rec.quiet }.map { c.getString(it.hint) }
                    if (hints.isNotEmpty()) addAll(hints) else if (quiet == null) add(c.getString(R.string.bbfix_hint_none))
                }
            }
            // The button opens the screen of the first cause: sound settings, the channel, or the DND help.
            val fix = when {
                shown != TestShown.FAIL || !fired -> null
                else -> HealthAction(ActionKind.OPEN_SETTINGS, causes.firstOrNull()?.target ?: "sound")
            }
            PartView(c.getString(R.string.part_sound), lines, if (shown == TestShown.ASK) c.getString(R.string.q_sound) else null, fix)
        }
        Part.VIBRATION -> {
            val lines = buildList {
                when {
                    !fired -> add(notArrived)
                    rec.vibration != null -> add(answered(rec.vibration)!!)
                    shown == TestShown.FAIL -> add(c.getString(R.string.t_vibration_off))
                }
            }
            PartView(c.getString(R.string.part_vibration), lines, if (shown == TestShown.ASK) c.getString(R.string.q_vibration) else null,
                if (shown == TestShown.FAIL && fired && rec.vibration == false) HealthAction(ActionKind.OPEN_SETTINGS, "sound") else null)
        }
        Part.SCREEN -> PartView(c.getString(R.string.part_full_screen), listOf(when {
            r == Result.OK -> c.getString(R.string.t_screen_ok)
            r == Result.NOT_ALLOWED -> c.getString(R.string.t_screen_denied)
            shown == TestShown.UNKNOWN -> c.getString(R.string.t_screen_unknown)
            else -> notArrived
        }), null, if (r == Result.NOT_ALLOWED) HealthAction(ActionKind.ALLOW, "fullscreen") else null)
        Part.BUTTONS -> PartView(c.getString(R.string.part_btns), listOf(when (shown) {
            TestShown.PASS -> c.getString(R.string.t_buttons_ok)
            TestShown.ASK -> c.getString(R.string.t_buttons_ask)
            else -> notArrived
        }), null, null)
    }
}

@Composable
private fun partIcon(part: Part): ImageVector = when (part) {
    Part.ALARM -> Ms.Alarm
    Part.NOTIFICATION -> Ms.Notifications
    Part.SOUND -> Ms.VolumeUp
    Part.VIBRATION -> Ms.Vibration
    Part.SCREEN -> Ms.Fullscreen
    Part.BUTTONS -> Ms.Check
}

/**
 * One of the six parts (§5.12): status icon and word, what was measured, the user's question with two
 * equal buttons «لا» · «نعم», and for a failure the source «أندرويد» and one fix.
 */
@Composable
fun TestResultRow(
    part: Part, shown: TestShown, title: String, lines: List<String>, question: String?, onAnswer: ((Boolean) -> Unit)?,
    fix: HealthAction?, onFix: (String) -> Unit, index: Int, count: Int,
    /** This row's question is the first one waiting: scroll it into view when it appears. */
    bringQuestion: Boolean = false,
) {
    val fail = shown == TestShown.FAIL
    val fg = if (fail) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val sub = if (fail) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, tint) = when (shown) {
        TestShown.PASS -> Ms.CheckCircle to MaterialTheme.colorScheme.primary
        TestShown.FAIL -> Ms.Error to MaterialTheme.colorScheme.error
        TestShown.UNKNOWN -> Ms.Help to MaterialTheme.colorScheme.onSurfaceVariant
        else -> partIcon(part) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val word = stringResource(when (shown) {
        TestShown.PASS -> R.string.res_pass
        TestShown.FAIL -> R.string.res_fail
        TestShown.ASK -> R.string.res_waiting
        TestShown.UNKNOWN -> R.string.st_unknown
        TestShown.WAITING -> R.string.res_waiting
    })
    Surface(
        color = if (fail) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(index, count),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = 2.dp).tagged(testPartTag(part.name)),
    ) {
        Row(Modifier.fillMaxWidth().padding(Dimens.l), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = fg, modifier = Modifier.weight(1f))
                    Text(word, style = MaterialTheme.typography.labelMedium, color = if (shown == TestShown.PASS) MaterialTheme.colorScheme.primary else sub,
                        modifier = Modifier.padding(start = Dimens.s))
                }
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = sub) }
                if (question != null && onAnswer != null) {
                    // The question and its answers are brought into view when they appear (at 200 % they were below
                    // the fold) and the question is announced (black-box re-test, P2 task 9).
                    val ask = remember { BringIntoViewRequester() }
                    if (bringQuestion) LaunchedEffect(question) { delay(100); ask.bringIntoView() }
                    Column(Modifier.bringIntoViewRequester(ask), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        Text(question, style = MaterialTheme.typography.titleMedium, color = fg,
                            modifier = Modifier.padding(top = Dimens.xs).semantics { liveRegion = LiveRegionMode.Polite })
                        // Two equal answers, «لا» first (start), «نعم» last.
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                            OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f).heightIn(min = Dimens.touch)) { Text(stringResource(R.string.answer_no)) }
                            OutlinedButton(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f).heightIn(min = Dimens.touch)) { Text(stringResource(R.string.answer_yes)) }
                        }
                    }
                }
                if (fix != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Dimens.xs)) {
                    SourceLabel(HealthSource.ANDROID, sub)
                    Spacer(Modifier.weight(1f))
                    HealthActionButton(fix.kind, { onFix(fix.target) })
                }
            }
        }
    }
}

/**
 * «اختبار التنبيه» (DESIGN-SPEC §5.12, F-009, F-027): a start sheet (style and delay), a running card with
 * a countdown, then the six parts with honest texts. It never shows a single "success": the summary
 * counts every part that failed, waits for an answer or is unknown. It writes no reminder.
 *
 * [onFix] gets a target: "dnd_help" for the DND help screen, otherwise a system-settings target.
 */
@Composable
fun AlarmTestScreen(
    record: TestAlarm.Record, results: Map<Part, Result>, nowMs: Long,
    onStart: (AlertPolicy.Style, TestAlarm.Delay) -> Unit, onAnswer: (Part, Boolean) -> Unit,
    onFix: (String) -> Unit, onCancel: () -> Unit, onDone: () -> Unit, onBack: () -> Unit,
    /** What Health reads now, for the hints after «لا» (null: only what the test recorded). */
    health: Health? = null,
) {
    val context = LocalContext.current
    val idle = record.startedAt == 0L || results.isEmpty()
    val running = TestResults.running(record, results)
    var sheet by rememberSaveable { mutableStateOf(idle) }
    val big = LocalDensity.current.fontScale >= 1.5f
    Page(stringResource(R.string.test_page_title), onBack, bottomBar = {
        BottomActionBar {
            when {
                idle -> Button(onClick = { sheet = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.test_start)) }
                running -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { DoneLabel() }
                else -> ButtonPair(big,
                    secondary = { m ->
                        OutlinedButton(onClick = { sheet = true }, modifier = m.heightIn(min = 56.dp)) {
                            Icon(Ms.Refresh, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.size(Dimens.s)); Text(stringResource(R.string.retest))
                        }
                    },
                    primary = { m -> Button(onClick = onDone, modifier = m.heightIn(min = 56.dp)) { DoneLabel() } })
            }
        }
    }) {
        when {
            idle -> Text(stringResource(R.string.test_writes_nothing), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.m))
            running -> RunningCard(remaining(context, record.plannedAt - nowMs), onCancel)
            else -> {
                val counts = TestResults.counts(results)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                    Row(Modifier.padding(Dimens.xl), verticalAlignment = Alignment.Top) {
                        Icon(Ms.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = Dimens.l).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                            Text(stringResource(R.string.test_done), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.semantics { heading() })
                            Text(L10n.join(context, listOf(
                                counts[TestShown.PASS]?.let { L10n.plural(context, R.plurals.test_n_pass, it.toLong()) },
                                counts[TestShown.FAIL]?.let { L10n.plural(context, R.plurals.test_n_fail, it.toLong()) },
                                counts[TestShown.ASK]?.let { L10n.plural(context, R.plurals.test_n_wait, it.toLong()) },
                                counts[TestShown.UNKNOWN]?.let { L10n.plural(context, R.plurals.test_n_unknown, it.toLong()) },
                            )), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.test_tested, styleName(context, record.style), delayName(context, record.delay)),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.test_parts), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Dimens.xxl, end = Dimens.xxl, top = Dimens.l, bottom = Dimens.xs).semantics { heading() })
                val parts = Part.entries.filter { it in results }
                val views = parts.map { part -> partView(context, part, results.getValue(part), record, health) }
                val firstAsk = parts.indices.firstOrNull { i -> views[i].question != null && (parts[i] == Part.SOUND || parts[i] == Part.VIBRATION) }
                parts.forEachIndexed { i, part ->
                    val r = results.getValue(part)
                    val v = views[i]
                    TestResultRow(part, TestResults.shown(part, r), v.title, v.lines, v.question,
                        if (part == Part.SOUND || part == Part.VIBRATION) { yes -> onAnswer(part, yes) } else null,
                        v.fix, onFix, i, parts.size, bringQuestion = i == firstAsk)
                }
                Text(stringResource(R.string.test_writes_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.m))
            }
        }
    }
    if (sheet) StartSheet(record, onStart = { s, d -> sheet = false; onStart(s, d) }, onDismiss = { sheet = false })
}

@Composable
private fun DoneLabel() {
    Icon(Ms.Check, null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.size(Dimens.s))
    Text(stringResource(R.string.done))
}

@Composable
private fun RunningCard(left: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
        Column(Modifier.padding(Dimens.xl), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Ms.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.test_running, left), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = Dimens.l).semantics { liveRegion = LiveRegionMode.Polite })
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.test_cancel)) }
        }
    }
}

/** Test tag of the sheet's «ابدأ الاختبار». */
const val START_TAG = "test_start_go"

/** The start sheet (§5.12, F-027): «نوع التنبيه» and «متى», then «ابدأ الاختبار». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartSheet(record: TestAlarm.Record, onStart: (AlertPolicy.Style, TestAlarm.Delay) -> Unit, onDismiss: () -> Unit) {
    var style by rememberSaveable { mutableStateOf(runCatching { AlertPolicy.Style.valueOf(record.style) }.getOrDefault(AlertPolicy.Style.NORMAL)) }
    var delay by rememberSaveable { mutableStateOf(TestAlarm.Delay.SECONDS_10) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        StartChoices(style, { style = it }, delay, { delay = it }, onStart = { onStart(style, delay) })
    }
}

/** The sheet's content, also usable on its own (tests). */
@Composable
fun StartChoices(
    style: AlertPolicy.Style, onStyle: (AlertPolicy.Style) -> Unit, delay: TestAlarm.Delay, onDelay: (TestAlarm.Delay) -> Unit, onStart: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Dimens.l)) {
        Text(stringResource(R.string.test_page_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s).semantics { heading() })
        @Composable
        fun <T> choices(header: Int, options: List<Pair<T, Int>>, selected: T, onPick: (T) -> Unit) {
            Text(stringResource(header), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s).semantics { heading() })
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact)
                            .selectable(selected = value == selected, role = Role.RadioButton) { onPick(value) }
                            .padding(horizontal = Dimens.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.l))
                    }
                }
            }
        }
        choices(R.string.test_kind, listOf(AlertPolicy.Style.NORMAL to R.string.test_style_normal, AlertPolicy.Style.ALARM to R.string.test_style_alarm), style, onStyle)
        choices(R.string.test_when, listOf(
            TestAlarm.Delay.SECONDS_10 to R.string.test_in_10s, TestAlarm.Delay.HOUR_1 to R.string.test_in_1h,
            TestAlarm.Delay.TOMORROW_7 to R.string.test_tomorrow_7,
        ), delay, onDelay)
        Text(stringResource(R.string.test_writes_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xl).heightIn(min = 56.dp).tagged(START_TAG)) {
            Text(stringResource(R.string.test_start))
        }
    }
}

