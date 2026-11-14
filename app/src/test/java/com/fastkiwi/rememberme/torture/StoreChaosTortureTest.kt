package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

/**
 * Chaos (torture suite 5), the data file: the process dies between the write steps (copy to .bak, write .tmp,
 * rename), or the disk tears a write. Every state such a death can leave is laid out on disk and the store is
 * opened again. The newest complete copy must be what loads; a damaged file is kept, never deleted; and the store
 * works afterwards.
 */
class StoreChaosTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T06:00:00Z")
    private lateinit var dir: File

    @Before fun makeDir() { dir = Files.createTempDirectory("rm-chaos").toFile() }
    @After fun clean() { if (::dir.isInitialized) dir.deleteRecursively() }

    private fun state(vararg titles: String): AppState {
        var s = AppState()
        for (t in titles) s = Tasks.save(s, TaskInput(title = t, dueAt = now.plusSeconds(3600)), now, zone).first
        return s
    }
    private val older = state("old")
    private val current = state("old", "new")
    private val newest = state("old", "new", "newest")
    private fun f(name: String) = File(dir, name)
    private fun titles(s: AppState) = s.tasks.map { it.title }

    private fun lay(file: String?, bak: String?, tmp: String?) {
        dir.mkdirs()
        file?.let { f("data.json").writeText(it) }
        bak?.let { f("data.json.bak").writeText(it) }
        tmp?.let { f("data.json.tmp").writeText(it) }
    }

    private fun damaged() = dir.listFiles()!!.filter { it.name.startsWith("data.json.damaged-") }

    /** After the reopen: a change is written and reads back in a third process. */
    private fun stillWorks(store: Store) {
        store.change { Tasks.save(it, TaskInput(title = "after"), now, zone).first }
        assertTrue(titles(Store(dir) { now }.state.value).contains("after"))
    }

    @Test fun deathWhileWritingTheTmpKeepsTheCommittedFile() {
        val text = Codec.encode(newest)
        lay(Codec.encode(current), Codec.encode(older), text.substring(0, text.length / 2))
        val s = Store(dir) { now }
        assertEquals(titles(current), titles(s.state.value))
        stillWorks(s)
    }

    @Test fun deathBeforeTheRenameKeepsTheCommittedFile() {
        lay(Codec.encode(current), Codec.encode(current), Codec.encode(newest))
        val s = Store(dir) { now }
        assertEquals(titles(current), titles(s.state.value))
        stillWorks(s)
    }

    /**
     * Where a rename cannot replace a file (Store.write's fallback: delete data.json, then rename), a death between
     * the two leaves only .bak (one change old) and a complete, fsynced .tmp (the newest). The newest is lost.
     */
    @Test fun deathBetweenDeleteAndRenameLoadsTheNewestCompleteCopy() {
        lay(null, Codec.encode(current), Codec.encode(newest))
        val s = Store(dir) { now }
        assertEquals("the complete data.json.tmp was ignored", titles(newest), titles(s.state.value))
    }

    @Test fun aTornMainFileFallsBackToTheBackupAndIsKept() {
        val text = Codec.encode(current)
        for ((i, torn) in listOf("", text.substring(0, text.length - 3), text.replace("\"tasks\"", "\"tas\u0000ks\""), "\u0000".repeat(4096), "{\"version\":1,").withIndex()) {
            dir.deleteRecursively()
            lay(torn, Codec.encode(older), null)
            val s = Store(dir) { now.plusSeconds(i.toLong()) }
            assertEquals("case $i", titles(older), titles(s.state.value))
            assertTrue("case $i: recovered flag", s.recovered)
            assertEquals("case $i: damaged copy kept", 1, damaged().size)
            assertEquals("case $i: damaged bytes kept as they were", torn, damaged().single().readText())
            stillWorks(s)
            assertEquals("case $i: still kept after writes", 1, damaged().size)
        }
    }

    @Test fun bothCopiesDamagedStartsEmptyAndKeepsTheDamagedOne() {
        lay("garbage", "also garbage", null)
        val s = Store(dir) { now }
        assertTrue(s.state.value.tasks.isEmpty())
        assertEquals(1, damaged().size)
        s.change { Tasks.save(it, TaskInput(title = "one"), now, zone).first }
        s.change { Tasks.save(it, TaskInput(title = "two"), now, zone).first }
        assertEquals("garbage", damaged().single().readText())
    }

    @Test fun twoDamagedFilesInTheSameSecondAreBothKept() {
        lay("first damage", Codec.encode(older), null)
        Store(dir) { now }
        f("data.json").writeText("second damage")
        Store(dir) { now }
        assertEquals("a damaged copy was overwritten: ${damaged().map { it.name + "=" + it.readText() }}", 2, damaged().size)
    }

    @Test fun onlyATmpFromTheFirstEverWriteIsNotLost() {
        lay(null, null, Codec.encode(current))
        val s = Store(dir) { now }
        assertEquals("the first save, complete in data.json.tmp, was ignored", titles(current), titles(s.state.value))
    }

    @Test fun killAtEveryByteOfTheTmpNeverLosesTheCommittedData() {
        val text = Codec.encode(newest).toByteArray()
        val step = maxOf(1, text.size / 200)
        for (n in 0..text.size step step) {
            dir.deleteRecursively()
            lay(Codec.encode(current), Codec.encode(current), null)
            f("data.json.tmp").writeBytes(text.copyOf(n))
            assertEquals("cut at $n", titles(current), titles(Store(dir) { now }.state.value))
        }
    }
}
