package com.fastkiwi.rememberme.ui.quick

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Template
import com.fastkiwi.rememberme.core.TemplateFill
import com.fastkiwi.rememberme.core.TemplateGroup
import com.fastkiwi.rememberme.core.Templates
import com.fastkiwi.rememberme.data.TemplatesStore
import com.fastkiwi.rememberme.ui.ConfirmDialog
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.GroupItem
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.Page
import com.fastkiwi.rememberme.ui.SectionHeader
import java.time.Instant

/**
 * «من قالب» with the phone's own templates (F-047). A tap gives [onPick] the editor's fields; nothing is saved
 * (the editor saves on «حفظ»). Long-press on one of «قوالبي» renames or deletes it.
 */
@Composable
fun TemplatesRoute(onPick: (TemplateFill) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TemplatesStore(context) }
    var user by remember { mutableStateOf(store.list()) }
    TemplatesScreen(
        user = user,
        onPick = { t -> onPick(Templates.fill(t, templateName(context, t), Instant.now(), Repo.zone())) },
        onRename = { t, name -> store.rename(t.key, name); user = store.list() },
        onDelete = { t -> store.delete(t.key); user = store.list() },
        onBack = onBack,
    )
}

/** The screen itself (stateless). */
@Composable
fun TemplatesScreen(
    user: List<Template>,
    onPick: (Template) -> Unit,
    onRename: (Template, String) -> Unit,
    onDelete: (Template) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Template?>(null) }
    var deleting by remember { mutableStateOf<Template?>(null) }
    Page(stringResource(R.string.templates_title), onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ms.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.m))
            Text(stringResource(R.string.templates_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (group in listOf(TemplateGroup.EVENTS, TemplateGroup.BILLS, TemplateGroup.ROUTINE)) {
            val items = Templates.BUILT_IN.filter { it.group == group }
            SectionHeader(stringResource(groupName(group)))
            items.forEachIndexed { i, t -> TemplateRow(t, i, items.size, onClick = { onPick(t) }) }
        }
        SectionHeader(stringResource(R.string.tpl_mine))
        if (user.isEmpty()) Text(stringResource(R.string.tpl_mine_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        user.forEachIndexed { i, t ->
            TemplateRow(t, i, user.size, onClick = { onPick(t) }, onRename = { renaming = t }, onDelete = { deleting = t })
        }
    }
    renaming?.let { t -> RenameDialog(t.name.orEmpty(), onSave = { onRename(t, it) }, onDismiss = { renaming = null }) }
    deleting?.let { t ->
        ConfirmDialog(
            title = stringResource(R.string.tpl_delete_title, t.name.orEmpty()), text = stringResource(R.string.tpl_delete_body),
            confirm = stringResource(R.string.delete), onConfirm = { onDelete(t) }, onDismiss = { deleting = null }, destructive = true,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateRow(t: Template, index: Int, count: Int, onClick: () -> Unit, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    val name = templateName(context, t)
    var menu by remember { mutableStateOf(false) }
    GroupItem(index, count) {
        Box {
            val actions = stringResource(R.string.tpl_actions, name)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .combinedClickable(
                        role = Role.Button, onClick = onClick,
                        onLongClick = if (onRename != null) ({ menu = true }) else null,
                        onLongClickLabel = if (onRename != null) actions else null,
                    )
                    .padding(start = Dimens.l, end = Dimens.s, top = Dimens.m, bottom = Dimens.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = CircleShape, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(templateIcon(t), null, Modifier.size(24.dp)) }
                }
                Spacer(Modifier.width(Dimens.l))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                    templateSummary(context, t)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Ms.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRename != null && onDelete != null) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.tpl_rename)) }, leadingIcon = { Icon(Ms.Edit, null) },
                    onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Ms.Delete, null) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tpl_rename_title)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                label = { Text(stringResource(R.string.tpl_name)) }, modifier = Modifier.fillMaxWidth())
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { onSave(name); onDismiss() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
    )
}

private fun groupName(g: TemplateGroup) = when (g) {
    TemplateGroup.EVENTS -> R.string.tpl_group_events
    TemplateGroup.BILLS -> R.string.tpl_group_bills
    TemplateGroup.ROUTINE -> R.string.tpl_group_routine
    TemplateGroup.MINE -> R.string.tpl_mine
}

/** A built-in's localized name (also the title it fills), or the user's own name. */
fun templateName(context: Context, t: Template): String = t.name ?: context.getString(when (t.key) {
    "birthday" -> R.string.tpl_birthday
    "anniversary" -> R.string.tpl_anniversary
    "yearly" -> R.string.tpl_yearly
    "bill" -> R.string.tpl_bill
    "medical" -> R.string.tpl_medical
    "document" -> R.string.tpl_document
    "medicine" -> R.string.tpl_medicine
    "maintenance" -> R.string.tpl_maintenance
    else -> R.string.tpl_weekly
})

// Closest vendored symbols (cake, favorite, medical_services, medication and build are not vendored).
@Composable
private fun templateIcon(t: Template): ImageVector = when (t.key) {
    "birthday" -> Ms.Event
    "anniversary" -> Ms.CalendarMonth
    "yearly" -> Ms.DateRange
    "bill" -> Ms.ReceiptLong
    "medical" -> Ms.HealthAndSafety
    "document" -> Ms.Today
    "medicine" -> Ms.Alarm
    "maintenance" -> Ms.Tune
    "weekly" -> Ms.Repeat
    else -> Ms.DashboardCustomize
}

/** «يوم», «ساعة», «30 دقيقة»: a lead time in its largest whole unit. */
private fun lead(context: Context, minutes: Int): String = when {
    minutes % 1440 == 0 -> L10n.plural(context, R.plurals.days, minutes / 1440L)
    minutes % 60 == 0 -> L10n.plural(context, R.plurals.hours, minutes / 60L)
    else -> L10n.plural(context, R.plurals.minutes, minutes.toLong())
}

/** What a template fills, in words: «كل سنة، تنبيه قبل يوم», «تنبيه قبل يوم وقبل ساعة», «كل يوم، منبّه مهم». */
fun templateSummary(context: Context, t: Template): String? {
    val repeat = when (t.repeat) {
        Repeat.NONE -> null
        Repeat.DAILY -> context.getString(R.string.repeat_daily)
        Repeat.WEEKLY -> context.getString(R.string.repeat_weekly)
        Repeat.DAYS -> L10n.daysSummary(context, 6, t.days)
        Repeat.INTERVAL -> context.getString(R.string.repeat_every, L10n.plural(context, R.plurals.days, t.everyDays.toLong()))
        Repeat.HOURS -> context.getString(R.string.repeat_every, L10n.plural(context, R.plurals.hours, t.everyHours.toLong()))
        Repeat.MONTHLY -> context.getString(R.string.repeat_monthly)
        Repeat.YEARLY -> context.getString(R.string.repeat_yearly)
    }
    val alerts = t.alerts.sortedDescending()
    val alertText = alerts.firstOrNull()?.let { first ->
        alerts.drop(1).fold(context.getString(R.string.tpl_alert_before, lead(context, first))) { acc, m ->
            context.getString(R.string.tpl_and_before, acc, lead(context, m))
        }
    }
    val style = if (t.style == "alarm") context.getString(R.string.tpl_style_alarm) else null
    val title = if (!t.builtIn && t.title != t.name) t.title else null
    return L10n.join(context, listOf(title, repeat, alertText, style)).ifEmpty { null }
}
