package com.fastkiwi.rememberme.torture.blackbox

import android.app.Activity
import android.app.Notification
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.service.notification.StatusBarNotification
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsOwner
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A window on screen: the activity's own window, a dialog, a bottom sheet or a popup menu. */
class RootWin(
    val view: View, val params: WindowManager.LayoutParams, val x: Int, val y: Int,
    val isActivity: Boolean, val modal: Boolean, val kindName: String, val semantics: SemanticsOwner?,
)

@Suppress("UNCHECKED_CAST")
private fun wmgViews(): List<Pair<View, WindowManager.LayoutParams>> {
    val cls = Class.forName("android.view.WindowManagerGlobal")
    val inst = cls.getMethod("getInstance").invoke(null)
    val views = cls.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>
    val params = cls.getDeclaredField("mParams").apply { isAccessible = true }.get(inst) as List<WindowManager.LayoutParams>
    return views.indices.mapNotNull { i -> params.getOrNull(i)?.let { views[i] to it } }
}

private fun unwrap(c: Context?): Activity? {
    var x = c
    while (x != null) {
        if (x is Activity) return x
        x = (x as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

private fun semanticsOf(v: View): SemanticsOwner? {
    if (v is RootForTest) return v.semanticsOwner
    if (v is ViewGroup) for (i in 0 until v.childCount) semanticsOf(v.getChildAt(i))?.let { return it }
    return null
}

/** The windows of the window in front that a person can see, bottom first. Empty for Android's own screens. */
fun Driver.visibleWindows(): List<RootWin> {
    if (lockScreenShowing()) return emptyList()
    val t = frontTask() ?: return emptyList()
    if (t.overlay?.transparent == false) return emptyList()
    val a = frontWin()?.activity ?: return emptyList()
    return windowsOf(a)
}

fun Driver.windowsOf(a: Activity): List<RootWin> {
    val decor = a.window.decorView
    val dm = app.resources.displayMetrics
    val out = mutableListOf<RootWin>()
    for ((v, p) in wmgViews()) {
        val isAct = v === decor
        val mine = isAct || unwrap(v.context) === a || (p.token != null && (p.token === decor.applicationWindowToken || p.token === decor.windowToken || p.token === a.window.attributes.token))
        if (!mine) continue
        if (!isAct && (!v.isAttachedToWindow || v.visibility != View.VISIBLE || v.width == 0 || v.height == 0)) continue
        val (x, y) = if (isAct) 0 to 0 else place(v, p, dm.widthPixels, dm.heightPixels)
        val sub = p.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
        val kind = when {
            isAct -> "the screen"
            sub -> "a menu or popup"
            v.height >= dm.heightPixels * 0.9 -> "a bottom sheet or full-screen dialog"
            else -> "a dialog"
        }
        val modal = !isAct && (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
        out += RootWin(v, p, x, y, isAct, modal, kind, semanticsOf(v))
    }
    // The activity's own window first, then the others in the order they were opened.
    return out.sortedBy { if (it.isActivity) 0 else 1 }
}

/** Where Android would place a window of this size with these layout parameters. */
private fun place(v: View, p: WindowManager.LayoutParams, sw: Int, sh: Int): Pair<Int, Int> {
    val w = v.width; val h = v.height
    // Compose computes popup positions from the left edge already, so START is taken as left here.
    val g = Gravity.getAbsoluteGravity(p.gravity, View.LAYOUT_DIRECTION_LTR)
    val x = when (g and Gravity.HORIZONTAL_GRAVITY_MASK) {
        Gravity.CENTER_HORIZONTAL -> (sw - w) / 2 + p.x
        Gravity.RIGHT -> sw - w - p.x
        else -> p.x
    }
    val y = when (g and Gravity.VERTICAL_GRAVITY_MASK) {
        Gravity.CENTER_VERTICAL -> (sh - h) / 2 + p.y
        Gravity.BOTTOM -> sh - h - p.y
        else -> p.y
    }
    return x to y
}

fun Driver.windowDebug(): String = buildString {
    for ((v, p) in wmgViews()) {
        val loc = IntArray(2).also { runCatching { v.getLocationOnScreen(it) } }
        appendLine("${v.javaClass.simpleName} type=${p.type} flags=0x${Integer.toHexString(p.flags)} gravity=0x${Integer.toHexString(p.gravity)} x=${p.x} y=${p.y} " +
            "lp=${p.width}x${p.height} size=${v.width}x${v.height} screen=${loc[0]},${loc[1]} attached=${v.isAttachedToWindow} vis=${v.visibility} dim=${p.dimAmount} ctx=${unwrap(v.context)?.javaClass?.simpleName}")
    }
}

// =========================================================================================
// Drawing what the person sees
// =========================================================================================

class Look(val dark: Boolean) {
    val bg = if (dark) 0xFF1C1B1F.toInt() else 0xFFF7F7F7.toInt()
    val card = if (dark) 0xFF2B2930.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
    val sub = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
    val accent = if (dark) 0xFF8FD3B0.toInt() else 0xFF246A4B.toInt()
}

object Fonts {
    private var regular: Typeface? = null
    private var bold: Typeface? = null
    fun get(c: Context, b: Boolean): Typeface {
        if (regular == null) {
            runCatching {
                val f = Class.forName(c.packageName + ".R\$font").fields
                fun load(part: String) = f.firstOrNull { it.name.contains(part) }?.let { c.resources.getFont(it.getInt(null)) }
                regular = load("regular") ?: f.firstOrNull()?.let { c.resources.getFont(it.getInt(null)) }
                bold = load("bold") ?: regular
            }
            if (regular == null) { regular = Typeface.DEFAULT; bold = Typeface.DEFAULT_BOLD }
        }
        return (if (b) bold else regular) ?: Typeface.DEFAULT
    }
}

class Pen(val c: Canvas, val ctx: Context, val d: Float) {
    /** Draws wrapped text; returns its height in pixels. */
    fun text(s: String, x: Float, y: Float, width: Float, sizeDp: Float, color: Int, bold: Boolean = false, center: Boolean = false, maxLines: Int = 20): Float {
        if (width <= 0) return 0f
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = sizeDp * d; typeface = Fonts.get(ctx, bold) }
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt())
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    fun box(l: Float, t: Float, r: Float, b: Float, color: Int, radiusDp: Float = 16f) {
        c.drawRoundRect(RectF(l, t, r, b), radiusDp * d, radiusDp * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    fun measure(s: String, width: Float, sizeDp: Float, bold: Boolean = false, maxLines: Int = 20): Float {
        if (width <= 0) return 0f
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizeDp * d; typeface = Fonts.get(ctx, bold) }
        return StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setMaxLines(maxLines).build().height.toFloat()
    }
}

fun Driver.render(): Bitmap {
    val dm = app.resources.displayMetrics
    val sw = dm.widthPixels; val sh = dm.heightPixels
    val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val pen = Pen(c, app, density)
    val look = Look(night)
    c.drawColor(Color.BLACK)
    when {
        lockScreenShowing() -> drawLock(pen, sw, sh)
        frontLayer() === Home -> drawHome(pen, sw, sh)
        else -> {
            val t = frontTask()!!
            val o = t.overlay
            if (o == null || o.transparent) drawWindows(c)
            if (o != null) drawOverlay(pen, o, sw, sh, look)
        }
    }
    if (shadeOpen) drawShade(pen, sw, sh, look)
    else if (!lockScreenShowing()) headsUps.lastOrNull()?.let { drawHeadsUp(pen, it, sw, look) }
    toasts.lastOrNull()?.let { drawToast(pen, it, sw, sh) }
    crash?.let { drawCrash(pen, sw, sh, look) }
    return bmp
}

private fun Driver.drawWindows(c: Canvas) {
    val a = frontWin()?.activity ?: return
    for (w in windowsOf(a)) {
        if (!w.isActivity && w.params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
            c.drawColor(Color.argb((w.params.dimAmount.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0))
        }
        c.save(); c.translate(w.x.toFloat(), w.y.toFloat())
        w.view.draw(c)
        c.restore()
    }
}

private fun Driver.timeText(): Pair<String, String> {
    val now = Instant.now().atZone(ZoneId.systemDefault())
    val loc = Locale.Builder().setLanguage(sysLang).setUnicodeLocaleKeyword("nu", "latn").build()
    return DateTimeFormatter.ofPattern("H:mm", loc).format(now) to DateTimeFormatter.ofPattern("EEEE d MMMM", loc).format(now)
}

private fun Driver.drawLock(pen: Pen, sw: Int, sh: Int) {
    val d = density
    val c = pen.c
    c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, sh.toFloat(), 0xFF22323C.toInt(), 0xFF0B1115.toInt(), Shader.TileMode.CLAMP) })
    val (time, date) = timeText()
    var y = 64 * d
    y += pen.text(time, 0f, y, sw.toFloat(), 64f, Color.WHITE, bold = false, center = true)
    y += pen.text(date, 0f, y, sw.toFloat(), 16f, 0xFFCFD8DC.toInt(), center = true) + 24 * d
    val ar = sysLang == "ar"
    val list = if (lockMode == "none") emptyList() else appNotifications().filter { sbn ->
        val ch = sbn.notification.channelId?.let { nm.getNotificationChannel(it) }
        (ch?.importance ?: 3) >= 3
    }
    for (sbn in list.take(5)) {
        val n = visibleVersion(sbn)
        y += drawCard(pen, n, sbn, 12 * d, y, sw - 24 * d, Look(true), compact = true) + 8 * d
        if (y > sh - 160 * d) break
    }
    val msg = if (unlockRequested) (if (ar) "افتح القفل للمتابعة (unlock)" else "Unlock to continue (unlock)") else (if (ar) "الهاتف مقفل" else "Phone locked")
    pen.text(msg, 0f, sh - 80 * d, sw.toFloat(), 14f, Color.WHITE, center = true)
}

private fun Driver.drawHome(pen: Pen, sw: Int, sh: Int) {
    val d = density
    val c = pen.c
    c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, sw.toFloat(), sh.toFloat(), 0xFF2E5E4E.toInt(), 0xFF17324A.toInt(), Shader.TileMode.CLAMP) })
    val (time, date) = timeText()
    pen.text(time, 0f, 72 * d, sw.toFloat(), 56f, Color.WHITE, center = true)
    pen.text(date, 0f, 150 * d, sw.toFloat(), 16f, 0xFFE0E0E0.toInt(), center = true)
    val icon = runCatching { app.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
    val size = (60 * d).toInt()
    val cell = sw / 4
    val left = if (sysLang == "ar") sw - cell + (cell - size) / 2 else (cell - size) / 2
    val top = (sh - 220 * d).toInt()
    icon?.setBounds(left, top, left + size, top + size); icon?.draw(c)
    pen.text(appLabel(), (left - (cell - size) / 2).toFloat(), top + size + 6 * d, cell.toFloat(), 12f, Color.WHITE, center = true)
    pen.text(if (sysLang == "ar") "الشاشة الرئيسية" else "Home screen", 0f, sh - 60 * d, sw.toFloat(), 12f, 0xB0FFFFFF.toInt(), center = true)
}

