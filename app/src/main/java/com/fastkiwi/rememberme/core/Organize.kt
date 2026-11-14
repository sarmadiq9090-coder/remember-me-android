package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

/**
 * Tags and steps, stored in the task exactly as the Windows app stores them (`tags`, `checklist`), with
 * Windows' own normalisation (src/core/tasks.js `normalizeTags`, `normalizeChecklist`), so a file edited
 * on either side reads the same (FEATURES.md F-062, F-070).
 */
object Organize {
    const val MAX_TAGS = 10
    const val TAG_CHARS = 30
    const val MAX_STEPS = 50
    const val STEP_CHARS = 200
    private val STEP_ID = Regex("^[\\w-]{1,40}$")

    /** Up to 10 tags of up to 30 characters; "#" prefixes, extra spaces and case duplicates dropped. */
    fun normalizeTags(value: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in value) {
            val tag = raw.replace(Regex("^#+"), "").replace(Regex("\\s+"), " ").trim().take(TAG_CHARS)
            if (tag.isNotEmpty() && seen.add(tag.lowercase()) && out.size < MAX_TAGS) out += tag
            else if (tag.isNotEmpty() && out.size >= MAX_TAGS) break
        }
        return out
    }

    data class Step(val id: String, val text: String, val done: Boolean)

    /** Up to 50 steps of up to 200 characters, each with a stable id (a new one when missing or repeated). */
    fun normalizeSteps(value: List<Step>, newId: () -> String = { UUID.randomUUID().toString() }): List<Step> {
        val seen = HashSet<String>()
        return value.filter { it.text.isNotBlank() }.take(MAX_STEPS).map { s ->
            val id = if (STEP_ID.matches(s.id) && s.id !in seen) s.id else newId()
            seen += id
            Step(id, s.text.trim().take(STEP_CHARS), s.done)
        }
    }

    fun parseSteps(e: JsonElement?): List<Step> = (e as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        Step((o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), text, (o["done"] as? JsonPrimitive)?.booleanOrNull == true)
    }.orEmpty()

    fun parseTags(e: JsonElement?): List<String> =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    /** Windows' letter folding for search and tag matching (F-065): أ/إ/آ → ا، ى → ي، ة → ه, no diacritics or tatweel. */
    fun fold(s: String): String = buildString(s.length) {
        for (ch in s.lowercase()) when (ch) {
            'أ', 'إ', 'آ', 'ٱ' -> append('ا')
            'ى' -> append('ي')
            'ة' -> append('ه')
            'ـ' -> {}
            in 'ً'..'ٟ', 'ٰ' -> {}
            else -> append(ch)
        }
    }
}

/** The task's tags (Windows-normalised). */
val Task.tags: List<String> get() = Organize.normalizeTags(Organize.parseTags(extra["tags"]))

/** The task's steps (Windows-normalised; ids kept). */
val Task.steps: List<Organize.Step> get() = Organize.parseSteps(extra["checklist"])

/** Replaces the tags (an empty list removes the key, as Windows does). */
fun Task.withTags(tags: List<String>): Task {
    val n = Organize.normalizeTags(tags)
    return copy(extra = JsonObject(if (n.isEmpty()) extra - "tags" else extra + ("tags" to JsonArray(n.map { JsonPrimitive(it) }))))
}

/** Replaces the steps (an empty list removes the key, as Windows does). */
fun Task.withSteps(steps: List<Organize.Step>): Task {
    val n = Organize.normalizeSteps(steps)
    return copy(extra = JsonObject(if (n.isEmpty()) extra - "checklist" else extra + ("checklist" to JsonArray(n.map {
        JsonObject(mapOf("id" to JsonPrimitive(it.id), "text" to JsonPrimitive(it.text), "done" to JsonPrimitive(it.done)))
    }))))
}

/**
 * A saved filter (F-063). A "list" is `kind = "tag"`; other kinds are the built-in quick filters. Kept on
 * the phone (data/Filters.kt) until Windows is known to keep a top-level `android` object (A2).
 */
data class SavedFilter(val id: String, val name: String, val kind: String, val value: String = "") {
    companion object {
        /** tag (a list), priority (high), overdue, week (this week), untagged */
        val KINDS = setOf("tag", "priority", "overdue", "week", "untagged")
    }
}
