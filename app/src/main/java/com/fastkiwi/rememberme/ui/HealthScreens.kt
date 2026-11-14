package com.fastkiwi.rememberme.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.data.ScheduleLog
import java.time.Instant

/** What the health centre shows about the schedule itself (PRD P0.1). */
data class ScheduleStatus(
    /** The last engine run, or null before the first one. */
    val last: ScheduleLog.Entry?,
    /** The last run after a reboot, if the log still has one. */
    val lastBoot: ScheduleLog.Entry?,
    /** Active reminders with a time still ahead. */
    val scheduled: Int,
    /** Overdue reminders waiting for «تم» or «أجّل». */
    val attention: Int,
    /** Alarm lateness measured from the local log, in ms (alarm-woken runs only). */
    val lateness: List<Long> = emptyList(),
) {
    /**
     * The boot broadcast did not reach the app after the last restart although the app ran since:
     * the phone maker blocked it (seen only after the app was opened again; null when it cannot tell).
     */
    fun bootBlocked(bootAt: Long, nowMs: Long): Boolean? {
        if (bootAt <= 0 || nowMs - bootAt < 10 * 60_000L) return null
        if (lastBoot != null && lastBoot.at >= bootAt) return false
        return if ((last?.at ?: 0) >= bootAt) true else null
    }
    /** Never "all good" while an essential permission is missing or the last alarm was not found set. */
    fun ok(h: Health) = h.essentialOk && (last?.verified ?: true)
}

/** Builds the compatibility report: device and settings facts only, never reminders (PRD P0.3, D-079). */
object CompatReport {
    /** Bands, so the report never tells how many reminders someone has exactly. */
    fun band(n: Int) = when { n == 0 -> "0"; n <= 10 -> "1-10"; n <= 100 -> "11-100"; else -> "over 100" }

    fun build(context: Context, h: Health, s: ScheduleStatus, test: TestAlarm.Record, results: Map<TestAlarm.Part, TestAlarm.Result>): String = buildString {
        appendLine("compat-report@1")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Language: ${L10n.current(context).language}")
        appendLine("Notifications: ${h.notifications}; reminder channel high: ${h.channel}; sound: ${h.channelSound}; vibration: ${h.channelVibrates}; notification volume zero: ${h.volumeZero}")
        appendLine("Exact alarms: ${h.exact} (${h.exactPermission}); full screen: ${h.fullScreen}; battery unrestricted: ${h.battery}; Do Not Disturb on: ${h.dnd}")
        appendLine("Background restricted: ${h.backgroundRestricted}; standby bucket: ${if (h.bucket == 0) "unknown" else h.bucket}")
        val blocked = s.bootBlocked(h.bootAt, System.currentTimeMillis())
        appendLine("Boot broadcast since the last restart: ${when (blocked) { true -> "blocked"; false -> "seen"; null -> "unknown" }}")
        s.last?.let { appendLine("Last schedule check: due slot ${it.api}, notice slot ${it.otherApi}, verified ${it.verified}") }
        if (s.lateness.isNotEmpty()) appendLine("Alarm lateness: median ${s.lateness.sorted()[s.lateness.size / 2] / 1000} s, worst ${s.lateness.max() / 1000} s (${s.lateness.size} alarms)")
        appendLine("Timed reminders: ${band(s.scheduled + s.attention)}")
        if (test.startedAt > 0) {
            val delay = if (test.firedAt > 0) "${(test.firedAt - test.plannedAt) / 1000} s" else "not arrived"
            appendLine("Alarm test: ${test.api}, delay $delay")
            results.forEach { (part, r) -> appendLine("  ${part.name.lowercase()}: ${r.name.lowercase()}") }
        }
        append("No reminder titles, notes, times, ids, time zone or personal data are included.")
    }
}

private fun statusOf(r: TestAlarm.Result) = when (r) {
    TestAlarm.Result.OK -> Status.READY
    TestAlarm.Result.FAILED -> Status.NEEDED
    TestAlarm.Result.NOT_ALLOWED -> Status.OFF
    else -> Status.ADVICE
}

