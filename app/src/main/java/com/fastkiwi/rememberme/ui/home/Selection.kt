package com.fastkiwi.rememberme.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.TooltipIconButton

/** The contextual top bar of selection mode (§5.17): close at the start, «3 محددة», select all, ⋮ «تصدير المحدد». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onExport: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        navigationIcon = { TooltipIconButton(Ms.Close, stringResource(R.string.clear_selection), onClose) },
        title = {
            Text(L10n.plural(context, R.plurals.selected_n, count.toLong()), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        },
        actions = {
            TooltipIconButton(Ms.SelectAll, stringResource(R.string.select_all), onSelectAll)
            Box {
                TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_selected)) }, leadingIcon = { Icon(Ms.Share, null) },
                        onClick = { menu = false; onExport() })
                }
            }
        },
    )
}

/**
 * The bottom action bar of selection mode: five labelled items «حذف»، «الأهمية»، «نقل»، «أجّل»، «تم», the
 * primary («تم», a filled pill) last, 64 dp high.
 */
@Composable
fun BulkActionBar(onDelete: () -> Unit, onPriority: () -> Unit, onMove: () -> Unit, onSnooze: () -> Unit, onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Dimens.s, vertical = Dimens.xs),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            BarItem(Ms.Delete, stringResource(R.string.bulk_delete), false, onDelete)
            BarItem(Ms.OutlinedFlag, stringResource(R.string.bulk_priority), false, onPriority)
            BarItem(Ms.DriveFileMove, stringResource(R.string.bulk_move), false, onMove)
            BarItem(Ms.Snooze, stringResource(R.string.snooze_title), false, onSnooze)
            BarItem(Ms.Check, stringResource(R.string.done), true, onDone)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(icon: ImageVector, label: String, primary: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = Dimens.xs),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val pill = if (primary) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(Modifier.background(pill, CircleShape).padding(horizontal = Dimens.l, vertical = Dimens.xs), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2,
            color = if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
