package com.fastkiwi.rememberme.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.prayer.Method
import com.fastkiwi.rememberme.core.prayer.Place
import com.fastkiwi.rememberme.core.prayer.Places
import com.fastkiwi.rememberme.data.EditorPrefs
import com.fastkiwi.rememberme.data.EditorSettings
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.ChoiceDialog
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SheetShape
import com.fastkiwi.rememberme.ui.TooltipIconButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val PRAYER_NAMES = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

@Composable
fun prayerName(name: String): String = stringResource(when (name) {
    "fajr" -> R.string.fajr
    "dhuhr" -> R.string.dhuhr
    "asr" -> R.string.asr
    "maghrib" -> R.string.maghrib
    else -> R.string.isha
})

@Composable
fun methodName(m: Method): String = stringResource(when (m) {
    Method.UMM_AL_QURA -> R.string.pm_umm_al_qura
    Method.MWL -> R.string.pm_mwl
    Method.EGYPTIAN -> R.string.pm_egyptian
    Method.KARACHI -> R.string.pm_karachi
    Method.ISNA -> R.string.pm_isna
    Method.DUBAI -> R.string.pm_dubai
    Method.KUWAIT -> R.string.pm_kuwait
    Method.QATAR -> R.string.pm_qatar
    Method.JAFARI -> R.string.pm_jafari
    Method.TEHRAN -> R.string.pm_tehran
})

@Composable
private fun arabic() = LocalConfiguration.current.locales[0].language == "ar"

@Composable
fun placeName(p: Place): String = if (arabic()) p.nameAr else p.nameEn

/** «بعد العصر بـ15 دقيقة» / «قبل الفجر بـ10 دقائق» / «عند المغرب»: the row's prayer line (DESIGN-SPEC §5.24). */
@Composable
fun prayerRelative(name: String, offset: Int): String {
    val context = LocalContext.current
    val p = prayerName(name)
    return when {
        offset > 0 -> stringResource(R.string.prayer_rel_after, p, L10n.duration(context, offset.toLong()))
        offset < 0 -> stringResource(R.string.prayer_rel_before, p, L10n.duration(context, -offset.toLong()))
        else -> stringResource(R.string.prayer_rel_at, p)
    }
}

/**
 * The first occurrence of prayer [name] + [offset] minutes on [date] or later (and after [now]), with the
 * phone's setup; null when the city or the method is not chosen, or the prayer does not occur.
 */
fun prayerOccurrence(s: EditorSettings, name: String, offset: Int, date: LocalDate, now: Instant, zone: ZoneId): Instant? {
    val start = date.atStartOfDay(zone).toInstant().minusSeconds(1)
    return EditorPrefs.prayerNext(s, name, offset, if (start.isAfter(now)) start else now)
}

