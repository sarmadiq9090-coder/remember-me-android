package com.fastkiwi.rememberme.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** DESIGN-SPEC §3.5 screen motion. */
object ScreenMotion {
    const val IN_MS = 300
    const val OUT_MS = 200
    const val REDUCED_MS = 150
    /** The shared-axis travel. */
    val SHIFT = 30.dp
}

/**
 * The main window's screens over the app's own back stack (no navigation library), F-105 / DESIGN-SPEC §3.5:
 * a push slides the new screen in along the reading direction (shared axis X: 300 ms in, 200 ms out, from the
 * start side in Arabic), a pop slides back the other way; reduced motion is a 150 ms crossfade. Back is
 * predictive: while the back gesture is held the previous screen is drawn as far as the gesture has gone, and
 * letting go finishes ([onBack]) or cancels it. A screen that is leaving takes no taps, so a quick double tap
 * on «رجوع» can never pop twice. The surface colour is drawn behind both screens, so nothing flashes.
 */
@Composable
fun <S : Any> ScreenStackHost(stack: List<S>, onBack: () -> Unit, content: @Composable (S) -> Unit) {
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shift = with(LocalDensity.current) { ScreenMotion.SHIFT.roundToPx() }
    val state = remember { SeekableTransitionState(stack) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stack) { if (state.currentState != stack || state.targetState != stack) state.animateTo(stack) }
    PredictiveBackHandler(enabled = stack.size > 1) { progress ->
        val previous = stack.dropLast(1)
        try {
            progress.collect { e -> state.seekTo(e.progress.coerceIn(0f, 1f), previous) }
            onBack()
        } catch (e: CancellationException) {
            // The gesture was cancelled: the screen settles back where it was.
            scope.launch { state.animateTo(state.currentState) }
            throw e
        }
    }
    val transition = rememberTransition(state, label = "screens")
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { screenTransform(reduced, rtl, shift) },
            contentKey = { it.last() },
        ) { s ->
            val leaving = this.transition.targetState != EnterExitState.Visible
            Box(Modifier.fillMaxSize().then(if (leaving) Modifier.pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            } else Modifier)) { content(s.last()) }
        }
    }
}

/** Forward (deeper) when the stack grows or stays as deep (a screen replaced), back when it shrinks. */
internal fun <S> AnimatedContentTransitionScope<List<S>>.screenTransform(reduced: Boolean, rtl: Boolean, shift: Int): ContentTransform {
    if (reduced) return (fadeIn(tween(ScreenMotion.REDUCED_MS)) togetherWith fadeOut(tween(ScreenMotion.REDUCED_MS)))
        .using(SizeTransform(clip = false))
    val forward = targetState.size >= initialState.size
    // The next screen comes from the end side of the reading direction: the right in English, the left in Arabic.
    val end = if (rtl) -1 else 1
    val dir = if (forward) end else -end
    val enter = slideInHorizontally(tween(ScreenMotion.IN_MS, easing = FastOutSlowInEasing)) { dir * shift } +
        fadeIn(tween(ScreenMotion.IN_MS - 90, delayMillis = 90))
    val exit = slideOutHorizontally(tween(ScreenMotion.OUT_MS, easing = FastOutSlowInEasing)) { -dir * shift } +
        fadeOut(tween(ScreenMotion.OUT_MS))
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}
