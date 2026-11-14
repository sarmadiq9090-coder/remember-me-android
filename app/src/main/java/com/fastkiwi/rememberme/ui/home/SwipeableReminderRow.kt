package com.fastkiwi.rememberme.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.ui.Behaviour
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.LocalReducedMotion
import com.fastkiwi.rememberme.ui.Ms
import kotlinx.coroutines.launch

/**
 * A row with the two swipe actions of DESIGN-SPEC §5.2: start→end «تم» at once (haptic CONFIRM, the caller
 * shows the Undo snackbar), end→start «أجّل», which springs back and opens the Snooze sheet. Directions
 * follow the layout direction. Threshold 40 % of the row. [enabled] false: no swipe at all (done rows,
 * selection mode, the setting «السحب على التذكيرات» off). TalkBack users have the same two custom actions.
 */
@Composable
fun SwipeableReminderRow(
    task: Task,
    onDone: (Task) -> Unit,
    onSnoozeRequest: (Task) -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) { content(); return }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    // Not saveable on purpose: a row that comes back (Undo) must not return already swiped and act again.
    val state = remember(task.id, task.dueAt) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * Behaviour.SwipeThreshold }) }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            // The icon grows to full size once the threshold is crossed (a state change, not decoration).
            val past = state.targetValue != SwipeToDismissBoxValue.Settled
            val scale = if (reduced || past) 1f else 0.8f
            when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary).padding(horizontal = Dimens.xl).clearAndSetSemantics { },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start,
                ) {
                    Icon(Ms.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.scale(scale))
                    Spacer(Modifier.width(Dimens.s))
                    Text(stringResource(R.string.done), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                }
                SwipeToDismissBoxValue.EndToStart -> Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = Dimens.xl).clearAndSetSemantics { },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                ) {
                    Text(stringResource(R.string.snooze_title), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(Dimens.s))
                    Icon(Ms.Snooze, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.scale(scale))
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDone(task)
                }
                SwipeToDismissBoxValue.EndToStart -> onSnoozeRequest(task)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // A repeating reminder stays in the list (at its next time); a snooze changes nothing yet.
            scope.launch { state.reset() }
        },
    ) { content() }
}
