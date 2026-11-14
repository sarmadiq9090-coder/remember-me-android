package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.DataFormatException
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.T
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DataTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = Instant.parse("2026-11-14T07:00:00Z")
    private val zone = ZoneId.of("Asia/Baghdad")
    private fun sample() = javaClass.getResource("/windows-data.json")!!.readText()

    @Before fun ids() = T.ids()

    // ---------- data.json ----------

    @Test fun readsTheWindowsFileAndKeepsEverythingItDoesNotUse() {
        val s = Codec.decode(sample(), now)
        assertEquals(3, s.tasks.size)
        val rent = s.tasks[0]
        assertEquals(Repeat.MONTHLY, rent.repeat)
        assertEquals(Priority.HIGH, rent.priority)
        assertEquals(60, rent.remindBefore)
        assertEquals(Instant.parse("2026-10-31T06:00:00Z"), rent.rule.from)
        assertEquals(listOf(1, 3, 5), s.tasks[1].rule.days)
        assertEquals(5, Codec.settingInt(s.settings, "remindAgain"))
        assertEquals(SnoozePreset.In(15), SnoozePreset.fromSettings(s.settings)[0])
        // Round trip: every field survives, including ones this app does not know.
        val back = Codec.json.parseToJsonElement(Codec.encode(s)).jsonObject
        val orig = Codec.json.parseToJsonElement(sample()).jsonObject
        assertEquals(orig["notes"], back["notes"])
        assertEquals(orig["attachments"], back["attachments"])
        assertEquals(orig["settings"], back["settings"])
        for (i in 0 until 3) {
            val o = orig["tasks"]!!.jsonArray[i].jsonObject
            val b = back["tasks"]!!.jsonArray[i].jsonObject
            for ((k, v) in o) assertEquals("task $i field $k", v, b[k])
        }
        assertEquals(JsonObject(mapOf("kept" to JsonPrimitive(true))), back["tasks"]!!.jsonArray[1].jsonObject["futureField"])
    }

    @Test fun writesWhatWindowsExpects() {
        val (s, t) = Tasks.save(AppState(), TaskInput(title = "Hourly", dueAt = now, repeat = Repeat.HOURS, everyHours = 3), now, zone)
        val o = Codec.json.parseToJsonElement(Codec.encode(s)).jsonObject
        assertEquals(1, o["version"]!!.jsonPrimitive.content.toInt())
        val task = o["tasks"]!!.jsonArray[0].jsonObject
        assertEquals("2026-11-14T07:00:00.000Z", task["dueAt"]!!.jsonPrimitive.content)
        assertEquals("hours", task["repeat"]!!.jsonPrimitive.content)
        assertEquals(3, task["repeatHours"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonArray(emptyList()), task["attachments"])
        assertEquals(t.id, task["id"]!!.jsonPrimitive.content)
        assertTrue(o["notes"] is JsonArray)
    }

    @Test fun oddValuesAreNormalizedAndBadShapesRejected() {
        val odd = """{"version":1,"notes":[],"tasks":[{"id":"a","title":"  x ","priority":"urgent","category":"?","dueAt":"not a date","repeat":"daily","remindBefore":7,"snoozeCount":5000}]}"""
        val t = Codec.decode(odd, now).tasks[0]
        assertEquals("x", t.title)
        assertEquals(Priority.NORMAL, t.priority)
        assertEquals("A repeat without a time is one-time", Repeat.NONE, t.repeat)
        assertEquals(0, t.remindBefore)
        assertEquals(0, t.snoozeCount)
        assertThrows(DataFormatException::class.java) { Codec.decode("""{"version":2,"tasks":[],"notes":[]}""", now) }
        assertThrows(DataFormatException::class.java) { Codec.decode("""{"version":1,"tasks":[{"title":"no id"}],"notes":[]}""", now) }
        assertThrows(DataFormatException::class.java) { Codec.decode("""{"version":1,"tasks":[{"id":"a","title":"a"},{"id":"a","title":"b"}],"notes":[]}""", now) }
        assertThrows(DataFormatException::class.java) { Codec.decode("not json", now) }
    }

    // ---------- Backup zip ----------

    @Test fun exportZipHasTheWindowsFolderLayoutAndAChecksum() {
        val s = Codec.decode(sample(), now)
        val out = ByteArrayOutputStream()
        val folder = Backup.export(s, out, now)
        assertEquals("RememberMe-backup-2026-11-14T07-00-00-000Z", folder)
        val files = HashMap<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        assertEquals(setOf("$folder/data.json", "$folder/backup.json"), files.keys)
        val info = Codec.json.parseToJsonElement(files["$folder/backup.json"]!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("Remember Me", info["app"]!!.jsonPrimitive.content)
        assertEquals("sha256", info["algorithm"]!!.jsonPrimitive.content)
        assertEquals(Backup.sha256(files["$folder/data.json"]!!), info["files"]!!.jsonObject["data.json"]!!.jsonPrimitive.content)
        // Attachments cannot travel: the list is emptied and every reference removed (Windows' restore checks them).
        val data = Codec.json.parseToJsonElement(files["$folder/data.json"]!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonArray(emptyList()), data["attachments"])
        assertEquals(JsonArray(emptyList()), data["notes"]!!.jsonArray[0].jsonObject["attachments"])
        assertEquals(JsonArray(emptyList()), data["tasks"]!!.jsonArray[0].jsonObject["attachments"])
        assertFalse(files["$folder/data.json"]!!.let { it.size >= 3 && it[0] == 0xEF.toByte() })
        // Extracted for tools/windows-validate.cjs, which restores it with the Windows app's own code.
        val check = File("build/windows-check/$folder").apply { deleteRecursively(); mkdirs() }
        for ((name, bytes) in files) File(check, name.substringAfter('/')).writeBytes(bytes)
    }

    @Test fun importRoundTripAndWindowsShapes() {
        val s = Codec.decode(sample(), now)
        val out = ByteArrayOutputStream()
        Backup.export(s, out, now)
        val r = Backup.read(out.toByteArray().inputStream(), now)
        assertTrue(r.verified)
        assertEquals(s.tasks.map { it.id }, r.state.tasks.map { it.id })
        assertEquals(Codec.encode(Backup.stripAttachments(s).first), Codec.encode(r.state))
        // A bare data.json (as copied out of a Windows backup folder), with a BOM: accepted, unverified, attachments dropped.
        val bare = Backup.read(("﻿" + sample()).toByteArray().inputStream(), now)
        assertFalse(bare.verified)
        assertEquals(1, bare.droppedAttachments)
        // A zip of a Windows backup folder, with attachment files that are ignored.
        val win = ByteArrayOutputStream()
        ZipOutputStream(win).use { z ->
            z.putNextEntry(ZipEntry("RememberMe-backup-x/attachments/att-1/receipt.pdf")); z.write(ByteArray(1234)); z.closeEntry()
            z.putNextEntry(ZipEntry("RememberMe-backup-x/data.json")); z.write(sample().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("RememberMe-backup-x/backup.json"))
            z.write("""{"app":"Remember Me","kind":"manual","algorithm":"sha256","files":{"data.json":"${Backup.sha256(sample().toByteArray())}"}}""".toByteArray()); z.closeEntry()
        }
        val w = Backup.read(win.toByteArray().inputStream(), now)
        assertTrue(w.verified)
        assertEquals(3, w.state.tasks.size)
    }

    @Test fun importRefusesDamagedForeignAndHostileFiles() {
        fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        }.toByteArray()
        val good = sample().toByteArray()
        fun reason(bytes: ByteArray) = runCatching { Backup.read(bytes.inputStream(), now) }.exceptionOrNull().let { (it as Backup.ImportException).reason }
        // Checksum mismatch.
        assertEquals(Backup.ImportException.Reason.DAMAGED, reason(zip("b/data.json" to good, "b/backup.json" to """{"algorithm":"sha256","files":{"data.json":"00"}}""".toByteArray())))
        // No data.json, or only one hidden behind "..".
        assertEquals(Backup.ImportException.Reason.NOT_A_BACKUP, reason(zip("photo.jpg" to ByteArray(10))))
        assertEquals(Backup.ImportException.Reason.NOT_A_BACKUP, reason(zip("../../data.json" to good)))
        // Not our data.
        assertEquals(Backup.ImportException.Reason.INVALID, reason("""{"hello":"world"}""".toByteArray()))
        // A zip bomb is cut off by the size cap.
        val big = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("b/data.json")); val chunk = ByteArray(1 shl 20); repeat(25) { z.write(chunk) }; z.closeEntry() }
        }.toByteArray()
        assertEquals(Backup.ImportException.Reason.TOO_LARGE, reason(big))
    }

    // ---------- Store ----------

    @Test fun storeWritesDurablyKeepsABackupAndRecoversFromDamage() {
        val dir = tmp.newFolder("data")
        val store = Store(dir) { now }
        store.change { Tasks.save(it, TaskInput(title = "one"), now, zone).first }
        store.change { Tasks.save(it, TaskInput(title = "two"), now, zone).first }
        assertTrue(File(dir, "data.json").isFile)
        assertEquals(1, Codec.decode(File(dir, "data.json.bak").readText(), now).tasks.size)
        assertFalse(File(dir, "data.json.tmp").exists())
        assertEquals(2, Store(dir) { now }.state.value.tasks.size)
        // A damaged file is kept aside and the previous good copy is loaded.
        File(dir, "data.json").writeText("{ broken")
        val reopened = Store(dir) { now }
        assertTrue(reopened.recovered)
        assertEquals(1, reopened.state.value.tasks.size)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("data.json.damaged-") })
        // Only .bak left (a crash mid-write): loaded.
        File(dir, "data.json").delete()
        assertEquals(1, Store(dir) { now }.state.value.tasks.size)
    }

    @Test fun safetyCopiesKeepTheNewestThree() {
        val dir = tmp.newFolder("data2")
        var t = now
        val store = Store(dir) { t }
        store.change { Tasks.save(it, TaskInput(title = "before"), now, zone).first }
        repeat(5) { t = t.plusSeconds(1); store.safetyCopy() }
        assertEquals(3, store.safetyCopies().size)
        store.replace(AppState())
        assertTrue(store.state.value.tasks.isEmpty())
        assertTrue(store.restoreSafety(store.safetyCopies().first()))
        assertEquals("before", store.state.value.tasks.single().title)
        assertNotNull(store.state.value)
    }

    @Test fun restoringASafetyCopyKeepsTheCurrentDataAsAnother() {
        val dir = tmp.newFolder("data3")
        var t = now
        val store = Store(dir) { t }
        store.change { Tasks.save(it, TaskInput(title = "old"), now, zone).first }
        val copy = store.safetyCopy()
        store.replace(AppState())
        store.change { Tasks.save(it, TaskInput(title = "current"), now, zone).first }
        t = t.plusSeconds(5)
        // One tap on a safety copy must not throw away what is on the phone now.
        assertTrue(store.restoreSafety(copy))
        assertEquals("old", store.state.value.tasks.single().title)
        val kept = store.safetyCopies().map { Codec.decode(it.readText(), now).tasks.map { x -> x.title } }
        assertTrue(kept.toString(), listOf("current") in kept)
    }
}
