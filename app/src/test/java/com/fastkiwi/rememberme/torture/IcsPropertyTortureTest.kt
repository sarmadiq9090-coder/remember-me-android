package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.nextOccurrence
import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.data.IcsImport
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VToDo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Property (torture suite 3): the .ics export of random reminders. ical4j (test only) must read the file and its
 * text must read back as written; each event's recurrence set, read from the file by RFC 5545 itself ([IcsRfc]: the
 * file's own VTIMEZONE, its local DTSTART, RRULE and RDATE times; ical4j's Recur only expands RRULE dates), must be
 * exactly the app's own occurrences, with DTSTART one of its RRULE's instances; and the file must import back
 * (IcsImport) to the same reminders (F-085 round trip). ical4j's own time-zone reading is not the reference: it
 * reads VTIMEZONE onset times as UTC and compares times in zones with non-whole-hour offsets wrongly.
 */
class IcsPropertyTortureTest {
    @Before fun on() = Torture.assumeOn()

    /** The app's series from the exported start: what a calendar should show. */
    private fun appSeries(t: Task, zone: ZoneId, n: Int): List<Instant> {
        val start = if (t.repeat != Repeat.NONE) t.scheduleAt ?: t.dueAt!! else t.dueAt!!
        if (t.repeat == Repeat.NONE) return listOf(start)
        val out = arrayListOf(start)
        var cur = start
        repeat(n - 1) { cur = nextOccurrence(cur, t.repeat, cur, t.rule, zone); out += cur }
        return out
    }