/**
 * «صحة التذكيرات» (PRD P0.1): the overall state first, then what Android controls, then what the phone
 * maker controls. It never says "ready" while an essential item is missing, and it rechecks when the
 * app comes back from the system's settings.
 */
@Composable
fun HealthScreen(
    health: Health, schedule: ScheduleStatus, maker: Maker?, firstRun: Boolean, now: Instant,
    onCheck: () -> Unit, onTest: () -> Unit, onReport: () -> Unit,
    onFix: (String) -> Unit, onContinue: () -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    val allow = stringResource(R.string.allow)
    val open = stringResource(R.string.open_settings)
    fun st(ok: Boolean, essential: Boolean) = if (ok) Status.READY else if (essential) Status.NEEDED else Status.OFF
    val ok = schedule.ok(health)
    Page(stringResource(R.string.health_title), onBack, bottomBar = {
        if (firstRun) BottomActionBar {
            if (health.essentialOk) Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.continue_)) }
            else OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.continue_)) }
        }
    }) {
        // The overall state, with words and an icon (never colour alone).
        Surface(
            color = if (ok) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        ) {
            Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Icon(if (ok) Ms.CheckCircle else Ms.WarningAmber, null,
                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text(stringResource(if (ok) R.string.health_ok_title else R.string.health_bad_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = Dimens.m).semantics { heading() })
                }
                val last = schedule.last
                Text(
                    if (last == null) stringResource(R.string.health_never_checked)
                    else stringResource(if (last.verified) R.string.health_last_check_ok else R.string.health_last_check_bad,
                        L10n.dueText(context, Instant.ofEpochMilli(last.at), now)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.health_counts, schedule.scheduled.toString(), schedule.attention.toString()), style = MaterialTheme.typography.bodyMedium)
                if (schedule.lateness.isNotEmpty()) Text(
                    stringResource(R.string.health_lateness, (schedule.lateness.sorted()[schedule.lateness.size / 2] / 1000).toString(),
                        (schedule.lateness.max() / 1000).toString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (last != null && last.api == "INEXACT") Text(stringResource(R.string.health_inexact), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.check_now)) }
                    Button(onClick = onTest, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.test_now)) }
                }
            }
        }
        Text(stringResource(R.string.background_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        // Android's own settings.
        val android = buildList<Pair<Status, @Composable () -> Unit>> {
            val n = st(health.notifications, true)
            add(n to { CheckRow(n, null, stringResource(R.string.chk_notifications), stringResource(R.string.chk_notifications_text), allow) { onFix("notifications") } })
            if (health.notifications) {
                val c = st(health.channel && health.channelSound, true)
                add(c to { CheckRow(c, null, stringResource(R.string.chk_channel), stringResource(R.string.chk_channel_text), open) { onFix("channel") } })
            }
            val e = st(health.exact, true)
            add(e to { CheckRow(e, null, stringResource(R.string.chk_exact), stringResource(R.string.chk_exact_text), allow) { onFix("exact") } })
            if (health.backgroundRestricted) add(Status.NEEDED to {
                CheckRow(Status.NEEDED, null, stringResource(R.string.chk_background), stringResource(R.string.chk_background_text), open) { onFix("app") }
            })
            if (health.bucketRestricted) add(Status.NEEDED to {
                CheckRow(Status.NEEDED, null, stringResource(R.string.chk_bucket), stringResource(R.string.chk_bucket_text), open) { onFix("battery") }
            })
            val f = st(health.fullScreen, false)
            add(f to { CheckRow(f, null, stringResource(R.string.chk_fullscreen), stringResource(R.string.chk_fullscreen_text), allow) { onFix("fullscreen") } })
            val b = st(health.battery, false)
            add(b to { CheckRow(b, null, stringResource(R.string.chk_battery), stringResource(R.string.chk_battery_text), allow) { onFix("battery") } })
            val blocked = schedule.bootBlocked(health.bootAt, now.toEpochMilli())
            add((if (blocked == true) Status.NEEDED else Status.ADVICE) to {
                val boot = schedule.lastBoot
                when {
                    blocked == true -> CheckRow(Status.NEEDED, Ms.RestartAlt, stringResource(R.string.chk_boot), stringResource(R.string.chk_boot_blocked), open) { onFix("maker") }
                    boot != null -> CheckRow(Status.READY, Ms.RestartAlt, stringResource(R.string.chk_boot),
                        stringResource(R.string.chk_boot_seen, L10n.dueText(context, Instant.ofEpochMilli(boot.at), now)), null) {}
                    else -> CheckRow(Status.ADVICE, Ms.RestartAlt, stringResource(R.string.chk_boot), stringResource(R.string.chk_boot_text), null) {}
                }
            })
            if (health.volumeZero) add(Status.OFF to {
                CheckRow(Status.OFF, null, stringResource(R.string.chk_volume), stringResource(R.string.chk_volume_text), null) {}
            })
            add(Status.ADVICE to { CheckRow(Status.ADVICE, Ms.HourglassEmpty, stringResource(R.string.chk_hibernation), stringResource(R.string.chk_hibernation_text), open) { onFix("hibernation") } })
            if (health.dnd) add(Status.ADVICE to { CheckRow(Status.ADVICE, Ms.DoNotDisturbOn, stringResource(R.string.chk_dnd), stringResource(R.string.chk_dnd_text), open) { onFix("dnd") } })
        }
        SectionHeader(stringResource(R.string.group_android), trailing = {
            val countable = android.filter { it.first != Status.ADVICE }
            Text(stringResource(R.string.ready_count, countable.count { it.first == Status.READY }.toString(), countable.size.toString()),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        android.forEachIndexed { i, (status, row) ->
            GroupItem(i, android.size, if (status == Status.NEEDED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) { row() }
        }
        // The phone maker's own settings: the app cannot read them, so these are steps to check by hand.
        SectionHeader(stringResource(R.string.group_maker))
        GroupItem(0, 1) {
            // Each maker's steps say honestly whether they were tried on a real phone (none yet).
            if (maker != null) CheckRow(Status.ADVICE, Ms.PhoneAndroid, stringResource(R.string.chk_maker, stringResource(maker.name)),
                stringResource(maker.steps) + "\n" + stringResource(R.string.maker_untested), open) { onFix("maker") }
            else CheckRow(Status.ADVICE, Ms.PhoneAndroid, stringResource(R.string.chk_maker_generic), stringResource(R.string.steps_generic), open) { onFix("battery_list") }
        }
        TextButton(onClick = onReport, modifier = Modifier.padding(horizontal = Dimens.l).heightIn(min = Dimens.touch)) { Text(stringResource(R.string.compat_share)) }
        Text(stringResource(R.string.background_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
    }
}

/** «اختبر التنبيه الآن» (PRD P0.4): start, then each part with what it showed and what to do. */
@Composable
fun AlarmTestScreen(
    record: TestAlarm.Record, results: Map<TestAlarm.Part, TestAlarm.Result>,
    onStart: () -> Unit, onAnswer: (TestAlarm.Part, Boolean) -> Unit, onFix: (String) -> Unit, onReport: () -> Unit, onBack: () -> Unit,
) {
    Page(stringResource(R.string.test_screen_title), onBack, bottomBar = {
        BottomActionBar {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(if (record.startedAt == 0L) R.string.test_start else R.string.test_again))
            }
        }
    }) {
        Text(stringResource(R.string.test_intro, TestAlarm.DELAY_SECONDS.toString()), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        if (record.startedAt == 0L) return@Page
        SectionHeader(stringResource(R.string.test_results))
        val parts = results.entries.toList()
        parts.forEachIndexed { i, (part, r) ->
            val status = statusOf(r)
            GroupItem(i, parts.size, if (status == Status.NEEDED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    CheckRow(status, if (r == TestAlarm.Result.WAITING) Ms.Schedule else Ms.Info,
                        testPartTitle(part), testPartText(part, r, record), fixLabel(part, r)) { onFix(fixTarget(part)) }
                    if (r == TestAlarm.Result.ASK && (part == TestAlarm.Part.SOUND || part == TestAlarm.Part.VIBRATION)) {
                        Row(Modifier.padding(start = 56.dp, bottom = Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            OutlinedButton(onClick = { onAnswer(part, false) }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.answer_no)) }
                            FilledTonalButton(onClick = { onAnswer(part, true) }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.answer_yes)) }
                        }
                    }
                }
            }
        }
        TextButton(onClick = onReport, modifier = Modifier.padding(horizontal = Dimens.l).heightIn(min = Dimens.touch)) { Text(stringResource(R.string.compat_share)) }
    }
}

@Composable
private fun testPartTitle(p: TestAlarm.Part) = stringResource(when (p) {
    TestAlarm.Part.ALARM -> R.string.part_alarm
    TestAlarm.Part.NOTIFICATION -> R.string.part_notification
    TestAlarm.Part.SOUND -> R.string.part_sound
    TestAlarm.Part.VIBRATION -> R.string.part_vibration
    TestAlarm.Part.SCREEN -> R.string.part_screen
    TestAlarm.Part.BUTTONS -> R.string.part_buttons
})

@Composable
private fun testPartText(p: TestAlarm.Part, r: TestAlarm.Result, rec: TestAlarm.Record): String = when (r) {
    TestAlarm.Result.WAITING -> stringResource(R.string.result_waiting)
    TestAlarm.Result.NOT_ALLOWED -> stringResource(R.string.result_screen_not_allowed)
    TestAlarm.Result.OK -> if (p == TestAlarm.Part.ALARM) stringResource(R.string.result_alarm_ok, ((rec.firedAt - rec.plannedAt).coerceAtLeast(0) / 1000).toString())
        else stringResource(R.string.result_ok)
    TestAlarm.Result.ASK -> stringResource(when (p) {
        TestAlarm.Part.SOUND -> R.string.ask_sound
        TestAlarm.Part.VIBRATION -> R.string.ask_vibration
        TestAlarm.Part.SCREEN -> R.string.ask_screen
        else -> R.string.ask_buttons
    })
    TestAlarm.Result.FAILED -> stringResource(when (p) {
        TestAlarm.Part.ALARM -> R.string.fail_alarm
        TestAlarm.Part.NOTIFICATION -> R.string.fail_notification
        TestAlarm.Part.SOUND -> R.string.fail_sound
        TestAlarm.Part.VIBRATION -> R.string.fail_vibration
        TestAlarm.Part.SCREEN -> R.string.fail_screen
        TestAlarm.Part.BUTTONS -> R.string.fail_buttons
    })
}

@Composable
private fun fixLabel(p: TestAlarm.Part, r: TestAlarm.Result): String? =
    if (r == TestAlarm.Result.FAILED || r == TestAlarm.Result.NOT_ALLOWED) stringResource(R.string.open_settings).takeIf { p != TestAlarm.Part.BUTTONS } else null

private fun fixTarget(p: TestAlarm.Part) = when (p) {
    TestAlarm.Part.ALARM -> "battery"
    TestAlarm.Part.NOTIFICATION -> "notifications"
    TestAlarm.Part.SOUND, TestAlarm.Part.VIBRATION -> "channel"
    TestAlarm.Part.SCREEN -> "fullscreen"
    TestAlarm.Part.BUTTONS -> "notifications"
}

/** The test reminder's own screen, opened by its full-screen intent or a tap on its notification. */
@Composable
fun TestAlertScreen(now: Instant, onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Dimens.l)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Spacer(Modifier.size(Dimens.xxl))
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Ms.NotificationsActive, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                TimeText(now, MaterialTheme.typography.displayLarge, MaterialTheme.colorScheme.onSurface, ampmSize = 28)
                Text(stringResource(R.string.test_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.test_screen_ok), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Icon(Ms.Check, null)
                Spacer(Modifier.size(Dimens.s))
                Text(stringResource(R.string.done), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
