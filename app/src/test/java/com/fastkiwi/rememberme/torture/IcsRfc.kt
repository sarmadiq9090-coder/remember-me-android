package com.fastkiwi.rememberme.torture

import net.fortuna.ical4j.model.Recur
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * An RFC 5545 reading of the times in an .ics file, written for the torture suite as the reference: it turns the
 * file's own local times into instants by the file's own VTIMEZONE, by the letter of the RFC, so a test measures
 * the file and not a library's time-zone code. ical4j is used only for what it does right: expanding an RRULE over
 * local dates and times ([Recur] on [LocalDateTime]).
 *
 * - VTIMEZONE (§3.6.5): each observance's onsets are its DTSTART, its RDATEs and its RRULE expanded from DTSTART,
 *   all local times in its TZOFFSETFROM; the offset at an instant is the TZOFFSETTO of the latest onset at or
 *   before it (before the first onset: that onset's TZOFFSETFROM).
 * - A local DATE-TIME with a TZID (§3.3.5): the instant whose offset is the zone's offset there; a time that occurs
 *   twice is the first one; a time in a gap is read with the offset before the gap.
 * - Recurrence (§3.8.5.3): DTSTART is the first instance; RRULE instances are computed in DTSTART's local time
 *   (UTC when DTSTART is in UTC), and an instance whose local time does not exist or occurs twice is read like an
 *   explicit DATE-TIME (§3.3.5), as §3.8.5.3 says. (§3.3.10 says such an instance is ignored instead; the two
 *   paragraphs of the RFC disagree, and readers follow §3.8.5.3, as the app does: it rings later on that night.)
 *   RDATE values are added; a set has no duplicates.
 * - DTSTART must be one of the RRULE's own instances: otherwise the RFC calls the set undefined, and
 *   [Event.synchronized] is false.
 * - TEXT (§3.3.11): backslash escapes (backslash, semicolon, comma, n or N for a line break) read left to right.
 */
object IcsRfc {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** Content lines, unfolded (§3.1). */
    fun lines(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\r\n", "\n")) {
            if (raw.isEmpty()) continue
            if ((raw[0] == ' ' || raw[0] == '\t') && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1) else out += raw
        }
        return out
    }

    fun prop(line: String): Prop {
        var quoted = false
        var colon = -1
        val cuts = ArrayList<Int>()
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (quoted) continue
            if (c == ';' && colon < 0) cuts += i
            if (c == ':') { colon = i; break }
        }
        require(colon > 0) { "not a content line: $line" }
        val head = line.substring(0, colon)
        val parts = (listOf(-1) + cuts).zip(cuts + colon).map { (a, b) -> head.substring(a + 1, minOf(b, head.length)) }
        val params = parts.drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=').trim('"') }
        return Prop(parts[0].uppercase(), params, line.substring(colon + 1))
    }

    /** The properties of each top-level [name] component inside the VCALENDAR (sub-components such as VALARM left out). */
    fun components(lines: List<String>, name: String): List<List<Prop>> {
        val out = ArrayList<List<Prop>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i] != "BEGIN:$name") { i++; continue }
            val props = ArrayList<Prop>()
            var depth = 0
            i++
            while (lines[i] != "END:$name" || depth > 0) {
                when {
                    lines[i].startsWith("BEGIN:") -> depth++
                    lines[i].startsWith("END:") -> depth--
                    depth == 0 -> props += prop(lines[i])
                }
                i++
            }
            out += props
            i++
        }
        return out
    }

    /** A TEXT value unescaped (§3.3.11), left to right. */
    fun text(v: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** UID → SUMMARY (unescaped) of every VEVENT and VTODO. */
    fun summaries(text: String): Map<String, String> {
        val lines = lines(text)
        return (components(lines, "VEVENT") + components(lines, "VTODO")).associate { ps ->
            ps.first { it.name == "UID" }.value to text(ps.firstOrNull { it.name == "SUMMARY" }?.value.orEmpty())
        }
    }

    fun offset(v: String): ZoneOffset =
        ZoneOffset.of(v.substring(0, 3) + ":" + v.substring(3, 5) + (if (v.length > 5) ":" + v.substring(5) else ""))

    private fun local(v: String): LocalDateTime = LocalDateTime.parse(v.removeSuffix("Z"), LOCAL)

    class Onset(val at: Instant, val from: ZoneOffset, val to: ZoneOffset)

    /** A VTIMEZONE read by §3.6.5, with its onsets up to [until]. */
    class Zone(val id: String, val onsets: List<Onset>) {
        private val offsets = onsets.flatMap { listOf(it.from, it.to) }.distinct()

        fun offsetAt(at: Instant): ZoneOffset = onsets.lastOrNull { !it.at.isAfter(at) }?.to ?: onsets.first().from

        /** §3.3.5: a local time to its instant. */
        fun instant(l: LocalDateTime): Instant {
            offsets.map { l.toInstant(it) to it }.filter { (at, o) -> offsetAt(at) == o }.minOfOrNull { it.first }?.let { return it }
            // In a gap: read with the offset before it.
            val gap = onsets.firstOrNull { it.to.totalSeconds > it.from.totalSeconds && !l.isBefore(LocalDateTime.ofInstant(it.at, it.from)) && l.isBefore(LocalDateTime.ofInstant(it.at, it.to)) }
                ?: error("$id: no instant for $l")
            return l.toInstant(gap.from)
        }
    }

    /** Every VTIMEZONE of [lines] by TZID, with onsets expanded to [until]. */
    fun zones(lines: List<String>, until: Instant): Map<String, Zone> =
        components(lines, "VTIMEZONE").map { props -> props.first { it.name == "TZID" }.value }.associateWith { Zone(it, onsets(lines, it, until)) }

    /** The onsets of the VTIMEZONE [tzid] in [lines] (all of them when [tzid] is null), sorted, up to [until]. */
    fun onsets(lines: List<String>, tzid: String?, until: Instant): List<Onset> {
        val out = ArrayList<Onset>()
        var i = 0
        while (i < lines.size) {
            if (lines[i] != "BEGIN:VTIMEZONE") { i++; continue }
            val end = (i until lines.size).first { lines[it] == "END:VTIMEZONE" }
            val block = lines.subList(i, end + 1)
            if (tzid == null || block.any { it == "TZID:$tzid" }) {
                var j = 0
                while (j < block.size) {
                    if (block[j] != "BEGIN:STANDARD" && block[j] != "BEGIN:DAYLIGHT") { j++; continue }
                    val e = (j until block.size).first { block[it] == block[j].replace("BEGIN", "END") }
                    val ps = block.subList(j + 1, e).map(::prop)
                    fun v(k: String) = ps.firstOrNull { it.name == k }?.value
                    val start = local(v("DTSTART")!!)
                    val from = offset(v("TZOFFSETFROM")!!)
                    val to = offset(v("TZOFFSETTO")!!)
                    val locals = linkedSetOf(start)
                    ps.filter { it.name == "RDATE" }.forEach { r -> r.value.split(',').forEach { locals += local(it) } }
                    ps.filter { it.name == "RRULE" }.forEach { r ->
                        locals += Recur<LocalDateTime>(r.value).getDates(start, start, LocalDateTime.ofInstant(until, ZoneOffset.UTC).plusDays(2))
                    }
                    for (l in locals) out += Onset(l.atOffset(from).toInstant(), from, to)
                    j = e + 1
                }
            }
            i = end + 1
        }
        return out.sortedBy { it.at }
    }

    /** A VEVENT's recurrence set by the RFC. */
    class Event(val uid: String, val instances: List<Instant>, val synchronized: Boolean, val dtstart: Instant, val rrule: String?)

    /** Every VEVENT of [text], its instances up to [until] (inclusive). */
    fun events(text: String, until: Instant): List<Event> {
        val lines = lines(text)
        val zones = zones(lines, until.plusSeconds(400L * 86_400))
        return components(lines, "VEVENT").map { props ->
            fun time(p: Prop): Pair<LocalDateTime, (LocalDateTime) -> Instant> {
                val tz = p.params["TZID"]
                return when {
                    p.value.endsWith("Z") -> local(p.value) to { l: LocalDateTime -> l.toInstant(ZoneOffset.UTC) }
                    tz != null -> { val z = zones[tz] ?: error("no VTIMEZONE $tz"); local(p.value) to z::instant }
                    else -> error("floating time ${p.value}")
                }
            }
            val uid = props.first { it.name == "UID" }.value
            val (start, toInstant) = time(props.first { it.name == "DTSTART" })
            val set = sortedSetOf(toInstant(start))
            val rrule = props.filter { it.name == "RRULE" }.also { require(it.size <= 1) { "$uid: several RRULEs" } }.firstOrNull()?.value
            var synced = true
            if (rrule != null) {
                // Local end: the latest local time that can still be before [until] in any offset.
                val end = LocalDateTime.ofInstant(until, ZoneOffset.UTC).plusDays(2)
                val dates = Recur<LocalDateTime>(rrule).getDates(start, start, end)
                synced = start in dates
                for (l in dates) toInstant(l).takeIf { !it.isAfter(until) }?.let { set += it }
            }
            for (r in props.filter { it.name == "RDATE" }) {
                val (_, conv) = time(r)
                r.value.split(',').forEach { v -> conv(local(v)).takeIf { !it.isAfter(until) }?.let { set += it } }
            }
            Event(uid, set.filter { !it.isAfter(until) }, synced, toInstant(start), rrule)
        }
    }
}
