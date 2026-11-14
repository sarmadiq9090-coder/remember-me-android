package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Task
import java.io.File
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
fun Radios(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        options.forEach { (code, text) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = code == selected, role = Role.RadioButton) { onSelect(code) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = code == selected, onClick = null)
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
fun SwitchRow(text: String, hint: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun LinkRow(text: String, hint: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

data class SettingsModel(
    val language: String, val theme: String, val sound: Boolean, val remindAgain: Int, val weekStart: Int,
    val arabicMonths: String, val lockPrivacy: Boolean,
)

@Composable
fun SettingsScreen(
    m: SettingsModel,
    safety: List<File>,
    onLanguage: (String) -> Unit, onTheme: (String) -> Unit, onSound: (Boolean) -> Unit, onRemindAgain: (Int) -> Unit,
    onWeekStart: (Int) -> Unit, onMonths: (String) -> Unit, onLockPrivacy: (Boolean) -> Unit,
    onBackground: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onRestoreSafety: (File) -> Unit,
    onTrash: () -> Unit, onAbout: () -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    Page(stringResource(R.string.settings), onBack) {
        LinkRow(stringResource(R.string.background_title), stringResource(R.string.background_hint), onBackground)
        HorizontalDivider()
        Section(stringResource(R.string.language))
        Radios(listOf("ar" to "العربية", "en" to "English"), m.language, onLanguage)
        Section(stringResource(R.string.appearance))
        Radios(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)), m.theme, onTheme)
        Section(stringResource(R.string.sound_section))
        SwitchRow(stringResource(R.string.sound), stringResource(R.string.sound_hint), m.sound, onSound)
        Choice(
            stringResource(R.string.remind_again),
            listOf(0, 2, 5, 15).map { it.toString() to if (it == 0) stringResource(R.string.off) else stringResource(R.string.every_duration, L10n.duration(context, it.toLong())) },
            m.remindAgain.toString(),
        ) { onRemindAgain(it.toInt()) }
        Section(stringResource(R.string.calendar))
        Choice(stringResource(R.string.week_start), listOf(6, 0, 1).map { it.toString() to L10n.weekdayName(context, it) }, m.weekStart.toString()) { onWeekStart(it.toInt()) }
        if (m.language == "ar") Choice(
            stringResource(R.string.month_names),
            listOf("auto" to stringResource(R.string.months_auto), "levant" to "تشرين الثاني", "standard" to "نوفمبر"),
            m.arabicMonths, onMonths,
        )
        Section(stringResource(R.string.privacy))
        SwitchRow(stringResource(R.string.lock_privacy), stringResource(R.string.lock_privacy_hint), m.lockPrivacy, onLockPrivacy)
        Section(stringResource(R.string.backup))
        Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.export)) }
            OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.import_)) }
        }
        if (safety.isNotEmpty()) {
            Text(stringResource(R.string.safety_copies), style = MaterialTheme.typography.bodyMedium)
            safety.forEach { f ->
                val at = Instant.ofEpochMilli(f.lastModified())
                LinkRow(stringResource(R.string.safety_copy_from, L10n.dueText(context, at)), stringResource(R.string.tap_to_restore)) { onRestoreSafety(f) }
            }
        }
        HorizontalDivider()
        LinkRow(stringResource(R.string.trash), null, onTrash)
        LinkRow(stringResource(R.string.about), null, onAbout)
    }
}

@Composable
fun CheckItem(ok: Boolean?, title: String, text: String, action: String?, essential: Boolean = true, onClick: (() -> Unit)?) {
    // Red is only for what reminders cannot work without; a missing extra is shown as advice.
    val shown = if (ok == false && !essential) null else ok
    Card(colors = CardDefaults.cardColors(containerColor = when {
        shown == false -> MaterialTheme.colorScheme.errorContainer
        ok == false -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    })) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (shown) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Error; null -> Icons.Filled.Info },
                contentDescription = stringResource(when { shown == true -> R.string.status_ok; shown == false -> R.string.status_needed; ok == false -> R.string.status_recommended; else -> R.string.status_check }),
                tint = when (shown) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.secondary },
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (action != null && onClick != null && ok != true) TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
            }
        }
    }
}

@Composable
fun BackgroundScreen(health: Health, maker: Maker?, onFix: (String) -> Unit, onBack: () -> Unit) {
    Page(stringResource(R.string.background_title), onBack) {
        Text(stringResource(R.string.background_intro), style = MaterialTheme.typography.bodyLarge)
        CheckItem(health.notifications, stringResource(R.string.chk_notifications), stringResource(R.string.chk_notifications_text), stringResource(R.string.allow)) { onFix("notifications") }
        if (health.notifications && !health.channel) CheckItem(false, stringResource(R.string.chk_channel), stringResource(R.string.chk_channel_text), stringResource(R.string.open_settings)) { onFix("channel") }
        CheckItem(health.exact, stringResource(R.string.chk_exact), stringResource(R.string.chk_exact_text), stringResource(R.string.allow)) { onFix("exact") }
        CheckItem(health.fullScreen, stringResource(R.string.chk_fullscreen), stringResource(R.string.chk_fullscreen_text), stringResource(R.string.allow), essential = false) { onFix("fullscreen") }
        CheckItem(health.battery, stringResource(R.string.chk_battery), stringResource(R.string.chk_battery_text), stringResource(R.string.allow), essential = false) { onFix("battery") }
        CheckItem(null, stringResource(R.string.chk_hibernation), stringResource(R.string.chk_hibernation_text), stringResource(R.string.open_settings)) { onFix("hibernation") }
        if (health.dnd) CheckItem(null, stringResource(R.string.chk_dnd), stringResource(R.string.chk_dnd_text), stringResource(R.string.open_settings)) { onFix("dnd") }
        if (maker != null) CheckItem(null, stringResource(R.string.chk_maker, stringResource(maker.name)), stringResource(maker.steps), stringResource(R.string.open_settings)) { onFix("maker") }
        Text(stringResource(R.string.background_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TrashScreen(state: AppState, now: Instant, onRestore: (Task) -> Unit, onPurge: (Task) -> Unit, onEmpty: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val items = state.tasks.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    Page(stringResource(R.string.trash), onBack) {
        Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.bodyLarge)
        items.forEach { t ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.deleted_at, L10n.dueText(context, t.deletedAt!!, now)), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onRestore(t) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.restore)) }
                        TextButton(onClick = { onPurge(t) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.delete_forever)) }
                    }
                }
            }
        }
        if (items.isNotEmpty()) OutlinedButton(onClick = onEmpty, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.empty_trash)) }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Page(stringResource(R.string.about), onBack) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.version_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyLarge)
        Section(stringResource(R.string.licences))
        Text(stringResource(R.string.licences_text), style = MaterialTheme.typography.bodyMedium)
        Text("© 2026 FastKiwi", style = MaterialTheme.typography.bodySmall)
    }
}
