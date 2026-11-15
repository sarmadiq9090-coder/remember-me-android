package com.fastkiwi.rememberme.ui.health

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.groupShape

/** The word of a status (also what tests look for). */
fun statusWordRes(s: HealthStatus) = when (s) {
    HealthStatus.READY -> R.string.st_ready
    HealthStatus.NEEDED -> R.string.st_needed
    HealthStatus.RESTRICTED -> R.string.st_restricted
    HealthStatus.OFF -> R.string.st_off
    HealthStatus.INFO -> R.string.st_info
    HealthStatus.UNKNOWN -> R.string.st_unknown
}

@Composable
private fun statusIcon(s: HealthStatus): Pair<ImageVector, Color> = when (s) {
    HealthStatus.READY -> Ms.CheckCircle to MaterialTheme.colorScheme.primary
    HealthStatus.NEEDED -> Ms.Error to MaterialTheme.colorScheme.error
    HealthStatus.RESTRICTED, HealthStatus.OFF -> Ms.Info to MaterialTheme.colorScheme.tertiary
    HealthStatus.INFO -> Ms.Info to MaterialTheme.colorScheme.onSurfaceVariant
    HealthStatus.UNKNOWN -> Ms.Help to MaterialTheme.colorScheme.onSurfaceVariant
}

/** «12 ثانية» with the Arabic plural forms; Western digits. */
fun seconds(context: Context, ms: Long): String = L10n.plural(context, R.plurals.health_seconds, ms / 1000)

/** A group header with its count at the end: «ضروري … 2 من 3 جاهز». */
@Composable
fun HealthGroupHeader(title: String, ready: Pair<Int, Int>?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
            .padding(start = Dimens.xxl, end = Dimens.xxl, top = Dimens.l, bottom = Dimens.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (ready != null) Text(
            stringResource(R.string.ready_count, ready.first.toString(), ready.second.toString()),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** «أندرويد» / «الشركة المصنعة»: where the setting lives. */
@Composable
fun SourceLabel(source: HealthSource, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = Color.Transparent, contentColor = color, border = BorderStroke(1.dp, color)) {
        Text(
            stringResource(if (source == HealthSource.ANDROID) R.string.src_android else R.string.src_maker),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Dimens.s, vertical = 2.dp),
        )
    }
}

/** The one action of a row: «إصلاح» (error, filled), «اسمح» (tonal) or «افتح الإعدادات» (outlined). */
@Composable
fun HealthActionButton(kind: ActionKind, onClick: () -> Unit, label: String? = null) {
    val m = Modifier.heightIn(min = Dimens.touch)
    when (kind) {
        ActionKind.FIX -> Button(onClick = onClick, modifier = m,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text(label ?: stringResource(R.string.fix)) }
        ActionKind.ALLOW -> FilledTonalButton(onClick = onClick, modifier = m) { Text(label ?: stringResource(R.string.allow)) }
        ActionKind.OPEN_SETTINGS -> OutlinedButton(onClick = onClick, modifier = m) { Text(label ?: stringResource(R.string.open_settings)) }
    }
}

/**
 * A Health row (§5.11 item 3): status icon, headline with the status word at the end, supporting text,
 * and, when not ready, the source label at the start and one action at the end.
 */
@Composable
fun HealthRow(
    status: HealthStatus, title: String, detail: String?, source: HealthSource?, action: ActionKind?, onAction: () -> Unit,
    modifier: Modifier = Modifier, extra: String? = null, index: Int = 0, count: Int = 1,
) {
    val needed = status == HealthStatus.NEEDED
    val bg = if (needed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (needed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val sub = if (needed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, tint) = statusIcon(status)
    val wordColor = when (status) {
        HealthStatus.READY -> MaterialTheme.colorScheme.primary
        HealthStatus.NEEDED -> MaterialTheme.colorScheme.onErrorContainer
        HealthStatus.RESTRICTED, HealthStatus.OFF -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = groupShape(index, count), modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(Dimens.l), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = Dimens.l)) {
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = fg, modifier = Modifier.weight(1f))
                    Text(stringResource(statusWordRes(status)), style = MaterialTheme.typography.labelMedium, color = wordColor,
                        modifier = Modifier.padding(start = Dimens.s))
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = sub)
                if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = sub, modifier = Modifier.padding(top = Dimens.xs))
                if (source != null || action != null) {
                    Spacer(Modifier.size(Dimens.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (source != null) SourceLabel(source, if (needed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (action != null) HealthActionButton(action, onAction)
                    }
                }
            }
        }
    }
}

/** One key/value line of «الجدولة». */
@Composable
fun KeyValueRow(text: String, index: Int, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(index, count),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = 2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m).heightIn(min = 24.dp))
    }
}

/** A numbered step: a 28 dp secondaryContainer disc with the number, then the text (DND help, maker steps). */
@Composable
fun NumberedStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(horizontal = Dimens.xl, vertical = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
        // G-03: the disc is at least 28 dp and grows with the number (a circle, never clipping it at 200 %).
        Box(Modifier.testTag("numbered-step-disc").background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).squareAtLeast(28.dp),
            contentAlignment = Alignment.Center) {
            Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.width(Dimens.l))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** A square of the content's larger side, at least [min]; the content is centred. */
private fun Modifier.squareAtLeast(min: androidx.compose.ui.unit.Dp) = this.layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    val d = maxOf(p.width, p.height, min.roundToPx())
    layout(d, d) { p.place((d - p.width) / 2, (d - p.height) / 2) }
}

/** A test tag for a Health row, so tests can check its source and its one action. */
fun healthRowTag(key: String) = "health_row_$key"

/** A test tag for an alert-test part. */
fun testPartTag(part: String) = "test_part_$part"

/** Used by rows: their test tag modifier. */
fun Modifier.tagged(tag: String) = this.testTag(tag)
