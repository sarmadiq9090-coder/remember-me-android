package com.fastkiwi.rememberme.ui.settings

import androidx.annotation.StringRes
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Organize

/**
 * One searchable row of Settings (DESIGN-SPEC §5.14, §6): its key (the row's own control), title, group and a short
 * synonym list (comma-separated, 0 = none). The index is static; values are added at search time.
 */
data class SettingEntry(val key: String, @param:StringRes val titleRes: Int, @param:StringRes val groupRes: Int, @param:StringRes val synonymsRes: Int = 0)

object SettingsIndex {
    val ENTRIES: List<SettingEntry> = listOf(
        SettingEntry("health", R.string.health_title, R.string.set_group_top, R.string.set_syn_health),
        // التنبيه
        SettingEntry("tone_normal", R.string.set_default_tone_normal, R.string.section_alerts, R.string.set_syn_tone),
        SettingEntry("tone_alarm", R.string.set_default_tone_alarm, R.string.section_alerts, R.string.set_syn_tone),
        SettingEntry("important", R.string.set_important_reminders, R.string.section_alerts, R.string.set_syn_important),
        SettingEntry("fullscreen", R.string.set_full_screen, R.string.section_alerts, R.string.set_syn_fullscreen),
        SettingEntry("ring", R.string.set_ring_length, R.string.section_alerts, R.string.set_syn_ring),
        SettingEntry("snooze", R.string.set_snooze_presets, R.string.section_alerts, R.string.set_syn_snooze),
        SettingEntry("again", R.string.remind_again, R.string.section_alerts, R.string.set_syn_again),
        SettingEntry("vibration", R.string.set_vibration, R.string.section_alerts, R.string.set_syn_vibration),
        SettingEntry("sound", R.string.sound, R.string.section_alerts, R.string.set_syn_tone),
        // شاشة القفل
        SettingEntry("lock", R.string.set_lock_screen, R.string.set_section_lock, R.string.set_syn_lock),
        SettingEntry("pin", R.string.set_pin, R.string.set_section_lock, R.string.set_syn_lock),
        // العرض
        SettingEntry("language", R.string.language, R.string.section_display, R.string.set_syn_language),
        SettingEntry("theme", R.string.theme, R.string.section_display, R.string.set_syn_theme),
        SettingEntry("months", R.string.month_names, R.string.section_display),
        SettingEntry("week", R.string.week_start, R.string.section_display),
        SettingEntry("hijri", R.string.set_hijri, R.string.section_display, R.string.set_syn_hijri),
        SettingEntry("hijri_adjust", R.string.set_hijri_adjust, R.string.section_display, R.string.set_syn_hijri),
        SettingEntry("swipe", R.string.set_swipe, R.string.section_display, R.string.set_syn_swipe),
        // الصلاة
        SettingEntry("prayer_method", R.string.set_prayer_method, R.string.set_section_prayer, R.string.set_syn_prayer),
        SettingEntry("prayer_asr", R.string.set_prayer_asr, R.string.set_section_prayer, R.string.set_syn_prayer),
        SettingEntry("prayer_city", R.string.set_prayer_city, R.string.set_section_prayer, R.string.set_syn_prayer),
        // ملخص الصباح
        SettingEntry("summary", R.string.set_section_summary, R.string.set_section_summary, R.string.set_syn_summary),
        SettingEntry("summary_time", R.string.set_summary_time, R.string.set_section_summary, R.string.set_syn_summary),
        // التنظيم
        SettingEntry("lists", R.string.set_lists_title, R.string.set_section_organise, R.string.set_syn_lists),
        SettingEntry("templates", R.string.set_templates, R.string.set_section_organise),
        SettingEntry("history", R.string.set_history, R.string.set_section_organise, R.string.set_syn_history),
        SettingEntry("history_clear", R.string.set_history_clear, R.string.set_section_organise, R.string.set_syn_history),
        SettingEntry("autocomplete", R.string.set_autocomplete, R.string.set_section_organise, R.string.set_syn_steps),
        // بياناتك
        SettingEntry("export", R.string.export, R.string.section_data, R.string.set_syn_backup),
        SettingEntry("auto_backup", R.string.set_auto_backup, R.string.section_data, R.string.set_syn_backup),
        SettingEntry("encrypt", R.string.set_encrypt, R.string.section_data, R.string.set_syn_encrypt),
        SettingEntry("backup_tones", R.string.set_backup_tones, R.string.section_data, R.string.set_syn_tone),
        SettingEntry("restore", R.string.import_, R.string.section_data, R.string.set_syn_backup),
        SettingEntry("safety", R.string.set_safety_copies, R.string.section_data, R.string.set_syn_backup),
        SettingEntry("ics_import", R.string.set_ics_import, R.string.section_data, R.string.set_syn_calendar),
        SettingEntry("ics_export", R.string.set_ics_export, R.string.section_data, R.string.set_syn_calendar),
        SettingEntry("compat", R.string.compat_title, R.string.section_data, R.string.set_syn_report),
        SettingEntry("alert_log", R.string.set_alert_log, R.string.section_data, R.string.set_syn_report),
        SettingEntry("trash", R.string.trash, R.string.section_data, R.string.set_syn_trash),
        SettingEntry("delete_all", R.string.set_delete_all, R.string.section_data, R.string.set_syn_delete),
        // حول
        SettingEntry("privacy", R.string.set_privacy, R.string.section_about, R.string.set_syn_privacy),
        SettingEntry("licences", R.string.licences, R.string.section_about),
        SettingEntry("about", R.string.about, R.string.section_about, R.string.set_syn_about),
        // صحة التذكيرات (open the Health screen)
        SettingEntry("h_notifications", R.string.chk_notifications, R.string.health_title, R.string.set_syn_h_notifications),
        SettingEntry("h_exact", R.string.chk_exact, R.string.health_title, R.string.set_syn_h_exact),
        SettingEntry("h_channel", R.string.set_idx_channel_sound, R.string.health_title, R.string.set_syn_tone),
        SettingEntry("h_silent", R.string.set_idx_phone_silent, R.string.health_title, R.string.set_syn_tone),
        SettingEntry("h_dnd", R.string.set_idx_dnd, R.string.health_title, R.string.set_syn_dnd),
        SettingEntry("h_battery", R.string.chk_battery, R.string.health_title, R.string.set_syn_battery),
        SettingEntry("h_fullscreen", R.string.chk_fullscreen, R.string.health_title, R.string.set_syn_fullscreen),
        SettingEntry("h_test_sound", R.string.set_idx_test_sound, R.string.health_title, R.string.set_syn_tone),
    )

