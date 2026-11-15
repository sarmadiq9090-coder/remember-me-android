package com.fastkiwi.rememberme.torture

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.BackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Fuzz (torture suite 4): backup import and the encrypted container. Whatever the bytes, [Backup.read] either
 * returns a backup or throws [Backup.ImportException]; a backup it returns can be written by the store
 * (Codec.encode) and reads back the same. [BackupCrypto.decrypt] returns the plain bytes only for the right
 * password and an untouched file; everything else is WrongPassword or Damaged.
 */
class BackupFuzzTortureTest {
    @Before fun on() = Torture.assumeOn()

    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T06:00:00Z")

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun sample(n: Int, seed: Long): AppState = Gen(Random(seed)).state(n, now, zone)

    private fun exported(s: AppState): ByteArray = ByteArrayOutputStream().also { Backup.export(s, it, now) }.toByteArray()

    /** Reads [bytes]; the outcome is "ok", "refused:<reason>", or a description of what went wrong. */
    private fun outcome(bytes: ByteArray): String = try {
        val r = Backup.read(bytes.inputStream(), now)
        // The store will write it: that must work and give the same reminders back.
        val text = Codec.encode(r.state)
        val back = Codec.decode(text, now)
        if (back.tasks.map { it.id } != r.state.tasks.map { it.id }) "BAD: re-read changed the reminders" else "ok"
    } catch (e: Backup.ImportException) {
        "refused:${e.reason}"
    } catch (e: Throwable) {
        "BAD: ${e::class.java.name}: ${e.message?.take(120)}"
    }

    @Test fun randomTruncatedAndMutatedBackupsAreReadOrRefusedCleanly() {
        val seed = Torture.seed("backup-fuzz", 8001)
        val rnd = Random(seed)
        val good = exported(sample(40, seed))
        val bare = Codec.encode(sample(10, seed + 1)).toByteArray()
        assertEquals("ok", outcome(good))
        val bad = ArrayList<String>()
        val tally = HashMap<String, Int>()
        repeat(Torture.count(3_000, 20_000)) { i ->
            val base = if (rnd.nextBoolean()) good else bare
            val bytes = when (rnd.nextInt(7)) {
                0 -> ByteArray(rnd.nextInt(0, 5000)).also(rnd::nextBytes)
                1 -> base.copyOf(rnd.nextInt(0, base.size))
                2 -> base.copyOf().also { b -> repeat(rnd.nextInt(1, 8)) { val k = rnd.nextInt(b.size); b[k] = (b[k].toInt() xor (1 shl rnd.nextInt(8))).toByte() } }
                3 -> base.copyOf().also { b -> val k = rnd.nextInt(b.size); val len = rnd.nextInt(1, 64); for (j in k until minOf(b.size, k + len)) b[j] = rnd.nextInt(256).toByte() }
                4 -> base + ByteArray(rnd.nextInt(1, 300)).also(rnd::nextBytes)
                5 -> "PK".toByteArray() + ByteArray(rnd.nextInt(10, 2000)).also(rnd::nextBytes)
                else -> { val k = rnd.nextInt(base.size); base.copyOfRange(0, k) + base.copyOfRange(minOf(base.size, k + rnd.nextInt(1, 50)), base.size) }
            }
            val o = outcome(bytes)
            tally.merge(o.substringBefore(':'), 1, Int::plus)
            if (o.startsWith("BAD")) bad += "case $i: $o"
        }
        println("[torture] backup fuzz outcomes: $tally")
        assertEquals(bad.take(8).joinToString("\n"), 0, bad.size)
    }

