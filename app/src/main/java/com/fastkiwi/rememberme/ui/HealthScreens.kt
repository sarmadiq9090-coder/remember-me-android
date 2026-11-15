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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.data.ScheduleLog
import com.fastkiwi.rememberme.ui.health.HealthGroup
import com.fastkiwi.rememberme.ui.health.HealthGroupHeader
import com.fastkiwi.rememberme.ui.health.HealthItem
import com.fastkiwi.rememberme.ui.health.HealthModel
import com.fastkiwi.rememberme.ui.health.HealthRow
import com.fastkiwi.rememberme.ui.health.HealthStatus
import com.fastkiwi.rememberme.ui.health.KeyValueRow
import com.fastkiwi.rememberme.ui.health.MakerSteps
import com.fastkiwi.rememberme.ui.health.healthRowTag
import com.fastkiwi.rememberme.ui.health.makerStepText
import com.fastkiwi.rememberme.ui.health.seconds
import com.fastkiwi.rememberme.ui.health.tagged
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
    /** Alarm lateness measured from the local log, in ms (alarm-woken runs only), newest first. */
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

/** Builds the compatibility report: device and settings facts only, never reminders (PRD P0.3, D-079, F-028). */
object CompatReport {
    /** Bands, so the report never tells how many reminders someone has exactly. */
    fun band(n: Int) = when { n == 0 -> "0"; n <= 10 -> "1-10"; n <= 100 -> "11-100"; else -> "over 100" }

    private fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "unknown" }
    private fun vol(v: Int?, max: Int?) = if (v == null) "unknown" else if (max != null && max > 0) "$v/$max" else "$v"

    fun build(context: Context, h: Health, s: ScheduleStatus, test: TestAlarm.Record, results: Map<TestAlarm.Part, TestAlarm.Result>): String = buildString {
        appendLine("compat-report@1")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Language: ${L10n.current(context).language}")
        appendLine("Notifications: ${h.notifications}; reminder channel high: ${h.channel}; sound: ${h.channelSound}; vibration: ${h.channelVibrates}; important alarm channel audible: ${h.alarmChannelSound}")
        appendLine("Exact alarms: ${h.exact} (${h.exactPermission}); full screen: ${h.fullScreen}; battery unrestricted: ${h.battery}")
        appendLine("Ringer: ${h.ringer?.name?.lowercase() ?: "unknown"}; Do Not Disturb: ${h.dndState?.name?.lowercase() ?: "unknown"}; alarms allowed in DND: ${yn(h.dndAlarmsAllowed)}")
        appendLine("Volume: alarm ${vol(h.alarmVolume, h.alarmVolumeMax)}, notifications ${vol(h.notifVolume, h.notifVolumeMax)}; lock screen hides all: ${yn(h.lockScreenHidesAll)}")
        appendLine("Tone problems in 7 days: ${h.toneProblems ?: "unknown"}; ringing service refused: ${h.fgsFailures}")
        appendLine("Background restricted: ${h.backgroundRestricted}; standby bucket: ${if (h.bucket == 0) "unknown" else h.bucket}")
        val blocked = s.bootBlocked(h.bootAt, System.currentTimeMillis())
        appendLine("Boot broadcast since the last restart: ${when (blocked) { true -> "blocked"; false -> "seen"; null -> "unknown" }}")
        s.last?.let { appendLine("Last schedule check: due slot ${it.api}, notice slot ${it.otherApi}, verified ${it.verified}") }
        if (s.lateness.isNotEmpty()) appendLine("Alarm lateness: median ${s.lateness.sorted()[s.lateness.size / 2] / 1000} s, worst ${s.lateness.max() / 1000} s (${s.lateness.size} alarms)")
        appendLine("Timed reminders: ${band(s.scheduled + s.attention)}")
        if (test.startedAt > 0) {
            val delay = test.lateMs?.let { "${it / 1000} s" } ?: "not arrived"
            appendLine("Alarm test: ${test.api}, ${test.style.lowercase()}, ${test.delay.lowercase()}, delay $delay, playback ${yn(test.playback)}")
            results.forEach { (part, r) -> appendLine("  ${part.name.lowercase()}: ${r.name.lowercase()}") }
        }
        append("No reminder titles, notes, times, ids, time zone or personal data are included.")
    }
}