/** A notification as Android draws it in the shade or on the lock screen; returns its height. */
private fun Driver.drawCard(pen: Pen, n: Notification, sbn: StatusBarNotification, x: Float, y: Float, w: Float, look: Look, compact: Boolean = false): Float {
    val d = density
    val pad = 14 * d
    val inner = w - 2 * pad
    val title = title(n)
    val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    val body = if (!compact && big != null) big else text
    val actions = n.actions?.map { it.title.toString() } ?: emptyList()
    val head = appLabel() + " • " + timeOf(n)
    var h = pad + pen.measure(head, inner, 12f) + 4 * d + pen.measure(title, inner, 15f, true, 2) + 2 * d + pen.measure(body, inner, 14f, false, if (compact) 2 else 6) + pad
    if (actions.isNotEmpty()) h += 36 * d
    pen.box(x, y, x + w, y + h, if (look.dark) 0xF02B2930.toInt() else 0xFFFFFFFF.toInt(), 20f)
    var cy = y + pad
    cy += pen.text(head, x + pad, cy, inner, 12f, look.sub) + 4 * d
    cy += pen.text(title, x + pad, cy, inner, 15f, look.text, bold = true, maxLines = 2) + 2 * d
    cy += pen.text(body, x + pad, cy, inner, 14f, look.sub, maxLines = if (compact) 2 else 6)
    if (actions.isNotEmpty()) {
        val line = actions.joinToString("     ")
        pen.text(line, x + pad, cy + 10 * d, inner, 14f, look.accent, bold = true, maxLines = 1)
    }
    return h
}

