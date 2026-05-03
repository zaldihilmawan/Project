package com.zaldi.gesturecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * AccessibilityService that receives gesture commands and executes
 * actual touch events (swipe, tap, etc.) across ALL installed apps.
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_GESTURE = "com.zaldi.gesturecontrol.ACTION_GESTURE"
        const val EXTRA_GESTURE = "gesture_type"

        // Swipe motion tracker (sent from overlay service as coordinates)
        const val EXTRA_DX = "delta_x"
        const val EXTRA_DY = "delta_y"
    }

    private var screenWidth = 0
    private var screenHeight = 0

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_GESTURE) return
            val gesture = intent.getStringExtra(EXTRA_GESTURE) ?: return
            handleGesture(gesture)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val filter = IntentFilter(ACTION_GESTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gestureReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(gestureReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ─── Gesture Router ────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleGesture(gesture: String) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        when (gesture) {
            // Scroll UP (e.g. next TikTok video)
            "SWIPE_UP" -> swipe(cx, cy + 400f, cx, cy - 400f, 300)

            // Scroll DOWN
            "SWIPE_DOWN" -> swipe(cx, cy - 400f, cx, cy + 400f, 300)

            // Swipe LEFT
            "SWIPE_LEFT" -> swipe(cx + 400f, cy, cx - 400f, cy, 300)

            // Swipe RIGHT
            "SWIPE_RIGHT" -> swipe(cx - 400f, cy, cx + 400f, cy, 300)

            // Tap center (e.g. pause/play)
            "FIST_TAP" -> tap(cx, cy)

            // Double tap (e.g. Like on TikTok/Instagram)
            "THUMBS_LIKE" -> {
                tap(cx, cy)
                android.os.Handler(mainLooper).postDelayed({ tap(cx, cy) }, 100)
            }

            // Open hand: simulate media key (pause/play)
            "OPEN_HAND" -> tap(cx, cy)

            // Peace sign: screenshot via global action
            "PEACE_SCREENSHOT" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

            // One finger: gentle scroll up
            "ONE_FINGER_SCROLL_UP" -> swipe(cx, cy + 200f, cx, cy - 200f, 250)

            // Shaka: volume up simulation via swipe on volume bar
            "SHAKA_VOLUME" -> volumeUp()

            // Back navigation
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)

            // Home
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)

            // Recent apps
            "RECENT" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    // ─── Touch Simulation Primitives ──────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.N)
    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun volumeUp() {
        // Swipe the volume slider area (top-right typically)
        swipe(screenWidth - 30f, screenHeight * 0.3f, screenWidth - 30f, screenHeight * 0.2f, 200)
    }
}