/** The title and supporting text of a Health row. */
private fun rowText(context: Context, item: HealthItem, h: Health, s: ScheduleStatus, now: Instant): Pair<String, String?> {
    val st = item.status
    fun r(id: Int) = context.getString(id)
    val unreadable = r(R.string.unreadable)
    return when (item.key) {
        "notifications" -> r(R.string.chk_notifications) to (if (st == HealthStatus.NEEDED) r(R.string.chk_notifications_text) else null)
        "exact" -> r(R.string.chk_exact) to r(if (h.exactPermission.startsWith("none") || Build.VERSION.SDK_INT < 31) R.string.h_exact_none else R.string.h_exact_perm)
        "channel" -> r(R.string.h_channel) to (if (st == HealthStatus.NEEDED) r(R.string.h_channel_off) else null)
        "background" -> r(R.string.chk_background) to r(R.string.chk_background_text)
        "schedule" -> r(R.string.h_schedule) to r(R.string.h_schedule_bad)
        "playback" -> r(R.string.h_playback) to r(R.string.h_playback_bad)
        "fullscreen" -> r(R.string.h_fullscreen) to (if (st == HealthStatus.READY) null else r(R.string.h_fullscreen_off))
        "battery" -> r(R.string.h_battery) to when {
            st == HealthStatus.READY -> null
            h.bucketRestricted -> r(R.string.h_bucket)
            else -> r(R.string.h_battery_off)
        }
        "boot" -> r(R.string.h_boot) to when (st) {
            HealthStatus.RESTRICTED -> r(R.string.chk_boot_blocked)
            HealthStatus.READY -> s.lastBoot?.let { context.getString(R.string.chk_boot_seen, L10n.dueText(context, Instant.ofEpochMilli(it.at), now)) }
            else -> r(R.string.h_boot_unknown)
        }
        "autobackup" -> r(R.string.h_autobackup) to r(R.string.banner_autobackup_failed)
        "lock" -> r(R.string.h_lock) to when (st) {
            HealthStatus.READY -> r(R.string.h_lock_shows)
            HealthStatus.OFF -> r(R.string.h_lock_hides)
            else -> unreadable
        }
        "ringer" -> r(R.string.h_ringer) to when (h.ringer) {
            AlertPolicy.Ringer.NORMAL -> r(R.string.h_ringer_normal)
            AlertPolicy.Ringer.VIBRATE -> r(R.string.h_ringer_vibrate)
            AlertPolicy.Ringer.SILENT -> context.getString(R.string.key_value, r(R.string.phone_silent), r(R.string.phone_silent_note))
            null -> unreadable
        }
        "dnd" -> r(R.string.h_dnd) to when (h.dndState) {
            AlertPolicy.Dnd.OFF -> r(R.string.dnd_off)
            AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED, AlertPolicy.Dnd.ALARMS_ONLY -> r(R.string.dnd_on_alarms) + " " + r(R.string.dnd_normal_note)
            AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED -> r(R.string.dnd_on_blocks)
            AlertPolicy.Dnd.NONE -> r(R.string.dnd_on_total)
            AlertPolicy.Dnd.PRIORITY_UNKNOWN, null -> unreadable
        }
        "alarm_volume", "notif_volume" -> {
            val alarm = item.key == "alarm_volume"
            val v = if (alarm) h.alarmVolume else h.notifVolume
            val max = if (alarm) h.alarmVolumeMax else h.notifVolumeMax
            r(if (alarm) R.string.h_alarm_volume else R.string.h_notif_volume) to when {
                v == null -> unreadable
                v == 0 -> r(R.string.volume_zero)
                else -> context.getString(R.string.volume_of, v.toString(), (max ?: v).toString())
            }
        }
        "tones" -> r(R.string.h_tones) to when (val n = h.toneProblems) {
            null -> unreadable
            0 -> r(R.string.h_tones_ok)
            else -> L10n.plural(context, R.plurals.h_tones_bad, n.toLong())
        }
        "alarm_channel" -> r(R.string.h_alarm_channel) to r(if (h.alarmChannelSound) R.string.h_alarm_channel_ok else R.string.h_alarm_channel_off)
        else -> makerStepText(context, item.key)
    }
}

