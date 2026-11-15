package com.fastkiwi.rememberme.core.quickadd

import com.fastkiwi.rememberme.core.Codec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Parity with the Windows app: every case in quickadd-golden.json is the Windows parseWhen() answer
 * (tools/quickadd-golden.cjs). Android must give the same title, due time, repeat, question and
 * options. English cases list the fields the Windows tests check ("checked"); see the tool's header.
 */
class QuickAddParityTest {
    private val root = Codec.json.parseToJsonElement(javaClass.getResource("/quickadd-golden.json")!!.readText()).jsonObject
    private val cases = root["cases"]!!.jsonArray.map { it.jsonObject }

    private fun mismatches(lang: String): List<String> {
        val out = ArrayList<String>()
        for (o in cases.filter { it["lang"]!!.jsonPrimitive.content == lang }) {
            val text = o["text"]!!.jsonPrimitive.content
            val zone = ZoneId.of(o["zone"]!!.jsonPrimitive.content)
            val now = Instant.parse(o["now"]!!.jsonPrimitive.content)
            val got = QuickAdd.parseStrict(text, now, zone, o["weekStart"]!!.jsonPrimitive.int)
            val expect = o["expect"]
            val where = "[$lang ${o["context"]!!.jsonPrimitive.content} ${zone.id}] «$text»"
            if (expect == null || expect is JsonNull) {
                if (got.understood || got.dueAt != null || got.ask != null || got.title != text) out += "$where: Windows finds no date, Android $got"
                continue
            }
            val want = expect.jsonObject
            val checked = (o["checked"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
            fun check(field: String) = checked == null || field in checked
            val diffs = ArrayList<String>()
            if (!got.understood) diffs += "not understood"
            // Accepted Android improvement (black-box U2, 2026-11-15): the name «علي» before a day is kept in the
            // title; Windows cuts it as the connector «على». Noted for the Windows backlog.
            val keptAli = got.title.split(' ').count { it == "علي" } == (want.str("title") ?: "").split(' ').count { it == "علي" } + 1 &&
                got.title.replace(" علي", "") == want.str("title")
            if (check("title") && got.title != want.str("title") && !keptAli) diffs += "title «${got.title}» ≠ «${want.str("title")}»"
            if (check("dueAt") && got.dueAt?.let(Codec::iso) != want.str("dueAt")) diffs += "dueAt ${got.dueAt} ≠ ${want.str("dueAt")}"
            if (check("ask") && got.ask?.name?.lowercase() != want.str("ask")) diffs += "ask ${got.ask} ≠ ${want.str("ask")}"
            if (check("options")) {
                val options = got.options.map { Codec.iso(it.dueAt) }
                val wanted = want["options"]!!.jsonArray.map { it.jsonPrimitive.content }
                if (options != wanted) diffs += "options $options ≠ $wanted"
            }
            if (check("repeat") && got.repeat.code != want.str("repeat")) diffs += "repeat ${got.repeat} ≠ ${want.str("repeat")}"
            if (check("repeatDays")) {
                val days = (want["repeatDays"] as? JsonArray)?.map { it.jsonPrimitive.int }.orEmpty()
                if (got.days != days) diffs += "days ${got.days} ≠ $days"
            }
            if (check("repeatEvery")) {
                val every = (want["repeatEvery"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int
                if (every != null && got.everyDays != every) diffs += "every ${got.everyDays} ≠ $every"
            }
            if (diffs.isNotEmpty()) out += "$where: ${diffs.joinToString("; ")}"
        }
        return out
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test fun goldenFileIsBigEnough() {
        assertTrue(cases.size >= 600)
        assertTrue(cases.count { it["lang"]!!.jsonPrimitive.content == "ar" } >= 500)
        // Most cases are dated readings, not "no date", so the comparison has something to compare.
        val dated = cases.count { it["expect"] is JsonObject && it["lang"]!!.jsonPrimitive.content == "ar" }
        assertTrue("$dated dated Arabic cases", dated >= 1500)
    }

    @Test fun arabicSameAsWindows() {
        val bad = mismatches("ar")
        assertEquals(bad.take(15).joinToString("\n"), 0, bad.size)
    }

    @Test fun englishSameAsWindows() {
        val bad = mismatches("en")
        assertEquals(bad.take(15).joinToString("\n"), 0, bad.size)
    }
}
