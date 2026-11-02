package com.mobile.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.hypot

// Root of the floating editor card; the Back key closes it.
class EditorFrame(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && onBack != null) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

// Marker: a tinted halo with a labelled badge at the exact touch point, such as an action
// number or a key name. A null label draws a swipe end point instead.
class MarkerView(context: Context, label: String?, accent: Int, onAccent: Int) : View(context) {
    private val density = context.resources.displayMetrics.density

    var label: String? = label
        set(value) {
            field = value
            invalidate()
        }
    private val shadow = Color.argb(150, 0, 0, 0)

    // White edges with a soft shadow keep the marker visible on light, dark and same-colored backgrounds.
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        setShadowLayer(2 * density, 0f, 0f, shadow)
    }
    private val badgeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2 * density, 0f, 0f, shadow)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or ((if (label == null) 40 else 70) shl 24) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onAccent
        textSize = 12 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val badgeRect = RectF()

    init {
        // Shadow layers need software drawing.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 5 * density
        canvas.drawCircle(cx, cy, r + 2 * density, outline)
        canvas.drawCircle(cx, cy, r, halo)
        canvas.drawCircle(cx, cy, r, ring)
        val text = label
        if (text == null) {
            canvas.drawCircle(cx, cy, 7.5f * density, badgeEdge)
            canvas.drawCircle(cx, cy, 6 * density, badge)
        } else {
            // Round for one or two characters, a pill for longer names such as "SPACE".
            val half = maxOf(11 * density, this.text.measureText(text) / 2 + 6 * density).coerceAtMost(cx - 1.5f * density)
            badgeRect.set(cx - half, cy - 11 * density, cx + half, cy + 11 * density)
            badgeRect.inset(-1.5f * density, -1.5f * density)
            canvas.drawRoundRect(badgeRect, 12.5f * density, 12.5f * density, badgeEdge)
            badgeRect.inset(1.5f * density, 1.5f * density)
            canvas.drawRoundRect(badgeRect, 11 * density, 11 * density, badge)
            canvas.drawText(text, cx, cy - (this.text.descent() + this.text.ascent()) / 2, this.text)
        }
    }
}

// Full-screen, touch-through layer that draws an arrow for each swipe, from start marker to end marker.
class SwipeLinesView(context: Context, accent: Int, private val markerRadius: Float) : View(context) {
    private val density = context.resources.displayMetrics.density

    // Each entry is startX, startY, endX, endY in this view's coordinates.
    var segments: List<FloatArray> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val arrow = Path()

    override fun onDraw(canvas: Canvas) {
        for (s in segments) {
            val dx = s[2] - s[0]
            val dy = s[3] - s[1]
            val length = hypot(dx, dy)
            if (length < markerRadius * 2 + 4 * density) continue
            val ux = dx / length
            val uy = dy / length
            val sx = s[0] + ux * markerRadius
            val sy = s[1] + uy * markerRadius
            val ex = s[2] - ux * markerRadius
            val ey = s[3] - uy * markerRadius
            val size = 10 * density
            arrow.reset()
            arrow.moveTo(ex, ey)
            arrow.lineTo(ex - ux * size - uy * size * 0.6f, ey - uy * size + ux * size * 0.6f)
            arrow.lineTo(ex - ux * size + uy * size * 0.6f, ey - uy * size - ux * size * 0.6f)
            arrow.close()
            canvas.drawLine(sx, sy, ex - ux * size * 0.5f, ey - uy * size * 0.5f, edge)
            canvas.drawPath(arrow, edge)
            canvas.drawLine(sx, sy, ex - ux * size * 0.5f, ey - uy * size * 0.5f, line)
            canvas.drawPath(arrow, head)
        }
    }
}

// Joystick: a large circle whose center is the held finger. While listening, a thumb
// dot shows where the direction keys have pushed the finger, as an offset from the center.
class JoystickView(context: Context, accent: Int) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val shadow = Color.argb(150, 0, 0, 0)
    private var thumbX = 0f
    private var thumbY = 0f

    var showThumb = false
        set(value) {
            field = value
            invalidate()
        }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        setShadowLayer(2 * density, 0f, 0f, shadow)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or (45 shl 24) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2 * density, 0f, 0f, shadow)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setThumb(x: Float, y: Float) {
        thumbX = x
        thumbY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 5 * density
        canvas.drawCircle(cx, cy, r + 2 * density, outline)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, 5.5f * density, dotEdge)
        canvas.drawCircle(cx, cy, 4 * density, dot)
        if (showThumb) {
            canvas.drawCircle(cx + thumbX, cy + thumbY, 15.5f * density, dotEdge)
            canvas.drawCircle(cx + thumbX, cy + thumbY, 14 * density, dot)
        }
    }
}

// Short name for a key code, such as "A", "SPACE" or "F1".
fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
