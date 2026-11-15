package com.fastkiwi.rememberme.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.stateDescription
import com.fastkiwi.rememberme.data.SettingsPrefs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.BuildConfig
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Task
import java.io.File
import java.time.Instant

/** A pushed screen: back arrow, title, scrolling content, and an optional bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), onBack) },
                actions = { actions() },
            )
        },
        bottomBar = bottomBar,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.xl), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            content()
        }
    }
}

/** What Settings shows that comes from outside it (data.json settings and the phone's checks). */
data class SettingsModel(
    val language: String, val theme: String, val sound: Boolean, val remindAgain: Int, val weekStart: Int,
    val arabicMonths: String, val essentialOff: Int, val trashCount: Int,
    val presets: List<com.fastkiwi.rememberme.core.SnoozePreset> = com.fastkiwi.rememberme.core.SnoozePreset.DEFAULTS,
    /** Some reminder is prayer-relative (the prayer group shows then, or once set up). */
    val prayerUsed: Boolean = false,
)

/** What Settings asks of the activity (DESIGN-SPEC §5.14). Phone-only settings are written here directly. */
class SettingsCallbacks(
    /** A shared setting in data.json (the same as on Windows): theme, sound, remindAgain, weekStart, snoozePresets. */
    val onSetting: (String, kotlinx.serialization.json.JsonElement) -> Unit = { _, _ -> },
    val onLanguage: (String) -> Unit = {},
    val onMonths: (String) -> Unit = {},
    /** A phone setting the engine reads changed (full screen, pin, lock screen, ring, vibration, summary…): re-plan. */
    val onPhoneChanged: () -> Unit = {},
    val onHealth: () -> Unit = {},
    /** "normal" or "alarm": opens the tone sheet for that default. */
    val onPickTone: (String) -> Unit = {},
    val onLists: () -> Unit = {},
    val onTemplates: () -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onRestoreSafety: (File) -> Unit = {},
    val onCompat: () -> Unit = {},
    val onAlertLog: () -> Unit = {},
    val onTrash: () -> Unit = {},
    val onAbout: () -> Unit = {},
    /** 1.0.5: «أبلغنا عن مشكلة أو اقتراح». */
    val onFeedback: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** The phone-only values, read again whenever something may have changed them. */
private data class PhoneValues(
    val normalTone: com.fastkiwi.rememberme.core.ToneRef?, val importantTone: com.fastkiwi.rememberme.core.ToneRef?,
    val importantStyle: String, val fullScreen: String, val ringSeconds: Int, val vibrate: Boolean, val pattern: String,
    val lockPrivacy: Boolean, val pin: Boolean, val hijri: Boolean, val hijriAdjust: Int, val swipe: Boolean,
    val prayerMethod: String, val prayerAsr: String, val prayerCity: String, val summary: Int, val history: Boolean,
    val autoComplete: Boolean, val encrypt: Boolean, val backupTones: Boolean, val autoBackup: String, val lastAutoBackup: Long, val autoBackupFailed: Boolean,
) {
    companion object {
        fun read(c: Context): PhoneValues {
            val p = com.fastkiwi.rememberme.Repo.prefs(c)
            val s = SettingsPrefs(c)
            return PhoneValues(p.normalTone, p.importantTone, s.importantStyle, p.fullScreenMode, p.ringSeconds, p.vibrateDefault, p.vibrationPattern,
                p.lockPrivacy, p.pinDefault, p.hijriDisplay, p.hijriAdjust, s.swipeOn, p.prayerMethod, p.prayerAsr, p.prayerCity, p.summaryMinutes,
                p.historyOn, p.autoCompleteSteps, s.encryptBackups, s.backupTones, p.autoBackupUri, p.lastAutoBackup, s.autoBackupFailedAt > 0)
        }
    }
}

/** The keys whose row opens a single-choice (or picker) dialog of Settings itself. */
private val DIALOG_KEYS = setOf("language", "theme", "months", "week", "again", "important", "ring", "vibration", "lock",
    "hijri_adjust", "prayer_method", "prayer_asr", "summary_time", "history_clear", "safety")

/**
 * Settings 1.0.0 (DESIGN-SPEC §5.14): «صحة التذكيرات» alone at the top, then nine groups; values as supporting text,
 * switches for on/off, single-choice dialogs that apply at once; no primary button. The search icon turns the top
 * bar into a search field over a static index (title, value, group, synonyms); a result opens the row's own control
 * and highlights the row for 1 s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    m: SettingsModel,
    safety: List<File>,
    flows: com.fastkiwi.rememberme.ui.settings.SettingsFlows?,
    cb: SettingsCallbacks,
    tick: Long = 0,
) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var bump by remember { mutableIntStateOf(0) }
    val v = remember(bump, tick, flows?.version) { PhoneValues.read(context) }
    fun changed(engine: Boolean = true) { bump++; if (engine) cb.onPhoneChanged() }
    val prefs = com.fastkiwi.rememberme.Repo.prefs(context)
    val updatePrefs = remember { com.fastkiwi.rememberme.update.UpdatePrefs(context) }
    var updAuto by remember { mutableStateOf(updatePrefs.autoUpdateCheck) }
    var updWifi by remember { mutableStateOf(updatePrefs.updateWifiOnly) }
    val sp = remember { SettingsPrefs(context) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var highlight by remember { mutableStateOf<String?>(null) }
    var safetyPick by remember { mutableStateOf<File?>(null) }
    val scroll = rememberScrollState()
    val tops = remember { HashMap<String, Float>() }
    var columnTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // Sub-pages of Settings (Back returns to Settings).
    when (sub) {
        "snooze" -> {
            BackHandler { sub = null }
            com.fastkiwi.rememberme.ui.settings.SnoozeEditorScreen(m.presets, { cb.onSetting("snoozePresets", com.fastkiwi.rememberme.ui.settings.SnoozeOptions.toJson(it)) }, { sub = null })
            return
        }
        "city" -> {
            BackHandler { sub = null }
            com.fastkiwi.rememberme.ui.settings.CityPickerScreen(v.prayerCity, { prefs.prayerCity = it; changed(); sub = null }, { sub = null })
            return
        }
        "privacy" -> { BackHandler { sub = null }; com.fastkiwi.rememberme.ui.settings.PrivacyScreen { sub = null }; return }
        "licences" -> { BackHandler { sub = null }; com.fastkiwi.rememberme.ui.settings.LicencesScreen { sub = null }; return }
    }

    val languages = listOf("ar" to "العربية", "en" to "English")
    val themes = listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark))
    val months = listOf("auto" to stringResource(R.string.months_auto), "levant" to "تشرين الثاني", "standard" to "نوفمبر")
    val weekStarts = listOf(6, 0, 1).map { it.toString() to L10n.weekdayName(context, it) }
    val again = listOf(0, 2, 5, 15).map {
        it.toString() to if (it == 0) stringResource(R.string.off)
        else stringResource(R.string.set_again_value, L10n.duration(context, it.toLong()), com.fastkiwi.rememberme.core.Planner.MAX_AGAIN.toString())
    }
    val styles = listOf("alarm" to stringResource(R.string.set_style_alarm), "normal" to stringResource(R.string.set_style_normal))
    val styleHints = mapOf("alarm" to stringResource(R.string.set_style_alarm_hint), "normal" to stringResource(R.string.set_style_normal_hint))
    val rings = listOf(30, 60, 120).map { it.toString() to L10n.duration(context, it / 60L).let { d -> if (it == 30) stringResource(R.string.set_ring_30) else d } }
    val vibrations = listOf("short" to stringResource(R.string.set_vib_short), "normal" to stringResource(R.string.set_vib_normal), "long" to stringResource(R.string.set_vib_long), "off" to stringResource(R.string.set_vib_off))
    val locks = listOf("show" to stringResource(R.string.set_lock_show), "hide" to stringResource(R.string.set_lock_hide))
    val adjusts = (-2..2).map { it.toString() to com.fastkiwi.rememberme.ui.settings.hijriAdjustLabel(context, it) }
    val methods = com.fastkiwi.rememberme.ui.settings.PrayerNames.methods(context)
    val asrs = com.fastkiwi.rememberme.ui.settings.PrayerNames.asr(context)

    fun tone(t: com.fastkiwi.rememberme.core.ToneRef?, alarm: Boolean): String = when (t?.kind) {
        null, "default" -> if (com.fastkiwi.rememberme.data.Tones.defaultIsBundled(context,
                if (alarm) com.fastkiwi.rememberme.core.AlertPolicy.Style.ALARM else com.fastkiwi.rememberme.core.AlertPolicy.Style.NORMAL))
            res.getString(R.string.tone_bundled_default) else res.getString(if (alarm) R.string.set_tone_phone_alarm else R.string.set_tone_phone_notification)
        "silent" -> res.getString(R.string.set_tone_silent)
        "bundled" -> res.getString(R.string.set_tone_bundled, t.ref.removePrefix("chime-"))
        else -> t.title.ifEmpty { res.getString(if (alarm) R.string.set_tone_phone_alarm else R.string.set_tone_phone_notification) }
    }
    val vibrationCode = if (!v.vibrate) "off" else v.pattern
    val prayerShown = m.prayerUsed || v.prayerMethod.isNotEmpty() || v.prayerCity.isNotEmpty()
    val autoBackupText = when {
        v.autoBackup.isEmpty() -> stringResource(R.string.off)
        v.autoBackupFailed -> stringResource(R.string.set_auto_backup_failed)
        else -> stringResource(R.string.set_auto_backup_on, com.fastkiwi.rememberme.data.AutoBackup.folderName(context) ?: "…")
    }
    // Current values, for the rows and for search ("Matches the row title, its value…").
    val values: Map<String, String?> = mapOf(
        "health" to if (m.essentialOff > 0) L10n.plural(context, R.plurals.essential_off, m.essentialOff.toLong()) else stringResource(R.string.health_ok),
        "tone_normal" to tone(v.normalTone, false),
        "tone_alarm" to tone(v.importantTone, true),
        "important" to (styles.first { it.first == v.importantStyle }.second + stringResource(R.string.list_separator) + styleHints.getValue(v.importantStyle)),
        // 1.0.5: a switch; on means «للتنبيهات المهمة فقط» (there is no mode for every reminder).
        "fullscreen" to stringResource(if (v.fullScreen == "off") R.string.set_fs_off_hint else R.string.set_fs_important_hint),
        "ring" to rings.first { it.first == v.ringSeconds.toString() }.second,
        "snooze" to com.fastkiwi.rememberme.ui.settings.SnoozeOptions.summary(context, m.presets),
        "again" to again.first { it.first == m.remindAgain.toString() }.second,
        "vibration" to vibrations.first { it.first == vibrationCode }.second,
        "sound" to stringResource(R.string.sound_hint),
        "lock" to locks.first { it.first == if (v.lockPrivacy) "hide" else "show" }.second,
        "pin" to stringResource(R.string.set_pin_hint),
        "language" to languages.first { it.first == m.language }.second,
        "theme" to themes.first { it.first == m.theme }.second,
        "months" to months.first { it.first == m.arabicMonths }.second,
        "week" to weekStarts.first { it.first == m.weekStart.toString() }.second,
        "hijri" to stringResource(R.string.set_hijri_hint),
        "hijri_adjust" to adjusts.first { it.first == v.hijriAdjust.toString() }.second,
        "swipe" to stringResource(R.string.set_swipe_hint),
        "prayer_method" to (methods.firstOrNull { it.first == v.prayerMethod }?.second ?: stringResource(R.string.set_not_chosen)),
        "prayer_asr" to (asrs.firstOrNull { it.first == v.prayerAsr }?.second ?: asrs.first().second),
        "prayer_city" to (SettingsPrefs.place(v.prayerCity)?.let { if (m.language == "en") it.nameEn else it.nameAr } ?: stringResource(R.string.set_not_chosen)),
        "summary" to if (v.summary < 0) stringResource(R.string.off) else stringResource(R.string.set_summary_hint),
        "summary_time" to com.fastkiwi.rememberme.ui.settings.SnoozeOptions.clock(context, java.time.LocalTime.of(maxOf(v.summary, 0) / 60 % 24, maxOf(v.summary, 0) % 60)),
        "history" to stringResource(R.string.set_history_hint),
        "autocomplete" to null,
        "export" to stringResource(if (v.encrypt) R.string.set_export_hint_encrypted else R.string.export_hint),
        "auto_backup" to autoBackupText,
        "encrypt" to stringResource(R.string.set_encrypt_hint),
        "backup_tones" to stringResource(R.string.set_backup_tones_hint),
        "ics_import" to stringResource(R.string.set_ics_import_hint),
        "ics_export" to stringResource(R.string.export_ics_hint),
        "about" to stringResource(R.string.version_text, BuildConfig.VERSION_NAME),
        "feedback" to stringResource(R.string.feedback_settings_hint),
        "update_auto" to stringResource(R.string.update_auto_hint),
        "update_wifi" to stringResource(R.string.update_wifi_only_hint),
    )
    val hidden = buildSet {
        if (m.language != "ar") add("months")
        if (!v.hijri) add("hijri_adjust")
        if (!prayerShown) addAll(listOf("prayer_method", "prayer_asr", "prayer_city"))
        if (v.summary < 0) add("summary_time")
        if (safety.isEmpty()) add("safety")
        if (flows == null) addAll(listOf("export", "auto_backup", "encrypt", "backup_tones", "restore", "ics_import", "ics_export", "delete_all"))
    }

    /** Opens a row's own control: its dialog, its screen, or (switches) nothing more than the highlight. */
    fun open(key: String) {
        when {
            com.fastkiwi.rememberme.ui.settings.SettingsIndex.isHealth(key) -> cb.onHealth()
            key in DIALOG_KEYS -> dialog = key
            key == "tone_normal" -> cb.onPickTone("normal")
            key == "tone_alarm" -> cb.onPickTone("alarm")
            key == "snooze" || key == "privacy" || key == "licences" -> sub = key
            key == "prayer_city" -> sub = "city"
            key == "lists" -> cb.onLists()
            key == "templates" -> cb.onTemplates()
            key == "export" -> flows?.export()
            key == "auto_backup" -> flows?.autoBackup()
            key == "restore" -> flows?.restore()
            key == "ics_import" -> flows?.importIcs()
            key == "ics_export" -> flows?.exportIcs()
            key == "compat" -> cb.onCompat()
            key == "alert_log" -> cb.onAlertLog()
            key == "trash" -> cb.onTrash()
            key == "delete_all" -> flows?.deleteAll()
            key == "about" -> cb.onAbout()
            key == "feedback" -> cb.onFeedback()
        }
    }

    val icons: Map<String, ImageVector> = mapOf(
        "tone_normal" to Ms.MusicNote, "tone_alarm" to Ms.MusicNote, "important" to Ms.Alarm, "fullscreen" to Ms.Fullscreen,
        "ring" to Ms.Schedule, "snooze" to Ms.Snooze, "again" to Ms.NotificationsActive, "vibration" to Ms.Vibration, "sound" to Ms.VolumeUp,
        "lock" to Ms.VisibilityOff, "pin" to Ms.PushPin, "language" to Ms.Language, "theme" to Ms.Contrast, "months" to Ms.CalendarMonth,
        "week" to Ms.DateRange, "hijri" to Ms.CalendarMonth, "hijri_adjust" to Ms.Tune, "swipe" to Ms.Swipe, "prayer_method" to Ms.Mosque,
        "prayer_asr" to Ms.Mosque, "prayer_city" to Ms.Mosque, "summary" to Ms.WbTwilight, "summary_time" to Ms.Schedule,
        "lists" to Ms.ListIcon, "templates" to Ms.DashboardCustomize, "history" to Ms.History, "history_clear" to Ms.Delete,
        "autocomplete" to Ms.Checklist, "export" to Ms.Download, "auto_backup" to Ms.Backup, "encrypt" to Ms.Lock, "backup_tones" to Ms.LibraryMusic,
        "restore" to Ms.SettingsBackupRestore, "safety" to Ms.History, "ics_import" to Ms.Upload, "ics_export" to Ms.Event,
        "compat" to Ms.ReceiptLong, "alert_log" to Ms.Notes, "trash" to Ms.Delete, "delete_all" to Ms.Delete, "privacy" to Ms.VerifiedUser,
        "licences" to Ms.Info, "about" to Ms.Info, "feedback" to Ms.Feedback, "update_auto" to Ms.SystemUpdate, "update_wifi" to Ms.Download, "health" to Ms.HealthAndSafety,
    )

    LaunchedEffect(highlight) {
        val key = highlight ?: return@LaunchedEffect
        // Wait one frame so the rows are laid out, then bring the row into view and keep it highlighted for 1 s.
        kotlinx.coroutines.delay(50)
        tops[key]?.let { y -> scroll.animateScrollTo((scroll.value + (y - columnTop) - 200f).toInt().coerceAtLeast(0)) }
        kotlinx.coroutines.delay(1000)
        highlight = null
    }

    @Composable
    fun Tracked(key: String, content: @Composable () -> Unit) {
        val hl = highlight == key
        Box(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { tops[key] = it.positionInRoot().y }
                .then(if (hl) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                .semantics { if (hl) stateDescription = res.getString(R.string.set_found) },
        ) { content() }
    }

    @Composable
    fun row(key: String, title: String, onClick: () -> Unit = { open(key) }, trailing: @Composable (() -> Unit)? = null, error: Boolean = false) =
        Tracked(key) {
            com.fastkiwi.rememberme.ui.settings.ValueRow(icons[key], title, values[key], onClick, titleColor = if (error) MaterialTheme.colorScheme.error else null, trailing = trailing)
        }

    @Composable
    fun switch(key: String, title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Tracked(key) { SwitchRow(icons[key], title, values[key], checked, onChange) }

    Scaffold(
        topBar = {
            if (searching) {
                com.fastkiwi.rememberme.ui.settings.SettingsSearchField(query, { query = it }, { searching = false; query = "" })
            } else TopAppBar(
                title = { Text(stringResource(R.string.settings), modifier = Modifier.semantics { heading() }) },
                navigationIcon = { TooltipIconButton(Ms.ArrowBack, stringResource(R.string.back), cb.onBack) },
                actions = { TooltipIconButton(Ms.Search, stringResource(R.string.search), { searching = true }) },
            )
        },
    ) { pad ->
        if (searching) {
            BackHandler { searching = false; query = "" }
            val hits = com.fastkiwi.rememberme.ui.settings.SettingsIndex.search(query, { res.getString(it) }, { values[it] }, hidden)
            com.fastkiwi.rememberme.ui.settings.SettingsSearchResults(Modifier.padding(pad), query, hits, { icons[it] ?: Ms.Settings }) { key ->
                searching = false
                query = ""
                highlight = key
                open(key)
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().onGloballyPositioned { columnTop = it.positionInRoot().y }
                .verticalScroll(scroll).padding(bottom = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.xs),
        ) {
            Spacer(Modifier.size(Dimens.s))
            // «صحة التذكيرات», alone at the top.
            val off = m.essentialOff > 0
            GroupItem(0, 1, if (off) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Tracked("health") {
                    com.fastkiwi.rememberme.ui.settings.ValueRow(Ms.HealthAndSafety, stringResource(R.string.health_title), values["health"], cb.onHealth,
                        valueIcon = if (off) Ms.Error else Ms.CheckCircle) {
                        Icon(Ms.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SectionHeader(stringResource(R.string.section_alerts))
            Group(listOf(
                { row("tone_normal", stringResource(R.string.set_default_tone_normal)) },
                { row("tone_alarm", stringResource(R.string.set_default_tone_alarm)) },
                { row("important", stringResource(R.string.set_important_reminders)) },
                { switch("fullscreen", stringResource(R.string.set_full_screen), v.fullScreen != "off") { prefs.fullScreenMode = if (it) "important" else "off"; changed() } },
                { row("ring", stringResource(R.string.set_ring_length)) },
                { row("snooze", stringResource(R.string.set_snooze_presets)) },
                { row("again", stringResource(R.string.remind_again)) },
                { row("vibration", stringResource(R.string.set_vibration)) },
                { switch("sound", stringResource(R.string.sound), m.sound) { cb.onSetting("sound", kotlinx.serialization.json.JsonPrimitive(it)) } },
            ))
            SectionHeader(stringResource(R.string.set_section_lock))
            Group(listOf(
                { row("lock", stringResource(R.string.set_lock_screen)) },
                { switch("pin", stringResource(R.string.set_pin), v.pin) { prefs.pinDefault = it; changed() } },
            ))
            SectionHeader(stringResource(R.string.section_display))
            Group(buildList {
                add { row("language", stringResource(R.string.language)) }
                add { row("theme", stringResource(R.string.theme)) }
                if ("months" !in hidden) add { row("months", stringResource(R.string.month_names)) }
                add { row("week", stringResource(R.string.week_start)) }
                add { switch("hijri", stringResource(R.string.set_hijri), v.hijri) { prefs.hijriDisplay = it; changed() } }
                if (v.hijri) add { row("hijri_adjust", stringResource(R.string.set_hijri_adjust)) }
                add { switch("swipe", stringResource(R.string.set_swipe), v.swipe) { sp.swipeOn = it; changed(engine = false) } }
            })
            if (prayerShown) {
                SectionHeader(stringResource(R.string.set_section_prayer))
                Group(listOf(
                    { row("prayer_method", stringResource(R.string.set_prayer_method)) },
                    { row("prayer_asr", stringResource(R.string.set_prayer_asr)) },
                    { row("prayer_city", stringResource(R.string.set_prayer_city)) },
                ))
            }
            SectionHeader(stringResource(R.string.set_section_summary))
            Group(buildList {
                add {
                    switch("summary", stringResource(R.string.set_section_summary), v.summary >= 0) {
                        prefs.summaryMinutes = if (it) 8 * 60 else -1; changed()
                    }
                }
                if (v.summary >= 0) add { row("summary_time", stringResource(R.string.set_summary_time)) }
            })
            SectionHeader(stringResource(R.string.set_section_organise))
            Group(listOf(
                { row("lists", stringResource(R.string.set_lists_title)) },
                { row("templates", stringResource(R.string.set_templates)) },
                { switch("history", stringResource(R.string.set_history), v.history) { prefs.historyOn = it; changed(engine = false) } },
                { row("history_clear", stringResource(R.string.set_history_clear)) },
                { switch("autocomplete", stringResource(R.string.set_autocomplete), v.autoComplete) { prefs.autoCompleteSteps = it; changed(engine = false) } },
            ))
            SectionHeader(stringResource(R.string.section_data))
            Group(buildList {
                if (flows != null) {
                    add { row("export", stringResource(R.string.export)) }
                    add { row("auto_backup", stringResource(R.string.set_auto_backup)) }
                    add { switch("encrypt", stringResource(R.string.set_encrypt), v.encrypt) { flows.setEncrypt(it) } }
                    add { switch("backup_tones", stringResource(R.string.set_backup_tones), v.backupTones) { flows.setBackupTones(it) } }
                    add { row("restore", stringResource(R.string.import_)) }
                }
                if (safety.isNotEmpty()) add { row("safety", stringResource(R.string.set_safety_copies)) }
                if (flows != null) {
                    add { row("ics_import", stringResource(R.string.set_ics_import)) }
                    add { row("ics_export", stringResource(R.string.set_ics_export)) }
                }
                add { row("compat", stringResource(R.string.compat_title)) }
                add { row("alert_log", stringResource(R.string.set_alert_log)) }
                add {
                    row("trash", stringResource(R.string.trash), trailing = {
                        if (m.trashCount > 0) Text(m.trashCount.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                }
                if (flows != null) add { row("delete_all", stringResource(R.string.set_delete_all), error = true) }
            })
            // 1.0.5 (D-081): the two update settings, both on by default.
            SectionHeader(stringResource(R.string.updates_title))
            Group(listOf(
                { switch("update_auto", stringResource(R.string.update_auto), updAuto) { updAuto = it; updatePrefs.autoUpdateCheck = it } },
                { switch("update_wifi", stringResource(R.string.update_wifi_only), updWifi) { updWifi = it; updatePrefs.updateWifiOnly = it } },
            ))
            SectionHeader(stringResource(R.string.section_about))
            Group(listOf(
                { row("privacy", stringResource(R.string.set_privacy)) },
                { row("licences", stringResource(R.string.licences)) },
                { row("feedback", stringResource(R.string.feedback_title)) },
                { row("about", stringResource(R.string.about)) },
            ))
        }
    }

    when (dialog) {
        "language" -> ChoiceDialog(stringResource(R.string.language), languages, m.language, cb.onLanguage, { dialog = null })
        "theme" -> ChoiceDialog(stringResource(R.string.theme), themes, m.theme, { cb.onSetting("theme", kotlinx.serialization.json.JsonPrimitive(it)) }, { dialog = null })
        "months" -> ChoiceDialog(stringResource(R.string.month_names), months, m.arabicMonths, cb.onMonths, { dialog = null })
        "week" -> ChoiceDialog(stringResource(R.string.week_start), weekStarts, m.weekStart.toString(), { cb.onSetting("weekStart", kotlinx.serialization.json.JsonPrimitive(it.toInt())) }, { dialog = null })
        // Only Windows' four values are ever written (F-201).
        "again" -> ChoiceDialog(stringResource(R.string.remind_again), again, m.remindAgain.toString(), {
            it.toInt().takeIf { n -> n in com.fastkiwi.rememberme.core.Planner.REMIND_AGAIN }?.let { n -> cb.onSetting("remindAgain", kotlinx.serialization.json.JsonPrimitive(n)) }
        }, { dialog = null })
        "important" -> ChoiceDialog(stringResource(R.string.set_important_reminders), styles, v.importantStyle, { sp.importantStyle = it; com.fastkiwi.rememberme.core.AlertPolicy.highIsAlarm = it != "normal"; changed() }, { dialog = null }, supporting = styleHints)
        "ring" -> com.fastkiwi.rememberme.ui.settings.ChoiceWithNote(stringResource(R.string.set_ring_length), rings, v.ringSeconds.toString(), { prefs.ringSeconds = it.toInt(); changed() }, { dialog = null },
            note = stringResource(R.string.set_ring_note))
        "vibration" -> ChoiceDialog(stringResource(R.string.set_vibration), vibrations, vibrationCode, {
            if (it == "off") prefs.vibrateDefault = false else { prefs.vibrateDefault = true; prefs.vibrationPattern = it }
            changed()
        }, { dialog = null })
        "lock" -> ChoiceDialog(stringResource(R.string.set_lock_screen), locks, if (v.lockPrivacy) "hide" else "show", { prefs.lockPrivacy = it == "hide"; changed() }, { dialog = null })
        "hijri_adjust" -> com.fastkiwi.rememberme.ui.settings.ChoiceWithNote(stringResource(R.string.set_hijri_adjust), adjusts, v.hijriAdjust.toString(), { prefs.hijriAdjust = it.toInt(); changed() }, { dialog = null },
            note = stringResource(R.string.set_hijri_note))
        "prayer_method" -> com.fastkiwi.rememberme.ui.settings.ChoiceWithNote(stringResource(R.string.set_prayer_method), methods, v.prayerMethod, { prefs.prayerMethod = it; changed() }, { dialog = null },
            note = stringResource(R.string.set_prayer_note))
        "prayer_asr" -> ChoiceDialog(stringResource(R.string.set_prayer_asr), asrs, v.prayerAsr, { prefs.prayerAsr = it; changed() }, { dialog = null })
        "summary_time" -> com.fastkiwi.rememberme.ui.settings.TimeDialog(stringResource(R.string.set_summary_time), maxOf(v.summary, 0), {
            prefs.summaryMinutes = it; changed()
        }, { dialog = null })
        "history_clear" -> ConfirmDialog(stringResource(R.string.set_history_clear), stringResource(R.string.set_history_clear_body), stringResource(R.string.set_history_clear),
            cb.onClearHistory, { dialog = null }, destructive = true)
        "safety" -> ChoiceDialog(
            stringResource(R.string.set_safety_copies),
            safety.map { it.name to L10n.safetyLabel(context, it) },
            "", { name -> safetyPick = safety.firstOrNull { it.name == name } }, { dialog = null },
        )
    }
    safetyPick?.let { f ->
        ConfirmDialog(stringResource(R.string.restore), stringResource(R.string.restore_safety_confirm), stringResource(R.string.restore),
            { cb.onRestoreSafety(f) }, { safetyPick = null })
    }
}

/** How a permission row looks: ready, needed (essential), off (recommended) or advice. */
enum class Status { READY, NEEDED, OFF, ADVICE }

@Composable
fun CheckRow(status: Status, icon: ImageVector? = null, title: String, text: String, action: String?, onClick: () -> Unit) {
    val (statusIcon, tint, label) = when (status) {
        Status.READY -> Triple(Ms.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.status_ok))
        Status.NEEDED -> Triple(Ms.Error, MaterialTheme.colorScheme.error, stringResource(R.string.status_needed))
        Status.OFF -> Triple(Ms.Info, MaterialTheme.colorScheme.tertiary, stringResource(R.string.status_off))
        Status.ADVICE -> Triple(icon ?: Ms.Info, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.status_check))
    }
    val fg = if (status == Status.NEEDED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(Dimens.l), verticalAlignment = Alignment.Top) {
        Icon(statusIcon, contentDescription = label, tint = tint)
        Column(Modifier.weight(1f).padding(start = Dimens.l)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = fg, modifier = Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(start = Dimens.s))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (status == Status.NEEDED) fg else MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && status != Status.READY) {
                Spacer(Modifier.size(Dimens.s))
                when (status) {
                    Status.NEEDED -> Button(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action) }
                    Status.OFF -> FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action) }
                    else -> OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(action) }
                }
            }
        }
    }
}

@Composable
fun TrashScreen(state: AppState, now: Instant, onRestore: (Task) -> Unit, onPurge: (Task) -> Unit, onEmpty: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val items = state.tasks.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    var menu by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<Task?>(null) }
    var topMenu by remember { mutableStateOf(false) }
    Page(stringResource(R.string.trash), onBack, actions = {
        if (items.isNotEmpty()) Box {
            TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { topMenu = true })
            DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.empty_trash)) }, onClick = { topMenu = false; confirmEmpty = true })
            }
        }
    }) {
        Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s))
        if (items.isEmpty()) Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(Dimens.xxl))
        items.forEachIndexed { i, t ->
            GroupItem(i, items.size) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(stringResource(R.string.deleted_at, L10n.dueText(context, t.deletedAt!!, now)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onRestore(t) }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.restore)) }
                    Box {
                        TooltipIconButton(Ms.MoreVert, stringResource(R.string.more_options), { menu = t.id })
                        DropdownMenu(expanded = menu == t.id, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_forever)) }, onClick = { menu = null; confirmPurge = t })
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) ConfirmDialog(stringResource(R.string.empty_trash), stringResource(R.string.empty_trash_confirm), stringResource(R.string.delete_forever),
        onEmpty, { confirmEmpty = false }, destructive = true)
    confirmPurge?.let { t ->
        ConfirmDialog(stringResource(R.string.delete_forever), stringResource(R.string.delete_forever_confirm, L10n.isolate(context, t.title)), stringResource(R.string.delete_forever),
            { onPurge(t) }, { confirmPurge = null }, destructive = true)
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    updates: com.fastkiwi.rememberme.update.UpdateController? = null,
    onFeedback: () -> Unit = {},
) {
    Page(stringResource(R.string.about), onBack) {
        Column(Modifier.fillMaxWidth().padding(Dimens.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            BrandMark(72.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.version_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                Row(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.CloudOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Dimens.s))
                    Text(stringResource(R.string.offline), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l)) {
            Row(Modifier.padding(Dimens.l)) {
                Icon(Ms.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.about_voice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // 1.0.5 (D-081): «تحقق من التحديثات», then «أبلغنا عن مشكلة أو اقتراح».
        updates?.let { com.fastkiwi.rememberme.ui.settings.AboutUpdates(it) }
        Group(listOf({ SettingsRow(Ms.Feedback, stringResource(R.string.feedback_title), stringResource(R.string.feedback_settings_hint), onFeedback) }))
        SectionHeader(stringResource(R.string.licences))
        Group(listOf(
            { SettingsRow(null, stringResource(R.string.licence_font), stringResource(R.string.licence_ofl)) },
            { SettingsRow(null, stringResource(R.string.licence_icons), stringResource(R.string.licence_apache)) },
            { SettingsRow(null, stringResource(R.string.licence_libs), stringResource(R.string.licence_apache)) },
        ))
        Text("© 2026 FastKiwi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(Dimens.l))
    }
}