private fun Driver.timeOf(n: Notification): String {
    val loc = Locale.Builder().setLanguage(sysLang).setUnicodeLocaleKeyword("nu", "latn").build()
    return DateTimeFormatter.ofPattern("H:mm", loc).format(Instant.ofEpochMilli(n.`when`).atZone(ZoneId.systemDefault()))
}

private fun Driver.drawShade(pen: Pen, sw: Int, sh: Int, look: Look) {
    val d = density
    pen.c.drawColor(if (look.dark) 0xF0101012.toInt() else 0xF0E8E8EC.toInt())
    val (time, date) = timeText()
    var y = 24 * d
    y += pen.text("$time   $date", 16 * d, y, sw - 32 * d, 16f, look.text) + 16 * d
    val list = appNotifications()
    if (list.isEmpty()) pen.text(if (sysLang == "ar") "لا إشعارات" else "No notifications", 0f, y + 40 * d, sw.toFloat(), 16f, look.sub, center = true)
    for (sbn in list) {
        y += drawCard(pen, sbn.notification, sbn, 12 * d, y, sw - 24 * d, look) + 8 * d
        if (y > sh - 80 * d) break
    }
}

private fun Driver.drawHeadsUp(pen: Pen, sbn: StatusBarNotification, sw: Int, look: Look) {
    val d = density
    val n = sbn.notification
    // A shadow line so the banner stands out over the app.
    pen.box(10 * d, 10 * d, sw - 10 * d, 12 * d + 4 * d, 0x40000000)
    drawCard(pen, n, sbn, 12 * d, 12 * d, sw - 24 * d, look, compact = true)
}

