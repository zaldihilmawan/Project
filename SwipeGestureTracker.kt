package com.zaldi.gesturecontrol

import kotlin.math.abs

/**
 * Tracks hand landmark positions across frames to detect directional swipe gestures.
 * Uses wrist position delta to determine swipe direction.
 * Lightweight — no ML, no allocations per frame.
 */
class SwipeGestureTracker {

    private var prevX = -1f
    private var prevY = -1f
    private val MIN_SWIPE_DELTA = 0.08f   // minimum movement % of screen to count as swipe
    private val RESET_FRAMES = 8          // frames before resetting prev position

    private var frameCount = 0
    private var lastSwipeDirection = ""
    private var lockFrames = 0            // prevent double-firing

    /**
     * Feed normalized wrist position (0..1 range from MediaPipe).
     * Returns swipe direction string or empty string if no swipe detected.
     */
    fun update(wristX: Float, wristY: Float): String {
        frameCount++

        if (lockFrames > 0) {
            lockFrames--
            prevX = wristX
            prevY = wristY
            return ""
        }

        if (prevX < 0f) {
            prevX = wristX
            prevY = wristY
            return ""
        }

        val dx = wristX - prevX
        val dy = wristY - prevY

        // Reset tracking if hand stays still
        if (frameCount > RESET_FRAMES) {
            prevX = wristX
            prevY = wristY
            frameCount = 0
            lastSwipeDirection = ""
            return ""
        }

        var result = ""

        if (abs(dy) > abs(dx) && abs(dy) > MIN_SWIPE_DELTA) {
            result = if (dy < 0) "SWIPE_UP" else "SWIPE_DOWN"
        } else if (abs(dx) > abs(dy) && abs(dx) > MIN_SWIPE_DELTA) {
            result = if (dx < 0) "SWIPE_LEFT" else "SWIPE_RIGHT"
        }

        if (result.isNotEmpty() && result != lastSwipeDirection) {
            lastSwipeDirection = result
            prevX = wristX
            prevY = wristY
            frameCount = 0
            lockFrames = 6 // lock 6 frames after swipe to prevent repeat
            return result
        }

        return ""
    }

    fun reset() {
        prevX = -1f
        prevY = -1f
        frameCount = 0
        lastSwipeDirection = ""
        lockFrames = 0
    }
}