    /** Keys whose row opens the Health screen. */
    fun isHealth(key: String) = key == "health" || key.startsWith("h_")

    /** One result: the entry, its title, the group's name and where the query sits in the title (to show in bold). */
    data class Hit(val entry: SettingEntry, val title: String, val group: String, val highlight: IntRange?)

    /**
     * The entries matching [query] by title, current value, group name or synonyms, all with Windows' letter folding
     * ([Organize.fold]): «اصوات» finds «أصوات». Entries in [hidden] (rows not shown now) are left out.
     * Title matches come first.
     */
    fun search(
        query: String,
        text: (Int) -> String,
        value: (String) -> String?,
        hidden: Set<String> = emptySet(),
        entries: List<SettingEntry> = ENTRIES,
    ): List<Hit> {
        val q = Organize.fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        fun has(s: String?) = s != null && Organize.fold(s).let { f -> words.all { it in f } }
        val hits = entries.filter { it.key !in hidden }.mapNotNull { e ->
            val title = text(e.titleRes)
            val group = text(e.groupRes)
            val syn = if (e.synonymsRes != 0) text(e.synonymsRes) else null
            val byTitle = has(title)
            if (!byTitle && !has(value(e.key)) && !has(group) && !has(syn)) return@mapNotNull null
            Triple(e, byTitle, Hit(e, title, group, if (byTitle) range(title, query.trim()) else null))
        }
        return hits.sortedBy { if (it.second) 0 else 1 }.map { it.third }
    }

    /** Where [query] appears in [title] ignoring the letter folding (folding keeps lengths except removed marks). */
    private fun range(title: String, query: String): IntRange? {
        val q = Organize.fold(query)
        // Map each kept character of the folded title back to its index in the original.
        val map = ArrayList<Int>()
        val folded = StringBuilder()
        title.forEachIndexed { i, ch -> val f = Organize.fold(ch.toString()); for (c in f) { folded.append(c); map += i } }
        val at = folded.indexOf(q)
        if (at < 0 || q.isEmpty()) return null
        return map[at]..map[at + q.length - 1]
    }
}
