package com.fastkiwi.rememberme

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * 1.0.6: the F-Droid build has no self-updater and no internet permission, so the privacy texts shown in About and
 * Privacy must not say it connects to GitHub for updates (or that this can be turned off in Settings). The `full`
 * build keeps the GitHub wording.
 */
@RunWith(RobolectricTestRunner::class)
class FlavorStringsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val texts = listOf(R.string.about_privacy, R.string.offline, R.string.set_privacy_intro, R.string.set_never_internet)

    @Test fun privacyTextsMatchTheFlavor() {
        for (locale in listOf(Locale("ar"), Locale.ENGLISH)) {
            val ctx = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(locale) })
            for (id in texts) {
                val s = ctx.getString(id)
                if (BuildConfig.UPDATER_ENABLED) {
                    assertTrue("$locale: $s", s.contains("GitHub") || id == R.string.offline)
                } else {
                    assertFalse("$locale: fdroid text mentions GitHub: $s", s.contains("GitHub"))
                    assertFalse("$locale: fdroid text mentions updates: $s", s.contains("update", ignoreCase = true) || s.contains("تحديث"))
                    assertFalse("$locale: fdroid text mentions a setting: $s", s.contains("Settings") || s.contains("الإعدادات"))
                }
            }
        }
    }
}