/**
 * «الوقت» (DESIGN-SPEC §5.24, F-050): «ساعة محددة | حسب الصلاة». By prayer: the five prayers as chips,
 * «قبل | بعد» + a 5-minute stepper, the preview «اليوم نحو 3:05 م (تقريبي)» with the prayer's own time and the
 * city, and «المدينة: …» / «الطريقة: …». Nothing is assumed: on first use it asks for the city, then the
 * method ([onCity], [onMethod] store them on the phone). [onPrayer] gets the name, the signed offset and the
 * computed occurrence; [onClock] a plain time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrayerTimeSheet(
    settings: EditorSettings,
    date: LocalDate,
    clock: LocalTime,
    prayer: Pair<String, Int>?,
    startWithPrayer: Boolean,
    now: Instant,
    zone: ZoneId,
    onCity: (String) -> Unit,
    onMethod: (String) -> Unit,
    onClock: (LocalTime) -> Unit,
    onPrayer: (name: String, offset: Int, at: Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var byPrayer by remember { mutableStateOf(startWithPrayer || prayer != null) }
    var name by remember { mutableStateOf(prayer?.first) }
    var after by remember { mutableStateOf((prayer?.second ?: 15) >= 0) }
    var minutes by remember { mutableIntStateOf(kotlin.math.abs(prayer?.second ?: 15)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val timeState = rememberTimePickerState(clock.hour, clock.minute, L10n.is24(context))
    val offset = if (after) minutes else -minutes
    // First use: the city, then the method (no default, F-050).
    LaunchedEffect(byPrayer, settings.prayerCity, settings.prayerMethod) {
        if (byPrayer && dialog == null) dialog = when {
            settings.place == null -> "city"
            settings.method == null -> "method"
            else -> null
        }
    }
    val at = name?.let { n -> prayerOccurrence(settings, n, offset, date, now, zone) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Dimens.l)) {
            Text(stringResource(R.string.time), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Dimens.xl).semantics { heading() })
            Spacer(Modifier.size(Dimens.m))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l)) {
                SegmentedButton(selected = !byPrayer, onClick = { byPrayer = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = Dimens.touch)) {
                    Text(stringResource(R.string.time_mode_clock), maxLines = 1)
                }
                SegmentedButton(selected = byPrayer, onClick = { byPrayer = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = Dimens.touch)) {
                    Text(stringResource(R.string.time_mode_prayer), maxLines = 1)
                }
            }
            if (!byPrayer) {
                Column(Modifier.fillMaxWidth().padding(Dimens.l), horizontalAlignment = Alignment.CenterHorizontally) { TimeInput(state = timeState) }
            } else {
                EditorHeader(stringResource(R.string.prayer_label))
                FlowRow(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    PRAYER_NAMES.forEach { n ->
                        FilterChip(selected = name == n, onClick = { name = n }, label = { Text(prayerName(n)) },
                            leadingIcon = if (name == n) ({ Icon(Ms.Check, null, Modifier.size(18.dp)) }) else null)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        SegmentedButton(selected = !after, onClick = { after = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = Dimens.touch)) {
                            Text(stringResource(R.string.prayer_before), maxLines = 1)
                        }
                        SegmentedButton(selected = after, onClick = { after = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = Dimens.touch)) {
                            Text(stringResource(R.string.prayer_after), maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(Dimens.s))
                    TooltipIconButton(Ms.Remove, stringResource(R.string.decrease), { minutes = (minutes - 5).coerceAtLeast(0) }, enabled = minutes > 0)
                    Text(L10n.duration(context, minutes.toLong()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Dimens.xs))
                    TooltipIconButton(Ms.Add, stringResource(R.string.increase), { minutes = (minutes + 5).coerceAtMost(240) }, enabled = minutes < 240)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                    Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.Top) {
                        Icon(Ms.Event, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Dimens.m))
                        Column {
                            val place = settings.place
                            when {
                                !settings.prayerReady -> Text(stringResource(R.string.prayer_need_setup), style = MaterialTheme.typography.bodyLarge)
                                name == null -> Text(stringResource(R.string.prayer_label), style = MaterialTheme.typography.bodyLarge)
                                at == null -> Text(stringResource(R.string.prayer_never), style = MaterialTheme.typography.bodyLarge)
                                else -> {
                                    Text(stringResource(R.string.prayer_preview, L10n.dayText(context, at, now, zone), L10n.time(context, at, zone), stringResource(R.string.approx)),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                    if (place != null) Text(
                                        stringResource(R.string.prayer_at_city, prayerName(name!!), L10n.time(context, at.minusSeconds(offset * 60L), zone), placeName(place)),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs), verticalAlignment = Alignment.Top) {
                    Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.s))
                    Text(stringResource(R.string.prayer_note) + " " + stringResource(R.string.prayer_approx_note),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(Modifier.padding(horizontal = Dimens.s)) {
                    val choose = stringResource(R.string.prayer_choose)
                    TextButton(onClick = { dialog = "city" }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                        Text(stringResource(R.string.city_is, settings.place?.let { placeName(it) } ?: choose))
                    }
                    TextButton(onClick = { dialog = "method" }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                        Text(stringResource(R.string.method_is, settings.method?.let { methodName(it) } ?: choose))
                    }
                }
            }
            Spacer(Modifier.size(Dimens.m))
            Column(Modifier.padding(horizontal = Dimens.l)) {
                ButtonPair(
                    LocalDensity.current.fontScale >= 1.5f,
                    secondary = { m -> OutlinedButton(onClick = onDismiss, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
                    primary = { m ->
                        Button(
                            onClick = {
                                if (!byPrayer) onClock(LocalTime.of(timeState.hour, timeState.minute)) else onPrayer(name!!, offset, at!!)
                                onDismiss()
                            },
                            enabled = !byPrayer || (name != null && at != null),
                            modifier = m.heightIn(min = Dimens.touch),
                        ) { Text(stringResource(R.string.done)) }
                    },
                )
            }
        }
    }

    when (dialog) {
        "city" -> CityDialog(onPick = { onCity(it.nameEn); dialog = if (settings.method == null) "method" else null }, onDismiss = { dialog = null })
        "method" -> ChoiceDialog(
            stringResource(R.string.prayer_method_title), Method.entries.map { it.name to methodName(it) }, settings.prayerMethod,
            { onMethod(it) }, { dialog = null },
        )
    }
}

/** A searchable list of the bundled cities (no location permission, F-050). */
@Composable
fun CityDialog(onPick: (Place) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val ar = arabic()
    val q = Organize.fold(query.trim())
    val list = Places.all.filter { q.isEmpty() || Organize.fold(it.nameAr).contains(q) || it.nameEn.lowercase().contains(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prayer_city_title)) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.prayer_city_search)) },
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list) { p ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Dimens.rowCompact).clickable(role = Role.Button) { onPick(p); onDismiss() }.padding(horizontal = Dimens.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (ar) p.nameAr else p.nameEn, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(p.country, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
