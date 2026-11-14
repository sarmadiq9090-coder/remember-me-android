package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Task
import java.io.File
import java.time.Instant

/** A pushed screen: back arrow, title, scrolling content, and an optional bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), onBack) },
                actions = { actions() },
            )
        },
        bottomBar = bottomBar,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.xl), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            content()
        }
    }
}

data class SettingsModel(
    val language: String, val theme: String, val sound: Boolean, val remindAgain: Int, val weekStart: Int,
    val arabicMonths: String, val lockPrivacy: Boolean, val essentialOff: Int, val trashCount: Int,
)

@Composable
fun SettingsScreen(
    m: SettingsModel,
    safety: List<File>,
    onLanguage: (String) -> Unit, onTheme: (String) -> Unit, onSound: (Boolean) -> Unit, onRemindAgain: (Int) -> Unit,
    onWeekStart: (Int) -> Unit, onMonths: (String) -> Unit, onLockPrivacy: (Boolean) -> Unit,
    onBackground: () -> Unit, onExport: () -> Unit, onExportIcs: () -> Unit, onImport: () -> Unit, onRestoreSafety: (File) -> Unit,
    onTrash: () -> Unit, onAbout: () -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var safetyPick by remember { mutableStateOf<File?>(null) }
    val languages = listOf("ar" to "العربية", "en" to "English")
    val themes = listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark))
    val months = listOf("auto" to stringResource(R.string.months_auto), "levant" to "تشرين الثاني", "standard" to "نوفمبر")
    val weekStarts = listOf(6, 0, 1).map { it.toString() to L10n.weekdayName(context, it) }
    val again = listOf(0, 2, 5, 15).map { it.toString() to if (it == 0) stringResource(R.string.off) else stringResource(R.string.every_duration, L10n.duration(context, it.toLong())) }
    Page(stringResource(R.string.settings), onBack) {
        Spacer(Modifier.size(Dimens.s))
        // The permission status, alone at the top.
        val off = m.essentialOff > 0
        GroupItem(0, 1, if (off) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
            SettingsRow(
                if (off) Ms.WarningAmber else Ms.CheckCircle, stringResource(R.string.health_title),
                if (off) L10n.plural(context, R.plurals.essential_off, m.essentialOff.toLong()) else stringResource(R.string.health_ok), onBackground,
            ) { Icon(Ms.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        SectionHeader(stringResource(R.string.section_display))
        Group(buildList {
            add { SettingsRow(Ms.Language, stringResource(R.string.language), languages.first { it.first == m.language }.second, { dialog = "language" }) }
            add { SettingsRow(Ms.Contrast, stringResource(R.string.theme), themes.first { it.first == m.theme }.second, { dialog = "theme" }) }
            if (m.language == "ar") add { SettingsRow(Ms.CalendarMonth, stringResource(R.string.month_names), months.first { it.first == m.arabicMonths }.second, { dialog = "months" }) }
            add { SettingsRow(Ms.DateRange, stringResource(R.string.week_start), weekStarts.first { it.first == m.weekStart.toString() }.second, { dialog = "week" }) }
        })
        SectionHeader(stringResource(R.string.section_alerts))
        Group(listOf(
            { SwitchRow(Ms.VolumeUp, stringResource(R.string.sound), stringResource(R.string.sound_hint), m.sound, onSound) },
            { SettingsRow(Ms.NotificationsActive, stringResource(R.string.remind_again), again.first { it.first == m.remindAgain.toString() }.second, { dialog = "again" }) },
            { SwitchRow(Ms.VisibilityOff, stringResource(R.string.lock_privacy), stringResource(R.string.lock_privacy_hint), m.lockPrivacy, onLockPrivacy) },
        ))
        SectionHeader(stringResource(R.string.section_data))
        Group(buildList {
            add { SettingsRow(Ms.Download, stringResource(R.string.export), stringResource(R.string.export_hint), onExport) }
            add { SettingsRow(Ms.SettingsBackupRestore, stringResource(R.string.import_), null, onImport) }
            add { SettingsRow(Ms.Event, stringResource(R.string.export_ics), stringResource(R.string.export_ics_hint), onExportIcs) }
            if (safety.isNotEmpty()) add { SettingsRow(Ms.History, stringResource(R.string.safety_copies), null, { dialog = "safety" }) }
            add {
                SettingsRow(Ms.Delete, stringResource(R.string.trash), null, onTrash) {
                    if (m.trashCount > 0) Text(m.trashCount.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        })
        SectionHeader(stringResource(R.string.section_about))
        Group(listOf({ SettingsRow(Ms.Info, stringResource(R.string.about), stringResource(R.string.version_text, BuildConfig.VERSION_NAME), onAbout) }))
    }
    when (dialog) {
        "language" -> ChoiceDialog(stringResource(R.string.language), languages, m.language, onLanguage, { dialog = null })
        "theme" -> ChoiceDialog(stringResource(R.string.theme), themes, m.theme, onTheme, { dialog = null })
        "months" -> ChoiceDialog(stringResource(R.string.month_names), months, m.arabicMonths, onMonths, { dialog = null })
        "week" -> ChoiceDialog(stringResource(R.string.week_start), weekStarts, m.weekStart.toString(), { onWeekStart(it.toInt()) }, { dialog = null })
        "again" -> ChoiceDialog(stringResource(R.string.remind_again), again, m.remindAgain.toString(), { onRemindAgain(it.toInt()) }, { dialog = null })
        "safety" -> ChoiceDialog(
            stringResource(R.string.safety_copies),
            safety.map { it.name to L10n.safetyLabel(context, it) },
            "", { name -> safetyPick = safety.firstOrNull { it.name == name } }, { dialog = null },
        )
    }
    safetyPick?.let { f ->
        ConfirmDialog(stringResource(R.string.restore), stringResource(R.string.restore_safety_confirm), stringResource(R.string.restore),
            { onRestoreSafety(f) }, { safetyPick = null })
    }
}

/** How a permission row looks: ready, needed (essential), off (recommended) or advice. */
enum class Status { READY, NEEDED, OFF, ADVICE }

