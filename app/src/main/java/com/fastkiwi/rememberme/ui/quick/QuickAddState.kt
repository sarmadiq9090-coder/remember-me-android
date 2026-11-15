package com.fastkiwi.rememberme.ui.quick

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.quickadd.ArabicDates
import com.fastkiwi.rememberme.core.quickadd.EnglishDates
import com.fastkiwi.rememberme.core.quickadd.QuickAdd
import com.fastkiwi.rememberme.core.quickadd.QuickOption
import com.fastkiwi.rememberme.core.quickadd.QuickResult
import com.fastkiwi.rememberme.ui.Behaviour
import java.time.Instant
import java.time.ZoneId

/** A dated choice the person picked, or «بلا موعد». */
sealed interface QuickChoice {
    data class At(val dueAt: Instant) : QuickChoice
    data object NoDate : QuickChoice
}

/**
 * The Quick Add sheet's state (DESIGN-SPEC §5.3, F-042). The rule it keeps: **nothing is saved with a date the
 * person has not seen**. [canSave] is false while the text has changed since the preview was read (the 150 ms
 * debounce), and while the reading asks a question that has no answer yet. What [toInput] returns is exactly what
 * the preview card shows.
 */
class QuickAddState(
    initialText: String = "",
    val zone: ZoneId = ZoneId.systemDefault(),
    private val weekStart: Int = 6,
    private val clock: () -> Instant = Instant::now,
) {
    var text by mutableStateOf(initialText.take(MAX_INPUT)); private set
    /** The text [result] was read from; differs from [text] while the debounce runs. */
    var parsedText by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<QuickResult?>(null); private set
    /** The words that gave the time («بعد ساعة»), shown muted on the preview. */
    var phrase by mutableStateOf<String?>(null); private set
    var choice by mutableStateOf<QuickChoice?>(null); private set
    /** The person dropped the date with the preview's ✕. */
    var dateCleared by mutableStateOf(false); private set
    var category by mutableStateOf(Category.PERSONAL)
    var priority by mutableStateOf(Priority.NORMAL)

    init { parseNow() }

    fun onTextChange(value: String) { text = value.take(MAX_INPUT) }

    /** Appends dictated words to the field. */
    fun append(words: String) {
        val w = words.trim()
        if (w.isEmpty()) return
        onTextChange(if (text.isBlank()) w else text.trimEnd() + " " + w)
    }

    /** Reads [text] now (the sheet calls it after the debounce). A new reading forgets an earlier choice. */
    fun parseNow() {
        val t = text
        if (t == parsedText) return
        val now = clock()
        val r = if (t.isBlank()) null else QuickAdd.parse(t, now, zone, weekStart)
        result = r
        phrase = if (r?.understood == true) phraseOf(t, now) else null
        parsedText = t
        choice = null
        dateCleared = false
    }

    val pending: Boolean get() = parsedText != text

    /** The reading asks a question (AM or PM, this week or next…) that the person has to answer. */
    val asking: Boolean get() = !dateCleared && result?.ask != null

    /** At most [Behaviour.MaxQuickAddChoices] dated choices, all in the future. */
    val options: List<QuickOption> get() = if (asking) result!!.options.take(Behaviour.MaxQuickAddChoices) else emptyList()

    /** The moment the preview shows (and [toInput] saves), or null. */
    val dueAt: Instant?
        get() {
            val r = result ?: return null
            if (dateCleared) return null
            return if (r.ask != null) (choice as? QuickChoice.At)?.dueAt else r.dueAt
        }

    val repeat: Repeat get() = if (dueAt != null) result?.repeat ?: Repeat.NONE else Repeat.NONE

    /** Nothing understood, or the date was dropped: the text is saved as a title with no date. */
    val noDate: Boolean get() = !pending && text.isNotBlank() && (result?.understood != true || dateCleared || choice == QuickChoice.NoDate)

    val canSave: Boolean get() = text.isNotBlank() && !pending && result != null && (!asking || choice != null) && title.isNotEmpty()

    /** The title the preview shows: the text without the date phrase, or all of it when no date is used. */
    val title: String get() = split(dueAt != null || (asking && choice == null)).first

    fun choose(c: QuickChoice) {
        if (c is QuickChoice.At && options.none { it.dueAt == c.dueAt }) return
        choice = c
    }

    fun clearDate() { dateCleared = true; choice = null }

    /** The reminder to save, or null while [canSave] is false. */
    fun toInput(): TaskInput? = if (!canSave) null else input()

    /** What «خيارات أكثر» carries to the editor: title, date and repeat as shown (no date while a question is open). */
    fun draft(): TaskInput = input()

    private fun input(): TaskInput {
        val due = if (pending) null else dueAt
        val (title, body) = split(due != null)
        val r = result
        return TaskInput(
            title = title, body = body, priority = priority, category = category, dueAt = due,
            repeat = if (due != null) r?.repeat ?: Repeat.NONE else Repeat.NONE,
            days = if (due != null) r?.days.orEmpty() else emptyList(),
            everyDays = (r?.everyDays ?: 2).coerceIn(2, 365),
            everyHours = (r?.everyHours ?: 1).coerceIn(1, 23),
        )
    }

    /** Title (one line, at most 200 characters) and, for a long or several-line text, the whole text as the note. */
    private fun split(usePhrase: Boolean): Pair<String, String> {
        val full = text.trim()
        val r = result
        val base = dropLead(if (usePhrase && r != null && r.understood) r.title.trim() else full)
        val firstLine = base.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val title = if (firstLine.length <= MAX_TITLE) firstLine else firstLine.take(MAX_TITLE).trimEnd()
        val body = if (full.length > MAX_TITLE || full.contains('\n')) full else ""
        return title to body
    }

    /** «ذكرني أتصل بأمي» → «أتصل بأمي» (DESIGN-SPEC §5.3): the leading "remind me" is not part of the title. */
    private fun dropLead(t: String): String {
        val rest = LEAD.find(t)?.let { t.substring(it.range.last + 1).trim() } ?: return t
        return rest.ifEmpty { t }
    }

    private fun phraseOf(t: String, now: Instant): String? = runCatching {
        val typed = t.take(QuickAdd.MAX_TEXT)
        val reading = ArabicDates.parse(typed) ?: EnglishDates.parse(typed, now, zone) ?: return null
        reading.spans.sortedBy { it[0] }
            .map { typed.substring(it[0].coerceIn(0, typed.length), it[1].coerceIn(0, typed.length)).trim() }
            .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }.getOrNull()

    companion object {
        /** A shared text is cut to 2,000 characters (F-049); typing is limited the same way. */
        const val MAX_INPUT = 2_000
        const val MAX_TITLE = 200
        private val LEAD = Regex("^(?:ذكّرني|ذكرني|ذكريني|ذكّريني|remind me to|remind me)(?:\\s+|$)", RegexOption.IGNORE_CASE)
    }
}
