package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Planner
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.data.IcsImport
import com.fastkiwi.rememberme.ui.search
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Performance (torture suite 6). JVM, not a phone: the numbers are for spotting regressions and gross costs; a
 * phone is several times slower. Each measurement is the median of several runs after warm-up, written to
 * torture/reports/perf-<date>.txt. Ceilings are generous (5× the FEATURES.md gates where there is one); the
 * strict gate is printed next to each number.
 */
class PerfTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T06:00:00Z")

    private fun median(runs: Int = 7, warm: Int = 3, block: () -> Unit): Double {
        repeat(warm) { block() }
        return (0 until runs).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }.sorted()[runs / 2]
    }

    private fun line(what: String, ms: Double, gate: Double?, ceiling: Double) {
        val g = gate?.let { " (gate ${it.toInt()} ms: ${if (ms <= it) "met" else "NOT met"})" } ?: ""
        Torture.report("perf", "JVM, not a phone: %s: %.1f ms%s, ceiling %d ms".format(java.util.Locale.ROOT, what, ms, g, ceiling.toInt()))
        assertTrue("$what took $ms ms (ceiling $ceiling)", ms <= ceiling)
    }

    @Test fun planTenThousand() {
        val s = Gen(Random(Torture.seed("perf-plan", 12_001))).state(10_000, now, zone, spreadSeconds = 5L * 86_400)
        val ms = median { Planner.plan(s, now, 2, emptyMap(), emptyMap()) }
        line("Planner.plan, 10,000 reminders", ms, 200.0, 1_000.0)
    }

    @Test fun searchFiveThousand() {
        val s = Gen(Random(Torture.seed("perf-search", 12_002))).state(5_000, now, zone)
        for (q in listOf("اتصل", "الإيجار", "call", "🙂", "x")) {
            val ms = median { search(s, q) }
            line("search «$q», 5,000 reminders", ms, 100.0, 500.0)
        }
    }

    @Test fun importAFiveThousandTaskBackup() {
        val s = Gen(Random(Torture.seed("perf-import", 12_003))).state(5_000, now, zone)
        val zip = ByteArrayOutputStream().also { Backup.export(s, it, now) }.toByteArray()
        line("Backup.export, 5,000 reminders (${zip.size / 1024} KB)", median(5, 2) { Backup.export(s, ByteArrayOutputStream(), now) }, null, 5_000.0)
        line("Backup.read, 5,000 reminders", median(5, 2) { Backup.read(zip.inputStream(), now) }, null, 5_000.0)
        val text = Codec.encode(s)
        line("Codec.decode of data.json, 5,000 reminders (${text.length / 1024} KB)", median(5, 2) { Codec.decode(text, now) }, null, 3_000.0)
    }

    @Test fun icsExportAndImportFiveThousand() {
        val s = Gen(Random(Torture.seed("perf-ics", 12_004))).state(5_000, now, zone)
        val text = Ics.export(s, zone, now)
        line("Ics.export, 5,000 reminders (${text.length / 1024} KB)", median(5, 2) { Ics.export(s, zone, now) }, null, 5_000.0)
        line("IcsImport.read of that file", median(3, 1) { IcsImport.read(text, zone, now) }, null, 10_000.0)
    }
}
