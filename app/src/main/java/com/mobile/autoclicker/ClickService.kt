package com.mobile.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.hypot
import kotlin.random.Random
import com.google.android.material.R as MR

class ClickService : AccessibilityService() {

    companion object {
        var instance: ClickService? = null
            private set(value) {
                field = value
                stateListener?.invoke()
            }

        // Lets the open settings screen refresh when the service turns on or off.
        var stateListener: (() -> Unit)? = null
    }

    private class Marker(val view: MarkerView, val params: WindowManager.LayoutParams)

    // A swipe has an end marker; a tap does not.
    private class Item(val action: Action, val start: Marker, val end: Marker?)

    private lateinit var wm: WindowManager
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())

    // Themed context so the overlay uses the same Material colors as the app.
    private lateinit var ui: Context
    private var nightMode = 0
    private var panel: LinearLayout? = null
    private var lines: SwipeLinesView? = null
    private var playButton: ImageView? = null
    private var playBackground: GradientDrawable? = null
    private var runLabel: TextView? = null
    private var editor: View? = null
    private val editButtons = mutableListOf<View>()
    private val items = mutableListOf<Item>()

    private var running = false
    private var index = 0
    private var cycles = 0
    private var countdown = 0
    private var intervalMs = 500L
    private var randomIntervalMs = 0L
    private var holdMs = 10L
    private var swipeMs = 300L
    private var repeat = 0
    private var randomPx = 0
    private var timeLimitMs = 0L

    private val stepRunnable = Runnable { step() }
    private val stopRunnable = Runnable { stop() }
    private val countdownRunnable = Runnable { countdownTick() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightMode != nightMode) {
            nightMode = newNightMode
            // Rebuild the overlay so it picks up the light or dark colors.
            if (panel != null && !running) {
                hidePanel()
                showPanel()
            }
        }
    }

    override fun onDestroy() {
        stop()
        hidePanel()
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(attr: Int): Int = MaterialColors.getColor(ui, attr, Color.GRAY)

    private val markerSize get() = dp(56)

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    // ---------- Floating panel ----------

    fun showPanel() {
        if (panel != null) return
        ui = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(this, R.style.Theme_AutoClicker))

        // Swipe arrows sit under everything else and never take touches.
        val lineView = SwipeLinesView(ui, color(MR.attr.colorPrimary), markerSize / 2f)
        val lineParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0)
        lineParams.flags = lineParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.addView(lineView, lineParams)
        lines = lineView

        val layout = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(color(MR.attr.colorSurfaceContainerHigh))
                setStroke(dp(1), color(MR.attr.colorOutlineVariant))
            }
        }
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, dp(8), resources.displayMetrics.heightPixels / 4)

        val handle = FrameLayout(ui).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(24))
            contentDescription = getString(R.string.panel_move)
            val bar = View(ui).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(color(MR.attr.colorOutline))
                }
            }
            addView(bar, FrameLayout.LayoutParams(dp(20), dp(4), Gravity.CENTER))
        }
        makeDraggable(handle, layout, params)
        layout.addView(handle)

        val playShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        playBackground = playShape
        playButton = ImageView(ui).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), playShape, null)
            setOnClickListener { if (running) stop() else start() }
        }
        layout.addView(playButton)

        runLabel = TextView(ui).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(MR.attr.colorOnSurfaceVariant))
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        layout.addView(runLabel)

        val divider = View(ui).apply { setBackgroundColor(color(MR.attr.colorOutlineVariant)) }
        layout.addView(divider, LinearLayout.LayoutParams(dp(24), dp(1)).apply { setMargins(0, dp(8), 0, dp(4)) })

        editButtons.clear()
        editButtons.add(iconButton(R.drawable.ic_tap, R.string.panel_add_tap) { addTap() })
        editButtons.add(iconButton(R.drawable.ic_swipe, R.string.panel_add_swipe) { addSwipe() })
        editButtons.add(iconButton(R.drawable.ic_remove, R.string.panel_remove) { removeLast() })
        editButtons.forEach { layout.addView(it) }
        layout.addView(iconButton(R.drawable.ic_close, R.string.panel_hide) { hidePanel() })

        wm.addView(layout, params)
        panel = layout
        updateControls()

        loadItems()
        if (items.isEmpty()) addTap()
    }

    fun hidePanel() {
        if (!::wm.isInitialized) return
        stop()
        editor?.let { wm.removeView(it) }
        editor = null
        if (panel != null) saveActions()
        clearItems()
        panel?.let { wm.removeView(it) }
        lines?.let { wm.removeView(it) }
        panel = null
        lines = null
        playButton = null
        playBackground = null
        runLabel = null
        editButtons.clear()
    }

    // Rebuilds the markers from saved settings, for example after a macro is loaded.
    fun reloadActions() {
        if (panel == null) return
        stop()
        if (editor != null) closeEditor()
        clearItems()
        loadItems()
    }

    private fun iconButton(icon: Int, label: Int, onClick: () -> Unit): ImageView {
        val ripple = TypedValue()
        ui.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        return ImageView(ui).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(MR.attr.colorOnSurfaceVariant))
            setBackgroundResource(ripple.resourceId)
            contentDescription = getString(label)
            setOnClickListener { onClick() }
        }
    }

    private fun updateControls() {
        val play = playButton ?: return
        val active = running
        play.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
        play.contentDescription = getString(if (active) R.string.panel_stop else R.string.panel_start)
        play.imageTintList = ColorStateList.valueOf(color(if (active) MR.attr.colorOnError else MR.attr.colorOnPrimary))
        playBackground?.setColor(color(if (active) MR.attr.colorError else MR.attr.colorPrimary))
        runLabel?.visibility = if (active) View.VISIBLE else View.GONE
        editButtons.forEach {
            it.isEnabled = !active
            it.alpha = if (active) 0.38f else 1f
        }
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        panel?.visibility = v
        lines?.visibility = v
        items.forEach {
            it.start.view.visibility = v
            it.end?.view?.visibility = v
        }
    }

    // ---------- Actions and markers ----------

    private fun loadItems() {
        settings.actions.forEach { addItem(it) }
        updateLines()
    }

    private fun clearItems() {
        items.forEach {
            wm.removeView(it.start.view)
            it.end?.let { end -> wm.removeView(end.view) }
        }
        items.clear()
        updateLines()
    }

    private fun addItem(action: Action) {
        val start = addMarker(action.x, action.y, (items.size + 1).toString())
        val end = if (action.isSwipe) addMarker(action.x2, action.y2, null) else null
        val item = Item(action, start, end)
        items.add(item)
        listOfNotNull(start, end).forEach { marker ->
            makeDraggable(marker.view, marker.view, marker.params, onMove = { updateLines() }, onDrop = { saveActions() }, onClick = { openEditor(items.indexOf(item)) })
        }
    }

    private fun addMarker(x: Int, y: Int, label: String?): Marker {
        val view = MarkerView(ui, label, color(MR.attr.colorPrimary), color(MR.attr.colorOnPrimary))
        val params = overlayParams(markerSize, markerSize, x, y)
        wm.addView(view, params)
        return Marker(view, params)
    }

    private fun addTap() {
        if (running) return
        val dm = resources.displayMetrics
        val offset = items.size * dp(24)
        addItem(Action(Action.TAP, dm.widthPixels / 2 - markerSize / 2 + offset, dm.heightPixels / 2 - markerSize / 2 + offset))
        updateLines()
        saveActions()
    }

    // New swipes go upward through the middle of the screen, the usual scroll gesture.
    private fun addSwipe() {
        if (running) return
        val dm = resources.displayMetrics
        val offset = items.size * dp(24)
        val x = dm.widthPixels / 2 - markerSize / 2 + offset
        addItem(Action(Action.SWIPE, x, dm.heightPixels * 2 / 3 - markerSize / 2, x, dm.heightPixels / 3 - markerSize / 2))
        updateLines()
        saveActions()
    }

    private fun removeLast() {
        if (running || items.isEmpty()) return
        val item = items.removeAt(items.size - 1)
        wm.removeView(item.start.view)
        item.end?.let { wm.removeView(it.view) }
        updateLines()
        saveActions()
    }

    private fun saveActions() {
        items.forEach {
            it.action.x = it.start.params.x
            it.action.y = it.start.params.y
            it.end?.let { end ->
                it.action.x2 = end.params.x
                it.action.y2 = end.params.y
            }
        }
        settings.actions = items.map { it.action }
    }

    private fun updateLines() {
        val half = markerSize / 2f
        lines?.segments = items.mapNotNull { item ->
            item.end?.let { floatArrayOf(item.start.params.x + half, item.start.params.y + half, it.params.x + half, it.params.y + half) }
        }
    }

    // Tapping a marker opens a floating card for that action's own delay and duration.
    // It is an overlay window rather than an activity so launchers cannot put it in a pop-up frame.
    private fun openEditor(i: Int) {
        if (running || i < 0 || editor != null) return
        saveActions()
        val action = items[i].action
        val frame = LayoutInflater.from(ui).inflate(R.layout.overlay_editor, null) as EditorFrame
        val delayLayout = frame.findViewById<TextInputLayout>(R.id.delayLayout)
        val delay = frame.findViewById<TextInputEditText>(R.id.delay)
        val durationLayout = frame.findViewById<TextInputLayout>(R.id.durationLayout)
        val duration = frame.findViewById<TextInputEditText>(R.id.duration)

        val type = getString(if (action.isSwipe) R.string.type_swipe else R.string.type_tap)
        frame.findViewById<TextView>(R.id.editorTitle).text = getString(R.string.edit_title, i + 1, type)
        delayLayout.helperText = getString(R.string.edit_default, settings.intervalMs)
        durationLayout.hint = getString(if (action.isSwipe) R.string.swipe_duration else R.string.hold)
        durationLayout.helperText = getString(R.string.edit_default, if (action.isSwipe) settings.swipeMs else settings.holdMs)
        if (action.delayMs >= 0) delay.setText(action.delayMs.toString())
        if (action.durationMs > 0) duration.setText(action.durationMs.toString())

        frame.onBack = { closeEditor() }
        frame.findViewById<View>(R.id.editorCancel).setOnClickListener { closeEditor() }
        frame.findViewById<View>(R.id.editorDelete).setOnClickListener {
            settings.actions = items.map { it.action }.filterIndexed { index, _ -> index != i }
            closeEditor()
            clearItems()
            loadItems()
        }
        // A blank field means "use the default".
        frame.findViewById<View>(R.id.editorSave).setOnClickListener {
            val delayText = delay.text.toString()
            val durationText = duration.text.toString()
            val delayValue = if (delayText.isBlank()) Action.DEFAULT else delayText.toLongOrNull() ?: -2L
            val durationValue = if (durationText.isBlank()) Action.DEFAULT else durationText.toLongOrNull() ?: -2L
            val delayOk = delayValue == Action.DEFAULT || delayValue >= Settings.MIN_DELAY_MS
            val durationOk = durationValue == Action.DEFAULT || durationValue in 1..Settings.MAX_GESTURE_MS
            delayLayout.error = if (delayOk) null else getString(R.string.error_interval)
            durationLayout.error = if (durationOk) null else getString(R.string.error_duration)
            if (delayOk && durationOk) {
                action.delayMs = delayValue
                action.durationMs = durationValue
                saveActions()
                closeEditor()
            }
        }

        // Focusable (for the keyboard) but not modal, so the keyboard below it still gets touches.
        // It sits near the top so the keyboard does not cover it.
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(minOf(dm.widthPixels - dp(16), dp(416)), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(32)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        // Hide the panel and markers so they do not cover the card.
        setOverlaysVisible(false)
        wm.addView(frame, params)
        editor = frame
    }

    private fun closeEditor() {
        editor?.let { wm.removeView(it) }
        editor = null
        setOverlaysVisible(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(touchView: View, moveView: View, params: WindowManager.LayoutParams, onMove: (() -> Unit)? = null, onDrop: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
        val slop = ViewConfiguration.get(ui).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        touchView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(moveView, params)
                        onMove?.invoke()
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) onDrop?.invoke() else onClick?.invoke()
            }
            true
        }
    }

    // Markers must let touches pass through while running, otherwise they would catch their own taps.
    private fun setMarkersTouchable(touchable: Boolean) {
        items.flatMap { listOfNotNull(it.start, it.end) }.forEach {
            it.params.flags = if (touchable) it.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else it.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            it.view.alpha = if (touchable) 1f else 0.6f
            wm.updateViewLayout(it.view, it.params)
        }
        lines?.alpha = if (touchable) 1f else 0.6f
    }

    // ---------- Running ----------

    private fun start() {
        if (running) return
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.toast_add_action, Toast.LENGTH_SHORT).show()
            return
        }
        saveActions()
        intervalMs = settings.intervalMs
        randomIntervalMs = settings.randomIntervalMs
        holdMs = settings.holdMs
        swipeMs = settings.swipeMs
        repeat = settings.repeat
        randomPx = settings.randomPx
        timeLimitMs = settings.timeLimitSec * 1000
        countdown = settings.startDelaySec
        index = 0
        cycles = 0
        running = true
        updateControls()
        setMarkersTouchable(false)
        if (countdown > 0) countdownTick() else begin()
    }

    private fun countdownTick() {
        if (!running) return
        if (countdown <= 0) {
            begin()
            return
        }
        runLabel?.text = getString(R.string.run_countdown, countdown)
        countdown--
        handler.postDelayed(countdownRunnable, 1000)
    }

    private fun begin() {
        if (!running) return
        updateRunLabel()
        if (timeLimitMs > 0) handler.postDelayed(stopRunnable, timeLimitMs)
        // Give the window manager a moment to apply the pass-through flag before the first action.
        handler.postDelayed(stepRunnable, 150)
    }

    private fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(stopRunnable)
        handler.removeCallbacks(countdownRunnable)
        updateControls()
        setMarkersTouchable(true)
    }

    private fun updateRunLabel() {
        runLabel?.text = if (repeat > 0) getString(R.string.run_cycle_of, cycles + 1, repeat) else getString(R.string.run_cycle, cycles + 1)
    }

    private fun touchPoint(marker: Marker): PointF {
        val loc = IntArray(2)
        marker.view.getLocationOnScreen(loc)
        var x = loc[0] + marker.view.width / 2f
        var y = loc[1] + marker.view.height / 2f
        if (randomPx > 0) {
            x += Random.nextInt(-randomPx, randomPx + 1)
            y += Random.nextInt(-randomPx, randomPx + 1)
        }
        // Gesture paths may not have negative coordinates.
        return PointF(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
    }

    private fun step() {
        if (!running) return
        val item = items[index % items.size]
        val start = touchPoint(item.start)
        val path = Path().apply { moveTo(start.x, start.y) }
        item.end?.let {
            val end = touchPoint(it)
            path.lineTo(end.x, end.y)
        }
        val ownDuration = item.action.durationMs
        val duration = if (ownDuration > 0) ownDuration else if (item.end != null) swipeMs else holdMs
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(1L, GestureDescription.getMaxGestureDuration()))
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = next()
            override fun onCancelled(gestureDescription: GestureDescription?) = next()
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null)) {
            Toast.makeText(this, R.string.toast_action_failed, Toast.LENGTH_SHORT).show()
            stop()
        }
    }

    private fun next() {
        if (!running) return
        val action = items[index % items.size].action
        index++
        if (index % items.size == 0) {
            cycles++
            if (repeat > 0 && cycles >= repeat) {
                stop()
                return
            }
            updateRunLabel()
        }
        val wait = if (action.delayMs >= 0) action.delayMs else intervalMs
        val jitter = if (randomIntervalMs > 0) Random.nextLong(-randomIntervalMs, randomIntervalMs + 1) else 0L
        handler.postDelayed(stepRunnable, (wait + jitter).coerceAtLeast(Settings.MIN_DELAY_MS))
    }
}
