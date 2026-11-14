package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class Priority(val code: String) {
    NORMAL("normal"), MEDIUM("medium"), HIGH("high");
    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } ?: NORMAL }
}

enum class Category(val code: String) {
    PERSONAL("personal"), WORK("work"), STUDY("study");
    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } ?: PERSONAL }
}

/**
 * One task of the Windows data.json (ARCHITECTURE.md, "Data: data.json"). The fields this app uses are
 * typed; every other field (tags, checklist, attachments, anything a later Windows version adds) is kept
 * in [extra] and written back unchanged.
 */
data class Task(
    val id: String,
    val title: String,
    val body: String = "",
    val priority: Priority = Priority.NORMAL,
    val category: Category = Category.PERSONAL,
    val dueAt: Instant? = null,
    val scheduleAt: Instant? = null,
    val repeat: Repeat = Repeat.NONE,
    val rule: RepeatRule = RepeatRule(),
    val remindBefore: Int = 0,
    val snoozeCount: Int = 0,
    val snoozedAt: Instant? = null,
    val completedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val sourceId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val active: Boolean get() = completedAt == null && deletedAt == null
}

/** The whole data file: tasks plus everything else (notes, attachments, settings) kept as JSON. */
data class AppState(
    val tasks: List<Task> = emptyList(),
    /** Every top-level field except version and tasks: notes, attachments, settings, unknown ones. */
    val rest: JsonObject = JsonObject(mapOf("notes" to JsonArray(emptyList()), "attachments" to JsonArray(emptyList()), "settings" to JsonObject(emptyMap()))),
) {
    val settings: JsonObject get() = rest["settings"] as? JsonObject ?: JsonObject(emptyMap())
    fun withSettings(settings: JsonObject) = copy(rest = JsonObject(rest + ("settings" to settings)))
    fun task(id: String) = tasks.firstOrNull { it.id == id }
}

class DataFormatException(message: String) : Exception(message)

object Codec {
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** JavaScript's toISOString(): always UTC with milliseconds. */
    fun iso(at: Instant): String = ISO.format(at)

    fun parseInstant(value: JsonElement?): Instant? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    private val TASK_KEYS = setOf(
        "id", "title", "body", "priority", "category", "dueAt", "scheduleAt", "repeat", "repeatDays",
        "repeatEvery", "repeatHours", "repeatFrom", "remindBefore", "snoozeCount", "snoozedAt",
        "completedAt", "deletedAt", "sourceId", "createdAt", "updatedAt",
    )

