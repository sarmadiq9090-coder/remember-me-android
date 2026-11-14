package com.fastkiwi.rememberme.data

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.DirectBoot
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.core.SavedFilter
import com.fastkiwi.rememberme.core.TaskInput
import com.fastkiwi.rememberme.core.Tasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Instant

/** F-088: after «حذف كل البيانات» the app's files and preference folders are empty and no alarm is left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeleteAllTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun listing(dir: File): List<String> = dir.walkTopDown().filter { it != dir }.map { it.relativeTo(dir).path }.toList()

    @Test fun deletesEveryFileAndPreferenceAndCancelsAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Repo.resetForTests()
        val now = Instant.now()
        // Fill everything the app can keep.
        Repo.change(app) { s ->
            var st = s
            st = Tasks.save(st, TaskInput(title = "soon", dueAt = now.plusSeconds(3600)), now, Repo.zone()).first
            Tasks.save(st, TaskInput(title = "later", dueAt = now.plusSeconds(7200)), now, Repo.zone()).first
        }
        Repo.change(app) { it }
        Repo.store(app).safetyCopy()
        Repo.prefs(app).summaryMinutes = 8 * 60
        SettingsPrefs(app).swipeOn = false
        Filters(app).add(SavedFilter("1", "Home", "tag", "home"))
        AlertLog(app).add(AlertLog.Event.FIRED, "k")
        File(Tones.dir(app).apply { mkdirs() }, "abc.mp3").writeBytes(ByteArray(3))
        File(app.filesDir, "history.json").writeText("[]")
        File(app.cacheDir, "tmp.bin").writeBytes(ByteArray(2))
        DirectBoot.remember(app, listOf(now.plusSeconds(3600).toEpochMilli() to false), "ar")
        Engine.sync(app)
        com.fastkiwi.rememberme.alarm.Summary.arm(app, now)
        val am = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertTrue("alarms armed before", am.scheduledAlarms.isNotEmpty())
        assertTrue(listing(app.filesDir).isNotEmpty())

        DeleteAll.run(app)

        assertEquals(emptyList<String>(), listing(app.filesDir))
        assertEquals(emptyList<String>(), listing(app.cacheDir))
        val prefsDir = File(app.dataDir, "shared_prefs")
        val left = prefsDir.listFiles()?.map { it.name.removeSuffix(".xml") }.orEmpty().filter { it !in DeleteAll.LIBRARY_PREFS }
        assertEquals(emptyList<String>(), left)
        val dp = app.createDeviceProtectedStorageContext()
        assertEquals(emptyList<String>(), File(dp.dataDir, "shared_prefs").listFiles()?.map { it.name }.orEmpty())
        assertTrue(DirectBoot.times(app).isEmpty())
        assertEquals(emptyList<Any>(), am.scheduledAlarms.map { it.operation })
        assertTrue(app.getSharedPreferences("device", Context.MODE_PRIVATE).all.isEmpty())
        // A new start reads an empty folder.
        Repo.resetForTests()
        assertTrue(Repo.state(app).tasks.isEmpty())
        assertTrue(SettingsPrefs(app).swipeOn)
    }
}
