package com.fastkiwi.rememberme.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.prayer.AsrRule
import com.fastkiwi.rememberme.core.prayer.Method
import com.fastkiwi.rememberme.core.prayer.Places
import com.fastkiwi.rememberme.data.SettingsPrefs
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.Page
import com.fastkiwi.rememberme.ui.SectionHeader
import com.fastkiwi.rememberme.ui.SettingsRow
import com.fastkiwi.rememberme.ui.TooltipIconButton
import com.fastkiwi.rememberme.ui.Group

/** A settings row whose value can carry a small status icon (the Health row) and whose title can be red (delete all). */
@Composable
fun ValueRow(
    icon: ImageVector?, title: String, value: String?, onClick: (() -> Unit)?, valueIcon: ImageVector? = null,
    titleColor: Color? = null, trailing: @Composable (() -> Unit)? = null,
) {
    var m = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = Dimens.l, vertical = Dimens.m), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = titleColor ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = titleColor ?: Color.Unspecified)
            if (value != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (valueIcon != null) Icon(valueIcon, null, tint = if (valueIcon == Ms.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = Dimens.xs).size(16.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A single-choice dialog with a muted note under the choices (full screen, ring length, Hijri, prayer). */
@Composable
fun ChoiceWithNote(
    title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit,
    note: String, supporting: Map<String, String> = emptyMap(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (code, text) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = code == selected, role = Role.RadioButton) { onSelect(code); onDismiss() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == selected, onClick = null)
                            Column(Modifier.padding(start = Dimens.l)) {
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                                supporting[code]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = Dimens.s)) {
                    Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.s).size(18.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A time picker dialog: minutes after midnight in, minutes after midnight out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minutes / 60 % 24, minutes % 60, L10n.is24(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute); onDismiss() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.set_ok))
            }
        },
    )
}

/** «−1 يوم»، «بلا تعديل»، «+1 يوم» (Western digits; the sign is a word-independent mark). */
fun hijriAdjustLabel(context: Context, days: Int): String = when {
    days == 0 -> context.getString(R.string.set_hijri_no_adjust)
    days > 0 -> context.getString(R.string.set_hijri_plus, L10n.plural(context, R.plurals.days, days.toLong()))
    else -> context.getString(R.string.set_hijri_minus, L10n.plural(context, R.plurals.days, (-days).toLong()))
}

/** Names of the ten prayer-time methods (core/prayer/PrayerTimes.kt) and the two Asr rules. */
object PrayerNames {
    fun methods(context: Context): List<Pair<String, String>> = Method.entries.map { m ->
        m.name to context.getString(when (m) {
            Method.UMM_AL_QURA -> R.string.set_pm_umm_al_qura
            Method.MWL -> R.string.set_pm_mwl
            Method.EGYPTIAN -> R.string.set_pm_egyptian
            Method.KARACHI -> R.string.set_pm_karachi
            Method.ISNA -> R.string.set_pm_isna
            Method.DUBAI -> R.string.set_pm_dubai
            Method.KUWAIT -> R.string.set_pm_kuwait
            Method.QATAR -> R.string.set_pm_qatar
            Method.JAFARI -> R.string.set_pm_jafari
            Method.TEHRAN -> R.string.set_pm_tehran
        })
    }

    fun asr(context: Context): List<Pair<String, String>> = AsrRule.entries.map { a ->
        a.name to context.getString(if (a == AsrRule.HANAFI) R.string.set_asr_hanafi else R.string.set_asr_standard)
    }
}

