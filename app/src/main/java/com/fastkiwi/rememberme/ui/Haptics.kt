package com.fastkiwi.rememberme.ui

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * F-104: the short "confirm" haptic for a completed action («تم» and «أجّل» on the reminder screen, «حفظ» in the
 * editor). It goes through [android.view.View.performHapticFeedback] without FLAG_IGNORE_GLOBAL_SETTING, so the
 * phone's own "touch feedback" setting turns it off.
 */
object ConfirmHaptic {
    /** `CONFIRM` from Android 11 (API 30); before it the light `VIRTUAL_KEY` tick (an inlined int, read only from 30). */
    @SuppressLint("InlinedApi")
    fun constant(sdk: Int = Build.VERSION.SDK_INT): Int =
        if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
}

/** Tests replace the haptic with a recorder; null in the app (the View's haptic is used). */
val LocalConfirmHaptic = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The confirm haptic of this screen's View; call it after the action took effect. */
@Composable
fun rememberConfirmHaptic(): () -> Unit {
    val override = LocalConfirmHaptic.current
    val view = LocalView.current
    return remember(override, view) { override ?: { view.performHapticFeedback(ConfirmHaptic.constant()); Unit } }
}
