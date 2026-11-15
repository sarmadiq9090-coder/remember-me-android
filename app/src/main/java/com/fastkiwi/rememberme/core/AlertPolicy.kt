package com.fastkiwi.rememberme.core

/**
 * How a due reminder alerts, decided from the reminder, the phone's settings and the phone's state.
 * Pure, so every combination is unit-tested (FEATURES.md F-015, F-019, F-020, F-021, F-023).
 *
 * Never bypasses Do Not Disturb: the app plays a tone only where Android itself would let that kind of
 * sound through, and says why when it does not.
 */
object AlertPolicy {
    enum class Style { NORMAL, ALARM }
    enum class Ringer { NORMAL, VIBRATE, SILENT }
    /** Do Not Disturb as far as the app can read it: OFF, PRIORITY (with alarms allowed or not), ALARMS only, NONE (total silence). */
    enum class Dnd { OFF, PRIORITY_ALARMS_ALLOWED, PRIORITY_ALARMS_BLOCKED, PRIORITY_UNKNOWN, ALARMS_ONLY, NONE }
    /**
     * 1.0.5, the owner: «الشاشه الكاملة للتنبيهات المهمة فقط». IMPORTANT (the default) or OFF; there is no mode for
     * every reminder any more (1.0.4's ALWAYS reads as IMPORTANT).
     */
    enum class FullScreenMode { IMPORTANT, OFF }

    /** Important: «منبّه مهم», or importance «مهم». Only these open the full screen over the lock screen. */
    fun important(t: Task, style: Style) = style == Style.ALARM || t.priority == Priority.HIGH

    /** Why a tone is not played (shown in Health and the alert test). */
    enum class Quiet { NONE, SILENT_TONE, SOUND_OFF, RINGER_SILENT, RINGER_VIBRATE, DND, CHANNEL_MUTED }

    data class Decision(
        val style: Style,
        val playTone: Boolean,
        val vibrate: Boolean,
        val quiet: Quiet,
        val fullScreen: Boolean,
        val pinned: Boolean,
    )

    /** Settings «التذكيرات المهمة»: high priority is «منبّه مهم» by default (phone setting, F-015). */
    @Volatile var highIsAlarm: Boolean = true

    /** The reminder's style: its own choice, or by priority (high → alarm unless the setting says otherwise). */
    fun style(t: Task, ext: AlertExt = AlertExt.of(t)): Style = when (ext.style) {
        "alarm" -> Style.ALARM
        "normal" -> Style.NORMAL
        else -> if (t.priority == Priority.HIGH && highIsAlarm) Style.ALARM else Style.NORMAL
    }

    fun decide(
        t: Task,
        ringer: Ringer,
        dnd: Dnd,
        soundOn: Boolean,
        channelAudible: Boolean,
        vibrateDefault: Boolean,
        fullScreenMode: FullScreenMode,
        pinDefault: Boolean,
        ext: AlertExt = AlertExt.of(t),
    ): Decision {
        val style = style(t, ext)
        val silentTone = ext.tone?.kind == "silent"
        val wantsVibration = ext.vibrate ?: vibrateDefault
        val dndBlocks = when (dnd) {
            Dnd.OFF -> false
            Dnd.NONE -> true
            Dnd.ALARMS_ONLY, Dnd.PRIORITY_ALARMS_ALLOWED -> style == Style.NORMAL
            // Unknown: Android's own default lets alarms through in priority mode.
            Dnd.PRIORITY_UNKNOWN -> style == Style.NORMAL
            Dnd.PRIORITY_ALARMS_BLOCKED -> true
        }
        val quiet = when {
            silentTone -> Quiet.SILENT_TONE
            !soundOn -> Quiet.SOUND_OFF
            dndBlocks -> Quiet.DND
            !channelAudible -> Quiet.CHANNEL_MUTED
            style == Style.NORMAL && ringer == Ringer.SILENT -> Quiet.RINGER_SILENT
            style == Style.NORMAL && ringer == Ringer.VIBRATE -> Quiet.RINGER_VIBRATE
            else -> Quiet.NONE
        }
        // Vibration follows the phone for a normal reminder (none in silent mode, none when DND blocks it).
        val vibrate = wantsVibration && !dndBlocks && when (style) {
            Style.NORMAL -> ringer != Ringer.SILENT
            Style.ALARM -> true
        }
        // Only an important reminder, unless the setting is off or this reminder says «إشعار فقط». A reminder's
        // own "on" (from 1.0.4) no longer lifts a normal one.
        val full = fullScreenMode == FullScreenMode.IMPORTANT && important(t, style) && ext.fullScreen != "off"
        val pinned = when (ext.pin) { "on" -> true; "off" -> false; else -> pinDefault }
        return Decision(style, quiet == Quiet.NONE, vibrate, quiet, full, pinned)
    }

    /** At most this many quiet re-posts after a swipe, per occurrence (F-021). */
    const val MAX_REPOSTS = 5
    /** Ring durations for «منبّه مهم» (F-018), seconds; a normal reminder plays once, at most 30 s. */
    val RING_SECONDS = listOf(30, 60, 120)
    const val NORMAL_MAX_SECONDS = 30
}
