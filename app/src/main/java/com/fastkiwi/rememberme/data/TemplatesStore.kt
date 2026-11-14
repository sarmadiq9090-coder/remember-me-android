package com.fastkiwi.rememberme.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.EARLY_MINUTES
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Template
import com.fastkiwi.rememberme.core.TemplateGroup
import com.fastkiwi.rememberme.core.Templates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * The user's own templates («قوالبي», FEATURES.md F-047). Kept on this phone only until the A2 round-trip test
 * proves that Windows keeps an unknown top-level key; then they move to `android.templates` in data.json.
 * Nothing here ever creates a reminder.
 */
class TemplatesStore(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("templates", Context.MODE_PRIVATE)

    fun list(): List<Template> {
        val text = p.getString(KEY, null) ?: return emptyList()
        val arr = runCatching { Codec.json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::decode) }
    }

    /** Saves the editor's fields as a new template (the editor's ⋮ «حفظ كقالب»); returns it. */
    fun save(input: TaskInput, name: String, ext: AlertExt? = null, zone: ZoneId = ZoneId.systemDefault()): Template {
        val t = Templates.fromInput(UUID.randomUUID().toString(), name, input, ext, zone)
        write((list() + t).takeLast(MAX))
        return t
    }

    fun rename(key: String, name: String) {
        val n = name.trim().take(60)
        if (n.isEmpty()) return
        write(list().map { if (it.key == key) it.copy(name = n) else it })
    }

    fun delete(key: String) = write(list().filter { it.key != key })

    private fun write(items: List<Template>) = p.edit { putString(KEY, JsonArray(items.map(::encode)).toString()) }

    companion object {
        private const val KEY = "user"
        const val MAX = 50

        fun encode(t: Template): JsonObject = JsonObject(buildMap {
            put("v", JsonPrimitive(1))
            put("key", JsonPrimitive(t.key))
            put("name", JsonPrimitive(t.name ?: t.title))
            put("title", JsonPrimitive(t.title))
            if (t.body.isNotEmpty()) put("body", JsonPrimitive(t.body))
            put("priority", JsonPrimitive(t.priority.code))
            put("category", JsonPrimitive(t.category.code))
            put("repeat", JsonPrimitive(t.repeat.code))
            if (t.days.isNotEmpty()) put("days", JsonArray(t.days.map { JsonPrimitive(it) }))
            put("everyDays", JsonPrimitive(t.everyDays))
            put("everyHours", JsonPrimitive(t.everyHours))
            if (t.alerts.isNotEmpty()) put("alerts", JsonArray(t.alerts.map { JsonPrimitive(it) }))
            t.style?.let { put("style", JsonPrimitive(it)) }
            t.time?.let { put("time", JsonPrimitive(it.withSecond(0).withNano(0).toString())) }
        })

        private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun int(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        fun decode(o: JsonObject): Template? {
            val key = str(o, "key")?.takeIf { it.isNotEmpty() } ?: return null
            val title = str(o, "title")?.trim()?.take(200).orEmpty()
            if (title.isEmpty()) return null
            val time = str(o, "time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            return Template(
                key = key, group = TemplateGroup.MINE, name = str(o, "name")?.trim()?.take(60)?.ifEmpty { null } ?: title.take(60),
                title = title, body = str(o, "body").orEmpty().take(20_000),
                priority = Priority.of(str(o, "priority")), category = Category.of(str(o, "category")),
                repeat = if (time == null) Repeat.NONE else Repeat.of(str(o, "repeat")),
                days = (o["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it in 0..6 }?.distinct().orEmpty(),
                everyDays = (int(o, "everyDays") ?: 2).coerceIn(2, 365),
                everyHours = (int(o, "everyHours") ?: 1).coerceIn(1, 23),
                alerts = (o["alerts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it in EARLY_MINUTES }?.distinct()?.take(4).orEmpty(),
                style = str(o, "style")?.takeIf { it == "alarm" || it == "normal" },
                time = time,
            )
        }
    }
}

/** For the editor's ⋮ «حفظ كقالب» (another area calls it). Saves a template, never a reminder. */
fun saveUserTemplate(context: Context, input: TaskInput, name: String, ext: AlertExt? = null): Template =
    TemplatesStore(context).save(input, name, ext)
