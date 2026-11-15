package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.data.IcsImport
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VTimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId

/**
 * Property (torture suite 3): the VTIMEZONE the .ics export writes for a zone must give, read by the letter of
 * RFC 5545 §3.6.5 (each observance's onsets = its DTSTART, local time in TZOFFSETFROM, plus its RRULE expanded by
 * ical4j's Recur; the offset at an instant is the TZOFFSETTO of the latest onset before it), the same UTC offset as
 * Java's zone rules, every week and an hour either side of every transition, for ten years, for every zone with
 * DST (heavy: every zone). A wrong observance shifts every reminder in that part of the year by the DST difference
 * in any calendar app that uses the file's VTIMEZONE.
 */
class IcsTimezoneTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val now = Instant.parse("2026-11-15T06:00:00Z")
    private val local = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun samples(zone: ZoneId): List<Instant> {
        val end = now.plusSeconds(10L * 366 * 86_400)
        val out = ArrayList<Instant>()
        var t = now
        while (t.isBefore(end)) { out += t; t = t.plusSeconds(7L * 86_400 + 3_600) }
        generateSequence(zone.rules.nextTransition(now)) { zone.rules.nextTransition(it.instant) }.takeWhile { it.instant.isBefore(end) }
            .forEach { tr -> out += tr.instant.minusSeconds(3_600); out += tr.instant.plusSeconds(3_600) }
        return out
    }

    private fun offset(v: String): java.time.ZoneOffset = java.time.ZoneOffset.of(v.substring(0, 3) + ":" + v.substring(3, 5) + (if (v.length > 5) ":" + v.substring(5) else ""))

    /** Onsets (instant → offset after) of every observance in [lines], by RFC 5545. */
    private fun onsets(lines: List<String>, until: Instant): List<Pair<Instant, java.time.ZoneOffset>> {
        val out = ArrayList<Pair<Instant, java.time.ZoneOffset>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i] == "BEGIN:STANDARD" || lines[i] == "BEGIN:DAYLIGHT") {
                var e = i
                while (lines[e] != lines[i].replace("BEGIN", "END")) e++
                val block = lines.subList(i, e + 1)
                fun v(k: String) = block.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')
                val start = java.time.LocalDateTime.parse(v("DTSTART")!!, local)
                val from = offset(v("TZOFFSETFROM")!!)
                val to = offset(v("TZOFFSETTO")!!)
                val starts = v("RRULE")?.let { r ->
                    net.fortuna.ical4j.model.Recur<java.time.LocalDateTime>(r).getDates(start, start, java.time.LocalDateTime.ofInstant(until, java.time.ZoneOffset.UTC).plusDays(2))
                } ?: listOf(start)
                for (st in starts) out += st.atOffset(from).toInstant() to to
                i += block.size
            } else i++
        }
        return out.sortedBy { it.first }
    }

    @Test fun everyZonesVTimezoneGivesJavasOffsets() {
        val zones = ZoneId.getAvailableZoneIds().filter { !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it.contains('/') }.sorted().map(ZoneId::of)
            .filter { Torture.heavy || it.rules.transitionRules.isNotEmpty() || it.id in setOf("Asia/Baghdad", "Asia/Riyadh", "Asia/Tehran", "Asia/Dubai", "Asia/Kolkata") }
        val wrong = LinkedHashMap<String, String>()
        var checked = 0
        for (zone in zones) {
            val lines = Ics.vtimezone(zone, now)
            // ical4j must at least read it.
            val text = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"
            try { CalendarBuilder().build(StringReader(text)).getComponents<VTimeZone>("VTIMEZONE").single() } catch (e: Exception) {
                wrong[zone.id] = "ical4j cannot read it: ${e.message}"; continue
            }
            val samples = samples(zone)
            val on = onsets(lines, samples.max())
            for (at in samples) {
                checked++
                val want = zone.rules.getOffset(at)
                val got = on.lastOrNull { !it.first.isAfter(at) }?.second
                if (want != got) {
                    wrong.putIfAbsent(zone.id, "at ${at.atZone(zone)}: Java $want, the file's VTIMEZONE $got; Java rules ${zone.rules.transitionRules.joinToString("; ")}")
                }
            }
        }
        Torture.report("ics", "VTIMEZONE check (RFC 5545 reading): ${zones.size} zones, $checked instants, ${wrong.size} zones wrong: ${wrong.keys}")
        wrong.entries.take(10).forEach { Torture.report("ics", "  ${it.key}: ${it.value}") }
        assertEquals(wrong.entries.take(6).joinToString("\n") { "${it.key}: ${it.value}" }, 0, wrong.size)
    }

    /** The app's own importer reads every exported VTIMEZONE back to Java's offsets (round trip). */
    @Test fun theImporterReadsEveryExportedZoneBack() {
        val zones = ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted().map(ZoneId::of)
            .filter { it.rules.transitionRules.isNotEmpty() }
        val wrong = ArrayList<String>()
        for (zone in zones) {
            // A reminder at 12:00 local on the 15th of each month for three years, in an exported file of that zone.
            val ics = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\n")
            Ics.vtimezone(zone, now).forEach { ics.append(it).append("\r\n") }
            val wants = (1..36).map { m -> now.atZone(zone).toLocalDate().withDayOfMonth(15).plusMonths(m.toLong()).atTime(12, 0) }
            for ((i, w) in wants.withIndex()) ics.append("BEGIN:VEVENT\r\nUID:$i\r\nSUMMARY:m$i\r\nDTSTART;TZID=${zone.id}:${w.toString().replace("-", "").replace(":", "")}00\r\nEND:VEVENT\r\n")
            ics.append("END:VCALENDAR\r\n")
            val r = IcsImport.read(ics.toString(), ZoneId.of("UTC"), now)
            for ((i, w) in wants.withIndex()) {
                val got = r.inputs.firstOrNull { it.title == "m$i" }?.dueAt
                val want = w.atZone(zone).toInstant()
                if (got != want) { wrong += "${zone.id} ${w}: want $want got $got"; break }
            }
        }
        assertEquals(wrong.take(6).joinToString("\n"), 0, wrong.size)
    }
}
