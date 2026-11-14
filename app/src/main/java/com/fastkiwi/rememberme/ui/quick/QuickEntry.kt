package com.fastkiwi.rememberme.ui.quick

import android.content.Context
import android.content.Intent
import com.fastkiwi.rememberme.MainActivity

/** A request to open the Quick Add sheet, with the text to start from (a share, dictated words). */
data class QuickAddRequest(val text: String = "", val id: Long = System.nanoTime())

/**
 * The ways into Quick Add from outside the app (DESIGN-SPEC §5.3): the launcher shortcut «إضافة سريعة», the
 * widget's «+», the Quick Settings tile and Android share all open MainActivity with [ACTION_QUICK_ADD]; the
 * shortcut «اليوم» uses [ACTION_TODAY]. Nothing is ever saved by an intent: the sheet shows the preview first.
 */
object QuickEntry {
    const val ACTION_QUICK_ADD = "com.fastkiwi.rememberme.QUICK_ADD"
    const val ACTION_TODAY = "com.fastkiwi.rememberme.TODAY"
    const val EXTRA_TEXT = "com.fastkiwi.rememberme.extra.QUICK_TEXT"

    fun quickAddIntent(context: Context, text: String? = null): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_QUICK_ADD).apply {
            if (!text.isNullOrBlank()) putExtra(EXTRA_TEXT, text.take(QuickAddState.MAX_INPUT))
        }

    /** The Quick Add an intent asks for, or null (the text is cut to 2,000 characters whoever sent it). */
    fun request(intent: Intent?): QuickAddRequest? {
        if (intent?.action != ACTION_QUICK_ADD) return null
        val text = runCatching { intent.getStringExtra(EXTRA_TEXT) }.getOrNull().orEmpty().take(QuickAddState.MAX_INPUT)
        return QuickAddRequest(text)
    }

    fun isToday(intent: Intent?): Boolean = intent?.action == ACTION_TODAY
}
