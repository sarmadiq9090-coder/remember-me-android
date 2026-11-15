package com.fastkiwi.rememberme.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The groups of the «من قالب» screen (DESIGN-SPEC §5.4). */
enum class TemplateGroup { EVENTS, BILLS, ROUTINE, MINE }

/**
 * A template (FEATURES.md F-047): what it fills in the editor, never a reminder by itself. Built-ins are data
 * in code and are named by [key] (the screen shows the localized name, which also becomes the title); the
 * user's own templates carry their [name] and [title].
 */
data class Template(
    val key: String,
    val group: TemplateGroup,
    /** The user's name for one of «قوالبي»; null for a built-in. */
    val name: String? = null,
    /** The title to fill; empty for a built-in (the screen fills its localized name). */
    val title: String = "",
    val body: String = "",
    val priority: Priority = Priority.NORMAL,
    val category: Category = Category.PERSONAL,
    val repeat: Repeat = Repeat.NONE,
    val days: List<Int> = emptyList(),
    val everyDays: Int = 2,
    val everyHours: Int = 1,
    /** Early alerts in minutes, each one of [EARLY_MINUTES], largest first. */
    val alerts: List<Int> = emptyList(),
    /** "alarm" for «منبّه مهم»; null = by priority. */
    val style: String? = null,
    /** The time of day the editor starts with; null = no date (a note). */
    val time: LocalTime? = LocalTime.of(9, 0),
) {
    val builtIn get() = name == null
}

/** What a template puts in the editor: the fields, plus the Android-only part (extra alerts, alert style). */
/** [draft]: text the person typed (Quick Add's «خيارات أكثر»), not a template: Back asks before dropping it. */
data class TemplateFill(val input: TaskInput, val ext: AlertExt = AlertExt(), val draft: Boolean = false)

object Templates {
    private const val DAY = 1440
    private const val HOUR = 60

    /**
     * The built-ins, in screen order. The lead times are limited to the ones both apps know ([EARLY_MINUTES]:
     * 5, 10, 15, 30 minutes, 1 hour, 1 day), so a week, two days or 30 days before become 1 day before.
     */
    val BUILT_IN: List<Template> = listOf(
        Template("birthday", TemplateGroup.EVENTS, repeat = Repeat.YEARLY, alerts = listOf(DAY)),
        Template("anniversary", TemplateGroup.EVENTS, repeat = Repeat.YEARLY, alerts = listOf(DAY)),
        Template("yearly", TemplateGroup.EVENTS, repeat = Repeat.YEARLY),
        Template("bill", TemplateGroup.BILLS, repeat = Repeat.MONTHLY, alerts = listOf(DAY)),
        Template("medical", TemplateGroup.BILLS, alerts = listOf(DAY, HOUR)),
        Template("document", TemplateGroup.BILLS, repeat = Repeat.YEARLY, alerts = listOf(DAY)),
        Template("medicine", TemplateGroup.ROUTINE, repeat = Repeat.DAILY, style = "alarm"),
        Template("maintenance", TemplateGroup.ROUTINE, repeat = Repeat.INTERVAL, everyDays = 180),
        Template("weekly", TemplateGroup.ROUTINE, repeat = Repeat.WEEKLY),
    )

    /** The first moment at [time] still ahead of [now]: today, or tomorrow once it has passed. */
    fun nextAt(time: LocalTime, now: Instant, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate()
        val first = localToInstant(today.atTime(time), zone)
        return if (first.isAfter(now)) first else localToInstant(today.plusDays(1).atTime(time), zone)
    }

    /**
     * The editor's fields for [t]. [title] is the localized name of a built-in (a user template keeps its own
     * title). Saves nothing. The nearest alert goes to `remindBefore` (the one Windows reads), the others to the
     * Android part (F-046).
     */
    fun fill(t: Template, title: String, now: Instant, zone: ZoneId): TemplateFill {
        val due = t.time?.let { nextAt(it, now, zone) }
        val alerts = if (due == null) emptyList() else t.alerts.filter { it in EARLY_MINUTES }.distinct().sortedDescending()
        val input = TaskInput(
            title = t.title.ifBlank { title }.trim().take(200),
            body = t.body,
            priority = t.priority,
            category = t.category,
            dueAt = due,
            repeat = if (due == null) Repeat.NONE else t.repeat,
            days = t.days,
            everyDays = t.everyDays,
            everyHours = t.everyHours,
            remindBefore = alerts.lastOrNull() ?: 0,
        )
        return TemplateFill(input, AlertExt(alerts = alerts.dropLast(1).take(3), style = t.style?.takeIf { it == "alarm" || it == "normal" }))
    }

    /** A user template from the editor's fields (⋮ «حفظ كقالب»): the time of day is kept, never the date. */
    fun fromInput(key: String, name: String, input: TaskInput, ext: AlertExt?, zone: ZoneId): Template {
        val alerts = (listOf(input.remindBefore) + ext?.alerts.orEmpty()).filter { it in EARLY_MINUTES }.distinct().sortedDescending()
        val time = input.dueAt?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)
        return Template(
            key = key, group = TemplateGroup.MINE, name = name.trim().take(60).ifEmpty { input.title.trim().take(60) },
            title = input.title.trim().take(200), body = input.body.take(20_000), priority = input.priority,
            category = input.category, repeat = if (time == null) Repeat.NONE else input.repeat, days = input.days,
            everyDays = input.everyDays, everyHours = input.everyHours, alerts = if (time == null) emptyList() else alerts,
            style = ext?.style, time = time,
        )
    }
}
