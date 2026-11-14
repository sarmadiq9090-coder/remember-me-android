package com.fastkiwi.rememberme

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.fastkiwi.rememberme.ui.quick.QuickAddState
import com.fastkiwi.rememberme.ui.quick.QuickEntry

/**
 * «ذكرني» in Android's share sheet (F-049): text from any app opens the Quick Add preview. It never saves by
 * itself; the text is cut to 2,000 characters. Exported for ACTION_SEND text/plain only; no UI of its own.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = textOf(intent)
        if (text != null) {
            startActivity(QuickEntry.quickAddIntent(this, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    companion object {
        /** The shared text (subject first when both are given), trimmed and cut to 2,000 characters, or null. */
        fun textOf(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_SEND) return null
            val type = intent.type.orEmpty()
            if (!type.startsWith("text/")) return null
            val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull().orEmpty().trim()
            val subject = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString() }.getOrNull().orEmpty().trim()
            val joined = when {
                subject.isNotEmpty() && text.isNotEmpty() && !text.startsWith(subject) -> subject + "\n" + text
                text.isNotEmpty() -> text
                else -> subject
            }
            return joined.take(QuickAddState.MAX_INPUT).trim().ifEmpty { null }
        }
    }
}
