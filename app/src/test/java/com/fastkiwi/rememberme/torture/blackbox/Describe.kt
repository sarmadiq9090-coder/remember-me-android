package com.fastkiwi.rememberme.torture.blackbox

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Writes the picture and the three texts for one step. */
fun Driver.capture(name: String, label: String, result: String) {
    val bmp: Bitmap = render()
    FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val screen = screenText()
    val notes = notificationsText()
    val tree = talkbackText()
    val sb = StringBuilder()
    sb.appendLine("STEP: $label")
    sb.appendLine("RESULT: $result")
    if (events.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("WHAT HAPPENED BY ITSELF DURING THIS STEP:")
        events.distinct().forEach { sb.appendLine("  - $it") }
    }
    sb.appendLine()
    sb.appendLine("== SCREEN ==")
    sb.append(screen)
    sb.appendLine()
    sb.appendLine("== WHAT TALKBACK FINDS (top of the screen first; windows on top first) ==")
    sb.append(tree)
    sb.appendLine()
    sb.appendLine("== NOTIFICATIONS ==")
    sb.append(notes)
    File(out, "$name.txt").writeText(sb.toString(), Charsets.UTF_8)
    File(out, "screen.txt").writeText("After step: $label\n\n$screen", Charsets.UTF_8)
    File(out, "notifications.txt").writeText("After step: $label\n\n$notes", Charsets.UTF_8)
    runCatching { File(out, "driver-debug.txt").appendText("== $name default=${java.util.Locale.getDefault().toLanguageTag()} " +
        "app=${app.resources.configuration.locales.toLanguageTags()} front=${frontWin()?.activity?.resources?.configuration?.locales?.toLanguageTags()}\n${windowDebug()}\n", Charsets.UTF_8) }
}

fun Driver.screenText(): String = buildString {
    val now = Instant.now().atZone(ZoneId.systemDefault())
    appendLine("Phone time: ${DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).format(now)} (${ZoneId.systemDefault().id})")
    val front = when {
        crash != null -> "Android's message that the app has stopped"
        shadeOpen -> "the notification shade (pulled down over the screen)"
        lockScreenShowing() -> "the lock screen" + (if (unlockRequested) " (Android asks you to unlock)" else "")
        frontLayer() === Home -> "Android's home screen (the app is in the background or closed)"
        frontTask()?.overlay != null -> "an Android screen: «${frontTask()!!.overlay!!.title}»" + (if (frontTask()!!.overlay!!.transparent) " (a dialog over the app)" else "")
        else -> frontWin()?.let { w ->
            "${appLabel()} — " + (if (w.launcher) "its main window" else "its window «${w.name}»") + (if (w.showWhenLocked) " (this window may show over the lock screen)" else "") +
                (if (locked) " — shown over the lock screen" else "")
        } ?: "nothing"
    }
    appendLine("In front: $front")
    val wins = runCatching { visibleWindows() }.getOrDefault(emptyList())
    val extra = wins.filter { !it.isActivity }
    if (extra.isNotEmpty()) appendLine("Open on top of the app: " + extra.joinToString { it.kindName })
    val behind = tasks().filter { it !== frontTask() && it.wins.isNotEmpty() }
    if (behind.isNotEmpty() || (frontLayer() === Home && tasks().isNotEmpty())) appendLine("In the background: ${appLabel()} (recent apps)")
    appendLine("Phone: ${if (locked) "locked, screen off until something lights it" else "unlocked, screen on"}; " +
        "dark mode ${if (night) "on" else "off"}; text size ${(fontScale * 100).toInt()}%; phone language ${if (sysLang == "ar") "Arabic" else "English"}" +
        "; app language now ${effectiveLocale().displayName}")
    appendLine("Sound settings: ringer ${ringerName()}; Do Not Disturb ${when (dndMode) { "off" -> "off"; "on" -> "on (priority only, alarms allowed)"; "alarms" -> "alarms only"; else -> "total silence" }}")
    appendLine("App notifications allowed: ${if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == 0 && nm.areNotificationsEnabled()) "yes" else "no"}; " +
        "lock-screen notifications: ${when (lockMode) { "all" -> "show all content"; "hide-sensitive" -> "hide sensitive content"; else -> "none" }}")
    val next = runCatching { am.nextAlarmClock }.getOrNull()
    appendLine("Status bar: " + listOfNotNull(
        next?.let { "alarm-clock icon (next alarm ${DateTimeFormatter.ofPattern("EEE HH:mm:ss", java.util.Locale.ENGLISH).format(Instant.ofEpochMilli(it.triggerTime).atZone(ZoneId.systemDefault()))})" },
        appNotifications().size.takeIf { it > 0 }?.let { "$it notification icon(s)" },
        if (dndMode != "off") "Do Not Disturb icon" else null,
        when (ringerName()) { "silent" -> "silent icon"; "vibrate" -> "vibrate icon"; else -> null },
    ).ifEmpty { listOf("nothing special") }.joinToString("; "))
    appendLine("Sound now: " + (soundNow() ?: "silent"))
    lastSound?.let { appendLine("Last ringing the app started: $it") }
    if (toasts.isNotEmpty()) appendLine("Short message (toast) at the bottom: " + toasts.joinToString { "«$it»" })
    if (headsUps.isNotEmpty() && !shadeOpen) appendLine("Banner at the top: " + headsUps.joinToString { "«${title(it.notification)}»" })
    appendLine("Not shown in pictures: the status bar, the on-screen keyboard, real sound.")
}

