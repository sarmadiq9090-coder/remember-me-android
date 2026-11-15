package com.fastkiwi.rememberme.ui.quick

import android.app.Activity
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.speech.SpeechRecognizer
import androidx.core.content.edit
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.L10n
import java.util.Locale

/**
 * Voice input through the phone's speech dialog (F-048), made safe for phones where it does not work (1.0.1).
 *
 * - The mic shows only when the phone has both the speech dialog and a speech service ([available]).
 * - 1.0.2: the service is asked first which languages it supports (and, on Android 13+, which are on the
 *   phone). The language is one it supports: the phone's own, then ar-SA, ar-EG, ar-AE, ar; without a list,
 *   ar-SA first. Never the region the app picked for month names, and ar-IQ only when the service lists it.
 *   Offline is asked only for a language that is on the phone.
 * - A failed or empty result moves to the next language for the next try; a result with text keeps that
 *   language. Both are remembered on this phone only.
 *
 * In-app recognition (SpeechRecognizer, on-device on Android 13+) needs the microphone permission, which this
 * app deliberately does not have; the dialog is asked to work offline instead, when the language is on the phone.
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
     * What the phone's speech service says it can do: the languages it understands ([supported], null when it
     * did not say) and the ones it has on the phone itself ([offline]).
     */
    data class Details(val supported: List<String>?, val offline: Set<String> = emptySet())

    /** Tests and the black-box driver answer for the speech service here. */
    @VisibleForTesting var detailsSource: (suspend (Context) -> Details?)? = null

    private const val ASK_MS = 1_500L
    private const val KEEP_MS = 10 * 60_000L
    private var cached: Pair<Long, Details?>? = null

    private val PREFERRED = mapOf(
        "ar" to listOf("ar-SA", "ar-EG", "ar-AE", "ar"),
        "en" to listOf("en-US", "en-GB", "en"),
    )

    private fun norm(tag: String) = tag.replace('_', '-').lowercase(Locale.ROOT)
    private fun same(a: String, b: String) = norm(a) == norm(b)

    /**
     * The languages to try, in order; null = send no language. [app] is the app's locale, [phone] the phone's
     * own (system) locale, [d] what the service said. With a list: only languages in it (the phone's own, then
     * ar-SA, ar-EG, ar-AE, ar), so ar-IQ only when the service says it has it. Without a list: ar-SA first, never
     * the phone's region (1.0.2: an Iraqi phone got Google's «غير متاحة» for ar-IQ). Only language and country
     * are sent (no -u-nu-latn and the like).
     */
    fun candidates(app: Locale, phone: Locale, d: Details? = null): List<String?> {
        val lang = app.language.ifEmpty { "ar" }
        val prefer = PREFERRED[lang] ?: listOf(lang)
        val out = LinkedHashSet<String?>()
        val sup = d?.supported?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
        if (sup == null) {
            out += prefer
        } else {
            val phoneTag = if (phone.language == lang && phone.country.isNotEmpty()) "$lang-${phone.country}" else null
            // The service's own spelling of each language it has.
            for (w in listOfNotNull(phoneTag) + prefer) sup.firstOrNull { same(it, w) }?.let { out += it.replace('_', '-') }
            if (out.isEmpty()) sup.firstOrNull { norm(it).substringBefore('-') == lang }?.let { out += it.replace('_', '-') }
        }
        out += null
        return out.toList()
    }

    /** Offline only for a language the service has on the phone itself. */
    fun offline(tag: String?, d: Details?): Boolean = tag != null && d?.offline?.any { same(it, tag) } == true

    /**
     * Asks the speech service which languages it supports (the standard language-details broadcast) and, on
     * Android 13+, which it has on the phone; each answer has [ASK_MS] at most. Kept for [KEEP_MS].
     */
    suspend fun details(context: Context): Details? {
        detailsSource?.let { return it(context) }
        val now = System.currentTimeMillis()
        cached?.let { (at, d) -> if (now - at < KEEP_MS) return d }
        val listed = withTimeoutOrNull(ASK_MS) { runCatching { askLanguages(context) }.getOrNull() }
        val support = if (Build.VERSION.SDK_INT >= 33) withTimeoutOrNull(ASK_MS) { runCatching { askSupport(context) }.getOrNull() } else null
        val supported = listed?.takeIf { it.isNotEmpty() } ?: support?.first?.takeIf { it.isNotEmpty() }
        val offline = support?.second.orEmpty()
        val d = if (supported == null && offline.isEmpty()) null else Details(supported, offline)
        cached = now to d
        return d
    }

    /** RecognizerIntent's language-details broadcast: EXTRA_SUPPORTED_LANGUAGES, or null when nobody answers. */
    private suspend fun askLanguages(context: Context): List<String>? {
        val ask = RecognizerIntent.getVoiceDetailsIntent(context) ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { c ->
                context.sendOrderedBroadcast(ask, null, object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val list = runCatching { getResultExtras(true).getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES) }.getOrNull()
                        if (c.isActive) c.resume(list)
                    }
                }, null, Activity.RESULT_OK, null, null)
            }
        }
    }

    /** Android 13+: (online + on-phone languages, on-phone languages) from the speech service itself. */
    @RequiresApi(33)
    private suspend fun askSupport(context: Context): Pair<List<String>, Set<String>>? = withContext(Dispatchers.Main) {
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { c ->
                sr.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(r: RecognitionSupport) {
                            val on = r.installedOnDeviceLanguages.toSet()
                            if (c.isActive) c.resume((r.onlineLanguages + r.installedOnDeviceLanguages + r.supportedOnDeviceLanguages).distinct() to on)
                        }
                        override fun onError(error: Int) { if (c.isActive) c.resume(null) }
                    })
            }
        } finally {
            runCatching { sr.destroy() }
        }
    }

    /** The phone's own language (Android 13+ keeps the per-app language apart; ask for the system's). */
    private fun phoneLocale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.let { return it[0] }
        }
        return Resources.getSystem().configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("quick", Context.MODE_PRIVATE)

    private fun key(tag: String?) = tag ?: NONE

    private var lastList: List<String?>? = null

    /** The language for the next try on this phone, given what the service said ([d]). */
    fun language(context: Context, d: Details? = null): String? {
        val list = candidates(L10n.current(context), phoneLocale(context), d)
        lastList = list
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

    /** The dialog's intent for [tag] (null: no language); [offline] only when that language is on the phone. */
    fun intent(context: Context, tag: String?, offline: Boolean = false): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .apply { if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag) }
        .apply { if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
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
        return (lastList ?: candidates(L10n.current(context), phoneLocale(context))).all { key(it) in failed }
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
