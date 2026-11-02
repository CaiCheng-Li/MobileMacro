package com.mobile.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// One step of a macro. Positions are the top-left corners of the marker windows.
class Action(val type: Int, var x: Int, var y: Int, var x2: Int = 0, var y2: Int = 0, var delayMs: Long = DEFAULT, var durationMs: Long = DEFAULT) {
    val isSwipe get() = type == SWIPE

    fun toJson(): JSONObject = JSONObject().put("type", if (isSwipe) "swipe" else "tap").put("x", x).put("y", y).put("x2", x2).put("y2", y2).put("delay", delayMs).put("duration", durationMs)

    companion object {
        const val TAP = 0
        const val SWIPE = 1

        // Per-action delay or duration not set: use the global setting.
        const val DEFAULT = -1L

        fun fromJson(o: JSONObject) = Action(if (o.optString("type") == "swipe") SWIPE else TAP, o.optInt("x"), o.optInt("y"), o.optInt("x2"), o.optInt("y2"), o.optLong("delay", DEFAULT), o.optLong("duration", DEFAULT))

        fun listFromJson(a: JSONArray): List<Action> = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

// A key point of the keyboard layout: pressing key (a KeyEvent key code) taps it.
class KeyPoint(val key: Int, val x: Int, val y: Int) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("x", x).put("y", y)

    companion object {
        fun listFromJson(a: JSONArray): List<KeyPoint> = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            KeyPoint(o.optInt("key"), o.optInt("x"), o.optInt("y"))
        }
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var intervalMs: Long
        get() = prefs.getLong("interval_ms", 500L)
        set(v) = prefs.edit().putLong("interval_ms", v.coerceAtLeast(MIN_DELAY_MS)).apply()

    // Each wait is the interval plus or minus a random amount up to this value.
    var randomIntervalMs: Long
        get() = prefs.getLong("random_interval_ms", 0L)
        set(v) = prefs.edit().putLong("random_interval_ms", v.coerceAtLeast(0L)).apply()

    var holdMs: Long
        get() = prefs.getLong("hold_ms", 10L)
        set(v) = prefs.edit().putLong("hold_ms", v.coerceIn(1L, MAX_GESTURE_MS)).apply()

    var swipeMs: Long
        get() = prefs.getLong("swipe_ms", 300L)
        set(v) = prefs.edit().putLong("swipe_ms", v.coerceIn(1L, MAX_GESTURE_MS)).apply()

    var startDelaySec: Int
        get() = prefs.getInt("start_delay_sec", 0)
        set(v) = prefs.edit().putInt("start_delay_sec", v.coerceIn(0, MAX_START_DELAY_SEC)).apply()

    var timeLimitSec: Long
        get() = prefs.getLong("time_limit_sec", 0L)
        set(v) = prefs.edit().putLong("time_limit_sec", v.coerceAtLeast(0L)).apply()

    var repeat: Int
        get() = prefs.getInt("repeat", 0)
        set(v) = prefs.edit().putInt("repeat", v.coerceAtLeast(0)).apply()

    var randomPx: Int
        get() = prefs.getInt("random_px", 0).coerceIn(0, 100)
        set(v) = prefs.edit().putInt("random_px", v.coerceIn(0, 100)).apply()

    var actions: List<Action>
        get() {
            val json = prefs.getString("actions", null) ?: return legacyTargets()
            return try {
                Action.listFromJson(JSONArray(json))
            } catch (e: JSONException) {
                emptyList()
            }
        }
        set(v) = prefs.edit().putString("actions", JSONArray(v.map { it.toJson() }).toString()).apply()

    // ---------- Keyboard layout ----------

    // The floating panel shows the keyboard layout instead of the macro.
    var keyboardLayout: Boolean
        get() = prefs.getBoolean("keyboard_layout", false)
        set(v) = prefs.edit().putBoolean("keyboard_layout", v).apply()

    var keyPoints: List<KeyPoint>
        get() = try {
            KeyPoint.listFromJson(JSONArray(prefs.getString("key_points", "[]")))
        } catch (e: JSONException) {
            emptyList()
        }
        set(v) = prefs.edit().putString("key_points", JSONArray(v.map { it.toJson() }).toString()).apply()

    var joystickOn: Boolean
        get() = prefs.getBoolean("joystick_on", true)
        set(v) = prefs.edit().putBoolean("joystick_on", v).apply()

    // Top-left corner of the joystick window; NO_POSITION until it is first placed.
    var joystickX: Int
        get() = prefs.getInt("joystick_x", NO_POSITION)
        set(v) = prefs.edit().putInt("joystick_x", v).apply()

    var joystickY: Int
        get() = prefs.getInt("joystick_y", NO_POSITION)
        set(v) = prefs.edit().putInt("joystick_y", v).apply()

    var joystickSizeDp: Int
        get() = prefs.getInt("joystick_size_dp", 240).coerceIn(MIN_JOYSTICK_DP, MAX_JOYSTICK_DP)
        set(v) = prefs.edit().putInt("joystick_size_dp", v.coerceIn(MIN_JOYSTICK_DP, MAX_JOYSTICK_DP)).apply()

    // How far the direction keys push the joystick finger, in percent of the circle's radius.
    var joystickReach: Int
        get() = prefs.getInt("joystick_reach", 100).coerceIn(MIN_REACH, 100)
        set(v) = prefs.edit().putInt("joystick_reach", v.coerceIn(MIN_REACH, 100)).apply()

    // Mouse camera (Android 14+): mouse movement drags from the LOOK point.
    var mouseLook: Boolean
        get() = prefs.getBoolean("mouse_look", true)
        set(v) = prefs.edit().putBoolean("mouse_look", v).apply()

    var mouseSensitivity: Int
        get() = prefs.getInt("mouse_sensitivity", 5).coerceIn(1, 10)
        set(v) = prefs.edit().putInt("mouse_sensitivity", v.coerceIn(1, 10)).apply()

    // Top-left corner of the LOOK point's marker window; NO_POSITION until it is first placed.
    var lookX: Int
        get() = prefs.getInt("look_x", NO_POSITION)
        set(v) = prefs.edit().putInt("look_x", v).apply()

    var lookY: Int
        get() = prefs.getInt("look_y", NO_POSITION)
        set(v) = prefs.edit().putInt("look_y", v).apply()

    // Version 1 stored tap targets as "x,y;x,y".
    private fun legacyTargets(): List<Action> = prefs.getString("targets", "")!!.split(";").mapNotNull {
        val p = it.split(",")
        val x = p.getOrNull(0)?.toIntOrNull()
        val y = p.getOrNull(1)?.toIntOrNull()
        if (x != null && y != null) Action(Action.TAP, x, y) else null
    }

    fun toJson(): JSONObject = JSONObject().put("interval_ms", intervalMs).put("random_interval_ms", randomIntervalMs).put("hold_ms", holdMs).put("swipe_ms", swipeMs).put("start_delay_sec", startDelaySec).put("time_limit_sec", timeLimitSec).put("repeat", repeat).put("random_px", randomPx).put("actions", JSONArray(actions.map { it.toJson() }))

    // The keyboard layout, saved as a profile.
    fun layoutToJson(): JSONObject = JSONObject().put("key_points", JSONArray(keyPoints.map { it.toJson() })).put("joystick_on", joystickOn).put("joystick_x", joystickX).put("joystick_y", joystickY)
        .put("joystick_size_dp", joystickSizeDp).put("joystick_reach", joystickReach)
        .put("mouse_look", mouseLook).put("mouse_sensitivity", mouseSensitivity).put("look_x", lookX).put("look_y", lookY)

    fun loadLayout(o: JSONObject) {
        o.optJSONArray("key_points")?.let { keyPoints = KeyPoint.listFromJson(it) }
        joystickOn = o.optBoolean("joystick_on", joystickOn)
        joystickX = o.optInt("joystick_x", joystickX)
        joystickY = o.optInt("joystick_y", joystickY)
        joystickSizeDp = o.optInt("joystick_size_dp", joystickSizeDp)
        joystickReach = o.optInt("joystick_reach", joystickReach)
        mouseLook = o.optBoolean("mouse_look", mouseLook)
        mouseSensitivity = o.optInt("mouse_sensitivity", mouseSensitivity)
        lookX = o.optInt("look_x", lookX)
        lookY = o.optInt("look_y", lookY)
    }

    fun load(o: JSONObject) {
        intervalMs = o.optLong("interval_ms", intervalMs)
        randomIntervalMs = o.optLong("random_interval_ms", randomIntervalMs)
        holdMs = o.optLong("hold_ms", holdMs)
        swipeMs = o.optLong("swipe_ms", swipeMs)
        startDelaySec = o.optInt("start_delay_sec", startDelaySec)
        timeLimitSec = o.optLong("time_limit_sec", timeLimitSec)
        repeat = o.optInt("repeat", repeat)
        randomPx = o.optInt("random_px", randomPx)
        o.optJSONArray("actions")?.let { actions = Action.listFromJson(it) }
    }

    companion object {
        const val NO_POSITION = Int.MIN_VALUE
        const val MIN_JOYSTICK_DP = 120
        const val MAX_JOYSTICK_DP = 480
        const val MIN_REACH = 20
        const val MIN_DELAY_MS = 10L
        const val MAX_GESTURE_MS = 60_000L
        const val MAX_START_DELAY_SEC = 3600
    }
}

// Named snapshots stored as JSON: macros (the actions and their settings) and
// profiles (the keyboard layout).
class Snapshots(context: Context, file: String) {
    private val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun names(): List<String> = prefs.all.keys.sortedBy { it.lowercase() }

    fun get(name: String): JSONObject? = try {
        prefs.getString(name, null)?.let { JSONObject(it) }
    } catch (e: JSONException) {
        null
    }

    fun put(name: String, json: JSONObject) = prefs.edit().putString(name, json.toString()).apply()

    fun delete(name: String) = prefs.edit().remove(name).apply()

    fun rename(from: String, to: String) {
        val json = prefs.getString(from, null) ?: return
        prefs.edit().remove(from).putString(to, json).apply()
    }

    companion object {
        fun macros(context: Context) = Snapshots(context, "macros")
        fun profiles(context: Context) = Snapshots(context, "profiles")
    }
}
