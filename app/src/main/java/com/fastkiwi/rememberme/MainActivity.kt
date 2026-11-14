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
    // [settings] begin
    data object Lists : Screen()
    // [settings] end

    /** For the saved instance state, so a rotation or a language change keeps the open screen. */
    fun code(): String = when (this) {
        Home -> "home"
        is Edit -> "edit:" + (id ?: "")
        Settings -> "settings"
        Background -> "background"
        Trash -> "trash"
        About -> "about"
        AlarmTest -> "alarmtest"
        // [settings] begin
        Lists -> "lists"
        // [settings] end
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
            // [settings] begin
            code == "lists" -> Lists
            // [settings] end
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
    /** The compatibility report shown before sharing, or null. */
    private var reportText by mutableStateOf<String?>(null)
    /** The permissions screen was opened by the first start (it then has «متابعة»). */
    private var onboarding by mutableStateOf(false)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshHealth() }
    // [settings] begin
    /** Backup, restore, calendar files, weekly copy, encryption, delete all and «ما الجديد» (ui/settings/SettingsFlows.kt). */
    val flows = com.fastkiwi.rememberme.ui.settings.SettingsFlows(this)
    // [settings] end

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
        // [settings] begin
        flows.onRestored = { safety = Repo.store(this).safetyCopies() }
        if (savedInstanceState == null) flows.checkWhatsNew()
        // [settings] end
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

    // [settings] begin
    /** Saves a backup; what Windows would change is listed first (F-092, D-011). */
    fun startExport() = flows.export()
    // [settings] end

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
        // [settings] begin
        flows.Dialogs()
        // [settings] end
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
            // [settings] begin
            Screen.Settings -> SettingsScreen(
                m = SettingsModel(prefs.language, Repo.theme(state), Repo.sound(state), Repo.remindAgain(state), Repo.weekStart(state), Repo.arabicMonths(state),
                    essentialOff = (health ?: Health.check(this)).let { h -> listOf(h.notifications, h.channel, h.exact).count { !it } },
                    trashCount = state.tasks.count { it.deletedAt != null }, presets = Repo.presets(state),
                    prayerUsed = state.tasks.any { it.active && com.fastkiwi.rememberme.core.AlertExt.of(it).prayer != null }),
                safety = safety, flows = flows, tick = tick,
                cb = com.fastkiwi.rememberme.ui.SettingsCallbacks(
                    onSetting = { key, value -> act { s -> s.withSettings(JsonObject(s.settings + (key to value))) } },
                    onLanguage = { prefs.language = it; L10n.apply(this); Notifier.channels(this); Engine.syncAsync(this) },
                    onMonths = { v ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            Repo.change(ctx) { s -> s.withSettings(JsonObject(s.settings + ("arabicMonths" to JsonPrimitive(v)))) }
                            withContext(Dispatchers.Main) { L10n.apply(this@MainActivity) }
                        }
                    },
                    onPhoneChanged = { tick = System.currentTimeMillis(); Engine.syncAsync(this) },
                    onHealth = { push(Screen.Background) },
                    onPickTone = { /* lead: open TonePickerSheet for "normal" / "alarm" defaults, then set tick */ },
                    onLists = { push(Screen.Lists) },
                    onTemplates = { /* lead: push the templates screen */ },
                    onClearHistory = { /* lead: clear the change history (F-072) */ },
                    onRestoreSafety = { f ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val ok = runCatching { Repo.store(ctx).restoreSafety(f) }.getOrDefault(false).also { Engine.sync(ctx) }
                            withContext(Dispatchers.Main) { toast(if (ok) R.string.restore_done else R.string.import_failed) }
                        }
                    },
                    onCompat = { reportText = report(state, now) },
                    onAlertLog = { /* lead: open the alert log (F-024) */ },
                    onTrash = { push(Screen.Trash) },
                    onAbout = { push(Screen.About) },
                    onBack = { pop() },
                ),
            )
            Screen.Lists -> {
                val filters = androidx.compose.runtime.remember { com.fastkiwi.rememberme.data.Filters(ctx) }
                var lists by androidx.compose.runtime.remember { mutableStateOf(filters.all()) }
                com.fastkiwi.rememberme.ui.settings.ListsScreen(
                    state = state, lists = lists, now = now, zone = zone, weekStart = Repo.weekStart(state),
                    onLists = { filters.save(it); lists = filters.all() },
                    onChange = { block -> act(block) },
                    onCategory = { /* lead: show Home filtered by this category */ pop() },
                    onFilter = { /* lead: show Home with this saved filter */ pop() },
                    onDone = { pop() },
                    onTrash = { push(Screen.Trash) },
                    onBack = { pop() },
                )
            }
            // [settings] end
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
