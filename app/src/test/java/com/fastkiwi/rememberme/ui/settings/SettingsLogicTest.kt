package com.fastkiwi.rememberme.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Priority
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.core.SnoozePreset
import com.fastkiwi.rememberme.core.T
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.core.tags
import com.fastkiwi.rememberme.core.withExt
import com.fastkiwi.rememberme.core.withTags
import com.fastkiwi.rememberme.data.AutoBackup
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.BackupCrypto
import com.fastkiwi.rememberme.data.BackupPassword
import com.fastkiwi.rememberme.data.SettingsPrefs
import kotlinx.serialization.json.JsonObject
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
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The logic behind Settings: search, snooze presets, lists and tags, export effects, restore, weekly copy, what's new. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ar")
class SettingsLogicTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-11-15T07:00:00Z")
    private val zone = ZoneId.of("Asia/Baghdad")

    @Before fun ids() = T.ids()

    private fun state(vararg inputs: TaskInput): AppState = inputs.fold(AppState()) { s, i -> Tasks.save(s, i, now, zone).first }

    // ---------------------------------------------------------------- search

    private fun search(q: String, values: Map<String, String> = emptyMap(), hidden: Set<String> = emptySet()) =
        SettingsIndex.search(q, { c.getString(it) }, { values[it] }, hidden)

    @Test fun searchMatchesTitleGroupSynonymsAndValueWithFolding() {
        val sound = search("صوت").map { it.entry.key }
        // By title («إعادة التنبيه بالصوت»), by synonym (the tones), and Health rows.
        assertTrue(sound.toString(), "again" in sound && "tone_normal" in sound && "tone_alarm" in sound && "h_channel" in sound && "h_test_sound" in sound)
        // Title matches first.
        assertTrue(sound.indexOf("again") < sound.indexOf("tone_normal"))
        // Hamza folding: «اصوات» finds the synonym «أصوات».
        assertTrue("tone_normal" in search("اصوات").map { it.entry.key })
        // Group: «التنبيه» lists the group's rows with «في: التنبيه».
        val hit = search("الاهتزاز").first { it.entry.key == "vibration" }
        assertEquals("التنبيه", hit.group)
        assertEquals(c.getString(R.string.set_in_group, hit.group), "في: التنبيه")
        // Value: a current value is searchable.
        assertEquals(listOf("ring"), search("دقيقتان", mapOf("ring" to "دقيقتان")).map { it.entry.key }.filter { it == "ring" })
        // Hidden rows are not offered; an empty query finds nothing.
        assertFalse("hijri_adjust" in search("تعديل التاريخ الهجري", hidden = setOf("hijri_adjust")).map { it.entry.key })
        assertTrue(search("  ").isEmpty())
        assertTrue(search("كلمةلاوجودلها").isEmpty())
    }

    @Test fun searchHighlightsTheMatchInTheTitle() {
        val hit = search("صوت").first { it.entry.key == "again" }
        assertEquals("صوت", hit.title.substring(hit.highlight!!.first, hit.highlight!!.last + 1))
        val folded = search("اعادة").first { it.entry.key == "again" }
        assertEquals("إعادة", folded.title.substring(folded.highlight!!.first, folded.highlight!!.last + 1))
    }

    @Test fun everyIndexEntryHasItsStringsAndNoMiddleDotOrIndicDigits() {
        val keys = SettingsIndex.ENTRIES.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (e in SettingsIndex.ENTRIES) for (id in listOfNotNull(e.titleRes, e.groupRes, e.synonymsRes.takeIf { it != 0 })) {
            val s = c.getString(id)
            assertTrue(e.key, s.isNotBlank() && "·" !in s && s.none { it in '٠'..'٩' })
        }
    }

    // ---------------------------------------------------------------- snooze presets (F-043)

    @Test fun snoozePresetsAreAlwaysSixValidWindowsPresets() {
        val d = SnoozePreset.DEFAULTS
        assertTrue(SnoozeOptions.valid(d))
        val next = SnoozeOptions.replace(d, 0, SnoozePreset.In(5))!!
        assertEquals(SnoozePreset.In(5), next[0])
        assertEquals(6, SnoozeOptions.toJson(next).size)
        // Every offered choice is accepted by the Windows rules.
        for (m in SnoozeOptions.MINUTES) assertNotNull(SnoozeOptions.replace(d, 1, SnoozePreset.In(m)))
        assertNotNull(SnoozeOptions.replace(d, 2, SnoozePreset.Today(LocalTime.of(1, 0))))
        // An invalid set is never produced: «today» before 01:00, a bad slot, a wrong length.
        assertNull(SnoozeOptions.replace(d, 2, SnoozePreset.Today(LocalTime.of(0, 30))))
        assertNull(SnoozeOptions.replace(d, 6, SnoozePreset.Week))
        assertFalse(SnoozeOptions.valid(d.take(5)))
        assertNull(SnoozeOptions.replace(d, 0, SnoozePreset.In(0)))
        // Round trip through settings.
        val settings = JsonObject(mapOf("snoozePresets" to SnoozeOptions.toJson(next)))
        assertEquals(next, SnoozePreset.fromSettings(settings))
        assertEquals("غدًا في الوقت نفسه", SnoozeOptions.label(c, SnoozePreset.In(1440)))
        assertFalse("·" in SnoozeOptions.summary(c, d))
    }

    // ---------------------------------------------------------------- lists and tags (F-062, F-063)

    @Test fun tagsRenameAndDeleteAcrossRemindersAndListsFollow() {
        var s = state(TaskInput(title = "a"), TaskInput(title = "b"), TaskInput(title = "c"))
        s = s.copy(tasks = s.tasks.mapIndexed { i, t -> when (i) { 0 -> t.withTags(listOf("منزل", "سفر")); 1 -> t.withTags(listOf("منزل")); else -> t } })
        assertEquals(listOf("منزل" to 2, "سفر" to 1), ListsLogic.tagCounts(s))
        assertEquals(2, ListsLogic.tagged(s, "منزل"))
        val later = now.plusSeconds(60)
        val renamed = ListsLogic.renameTag(s, "منزل", "البيت", later)
        assertEquals(listOf("البيت", "سفر"), renamed.tasks[0].tags)
        assertEquals(listOf("البيت"), renamed.tasks[1].tags)
        assertEquals(later, renamed.tasks[0].updatedAt)
        assertEquals(s.tasks[2], renamed.tasks[2])
        // Renaming onto an existing tag merges (no duplicates).
        assertEquals(listOf("سفر"), ListsLogic.renameTag(s, "منزل", "سفر", later).tasks[0].tags)
        val deleted = ListsLogic.deleteTag(s, "منزل", later)
        assertEquals(listOf("سفر"), deleted.tasks[0].tags)
        assertTrue(deleted.tasks[1].tags.isEmpty())
        assertFalse("tags" in deleted.tasks[1].extra)
        // Lists on the renamed tag follow; removing a list is a list operation only.
        val lists = listOf(SavedFilter("1", "المنزل", "tag", "منزل"), SavedFilter("2", "", "overdue"))
        assertEquals("البيت", ListsLogic.renameInLists(lists, "منزل", "البيت")[0].value)
        assertEquals(listOf("2", "1"), ListsLogic.move(lists, 0, 1).map { it.id })
        assertEquals(lists, ListsLogic.move(lists, 0, -1))
    }

    @Test fun savedFilterCountsAndCategoryCounts() {
        val s = state(
            TaskInput(title = "late", dueAt = now.minusSeconds(3600), priority = Priority.HIGH),
            TaskInput(title = "soon", dueAt = now.plusSeconds(3600)),
            TaskInput(title = "far", dueAt = now.plusSeconds(40L * 86400)),
        )
        fun n(kind: String) = ListsLogic.count(SavedFilter("x", "", kind), s, now, zone, 6)
        assertEquals(1, n("priority"))
        assertEquals(1, n("overdue"))
        assertEquals(2, n("week"))
        assertEquals(3, n("untagged"))
        assertEquals(3, ListsLogic.categoryCounts(s).values.sum())
    }

    // ---------------------------------------------------------------- export effects (F-092)

    @Test fun exportEffectsCountEachChange() {
        var s = state(
            TaskInput(title = "water", dueAt = now.plusSeconds(3600), repeat = Repeat.HOURS, everyHours = 2),
            TaskInput(title = "meeting", dueAt = now.plusSeconds(7200), remindBefore = 60),
            TaskInput(title = "fajr", dueAt = now.plusSeconds(9000), repeat = Repeat.DAILY),
            TaskInput(title = "plain", dueAt = now.plusSeconds(9000)),
        )
        s = s.copy(tasks = s.tasks.map { t ->
            when (t.title) {
                "meeting" -> t.withExt(AlertExt(alerts = listOf(1440, 60), tone = ToneRef.SILENT))
                "fajr" -> t.withExt(AlertExt(prayer = "fajr" to 10))
                else -> t
            }
        })
        val fx = ExportEffects.of(s)
        assertEquals(listOf("water"), fx.hourly)
        assertEquals(1, fx.prayer)
        assertEquals(0, fx.hijri)
        assertEquals(1, fx.extraAlerts)
        assertEquals(1, fx.keptUnused)
        assertTrue(fx.any)
        assertFalse(ExportEffects.of(state(TaskInput(title = "x", dueAt = now.plusSeconds(60)))).any)
    }

    // ---------------------------------------------------------------- restore (§5.20, F-086)

    private fun zip(s: AppState): ByteArray = ByteArrayOutputStream().also { Backup.export(s, it, now) }.toByteArray()

    private fun read(bytes: ByteArray, pw: String? = null, prayer: Boolean = true) =
        RestoreReader.read(bytes, pw?.toCharArray(), now, { it.kind == "system" }, prayer)

    @Test fun restoreSummaryCountsAndWarns() {
        var s = state(
            TaskInput(title = "daily", dueAt = now.plusSeconds(60), repeat = Repeat.DAILY),
            TaskInput(title = "once", dueAt = now.plusSeconds(60)),
            TaskInput(title = "toned", dueAt = now.plusSeconds(60)),
            TaskInput(title = "prayer", dueAt = now.plusSeconds(60), repeat = Repeat.DAILY),
            TaskInput(title = "gone", dueAt = now.plusSeconds(60)),
        )
        s = s.copy(tasks = s.tasks.map { t ->
            when (t.title) {
                "toned" -> t.withExt(AlertExt(tone = ToneRef("system", "content://x", "Old phone tone")))
                "prayer" -> t.withExt(AlertExt(prayer = "asr" to 15))
                "gone" -> t.copy(deletedAt = now)
                else -> t
            }
        })
        val r = read(zip(s), prayer = false) as RestoreReader.Result.Ready
        with(r.summary) {
            assertEquals(RestoreReader.Source.ANDROID, source)
            assertTrue(verified)
            assertFalse(encrypted)
            assertEquals(4, active)
            assertEquals(2, repeating)
            assertEquals(1, trash)
            assertEquals(1, missingTones)
            assertEquals(1, prayerWithoutSettings)
        }
        assertEquals(0, (read(zip(s), prayer = true) as RestoreReader.Result.Ready).summary.prayerWithoutSettings)
    }

    @Test fun restoreRefusalsAndPasswords() {
        val plain = zip(state(TaskInput(title = "a")))
        val sealed = BackupCrypto.encrypt(plain, "سر 123".toCharArray())
        assertEquals(RestoreReader.Result.NeedsPassword, read(sealed))
        assertEquals(RestoreReader.Result.Refused(RestoreReader.Refusal.WRONG_PASSWORD), read(sealed, "خطأ"))
        val ok = read(sealed, "سر 123") as RestoreReader.Result.Ready
        assertTrue(ok.summary.encrypted)
        assertEquals(1, ok.summary.active)
        // A changed byte after the header: damaged, not a wrong password.
        val bad = sealed.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertEquals(RestoreReader.Result.Refused(RestoreReader.Refusal.DAMAGED), read(bad, "سر 123"))
        assertEquals(RestoreReader.Result.Refused(RestoreReader.Refusal.INVALID), read("hello".toByteArray()))
        assertEquals(RestoreReader.Result.Refused(RestoreReader.Refusal.NEWER), read("""{"version":2,"tasks":[],"notes":[]}""".toByteArray()))
        val notBackup = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1) } }.toByteArray()
        assertEquals(RestoreReader.Result.Refused(RestoreReader.Refusal.NOT_A_BACKUP), read(notBackup))
        assertEquals(RestoreReader.Result.Refused(RestoreReader.Refusal.TOO_LARGE), read(ByteArray((RestoreReader.MAX_BYTES + 1).toInt())))
        // A bare data.json has no source; a Windows backup.json without "source" is Windows.
        val bare = com.fastkiwi.rememberme.core.Codec.encode(state(TaskInput(title = "a"))).toByteArray()
        assertEquals(RestoreReader.Source.UNKNOWN, RestoreReader.sourceOf(bare))
    }

    // ---------------------------------------------------------------- weekly copy (F-087)

    private class MemFolder : AutoBackup.Folder {
        val files = linkedMapOf<String, ByteArray>("notes.txt" to ByteArray(1))
        override fun names() = files.keys.toList()
        override fun write(name: String, mime: String, bytes: ByteArray) { files[name] = bytes }
        override fun delete(name: String) { files.remove(name) }
    }

    @Test fun weeklyCopyKeepsTheNewestFiveAndNeverTouchesOtherFiles() {
        assertTrue(AutoBackup.due(0, now.toEpochMilli()))
        assertFalse(AutoBackup.due(now.toEpochMilli() - 6 * 86_400_000L, now.toEpochMilli()))
        assertTrue(AutoBackup.due(now.toEpochMilli() - 7 * 86_400_000L, now.toEpochMilli()))
        val folder = MemFolder()
        val s = state(TaskInput(title = "a"))
        for (week in 0 until 7) AutoBackup.writeTo(folder, s, now.plusSeconds(week * 7L * 86400), null)
        val ours = folder.names().filter { it.startsWith(AutoBackup.PREFIX) }.sorted()
        assertEquals(5, ours.size)
        assertEquals(AutoBackup.fileName(now.plusSeconds(2 * 7L * 86400), false), ours.first())
        assertTrue("notes.txt" in folder.names())
        // An encrypted copy opens with the password.
        AutoBackup.writeTo(folder, s, now.plusSeconds(70L * 86400), "pw".toCharArray())
        val enc = folder.files.getValue(AutoBackup.fileName(now.plusSeconds(70L * 86400), true))
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertTrue(read(enc, "pw") is RestoreReader.Result.Ready)
    }

    @Test fun backupPasswordIsStoredOnlyWrapped() {
        val real = BackupPassword.wrapper
        BackupPassword.wrapper = object : BackupPassword.Wrapper {
            override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun unwrap(sealed: ByteArray) = wrap(sealed)
            override fun delete() {}
        }
        try {
            assertTrue(BackupPassword.save(c, "كلمة سر".toCharArray()))
            val raw = c.getSharedPreferences("device", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains("كلمة سر"))
            assertEquals("كلمة سر", String(BackupPassword.load(c)!!))
            BackupPassword.clear(c)
            assertNull(BackupPassword.load(c))
        } finally {
            BackupPassword.wrapper = real
        }
        // Without an Android Keystore (Robolectric) nothing is stored.
        assertFalse(BackupPassword.save(c, "x".toCharArray()))
        assertFalse(BackupPassword.has(c))
    }

    // ---------------------------------------------------------------- what's new (F-106), prayer city

    @Test fun whatsNewOnlyAfterAnUpdateFromBefore100AndOnce() {
        val old = File("before-update-v2-20261115-070000-000.json")
        val same = File("before-update-v3-20261115-070000-000.json")
        val other = File("before-import-20261115-070000-000.json")
        assertTrue(shouldShowWhatsNew(0, listOf(other, old)))
        assertFalse(shouldShowWhatsNew(3, listOf(old)))
        assertFalse(shouldShowWhatsNew(0, listOf(same, other)))
        assertFalse(shouldShowWhatsNew(0, emptyList()))
        val s = state(
            TaskInput(title = "high", priority = Priority.HIGH, dueAt = now.plusSeconds(60)),
            TaskInput(title = "normal", dueAt = now.plusSeconds(60)),
            TaskInput(title = "done-high", priority = Priority.HIGH),
        ).let { st -> st.copy(tasks = st.tasks.map { if (it.title == "done-high") it.copy(completedAt = now) else it }) }
        val after = makeAllNormal(s, now)
        assertEquals("normal", AlertExt.of(after.tasks[0]).style)
        assertNull(AlertExt.of(after.tasks[1]).style)
        assertNull(AlertExt.of(after.tasks[2]).style)
    }

    @Test fun prayerCityIsAListedCityOrTypedCoordinates() {
        assertEquals("Baghdad", SettingsPrefs.place("Baghdad")!!.nameEn)
        val geo = SettingsPrefs.geo(33.3123, 44.3612, ZoneId.of("Asia/Baghdad"))
        assertEquals("geo:33.3123,44.3612,Asia/Baghdad", geo)
        val p = SettingsPrefs.place(geo)!!
        assertEquals(33.3123, p.lat, 1e-9)
        assertEquals("Asia/Baghdad", p.zoneId)
        assertNull(SettingsPrefs.place("geo:99,0,Asia/Baghdad"))
        assertNull(SettingsPrefs.place(""))
    }
}
