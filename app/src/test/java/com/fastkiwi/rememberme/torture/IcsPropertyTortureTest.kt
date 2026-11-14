package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.nextOccurrence
import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.data.IcsImport
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VToDo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import kotlin.random.Random

/**
 * Property (torture suite 3): the .ics export of random reminders, read by an independent RFC 5545 implementation
 * (ical4j, test only). Each event's expanded occurrences must equal the app's own next occurrences, the text must
 * read back as written, and the file must import back (IcsImport) to the same reminders (F-085 round trip).
 */
class IcsPropertyTortureTest {
    @Before fun on() = Torture.assumeOn()

    private fun instant(t: Any): Instant = when (t) {
        is ZonedDateTime -> t.toInstant()
        is java.time.OffsetDateTime -> t.toInstant()
        is Instant -> t
        else -> error("unexpected temporal ${t::class}")
    }

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
                failures += "file $file (${zone.id}): ical4j cannot read the export: ${e.message}"; return@repeat
            }
            val vevents = cal.getComponents<VEvent>("VEVENT").associateBy { it.getProperty<net.fortuna.ical4j.model.property.Uid>("UID").get().value }
            val vtodos = cal.getComponents<VToDo>("VTODO").associateBy { it.getProperty<net.fortuna.ical4j.model.property.Uid>("UID").get().value }
            val active = s.tasks.filter { it.active }
            if (vevents.size + vtodos.size != active.size) failures += "file $file: ${active.size} active reminders, ${vevents.size} events + ${vtodos.size} to-dos"
            for (t in active) {
                checked++
                val uid = "${t.id}@rememberme.fastkiwi.app"
                if (t.dueAt == null) {
                    val todo = vtodos[uid] ?: run { failures += "untimed ${t.id} missing"; null } ?: continue
                    if (todo.summary.get().value != t.title) failures += "to-do title «${t.title}» read back as «${todo.summary.get().value}»"
                    continue
                }
                val ev = vevents[uid] ?: run { failures += "event ${t.id} missing"; null } ?: continue
                events++
                if (ev.summary.get().value != t.title) failures += "title «${t.title}» read back as «${ev.summary.get().value}»"
                val n = when (t.repeat) { Repeat.HOURS -> 30; Repeat.YEARLY -> 6; Repeat.NONE -> 1; else -> 14 }
                val want = appSeries(t, zone, n)
                val end = ZonedDateTime.ofInstant(want.last().plusSeconds(1), zone)
                val got = try {
                    ev.calculateRecurrenceSet<Temporal>(Period<ZonedDateTime>(ZonedDateTime.ofInstant(want.first().minusSeconds(1), zone), end))
                        .map { instant(it.start) }.sorted()
                } catch (e: Exception) { failures += "ical4j cannot expand ${t.repeat} ${Ics.rrule(t, zone)}: ${e.message}"; continue }
                if (got != want) {
                    val i = want.indices.firstOrNull { it >= got.size || got[it] != want[it] } ?: want.size
                    failures += "${zone.id} ${t.repeat} ${Ics.rrule(t, zone)} days=${t.rule.days} from=${t.rule.from?.atZone(zone)?.toLocalDateTime()} " +
                        "start=${want.first().atZone(zone).toLocalDateTime()}: occurrence $i app ${want.getOrNull(i)?.atZone(zone)?.toLocalDateTime()} calendar ${got.getOrNull(i)?.atZone(zone)?.toLocalDateTime()} (${got.size} vs ${want.size})"
                }
                val alarms = ev.alarms.size
                if (alarms != 1 + (if (t.remindBefore > 0) 1 else 0)) failures += "alarms $alarms for remindBefore ${t.remindBefore}"
            }
            // Import back: the same reminders, at the same next times.
            val r = IcsImport.read(text, zone, now)
            if (r.inputs.size != active.count { it.dueAt == null || it.repeat != Repeat.NONE || it.dueAt!!.isAfter(now) } &&
                r.inputs.size + r.skipped.size != active.size) failures += "file $file: ${active.size} exported, ${r.inputs.size} imported, ${r.skipped.size} skipped"
            val (_, added) = IcsImport.save(AppState(), r.inputs, now, zone)
            val byTitle = added.groupBy { it.title }
            for (t in active) {
                val back = byTitle[t.title]?.firstOrNull { it.repeat == t.repeat } ?: run {
                    if (t.dueAt == null || t.repeat != Repeat.NONE || t.dueAt!!.isAfter(now)) failures += "«${t.title}» ${t.repeat} not imported back; warnings ${r.warnings.take(2)} skipped ${r.skipped.take(2)}"
                    null
                } ?: continue
                if (t.dueAt == null) continue
                val wantNext = if (t.repeat == Repeat.NONE) t.dueAt else {
                    val start = t.scheduleAt ?: t.dueAt!!
                    if (start.isAfter(now)) start else nextOccurrence(start, t.repeat, now, t.rule, zone)
                }
                if (back.dueAt != wantNext) failures += "${zone.id} «${t.title.take(20)}» ${t.repeat} ${Ics.rrule(t, zone)}: next ${wantNext?.atZone(zone)} imported as ${back.dueAt?.atZone(zone)}"
                if (back.remindBefore != t.remindBefore) failures += "remindBefore ${t.remindBefore} imported as ${back.remindBefore}"
            }
        }
        Torture.report("ics", "ICS property seed $seed: $checked reminders ($events timed) in ${total / perFile} files, ${failures.size} differences")
        failures.take(15).forEach { Torture.report("ics", "  $it") }
        assertTrue("$checked checked", checked >= 1_000)
        assertEquals(failures.take(8).joinToString("\n"), 0, failures.size)
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
