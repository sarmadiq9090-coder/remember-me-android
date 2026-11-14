package com.fastkiwi.rememberme.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.withExt
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import java.io.File
import java.time.Instant

/** The first versionCode of 1.0.0 (FEATURES.md: 0.1.1 = 2). */
const val WHATS_NEW_CODE = 3

/** The versionCode a data safety copy was made before ("before-update-v2-…json"), or null. */
fun updatedFromCode(f: File): Int? = Regex("^before-update-v(\\d+)-").find(f.name)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Whether «ما الجديد في 1.0.0» should show (F-106): once, and only on a phone that updated from a version before
 * 1.0.0. The store keeps a copy of the data named after the old versionCode whenever a new version first opens
 * existing data, which is the proof of an update (Prefs.lastVersion is already the new one by the time the UI asks).
 */
fun shouldShowWhatsNew(context: Context): Boolean {
    if (BuildConfig.VERSION_CODE < WHATS_NEW_CODE) return false
    val prefs = Repo.prefs(context)
    if (prefs.whatsNewSeen >= WHATS_NEW_CODE) return false
    return shouldShowWhatsNew(prefs.whatsNewSeen, Repo.store(context).safetyCopies())
}

/** The rule alone, for tests. */
fun shouldShowWhatsNew(seen: Int, safetyCopies: List<File>): Boolean =
    seen < WHATS_NEW_CODE && safetyCopies.any { f -> updatedFromCode(f)?.let { it < WHATS_NEW_CODE } == true }

/** Records that the dialog was shown, so it never comes back. */
fun markWhatsNewSeen(context: Context) { Repo.prefs(context).whatsNewSeen = maxOf(BuildConfig.VERSION_CODE, WHATS_NEW_CODE) }

/** «اجعل كل التذكيرات عادية»: every active high-priority reminder gets the normal alert type (one change). */
fun makeAllNormal(s: AppState, now: Instant): AppState = s.copy(tasks = s.tasks.map { t ->
    if (t.active && t.priority == Priority.HIGH && AlertExt.of(t).style != "normal") t.withExt(AlertExt.of(t).copy(style = "normal")).copy(updatedAt = now) else t
})

/** DESIGN-SPEC §5.25: four icon lines; «اجعل كل التذكيرات عادية» (S, first) · «حسنًا» (P, last). */
@Composable
fun WhatsNewDialog(onMakeAllNormal: () -> Unit, onOk: () -> Unit) {
    @Composable
    fun line(icon: ImageVector, text: String) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = Dimens.m))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text(stringResource(R.string.set_whatsnew_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                line(Ms.Alarm, stringResource(R.string.set_whatsnew_1))
                line(Ms.MusicNote, stringResource(R.string.set_whatsnew_2))
                line(Ms.HealthAndSafety, stringResource(R.string.set_whatsnew_3))
                line(Ms.Add, stringResource(R.string.set_whatsnew_4))
            }
        },
        dismissButton = {
            TextButton(onClick = { onMakeAllNormal(); onOk() }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text(stringResource(R.string.set_make_all_normal))
            }
        },
        confirmButton = { Button(onClick = onOk, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.set_ok)) } },
    )
}