@Composable
fun CheckRow(status: Status, icon: ImageVector? = null, title: String, text: String, action: String?, onClick: () -> Unit) {
    val (statusIcon, tint, label) = when (status) {
        Status.READY -> Triple(Ms.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.status_ok))
        Status.NEEDED -> Triple(Ms.Error, MaterialTheme.colorScheme.error, stringResource(R.string.status_needed))
        Status.OFF -> Triple(Ms.Info, MaterialTheme.colorScheme.tertiary, stringResource(R.string.status_off))
        Status.ADVICE -> Triple(icon ?: Ms.Info, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.status_check))
    }
    val fg = if (status == Status.NEEDED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(Dimens.l), verticalAlignment = Alignment.Top) {
        Icon(statusIcon, contentDescription = label, tint = tint)
        Column(Modifier.weight(1f).padding(start = Dimens.l)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = fg, modifier = Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(start = Dimens.s))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (status == Status.NEEDED) fg else MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && status != Status.READY) {
                Spacer(Modifier.size(Dimens.s))
                when (status) {
                    Status.NEEDED -> Button(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action) }
                    Status.OFF -> FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action) }
                    else -> OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action) }
                }
            }
        }
    }
}

@Composable
fun TrashScreen(state: AppState, now: Instant, onRestore: (Task) -> Unit, onPurge: (Task) -> Unit, onEmpty: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val items = state.tasks.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    var menu by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<Task?>(null) }
    var topMenu by remember { mutableStateOf(false) }
    Page(stringResource(R.string.trash), onBack, actions = {
        if (items.isNotEmpty()) Box {
            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { topMenu = true })
            DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.empty_trash)) }, onClick = { topMenu = false; confirmEmpty = true })
            }
        }
    }) {
        Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        if (items.isEmpty()) Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(Dimens.xxl))
        items.forEachIndexed { i, t ->
            GroupItem(i, items.size) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(stringResource(R.string.deleted_at, L10n.dueText(context, t.deletedAt!!, now)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onRestore(t) }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.restore)) }
                    Box {
                        TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = t.id })
                        DropdownMenu(expanded = menu == t.id, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_forever)) }, onClick = { menu = null; confirmPurge = t })
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) ConfirmDialog(stringResource(R.string.empty_trash), stringResource(R.string.empty_trash_confirm), stringResource(R.string.delete_forever),
        onEmpty, { confirmEmpty = false }, destructive = true)
    confirmPurge?.let { t ->
        ConfirmDialog(stringResource(R.string.delete_forever), stringResource(R.string.delete_forever_confirm, t.title), stringResource(R.string.delete_forever),
            { onPurge(t) }, { confirmPurge = null }, destructive = true)
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Page(stringResource(R.string.about), onBack) {
        Column(Modifier.fillMaxWidth().padding(Dimens.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            BrandMark(72.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.version_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                Row(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.CloudOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Dimens.s))
                    Text(stringResource(R.string.offline), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l)) {
            Row(Modifier.padding(Dimens.l)) {
                Icon(Ms.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.l))
            }
        }
        SectionHeader(stringResource(R.string.licences))
        Group(listOf(
            { SettingsRow(null, stringResource(R.string.licence_font), stringResource(R.string.licence_ofl)) },
            { SettingsRow(null, stringResource(R.string.licence_icons), stringResource(R.string.licence_apache)) },
            { SettingsRow(null, stringResource(R.string.licence_libs), stringResource(R.string.licence_apache)) },
        ))
        Text("© 2026 FastKiwi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(Dimens.l))
    }
}
