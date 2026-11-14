package com.fastkiwi.rememberme

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import com.fastkiwi.rememberme.alarm.RingService
import com.fastkiwi.rememberme.core.AppState
import com.fastkiwi.rememberme.core.Codec
import com.fastkiwi.rememberme.data.AlertLog
import com.fastkiwi.rememberme.core.Tasks
import com.fastkiwi.rememberme.ui.AlertScreen
import com.fastkiwi.rememberme.ui.TestAlertScreen
import com.fastkiwi.rememberme.alarm.TestAlarm
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
    /** Opened by «اختبر التنبيه الآن»: shows the test reminder instead of the queue. */
    private var test by mutableStateOf(false)
    /** Opened by «أجّل حتى…» on the notification: the time picker comes first (F-044). */
    private var pick by mutableStateOf(false)
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
        test = intent.getBooleanExtra(EXTRA_TEST, false)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        if (test) TestAlarm.screenShown(this)
        else first?.let { id -> Repo.state(this).tasks.firstOrNull { it.id == id }?.dueAt?.let { due ->
            runCatching { AlertLog(this).add(AlertLog.Event.SCREEN_SHOWN, AlertLog(this).keyOf(id, Codec.iso(due))) } } }
        setContent {
            val state by Repo.store(this).state.collectAsState()
            AppTheme(Repo.theme(state)) { Content(state) }
            LaunchedEffect(Unit) { while (true) { delay(15_000); tick = System.currentTimeMillis() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        first = intent.getStringExtra(EXTRA_ID) ?: first
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        if (intent.getBooleanExtra(EXTRA_TEST, false)) { test = true; TestAlarm.screenShown(this) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(state: AppState) {
        val now = Instant.ofEpochMilli(maxOf(tick, System.currentTimeMillis()))
        if (test) {
            TestAlertScreen(now, onDone = { TestAlarm.actionDone(this); finish() })
            return
        }
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
            pick = false
            RingService.stop(this)
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
            pickFirst = pick && task.id == first,
        )
    }

    /** A volume key on the reminder screen silences the ringing (F-016); the reminder stays. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && RingService.ringingId != 0) {
            RingService.stop(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
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

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TEST = "test"
        const val EXTRA_PICK = "pick"
    }
}
