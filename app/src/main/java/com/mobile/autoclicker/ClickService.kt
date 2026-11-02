package com.mobile.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
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
import androidx.annotation.RequiresApi
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.abs
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

        // Share of the remaining distance the joystick finger covers each frame.
        private const val STICK_EASE = 0.45f

        // Each mouse camera drag: how long it takes, and the shortest one sent.
        private const val LOOK_SWIPE_MS = 24L
        private const val MIN_LOOK_DP = 6

        // Joystick keys: WASD and the arrow keys.
        private val STICK_UP = setOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP)
        private val STICK_DOWN = setOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN)
        private val STICK_LEFT = setOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT)
        private val STICK_RIGHT = setOf(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT)
        private const val FRAME_MS = 16L
    }

    private class Marker(val view: MarkerView, val params: WindowManager.LayoutParams)

    // A swipe has an end marker; a tap does not.
    private class Item(val action: Action, val start: Marker, val end: Marker?)

    // A key point of the keyboard layout: pressing key taps the marker.
    private class KeyMarker(var key: Int, val marker: Marker)

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
    private var layoutButton: ImageView? = null
    private var layoutBackground: GradientDrawable? = null
    private var editor: View? = null
    private val editButtons = mutableListOf<ImageView>()
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

    // Keyboard layout: lettered key points and the joystick.
    private var keyboardLayout = false
    private var listening = false
    private val keyMarkers = mutableListOf<KeyMarker>()
    private var joystick: JoystickView? = null
    private var joystickParams: WindowManager.LayoutParams? = null
    private var joystickOn = true
    private var fingers: Fingers? = null
    private val heldKeys = mutableSetOf<Int>()
    private val joystickFinger = Any()
    private var keyCapture: ((KeyEvent) -> Boolean)? = null

    // Joystick finger offset from the circle's center, and where the held direction keys point it.
    private val stickKeys = mutableSetOf<Int>()
    private var stickX = 0f
    private var stickY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var stickReach = 1f
    private var stickTicking = false

    // Mouse camera: the LOOK point where camera drags start, and mouse movement not yet sent.
    private var lookMarker: Marker? = null
    private var mouseLookOn = false
    private var lookGain = 1f
    private var lookPendingX = 0f
    private var lookPendingY = 0f
    private var lookSwiping = false
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    private val stepRunnable = Runnable { step() }
    private val stopRunnable = Runnable { stop() }
    private val countdownRunnable = Runnable { countdownTick() }
    private val stickRunnable = Runnable { stickTick() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) fingers = Fingers(this)
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stop()
        stopListening()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightMode != nightMode) {
            nightMode = newNightMode
            // Rebuild the overlay so it picks up the light or dark colors.
            if (panel != null && !running && !listening) {
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

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private val markerSize get() = dp(56)

    // Holding the joystick finger down needs continued gestures, from Android 8.0.
    private val joystickSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

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
        keyboardLayout = settings.keyboardLayout
        joystickOn = settings.joystickOn
        mouseLookOn = settings.mouseLook && Build.VERSION.SDK_INT >= 34

        // Swipe arrows sit under everything else and never take touches.
        val lineView = SwipeLinesView(ui, color(MR.attr.colorPrimary), markerSize / 2f)
        val lineParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0)
        lineParams.flags = lineParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.addView(lineView, lineParams)
        lines = lineView

        // The joystick goes under the panel and markers.
        if (joystickSupported) addJoystick()

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
            setOnClickListener {
                when {
                    keyboardLayout -> if (listening) stopListening() else startListening()
                    running -> stop()
                    else -> start()
                }
            }
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

        // The same three buttons edit whichever layout is shown; updateControls sets their icons.
        editButtons.clear()
        editButtons.add(iconButton { if (keyboardLayout) addKeyPoint() else addTap() })
        editButtons.add(iconButton { if (keyboardLayout) toggleJoystick() else addSwipe() })
        editButtons.add(iconButton { if (keyboardLayout) removeLastKeyPoint() else removeLast() })
        editButtons.forEach { layout.addView(it) }

        val layoutShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        layoutBackground = layoutShape
        layoutButton = ImageView(ui).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageResource(R.drawable.ic_keyboard)
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(ColorStateList.valueOf(color(android.R.attr.colorControlHighlight)), layoutShape, mask)
            setOnClickListener { setKeyboardLayout(!keyboardLayout) }
        }
        layout.addView(layoutButton)
        layout.addView(iconButton(R.drawable.ic_close, R.string.panel_hide) { hidePanel() })

        wm.addView(layout, params)
        panel = layout

        loadItems()
        if (items.isEmpty() && !keyboardLayout) addTap()
        loadKeyPoints()
        // The mouse camera reads the mouse, which accessibility services get from Android 14.
        if (Build.VERSION.SDK_INT >= 34) addLookMarker()
        refreshOverlays()
        updateControls()
    }

    fun hidePanel() {
        if (!::wm.isInitialized) return
        stop()
        stopListening()
        editor?.let { wm.removeView(it) }
        editor = null
        if (panel != null) {
            saveActions()
            saveKeyPoints()
        }
        clearItems()
        clearKeyPoints()
        lookMarker?.let { wm.removeView(it.view) }
        lookMarker = null
        joystick?.let { wm.removeView(it) }
        panel?.let { wm.removeView(it) }
        lines?.let { wm.removeView(it) }
        joystick = null
        joystickParams = null
        panel = null
        lines = null
        playButton = null
        playBackground = null
        runLabel = null
        layoutButton = null
        layoutBackground = null
        editButtons.clear()
    }

    // Rebuilds the markers from saved settings, for example after a macro is loaded.
    fun reloadActions() {
        if (panel == null) return
        stop()
        if (editor != null) closeEditor()
        clearItems()
        loadItems()
        refreshOverlays()
        updateControls()
    }

    // Saves where the key points, joystick and LOOK point are now, before a profile is saved.
    fun saveLayout() {
        if (panel != null) saveKeyPoints()
    }

    // Rebuilds the keyboard layout from saved settings, after a profile is loaded.
    fun reloadLayout() {
        if (panel == null) return
        stopListening()
        if (editor != null) closeEditor()
        clearKeyPoints()
        loadKeyPoints()
        joystickOn = settings.joystickOn
        joystickParams?.let {
            placeJoystick(it)
            wm.updateViewLayout(joystick, it)
        }
        mouseLookOn = settings.mouseLook && Build.VERSION.SDK_INT >= 34
        lookMarker?.let {
            wm.removeView(it.view)
            addLookMarker()
        }
        refreshOverlays()
        updateControls()
    }

    private fun iconButton(icon: Int = 0, label: Int = 0, onClick: () -> Unit): ImageView {
        val ripple = TypedValue()
        ui.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        return ImageView(ui).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            if (icon != 0) setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(MR.attr.colorOnSurfaceVariant))
            setBackgroundResource(ripple.resourceId)
            if (label != 0) contentDescription = getString(label)
            setOnClickListener { onClick() }
        }
    }

    private fun updateControls() {
        val play = playButton ?: return
        val active = running || listening
        play.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
        play.contentDescription = getString(
            when {
                keyboardLayout -> if (listening) R.string.panel_stop_listening else R.string.panel_start_listening
                running -> R.string.panel_stop
                else -> R.string.panel_start
            }
        )
        play.imageTintList = ColorStateList.valueOf(color(if (active) MR.attr.colorOnError else MR.attr.colorOnPrimary))
        playBackground?.setColor(color(if (active) MR.attr.colorError else MR.attr.colorPrimary))
        runLabel?.visibility = if (running) View.VISIBLE else View.GONE

        val onVariant = color(MR.attr.colorOnSurfaceVariant)
        val (add, middle, remove) = editButtons
        if (keyboardLayout) {
            add.setImageResource(R.drawable.ic_key_add)
            add.contentDescription = getString(R.string.panel_add_key)
            middle.setImageResource(R.drawable.ic_joystick)
            middle.contentDescription = getString(if (joystickOn) R.string.panel_joystick_hide else R.string.panel_joystick_show)
            middle.imageTintList = ColorStateList.valueOf(if (joystickOn) color(MR.attr.colorPrimary) else onVariant)
            middle.visibility = if (joystickSupported) View.VISIBLE else View.GONE
            remove.contentDescription = getString(R.string.panel_remove_key)
        } else {
            add.setImageResource(R.drawable.ic_tap)
            add.contentDescription = getString(R.string.panel_add_tap)
            middle.setImageResource(R.drawable.ic_swipe)
            middle.contentDescription = getString(R.string.panel_add_swipe)
            middle.imageTintList = ColorStateList.valueOf(onVariant)
            middle.visibility = View.VISIBLE
            remove.contentDescription = getString(R.string.panel_remove)
        }
        remove.setImageResource(R.drawable.ic_remove)
        editButtons.forEach { setEnabled(it, !active) }

        layoutButton?.let {
            setEnabled(it, !active)
            it.contentDescription = getString(if (keyboardLayout) R.string.panel_layout_macro else R.string.panel_layout_keys)
            it.imageTintList = ColorStateList.valueOf(color(if (keyboardLayout) MR.attr.colorOnSecondaryContainer else MR.attr.colorOnSurfaceVariant))
        }
        layoutBackground?.setColor(if (keyboardLayout) color(MR.attr.colorSecondaryContainer) else Color.TRANSPARENT)
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    // Shows the overlays of the current layout. Everything but the editor card hides while it is open.
    private fun refreshOverlays() {
        val editing = editor != null
        fun visibility(show: Boolean) = if (show && !editing) View.VISIBLE else View.GONE
        panel?.visibility = visibility(true)
        val macro = visibility(!keyboardLayout)
        lines?.visibility = macro
        items.forEach {
            it.start.view.visibility = macro
            it.end?.view?.visibility = macro
        }
        val keys = visibility(keyboardLayout)
        keyMarkers.forEach { it.marker.view.visibility = keys }
        joystick?.visibility = visibility(keyboardLayout && joystickOn)
        lookMarker?.view?.visibility = visibility(keyboardLayout && mouseLookOn)
    }

    private fun setKeyboardLayout(on: Boolean) {
        if (on == keyboardLayout || running || listening) return
        if (editor != null) closeEditor()
        saveActions()
        saveKeyPoints()
        keyboardLayout = on
        settings.keyboardLayout = on
        if (on) refreshJoystickSize()
        refreshOverlays()
        updateControls()
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
        val start = addMarker(action.x, action.y, (items.size + 1).toString(), MR.attr.colorPrimary, MR.attr.colorOnPrimary)
        val end = if (action.isSwipe) addMarker(action.x2, action.y2, null, MR.attr.colorPrimary, MR.attr.colorOnPrimary) else null
        val item = Item(action, start, end)
        items.add(item)
        listOfNotNull(start, end).forEach { marker ->
            makeDraggable(marker.view, marker.view, marker.params, onMove = { updateLines() }, onDrop = { saveActions() }, onClick = { openEditor(items.indexOf(item)) })
        }
    }

    private fun addMarker(x: Int, y: Int, label: String?, accent: Int, onAccent: Int): Marker {
        val view = MarkerView(ui, label, color(accent), color(onAccent))
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

    // ---------- Editor cards ----------

    // Tapping a marker opens a floating card for that action's own delay and duration.
    // It is an overlay window rather than an activity so launchers cannot put it in a pop-up frame.
    private fun openEditor(i: Int) {
        if (running || i < 0 || editor != null) return
        saveActions()
        val action = items[i].action
        val frame = LayoutInflater.from(ui).inflate(R.layout.overlay_editor, null) as EditorFrame
        frame.findViewById<View>(R.id.editorKeyRow).visibility = View.GONE
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

        frame.findViewById<View>(R.id.editorDelete).setOnClickListener {
            settings.actions = items.map { it.action }.filterIndexed { index, _ -> index != i }
            closeEditor()
            clearItems()
            loadItems()
            refreshOverlays()
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
        showEditor(frame)
    }

    // A key point's card: press its key button, then any key, to change which key taps it.
    private fun openKeyEditor(point: KeyMarker) {
        if (listening || editor != null) return
        saveKeyPoints()
        val frame = LayoutInflater.from(ui).inflate(R.layout.overlay_editor, null) as EditorFrame
        frame.findViewById<View>(R.id.editorTiming).visibility = View.GONE
        val title = frame.findViewById<TextView>(R.id.editorTitle)
        val keyButton = frame.findViewById<MaterialButton>(R.id.editorKey)
        var key = point.key
        val showKey = {
            title.text = getString(R.string.key_point_title, keyName(key))
            keyButton.text = keyName(key)
        }
        showKey()
        // Keys are caught by the service's key filter, before any window or the keyboard app sees them.
        keyButton.setOnClickListener {
            keyButton.setText(R.string.edit_key_press)
            captureKeys { e ->
                when {
                    e.keyCode == KeyEvent.KEYCODE_BACK -> {
                        if (e.action == KeyEvent.ACTION_UP) {
                            captureKeys(null)
                            showKey()
                        }
                        true
                    }
                    KeyEvent.isModifierKey(e.keyCode) -> false
                    e.action == KeyEvent.ACTION_DOWN -> {
                        key = e.keyCode
                        showKey()
                        true
                    }
                    else -> {
                        // Stop on key up, so the release does not reach the app either.
                        if (e.keyCode == key) captureKeys(null)
                        true
                    }
                }
            }
        }
        frame.findViewById<View>(R.id.editorDelete).setOnClickListener {
            keyMarkers.remove(point)
            wm.removeView(point.marker.view)
            saveKeyPoints()
            closeEditor()
        }
        frame.findViewById<View>(R.id.editorSave).setOnClickListener {
            point.key = key
            point.marker.view.label = keyName(key)
            saveKeyPoints()
            closeEditor()
        }
        showEditor(frame)
    }

    private fun showEditor(frame: EditorFrame) {
        frame.onBack = { closeEditor() }
        frame.findViewById<View>(R.id.editorCancel).setOnClickListener { closeEditor() }
        // Focusable (for the keyboard) but not modal, so the keyboard below it still gets touches.
        // It sits near the top so the keyboard does not cover it.
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(minOf(dm.widthPixels - dp(16), dp(416)), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(32)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        wm.addView(frame, params)
        editor = frame
        // Hide the panel and markers so they do not cover the card.
        refreshOverlays()
    }

    private fun closeEditor() {
        captureKeys(null)
        editor?.let { wm.removeView(it) }
        editor = null
        refreshOverlays()
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

    // Overlays must let touches pass through while running or listening, otherwise they would catch the app's touches.
    private fun setTouchable(view: View, params: WindowManager.LayoutParams, touchable: Boolean) {
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        view.alpha = if (touchable) 1f else 0.6f
        wm.updateViewLayout(view, params)
    }

    private fun setMarkersTouchable(touchable: Boolean) {
        items.flatMap { listOfNotNull(it.start, it.end) }.forEach { setTouchable(it.view, it.params, touchable) }
        lines?.alpha = if (touchable) 1f else 0.6f
    }

    // ---------- Running ----------

    private fun start() {
        if (running || listening) return
        if (items.isEmpty()) {
            toast(R.string.toast_add_action)
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
            toast(R.string.toast_action_failed)
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

    // ---------- Keyboard layout ----------

    private fun loadKeyPoints() {
        settings.keyPoints.forEach { addKeyMarker(it.key, it.x, it.y) }
    }

    private fun clearKeyPoints() {
        keyMarkers.forEach { wm.removeView(it.marker.view) }
        keyMarkers.clear()
    }

    private fun addKeyMarker(key: Int, x: Int, y: Int) {
        val marker = addMarker(x, y, keyName(key), MR.attr.colorTertiary, MR.attr.colorOnTertiary)
        val point = KeyMarker(key, marker)
        keyMarkers.add(point)
        makeDraggable(marker.view, marker.view, marker.params, onDrop = { saveKeyPoints() }, onClick = { openKeyEditor(point) })
    }

    // New key points take the next letter not yet used: A, B, C and so on, skipping
    // W, A, S and D while the joystick uses them.
    private fun addKeyPoint() {
        if (listening) return
        val used = keyMarkers.map { it.key }.toSet() + if (joystickOn && joystick != null) STICK_UP + STICK_DOWN + STICK_LEFT + STICK_RIGHT else emptySet()
        val key = (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).firstOrNull { it !in used }
        if (key == null) {
            toast(R.string.toast_keys_full)
            return
        }
        val dm = resources.displayMetrics
        val offset = keyMarkers.size % 10 * dp(24)
        addKeyMarker(key, dm.widthPixels / 2 - markerSize / 2 + offset, dm.heightPixels / 3 - markerSize / 2 + offset)
        saveKeyPoints()
    }

    private fun removeLastKeyPoint() {
        if (listening || keyMarkers.isEmpty()) return
        wm.removeView(keyMarkers.removeAt(keyMarkers.size - 1).marker.view)
        saveKeyPoints()
    }

    private fun saveKeyPoints() {
        settings.keyPoints = keyMarkers.map { KeyPoint(it.key, it.marker.params.x, it.marker.params.y) }
        joystickParams?.let {
            settings.joystickX = it.x
            settings.joystickY = it.y
        }
        lookMarker?.let {
            settings.lookX = it.params.x
            settings.lookY = it.params.y
        }
    }

    private fun addJoystick() {
        val view = JoystickView(ui, color(MR.attr.colorTertiary))
        val params = overlayParams(0, 0, 0, 0)
        placeJoystick(params)
        wm.addView(view, params)
        makeDraggable(view, view, params, onDrop = { saveKeyPoints() })
        joystick = view
        joystickParams = params
    }

    // Size and position from the settings; it starts out centered on the right half of the screen.
    private fun placeJoystick(params: WindowManager.LayoutParams) {
        val size = dp(settings.joystickSizeDp)
        val dm = resources.displayMetrics
        params.width = size
        params.height = size
        params.x = settings.joystickX.takeIf { it != Settings.NO_POSITION } ?: (dm.widthPixels * 3 / 4 - size / 2)
        params.y = settings.joystickY.takeIf { it != Settings.NO_POSITION } ?: (dm.heightPixels / 2 - size / 2)
    }

    // Applies a new joystick size from the settings screen, keeping the circle's center in place.
    fun refreshJoystickSize() {
        val view = joystick ?: return
        val params = joystickParams ?: return
        if (listening) return
        val size = dp(settings.joystickSizeDp)
        if (size == params.width) return
        params.x += (params.width - size) / 2
        params.y += (params.height - size) / 2
        params.width = size
        params.height = size
        wm.updateViewLayout(view, params)
        saveKeyPoints()
    }

    private fun toggleJoystick() {
        if (listening || !joystickSupported) return
        joystickOn = !joystickOn
        settings.joystickOn = joystickOn
        refreshOverlays()
        updateControls()
    }

    private fun startListening() {
        if (listening || running || !keyboardLayout) return
        val useJoystick = joystickOn && joystick != null
        val useMouse = mouseLookOn && lookMarker != null
        if (keyMarkers.isEmpty() && !useJoystick && !useMouse) {
            toast(R.string.toast_add_key)
            return
        }
        if (editor != null) closeEditor()
        refreshJoystickSize()
        saveKeyPoints()
        holdMs = settings.holdMs
        randomPx = settings.randomPx
        stickReach = settings.joystickReach / 100f
        lookGain = settings.mouseSensitivity / 5f
        listening = true
        setInputFilter(keys = true, mouse = useMouse)
        keyMarkers.forEach { setTouchable(it.marker.view, it.marker.params, false) }
        lookMarker?.let { setTouchable(it.view, it.params, false) }
        joystick?.let {
            setTouchable(it, joystickParams!!, false)
            it.showThumb = true
        }
        updateControls()
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        releaseJoystick()
        stickKeys.clear()
        lookPendingX = 0f
        lookPendingY = 0f
        lastMouseX = Float.NaN
        lastMouseY = Float.NaN
        lookSwiping = false
        fingers?.releaseAll()
        heldKeys.clear()
        setInputFilter(keys = false, mouse = false)
        joystick?.let {
            it.showThumb = false
            setTouchable(it, joystickParams!!, true)
        }
        lookMarker?.let { setTouchable(it.view, it.params, true) }
        keyMarkers.forEach { setTouchable(it.marker.view, it.marker.params, true) }
        updateControls()
    }

    // Key events, and on Android 14+ mouse events, come to the service before any app while it listens.
    private fun setInputFilter(keys: Boolean, mouse: Boolean) {
        val info = serviceInfo ?: return
        info.flags = if (keys) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        if (Build.VERSION.SDK_INT >= 34) info.motionEventSources = if (mouse) InputDevice.SOURCE_MOUSE else 0
        serviceInfo = info
    }

    // While a key point's card waits for a key, every key goes to it.
    private fun captureKeys(capture: ((KeyEvent) -> Boolean)?) {
        if (capture == null && keyCapture == null) return
        keyCapture = capture
        setInputFilter(keys = capture != null, mouse = false)
    }

    // Keys with a key point, and the joystick keys, are used up; every other key goes on to the
    // app as usual. A key point wins over the joystick when both use the same key.
    override fun onKeyEvent(event: KeyEvent): Boolean {
        keyCapture?.let { return it(event) }
        if (!listening) return false
        val code = event.keyCode
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Held keys repeat; the finger is already down.
                if (code in heldKeys) return true
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
                val matches = keyMarkers.filter { it.key == code }
                when {
                    matches.isNotEmpty() -> matches.forEach { keyDown(it) }
                    joystickListening && code in STICK_UP + STICK_DOWN + STICK_LEFT + STICK_RIGHT -> {
                        if (stickKeys.isEmpty()) pressJoystick()
                        stickKeys.add(code)
                        updateStickTarget()
                    }
                    else -> return false
                }
                heldKeys.add(code)
                true
            }
            KeyEvent.ACTION_UP -> {
                if (!heldKeys.remove(code)) return false
                if (stickKeys.remove(code)) {
                    if (stickKeys.isEmpty()) releaseJoystick() else updateStickTarget()
                } else {
                    keyMarkers.filter { it.key == code }.forEach { fingers?.release(it) }
                }
                true
            }
            else -> false
        }
    }

    // The tap lasts as long as the key is held. Before Android 8.0 it is a tap of the set duration.
    private fun keyDown(point: KeyMarker) {
        val p = touchPoint(point.marker)
        val f = fingers
        if (f != null) {
            f.press(point, p.x, p.y)
        } else {
            val path = Path().apply { moveTo(p.x, p.y) }
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, holdMs)).build(), null, null)
        }
    }

    private fun joystickCenter(): PointF {
        val view = joystick ?: return PointF()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return PointF(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    // The walk finger touches down at the circle's center on the first direction key, as a
    // floating game stick expects, and lifts with the last one. While it is up the mouse can
    // turn the camera, because Android hides the mouse from the app while it holds a finger.
    private fun pressJoystick() {
        if (!listening) return
        lookPendingX = 0f
        lookPendingY = 0f
        val c = joystickCenter()
        fingers?.press(joystickFinger, c.x.coerceAtLeast(0f), c.y.coerceAtLeast(0f))
    }

    private fun releaseJoystick() {
        handler.removeCallbacks(stickRunnable)
        stickTicking = false
        stickX = 0f
        stickY = 0f
        targetX = 0f
        targetY = 0f
        fingers?.release(joystickFinger)
        joystick?.setThumb(0f, 0f)
    }

    private val joystickListening get() = listening && joystickOn && joystick != null

    // The held direction keys point the finger at the circle's edge (diagonals too).
    private fun updateStickTarget() {
        val view = joystick ?: return
        var dx = 0f
        var dy = 0f
        for (k in stickKeys) {
            when (k) {
                in STICK_UP -> dy -= 1f
                in STICK_DOWN -> dy += 1f
                in STICK_LEFT -> dx -= 1f
                in STICK_RIGHT -> dx += 1f
            }
        }
        val length = hypot(dx, dy)
        val reach = (view.width / 2f - dp(12)) * stickReach
        targetX = if (length > 0f) dx / length * reach else 0f
        targetY = if (length > 0f) dy / length * reach else 0f
        if (!stickTicking) {
            stickTicking = true
            handler.post(stickRunnable)
        }
    }

    // Once a frame the finger covers part of the way to its target, so it slides there
    // in a few frames instead of jumping.
    private fun stickTick() {
        val view = joystick
        if (!listening || view == null) {
            stickTicking = false
            return
        }
        stickX += (targetX - stickX) * STICK_EASE
        stickY += (targetY - stickY) * STICK_EASE
        val arrived = abs(targetX - stickX) < 1f && abs(targetY - stickY) < 1f
        if (arrived) {
            stickX = targetX
            stickY = targetY
        }
        val c = joystickCenter()
        fingers?.move(joystickFinger, (c.x + stickX).coerceAtLeast(0f), (c.y + stickY).coerceAtLeast(0f), now = true)
        view.setThumb(stickX, stickY)
        if (arrived) stickTicking = false else handler.postDelayed(stickRunnable, FRAME_MS)
    }

    // ---------- Mouse camera ----------

    private fun addLookMarker() {
        val dm = resources.displayMetrics
        val x = settings.lookX.takeIf { it != Settings.NO_POSITION } ?: (dm.widthPixels * 3 / 4 - markerSize / 2)
        val y = settings.lookY.takeIf { it != Settings.NO_POSITION } ?: (dm.heightPixels * 2 / 5 - markerSize / 2)
        val marker = addMarker(x, y, getString(R.string.look_label), MR.attr.colorSecondary, MR.attr.colorOnSecondary)
        makeDraggable(marker.view, marker.view, marker.params, onDrop = { saveKeyPoints() })
        lookMarker = marker
    }

    // Mouse movement while no finger is held. Android drops mouse events while a camera drag
    // runs, so the cursor's own position, which keeps moving, fills in what was missed;
    // the relative axes take over where the cursor is stuck at a screen edge.
    @RequiresApi(34)
    override fun onMotionEvent(event: MotionEvent) {
        if (!listening || lookMarker == null || !mouseLookOn) return
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_MOVE) return
        var relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        var relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        for (h in 0 until event.historySize) {
            relX += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, h)
            relY += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, h)
        }
        var dx = relX
        var dy = relY
        if (!lastMouseX.isNaN()) {
            val absX = event.x - lastMouseX
            val absY = event.y - lastMouseY
            if (abs(absX) > abs(dx)) dx = absX
            if (abs(absY) > abs(dy)) dy = absY
        }
        lastMouseX = event.x
        lastMouseY = event.y
        lookPendingX += dx
        lookPendingY += dy
        sendLook()
    }

    // One short drag from the LOOK point, as long as the mouse moved since the last one.
    private fun sendLook() {
        val marker = lookMarker ?: return
        if (!listening || lookSwiping) return
        // Mouse movement can't be read while walking or holding a key, so it is not saved up either.
        val f = fingers ?: return
        if (!f.isIdle) {
            lookPendingX = 0f
            lookPendingY = 0f
            return
        }
        val ox = lookPendingX * lookGain
        val oy = lookPendingY * lookGain
        // Very short drags could count as a tap on the game's camera area.
        if (hypot(ox, oy) < dp(MIN_LOOK_DP)) return
        lookPendingX = 0f
        lookPendingY = 0f
        val loc = IntArray(2)
        marker.view.getLocationOnScreen(loc)
        val sx = loc[0] + marker.view.width / 2f
        val sy = loc[1] + marker.view.height / 2f
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo((sx + ox).coerceAtLeast(0f), (sy + oy).coerceAtLeast(0f))
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = lookDone()
            override fun onCancelled(gestureDescription: GestureDescription?) = lookDone()
        }
        lookSwiping = true
        if (!dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, LOOK_SWIPE_MS)).build(), callback, null)) lookSwiping = false
    }

    private fun lookDone() {
        lookSwiping = false
        sendLook()
    }

    // Shows the LOOK point from the settings screen's switch.
    fun refreshMouseLook() {
        mouseLookOn = settings.mouseLook && Build.VERSION.SDK_INT >= 34
        if (listening) return
        refreshOverlays()
    }
}