/**
 * «صحة التذكيرات» (DESIGN-SPEC §5.11, F-008, F-013, F-023): a summary, then five groups. Every row says
 * its state in a word; a row that is not ready says where the setting lives and has exactly one action.
 * Anything the app cannot read is «غير معروف», never «جاهز». It never says everything essential is ready
 * while an essential item is missing, and it checks again whenever the app comes back to the front.
 *
 * [onFix] gets a system-settings target (see [com.fastkiwi.rememberme.ui.health.HealthFix]).
 */
@Composable
fun HealthScreen(
    health: Health, schedule: ScheduleStatus, firstRun: Boolean, now: Instant,
    onCheck: () -> Unit, onTest: () -> Unit, onReport: () -> Unit, onAlertLog: () -> Unit, onDndHelp: () -> Unit,
    onFix: (String) -> Unit, onContinue: () -> Unit, onBack: () -> Unit,
    onResumeCheck: () -> Unit = {},
    manufacturer: String = Build.MANUFACTURER, sdk: Int = Build.VERSION.SDK_INT,
) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onResumeCheck() }
    var menu by remember { mutableStateOf(false) }
    val essentials = HealthModel.essentials(health, schedule)
    val missing = essentials.count { it.status != HealthStatus.READY }
    val big = LocalDensity.current.fontScale >= 1.5f
    fun act(target: String) = when (target) {
        "check" -> onCheck()
        "dnd_help" -> onDndHelp()
        "alert_log" -> onAlertLog()
        else -> onFix(target)
    }
    Page(stringResource(R.string.health_title), onBack, actions = {
        Box {
            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.compat_title)) }, onClick = { menu = false; onReport() },
                    leadingIcon = { Icon(Ms.Share, null) }, modifier = Modifier.heightIn(min = Dimens.touch))
                DropdownMenuItem(text = { Text(stringResource(R.string.alog_title)) }, onClick = { menu = false; onAlertLog() },
                    leadingIcon = { Icon(Ms.History, null) }, modifier = Modifier.heightIn(min = Dimens.touch))
            }
        }
    }, bottomBar = {
        BottomActionBar {
            val test: @Composable (Modifier) -> Unit = { m ->
                // The row's «إصلاح» is the primary while an essential is missing.
                if (missing > 0) OutlinedButton(onClick = onTest, modifier = m.heightIn(min = 56.dp)) { TestLabel() }
                else Button(onClick = onTest, modifier = m.heightIn(min = 56.dp)) { TestLabel() }
            }
            if (firstRun) ButtonPair(big, secondary = test, primary = { m ->
                if (missing == 0) Button(onClick = onContinue, modifier = m.heightIn(min = 56.dp)) { Text(stringResource(R.string.continue_)) }
                else OutlinedButton(onClick = onContinue, modifier = m.heightIn(min = 56.dp)) { Text(stringResource(R.string.continue_)) }
            }) else test(Modifier.fillMaxWidth())
        }
    }) {
        SummaryCard(missing, health, schedule, onCheck)
        @Composable
        fun group(title: Int, items: List<HealthItem>) {
            HealthGroupHeader(stringResource(title), HealthModel.readyCount(items))
            items.forEachIndexed { i, item ->
                val (t, d) = rowText(context, item, health, schedule, now)
                val extra = item.tried?.let { stringResource(if (it) R.string.maker_tried else R.string.maker_untried) }
                HealthRow(item.status, t, d, item.source, item.action?.kind, { item.action?.let { act(it.target) } },
                    Modifier.tagged(healthRowTag(item.key)), extra, i, items.size)
            }
        }
        group(R.string.group_essential, essentials)
        group(R.string.group_recommended, HealthModel.recommended(health, schedule, now.toEpochMilli()))
        group(R.string.group_phone_now, HealthModel.phoneNow(health))
        val maker = MakerSteps.of(manufacturer, sdk)
        group(R.string.group_maker_short, HealthModel.maker(manufacturer, sdk))
        Text(
            if (maker != null) stringResource(R.string.maker_for, stringResource(makerNameRes(maker.first)), Build.VERSION.RELEASE ?: sdk.toString())
            else stringResource(R.string.background_footer),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.xs),
        )
        // «الجدولة»: key/values from the local logs.
        HealthGroupHeader(stringResource(R.string.group_scheduling), null)
        val lines = buildList {
            add(stringResource(R.string.scheduled_n, schedule.scheduled.toString()))
            add(stringResource(R.string.attention_n, schedule.attention.toString()))
            add(stringResource(R.string.last_delay, schedule.lateness.firstOrNull()?.let { seconds(context, it) } ?: stringResource(R.string.delay_not_measured)))
            schedule.last?.takeIf { it.api == "INEXACT" }?.let { add(stringResource(R.string.health_inexact)) }
        }
        lines.forEachIndexed { i, l -> KeyValueRow(l, i, lines.size) }
    }
}

