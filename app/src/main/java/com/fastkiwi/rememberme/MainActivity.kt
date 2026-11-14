package com.fastkiwi.rememberme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Completion
import com.fastkiwi.rememberme.core.Repeat
import com.fastkiwi.rememberme.ui.ConfirmDialog
import androidx.compose.ui.res.stringResource
import com.fastkiwi.rememberme.core.TaskException
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.Backup
import com.fastkiwi.rememberme.data.Ics
import com.fastkiwi.rememberme.ui.SnackRequest
import com.fastkiwi.rememberme.data.Prefs
import com.fastkiwi.rememberme.ui.AboutScreen
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.HealthScreen
import com.fastkiwi.rememberme.ui.AlarmTestScreen
import com.fastkiwi.rememberme.ui.ScheduleStatus
import com.fastkiwi.rememberme.ui.CompatReport
import com.fastkiwi.rememberme.alarm.TestAlarm
import com.fastkiwi.rememberme.data.ScheduleLog
import com.fastkiwi.rememberme.ui.EditScreen
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.HomeScreen
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.Makers
import com.fastkiwi.rememberme.ui.SettingsModel
import com.fastkiwi.rememberme.ui.SettingsScreen
import com.fastkiwi.rememberme.ui.SystemScreens
import com.fastkiwi.rememberme.ui.TrashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Screens of the main window; a small back stack instead of a navigation library. */
sealed class Screen {
    data object Home : Screen()
    data class Edit(val id: String?) : Screen()
    data object Settings : Screen()
    data object Background : Screen()
    data object Trash : Screen()
    data object About : Screen()
    data object AlarmTest : Screen()

    /** For the saved instance state, so a rotation or a language change keeps the open screen. */
    fun code(): String = when (this) {
        Home -> "home"
        is Edit -> "edit:" + (id ?: "")
        Settings -> "settings"
        Background -> "background"
        Trash -> "trash"
        About -> "about"
        AlarmTest -> "alarmtest"
    }

    companion object {
        fun of(code: String): Screen? = when {
            code == "home" -> Home
            code.startsWith("edit:") -> Edit(code.removePrefix("edit:").ifEmpty { null })
            code == "settings" -> Settings
            code == "background" -> Background
            code == "trash" -> Trash
            code == "about" -> About
            code == "alarmtest" -> AlarmTest
            else -> null
        }
    }
}

class MainActivity : AppCompatActivity() {
    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
    private var health by mutableStateOf<Health?>(null)
    private var tick by mutableLongStateOf(System.currentTimeMillis())
    private var safety by mutableStateOf(listOf<File>())
    /** A snackbar for Home to show (the undo of a delete made in the editor). */
    private var homeSnack by mutableStateOf<SnackRequest?>(null)
    /** Titles of every-N-hours reminders to warn about before a Windows-format export (D-011), or null. */
    private var exportWarning by mutableStateOf<List<String>?>(null)
    /** A checked backup waiting for the user's confirmation, or null. */
    private var pendingImport by mutableStateOf<Backup.Imported?>(null)
    /** The compatibility report shown before sharing, or null. */
    private var reportText by mutableStateOf<String?>(null)
    /** The permissions screen was opened by the first start (it then has «متابعة»). */
    private var onboarding by mutableStateOf(false)

    private val icsExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(Ics.export(Repo.state(this@MainActivity), Repo.zone(), Instant.now()).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }

    // [home] begin
    /** Ids of the reminders «تصدير المحدد» saves, while the save dialog is open. */
    private var exportIds: List<String> = emptyList()
    private val selectedExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val ids = exportIds.toHashSet()
        exportIds = emptyList()
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val s = Repo.state(this@MainActivity)
                    contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(Ics.export(s.copy(tasks = s.tasks.filter { t -> t.id in ids }), Repo.zone(), Instant.now()).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }

    /** «تصدير المحدد» (F-066): the selected reminders as a calendar file, through the system's save dialog. */
    fun exportSelected(ids: List<String>) {
        if (ids.isEmpty()) return
        exportIds = ids
        selectedExporter.launch(Backup.folderName(Instant.now()).replace("backup", "calendar") + ".ics")
    }
    // [home] end

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshHealth() }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { Backup.export(Repo.state(this@MainActivity), it, Instant.now()) }
                    Repo.prefs(this@MainActivity).lastExport = System.currentTimeMillis()
                }.isSuccess
            }
            toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            // Read and check the whole file first; nothing is replaced until the user has seen the summary.
            val result = withContext(Dispatchers.IO) { readImport { contentResolver.openInputStream(uri)!! } }
            result.onSuccess { pendingImport = it }.onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    /** Reads and fully checks a backup without changing anything; the failure carries the message to show. */
    fun readImport(open: () -> java.io.InputStream): Result<Backup.Imported> = try {
        Result.success(open().use { Backup.read(it, Instant.now()) })
    } catch (e: Backup.ImportException) {
        Result.failure(Exception(getString(when (e.reason) {
            Backup.ImportException.Reason.NOT_A_BACKUP -> R.string.import_not_backup
            Backup.ImportException.Reason.DAMAGED -> R.string.import_damaged
            Backup.ImportException.Reason.TOO_LARGE -> R.string.import_too_large
            Backup.ImportException.Reason.INVALID -> R.string.import_invalid
        })))
    } catch (_: Exception) {
        Result.failure(Exception(getString(R.string.import_failed)))
    }

    /** Applies a checked backup: the current data is kept as a safety copy first. Returns the message. */
    fun applyImport(imported: Backup.Imported): String = try {
        Repo.store(this).safetyCopy()
        Repo.change(this) { imported.state }
        getString(R.string.import_done, imported.state.tasks.count { it.deletedAt == null }.toString())
    } catch (_: Exception) {
        getString(R.string.import_failed)
    }

    /** Both steps at once (tests, and older callers). */
    fun importFrom(open: () -> java.io.InputStream): String = readImport(open).fold({ applyImport(it) }, { it.message ?: getString(R.string.import_failed) })

    override fun onCreate(savedInstanceState: Bundle?) {
        L10n.apply(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val saved = savedInstanceState?.getStringArrayList(KEY_STACK)?.mapNotNull(Screen::of)
        if (!saved.isNullOrEmpty() && saved.first() == Screen.Home) stack = saved else handle(intent)
        safety = Repo.store(this).safetyCopies()
        val prefs = Repo.prefs(this)
        prefs.firstRun
        if (!prefs.onboarded) {
            prefs.onboarded = true
            stack = stack + Screen.Background
            onboarding = true
            askNotifications()
        }
        setContent {
            val state by Repo.store(this).state.collectAsState()
            val reduced = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            AppTheme(Repo.theme(state)) {
                androidx.compose.runtime.CompositionLocalProvider(com.fastkiwi.rememberme.ui.LocalReducedMotion provides reduced) { Root(state) }
            }
            LaunchedEffect(Unit) {
                // The lists show "late by" and move items between tabs as time passes.
                while (true) { delay(30_000); tick = System.currentTimeMillis() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_STACK, ArrayList(stack.map { it.code() }))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshHealth()
        tick = System.currentTimeMillis()
        Engine.syncAsync(this, reason = "open")
    }

    private fun handle(intent: Intent?) {
        when {
            intent?.action == ACTION_NEW -> stack = listOf(Screen.Home, Screen.Edit(null))
            intent?.getStringExtra(EXTRA_OPEN) != null -> stack = listOf(Screen.Home, Screen.Edit(intent.getStringExtra(EXTRA_OPEN)))
        }
    }

    private fun refreshHealth() { health = Health.check(this) }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            Repo.prefs(this).notifAsked = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Saves the Windows-format backup. Reminders that repeat every few hours are Android-only and become
     * one-time on Windows, so they are listed first and the user decides (D-011).
     */
    fun startExport() {
        val hourly = Repo.state(this).tasks.filter { it.deletedAt == null && it.completedAt == null && it.repeat == Repeat.HOURS }
        if (hourly.isEmpty()) exporter.launch(Backup.folderName(Instant.now()) + ".zip") else exportWarning = hourly.map { it.title }
    }

    private fun scheduleStatus(state: AppState, now: Instant): ScheduleStatus {
        val log = ScheduleLog(this)
        return ScheduleStatus(log.last(), log.lastBoot(),
            state.tasks.count { it.active && it.dueAt != null && it.dueAt.isAfter(now) },
            state.tasks.count { it.active && it.dueAt != null && !it.dueAt.isAfter(now) },
            log.lateness())
    }

    private fun testResults(rec: TestAlarm.Record, now: Instant): Map<TestAlarm.Part, TestAlarm.Result> {
        val h = health ?: Health.check(this)
        return TestAlarm.evaluate(rec, now.toEpochMilli(), h.channel, h.channelSound, h.channelVibrates, h.dnd)
    }

    private fun report(state: AppState, now: Instant): String {
        val h = Health.check(this)
        val rec = TestAlarm.record(this)
        return CompatReport.build(this, h, scheduleStatus(state, now), rec, testResults(rec, now))
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()

    fun push(s: Screen) { stack = stack + s }
    private fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else finish() }

    /** Data changes run off the main thread; the screen follows the store's state flow. */
    private fun act(block: (AppState) -> AppState) {
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Repo.change(this@MainActivity, block) } }
    }

    private fun setSetting(key: String, value: JsonPrimitive) = act { s -> s.withSettings(JsonObject(s.settings + (key to value))) }

    private fun fix(what: String) {
        when (what) {
            // The system asks once more at most; after that only Settings can allow notifications.
            "notifications" -> if (Build.VERSION.SDK_INT >= 33 && !Repo.prefs(this).notifAsked) askNotifications()
                else SystemScreens.open(this, SystemScreens.notifications(this))
            "channel" -> SystemScreens.open(this, SystemScreens.channel(this))
            "exact" -> SystemScreens.open(this, SystemScreens.exact(this))
            "fullscreen" -> SystemScreens.open(this, SystemScreens.fullScreen(this))
            "battery" -> SystemScreens.open(this, SystemScreens.battery(this))
            "hibernation" -> SystemScreens.open(this, SystemScreens.hibernation(this))
            "dnd" -> SystemScreens.open(this, SystemScreens.dnd())
            "maker" -> Makers.current()?.let { Makers.open(this, it) }
            "battery_list" -> SystemScreens.open(this, SystemScreens.batteryList())
            "app" -> SystemScreens.open(this, SystemScreens.appDetails(this))
        }
    }

    @Composable
    private fun Root(state: AppState) {
        val now = Instant.ofEpochMilli(maxOf(tick, System.currentTimeMillis()))
        val zone = Repo.zone()
        val ctx: Context = this
        val prefs = Repo.prefs(this)
        BackHandler(enabled = stack.size > 1) { pop() }
        val screen = stack.last()
        // Keyed by the screen, so the editor's remembered fields never carry over to another reminder.
        key(screen) { ScreenContent(screen, state, now, zone, ctx, prefs) }
        pendingImport?.let { imp ->
            val current = Repo.state(this).tasks.count { it.deletedAt == null }
            val lines = buildList {
                add(getString(R.string.restore_summary_counts, imp.active.toString(), imp.done.toString()))
                if (imp.trashed > 0) add(getString(R.string.restore_summary_trash, imp.trashed.toString()))
                if (imp.notes > 0) add(getString(R.string.restore_summary_notes, imp.notes.toString()))
                if (imp.droppedAttachments > 0) add(getString(R.string.import_attachments, imp.droppedAttachments.toString()))
                add(getString(if (imp.verified) R.string.restore_summary_verified else R.string.import_unverified))
                imp.createdAt?.let { add(getString(R.string.restore_summary_date, L10n.dueText(this@MainActivity, it))) }
                add(getString(R.string.restore_summary_safety, current.toString()))
            }
            ConfirmDialog(
                title = stringResource(R.string.restore_summary_title), text = lines.joinToString("\n"),
                confirm = stringResource(R.string.restore),
                onConfirm = {
                    lifecycleScope.launch {
                        val message = withContext(Dispatchers.IO) { applyImport(imp) }
                        safety = Repo.store(this@MainActivity).safetyCopies()
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = { pendingImport = null },
            )
        }
        reportText?.let { text ->
            // The report is shown in full before anything is shared; the user picks where it goes.
            ConfirmDialog(
                title = stringResource(R.string.compat_title), text = stringResource(R.string.compat_intro) + "\n\n" + text,
                confirm = stringResource(R.string.compat_send),
                onConfirm = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { startActivity(Intent.createChooser(send, getString(R.string.compat_title))) }
                },
                onDismiss = { reportText = null },
            )
        }
        exportWarning?.let { titles ->
            ConfirmDialog(
                title = stringResource(R.string.export_hours_title),
                text = stringResource(R.string.export_hours_body) + "\n" + titles.joinToString("\n") { "• $it" },
                confirm = stringResource(R.string.export_anyway),
                onConfirm = { exporter.launch(Backup.folderName(Instant.now()) + ".zip") },
                onDismiss = { exportWarning = null },
            )
        }
    }

    @Composable
    private fun ScreenContent(screen: Screen, state: AppState, now: Instant, zone: ZoneId, ctx: Context, prefs: Prefs) {
        when (screen) {
            Screen.Home -> {
                val month = 30L * 86_400_000
                val last = maxOf(prefs.lastExport, prefs.firstRun, prefs.backupNudgeDismissed)
                val nudge = state.tasks.any { it.deletedAt == null } && System.currentTimeMillis() - last > month
                HomeScreen(
                    state = state, now = now, health = health ?: Health.check(this), backupNudge = nudge,
                    onNew = { push(Screen.Edit(null)) },
                    onOpen = { push(Screen.Edit(it.id)) },
                    onAlert = { startActivity(Notifier.alertIntent(this, it.id)) },
                    onComplete = { t, showUndo ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            var done: Completion? = null
                            runCatching { Repo.change(ctx) { s -> Tasks.complete(s, t.id, Instant.now(), zone)?.also { done = it.second }?.first ?: s } }
                            done?.let { c -> withContext(Dispatchers.Main) { showUndo { act { s -> Tasks.undoComplete(s, c, Instant.now()) } } } }
                        }
                    },
                    onReopen = { t -> act { s -> Tasks.reopen(s, t.id, Instant.now()) } },
                    onTrash = { t -> act { s -> Tasks.trash(s, t.id, Instant.now()) } },
                    onUndoTrash = { t -> act { s -> Tasks.restore(s, t.id) } },
                    onSettings = { push(Screen.Settings) },
                    onHealth = { push(Screen.Background) },
                    onBackup = { startExport() },
                    onDismissBackup = { prefs.backupNudgeDismissed = System.currentTimeMillis(); tick = System.currentTimeMillis() },
                    snack = homeSnack, newerData = Repo.store(this).newer,
                    onSnackShown = { homeSnack = null },
                    // [home] begin
                    swipe = com.fastkiwi.rememberme.data.HomePrefs(this).swipe,
                    filters = com.fastkiwi.rememberme.data.Filters(this).all(),
                    change = { block, then ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { Repo.change(ctx, block) }
                            withContext(Dispatchers.Main) { then() }
                        }
                    },
                    onExportSelected = { ids -> exportSelected(ids) },
                    // [home] end
                )
            }
            is Screen.Edit -> {
                val task = screen.id?.let { state.task(it) }
                if (screen.id != null && task == null) { LaunchedEffect(screen) { pop() }; return }
                EditScreen(
                    task = task, weekStart = Repo.weekStart(state), now = now,
                    onSave = { input ->
                        try {
                            // Checked here so a mistake is shown in the editor; then saved off the main thread.
                            Tasks.save(Repo.state(this), input, Instant.now(), zone)
                            act { s -> runCatching { Tasks.save(s, input, Instant.now(), zone).first }.getOrDefault(s) }
                            pop(); null
                        } catch (e: TaskException) {
                            getString(when (e.reason) {
                                TaskException.Reason.TITLE -> R.string.err_title
                                TaskException.Reason.REPEAT_NEEDS_TIME -> R.string.err_repeat_time
                                TaskException.Reason.GONE -> R.string.err_gone
                                TaskException.Reason.COMPLETED -> R.string.err_completed
                                TaskException.Reason.SNOOZE_PAST -> R.string.choose_later_time
                            })
                        }
                    },
                    onDelete = { t ->
                        act { s -> Tasks.trash(s, t.id, Instant.now()) }
                        homeSnack = SnackRequest(getString(R.string.moved_to_trash)) { act { s -> Tasks.restore(s, t.id) } }
                        pop()
                    },
                    onBack = { pop() },
                )
            }
            Screen.Settings -> SettingsScreen(
                m = SettingsModel(prefs.language, Repo.theme(state), Repo.sound(state), Repo.remindAgain(state), Repo.weekStart(state), Repo.arabicMonths(state), prefs.lockPrivacy,
                    essentialOff = (health ?: Health.check(this)).let { h -> listOf(h.notifications, h.channel, h.exact).count { !it } },
                    trashCount = state.tasks.count { it.deletedAt != null }),
                safety = safety,
                onLanguage = { prefs.language = it; L10n.apply(this); Notifier.channels(this); Engine.syncAsync(this) },
                onTheme = { setSetting("theme", JsonPrimitive(it)) },
                onSound = { setSetting("sound", JsonPrimitive(it)) },
                onRemindAgain = { setSetting("remindAgain", JsonPrimitive(it)) },
                onWeekStart = { setSetting("weekStart", JsonPrimitive(it)) },
                onMonths = { v ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        Repo.change(ctx) { s -> s.withSettings(JsonObject(s.settings + ("arabicMonths" to JsonPrimitive(v)))) }
                        withContext(Dispatchers.Main) { L10n.apply(this@MainActivity) }
                    }
                },
                onLockPrivacy = { prefs.lockPrivacy = it; tick = System.currentTimeMillis(); Engine.syncAsync(this) },
                onBackground = { push(Screen.Background) },
                onExport = { startExport() },
                onExportIcs = { icsExporter.launch(Backup.folderName(Instant.now()).replace("backup", "calendar") + ".ics") },
                onImport = { importer.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain", "*/*")) },
                onRestoreSafety = { f ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ok = runCatching { Repo.store(ctx).restoreSafety(f) }.getOrDefault(false).also { Engine.sync(ctx) }
                        withContext(Dispatchers.Main) { toast(if (ok) R.string.restore_done else R.string.import_failed) }
                    }
                },
                onTrash = { push(Screen.Trash) },
                onAbout = { push(Screen.About) },
                onBack = { pop() },
            )
            Screen.Background -> HealthScreen(
                health ?: Health.check(this), scheduleStatus(state, now), Makers.current(), firstRun = onboarding, now = now,
                onCheck = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { Engine.sync(ctx, reason = "check") }
                        withContext(Dispatchers.Main) { refreshHealth(); tick = System.currentTimeMillis() }
                    }
                },
                onTest = { push(Screen.AlarmTest) },
                onReport = { reportText = report(state, now) },
                onFix = ::fix, onContinue = { onboarding = false; pop() }, onBack = { onboarding = false; pop() },
            )
            Screen.AlarmTest -> {
                // While a test runs, refresh every second so each part's result appears as it happens.
                LaunchedEffect(Unit) { while (true) { delay(1000); tick = System.currentTimeMillis() } }
                val rec = TestAlarm.record(this)
                AlarmTestScreen(rec, testResults(rec, now),
                    onStart = { TestAlarm.start(ctx); tick = System.currentTimeMillis() },
                    onAnswer = { part, yes ->
                        if (part == TestAlarm.Part.SOUND) TestAlarm.answer(ctx, sound = yes) else TestAlarm.answer(ctx, vibration = yes)
                        tick = System.currentTimeMillis()
                    },
                    onFix = ::fix, onReport = { reportText = report(state, now) }, onBack = { pop() })
            }
            Screen.Trash -> TrashScreen(state, now,
                onRestore = { t -> act { s -> Tasks.restore(s, t.id) } },
                onPurge = { t -> act { s -> Tasks.purge(s, t.id) } },
                onEmpty = { act { s -> Tasks.purgeTrash(s, Instant.now(), all = true) } },
                onBack = { pop() })
            Screen.About -> AboutScreen { pop() }
        }
    }

    companion object {
        const val ACTION_NEW = "com.fastkiwi.rememberme.NEW"
        const val EXTRA_OPEN = "open"
        private const val KEY_STACK = "stack"
    }
}