    /** Reads data.json. Odd values are normalized (as validateState does on Windows); a wrong shape throws. */
    fun decode(text: String, now: Instant = Instant.now()): AppState {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw DataFormatException("not a JSON object")
        if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) throw DataFormatException("version is not 1")
        val tasks = root["tasks"] as? JsonArray ?: throw DataFormatException("tasks missing")
        if (root["notes"] !is JsonArray) throw DataFormatException("notes missing")
        val seen = HashSet<String>()
        val out = tasks.map { el ->
            val obj = el as? JsonObject ?: throw DataFormatException("task is not an object")
            val task = decodeTask(obj, now)
            if (!seen.add(task.id)) throw DataFormatException("duplicate task id")
            task
        }
        val rest = JsonObject(root.filterKeys { it != "version" && it != "tasks" }.let { m ->
            m + mapOf(
                "attachments" to (m["attachments"] as? JsonArray ?: JsonArray(emptyList())),
                "settings" to (m["settings"] as? JsonObject ?: JsonObject(emptyMap())),
            )
        })
        return AppState(out, rest)
    }

    private fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun int(obj: JsonObject, key: String): Int? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    fun decodeTask(obj: JsonObject, now: Instant): Task {
        val id = str(obj, "id")?.takeIf { it.isNotEmpty() } ?: throw DataFormatException("task without id")
        val title = str(obj, "title")?.trim().orEmpty().take(200).ifEmpty { "…" }
        val dueAt = parseInstant(obj["dueAt"])
        var repeat = Repeat.of(str(obj, "repeat"))
        if (dueAt == null) repeat = Repeat.NONE
        val from = parseInstant(obj["repeatFrom"])
        val scheduleAt = parseInstant(obj["scheduleAt"]) ?: if (repeat != Repeat.NONE) dueAt else null
        val days = (obj["repeatDays"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            ?.filter { it in 0..6 }?.distinct()?.sorted().orEmpty()
        val rule = when (repeat) {
            Repeat.INTERVAL -> RepeatRule(everyDays = (int(obj, "repeatEvery") ?: 2).coerceIn(2, 365), from = from)
            Repeat.HOURS -> RepeatRule(everyHours = (int(obj, "repeatHours") ?: 1).coerceIn(1, 23), from = from)
            Repeat.DAYS -> RepeatRule(days = days.ifEmpty { listOf(jsDay(dueAt!!.atZone(ZoneOffset.UTC).toLocalDate())) }, from = from)
            Repeat.MONTHLY, Repeat.YEARLY -> RepeatRule(from = from ?: scheduleAt ?: dueAt)
            Repeat.DAILY, Repeat.WEEKLY -> RepeatRule(from = from)
            Repeat.NONE -> RepeatRule()
        }
        val early = int(obj, "remindBefore")?.takeIf { dueAt != null && it in EARLY_MINUTES } ?: 0
        val created = parseInstant(obj["createdAt"]) ?: now
        return Task(
            id = id, title = title, body = str(obj, "body").orEmpty().take(20_000),
            priority = Priority.of(str(obj, "priority")), category = Category.of(str(obj, "category")),
            dueAt = dueAt, scheduleAt = scheduleAt, repeat = repeat, rule = rule, remindBefore = early,
            snoozeCount = int(obj, "snoozeCount")?.takeIf { it in 1..999 } ?: 0,
            snoozedAt = parseInstant(obj["snoozedAt"]), completedAt = parseInstant(obj["completedAt"]),
            deletedAt = parseInstant(obj["deletedAt"]), sourceId = str(obj, "sourceId"),
            createdAt = created, updatedAt = parseInstant(obj["updatedAt"]) ?: created,
            extra = JsonObject(obj.filterKeys { it !in TASK_KEYS }),
        )
    }

    fun encodeTask(t: Task): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        fun put(k: String, v: Instant?) { m[k] = v?.let { JsonPrimitive(iso(it)) } ?: JsonNull }
        m["id"] = JsonPrimitive(t.id)
        m["title"] = JsonPrimitive(t.title)
        m["body"] = JsonPrimitive(t.body)
        m["priority"] = JsonPrimitive(t.priority.code)
        m["category"] = JsonPrimitive(t.category.code)
        put("dueAt", t.dueAt)
        put("scheduleAt", t.scheduleAt)
        m["repeat"] = JsonPrimitive(t.repeat.code)
        when (t.repeat) {
            Repeat.DAYS -> m["repeatDays"] = JsonArray(t.rule.days.map { JsonPrimitive(it) })
            Repeat.INTERVAL -> m["repeatEvery"] = JsonPrimitive(t.rule.everyDays)
            Repeat.HOURS -> m["repeatHours"] = JsonPrimitive(t.rule.everyHours)
            else -> Unit
        }
        if (t.repeat != Repeat.NONE) t.rule.from?.let { m["repeatFrom"] = JsonPrimitive(iso(it)) }
        if (t.remindBefore > 0 && t.dueAt != null) m["remindBefore"] = JsonPrimitive(t.remindBefore)
        if (t.snoozeCount > 0) m["snoozeCount"] = JsonPrimitive(t.snoozeCount)
        put("snoozedAt", t.snoozedAt)
        put("completedAt", t.completedAt)
        put("deletedAt", t.deletedAt)
        t.sourceId?.let { m["sourceId"] = JsonPrimitive(it) }
        if ("attachments" !in t.extra) m["attachments"] = JsonArray(emptyList())
        m["createdAt"] = JsonPrimitive(iso(t.createdAt))
        m["updatedAt"] = JsonPrimitive(iso(t.updatedAt))
        for ((k, v) in t.extra) if (k !in m) m[k] = v
        return JsonObject(m)
    }

    fun encode(state: AppState): String {
        val m = LinkedHashMap<String, JsonElement>()
        m["version"] = JsonPrimitive(1)
        m["tasks"] = JsonArray(state.tasks.map(::encodeTask))
        for ((k, v) in state.rest) m[k] = v
        return json.encodeToString(JsonObject.serializer(), JsonObject(m))
    }

    // Small typed readers for settings (data.json "settings").
    fun settingInt(s: JsonObject, key: String): Int? = (s[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    fun settingBool(s: JsonObject, key: String): Boolean? = (s[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    fun settingStr(s: JsonObject, key: String): String? = (s[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    @Suppress("unused") fun arr(e: JsonElement?) = (e as? JsonArray)?.jsonArray
    @Suppress("unused") fun obj(e: JsonElement?) = (e as? JsonObject)?.jsonObject
    @Suppress("unused") fun prim(e: JsonElement?) = (e as? JsonPrimitive)?.jsonPrimitive
}

/** Minutes before the due time for the optional, quieter early notice (Windows EARLY). */
val EARLY_MINUTES = listOf(5, 10, 15, 30, 60, 1440)
