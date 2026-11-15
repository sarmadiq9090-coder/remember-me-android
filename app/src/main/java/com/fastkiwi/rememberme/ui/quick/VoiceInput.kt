package com.fastkiwi.rememberme.ui.quick

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.edit
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.L10n
import java.util.Locale

/**
 * Voice input through the phone's speech dialog (F-048), made safe for phones where it does not work (1.0.1).
 *
 * - The mic shows only when the phone has both the speech dialog and a speech service ([available]).
 * - The language is the phone's own (when it is the app's language), then the plain language, then a common
 *   region, then none at all (the service's own language) — never the region the app picked for month names
 *   (ar-IQ / ar-EG), which Google's dialog may refuse as «غير متاحة».
 * - A failed or empty result moves to the next language for the next try; a result with text keeps that
 *   language. Both are remembered on this phone only.
 *
 * In-app recognition (SpeechRecognizer, on-device on Android 13+) needs the microphone permission, which this
 * app deliberately does not have; the dialog is asked to work offline instead (EXTRA_PREFER_OFFLINE).
 */
object VoiceInput {
    /** What came back from the dialog. */
    sealed interface Outcome {
        data class Text(val text: String) : Outcome
        /** No text and no error: cancelled, Back on a dead dialog, or nothing heard. */
        data object NoText : Outcome
        /** The service reported an error (client, server, network, audio) or could not be opened. */
        data object Failed : Outcome
    }

    private const val NONE = "-"

    /** The phone has a speech dialog to open and a speech service behind it. */
    fun available(context: Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context) &&
            probe().resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    private fun probe() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)

    /**
     * The languages to try, in order; null = send no language. [app] is the app's locale, [phone] the phone's
     * own (system) locale. Only language and country are sent (no -u-nu-latn and the like).
     */
    fun candidates(app: Locale, phone: Locale): List<String?> {
        val lang = app.language.ifEmpty { "ar" }
        val out = LinkedHashSet<String?>()
        if (phone.language == lang && phone.country.isNotEmpty()) out += "$lang-${phone.country}"
        out += lang
        FALLBACK[lang]?.let { out += it }
        out += null
        return out.toList()
    }

    private val FALLBACK = mapOf("ar" to "ar-SA", "en" to "en-US")

    /** The phone's own language (Android 13+ keeps the per-app language apart; ask for the system's). */
    private fun phoneLocale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.let { return it[0] }
        }
        return Resources.getSystem().configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("quick", Context.MODE_PRIVATE)

    private fun key(tag: String?) = tag ?: NONE

    /** The language for the next try on this phone. */
    fun language(context: Context): String? {
        val list = candidates(L10n.current(context), phoneLocale(context))
        val p = prefs(context)
        val good = list.indexOfFirst { key(it) == p.getString("voiceGood", null) }
        if (good >= 0) return list[good]
        val failed = p.getStringSet("voiceFailed", emptySet()).orEmpty()
        val next = list.indexOfFirst { key(it) !in failed }
        if (next >= 0) return list[next]
        // Every language failed once: start again from the first.
        p.edit { remove("voiceFailed") }
        return list.first()
    }

    /** The dialog's intent for [tag] (null: no language), offline when it can. */
    fun intent(context: Context, tag: String?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .apply { if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag) }
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.qa_label))

    /** Reads the dialog's answer for [tag] and remembers whether that language worked. */
    fun result(context: Context, tag: String?, code: Int, data: Intent?): Outcome {
        val text = runCatching { data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) }.getOrNull()
            ?.firstOrNull { it.isNotBlank() }?.trim()
        val p = prefs(context)
        return when {
            text != null -> { p.edit { putString("voiceGood", key(tag)); remove("voiceFailed"); remove("voiceDead") }; Outcome.Text(text) }
            code == Activity.RESULT_OK || code == Activity.RESULT_CANCELED || code == RecognizerIntent.RESULT_NO_MATCH -> {
                // Back on a dialog that never worked (or one that heard nothing): the next try uses another
                // language. Only in the language that has worked here, a cancel is just a cancel.
                if (p.getString("voiceGood", null) == key(tag)) return Outcome.NoText
                failed(context, tag)
                // Every language came back empty and none ever worked: say plainly that it does not work here.
                // Once every language has failed, say so plainly every time until one works.
                if (exhausted(context)) p.edit { putBoolean("voiceDead", true) }
                if (p.getBoolean("voiceDead", false)) Outcome.Failed else Outcome.NoText
            }
            else -> { failed(context, tag); Outcome.Failed }
        }
    }

    private fun exhausted(context: Context): Boolean {
        val failed = prefs(context).getStringSet("voiceFailed", emptySet()).orEmpty()
        return candidates(L10n.current(context), phoneLocale(context)).all { key(it) in failed }
    }

    /** [tag] did not work: forget it as the working one and skip it next time. */
    fun failed(context: Context, tag: String?) {
        val p = prefs(context)
        val set = p.getStringSet("voiceFailed", emptySet()).orEmpty() + key(tag)
        p.edit {
            putStringSet("voiceFailed", set)
            if (p.getString("voiceGood", null) == key(tag)) remove("voiceGood")
        }
    }
}
