package com.fastkiwi.rememberme.update

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fastkiwi.rememberme.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/** What the «التحديثات» screen shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val done: Long, val total: Long) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val error: UpdateError, val release: Release? = null) : UpdateState
}

/**
 * 1.0.5 update (D-081, from Kiwi Scan 2.0): the app side of [Updater]. The automatic check runs only when the app is opened, at
 * most once every 7 days ([UpdatePolicy]), and only after the one-time notice was shown; there is no
 * background service. [checkNow] is the manual button. Nothing is downloaded without a tap, and
 * nothing is installed without Android's own confirmation.
 */
class UpdateController(
    private val ctx: Context,
    private val settings: UpdatePrefs,
    private val scope: CoroutineScope,
    private val updater: Updater = Updater(
        UpdateClient("RememberMe/${BuildConfig.VERSION_NAME}"),
        BuildConfig.VERSION_NAME,
        AndroidApkVerifier(ctx),
        File(ctx.cacheDir, "updates"),
    ) { if (isRobolectric()) NetworkState.OFFLINE else currentNetwork(ctx) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Called when the automatic check finds a newer version (the app shows a message). */
    private val onAvailable: (Release) -> Unit = {},
) {
    private val _state = mutableStateOf<UpdateState>(UpdateState.Idle)
    val state: State<UpdateState> = _state

    private val _noticePending = mutableStateOf(!settings.updateNoticeShown && (showNoticeInTests || !isRobolectric()))
    /** The one-time notice about the update connection is waiting to be shown. */
    val noticePending: State<Boolean> = _noticePending

    private var job: Job? = null

    fun noticeSeen() {
        settings.updateNoticeShown = true
        _noticePending.value = false
    }

    /** At app start: the weekly automatic check, if it is due. Returns the job (tests wait on it). */
    fun onAppOpened(): Job? {
        if (job?.isActive == true) return null
        val t = now()
        // A check recorded while the clock ran ahead would switch the automatic check off until that date:
        // restart the 7-day wait from the corrected clock instead.
        if (settings.lastUpdateCheck > t) settings.lastUpdateCheck = t
        val due = UpdatePolicy.autoCheckDue(settings.autoUpdateCheck, settings.updateNoticeShown, settings.lastUpdateCheck, t)
        if (!due) {
            // Leftovers of an earlier run (a download cut by process death, a verified file the user did not
            // install): nothing resumes them, so they only hold storage.
            if (_state.value == UpdateState.Idle) return scope.launch(Dispatchers.IO) { updater.clean() }.also { job = it }
            return null
        }
        autoRunning = true
        return scope.launch {
            try {
            if (_state.value == UpdateState.Idle) withContext(Dispatchers.IO) { updater.clean() }
            val r = withContext(Dispatchers.IO) { updater.check() }
            // Offline: nothing was asked; try again at the next start. Otherwise wait 7 days.
            if (!(r is CheckResult.Failed && r.error == UpdateError.OFFLINE)) settings.lastUpdateCheck = now()
            if (manualWaiting) {
                // «تحقق من التحديثات» was tapped while this ran: it gets this answer.
                manualWaiting = false
                _state.value = when (r) {
                    CheckResult.UpToDate -> UpdateState.UpToDate
                    is CheckResult.Available -> UpdateState.Available(r.release)
                    is CheckResult.Failed -> UpdateState.Failed(r.error)
                }
            } else if (r is CheckResult.Available) {
                _state.value = UpdateState.Available(r.release)
                onAvailable(r.release)
            }
            } finally {
                autoRunning = false
            }
        }.also { job = it }
    }

    /** «تحقق من التحديثات»: any time. */
    fun checkNow(): Job? {
        if (job?.isActive == true) {
            // The automatic check is running: show «جارٍ التحقق» and give its answer when it ends (bug hunt H3).
            if (autoRunning) { manualWaiting = true; _state.value = UpdateState.Checking }
            return null
        }
        _state.value = UpdateState.Checking
        return scope.launch {
            val r = withContext(Dispatchers.IO) { updater.check() }
            _state.value = when (r) {
                CheckResult.UpToDate -> UpdateState.UpToDate
                is CheckResult.Available -> UpdateState.Available(r.release)
                is CheckResult.Failed -> UpdateState.Failed(r.error)
            }
        }.also { job = it }
    }

    /** «تنزيل وتثبيت»: download, verify (version, sha256, certificate), then hand to Android's installer. */
    fun downloadAndInstall(release: Release): Job? {
        if (job?.isActive == true) return null
        _state.value = UpdateState.Downloading(release, 0, release.apkSize)
        return scope.launch {
            val r = runInterruptible(Dispatchers.IO) {
                updater.download(release, settings.updateWifiOnly) { done, total ->
                    scope.launch(Dispatchers.Main) { if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Downloading(release, done, total) }
                }
            }
            when (r) {
                is DownloadResult.Failed -> _state.value = UpdateState.Failed(r.error, release)
                is DownloadResult.Verified -> {
                    _state.value = UpdateState.Ready(r.release, r.apk)
                    install(r.apk, r.release)
                }
            }
        }.also { job = it }
    }

    /** Opens Android's install confirmation for a verified file (again, if the user backed out). */
    fun install(apk: File, release: Release) {
        // A confirmation is already waiting (the person had left the app): open it, never a second session (H4).
        if (UpdateInstallEvents.pending != null) { openPendingConfirm(); return }
        if (UpdateInstallEvents.inFlight && UpdateInstallEvents.lastInstall?.first == apk) return
        lastInstall = apk to release
        UpdateInstallEvents.failed = false
        if (!apk.exists()) { _state.value = UpdateState.Failed(UpdateError.STORAGE, release); return }
        // The installer could not even start (a session could not be created or written): the file is kept and the
        // message says the update was not installed, so the next tap tries the installer again.
        val started = installer(apk)
        UpdateInstallEvents.inFlight = started
        _state.value = if (started) UpdateState.Ready(release, apk) else UpdateState.Failed(UpdateError.INSTALL, release)
    }

    /**
     * «حاول مرة أخرى» after a failed install: the verified file again if it is still there, otherwise a fresh
     * download (never a storage error for a file that was simply cleaned).
     */
    fun retryInstall(release: Release): Job? {
        if (job?.isActive == true) return null
        val last = lastInstall
        if (last != null && last.second.apkName == release.apkName && last.first.exists()) {
            install(last.first, last.second)
            return null
        }
        return downloadAndInstall(release)
    }

    /** Shared by every controller of this process (H1): a recreated MainActivity still knows what was installed. */
    private var lastInstall: Pair<File, Release>?
        get() = UpdateInstallEvents.lastInstall
        set(v) { UpdateInstallEvents.lastInstall = v }

    @Volatile private var manualWaiting = false
    private var autoRunning = false

    private val _confirmPending = mutableStateOf(UpdateInstallEvents.pending != null)
    /** Android's confirmation screen is waiting because the user had left the app: the banner «التحديث جاهز للتثبيت». */
    val confirmPending: State<Boolean> = _confirmPending

    /** The app came to the front (or the installer just reported): show the banner / failure message if one is due. */
    fun refreshInstallStatus() {
        _confirmPending.value = UpdateInstallEvents.pending != null
        if (UpdateInstallEvents.failed) {
            UpdateInstallEvents.failed = false
            val release = lastInstall?.second ?: (_state.value as? UpdateState.Ready)?.release
            if (release != null) _state.value = UpdateState.Failed(UpdateError.INSTALL, release)
        }
    }

    /** The banner's button: opens Android's own confirmation screen. */
    fun openPendingConfirm() {
        val i = UpdateInstallEvents.pending ?: return
        UpdateInstallEvents.pending = null
        _confirmPending.value = false
        if (runCatching { ctx.startActivity(i) }.isFailure) {
            lastInstall?.second?.let { _state.value = UpdateState.Failed(UpdateError.INSTALL, it) }
        }
    }

    fun dismissPendingConfirm() { _confirmPending.value = false }

    init { UpdateInstallEvents.listener = { refreshInstallStatus() } }

    @VisibleForTesting
    internal var installer: (File) -> Boolean = { f -> runCatching { UpdateInstaller.install(ctx, f) }.isSuccess }

    companion object {
        /**
         * Tests that check the notice itself turn it on; every other Robolectric test starts without it.
         * Under Robolectric the app's own updater also always sees "offline", so no test (and no black-box
         * session) can ever reach the real GitHub; the update tests inject a local mock server instead.
         */
        @VisibleForTesting
        @Volatile
        var showNoticeInTests = false

        private fun isRobolectric() = Build.FINGERPRINT == "robolectric"
    }
}
