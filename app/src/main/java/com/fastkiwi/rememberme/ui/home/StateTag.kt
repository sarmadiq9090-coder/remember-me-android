package com.fastkiwi.rememberme.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms

/** What a tag says (DESIGN-SPEC §3.1 role map): always an icon and a word, never colour alone. */
enum class TagKind { SNOOZED, ALARM, PRIORITY_HIGH, PRIORITY_MED, OK, BAD, NEUTRAL }

/**
 * A small state tag: «مؤجّل» (snooze, secondaryContainer), «منبّه» (alarm, neutral), «مهم» (filled flag,
 * tertiaryContainer), «متوسط» (outlined flag). [description] replaces the spoken text («منبّه مهم» in full).
 * [emphasis] draws the alarm tag on tertiaryContainer (the reminder screen's hero).
 */
@Composable
fun StateTag(kind: TagKind, text: String, modifier: Modifier = Modifier, description: String? = null, emphasis: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val (icon: ImageVector, container: Color, content: Color) = when (kind) {
        TagKind.SNOOZED -> Triple(Ms.Snooze, cs.secondaryContainer, cs.onSecondaryContainer)
        TagKind.ALARM -> if (emphasis) Triple(Ms.Alarm, cs.tertiaryContainer, cs.onTertiaryContainer)
            else Triple(Ms.Alarm, cs.surfaceContainerHighest, cs.onSurfaceVariant)
        TagKind.PRIORITY_HIGH -> Triple(Ms.Flag, cs.tertiaryContainer, cs.onTertiaryContainer)
        TagKind.PRIORITY_MED -> Triple(Ms.OutlinedFlag, Color.Transparent, cs.onSurfaceVariant)
        TagKind.OK -> Triple(Ms.CheckCircle, cs.primaryContainer, cs.onPrimaryContainer)
        TagKind.BAD -> Triple(Ms.Error, cs.errorContainer, cs.onErrorContainer)
        TagKind.NEUTRAL -> Triple(Ms.Info, cs.surfaceContainerHighest, cs.onSurfaceVariant)
    }
    Surface(
        color = container, contentColor = content, shape = MaterialTheme.shapes.small,
        border = if (kind == TagKind.PRIORITY_MED) BorderStroke(1.dp, cs.outline) else null,
        modifier = if (description != null) modifier.semantics(mergeDescendants = true) { contentDescription = description } else modifier,
    ) {
        Row(Modifier.padding(horizontal = Dimens.s, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dimens.xs))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
