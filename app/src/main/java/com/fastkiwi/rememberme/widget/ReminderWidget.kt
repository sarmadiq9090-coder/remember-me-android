package com.fastkiwi.rememberme.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.ui.L10n
import java.time.Instant

/** Home-screen widget: the next reminders (overdue first) and a + button for a new one. */
class ReminderWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids)

    companion object {
        private val ROWS = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4)
        private val TIMES = intArrayOf(R.id.w_time1, R.id.w_time2, R.id.w_time3, R.id.w_time4)
        private val TITLES = intArrayOf(R.id.w_title1, R.id.w_title2, R.id.w_title3, R.id.w_title4)
        private val WARNS = intArrayOf(R.id.w_warn1, R.id.w_warn2, R.id.w_warn3, R.id.w_warn4)

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReminderWidget::class.java))
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val c = L10n.context(context)
            val now = Instant.now()
            val items = Repo.state(context).tasks.filter { it.active && it.dueAt != null }.sortedBy { it.dueAt }.take(ROWS.size)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(context, 200, Intent(context, MainActivity::class.java), flags)
            val add = PendingIntent.getActivity(context, 201, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW), flags)
            for (id in ids) {
                val v = RemoteViews(context.packageName, R.layout.widget_reminders)
                v.setTextViewText(R.id.w_title, c.getString(R.string.app_name))
                v.setContentDescription(R.id.w_add, c.getString(R.string.new_reminder))
                v.setOnClickPendingIntent(R.id.w_add, add)
                v.setOnClickPendingIntent(R.id.w_root, open)
                v.setViewVisibility(R.id.w_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.w_empty, c.getString(R.string.nothing_ahead))
                ROWS.forEachIndexed { i, row ->
                    val t = items.getOrNull(i)
                    if (t == null) v.setViewVisibility(row, View.GONE) else {
                        v.setViewVisibility(row, View.VISIBLE)
                        val late = !t.dueAt!!.isAfter(now)
                        val zone = Repo.zone()
                        val sameDay = t.dueAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
                        // The time, and under it «متأخر», «غدًا» or the date; nothing extra for later today.
                        val label = when {
                            late -> c.getString(R.string.late_short, "").trim()
                            sameDay -> null
                            else -> L10n.dayText(c, t.dueAt, now)
                        }
                        v.setTextViewText(TIMES[i], listOfNotNull(L10n.time(c, t.dueAt), label).joinToString("\n"))
                        v.setTextColor(TIMES[i], c.getColor(if (late) R.color.widget_error else R.color.widget_on_surface))
                        v.setTextViewText(TITLES[i], t.title)
                        v.setViewVisibility(WARNS[i], if (late) View.VISIBLE else View.GONE)
                        v.setContentDescription(row, L10n.join(c, listOf(t.title, L10n.dueText(c, t.dueAt, now), label.takeIf { late })))
                    }
                }
                manager.updateAppWidget(id, v)
            }
        }
    }
}
