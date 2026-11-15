package com.fastkiwi.rememberme

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.data.AlertLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/** F-016/F-017 (QC G-09): tones are prepared asynchronously; a broken or stuck source falls to the next, then to the sounding fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class RingServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("alert_log", Context.MODE_PRIVATE).edit().clear().commit()
        Notifier.channels(app)
        nm.cancelAll()
        ShadowMediaPlayer.resetStaticState()
    }

    @After fun tearDown() { ShadowMediaPlayer.resetStaticState() }

    private fun start(sources: List<String>) {
        val n = Notification.Builder(app, Notifier.CH_NORMAL).setSmallIcon(R.drawable.ic_stat_bell).setContentTitle("x").build()
        val i = Intent(app, RingService::class.java).putExtra(RingService.EXTRA_ID, 1234).putExtra(RingService.EXTRA_NOTIFICATION, n)
            .putExtra(RingService.EXTRA_SOURCES, sources.toTypedArray()).putExtra(RingService.EXTRA_STYLE, "normal")
            .putExtra(RingService.EXTRA_SECONDS, 30).putExtra(RingService.EXTRA_PLAY, true).putExtra(RingService.EXTRA_KEY, "k")
        Robolectric.buildService(RingService::class.java, i).create().startCommand(0, 1)
    }

    private fun events() = AlertLog(app).entries().map { it.event to it.code }

    @Test fun nothingPlayableEndsOnTheSoundingFallbackChannel() {
        start(listOf("/nowhere/a.mp3", "/nowhere/b.mp3"))
        ShadowLooper.idleMainLooper(10L, java.util.concurrent.TimeUnit.SECONDS)
        val e = events()
        assertEquals(2, e.count { it.first == AlertLog.Event.PLAY_FAILED })
        assertTrue(e.any { it.first == AlertLog.Event.FALLBACK && it.second == "play" })
        assertEquals(Notifier.CH_ALERT, nm.activeNotifications.single { it.id == 1234 }.notification.channelId)
    }

    @Test fun aSourceThatNeverPreparesIsSkippedAfterThreeSecondsAndTheNextPlays() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("/stuck.mp3"), ShadowMediaPlayer.MediaInfo(1000, 60_000))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("/good.mp3"), ShadowMediaPlayer.MediaInfo(1000, 100))
        start(listOf("/stuck.mp3", "/good.mp3"))
        ShadowLooper.idleMainLooper(2L, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("still waiting for the first source", events().none { it.first == AlertLog.Event.PLAY_FAILED })
        ShadowLooper.idleMainLooper(2L, java.util.concurrent.TimeUnit.SECONDS)
        val e = events()
        assertTrue(e.toString(), e.any { it.first == AlertLog.Event.PLAY_FAILED && it.second == "timeout" })
        assertTrue(e.toString(), e.any { it.first == AlertLog.Event.FALLBACK && it.second == "source1" })
        assertTrue("no fallback channel when a tone plays", e.none { it.first == AlertLog.Event.FALLBACK && it.second == "play" })
    }
}