fun Driver.notificationsText(): String = buildString {
    val list = appNotifications()
    if (list.isEmpty()) { appendLine("(none)"); return@buildString }
    for ((i, sbn) in list.withIndex()) {
        val n = sbn.notification
        val ch = n.channelId?.let { nm.getNotificationChannel(it) }
        appendLine("${i + 1}. «${title(n)}»")
        n.extras.getCharSequence(Notification.EXTRA_TEXT)?.let { appendLine("   text: $it") }
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { if (it.toString() != n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()) appendLine("   expanded text: ${it.toString().replace("\n", " / ")}") }
        n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { appendLine("   sub text: $it") }
        val actions = n.actions?.map { "[${it.title}]" } ?: emptyList()
        appendLine("   buttons: " + (actions.joinToString(" ").ifEmpty { "none" }))
        appendLine("   category (channel): «${ch?.name ?: n.channelId}» — " + importanceName(ch?.importance) +
            (if (ch != null) "; the category itself " + (if (ch.sound != null) "has a sound" else "has no sound") + (if (ch.shouldVibrate()) " and vibrates" else "") else ""))
        val flags = mutableListOf<String>()
        flags += if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) "pinned (ongoing): swiping it away is refused on the lock screen" else "can be swiped away"
        flags += if (silentByGroup(n)) "posted silently (no sound, no banner)" else "posted as an alert"
        if (n.fullScreenIntent != null) flags += "asks for a full-screen alert"
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) flags += "group summary"
        if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) flags += "closes when tapped"
        if (n.timeoutAfter > 0) flags += "disappears by itself after ${n.timeoutAfter / 1000} s"
        if (n.contentIntent == null) flags += "tapping it does nothing"
        appendLine("   " + flags.joinToString("; "))
        appendLine("   time shown: ${DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH).format(Instant.ofEpochMilli(n.`when`).atZone(ZoneId.systemDefault()))}")
        val pub = n.publicVersion
        val lock = when (n.visibility) {
            Notification.VISIBILITY_SECRET -> "hidden completely"
            Notification.VISIBILITY_PRIVATE -> if (pub != null) "«${title(pub)}»" + (pub.extras.getCharSequence(Notification.EXTRA_TEXT)?.let { " — $it" } ?: "") +
                (pub.actions?.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = " buttons: ") { "[${it.title}]" } ?: "") else "«${appLabel()}» — contents hidden"
            else -> "full content (public)"
        }
        appendLine("   on the lock screen: with «show all content» (Android's usual setting) the full content" +
            (if ((ch?.importance ?: 3) < NotificationManager.IMPORTANCE_DEFAULT) " — but this category is silent, so Android hides it from the lock screen" else "") +
            "; with «hide sensitive content»: $lock")
    }
}

private fun importanceName(i: Int?): String = when (i) {
    null -> "importance unknown"
    NotificationManager.IMPORTANCE_NONE -> "turned OFF by the user (not shown)"
    NotificationManager.IMPORTANCE_MIN -> "minimal (silent, collapsed)"
    NotificationManager.IMPORTANCE_LOW -> "silent (no sound, no banner)"
    NotificationManager.IMPORTANCE_DEFAULT -> "default (sound allowed, no banner)"
    else -> "high (sound allowed and a banner at the top)"
}

