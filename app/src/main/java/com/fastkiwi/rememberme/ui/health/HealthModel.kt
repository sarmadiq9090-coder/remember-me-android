package com.fastkiwi.rememberme.ui.health

import com.fastkiwi.rememberme.core.AlertPolicy
import com.fastkiwi.rememberme.ui.Health
import com.fastkiwi.rememberme.ui.ScheduleStatus

/** A row's state; each has its own word and icon, never colour alone (DESIGN-SPEC §3.1, §5.11). */
enum class HealthStatus {
    READY, NEEDED, RESTRICTED, OFF, INFO, UNKNOWN;

    /** Not ready: the row shows where the setting lives and exactly one action. «للعلم» is a fact, not a fault. */
    val notReady get() = this != READY && this != INFO
}

enum class HealthGroup { ESSENTIAL, RECOMMENDED, PHONE_NOW, MAKER, SCHEDULING }

/** Where the setting lives: Android's own settings or the phone maker's. */
enum class HealthSource { ANDROID, MAKER }

/** The one action of a not-ready row; its look follows the group (§5.11 item 3). */
enum class ActionKind { FIX, ALLOW, OPEN_SETTINGS }

/** [target] is what to open (see [HealthFix] and MainActivity.fix). */
data class HealthAction(val kind: ActionKind, val target: String)

/**
 * One row of «صحة التذكيرات». [key] picks its title and supporting text in [HealthScreen]; [arg] fills
 * the text (a count). [tried] is the F-013 label of a maker step («جُرِّب على هاتف»).
 */
data class HealthItem(
    val key: String,
    val group: HealthGroup,
    val status: HealthStatus,
    val source: HealthSource? = null,
    val action: HealthAction? = null,
    val arg: String? = null,
    val tried: Boolean? = null,
)

/** A maker step (F-013): [id] names its strings (mk_<id>, mk_<id>_d); [target] opens the closest screen. */
data class MakerStep(val id: String, val target: String, val tried: Boolean = false)

/**
 * Steps per phone maker and Android version (FEATURES.md F-013). A step is marked tried only after it
 * was tried on a real phone of that maker and version; none has been yet.
 */
object MakerSteps {
    /** The maker's display string key and its steps, or null for a maker without known steps. */
    fun of(manufacturer: String, sdk: Int): Pair<String, List<MakerStep>>? = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> "maker_xiaomi" to listOf(
            MakerStep("xiaomi_autostart", "maker"), MakerStep("xiaomi_battery", "app"), MakerStep("xiaomi_lock", "app"),
        )
        // One UI 6 (Android 14) renamed the list to «never auto sleeping apps» and moved it under Device care.
        "samsung" -> "maker_samsung" to listOf(
            MakerStep("samsung_battery", "app"),
            if (sdk >= 34) MakerStep("samsung_sleep6", "maker") else MakerStep("samsung_sleep5", "maker"),
        )
        "huawei", "honor" -> "maker_huawei" to listOf(MakerStep("huawei_launch", "maker"))
        "oppo", "realme", "oneplus" -> "maker_oppo" to listOf(MakerStep("oppo_background", "app"), MakerStep("oppo_autostart", "maker"))
        "vivo", "iqoo" -> "maker_vivo" to listOf(MakerStep("vivo_background", "battery_list"), MakerStep("vivo_autostart", "maker"))
        else -> null
    }
}

/** Builds the rows of «صحة التذكيرات» from what the app could read. Pure, so every combination is unit-tested. */
object HealthModel {
    private fun action(group: HealthGroup, target: String) = HealthAction(
        when (group) {
            HealthGroup.ESSENTIAL -> ActionKind.FIX
            HealthGroup.RECOMMENDED -> ActionKind.ALLOW
            else -> ActionKind.OPEN_SETTINGS
        }, target,
    )

    /** A row; when it is not ready it always gets a source and exactly one action. */
    private fun item(key: String, group: HealthGroup, status: HealthStatus, target: String, source: HealthSource = HealthSource.ANDROID,
                     arg: String? = null, actionWhenInfo: Boolean = false, tried: Boolean? = null) =
        if (status.notReady || (status == HealthStatus.INFO && actionWhenInfo))
            HealthItem(key, group, status, source, action(group, target), arg, tried)
        else HealthItem(key, group, status, null, null, arg, tried)

    /** «ضروري»: without these, reminders are not shown or arrive late. */
    fun essentials(h: Health, s: ScheduleStatus): List<HealthItem> = buildList {
        val g = HealthGroup.ESSENTIAL
        fun st(ok: Boolean) = if (ok) HealthStatus.READY else HealthStatus.NEEDED
        add(item("notifications", g, st(h.notifications), "notifications"))
        add(item("exact", g, st(h.exact), "exact"))
        add(item("channel", g, st(h.channel && h.channelSound), "channel"))
        if (h.backgroundRestricted) add(item("background", g, HealthStatus.NEEDED, "app"))
        if (s.last?.verified == false) add(item("schedule", g, HealthStatus.NEEDED, "check"))
        if (h.fgsFailures > 0) add(item("playback", g, HealthStatus.NEEDED, "battery"))
    }

