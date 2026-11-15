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
// [health] begin
import com.fastkiwi.rememberme.ui.health.AlarmTestScreen
import com.fastkiwi.rememberme.ui.health.AlertLogScreen
import com.fastkiwi.rememberme.ui.health.CompatReportScreen
import com.fastkiwi.rememberme.ui.health.DndHelpScreen
import com.fastkiwi.rememberme.ui.health.HealthFix
import com.fastkiwi.rememberme.ui.health.TestResults
// [health] end
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
    // [quick] begin
    data object Templates : Screen()
    // [quick] end
    // [editor] begin
    /** A reminder's change history (F-072). */
    data class History(val id: String) : Screen()
    // [editor] end
    // [settings] begin
    data object Lists : Screen()
    // [settings] end
    // [health] begin
    data object DndHelp : Screen()
    data object CompatReport : Screen()
    data object AlertLog : Screen()
    // [health] end

    /** For the saved instance state, so a rotation or a language change keeps the open screen. */
    fun code(): String = when (this) {
        Home -> "home"
        is Edit -> "edit:" + (id ?: "")
        Settings -> "settings"
        Background -> "background"
        Trash -> "trash"
        About -> "about"
        AlarmTest -> "alarmtest"
        // [quick] begin
        Templates -> "templates"
        // [quick] end
        // [editor] begin
        is History -> "history:$id"
        // [editor] end
        // [settings] begin
        Lists -> "lists"
        // [settings] end
        // [health] begin
        DndHelp -> "dndhelp"
        CompatReport -> "compatreport"
        AlertLog -> "alertlog"
        // [health] end
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
            // [quick] begin
            code == "templates" -> Templates
            // [quick] end
            // [editor] begin
            code.startsWith("history:") -> History(code.removePrefix("history:"))
            // [editor] end
            // [settings] begin
            code == "lists" -> Lists
            // [settings] end
            // [health] begin
            code == "dndhelp" -> DndHelp
            code == "compatreport" -> CompatReport
            code == "alertlog" -> AlertLog
            // [health] end
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
    /** The permissions screen was opened by the first start (it then has «متابعة»). */
    private var onboarding by mutableStateOf(false)
    // [quick] begin
    /** The open Quick Add sheet (FAB, shortcut, widget «+», tile, share), or null. */
    var quickAdd by mutableStateOf<com.fastkiwi.rememberme.ui.quick.QuickAddRequest?>(null)
    /** Fields for the editor from a template or Quick Add's «خيارات أكثر»; the lead passes it to EditScreen. */
    var editPrefill by mutableStateOf<com.fastkiwi.rememberme.core.TemplateFill?>(null)
    // [lead] begin
    /** The default tone being chosen from Settings: "normal", "alarm", or null. */
    private var tonePickFor by mutableStateOf<String?>(null)
    /** Lists and tags: Home filtered by a saved filter or searched for a category. */
    private var homeRequest by mutableStateOf<com.fastkiwi.rememberme.ui.HomeRequest?>(null)
    // [lead] end
    /** Bumped by the launcher shortcut «اليوم»; the lead routes it to Home's day/agenda view. */
    var todayRequest by mutableLongStateOf(0L)
    /** The reminder Quick Add just saved, for Home to scroll into view. */
    var lastAddedId by mutableStateOf<String?>(null)
    /** Opens Quick Add (Home's FAB calls this). */
    fun openQuickAdd(text: String = "") { quickAdd = com.fastkiwi.rememberme.ui.quick.QuickAddRequest(text) }
    // [quick] end

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
        // [editor] begin
        com.fastkiwi.rememberme.data.EditorPrefs.install(this)
        // [editor] end
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
            // [quick] begin
            com.fastkiwi.rememberme.ui.quick.QuickEntry.request(intent) != null -> {
                stack = listOf(Screen.Home)
                quickAdd = com.fastkiwi.rememberme.ui.quick.QuickEntry.request(intent)
            }
            com.fastkiwi.rememberme.ui.quick.QuickEntry.isToday(intent) -> { stack = listOf(Screen.Home); todayRequest = System.currentTimeMillis() }
            // [quick] end
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

    // [health] begin
    private fun testResults(rec: TestAlarm.Record, now: Instant): Map<TestAlarm.Part, TestAlarm.Result> =
        TestResults.of(rec, health ?: Health.check(this), now.toEpochMilli())

    /** A Health or alert-test fix: Health's own system screens first, then the shared ones (and the permission request). */
    private fun healthFix(what: String) {
        if (what == "dnd_help") { push(Screen.DndHelp); return }
        if (!HealthFix.open(this, what)) fix(what)
    }
    // [health] end

    private fun report(state: AppState, now: Instant): String {
        val h = Health.check(this)
        val rec = TestAlarm.record(this)
        return CompatReport.build(this, h, scheduleStatus(state, now), rec, testResults(rec, now))
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()

    fun push(s: Screen) { stack = stack + s }
    private fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else finish() }

    /** Data changes run off the main thread; the screen follows the store's state flow. */
    /** A change from a screen, saved off the main thread; a failed save (disk full) is said, never silently lost. */
    private fun act(block: (AppState) -> AppState) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { Repo.change(this@MainActivity, block) }.isSuccess
            if (!ok) withContext(Dispatchers.Main) { toast(R.string.change_not_saved) }
        }
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
        // [quick] begin
        quickAdd?.let { req ->
            com.fastkiwi.rememberme.ui.quick.QuickAddHost(
                request = req, weekStart = Repo.weekStart(state),
                onDismiss = { if (quickAdd == req) quickAdd = null },
                onSaved = { id ->
                    lastAddedId = id
                    homeSnack = SnackRequest(getString(R.string.added_msg)) {
                        lifecycleScope.launch(Dispatchers.IO) { runCatching { com.fastkiwi.rememberme.ui.quick.QuickAddSave.undo(ctx, id) } }
                    }
                },
                onMore = { input -> editPrefill = com.fastkiwi.rememberme.core.TemplateFill(input); push(Screen.Edit(null)) },
                onTemplate = { push(Screen.Templates) },
            )
        }
        // [quick] end
        // [settings] begin
        flows.Dialogs()
        // [settings] end
        // [lead] the default tones from Settings (F-014): the editor's tone sheet, for a style.
        tonePickFor?.let { which ->
            val alarm = which == "alarm"
            com.fastkiwi.rememberme.ui.editor.TonePickerSheet(
                current = if (alarm) prefs.importantTone else prefs.normalTone,
                style = if (alarm) com.fastkiwi.rememberme.core.AlertPolicy.Style.ALARM else com.fastkiwi.rememberme.core.AlertPolicy.Style.NORMAL,
                onPick = { t -> if (alarm) prefs.importantTone = t else prefs.normalTone = t; tonePickFor = null; tick = System.currentTimeMillis() },
                onDismiss = { tonePickFor = null },
                title = stringResource(if (alarm) R.string.set_default_tone_alarm else R.string.set_default_tone_normal),
                subtitle = null,
                defaultTone = null,
                defaultLabel = stringResource(if (alarm) R.string.set_tone_phone_alarm else R.string.set_tone_phone_notification),
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
                            runCatching { Repo.change(ctx) { s -> Tasks.complete(s, t.id, Instant.now(), zone, t.dueAt, checked = true)?.also { done = it.second }?.first ?: s } }
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
                    // [lead] calendar views (F-064), Quick Add (F-042), shortcuts (F-122)
                    hijri = prefs.hijriDisplay, hijriAdjust = prefs.hijriAdjust,
                    onQuickAdd = { openQuickAdd() },
                    onNewOnDay = { day ->
                        val at = day.atTime(9, 0).atZone(zone).toInstant()
                        editPrefill = com.fastkiwi.rememberme.core.TemplateFill(com.fastkiwi.rememberme.core.TaskInput(title = "", dueAt = if (at.isAfter(Instant.now())) at else null))
                        push(Screen.Edit(null))
                    },
                    todayRequest = todayRequest,
                    scrollToId = lastAddedId, onScrolled = { lastAddedId = null },
                    request = homeRequest, onRequestShown = { homeRequest = null },
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
                    // [editor] begin
                    initial = if (screen.id == null) editPrefill?.let { f -> f.input.copy(ext = if (f.ext.isEmpty) f.input.ext else f.ext) } else null,
                    onDuplicate = { input -> editPrefill = com.fastkiwi.rememberme.core.TemplateFill(input); push(Screen.Edit(null)) },
                    // [lead] «حفظ كقالب» (F-047): named after the title; renamed from «قوالبي» by a long press.
                    onSaveAsTemplate = { input ->
                        com.fastkiwi.rememberme.data.saveUserTemplate(ctx, input, input.title.ifBlank { getString(R.string.app_name) }, input.ext)
                        Toast.makeText(ctx, getString(R.string.template_saved), Toast.LENGTH_SHORT).show()
                    },
                    onHistory = { t -> push(Screen.History(t.id)) },
                    onDndHelp = { push(Screen.DndHelp) },
                    // [editor] end
                )
                // [editor] begin: the editor keeps the prefill in its saved state; the next new reminder starts empty.
                if (screen.id == null && editPrefill != null) LaunchedEffect(screen) { editPrefill = null }
                // [editor] end
            }
            // [editor] begin
            is Screen.History -> {
                val task = state.task(screen.id)
                com.fastkiwi.rememberme.ui.editor.HistoryScreen(
                    title = task?.title.orEmpty(), events = com.fastkiwi.rememberme.data.History.of(this).events(screen.id),
                    now = now, zone = zone, onBack = { pop() },
                )
            }
            // [editor] end
            // [settings] begin
            Screen.Settings -> SettingsScreen(
                m = SettingsModel(prefs.language, Repo.theme(state), Repo.sound(state), Repo.remindAgain(state), Repo.weekStart(state), Repo.arabicMonths(state),
                    essentialOff = com.fastkiwi.rememberme.ui.health.HealthModel.essentialMissing(health ?: Health.check(this), scheduleStatus(state, now)),
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
                    onPickTone = { style -> tonePickFor = style },
                    onLists = { push(Screen.Lists) },
                    onTemplates = { push(Screen.Templates) },
                    onClearHistory = { com.fastkiwi.rememberme.data.History.of(ctx).clear(); toast(R.string.history_cleared) },
                    onRestoreSafety = { f ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val ok = runCatching { Repo.store(ctx).restoreSafety(f) }.getOrDefault(false).also { Engine.sync(ctx) }
                            withContext(Dispatchers.Main) { toast(if (ok) R.string.restore_done else R.string.import_failed) }
                        }
                    },
                    onCompat = { push(Screen.CompatReport) },
                    onAlertLog = { push(Screen.AlertLog) },
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
                    onCategory = { c -> homeRequest = com.fastkiwi.rememberme.ui.HomeRequest(search = getString(when (c) {
                        com.fastkiwi.rememberme.core.Category.PERSONAL -> R.string.cat_personal
                        com.fastkiwi.rememberme.core.Category.WORK -> R.string.cat_work
                        com.fastkiwi.rememberme.core.Category.STUDY -> R.string.cat_study })); stack = listOf(Screen.Home) },
                    onFilter = { f -> homeRequest = com.fastkiwi.rememberme.ui.HomeRequest(filter = f.id); stack = listOf(Screen.Home) },
                    onDone = { pop() },
                    onTrash = { push(Screen.Trash) },
                    onBack = { pop() },
                )
            }
            // [settings] end
            // [health] begin
            Screen.Background -> HealthScreen(
                health ?: Health.check(this), scheduleStatus(state, now), firstRun = onboarding, now = now,
                onCheck = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { Engine.sync(ctx, reason = "check") }
                        withContext(Dispatchers.Main) { refreshHealth(); tick = System.currentTimeMillis() }
                    }
                },
                onTest = { push(Screen.AlarmTest) },
                onReport = { push(Screen.CompatReport) },
                onAlertLog = { push(Screen.AlertLog) },
                onDndHelp = { push(Screen.DndHelp) },
                onFix = ::healthFix, onContinue = { onboarding = false; pop() }, onBack = { onboarding = false; pop() },
                onResumeCheck = { refreshHealth() },
            )
            Screen.AlarmTest -> {
                // While a test runs, refresh every second so the countdown and each part's result appear as they happen.
                LaunchedEffect(Unit) { while (true) { delay(1000); tick = System.currentTimeMillis() } }
                val rec = TestAlarm.record(this)
                AlarmTestScreen(rec, testResults(rec, now), now.toEpochMilli(),
                    onStart = { style, wait -> TestAlarm.start(ctx, Instant.now(), style, wait, zone); refreshHealth(); tick = System.currentTimeMillis() },
                    onAnswer = { part, yes ->
                        if (part == TestAlarm.Part.SOUND) TestAlarm.answer(ctx, sound = yes) else TestAlarm.answer(ctx, vibration = yes)
                        tick = System.currentTimeMillis()
                    },
                    onFix = ::healthFix,
                    onCancel = { TestAlarm.cancel(ctx); tick = System.currentTimeMillis() },
                    onDone = { pop() }, onBack = { pop() })
            }
            Screen.DndHelp -> (health ?: Health.check(this)).let { h -> DndHelpScreen(h.dndState, h.dndAlarmsAllowed, onBack = { pop() }) }
            Screen.CompatReport -> CompatReportScreen(report(state, now), onBack = { pop() })
            Screen.AlertLog -> {
                var entries by androidx.compose.runtime.remember { mutableStateOf(com.fastkiwi.rememberme.data.AlertLog(ctx).entries()) }
                AlertLogScreen(entries, onClear = { com.fastkiwi.rememberme.data.AlertLog(ctx).clear(); entries = emptyList() }, onBack = { pop() }, now = now)
            }
            // [health] end
            Screen.Trash -> TrashScreen(state, now,
                onRestore = { t -> act { s -> Tasks.restore(s, t.id) } },
                onPurge = { t -> act { s -> Tasks.purge(s, t.id) } },
                onEmpty = { act { s -> Tasks.purgeTrash(s, Instant.now(), all = true) } },
                onBack = { pop() })
            Screen.About -> AboutScreen { pop() }
            // [quick] begin
            Screen.Templates -> com.fastkiwi.rememberme.ui.quick.TemplatesRoute(
                onPick = { fill -> editPrefill = fill; stack = stack.dropLast(1) + Screen.Edit(null) },
                onBack = { pop() },
            )
            // [quick] end
        }
    }

    companion object {
        const val ACTION_NEW = "com.fastkiwi.rememberme.NEW"
        const val EXTRA_OPEN = "open"
        private const val KEY_STACK = "stack"
    }
}
