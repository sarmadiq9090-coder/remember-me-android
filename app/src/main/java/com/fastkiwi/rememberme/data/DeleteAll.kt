package com.fastkiwi.rememberme.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.app.NotificationManagerCompat
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.AlarmReceiver
import com.fastkiwi.rememberme.alarm.DirectBoot
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Summary
import com.fastkiwi.rememberme.alarm.TestAlarm
import java.io.File

/**
 * «حذف كل البيانات» (F-088): removes everything the app keeps on this phone — data.json and its .bak/.tmp, damaged
 * copies, safety copies and snapshots (all under files/), logs, history, copied tones, every SharedPreferences file
 * (settings, lists, logs, the backup password) and the device-protected «boot_times» file — and cancels every alarm
 * and notification. The caller offers a backup first and asks for the typed confirmation; afterwards the app
 * restarts as on a first install.
 */
object DeleteAll {
    /** SharedPreferences files a library (not this app) may recreate by itself at launch; left out of the test count. */
    val LIBRARY_PREFS: Set<String> = setOf("androidx.profileinstaller.profileinstaller_prefs", "WebViewChromiumPrefs")

    /** The app's own preference files, also cleared when a listing of the folder is not possible. */
    private val OWN_PREFS = listOf("device", "filters", "alert_log", "schedule_log", "alarm_test")
    private val DEVICE_PROTECTED_PREFS = listOf("boot_times")

    /** Must run off the main thread (it waits for a running engine pass to finish). */
    fun run(context: Context) {
        val app = context.applicationContext
        runCatching { Engine.drain() }
        cancelAlarms(app)
        runCatching { TestAlarm.cancel(app) }
        runCatching { NotificationManagerCompat.from(app).cancelAll() }
        for (dir in listOfNotNull(app.filesDir, app.cacheDir, app.noBackupFilesDir)) dir.listFiles()?.forEach { it.deleteRecursively() }
        // The Keystore key first: clearing it writes to "device", which is deleted next.
        runCatching { BackupPassword.clear(app) }
        clearPrefs(app, OWN_PREFS)
        runCatching { clearPrefs(app.createDeviceProtectedStorageContext(), DEVICE_PROTECTED_PREFS) }
        // The open store and settings are forgotten: the next start reads an empty folder.
        Repo.resetForTests()
    }

    private fun clearPrefs(c: Context, known: List<String>) {
        val dir = File(c.dataDir, "shared_prefs")
        val names = (dir.listFiles()?.map { it.name.removeSuffix(".xml") }.orEmpty() + known).distinct().filter { it !in LIBRARY_PREFS }
        for (name in names) {
            // Clear first: a SharedPreferences object stays cached in memory after its file is deleted.
            runCatching { c.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
            runCatching { c.deleteSharedPreferences(name) }
        }
    }

    /** The exact PendingIntents the engine, the summary and the locked-boot path arm (same action, data, code). */
    private fun cancelAlarms(app: Context) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        fun cancel(c: Context, code: Int, intent: Intent) = runCatching {
            PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it); it.cancel() }
        }
        for (slot in listOf(Engine.SLOT_A, Engine.HOUSEKEEPING, Engine.SLOT_B)) {
            cancel(app, slot, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.FIRE).setData("rememberme://slot/$slot".toUri()))
        }
        cancel(app, SUMMARY_SLOT, Intent(app, AlarmReceiver::class.java).setAction(Summary.ACTION))
        runCatching {
            val dp = app.createDeviceProtectedStorageContext()
            cancel(dp, LOCKED_SLOT, Intent(app, AlarmReceiver::class.java).setAction(DirectBoot.ACTION))
        }
    }

    // Request codes of Summary (104) and DirectBoot (110); private there, repeated here (a test checks the result).
    private const val SUMMARY_SLOT = 104
    private const val LOCKED_SLOT = 110
}
