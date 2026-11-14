package com.fastkiwi.rememberme.core

import kotlinx.serialization.json.JsonArray
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
 * Parity with the Windows app: every case in windows-golden.json was computed by the Windows app's own
 * nextOccurrence() (tools/windows-golden.cjs). Android must give the same instant for each.
 */
class RepeatParityTest {
    @Test fun sameAnswersAsWindows() {
        val root = Codec.json.parseToJsonElement(javaClass.getResource("/windows-golden.json")!!.readText()).jsonObject
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.size >= 500)
        val mismatches = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val zone = ZoneId.of(o["zone"]!!.jsonPrimitive.content)
            val repeat = Repeat.of(o["repeat"]!!.jsonPrimitive.content)
            val rule = RepeatRule(
                days = (o["repeatDays"] as? JsonArray)?.map { it.jsonPrimitive.int }.orEmpty(),
                everyDays = o["repeatEvery"]?.jsonPrimitive?.int ?: 2,
                from = Instant.parse(o["repeatFrom"]!!.jsonPrimitive.content),
            )
            val got = nextOccurrence(Instant.parse(o["current"]!!.jsonPrimitive.content), repeat, Instant.parse(o["now"]!!.jsonPrimitive.content), rule, zone)
            val want = Instant.parse(o["next"]!!.jsonPrimitive.content)
            if (got != want) mismatches += "$o → android $got"
        }
        assertEquals(mismatches.take(5).joinToString("\n"), 0, mismatches.size)
    }
}
