package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.RepeatRule
import com.fastkiwi.rememberme.core.jsDay
import com.fastkiwi.rememberme.core.nextOccurrence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Property (torture suite 3): the repeat engine against the Windows engine on random cases. The cases are made
 * here from a printed seed; torture/tools/repeat-parity.cjs answers them with the Windows app's own
 * src/core/repeat.js (required read-only from the Remember-Me checkout next to this worktree, or REMEMBER_ME_DIR).
 * A difference where Node's and Java's time-zone data disagree on the offset is listed apart (tzdata, not rules).
 */
class RepeatParityTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val zones = listOf(
        "Asia/Baghdad", "Asia/Riyadh", "Africa/Cairo", "Europe/Berlin", "America/New_York", "Asia/Beirut", "Australia/Sydney", "UTC",
        "Asia/Tehran", "Australia/Lord_Howe", "America/Santiago", "America/Havana", "Asia/Gaza", "Pacific/Chatham", "Europe/London",
        "America/Sao_Paulo", "Asia/Damascus", "Asia/Amman", "Pacific/Apia", "America/St_Johns",
    )
    private val modes = listOf(Repeat.DAILY, Repeat.WEEKLY, Repeat.DAYS, Repeat.INTERVAL, Repeat.MONTHLY, Repeat.YEARLY)

    private fun windowsDir(): File = System.getenv("REMEMBER_ME_DIR")?.let(::File) ?: File(Torture.root.parentFile, "Remember-Me")

    @Test fun sameAnswersAsTheWindowsEngineOnRandomCases() {
        val seed = Torture.seed("repeat-parity", 5001)
        val rnd = Random(seed)
        val n = Torture.count(6_000, 30_000)
        data class Case(val zone: ZoneId, val repeat: Repeat, val current: Instant, val now: Instant, val rule: RepeatRule)
        val cases = ArrayList<Case>(n)
        while (cases.size < n) {
            val zone = ZoneId.of(zones[rnd.nextInt(zones.size)])
            val repeat = modes[rnd.nextInt(modes.size)]
            val local = LocalDateTime.of(rnd.nextInt(2012, 2036), rnd.nextInt(1, 13), 1, listOf(0, 0, 1, 2, 2, 3, 9, 12, 23)[rnd.nextInt(9)], listOf(0, 15, 30, 45, 59)[rnd.nextInt(5)])
                .plusDays(listOf(0, 1, 4, 14, 27, 28, 29, 30, rnd.nextInt(0, 31))[rnd.nextInt(9)].toLong())
            val start = ZonedDateTime.of(local, zone).toInstant()
            val rule = when (repeat) {
                Repeat.DAYS -> RepeatRule(days = (0..6).filter { rnd.nextInt(3) == 0 }.ifEmpty { listOf(jsDay(local.toLocalDate())) }, from = start)
                Repeat.INTERVAL -> RepeatRule(everyDays = listOf(2, 3, 7, 10, 14, 30, 31, 90, 365)[rnd.nextInt(9)], from = start)
                else -> RepeatRule(from = start)
            }
            // The current occurrence: usually one the app itself reached, sometimes any moment (a snoozed or edited one).
            var current = start
            if (rnd.nextInt(10) < 7) repeat(rnd.nextInt(0, 6)) { current = nextOccurrence(current, repeat, current, rule, zone) }
            else current = start.plusSeconds(rnd.nextLong(0, 90L * 86_400))
            val now = current.plusSeconds(listOf(-3_600L, 0L, 1L, 60L, 86_400L, 9L * 86_400, 40L * 86_400, 400L * 86_400, 3_000L * 86_400)[rnd.nextInt(9)] + rnd.nextLong(0, 3_600))
            cases += Case(zone, repeat, current, now, rule)
        }
        val dir = File(Torture.root, "app/build/torture").apply { mkdirs() }
        val input = File(dir, "parity-cases-$seed.json")
        val output = File(dir, "parity-answers-$seed.json")
        input.writeText(JsonArray(cases.map { c ->
            JsonObject(buildMap {
                put("zone", JsonPrimitive(c.zone.id)); put("repeat", JsonPrimitive(c.repeat.code))
                put("current", JsonPrimitive(Codec.iso(c.current))); put("now", JsonPrimitive(Codec.iso(c.now)))
                put("repeatFrom", JsonPrimitive(Codec.iso(c.rule.from!!)))
                if (c.repeat == Repeat.DAYS) put("repeatDays", JsonArray(c.rule.days.map { JsonPrimitive(it) }))
                if (c.repeat == Repeat.INTERVAL) put("repeatEvery", JsonPrimitive(c.rule.everyDays))
            })
        }).toString())
        val win = windowsDir()
        assertTrue("The Windows app is not at $win (set REMEMBER_ME_DIR)", File(win, "src/core/repeat.js").isFile)
        val node = System.getenv("NODE_BIN") ?: "node"
        val proc = ProcessBuilder(node, File(Torture.root, "torture/tools/repeat-parity.cjs").path, win.path, input.path, output.path)
            .redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        assertTrue("node did not finish", proc.waitFor(5, TimeUnit.MINUTES))
        assertEquals("node failed: $log", 0, proc.exitValue())
        println("[torture] $log")
        val answers = Codec.json.parseToJsonElement(output.readText()).jsonArray
        assertEquals(cases.size, answers.size)
        val rules = ArrayList<String>()
        val tzdata = ArrayList<String>()
        val byMode = HashMap<String, Int>()
        for ((i, c) in cases.withIndex()) {
            val a = answers[i].jsonObject
            val got = nextOccurrence(c.current, c.repeat, c.now, c.rule, c.zone)
            val line = { want: String -> "${c.zone.id} ${c.repeat.code} from=${c.rule.from!!.atZone(c.zone).toLocalDateTime()} days=${c.rule.days} every=${c.rule.everyDays} " +
                "current=${c.current.atZone(c.zone).toLocalDateTime()} now=${c.now.atZone(c.zone).toLocalDateTime()}: windows $want, android ${got.atZone(c.zone).toLocalDateTime()}${got.atZone(c.zone).offset}" }
            a["error"]?.let { rules += line("threw ${it.jsonPrimitive.content}"); return@let }
            if (a["error"] != null) continue
            val want = Instant.parse(a["next"]!!.jsonPrimitive.content)
            if (want == got) continue
            val sameData = a["offset"]!!.jsonPrimitive.int * 60 == c.zone.rules.getOffset(c.current).totalSeconds &&
                a["nextOffset"]!!.jsonPrimitive.int * 60 == c.zone.rules.getOffset(want).totalSeconds
            val text = line(want.atZone(c.zone).toLocalDateTime().toString() + want.atZone(c.zone).offset)
            // A known Windows bug (monthsLater builds the 1st, then setDate: when that 1st's midnight or 2:00
            // falls in a DST gap, Windows ends an hour late). Android keeps the series' wall-clock time, which
            // is right; recorded for the Windows backlog. Only this exact shape is accepted.
            val windowsGapBug = (c.repeat == com.fastkiwi.rememberme.core.Repeat.MONTHLY || c.repeat == com.fastkiwi.rememberme.core.Repeat.YEARLY) &&
                got.atZone(c.zone).toLocalTime() == c.rule.from!!.atZone(c.zone).toLocalTime() &&
                java.time.Duration.between(got, want).toMinutes() == 60L &&
                got.atZone(c.zone).toLocalDate().withDayOfMonth(1).let { first ->
                    val t = c.zone.rules.getValidOffsets(first.atTime(c.rule.from!!.atZone(c.zone).toLocalTime())).isEmpty()
                    t || c.zone.rules.getValidOffsets(first.atStartOfDay()).isEmpty()
                }
            if (windowsGapBug) { Torture.report("parity", "  WINDOWS-BUG (accepted) $text"); continue }
            if (sameData) { rules += text; byMode.merge(c.repeat.code + "@" + c.zone.id, 1, Int::plus) } else tzdata += text
        }
        Torture.report("parity", "repeat parity seed $seed: ${cases.size} cases, ${rules.size} rule differences ${byMode}, ${tzdata.size} tzdata differences")
        rules.take(20).forEach { Torture.report("parity", "  RULE  $it") }
        tzdata.take(5).forEach { Torture.report("parity", "  TZDATA $it") }
        assertEquals("Android and Windows disagree on ${rules.size} of ${cases.size} cases:\n" + rules.take(6).joinToString("\n"), 0, rules.size)
    }
}
