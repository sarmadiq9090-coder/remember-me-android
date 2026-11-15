package com.fastkiwi.rememberme.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.alarm.Notifier
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.core.Completion
import com.fastkiwi.rememberme.core.Task
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.ui.Behaviour
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.quick.QuickEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Home-screen widget (DESIGN-SPEC §5.22): the next reminders, overdue first, and «+» for Quick Add. Rows that are
 * due, overdue or today have a 48 dp Done circle (F-121). The tap carries the due time the row showed, so a stale
 * tap (the reminder has moved on since the widget was drawn) changes nothing. A completed row shows a filled check,
 * «أُنجز: <title>» and «تراجع» for 10 s, then the widget is drawn again without it.
 */
class ReminderWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != DONE && action != UNDO && action != EXPIRE) { super.onReceive(context, intent); return }
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                val now = Instant.now()
                when (action) {
                    DONE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                        done(app, id, intent.getStringExtra(EXTRA_DUE)?.let { runCatching { Instant.parse(it) }.getOrNull() }, now)
                    }
                    UNDO -> intent.getStringExtra(EXTRA_ID)?.let { undo(app, it, now) }
                    else -> refresh(app)
                }
            } catch (_: java.io.IOException) {
                // Nothing could be saved (disk full): nothing changed; draw what is true.
                runCatching { refresh(app) }
            } finally { pending.finish() }
        }
    }

    /** A row completed from the widget, waiting for its «تراجع» until [until] (epoch ms). */
    data class Recent(val id: String, val copyId: String, val title: String, val until: Long)

    /** What one row shows: a reminder, or a just-completed one with its undo. */
    sealed interface Row {
        data class Item(val task: Task, val late: Boolean, val today: Boolean) : Row { val canComplete get() = late || today }
        data class Done(val recent: Recent) : Row
    }

    companion object {
        const val DONE = "com.fastkiwi.rememberme.WIDGET_DONE"
        const val UNDO = "com.fastkiwi.rememberme.WIDGET_UNDO"
        const val EXPIRE = "com.fastkiwi.rememberme.WIDGET_EXPIRE"
        const val EXTRA_ID = "id"
        const val EXTRA_DUE = "due"
        const val MAX_ROWS = 4
        private const val PREFS = "widget"
        private const val KEY_RECENT = "recent"
        private val worker = Executors.newSingleThreadExecutor()

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReminderWidget::class.java))
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        /**
         * «تم» from a row (the ActionReceiver.DONE rules: expectedDueAt, checked). [due] is the due time the row
         * showed; when the reminder has moved on since, nothing changes. Returns true when it was completed.
         */
        fun done(context: Context, id: String, due: Instant?, now: Instant): Boolean {
            if (due == null) return false
            if (RingService.ringingId == Notifier.dueId(id)) RingService.stop(context)
            val zone = Repo.zone()
            var completion: Completion? = null
            var title = ""
            Repo.change(context) { s ->
                val r = Tasks.complete(s, id, now, zone, due, checked = true)
                completion = r?.second
                title = s.task(id)?.title.orEmpty()
                r?.first ?: s
            }
            val c = completion
            if (c != null) {
                runCatching { AlertLog(context).let { log -> log.add(AlertLog.Event.DONE, log.keyOf(id, Codec.iso(due))) } }
                val nowMs = now.toEpochMilli()
                writeRecent(context, recent(context, nowMs).filter { it.id != id } + Recent(id, c.copyId, title, nowMs + Behaviour.UndoMillis))
                scheduleExpire(context)
            }
            refresh(context)
            return c != null
        }

        /** «تراجع»: puts the reminder back exactly as it was (F-052). */
        fun undo(context: Context, id: String, now: Instant) {
            val nowMs = now.toEpochMilli()
            val r = recent(context, nowMs).firstOrNull { it.id == id }
            if (r != null) Repo.change(context) { s -> Tasks.undoComplete(s, Completion(r.id, r.copyId), now) }
            writeRecent(context, recent(context, nowMs).filter { it.id != id })
            refresh(context)
        }

        /** The rows to draw at [now]: the rows waiting for their undo first, then the next reminders. */
        fun rows(tasks: List<Task>, recent: List<Recent>, now: Instant, zone: ZoneId): List<Row> {
            val waiting = recent.filter { it.until > now.toEpochMilli() }.take(MAX_ROWS)
            val hidden = waiting.map { it.id }.toSet()
            val today = now.atZone(zone).toLocalDate()
            val items = tasks.filter { it.active && it.dueAt != null && it.id !in hidden }.sortedBy { it.dueAt }.map { t ->
                Row.Item(t, late = !t.dueAt!!.isAfter(now), today = t.dueAt.atZone(zone).toLocalDate() == today)
            }
            return (waiting.map { Row.Done(it) } + items).take(MAX_ROWS)
        }

        /** The whole widget at [now] (also used by the tests). */
        fun views(context: Context, now: Instant = Instant.now()): RemoteViews {
            val c = L10n.context(context)
            val zone = Repo.zone()
            val rows = rows(Repo.state(context).tasks, recent(context, now.toEpochMilli()), now, zone)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(context, 200, Intent(context, MainActivity::class.java), flags)
            val add = PendingIntent.getActivity(context, 201, QuickEntry.quickAddIntent(context), flags)
            val v = RemoteViews(context.packageName, R.layout.widget_reminders)
            v.setTextViewText(R.id.w_title, c.getString(R.string.app_name))
            v.setContentDescription(R.id.w_add, c.getString(R.string.new_reminder))
            v.setOnClickPendingIntent(R.id.w_add, add)
            v.setOnClickPendingIntent(R.id.w_root, open)
            v.setViewVisibility(R.id.w_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w_empty, c.getString(R.string.nothing_ahead))
            v.removeAllViews(R.id.w_list)
            for (row in rows) v.addView(R.id.w_list, when (row) {
                is Row.Done -> doneRow(context, c, row.recent, flags)
                is Row.Item -> itemRow(context, c, row, now, zone, flags)
            })
            return v
        }

        private fun itemRow(context: Context, c: Context, row: Row.Item, now: Instant, zone: ZoneId, flags: Int): RemoteViews {
            val t = row.task
            val due = t.dueAt!!
            val r = RemoteViews(context.packageName, R.layout.widget_row)
            // The time, and under it «متأخر», «اليوم», «غدًا» or the date.
            val label = if (row.late) c.getString(R.string.late_short, "").trim() else L10n.dayText(c, due, now, zone)
            r.setTextViewText(R.id.w_time, L10n.time(c, due, zone) + "\n" + label)
            r.setTextColor(R.id.w_time, c.getColor(if (row.late) R.color.widget_error else R.color.widget_on_surface))
            r.setTextViewText(R.id.w_text, t.title)
            r.setViewVisibility(R.id.w_warn, if (row.late) View.VISIBLE else View.GONE)
            r.setContentDescription(R.id.w_row, L10n.join(c, listOf(L10n.isolate(c, t.title), L10n.dueText(c, due, now, zone), label.takeIf { row.late })))
            if (row.canComplete) {
                r.setViewVisibility(R.id.w_done, View.VISIBLE)
                r.setContentDescription(R.id.w_done, c.getString(R.string.widget_done_cd, L10n.isolate(c, t.title)))
                val tap = Intent(context, ReminderWidget::class.java).setAction(DONE)
                    .setData(("rememberme://widget/done/" + Uri.encode(t.id) + "/" + due.toEpochMilli()).toUri())
                    .putExtra(EXTRA_ID, t.id).putExtra(EXTRA_DUE, Codec.iso(due))
                r.setOnClickPendingIntent(R.id.w_done, PendingIntent.getBroadcast(context, 203, tap, flags))
            } else {
                // Later days: no circle; the time column stays aligned.
                r.setViewVisibility(R.id.w_done, View.INVISIBLE)
            }
            return r
        }

        private fun doneRow(context: Context, c: Context, recent: Recent, flags: Int): RemoteViews {
            val r = RemoteViews(context.packageName, R.layout.widget_row_done)
            r.setTextViewText(R.id.w_text, c.getString(R.string.widget_done_state, L10n.isolate(c, recent.title)))
            r.setTextViewText(R.id.w_undo, c.getString(R.string.undo))
            val tap = Intent(context, ReminderWidget::class.java).setAction(UNDO)
                .setData(("rememberme://widget/undo/" + Uri.encode(recent.id)).toUri()).putExtra(EXTRA_ID, recent.id)
            r.setOnClickPendingIntent(R.id.w_undo, PendingIntent.getBroadcast(context, 204, tap, flags))
            return r
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = views(context)
            for (id in ids) manager.updateAppWidget(id, v)
        }

        // ---- The rows waiting for «تراجع» (phone only, a few seconds long) ----

        fun recent(context: Context, nowMs: Long): List<Recent> {
            val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, null) ?: return emptyList()
            val arr = runCatching { Codec.json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                Recent(id, (o["copy"] as? JsonPrimitive)?.content ?: id, (o["title"] as? JsonPrimitive)?.content.orEmpty(),
                    (o["until"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }.filter { it.until > nowMs }
        }

        private fun writeRecent(context: Context, items: List<Recent>) {
            val arr = JsonArray(items.takeLast(MAX_ROWS).map {
                JsonObject(mapOf("id" to JsonPrimitive(it.id), "copy" to JsonPrimitive(it.copyId), "title" to JsonPrimitive(it.title), "until" to JsonPrimitive(it.until)))
            })
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_RECENT, arr.toString()) }
        }

        /** Draws the widget again once the undo time is over (not a wake-up: the screen is on after a tap). */
        private fun scheduleExpire(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(context, 205, Intent(context, ReminderWidget::class.java).setAction(EXPIRE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = SystemClock.elapsedRealtime() + Behaviour.UndoMillis + 500
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            runCatching { if (exact) am.setExact(AlarmManager.ELAPSED_REALTIME, at, pi) else am.set(AlarmManager.ELAPSED_REALTIME, at, pi) }
        }
    }
}
