package com.fastkiwi.rememberme.data

import android.content.Context
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.Tasks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant

/**
 * The change history of each reminder (F-072, DESIGN-SPEC §5.19): a private file on this phone only, never in
 * data.json or a backup (`files/history.json` is outside the backup and transfer rules). Off by default;
 * while off nothing is written, not even the file. Only the event type, its instants, a repeat code and an
 * alert's minutes are kept: never a title or a note. At most [PER_TASK] events per reminder and [TOTAL] in all
 * (the oldest go first).
 */
class History(private val file: File, private val on: () -> Boolean) {

    enum class Kind(val code: String) {
        CREATED("c"), MOVED("m"), SNOOZED("s"), DONE("d"), REPEAT("r"), ALERT_ADDED("a");
        companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } }
    }

    /**
     * One event: [at] is when it happened; [from]/[to] the old and new due time (moved, snoozed); [minutes] an
     * added alert's lead time; [repeat] the new repeat code.
     */
    data class Event(
        val taskId: String,
        val kind: Kind,
        val at: Instant,
        val from: Instant? = null,
        val to: Instant? = null,
        val minutes: Int? = null,
        val repeat: String? = null,
    )

    /** Adds events (nothing while history is off). */
    @Synchronized
    fun record(events: List<Event>) {
        if (events.isEmpty() || !on()) return
        val all = read() + events
        val perTask = HashMap<String, Int>()
        // Keep the newest PER_TASK of each reminder and the newest TOTAL overall.
        val kept = all.asReversed().filter { e -> (perTask.merge(e.taskId, 1, Int::plus) ?: 0) <= PER_TASK }.take(TOTAL).asReversed()
        write(kept)
    }

    /** A reminder's events, oldest first. */
    @Synchronized
    fun events(taskId: String): List<Event> = read().filter { it.taskId == taskId }

    @Synchronized
    fun all(): List<Event> = read()

    /** «امسح السجل»: removes the file. */
    @Synchronized
    fun clear() { file.delete() }

    private fun read(): List<Event> {
        if (!file.exists()) return emptyList()
        val arr = runCatching { Codec.json.parseToJsonElement(file.readText()) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { decode(it) }
    }

    private fun write(list: List<Event>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JsonArray(list.map(::encode)).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val PER_TASK = 50
        const val TOTAL = 5000
        /** More new reminders than this in one change is a restore or an import, not "created". */
        private const val MAX_CREATED = 3

        fun file(context: Context) = File(context.applicationContext.filesDir, "history.json")

        fun of(context: Context): History {
            val app = context.applicationContext
            return History(file(app)) { Prefs(app).historyOn }
        }

        /** Records what changed between two states of the data (called after every Repo.change). */
        fun observe(context: Context, before: AppState, after: AppState, now: Instant = Instant.now()) {
            if (before === after || !Prefs(context.applicationContext).historyOn) return
            of(context).record(diff(before, after, now))
        }

        private fun encode(e: Event): JsonObject {
            val m = LinkedHashMap<String, JsonElement>()
            m["t"] = JsonPrimitive(e.taskId)
            m["k"] = JsonPrimitive(e.kind.code)
            m["at"] = JsonPrimitive(e.at.toEpochMilli())
            e.from?.let { m["a"] = JsonPrimitive(it.toEpochMilli()) }
            e.to?.let { m["b"] = JsonPrimitive(it.toEpochMilli()) }
            e.minutes?.let { m["n"] = JsonPrimitive(it) }
            e.repeat?.let { m["r"] = JsonPrimitive(it) }
            return JsonObject(m)
        }

        private fun decode(el: JsonElement): Event? {
            val o = el as? JsonObject ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun ms(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.let(Instant::ofEpochMilli)
            return Event(
                taskId = str("t") ?: return null, kind = Kind.of(str("k")) ?: return null, at = ms("at") ?: return null,
                from = ms("a"), to = ms("b"), minutes = (o["n"] as? JsonPrimitive)?.intOrNull, repeat = str("r"),
            )
        }

        /**
         * The events between two states (pure): created, done (also a repeat's completed copy), snoozed, moved
         * (a new due time that is not a snooze, a completion or an undone completion), repeat changed, alert added.
         */
        fun diff(before: AppState, after: AppState, now: Instant): List<Event> {
            val out = ArrayList<Event>()
            val old = before.tasks.associateBy { it.id }
            val new = after.tasks.associateBy { it.id }
            val created = after.tasks.filter { it.id !in old && !(it.sourceId != null && it.completedAt != null) }
            // A completed copy that appeared (Done on a repeat) or disappeared (its Undo), by source.
            val copiesAdded = after.tasks.filter { it.id !in old && it.sourceId != null && it.completedAt != null }.mapNotNull { it.sourceId }.toSet()
            val copiesRemoved = before.tasks.filter { it.id !in new && it.sourceId != null && it.completedAt != null }.mapNotNull { it.sourceId }.toSet()
            if (created.size <= MAX_CREATED) created.forEach { out += Event(it.id, Kind.CREATED, now, to = it.dueAt) }
            for (src in copiesAdded) out += Event(src, Kind.DONE, now)
            for (t in after.tasks) {
                val o = old[t.id] ?: continue
                if (t.deletedAt != null) continue
                if (o.completedAt == null && t.completedAt != null) { out += Event(t.id, Kind.DONE, now); continue }
                if (t.completedAt != null) continue
                val snoozed = t.snoozedAt != null && t.snoozedAt != o.snoozedAt && t.dueAt != null
                if (snoozed) out += Event(t.id, Kind.SNOOZED, now, from = t.snoozedAt, to = t.dueAt)
                else if (t.dueAt != o.dueAt && t.id !in copiesAdded && t.id !in copiesRemoved) out += Event(t.id, Kind.MOVED, now, from = o.dueAt, to = t.dueAt)
                if (t.repeat != o.repeat) out += Event(t.id, Kind.REPEAT, now, repeat = t.repeat.code)
                if (t.id !in copiesAdded) for (m in added(o, t)) out += Event(t.id, Kind.ALERT_ADDED, now, minutes = m)
            }
            return out
        }

        private fun added(o: Task, t: Task): List<Int> = Tasks.noticeMinutes(t) - Tasks.noticeMinutes(o).toSet()
    }
}
