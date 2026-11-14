package com.fastkiwi.rememberme

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.ui.AlertScreen
import com.fastkiwi.rememberme.ui.AppTheme
import com.fastkiwi.rememberme.ui.L10n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The reminder screen (D-005), opened by the full-screen intent, by tapping the notification, or from
 * the Overdue list. It works over the lock screen; opening the task for editing asks to unlock first.
 * It closes by itself once nothing is due.
 */
class AlertActivity : AppCompatActivity() {
    private var first by mutableStateOf<String?>(null)
    private var tick by mutableLongStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        L10n.apply(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        first = intent.getStringExtra(EXTRA_ID)
        setContent {
            val state by Repo.store(this).state.collectAsState()
            AppTheme(Repo.theme(state)) { Content(state) }
            LaunchedEffect(Unit) { while (true) { delay(15_000); tick = System.currentTimeMillis() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        first = intent.getStringExtra(EXTRA_ID) ?: first
    }

    @androidx.compose.runtime.Composable
    private fun Content(state: AppState) {
        val now = Instant.ofEpochMilli(maxOf(tick, System.currentTimeMillis()))
        val queue = Tasks.due(state, now)
        if (queue.isEmpty()) {
            LaunchedEffect(Unit) { finish() }
            return
        }
        val index = queue.indexOfFirst { it.id == first }.takeIf { it >= 0 } ?: 0
        val task = queue[index]
        val zone = Repo.zone()
        val due = task.dueAt
        fun act(block: (AppState) -> AppState?) {
            first = null
            lifecycleScope.launch(Dispatchers.IO) { Repo.change(this@AlertActivity) { s -> block(s) ?: s } }
        }
        AlertScreen(
            task = task, position = index + 1, count = queue.size, presets = Repo.presets(state), now = now,
            onDone = { act { s -> Tasks.complete(s, task.id, Instant.now(), zone, due, checked = true)?.first } },
            onSkip = { act { s -> Tasks.skip(s, task.id, Instant.now(), zone, due, checked = true) } },
            onSnooze = { at -> act { s -> runCatching { Tasks.snooze(s, task.id, at, Instant.now(), due, checked = true) }.getOrNull() } },
            onEdit = { openInApp(task.id) },
            onLater = { finish() },
            // Lock-screen privacy: the title and note wait for the unlock.
            hidden = Repo.prefs(this).lockPrivacy && getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
    }

    private fun openInApp(id: String) {
        val open = { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { open() }
            })
        } else open()
    }

    companion object { const val EXTRA_ID = "id" }
}
