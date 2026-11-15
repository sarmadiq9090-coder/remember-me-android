package com.fastkiwi.rememberme.update

import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Independent bug hunt, 1.0.5 update path. Every test here failed on 2055fb6 (H5 adapted to the verdict-returning verifier). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateHuntTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private lateinit var server: MiniHttpServer
    private lateinit var base: String
    private lateinit var work: File
    private val apkBytes = ByteArray(3_000_000) { (it * 31 % 251).toByte() }
    @Volatile private var latestDelayMs = 0L
    private var tag = "v2.1.0"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        UpdateInstallEvents.pending = null
        UpdateInstallEvents.failed = false
        UpdateInstallEvents.foreground = false
        UpdateInstallEvents.lastInstall = null
        UpdateInstallEvents.inFlight = false
        work = File(ctx.cacheDir, "updates-hunt").apply { deleteRecursively(); mkdirs() }
        server = MiniHttpServer { path, _ ->
            when (path) {
                "/repos/x/releases/latest" -> {
                    if (latestDelayMs > 0) Thread.sleep(latestDelayMs)
                    Triple(200, emptyMap(), """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[
                        {"name":"RememberMe-2.1.0.apk","size":${apkBytes.size},"browser_download_url":"$base/dl/RememberMe-2.1.0.apk"},
                        {"name":"SHA256SUMS.txt","size":100,"browser_download_url":"$base/dl/SHA256SUMS.txt"}]}""".toByteArray())
                }
                "/dl/SHA256SUMS.txt" -> Triple(200, emptyMap(), "${sha(apkBytes)}  RememberMe-2.1.0.apk\n".toByteArray())
                "/dl/RememberMe-2.1.0.apk" -> Triple(200, emptyMap(), apkBytes)
                else -> Triple(404, emptyMap(), "{}".toByteArray())
            }
        }
        base = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
        work.deleteRecursively()
        UpdateInstallEvents.pending = null
        UpdateInstallEvents.failed = false
        UpdateInstallEvents.listener = null
    }

    private fun client() = UpdateClient("RememberMe/2.0.0", "$base/repos/x/releases/latest", setOf("127.0.0.1"), requireHttps = false, timeoutMs = 5_000)

    private val release get() = Release(SemVer.parse("2.1.0")!!, "v2.1.0", "RememberMe-2.1.0.apk", "$base/dl/RememberMe-2.1.0.apk", apkBytes.size.toLong(), "$base/dl/SHA256SUMS.txt")

    private fun report(status: Int, confirm: Intent? = null) {
        val i = Intent(UpdateInstaller.ACTION).putExtra(PackageInstaller.EXTRA_STATUS, status)
        if (confirm != null) i.putExtra(Intent.EXTRA_INTENT, confirm)
        UpdateInstallReceiver().onReceive(ctx, i)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * H1 (S2): MainActivity is recreated (rotation while Android's confirmation is open, or the app reopened) between
     * the install and the installer's report. The new controller has no lastInstall and is Idle, so the failure is
     * swallowed (UpdateInstallEvents.failed is cleared) and the person sees no message at all.
     */
    @Test
    fun installFailureAfterActivityRecreationIsStillShown() {
        val f = File(work, "RememberMe-2.1.0.apk").apply { writeText("apk") }
        val u = Updater(client(), "2.0.0", { ApkVerdict.OK }, work) { NetworkState.WIFI }
        val old = UpdateController(ctx, UpdatePrefs(ctx), CoroutineScope(Dispatchers.Unconfined), u)
        old.installer = { true }
        old.install(f, release)
        // Rotation: a new MainActivity builds a new controller (the `updates` lazy), which takes the global listener.
        val recreated = UpdateController(ctx, UpdatePrefs(ctx), CoroutineScope(Dispatchers.Unconfined), u)
        recreated.refreshInstallStatus() // onStart
        report(PackageInstaller.STATUS_FAILURE)
        assertEquals(UpdateState.Failed(UpdateError.INSTALL, release), recreated.state.value)
    }

    /**
     * H2 (S2): the download runs blocking code inside withContext(IO); cancelling the activity's lifecycleScope
     * (rotation, back) does not stop it. It keeps using the network invisibly (UI is Idle in the new activity),
     * its result is thrown away, and the verified APK is left in the cache.
     */
    @Test
    fun downloadStopsWhenItsScreenScopeIsCancelled() {
        val calls = AtomicInteger()
        val u = Updater(client(), "2.0.0", { ApkVerdict.OK }, work) { calls.incrementAndGet(); Thread.sleep(40); NetworkState.WIFI }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = UpdateController(ctx, UpdatePrefs(ctx).apply { updateWifiOnly = true }, scope, u)
        var installs = 0
        c.installer = { installs++; true }
        c.downloadAndInstall(release)
        Thread.sleep(300)
        scope.cancel() // activity destroyed
        val atCancel = calls.get()
        Thread.sleep(5_000)
        val after = calls.get() - atCancel
        assertTrue("download kept running after its scope was cancelled: $after more chunks read; files left: ${work.list()?.toList()}; installs=$installs",
            after <= 1 && work.listFiles().orEmpty().isEmpty())
    }

    /**
     * H3 (S3): «تحقق من التحديثات» tapped while the automatic check (app open) is still running is silently ignored:
     * no «جارٍ التحقق», and when the automatic check ends with "up to date" (or a failure) nothing is shown.
     */
    @Test
    fun manualCheckDuringTheAutomaticCheckGivesAnAnswer() {
        latestDelayMs = 1_500
        val settings = UpdatePrefs(ctx).apply { updateNoticeShown = true; autoUpdateCheck = true; lastUpdateCheck = 0L }
        val c = UpdateController(ctx, settings, CoroutineScope(Dispatchers.Unconfined), Updater(client(), "2.1.0", { ApkVerdict.OK }, work) { NetworkState.WIFI }, { 5L })
        val auto = c.onAppOpened()!!
        c.checkNow() // the person taps the button a moment after opening the app
        val afterTap = c.state.value
        runBlocking { auto.join() }
        assertEquals("right after the tap: $afterTap; after the automatic check ended", UpdateState.UpToDate, c.state.value)
    }

    /**
     * H4 (S3): a second tap on «تثبيت» (Ready row, no busy guard) while Android's confirmation for the first session
     * is still waiting creates a second PackageInstaller session (two confirmation screens, the first session leaked).
     */
    @Test
    fun secondInstallTapWhileConfirmationIsPendingDoesNotOpenASecondSession() {
        val f = File(work, "RememberMe-2.1.0.apk").apply { writeText("apk") }
        val c = UpdateController(ctx, UpdatePrefs(ctx), CoroutineScope(Dispatchers.Unconfined), Updater(client(), "2.0.0", { ApkVerdict.OK }, work) { NetworkState.WIFI })
        var sessions = 0
        c.installer = { sessions++; true }
        c.install(f, release)
        report(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.intent.action.VIEW")) // person is in another app
        val st = c.state.value as UpdateState.Ready
        c.install(st.apk, st.release) // the Ready row's «تثبيت» is tapped again
        assertEquals("installer sessions created", 1, sessions)
    }

    /**
     * H5 (S3): a release whose APK has the same key but is not a higher versionCode (tag bumped, build not) is refused by
     * AndroidApkVerifier.acceptable, but the user is told «الملف المنزَّل غير موقّع بمفتاح التطبيق» (signature mismatch),
     * which suggests tampering; and the next check offers it again.
     */
    @Test
    fun sameKeyButNotNewerVersionCodeIsNotReportedAsSignatureMismatch() {
        val k = setOf("aa")
        val u = Updater(client(), "2.0.0", { AndroidApkVerifier.acceptable("p", "p", k, k, 8, 8) }, work) { NetworkState.WIFI }
        val r = (u.check() as CheckResult.Available).release
        assertEquals(DownloadResult.Failed(UpdateError.NOT_NEWER), u.download(r, wifiOnly = false))
    }
}
