package com.fastkiwi.rememberme.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Group
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SectionHeader
import com.fastkiwi.rememberme.ui.SettingsRow
import com.fastkiwi.rememberme.update.UpdateController
import com.fastkiwi.rememberme.update.UpdateError
import com.fastkiwi.rememberme.update.UpdateState

/**
 * 1.0.5 (D-081, as Kiwi Scan's «التحديثات»): the update part of «حول ذكرني». «تحقق من التحديثات» with the state under it,
 * then «تنزيل وتثبيت» / «تثبيت» / «حاول مرة أخرى» when there is something to do; a failed check is said aloud (a polite
 * live region) with what to do; then the owner's wording and the details.
 */
@Composable
fun AboutUpdates(updates: UpdateController) {
    val st = updates.state.value
    val busy = st is UpdateState.Checking || st is UpdateState.Downloading
    val release = when (st) {
        is UpdateState.Available -> st.release
        is UpdateState.Failed -> st.release
        else -> null
    }
    SectionHeader(stringResource(R.string.updates_title))
    val rows = mutableListOf<@Composable () -> Unit>({
        SettingsRow(Ms.SystemUpdate, stringResource(R.string.update_check_now), updateStatus(st), { if (!busy) updates.checkNow() })
    })
    when {
        st is UpdateState.Ready -> rows += {
            SettingsRow(Ms.Download, stringResource(R.string.update_install_again), stringResource(R.string.update_ready), { updates.install(st.apk, st.release) })
        }
        st is UpdateState.Failed && st.error == UpdateError.INSTALL && release != null -> rows += {
            SettingsRow(Ms.Download, stringResource(R.string.update_retry), stringResource(R.string.update_err_install), { if (!busy) updates.retryInstall(release) })
        }
        release != null -> rows += {
            SettingsRow(Ms.Download, stringResource(R.string.update_download_install), stringResource(R.string.update_available, release.version.toString()),
                { if (!busy) updates.downloadAndInstall(release) })
        }
    }
    Group(rows)
    if (st is UpdateState.Failed && st.error != UpdateError.INSTALL) {
        Text(
            stringResource(updateErrorText(st.error)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.s).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Spacer(Modifier.height(Dimens.s))
    Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.xl))
    Spacer(Modifier.height(Dimens.s))
    Text(stringResource(R.string.update_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.xl))
}

/** The line under «تحقق من التحديثات». */
@Composable
private fun updateStatus(st: UpdateState): String = when (st) {
    UpdateState.Idle -> stringResource(R.string.updates_version, BuildConfig.VERSION_NAME)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, st.release.version.toString())
    is UpdateState.Downloading -> stringResource(
        R.string.update_downloading,
        if (st.total > 0) java.text.NumberFormat.getPercentInstance(java.util.Locale.ROOT).format(st.done.toDouble() / st.total) else "",
    )
    is UpdateState.Ready -> stringResource(R.string.update_ready)
    is UpdateState.Failed -> stringResource(R.string.updates_version, BuildConfig.VERSION_NAME) // the error is the live line under it
}

fun updateErrorText(e: UpdateError): Int = when (e) {
    UpdateError.OFFLINE -> R.string.update_err_offline
    UpdateError.NETWORK -> R.string.update_err_network
    UpdateError.NO_RELEASE -> R.string.update_err_no_release
    UpdateError.BAD_RELEASE -> R.string.update_err_bad_release
    UpdateError.WIFI_REQUIRED -> R.string.update_err_wifi
    UpdateError.SHA_MISMATCH -> R.string.update_err_sha
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_err_signature
    UpdateError.NOT_NEWER -> R.string.update_err_not_newer
    UpdateError.STORAGE -> R.string.update_err_storage
    UpdateError.INSTALL -> R.string.update_err_install
}

/** Shown once, before the first automatic check: the owner's wording (Arabic and English). */
@Composable
fun UpdateNoticeDialog(updates: UpdateController, onSettings: () -> Unit) {
    if (!updates.noticePending.value) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_notice_title)) },
        text = { Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { updates.noticeSeen(); updates.onAppOpened() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.update_notice_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { updates.noticeSeen(); onSettings() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.update_notice_settings))
            }
        },
    )
}

/** The person left the app while the update downloaded, so Android's confirmation could not open then. */
@Composable
fun UpdateConfirmDialog(updates: UpdateController) {
    if (!updates.confirmPending.value) return
    AlertDialog(
        onDismissRequest = { updates.dismissPendingConfirm() },
        title = { Text(stringResource(R.string.update_ready_title)) },
        text = { Text(stringResource(R.string.update_ready_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { updates.openPendingConfirm() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.update_install_again))
            }
        },
        dismissButton = {
            TextButton(onClick = { updates.dismissPendingConfirm() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}
