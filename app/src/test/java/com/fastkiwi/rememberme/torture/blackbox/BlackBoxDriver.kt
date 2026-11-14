package com.fastkiwi.rememberme.torture.blackbox

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The black-box driver's entry point: replays <session>/script.txt against a freshly installed app and
 * writes what a person would see after each line into <session>/out/. See torture/blackbox/README.md.
 *
 * The session folder comes from -Dbb.session, the BB_SESSION environment variable, or the first line of
 * torture/blackbox/current-session.txt (written by run.sh). Without one the test is skipped, so a normal
 * test run is not affected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = Driver.START_QUALIFIERS, shadows = [FullScreenShadow::class])
class BlackBoxDriver {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun replay() {
        val path = System.getProperty("bb.session")?.takeIf { it.isNotBlank() }
            ?: System.getenv("BB_SESSION")?.takeIf { it.isNotBlank() }
            ?: listOf(File("../torture/blackbox/current-session.txt"), File("torture/blackbox/current-session.txt"))
                .firstOrNull { it.isFile }?.readText()?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        Assume.assumeTrue("No black-box session given", path != null)
        val session = File(path!!)
        require(File(session, "script.txt").isFile) { "No script.txt in the session folder $session" }
        Driver(rule, session).run()
    }
}