@Composable
private fun TestLabel() {
    Icon(Ms.NotificationsActive, null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.size(Dimens.s))
    Text(stringResource(R.string.test_now))
}

private fun makerNameRes(key: String) = when (key) {
    "maker_xiaomi" -> R.string.maker_xiaomi
    "maker_samsung" -> R.string.maker_samsung
    "maker_huawei" -> R.string.maker_huawei
    "maker_oppo" -> R.string.maker_oppo
    else -> R.string.maker_vivo
}

/** Test tag of a summary-card line saying the sound will not be heard. */
const val SOUND_WARNING_TAG = "health_sound_warning"

/**
 * The summary: error container with the plural count when an essential is missing, otherwise primary container;
 * under the title, a warning line for each reason the sound will not be heard ([HealthModel.soundWarnings]).
 */
@Composable
private fun SummaryCard(missing: Int, health: Health, schedule: ScheduleStatus, onCheck: () -> Unit) {
    val context = LocalContext.current
    val bad = missing > 0
    val fg = if (bad) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, contentColor = fg,
        shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
    ) {
        Row(Modifier.padding(Dimens.xl), verticalAlignment = Alignment.Top) {
            Icon(if (bad) Ms.Warning else Ms.CheckCircle, null, tint = fg)
            Column(Modifier.weight(1f).padding(start = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(
                    if (bad) L10n.plural(context, R.plurals.essential_off, missing.toLong()) else stringResource(R.string.health_all_ready),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
                if (bad) Text(stringResource(R.string.banner_health), style = MaterialTheme.typography.bodyMedium)
                // The essential count stays honest, but the card also says when the sound will not be heard (U2-1).
                HealthModel.soundWarnings(health).forEach { key ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.tagged(SOUND_WARNING_TAG)) {
                        Icon(Ms.Warning, null, tint = fg, modifier = Modifier.size(20.dp))
                        Text(stringResource(when (key) {
                            "notif_volume" -> R.string.bbfix_sum_notif_zero
                            "alarm_volume" -> R.string.bbfix_sum_alarm_zero
                            "channel" -> R.string.bbfix_sum_normal_muted
                            else -> R.string.bbfix_sum_alarm_muted
                        }), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Dimens.s))
                    }
                }
                val at = health.checkedAt.takeIf { it > 0 } ?: schedule.last?.at
                Text(
                    if (at == null) stringResource(R.string.health_never_checked)
                    else stringResource(R.string.last_check, L10n.time(context, Instant.ofEpochMilli(at))),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(Dimens.xs))
                OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = Dimens.touch)) {
                    Icon(Ms.Refresh, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(Dimens.s))
                    Text(stringResource(R.string.check_now))
                }
            }
        }
    }
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
