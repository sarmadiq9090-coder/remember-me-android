package com.fastkiwi.rememberme.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.feedback.DeviceInfo
import com.fastkiwi.rememberme.feedback.Feedback
import com.fastkiwi.rememberme.feedback.FeedbackKind
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.Page
import java.util.Locale

/**
 * «أبلغنا عن مشكلة أو اقتراح» (1.0.5, D-081, as in Kiwi Scan). Nothing is sent from the app: the person's own mail
 * app or browser opens with the text, and the person reviews and sends it there. Device information (version,
 * Android, model, language) is attached only when the box is ticked; never a reminder.
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(FeedbackKind.PROBLEM) }
    var text by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(false) } // off by default (privacy)
    val info = remember { deviceInfo(ctx) }
    val noMail = stringResource(R.string.feedback_no_mail_app)
    val noBrowser = stringResource(R.string.feedback_no_browser)

    fun open(uri: String, action: String, failMsg: String) {
        try {
            val intent = Intent(action, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Intent.ACTION_SENDTO) {
                // Some mail apps read the extras rather than the mailto: query.
                intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(Feedback.EMAIL))
                intent.putExtra(Intent.EXTRA_SUBJECT, Feedback.subject(kind, BuildConfig.VERSION_NAME))
                intent.putExtra(Intent.EXTRA_TEXT, Feedback.body(text, if (attach) info else null))
            }
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, failMsg, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(ctx, failMsg, Toast.LENGTH_LONG).show()
        }
    }

    val attached = if (attach) info else null
    Page(stringResource(R.string.feedback_title), onBack, bottomBar = {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
            Button(
                onClick = { open(Feedback.mailto(kind, BuildConfig.VERSION_NAME, text, attached), Intent.ACTION_SENDTO, noMail) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Icon(Ms.Mail, null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.feedback_send_email)) }
            TextButton(
                onClick = { open(Feedback.githubIssue(kind, BuildConfig.VERSION_NAME, text, attached), Intent.ACTION_VIEW, noBrowser) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touch),
            ) { Icon(Ms.OpenInNew, null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.feedback_send_github)) }
            Text(stringResource(R.string.feedback_review_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.l)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.padding(Dimens.m), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.Lock, null)
                    Text(stringResource(R.string.feedback_privacy), Modifier.padding(start = Dimens.m), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.feedback_kind), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Dimens.xl, bottom = Dimens.s).semantics { heading() })
            val kinds = listOf(FeedbackKind.PROBLEM to stringResource(R.string.feedback_problem), FeedbackKind.SUGGESTION to stringResource(R.string.feedback_suggestion))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, (k, label) ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                        modifier = Modifier.heightIn(min = Dimens.touch)) { Text(label) }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = Feedback.clipInput(it) },
                label = { Text(stringResource(if (kind == FeedbackKind.PROBLEM) R.string.feedback_hint_problem else R.string.feedback_hint_suggestion)) },
                supportingText = { Text(stringResource(R.string.feedback_count, text.length.toString(), Feedback.MAX_TEXT.toString())) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.l),
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                    .toggleable(value = attach, onValueChange = { attach = it }, role = Role.Checkbox)
                    .padding(vertical = Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = attach, onCheckedChange = null)
                Text(stringResource(R.string.feedback_attach_device), Modifier.padding(start = Dimens.s))
            }
            if (attach) {
                Text(stringResource(R.string.feedback_attached_preview), style = MaterialTheme.typography.labelLarge)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.xs, bottom = Dimens.l)) {
                    Text(Feedback.deviceLines(info), Modifier.padding(Dimens.m), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Only non-identifying details: never a reminder, a title, a note or a log. */
fun deviceInfo(ctx: Context): DeviceInfo {
    val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
    return DeviceInfo(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        language = locale.toLanguageTag(),
    )
}
