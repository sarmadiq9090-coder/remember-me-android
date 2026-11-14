package com.fastkiwi.rememberme.torture

import android.app.Notification
import android.app.NotificationManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the calls the app makes to NotificationManager. On a phone each one is a binder call to the system
 * server, so their number per engine run is a cost the JVM timing does not show.
 */
@Implements(NotificationManager::class)
class CountingNotificationManager : ShadowNotificationManager() {
    companion object {
        val cancels = AtomicInteger()
        val notifies = AtomicInteger()
        fun reset() { cancels.set(0); notifies.set(0) }
    }

    @Implementation
    override fun cancel(tag: String?, id: Int) { cancels.incrementAndGet(); super.cancel(tag, id) }

    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification) { notifies.incrementAndGet(); super.notify(tag, id, notification) }
}
