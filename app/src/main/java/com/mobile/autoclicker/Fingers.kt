package com.mobile.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper

// Virtual fingers that stay down until released, for held keys and the joystick.
// Each change sends one gesture that continues every finger still down, because a new
// gesture cancels any stroke it does not continue. Needs Android 8.0; ClickService only
// creates it there.
class Fingers(private val service: AccessibilityService) {
    // home is where the finger first went down; it touches down there again after a cancel.
    private class Finger(var x: Float, var y: Float) {
        val homeX = x
        val homeY = y
        var stroke: StrokeDescription? = null
        var sentX = x
        var sentY = y
    }

    private val handler = Handler(Looper.getMainLooper())
    private val down = LinkedHashMap<Any, Finger>()
    private val maxFingers = GestureDescription.getMaxStrokeCount()
    private var flushPending = false
    private val flushRunnable = Runnable {
        flushPending = false
        send()
    }

    // No finger down and no gesture of ours still playing, such as a release.
    val isIdle get() = down.isEmpty() && !inFlight
    private var inFlight = false

    fun press(id: Any, x: Float, y: Float) {
        if (id in down || down.size >= maxFingers) return
        down[id] = Finger(x, y)
        send()
    }

    // Moves are batched about once a frame unless now is set, for callers that already run once a frame.
    fun move(id: Any, x: Float, y: Float, now: Boolean = false) {
        val finger = down[id] ?: return
        finger.x = x
        finger.y = y
        if (now) {
            send()
        } else if (!flushPending) {
            flushPending = true
            handler.postDelayed(flushRunnable, FRAME_MS)
        }
    }

    fun release(id: Any) {
        if (id !in down) return
        send(lift = id)
    }

    fun releaseAll() {
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(keepAliveRunnable)
        flushPending = false
        if (down.isNotEmpty()) send(lift = ALL)
    }

    // A real touch on the screen makes Android cancel the injected fingers. Held fingers are
    // re-sent every so often; when Android reports the latest gesture cancelled, they go back
    // down as new touches a moment later, at their home point. Games with a floating stick
    // center it where the touch lands, so landing anywhere else would shift the stick.
    private var sequence = 0
    private val keepAliveRunnable = Runnable { if (down.isNotEmpty()) send() }

    private fun lost() {
        down.values.forEach { it.stroke = null }
        handler.removeCallbacks(keepAliveRunnable)
        handler.postDelayed(keepAliveRunnable, RETRY_MS)
    }

    private fun send(lift: Any? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(keepAliveRunnable)
        flushPending = false
        val builder = GestureDescription.Builder()
        val iterator = down.entries.iterator()
        val lifted = mutableListOf<Any>()
        var strokes = 0
        while (iterator.hasNext()) {
            val (id, f) = iterator.next()
            val up = lift === ALL || id == lift
            val previous = f.stroke
            if (up) lifted.add(id)
            // A finger that was cancelled is already up; touching down again just to lift would be a tap.
            if (up && previous == null) continue
            val path = Path()
            // Android cancels a held finger that is continued without moving, so a finger
            // that stays put steps 1 px aside and back, well under any app's touch slop.
            var toX = f.x
            var toY = f.y
            if (previous == null) {
                toX = f.homeX
                toY = f.homeY
            } else if (!up && f.x == f.sentX && f.y == f.sentY) {
                toX = f.x + 1f
            }
            val stroke = if (previous == null) {
                path.moveTo(toX, toY)
                StrokeDescription(path, 0, 1, !up)
            } else {
                path.moveTo(f.sentX, f.sentY)
                if (toX != f.sentX || toY != f.sentY) path.lineTo(toX, toY)
                previous.continueStroke(path, 0, FRAME_MS, !up)
            }
            f.stroke = stroke
            f.sentX = toX
            f.sentY = toY
            builder.addStroke(stroke)
            strokes++
        }
        lifted.forEach { down.remove(it) }
        if (strokes == 0) return
        // A finger that touched down at home while it should be elsewhere moves there next frame.
        val behind = down.values.any { it.sentX != it.x || it.sentY != it.y }
        val id = ++sequence
        // A gesture replaced by a newer one of ours also reports cancelled; only the latest counts.
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (id == sequence) inFlight = false
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (id != sequence) return
                inFlight = false
                if (down.isNotEmpty()) lost()
            }
        }
        inFlight = true
        if (!service.dispatchGesture(builder.build(), callback, null)) {
            inFlight = false
            lost()
        }
        else if (down.isNotEmpty()) handler.postDelayed(keepAliveRunnable, if (behind) FRAME_MS else KEEP_ALIVE_MS)
    }

    companion object {
        private const val FRAME_MS = 16L
        private const val KEEP_ALIVE_MS = 100L
        private const val RETRY_MS = 250L
        private val ALL = Any()
    }
}
