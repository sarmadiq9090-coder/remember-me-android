package com.fastkiwi.rememberme.torture.blackbox

import android.app.NotificationManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Android's "full-screen notifications" permission (Android 14+), which Robolectric does not model:
 * the driver's phone allows it unless the script says «full-screen-permission off».
 */
@Implements(NotificationManager::class)
class FullScreenShadow : ShadowNotificationManager() {
    companion object { @JvmStatic var allowed = true }

    @Implementation(minSdk = 34)
    protected fun canUseFullScreenIntent(): Boolean = allowed
}
