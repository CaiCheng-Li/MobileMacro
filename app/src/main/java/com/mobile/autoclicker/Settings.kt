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

    // Version 1 stored tap targets as "x,y;x,y".
    private fun legacyTargets(): List<Action> = prefs.getString("targets", "")!!.split(";").mapNotNull {
        val p = it.split(",")
        val x = p.getOrNull(0)?.toIntOrNull()
        val y = p.getOrNull(1)?.toIntOrNull()
        if (x != null && y != null) Action(Action.TAP, x, y) else null
    }

    fun toJson(): JSONObject = JSONObject().put("interval_ms", intervalMs).put("random_interval_ms", randomIntervalMs).put("hold_ms", holdMs).put("swipe_ms", swipeMs).put("start_delay_sec", startDelaySec).put("time_limit_sec", timeLimitSec).put("repeat", repeat).put("random_px", randomPx).put("actions", JSONArray(actions.map { it.toJson() }))

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
        const val MIN_DELAY_MS = 10L
        const val MAX_GESTURE_MS = 60_000L
        const val MAX_START_DELAY_SEC = 3600
    }
}

// Named snapshots of the settings and actions.
class Macros(context: Context) {
    private val prefs = context.getSharedPreferences("macros", Context.MODE_PRIVATE)

    fun names(): List<String> = prefs.all.keys.sortedBy { it.lowercase() }

    fun get(name: String): JSONObject? = try {
        prefs.getString(name, null)?.let { JSONObject(it) }
    } catch (e: JSONException) {
        null
    }

    fun save(name: String, settings: Settings) = prefs.edit().putString(name, settings.toJson().toString()).apply()

    fun delete(name: String) = prefs.edit().remove(name).apply()
}