    @Test fun exportedRemindersExpandAndImportBackTheSame() {
        val seed = Torture.seed("ics-property", 7001)
        val rnd = Random(seed)
        val gen = Gen(rnd)
        var idn = 0
        Tasks.newId = { "ics-$seed-${++idn}" }
        val total = Torture.count(1_200, 6_000)
        val perFile = 60
        val failures = ArrayList<String>()
        var checked = 0
        var events = 0
        repeat(total / perFile) { file ->
            val zone = gen.pick(gen.zones)
            val now = Instant.parse("2026-11-15T06:00:00Z").plusSeconds(rnd.nextLong(-200L * 86_400, 800L * 86_400))
            var s = AppState()
            repeat(perFile) {
                val (next, t) = Tasks.save(s, gen.input(now, spreadSeconds = 120L * 86_400), now.minusSeconds(3600), zone)
                s = next
                // Some have moved on: done a few times, snoozed or skipped.
                repeat(rnd.nextInt(0, 3)) { s.task(t.id)?.let { x -> if (x.repeat != Repeat.NONE) s = Tasks.complete(s, x.id, x.scheduleAt!!.plusSeconds(60), zone)?.first ?: s } }
                if (rnd.nextInt(5) == 0) s.task(t.id)?.dueAt?.let { d -> s = Tasks.snooze(s, t.id, d.plusSeconds(900), d) ?: s }
            }
            val text = Ics.export(s, zone, now)
            val cal = try { CalendarBuilder().build(StringReader(text)) } catch (e: Exception) {
                failures += "[unreadable] file $file (${zone.id}): ical4j cannot read the export: ${e.message}"; return@repeat
            }
            val vevents = cal.getComponents<VEvent>("VEVENT").associateBy { it.getProperty<net.fortuna.ical4j.model.property.Uid>("UID").get().value }
            val vtodos = cal.getComponents<VToDo>("VTODO").associateBy { it.getProperty<net.fortuna.ical4j.model.property.Uid>("UID").get().value }
            val active = s.tasks.filter { it.active }
            if (vevents.size + vtodos.size != active.size) failures += "[count] file $file: ${active.size} active reminders, ${vevents.size} events + ${vtodos.size} to-dos"
            // The app's series of each timed reminder, and the file's own recurrence sets read by RFC 5545 (IcsRfc).
            val wants = active.filter { it.dueAt != null }.associate { t ->
                val n = when (t.repeat) { Repeat.HOURS -> 30; Repeat.YEARLY -> 6; Repeat.NONE -> 1; else -> 14 }
                t.id to appSeries(t, zone, n)
            }
            val until = wants.values.maxOfOrNull { it.last() }?.plusSeconds(1) ?: now
            // Titles by the RFC's TEXT unescaping (ical4j reads a backslash before a line break wrongly).
            val summaries = IcsRfc.summaries(text)
            val rfc = try { IcsRfc.events(text, until).associateBy { it.uid } } catch (e: Exception) {
                failures += "[expand] file $file (${zone.id}): the RFC 5545 reading fails: $e"; return@repeat
            }
            for (t in active) {
                checked++
                val uid = "${t.id}@rememberme.fastkiwi.app"
                if (t.dueAt == null) {
                    val todo = vtodos[uid] ?: run { failures += "[missing] untimed ${t.id}"; null } ?: continue
                    if (summaries[uid] != t.title) failures += "[title] to-do title «${t.title}» read back as «${summaries[uid]}»"
                    continue
                }
                val ev = vevents[uid] ?: run { failures += "[missing] event ${t.id}"; null } ?: continue
                val set = rfc[uid] ?: run { failures += "[missing] event ${t.id} in the RFC reading"; null } ?: continue
                events++
                if (summaries[uid] != t.title) failures += "[title] title «${t.title}» read back as «${summaries[uid]}»"
                val want = wants.getValue(t.id)
                val describe = { "${zone.id} ${t.repeat} ${set.rrule} days=${t.rule.days} from=${t.rule.from?.atZone(zone)?.toLocalDateTime()} " +
                    "start=${want.first().atZone(zone).toLocalDateTime()}" }
                // RFC 5545 leaves a recurrence set undefined when DTSTART is not one of its RRULE's instances.
                if (!set.synchronized) failures += "[dtstart-off-rule] ${describe()}: DTSTART ${set.dtstart.atZone(zone).toLocalDateTime()} is not an instance of its RRULE"
                val got = set.instances.filter { !it.isAfter(want.last()) }
                if (got != want) {
                    val i = want.indices.firstOrNull { it >= got.size || got[it] != want[it] } ?: want.size
                    // An hourly series in a zone with DST: the file's RRULE counts wall-clock hours (RFC 5545 computes
                    // instances in local time), the app counts elapsed hours. Named apart, still a difference.
                    val elapsed = t.repeat == Repeat.HOURS && want.indices.all { want[it] == set.dtstart.plusSeconds(it * 3600L * t.rule.everyHours) }
                    failures += (if (elapsed) "[hourly-dst] " else "[occurrences] ") + "${describe()}: occurrence $i app ${want.getOrNull(i)?.atZone(zone)?.toLocalDateTime()} " +
                        "file ${got.getOrNull(i)?.atZone(zone)?.toLocalDateTime()} (${got.size} vs ${want.size})"
                }
                val alarms = ev.alarms.size
                if (alarms != 1 + (if (t.remindBefore > 0) 1 else 0)) failures += "[alarms] alarms $alarms for remindBefore ${t.remindBefore}"
            }
            // Import back: the same reminders, at the same next times.
            val r = IcsImport.read(text, zone, now)
            if (r.inputs.size != active.count { it.dueAt == null || it.repeat != Repeat.NONE || it.dueAt!!.isAfter(now) } &&
                r.inputs.size + r.skipped.size != active.size) failures += "[import-count] file $file: ${active.size} exported, ${r.inputs.size} imported, ${r.skipped.size} skipped"
            val (_, added) = IcsImport.save(AppState(), r.inputs, now, zone)
            val oneLine = { x: String -> x.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim() }
            val byTitle = added.groupBy { oneLine(it.title) }
            for (t in active) {
                val wantNextOf = { x: Task -> if (x.dueAt == null) null else if (x.repeat == Repeat.NONE) x.dueAt else {
                    val start = x.scheduleAt ?: x.dueAt!!
                    if (start.isAfter(now)) start else nextOccurrence(start, x.repeat, now, x.rule, zone)
                } }
                // Titles can repeat in random data: the candidate at the expected time, else the first.
                val candidates = byTitle[oneLine(t.title)]?.filter { it.repeat == t.repeat }.orEmpty()
                // A one-time reminder whose time has passed by "now" is skipped by the import, as on Windows.
                if (t.repeat == Repeat.NONE && t.dueAt != null && !t.dueAt!!.isAfter(now)) continue
                val back = (candidates.firstOrNull { it.dueAt == wantNextOf(t) && it.remindBefore == t.remindBefore }
                    ?: candidates.firstOrNull { it.dueAt == wantNextOf(t) } ?: candidates.firstOrNull()) ?: run {
                    if (t.dueAt == null || t.repeat != Repeat.NONE || t.dueAt!!.isAfter(now)) failures += "[not-imported] «${t.title}» ${t.repeat} not imported back; warnings ${r.warnings.take(2)} skipped ${r.skipped.take(2)}"
                    null
                } ?: continue
                if (t.dueAt == null) continue
                val wantNext = wantNextOf(t)
                if (back.dueAt != wantNext) failures += "[import-next] ${zone.id} «${t.title.take(20)}» ${t.repeat} ${Ics.rrule(t, zone)}: next ${wantNext?.atZone(zone)} imported as ${back.dueAt?.atZone(zone)}; warnings ${r.warnings.take(2).map { it.english() }}"
                if (back.remindBefore != t.remindBefore) failures += "[import-alarm] remindBefore ${t.remindBefore} imported as ${back.remindBefore}"
            }
        }
        val byCat = failures.groupBy { it.substringBefore(']').removePrefix("[") }
        Torture.report("ics", "ICS property seed $seed: $checked reminders ($events timed) in ${total / perFile} files, ${failures.size} differences ${byCat.mapValues { it.value.size }}")
        for ((_, list) in byCat) list.take(4).forEach { Torture.report("ics", "  $it") }
        assertTrue("$checked checked", checked >= 1_000)
        assertEquals(byCat.values.flatMap { it.take(2) }.joinToString("NL"), 0, failures.size)
    }

    /** A title with control characters (pasted text) must still give a file a calendar can read. */
    @Test fun controlCharactersInATitleStillGiveAReadableFile() {
        val zone = ZoneId.of("Asia/Baghdad")
        val now = Instant.parse("2026-11-15T06:00:00Z")
        val bad = ArrayList<String>()
        for (c in (0..31).filter { it != 10 && it != 13 } + listOf(127)) {
            val title = "a${c.toChar()}b"
            val s = Tasks.save(AppState(), com.fastkiwi.rememberme.core.TaskInput(title = title, dueAt = now.plusSeconds(3600)), now, zone).first
            val text = Ics.export(s, zone, now)
            val ok = runCatching { CalendarBuilder().build(StringReader(text)).getComponents<VEvent>("VEVENT").single().summary.get().value }
            if (ok.isFailure) bad += "U+%04X: %s".format(c, ok.exceptionOrNull()?.message?.take(60))
        }
        assertEquals("export not readable with: $bad", 0, bad.size)
    }
}
