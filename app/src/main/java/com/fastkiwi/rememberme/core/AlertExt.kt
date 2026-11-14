package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Android-only part of a reminder, kept under one namespaced key `android` in the task (FEATURES.md
 * "Data format"). Windows keeps the key untouched and ignores it. Absent members mean the defaults.
 * Unknown members are kept as they are (forward compatibility).
 */
data class AlertExt(
    val tone: ToneRef? = null,
    /** "normal" or "alarm"; null = by priority (high → alarm). */
    val style: String? = null,
    /** "on" or "off"; null = the phone's full-screen mode. */
    val fullScreen: String? = null,
    /** "on" or "off"; null = the phone's pin setting. */
    val pin: String? = null,
    val vibrate: Boolean? = null,
    /** Extra early alerts in minutes (EARLY_MINUTES), besides `remindBefore`. */
    val alerts: List<Int> = emptyList(),
    val speak: Boolean? = null,
    /** Prayer-relative: prayer name and offset minutes. */
    val prayer: Pair<String, Int>? = null,
    /** Hijri yearly: month and day. */
    val hijri: Pair<Int, Int>? = null,
    val unknown: Map<String, JsonElement> = emptyMap(),
) {
    val isEmpty get() = tone == null && style == null && fullScreen == null && pin == null && vibrate == null &&
        alerts.isEmpty() && speak == null && prayer == null && hijri == null && unknown.isEmpty()

    fun toJson(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["v"] = JsonPrimitive(1)
        tone?.let { m["tone"] = it.toJson() }
        style?.let { m["style"] = JsonPrimitive(it) }
        fullScreen?.let { m["fullScreen"] = JsonPrimitive(it) }
        pin?.let { m["pin"] = JsonPrimitive(it) }
        vibrate?.let { m["vibrate"] = JsonPrimitive(it) }
        if (alerts.isNotEmpty()) m["alerts"] = JsonArray(alerts.map { JsonPrimitive(it) })
        speak?.let { m["speak"] = JsonPrimitive(it) }
        prayer?.let { m["prayer"] = JsonObject(mapOf("name" to JsonPrimitive(it.first), "offset" to JsonPrimitive(it.second))) }
        hijri?.let { m["hijri"] = JsonObject(mapOf("month" to JsonPrimitive(it.first), "day" to JsonPrimitive(it.second))) }
        for ((k, v) in unknown) if (k !in m) m[k] = v
        return JsonObject(m)
    }

    companion object {
        const val KEY = "android"
        private val KNOWN = setOf("v", "tone", "style", "fullScreen", "pin", "vibrate", "alerts", "speak", "prayer", "hijri")

        private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun int(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        fun of(t: Task): AlertExt = parse(t.extra[KEY])

        fun parse(e: JsonElement?): AlertExt {
            val o = e as? JsonObject ?: return AlertExt()
            return AlertExt(
                tone = ToneRef.parse(o["tone"]),
                style = str(o, "style")?.takeIf { it == "normal" || it == "alarm" },
                fullScreen = str(o, "fullScreen")?.takeIf { it == "on" || it == "off" },
                pin = str(o, "pin")?.takeIf { it == "on" || it == "off" },
                vibrate = (o["vibrate"] as? JsonPrimitive)?.booleanOrNull,
                alerts = (o["alerts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it in EARLY_MINUTES }?.distinct()?.take(3).orEmpty(),
                speak = (o["speak"] as? JsonPrimitive)?.booleanOrNull,
                prayer = (o["prayer"] as? JsonObject)?.let { p -> str(p, "name")?.let { n -> n to (int(p, "offset") ?: 0) } },
                hijri = (o["hijri"] as? JsonObject)?.let { h -> int(h, "month")?.let { m -> int(h, "day")?.let { d -> m to d } } },
                unknown = o.filterKeys { it !in KNOWN },
            )
        }
    }
}

/**
 * The reminder's own «على شاشة القفل» choice: "show", "hide", or null = the phone setting. Stored as the
 * `lock` member of the `android` object (written by the editor; kept like any member).
 */
fun AlertExt.lockChoice(): String? = (unknown["lock"] as? JsonPrimitive)?.content?.takeIf { it == "show" || it == "hide" }

/** Whether the lock screen hides this reminder's details: its own choice, else [phoneHides]. */
fun AlertExt.hidesOnLockScreen(phoneHides: Boolean): Boolean = when (lockChoice()) { "hide" -> true; "show" -> false; else -> phoneHides }

/** Puts [ext] on the task (or removes the key when it holds nothing). */
fun Task.withExt(ext: AlertExt): Task = copy(extra = JsonObject(
    if (ext.isEmpty) extra - AlertExt.KEY else extra + (AlertExt.KEY to ext.toJson()),
))

/** A tone choice: by stable reference (FEATURES.md F-014). */
data class ToneRef(val kind: String, val ref: String = "", val title: String = "") {
    fun toJson() = JsonObject(buildMap {
        put("kind", JsonPrimitive(kind))
        if (ref.isNotEmpty()) put("ref", JsonPrimitive(ref))
        if (title.isNotEmpty()) put("title", JsonPrimitive(title))
    })

    companion object {
        /** default | system (content URI) | bundled (chime-1..3) | file (sha256 of the app's copy) | silent */
        val KINDS = setOf("default", "system", "bundled", "file", "silent")
        val SILENT = ToneRef("silent")
        val DEFAULT = ToneRef("default")

        fun parse(e: JsonElement?): ToneRef? {
            val o = e as? JsonObject ?: return null
            val kind = (o["kind"] as? JsonPrimitive)?.content?.takeIf { it in KINDS } ?: return null
            return ToneRef(kind, (o["ref"] as? JsonPrimitive)?.content.orEmpty(), (o["title"] as? JsonPrimitive)?.content.orEmpty())
        }
    }
}