    /** How many essential rows are not ready (0 = «كل شيء ضروري جاهز»). Settings' top row uses the same count. */
    fun essentialMissing(h: Health, s: ScheduleStatus): Int = essentials(h, s).count { it.status != HealthStatus.READY }

    /**
     * Why the sound will not be heard although the essentials may be ready (black-box U2-1), in this order:
     * "notif_volume" / "alarm_volume" at zero, "channel" («تذكيرات عادية») or "alarm_channel" («منبّهات مهمة») muted.
     * The summary card lists them under its title, so a green card never implies "you will hear it".
     */
    fun soundWarnings(h: Health): List<String> = buildList {
        if (h.notifVolume == 0) add("notif_volume")
        if (h.alarmVolume == 0) add("alarm_volume")
        if (!h.channelSound) add("channel")
        if (!h.alarmChannelSound) add("alarm_channel")
    }

    /** «يُنصح به». */
    fun recommended(h: Health, s: ScheduleStatus, nowMs: Long): List<HealthItem> = buildList {
        val g = HealthGroup.RECOMMENDED
        add(item("fullscreen", g, if (h.fullScreen) HealthStatus.READY else HealthStatus.OFF, "fullscreen"))
        add(item("battery", g, if (h.battery && !h.bucketRestricted) HealthStatus.READY else HealthStatus.RESTRICTED, "battery"))
        val blocked = s.bootBlocked(h.bootAt, nowMs)
        add(when {
            blocked == true -> item("boot", g, HealthStatus.RESTRICTED, "maker", HealthSource.MAKER)
            blocked == false || s.lastBoot != null -> item("boot", g, HealthStatus.READY, "maker")
            else -> item("boot", g, HealthStatus.UNKNOWN, "maker", HealthSource.MAKER)
        })
        // F-087: the weekly automatic copy failed last time.
        if (h.autoBackupFailed) add(item("autobackup", g, HealthStatus.RESTRICTED, "settings"))
        add(item("lock", g, when (h.lockScreenHidesAll) {
            false -> HealthStatus.READY
            true -> HealthStatus.OFF
            null -> HealthStatus.UNKNOWN
        }, "notifications"))
    }

    /** «الهاتف الآن»: facts, never red; anything the app cannot read is «غير معروف», never «جاهز» (F-023). */
    fun phoneNow(h: Health): List<HealthItem> = buildList {
        val g = HealthGroup.PHONE_NOW
        add(item("ringer", g, when (h.ringer) {
            AlertPolicy.Ringer.NORMAL -> HealthStatus.READY
            AlertPolicy.Ringer.VIBRATE, AlertPolicy.Ringer.SILENT -> HealthStatus.INFO
            null -> HealthStatus.UNKNOWN
        }, "sound"))
        add(item("dnd", g, when (h.dndState) {
            AlertPolicy.Dnd.OFF -> HealthStatus.READY
            AlertPolicy.Dnd.PRIORITY_ALARMS_ALLOWED, AlertPolicy.Dnd.ALARMS_ONLY,
            AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED, AlertPolicy.Dnd.NONE -> HealthStatus.INFO
            AlertPolicy.Dnd.PRIORITY_UNKNOWN, null -> HealthStatus.UNKNOWN
        }, "dnd_help", actionWhenInfo = h.dndState == AlertPolicy.Dnd.PRIORITY_ALARMS_BLOCKED || h.dndState == AlertPolicy.Dnd.NONE))
        fun volume(key: String, v: Int?) = item(key, g, when {
            v == null -> HealthStatus.UNKNOWN
            v == 0 -> HealthStatus.OFF
            else -> HealthStatus.READY
        }, "sound")
        add(volume("alarm_volume", h.alarmVolume))
        add(volume("notif_volume", h.notifVolume))
        val tones = h.toneProblems
        add(item("tones", g, when {
            tones == null -> HealthStatus.UNKNOWN
            tones == 0 -> HealthStatus.READY
            else -> HealthStatus.INFO
        }, "alert_log", arg = tones?.toString(), actionWhenInfo = true))
        add(item("alarm_channel", g, if (h.alarmChannelSound) HealthStatus.READY else HealthStatus.OFF, "alarm_channel"))
    }

    /** «الشركة المصنعة»: the app cannot read these settings, so each step is «غير معروف» with its own button. */
    fun maker(manufacturer: String, sdk: Int): List<HealthItem> {
        val steps = MakerSteps.of(manufacturer, sdk)?.second ?: listOf(MakerStep("generic", "battery_list"))
        return steps.map { item(it.id, HealthGroup.MAKER, HealthStatus.UNKNOWN, it.target, HealthSource.MAKER, tried = it.tried) }
    }

    /** Every status row, in screen order. */
    fun items(h: Health, s: ScheduleStatus, nowMs: Long, manufacturer: String, sdk: Int): List<HealthItem> =
        essentials(h, s) + recommended(h, s, nowMs) + phoneNow(h) + maker(manufacturer, sdk)

    /** «2 من 3 جاهز» for a group. */
    fun readyCount(items: List<HealthItem>): Pair<Int, Int> = items.count { it.status == HealthStatus.READY } to items.size
}
