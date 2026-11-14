package com.fastkiwi.rememberme.data

import android.content.Context
import androidx.core.content.edit
import com.fastkiwi.rememberme.core.SavedFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's lists and saved filters (F-063), in order, on this phone only (not in data.json until the A2
 * test shows Windows keeps a top-level `android` object). Removing a list never removes its tag.
 */
class Filters(context: Context) {
    private val p = context.getSharedPreferences("filters", Context.MODE_PRIVATE)

    fun all(): List<SavedFilter> = runCatching {
        com.fastkiwi.rememberme.core.Codec.json.parseToJsonElement(p.getString("list", "[]") ?: "[]").jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val kind = o["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (kind !in SavedFilter.KINDS) return@mapNotNull null
            SavedFilter(o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null, o["name"]?.jsonPrimitive?.content.orEmpty(), kind,
                o["value"]?.jsonPrimitive?.content.orEmpty())
        }
    }.getOrDefault(emptyList())

    fun save(list: List<SavedFilter>) = p.edit {
        putString("list", JsonArray(list.map { f ->
            JsonObject(mapOf("id" to JsonPrimitive(f.id), "name" to JsonPrimitive(f.name), "kind" to JsonPrimitive(f.kind), "value" to JsonPrimitive(f.value)))
        }).toString())
    }

    fun add(f: SavedFilter) = save(all().filter { it.id != f.id } + f)
    fun remove(id: String) = save(all().filter { it.id != id })
    fun clear() = p.edit { clear() }
}