private fun Driver.drawToast(pen: Pen, s: String, sw: Int, sh: Int) {
    val d = density
    val w = sw - 80 * d
    val h = pen.measure(s, w - 32 * d, 14f) + 24 * d
    val top = sh - 120 * d - h
    pen.box(40 * d, top, sw - 40 * d, top + h, 0xE0303030.toInt(), 24f)
    pen.text(s, 56 * d, top + 12 * d, w - 32 * d, 14f, Color.WHITE, center = true)
}

private fun Driver.drawCrash(pen: Pen, sw: Int, sh: Int, look: Look) {
    val d = density
    pen.c.drawColor(0x99000000.toInt())
    val ar = sysLang == "ar"
    val w = sw - 64 * d
    val top = sh / 2f - 90 * d
    pen.box(32 * d, top, 32 * d + w, top + 180 * d, look.card, 28f)
    pen.text(if (ar) "توقف تطبيق «${appLabel()}»" else "${appLabel()} keeps stopping", 56 * d, top + 28 * d, w - 48 * d, 20f, look.text, bold = true)
    pen.text(if (ar) "إغلاق التطبيق" else "Close app", 56 * d, top + 110 * d, w - 48 * d, 15f, look.accent, bold = true)
}

private fun Driver.drawOverlay(pen: Pen, o: Overlay, sw: Int, sh: Int, look: Look) {
    val d = density
    val c = pen.c
    if (o.transparent) {
        c.drawColor(0x99000000.toInt())
        val w = sw - 48 * d
        val inner = w - 48 * d
        val textH = pen.measure(o.title, inner, 20f) + o.lines.sumOf { pen.measure(it, inner, 14f).toDouble() }.toFloat() + o.lines.size * 8 * d
        val h = 24 * d + textH + 24 * d + o.buttons.size * 52 * d + 16 * d
        val top = (sh - h) / 2
        pen.box(24 * d, top, 24 * d + w, top + h, look.card, 28f)
        var y = top + 24 * d
        y += pen.text(o.title, 48 * d, y, inner, 20f, look.text, center = true) + 8 * d
        for (l in o.lines) y += pen.text(l, 48 * d, y, inner, 14f, look.sub, center = true) + 8 * d
        y += 16 * d
        for (b in o.buttons) {
            pen.box(40 * d, y, 40 * d + w - 32 * d, y + 44 * d, if (look.dark) 0xFF3B4A43.toInt() else 0xFFDCEFE5.toInt(), 22f)
            pen.text(b, 40 * d, y + 11 * d, w - 32 * d, 15f, look.accent, bold = true, center = true)
            y += 52 * d
        }
        return
    }
    c.drawColor(look.bg)
    pen.box(0f, 0f, sw.toFloat(), 72 * d, look.card, 0f)
    pen.text(o.title, 16 * d, 22 * d, sw - 32 * d, 18f, look.text, bold = true, maxLines = 2)
    var y = 96 * d
    for (l in o.lines) { y += pen.text(l, 16 * d, y, sw - 32 * d, 15f, look.text) + 10 * d; if (y > sh - 120 * d) break }
    var by = sh - 80 * d
    for (b in o.buttons.reversed()) {
        pen.box(16 * d, by, sw - 16 * d, by + 48 * d, look.accent, 24f)
        pen.text(b, 16 * d, by + 13 * d, sw - 32 * d, 16f, if (look.dark) 0xFF00391F.toInt() else Color.WHITE, bold = true, center = true)
        by -= 56 * d
    }
    pen.text(if (sysLang == "ar") "شاشة من أندرويد (رسمها المشغِّل)" else "Android screen (drawn by the driver)", 0f, sh - 24 * d, sw.toFloat(), 11f, look.sub, center = true)
}

// =========================================================================================
// A document provider for picked and saved files
// =========================================================================================

class FakeDocs : ContentProvider() {
    companion object {
        private val files = mutableMapOf<String, File>()
        private var installed = false
        fun add(f: File): Uri {
            if (!installed) { org.robolectric.Robolectric.setupContentProvider(FakeDocs::class.java, Driver.DOCS); installed = true }
            files[f.name] = f
            return Uri.parse("content://${Driver.DOCS}/document/primary%3ADownload%2F" + Uri.encode(f.name))
        }
        fun of(uri: Uri): File? = files[Uri.decode(uri.lastPathSegment ?: "").substringAfterLast('/')]
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = of(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = of(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
}
