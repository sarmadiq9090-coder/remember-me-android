package com.fastkiwi.rememberme.ui

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0.7: the app language reaches Android on the very first start of a phone in English (Android 13+), so the
 * screens and the notifications never disagree; and a language picked in Android's per-app Settings is adopted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h860dp-xxhdpi")
class AppLanguageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lm get() = app.getSystemService(LocaleManager::class.java)

    @Before fun setUp() {
        Repo.resetForTests()
        app.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        lm.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    @Test fun firstStartOnAnEnglishPhoneSetsArabicForTheApp() {
        assertFalse(L10n.apply(app, adoptSystem = true))
        assertEquals("ar", lm.applicationLocales[0].language)
        assertEquals("ar", Repo.prefs(app).language)
        assertEquals("ar", L10n.current(app).language)
    }

    @Test fun englishPickedInAndroidSettingsBecomesTheAppLanguage() {
        L10n.apply(app)
        lm.applicationLocales = LocaleList.forLanguageTags("en")
        assertTrue(L10n.apply(app, adoptSystem = true))
        assertEquals("en", Repo.prefs(app).language)
        assertEquals("en", L10n.current(app).language)
        assertEquals("en", lm.applicationLocales[0].language)
    }

    @Test fun languagePickedInsideTheAppWinsOverTheOldSystemValue() {
        L10n.apply(app)
        Repo.prefs(app).language = "en"
        assertFalse(L10n.apply(app))
        assertEquals("en", lm.applicationLocales[0].language)
        assertEquals("en", Repo.prefs(app).language)
    }

    @Test fun nothingChangesWhenAlreadyInStep() {
        L10n.apply(app)
        val before = lm.applicationLocales.toLanguageTags()
        assertFalse(L10n.apply(app, adoptSystem = true))
        assertEquals(before, lm.applicationLocales.toLanguageTags())
    }
}
