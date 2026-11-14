package com.fastkiwi.rememberme.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SheetShape

/** The phone's full-screen mode as words: «دائمًا» / «للتذكيرات المهمة فقط» / «مطفأة، إشعار عادي فقط». */
@Composable
fun fullScreenModeName(mode: String): String = stringResource(when (mode) {
    "important" -> R.string.fs_important
    "off" -> R.string.fs_off
    else -> R.string.fs_always
})

/**
 * «طريقة التنبيه» (DESIGN-SPEC §5.7, F-015, F-020): «تذكير عادي» / «منبّه مهم» as two cards, with
 * «السماح في «عدم الإزعاج»» under the alarm card ([onDndHelp]: the DND help screen; the app never asks for DND
 * access); then the full-screen choice for this reminder. [style] is "normal" or "alarm"; [fullScreen] null
 * (the phone's mode, [globalMode]), "on" or "off". Nothing applies before «تم».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertStyleSheet(
    style: String,
    fullScreen: String?,
    globalMode: String,
    onDone: (style: String, fullScreen: String?) -> Unit,
    onDndHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    var s by remember { mutableStateOf(style) }
    var fs by remember { mutableStateOf(fullScreen) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Dimens.l)) {
            Text(stringResource(R.string.alert_way), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Dimens.xl).semantics { heading() })
            EditorHeader(stringResource(R.string.alert_style))
            Column(Modifier.padding(horizontal = Dimens.l).selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                ChoiceCard(Ms.Notifications, stringResource(R.string.style_normal), stringResource(R.string.style_normal_body), s == "normal", { s = "normal" })
                ChoiceCard(Ms.Alarm, stringResource(R.string.style_alarm), stringResource(R.string.style_alarm_body), s == "alarm", { s = "alarm" }) {
                    TextButton(onClick = onDndHelp, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.dnd_allow)) }
                }
            }
            EditorHeader(stringResource(R.string.full_screen))
            Column(Modifier.padding(horizontal = Dimens.s).selectableGroup()) {
                RadioRow(stringResource(R.string.fs_follow_is, fullScreenModeName(globalMode)), fs == null) { fs = null }
                RadioRow(stringResource(R.string.fs_show), fs == "on") { fs = "on" }
                RadioRow(stringResource(R.string.fs_notify_only), fs == "off") { fs = "off" }
            }
            Spacer(Modifier.size(Dimens.m))
            Column(Modifier.padding(horizontal = Dimens.l)) {
                ButtonPair(
                    LocalDensity.current.fontScale >= 1.5f,
                    secondary = { m -> OutlinedButton(onClick = onDismiss, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
                    primary = { m -> Button(onClick = { onDone(s, fs); onDismiss() }, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.done)) } },
                )
            }
        }
    }
}
