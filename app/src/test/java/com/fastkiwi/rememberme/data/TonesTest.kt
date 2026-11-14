package com.fastkiwi.rememberme.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.withExt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Tones from files (F-014, F-017; manager's conditions 2026-11-15): 5 MB, real audio, removed when unused, optional in backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TonesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val realCheck = Tones.hasAudioTrack

    @Before fun setUp() {
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        Tones.dir(app).deleteRecursively()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        Tones.hasAudioTrack = { true } // Robolectric has no media extractor; the sniff below still applies.
    }

    @After fun tearDown() { Tones.hasAudioTrack = realCheck }

    private fun mp3(size: Int = 2048) = ByteArray(size).also { "ID3".forEachIndexed { i, ch -> it[i] = ch.code.toByte() }; it[10] = 7 }

    @Test fun anAudioFileIsCopiedOnceByItsContent() {
        val a = Tones.importFile(app, { mp3().inputStream() }, "رنة.mp3")!!
        val b = Tones.importFile(app, { mp3().inputStream() }, "copy.mp3")!!
        assertEquals("file", a.kind)
        assertEquals(a.ref, b.ref)
        assertEquals(1, Tones.dir(app).listFiles()!!.size)
        assertNotNull(Tones.resolve(app, a))
    }

    @Test fun notAudioTooBigEmptyAndTooManyAreRefusedWithAReason() {
        assertNull(Tones.importFile(app, { "<html>not a song</html>".toByteArray().inputStream() }, "x.mp3"))
        assertEquals(Tones.Refusal.NOT_AUDIO, Tones.lastRefusal)
        assertNull(Tones.importFile(app, { mp3((5 * 1024 * 1024) + 1).inputStream() }, "big.mp3"))
        assertEquals(Tones.Refusal.TOO_BIG, Tones.lastRefusal)
        assertNull(Tones.importFile(app, { ByteArray(0).inputStream() }, "empty.mp3"))
        assertEquals(Tones.Refusal.EMPTY, Tones.lastRefusal)
        // The extractor finds no audio track: the copy is removed again.
        Tones.hasAudioTrack = { false }
        assertNull(Tones.importFile(app, { mp3().inputStream() }, "fake.mp3"))
        assertEquals(Tones.Refusal.NOT_AUDIO, Tones.lastRefusal)
        assertTrue(Tones.dir(app).listFiles().orEmpty().isEmpty())
        Tones.hasAudioTrack = { true }
        for (i in 1..10) assertNotNull(Tones.importFile(app, { mp3(2048 + i).inputStream() }, "t$i.mp3"))
        assertNull("files: ${Tones.dir(app).listFiles()?.map { it.name.take(8) + " " + it.length() }}", Tones.importFile(app, { mp3(4096).inputStream() }, "eleven.mp3"))
        assertEquals(Tones.Refusal.TOO_MANY, Tones.lastRefusal)
        // Every container we accept is recognised; random text is not.
        for (sig in listOf("OggS", "fLaC", "#!AMR", "MThd")) assertTrue(sig, Tones.looksLikeAudio((sig + "xxxxxxxxxxxx").toByteArray()))
        assertTrue(Tones.looksLikeAudio("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertTrue(Tones.looksLikeAudio(byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".toByteArray()))
        assertFalse(Tones.looksLikeAudio("hello world".toByteArray()))
    }

    @Test fun aCopyNoReminderUsesIsRemovedAndAUsedOneStays() {
        val used = Tones.importFile(app, { mp3(3000).inputStream() }, "used.mp3")!!
        val unused = Tones.importFile(app, { mp3(3001).inputStream() }, "unused.mp3")!!
        val def = Tones.importFile(app, { mp3(3002).inputStream() }, "default.mp3")!!
        Repo.prefs(app).importantTone = def
        Repo.store(app).change { s0 ->
            val s = Tasks.save(s0, TaskInput(title = "x", dueAt = now.plusSeconds(3600)), now, zone).first
            s.copy(tasks = s.tasks.map { it.withExt(AlertExt(tone = used)) })
        }
        // Just picked: kept for a day (the editor may not be saved yet).
        Tones.cleanupUnused(app)
        assertEquals(3, Tones.dir(app).listFiles()!!.size)
        Tones.cleanupUnused(app, System.currentTimeMillis() + Tones.GRACE_MS + 60_000)
        val left = Tones.dir(app).listFiles()!!.map { it.name.substringBefore('.') }.toSet()
        assertEquals(setOf(used.ref, def.ref), left)
        assertFalse(unused.ref in left)
    }

    @Test fun aBackupCanCarryTheTonesAndARestoreGetsThemBack() {
        val t = Tones.importFile(app, { mp3(4000).inputStream() }, "mine.mp3")!!
        val state = Repo.store(app).change { s0 ->
            val s = Tasks.save(s0, TaskInput(title = "x", dueAt = now.plusSeconds(3600)), now, zone).first
            s.copy(tasks = s.tasks.map { it.withExt(AlertExt(tone = t)) })
        }
        val with = ByteArrayOutputStream().also { Backup.export(state, it, now, Tones.forBackup(app, state)) }.toByteArray()
        val without = ByteArrayOutputStream().also { Backup.export(state, it, now) }.toByteArray()
        val r = Backup.read(with.inputStream(), now)
        assertTrue(r.verified)
        assertEquals("zip: ${java.util.zip.ZipInputStream(with.inputStream()).let { z -> generateSequence { z.nextEntry?.name }.toList() }}", setOf(t.ref), r.tones.keys.map { it.substringBefore('.') }.toSet())
        assertTrue(Backup.read(without.inputStream(), now).tones.isEmpty())
        // A tampered tone is dropped (its content no longer matches its name).
        Tones.dir(app).deleteRecursively()
        Tones.restore(app, r.tones)
        assertNotNull(Tones.resolve(app, t))
        val chain = Tones.chain(app, t, null, AlertPolicy.Style.NORMAL)
        assertFalse(chain.fellBack)
    }

    @Test fun aMissingCopyFallsBackAndSaysSo() {
        val gone = ToneRef("file", "0".repeat(64), "deleted.mp3")
        val chain = Tones.chain(app, gone, null, AlertPolicy.Style.NORMAL)
        assertTrue(chain.fellBack)
        assertTrue("never silent by accident", chain.sources.isNotEmpty())
    }
}
