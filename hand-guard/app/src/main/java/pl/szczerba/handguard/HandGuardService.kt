package pl.szczerba.handguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Counts WHAT KIND of interaction happens (click, scroll, text change, key)
 * and in which app. It never reads screen content or typed text.
 */
class HandGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage: String? = null
    private var ticks = 0
    private var receiverRegistered = false

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            Store.heartbeat(now)
            if (++ticks % 2 == 0) Store.flush(now) // save every ~60 s
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> Store.screenOff(now)
                Intent.ACTION_SCREEN_ON -> Store.screenOn(now)
                Intent.ACTION_SHUTDOWN -> Store.phoneShuttingDown(now)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val pm = getSystemService(PowerManager::class.java)
        Store.serviceStarted(System.currentTimeMillis(), pm?.isInteractive ?: true)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true

        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> EventKind.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> EventKind.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_SELECTED -> EventKind.SELECT
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> EventKind.SCROLL
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> EventKind.TEXT
            else -> return
        }
        if (pkg != null) lastPackage = pkg
        Store.recordEvent(System.currentTimeMillis(), kind, pkg)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Store.recordEvent(System.currentTimeMillis(), EventKind.KEY, lastPackage)
        }
        return false // never swallow the key
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        // Called when the user turns the service off in settings.
        Store.serviceStopped(System.currentTimeMillis())
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Store.flush()
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacks(tick)
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    companion object {
        private const val TICK_MS = 30_000L
    }
}