fun Driver.talkbackText(): String = buildString {
    if (crash != null) {
        appendLine("- «${appLabel()} keeps stopping» (Android dialog) — the app crashed; the script stops here")
        return@buildString
    }
    if (shadeOpen) {
        appendLine("Notification shade:")
        appNotifications().forEach { appendLine("  " + notifLine(it, it.notification)) }
        if (appNotifications().isEmpty()) appendLine("  - «No notifications»")
        return@buildString
    }
    if (headsUps.isNotEmpty() && !lockScreenShowing()) {
        appendLine("Banner at the top (heads-up):")
        headsUps.forEach { appendLine("  " + notifLine(it, it.notification)) }
    }
    when {
        lockScreenShowing() -> {
            appendLine("Lock screen:")
            appendLine("  - «${timeLine()}» (clock)")
            if (lockMode != "none") appNotifications().filter { (it.notification.channelId?.let { c -> nm.getNotificationChannel(c) }?.importance ?: 3) >= 3 }
                .forEach { appendLine("  " + notifLine(it, visibleVersion(it))) }
            appendLine("  - «${if (unlockRequested) "Unlock to continue" else "Phone locked"}» (use unlock)")
        }
        frontLayer() === Home -> {
            appendLine("Home screen:")
            appendLine("  - «${appLabel()}» (app icon) — actions: open-app")
        }
        else -> {
            val t = frontTask()!!
            t.overlay?.let { o ->
                appendLine("Android screen" + (if (o.transparent) " (dialog over the app)" else "") + ":")
                appendLine("  - «${o.title}» (title)")
                o.lines.forEach { appendLine("  - «$it»") }
                o.buttons.forEach { appendLine("  - «$it» (Button) — actions: tap") }
                if (!o.transparent) return@buildString
                appendLine("(Behind the dialog — TalkBack cannot reach it:)")
            }
            val wins = visibleWindows()
            val modalIndex = wins.indexOfLast { it.modal }
            for ((i, w) in wins.withIndex().reversed()) {
                val behind = modalIndex > i || t.overlay != null
                appendLine((if (w.isActivity) "The app screen" else w.kindName.replaceFirstChar { it.uppercase() }) +
                    (if (behind) " (behind the window on top — TalkBack cannot reach it)" else "") + ":")
                val o = w.semantics
                if (o == null) { appendLine("  (no accessible content)"); continue }
                val rtl = w.view.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val lines = mutableListOf<String>()
                dumpNode(o.rootSemanticsNode, w, rtl, 1, lines)
                if (lines.isEmpty()) appendLine("  (nothing TalkBack can read)") else lines.forEach { appendLine(it) }
            }
        }
    }
}

private fun Driver.timeLine(): String = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH).format(Instant.now().atZone(ZoneId.systemDefault()))

private fun Driver.notifLine(sbn: StatusBarNotification, n: Notification): String {
    val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val b = n.actions?.joinToString(", ") { "«${it.title}»" }
    return "- notification «${title(n)}»" + (text?.let { ", «$it»" } ?: "") + (b?.let { " — buttons: $it" } ?: "") +
        " — actions: notification-tap" + (if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) ", notification-swipe" else ", notification-swipe (pinned)")
}

/** Reading order inside one parent: rows from the top, and in each row from the start edge (right in Arabic). */
private fun ordered(children0: List<SemanticsNode>, rtl: Boolean): List<SemanticsNode> {
    val (children, unplaced) = children0.partition { it.boundsInWindow.width > 0f && it.boundsInWindow.height > 0f }
    val sorted = children.sortedBy { it.boundsInWindow.top }
    val rows = mutableListOf<MutableList<SemanticsNode>>()
    var rowBottom = Float.NEGATIVE_INFINITY
    for (c in sorted) {
        val b = c.boundsInWindow
        if (rows.isNotEmpty() && b.center.y < rowBottom) { rows.last() += c; rowBottom = maxOf(rowBottom, b.bottom) }
        else { rows += mutableListOf(c); rowBottom = b.bottom }
    }
    return rows.flatMap { r -> if (rtl) r.sortedByDescending { it.boundsInWindow.right } else r.sortedBy { it.boundsInWindow.left } } + unplaced
}

private fun Driver.dumpNode(n: SemanticsNode, w: RootWin, rtl: Boolean, depth: Int, out: MutableList<String>) {
    if (hidden(n)) return
    val line = describe(n, w)
    if (line != null) out += "  ".repeat(depth) + "- " + line
    for (c in ordered(n.children, rtl)) dumpNode(c, w, rtl, depth + if (line != null) 1 else 0, out)
}

