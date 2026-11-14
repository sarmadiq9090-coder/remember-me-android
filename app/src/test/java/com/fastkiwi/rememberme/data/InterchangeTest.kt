package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.steps
import com.fastkiwi.rememberme.core.tags
import com.fastkiwi.rememberme.core.withExt
import com.fastkiwi.rememberme.core.withSteps
import com.fastkiwi.rememberme.core.withTags
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * F-091: both directions with the Windows app's own code. Phase 1 writes an Android backup with every
 * 1.0.0 member (the `android` object, tags, steps, snooze presets) to build/interchange/android/.
 * `node tools/windows-roundtrip.cjs` restores it with Windows' readBackup(), edits it with Windows'
 * saveTask/snoozeTask/completeTask, and saves a Windows backup to build/interchange/windows/. Phase 2
 * reads that backup back and checks that nothing Android-only was lost. `tools/interchange.sh` runs all
 * three; phase 2 is skipped when the Windows output is absent (plain `gradle test`).
 */
class InterchangeTest {
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val zone = ZoneId.of("Asia/Baghdad")
    private val ext = AlertExt(tone = ToneRef("bundled", "chime-2"), style = "alarm", fullScreen = "off", pin = "on", vibrate = false,
        alerts = listOf(1440, 60), speak = true, unknown = mapOf("future" to JsonPrimitive("kept")))

    private fun sample(): AppState {
        var s = AppState()
        s = Tasks.save(s, TaskInput(title = "دواء الضغط", dueAt = now.plusSeconds(3600), repeat = Repeat.DAILY, priority = Priority.HIGH, remindBefore = 60), now, zone).first
        s = Tasks.save(s, TaskInput(title = "Meeting notes", dueAt = now.plusSeconds(7200), remindBefore = 15), now, zone).first
        val a = s.tasks[0].withExt(ext).withTags(listOf("صحة", "#عائلة")).withSteps(listOf(Organize.Step("s1", "اشترِ", false), Organize.Step("s2", "خذ الحبة", true)))
        val b = s.tasks[1].withTags(listOf("work"))
        return s.copy(tasks = listOf(a, b))
    }

    @Test fun phase1_androidExportForWindows() {
        val out = ByteArrayOutputStream()
        Backup.export(sample(), out, now)
        val dir = File("build/interchange/android").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                File(dir, e.name.substringAfter('/')).apply { parentFile?.mkdirs() }.writeBytes(z.readBytes())
            }
        }
        assertTrue(File(dir, "data.json").exists())
        File("build/interchange/windows").deleteRecursively()
    }

    @Test fun phase2_windowsEditedBackupKeepsEverything() {
        val root = File("build/interchange/windows")
        val folder = root.listFiles()?.firstOrNull { it.isDirectory }
        assumeTrue("Run tools/interchange.sh for this phase", folder != null)
        val s = Codec.decode(File(folder!!, "data.json").readText(), now)
        val med = s.tasks.first { it.title == "دواء الضغط" && it.completedAt == null }
        // Windows edited the title's note, snoozed and completed things: the Android-only members survive.
        assertEquals(ext, AlertExt.of(med))
        assertEquals(listOf("صحة", "عائلة"), med.tags)
        assertEquals(listOf("s1", "s2"), med.steps.map { it.id })
        assertEquals("edited on Windows", med.body)
        val notes = s.tasks.first { it.title == "Meeting notes" }
        assertEquals(listOf("work"), notes.tags)
        assertTrue("Windows completed it", notes.completedAt != null)
        // The repeat's history copy from Windows' completeTask keeps the ticks; the next occurrence is unticked.
        val copy = s.tasks.firstOrNull { it.sourceId == med.id }
        if (copy != null) assertEquals(listOf(false, false), med.steps.map { it.done })
    }
}