    @Test fun hostileShapesAreReadOrRefusedCleanly() {
        val cases = LinkedHashMap<String, ByteArray>()
        fun json(s: String) = s.toByteArray()
        cases["empty"] = ByteArray(0)
        cases["not json"] = json("hello")
        cases["array"] = json("[]")
        cases["newer version"] = json("""{"version":2,"tasks":[],"notes":[]}""")
        cases["version as text"] = json("""{"version":"1","tasks":[],"notes":[]}""")
        cases["version 1.0"] = json("""{"version":1.0,"tasks":[],"notes":[]}""")
        cases["tasks object"] = json("""{"version":1,"tasks":{},"notes":[]}""")
        cases["task null"] = json("""{"version":1,"tasks":[null],"notes":[]}""")
        cases["wrong types"] = json("""{"version":1,"tasks":[{"id":"a","title":5,"dueAt":123,"repeat":7,"repeatDays":"x","repeatEvery":"3","snoozeCount":-4,"remindBefore":"60","priority":[],"category":{},"createdAt":false,"android":"x","tags":7,"checklist":{}}],"notes":[{}],"settings":[]}""")
        cases["repeat without due"] = json("""{"version":1,"tasks":[{"id":"a","title":"x","repeat":"monthly"}],"notes":[]}""")
        cases["days without due"] = json("""{"version":1,"tasks":[{"id":"a","title":"x","repeat":"days","scheduleAt":"2026-01-01T00:00:00Z"}],"notes":[]}""")
        cases["bad dates"] = json("""{"version":1,"tasks":[{"id":"a","title":"x","dueAt":"2026-13-45T99:99:99Z","repeat":"daily","scheduleAt":"+999999999-12-31T23:59:59Z"}],"notes":[]}""")
        cases["far future due"] = json("""{"version":1,"tasks":[{"id":"a","title":"x","dueAt":"+100000-01-01T00:00:00Z","repeat":"monthly"}],"notes":[]}""")
        cases["far past due"] = json("""{"version":1,"tasks":[{"id":"a","title":"x","dueAt":"-5000-01-01T00:00:00Z","repeat":"daily"}],"notes":[]}""")
        cases["empty id"] = json("""{"version":1,"tasks":[{"id":"","title":"x"}],"notes":[]}""")
        cases["duplicate ids"] = json("""{"version":1,"tasks":[{"id":"a","title":"x"},{"id":"a","title":"y"}],"notes":[]}""")
        cases["bom"] = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json("""{"version":1,"tasks":[],"notes":[]}""")
        cases["deep json in root"] = json("""{"version":1,"tasks":[],"notes":[],"x":""" + "[".repeat(50_000) + "]".repeat(50_000) + "}")
        cases["deep json in a task"] = json("""{"version":1,"tasks":[{"id":"a","title":"x","x":""" + "[".repeat(50_000) + "]".repeat(50_000) + "}],\"notes\":[]}")
        cases["huge string"] = json("""{"version":1,"tasks":[{"id":"a","title":"""" + "ب".repeat(3_000_000) + """"}],"notes":[]}""")
        cases["200,000 tasks"] = json("""{"version":1,"notes":[],"tasks":[""" + (0 until 200_000).joinToString(",") { """{"id":"$it","title":"t"}""" } + "]}")
        cases["zip without data"] = zip("x/readme.txt" to json("hi"))
        cases["zip path traversal"] = zip("../data.json" to json("""{"version":1,"tasks":[],"notes":[]}"""))
        cases["zip absolute"] = zip("/data.json" to json("""{"version":1,"tasks":[],"notes":[]}"""))
        cases["zip deep folder"] = zip("a/b/c/d/data.json" to json("""{"version":1,"tasks":[],"notes":[]}"""))
        cases["zip two data files"] = zip("a/data.json" to json("""{"version":1,"tasks":[{"id":"far","title":"x"}],"notes":[]}"""), "data.json" to json("""{"version":1,"tasks":[{"id":"near","title":"x"}],"notes":[]}"""))
        cases["zip wrong hash"] = zip("b/backup.json" to json("""{"algorithm":"sha256","files":{"data.json":"00"}}"""), "b/data.json" to json("""{"version":1,"tasks":[],"notes":[]}"""))
        cases["zip backup.json garbage"] = zip("b/backup.json" to json("{{{"), "b/data.json" to json("""{"version":1,"tasks":[],"notes":[]}"""))
        cases["zip bomb"] = zip("data.json" to ByteArray(25 * 1024 * 1024) { ' '.code.toByte() })
        cases["zip many entries"] = zip(*(0 until 20_000).map { "f$it/backup.json" to json("{}") }.toTypedArray(), "data.json" to json("""{"version":1,"tasks":[],"notes":[]}"""))
        cases["encrypted file given as backup"] = BackupCrypto.encrypt(exported(sample(3, 1)), "pw".toCharArray(), 1000)
        val bad = ArrayList<String>()
        val report = ArrayList<String>()
        for ((name, bytes) in cases) {
            val t0 = System.nanoTime()
            val o = outcome(bytes)
            val ms = (System.nanoTime() - t0) / 1_000_000
            report += "$name → $o ($ms ms)"
            if (o.startsWith("BAD")) bad += "$name: $o"
            if (ms > 20_000) bad += "$name took $ms ms"
        }
        report.forEach { println("[torture] backup shape: $it") }
        // "zip two data files": the one nearest the root wins.
        assertEquals("near", Backup.read(cases["zip two data files"]!!.inputStream(), now).state.tasks.single().id)
        assertTrue(outcome(cases["newer version"]!!).startsWith("refused"))
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test fun encryptedBackupsRefuseEverythingButTheRightPasswordOnAnIntactFile() {
        val seed = Torture.seed("crypto-fuzz", 8002)
        val rnd = Random(seed)
        val plain = exported(sample(5, seed))
        val pw = "كلمة سر 🙂 pass".toCharArray()
        val sealed = BackupCrypto.encrypt(plain, pw, 1_000)
        assertTrue(BackupCrypto.decrypt(sealed, pw.copyOf()).contentEquals(plain))
        val bad = ArrayList<String>()
        val tally = HashMap<String, Int>()
        fun attempt(name: String, data: ByteArray, password: CharArray) {
            val r = try {
                val out = BackupCrypto.decrypt(data, password)
                if (data.contentEquals(sealed) && password.contentEquals(pw)) "ok" else "BAD: opened ($name, ${out.size} bytes)"
            } catch (_: BackupCrypto.WrongPassword) { "wrong" } catch (_: BackupCrypto.Damaged) { "damaged" } catch (e: Throwable) { "BAD: $name threw ${e::class.java.name} ${e.message}" }
            tally.merge(r.substringBefore(':'), 1, Int::plus)
            if (r.startsWith("BAD")) bad += r
        }
        repeat(Torture.count(400, 3_000)) {
            attempt("wrong password", sealed, CharArray(rnd.nextInt(1, 20)) { (rnd.nextInt(0x20, 0x7f)).toChar() })
            attempt("truncated", sealed.copyOf(rnd.nextInt(0, sealed.size)), pw.copyOf())
            // One bit anywhere except the iteration count's top bytes (that is a 10-million-round PBKDF2, slow but refused).
            val k = rnd.nextInt(sealed.size).let { if (it in 6..8) 9 else it }
            attempt("bit flip at $k", sealed.copyOf().also { it[k] = (it[k].toInt() xor (1 shl rnd.nextInt(8))).toByte() }, pw.copyOf())
            attempt("appended", sealed + byteArrayOf(rnd.nextInt(256).toByte()), pw.copyOf())
            attempt("random", "RMBK1".toByteArray() + ByteArray(rnd.nextInt(0, 200)).also(rnd::nextBytes), pw.copyOf())
        }
        attempt("empty password", sealed, CharArray(0))
        attempt("lone surrogate", sealed, charArrayOf('\uD800'))
        attempt("empty file", ByteArray(0), pw.copyOf())
        attempt("header only", sealed.copyOf(62), pw.copyOf())
        println("[torture] crypto fuzz outcomes: $tally")
        assertEquals(bad.take(8).joinToString("\n"), 0, bad.size)
    }

    @Test fun aHugeEncryptedLengthFieldIsRefusedWithoutAllocating() {
        val sealed = BackupCrypto.encrypt("x".toByteArray(), "p".toCharArray(), 1_000)
        val out = sealed.copyOf()
        // length = 2^62
        java.nio.ByteBuffer.wrap(out).putLong(38, 1L shl 62)
        assertTrue(runCatching { BackupCrypto.decrypt(out, "p".toCharArray()) }.exceptionOrNull() is BackupCrypto.Damaged)
        Tasks.newId
    }
}
