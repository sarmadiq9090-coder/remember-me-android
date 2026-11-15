package com.fastkiwi.rememberme

import com.fastkiwi.rememberme.core.AlertExt
import com.fastkiwi.rememberme.core.ToneRef
import com.fastkiwi.rememberme.ui.settings.PrivacyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F-090: docs/DATA-FORMAT.md lists the `android` members the code writes. F-089: every manifest permission is explained. */
class DocsTest {
    private fun doc(name: String) = File("../docs/$name").readText(Charsets.UTF_8)

    @Test fun dataFormatDocListsExactlyTheAndroidMembers() {
        val text = doc("DATA-FORMAT.md")
        val section = text.substringAfter("## 2. Task `android` members").substringBefore("\n## ")
        val documented = Regex("^\\| `([A-Za-z]+)` \\|", RegexOption.MULTILINE).findAll(section).map { it.groupValues[1] }.toSet()
        // Every member set: the keys the codec writes.
        val full = AlertExt(
            tone = ToneRef("bundled", "chime-1"), style = "alarm", fullScreen = "on", pin = "off", vibrate = true,
            alerts = listOf(60), speak = true, prayer = "fajr" to 10, hijri = 9 to 1,
        )
        assertEquals(full.toJson().keys, documented)
        // And the parser reads back exactly those (nothing is left in "unknown").
        assertTrue(AlertExt.parse(full.toJson()).unknown.isEmpty())
    }

    @Test fun everyManifestPermissionIsExplainedInTheAppAndTheDoc() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(permissions.isNotEmpty())
        assertEquals(permissions, PrivacyText.PERMISSIONS.map { it.first }.toSet())
        val privacy = doc("PRIVACY.md")
        for (p in permissions) assertTrue(p, privacy.contains("`" + p.substringAfterLast('.') + "`"))
        // 1.0.5 (D-081): the internet for updates only, and only to GitHub over HTTPS.
        assertTrue("android.permission.INTERNET" in permissions)
        val net = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(net.contains("cleartextTrafficPermitted=\"false\""))
        val domains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(net).map { it.groupValues[1] }.toSet()
        assertEquals(com.fastkiwi.rememberme.update.UpdateClient.GITHUB_HOSTS, domains)
    }
}
