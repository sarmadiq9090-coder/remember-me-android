package com.fastkiwi.rememberme.torture

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.ActionReceiver
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.data.AlertLog
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.ExecutorService

/**
 * A clean simulated phone for the engine torture tests, as EngineReliabilityTest sets it up, plus counters:
 * a loud announcement is a start of [RingService] (the app plays the tone itself, F-016), so it is counted from
 * the started services, which Robolectric records for the whole test, not from the notifications left on screen.
 */
class EngineHarness(val zone: ZoneId = ZoneId.of("Asia/Baghdad")) {
    val app: Application = ApplicationProvider.getApplicationContext()
    private var tzBefore: TimeZone? = null
    val crashes = java.util.Collections.synchronizedList(ArrayList<Throwable>())
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    fun setUp() {
        Engine.drain()
        drainActions()
        tzBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "schedule_log", "alarm_test", "alert_log")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        app.createDeviceProtectedStorageContext().getSharedPreferences("boot_times", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Notifier.channels(app)
        nm.cancelAll()
        shadowOf(app).clearStartedServices()
        RingService.ringingId = 0
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
    }

    fun tearDown() {
        Engine.drain()
        drainActions()
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
        tzBefore?.let { TimeZone.setDefault(it) }
    }

    val am: AlarmManager get() = app.getSystemService(AlarmManager::class.java)
    val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    fun put(state: AppState) { Repo.store(app).replace(state) }
    fun state(): AppState = Repo.state(app)

    /** Task ids whose due notification is on screen. */
    fun dueShown(): List<String> = nm.activeNotifications.mapNotNull { Notifier.dueTask(it) }
    /** Alarms that wake the phone (housekeeping is a non-waking window). */
    fun wakeAlarms() = shadowOf(am).scheduledAlarms.filter { it.type == AlarmManager.RTC_WAKEUP }
    fun summaryShown() = nm.activeNotifications.any { it.id == 1 && it.tag == null }

    /** Every RingService start since the test began: (notification id, alert key). */
    fun rings(): List<Pair<Int, String>> = shadowOf(app).allStartedServices
        .filter { it.component?.className == RingService::class.java.name }
        .map { it.getIntExtra(RingService.EXTRA_ID, 0) to (it.getStringExtra(RingService.EXTRA_KEY) ?: "") }

    /** Loud announcements per task id. */
    fun ringsOf(id: String) = rings().count { it.first == Notifier.dueId(id) }

    fun posted(event: AlertLog.Event) = AlertLog(app).entries().count { it.event == event }

    /** Waits for ActionReceiver's own background thread (private) to finish what it was given. */
    fun drainActions() {
        val f = ActionReceiver::class.java.declaredFields.firstOrNull { ExecutorService::class.java.isAssignableFrom(it.type) }
            ?: ActionReceiver.Companion::class.java.declaredFields.firstOrNull { ExecutorService::class.java.isAssignableFrom(it.type) }
            ?: return
        f.isAccessible = true
        val owner = if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else ActionReceiver.Companion
        (f.get(owner) as ExecutorService).submit {}.get()
    }

    /**
     * Delivers [intent] to [receiver] as the system does: with a PendingResult, so goAsync() works (a bare
     * onReceive call has none, and a receiver that finishes it from a worker thread would fail only in the test).
     */
    fun deliver(receiver: android.content.BroadcastReceiver, intent: Intent) {
        val create = Class.forName("org.robolectric.shadows.ShadowBroadcastPendingResult")
            .getDeclaredMethod("create", Int::class.javaPrimitiveType, String::class.java, android.os.Bundle::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false)
        android.content.BroadcastReceiver::class.java.getDeclaredMethod("setPendingResult", android.content.BroadcastReceiver.PendingResult::class.java)
            .apply { isAccessible = true }.invoke(receiver, pending)
        receiver.onReceive(app, intent)
    }
}
