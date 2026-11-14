package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The alert's six "remind me again" choices, as in the Windows app's ui/lib/alert-format.js and
 * stored in settings.snoozePresets.
 */
sealed class SnoozePreset {
    data class In(val minutes: Int) : SnoozePreset()
    data class Today(val time: LocalTime) : SnoozePreset()
    data class Tomorrow(val time: LocalTime) : SnoozePreset()
    data object Week : SnoozePreset()
    data object Pick : SnoozePreset()

    fun toJson(): JsonObject = when (this) {
        is In -> JsonObject(mapOf("kind" to JsonPrimitive("in"), "minutes" to JsonPrimitive(minutes)))
        is Today -> JsonObject(mapOf("kind" to JsonPrimitive("today"), "time" to JsonPrimitive(hhmm(time))))
        is Tomorrow -> JsonObject(mapOf("kind" to JsonPrimitive("tomorrow"), "time" to JsonPrimitive(hhmm(time))))
        Week -> JsonObject(mapOf("kind" to JsonPrimitive("week")))
        Pick -> JsonObject(mapOf("kind" to JsonPrimitive("pick")))
    }

    companion object {
        private fun hhmm(t: LocalTime) = "%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute)
        private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
        const val MAX_MINUTES = 30 * 24 * 60
        val DEFAULTS: List<SnoozePreset> = listOf(In(10), In(60), Today(LocalTime.of(18, 0)), Tomorrow(LocalTime.of(9, 0)), Week, Pick)

        fun parse(e: JsonElement?): SnoozePreset? {
            val o = e as? JsonObject ?: return null
            val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val time = (o["time"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { s ->
                TIME.matchEntire(s)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            }
            return when (kind) {
                "in" -> (o["minutes"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_MINUTES }?.let { In(it) }
                // A time today closes an hour before it, so one before 01:00 could never be chosen.
                "today" -> time?.takeIf { it >= LocalTime.of(1, 0) }?.let { Today(it) }
                "tomorrow" -> time?.let { Tomorrow(it) }
                "week" -> Week
                "pick" -> Pick
                else -> null
            }
        }

        /** Always six: anything but a list of six keeps the defaults, and a bad slot gets that slot's default. */
        fun normalize(e: JsonElement?): List<SnoozePreset> {
            val list = (e as? JsonArray)?.takeIf { it.size == DEFAULTS.size } ?: return DEFAULTS
            return list.mapIndexed { i, el -> parse(el) ?: DEFAULTS[i] }
        }

        fun fromSettings(settings: JsonObject) = normalize(settings["snoozePresets"])
    }
}

/** When a preset snoozes to, or null when it does not apply now (today's time is less than an hour away, or Pick). */
fun snoozeTime(preset: SnoozePreset, now: Instant, zone: ZoneId): Instant? = when (preset) {
    // Whole days keep the wall-clock time, also across a DST change.
    is SnoozePreset.In -> if (preset.minutes % (24 * 60) == 0) plusLocalDays(now, preset.minutes / (24 * 60L), zone) else now.plusSeconds(preset.minutes * 60L)
    is SnoozePreset.Today -> todayAt(now, preset.time, zone).takeIf { now.isBefore(it.minusSeconds(3600)) }
    is SnoozePreset.Tomorrow -> todayAt(now, preset.time, zone, 1)
    SnoozePreset.Week -> plusLocalDays(now, 7, zone)
    SnoozePreset.Pick -> null
}

/** The first preset that gives a time now: what the notification's «أجّل» button uses. */
fun quickSnooze(presets: List<SnoozePreset>, now: Instant, zone: ZoneId): Instant =
    presets.firstNotNullOfOrNull { snoozeTime(it, now, zone) } ?: now.plusSeconds(600)