private fun Driver.describe(n: SemanticsNode, w: RootWin): String? {
    val c = n.config
    val texts = c.getOrNull(SemanticsProperties.Text)?.map { it.text }?.filter { it.isNotBlank() } ?: emptyList()
    val edit = c.getOrNull(SemanticsProperties.EditableText)?.text
    val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.filter { it.isNotBlank() } ?: emptyList()
    val state = c.getOrNull(SemanticsProperties.StateDescription)
    val role = c.getOrNull(SemanticsProperties.Role)?.toString()
    val toggle = c.getOrNull(SemanticsProperties.ToggleableState)
    val selected = c.getOrNull(SemanticsProperties.Selected)
    val heading = c.contains(SemanticsProperties.Heading)
    val disabled = c.contains(SemanticsProperties.Disabled)
    val focused = c.getOrNull(SemanticsProperties.Focused) == true
    val click = c.getOrNull(SemanticsActions.OnClick)
    val longClick = c.getOrNull(SemanticsActions.OnLongClick)
    val custom = c.getOrNull(SemanticsActions.CustomActions)?.map { it.label } ?: emptyList()
    val scroll = c.contains(SemanticsActions.ScrollBy)
    val setText = c.contains(SemanticsActions.SetText)
    val progress = c.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
    val error = c.getOrNull(SemanticsProperties.Error)
    val pane = c.getOrNull(SemanticsProperties.PaneTitle)
    val password = c.contains(SemanticsProperties.Password)
    val live = c.getOrNull(SemanticsProperties.LiveRegion)
    val anything = texts.isNotEmpty() || edit != null || desc.isNotEmpty() || state != null || role != null || toggle != null || heading ||
        click != null || longClick != null || custom.isNotEmpty() || scroll || setText || progress != null || error != null || pane != null
    if (!anything) return null
    val parts = mutableListOf<String>()
    val says = when {
        desc.isNotEmpty() -> desc.joinToString(", ")
        setText -> listOfNotNull(edit?.takeIf { it.isNotEmpty() }?.let { if (password) "•••" else it }, texts.joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(", ")
        else -> texts.joinToString(", ")
    }
    parts += if (says.isNotEmpty()) "«$says»" else if (click != null || toggle != null) {
        val inside = w.semantics?.let { o -> allNodes(o.unmergedRootSemanticsNode).firstOrNull { it.id == n.id } }
            ?.let { u -> allNodes(u).drop(1).flatMap { textsOf(it) + (it.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) }.filter { it.isNotBlank() } }
            .orEmpty()
        "(NO LABEL — TalkBack would only say the kind of control" + (if (inside.isNotEmpty()) "; text drawn inside it but not given to TalkBack: «${inside.joinToString(", ")}»" else "") + ")"
    } else "(no text)"
    val kind = when {
        setText -> "edit field" + (if (edit.isNullOrEmpty()) ", empty" else "")
        role != null -> role
        heading -> "Heading"
        scroll -> "scrollable list"
        progress != null -> "progress"
        pane != null -> "pane «$pane»"
        else -> null
    }
    kind?.let { parts += "($it)" }
    if (desc.isNotEmpty() && texts.isNotEmpty() && texts.joinToString(", ") != desc.joinToString(", ")) parts += "visible text: «${texts.joinToString(", ")}»"
    state?.let { parts += "state: «$it»" }
    toggle?.let { parts += when (it) { ToggleableState.On -> "checked/on"; ToggleableState.Off -> "not checked/off"; else -> "partly checked" } }
    if (selected == true) parts += "selected" else if (selected == false && role in listOf("Tab", "RadioButton")) parts += "not selected"
    if (heading && role != null) parts += "heading"
    if (disabled) parts += "DISABLED"
    if (focused) parts += "has the cursor/focus"
    error?.let { parts += "error: «$it»" }
    progress?.let { parts += "value ${it.current} of ${it.range.start}–${it.range.endInclusive}" }
    if (live != null) parts += "announced when it changes"
    val acts = mutableListOf<String>()
    if (click != null) acts += "tap" + (click.label?.let { " («$it»)" } ?: "")
    if (longClick != null) acts += "long-press" + (longClick.label?.let { " («$it»)" } ?: "")
    if (setText) acts += "type"
    if (scroll) acts += "scroll"
    custom.forEach { acts += "«$it»" }
    if (acts.isNotEmpty()) parts += "actions: " + acts.joinToString(", ")
    val b = n.boundsInWindow
    val d = density
    val x = (b.left + w.x) / d; val y = (b.top + w.y) / d
    val visible = onScreen(n, w)
    parts += if (b.width <= 0f || b.height <= 0f) "not laid out on screen (OFF SCREEN — scroll to reach)"
        else "at %.0f,%.0f size %.0f×%.0f dp".format(java.util.Locale.ROOT, x, y, b.width / d, b.height / d) + (if (!visible) " (OFF SCREEN — scroll to reach)" else "")
    return parts.joinToString(" — ")
}
