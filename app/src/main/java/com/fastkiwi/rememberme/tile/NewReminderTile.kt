package com.fastkiwi.rememberme.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.L10n
import com.fastkiwi.rememberme.ui.quick.QuickEntry

/**
 * Quick Settings tile «تذكير جديد» (DESIGN-SPEC §5.23, F-123): a tap opens Quick Add. Bound only by the system
 * (BIND_QUICK_SETTINGS_TILE is a binding permission, not a uses-permission). On the lock screen Android asks to
 * unlock first. Long-press opens the app (QS_TILE_PREFERENCES on MainActivity).
 */
class NewReminderTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val c = L10n.context(this)
        tile.label = c.getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = c.getString(R.string.tile_subtitle)
        tile.contentDescription = c.getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_bell)
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 300, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** What the tile opens: Quick Add in the app's own task. */
        fun intent(context: Context): Intent = QuickEntry.quickAddIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
