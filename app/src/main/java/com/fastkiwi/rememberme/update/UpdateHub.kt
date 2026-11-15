package com.fastkiwi.rememberme.update

import android.content.Context
import android.widget.Toast
import com.fastkiwi.rememberme.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's one [UpdateController], for the whole process (bug hunt H1/H2): a rotation or a reopened screen keeps the
 * check, the download and the install in progress, and the installer's report reaches the controller the screen shows.
 * It holds the application context only. Rebuilt when the Application changes (each Robolectric test has its own).
 */
object UpdateHub {
    @Volatile private var app: Context? = null
    @Volatile private var controller: UpdateController? = null

    @Synchronized
    fun controller(context: Context): UpdateController {
        val a = context.applicationContext
        controller?.let { if (app === a) return it }
        val c = UpdateController(a, UpdatePrefs(a), CoroutineScope(SupervisorJob() + Dispatchers.Main), onAvailable = { r ->
            Toast.makeText(a, a.getString(R.string.update_available_msg, r.version.toString()), Toast.LENGTH_LONG).show()
        })
        app = a
        controller = c
        return c
    }
}
