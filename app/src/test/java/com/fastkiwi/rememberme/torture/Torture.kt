package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Category
import com.fastkiwi.rememberme.core.EARLY_MINUTES
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.withExt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Shared switches and helpers of the torture suite (torture/README.md).
 *
 * Every torture test is skipped unless `TORTURE=1` (environment, or `-Dtorture=true` on the test JVM), so the
 * ordinary `testDebugUnitTest` run is not slowed or blocked by it; `torture/run.sh` sets it. The slow parts also
 * need `TORTURE_HEAVY=1`. Every random test prints its seed; `TORTURE_SEED=<n>` replays one.
 */
object Torture {
    private fun flag(env: String, prop: String): Boolean =
        System.getenv(env)?.let { it == "1" || it.equals("true", true) } == true || System.getProperty(prop) == "true"

    val on: Boolean get() = flag("TORTURE", "torture") || heavy
    val heavy: Boolean get() = flag("TORTURE_HEAVY", "torture.heavy")

    fun assumeOn() = Assume.assumeTrue("torture suite: set TORTURE=1 (torture/run.sh)", on)
    fun assumeHeavy() = Assume.assumeTrue("heavy torture: set TORTURE_HEAVY=1 (torture/run.sh)", heavy)

    /** Light count normally, heavy count with TORTURE_HEAVY=1. */
    fun count(light: Int, heavy: Int) = if (this.heavy) heavy else light

    /** The seed of a random test, printed so the run can be replayed with TORTURE_SEED. */
    fun seed(name: String, default: Long): Long {
        val s = System.getenv("TORTURE_SEED")?.toLongOrNull() ?: System.getProperty("torture.seed")?.toLongOrNull() ?: default
        println("[torture] $name seed=$s (replay: TORTURE_SEED=$s)")
        return s
    }

    /** The worktree root (tests run with the app module as the working directory). */
    val root: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        d ?: File(System.getProperty("user.dir")).absoluteFile.parentFile
    }

    val reports: File get() = File(root, "torture/reports").also { it.mkdirs() }

    /** Appends a line to torture/reports/<name>-<date>.txt and prints it. */
    fun report(name: String, line: String) {
        println("[torture] $line")
        File(reports, "$name-${LocalDate.now()}.txt").appendText(line + "\n", Charsets.UTF_8)
    }

    /** The Windows app's checkout (read only): REMEMBER_ME_DIR, else ../Remember-Me next to the worktree. */
    fun windowsDir(): File = System.getenv("REMEMBER_ME_DIR")?.let(::File) ?: File(root.parentFile, "Remember-Me")

    /** Runs torture/tools/<script> with Node and returns its output; fails the test when Node fails. */
    fun node(script: String, vararg args: String): String {
        val proc = ProcessBuilder(listOf(System.getenv("NODE_BIN") ?: "node", File(root, "torture/tools/$script").path) + args)
            .redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) { "node $script did not finish" }
        check(proc.exitValue() == 0) { "node $script failed: $log" }
        println("[torture] $log")
        return log
    }

    /** Android's Instant.now() has millisecond precision; tests use the same. */
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
}

/** Random reminders and text, all from one seeded [Random]. */
class Gen(val rnd: Random) {
    val zones = listOf(
        "Asia/Baghdad", "Europe/Berlin", "America/New_York", "Asia/Tehran", "Australia/Sydney", "Asia/Riyadh",
        "Africa/Cairo", "Asia/Beirut", "UTC", "Australia/Lord_Howe", "America/Santiago", "Pacific/Chatham",
    ).map(ZoneId::of)

    fun <T> pick(list: List<T>): T = list[rnd.nextInt(list.size)]
    fun chance(p: Double) = rnd.nextDouble() < p

    private val pieces = listOf(
        "اتصل بأمي", "دفع الإيجار", "موعد الطبيب", "اجتماع", "call mom", "pay rent", "dentist", "🙂", "🎉", "👨‍👩‍👧",
        "‏", "‎", "‫", "؛", ";", ",", "\\", "\"", "'", "\n", "  ", "ـــ", "ً", "١٢٣", "123", "#tag", "<b>", "&amp;", "İ", "ß",
        "x".repeat(70), "طويل ".repeat(15), "‍", "‌", " ",
    )

    fun text(maxPieces: Int = 6): String = (1..rnd.nextInt(1, maxPieces + 1)).joinToString(if (chance(0.5)) " " else "") { pick(pieces) }

    fun title(): String = text().trim().take(200).ifEmpty { "t" }

    val repeats = listOf(Repeat.NONE, Repeat.DAILY, Repeat.WEEKLY, Repeat.DAYS, Repeat.INTERVAL, Repeat.MONTHLY, Repeat.YEARLY, Repeat.HOURS)

    /** An editor input: maybe timed, maybe repeating, with notices. [around] is the centre of the due times. */
    fun input(around: Instant, spreadSeconds: Long = 30L * 86_400, id: String? = null, repeat: Repeat? = null, timed: Boolean? = null): TaskInput {
        val hasTime = timed ?: chance(0.85)
        val due = if (hasTime) around.plusSeconds(rnd.nextLong(-spreadSeconds, spreadSeconds)).truncatedTo(ChronoUnit.MINUTES) else null
        val rep = if (due == null) Repeat.NONE else repeat ?: pick(repeats)
        return TaskInput(
            id = id, title = title(), body = if (chance(0.3)) text(10) else "",
            priority = pick(Priority.entries), category = pick(Category.entries), dueAt = due, repeat = rep,
            days = (0..6).filter { chance(0.35) }, everyDays = rnd.nextInt(2, 40), everyHours = rnd.nextInt(1, 24),
            remindBefore = if (chance(0.4)) pick(EARLY_MINUTES) else 0,
        )
    }

    /** Unknown fields that must survive every operation (Windows keys and the Android extension). */
    fun decorate(t: Task): Task {
        var x = t.copy(extra = JsonObject(t.extra + mapOf(
            "x-unknown" to JsonPrimitive("keep-${rnd.nextInt(1000)}"),
            "tags" to JsonArray(listOf(JsonPrimitive("منزل"), JsonPrimitive("work"))),
        )))
        if (chance(0.5)) x = x.withExt(AlertExt(style = pick(listOf("normal", "alarm")), alerts = listOf(pick(EARLY_MINUTES)),
            unknown = mapOf("future" to JsonPrimitive(true))))
        return x
    }

    /** [n] reminders through Tasks.save (real ids, rules and anchors), some snoozed, done or trashed. */
    fun state(n: Int, now: Instant, zone: ZoneId, spreadSeconds: Long = 3L * 86_400): AppState {
        val tasks = ArrayList<Task>(n)
        var s = AppState()
        for (i in 0 until n) {
            val (_, t) = Tasks.save(s, input(now, spreadSeconds), now.minusSeconds(rnd.nextLong(60, 30L * 86_400)), zone)
            var x = decorate(t)
            when (rnd.nextInt(20)) {
                0 -> x = x.copy(completedAt = now.minusSeconds(60))
                1 -> x = x.copy(deletedAt = now.minusSeconds(rnd.nextLong(60, 40L * 86_400)))
                2, 3 -> if (x.dueAt != null) x = x.copy(dueAt = x.dueAt!!.plusSeconds(600), snoozedAt = x.updatedAt, snoozeCount = 1)
            }
            tasks += x
        }
        return s.copy(tasks = tasks).also { s = it }
    }
}