/** The search top bar (DESIGN-SPEC §5.14 bottom): back at the start, the field, clear × at the end. */
@Composable
fun SettingsSearchField(query: String, onQuery: (String) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Dimens.xs, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), onBack)
        TextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.set_search_hint)) },
            label = null,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            trailingIcon = if (query.isNotEmpty()) ({ TooltipIconButton(Ms.Close, stringResource(R.string.clear), { onQuery("") }) }) else null,
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The results: a count header, rows with the match in bold primary and «في: <group>», or the empty state. */
@Composable
fun SettingsSearchResults(modifier: Modifier, query: String, hits: List<SettingsIndex.Hit>, icon: @Composable (String) -> ImageVector, onOpen: (String) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.xl), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        if (query.isBlank()) return@Column
        if (hits.isEmpty()) {
            Text(stringResource(R.string.set_no_results, query.trim()), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(Dimens.xl).semantics { liveRegion = LiveRegionMode.Polite })
            return@Column
        }
        Text(pluralStringResource(R.plurals.set_results_n, hits.size, hits.size), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s).semantics { liveRegion = LiveRegionMode.Polite })
        hits.forEachIndexed { i, h ->
            GroupItem(i, hits.size) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onOpen(h.entry.key) }
                        .padding(horizontal = Dimens.l, vertical = Dimens.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon(h.entry.key), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.l))
                    Column(Modifier.weight(1f)) {
                        val primary = MaterialTheme.colorScheme.primary
                        Text(buildAnnotatedString {
                            val r = h.highlight
                            if (r == null) append(h.title) else {
                                append(h.title.substring(0, r.first))
                                withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(h.title.substring(r.first, r.last + 1)) }
                                append(h.title.substring(r.last + 1))
                            }
                        }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                        Text(stringResource(R.string.set_in_group, h.group), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** «المدينة»: the bundled list with search (Arabic or English, letter-folded), or typed coordinates. No location. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerScreen(current: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var coords by rememberSaveable { mutableStateOf(false) }
    val en = Repo.prefs(context).language == "en"
    val q = Organize.fold(query.trim())
    val list = Places.all.filter { q.isEmpty() || Organize.fold(it.nameAr).contains(q) || Organize.fold(it.nameEn).contains(q) }
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.set_prayer_city)) },
                navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), onBack) },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.set_city_search)) },
                    leadingIcon = { Icon(Ms.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s))
                Text(stringResource(R.string.set_prayer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs))
                GroupItem(0, 1) { SettingsRow(Ms.Tune, stringResource(R.string.set_city_coords), stringResource(R.string.set_city_coords_hint), { coords = true }) }
                Spacer(Modifier.size(Dimens.s))
            }
            items(list, key = { it.nameEn }) { p ->
                val selected = p.nameEn == current
                GroupItem(0, 1, if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton) { onPick(p.nameEn) }
                            .padding(horizontal = Dimens.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(if (en) p.nameEn else p.nameAr, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.l).weight(1f))
                        Text(p.country, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (coords) {
        var lat by rememberSaveable { mutableStateOf("") }
        var lon by rememberSaveable { mutableStateOf("") }
        val la = lat.trim().replace('٫', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lo = lon.trim().replace('٫', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        AlertDialog(
            onDismissRequest = { coords = false },
            title = { Text(stringResource(R.string.set_city_coords)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    OutlinedTextField(lat, { lat = it }, singleLine = true, label = { Text(stringResource(R.string.set_latitude)) },
                        isError = lat.isNotEmpty() && la == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(lon, { lon = it }, singleLine = true, label = { Text(stringResource(R.string.set_longitude)) },
                        isError = lon.isNotEmpty() && lo == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text(stringResource(R.string.set_city_coords_zone, Repo.zone().id), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            dismissButton = { TextButton(onClick = { coords = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(enabled = la != null && lo != null, onClick = { coords = false; onPick(SettingsPrefs.geo(la!!, lo!!, Repo.zone())) }) {
                    Text(stringResource(R.string.set_ok))
                }
            },
        )
    }
}

/**
 * «الخصوصية» (F-089): every permission in the manifest and why, what the app never does, and what voice input
 * discloses. The same text is in docs/PRIVACY.md; a test checks that every manifest permission has a line here.
 */
object PrivacyText {
    /** Manifest permission → its explanation (title, why). */
    val PERMISSIONS: List<Triple<String, Int, Int>> = listOf(
        Triple("android.permission.POST_NOTIFICATIONS", R.string.set_perm_notifications, R.string.set_perm_notifications_why),
        Triple("android.permission.USE_EXACT_ALARM", R.string.set_perm_exact, R.string.set_perm_exact_why),
        Triple("android.permission.SCHEDULE_EXACT_ALARM", R.string.set_perm_exact_old, R.string.set_perm_exact_old_why),
        Triple("android.permission.USE_FULL_SCREEN_INTENT", R.string.set_perm_fullscreen, R.string.set_perm_fullscreen_why),
        Triple("android.permission.RECEIVE_BOOT_COMPLETED", R.string.set_perm_boot, R.string.set_perm_boot_why),
        Triple("android.permission.VIBRATE", R.string.set_perm_vibrate, R.string.set_perm_vibrate_why),
        Triple("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", R.string.set_perm_battery, R.string.set_perm_battery_why),
        Triple("android.permission.FOREGROUND_SERVICE", R.string.set_perm_fgs, R.string.set_perm_fgs_why),
    )
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Page(stringResource(R.string.set_privacy), onBack) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l)) {
            Row(Modifier.padding(Dimens.l)) {
                Icon(Ms.CloudOff, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.set_privacy_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = Dimens.l))
            }
        }
        SectionHeader(stringResource(R.string.set_privacy_permissions))
        Group(PrivacyText.PERMISSIONS.map { (_, title, why) -> { SettingsRow(Ms.VerifiedUser, stringResource(title), stringResource(why)) } })
        SectionHeader(stringResource(R.string.set_privacy_never))
        Group(listOf(R.string.set_never_internet, R.string.set_never_location, R.string.set_never_files, R.string.set_never_mic, R.string.set_never_stats)
            .map { id -> { SettingsRow(Ms.VisibilityOff, stringResource(id)) } })
        SectionHeader(stringResource(R.string.set_privacy_voice))
        Text(stringResource(R.string.set_privacy_voice_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Dimens.xl))
        SectionHeader(stringResource(R.string.set_privacy_backups))
        Text(stringResource(R.string.set_privacy_backups_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Dimens.xl))
    }
}

/** «التراخيص»: the font, the icons, the libraries. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Page(stringResource(R.string.licences), onBack) {
        Group(listOf(
            { SettingsRow(null, stringResource(R.string.licence_font), stringResource(R.string.licence_ofl)) },
            { SettingsRow(null, stringResource(R.string.set_licence_symbols), stringResource(R.string.licence_apache)) },
            { SettingsRow(null, stringResource(R.string.licence_libs), stringResource(R.string.licence_apache)) },
        ))
        Text("© 2026 FastKiwi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(Dimens.l))
    }
}
